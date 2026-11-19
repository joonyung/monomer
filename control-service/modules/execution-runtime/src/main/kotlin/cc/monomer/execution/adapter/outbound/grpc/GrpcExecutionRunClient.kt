package cc.monomer.execution.adapter.outbound.grpc

import cc.monomer.execution.api.ArrowResultManifest
import cc.monomer.execution.api.BinaryValue
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.ControlRunAssignment
import cc.monomer.execution.api.RawSqlValidationRequest
import cc.monomer.execution.api.RawSqlValidationResult
import cc.monomer.execution.api.ExecutionRunEvent
import cc.monomer.execution.api.ExecutionRunPort
import cc.monomer.execution.api.NullQueryParameterType
import cc.monomer.execution.api.PhysicalOutcome
import cc.monomer.execution.api.PhysicalOutcomeKind
import cc.monomer.execution.api.Sha256Digest
import cc.monomer.execution.api.QueryParameterValue
import cc.monomer.execution.protocol.v1.ConnectorRuntimeBinding
import cc.monomer.execution.protocol.v1.ConnectorRuntimeProperty
import cc.monomer.execution.protocol.v1.ControlAssignment
import cc.monomer.execution.protocol.v1.ControlMessage
import cc.monomer.execution.protocol.v1.ExecutionEvent
import cc.monomer.execution.protocol.v1.ExecutionPlaneGrpcKt
import cc.monomer.execution.protocol.v1.NullQueryParameter
import cc.monomer.execution.protocol.v1.PhysicalOutcomeKind as ProtoPhysicalOutcomeKind
import cc.monomer.execution.protocol.v1.QueryRequest
import cc.monomer.execution.protocol.v1.QueryParameter
import cc.monomer.execution.protocol.v1.QueryParameterType
import cc.monomer.execution.protocol.v1.ValidateRawSqlRequest as ProtoValidateRawSqlRequest
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusException
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.receiveAsFlow

class GrpcExecutionRunClient(
    private val stub: ExecutionPlaneGrpcKt.ExecutionPlaneCoroutineStub,
    private val clock: Clock,
) : ExecutionRunPort {
    override suspend fun validateRawSql(
        request: RawSqlValidationRequest,
    ): RawSqlValidationResult {
        val remainingMillis =
            Duration.between(clock.instant(), request.deadline)
                .toMillis()
                .coerceAtLeast(1)
        val response = try {
            stub.withWaitForReady().withDeadlineAfter(remainingMillis, TimeUnit.MILLISECONDS)
                .validateRawSql(request.toProto())
        } catch (failure: StatusException) {
            if (failure.status.code !in setOf(Status.Code.DEADLINE_EXCEEDED, Status.Code.UNAVAILABLE)) throw failure
            return RawSqlValidationResult.Failed(
                failureCode = if (failure.status.code == Status.Code.DEADLINE_EXCEEDED) {
                    "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED"
                } else {
                    "EXECUTION_UNAVAILABLE"
                },
                safeMessage = "raw SQL validation is temporarily unavailable",
            )
        }
        return when (response.resultCase) {
            cc.monomer.execution.protocol.v1.ValidateRawSqlResponse.ResultCase.ACCEPTED ->
                RawSqlValidationResult.Accepted
            cc.monomer.execution.protocol.v1.ValidateRawSqlResponse.ResultCase.FAILED ->
                RawSqlValidationResult.Failed(
                    failureCode = response.failed.failureCode,
                    safeMessage = response.failed.safeMessage,
                )
            cc.monomer.execution.protocol.v1.ValidateRawSqlResponse.ResultCase.RESULT_NOT_SET,
            null,
            -> RawSqlValidationResult.Failed(
                failureCode = "RAW_SQL_VALIDATION_FAILED",
                safeMessage = "raw SQL validation failed",
            )
        }
    }

    override fun run(assignment: ControlRunAssignment): Flow<ExecutionRunEvent> =
        channelFlow {
            val controlMessages = Channel<ControlMessage>(capacity = 1)
            try {
                controlMessages.send(
                    ControlMessage.newBuilder()
                        .setAssignment(assignment.toProto())
                        .build(),
                )
                val remainingMillis =
                    Duration.between(clock.instant(), assignment.deadline)
                        .toMillis()
                        .coerceAtLeast(1)
                stub.withWaitForReady().withDeadlineAfter(remainingMillis, TimeUnit.MILLISECONDS)
                    .run(controlMessages.receiveAsFlow())
                    .collect { event ->
                        val domainEvent = event.toDomainOrNull()
                        if (domainEvent != null) send(domainEvent)
                    }
            } finally {
                controlMessages.close()
            }
        }

    companion object {
        fun plaintext(
            target: URI,
            maximumInboundMessageBytes: Int,
        ): Pair<ManagedChannel, GrpcExecutionRunClient> {
            require(target.scheme == "http") { "Execution Dial target must be plaintext http" }
            require(target.host.isNotBlank())
            require(target.port in 1..65_535)
            require(maximumInboundMessageBytes in 1_024..64 * 1_024 * 1_024)
            val channel =
                ManagedChannelBuilder.forAddress(target.host, target.port)
                    .usePlaintext()
                    .maxInboundMessageSize(maximumInboundMessageBytes)
                    .build()
            return channel to
                GrpcExecutionRunClient(
                    ExecutionPlaneGrpcKt.ExecutionPlaneCoroutineStub(channel),
                    Clock.systemUTC(),
                )
        }
    }
}

