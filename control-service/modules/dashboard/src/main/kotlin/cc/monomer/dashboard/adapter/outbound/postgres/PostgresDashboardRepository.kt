@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.adapter.outbound.postgres

import cc.monomer.dashboard.api.DashboardCurrentSemanticReferences
import cc.monomer.dashboard.api.DashboardDefinitionChangeAction
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardOwnerDefinitionChange
import cc.monomer.dashboard.application.DashboardPageCursor
import cc.monomer.dashboard.application.DashboardRepository
import cc.monomer.dashboard.domain.Dashboard
import cc.monomer.dashboard.domain.DashboardListEntry
import cc.monomer.dashboard.domain.DashboardListItem
import cc.monomer.dashboard.domain.DashboardListSchedule
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import javax.sql.DataSource
import kotlin.uuid.Uuid

class PostgresDashboardRepository(
    dataSource: DataSource,
) : DashboardRepository {
    private val jdbc = PostgresDashboardJdbc(dataSource)

    override fun readCurrentSemanticReferences(workspaceId: WorkspaceId): List<DashboardCurrentSemanticReferences> = jdbc.queryMany(
        "SELECT dashboard_id, definition FROM dashboard.dashboards WHERE workspace_id = ? ORDER BY dashboard_id",
        { it.setUuid(1, workspaceId.value) },
        { row -> cc.monomer.dashboard.application.dashboardSemanticReferences(DashboardId(row.getUuid("dashboard_id")), dashboardRowJson.parseToJsonElement(row.getString("definition")).jsonObject) },
    )

    override fun findForUpdate(workspaceId: WorkspaceId, dashboardId: DashboardId): Dashboard? =
        jdbc.lockCurrentDashboard(workspaceId, dashboardId)

    override fun find(workspaceId: WorkspaceId, dashboardId: DashboardId): Dashboard? =
        jdbc.findCurrentDashboard(workspaceId, dashboardId)

    override fun list(workspaceId: WorkspaceId): List<DashboardListEntry> =
        jdbc.queryMany(
            """
            SELECT current.dashboard_id, current.workspace_id,
                   current.definition -> 'meta' ->> 'title' AS title,
                   current.definition -> 'meta' ->> 'description' AS description,
                   current.updated_at, current.updated_by, current.credential_surface, current.agent_client_id,
                   current.definition -> 'schedules' AS schedules,
                   (draft.dashboard_id IS NOT NULL AND draft.definition <> current.definition) AS draft_has_changes,
                   draft.last_edited_at AS draft_last_edited_at, draft.last_edited_by AS draft_last_edited_by,
                   draft.last_edit_surface AS draft_last_edit_surface,
                   draft.last_edit_agent_client_id AS draft_last_edit_agent_client_id,
                   latest.latest_results_at
            FROM dashboard.dashboards current
            LEFT JOIN dashboard.drafts draft
              ON draft.workspace_id = current.workspace_id AND draft.dashboard_id = current.dashboard_id
            LEFT JOIN LATERAL (
                SELECT MAX(run.updated_at) AS latest_results_at
                FROM dashboard.query_runs run
                WHERE run.workspace_id = current.workspace_id
                  AND run.dashboard_id = current.dashboard_id
                  AND run.viewer_session_id IS NOT NULL
                  AND run.state = 'SUCCEEDED'
            ) latest ON TRUE
            WHERE current.workspace_id = ?
            ORDER BY current.updated_at DESC, current.dashboard_id DESC
            """.trimIndent(),
            { statement -> statement.setUuid(1, workspaceId.value) },
        ) { row ->
            DashboardListEntry(
                id = DashboardId(row.getUuid("dashboard_id")),
                workspaceId = WorkspaceId(row.getUuid("workspace_id")),
                title = row.getString("title"),
                description = row.getString("description"),
                updatedAt = row.getInstant("updated_at"),
                updatedBy = row.getString("updated_by"),
                credentialSurface = row.getString("credential_surface")?.let(cc.monomer.identity.api.CredentialSurface::valueOf),
                agentClientId = row.getString("agent_client_id")?.let { cc.monomer.identity.api.AgentClientId(it) },
                latestResultsAt = row.getTimestamp("latest_results_at")?.toInstant(),
                schedules = decodeListSchedules(row.getString("schedules")),
                draftHasChanges = row.getBoolean("draft_has_changes"),
                draftLastEditedAt = row.getTimestamp("draft_last_edited_at")?.toInstant(),
                draftLastEditedBy = row.getString("draft_last_edited_by"),
                draftLastEditSurface = row.getString("draft_last_edit_surface")?.let(cc.monomer.identity.api.CredentialSurface::valueOf),
                draftLastEditAgentClientId = row.getString("draft_last_edit_agent_client_id")?.let { cc.monomer.identity.api.AgentClientId(it) },
            )
        }

    override fun findPage(
        workspaceId: WorkspaceId,
        cursor: DashboardPageCursor?,
        limit: Int,
    ): List<DashboardListItem> {
        require(limit > 0)
        val sql = buildString {
            append(
                """
                SELECT dashboard_id, workspace_id, definition -> 'meta' ->> 'title' AS title,
                       definition -> 'meta' ->> 'description' AS description, updated_at
                FROM dashboard.dashboards
                WHERE workspace_id = ?
                """.trimIndent(),
            )
            if (cursor != null) append("\nAND (updated_at, dashboard_id) < (?, ?)")
            append(
                """
                ORDER BY updated_at DESC, dashboard_id DESC
                LIMIT ?
                """.prependIndent("\n"),
            )
        }
        return jdbc.queryMany(
            sql,
            { statement ->
                var index = 1
                statement.setUuid(index++, workspaceId.value)
                if (cursor != null) {
                    statement.setInstant(index++, cursor.updatedAt)
                    statement.setUuid(index++, cursor.dashboardId.value)
                }
                statement.setInt(index, limit)
            },
        ) { row ->
            DashboardListItem(
                id = DashboardId(row.getUuid("dashboard_id")),
                workspaceId = WorkspaceId(row.getUuid("workspace_id")),
                title = row.getString("title"),
                description = row.getString("description"),
                updatedAt = row.getInstant("updated_at"),
            )
        }
    }

    override fun insert(dashboard: Dashboard): Boolean {
        val inserted = jdbc.update(
            """
            INSERT INTO dashboard.dashboards (dashboard_id, workspace_id, definition, created_at, updated_at, updated_by, credential_surface, agent_client_id)
            VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            ON CONFLICT (dashboard_id) DO NOTHING
            """.trimIndent(),
        ) { statement ->
            statement.setUuid(1, dashboard.id.value)
            statement.setUuid(2, dashboard.workspaceId.value)
            statement.setString(3, dashboard.definition.document.toString())
            statement.setInstant(4, dashboard.createdAt)
            statement.setInstant(5, dashboard.updatedAt)
            statement.setString(6, dashboard.updatedBy)
            statement.setString(7, dashboard.credentialSurface?.name)
            statement.setString(8, dashboard.agentClientId?.value)
        } == 1
        if (inserted) {
            synchronizeSchedules(dashboard.workspaceId, dashboard.id, null, dashboard.definition, jdbc.databaseNow())
            jdbc.appendDefinitionChange(DashboardOwnerDefinitionChange(Uuid.generateV7(), dashboard.workspaceId, dashboard.id,
                dashboard.definition.meta.title, DashboardDefinitionChangeAction.CREATED, dashboard.definition.document,
                dashboard.updatedBy, dashboard.credentialSurface, dashboard.agentClientId, dashboard.updatedAt, false))
        }
        return inserted
    }

    override fun applyCurrent(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        definition: cc.monomer.dashboard.domain.DashboardDefinition,
        updatedAt: Instant,
        updatedBy: String?,
        credentialSurface: cc.monomer.identity.api.CredentialSurface?,
        agentClientId: cc.monomer.identity.api.AgentClientId?,
    ): Boolean {
        val before = jdbc.lockCurrentDashboard(workspaceId, dashboardId) ?: return false
        val databaseTime = jdbc.databaseNow()
        val applied = jdbc.update(
            """
            UPDATE dashboard.dashboards
            SET definition = ?::jsonb, updated_at = ?, updated_by = ?, credential_surface = ?, agent_client_id = ?
            WHERE dashboard_id = ? AND workspace_id = ?
            """.trimIndent(),
        ) { statement ->
            statement.setString(1, definition.document.toString())
            statement.setInstant(2, updatedAt)
            statement.setString(3, updatedBy)
            statement.setString(4, credentialSurface?.name)
            statement.setString(5, agentClientId?.value)
            statement.setUuid(6, dashboardId.value)
            statement.setUuid(7, workspaceId.value)
        } == 1
        if (applied) {
            synchronizeSchedules(workspaceId, dashboardId, before.definition, definition, databaseTime)
            jdbc.appendDefinitionChange(DashboardOwnerDefinitionChange(Uuid.generateV7(), workspaceId, dashboardId,
                definition.meta.title, DashboardDefinitionChangeAction.APPLIED, definition.document,
                updatedBy, credentialSurface, agentClientId, updatedAt, false))
        }
        return applied
    }

    private fun synchronizeSchedules(workspaceId: WorkspaceId, dashboardId: DashboardId, before: cc.monomer.dashboard.domain.DashboardDefinition?, after: cc.monomer.dashboard.domain.DashboardDefinition, at: Instant) {
        val existing = jdbc.listScheduleCursors(workspaceId, dashboardId).associateBy { it.scheduleKey }
        existing.keys.filter { it !in after.schedules }.forEach { key ->
            jdbc.update("DELETE FROM dashboard.schedule_cursors WHERE workspace_id = ? AND dashboard_id = ? AND schedule_key = ?", {
                it.setUuid(1, workspaceId.value); it.setUuid(2, dashboardId.value); it.setString(3, key)
            })
        }
        after.schedules.forEach { (key, schedule) ->
            val old = before?.document?.get("schedules")?.jsonObject?.get(key)
            val current = after.document.getValue("schedules").jsonObject.getValue(key)
            if (key !in existing || old != current) {
                val last = jdbc.queryOne("SELECT max(scheduled_at) AS last FROM dashboard.schedule_occurrences WHERE workspace_id = ? AND dashboard_id = ? AND schedule_key = ?", {
                    it.setUuid(1, workspaceId.value); it.setUuid(2, dashboardId.value); it.setString(3, key)
                }, { it.getTimestamp("last")?.toInstant() })
                val next = schedule.nextAfter(if (last != null && last > at) last else at)
                jdbc.update("""INSERT INTO dashboard.schedule_cursors (workspace_id, dashboard_id, schedule_key, next_scheduled_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT (workspace_id, dashboard_id, schedule_key) DO UPDATE SET next_scheduled_at = EXCLUDED.next_scheduled_at""", {
                    it.setUuid(1, workspaceId.value); it.setUuid(2, dashboardId.value); it.setString(3, key); it.setInstant(4, next)
                })
            }
        }
    }

    private fun decodeListSchedules(raw: String?): List<DashboardListSchedule> =
        raw?.let { dashboardRowJson.parseToJsonElement(it).jsonObject }.orEmpty().entries
            .sortedBy { it.key }
            .map { (key, value) ->
                val schedule = value.jsonObject
                DashboardListSchedule(
                    key = key,
                    cron = schedule.getValue("cron").jsonPrimitive.content,
                    timeZone = schedule.getValue("timeZone").jsonPrimitive.content,
                )
            }
}
