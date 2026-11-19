@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.connector.adapter.outbound.postgres

import cc.monomer.connector.api.ConnectorSearchReadPort
import cc.monomer.connector.api.ConnectorSearchFields
import cc.monomer.connector.api.connectorSearchFields
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorProductType
import cc.monomer.connector.api.ContentDigest
import cc.monomer.connector.api.CredentialEnvelopeReference
import cc.monomer.connector.application.ConnectorCurrentReadQueryPort
import cc.monomer.connector.application.ConnectorExecutionAccessCandidate
import cc.monomer.connector.application.ConnectorRepository
import cc.monomer.connector.domain.Connector
import cc.monomer.connector.domain.ConnectorMutationKind
import cc.monomer.connector.domain.ConnectorMutationReceipt
import cc.monomer.connector.api.connectorPropertiesFromStorage
import cc.monomer.connector.api.storageValues
import cc.monomer.connector.domain.ConnectorType
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronizationManager

class PostgresConnectorRepository(
    private val dataSource: DataSource,
    private val objectMapper: ObjectMapper,
) : ConnectorRepository, ConnectorCurrentReadQueryPort, ConnectorSearchReadPort {
    override fun readCurrentListings(workspaceId: WorkspaceId, ids: Set<ConnectorId>): List<cc.monomer.connector.api.CurrentConnectorRead> {
        requireSearchReadTransaction()
        return if (ids.isEmpty()) emptyList() else findCurrentByWorkspaceAndIds(workspaceId, ids)
    }

    override fun inventory(): List<ConnectorSearchFields> {
        requireSearchReadTransaction()
        return findCurrentRead("TRUE") {}.map { current ->
            connectorSearchFields(current.workspaceId, current.connectorId, current.displayName, current.properties)
        }
    }

    private fun requireSearchReadTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.hasResource(dataSource)) {
            "Connector search reads require the transaction bound to the exact DataSource"
        }
    }

    override fun countCurrentConnectors(workspaceId: WorkspaceId): Long =
        withConnection(requireTransaction = true) { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM connector.connectors WHERE workspace_id = ? AND deleted_at IS NULL",
            ).use { statement ->
                statement.setUuid(1, workspaceId.value)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    resultSet.getLong(1)
                }
            }
        }

    override fun findConnector(connectorId: ConnectorId): Connector? =
        queryOne(
            """
            SELECT connectors.*, credentials.key_version, credentials.envelope_digest
            FROM connector.connectors connectors
            JOIN connector.credential_versions credentials
              ON credentials.workspace_id = connectors.workspace_id
             AND credentials.connector_id = connectors.connector_id
             AND credentials.credential_version_id = connectors.credential_version_id
            WHERE connectors.connector_id = ? AND connectors.deleted_at IS NULL
            """.trimIndent(),
            { statement -> statement.setUuid(1, connectorId.value) },
            { resultSet -> resultSet.toConnector() },
        )

    override fun findSampleConnector(workspaceId: WorkspaceId): Connector? = queryOne(
        """SELECT connectors.*, credentials.key_version, credentials.envelope_digest
           FROM connector.connectors connectors
           JOIN connector.credential_versions credentials ON credentials.workspace_id = connectors.workspace_id
              AND credentials.connector_id = connectors.connector_id AND credentials.credential_version_id = connectors.credential_version_id
           WHERE connectors.workspace_id = ? AND connectors.connector_type = 'duckdb' AND connectors.deleted_at IS NULL""",
        { statement -> statement.setUuid(1, workspaceId.value) }, { resultSet -> resultSet.toConnector() },
    )

    override fun insertConnector(connector: Connector) {
        check(
            update(
                """
                INSERT INTO connector.connectors (
                    connector_id, workspace_id, connector_type, display_name, properties,
                    credential_version_id, authority_revision, created_at, updated_at, deleted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ) { statement ->
                statement.setUuid(1, connector.id.value)
                statement.setUuid(2, connector.workspaceId.value)
                statement.setString(3, connector.connectorType.value)
                statement.setString(4, connector.displayName)
                statement.setJson(5, objectMapper.writeValueAsString(connector.properties.storageValues()))
                statement.setUuid(6, connector.credentialReference.credentialVersionId.value)
                statement.setLong(7, connector.authorityRevision)
                statement.setInstant(8, connector.createdAt)
                statement.setInstant(9, connector.updatedAt)
                statement.setNullableInstant(10, connector.deletedAt)
            } == 1
        )
    }

    override fun updateConnector(connector: Connector, expectedAuthorityRevision: Long): Boolean =
        update(
            """
            UPDATE connector.connectors
            SET display_name = ?, properties = ?, credential_version_id = ?,
                authority_revision = ?, updated_at = ?
            WHERE connector_id = ? AND workspace_id = ? AND authority_revision = ? AND deleted_at IS NULL
            """.trimIndent(),
        ) { statement ->
            statement.setString(1, connector.displayName)
            statement.setJson(2, objectMapper.writeValueAsString(connector.properties.storageValues()))
            statement.setUuid(3, connector.credentialReference.credentialVersionId.value)
            statement.setLong(4, connector.authorityRevision)
            statement.setInstant(5, connector.updatedAt)
            statement.setUuid(6, connector.id.value)
            statement.setUuid(7, connector.workspaceId.value)
            statement.setLong(8, expectedAuthorityRevision)
        } == 1

    override fun markDeleted(connector: Connector, deletedAt: Instant): Boolean =
        update(
            """
            UPDATE connector.connectors
            SET deleted_at = ?, credential_version_id = NULL, authority_revision = authority_revision + 1
            WHERE connector_id = ? AND workspace_id = ? AND authority_revision = ? AND deleted_at IS NULL
            """.trimIndent(),
        ) { statement ->
            statement.setInstant(1, deletedAt)
            statement.setUuid(2, connector.id.value)
            statement.setUuid(3, connector.workspaceId.value)
            statement.setLong(4, connector.authorityRevision)
        } == 1

    override fun findMutationReceipt(
        workspaceId: WorkspaceId,
        mutationIdempotencyKey: String,
    ): ConnectorMutationReceipt? =
        queryOne(
            """
            SELECT *
            FROM connector.connector_mutation_receipts
            WHERE workspace_id = ? AND mutation_idempotency_key = ?
            """.trimIndent(),
            { statement ->
                statement.setUuid(1, workspaceId.value)
                statement.setString(2, mutationIdempotencyKey)
            },
            { resultSet -> resultSet.toMutationReceipt() },
        )

    override fun insertMutationReceipt(receipt: ConnectorMutationReceipt) {
        check(
            update(
                """
                INSERT INTO connector.connector_mutation_receipts (
                    workspace_id, mutation_idempotency_key, actor_id, mutation_kind,
                    authorization_scope_digest, request_digest, connector_id,
                    credential_version_id, committed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ) { statement ->
                statement.setUuid(1, receipt.workspaceId.value)
                statement.setString(2, receipt.mutationIdempotencyKey)
                statement.setUuid(3, receipt.actorId.value)
                statement.setString(4, receipt.mutationKind.name)
                statement.setString(5, receipt.authorizationScopeDigest.value)
                statement.setString(6, receipt.requestDigest.value)
                statement.setUuid(7, receipt.connectorId.value)
                statement.setUuid(8, receipt.credentialVersionId.value)
                statement.setInstant(9, receipt.committedAt)
            } == 1
        )
    }

    override fun findCurrentByWorkspaceAndId(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
    ): cc.monomer.connector.api.CurrentConnectorRead? =
        findCurrentRead(
            predicate = "connectors.workspace_id = ? AND connectors.connector_id = ?",
        ) { statement ->
            statement.setUuid(1, workspaceId.value)
            statement.setUuid(2, connectorId.value)
        }.firstOrNull()

    override fun findCurrentByWorkspaceAndIds(
        workspaceId: WorkspaceId,
        connectorIds: Set<ConnectorId>,
    ): List<cc.monomer.connector.api.CurrentConnectorRead> {
        if (connectorIds.isEmpty()) return emptyList()
        val orderedConnectorIds = connectorIds.sortedBy { connectorId -> connectorId.value.toString() }
        val placeholders = List(orderedConnectorIds.size) { "?" }.joinToString(", ")
        return findCurrentRead(
            predicate = "connectors.workspace_id = ? AND connectors.connector_id IN ($placeholders)",
        ) { statement ->
            statement.setUuid(1, workspaceId.value)
            orderedConnectorIds.forEachIndexed { index, connectorId ->
                statement.setUuid(index + 2, connectorId.value)
            }
        }
    }

    override fun listCurrentByWorkspace(
        workspaceId: WorkspaceId,
    ): List<cc.monomer.connector.api.CurrentConnectorRead> =
        findCurrentRead("connectors.workspace_id = ?") { statement ->
            statement.setUuid(1, workspaceId.value)
        }

    override fun findCurrentExecutionAccessCandidate(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
    ): ConnectorExecutionAccessCandidate? =
        queryOne(
            """
            SELECT connectors.*, credentials.key_version, credentials.envelope_digest
            FROM connector.connectors connectors
            JOIN connector.credential_versions credentials
              ON credentials.workspace_id = connectors.workspace_id
             AND credentials.connector_id = connectors.connector_id
             AND credentials.credential_version_id = connectors.credential_version_id
            WHERE connectors.workspace_id = ?
              AND connectors.connector_id = ?
              AND connectors.deleted_at IS NULL
            """.trimIndent(),
            { statement ->
                statement.setUuid(1, workspaceId.value)
                statement.setUuid(2, connectorId.value)
            },
            { resultSet -> ConnectorExecutionAccessCandidate(resultSet.toConnector()) },
        )

    private fun findCurrentRead(
        predicate: String,
        binder: (PreparedStatement) -> Unit,
    ): List<cc.monomer.connector.api.CurrentConnectorRead> =
        queryMany(
            """
            SELECT connectors.connector_id, connectors.workspace_id, connectors.connector_type,
                   connectors.display_name, connectors.properties, connectors.created_at,
                   connectors.updated_at
            FROM connector.connectors connectors
            JOIN connector.credential_versions credentials
              ON credentials.workspace_id = connectors.workspace_id
             AND credentials.connector_id = connectors.connector_id
             AND credentials.credential_version_id = connectors.credential_version_id
            WHERE $predicate AND connectors.deleted_at IS NULL
            ORDER BY connectors.created_at ASC, connectors.connector_id ASC
            """.trimIndent(),
            binder,
        ) { resultSet ->
            val properties = resultSet.readProperties()
            cc.monomer.connector.api.CurrentConnectorRead(
                connectorId = ConnectorId(resultSet.getUuid("connector_id")),
                workspaceId = WorkspaceId(resultSet.getUuid("workspace_id")),
                connectorType = ConnectorProductType.fromStorageValue(resultSet.getString("connector_type")),
                displayName = resultSet.getString("display_name"),
                properties = connectorPropertiesFromStorage(ConnectorProductType.fromStorageValue(resultSet.getString("connector_type")), properties),
                createdAt = resultSet.getInstant("created_at"),
                updatedAt = resultSet.getInstant("updated_at"),
            )
        }

    private fun ResultSet.toConnector(): Connector {
        val type = ConnectorType(getString("connector_type"))
        return Connector(
            id = ConnectorId(getUuid("connector_id")),
            workspaceId = WorkspaceId(getUuid("workspace_id")),
            connectorType = type,
            displayName = getString("display_name"),
            properties = connectorPropertiesFromStorage(ConnectorProductType.fromStorageValue(type.value), readProperties()),
            credentialReference = CredentialEnvelopeReference(
                credentialVersionId = cc.monomer.connector.api.CredentialVersionId(
                    getUuid("credential_version_id"),
                ),
                keyVersion = getString("key_version"),
                envelopeDigest = ContentDigest(getString("envelope_digest")),
            ),
            authorityRevision = getLong("authority_revision"),
            createdAt = getInstant("created_at"),
            updatedAt = getInstant("updated_at"),
            deletedAt = getNullableInstant("deleted_at"),
        )
    }

    private fun ResultSet.toMutationReceipt(): ConnectorMutationReceipt =
        ConnectorMutationReceipt(
            workspaceId = WorkspaceId(getUuid("workspace_id")),
            mutationIdempotencyKey = getString("mutation_idempotency_key"),
            actorId = PrincipalId(getUuid("actor_id")),
            mutationKind = ConnectorMutationKind.valueOf(getString("mutation_kind")),
            authorizationScopeDigest = ContentDigest(getString("authorization_scope_digest")),
            requestDigest = ContentDigest(getString("request_digest")),
            connectorId = ConnectorId(getUuid("connector_id")),
            credentialVersionId = cc.monomer.connector.api.CredentialVersionId(
                getUuid("credential_version_id"),
            ),
            committedAt = getInstant("committed_at"),
        )

    private fun ResultSet.readProperties(): Map<String, String> {
        val propertiesNode = objectMapper.readTree(getString("properties"))
        check(propertiesNode.isObject) { "Connector properties must be a JSON object" }
        return propertiesNode.properties().asSequence().associate { (key, value) ->
            check(value.isTextual) { "Connector properties must contain strings" }
            key to value.textValue()
        }.toSortedMap()
    }

    private fun update(sql: String, binder: (PreparedStatement) -> Unit): Int =
        withConnection(requireTransaction = true) { connection ->
            connection.prepareStatement(sql).use { statement ->
                binder(statement)
                statement.executeUpdate()
            }
        }

    private fun <T> queryOne(
        sql: String,
        binder: (PreparedStatement) -> Unit,
        mapper: (ResultSet) -> T,
    ): T? = withConnection(requireTransaction = false) { connection ->
        connection.prepareStatement(sql).use { statement ->
            binder(statement)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) mapper(resultSet) else null
            }
        }
    }

    private fun <T> queryMany(
        sql: String,
        binder: (PreparedStatement) -> Unit,
        mapper: (ResultSet) -> T,
    ): List<T> = withConnection(requireTransaction = false) { connection ->
        connection.prepareStatement(sql).use { statement ->
            binder(statement)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) add(mapper(resultSet))
                }
            }
        }
    }

    private fun <T> withConnection(requireTransaction: Boolean, block: (Connection) -> T): T {
        if (requireTransaction) {
            check(TransactionSynchronizationManager.isActualTransactionActive()) {
                "Connector writes require an active owner transaction"
            }
            check(TransactionSynchronizationManager.hasResource(dataSource)) {
                "Connector writes require the transaction bound to the exact DataSource"
            }
        }
        val connection = DataSourceUtils.getConnection(dataSource)
        return try {
            block(connection)
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }
}

internal fun PreparedStatement.setUuid(index: Int, value: Uuid) = setObject(index, value.toJavaUuid())

internal fun PreparedStatement.setInstant(index: Int, value: Instant) =
    setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))

private fun PreparedStatement.setNullableInstant(index: Int, value: Instant?) {
    if (value == null) setNull(index, Types.TIMESTAMP_WITH_TIMEZONE) else setInstant(index, value)
}

private fun PreparedStatement.setJson(index: Int, value: String) = setObject(index, value, Types.OTHER)

internal fun ResultSet.getUuid(columnLabel: String): Uuid =
    getObject(columnLabel, UUID::class.java).toKotlinUuid()

private fun ResultSet.getInstant(columnLabel: String): Instant =
    getObject(columnLabel, OffsetDateTime::class.java).toInstant()

private fun ResultSet.getNullableInstant(columnLabel: String): Instant? =
    getObject(columnLabel, OffsetDateTime::class.java)?.toInstant()
