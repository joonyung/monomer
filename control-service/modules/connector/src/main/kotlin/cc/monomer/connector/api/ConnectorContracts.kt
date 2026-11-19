@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.connector.api

import cc.monomer.identity.api.WorkspaceId
import cc.monomer.execution.api.ConnectorRuntimeBindingSpec
import java.time.Instant
import kotlin.uuid.Uuid

@JvmInline
value class ConnectorId(val value: Uuid) {
    init {
        value.requireUuidV7("connector id")
    }

    companion object {
        fun generate(): ConnectorId = ConnectorId(Uuid.generateV7())
    }

    override fun toString(): String = value.toString()
}

@JvmInline
value class CredentialVersionId(val value: Uuid) {
    init {
        value.requireUuidV7("credential version id")
    }

    companion object {
        fun generate(): CredentialVersionId = CredentialVersionId(Uuid.generateV7())
    }

    override fun toString(): String = value.toString()
}

data class ContentDigest(val value: String) {
    init {
        require(value.matches(Regex("^sha256:[0-9a-f]{64}$"))) {
            "Content digest must be a lowercase sha256 digest"
        }
    }
}

data class CredentialEnvelopeReference(
    val credentialVersionId: CredentialVersionId,
    val keyVersion: String,
    val envelopeDigest: ContentDigest,
) {
    init {
        require(
            keyVersion.length in 1..512 &&
                keyVersion.matches(CREDENTIAL_KEY_VERSION_PATTERN) &&
                keyVersion.split('/').none { segment -> segment == "." || segment == ".." },
        ) {
            "Key version must be a canonical secret-store version identifier"
        }
    }

    override fun toString(): String =
        "CredentialEnvelopeReference(" +
            "credentialVersionId=$credentialVersionId, " +
            "keyVersion=[REDACTED], envelopeDigest=$envelopeDigest)"
}

private val CREDENTIAL_KEY_VERSION_PATTERN = Regex("^[A-Za-z0-9._~:/-]+$")

private fun Uuid.requireUuidV7(label: String) {
    require(this != Uuid.NIL) { "$label must not be nil" }
    val bytes = toByteArray()
    require(bytes[8].toInt().and(0xc0) == 0x80) {
        "$label must use the IETF UUID variant"
    }
    require(bytes[6].toInt().and(0xf0) == 0x70) {
        "$label must be UUIDv7"
    }
}

/** The stable, current Connector binding used by a physical execution admission. */
data class ConnectorExecutionBinding(
    val connectorId: ConnectorId,
    val workspaceId: WorkspaceId,
    val connectorType: String,
    val displayName: String,
    val properties: Map<String, String>,
    val credentialReference: CredentialEnvelopeReference,
    val updatedAt: Instant,
) {
    init {
        require(connectorType.isNotBlank()) { "Connector type must not be blank" }
        require(displayName.isNotBlank()) { "Connector display name must not be blank" }
        require(properties.keys.none(String::isBlank)) { "Connector property keys must not be blank" }
        require(properties.values.none(String::isBlank)) { "Connector property values must not be blank" }
    }
}

/** The current Connector state returned by a mutation. Secret material is never included. */
data class ConnectorMutationView(
    val connectorId: ConnectorId,
    val workspaceId: WorkspaceId,
    val connectorType: String,
    val displayName: String,
    val properties: ConnectorSettingsProperties,
    val credentialVersionId: CredentialVersionId,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(connectorType.isNotBlank()) { "Connector type must not be blank" }
        require(displayName.isNotBlank()) { "Connector display name must not be blank" }
    }
}

sealed interface ConnectorExecutionAccessResult {
    data class Granted(
        val binding: ConnectorExecutionBinding,
    ) : ConnectorExecutionAccessResult

    data object NotFoundOrOutsideWorkspace : ConnectorExecutionAccessResult
}

interface ConnectorExecutionAccessUseCase {
    suspend fun readCurrentForExecution(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
    ): ConnectorExecutionAccessResult
}

interface ConnectorExecutionAccessInOwnerTransactionPort {
    fun readCurrentForExecutionInOwnerTransaction(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
    ): ConnectorExecutionAccessResult
}

fun interface ConnectorRuntimeBindingProjectionPort {
    fun project(binding: ConnectorExecutionBinding): ConnectorRuntimeBindingSpec
}
