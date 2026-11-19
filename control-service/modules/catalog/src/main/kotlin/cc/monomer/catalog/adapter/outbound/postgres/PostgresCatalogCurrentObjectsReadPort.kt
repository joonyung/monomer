@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.adapter.outbound.postgres

import cc.monomer.catalog.api.CatalogCurrentObject
import cc.monomer.catalog.api.CatalogCurrentObjectsReadPort
import cc.monomer.catalog.api.CatalogCurrentObjectSet
import cc.monomer.catalog.api.CatalogCurrentRelationPage
import cc.monomer.catalog.api.CatalogCurrentRelationPageCursor
import cc.monomer.catalog.api.CatalogCurrentRelationSummary
import cc.monomer.catalog.api.CatalogDigest
import cc.monomer.catalog.api.CatalogObjectId
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.catalog.api.CatalogPhysicalAddress
import cc.monomer.catalog.api.CatalogRelationshipKind
import cc.monomer.catalog.api.RelationKind
import cc.monomer.catalog.application.CatalogBrowseCatalog
import cc.monomer.catalog.application.CatalogBrowseReadPort
import cc.monomer.catalog.application.CatalogBrowseRelation
import cc.monomer.catalog.application.CatalogBrowseSchema
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronizationManager

class PostgresCatalogCurrentObjectsReadPort(
    private val dataSource: DataSource,
) : CatalogCurrentObjectsReadPort, CatalogBrowseReadPort {
    override fun readCurrent(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
    ): CatalogCurrentObjectSet? {
        requireOwnerTransaction()
        val connection = DataSourceUtils.getConnection(dataSource)
        return try {
            check(!connection.autoCommit) {
                "Catalog current-object read requires a non-autocommit owner transaction"
            }
            connection.prepareStatement(READ_CURRENT_SQL).use { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setObject(2, connectorId.value.toJavaUuid())
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) add(resultSet.toCurrentObject())
                    }.takeIf { it.isNotEmpty() }?.let { objects ->
                        CatalogCurrentObjectSet(
                            workspaceId = workspaceId,
                            connectorId = connectorId,
                            objects = objects,
                        )
                    }
                }
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }

    override fun searchCurrentRelations(
        workspaceId: WorkspaceId,
        query: String,
        cursor: CatalogCurrentRelationPageCursor?,
        pageSize: Int,
    ): CatalogCurrentRelationPage {
        require(pageSize in 1..MAXIMUM_CATALOG_RELATION_PAGE_SIZE) {
            "Catalog Relation page size must be between 1 and $MAXIMUM_CATALOG_RELATION_PAGE_SIZE"
        }
        requireOwnerTransaction()
        val connection = DataSourceUtils.getConnection(dataSource)
        return try {
            check(!connection.autoCommit) {
                "Catalog current Relation search requires a non-autocommit owner transaction"
            }
            val sql = if (cursor == null) SEARCH_CURRENT_RELATIONS_SQL else SEARCH_CURRENT_RELATIONS_AFTER_SQL
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setString(2, query.trim().lowercase())
                var parameterIndex = 3
                if (cursor != null) {
                    statement.setString(parameterIndex++, cursor.normalizedQualifiedName)
                    statement.setObject(parameterIndex++, cursor.connectorId.value.toJavaUuid())
                    statement.setObject(parameterIndex++, cursor.objectId.value.toJavaUuid())
                }
                statement.setInt(parameterIndex, pageSize + 1)
                statement.executeQuery().use { resultSet ->
                    val rows = buildList {
                        while (resultSet.next()) add(resultSet.toCurrentRelationRow())
                    }
                    val items = rows.take(pageSize)
                    CatalogCurrentRelationPage(
                        items = items.map(CurrentRelationRow::summary),
                        nextCursor = if (rows.size > pageSize) items.lastOrNull()?.cursor else null,
                    )
                }
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }

    override fun readCatalogs(workspaceId: WorkspaceId, connectorId: ConnectorId): List<CatalogBrowseCatalog> =
        readOwnerRows(READ_BROWSE_CATALOGS_SQL, { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.setObject(2, connectorId.value.toJavaUuid())
        }) { resultSet ->
            CatalogBrowseCatalog(
                name = resultSet.getString("catalog_name"),
                observedAt = resultSet.getTimestamp("observed_at").toInstant(),
            )
        }

    override fun readSchemas(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        catalogName: String?,
    ): List<CatalogBrowseSchema> {
        val sql = if (catalogName == null) READ_BROWSE_SCHEMAS_SQL else READ_BROWSE_CATALOG_SCHEMAS_SQL
        return readOwnerRows(sql, { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.setObject(2, connectorId.value.toJavaUuid())
            if (catalogName != null) statement.setString(3, catalogName)
        }) { resultSet ->
            CatalogBrowseSchema(
                catalogName = resultSet.getString("catalog_name"),
                name = resultSet.getString("schema_name"),
                observedAt = resultSet.getTimestamp("observed_at").toInstant(),
            )
        }
    }

    override fun readRelations(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        catalogName: String?,
        schemaName: String,
    ): List<CatalogBrowseRelation> {
        val sql = if (catalogName == null) READ_BROWSE_RELATIONS_SQL else READ_BROWSE_CATALOG_RELATIONS_SQL
        return readOwnerRows(sql, { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.setObject(2, connectorId.value.toJavaUuid())
            var parameterIndex = 3
            if (catalogName != null) statement.setString(parameterIndex++, catalogName)
            statement.setString(parameterIndex, schemaName)
        }) { resultSet ->
            CatalogBrowseRelation(
                id = CatalogObjectId(resultSet.getObject("object_id", UUID::class.java).toKotlinUuid()),
                catalogName = resultSet.getString("catalog_name"),
                schemaName = resultSet.getString("schema_name"),
                name = resultSet.getString("object_name"),
                kind = RelationKind.valueOf(resultSet.getString("relation_kind")),
                observedAt = resultSet.getTimestamp("observed_at").toInstant(),
            )
        }
    }

    override fun countColumns(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        relations: List<CatalogBrowseRelation>,
    ): Map<CatalogObjectId, Int> {
        val countsByRelation = relations.groupBy { it.catalogName to it.schemaName }.flatMap { (container, members) ->
            val (catalogName, schemaName) = container
            val sql = if (catalogName == null) COUNT_BROWSE_UNCATALOGED_COLUMNS_SQL else COUNT_BROWSE_COLUMNS_SQL
            readOwnerRows(sql, { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setObject(2, connectorId.value.toJavaUuid())
                var parameterIndex = 3
                if (catalogName != null) statement.setString(parameterIndex++, catalogName)
                statement.setString(parameterIndex++, schemaName)
                statement.setArray(
                    parameterIndex,
                    statement.connection.createArrayOf("text", members.map(CatalogBrowseRelation::name).distinct().toTypedArray()),
                )
            }) { resultSet ->
                CatalogObjectId(resultSet.getObject("parent_object_id", UUID::class.java).toKotlinUuid()) to
                    resultSet.getInt("column_count")
            }
        }.toMap()
        return relations.associate { relation -> relation.id to (countsByRelation[relation.id] ?: 0) }
    }

    override fun readRelationSubtree(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        catalogName: String?,
        schemaName: String,
        relationName: String,
    ): List<CatalogCurrentObject> {
        val sql = if (catalogName == null) READ_UNCATALOGED_RELATION_SUBTREE_SQL else READ_RELATION_SUBTREE_SQL
        return readOwnerRows(sql, { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.setObject(2, connectorId.value.toJavaUuid())
            var parameterIndex = 3
            if (catalogName != null) statement.setString(parameterIndex++, catalogName)
            statement.setString(parameterIndex++, schemaName)
            statement.setString(parameterIndex, relationName)
        }) { resultSet -> resultSet.toCurrentObject() }
    }

    private fun <T> readOwnerRows(
        sql: String,
        bind: (PreparedStatement) -> Unit,
        map: (ResultSet) -> T,
    ): List<T> {
        requireOwnerTransaction()
        val connection = DataSourceUtils.getConnection(dataSource)
        return try {
            check(!connection.autoCommit) {
                "Catalog browse read requires a non-autocommit owner transaction"
            }
            connection.prepareStatement(sql).use { statement ->
                bind(statement)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) add(map(resultSet))
                    }
                }
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }

    private fun requireOwnerTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Catalog current-object read requires an active owner transaction"
        }
        check(TransactionSynchronizationManager.hasResource(dataSource)) {
            "Catalog current-object read requires the transaction bound to the exact DataSource"
        }
    }
}

