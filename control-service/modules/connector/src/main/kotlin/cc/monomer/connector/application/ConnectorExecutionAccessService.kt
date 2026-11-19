package cc.monomer.connector.application

import cc.monomer.connector.api.ConnectorExecutionAccessResult
import cc.monomer.connector.api.ConnectorExecutionAccessUseCase
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.domain.Connector
import cc.monomer.identity.api.WorkspaceId

data class ConnectorExecutionAccessCandidate(
    val connector: Connector,
)

interface ConnectorExecutionAccessQueryPort {
    fun findCurrentExecutionAccessCandidate(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
    ): ConnectorExecutionAccessCandidate?
}

interface ConnectorExecutionAccessReadPort {
    suspend fun readCurrentExecutionAccessCandidate(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
    ): ConnectorExecutionAccessCandidate?
}

class ConnectorExecutionAccessService(
    private val readPort: ConnectorExecutionAccessReadPort,
) : ConnectorExecutionAccessUseCase {
    override suspend fun readCurrentForExecution(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
    ): ConnectorExecutionAccessResult =
        resolveConnectorExecutionAccess(
            readPort.readCurrentExecutionAccessCandidate(workspaceId, connectorId),
        )
}

internal fun resolveConnectorExecutionAccess(
    candidate: ConnectorExecutionAccessCandidate?,
): ConnectorExecutionAccessResult {
    val scopedCandidate = candidate ?: return ConnectorExecutionAccessResult.NotFoundOrOutsideWorkspace
    return ConnectorExecutionAccessResult.Granted(scopedCandidate.connector.toExecutionBinding())
}
