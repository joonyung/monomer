@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.adapter.outbound.postgres

import cc.monomer.catalog.api.CatalogActivity
import cc.monomer.catalog.api.CatalogActivityReadPort
import cc.monomer.catalog.application.CatalogTransactionPort
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.AgentClientId
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.RecentAgentClient
import cc.monomer.identity.api.WorkspaceId
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

class PostgresCatalogActivityReadPort(
    dataSource: DataSource,
    private val transactions: CatalogTransactionPort,
) : CatalogActivityReadPort {
    private val jdbc = CatalogOwnerJdbc(dataSource)

    override suspend fun readActivity(
        workspaceId: WorkspaceId,
        refresh: Boolean,
        live: Boolean,
        before: Instant?,
        beforeId: Uuid?,
        actorId: PrincipalId?,
        credentialSurface: CredentialSurface?,
        agentClientId: AgentClientId?,
        limit: Int,
        watchedIds: Set<Uuid>,
    ): List<CatalogActivity> = transactions.inReadOnlyTransaction {
        require(limit in 1..101 && watchedIds.size <= 100)
        require((before == null) == (beforeId == null))
        val table = if (refresh) "list_refresh_jobs" else "ingest_jobs"
        val targetColumns = if (refresh) "catalog_name, schema_name, failure_code" else "entry_catalog_name AS catalog_name, NULL::text AS schema_name, " +
            "(SELECT tasks.failure_code FROM catalog.ingest_tasks tasks WHERE tasks.job_id = ingest_jobs.job_id AND tasks.state = 'FAILED' " +
            "ORDER BY tasks.parent_task_id IS NOT NULL, tasks.terminal_at, tasks.task_id LIMIT 1) AS failure_code"
        val timeColumn = if (live) "requested_at" else "terminal_at"
        val clauses = mutableListOf("workspace_id = ?")
        if (watchedIds.isNotEmpty()) clauses += "job_id = ANY (?)"
        else {
            clauses += if (live) "terminal_at IS NULL" else "terminal_at IS NOT NULL"
            if (before != null) clauses += "($timeColumn, job_id) < (?, ?)"
        }
        if (actorId != null) clauses += "requested_by = ?"
        if (credentialSurface != null) clauses += "credential_surface = ?"
        if (agentClientId != null) clauses += "agent_client_id = ?"
        jdbc.queryMany(
            "SELECT job_id, connector_id, $targetColumns, state, requested_at, terminal_at, requested_by, credential_surface, agent_client_id " +
                "FROM catalog.$table WHERE " + clauses.joinToString(" AND ") +
                " ORDER BY $timeColumn DESC, job_id DESC LIMIT ?",
            { statement ->
                var index = 1
                statement.setObject(index++, workspaceId.value.toJavaUuid())
                if (watchedIds.isNotEmpty()) statement.setArray(index++, statement.connection.createArrayOf("uuid", watchedIds.map { it.toJavaUuid() }.toTypedArray()))
                else if (before != null) {
                    statement.setTimestamp(index++, Timestamp.from(before))
                    statement.setObject(index++, beforeId!!.toJavaUuid())
                }
                if (actorId != null) statement.setObject(index++, actorId.value.toJavaUuid())
                if (credentialSurface != null) statement.setString(index++, credentialSurface.name)
                if (agentClientId != null) statement.setString(index++, agentClientId.value)
                statement.setInt(index, limit)
            },
        ) { row -> CatalogActivity(
            id = row.uuid("job_id"), connectorId = ConnectorId(row.uuid("connector_id")),
            catalogName = row.getString("catalog_name"), schemaName = row.getString("schema_name"),
            state = row.getString("state"), startedAt = row.getTimestamp("requested_at").toInstant(),
            terminalAt = row.getTimestamp("terminal_at")?.toInstant(), actorId = PrincipalId(row.uuid("requested_by")),
            credentialSurface = row.getString("credential_surface")?.let(CredentialSurface::valueOf),
            agentClientId = row.getString("agent_client_id")?.let { AgentClientId(it) },
            failureCode = row.getString("failure_code"),
        ) }
    }

    override suspend fun readRecentAgentClients(
        workspaceId: WorkspaceId,
        refresh: Boolean,
        scanLimit: Int,
    ): List<RecentAgentClient> = transactions.inReadOnlyTransaction {
        require(scanLimit in 1..RecentAgentClient.MAX_SCAN)
        val table = if (refresh) "list_refresh_jobs" else "ingest_jobs"
        jdbc.queryMany(
            "SELECT agent_client_id, max(terminal_at) AS last_seen_at FROM (" +
                "SELECT agent_client_id, terminal_at FROM catalog.$table WHERE workspace_id = ? AND terminal_at IS NOT NULL " +
                "ORDER BY terminal_at DESC, job_id DESC LIMIT ?) recent WHERE agent_client_id IS NOT NULL GROUP BY agent_client_id",
            { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setInt(2, scanLimit)
            },
        ) { row -> RecentAgentClient(
            AgentClientId(row.getString("agent_client_id")),
            row.getTimestamp("last_seen_at").toInstant(),
        ) }
    }
}
