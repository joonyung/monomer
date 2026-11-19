package cc.monomer.execution.application

import cc.monomer.execution.driver.spi.DriverOperationTiming
import cc.monomer.execution.driver.spi.DriverOperation
import cc.monomer.execution.driver.spi.DriverPhase
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.DriverPhysicalResult
import cc.monomer.execution.driver.spi.ExecutionDriverRegistry
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.RawSqlValidationException
import cc.monomer.execution.driver.spi.ResultProducingRunDriverSession
import cc.monomer.execution.driver.spi.RunIdentity
import cc.monomer.execution.driver.spi.RunResultSink
import cc.monomer.execution.protocol.v1.ControlMessage
import cc.monomer.execution.protocol.v1.ExecutionEvent
import cc.monomer.execution.protocol.v1.ExecutionPlaneGrpcKt
import cc.monomer.execution.protocol.v1.PhysicalOutcome
import cc.monomer.execution.protocol.v1.PhysicalOutcomeKind as ProtoPhysicalOutcomeKind
import cc.monomer.execution.protocol.v1.ValidateRawSqlRequest
import cc.monomer.execution.protocol.v1.ValidateRawSqlResponse
import cc.monomer.execution.protocol.v1.RunProgress
import cc.monomer.execution.runtime.BoundedResultSink
import cc.monomer.execution.runtime.CredentialDocumentCodec
import cc.monomer.execution.runtime.DirectArtifactUploader
import cc.monomer.execution.runtime.ResultFrameEmitter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import cc.monomer.execution.driver.spi.warehouseDiagnostic

class ExecutionPlaneService(
    private val driverRegistry: ExecutionDriverRegistry,
    private val maximumCredentialDocumentBytes: Int,
    private val maximumCredentialFields: Int,
    private val maximumCredentialFieldCharacters: Int,
    private val artifactUploader: DirectArtifactUploader,
) : ExecutionPlaneGrpcKt.ExecutionPlaneCoroutineImplBase() {
    private val activeRunIds = ConcurrentHashMap.newKeySet<String>()

    init {
        require(maximumCredentialDocumentBytes > 0)
        require(maximumCredentialFields > 0)
        require(maximumCredentialFieldCharacters > 0)
    }

    override fun run(requests: Flow<ControlMessage>): Flow<ExecutionEvent> =
        channelFlow {
            val controlMessages = requests.produceIn(this)
            var activeRunId: String? = null
            try {
                val first = controlMessages.receiveCatching().getOrNull()
                require(first?.messageCase == ControlMessage.MessageCase.ASSIGNMENT) {
                    "the first Control message must contain exactly one assignment"
                }
                if (!activeRunIds.add(first.assignment.runId)) {
                    operationTiming(first.assignment.connector.connectorType, DriverOperation.QUERY,
                        first.assignment.runId, first.assignment.workspaceId)
                        .log(DriverPhysicalResult.Failed("DUPLICATE_ACTIVE_RUN", "the run is already active"))
                    send(duplicateActiveRunRejected())
                    return@channelFlow
                }
                activeRunId = first.assignment.runId
                RunSession(
                    assignment = first.assignment,
                    driverRegistry = driverRegistry,
                    decodeCredential = ::decodeCredential,
                    artifactUploader = artifactUploader,
                    events = this,
                ).run()
            } finally {
                activeRunId?.let(activeRunIds::remove)
                controlMessages.cancel()
            }
        }.buffer(Channel.RENDEZVOUS)

    override suspend fun validateRawSql(
        request: ValidateRawSqlRequest,
    ): ValidateRawSqlResponse {
        var credential: MaterializedCredential? = null
        val timing = operationTiming(request.connector.connectorType, DriverOperation.VALIDATE, request.runId, request.workspaceId)
        var outcome: DriverPhysicalResult = DriverPhysicalResult.Cancelled
        val credentialDocument = request.connector.credentialDocument.toByteArray()
        return try {
            val mapped = timing.measure(DriverPhase.PREPARE) { request.toDriverValidationRequest() }
            credential = timing.measure(DriverPhase.CREDENTIAL) { decodeCredential(credentialDocument) }
            driverRegistry.validateRawSql(
                connector = mapped.connector,
                credential = credential,
                sql = mapped.sql,
                parameters = mapped.parameters,
                deadlineEpochMillis = mapped.deadlineEpochMillis,
                timing = timing,
            )
            outcome = DriverPhysicalResult.Succeeded(0uL, 0uL, null)
            ValidateRawSqlResponse.newBuilder()
                .setAccepted(cc.monomer.execution.protocol.v1.RawSqlAccepted.getDefaultInstance())
                .build()
        } catch (failure: CancellationException) {
            timing.interrupted()
            throw failure
        } catch (failure: Throwable) {
            val response = ValidateRawSqlResponse.newBuilder()
                .setFailed(
                    cc.monomer.execution.protocol.v1.RawSqlValidationFailed.newBuilder()
                        .setFailureCode(
                            (failure as? RawSqlValidationException)?.failureCode
                                ?: if (failure is IllegalArgumentException) {
                                    "INVALID_RAW_SQL_VALIDATION_REQUEST"
                                } else {
                                    "RAW_SQL_VALIDATION_FAILED"
                                },
                        )
                        .setSafeMessage(
                            (failure as? RawSqlValidationException)?.safeMessage
                                ?: if (failure is IllegalArgumentException) {
                                    "raw SQL validation request is invalid"
                                } else {
                                    "raw SQL validation failed"
                                },
                        ),
                )
                .build()
            outcome = DriverPhysicalResult.Failed(response.failed.failureCode, "raw SQL validation failed")
            response
        } finally {
            credentialDocument.fill(0)
            withContext(NonCancellable) { credential?.close() }
            timing.log(outcome)
        }
    }

    private fun decodeCredential(document: ByteArray): MaterializedCredential {
        try {
            require(document.isNotEmpty()) { "credential document is missing" }
            require(document.size <= maximumCredentialDocumentBytes) {
                "credential document exceeds the in-memory limit"
            }
            return CredentialDocumentCodec.decode(
                document = document,
                maximumFields = maximumCredentialFields,
                maximumFieldCharacters = maximumCredentialFieldCharacters,
            )
        } finally {
            document.fill(0)
        }
    }
}

