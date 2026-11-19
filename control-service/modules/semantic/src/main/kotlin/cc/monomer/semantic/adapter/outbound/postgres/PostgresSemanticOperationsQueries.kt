package cc.monomer.semantic.adapter.outbound.postgres

import cc.monomer.identity.api.AgentClientId
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.OperationsAccess
import cc.monomer.identity.api.OperationsUnauthenticatedException
import cc.monomer.semantic.api.*
import java.sql.Connection
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

class PostgresSemanticOperationsQueries(
    dataSource: DataSource,
    private val transaction: TransactionOperations,
    private val blockingDispatcher: CoroutineDispatcher,
) : SemanticOperationsQueries {

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

    override suspend fun journeyActivity(access: OperationsAccess, workspaceIds: Set<String>, from: Instant, to: Instant) = read(access) {
        require(from < to && Duration.between(from, to) <= Duration.ofDays(26 * 7))
        val p = mapOf("ids" to uuidArray(workspaceIds), "from" to Timestamp.from(from), "to" to Timestamp.from(to))
        val firstMetrics = jdbc.query(
            "SELECT workspace_id,min(created_at) first_created_at FROM semantic.metrics WHERE workspace_id=ANY(:ids) GROUP BY workspace_id", p,
        ) { r, _ -> r.getString("workspace_id") to r.getTimestamp("first_created_at").toInstant() }.toMap()
        val milestones = jdbc.query(
            """
            SELECT workspace_id,actor_id,
              min(occurred_at) FILTER(WHERE definition_kind='METRIC' AND action='CREATED') first_metric_created_change_at,
              min(occurred_at) FILTER(WHERE credential_surface='AGENT') first_agent_at
            FROM semantic.definition_changes
            WHERE workspace_id=ANY(:ids) AND actor_id IS NOT NULL AND ((definition_kind='METRIC' AND action='CREATED') OR credential_surface='AGENT')
            GROUP BY workspace_id,actor_id
            """.trimIndent(), p,
        ) { r, _ ->
            OperationsSemanticActorMilestone(
                r.getString("workspace_id"), r.getString("actor_id"),
                r.getTimestamp("first_metric_created_change_at")?.toInstant(), r.getTimestamp("first_agent_at")?.toInstant(),
            )
        }
        val weeklyDefinitionChanges = jdbc.query(
            """
            SELECT workspace_id,(date_trunc('week',occurred_at AT TIME ZONE 'UTC'))::date week_start,actor_id,credential_surface,agent_client_id,count(*) changes
            FROM semantic.definition_changes
            WHERE workspace_id=ANY(:ids) AND actor_id IS NOT NULL AND occurred_at>=:from AND occurred_at<:to GROUP BY 1,2,3,4,5
            """.trimIndent(), p,
        ) { r, _ ->
            OperationsSemanticWeeklyDefinitionChanges(
                r.getString("workspace_id"), r.getObject("week_start", LocalDate::class.java), r.getString("actor_id"),
                r.getString("credential_surface")?.let(CredentialSurface::valueOf), r.getString("agent_client_id")?.let(::AgentClientId), r.getLong("changes"),
            )
        }
        OperationsSemanticJourneyActivity(firstMetrics, milestones, weeklyDefinitionChanges)
    }

    private fun uuidArray(ids: Collection<String>) = object : AbstractSqlTypeValue() {
        override fun createTypeValue(connection: Connection, sqlType: Int, typeName: String?): Any =
            connection.createArrayOf("uuid", ids.map(UUID::fromString).toTypedArray())
    }
}
