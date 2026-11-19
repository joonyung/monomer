package cc.monomer.dashboard.adapter.outbound.postgres

import cc.monomer.dashboard.api.*
import cc.monomer.identity.api.AgentClientId
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.OperationsAccess
import cc.monomer.identity.api.OperationsUnauthenticatedException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.support.AbstractSqlTypeValue
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager

class PostgresDashboardOperationsQueries(
    dataSource: DataSource,
    private val transaction: TransactionOperations,
    private val blockingDispatcher: CoroutineDispatcher,
) : DashboardOperationsQueries {

    private val jdbc = NamedParameterJdbcTemplate(dataSource)
    init {
        val definition = requireNotNull(transaction as? TransactionDefinition)
        require(definition.isReadOnly && definition.timeout == 5)
    }
    private suspend fun <T> read(access: OperationsAccess, block: () -> T): T = withContext(blockingDispatcher) {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Operations reads require an independent root transaction"
        }
        if (!access.expiresAt.isAfter(Instant.now())) throw OperationsUnauthenticatedException()
        requireNotNull(transaction.execute {
            jdbc.jdbcTemplate.execute("SET LOCAL statement_timeout = '5s'")
            Box(block())
        }).value
    }
    private data class Box<T>(val value: T)

    // QueryRun actor_id is free text: only a canonical UUID names a user. Rows are grouped by the raw text first so the
    // pattern check and cast run once per group rather than once per run.
    private val canonicalActor =
        "CASE WHEN btrim(actor_text) ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN btrim(actor_text)::uuid END"

    override suspend fun journeyActivity(access: OperationsAccess, workspaceIds: Set<String>, from: Instant, to: Instant) = read(access) {
        require(from < to && Duration.between(from, to) <= Duration.ofDays(26 * 7))
        val p = mapOf("ids" to uuidArray(workspaceIds), "from" to Timestamp.from(from), "to" to Timestamp.from(to))
        val firstDashboards = jdbc.query(
            "SELECT workspace_id,min(created_at) first_created_at FROM dashboard.dashboards WHERE workspace_id=ANY(:ids) GROUP BY workspace_id", p,
        ) { r, _ -> r.getString("workspace_id") to r.getTimestamp("first_created_at").toInstant() }.toMap()
        val milestones = jdbc.query(
            """
            SELECT workspace_id,actor_id,min(first_succeeded_query_run_at) first_succeeded_query_run_at,
              min(first_created_change_at) first_created_change_at,min(first_agent_at) first_agent_at,
              min(first_agent_query_run_at) first_agent_query_run_at
            FROM (
              SELECT workspace_id,$canonicalActor actor_id,first_succeeded_query_run_at,NULL::timestamptz first_created_change_at,first_agent_at,
                first_agent_at first_agent_query_run_at
              FROM (
                SELECT workspace_id,actor_id actor_text,min(created_at) FILTER(WHERE state='SUCCEEDED') first_succeeded_query_run_at,
                  min(created_at) FILTER(WHERE credential_surface='AGENT') first_agent_at
                FROM dashboard.query_runs
                WHERE workspace_id=ANY(:ids) AND actor_id IS NOT NULL AND (state='SUCCEEDED' OR credential_surface='AGENT')
                GROUP BY workspace_id,actor_id
              ) query_run_actors
              UNION ALL
              SELECT workspace_id,actor_id,NULL::timestamptz,min(occurred_at) FILTER(WHERE action='CREATED'),
                min(occurred_at) FILTER(WHERE credential_surface='AGENT'),NULL::timestamptz
              FROM dashboard.definition_changes
              WHERE workspace_id=ANY(:ids) AND actor_id IS NOT NULL AND (action='CREATED' OR credential_surface='AGENT')
              GROUP BY workspace_id,actor_id
            ) actor_milestones WHERE actor_id IS NOT NULL GROUP BY workspace_id,actor_id
            """.trimIndent(), p,
        ) { r, _ ->
            OperationsDashboardActorMilestone(
                r.getString("workspace_id"), r.getString("actor_id"), r.getTimestamp("first_succeeded_query_run_at")?.toInstant(),
                r.getTimestamp("first_created_change_at")?.toInstant(), r.getTimestamp("first_agent_at")?.toInstant(),
                r.getTimestamp("first_agent_query_run_at")?.toInstant(),
            )
        }
        val weeklyQueryRuns = jdbc.query(
            """
            SELECT workspace_id,week_start,actor_id,credential_surface,agent_client_id,sum(runs) runs,sum(succeeded_runs) succeeded_runs
            FROM (
              SELECT workspace_id,week_start,$canonicalActor actor_id,credential_surface,agent_client_id,runs,succeeded_runs
              FROM (
                SELECT workspace_id,(date_trunc('week',created_at AT TIME ZONE 'UTC'))::date week_start,actor_id actor_text,credential_surface,agent_client_id,
                  count(*) runs,count(*) FILTER(WHERE state='SUCCEEDED') succeeded_runs
                FROM dashboard.query_runs
                WHERE workspace_id=ANY(:ids) AND actor_id IS NOT NULL AND created_at>=:from AND created_at<:to
                GROUP BY 1,2,3,4,5
              ) query_run_weeks
            ) attributed_query_run_weeks WHERE actor_id IS NOT NULL GROUP BY 1,2,3,4,5
            """.trimIndent(), p,
        ) { r, _ ->
            OperationsDashboardWeeklyQueryRuns(
                r.getString("workspace_id"), r.getObject("week_start", LocalDate::class.java), r.getString("actor_id"), surface(r), client(r),
                r.getLong("runs"), r.getLong("succeeded_runs"),
            )
        }
        val weeklyDefinitionChanges = jdbc.query(
            """
            SELECT workspace_id,(date_trunc('week',occurred_at AT TIME ZONE 'UTC'))::date week_start,actor_id,credential_surface,agent_client_id,count(*) changes
            FROM dashboard.definition_changes
            WHERE workspace_id=ANY(:ids) AND actor_id IS NOT NULL AND occurred_at>=:from AND occurred_at<:to GROUP BY 1,2,3,4,5
            """.trimIndent(), p,
        ) { r, _ ->
            OperationsDashboardWeeklyDefinitionChanges(
                r.getString("workspace_id"), r.getObject("week_start", LocalDate::class.java), r.getString("actor_id"), surface(r), client(r), r.getLong("changes"),
            )
        }
        OperationsDashboardJourneyActivity(firstDashboards, milestones, weeklyQueryRuns, weeklyDefinitionChanges)
    }

    private fun surface(r: ResultSet) = r.getString("credential_surface")?.let(CredentialSurface::valueOf)
    private fun client(r: ResultSet) = r.getString("agent_client_id")?.let(::AgentClientId)
    private fun uuidArray(ids: Collection<String>) = object : AbstractSqlTypeValue() {
        override fun createTypeValue(connection: Connection, sqlType: Int, typeName: String?): Any =
            connection.createArrayOf("uuid", ids.map(UUID::fromString).toTypedArray())
    }
}
