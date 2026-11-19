@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.explore.application

import cc.monomer.explore.api.ExploreResultRetention
import cc.monomer.explore.api.ExploreResultView
import cc.monomer.explore.api.ExploreRunFailureCode
import cc.monomer.explore.api.ExploreRunId
import cc.monomer.explore.api.ExploreRunState
import cc.monomer.explore.api.MAX_EXPLORE_FAILURE_DETAIL_LENGTH
import cc.monomer.explore.api.exploreResultObjectKey
import cc.monomer.explore.domain.ExploreTerminalReceipt
import cc.monomer.execution.api.ExecutionOwnerId
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunOwnerSettlementPort
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.PhysicalOutcome
import cc.monomer.execution.api.PhysicalOutcomeKind
import java.time.Duration

/** Commits one terminal physical outcome into either Explore run subtype. */
class ExploreSettlementAdapter(
    private val repository: ExploreRunRepository,
    private val activeTransactionPort: ActiveExploreOwnerSettlementTransactionPort,
    private val resultRetention: Duration,
) : ExecutionRunOwnerSettlementPort {
    init {
        require(!resultRetention.isNegative && !resultRetention.isZero)
    }

    override val ownerType: ExecutionOwnerType = ExecutionOwnerType.EXPLORE

    override fun settleInOwnerTransaction(
        command: ExecutionRunOwnerSettlementPort.SettleCommand,
    ): ExecutionRunOwnerSettlementPort.SettlementOutcome =
        activeTransactionPort.inActiveOwnerSettlementTransaction { settle(command) }

    private fun settle(
        command: ExecutionRunOwnerSettlementPort.SettleCommand,
    ): ExecutionRunOwnerSettlementPort.SettlementOutcome {
        val executionRun = command.run
        val physicalOutcome = requireNotNull(executionRun.physicalOutcome)
        if (
            physicalOutcome.arrowResultManifest?.objectKey?.let {
                it != exploreResultObjectKey(executionRun.workspaceId, ExploreRunId(executionRun.id.value))
            } == true
        ) {
            return ExecutionRunOwnerSettlementPort.SettlementOutcome.Conflict
        }
        val expectedOwner = ExecutionOwnerReference(
            type = ExecutionOwnerType.EXPLORE,
            id = ExecutionOwnerId(executionRun.id.value),
        )
        if (executionRun.owner != expectedOwner) {
            return ExecutionRunOwnerSettlementPort.SettlementOutcome.Conflict
        }
        val run = repository.lockRunForOwnerMutation(ExploreRunId(executionRun.id.value))
            ?: return ExecutionRunOwnerSettlementPort.SettlementOutcome.Conflict
        if (!executionRun.matchesOwner(run) || run.state != ExploreRunState.RUNNING) {
            return duplicateIfAlreadySettled(command, run)
        }
        val facts = terminalFacts(run, physicalOutcome, command.settledAt)
        val receipt = ExploreTerminalReceipt(
            workspaceId = executionRun.workspaceId,
            settlementIdempotencyKey = physicalOutcome.terminalIdempotencyKey,
            runId = run.id,
            terminalState = facts.state,
            failureCode = facts.failureCode,
            failureMessage = facts.failureMessage,
            result = facts.result,
            settledAt = command.settledAt,
        )
        if (!repository.commitTerminalReceipt(run.complete(receipt), receipt, run.revision)) {
            return ExecutionRunOwnerSettlementPort.SettlementOutcome.Conflict
        }
        return ExecutionRunOwnerSettlementPort.SettlementOutcome.Completed
    }

    private fun duplicateIfAlreadySettled(
        command: ExecutionRunOwnerSettlementPort.SettleCommand,
        run: cc.monomer.explore.domain.ExploreRun,
    ): ExecutionRunOwnerSettlementPort.SettlementOutcome {
        val physicalOutcome = command.run.physicalOutcome
            ?: return ExecutionRunOwnerSettlementPort.SettlementOutcome.Conflict
        val receipt = repository.findTerminalReceipt(
            workspaceId = command.run.workspaceId,
            settlementIdempotencyKey = physicalOutcome.terminalIdempotencyKey,
        ) ?: return ExecutionRunOwnerSettlementPort.SettlementOutcome.Conflict
        return if (receipt.runId == run.id) {
            ExecutionRunOwnerSettlementPort.SettlementOutcome.Duplicate
        } else {
            ExecutionRunOwnerSettlementPort.SettlementOutcome.Conflict
        }
    }

    private fun terminalFacts(
        run: cc.monomer.explore.domain.ExploreRun,
        physicalOutcome: PhysicalOutcome,
        settledAt: java.time.Instant,
    ): ExploreTerminalFacts = when (physicalOutcome.kind) {
        PhysicalOutcomeKind.SUCCEEDED -> {
            val manifest = physicalOutcome.arrowResultManifest
                ?: return ExploreTerminalFacts.failure(
                    ExploreRunFailureCode.EXECUTION_FAILED,
                    "Execution did not provide an Arrow result manifest",
                )
            ExploreTerminalFacts.success(
                ExploreResultView(
                    runId = run.id,
                    objectKey = manifest.objectKey,
                    objectEtag = manifest.objectEtag,
                    byteSize = manifest.byteSize,
                    mediaType = manifest.mediaType,
                    rowCount = manifest.rowCount,
                    columnCount = manifest.columnCount,
                    retention = ExploreResultRetention(settledAt.plus(resultRetention)),
                    ownerPrincipalId = run.ownerPrincipalId,
                    activatedAt = settledAt,
                ),
            )
        }
        PhysicalOutcomeKind.FAILED -> ExploreTerminalFacts.failure(
            when (physicalOutcome.failureCode) {
                "PARTITION_FILTER_REQUIRED" -> ExploreRunFailureCode.PARTITION_FILTER_REQUIRED
                "PERMISSION_DENIED" -> ExploreRunFailureCode.PERMISSION_DENIED
                "CONNECTION_FAILED" -> ExploreRunFailureCode.CONNECTION_FAILED
                "AUTHENTICATION_FAILED" -> ExploreRunFailureCode.AUTHENTICATION_FAILED
                "EXECUTION_ORPHANED" -> ExploreRunFailureCode.EXECUTION_ORPHANED
                else -> ExploreRunFailureCode.EXECUTION_FAILED
            },
            exploreExecutionFailureDetail(physicalOutcome.failureCode, physicalOutcome.safeMessage),
        )
        PhysicalOutcomeKind.CANCELLED -> ExploreTerminalFacts.failure(
            ExploreRunFailureCode.CANCELLED_BY_ACTOR,
            "Explore query was cancelled",
        )
    }

    private fun ExecutionRunSnapshot.matchesOwner(run: cc.monomer.explore.domain.ExploreRun): Boolean =
        owner == ExecutionOwnerReference(ExecutionOwnerType.EXPLORE, ExecutionOwnerId(run.id.value)) &&
            id == ExecutionRunId(run.id.value) &&
            workspaceId == run.workspaceId &&
            connectorId.value == run.connectorBinding.connectorId.value &&
            connectorType == run.connectorBinding.connectorType
}

