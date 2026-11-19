@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.explore.application

import cc.monomer.execution.api.ExecutionOwnerId
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.OwnerExecutionRunCancellationPort
import cc.monomer.explore.api.CancelExploreRunCommand
import cc.monomer.explore.api.CancelExploreRunOutcome
import cc.monomer.explore.domain.TERMINAL_STATES

/** Requests cancellation of a running physical Explore execution after ingress checks membership. */
class ExploreCancellationHandler(
    private val repository: ExploreRunRepository,
    private val transactionPort: ExploreTransactionPort,
    private val runCancellationPort: OwnerExecutionRunCancellationPort,
) {
    suspend fun cancel(command: CancelExploreRunCommand): CancelExploreRunOutcome =
        try {
            transactionPort.inTransaction {
                val run = repository.lockRunForOwnerMutation(command.runId)
                    ?: return@inTransaction CancelExploreRunOutcome.NotFound
                if (
                    run.workspaceId != command.workspaceId ||
                        run.ownerPrincipalId != command.ownerPrincipalId
                ) {
                    return@inTransaction CancelExploreRunOutcome.AccessDenied
                }
                if (run.state in TERMINAL_STATES) {
                    return@inTransaction CancelExploreRunOutcome.AlreadyTerminal
                }
                when (
                    runCancellationPort.requestCancellationInOwnerTransaction(
                        OwnerExecutionRunCancellationPort.RequestCancellationCommand(
                            runId = ExecutionRunId(run.id.value),
                            owner = ExecutionOwnerReference(
                                type = ExecutionOwnerType.EXPLORE,
                                id = ExecutionOwnerId(run.id.value),
                            ),
                            workspaceId = run.workspaceId,
                            reasonCode = command.reasonCode,
                            requestedAt = command.requestedAt,
                        ),
                    )
                ) {
                    is OwnerExecutionRunCancellationPort.CancellationOutcome.Applied,
                    is OwnerExecutionRunCancellationPort.CancellationOutcome.Duplicate,
                    -> CancelExploreRunOutcome.Accepted
                    is OwnerExecutionRunCancellationPort.CancellationOutcome.AlreadyTerminal ->
                        CancelExploreRunOutcome.AlreadyTerminal
                    OwnerExecutionRunCancellationPort.CancellationOutcome.Conflict ->
                        throw ExploreCancellationConflictException()
                }
            }
        } catch (_: ExploreCancellationConflictException) {
            CancelExploreRunOutcome.ConcurrentConflict
        } catch (_: ExploreTransactionConcurrentConflictException) {
            CancelExploreRunOutcome.ConcurrentConflict
        }
}

private class ExploreCancellationConflictException : RuntimeException()
