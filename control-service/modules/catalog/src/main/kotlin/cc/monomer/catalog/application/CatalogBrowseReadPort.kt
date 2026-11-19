@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.application

import cc.monomer.catalog.api.CatalogCurrentObject
import cc.monomer.catalog.api.CatalogObjectId
import cc.monomer.catalog.api.RelationKind
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import java.time.Instant

/** Current-object reads narrowed to one browse listing, in the caller's Catalog owner transaction. */
interface CatalogBrowseReadPort {
    fun readCatalogs(workspaceId: WorkspaceId, connectorId: ConnectorId): List<CatalogBrowseCatalog>

    /** Reads the Schemas of [catalogName], or of every Catalog when it is null. */
    fun readSchemas(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        catalogName: String?,
    ): List<CatalogBrowseSchema>

    /** Reads the Relations of [schemaName] in [catalogName], or in every Catalog when it is null. */
    fun readRelations(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        catalogName: String?,
        schemaName: String,
    ): List<CatalogBrowseRelation>

    /** Counts the current Columns of each given Relation; a Relation without Columns counts zero. */
    fun countColumns(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        relations: List<CatalogBrowseRelation>,
    ): Map<CatalogObjectId, Int>

    /** Reads the Relation at the exact address, where a null [catalogName] means no Catalog, and its members. */
    fun readRelationSubtree(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        catalogName: String?,
        schemaName: String,
        relationName: String,
    ): List<CatalogCurrentObject>
}

data class CatalogBrowseCatalog(
    val name: String,
    val observedAt: Instant,
)

data class CatalogBrowseSchema(
    val catalogName: String?,
    val name: String,
    val observedAt: Instant,
)

data class CatalogBrowseRelation(
    val id: CatalogObjectId,
    val catalogName: String?,
    val schemaName: String,
    val name: String,
    val kind: RelationKind,
    val observedAt: Instant,
)
