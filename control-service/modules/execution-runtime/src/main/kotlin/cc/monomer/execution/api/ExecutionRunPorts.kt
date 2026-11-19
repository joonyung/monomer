package cc.monomer.execution.api

import cc.monomer.identity.api.WorkspaceId
import java.time.Duration
import java.time.Instant

interface ExecutionRunUseCase {
    suspend fun recordPhysicalOutcome(command: RecordPhysicalOutcomeCommand): TerminalOutcome
    suspend fun claimDispatch(command: ClaimRunDispatchCommand): RunMutationOutcome
    suspend fun narrowExecutionDeadline(command: NarrowExecutionDeadlineCommand): RunMutationOutcome
    suspend fun recordProgress(command: RecordRunProgressCommand): RunMutationOutcome
    suspend fun acquireExecutionOccupancy(command: AcquireExecutionOccupancyCommand): RunMutationOutcome
    suspend fun releaseExecutionOccupancy(command: ReleaseExecutionOccupancyCommand): RunMutationOutcome
    suspend fun recoverExecutionOccupancy(command: RecoverExecutionOccupancyCommand): RunMutationOutcome
    suspend fun heartbeat(command: HeartbeatCommand): RunMutationOutcome

    data class AcquireExecutionOccupancyCommand(val runId: ExecutionRunId, val workspaceId: WorkspaceId, val observedAt: Instant)
    data class ReleaseExecutionOccupancyCommand(val runId: ExecutionRunId, val acquiredAt: Instant, val releasedAt: Instant)
    data class RecoverExecutionOccupancyCommand(val runId: ExecutionRunId, val observedAt: Instant, val orphanTimeout: Duration)
    data class HeartbeatCommand(val runId: ExecutionRunId, val observedAt: Instant)

    suspend fun recover(command: RecoverRunsCommand): RunRecoveryBatch
    suspend fun beginArtifactCleanup(command: BeginArtifactCleanupCommand): Boolean

    data class BeginArtifactCleanupCommand(
        val runId: ExecutionRunId,
        val expectedVersion: Long,
        val attemptedAt: Instant,
    )

    suspend fun completeArtifactCleanup(command: CompleteArtifactCleanupCommand): Boolean

    data class CompleteArtifactCleanupCommand(
        val runId: ExecutionRunId,
        val expectedVersion: Long,
        val completedAt: Instant,
    )

    data class RecordPhysicalOutcomeCommand(
        val runId: ExecutionRunId,
        val outcome: PhysicalOutcome,
        val receivedAt: Instant,
    )

    data class ClaimRunDispatchCommand(
        val runId: ExecutionRunId,
        val claimedAt: Instant,
        val executionDeadlineAt: Instant,
    ) {
        init {
            require(executionDeadlineAt.isAfter(claimedAt))
        }
    }

    data class NarrowExecutionDeadlineCommand(
        val runId: ExecutionRunId,
        val deadlineAt: Instant,
        val observedAt: Instant,
    )

    data class RecordRunProgressCommand(
        val runId: ExecutionRunId,
        val rowsProcessed: ULong,
        val bytesProcessed: ULong,
        val phase: String,
        val receivedAt: Instant,
    )

    data class RecoverRunsCommand(
        val observedAt: Instant,
        val orphanTimeout: Duration,
        val maximumOrphanRuns: Int,
        val maximumOwnerSettlementRuns: Int,
        val ownerSettlementTypes: Set<ExecutionOwnerType>,
    )

    sealed interface RunMutationOutcome {
        data class Applied(val snapshot: ExecutionRunSnapshot) : RunMutationOutcome
        data class Duplicate(val snapshot: ExecutionRunSnapshot) : RunMutationOutcome
        data object NotFound : RunMutationOutcome
        data object InvalidState : RunMutationOutcome
        data object BindingMismatch : RunMutationOutcome
        data object ConcurrentConflict : RunMutationOutcome

        /** The Workspace already holds its maximum execution occupancy; the run is unchanged and may retry. */
        data object ExecutionOccupancyFull : RunMutationOutcome
    }

    sealed interface TerminalOutcome {
        data class Accepted(val snapshot: ExecutionRunSnapshot) : TerminalOutcome
        data class Duplicate(val snapshot: ExecutionRunSnapshot) : TerminalOutcome
        data object NotFound : TerminalOutcome
        data object InvalidState : TerminalOutcome
        data object BindingMismatch : TerminalOutcome
        data object TerminalConflict : TerminalOutcome
        data object ConcurrentConflict : TerminalOutcome
    }

    data class RunRecoveryBatch(
        val dispatchDeadlineCandidates: List<ExecutionRunSnapshot>,
        val executionDeadlineCandidates: List<ExecutionRunSnapshot>,
        val orphanCandidates: List<ExecutionRunSnapshot>,
        val ownerSettlementCandidates: List<ExecutionRunSnapshot>,
        val occupancyRecoveryCandidates: List<ExecutionRunSnapshot>,
        val occupancyRecoveryTruncated: Boolean,
        val artifactCleanupCandidates: List<ExecutionRunSnapshot>,
        val dispatchDeadlineTruncated: Boolean,
        val executionDeadlineTruncated: Boolean,
        val orphanTruncated: Boolean,
        val ownerSettlementTruncated: Boolean,
        val artifactCleanupTruncated: Boolean,
        /** Candidate rows that violate the snapshot invariants; recovery skips them instead of failing the cycle. */
        val unreadableRunIds: List<ExecutionRunId>,
    )
}

