@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.explore

import cc.monomer.connector.api.ConnectorExecutionBinding
import cc.monomer.connector.application.ManagedCredentialDocumentCodec
import cc.monomer.connector.application.ManagedCredentialReadOutcome
import cc.monomer.connector.application.ManagedCredentialReadPort
import cc.monomer.connector.application.ManagedCredentialReadRequest
import cc.monomer.control.ControlRuntimeProperties
import cc.monomer.execution.api.ConnectorRuntimeBindingSpec
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunPort
import cc.monomer.execution.api.RawSqlExecutionRequest
import cc.monomer.execution.api.RawSqlExecutionRequestCanonicalizer
import cc.monomer.execution.api.RawSqlRunSpecCommand
import cc.monomer.execution.api.RawSqlRunSpecFactory
import cc.monomer.execution.api.RawSqlValidationRequest
import cc.monomer.execution.api.RawSqlValidationResult
import cc.monomer.execution.api.ResultSinkBinding
import cc.monomer.execution.api.Sha256Digest
import cc.monomer.explore.api.ExploreDigest
import cc.monomer.explore.api.ExploreExecutionReservation
import cc.monomer.explore.api.ExploreRunId
import cc.monomer.explore.api.exploreResultObjectKey
import cc.monomer.identity.api.WorkspaceId
import java.time.Clock
import java.time.Instant

/** The execution steps that SQL and Semantic Explore launches share before admission. */
internal class ExploreLaunchPreflight(
    private val managedCredentialReadPort: ManagedCredentialReadPort,
    private val executionRunPort: ExecutionRunPort,
    private val rawSqlRunSpecFactory: RawSqlRunSpecFactory,
    private val clock: Clock,
    runtimeProperties: ControlRuntimeProperties,
) {
    private val limits = runtimeProperties.executionRuntime.limits
    private val credentialVault = runtimeProperties.executionRuntime.credentialVault

    fun draft(workspaceId: WorkspaceId, sql: String, maximumResultRows: ULong): ExploreLaunchDraft {
        val requestedAt = clock.instant()
        val dispatchDeadline = requestedAt.plus(limits.dispatchReservationDuration)
        val executionDeadline = dispatchDeadline.plus(limits.executionDeadlineDuration)
        val runId = ExploreRunId.generate()
        val objectKey = exploreResultObjectKey(workspaceId, runId)
        return ExploreLaunchDraft(
            workspaceId = workspaceId,
            runId = runId,
            requestedAt = requestedAt,
            dispatchDeadline = dispatchDeadline,
            executionDeadline = executionDeadline,
            objectKey = objectKey,
            request = sqlRequest(sql, objectKey, maximumResultRows),
        )
    }

    suspend fun readCredentialDocument(binding: ConnectorExecutionBinding, deadline: Instant): ByteArray? =
        when (
            val outcome = managedCredentialReadPort.readExact(
                ManagedCredentialReadRequest(
                    workspaceId = binding.workspaceId,
                    connectorId = binding.connectorId,
                    connectorType = binding.connectorType.lowercase(),
                    reference = binding.credentialReference,
                    serverDeadline = deadline,
                ),
            )
        ) {
            is ManagedCredentialReadOutcome.Found ->
                try {
                    ManagedCredentialDocumentCodec.encode(
                        credentialMaterial = outcome.credentialMaterial,
                        maximumDocumentBytes = credentialVault.maximumDocumentBytes,
                        maximumFields = credentialVault.maximumFields,
                        maximumFieldNameBytes = credentialVault.maximumFieldNameBytes,
                        maximumFieldValueBytes = credentialVault.maximumFieldValueBytes,
                    )
                } finally {
                    outcome.close()
                }
            ManagedCredentialReadOutcome.Rejected,
            ManagedCredentialReadOutcome.Unavailable,
            -> null
        }

    /** Validates the draft SQL and zeroizes [credentialDocument] whatever the result. */
    suspend fun validate(
        draft: ExploreLaunchDraft,
        runtimeBinding: ConnectorRuntimeBindingSpec,
        credentialDocument: ByteArray,
    ): ExploreSqlValidation {
        val result = try {
            executionRunPort.validateRawSql(
                RawSqlValidationRequest(
                    runId = draft.executionRunId,
                    workspaceId = draft.workspaceId,
                    connectorId = runtimeBinding.connectorId,
                    connectorType = runtimeBinding.connectorType,
                    properties = runtimeBinding.properties,
                    credentialDocument = credentialDocument,
                    sql = draft.request.sql,
                    parameters = draft.request.parameters,
                    deadline = minOf(draft.executionDeadline, clock.instant().plusSeconds(60)),
                ),
            )
        } finally {
            credentialDocument.fill(0)
        }
        return when (result) {
            RawSqlValidationResult.Accepted -> ExploreSqlValidation.Accepted
            is RawSqlValidationResult.Failed -> when (result.failureCode) {
                "RAW_SQL_VALIDATION_REJECTED", "READ_ONLY_VIOLATION", "PARTITION_FILTER_REQUIRED" ->
                    ExploreSqlValidation.Rejected(result.failureCode, result.safeMessage)
                "PERMISSION_DENIED" -> ExploreSqlValidation.Rejected(
                    result.failureCode,
                    "The Connector's warehouse account is not allowed to run this SQL.",
                )
                else -> ExploreSqlValidation.Unavailable(result.failureCode)
            }
        }
    }

    fun reservation(draft: ExploreLaunchDraft, runtimeBinding: ConnectorRuntimeBindingSpec): ExploreExecutionReservation {
        val specification = rawSqlRunSpecFactory.create(
            RawSqlRunSpecCommand(
                runId = draft.executionRunId,
                workspaceId = draft.workspaceId,
                objectKey = draft.objectKey,
                connector = runtimeBinding,
                request = draft.request,
                deadline = draft.executionDeadline,
            ),
        )
        return ExploreExecutionReservation(
            executionRequestDigest = ExploreDigest("sha256:${specification.canonicalRequestDigest.value}"),
            connectorProperties = runtimeBinding.properties,
            runtimeBindings = specification.runtimeBindings,
            assignmentTemplate = specification.assignmentTemplate,
            dispatchIntentExpiresAt = draft.dispatchDeadline,
            deadline = draft.executionDeadline,
        )
    }

    private fun sqlRequest(sql: String, objectKey: String, maximumResultRows: ULong): RawSqlExecutionRequest {
        val unbound = RawSqlExecutionRequest(
            schemaVersion = RawSqlExecutionRequest.SCHEMA_VERSION,
            canonicalRequestDigest = Sha256Digest("0".repeat(64)),
            sql = sql,
            parameters = emptyList(),
            maximumResultRows = maximumResultRows,
            maximumArtifactBytes = RawSqlExecutionRequest.MAXIMUM_ARTIFACT_BYTES,
            maximumRecordBatchRows = DEFAULT_RECORD_BATCH_ROWS,
            maximumRecordBatchBytes = limits.maximumRunFrameBytes,
            maximumCellBytes = DEFAULT_CELL_BYTES,
            maximumMultipartPartBytes = ResultSinkBinding.MAXIMUM_MULTIPART_PART_BYTES,
            validateRawSqlBeforeRun = false,
            expectedColumnNames = emptyList(),
        )
        return unbound.copy(
            canonicalRequestDigest = RawSqlExecutionRequestCanonicalizer.canonicalDigest(unbound, objectKey),
        )
    }
}