private fun RawSqlValidationRequest.toProto(): ProtoValidateRawSqlRequest =
    ProtoValidateRawSqlRequest.newBuilder()
        .setRunId(runId.toString())
        .setWorkspaceId(workspaceId.toString())
        .setConnector(connectorBindingProto())
        .setSql(sql)
        .addAllParameters(parameters.map { parameter -> parameter.toProto() })
        .setDeadlineEpochMillis(deadline.toEpochMilli())
        .build()

private fun RawSqlValidationRequest.connectorBindingProto(): ConnectorRuntimeBinding =
    ConnectorRuntimeBinding.newBuilder()
        .setSchemaVersion(1)
        .setConnectorId(connectorId.toString())
        .setConnectorType(connectorType.name.lowercase())
        .addAllProperties(properties.toProtoProperties())
        .setCredentialDocument(ByteString.copyFrom(credentialDocument))
        .build()

fun ManagedChannel.shutdownQuietly(graceMillis: Long) {
    shutdown()
    if (!awaitTermination(graceMillis, TimeUnit.MILLISECONDS)) shutdownNow()
}

private fun ControlRunAssignment.toProto(): ControlAssignment {
    val request =
        QueryRequest.parseFrom(queryRequest.copyBytes()).also { parsed ->
            require(parsed.schemaVersion == 1)
            require(parsed.sql.isNotBlank())
            require(parsed.deliveryCase != QueryRequest.DeliveryCase.DELIVERY_NOT_SET)
        }
    return ControlAssignment.newBuilder()
        .setRunId(runId.toString())
        .setWorkspaceId(workspaceId.toString())
        .setConnector(
            ConnectorRuntimeBinding.newBuilder()
                .setSchemaVersion(1)
                .setConnectorId(connectorId.toString())
                .setConnectorType(connectorType.name.lowercase())
                .addAllProperties(properties.toProtoProperties())
                .setCredentialDocument(ByteString.copyFrom(credentialDocument))
                .build(),
        )
        .setRequest(request)
        .setDeadlineEpochMillis(deadline.toEpochMilli())
        .build()
}

private fun Map<String, ConnectorRuntimePropertyValue>.toProtoProperties(): List<ConnectorRuntimeProperty> =
    entries
        .sortedBy { entry -> entry.key }
        .map { (name, value) ->
            ConnectorRuntimeProperty.newBuilder()
                .setName(name)
                .also { property ->
                    when (value) {
                        is ConnectorRuntimePropertyValue.Text -> property.stringValue = value.value
                        is ConnectorRuntimePropertyValue.Integer -> property.integerValue = value.value
                    }
                }
                .build()
        }

private fun QueryParameterValue.toProto(): QueryParameter {
    val builder = QueryParameter.newBuilder().setName(name)
    when (this) {
        is QueryParameterValue.Text -> builder.stringValue = value
        is QueryParameterValue.Integer64 -> builder.int64Value = value
        is QueryParameterValue.Float64 -> builder.float64Value = value
        is QueryParameterValue.BooleanValue -> builder.booleanValue = value
        is QueryParameterValue.Decimal -> builder.decimalValue = value
        is QueryParameterValue.Date -> builder.dateValue = value.toString()
        is QueryParameterValue.Timestamp -> builder.timestampValue = value.toString()
        is QueryParameterValue.Bytes -> builder.bytesValue = ByteString.copyFrom(value.copyBytes())
        is QueryParameterValue.Null ->
            builder.nullValue = NullQueryParameter.newBuilder().setType(type.toProto()).build()
    }
    return builder.build()
}

