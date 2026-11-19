@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.adapter.outbound.postgres

import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardOwnerDefinitionChange
import cc.monomer.dashboard.api.DashboardScheduleStatus
import cc.monomer.dashboard.domain.Dashboard
import cc.monomer.dashboard.domain.DashboardDraft
import cc.monomer.identity.api.WorkspaceId
import java.time.Instant
import kotlin.uuid.Uuid

internal fun PostgresDashboardJdbc.findCurrentDashboard(workspaceId: WorkspaceId, dashboardId: DashboardId): Dashboard? =
    queryOne(
        """
        SELECT dashboard_id, workspace_id, definition, created_at, updated_at, updated_by, credential_surface, agent_client_id
        FROM dashboard.dashboards
        WHERE workspace_id = ? AND dashboard_id = ?
        """.trimIndent(),
        { statement ->
            statement.setUuid(1, workspaceId.value)
            statement.setUuid(2, dashboardId.value)
        },
        ::readDashboard,
    )

internal fun PostgresDashboardJdbc.lockCurrentDashboard(workspaceId: WorkspaceId, dashboardId: DashboardId): Dashboard? {
    queryOne("SELECT dashboard_id FROM dashboard.dashboards WHERE workspace_id = ? AND dashboard_id = ? FOR UPDATE", {
        it.setUuid(1, workspaceId.value); it.setUuid(2, dashboardId.value)
    }, { true }) ?: return null
    return findCurrentDashboard(workspaceId, dashboardId)
}

internal fun PostgresDashboardJdbc.databaseNow(): Instant = queryOne("SELECT clock_timestamp() AS now", {}, { it.getInstant("now") })!!

internal fun PostgresDashboardJdbc.listScheduleCursors(workspaceId: WorkspaceId, dashboardId: DashboardId): List<DashboardScheduleStatus> = queryMany(
    "SELECT schedule_key, next_scheduled_at FROM dashboard.schedule_cursors WHERE workspace_id = ? AND dashboard_id = ? ORDER BY schedule_key",
    { it.setUuid(1, workspaceId.value); it.setUuid(2, dashboardId.value) },
    { DashboardScheduleStatus(it.getString("schedule_key"), it.getInstant("next_scheduled_at")) },
)

internal fun PostgresDashboardJdbc.appendDefinitionChange(change: DashboardOwnerDefinitionChange) {
    requireOwnerTransaction()
    check(update("""INSERT INTO dashboard.definition_changes
            (change_id, workspace_id, dashboard_id, title, action, definition, actor_id, credential_surface, agent_client_id, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, GREATEST(?::timestamptz,
                (SELECT max(occurred_at) + INTERVAL '1 microsecond' FROM dashboard.definition_changes
                 WHERE workspace_id = ? AND dashboard_id = ?)))""", {
        it.setUuid(1, change.changeId); it.setUuid(2, change.workspaceId.value); it.setUuid(3, change.dashboardId.value)
        it.setString(4, change.title); it.setString(5, change.action.name); it.setString(6, change.definition.toString())
        it.setNullableUuid(7, change.actorId?.let(Uuid::parse)); it.setString(8, change.credentialSurface?.name); it.setString(9, change.agentClientId?.value)
        it.setInstant(10, change.occurredAt)
        it.setUuid(11, change.workspaceId.value); it.setUuid(12, change.dashboardId.value)
    }) == 1)
}

internal fun PostgresDashboardJdbc.findDraftForUpdate(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardDraft? =
    queryOne(
        """
        SELECT workspace_id, dashboard_id, definition, parameter_bindings, variables,
               authoring_revision, target_query_runs, ready_results, created_at, updated_at, last_edited_at, last_edited_by, last_edit_surface, last_edit_agent_client_id
        FROM dashboard.drafts
        WHERE workspace_id = ? AND dashboard_id = ?
        FOR UPDATE
        """.trimIndent(),
        { statement ->
            statement.setUuid(1, workspaceId.value)
            statement.setUuid(2, dashboardId.value)
        },
        ::readDraft,
    )
