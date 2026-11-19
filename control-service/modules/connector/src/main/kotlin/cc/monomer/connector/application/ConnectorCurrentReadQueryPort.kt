package cc.monomer.connector.application

import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.CurrentConnectorRead
import cc.monomer.identity.api.WorkspaceId

interface ConnectorCurrentReadQueryPort {
    fun findCurrentByWorkspaceAndId(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
    ): CurrentConnectorRead?

    fun findCurrentByWorkspaceAndIds(
        workspaceId: WorkspaceId,
        connectorIds: Set<ConnectorId>,
    ): List<CurrentConnectorRead>

    fun listCurrentByWorkspace(workspaceId: WorkspaceId): List<CurrentConnectorRead>
}
