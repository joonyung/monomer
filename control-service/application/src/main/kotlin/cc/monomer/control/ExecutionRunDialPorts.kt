package cc.monomer.control

import cc.monomer.control.observability.addCorrelationId
import cc.monomer.execution.api.ExecutionRunSnapshot
import org.slf4j.MDC
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

internal fun logExecutionRunAccepted(snapshot: ExecutionRunSnapshot, requestId: String?) {
    val event = executionRunTimingLogger.atInfo()
        .addKeyValue("event", "execution_run_accepted")
        .addCorrelationId("run_id", snapshot.id.toString())
        .addKeyValue("workspace_id", snapshot.workspaceId.toString())
        .addKeyValue("owner_type", snapshot.owner.type.name)
        .addKeyValue("result_sink", snapshot.runtimeBindings.resultSink.kind.name)
    if (requestId != null) event.addCorrelationId("request_id", requestId)
    event.log("execution_run_accepted")
}

/** Returns false when no transaction synchronization is active, so the caller logs once it knows the outcome. */
internal fun registerExecutionRunAcceptedAfterCommit(snapshot: ExecutionRunSnapshot, requestId: String?): Boolean =
    registerAfterCommit { logExecutionRunAccepted(snapshot, requestId) }

internal fun runAfterCommitOrNow(action: () -> Unit) {
    if (!registerAfterCommit(action)) action()
}

private fun registerAfterCommit(action: () -> Unit): Boolean {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) return false
    TransactionSynchronizationManager.registerSynchronization(
        object : TransactionSynchronization {
            override fun afterCommit() {
                action()
            }
        }
    )
    return true
}

class DialingExecutionRunAdmissionPort(
    private val delegate: cc.monomer.execution.application.ExecutionRunAdmissionService,
    private val dialCoordinator: ExecutionRunDialCoordinator
) : cc.monomer.execution.api.ExecutionRunAdmissionPort by delegate {
    override fun reserveInOwnerTransaction(
        command: cc.monomer.execution.api.ExecutionRunAdmissionPort.ReserveCommand
    ): cc.monomer.execution.api.ExecutionRunAdmissionPort.ReserveOutcome {
        val outcome = delegate.reserveInOwnerTransaction(command)
        if (outcome is cc.monomer.execution.api.ExecutionRunAdmissionPort.ReserveOutcome.Reserved) {
            val requestId = MDC.get("request_id")
            runAfterCommitOrNow { logExecutionRunAccepted(outcome.snapshot, requestId) }
            dialCoordinator.dispatchAfterCommit(outcome.snapshot.id)
        }
        return outcome
    }
}

class DialCancellingOwnerExecutionRunCancellationPort(
    private val delegate: cc.monomer.execution.api.OwnerExecutionRunCancellationPort,
    private val dialCoordinator: ExecutionRunDialCoordinator
) : cc.monomer.execution.api.OwnerExecutionRunCancellationPort {
    override fun requestCancellationInOwnerTransaction(
        command: cc.monomer.execution.api.OwnerExecutionRunCancellationPort.RequestCancellationCommand
    ): cc.monomer.execution.api.OwnerExecutionRunCancellationPort.CancellationOutcome {
        val outcome = delegate.requestCancellationInOwnerTransaction(command)
        when (outcome) {
            is cc.monomer.execution.api.OwnerExecutionRunCancellationPort.CancellationOutcome.Applied,
            is cc.monomer.execution.api.OwnerExecutionRunCancellationPort.CancellationOutcome.Duplicate,
            -> runAfterCommitOrNow { dialCoordinator.cancelLiveDial(command.runId) }
            is cc.monomer.execution.api.OwnerExecutionRunCancellationPort.CancellationOutcome.AlreadyTerminal,
            cc.monomer.execution.api.OwnerExecutionRunCancellationPort.CancellationOutcome.Conflict,
            -> Unit
        }
        return outcome
    }
}
