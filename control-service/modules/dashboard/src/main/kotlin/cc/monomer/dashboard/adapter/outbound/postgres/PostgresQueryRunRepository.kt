@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.adapter.outbound.postgres

import cc.monomer.connector.api.ConnectorId
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardSessionId
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.dashboard.application.DashboardQueryRunPageCursor
import cc.monomer.dashboard.application.EquivalentQueryRunLookup
import cc.monomer.dashboard.application.QueryRunRepository
import cc.monomer.dashboard.domain.DashboardQueryType
import cc.monomer.dashboard.domain.DashboardResolvedQuery
import cc.monomer.dashboard.domain.DashboardResolvedQueryCodec
import cc.monomer.dashboard.domain.QueryRun
import cc.monomer.dashboard.domain.QueryRunState
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import javax.sql.DataSource
import kotlin.uuid.toJavaUuid

class PostgresQueryRunRepository(
    dataSource: DataSource,
) : QueryRunRepository {
    private val jdbc = PostgresDashboardJdbc(dataSource)

    override fun find(queryRunId: QueryRunId): QueryRun? =
        jdbc.queryOne(
            """
            SELECT query_run_id, workspace_id, dashboard_id, viewer_session_id, query_name, connector_id,
                   query_type, resolved_query, rendered_sql, output_column_names, output_column_displays, reporting_time_zone, state, result_manifest, failure_code,
                   failure_message, created_at, updated_at, actor_id, credential_surface, agent_client_id
            FROM dashboard.query_runs
            WHERE query_run_id = ?
            """.trimIndent(),
            { statement -> statement.setUuid(1, queryRunId.value) },
            ::readQueryRun,
        )

    override fun find(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        viewerSessionId: DashboardSessionId?,
        queryRunId: QueryRunId,
    ): QueryRun? =
        jdbc.queryOne(
            """
            SELECT query_run_id, workspace_id, dashboard_id, viewer_session_id, query_name, connector_id,
                   query_type, resolved_query, rendered_sql, output_column_names, output_column_displays, reporting_time_zone, state, result_manifest, failure_code,
                   failure_message, created_at, updated_at, actor_id, credential_surface, agent_client_id
            FROM dashboard.query_runs
            WHERE workspace_id = ? AND dashboard_id = ?
              AND viewer_session_id IS NOT DISTINCT FROM ?::uuid
              AND query_run_id = ?
            """.trimIndent(),
            { statement ->
                statement.setUuid(1, workspaceId.value)
                statement.setUuid(2, dashboardId.value)
                statement.setNullableUuid(3, viewerSessionId?.value)
                statement.setUuid(4, queryRunId.value)
            },
            ::readQueryRun,
        )

    override fun findEquivalent(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        viewerSessionId: DashboardSessionId?,
        queryName: String,
        connectorId: ConnectorId,
        resolvedQuery: DashboardResolvedQuery,
        renderedSql: String,
        outputColumnNames: List<String>,
        allowedStates: Set<QueryRunState>,
    ): QueryRun? =
        jdbc.queryOne(
            """
            SELECT query_run_id, workspace_id, dashboard_id, viewer_session_id, query_name, connector_id,
                   query_type, resolved_query, rendered_sql, output_column_names, output_column_displays, reporting_time_zone, state, result_manifest, failure_code,
                   failure_message, created_at, updated_at, actor_id, credential_surface, agent_client_id
            FROM dashboard.query_runs
            WHERE workspace_id = ? AND dashboard_id = ? AND viewer_session_id IS NOT DISTINCT FROM ?::uuid
              AND query_name = ? AND connector_id = ?
              AND state = ANY (?::text[]) AND resolved_query = ?::jsonb
              AND rendered_sql = ? AND output_column_names = ?::jsonb AND query_type = ?
            ORDER BY CASE WHEN state = 'SUCCEEDED' THEN 0 ELSE 1 END, created_at DESC, query_run_id DESC
            LIMIT 1
            """.trimIndent(),
            { statement ->
                statement.setUuid(1, workspaceId.value)
                statement.setUuid(2, dashboardId.value)
                statement.setNullableUuid(3, viewerSessionId?.value)
                statement.setString(4, queryName)
                statement.setUuid(5, connectorId.value)
                statement.setArray(6, statement.connection.createArrayOf("text", allowedStates.map { it.name }.toTypedArray()))
                statement.setString(7, DashboardResolvedQueryCodec.encode(resolvedQuery).toString())
                statement.setString(8, renderedSql)
                statement.setString(9, JsonArray(outputColumnNames.map(::JsonPrimitive)).toString())
                statement.setString(10, resolvedQuery.type.name)
            },
            ::readQueryRun,
        )

    override fun findEquivalentRuns(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        viewerSessionId: DashboardSessionId?,
        lookups: List<EquivalentQueryRunLookup>,
        allowedStates: Set<QueryRunState>,
        deadline: Instant,
    ): List<QueryRun?> {
        if (lookups.isEmpty()) return emptyList()
        val runsByPosition = jdbc.queryManyWithin(deadline,
            """
            SELECT lookup.lookup_position, run.*
            FROM unnest(?::text[], ?::uuid[], ?::text[], ?::text[], ?::text[], ?::text[])
                 WITH ORDINALITY AS lookup(query_name, connector_id, resolved_query, rendered_sql, output_column_names, query_type, lookup_position)
            CROSS JOIN LATERAL (
                SELECT query_run_id, workspace_id, dashboard_id, viewer_session_id, query_name, connector_id,
                       query_type, resolved_query, rendered_sql, output_column_names, output_column_displays, reporting_time_zone, state, result_manifest, failure_code,
                       failure_message, created_at, updated_at, actor_id, credential_surface, agent_client_id
                FROM dashboard.query_runs
                WHERE workspace_id = ? AND dashboard_id = ? AND viewer_session_id IS NOT DISTINCT FROM ?::uuid
                  AND query_name = lookup.query_name AND connector_id = lookup.connector_id
                  AND state = ANY (?::text[]) AND resolved_query = lookup.resolved_query::jsonb
                  AND rendered_sql = lookup.rendered_sql AND output_column_names = lookup.output_column_names::jsonb AND query_type = lookup.query_type
                ORDER BY CASE WHEN state = 'SUCCEEDED' THEN 0 ELSE 1 END, created_at DESC, query_run_id DESC
                LIMIT 1
            ) run
            ORDER BY lookup.lookup_position
            """.trimIndent(),
            { statement ->
                fun textArray(values: List<String>) = statement.connection.createArrayOf("text", values.toTypedArray())
                statement.setArray(1, textArray(lookups.map { it.queryName }))
                statement.setArray(2, statement.connection.createArrayOf("uuid", lookups.map { it.connectorId.value.toJavaUuid() }.toTypedArray()))
                statement.setArray(3, textArray(lookups.map { DashboardResolvedQueryCodec.encode(it.resolvedQuery).toString() }))
                statement.setArray(4, textArray(lookups.map { it.renderedSql }))
                statement.setArray(5, textArray(lookups.map { lookup -> JsonArray(lookup.outputColumnNames.map(::JsonPrimitive)).toString() }))
                statement.setArray(6, textArray(lookups.map { it.resolvedQuery.type.name }))
                statement.setUuid(7, workspaceId.value)
                statement.setUuid(8, dashboardId.value)
                statement.setNullableUuid(9, viewerSessionId?.value)
                statement.setArray(10, textArray(allowedStates.map { it.name }))
            },
        ) { row -> row.getLong("lookup_position").toInt() to readQueryRun(row) }.toMap()
        return lookups.indices.map { index -> runsByPosition[index + 1] }
    }

    override fun findRecentDraftRuns(workspaceId: WorkspaceId, dashboardId: DashboardId, limit: Int): List<QueryRun> {
        require(limit > 0)
        return jdbc.queryMany(
            """
            SELECT query_run_id, workspace_id, dashboard_id, viewer_session_id, query_name, connector_id,
                   query_type, resolved_query, rendered_sql, output_column_names, output_column_displays, reporting_time_zone, state, result_manifest, failure_code,
                   failure_message, created_at, updated_at, actor_id, credential_surface, agent_client_id
            FROM dashboard.query_runs
            WHERE workspace_id = ? AND dashboard_id = ? AND viewer_session_id IS NULL
            ORDER BY created_at DESC, query_run_id DESC
            LIMIT ?
            """.trimIndent(),
            { statement ->
                statement.setUuid(1, workspaceId.value)
                statement.setUuid(2, dashboardId.value)
                statement.setInt(3, limit)
            },
            ::readQueryRun,
        )
    }

    override fun findDraftPage(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        cursor: DashboardQueryRunPageCursor?,
        limit: Int,
    ): List<QueryRun> {
        require(limit > 0)
        val sql = buildString {
            append(
                """
                SELECT query_run_id, workspace_id, dashboard_id, viewer_session_id, query_name, connector_id,
                       query_type, resolved_query, rendered_sql, output_column_names, output_column_displays, reporting_time_zone, state, result_manifest, failure_code,
                       failure_message, created_at, updated_at, actor_id, credential_surface, agent_client_id
                FROM dashboard.query_runs
                WHERE workspace_id = ? AND dashboard_id = ? AND viewer_session_id IS NULL
                """.trimIndent(),
            )
            if (cursor != null) append("\nAND (created_at, query_run_id) < (?, ?)")
            append(
                """
                ORDER BY created_at DESC, query_run_id DESC
                LIMIT ?
                """.prependIndent("\n"),
            )
        }
        return jdbc.queryMany(
            sql,
            { statement ->
                var index = 1
                statement.setUuid(index++, workspaceId.value)
                statement.setUuid(index++, dashboardId.value)
                if (cursor != null) {
                    statement.setInstant(index++, cursor.createdAt)
                    statement.setUuid(index++, cursor.queryRunId.value)
                }
                statement.setInt(index, limit)
            },
            ::readQueryRun,
        )
    }

    override fun findSessionPage(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        sessionId: DashboardSessionId,
        cursor: DashboardQueryRunPageCursor?,
        limit: Int,
    ): List<QueryRun> {
        require(limit > 0)
        val sql = buildString {
            append(
                """
                SELECT query_run_id, workspace_id, dashboard_id, viewer_session_id, query_name, connector_id,
                       query_type, resolved_query, rendered_sql, output_column_names, output_column_displays, reporting_time_zone, state, result_manifest, failure_code,
                       failure_message, created_at, updated_at, actor_id, credential_surface, agent_client_id
                FROM dashboard.query_runs
                WHERE workspace_id = ? AND dashboard_id = ? AND viewer_session_id = ?
                """.trimIndent(),
            )
            if (cursor != null) append("\nAND (created_at, query_run_id) < (?, ?)")
            append('\n')
            append(
                """
                ORDER BY created_at DESC, query_run_id DESC
                LIMIT ?
                """.trimIndent(),
            )
        }
        return jdbc.queryMany(
            sql,
            { statement ->
                var index = 1
                statement.setUuid(index++, workspaceId.value)
                statement.setUuid(index++, dashboardId.value)
                statement.setUuid(index++, sessionId.value)
                if (cursor != null) {
                    statement.setInstant(index++, cursor.createdAt)
                    statement.setUuid(index++, cursor.queryRunId.value)
                }
                statement.setInt(index, limit)
            },
            ::readQueryRun,
        )
    }

    override fun findRunningSessionRuns(workspaceId: WorkspaceId, dashboardId: DashboardId, sessionId: DashboardSessionId): List<QueryRun> = jdbc.queryMany(
        """
        SELECT query_run_id, workspace_id, dashboard_id, viewer_session_id, query_name, connector_id,
               query_type, resolved_query, rendered_sql, output_column_names, output_column_displays, reporting_time_zone, state, result_manifest, failure_code,
               failure_message, created_at, updated_at, actor_id, credential_surface, agent_client_id
        FROM dashboard.query_runs
        WHERE workspace_id = ? AND dashboard_id = ? AND viewer_session_id = ? AND state = 'RUNNING'
        ORDER BY created_at DESC, query_run_id DESC
        """.trimIndent(),
        { statement ->
            statement.setUuid(1, workspaceId.value)
            statement.setUuid(2, dashboardId.value)
            statement.setUuid(3, sessionId.value)
        }, ::readQueryRun,
    )

    override fun insert(queryRun: QueryRun): Boolean =
        jdbc.update(
            """
            INSERT INTO dashboard.query_runs (
                query_run_id, workspace_id, dashboard_id, viewer_session_id, query_name, connector_id, resolved_query, rendered_sql,
                output_column_names, state, result_manifest, failure_code, failure_message, created_at, updated_at, actor_id, credential_surface, agent_client_id, query_type, output_column_displays, reporting_time_zone
            ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (query_run_id) DO NOTHING
            """.trimIndent(),
        ) { statement -> bindQueryRun(statement, queryRun) } == 1

    override fun update(expectedUpdatedAt: Instant, queryRun: QueryRun): Boolean =
        jdbc.update(
            """
            UPDATE dashboard.query_runs
            SET state = ?, result_manifest = ?::jsonb, failure_code = ?, failure_message = ?, updated_at = ?
            WHERE query_run_id = ? AND updated_at = ?
            """.trimIndent(),
        ) { statement ->
            statement.setString(1, queryRun.state.name)
            statement.setString(2, queryRun.result?.let(::encodeManifest))
            statement.setString(3, queryRun.failureCode)
            statement.setString(4, queryRun.failureMessage)
            statement.setInstant(5, queryRun.updatedAt)
            statement.setUuid(6, queryRun.id.value)
            statement.setInstant(7, expectedUpdatedAt)
        } == 1

    private fun readQueryRun(row: ResultSet): QueryRun {
        val state = QueryRunState.valueOf(row.getString("state"))
        return QueryRun(
            id = QueryRunId(row.getUuid("query_run_id")),
            workspaceId = WorkspaceId(row.getUuid("workspace_id")),
            dashboardId = DashboardId(row.getUuid("dashboard_id")),
            viewerSessionId = row.getNullableUuid("viewer_session_id")?.let(::DashboardSessionId),
            queryName = row.getString("query_name"),
            queryType = DashboardQueryType.valueOf(row.getString("query_type")),
            connectorId = ConnectorId(row.getUuid("connector_id")),
            resolvedQuery = DashboardResolvedQueryCodec.decode(DashboardQueryType.valueOf(row.getString("query_type")), dashboardRowJson.parseToJsonElement(row.getString("resolved_query")).jsonObject),
            renderedSql = row.getString("rendered_sql"),

            outputColumnNames = dashboardRowJson.parseToJsonElement(row.getString("output_column_names"))
                .jsonArray
                .map { it.jsonPrimitive.content },
            state = state,
            result = row.getString("result_manifest")?.let(::decodeManifest),
            failureCode = row.getString("failure_code"),
            failureMessage = row.getString("failure_message"),
            createdAt = row.getInstant("created_at"),
            updatedAt = row.getInstant("updated_at"),
            actorId = row.getString("actor_id"),
            credentialSurface = row.getString("credential_surface")?.let(cc.monomer.identity.api.CredentialSurface::valueOf),
            agentClientId = row.getString("agent_client_id")?.let { cc.monomer.identity.api.AgentClientId(it) },
            outputColumnDisplays = row.getString("output_column_displays")?.let { cc.monomer.semantic.api.MetricDisplayJsonCodec.decodeColumns(dashboardRowJson.parseToJsonElement(it)) },
            reportingTimeZone = row.getString("reporting_time_zone")?.let(java.time.ZoneId::of),
        )
    }

    private fun bindQueryRun(statement: PreparedStatement, queryRun: QueryRun) {
        statement.setUuid(1, queryRun.id.value)
        statement.setUuid(2, queryRun.workspaceId.value)
        statement.setUuid(3, queryRun.dashboardId.value)
        statement.setNullableUuid(4, queryRun.viewerSessionId?.value)
        statement.setString(5, queryRun.queryName)
        statement.setUuid(6, queryRun.connectorId.value)
        statement.setString(7, DashboardResolvedQueryCodec.encode(queryRun.resolvedQuery).toString())
        statement.setString(8, queryRun.renderedSql)
        statement.setString(9, JsonArray(queryRun.outputColumnNames.map(::JsonPrimitive)).toString())
        statement.setString(10, queryRun.state.name)
        statement.setString(11, queryRun.result?.let(::encodeManifest))
        statement.setString(12, queryRun.failureCode)
        statement.setString(13, queryRun.failureMessage)
        statement.setInstant(14, queryRun.createdAt)
        statement.setInstant(15, queryRun.updatedAt)
        statement.setString(16, queryRun.actorId)
        statement.setString(17, queryRun.credentialSurface?.name)
        statement.setString(18, queryRun.agentClientId?.value)
        statement.setString(19, queryRun.queryType.name)
        statement.setString(20, queryRun.outputColumnDisplays?.let { cc.monomer.semantic.api.MetricDisplayJsonCodec.encodeColumns(it).toString() })
        statement.setString(21, queryRun.reportingTimeZone?.id)
    }
}