private const val CURRENT_OBJECT_COLUMNS = """
           object_id, identity_digest, parent_object_id, object_kind, relation_kind,
           catalog_name, schema_name, object_name, member_name, qualified_name,
           native_type, relation_definition, relation_partition_keys, relation_clustering_keys, relation_requires_partition_filter, column_ordinal, column_nullable,
           column_default_expression, relationship_kind, relationship_member_ordinal,
           relationship_constraint_name, relationship_source_column,
           relationship_referenced_catalog, relationship_referenced_schema,
           relationship_referenced_relation, relationship_referenced_column,
           content_digest, observed_at
"""

private const val READ_CURRENT_SQL = """
    SELECT $CURRENT_OBJECT_COLUMNS
      FROM catalog.current_objects
     WHERE workspace_id = ?
       AND connector_id = ?
     ORDER BY identity_digest
"""

private const val READ_BROWSE_CATALOGS_SQL = """
    SELECT catalog_name, observed_at
      FROM catalog.current_objects
     WHERE workspace_id = ?
       AND connector_id = ?
       AND object_kind = 'CATALOG'
     ORDER BY identity_digest
"""

private const val READ_BROWSE_SCHEMAS_SQL = """
    SELECT catalog_name, schema_name, observed_at
      FROM catalog.current_objects
     WHERE workspace_id = ?
       AND connector_id = ?
       AND object_kind = 'SCHEMA'
     ORDER BY identity_digest
"""

