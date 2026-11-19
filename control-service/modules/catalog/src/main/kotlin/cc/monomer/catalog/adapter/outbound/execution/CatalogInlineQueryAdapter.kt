@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.adapter.outbound.execution

import cc.monomer.catalog.application.CatalogInlineArrowBatch
import cc.monomer.catalog.application.CatalogInlineQueryCommand
import cc.monomer.catalog.application.CatalogInlineQueryPort
import cc.monomer.catalog.application.CatalogInlineQueryResult
import cc.monomer.catalog.domain.CatalogQueryParameter
import cc.monomer.execution.api.InlineQueryRunSpecFactory
import cc.monomer.execution.api.ExecutionRunEvent
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.PhysicalOutcome
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorRuntimeBindingSpec
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunAdmissionPort
import cc.monomer.execution.api.ExecutableRequestKind
import cc.monomer.execution.api.InlineQueryRunSpecCommand
import cc.monomer.execution.api.NullQueryParameterType
import cc.monomer.execution.api.QueryParameterValue
import cc.monomer.execution.api.ResultSinkBinding
import cc.monomer.catalog.application.CatalogTransactionPort
import cc.monomer.identity.api.WorkspaceId
import java.time.Clock
import java.time.Duration
import org.slf4j.MDC

/**
 * Bridges one Catalog SQL query to exactly one ExecutionRun. Inline frames are delivered to the
 * suspended Catalog child and never pass through the artifact store or durable staging.
 */
