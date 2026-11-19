package cc.monomer.catalog.application

import cc.monomer.catalog.api.CatalogIngestEntry
import cc.monomer.catalog.api.CatalogObjectId
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.catalog.api.CatalogRelationshipKind
import cc.monomer.catalog.application.CatalogBrowseReadPort
import cc.monomer.catalog.application.CatalogBrowseRelation
import cc.monomer.catalog.application.CatalogTransactionPort
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import kotlin.uuid.Uuid

interface CatalogBrowseUseCase {
    suspend fun listCatalogs(workspaceId: String, connectorId: String): List<CatalogSummary>
    suspend fun listSchemas(
        workspaceId: String,
        connectorId: String,
        catalogName: String?,
        page: Int?,
        pageSize: Int?,
        query: String?,
    ): SchemaListPage
    suspend fun listSchemasAfter(
        workspaceId: String,
        connectorId: String,
        catalogName: String?,
        afterName: String?,
        limit: Int,
    ): List<SchemaSummary>
    suspend fun listRelations(
        workspaceId: String,
        connectorId: String,
        catalogName: String?,
        schemaName: String,
        page: Int?,
        pageSize: Int?,
        query: String?,
    ): RelationListPage
    suspend fun listRelationsAfter(
        workspaceId: String,
        connectorId: String,
        catalogName: String?,
        schemaName: String,
        afterName: String?,
        limit: Int,
    ): List<RelationSummary>
    suspend fun getRelation(
        workspaceId: String,
        connectorId: String,
        catalogName: String?,
        schemaName: String,
        relationName: String,
    ): RelationDetail
}

