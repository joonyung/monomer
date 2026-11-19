@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.adapter.outbound.postgres

import cc.monomer.catalog.api.CatalogIngestEntry
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.catalog.api.CatalogRelationSelection
import cc.monomer.catalog.api.CatalogSearchAddress
import cc.monomer.catalog.api.CatalogSearchFields
import cc.monomer.catalog.api.CatalogSearchProjectionPort
import cc.monomer.catalog.api.CatalogSelectedSchema
import cc.monomer.catalog.api.CatalogSelection
import cc.monomer.catalog.api.ConnectorCatalogSelection
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.uuid.Uuid
import kotlin.uuid.toKotlinUuid
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Owner-transaction JDBC access shared by the Catalog PostgreSQL adapters. */
internal class CatalogOwnerJdbc(private val dataSource: DataSource) {
    fun lockConnector(workspaceId: WorkspaceId, connectorId: ConnectorId) {
        requireOwnerTransaction()
        lockAdvisory(workspaceId, "connector:${connectorId.value}")
    }

    private fun lockAdvisory(workspaceId: WorkspaceId, lockKey: String) {
        queryOne(
            """
            SELECT pg_advisory_xact_lock(hashtextextended(? || ':' || ?, 0))
            """.trimIndent(),
            { statement ->
                statement.setString(1, workspaceId.value.toString())
                statement.setString(2, lockKey)
            },
        ) { Unit }
    }

    fun requireOwnerTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Catalog repository access requires an active owner transaction"
        }
        check(TransactionSynchronizationManager.hasResource(dataSource)) {
            "Catalog repository access requires the exact owner DataSource"
        }
    }

    private fun ownerTransactionConnection(): Connection {
        requireOwnerTransaction()
        return DataSourceUtils.getConnection(dataSource)
    }

    fun <T> queryOne(
        sql: String,
        bind: (PreparedStatement) -> Unit,
        map: (ResultSet) -> T,
    ): T? {
        val connection = ownerTransactionConnection()
        return try {
            connection.prepareStatement(sql).use { statement ->
                bind(statement)
                statement.executeQuery().use { if (it.next()) map(it) else null }
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }

    fun <T> queryMany(
        sql: String,
        bind: (PreparedStatement) -> Unit,
        map: (ResultSet) -> T,
    ): List<T> = readRows(ownerTransactionConnection(), sql, bind, map)

    private fun <T> readRows(
        connection: Connection,
        sql: String,
        bind: (PreparedStatement) -> Unit,
        map: (ResultSet) -> T,
    ): List<T> = try {
        connection.prepareStatement(sql).use { statement ->
            bind(statement)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(map(result))
                }
            }
        }
    } finally {
        DataSourceUtils.releaseConnection(connection, dataSource)
    }

    fun update(sql: String, bind: (PreparedStatement) -> Unit): Int {
        val connection = ownerTransactionConnection()
        return try {
            connection.prepareStatement(sql).use { statement ->
                bind(statement)
                statement.executeUpdate()
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }
}

/** Encodes the Catalog selection JSON documents stored by selections and ingest jobs. */
internal class CatalogSelectionJsonCodec(private val objectMapper: ObjectMapper) {
    fun encodeConnectorCatalogSelection(selection: ConnectorCatalogSelection): String =
        objectMapper.writeValueAsString(selection.selectedCatalogNames.sorted())

    fun decodeConnectorCatalogSelection(raw: String): ConnectorCatalogSelection {
        val root = objectMapper.readTree(raw)
        require(root.isArray) { "Connector Catalog selection must be a JSON array" }
        val names = root.map { node ->
            require(node.isTextual) { "Connector Catalog selection names must be strings" }
            node.asText()
        }
        require(names.size == names.toSet().size) { "Connector Catalog names must be unique" }
        return ConnectorCatalogSelection(names.toSet())
    }

    fun encodeSelection(selection: CatalogSelection): String = objectMapper.writeValueAsString(
        mapOf(
            "selectedSchemas" to selection.selectedSchemas.sortedBy { it.schemaName }.map { selectedSchema ->
                mapOf(
                    "schemaName" to selectedSchema.schemaName,
                    "relations" to when (val relations = selectedSchema.relations) {
                        CatalogRelationSelection.AllRelations -> mapOf("kind" to "ALL_RELATIONS")
                        is CatalogRelationSelection.SelectedRelations -> mapOf(
                            "kind" to "SELECTED_RELATIONS",
                            "relationNames" to relations.relationNames.sorted(),
                        )
                    },
                )
            },
        ),
    )

    fun decodeSelection(raw: String): CatalogSelection {
        val root = objectMapper.readTree(raw)
        val schemaNodes = root.get("selectedSchemas")?.toList().orEmpty()
        val selectedSchemas = schemaNodes.map { node ->
            val relationNode = node.get("relations")
            val relations = when (relationNode.get("kind")?.asText()) {
                "ALL_RELATIONS" -> CatalogRelationSelection.AllRelations
                "SELECTED_RELATIONS" -> {
                    val relationNames = relationNode.get("relationNames")
                        ?.map(JsonNode::asText)
                        ?: error("Missing selected relation names")
                    require(relationNames.size == relationNames.toSet().size) {
                        "Selected relation names must be unique"
                    }
                    CatalogRelationSelection.SelectedRelations(relationNames.toSet())
                }
                else -> error("Unknown Catalog relation selection kind")
            }
            CatalogSelectedSchema(
                schemaName = node.get("schemaName")?.asText() ?: error("Missing Catalog schema name"),
                relations = relations,
            )
        }
        require(selectedSchemas.size == selectedSchemas.toSet().size) {
            "Selected schema names must be unique"
        }
        return CatalogSelection(selectedSchemas.toSet())
    }
}

internal fun CatalogSearchProjectionPort.projectSelectedCatalog(workspaceId: WorkspaceId, connectorId: ConnectorId, name: String) {
    upsert(CatalogSearchFields(workspaceId, CatalogSearchAddress(connectorId, name, null, null),
        CatalogObjectKind.CATALOG, listOf(name), emptyList()))
}

internal fun bindValues(statement: PreparedStatement, start: Int, values: List<Any?>) {
    values.forEachIndexed { index, value -> statement.setObject(start + index, value) }
}

internal fun CatalogIngestEntry.catalogName(): String? = when (this) {
    is CatalogIngestEntry.Connector -> null
    is CatalogIngestEntry.Catalog -> name
}

internal fun ResultSet.uuid(column: String): Uuid = getObject(column, UUID::class.java).toKotlinUuid()

internal fun ResultSet.timestamp(column: String): Instant = getTimestamp(column).toInstant()

internal fun PreparedStatement.setInstant(index: Int, value: Instant?) {
    setTimestamp(index, value?.let(Timestamp::from))
}
