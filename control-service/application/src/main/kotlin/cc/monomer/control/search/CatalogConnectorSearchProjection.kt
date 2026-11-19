@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.search

import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.catalog.api.CatalogSearchAddress
import cc.monomer.catalog.api.CatalogSearchFields
import cc.monomer.catalog.api.CatalogSearchProjectionPort
import cc.monomer.connector.api.ConnectorSearchFields
import cc.monomer.connector.api.ConnectorSearchProjectionPort
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.search.api.SearchDocument
import cc.monomer.search.api.SearchIndex
import cc.monomer.search.api.SearchResourceKind
import kotlin.uuid.toJavaUuid

class CatalogConnectorSearchProjection(private val index: SearchIndex) : CatalogSearchProjectionPort, ConnectorSearchProjectionPort {
    override fun upsert(fields: CatalogSearchFields) {
        index.upsert(SearchDocument(fields.workspaceId.value.toJavaUuid(), catalogSearchPath(fields.address),
            SearchResourceKind.valueOf(fields.kind.name), fields.primaryNames, emptyList(), fields.structuralNames))
    }

    override fun upsert(fields: ConnectorSearchFields) {
        index.upsert(SearchDocument(fields.workspaceId.value.toJavaUuid(), "workspace/connectors/${fields.connectorId.value}",
            SearchResourceKind.CONNECTOR, fields.primaryNames, emptyList(), fields.structuralNames))
    }

    override fun delete(workspaceId: WorkspaceId, connectorId: cc.monomer.connector.api.ConnectorId) {
        index.deleteSubtree(workspaceId.value.toJavaUuid(), "workspace/connectors/${connectorId.value}")
    }

    override fun deleteSubtree(workspaceId: WorkspaceId, address: CatalogSearchAddress) {
        index.deleteSubtree(workspaceId.value.toJavaUuid(), catalogSearchPath(address))
    }
}

fun catalogSearchPath(address: CatalogSearchAddress): String = buildString {
    append("workspace/connectors/${address.connectorId.value}/catalog")
    address.catalogName?.let { append("/catalogs/${AgentResourcePath.encodeNameSegment(it)}") }
    address.schemaName?.let { append("/schemas/${AgentResourcePath.encodeNameSegment(it)}") }
    address.relationName?.let { append("/relations/${AgentResourcePath.encodeNameSegment(it)}") }
}

fun catalogSearchAddress(path: String): CatalogSearchAddress? {
    val segments = path.split('/')
    if (segments.size < 6 || segments[1] != "connectors" || segments[3] != "catalog") return null
    val connector = cc.monomer.connector.api.ConnectorId(kotlin.uuid.Uuid.parse(segments[2]))
    val tail = segments.drop(4).map(AgentResourcePath::decodeNameSegment)
    val catalogName = if (tail[0] == "catalogs") tail.getOrNull(1) else null
    val schemaTail = if (catalogName == null) tail else tail.drop(2)
    val schema = if (schemaTail.firstOrNull() == "schemas") schemaTail.getOrNull(1) else null
    val relation = if (schemaTail.getOrNull(2) == "relations") schemaTail.getOrNull(3) else null
    return CatalogSearchAddress(connector, catalogName, schema, relation)
}