interface ExecutionRunQueryPort {
    suspend fun get(runId: ExecutionRunId): ExecutionRunSnapshot?

    suspend fun hasExecutionOccupancyCapacity(workspaceId: WorkspaceId): Boolean

    suspend fun readSql(command: ReadSqlCommand): ReadSqlOutcome

    /** Reads the recorded SQL of up to [MAX_SQL_BATCH_RUNS] runs of one workspace with a fixed number of queries. */
    suspend fun readSqlBatch(command: ReadSqlBatchCommand): ReadSqlBatchOutcome

    data class ReadSqlCommand(
        val runId: ExecutionRunId,
        val workspaceId: WorkspaceId,
        val owner: ExecutionOwnerReference,
    )

    data class ReadSqlBatchCommand(
        val workspaceId: WorkspaceId,
        val runs: List<RunOwnerBinding>,
    ) {
        init {
            require(runs.size <= MAX_SQL_BATCH_RUNS)
            require(runs.map(RunOwnerBinding::runId).toSet().size == runs.size)
        }
    }

    data class RunOwnerBinding(
        val runId: ExecutionRunId,
        val owner: ExecutionOwnerReference,
    )

    /** Every requested run appears exactly once, either with its SQL or as unavailable. */
    data class ReadSqlBatchOutcome(
        val recorded: List<RecordedRunSql>,
        val unavailableRunIds: List<ExecutionRunId>,
    )

    data class RecordedRunSql(val runId: ExecutionRunId, val sql: String) {
        override fun toString(): String = "RecordedRunSql(runId=$runId, sql=[REDACTED])"
    }

    sealed interface ReadSqlOutcome {
        data class Available(val sql: String) : ReadSqlOutcome {
            override fun toString(): String = "Available(sql=[REDACTED])"
        }
        data object NotFound : ReadSqlOutcome
        data object BindingMismatch : ReadSqlOutcome
        data object Unavailable : ReadSqlOutcome
    }
}

const val MAX_SQL_BATCH_RUNS: Int = 100

interface OwnerExecutionRunCancellationPort {
    fun requestCancellationInOwnerTransaction(command: RequestCancellationCommand): CancellationOutcome

    data class RequestCancellationCommand(
        val runId: ExecutionRunId,
        val owner: ExecutionOwnerReference,
        val workspaceId: WorkspaceId,
        val reasonCode: String,
        val requestedAt: Instant,
    ) {
        init {
            require(reasonCode.isNotBlank())
        }
    }

    sealed interface CancellationOutcome {
        data class Applied(val snapshot: ExecutionRunSnapshot) : CancellationOutcome
        data class Duplicate(val snapshot: ExecutionRunSnapshot) : CancellationOutcome
        data class AlreadyTerminal(val snapshot: ExecutionRunSnapshot) : CancellationOutcome
        data object Conflict : CancellationOutcome
    }
}

interface OwnerExecutionRunDiscardPort {
    fun discardInOwnerTransaction(command: DiscardCommand): DiscardOutcome

    data class DiscardCommand(
        val runId: ExecutionRunId,
        val owner: ExecutionOwnerReference,
        val workspaceId: WorkspaceId,
        val discardedAt: Instant,
    )

    sealed interface DiscardOutcome {
        data class Applied(val snapshot: ExecutionRunSnapshot) : DiscardOutcome
        data class Duplicate(val snapshot: ExecutionRunSnapshot) : DiscardOutcome
        data object NotFound : DiscardOutcome
        data object Conflict : DiscardOutcome
    }
}

interface ExecutionRunOwnerSettlementPort {
    val ownerType: ExecutionOwnerType

    fun settleInOwnerTransaction(command: SettleCommand): SettlementOutcome

    data class SettleCommand(
        val run: ExecutionRunSnapshot,
        val settledAt: Instant,
    ) {
        init {
            require(run.physicalOutcome != null)
            require(run.ownerSettledAt == null)
        }
    }

    sealed interface SettlementOutcome {
        data object Completed : SettlementOutcome
        data object Duplicate : SettlementOutcome
        data object Conflict : SettlementOutcome
    }
}

interface ExecutionRunSettlementUseCase {
    suspend fun settle(runId: ExecutionRunId, settledAt: Instant): SettlementOutcome

    sealed interface SettlementOutcome {
        data class Completed(val snapshot: ExecutionRunSnapshot) : SettlementOutcome
        data class Duplicate(val snapshot: ExecutionRunSnapshot) : SettlementOutcome
        data object NotFound : SettlementOutcome
        data object ConcurrentConflict : SettlementOutcome
        data object OwnerUnsupported : SettlementOutcome
        data object Conflict : SettlementOutcome
    }
}
