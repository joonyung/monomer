package cc.monomer.execution.api

import cc.monomer.identity.api.WorkspaceId
import java.time.Instant

interface ExecutionRunAdmissionPort {
    fun lockWorkspaceInOwnerTransaction(workspaceId: WorkspaceId)
    fun hasUnreleasedOccupancyInOwnerTransaction(workspaceId: WorkspaceId, runIds: Set<ExecutionRunId>): Boolean

    fun reserveInOwnerTransaction(command: ReserveCommand): ReserveOutcome

    data class ReserveCommand(
        val runId: ExecutionRunId,
        val owner: ExecutionOwnerReference,
        val workspaceId: WorkspaceId,
        val idempotencyKey: String,
        val requestFingerprint: Sha256Digest,
        val connectorId: ConnectorReference,
        val connectorType: ConnectorType,
        val connectorProperties: Map<String, ConnectorRuntimePropertyValue>,
        val requestKind: ExecutableRequestKind,
        val runtimeBindings: RunRuntimeBindings,
        val assignmentTemplate: BinaryValue,
        val dispatchIntentExpiresAt: Instant,
        val deadline: Instant,
        val reservedAt: Instant,
    ) {
        init {
            require(idempotencyKey.isNotBlank())
            require(workspaceId.value != kotlin.uuid.Uuid.NIL)
            require(assignmentTemplate.size in 1..RunDispatchLimits.MAXIMUM_PAYLOAD_BYTES)
            require(dispatchIntentExpiresAt.isAfter(reservedAt))
            require(!dispatchIntentExpiresAt.isAfter(deadline))
            require(deadline.isAfter(reservedAt))
            require(runtimeBindings.resultSink.isAllowedFor(requestKind))
            require(connectorProperties.isNotEmpty())
            require(connectorProperties.keys.none(String::isBlank))
        }
    }

    sealed interface ReserveOutcome {
        data class Reserved(val snapshot: ExecutionRunSnapshot) : ReserveOutcome
        data class Existing(val snapshot: ExecutionRunSnapshot) : ReserveOutcome
        data object IdempotencyConflict : ReserveOutcome
        data object Backpressured : ReserveOutcome
        data object InvalidDispatchTemplate : ReserveOutcome
    }
}

class ExecutionRunAdmissionRollbackException(
    val reason: Reason,
) : RuntimeException("execution run admission must roll back: $reason") {
    enum class Reason {
        RUN_CONFLICT,
        DISPATCH_INTENT_CONFLICT,
    }
}
