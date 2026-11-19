@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.adapter.outbound.postgres

import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.dashboard.application.DashboardDraftRepository
import cc.monomer.dashboard.application.DashboardDraftStatus
import cc.monomer.dashboard.application.DashboardDraftTargetQueryRun
import cc.monomer.dashboard.domain.DashboardDraft
import cc.monomer.dashboard.domain.QueryRunState
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import javax.sql.DataSource
import kotlin.uuid.Uuid

class PostgresDashboardDraftRepository(
    dataSource: DataSource,
) : DashboardDraftRepository {
    private val jdbc = PostgresDashboardJdbc(dataSource)

    override fun findDraft(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardDraft? =
        jdbc.queryOne(
            """
            SELECT workspace_id, dashboard_id, definition, parameter_bindings, variables,
                   authoring_revision, target_query_runs, ready_results, created_at, updated_at, last_edited_at, last_edited_by, last_edit_surface, last_edit_agent_client_id
            FROM dashboard.drafts
            WHERE workspace_id = ? AND dashboard_id = ?
            """.trimIndent(),
            { statement ->
                statement.setUuid(1, workspaceId.value)
                statement.setUuid(2, dashboardId.value)
            },
            ::readDraft,
        )

    override fun findDraftForUpdate(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardDraft? =
        jdbc.findDraftForUpdate(workspaceId, dashboardId)

    override fun findDraftStatus(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardDraftStatus? =
        jdbc.queryOne(
            """
            SELECT d.authoring_revision, d.target_query_runs, d.ready_results,
                   COALESCE((
                       SELECT jsonb_object_agg(q.query_run_id::text, q.state)
                       FROM dashboard.query_runs q
                       WHERE q.workspace_id = d.workspace_id AND q.dashboard_id = d.dashboard_id
                         AND q.viewer_session_id IS NULL
                         AND q.query_run_id IN (SELECT target.value::uuid FROM jsonb_each_text(d.target_query_runs) AS target)
                   ), '{}'::jsonb) AS target_query_run_states
            FROM dashboard.drafts d
            WHERE d.workspace_id = ? AND d.dashboard_id = ?
            """.trimIndent(),
            { statement ->
                statement.setUuid(1, workspaceId.value)
                statement.setUuid(2, dashboardId.value)
            },
        ) { row ->
            val states = dashboardRowJson.parseToJsonElement(row.getString("target_query_run_states")).jsonObject.entries
                .associate { (queryRunId, state) -> Uuid.parse(queryRunId) to QueryRunState.valueOf(state.jsonPrimitive.content) }
            DashboardDraftStatus(
                authoringRevision = row.getLong("authoring_revision"),
                targetQueryRuns = decodeIdMap(row.getString("target_query_runs"), ::QueryRunId).mapValues { (queryName, queryRunId) ->
                    DashboardDraftTargetQueryRun(queryRunId, checkNotNull(states[queryRunId.value]) { "Draft target QueryRun is missing for $queryName" })
                },
                readyResults = decodeDraftReadyResults(row.getString("ready_results")),
            )
        }

    override fun insert(draft: DashboardDraft): Boolean =
        jdbc.update(
            """
            INSERT INTO dashboard.drafts (
                workspace_id, dashboard_id, definition, parameter_bindings, variables,
                authoring_revision, target_query_runs, ready_results, created_at, updated_at, last_edited_at, last_edited_by, last_edit_surface, last_edit_agent_client_id
            ) VALUES (?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (workspace_id, dashboard_id) DO NOTHING
            """.trimIndent(),
        ) { statement ->
            statement.setUuid(1, draft.workspaceId.value)
            statement.setUuid(2, draft.dashboardId.value)
            statement.setString(3, draft.definition.document.toString())
            statement.setString(4, encodeObject(draft.parameterBindings))
            statement.setString(5, encodeObject(draft.variables))
            statement.setLong(6, draft.authoringRevision)
            statement.setString(7, encodeIdMap(draft.targetQueryRuns))
            statement.setString(8, encodeDraftReadyResults(draft.readyResults))
            statement.setInstant(9, draft.createdAt)
            statement.setInstant(10, draft.updatedAt)
            statement.setTimestamp(11, draft.lastEditedAt?.let(java.sql.Timestamp::from))
            statement.setString(12, draft.lastEditedBy)
            statement.setString(13, draft.lastEditSurface?.name)
            statement.setString(14, draft.lastEditAgentClientId?.value)
        } == 1

    override fun update(expectedUpdatedAt: Instant, draft: DashboardDraft): Boolean =
        jdbc.update(
            """
            UPDATE dashboard.drafts
            SET definition = ?::jsonb, parameter_bindings = ?::jsonb, variables = ?::jsonb,
                authoring_revision = ?, target_query_runs = ?::jsonb, ready_results = ?::jsonb,
                updated_at = ?, last_edited_at = ?, last_edited_by = ?, last_edit_surface = ?, last_edit_agent_client_id = ?
            WHERE workspace_id = ? AND dashboard_id = ? AND updated_at = ?
            """.trimIndent(),
        ) { statement ->
            statement.setString(1, draft.definition.document.toString())
            statement.setString(2, encodeObject(draft.parameterBindings))
            statement.setString(3, encodeObject(draft.variables))
            statement.setLong(4, draft.authoringRevision)
            statement.setString(5, encodeIdMap(draft.targetQueryRuns))
            statement.setString(6, encodeDraftReadyResults(draft.readyResults))
            statement.setInstant(7, draft.updatedAt)
            statement.setTimestamp(8, draft.lastEditedAt?.let(java.sql.Timestamp::from))
            statement.setString(9, draft.lastEditedBy)
            statement.setString(10, draft.lastEditSurface?.name)
            statement.setString(11, draft.lastEditAgentClientId?.value)
            statement.setUuid(12, draft.workspaceId.value)
            statement.setUuid(13, draft.dashboardId.value)
            statement.setInstant(14, expectedUpdatedAt)
        } == 1

    override fun updateAtAuthoringRevision(expectedRevision: Long, draft: DashboardDraft): Boolean =
        jdbc.update(
            """
            UPDATE dashboard.drafts
            SET definition = ?::jsonb, parameter_bindings = ?::jsonb, variables = ?::jsonb,
                authoring_revision = ?, target_query_runs = ?::jsonb, ready_results = ?::jsonb,
                updated_at = ?, last_edited_at = ?, last_edited_by = ?, last_edit_surface = ?, last_edit_agent_client_id = ?
            WHERE workspace_id = ? AND dashboard_id = ? AND authoring_revision = ?
            """.trimIndent(),
        ) { statement ->
            statement.setString(1, draft.definition.document.toString())
            statement.setString(2, encodeObject(draft.parameterBindings))
            statement.setString(3, encodeObject(draft.variables))
            statement.setLong(4, draft.authoringRevision)
            statement.setString(5, encodeIdMap(draft.targetQueryRuns))
            statement.setString(6, encodeDraftReadyResults(draft.readyResults))
            statement.setInstant(7, draft.updatedAt)
            statement.setTimestamp(8, draft.lastEditedAt?.let(java.sql.Timestamp::from))
            statement.setString(9, draft.lastEditedBy)
            statement.setString(10, draft.lastEditSurface?.name)
            statement.setString(11, draft.lastEditAgentClientId?.value)
            statement.setUuid(12, draft.workspaceId.value)
            statement.setUuid(13, draft.dashboardId.value)
            statement.setLong(14, expectedRevision)
        } == 1
}
