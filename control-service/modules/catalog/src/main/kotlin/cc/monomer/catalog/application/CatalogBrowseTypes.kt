package cc.monomer.catalog.application

import kotlin.uuid.Uuid

data class CatalogSummary(
    val name: String,
    val lastRefreshedAtEpochMs: Long?,
)

data class SchemaSummary(
    val catalogName: String?,
    val name: String,
    val lastRefreshedAtEpochMs: Long?,
)

data class SchemaListPage(
    val schemas: List<SchemaSummary>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
)

data class RelationSummary(
    val catalogName: String?,
    val schemaName: String,
    val name: String,
    val kind: String,
    val columnCount: Int,
    val lastRefreshedAtEpochMs: Long?,
)

data class RelationListPage(
    val relations: List<RelationSummary>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
)

data class ColumnInfo(
    val name: String,
    val nativeType: String,
    val ordinalPosition: Int,
    val nullable: Boolean,
    val defaultValue: String?,
)

data class PrimaryKeyInfo(
    val name: String?,
    val columns: List<String>,
)

data class ForeignKeyInfo(
    val name: String?,
    val columns: List<String>,
    val referencedConnectorId: String,
    val referencedCatalogName: String?,
    val referencedSchemaName: String,
    val referencedRelationName: String,
    val referencedColumns: List<String>,
)

data class RelationDetail(
    val resourceId: Uuid,
    val catalogName: String?,
    val schemaName: String,
    val name: String,
    val kind: String,
    val viewDefinition: String?,
    val columns: List<ColumnInfo>,
    val partitionKeys: List<String>,
    val clusteringKeys: List<String>,
    val requiresPartitionFilter: Boolean?,
    val primaryKey: PrimaryKeyInfo?,
    val foreignKeys: List<ForeignKeyInfo>,
)

class CatalogNotFoundException(message: String) : RuntimeException(message)
