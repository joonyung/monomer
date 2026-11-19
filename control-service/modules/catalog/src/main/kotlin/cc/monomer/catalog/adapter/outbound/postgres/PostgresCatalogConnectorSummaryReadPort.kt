@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.adapter.outbound.postgres

import cc.monomer.catalog.api.CatalogConnectorLastRefresh
import cc.monomer.catalog.api.CatalogConnectorSummary
import cc.monomer.catalog.api.CatalogConnectorSummaryReadPort
import cc.monomer.catalog.api.CatalogIngestJobId
import cc.monomer.catalog.api.CatalogRefreshState
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronizationManager

class PostgresCatalogConnectorSummaryReadPort(private val dataSource: DataSource) : CatalogConnectorSummaryReadPort {
    override fun readConnectorSummaries(workspaceId: WorkspaceId): List<CatalogConnectorSummary> {
        check(TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.hasResource(dataSource)) {
            "Catalog connector summaries require the transaction bound to the exact DataSource"
        }
        val connection = DataSourceUtils.getConnection(dataSource)
        return try {
            check(!connection.autoCommit) { "Catalog connector summaries require a non-autocommit transaction" }
            connection.prepareStatement(CONNECTOR_SUMMARIES_SQL).use { statement ->
                val workspace = workspaceId.value.toJavaUuid()
                for (index in 1..CONNECTOR_SUMMARIES_WORKSPACE_PARAMETERS) statement.setObject(index, workspace)
                statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toSummary()) } }
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }

    private fun ResultSet.toSummary() = CatalogConnectorSummary(
        connectorId = ConnectorId(getObject("connector_id", UUID::class.java).toKotlinUuid()),
        selectedCatalogCount = getInt("selected_catalog_count"),
        selectedSchemaCount = getInt("selected_schema_count"),
        tableCount = getInt("table_count"),
        lastRefresh = getObject("last_job_id", UUID::class.java)?.let { jobId ->
            CatalogConnectorLastRefresh(
                jobId = CatalogIngestJobId(jobId.toKotlinUuid()),
                state = CatalogRefreshState.valueOf(getString("last_state")),
                catalogName = getString("last_catalog_name"),
                requestedAt = getTimestamp("last_requested_at").toInstant(),
                finishedAt = getTimestamp("last_terminal_at")?.toInstant(),
                errorCount = getInt("last_error_count"),
                entryFailureCode = getString("last_entry_failure_code"),
            )
        },
        lastSuccessfulRefreshAt = getTimestamp("last_successful_at")?.toInstant(),
    )
}

private const val CONNECTOR_SUMMARIES_WORKSPACE_PARAMETERS = 7

private val CONNECTOR_SUMMARIES_SQL = """
    WITH schema_counts AS (
        SELECT connector_id, sum(jsonb_array_length(selection -> 'selectedSchemas'))::int AS selected_schema_count
          FROM catalog.selections
         WHERE workspace_id = ?
         GROUP BY connector_id
    ), catalog_counts AS (
        SELECT connector_id, jsonb_array_length(selected_catalog_names) AS selected_catalog_count
          FROM catalog.connector_catalog_selections
         WHERE workspace_id = ?
    ), table_counts AS (
        SELECT connector_id, count(*)::int AS table_count
          FROM catalog.current_objects
         WHERE workspace_id = ? AND object_kind = 'RELATION'
         GROUP BY connector_id
    ), latest_jobs AS (
        SELECT DISTINCT ON (connector_id) connector_id, job_id, state, entry_catalog_name, requested_at, terminal_at
          FROM catalog.ingest_jobs
         WHERE workspace_id = ?
         ORDER BY connector_id, requested_at DESC, job_id DESC
    ), successful_jobs AS (
        SELECT connector_id, max(terminal_at) AS last_successful_at
          FROM catalog.ingest_jobs
         WHERE workspace_id = ? AND state = 'COMPLETED'
         GROUP BY connector_id
    ), connectors AS (
        SELECT connector_id FROM catalog.selections WHERE workspace_id = ?
        UNION
        SELECT connector_id FROM catalog.connector_catalog_selections WHERE workspace_id = ?
        UNION
        SELECT connector_id FROM table_counts
        UNION
        SELECT connector_id FROM latest_jobs
    )
    SELECT connectors.connector_id,
           coalesce(catalog_counts.selected_catalog_count, 0) AS selected_catalog_count,
           coalesce(schema_counts.selected_schema_count, 0) AS selected_schema_count,
           coalesce(table_counts.table_count, 0) AS table_count,
           latest_jobs.job_id AS last_job_id,
           latest_jobs.state AS last_state,
           latest_jobs.entry_catalog_name AS last_catalog_name,
           latest_jobs.requested_at AS last_requested_at,
           latest_jobs.terminal_at AS last_terminal_at,
           (SELECT count(*)::int FROM catalog.ingest_tasks tasks
             WHERE tasks.job_id = latest_jobs.job_id AND tasks.state = 'FAILED') AS last_error_count,
           (SELECT tasks.failure_code FROM catalog.ingest_tasks tasks
             WHERE tasks.job_id = latest_jobs.job_id AND tasks.state = 'FAILED'
               AND tasks.parent_task_id IS NULL
             ORDER BY tasks.terminal_at, tasks.task_id LIMIT 1) AS last_entry_failure_code,
           successful_jobs.last_successful_at
      FROM connectors
      LEFT JOIN schema_counts ON schema_counts.connector_id = connectors.connector_id
      LEFT JOIN catalog_counts ON catalog_counts.connector_id = connectors.connector_id
      LEFT JOIN table_counts ON table_counts.connector_id = connectors.connector_id
      LEFT JOIN latest_jobs ON latest_jobs.connector_id = connectors.connector_id
      LEFT JOIN successful_jobs ON successful_jobs.connector_id = connectors.connector_id
     ORDER BY connectors.connector_id
""".trimIndent()
