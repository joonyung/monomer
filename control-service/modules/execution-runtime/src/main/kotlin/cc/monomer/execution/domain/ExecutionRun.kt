package cc.monomer.execution.domain

import cc.monomer.execution.api.RunCancelIntent
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.ExecutionRunState
import cc.monomer.execution.api.PhysicalOutcome
import cc.monomer.execution.api.PhysicalOutcomeKind
import cc.monomer.execution.api.ExecutionRunProgress
import cc.monomer.execution.api.isPhysicalTerminal
import java.time.Instant

internal sealed interface RunTransition {
    data class Applied(val snapshot: ExecutionRunSnapshot) : RunTransition
    data class Duplicate(val snapshot: ExecutionRunSnapshot) : RunTransition
    data object InvalidState : RunTransition
    data object BindingMismatch : RunTransition
    data object TerminalConflict : RunTransition
}

internal class ExecutionRun private constructor(
    val snapshot: ExecutionRunSnapshot
) {
    fun claimDispatch(
        claimedAt: Instant,
        executionDeadlineAt: Instant,
    ): RunTransition {
        if (snapshot.state != ExecutionRunState.RUNNING || snapshot.dispatchClaimedAt != null) {
            return RunTransition.InvalidState
        }
        if (
            claimedAt.isBefore(snapshot.createdAt) ||
            claimedAt.isAfter(snapshot.dispatchDeadlineAt) ||
            !executionDeadlineAt.isAfter(claimedAt) ||
            executionDeadlineAt.isAfter(snapshot.deadline)
        ) {
            return RunTransition.InvalidState
        }
        return RunTransition.Applied(
            snapshot.copy(
                dispatchClaimedAt = claimedAt,
                deadline = executionDeadlineAt,
                lastHeartbeatAt = claimedAt,
                updatedAt = claimedAt,
                version = snapshot.version + 1,
            ),
        )
    }

    fun narrowExecutionDeadline(deadlineAt: Instant, observedAt: Instant): RunTransition {
        if (snapshot.state != ExecutionRunState.RUNNING || snapshot.dispatchClaimedAt == null ||
            observedAt.isBefore(snapshot.dispatchClaimedAt) || !observedAt.isBefore(snapshot.dispatchDeadlineAt) ||
            !deadlineAt.isAfter(observedAt) || deadlineAt.isBefore(snapshot.dispatchDeadlineAt) || deadlineAt.isAfter(snapshot.deadline)
        ) {
            return RunTransition.InvalidState
        }
        if (deadlineAt == snapshot.deadline) return RunTransition.Duplicate(snapshot)
        return RunTransition.Applied(snapshot.copy(
            deadline = deadlineAt,
            updatedAt = maxOf(snapshot.updatedAt, observedAt),
            version = snapshot.version + 1,
        ))
    }

    fun acquireExecutionOccupancy(observedAt: Instant): RunTransition {
        if (snapshot.executionOccupancyAcquiredAt != null || snapshot.state != ExecutionRunState.RUNNING ||
            snapshot.dispatchClaimedAt == null || !observedAt.isBefore(snapshot.dispatchDeadlineAt) ||
            !observedAt.isBefore(snapshot.deadline) || observedAt.isBefore(snapshot.dispatchClaimedAt)
        ) return RunTransition.Duplicate(snapshot)
        return RunTransition.Applied(snapshot.copy(
            executionOccupancyAcquiredAt = observedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
            lastHeartbeatAt = maxOf(snapshot.lastHeartbeatAt ?: observedAt, observedAt),
            version = snapshot.version + 1,
        ))
    }

    fun releaseExecutionOccupancy(acquiredAt: Instant, releasedAt: Instant): RunTransition {
        if (snapshot.executionOccupancyAcquiredAt != acquiredAt || snapshot.executionOccupancyReleasedAt != null ||
            !snapshot.state.isPhysicalTerminal()
        ) return RunTransition.Duplicate(snapshot)
        if (releasedAt.isBefore(acquiredAt)) return RunTransition.InvalidState
        return RunTransition.Applied(snapshot.copy(executionOccupancyReleasedAt = releasedAt, version = snapshot.version + 1))
    }

    fun heartbeat(observedAt: Instant): RunTransition {
        if (snapshot.dispatchClaimedAt == null || snapshot.executionOccupancyReleasedAt != null ||
            (snapshot.state.isPhysicalTerminal() && snapshot.executionOccupancyAcquiredAt == null) ||
            !observedAt.isBefore(snapshot.deadline) ||
            !observedAt.isAfter(snapshot.lastHeartbeatAt ?: snapshot.dispatchClaimedAt)
        ) return RunTransition.Duplicate(snapshot)
        return RunTransition.Applied(snapshot.copy(lastHeartbeatAt = observedAt, version = snapshot.version + 1))
    }

    fun recoverExecutionOccupancy(observedAt: Instant, orphanTimeout: java.time.Duration): RunTransition {
        require(orphanTimeout > java.time.Duration.ZERO)
        if (snapshot.executionOccupancyReleasedAt != null) return RunTransition.Duplicate(snapshot)
        val dispatchExpired = snapshot.executionOccupancyAcquiredAt == null && !snapshot.dispatchDeadlineAt.isAfter(observedAt)
        val deadlineExpired = snapshot.dispatchClaimedAt != null && !snapshot.deadline.isAfter(observedAt)
        val stale = snapshot.dispatchClaimedAt != null &&
            !(snapshot.lastHeartbeatAt ?: snapshot.dispatchClaimedAt).isAfter(observedAt.minus(orphanTimeout))
        if (!dispatchExpired && !deadlineExpired && !stale) return RunTransition.Duplicate(snapshot)
        var recovered = snapshot
        if (!recovered.state.isPhysicalTerminal()) {
            val code = when {
                dispatchExpired -> "EXECUTION_UNAVAILABLE"
                deadlineExpired -> "EXECUTION_DEADLINE_EXCEEDED"
                else -> "EXECUTION_ORPHANED"
            }
            recovered = (recordPhysicalOutcome(PhysicalOutcome(
                terminalIdempotencyKey(), PhysicalOutcomeKind.FAILED, code, "Execution could not complete.",
            ), maxOf(observedAt, snapshot.updatedAt)) as RunTransition.Applied).snapshot
        }
        if (recovered.executionOccupancyAcquiredAt != null) {
            recovered = recovered.copy(executionOccupancyReleasedAt = maxOf(observedAt, recovered.executionOccupancyAcquiredAt))
        }
        if (recovered == snapshot) return RunTransition.Duplicate(snapshot)
        return RunTransition.Applied(recovered.copy(version = snapshot.version + 1))
    }

    fun recordProgress(
        rowsProcessed: ULong,
        bytesProcessed: ULong,
        phase: String,
        observedAt: Instant,
    ): RunTransition {
        if (snapshot.state != ExecutionRunState.RUNNING || snapshot.executionOccupancyReleasedAt != null) {
            return RunTransition.Duplicate(snapshot)
        }
        if (snapshot.dispatchClaimedAt == null) return RunTransition.InvalidState
        if (observedAt.isBefore(snapshot.updatedAt) || observedAt.isAfter(snapshot.deadline)) {
            return RunTransition.InvalidState
        }
        val progress = ExecutionRunProgress(rowsProcessed, bytesProcessed, phase, observedAt)
        if (snapshot.progress == progress && snapshot.lastHeartbeatAt == observedAt) {
            return RunTransition.Duplicate(snapshot)
        }
        return RunTransition.Applied(
            snapshot.copy(
                progress = progress,
                lastHeartbeatAt = maxOf(snapshot.lastHeartbeatAt ?: observedAt, observedAt),
                updatedAt = observedAt,
                version = snapshot.version + 1,
            ),
        )
    }

    fun recordPhysicalOutcome(
        outcome: PhysicalOutcome,
        receivedAt: Instant
    ): RunTransition {
        val existing = snapshot.physicalOutcome
        if (existing != null) {
            return if (existing == outcome) {
                RunTransition.Duplicate(snapshot)
            } else {
                RunTransition.TerminalConflict
            }
        }
        if (outcome.terminalIdempotencyKey != terminalIdempotencyKey()) {
            return RunTransition.BindingMismatch
        }
        if (receivedAt.isBefore(snapshot.updatedAt)) {
            return RunTransition.InvalidState
        }
        if (snapshot.state != ExecutionRunState.RUNNING) {
            return RunTransition.InvalidState
        }
        if (
            outcome.kind == PhysicalOutcomeKind.SUCCEEDED &&
            snapshot.runtimeBindings.resultSink.kind ==
                cc.monomer.execution.api.ResultSinkKind.ARTIFACT_UPLOAD &&
            outcome.arrowResultManifest == null &&
            snapshot.resultManifest == null
        ) {
            return RunTransition.BindingMismatch
        }
        val terminalState =
            when (outcome.kind) {
                PhysicalOutcomeKind.SUCCEEDED ->
                    ExecutionRunState.SUCCEEDED
                PhysicalOutcomeKind.FAILED ->
                    ExecutionRunState.FAILED
                PhysicalOutcomeKind.CANCELLED ->
                    ExecutionRunState.CANCELLED
            }
        return RunTransition.Applied(
            snapshot.copy(
                state = terminalState,
                physicalOutcome = outcome,
                resultManifest = outcome.arrowResultManifest ?: snapshot.resultManifest,
                updatedAt = receivedAt,
                version = snapshot.version + 1
            )
        )
    }

    fun requestCancel(
        reasonCode: String,
        requestedAt: Instant
    ): RunTransition {
        val intent = RunCancelIntent(reasonCode, requestedAt)
        if (snapshot.cancelIntent != null) {
            return if (snapshot.cancelIntent.reasonCode == reasonCode) {
                RunTransition.Duplicate(snapshot)
            } else {
                RunTransition.BindingMismatch
            }
        }
        if (snapshot.state.isPhysicalTerminal()) {
            return RunTransition.Duplicate(snapshot)
        }
        if (snapshot.state != ExecutionRunState.RUNNING) {
            return RunTransition.InvalidState
        }
        if (requestedAt.isBefore(snapshot.updatedAt)) {
            return RunTransition.InvalidState
        }
        val outcome =
            PhysicalOutcome(
                terminalIdempotencyKey = terminalIdempotencyKey(),
                kind = PhysicalOutcomeKind.CANCELLED,
                failureCode = null,
                safeMessage = reasonCode,
            )
        return RunTransition.Applied(
            snapshot.copy(
                state = ExecutionRunState.CANCELLED,
                physicalOutcome = outcome,
                cancelIntent = intent,
                updatedAt = requestedAt,
                version = snapshot.version + 1
            )
        )
    }

    fun discardOwner(discardedAt: Instant): RunTransition {
        if (snapshot.physicalOutcome == null) return RunTransition.InvalidState
        if (snapshot.ownerDiscardedAt != null) return RunTransition.Duplicate(snapshot)
        val recordedAt = maxOf(discardedAt, snapshot.updatedAt)
        return RunTransition.Applied(
            snapshot.copy(
                ownerDiscardedAt = recordedAt,
                ownerSettledAt = recordedAt,
                updatedAt = recordedAt,
                version = snapshot.version + 1,
            ),
        )
    }

    fun settleOwner(settledAt: Instant): RunTransition {
        if (snapshot.physicalOutcome == null) return RunTransition.InvalidState
        val existing = snapshot.ownerSettledAt
        if (existing != null) return RunTransition.Duplicate(snapshot)
        val recordedAt = maxOf(settledAt, snapshot.updatedAt)
        return RunTransition.Applied(
            snapshot.copy(
                ownerSettledAt = recordedAt,
                updatedAt = recordedAt,
                version = snapshot.version + 1,
            ),
        )
    }

    private fun terminalIdempotencyKey(): String = snapshot.id.terminalIdempotencyKey()

    companion object {
        fun create(snapshot: ExecutionRunSnapshot): ExecutionRun {
            require(snapshot.state == ExecutionRunState.RUNNING)
            require(snapshot.physicalOutcome == null)
            return ExecutionRun(snapshot)
        }

        fun restore(snapshot: ExecutionRunSnapshot): ExecutionRun =
            ExecutionRun(snapshot)
    }
}