internal fun exploreExecutionFailureDetail(code: String?, safeMessage: String?): String = when (code) {
    "RESULT_LIMIT_EXCEEDED" -> "The query result exceeds the supported size limit. Return fewer rows or smaller values."
    "UNSUPPORTED_RESULT_TYPE" -> safeExploreFailureDetail(
        safeMessage,
        "The query result contains a type that cannot be returned. Cast the column to a supported type.",
    )
    else -> safeExploreFailureDetail(safeMessage, "Explore query execution failed")
}

internal fun safeExploreFailureDetail(message: String?, fallback: String): String =
    message?.takeIf {
        it.isNotBlank() && it.length <= MAX_EXPLORE_FAILURE_DETAIL_LENGTH
    } ?: fallback

private data class ExploreTerminalFacts(
    val state: ExploreRunState,
    val failureCode: ExploreRunFailureCode?,
    val failureMessage: String?,
    val result: ExploreResultView?,
) {
    companion object {
        fun success(result: ExploreResultView) = ExploreTerminalFacts(
            state = ExploreRunState.SUCCEEDED,
            failureCode = null,
            failureMessage = null,
            result = result,
        )

        fun failure(code: ExploreRunFailureCode, message: String) = ExploreTerminalFacts(
            state = if (code == ExploreRunFailureCode.CANCELLED_BY_ACTOR) {
                ExploreRunState.CANCELLED
            } else {
                ExploreRunState.FAILED
            },
            failureCode = code,
            failureMessage = message,
            result = null,
        )
    }
}
