@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.connector.domain

import cc.monomer.connector.api.ConnectorExecutionBinding
import cc.monomer.connector.api.ConnectorSettingsProperties
import cc.monomer.connector.api.storageValues
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorMutationView
import cc.monomer.connector.api.ContentDigest
import cc.monomer.connector.api.CredentialEnvelopeReference
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import java.time.Instant

/** The only durable Connector configuration. Type is fixed when this aggregate is created. */
data class Connector(
    val id: ConnectorId,
    val workspaceId: WorkspaceId,
    val connectorType: ConnectorType,
    val displayName: String,
    val properties: ConnectorSettingsProperties,
    val credentialReference: CredentialEnvelopeReference,
    val authorityRevision: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
) {
    init {
        require(properties.connectorType.name.lowercase() == connectorType.value) { "Connector property type does not match" }
        require(displayName.isNotBlank()) { "Connector display name must not be blank" }
        require(authorityRevision > 0) { "Connector authority revision must be positive" }
        require(updatedAt >= createdAt) { "Connector update cannot precede creation" }
        require(deletedAt == null || deletedAt >= createdAt) {
            "Connector deletion cannot precede creation"
        }
    }

    fun revise(
        displayName: String,
        properties: ConnectorSettingsProperties,
        credentialReference: CredentialEnvelopeReference,
        committedAt: Instant,
    ): Connector {
        check(deletedAt == null) { "Deleted connector cannot be revised" }
        require(connectorType.value != "duckdb") { "Sample connectors are managed by Monomer" }
        require(properties.connectorType.name.lowercase() == connectorType.value) { "Connector property type does not match" }
        require(displayName.isNotBlank()) { "Connector display name must not be blank" }
        require(committedAt >= updatedAt) { "Connector update cannot precede the previous update" }
        if (connectorType.value in setOf("postgresql", "redshift")) {
            require(properties.storageValues().getValue("database") == this.properties.storageValues().getValue("database")) {
                "The database of a PostgreSQL or Redshift connector cannot change; create a new connector"
            }
        }
        return copy(
            displayName = displayName,
            properties = properties,
            credentialReference = credentialReference,
            authorityRevision = Math.addExact(authorityRevision, 1),
            updatedAt = committedAt,
        )
    }

    fun toMutationView(): ConnectorMutationView =
        ConnectorMutationView(
            connectorId = id,
            workspaceId = workspaceId,
            connectorType = connectorType.value,
            displayName = displayName,
            properties = properties,
            credentialVersionId = credentialReference.credentialVersionId,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    fun toExecutionBinding(): ConnectorExecutionBinding =
        ConnectorExecutionBinding(
            connectorId = id,
            workspaceId = workspaceId,
            connectorType = connectorType.value,
            displayName = displayName,
            properties = properties.storageValues(),
            credentialReference = credentialReference,
            updatedAt = updatedAt,
        )
}

@JvmInline
value class ConnectorType(val value: String) {
    init {
        require(value in setOf("bigquery", "databricks", "postgresql", "redshift", "snowflake", "trino", "duckdb")) {
            "Connector type must be one of bigquery, databricks, postgresql, redshift, snowflake, trino, or duckdb"
        }
    }
}

enum class ConnectorMutationKind {
    CREATE,
    REVISE,
    ROTATE_CREDENTIAL,
}

data class ConnectorMutationReceipt(
    val workspaceId: WorkspaceId,
    val mutationIdempotencyKey: String,
    val actorId: PrincipalId,
    val mutationKind: ConnectorMutationKind,
    val authorizationScopeDigest: ContentDigest,
    val requestDigest: ContentDigest,
    val connectorId: ConnectorId,
    val credentialVersionId: cc.monomer.connector.api.CredentialVersionId,
    val committedAt: Instant,
) {
    init {
        require(mutationIdempotencyKey.isNotBlank()) {
            "Connector mutation idempotency key must not be blank"
        }
    }
}
