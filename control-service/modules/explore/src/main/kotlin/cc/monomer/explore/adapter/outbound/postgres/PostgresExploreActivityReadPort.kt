@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.explore.adapter.outbound.postgres

import cc.monomer.connector.api.ConnectorId
import cc.monomer.explore.api.ExploreActivityReadPort
import cc.monomer.explore.api.ExploreRunFailureCode
import cc.monomer.explore.api.ExploreRunKind
import cc.monomer.explore.api.ExploreRunState
import cc.monomer.explore.application.ExploreTransactionPort
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronizationManager

class PostgresExploreActivityReadPort(
    private val dataSource: DataSource,
    private val activityReadTransactionPort: ExploreTransactionPort,
    private val statementTimeout: Duration,
    private val lockTimeout: Duration,
) : ExploreActivityReadPort {
    init {
        require(statementTimeout.toMillis() > 0)
        require(lockTimeout.toMillis() > 0 && lockTimeout <= statementTimeout)
    }

    override suspend fun readActivity(
        workspaceId: WorkspaceId,
        live: Boolean,
        before: Instant?,
        beforeId: Uuid?,
        actorId: PrincipalId?,
        credentialSurface: cc.monomer.identity.api.CredentialSurface?,
        agentClientId: cc.monomer.identity.api.AgentClientId?,
        limit: Int,
        watchedIds: Set<Uuid>,
    ): List<cc.monomer.explore.api.ExploreActivity> {
        require(limit in 1..101 && watchedIds.size <= 100)
        require((before == null) == (beforeId == null))
        val timeColumn = if (live) "created_at" else "terminal_at"
        val clauses = mutableListOf("runs.workspace_id = ?")
        if (watchedIds.isNotEmpty()) clauses += "runs.run_id = ANY (?)"
        else {
            clauses += if (live) "runs.state = 'RUNNING'" else "runs.state <> 'RUNNING'"
            if (before != null) clauses += "(runs.$timeColumn, runs.run_id) < (?, ?)"
        }
        if (actorId != null) clauses += "runs.owner_principal_id = ?"
        if (credentialSurface != null) clauses += "runs.credential_surface = ?"
        if (agentClientId != null) clauses += "runs.agent_client_id = ?"
        return activityRead { connection ->
            connection.prepareStatement(
                "SELECT runs.run_id, runs.kind, runs.state, runs.created_at, runs.terminal_at, runs.owner_principal_id, " +
                    "runs.credential_surface, runs.agent_client_id, runs.connector_id, runs.failure_code, details.query_json FROM explore.runs runs " +
                    "LEFT JOIN explore.semantic_run_details details ON details.run_id = runs.run_id AND details.workspace_id = runs.workspace_id " +
                    "WHERE " + clauses.joinToString(" AND ") +
                    " ORDER BY runs.$timeColumn DESC, runs.run_id DESC LIMIT ?",
            ).use { statement ->
                var index = 1
                statement.setObject(index++, workspaceId.value.toJavaUuid())
                if (watchedIds.isNotEmpty()) statement.setArray(index++, connection.createArrayOf("uuid", watchedIds.map { it.toJavaUuid() }.toTypedArray()))
                else if (before != null) {
                    statement.setInstant(index++, before)
                    statement.setObject(index++, beforeId!!.toJavaUuid())
                }
                if (actorId != null) statement.setObject(index++, actorId.value.toJavaUuid())
                if (credentialSurface != null) statement.setString(index++, credentialSurface.name)
                if (agentClientId != null) statement.setString(index++, agentClientId.value)
                statement.setInt(index, limit)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(cc.monomer.explore.api.ExploreActivity(
                        id = rows.uuid("run_id"), kind = ExploreRunKind.valueOf(rows.getString("kind")),
                        state = ExploreRunState.valueOf(rows.getString("state")), startedAt = rows.getTimestamp("created_at").toInstant(),
                        terminalAt = rows.getTimestamp("terminal_at")?.toInstant(),
                        actorId = PrincipalId(rows.uuid("owner_principal_id")),
                        credentialSurface = rows.getString("credential_surface")?.let(cc.monomer.identity.api.CredentialSurface::valueOf),
                        agentClientId = rows.getString("agent_client_id")?.let { cc.monomer.identity.api.AgentClientId(it) },
                        connectorId = ConnectorId(rows.uuid("connector_id")),
                        query = rows.getString("query_json")?.let(SemanticQueryIntentJsonCodec::decode),
                        failureCode = rows.getString("failure_code")?.let(ExploreRunFailureCode::valueOf),
                    ))
                } }
            }
        }
    }

    override suspend fun readRecentAgentClients(
        workspaceId: WorkspaceId,
        scanLimit: Int,
    ): List<cc.monomer.identity.api.RecentAgentClient> {
        require(scanLimit in 1..cc.monomer.identity.api.RecentAgentClient.MAX_SCAN)
        return activityRead { connection ->
            connection.prepareStatement(
                "SELECT agent_client_id, max(terminal_at) AS last_seen_at FROM (" +
                    "SELECT agent_client_id, terminal_at FROM explore.runs WHERE workspace_id = ? AND state <> 'RUNNING' " +
                    "ORDER BY terminal_at DESC, run_id DESC LIMIT ?) recent " +
                    "WHERE agent_client_id IS NOT NULL GROUP BY agent_client_id",
            ).use { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setInt(2, scanLimit)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(cc.monomer.identity.api.RecentAgentClient(
                        cc.monomer.identity.api.AgentClientId(rows.getString("agent_client_id")),
                        rows.getTimestamp("last_seen_at").toInstant(),
                    ))
                } }
            }
        }
    }

    private suspend fun <T> activityRead(block: (Connection) -> T): T =
        activityReadTransactionPort.inTransaction {
            check(TransactionSynchronizationManager.isActualTransactionActive()) {
                "Explore activity reads require an active owner transaction"
            }
            check(TransactionSynchronizationManager.hasResource(dataSource)) {
                "Explore activity reads require the exact owner DataSource transaction"
            }
            check(TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                "Explore activity reads require a read-only owner transaction"
            }
            val connection = DataSourceUtils.getConnection(dataSource)
            try {
                check(!connection.autoCommit && connection.isReadOnly) {
                    "Explore activity reads require a non-autocommit read-only owner connection"
                }
                connection.prepareStatement(
                    """
                    SELECT
                        set_config('statement_timeout', ?, true),
                        set_config('lock_timeout', ?, true)
                    """.trimIndent(),
                ).use { statement ->
                    DataSourceUtils.applyTransactionTimeout(statement, dataSource)
                    statement.setString(1, "${statementTimeout.toMillis()}ms")
                    statement.setString(2, "${lockTimeout.toMillis()}ms")
                    statement.execute()
                }
                block(connection)
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource)
            }
        }
}

private fun PreparedStatement.setInstant(index: Int, value: Instant?) {
    if (value == null) setNull(index, Types.TIMESTAMP_WITH_TIMEZONE)
    else setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
}

private fun ResultSet.uuid(column: String): Uuid = checkNotNull(getObject(column, UUID::class.java)?.toKotlinUuid())
