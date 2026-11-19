package cc.monomer.connector.api

import cc.monomer.identity.api.WorkspaceId
import java.time.Instant

/** The only public, workspace-scoped projection of current Connector state. */
data class CurrentConnectorRead(
    val connectorId: ConnectorId,
    val workspaceId: WorkspaceId,
    val connectorType: ConnectorProductType,
    val displayName: String,
    val properties: ConnectorSettingsProperties,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val sample: ConnectorSample? get() = (properties as? ConnectorSettingsProperties.Duckdb)?.let { ConnectorSample(it.sampleDataset, "Acme Cloud") }

    init {
        require(displayName.isNotBlank()) { "Connector display name must not be blank" }
    }
}

data class ConnectorSample(val dataset: String, val label: String)

enum class ConnectorProductType {
    POSTGRESQL,
    BIGQUERY,
    SNOWFLAKE,
    DATABRICKS,
    REDSHIFT,
    TRINO,
    DUCKDB;

    companion object {
        fun fromStorageValue(value: String): ConnectorProductType =
            valueOf(value.uppercase())
    }
}

interface ConnectorWorkspaceReadUseCase {
    suspend fun findByWorkspaceAndId(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
    ): CurrentConnectorRead?

    suspend fun findByWorkspaceAndIds(
        workspaceId: WorkspaceId,
        connectorIds: Set<ConnectorId>,
    ): List<CurrentConnectorRead>

    suspend fun listByWorkspace(workspaceId: WorkspaceId): List<CurrentConnectorRead>
}
