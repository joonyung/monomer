package cc.monomer.control.connector.application

data class ConnectorView(
    val id: String,
    val workspaceId: String,
    val name: String,
    val type: String,
    val properties: Map<String, Any?>,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val sample: cc.monomer.connector.api.ConnectorSample?,
)

class ConnectorNotFoundException(message: String) : RuntimeException(message)

class ConnectorInvalidArgumentException(message: String) : RuntimeException(message)
