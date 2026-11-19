package cc.monomer.catalog.application

import cc.monomer.catalog.application.CatalogConnectorStateRepository
import cc.monomer.connector.api.ConnectorDependentStateRemoval
import cc.monomer.connector.api.ConnectorDependentStateRemovalPort
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId

/** Removes a deleted Connector's current Catalog state; ingest and list-refresh history stays. */
class CatalogConnectorStateRemoval(
    private val repository: CatalogConnectorStateRepository,
) : ConnectorDependentStateRemovalPort {
    override fun removeInOwnerTransaction(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
    ): ConnectorDependentStateRemoval {
        repository.lockConnector(workspaceId, connectorId)
        if (repository.hasActiveConnectorWork(workspaceId, connectorId)) return ConnectorDependentStateRemoval.REFRESH_ACTIVE
        repository.removeConnectorState(workspaceId, connectorId)
        return ConnectorDependentStateRemoval.REMOVED
    }
}