class CatalogInlineQueryAdapter(
    private val connectorBinding: ConnectorRuntimeBindingSpec,
    private val runSpecFactory: InlineQueryRunSpecFactory,
    private val admittedRunAdmission: ExecutionRunAdmissionPort,
    private val transactionPort: CatalogTransactionPort,
    private val runInline: suspend (ExecutionRunId, suspend (ExecutionRunEvent.InlineResultBatch) -> Unit) -> PhysicalOutcome,
    private val registerAcceptanceAfterCommit: (ExecutionRunSnapshot, String?) -> Boolean,
    private val logAcceptance: (ExecutionRunSnapshot, String?) -> Unit,
    private val workspaceId: WorkspaceId,
    private val owner: ExecutionOwnerReference,
    private val clock: Clock,
    private val dispatchReservationDuration: Duration,
    private val executionDeadlineDuration: Duration,
    private val maximumRecordBatchRows: Int,
    private val maximumRecordBatchBytes: Int,
    private val maximumCellBytes: Int,
) : CatalogInlineQueryPort {
    init {
        require(maximumRecordBatchRows > 0)
        require(maximumRecordBatchBytes in 1..ResultSinkBinding.MAXIMUM_FRAME_BYTES)
        require(maximumCellBytes in 1..ResultSinkBinding.MAXIMUM_CELL_BYTES)
        require(!dispatchReservationDuration.isNegative && !dispatchReservationDuration.isZero)
        require(!executionDeadlineDuration.isNegative && !executionDeadlineDuration.isZero)
    }

    override suspend fun execute(
        command: CatalogInlineQueryCommand,
        onRunAdmittedInOwnerTransaction: (ExecutionRunId) -> Unit,
        onBatch: suspend (CatalogInlineArrowBatch) -> Unit,
    ): CatalogInlineQueryResult {
        check(workspaceId == command.workspaceId)
        check(command.connectorBinding == connectorBinding)
        if (connectorBinding.connectorId != ConnectorReference(command.connectorId.value)) {
            return CatalogInlineQueryResult.Failed(
                ExecutionRunId.generate(),
                "CONNECTOR_MISMATCH",
                "Captured connector binding is not owned by the requested connector",
            )
        }
        val runId = ExecutionRunId.generate()
        val requestedAt = clock.instant()
        val dispatchDeadline = requestedAt.plus(dispatchReservationDuration)
        val deadline = dispatchDeadline.plus(executionDeadlineDuration)
        val spec = runSpecFactory.create(
            InlineQueryRunSpecCommand(
                runId = runId,
                workspaceId = command.workspaceId,
                connector = connectorBinding,
                sql = command.sql,
                parameters = command.parameters.map { it.toQueryParameter() },
                maximumRecordBatchRows = maximumRecordBatchRows,
                maximumRecordBatchBytes = maximumRecordBatchBytes,
                maximumCellBytes = maximumCellBytes,
                deadline = deadline,
            ),
        )
        val admission = ExecutionRunAdmissionPort.ReserveCommand(
            runId = runId,
            owner = owner,
            workspaceId = command.workspaceId,
            idempotencyKey = "catalog-query:${command.executionReservationKey}",
            requestFingerprint = spec.canonicalRequestDigest,
            connectorId = connectorBinding.connectorId,
            connectorType = connectorBinding.connectorType,
            connectorProperties = connectorBinding.properties,
            requestKind = ExecutableRequestKind.QUERY,
            runtimeBindings = spec.runtimeBindings,
            assignmentTemplate = spec.assignmentTemplate,
            dispatchIntentExpiresAt = dispatchDeadline,
            deadline = deadline,
            reservedAt = requestedAt,
        )
        val requestId = MDC.get("request_id")
        var acceptanceAfterCommitRegistered = false
        val reserved = transactionPort.inTransaction {
            admittedRunAdmission.reserveInOwnerTransaction(admission).also { outcome ->
                when (outcome) {
                    is ExecutionRunAdmissionPort.ReserveOutcome.Reserved -> {
                        onRunAdmittedInOwnerTransaction(outcome.snapshot.id)
                        acceptanceAfterCommitRegistered = registerAcceptanceAfterCommit(outcome.snapshot, requestId)
                    }
                    is ExecutionRunAdmissionPort.ReserveOutcome.Existing ->
                        onRunAdmittedInOwnerTransaction(outcome.snapshot.id)
                    else -> Unit
                }
            }
        }
        if (reserved is ExecutionRunAdmissionPort.ReserveOutcome.Reserved && !acceptanceAfterCommitRegistered) {
            logAcceptance(reserved.snapshot, requestId)
        }
        val snapshot = when (reserved) {
            is ExecutionRunAdmissionPort.ReserveOutcome.Reserved -> reserved.snapshot
            is ExecutionRunAdmissionPort.ReserveOutcome.Existing -> reserved.snapshot
            else -> return CatalogInlineQueryResult.Failed(
                runId,
                "EXECUTION_RUN_NOT_ADMITTED",
                "Catalog query could not be admitted to Execution",
            )
        }
        val outcome = runInline(snapshot.id) { event ->
            onBatch(
                CatalogInlineArrowBatch(
                    sequence = event.sequence,
                    arrowIpc = event.arrowIpc.copyBytes(),
                    sha256 = event.sha256.value,
                ),
            )
        }
        return when (outcome.kind) {
            cc.monomer.execution.api.PhysicalOutcomeKind.SUCCEEDED ->
                CatalogInlineQueryResult.Succeeded(snapshot.id)
            cc.monomer.execution.api.PhysicalOutcomeKind.CANCELLED ->
                CatalogInlineQueryResult.Cancelled(snapshot.id)
            else -> CatalogInlineQueryResult.Failed(
                snapshot.id,
                outcome.failureCode ?: "EXECUTION_FAILED",
                outcome.safeMessage ?: "Catalog query failed",
            )
        }
    }

    private fun CatalogQueryParameter.toQueryParameter(): QueryParameterValue =
        (if (connectorBinding.connectorType == cc.monomer.execution.api.ConnectorType.DATABRICKS) name else "").let { parameterName ->
            value?.let { QueryParameterValue.Text(it, parameterName) }
                ?: QueryParameterValue.Null(NullQueryParameterType.TEXT, parameterName)
        }

}