private const val READ_BROWSE_CATALOG_SCHEMAS_SQL = """
    SELECT catalog_name, schema_name, observed_at
      FROM catalog.current_objects
     WHERE workspace_id = ?
       AND connector_id = ?
       AND object_kind = 'SCHEMA'
       AND catalog_name = ?
     ORDER BY identity_digest
"""

private const val READ_BROWSE_RELATIONS_SQL = """
    SELECT object_id, catalog_name, schema_name, object_name, relation_kind, observed_at
      FROM catalog.current_objects
     WHERE workspace_id = ?
       AND connector_id = ?
       AND object_kind = 'RELATION'
       AND schema_name = ?
     ORDER BY identity_digest
"""

private const val READ_BROWSE_CATALOG_RELATIONS_SQL = """
    SELECT object_id, catalog_name, schema_name, object_name, relation_kind, observed_at
      FROM catalog.current_objects
     WHERE workspace_id = ?
       AND connector_id = ?
       AND object_kind = 'RELATION'
       AND catalog_name = ?
       AND schema_name = ?
     ORDER BY identity_digest
"""

private const val COUNT_BROWSE_COLUMNS_SQL = """
    SELECT parent_object_id, count(*)::int AS column_count
      FROM catalog.current_objects
     WHERE workspace_id = ?
       AND connector_id = ?
       AND catalog_name = ?
       AND schema_name = ?
       AND object_name = ANY (?)
       AND object_kind = 'COLUMN'
     GROUP BY parent_object_id
"""

private const val COUNT_BROWSE_UNCATALOGED_COLUMNS_SQL = """
    SELECT parent_object_id, count(*)::int AS column_count
      FROM catalog.current_objects
     WHERE workspace_id = ?
       AND connector_id = ?
       AND catalog_name IS NULL
       AND schema_name = ?
       AND object_name = ANY (?)
       AND object_kind = 'COLUMN'
     GROUP BY parent_object_id
"""

private const val READ_RELATION_SUBTREE_SQL = """
    SELECT $CURRENT_OBJECT_COLUMNS
      FROM catalog.current_objects
     WHERE workspace_id = ?
       AND connector_id = ?
       AND catalog_name = ?
       AND schema_name = ?
       AND object_name = ?
       AND object_kind IN ('RELATION', 'COLUMN', 'RELATIONSHIP')
     ORDER BY identity_digest
"""

private const val READ_UNCATALOGED_RELATION_SUBTREE_SQL = """
    SELECT $CURRENT_OBJECT_COLUMNS
      FROM catalog.current_objects
     WHERE workspace_id = ?
       AND connector_id = ?
       AND catalog_name IS NULL
       AND schema_name = ?
       AND object_name = ?
       AND object_kind IN ('RELATION', 'COLUMN', 'RELATIONSHIP')
     ORDER BY identity_digest
"""

