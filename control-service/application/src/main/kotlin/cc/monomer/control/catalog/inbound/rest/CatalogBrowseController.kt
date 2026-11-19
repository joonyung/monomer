package cc.monomer.control.catalog.inbound.rest

import cc.monomer.catalog.application.CatalogBrowseUseCase
import cc.monomer.catalog.application.CatalogNotFoundException
import cc.monomer.catalog.application.CatalogSummary
import cc.monomer.catalog.application.ColumnInfo
import cc.monomer.catalog.application.ForeignKeyInfo
import cc.monomer.catalog.application.PrimaryKeyInfo
import cc.monomer.catalog.application.SchemaSummary
import cc.monomer.catalog.application.RelationDetail
import cc.monomer.catalog.application.RelationSummary
import cc.monomer.control.human.auth.ConnectorCatalogRequestAccess
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

@RestController
@RequestMapping("/v2/workspaces/{workspaceId}/catalog/connectors/{connectorId}")
class CatalogBrowseController(
    private val useCase: CatalogBrowseUseCase,
    private val requestAccess: ConnectorCatalogRequestAccess,
) {
    @GetMapping("/catalogs")
    suspend fun listCatalogs(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        exchange: ServerWebExchange,
    ): CatalogListResponse = catch404 {
        val access = requestAccess.requireConnector(exchange, workspaceId, connectorId)
        CatalogListResponse(useCase.listCatalogs(access.workspaceId, connectorId).map { it.toResponse() })
    }

    @GetMapping("/schemas")
    suspend fun listSchemas(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        @RequestParam(required = false) catalog: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) pageSize: Int?,
        @RequestParam(required = false) query: String?,
        exchange: ServerWebExchange,
    ): SchemaListResponse = catch404 {
        val access = requestAccess.requireConnector(exchange, workspaceId, connectorId)
        requirePageRequest(page, pageSize)
        val result = useCase.listSchemas(access.workspaceId, connectorId, catalog, page, pageSize, query)
        SchemaListResponse(
            schemas = result.schemas.map { it.toResponse() },
            totalCount = result.totalCount,
            page = result.page,
            pageSize = result.pageSize,
        )
    }

    @GetMapping("/schemas/{schemaName}/relations")
    suspend fun listRelations(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        @PathVariable schemaName: String,
        @RequestParam(required = false) catalog: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) pageSize: Int?,
        @RequestParam(required = false) query: String?,
        exchange: ServerWebExchange,
    ): RelationListResponse = catch404 {
        val access = requestAccess.requireConnector(exchange, workspaceId, connectorId)
        requirePageRequest(page, pageSize)
        val result = useCase.listRelations(access.workspaceId, connectorId, catalog, schemaName, page, pageSize, query)
        RelationListResponse(
            relations = result.relations.map { it.toResponse() },
            totalCount = result.totalCount,
            page = result.page,
            pageSize = result.pageSize,
        )
    }

    @GetMapping("/relations/{relationName}")
    suspend fun getRelation(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        @PathVariable relationName: String,
        @RequestParam(required = false) catalog: String?,
        @RequestParam(required = true) schema: String,
        exchange: ServerWebExchange,
    ): RelationDetailResponse = catch404 {
        val access = requestAccess.requireConnector(exchange, workspaceId, connectorId)
        useCase.getRelation(access.workspaceId, connectorId, catalog, schema, relationName).toResponse()
    }

    private fun requirePageRequest(page: Int?, pageSize: Int?) {
        if (page != null && page < 1) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be at least 1")
        }
        if (pageSize != null && pageSize !in 1..200) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "pageSize must be between 1 and 200")
        }
    }

    private inline fun <T> catch404(block: () -> T): T =
        try {
            block()
        } catch (e: CatalogNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        }

    data class CatalogListResponse(val catalogs: List<CatalogSummaryResponse>)
    data class SchemaListResponse(
        val schemas: List<SchemaSummaryResponse>,
        val totalCount: Int,
        val page: Int,
        val pageSize: Int,
    )
    data class RelationListResponse(
        val relations: List<RelationSummaryResponse>,
        val totalCount: Int,
        val page: Int,
        val pageSize: Int,
    )

    data class CatalogSummaryResponse(
        val name: String,
        val lastRefreshedAt: Long?,
    )

    data class SchemaSummaryResponse(
        val catalogName: String?,
        val name: String,
        val lastRefreshedAt: Long?,
    )

    data class RelationSummaryResponse(
        val catalogName: String?,
        val schemaName: String,
        val name: String,
        val kind: String,
        val columnCount: Int,
        val lastRefreshedAt: Long?,
    )

    data class ColumnResponse(
        val name: String,
        val nativeType: String,
        val ordinalPosition: Int,
        val nullable: Boolean,
        val defaultValue: String?,
    )

    data class PrimaryKeyResponse(
        val name: String?,
        val columns: List<String>,
    )

    data class ForeignKeyResponse(
        val name: String?,
        val columns: List<String>,
        val referencedConnectorId: String,
        val referencedCatalogName: String?,
        val referencedSchemaName: String,
        val referencedRelationName: String,
        val referencedColumns: List<String>,
    )

    data class LineageResourceRefResponse(
        val kind: String,
        val id: String,
    )

    data class RelationDetailResponse(
        val lineageResourceRef: LineageResourceRefResponse,
        val catalogName: String?,
        val schemaName: String,
        val name: String,
        val kind: String,
        val viewDefinition: String?,
        val columns: List<ColumnResponse>,
        val partitionKeys: List<String>,
        val clusteringKeys: List<String>,
        val requiresPartitionFilter: Boolean?,
        val primaryKey: PrimaryKeyResponse?,
        val foreignKeys: List<ForeignKeyResponse>,
    )
}