class CatalogBrowseService(
    private val browseReadPort: CatalogBrowseReadPort,
    private val selectedSchemaNames: (WorkspaceId, ConnectorId, CatalogIngestEntry) -> Set<String>,
    private val transactionPort: CatalogTransactionPort,
) : CatalogBrowseUseCase {
    override suspend fun listCatalogs(workspaceId: String, connectorId: String): List<CatalogSummary> =
        transactionPort.inReadOnlyTransaction {
            browseReadPort.readCatalogs(WorkspaceId(Uuid.parse(workspaceId)), ConnectorId(Uuid.parse(connectorId)))
        }
            .distinctBy { it.name }
            .sortedBy { it.name }
            .map { catalog ->
                CatalogSummary(
                    name = catalog.name,
                    lastRefreshedAtEpochMs = catalog.observedAt.toEpochMilli(),
                )
            }

    override suspend fun listSchemas(
        workspaceId: String,
        connectorId: String,
        catalogName: String?,
        page: Int?,
        pageSize: Int?,
        query: String?,
    ): SchemaListPage {
        val resolvedPage = page ?: DEFAULT_PAGE
        val resolvedPageSize = pageSize ?: DEFAULT_PAGE_SIZE
        val parsedWorkspaceId = WorkspaceId(Uuid.parse(workspaceId))
        val parsedConnectorId = ConnectorId(Uuid.parse(connectorId))
        val entry = if (catalogName == null) CatalogIngestEntry.Connector(parsedConnectorId)
            else runCatching { CatalogIngestEntry.Catalog(parsedConnectorId, catalogName) }.getOrNull()
        val (current, selected) = transactionPort.inReadOnlyTransaction {
            browseReadPort.readSchemas(parsedWorkspaceId, parsedConnectorId, catalogName) to
                entry?.let { selectedSchemaNames(parsedWorkspaceId, parsedConnectorId, it) }.orEmpty()
        }
        val loaded = current.filter { schema -> queryMatches(schema.name, query) }.map { schema ->
            SchemaSummary(
                catalogName = schema.catalogName,
                name = schema.name,
                lastRefreshedAtEpochMs = schema.observedAt.toEpochMilli(),
            )
        }
        val loadedNames = loaded.map(SchemaSummary::name).toSet()
        val notLoaded = selected.filter { it !in loadedNames && queryMatches(it, query) }.map { schemaName ->
            SchemaSummary(
                catalogName = catalogName,
                name = schemaName,
                lastRefreshedAtEpochMs = null,
            )
        }
        val schemas = (loaded + notLoaded).sortedWith(compareBy<SchemaSummary> { it.name }.thenBy { it.catalogName.orEmpty() })
        return SchemaListPage(
            schemas = schemas.page(resolvedPage, resolvedPageSize),
            totalCount = schemas.size,
            page = resolvedPage,
            pageSize = resolvedPageSize,
        )
    }

    override suspend fun listSchemasAfter(
        workspaceId: String,
        connectorId: String,
        catalogName: String?,
        afterName: String?,
        limit: Int,
    ): List<SchemaSummary> {
        require(limit > 0)
        return transactionPort.inReadOnlyTransaction {
            browseReadPort.readSchemas(WorkspaceId(Uuid.parse(workspaceId)), ConnectorId(Uuid.parse(connectorId)), catalogName)
        }
            .asSequence()
            .filter { schema ->
                schema.catalogName == catalogName &&
                    (afterName == null || schema.name > afterName)
            }
            .sortedBy { it.name }
            .take(limit)
            .map { schema ->
                SchemaSummary(
                    catalogName = schema.catalogName,
                    name = schema.name,
                    lastRefreshedAtEpochMs = schema.observedAt.toEpochMilli(),
                )
            }
            .toList()
    }

    override suspend fun listRelations(
        workspaceId: String,
        connectorId: String,
        catalogName: String?,
        schemaName: String,
        page: Int?,
        pageSize: Int?,
        query: String?,
    ): RelationListPage {
        val resolvedPage = page ?: DEFAULT_PAGE
        val resolvedPageSize = pageSize ?: DEFAULT_PAGE_SIZE
        val parsedWorkspaceId = WorkspaceId(Uuid.parse(workspaceId))
        val parsedConnectorId = ConnectorId(Uuid.parse(connectorId))
        return transactionPort.inReadOnlyTransaction {
            val filtered = browseReadPort.readRelations(parsedWorkspaceId, parsedConnectorId, catalogName, schemaName)
                .filter { relation -> queryMatches(relation.name, query) }
                .sortedWith(
                    compareBy<CatalogBrowseRelation> { it.name }
                        .thenBy { it.catalogName.orEmpty() },
                )
            val relations = filtered.page(resolvedPage, resolvedPageSize)
            val columnCounts = browseReadPort.countColumns(parsedWorkspaceId, parsedConnectorId, relations)
            RelationListPage(
                relations = relations.map { relation -> relation.toSummary(columnCounts) },
                totalCount = filtered.size,
                page = resolvedPage,
                pageSize = resolvedPageSize,
            )
        }
    }

    override suspend fun listRelationsAfter(
        workspaceId: String,
        connectorId: String,
        catalogName: String?,
        schemaName: String,
        afterName: String?,
        limit: Int,
    ): List<RelationSummary> {
        require(limit > 0)
        val parsedWorkspaceId = WorkspaceId(Uuid.parse(workspaceId))
        val parsedConnectorId = ConnectorId(Uuid.parse(connectorId))
        return transactionPort.inReadOnlyTransaction {
            val relations = browseReadPort.readRelations(parsedWorkspaceId, parsedConnectorId, catalogName, schemaName)
                .asSequence()
                .filter { relation ->
                    relation.catalogName == catalogName &&
                        (afterName == null || relation.name > afterName)
                }
                .sortedBy { it.name }
                .take(limit)
                .toList()
            val columnCounts = browseReadPort.countColumns(parsedWorkspaceId, parsedConnectorId, relations)
            relations.map { relation -> relation.toSummary(columnCounts) }
        }
    }

    override suspend fun getRelation(
        workspaceId: String,
        connectorId: String,
        catalogName: String?,
        schemaName: String,
        relationName: String,
    ): RelationDetail {
        val objects = transactionPort.inReadOnlyTransaction {
            browseReadPort.readRelationSubtree(
                WorkspaceId(Uuid.parse(workspaceId)),
                ConnectorId(Uuid.parse(connectorId)),
                catalogName,
                schemaName,
                relationName,
            )
        }
        val relation = objects.firstOrNull { objectRow ->
            objectRow.kind == CatalogObjectKind.RELATION &&
                objectRow.address.catalogName == catalogName &&
                objectRow.address.schemaName == schemaName &&
                objectRow.address.objectName == relationName
        } ?: throw CatalogNotFoundException("catalog relation not found")
        val columns = objects.filter { it.kind == CatalogObjectKind.COLUMN && it.parentId == relation.id }
            .sortedBy { requireNotNull(it.ordinal) }
        val relationships = objects.filter {
            it.kind == CatalogObjectKind.RELATIONSHIP && it.parentId == relation.id
        }
        val primaryKey = relationships
            .filter { it.relationshipKind == CatalogRelationshipKind.PRIMARY_KEY }
            .groupBy { requireNotNull(it.constraintName) }
            .toSortedMap()
            .entries
            .firstOrNull()
            ?.let { (name, members) ->
                PrimaryKeyInfo(
                    name = name,
                    columns = members.sortedBy { requireNotNull(it.memberOrdinal) }
                        .map { requireNotNull(it.sourceColumn) },
                )
            }
        val foreignKeys = relationships
            .filter { it.relationshipKind == CatalogRelationshipKind.FOREIGN_KEY }
            .groupBy { requireNotNull(it.constraintName) }
            .toSortedMap()
            .map { (name, members) ->
                val first = members.minBy { requireNotNull(it.memberOrdinal) }
                ForeignKeyInfo(
                    name = name,
                    columns = members.sortedBy { requireNotNull(it.memberOrdinal) }
                        .map { requireNotNull(it.sourceColumn) },
                    referencedConnectorId = connectorId,
                    referencedCatalogName = first.referencedCatalog,
                    referencedSchemaName = requireNotNull(first.referencedSchema),
                    referencedRelationName = requireNotNull(first.referencedRelation),
                    referencedColumns = members.sortedBy { requireNotNull(it.memberOrdinal) }
                        .map { requireNotNull(it.referencedColumn) },
                )
            }
        return RelationDetail(
            resourceId = relation.id.value,
            catalogName = relation.address.catalogName,
            schemaName = requireNotNull(relation.address.schemaName),
            name = requireNotNull(relation.address.objectName),
            kind = requireNotNull(relation.relationKind).name,
            viewDefinition = relation.definition,
            columns = columns.map { column ->
                ColumnInfo(
                    name = requireNotNull(column.address.memberName),
                    nativeType = requireNotNull(column.nativeType),
                    ordinalPosition = requireNotNull(column.ordinal),
                    nullable = requireNotNull(column.nullable),
                    defaultValue = column.defaultExpression,
                )
            },
            partitionKeys = relation.partitionKeys.orEmpty(),
            clusteringKeys = relation.clusteringKeys.orEmpty(),
            requiresPartitionFilter = relation.requiresPartitionFilter,
            primaryKey = primaryKey,
            foreignKeys = foreignKeys,
        )
    }

    private fun CatalogBrowseRelation.toSummary(columnCounts: Map<CatalogObjectId, Int>) = RelationSummary(
        catalogName = catalogName,
        schemaName = schemaName,
        name = name,
        kind = kind.name,
        columnCount = columnCounts.getValue(id),
        lastRefreshedAtEpochMs = observedAt.toEpochMilli(),
    )

    private fun queryMatches(value: String?, query: String?): Boolean =
        query.isNullOrBlank() || (value?.contains(query.trim(), ignoreCase = true) == true)

    private fun <T> List<T>.page(page: Int, pageSize: Int): List<T> {
        require(page > 0)
        require(pageSize > 0)
        val from = ((page - 1).toLong() * pageSize).coerceAtMost(size.toLong()).toInt()
        val to = (from.toLong() + pageSize).coerceAtMost(size.toLong()).toInt()
        return subList(from, to)
    }

    private companion object {
        const val DEFAULT_PAGE: Int = 1
        const val DEFAULT_PAGE_SIZE: Int = 50
    }
}
