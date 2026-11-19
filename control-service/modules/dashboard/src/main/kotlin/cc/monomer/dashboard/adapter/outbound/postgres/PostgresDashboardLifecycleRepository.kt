@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.adapter.outbound.postgres

import cc.monomer.dashboard.api.DashboardDefinitionChangeAction
import cc.monomer.dashboard.api.DashboardDeletionRun
import cc.monomer.dashboard.api.DashboardHistoryCursor
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardOwnerDefinitionChange
import cc.monomer.dashboard.api.DashboardSessionId
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.dashboard.application.DashboardLifecycleRepository
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.jsonObject
import java.sql.ResultSet
import javax.sql.DataSource
import kotlin.uuid.Uuid

class PostgresDashboardLifecycleRepository(
    dataSource: DataSource,
) : DashboardLifecycleRepository {
    private val jdbc = PostgresDashboardJdbc(dataSource)

    override fun lockForDeletion(workspaceId: WorkspaceId, dashboardId: DashboardId): cc.monomer.dashboard.application.DashboardDeletionState? {
        jdbc.requireOwnerTransaction()
        val draft = jdbc.findDraftForUpdate(workspaceId, dashboardId)
        val runs = jdbc.queryMany("SELECT query_run_id, state FROM dashboard.query_runs WHERE workspace_id = ? AND dashboard_id = ? ORDER BY query_run_id FOR UPDATE", {
            it.setUuid(1, workspaceId.value); it.setUuid(2, dashboardId.value)
        }, { row -> DashboardDeletionRun(QueryRunId(row.getUuid("query_run_id")), cc.monomer.execution.api.ExecutionRunId(row.getUuid("query_run_id")), row.getString("state") == "RUNNING") })
        val sessions = jdbc.queryMany("SELECT session_id FROM dashboard.sessions WHERE workspace_id = ? AND dashboard_id = ? ORDER BY session_id FOR UPDATE", {
            it.setUuid(1, workspaceId.value); it.setUuid(2, dashboardId.value)
        }, { DashboardSessionId(it.getUuid("session_id")) })
        val dashboard = jdbc.lockCurrentDashboard(workspaceId, dashboardId) ?: return null
        return cc.monomer.dashboard.application.DashboardDeletionState(dashboard, draft != null, runs, sessions)
    }

    override fun deleteOwnedRows(workspaceId: WorkspaceId, dashboardId: DashboardId) {
        jdbc.requireOwnerTransaction()
        listOf("schedule_occurrences", "schedule_cursors", "query_runs", "sessions", "drafts", "dashboards").forEach { table ->
            jdbc.update("DELETE FROM dashboard.$table WHERE workspace_id = ? AND dashboard_id = ?", {
                it.setUuid(1, workspaceId.value); it.setUuid(2, dashboardId.value)
            })
        }
    }

    override fun appendDefinitionChange(change: DashboardOwnerDefinitionChange) {
        jdbc.appendDefinitionChange(change)
    }

    override fun readDefinitionChanges(workspaceId: WorkspaceId, dashboardId: DashboardId, cursor: DashboardHistoryCursor?, limit: Int): List<DashboardOwnerDefinitionChange> {
        require(limit in 1..101)
        require(cursor == null || (cursor.workspaceId == workspaceId && cursor.dashboardId == dashboardId))
        return jdbc.queryMany("SELECT * FROM dashboard.definition_changes WHERE workspace_id = ? AND dashboard_id = ?" +
            (if (cursor == null) "" else " AND (occurred_at, change_id) < (?, ?)") + " ORDER BY occurred_at DESC, change_id DESC LIMIT ?", {
            it.setUuid(1, workspaceId.value); it.setUuid(2, dashboardId.value)
            if (cursor != null) { it.setInstant(3, cursor.occurredAt); it.setUuid(4, cursor.changeId) }
            it.setInt(if (cursor == null) 3 else 5, limit)
        }, ::readDefinitionChange)
    }

    override fun findDefinitionChange(workspaceId: WorkspaceId, dashboardId: DashboardId, changeId: Uuid): DashboardOwnerDefinitionChange? = jdbc.queryOne(
        "SELECT * FROM dashboard.definition_changes WHERE workspace_id = ? AND dashboard_id = ? AND change_id = ?", {
            it.setUuid(1, workspaceId.value); it.setUuid(2, dashboardId.value); it.setUuid(3, changeId)
        }, ::readDefinitionChange,
    )

    private fun readDefinitionChange(row: ResultSet) = DashboardOwnerDefinitionChange(
        row.getUuid("change_id"), WorkspaceId(row.getUuid("workspace_id")), DashboardId(row.getUuid("dashboard_id")),
        row.getString("title"), DashboardDefinitionChangeAction.valueOf(row.getString("action")), dashboardRowJson.parseToJsonElement(row.getString("definition")).jsonObject,
        row.getString("actor_id"), row.getString("credential_surface")?.let(cc.monomer.identity.api.CredentialSurface::valueOf),
        row.getString("agent_client_id")?.let { cc.monomer.identity.api.AgentClientId(it) }, row.getInstant("occurred_at"), false,
    )
}
