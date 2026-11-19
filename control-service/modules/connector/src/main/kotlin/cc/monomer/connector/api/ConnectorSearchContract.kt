package cc.monomer.connector.api

import cc.monomer.identity.api.WorkspaceId

data class ConnectorSearchFields(
    val workspaceId: WorkspaceId,
    val connectorId: ConnectorId,
    val primaryNames: List<String>,
    val structuralNames: List<String>,
)

interface ConnectorSearchProjectionPort {
    fun upsert(fields: ConnectorSearchFields)
    fun delete(workspaceId: WorkspaceId, connectorId: ConnectorId)
}

interface ConnectorSearchReadPort {
    fun readCurrentListings(workspaceId: WorkspaceId, ids: Set<ConnectorId>): List<CurrentConnectorRead>
    fun inventory(): List<ConnectorSearchFields>
}

internal fun connectorSearchFields(
    workspaceId: WorkspaceId,
    connectorId: ConnectorId,
    displayName: String,
    properties: ConnectorSettingsProperties,
): ConnectorSearchFields = ConnectorSearchFields(workspaceId, connectorId, listOf(displayName),
    listOf(properties.connectorType.name) + when (properties) {
        is ConnectorSettingsProperties.Duckdb -> listOf(properties.sampleDataset)
        is ConnectorSettingsProperties.Postgresql -> listOfNotNull(properties.database, properties.schema)
        is ConnectorSettingsProperties.Bigquery -> listOf(properties.projectId)
        is ConnectorSettingsProperties.Snowflake -> listOfNotNull(properties.database, properties.schema)
        is ConnectorSettingsProperties.Trino -> listOfNotNull(properties.catalog, properties.schema)
        is ConnectorSettingsProperties.Redshift -> listOfNotNull(properties.database, properties.schema)
        is ConnectorSettingsProperties.Databricks -> listOfNotNull(properties.catalog, properties.schema)
    })
