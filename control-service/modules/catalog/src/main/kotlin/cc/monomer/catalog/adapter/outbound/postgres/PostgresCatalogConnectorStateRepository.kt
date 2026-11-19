@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.adapter.outbound.postgres

import cc.monomer.catalog.api.CatalogSearchAddress
import cc.monomer.catalog.api.CatalogSearchProjectionPort
import cc.monomer.catalog.application.CatalogConnectorStateRepository
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import javax.sql.DataSource
import kotlin.uuid.toJavaUuid

/** PostgreSQL removal of a deleted Connector's current Catalog state; job history stays. */
class PostgresCatalogConnectorStateRepository(
    dataSource: DataSource,
    private val searchProjection: CatalogSearchProjectionPort,
) : CatalogConnectorStateRepository {
    private val jdbc = CatalogOwnerJdbc(dataSource)

    override fun lockConnector(workspaceId: WorkspaceId, connectorId: ConnectorId) {
        jdbc.lockConnector(workspaceId, connectorId)
    }

    override fun hasActiveConnectorWork(workspaceId: WorkspaceId, connectorId: ConnectorId): Boolean {
        jdbc.requireOwnerTransaction()
        return checkNotNull(jdbc.queryOne(
            """
                SELECT EXISTS (
                           SELECT 1 FROM catalog.ingest_jobs
                            WHERE workspace_id = ? AND connector_id = ? AND state IN ('RUNNING', 'CANCEL_REQUESTED')
                       )
                    OR EXISTS (
                           SELECT 1 FROM catalog.list_refresh_jobs
                            WHERE workspace_id = ? AND connector_id = ? AND state IN ('RUNNING', 'CANCEL_REQUESTED')
                       )
            """.trimIndent(),
            { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setObject(2, connectorId.value.toJavaUuid())
                statement.setObject(3, workspaceId.value.toJavaUuid())
                statement.setObject(4, connectorId.value.toJavaUuid())
            },
        ) { it.getBoolean(1) })
    }

    override fun removeConnectorState(workspaceId: WorkspaceId, connectorId: ConnectorId) {
        jdbc.requireOwnerTransaction()
        listOf(
            "catalog.selection_options",
            "catalog.selection_option_lists",
            "catalog.selections",
            "catalog.connector_catalog_selections",
            "catalog.current_objects",
        ).forEach { table ->
            jdbc.update("DELETE FROM $table WHERE workspace_id = ? AND connector_id = ?") { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setObject(2, connectorId.value.toJavaUuid())
            }
        }
        searchProjection.deleteSubtree(workspaceId, CatalogSearchAddress(connectorId, null, null, null))
    }
}
