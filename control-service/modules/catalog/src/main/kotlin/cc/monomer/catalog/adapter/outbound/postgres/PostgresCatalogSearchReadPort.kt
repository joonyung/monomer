@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.adapter.outbound.postgres

import cc.monomer.catalog.api.*
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronizationManager

class PostgresCatalogSearchReadPort(private val dataSource: DataSource) : CatalogSearchReadPort {
    override fun readCurrentListings(
        workspaceId: WorkspaceId,
        addresses: Set<CatalogSearchAddress>,
    ): List<CatalogSearchListing> {
        if (addresses.isEmpty()) return emptyList()
        val predicate = addresses.joinToString(" OR ", "(", ")") {
            "(connector_id = ? AND catalog_name IS NOT DISTINCT FROM ? AND schema_name IS NOT DISTINCT FROM ? AND object_name IS NOT DISTINCT FROM ?)"
        }
        return query("SELECT * FROM ($CURRENT_NAMES) names WHERE workspace_id = ? AND $predicate", { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            addresses.forEachIndexed { index, address ->
                val offset = 2 + index * 4
                statement.setObject(offset, address.connectorId.value.toJavaUuid())
                statement.setString(offset + 1, address.catalogName)
                statement.setString(offset + 2, address.schemaName)
                statement.setString(offset + 3, address.relationName)
            }
        }) { result ->
            val kind = CatalogObjectKind.valueOf(result.getString("object_kind"))
            CatalogSearchListing(result.address(), kind,
                result.getString("relation_kind")?.let(RelationKind::valueOf),
                if (kind == CatalogObjectKind.RELATION) result.getTimestamp("observed_at").toInstant() else null)
        }
    }

    override fun readRelationColumnNames(
        workspaceId: WorkspaceId,
        relations: Set<CatalogSearchAddress>,
    ): Map<CatalogSearchAddress, List<String>> {
        require(relations.all { it.schemaName != null && it.relationName != null })
        if (relations.isEmpty()) return emptyMap()
        val predicate = relations.joinToString(" OR ", "(", ")") {
            "(connector_id = ? AND catalog_name IS NOT DISTINCT FROM ? AND schema_name = ? AND object_name = ?)"
        }
        val rows = query(
            """
            SELECT connector_id, catalog_name, schema_name, object_name, member_name
              FROM catalog.current_objects
             WHERE workspace_id = ? AND object_kind = 'COLUMN' AND $predicate
             ORDER BY connector_id, catalog_name, schema_name, object_name, column_ordinal
            """.trimIndent(),
            { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                relations.forEachIndexed { index, address ->
                    val offset = 2 + index * 4
                    statement.setObject(offset, address.connectorId.value.toJavaUuid())
                    statement.setString(offset + 1, address.catalogName)
                    statement.setString(offset + 2, address.schemaName)
                    statement.setString(offset + 3, address.relationName)
                }
            },
        ) { result -> result.address() to result.getString("member_name") }
        return rows.groupBy({ it.first }, { it.second })
    }

    override fun inventory(): List<CatalogSearchFields> = query(
        """
        SELECT names.*, ARRAY(
            SELECT member_name FROM catalog.current_objects columns
             WHERE columns.workspace_id = names.workspace_id AND columns.connector_id = names.connector_id
               AND columns.object_kind = 'COLUMN' AND names.object_kind = 'RELATION'
               AND columns.catalog_name IS NOT DISTINCT FROM names.catalog_name
               AND columns.schema_name IS NOT DISTINCT FROM names.schema_name
               AND columns.object_name = names.object_name ORDER BY columns.column_ordinal
        ) AS column_names FROM ($CURRENT_NAMES) names
        """.trimIndent(), {},
    ) { result ->
        val address = result.address()
        val kind = CatalogObjectKind.valueOf(result.getString("object_kind"))
        val primaryName = when (kind) {
            CatalogObjectKind.CATALOG -> checkNotNull(address.catalogName)
            CatalogObjectKind.SCHEMA -> checkNotNull(address.schemaName)
            CatalogObjectKind.RELATION -> checkNotNull(address.relationName)
            else -> error("Unsupported search object")
        }
        val columns = result.getArray("column_names")
        val columnNames = try { (columns.array as Array<*>).map { it as String } } finally { columns.free() }
        CatalogSearchFields(WorkspaceId(result.getObject("workspace_id", UUID::class.java).toKotlinUuid()),
            address, kind, listOf(primaryName), when (kind) {
                CatalogObjectKind.CATALOG -> emptyList()
                CatalogObjectKind.SCHEMA -> listOfNotNull(address.catalogName)
                else -> listOfNotNull(address.catalogName, address.schemaName, result.getString("relation_kind")) + columnNames
            })
    }

    private fun <T> query(sql: String, bind: (java.sql.PreparedStatement) -> Unit, map: (ResultSet) -> T): List<T> {
        check(TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.hasResource(dataSource)) {
            "Catalog search reads require the transaction bound to the exact DataSource"
        }
        val connection = DataSourceUtils.getConnection(dataSource)
        return try {
            check(!connection.autoCommit) { "Catalog search reads require a non-autocommit transaction" }
            connection.prepareStatement(sql).use { statement ->
                bind(statement)
                statement.executeQuery().use { result -> buildList { while (result.next()) add(map(result)) } }
            }
        } finally { DataSourceUtils.releaseConnection(connection, dataSource) }
    }

    private fun ResultSet.address() = CatalogSearchAddress(
        ConnectorId(getObject("connector_id", UUID::class.java).toKotlinUuid()),
        getString("catalog_name"), getString("schema_name"), getString("object_name"),
    )
}

private val CURRENT_NAMES = """
    SELECT workspace_id, connector_id, selected_name AS catalog_name, NULL::text AS schema_name,
           NULL::text AS object_name, 'CATALOG'::text AS object_kind, NULL::text AS relation_kind,
           NULL::timestamptz AS observed_at
      FROM catalog.connector_catalog_selections,
           LATERAL jsonb_array_elements_text(selected_catalog_names) selected_name
    UNION ALL
    SELECT workspace_id, connector_id, catalog_name, schema_name, object_name, object_kind,
           relation_kind, observed_at
      FROM catalog.current_objects
     WHERE object_kind IN ('SCHEMA', 'RELATION')
""".trimIndent()