internal data class ExploreLaunchDraft(
    val workspaceId: WorkspaceId,
    val runId: ExploreRunId,
    val requestedAt: Instant,
    val dispatchDeadline: Instant,
    val executionDeadline: Instant,
    val objectKey: String,
    val request: RawSqlExecutionRequest,
) {
    val executionRunId: ExecutionRunId
        get() = ExecutionRunId(runId.value)
}

internal sealed interface ExploreSqlValidation {
    data object Accepted : ExploreSqlValidation
    data class Rejected(val code: String, val message: String) : ExploreSqlValidation
    data class Unavailable(val failureCode: String) : ExploreSqlValidation
}

/** One message and retry rule for SQL and Semantic launches that Execution could not serve. */
interface ExploreExecutionUnavailability {
    val failureCode: String

    val retryable: Boolean
        get() = failureCode != "AUTHENTICATION_FAILED"

    val message: String
        get() = when (failureCode) {
            "EXECUTION_BACKPRESSURED" -> "The workspace has reached its active run limit. Try again after an active run finishes."
            "AUTHENTICATION_FAILED" -> "Warehouse authentication failed. Update the Connector credentials before trying again."
            "CONNECTION_FAILED" -> "Warehouse connection failed. Check the Connector connection settings and warehouse availability before trying again."
            "CREDENTIAL_UNAVAILABLE" -> "Connector credentials are unavailable. Check the Connector credentials before trying again."
            "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED" -> "SQL validation timed out. Try again."
            else -> "Explore SQL execution is temporarily unavailable."
        }
}

private const val DEFAULT_RECORD_BATCH_ROWS: Int = 1_024
private const val DEFAULT_CELL_BYTES: Int = 4 * 1_024 * 1_024
