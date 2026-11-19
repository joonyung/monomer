package cc.monomer.execution.application

import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunOwnerSettlementPort
import cc.monomer.execution.api.ExecutionRunSettlementUseCase
import cc.monomer.execution.domain.ExecutionRun
import cc.monomer.execution.domain.RunTransition
import java.time.Instant

class ExecutionRunSettlementCoordinator(
    ownerSettlementPorts: List<ExecutionRunOwnerSettlementPort>,
    private val repository: ExecutionRunRepository,
    private val transactionRunner: ExecutionRuntimeTransactionRunner,
) : ExecutionRunSettlementUseCase {
    private val portsByOwnerType = ownerSettlementPorts.associateBy { port -> port.ownerType }

    init {
        require(portsByOwnerType.size == ownerSettlementPorts.size) {
            "execution owner settlement types must be unique"
        }
    }

    override suspend fun settle(
        runId: ExecutionRunId,
        settledAt: Instant,
    ): ExecutionRunSettlementUseCase.SettlementOutcome =
        try {
            transactionRunner.run settlement@{
                repository.requireAmbientTransaction()
                val admitted = repository.find(runId)
                    ?: return@settlement ExecutionRunSettlementUseCase.SettlementOutcome.NotFound
                val current = admitted.snapshot
                if (current.physicalOutcome == null) {
                    return@settlement ExecutionRunSettlementUseCase.SettlementOutcome.Conflict
                }
                if (current.ownerSettledAt != null || current.ownerDiscardedAt != null) {
                    return@settlement ExecutionRunSettlementUseCase.SettlementOutcome.Duplicate(current)
                }
                val port = portsByOwnerType[current.owner.type]
                    ?: return@settlement ExecutionRunSettlementUseCase.SettlementOutcome.OwnerUnsupported
                val settled = when (val transition = ExecutionRun.restore(current).settleOwner(settledAt)) {
                    is RunTransition.Applied -> transition.snapshot
                    is RunTransition.Duplicate ->
                        return@settlement ExecutionRunSettlementUseCase.SettlementOutcome.Duplicate(transition.snapshot)
                    else -> return@settlement ExecutionRunSettlementUseCase.SettlementOutcome.Conflict
                }
                when (
                    port.settleInOwnerTransaction(
                        ExecutionRunOwnerSettlementPort.SettleCommand(
                            run = current,
                            settledAt = requireNotNull(settled.ownerSettledAt),
                        ),
                    )
                ) {
                    ExecutionRunOwnerSettlementPort.SettlementOutcome.Completed,
                    ExecutionRunOwnerSettlementPort.SettlementOutcome.Duplicate,
                    -> Unit
                    ExecutionRunOwnerSettlementPort.SettlementOutcome.Conflict -> throw OwnerSettlementConflictException()
                }
                if (!repository.compareAndSet(current.version, settled)) throw OwnerSettlementConcurrentConflictException()
                ExecutionRunSettlementUseCase.SettlementOutcome.Completed(settled)
            }
        } catch (_: OwnerSettlementConcurrentConflictException) {
            ExecutionRunSettlementUseCase.SettlementOutcome.ConcurrentConflict
        } catch (_: OwnerSettlementConflictException) {
            ExecutionRunSettlementUseCase.SettlementOutcome.Conflict
        }
}

/** Thrown after an owner rejects settlement so the owner transaction rolls back every write it made. */
private class OwnerSettlementConflictException : RuntimeException()