private fun NullQueryParameterType.toProto(): QueryParameterType =
    when (this) {
        NullQueryParameterType.TEXT -> QueryParameterType.QUERY_PARAMETER_TYPE_TEXT
        NullQueryParameterType.INTEGER64 -> QueryParameterType.QUERY_PARAMETER_TYPE_INTEGER64
        NullQueryParameterType.FLOAT64 -> QueryParameterType.QUERY_PARAMETER_TYPE_FLOAT64
        NullQueryParameterType.BOOLEAN -> QueryParameterType.QUERY_PARAMETER_TYPE_BOOLEAN
        NullQueryParameterType.BYTES -> QueryParameterType.QUERY_PARAMETER_TYPE_BYTES
        NullQueryParameterType.DECIMAL -> QueryParameterType.QUERY_PARAMETER_TYPE_DECIMAL
        NullQueryParameterType.DATE -> QueryParameterType.QUERY_PARAMETER_TYPE_DATE
        NullQueryParameterType.TIMESTAMP -> QueryParameterType.QUERY_PARAMETER_TYPE_TIMESTAMP
    }

private fun ExecutionEvent.toDomainOrNull(): ExecutionRunEvent? =
    when (eventCase) {
        ExecutionEvent.EventCase.PROGRESS ->
            ExecutionRunEvent.Progress(
                rowsProcessed = progress.rowsProcessed.toULong(),
                bytesProcessed = progress.bytesProcessed.toULong(),
                phase = progress.phase,
            )
        ExecutionEvent.EventCase.INLINE_RESULT_BATCH -> inlineResultBatch.toDomain()
        ExecutionEvent.EventCase.PHYSICAL_OUTCOME ->
            physicalOutcome.toPhysicalOutcomeOrNull()?.let(ExecutionRunEvent::Terminal)
        ExecutionEvent.EventCase.EVENT_NOT_SET,
        null,
        -> null
    }

private fun cc.monomer.execution.protocol.v1.PhysicalOutcome.toPhysicalOutcomeOrNull(): PhysicalOutcome? {
    val kind =
        when (kind) {
            ProtoPhysicalOutcomeKind.PHYSICAL_OUTCOME_KIND_SUCCEEDED -> PhysicalOutcomeKind.SUCCEEDED
            ProtoPhysicalOutcomeKind.PHYSICAL_OUTCOME_KIND_FAILED -> PhysicalOutcomeKind.FAILED
            ProtoPhysicalOutcomeKind.PHYSICAL_OUTCOME_KIND_CANCELLED -> PhysicalOutcomeKind.CANCELLED
            ProtoPhysicalOutcomeKind.PHYSICAL_OUTCOME_KIND_UNSPECIFIED,
            ProtoPhysicalOutcomeKind.UNRECOGNIZED,
            -> return null
        }
    return runCatching {
        PhysicalOutcome(
            terminalIdempotencyKey = "physical-outcome",
            kind = kind,
            failureCode = failureCode.takeIf { value -> value.isNotBlank() },
            safeMessage = safeMessage.takeIf { value -> value.isNotBlank() },
            arrowResultManifest = arrowResultManifest.toDomainOrNull(),
        )
    }.getOrNull()
}

private fun cc.monomer.execution.protocol.v1.InlineResultBatch.toDomain(): ExecutionRunEvent.InlineResultBatch =
    ExecutionRunEvent.InlineResultBatch(
        sequence = sequence.toULong(),
        arrowIpc = BinaryValue.copyOf(arrowIpc.toByteArray()),
        sha256 = Sha256Digest(sha256),
    )

private fun cc.monomer.execution.protocol.v1.ArrowResultManifest.toDomainOrNull(): ArrowResultManifest? =
    runCatching {
        ArrowResultManifest(
            objectKey = objectKey,
            byteSize = byteSize.toULong(),
            objectEtag = objectEtag,
            rowCount = rowCount.toULong(),
            columnCount = columnCount,
            mediaType = mediaType,
        )
    }.getOrNull()