private fun duplicateActiveRunRejected(): ExecutionEvent =
    ExecutionEvent.newBuilder()
        .setPhysicalOutcome(
            PhysicalOutcome.newBuilder()
                .setKind(ProtoPhysicalOutcomeKind.PHYSICAL_OUTCOME_KIND_FAILED)
                .setFailureCode("DUPLICATE_ACTIVE_RUN")
                .setSafeMessage("the run is already active")
                .build(),
        )
        .build()

private class RunSession(
    private val assignment: cc.monomer.execution.protocol.v1.ControlAssignment,
    private val driverRegistry: ExecutionDriverRegistry,
    private val decodeCredential: (ByteArray) -> MaterializedCredential,
    private val artifactUploader: DirectArtifactUploader,
    private val events: ProducerScope<ExecutionEvent>,
) {
    suspend fun run() {
        var credential: MaterializedCredential? = null
        var driverSession: ResultProducingRunDriverSession? = null
        var stage = "ASSIGNMENT_MAPPING"
        val timing = operationTiming(assignment.connector.connectorType, DriverOperation.QUERY, assignment.runId, assignment.workspaceId)
        var outcome: DriverPhysicalResult = DriverPhysicalResult.Cancelled
        val credentialDocument = assignment.connector.credentialDocument.toByteArray()
        try {
            val assignmentWithoutCredential =
                assignment.toBuilder()
                    .setConnector(assignment.connector.toBuilder().clearCredentialDocument())
                    .build()
            val mappedAssignment = timing.measure(DriverPhase.PREPARE) { assignmentWithoutCredential.toDriverAssignment() }
            val runIdentity =
                RunIdentity(
                    runId =
                        cc.monomer.execution.driver.spi.RunId.parse(
                            assignmentWithoutCredential.runId,
                        ),
                )
            stage = "CREDENTIAL_DECODE"
            credential = timing.measure(DriverPhase.CREDENTIAL) { decodeCredential(credentialDocument) }
            val request = mappedAssignment.request
            if (request.validateRawSqlBeforeRun && mappedAssignment.connector.connectorType != cc.monomer.execution.driver.spi.ConnectorType.DUCKDB) {
                stage = "RAW_SQL_VALIDATION"
                val remainingMillis = timing.measure(DriverPhase.VALIDATE) {
                    val remaining = mappedAssignment.deadlineEpochMillis - System.currentTimeMillis()
                    if (remaining <= 0) throw RawSqlValidationException(
                        "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", "raw SQL validation deadline exceeded", null,
                    )
                    remaining
                }
                try {
                    withTimeout(remainingMillis) {
                        driverRegistry.validateRawSql(
                            connector = mappedAssignment.connector,
                            credential = credential,
                            sql = request.sql,
                            parameters = request.parameters,
                            deadlineEpochMillis = mappedAssignment.deadlineEpochMillis,
                            timing = timing,
                        )
                    }
                } catch (failure: TimeoutCancellationException) {
                    timing.interrupted()
                    throw RawSqlValidationException("RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", "raw SQL validation deadline exceeded", failure)
                }
            }
            stage = "DRIVER_OPEN"
            driverSession =
                driverRegistry.openSession(
                    connector = mappedAssignment.connector,
                    credential = credential,
                    resultSink = createResultSink(mappedAssignment),
                    timing = timing,
                )
            events.send(progressEvent("EXECUTE"))
            stage = "DRIVER_ENTER"
            driverSession.enter(
                cc.monomer.execution.driver.spi.StartExecutionContext(
                    run = runIdentity,
                    assignment = mappedAssignment,
                ),
            )
            stage = "PHYSICAL_RESULT"
            val result = driverSession.awaitPhysicalResult()
            outcome = result
            emitPhysicalResult(
                result = result,
                artifactManifestAllowed = request.isArtifactUpload,
            )
        } catch (failure: CancellationException) {
            outcome = DriverPhysicalResult.Cancelled
            timing.interrupted()
            val session = driverSession
            if (session != null) {
                withContext(NonCancellable) {
                    runCatching { session.cancel() }
                }
            }
            runCatching { events.send(cancelledOutcome()) }
            throw failure
        } catch (failure: Throwable) {
            val event = failedOutcome(failure, credential, stage)
            outcome = DriverPhysicalResult.Failed(event.physicalOutcome.failureCode, "external execution failed")
            events.send(event)
        } finally {
            credentialDocument.fill(0)
            withContext(NonCancellable) {
                val session = driverSession
                if (session != null) {
                    runCatching { session.close() }
                } else {
                    credential?.close()
                }
            }
            timing.log(outcome)
        }
    }

    private fun createResultSink(
        mappedAssignment: cc.monomer.execution.driver.spi.RunAssignment,
    ): RunResultSink {
        val target = mappedAssignment.request.resultTarget
        return BoundedResultSink(
            delivery = target,
            frameEmitter =
                object : ResultFrameEmitter {
                    override suspend fun emitInlineResult(
                        batch: cc.monomer.execution.driver.spi.InlineResultBatch,
                    ) {
                        events.send(
                            ExecutionEvent.newBuilder()
                                .setInlineResultBatch(batch.toProto())
                                .build(),
                        )
                    }
                },
            artifactUploader = artifactUploader,
            deadlineEpochMillis = mappedAssignment.deadlineEpochMillis,
        )
    }

    private suspend fun emitPhysicalResult(
        result: DriverPhysicalResult,
        artifactManifestAllowed: Boolean,
    ) {
        when (result) {
            is DriverPhysicalResult.Succeeded -> {
                require(artifactManifestAllowed || result.manifest == null) {
                    "INLINE_STREAM queries cannot emit an artifact manifest"
                }
                events.send(
                    ExecutionEvent.newBuilder()
                        .setPhysicalOutcome(
                            PhysicalOutcome.newBuilder()
                                .setKind(ProtoPhysicalOutcomeKind.PHYSICAL_OUTCOME_KIND_SUCCEEDED)
                                .also { builder ->
                                    result.manifest?.let { builder.setArrowResultManifest(it.toProto()) }
                                }
                                .build(),
                        )
                        .build(),
                )
            }
            is DriverPhysicalResult.Failed ->
                events.send(
                    ExecutionEvent.newBuilder()
                        .setPhysicalOutcome(
                            PhysicalOutcome.newBuilder()
                                .setKind(ProtoPhysicalOutcomeKind.PHYSICAL_OUTCOME_KIND_FAILED)
                                .setFailureCode(result.failureCode)
                                .setSafeMessage(result.safeMessage)
                                .build(),
                        )
                        .build(),
                )
            DriverPhysicalResult.Cancelled -> events.send(cancelledOutcome())
        }
    }

    private fun failedOutcome(failure: Throwable, credential: MaterializedCredential?, stage: String): ExecutionEvent =
        ExecutionEvent.newBuilder()
            .setPhysicalOutcome(
                PhysicalOutcome.newBuilder()
                    .setKind(ProtoPhysicalOutcomeKind.PHYSICAL_OUTCOME_KIND_FAILED)
                    .setFailureCode(
                        (failure as? RawSqlValidationException)?.failureCode
                            ?: if (stage == "RAW_SQL_VALIDATION") "RAW_SQL_VALIDATION_FAILED"
                            else if (failure is IllegalArgumentException) "INVALID_ASSIGNMENT" else "RUN_FAILED",
                    )
                    .setSafeMessage(
                        if (failure is RawSqlValidationException) {
                            val engineMessage = failure.cause?.takeIf {
                                it is java.sql.SQLException || failure.failureCode == "RAW_SQL_VALIDATION_REJECTED"
                            }?.message
                            warehouseDiagnostic(engineMessage ?: failure.safeMessage, credential, failure.safeMessage)
                        } else if (stage == "RAW_SQL_VALIDATION") {
                            "raw SQL validation failed"
                        } else if (failure is IllegalArgumentException) {
                            "assignment could not be executed"
                        } else {
                            "external execution failed"
                        },
                    )
                    .build(),
            )
            .build()

    private fun cancelledOutcome(): ExecutionEvent =
        ExecutionEvent.newBuilder()
            .setPhysicalOutcome(
                PhysicalOutcome.newBuilder()
                    .setKind(ProtoPhysicalOutcomeKind.PHYSICAL_OUTCOME_KIND_CANCELLED)
                    .build(),
            )
            .build()

    private fun progressEvent(phase: String): ExecutionEvent =
        ExecutionEvent.newBuilder()
            .setProgress(RunProgress.newBuilder().setPhase(phase).build())
            .build()
}

private fun operationTiming(type: String, operation: DriverOperation, runId: String, workspaceId: String): DriverOperationTiming =
    DriverOperationTiming(
        connectorType = ConnectorType.entries.firstOrNull { it.name.equals(type, ignoreCase = true) },
        operation = operation,
        runId = runId,
        workspaceId = workspaceId,
    )
