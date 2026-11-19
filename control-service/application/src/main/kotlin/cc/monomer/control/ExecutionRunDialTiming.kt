package cc.monomer.control

import cc.monomer.control.observability.addCorrelationId
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.PhysicalOutcomeKind
import cc.monomer.execution.api.ResultSinkKind
import java.time.Duration
import java.time.Instant

/**
 * Per-dial phase timestamps for one `execution_run_timing` line. Only identifiers, owner and
 * sink kinds, outcome codes, and durations are logged; SQL text, credentials, and result data
 * never enter this record.
 */
internal class ExecutionRunDialTiming(
    val runId: ExecutionRunId,
    val ownerType: ExecutionOwnerType,
    val resultSinkKind: ResultSinkKind,
    val admittedAt: Instant,
    val dispatchClaimedAt: Instant,
) {
    var occupancyAcquiredAt: Instant? = null
    var rpcStartedAt: Instant? = null
    var rpcTerminalAt: Instant? = null
    var terminalRecordedAt: Instant? = null
    var outcomeKind: PhysicalOutcomeKind? = null
    var failureCode: String? = null
    var terminalRecord: String? = null
    var ownerSettlementPending: Boolean = false

    @Volatile
    var terminalRecordStarted: Boolean = false
    var settlement: String? = "NOT_ATTEMPTED"
    var settlementRequestedAt: Instant? = null
    var settlementFinishedAt: Instant? = null
    var ownerSettledAt: Instant? = null

    fun log() {
        executionRunTimingLogger.atInfo()
            .addKeyValue("event", "execution_run_timing")
            .addCorrelationId("run_id", runId.toString())
            .addKeyValue("owner_type", ownerType.name)
            .addKeyValue("result_sink", resultSinkKind.name)
            .addKeyValue("outcome", outcomeKind?.name)
            .addKeyValue("failure_code", failureCode)
            .addKeyValue("terminal_record", terminalRecord)
            .addKeyValue("settlement", settlement)
            .addKeyValue("admitted_to_claim_ms", millisBetween(admittedAt, dispatchClaimedAt))
            .addKeyValue("occupancy_wait_ms", millisBetween(dispatchClaimedAt, occupancyAcquiredAt))
            .addKeyValue("accept_to_dial_ms", millisBetween(admittedAt, rpcStartedAt))
            .addKeyValue("rpc_ms", millisBetween(rpcStartedAt, rpcTerminalAt))
            .addKeyValue("terminal_to_settle_ms", millisBetween(terminalRecordedAt, settlementFinishedAt))
            .addKeyValue("settle_transaction_ms", millisBetween(settlementRequestedAt, settlementFinishedAt))
            .addKeyValue("admitted_to_settled_ms", millisBetween(admittedAt, ownerSettledAt))
            .log("execution_run_timing")
    }

    private fun millisBetween(start: Instant?, end: Instant?): Long? =
        if (start == null || end == null) null else Duration.between(start, end).toMillis()
}
