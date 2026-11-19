package cc.monomer.execution.api

import cc.monomer.identity.api.WorkspaceId
import java.time.Instant
import kotlinx.coroutines.flow.Flow

data class ControlRunAssignment(
    val runId: ExecutionRunId,
    val workspaceId: WorkspaceId,
    val connectorId: ConnectorReference,
    val connectorType: ConnectorType,
    val properties: Map<String, ConnectorRuntimePropertyValue>,
    val credentialDocument: ByteArray,
    val queryRequest: BinaryValue,
    val deadline: Instant,
) {
    init {
        require(properties.isNotEmpty())
        require(properties.keys.none(String::isBlank))
        require(credentialDocument.isNotEmpty())
        require(credentialDocument.size <= MAXIMUM_MCR1_DOCUMENT_BYTES)
        require(queryRequest.size > 0)
        require(deadline.toEpochMilli() > 0)
    }

    override fun toString(): String =
        "ControlRunAssignment(" +
            "runId=$runId, workspaceId=$workspaceId, " +
            "connectorId=$connectorId, connectorType=$connectorType, " +
            "properties=${properties.keys}, credentialDocument=[REDACTED], " +
            "queryRequest=BinaryValue(size=${queryRequest.size}), deadline=$deadline)"
}

data class RawSqlValidationRequest(
    val runId: ExecutionRunId,
    val workspaceId: WorkspaceId,
    val connectorId: ConnectorReference,
    val connectorType: ConnectorType,
    val properties: Map<String, ConnectorRuntimePropertyValue>,
    val credentialDocument: ByteArray,
    val sql: String,
    val parameters: List<QueryParameterValue>,
    val deadline: Instant,
) {
    init {
        require(properties.isNotEmpty())
        require(properties.keys.none(String::isBlank))
        require(credentialDocument.isNotEmpty())
        require(credentialDocument.size <= MAXIMUM_MCR1_DOCUMENT_BYTES)
        require(sql.isNotBlank())
        require(deadline.toEpochMilli() > 0)
    }

    override fun toString(): String =
        "RawSqlValidationRequest(" +
            "runId=$runId, workspaceId=$workspaceId, " +
            "connectorId=$connectorId, connectorType=$connectorType, " +
            "properties=${properties.keys}, credentialDocument=[REDACTED], " +
            "sql=[REDACTED], parameters=${parameters.size}, deadline=$deadline)"
}

sealed interface RawSqlValidationResult {
    data object Accepted : RawSqlValidationResult

    data class Failed(
        val failureCode: String,
        val safeMessage: String,
    ) : RawSqlValidationResult {
        init {
            require(failureCode.isNotBlank())
            require(safeMessage.isNotBlank())
        }
    }
}

sealed interface ExecutionRunEvent {
    data class Progress(
        val rowsProcessed: ULong,
        val bytesProcessed: ULong,
        val phase: String,
    ) : ExecutionRunEvent

    data class InlineResultBatch(
        val sequence: ULong,
        val arrowIpc: BinaryValue,
        val sha256: Sha256Digest,
    ) : ExecutionRunEvent {
        init {
            require(sequence > 0uL)
            require(arrowIpc.size > 0)
            require(sha256 == digest(arrowIpc))
        }

        companion object {
            fun fromPayload(sequence: ULong, arrowIpc: BinaryValue): InlineResultBatch =
                InlineResultBatch(sequence, arrowIpc, digest(arrowIpc))

            private fun digest(payload: BinaryValue): Sha256Digest = Sha256Digest.of(payload.copyBytes())
        }
    }

    data class Terminal(
        val outcome: PhysicalOutcome,
    ) : ExecutionRunEvent
}

interface ExecutionRunPort {
    suspend fun validateRawSql(
        request: RawSqlValidationRequest,
    ): RawSqlValidationResult

    fun run(assignment: ControlRunAssignment): Flow<ExecutionRunEvent>
}

const val MAXIMUM_MCR1_DOCUMENT_BYTES = 60 * 1_024