private const val SEARCH_CURRENT_RELATIONS_SQL = """
    SELECT object_id, connector_id, qualified_name, lower(qualified_name) AS normalized_qualified_name
      FROM catalog.current_objects
     WHERE workspace_id = ?
       AND object_kind = 'RELATION'
       AND strpos(lower(qualified_name), ?) > 0
     ORDER BY lower(qualified_name), connector_id, object_id
     LIMIT ?
"""

private const val SEARCH_CURRENT_RELATIONS_AFTER_SQL = """
    SELECT object_id, connector_id, qualified_name, lower(qualified_name) AS normalized_qualified_name
      FROM catalog.current_objects
     WHERE workspace_id = ?
       AND object_kind = 'RELATION'
       AND strpos(lower(qualified_name), ?) > 0
       AND (lower(qualified_name), connector_id, object_id) > (?, ?, ?)
     ORDER BY lower(qualified_name), connector_id, object_id
     LIMIT ?
"""

private const val MAXIMUM_CATALOG_RELATION_PAGE_SIZE = 100

private data class CurrentRelationRow(
    val summary: CatalogCurrentRelationSummary,
    val cursor: CatalogCurrentRelationPageCursor,
)

private fun ResultSet.toCurrentRelationRow(): CurrentRelationRow {
    val connectorId = ConnectorId(getObject("connector_id", UUID::class.java).toKotlinUuid())
    val objectId = CatalogObjectId(getObject("object_id", UUID::class.java).toKotlinUuid())
    return CurrentRelationRow(
        summary = CatalogCurrentRelationSummary(
            id = objectId,
            connectorId = connectorId,
            qualifiedName = getString("qualified_name"),
        ),
        cursor = CatalogCurrentRelationPageCursor(
            normalizedQualifiedName = getString("normalized_qualified_name"),
            connectorId = connectorId,
            objectId = objectId,
        ),
    )
}

private fun ResultSet.toCurrentObject(): CatalogCurrentObject {
    val kind = CatalogObjectKind.valueOf(getString("object_kind"))
    val relationKind = getString("relation_kind")?.let(RelationKind::valueOf)
    val address = CatalogPhysicalAddress(
        catalogName = getString("catalog_name"),
        schemaName = getString("schema_name"),
        objectName = getString("object_name"),
        memberName = getString("member_name"),
    )
    return CatalogCurrentObject(
        id = CatalogObjectId(getObject("object_id", UUID::class.java).toKotlinUuid()),
        identityDigest = CatalogDigest(getString("identity_digest")),
        parentId = getObject("parent_object_id", UUID::class.java)?.toKotlinUuid()?.let(::CatalogObjectId),
        kind = kind,
        relationKind = relationKind,
        address = address,
        qualifiedName = getString("qualified_name"),
        nativeType = getString("native_type"),
        contentDigest = CatalogDigest(getString("content_digest")),
        definition = getString("relation_definition"),
        partitionKeys = (getArray("relation_partition_keys")?.array as? Array<*>)?.map { it as String },
        clusteringKeys = (getArray("relation_clustering_keys")?.array as? Array<*>)?.map { it as String },
        requiresPartitionFilter = getBoolean("relation_requires_partition_filter").let { if (wasNull()) null else it },
        ordinal = getInt("column_ordinal").let { if (wasNull()) null else it },
        nullable = getBoolean("column_nullable").let { if (wasNull()) null else it },
        defaultExpression = getString("column_default_expression"),
        relationshipKind = getString("relationship_kind")?.let(CatalogRelationshipKind::valueOf),
        memberOrdinal = getInt("relationship_member_ordinal").let { if (wasNull()) null else it },
        constraintName = getString("relationship_constraint_name"),
        sourceColumn = getString("relationship_source_column"),
        referencedCatalog = getString("relationship_referenced_catalog"),
        referencedSchema = getString("relationship_referenced_schema"),
        referencedRelation = getString("relationship_referenced_relation"),
        referencedColumn = getString("relationship_referenced_column"),
        observedAt = getTimestamp("observed_at").toInstant(),
    )
}
