package cc.monomer.control.connector.application

import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorSettingsCodec
import cc.monomer.connector.api.ConnectorWorkspaceReadUseCase
import cc.monomer.connector.api.CurrentConnectorRead
import cc.monomer.identity.api.WorkspaceId
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.uuid.Uuid

interface ConnectorReadUseCase {
    suspend fun get(workspaceId: String, connectorId: String): ConnectorView

    suspend fun listByWorkspace(workspaceId: String): List<ConnectorView>
}

class ConnectorReadService(
    private val connectorWorkspaceReadUseCase: ConnectorWorkspaceReadUseCase,
) : ConnectorReadUseCase {
    private val connectorPropertiesMapper = ObjectMapper()
    private val connectorPropertiesType = object : TypeReference<Map<String, Any?>>() {}

    override suspend fun get(workspaceId: String, connectorId: String): ConnectorView {
        val parsedWorkspaceId = WorkspaceId(parseCanonicalUuid(workspaceId, "workspace id"))
        val parsedConnectorId = ConnectorId(parseCanonicalUuid(connectorId, "connector id"))
        return connectorWorkspaceReadUseCase.findByWorkspaceAndId(parsedWorkspaceId, parsedConnectorId)?.toView()
            ?: throw ConnectorNotFoundException("connector not found")
    }

    override suspend fun listByWorkspace(workspaceId: String): List<ConnectorView> =
        connectorWorkspaceReadUseCase
            .listByWorkspace(WorkspaceId(parseCanonicalUuid(workspaceId, "workspace id")))
            .map { connector -> connector.toView() }

    private fun parseCanonicalUuid(rawValue: String, fieldName: String): Uuid {
        val parsed = runCatching { Uuid.parse(rawValue) }
            .getOrElse { throw ConnectorInvalidArgumentException("invalid $fieldName: $rawValue") }
        if (parsed.toString() != rawValue) {
            throw ConnectorInvalidArgumentException("invalid $fieldName: $rawValue")
        }
        return parsed
    }

    private fun CurrentConnectorRead.toView(): ConnectorView = ConnectorView(
        id = connectorId.value.toString(),
        workspaceId = workspaceId.value.toString(),
        name = displayName,
        type = connectorType.name,
        properties = connectorPropertiesMapper.readValue(
            ConnectorSettingsCodec.encode(properties).toString(),
            connectorPropertiesType,
        ),
        createdAtEpochMs = createdAt.toEpochMilli(),
        updatedAtEpochMs = updatedAt.toEpochMilli(),
        sample = sample,
    )
}
