@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.adapter.outbound.postgres

import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardSessionId
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.dashboard.application.DashboardCurrentQueryRun
import cc.monomer.dashboard.application.DashboardSessionPageCursor
import cc.monomer.dashboard.application.DashboardSessionRepository
import cc.monomer.dashboard.application.DashboardSessionSummary
import cc.monomer.dashboard.domain.DashboardSession
import cc.monomer.dashboard.domain.QueryRunState
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import javax.sql.DataSource

class PostgresDashboardSessionRepository(
    dataSource: DataSource,
) : DashboardSessionRepository {
    private val jdbc = PostgresDashboardJdbc(dataSource)

    override fun find(workspaceId: WorkspaceId, sessionId: DashboardSessionId): DashboardSession? =
        jdbc.queryOne(
            """
            SELECT session_id, workspace_id, dashboard_id, definition_snapshot,
                   parameter_bindings, created_at, updated_at
            FROM dashboard.sessions
            WHERE workspace_id = ? AND session_id = ?
            """.trimIndent(),
            { statement ->
                statement.setUuid(1, workspaceId.value)
                statement.setUuid(2, sessionId.value)
            },
            ::readSession,
        )

    override fun find(workspaceId: WorkspaceId, dashboardId: DashboardId, sessionId: DashboardSessionId): DashboardSession? =
        findSession(workspaceId, dashboardId, sessionId, null)

    override fun findInitial(workspaceId: WorkspaceId, dashboardId: DashboardId, sessionId: DashboardSessionId, deadline: Instant): DashboardSession? =
        findSession(workspaceId, dashboardId, sessionId, deadline)

    private fun findSession(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        sessionId: DashboardSessionId,
        deadline: Instant?,
    ): DashboardSession? =
        jdbc.queryOne(deadline,
            """
            SELECT session_id, workspace_id, dashboard_id, definition_snapshot,
                   parameter_bindings, created_at, updated_at
            FROM dashboard.sessions
            WHERE workspace_id = ? AND dashboard_id = ? AND session_id = ?
            """.trimIndent(),
            { statement ->
                statement.setUuid(1, workspaceId.value)
                statement.setUuid(2, dashboardId.value)
                statement.setUuid(3, sessionId.value)
            },
            ::readSession,
        )

    override fun findForUpdate(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        sessionId: DashboardSessionId,
    ): DashboardSession? =
        jdbc.queryOne(
            """
            SELECT session_id, workspace_id, dashboard_id, definition_snapshot,
                   parameter_bindings, created_at, updated_at
            FROM dashboard.sessions
            WHERE workspace_id = ? AND dashboard_id = ? AND session_id = ?
            FOR UPDATE
            """.trimIndent(),
            { statement ->
                statement.setUuid(1, workspaceId.value)
                statement.setUuid(2, dashboardId.value)
                statement.setUuid(3, sessionId.value)
            },
            ::readSession,
        )

    override fun findPage(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        cursor: DashboardSessionPageCursor?,
        limit: Int,
    ): List<DashboardSessionSummary> {
        require(limit > 0)
        require(cursor == null || cursor.dashboardId == dashboardId) { "cursor belongs to another Dashboard" }
        val sql = buildString {
            append(
                """
                SELECT session_id, dashboard_id, parameter_bindings, created_at, updated_at,
                       (SELECT MAX(run.created_at) FROM dashboard.query_runs run
                        WHERE run.workspace_id = session.workspace_id
                          AND run.dashboard_id = session.dashboard_id
                          AND run.viewer_session_id = session.session_id) AS last_query_run_at,
                       (SELECT COALESCE(jsonb_agg(jsonb_build_object(
                           'name', run.query_name, 'id', run.query_run_id)
                           ORDER BY run.created_at DESC, run.query_run_id DESC), '[]'::jsonb)
                        FROM dashboard.query_runs run
                        WHERE run.workspace_id = session.workspace_id
                          AND run.dashboard_id = session.dashboard_id
                          AND run.viewer_session_id = session.session_id
                          AND run.state = 'RUNNING') AS running_query_runs
                FROM dashboard.sessions session WHERE workspace_id = ? AND dashboard_id = ?
                """.trimIndent(),
            )
            if (cursor != null) append(" AND (created_at, session_id) < (?, ?)")
            append(" ORDER BY created_at DESC, session_id DESC LIMIT ?")
        }
        return jdbc.queryMany(sql, { statement ->
            var index = 1
            statement.setUuid(index++, workspaceId.value)
            statement.setUuid(index++, dashboardId.value)
            if (cursor != null) {
                statement.setInstant(index++, cursor.createdAt)
                statement.setUuid(index++, cursor.sessionId.value)
            }
            statement.setInt(index, limit)
        }) { row ->
            val sessionId = DashboardSessionId(row.getUuid("session_id"))
            DashboardSessionSummary(
                sessionId, dashboardId, dashboardRowJson.parseToJsonElement(row.getString("parameter_bindings")).jsonObject,
                dashboardRowJson.parseToJsonElement(row.getString("running_query_runs")).jsonArray.map { element ->
                    val run = element.jsonObject
                    DashboardCurrentQueryRun(run.getValue("name").jsonPrimitive.content,
                        QueryRunId(kotlin.uuid.Uuid.parse(run.getValue("id").jsonPrimitive.content)), QueryRunState.RUNNING)
                },
                row.getInstant("created_at"), row.getInstant("updated_at"), row.getTimestamp("last_query_run_at")?.toInstant(),
            )
        }
    }

    override fun insert(session: DashboardSession): Boolean = jdbc.update(
        """
        INSERT INTO dashboard.sessions (session_id, workspace_id, dashboard_id, definition_snapshot, parameter_bindings, created_at, updated_at)
        VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?) ON CONFLICT (session_id) DO NOTHING
        """.trimIndent(),
    ) { statement ->
        statement.setUuid(1, session.id.value)
        statement.setUuid(2, session.workspaceId.value)
        statement.setUuid(3, session.dashboardId.value)
        statement.setString(4, session.definition.document.toString())
        statement.setString(5, encodeObject(session.parameterBindings))
        statement.setInstant(6, session.createdAt)
        statement.setInstant(7, session.updatedAt)
    } == 1
}
