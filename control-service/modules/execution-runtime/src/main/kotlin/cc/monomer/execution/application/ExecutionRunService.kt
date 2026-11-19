package cc.monomer.execution.application

import cc.monomer.execution.api.ExecutionRunQueryPort
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.ExecutionRunUseCase
import cc.monomer.execution.api.OwnerExecutionRunDiscardPort
import cc.monomer.execution.api.OwnerExecutionRunCancellationPort
import cc.monomer.execution.api.RunDispatchIntent
import cc.monomer.execution.api.RunDispatchIntentPort
import cc.monomer.execution.api.StoredDispatchIntentRead
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.execution.api.Sha256Digest
import cc.monomer.execution.api.isPhysicalTerminal
import cc.monomer.execution.domain.ExecutionRun
import cc.monomer.execution.domain.RunTransition

/**
 * Run mutations this service starts use [mutationTransactionRunner], a READ COMMITTED transaction of their
 * own that locks the current row, so a concurrent change surfaces as a version conflict instead of a
 * serialization failure. Reads use [readTransactionRunner], a read-only snapshot. The *InOwnerTransaction
 * methods join the owner's transaction instead.
 */
class ExecutionRunService(
    private val repository: ExecutionRunRepository,
    private val mutationTransactionRunner: ExecutionRuntimeTransactionRunner,
    private val readTransactionRunner: ExecutionRuntimeTransactionRunner,
    private val dispatchIntentPort: RunDispatchIntentPort,
    private val assignmentCodec: RunDispatchAssignmentCodec,
) : ExecutionRunUseCase, ExecutionRunQueryPort, OwnerExecutionRunCancellationPort, OwnerExecutionRunDiscardPort {
    override suspend fun recordPhysicalOutcome(
        command: ExecutionRunUseCase.RecordPhysicalOutcomeCommand
    ): ExecutionRunUseCase.TerminalOutcome =
        mutationTransactionRunner.run<ExecutionRunUseCase.TerminalOutcome> terminalTransaction@{
            val admitted =
                repository.findForUpdate(command.runId)
                    ?: return@terminalTransaction ExecutionRunUseCase.TerminalOutcome.NotFound
            val current = admitted.snapshot
            if (
                current.state.isPhysicalTerminal() &&
                (current.state in setOf(cc.monomer.execution.api.ExecutionRunState.FAILED, cc.monomer.execution.api.ExecutionRunState.CANCELLED) || current.ownerDiscardedAt != null) &&
                current.runtimeBindings.resultSink.kind == cc.monomer.execution.api.ResultSinkKind.ARTIFACT_UPLOAD &&
                command.outcome.terminalIdempotencyKey == current.id.terminalIdempotencyKey()
            ) {
                if (!repository.invalidateArtifactCleanup(current.id, current.version)) {
                    return@terminalTransaction ExecutionRunUseCase.TerminalOutcome.ConcurrentConflict
                }
                return@terminalTransaction if (current.physicalOutcome == command.outcome) {
                    ExecutionRunUseCase.TerminalOutcome.Duplicate(current.copy(version = current.version + 1))
                } else {
                    ExecutionRunUseCase.TerminalOutcome.TerminalConflict
                }
            }
            when (
                val transition =
                    ExecutionRun.restore(current).recordPhysicalOutcome(
                        outcome = command.outcome,
                        receivedAt = command.receivedAt
                    )
            ) {
                is RunTransition.Applied -> {
                    if (!repository.compareAndSet(current.version, transition.snapshot)) {
                        return@terminalTransaction ExecutionRunUseCase.TerminalOutcome.ConcurrentConflict
                    }
                    ExecutionRunUseCase.TerminalOutcome.Accepted(transition.snapshot)
                }
                is RunTransition.Duplicate ->
                    ExecutionRunUseCase.TerminalOutcome.Duplicate(transition.snapshot)
                RunTransition.InvalidState ->
                    ExecutionRunUseCase.TerminalOutcome.InvalidState
                RunTransition.BindingMismatch ->
                    ExecutionRunUseCase.TerminalOutcome.BindingMismatch
                RunTransition.TerminalConflict ->
                    ExecutionRunUseCase.TerminalOutcome.TerminalConflict
            }
        }

    override suspend fun claimDispatch(
        command: ExecutionRunUseCase.ClaimRunDispatchCommand,
    ): ExecutionRunUseCase.RunMutationOutcome =
        mutationTransactionRunner.run {
            val current = repository.findForUpdate(command.runId)?.snapshot
                ?: return@run ExecutionRunUseCase.RunMutationOutcome.NotFound
            persistTransition(current, ExecutionRun.restore(current).claimDispatch(
                claimedAt = command.claimedAt,
                executionDeadlineAt = command.executionDeadlineAt,
            ))
        }

    override suspend fun narrowExecutionDeadline(
        command: ExecutionRunUseCase.NarrowExecutionDeadlineCommand,
    ): ExecutionRunUseCase.RunMutationOutcome = mutationTransactionRunner.run {
        val current = repository.findForUpdate(command.runId)?.snapshot
            ?: return@run ExecutionRunUseCase.RunMutationOutcome.NotFound
        persistTransition(current, ExecutionRun.restore(current).narrowExecutionDeadline(
            command.deadlineAt, command.observedAt,
        ))
    }

    override suspend fun acquireExecutionOccupancy(command: ExecutionRunUseCase.AcquireExecutionOccupancyCommand): ExecutionRunUseCase.RunMutationOutcome =
        mutationTransactionRunner.run {
            repository.lockWorkspaceRunAdmission(command.workspaceId)
            val current = repository.findForUpdate(command.runId)?.snapshot
                ?: return@run ExecutionRunUseCase.RunMutationOutcome.NotFound
            if (current.workspaceId != command.workspaceId) return@run ExecutionRunUseCase.RunMutationOutcome.BindingMismatch
            val transition = ExecutionRun.restore(current).acquireExecutionOccupancy(command.observedAt)
            if (transition is RunTransition.Applied && repository.countExecutionOccupancy(command.workspaceId) >= MAXIMUM_WORKSPACE_EXECUTION_OCCUPANCY) {
                return@run ExecutionRunUseCase.RunMutationOutcome.ExecutionOccupancyFull
            }
            persistTransition(current, transition)
        }

    override suspend fun heartbeat(command: ExecutionRunUseCase.HeartbeatCommand): ExecutionRunUseCase.RunMutationOutcome =
        mutationTransactionRunner.run {
            val current = repository.findForUpdate(command.runId)?.snapshot
                ?: return@run ExecutionRunUseCase.RunMutationOutcome.NotFound
            persistTransition(current, ExecutionRun.restore(current).heartbeat(command.observedAt))
        }

    override suspend fun releaseExecutionOccupancy(command: ExecutionRunUseCase.ReleaseExecutionOccupancyCommand): ExecutionRunUseCase.RunMutationOutcome =
        mutationTransactionRunner.run {
            val current = repository.findForUpdate(command.runId)?.snapshot
                ?: return@run ExecutionRunUseCase.RunMutationOutcome.NotFound
            persistTransition(current, ExecutionRun.restore(current).releaseExecutionOccupancy(command.acquiredAt, command.releasedAt))
        }

    override suspend fun recoverExecutionOccupancy(command: ExecutionRunUseCase.RecoverExecutionOccupancyCommand): ExecutionRunUseCase.RunMutationOutcome =
        mutationTransactionRunner.run {
            val current = repository.findForUpdate(command.runId)?.snapshot
                ?: return@run ExecutionRunUseCase.RunMutationOutcome.NotFound
            persistTransition(current, ExecutionRun.restore(current).recoverExecutionOccupancy(command.observedAt, command.orphanTimeout))
        }

    override suspend fun recordProgress(
        command: ExecutionRunUseCase.RecordRunProgressCommand,
    ): ExecutionRunUseCase.RunMutationOutcome =
        mutationTransactionRunner.run {
            val current = repository.findForUpdate(command.runId)?.snapshot
                ?: return@run ExecutionRunUseCase.RunMutationOutcome.NotFound
            persistTransition(current, ExecutionRun.restore(current).recordProgress(
                rowsProcessed = command.rowsProcessed,
                bytesProcessed = command.bytesProcessed,
                phase = command.phase,
                observedAt = command.receivedAt,
            ))
        }

    override fun requestCancellationInOwnerTransaction(
        command: OwnerExecutionRunCancellationPort.RequestCancellationCommand
    ): OwnerExecutionRunCancellationPort.CancellationOutcome {
        repository.requireAmbientTransaction()
        val admitted =
            repository.find(command.runId)
                ?: return OwnerExecutionRunCancellationPort.CancellationOutcome.Conflict
        val current = admitted.snapshot
        if (
            current.owner != command.owner ||
            current.workspaceId != command.workspaceId
        ) {
            return OwnerExecutionRunCancellationPort.CancellationOutcome.Conflict
        }
        if (current.state.isPhysicalTerminal()) {
            return OwnerExecutionRunCancellationPort.CancellationOutcome.AlreadyTerminal(
                current
            )
        }
        return when (
            val transition =
                ExecutionRun.restore(current).requestCancel(
                    reasonCode = command.reasonCode,
                    requestedAt = command.requestedAt
                )
        ) {
            is RunTransition.Applied -> {
                if (!repository.compareAndSet(current.version, transition.snapshot)) {
                    OwnerExecutionRunCancellationPort.CancellationOutcome.Conflict
                } else {
                    OwnerExecutionRunCancellationPort.CancellationOutcome.Applied(
                        transition.snapshot
                    )
                }
            }
            is RunTransition.Duplicate ->
                OwnerExecutionRunCancellationPort.CancellationOutcome.Duplicate(
                    transition.snapshot
                )
            RunTransition.InvalidState,
            RunTransition.BindingMismatch,
            RunTransition.TerminalConflict,
            -> OwnerExecutionRunCancellationPort.CancellationOutcome.Conflict
        }
    }

    override fun discardInOwnerTransaction(
        command: OwnerExecutionRunDiscardPort.DiscardCommand,
    ): OwnerExecutionRunDiscardPort.DiscardOutcome {
        repository.requireAmbientTransaction()
        val current = repository.findForUpdate(command.runId)?.snapshot
            ?: return OwnerExecutionRunDiscardPort.DiscardOutcome.NotFound
        if (current.owner != command.owner || current.workspaceId != command.workspaceId) {
            return OwnerExecutionRunDiscardPort.DiscardOutcome.Conflict
        }
        return when (val transition = ExecutionRun.restore(current).discardOwner(command.discardedAt)) {
            is RunTransition.Applied -> {
                if (repository.compareAndSet(current.version, transition.snapshot)) {
                    OwnerExecutionRunDiscardPort.DiscardOutcome.Applied(transition.snapshot)
                } else {
                    OwnerExecutionRunDiscardPort.DiscardOutcome.Conflict
                }
            }
            is RunTransition.Duplicate -> OwnerExecutionRunDiscardPort.DiscardOutcome.Duplicate(transition.snapshot)
            else -> OwnerExecutionRunDiscardPort.DiscardOutcome.Conflict
        }
    }

    override suspend fun beginArtifactCleanup(
        command: ExecutionRunUseCase.BeginArtifactCleanupCommand,
    ): Boolean = mutationTransactionRunner.run {
        repository.beginArtifactCleanup(command.runId, command.expectedVersion, command.attemptedAt)
    }

    override suspend fun completeArtifactCleanup(
        command: ExecutionRunUseCase.CompleteArtifactCleanupCommand,
    ): Boolean = mutationTransactionRunner.run {
        repository.completeArtifactCleanup(command.runId, command.expectedVersion, command.completedAt)
    }

    override suspend fun recover(
        command: ExecutionRunUseCase.RecoverRunsCommand
    ): ExecutionRunUseCase.RunRecoveryBatch =
        readTransactionRunner.run {
            require(command.orphanTimeout > java.time.Duration.ZERO)
            require(command.maximumOrphanRuns in 1..1_000)
            require(command.maximumOwnerSettlementRuns in 1..1_000)
            require(command.ownerSettlementTypes.isNotEmpty())
            val dispatchDeadline = RecoveryCandidateWindow(
                repository.findDispatchDeadlineCandidates(command.observedAt, command.maximumOrphanRuns + 1),
                command.maximumOrphanRuns,
            )
            val executionDeadline = RecoveryCandidateWindow(
                repository.findExecutionDeadlineCandidates(command.observedAt, command.maximumOrphanRuns + 1),
                command.maximumOrphanRuns,
            )
            val orphan = RecoveryCandidateWindow(
                repository.findOrphanCandidates(
                    observedAt = command.observedAt,
                    staleAfter = command.observedAt.minus(command.orphanTimeout),
                    maximumRuns = command.maximumOrphanRuns + 1,
                ),
                command.maximumOrphanRuns,
            )
            val ownerSettlement = RecoveryCandidateWindow(
                repository.findOwnerSettlementCandidates(command.ownerSettlementTypes, command.maximumOwnerSettlementRuns + 1),
                command.maximumOwnerSettlementRuns,
            )
            val artifactCleanup = RecoveryCandidateWindow(
                repository.findArtifactCleanupCandidates(command.observedAt, command.maximumOwnerSettlementRuns + 1),
                command.maximumOwnerSettlementRuns,
            )
            val occupancy = RecoveryCandidateWindow(
                repository.findOccupancyRecoveryCandidates(
                    command.observedAt, command.observedAt.minus(command.orphanTimeout), command.maximumOrphanRuns + 1,
                ),
                command.maximumOrphanRuns,
            )
            ExecutionRunUseCase.RunRecoveryBatch(
                occupancyRecoveryCandidates = occupancy.runs,
                occupancyRecoveryTruncated = occupancy.truncated,
                dispatchDeadlineCandidates = dispatchDeadline.runs,
                executionDeadlineCandidates = executionDeadline.runs,
                orphanCandidates = orphan.runs,
                ownerSettlementCandidates = ownerSettlement.runs,
                artifactCleanupCandidates = artifactCleanup.runs,
                artifactCleanupTruncated = artifactCleanup.truncated,
                dispatchDeadlineTruncated = dispatchDeadline.truncated,
                executionDeadlineTruncated = executionDeadline.truncated,
                orphanTruncated = orphan.truncated,
                ownerSettlementTruncated = ownerSettlement.truncated,
                unreadableRunIds = listOf(dispatchDeadline, executionDeadline, orphan, ownerSettlement, artifactCleanup, occupancy)
                    .flatMap(RecoveryCandidateWindow::unreadableRunIds)
                    .distinct(),
            )
        }

    override suspend fun get(
        runId: cc.monomer.execution.api.ExecutionRunId
    ): ExecutionRunSnapshot? =
        readTransactionRunner.run { repository.find(runId)?.snapshot }

    override suspend fun hasExecutionOccupancyCapacity(workspaceId: WorkspaceId): Boolean =
        readTransactionRunner.run {
            repository.countExecutionOccupancy(workspaceId) < MAXIMUM_WORKSPACE_EXECUTION_OCCUPANCY
        }

    override suspend fun readSql(
        command: ExecutionRunQueryPort.ReadSqlCommand,
    ): ExecutionRunQueryPort.ReadSqlOutcome =
        readTransactionRunner.run readTransaction@{
            val snapshot = try {
                repository.find(command.runId)?.snapshot
            } catch (_: IllegalArgumentException) {
                return@readTransaction ExecutionRunQueryPort.ReadSqlOutcome.Unavailable
            } ?: return@readTransaction ExecutionRunQueryPort.ReadSqlOutcome.NotFound
            if (!snapshot.isBoundTo(command.runId, command.workspaceId, command.owner)) {
                return@readTransaction ExecutionRunQueryPort.ReadSqlOutcome.BindingMismatch
            }
            val intent = try {
                dispatchIntentPort.find(command.runId)
            } catch (_: IllegalArgumentException) {
                return@readTransaction ExecutionRunQueryPort.ReadSqlOutcome.Unavailable
            }
            recordedSql(snapshot, intent)
        }

    override suspend fun readSqlBatch(
        command: ExecutionRunQueryPort.ReadSqlBatchCommand,
    ): ExecutionRunQueryPort.ReadSqlBatchOutcome =
        readTransactionRunner.run {
            val runIds = command.runs.map(ExecutionRunQueryPort.RunOwnerBinding::runId).toSet()
            val snapshots = repository.findAll(runIds)
                .filterIsInstance<StoredExecutionRunRead.Readable>()
                .associate { read -> read.run.snapshot.id to read.run.snapshot }
            val intents = dispatchIntentPort.findAll(runIds)
                .filterIsInstance<StoredDispatchIntentRead.Readable>()
                .associate { read -> read.intent.runId to read.intent }
            val recorded = mutableListOf<ExecutionRunQueryPort.RecordedRunSql>()
            val unavailable = mutableListOf<cc.monomer.execution.api.ExecutionRunId>()
            for (binding in command.runs) {
                val snapshot = snapshots[binding.runId]
                val outcome = when {
                    snapshot == null -> ExecutionRunQueryPort.ReadSqlOutcome.NotFound
                    !snapshot.isBoundTo(binding.runId, command.workspaceId, binding.owner) ->
                        ExecutionRunQueryPort.ReadSqlOutcome.BindingMismatch
                    // An unreadable run or intent row is missing here, so it reads as unavailable.
                    else -> try {
                        recordedSql(snapshot, intents[binding.runId])
                    } catch (_: IllegalArgumentException) {
                        ExecutionRunQueryPort.ReadSqlOutcome.Unavailable
                    }
                }
                if (outcome is ExecutionRunQueryPort.ReadSqlOutcome.Available) {
                    recorded += ExecutionRunQueryPort.RecordedRunSql(binding.runId, outcome.sql)
                } else {
                    unavailable += binding.runId
                }
            }
            ExecutionRunQueryPort.ReadSqlBatchOutcome(recorded, unavailable)
        }

    private fun ExecutionRunSnapshot.isBoundTo(
        runId: cc.monomer.execution.api.ExecutionRunId,
        workspaceId: WorkspaceId,
        owner: ExecutionOwnerReference,
    ): Boolean = id == runId && this.workspaceId == workspaceId && this.owner == owner

    private fun recordedSql(
        snapshot: ExecutionRunSnapshot,
        intent: RunDispatchIntent?,
    ): ExecutionRunQueryPort.ReadSqlOutcome {
        if (intent == null) return ExecutionRunQueryPort.ReadSqlOutcome.Unavailable
        if (
            intent.runId != snapshot.id ||
            intent.expiresAt != snapshot.dispatchDeadlineAt ||
            intent.persistedAt != snapshot.createdAt ||
            Sha256Digest.of(intent.assignmentTemplate.copyBytes()) != intent.templateDigest
        ) {
            return ExecutionRunQueryPort.ReadSqlOutcome.Unavailable
        }
        val sql = assignmentCodec.readSql(intent.assignmentTemplate, snapshot)
            ?: return ExecutionRunQueryPort.ReadSqlOutcome.Unavailable
        return ExecutionRunQueryPort.ReadSqlOutcome.Available(sql)
    }

    private fun persistTransition(
        current: ExecutionRunSnapshot,
        result: RunTransition
    ): ExecutionRunUseCase.RunMutationOutcome =
        when (result) {
            is RunTransition.Applied -> {
                if (!repository.compareAndSet(current.version, result.snapshot)) {
                    ExecutionRunUseCase.RunMutationOutcome.ConcurrentConflict
                } else {
                    ExecutionRunUseCase.RunMutationOutcome.Applied(result.snapshot)
                }
            }
            is RunTransition.Duplicate ->
                ExecutionRunUseCase.RunMutationOutcome.Duplicate(result.snapshot)
            RunTransition.InvalidState ->
                ExecutionRunUseCase.RunMutationOutcome.InvalidState
            RunTransition.BindingMismatch ->
                ExecutionRunUseCase.RunMutationOutcome.BindingMismatch
            RunTransition.TerminalConflict ->
                ExecutionRunUseCase.RunMutationOutcome.InvalidState
        }

    private companion object {
        const val MAXIMUM_WORKSPACE_EXECUTION_OCCUPANCY = 8
    }
}

/** One recovery category: at most [maximumRuns] stored rows, split into readable runs and unreadable run IDs. */
private class RecoveryCandidateWindow(
    reads: List<StoredExecutionRunRead>,
    maximumRuns: Int,
) {
    private val window = reads.take(maximumRuns)
    val runs: List<ExecutionRunSnapshot> =
        window.filterIsInstance<StoredExecutionRunRead.Readable>().map { read -> read.run.snapshot }
    val unreadableRunIds: List<cc.monomer.execution.api.ExecutionRunId> =
        window.filterIsInstance<StoredExecutionRunRead.Unreadable>().map(StoredExecutionRunRead.Unreadable::runId)
    val truncated: Boolean = reads.size > maximumRuns
}
