package cc.monomer.catalog.api

import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import java.time.Instant

data class CatalogSearchAddress(
    val connectorId: ConnectorId,
    val catalogName: String?,
    val schemaName: String?,
    val relationName: String?,
)

data class CatalogSearchListing(
    val address: CatalogSearchAddress,
    val kind: CatalogObjectKind,
    val relationKind: RelationKind?,
    val updatedAt: Instant?,
)

data class CatalogSearchFields(
    val workspaceId: WorkspaceId,
    val address: CatalogSearchAddress,
    val kind: CatalogObjectKind,
    val primaryNames: List<String>,
    val structuralNames: List<String>,
)

interface CatalogSearchReadPort {
    fun readCurrentListings(workspaceId: WorkspaceId, addresses: Set<CatalogSearchAddress>): List<CatalogSearchListing>
    fun readRelationColumnNames(
        workspaceId: WorkspaceId,
        relations: Set<CatalogSearchAddress>,
    ): Map<CatalogSearchAddress, List<String>>
    fun inventory(): List<CatalogSearchFields>
}

interface CatalogSearchProjectionPort {
    fun upsert(fields: CatalogSearchFields)
    fun deleteSubtree(workspaceId: WorkspaceId, address: CatalogSearchAddress)
}
