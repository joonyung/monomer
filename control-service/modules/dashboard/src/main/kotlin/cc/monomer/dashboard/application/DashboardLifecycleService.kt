@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.application

import cc.monomer.dashboard.api.*
import cc.monomer.dashboard.domain.Dashboard
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

interface DashboardLifecycleRepository {
    fun lockForDeletion(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardDeletionState?
    fun deleteOwnedRows(workspaceId: WorkspaceId, dashboardId: DashboardId)
    fun appendDefinitionChange(change: DashboardOwnerDefinitionChange)
    fun readDefinitionChanges(workspaceId: WorkspaceId, dashboardId: DashboardId, cursor: DashboardHistoryCursor?, limit: Int): List<DashboardOwnerDefinitionChange>
    fun findDefinitionChange(workspaceId: WorkspaceId, dashboardId: DashboardId, changeId: Uuid): DashboardOwnerDefinitionChange?
}

data class DashboardDeletionState(val dashboard: Dashboard, val draftExisted: Boolean, val runs: List<DashboardDeletionRun>, val sessionIds: List<DashboardSessionId>)

class DashboardLifecycleService(
    private val transactionPort: DashboardTransactionPort,
    private val dashboardRepository: DashboardRepository,
    private val lifecycleRepository: DashboardLifecycleRepository,
    private val commandReceiptRepository: DashboardCommandReceiptRepository,
    private val executionAdmissionPort: DashboardExecutionAdmissionPort,
    private val dependencyCleanupPort: DashboardDependencyCleanupPort,
    private val searchProjection: DashboardSearchProjection,
) {
    suspend fun delete(command: DeleteDashboardCommand): DashboardOwnerOutcome<Unit> = try {
        transactionPort.inTransaction {
            executionAdmissionPort.lockWorkspace(command.workspaceId)
            val state = lifecycleRepository.lockForDeletion(command.workspaceId, command.dashboardId)
                ?: return@inTransaction DashboardOwnerOutcome.NotFound
            if (dependencyCleanupPort.discardRunsInCallerTransaction(command.workspaceId, state.runs, "DASHBOARD_DELETED", command.metadata.requestedAt) == DashboardRunDiscardOutcome.RETRYABLE_CONFLICT) {
                throw DeleteConflict()
            }
            lifecycleRepository.appendDefinitionChange(DashboardOwnerDefinitionChange(
                Uuid.generateV7(), command.workspaceId, command.dashboardId, state.dashboard.definition.meta.title,
                DashboardDefinitionChangeAction.DELETED, state.dashboard.definition.document, command.metadata.actorId,
                command.metadata.credentialSurface, command.metadata.agentClientId, command.metadata.requestedAt, false,
            ))
            lifecycleRepository.deleteOwnedRows(command.workspaceId, command.dashboardId)
            searchProjection.delete(command.workspaceId, command.dashboardId)
            dependencyCleanupPort.deletePagesInCallerTransaction(command)
            dependencyCleanupPort.invalidateAfterCommit(command.workspaceId, command.dashboardId, state.draftExisted, state.sessionIds)
            DashboardOwnerOutcome.Success(Unit)
        }
    } catch (_: DeleteConflict) {
        DashboardOwnerOutcome.Rejected("DASHBOARD_DELETE_RETRY_REQUIRED", "Dashboard execution changed. Retry deleting the dashboard.")
    }

    suspend fun listHistory(command: ListDashboardHistoryCommand): DashboardOwnerOutcome<DashboardOwnerHistoryPage> {
        require(command.pageSize in 1..100)
        if (command.cursor != null && (command.cursor.workspaceId != command.workspaceId || command.cursor.dashboardId != command.dashboardId)) {
            return DashboardOwnerOutcome.Rejected("INVALID_CURSOR", "History cursor belongs to another dashboard.")
        }
        return transactionPort.inTransaction {
            executionAdmissionPort.lockWorkspace(command.workspaceId)
            val latest = lifecycleRepository.readDefinitionChanges(command.workspaceId, command.dashboardId, null, 1).firstOrNull()
                ?: return@inTransaction DashboardOwnerOutcome.NotFound
            val exists = dashboardRepository.find(command.workspaceId, command.dashboardId) != null
            val rows = lifecycleRepository.readDefinitionChanges(command.workspaceId, command.dashboardId, command.cursor, command.pageSize + 1)
            val items = rows.take(command.pageSize).map { it.withRestorable(latest, exists) }
            DashboardOwnerOutcome.Success(DashboardOwnerHistoryPage(command.dashboardId, latest.title, !exists, items,
                if (rows.size > command.pageSize) items.last().let { DashboardHistoryCursor(command.workspaceId, command.dashboardId, it.occurredAt, it.changeId) } else null))
        }
    }

    suspend fun readHistory(command: ReadDashboardHistoryCommand): DashboardOwnerOutcome<DashboardOwnerDefinitionChange> = transactionPort.inTransaction {
        executionAdmissionPort.lockWorkspace(command.workspaceId)
        val change = lifecycleRepository.findDefinitionChange(command.workspaceId, command.dashboardId, command.changeId)
            ?: return@inTransaction DashboardOwnerOutcome.NotFound
        val latest = lifecycleRepository.readDefinitionChanges(command.workspaceId, command.dashboardId, null, 1).first()
        DashboardOwnerOutcome.Success(change.withRestorable(latest, dashboardRepository.find(command.workspaceId, command.dashboardId) != null))
    }

    suspend fun restore(command: RestoreDashboardCommand): DashboardOwnerOutcome<Dashboard> = transactionPort.inTransaction {
        executionAdmissionPort.lockWorkspace(command.workspaceId)
        commandReceiptRepository.lock(command.workspaceId, command.metadata.actorId, command.metadata.idempotencyKey)
        val request = buildJsonObject {
            put("kind", "RESTORE_DASHBOARD")
            put("dashboardId", command.dashboardId.toString())
            put("changeId", command.changeId.toString())
        }
        commandReceiptRepository.find(command.workspaceId, command.metadata.actorId, command.metadata.idempotencyKey)?.let { receipt ->
            if (receipt.normalizedRequest != request || receipt.dashboardId?.let { dashboardRepository.find(command.workspaceId, it) } == null) {
                return@inTransaction DashboardOwnerOutcome.Rejected("IDEMPOTENCY_CONFLICT", null)
            }
            val response = receipt.response as? CommandReceiptResponse.DashboardCreated
                ?: return@inTransaction DashboardOwnerOutcome.Rejected("IDEMPOTENCY_RECEIPT_INVALID", null)
            return@inTransaction DashboardOwnerOutcome.Success(response.dashboard)
        }
        if (dashboardRepository.find(command.workspaceId, command.dashboardId) != null) return@inTransaction LIVE_DASHBOARD_NOT_RESTORABLE
        val change = lifecycleRepository.findDefinitionChange(command.workspaceId, command.dashboardId, command.changeId)
            ?: return@inTransaction DashboardOwnerOutcome.NotFound
        val latest = lifecycleRepository.readDefinitionChanges(command.workspaceId, command.dashboardId, null, 1).first()
        if (!change.withRestorable(latest, false).restorable) return@inTransaction DUPLICATE_VERSION_NOT_RESTORABLE
        val definition = try {
            DashboardDefinitionDecoder.decode(change.definition)
        } catch (error: InvalidDashboardDefinitionException) {
            return@inTransaction DashboardOwnerOutcome.Rejected("INVALID_DASHBOARD_DEFINITION", error.message)
        }
        val dashboard = Dashboard(command.dashboardId, command.workspaceId, definition,
            command.metadata.requestedAt, command.metadata.requestedAt, command.metadata.actorId, command.metadata.credentialSurface, command.metadata.agentClientId)
        if (!dashboardRepository.insert(dashboard)) return@inTransaction LIVE_DASHBOARD_NOT_RESTORABLE
        searchProjection.upsert(dashboard.toSearchFields())
        check(commandReceiptRepository.insert(CommandReceipt(command.workspaceId, command.metadata.actorId, command.metadata.idempotencyKey,
            "RESTORE_DASHBOARD", command.dashboardId.toString(), request, command.dashboardId, null, emptyList(), CommandReceiptResponse.DashboardCreated(dashboard), command.metadata.requestedAt)))
        DashboardOwnerOutcome.Success(dashboard)
    }

    private fun DashboardOwnerDefinitionChange.withRestorable(latest: DashboardOwnerDefinitionChange, exists: Boolean) = copy(
        restorable = !exists && latest.action == DashboardDefinitionChangeAction.DELETED &&
            (changeId == latest.changeId || definition != latest.definition),
    )

    private class DeleteConflict : RuntimeException()

    private companion object {
        val LIVE_DASHBOARD_NOT_RESTORABLE = DashboardOwnerOutcome.Rejected(
            "DASHBOARD_NOT_RESTORABLE", "This dashboard isn't deleted, so there's nothing to restore. Open it to edit it instead.",
        )
        val DUPLICATE_VERSION_NOT_RESTORABLE = DashboardOwnerOutcome.Rejected(
            "DASHBOARD_NOT_RESTORABLE", "This version matches the dashboard as it was deleted. Restore the deleted version instead.",
        )
    }
}