private fun CatalogSummary.toResponse() = CatalogBrowseController.CatalogSummaryResponse(
    name = name,
    lastRefreshedAt = lastRefreshedAtEpochMs,
)

private fun SchemaSummary.toResponse() = CatalogBrowseController.SchemaSummaryResponse(
    catalogName = catalogName,
    name = name,
    lastRefreshedAt = lastRefreshedAtEpochMs,
)

private fun RelationSummary.toResponse() = CatalogBrowseController.RelationSummaryResponse(
    catalogName = catalogName,
    schemaName = schemaName,
    name = name,
    kind = kind,
    columnCount = columnCount,
    lastRefreshedAt = lastRefreshedAtEpochMs,
)

private fun RelationDetail.toResponse() = CatalogBrowseController.RelationDetailResponse(
    lineageResourceRef = CatalogBrowseController.LineageResourceRefResponse(
        kind = when (kind.uppercase()) {
            "VIEW", "MATERIALIZED_VIEW" -> "catalog_view"
            else -> "catalog_table"
        },
        id = resourceId.toString(),
    ),
    catalogName = catalogName,
    schemaName = schemaName,
    name = name,
    kind = kind,
    viewDefinition = viewDefinition,
    columns = columns.map { it.toResponse() },
    partitionKeys = partitionKeys,
    clusteringKeys = clusteringKeys,
    requiresPartitionFilter = requiresPartitionFilter,
    primaryKey = primaryKey?.toResponse(),
    foreignKeys = foreignKeys.map { it.toResponse() },
)

private fun ColumnInfo.toResponse() = CatalogBrowseController.ColumnResponse(
    name = name,
    nativeType = nativeType,
    ordinalPosition = ordinalPosition,
    nullable = nullable,
    defaultValue = defaultValue,
)

private fun PrimaryKeyInfo.toResponse() = CatalogBrowseController.PrimaryKeyResponse(
    name = name,
    columns = columns,
)

private fun ForeignKeyInfo.toResponse() = CatalogBrowseController.ForeignKeyResponse(
    name = name,
    columns = columns,
    referencedConnectorId = referencedConnectorId,
    referencedCatalogName = referencedCatalogName,
    referencedSchemaName = referencedSchemaName,
    referencedRelationName = referencedRelationName,
    referencedColumns = referencedColumns,
)
