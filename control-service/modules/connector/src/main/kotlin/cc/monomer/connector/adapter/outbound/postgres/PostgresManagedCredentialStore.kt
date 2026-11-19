@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.connector.adapter.outbound.postgres

import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ContentDigest
import cc.monomer.connector.api.CredentialEnvelopeReference
import cc.monomer.connector.application.CredentialMaterial
import cc.monomer.connector.application.ManagedCredentialDocumentCodec
import cc.monomer.connector.application.ManagedCredentialReadOutcome
import cc.monomer.connector.application.ManagedCredentialReadPort
import cc.monomer.connector.application.ManagedCredentialReadRequest
import cc.monomer.connector.application.ManagedCredentialWritePort
import cc.monomer.connector.application.ManagedCredentialWriteRequest
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.util.Arrays
import javax.sql.DataSource
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronizationManager

class PostgresManagedCredentialStore(
    private val dataSource: DataSource,
    private val keyRing: ManagedCredentialKeyRing,
    private val nonceSource: CredentialVaultNonceSource,
    private val clock: Clock,
    private val blockingContext: CoroutineContext,
    private val maximumCredentialDocumentBytes: Int,
    private val maximumCredentialFields: Int,
    private val maximumFieldNameBytes: Int,
    private val maximumFieldValueBytes: Int,
) : ManagedCredentialWritePort, ManagedCredentialReadPort {
    init {
        require(maximumCredentialDocumentBytes in 1..MAXIMUM_SCHEMA_DOCUMENT_BYTES)
        require(maximumCredentialFields > 0)
        require(maximumFieldNameBytes > 0)
        require(maximumFieldValueBytes > 0)
    }

    override fun writeInOwnerTransaction(
        request: ManagedCredentialWriteRequest,
        credentialMaterial: CredentialMaterial,
    ): CredentialEnvelopeReference {
        requireOwnerTransaction()
        credentialMaterial.requireCompatible(request.connectorType)
        val document = ManagedCredentialDocumentCodec.encode(
            credentialMaterial = credentialMaterial,
            maximumDocumentBytes = maximumCredentialDocumentBytes,
            maximumFields = maximumCredentialFields,
            maximumFieldNameBytes = maximumFieldNameBytes,
            maximumFieldValueBytes = maximumFieldValueBytes,
        )
        var nonce: ByteArray? = null
        var aad: ByteArray? = null
        var ciphertext: ByteArray? = null
        try {
            val generatedNonce = nonceSource.nextNonce()
            nonce = generatedNonce
            require(generatedNonce.size == GCM_NONCE_BYTES) { "Managed credential nonce must be exactly 12 bytes" }
            val keyVersion = keyRing.activeKeyVersion
            val additionalAuthenticatedData = canonicalCredentialAdditionalAuthenticatedData(
                workspaceId = request.workspaceId,
                connectorId = request.connectorId,
                credentialVersionId = request.credentialVersionId,
                connectorType = request.connectorType,
                keyVersion = keyVersion,
            )
            aad = additionalAuthenticatedData
            require(additionalAuthenticatedData.size <= MAXIMUM_ADDITIONAL_AUTHENTICATED_DATA_BYTES) {
                "Managed credential envelope metadata exceeds its bound"
            }
            val encryptedCredential = keyRing.useKey(keyVersion) { key ->
                encryptCredential(key, generatedNonce, additionalAuthenticatedData, document)
            } ?: throw ManagedCredentialKeyUnavailableException(null)
            ciphertext = encryptedCredential
            val envelopeDigest = credentialEnvelopeDigest(
                keyVersion = keyVersion,
                nonce = generatedNonce,
                additionalAuthenticatedData = additionalAuthenticatedData,
                ciphertext = encryptedCredential,
            )
            val inserted = withConnection { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO connector.credential_versions (
                        credential_version_id, workspace_id, connector_id, connector_type,
                        key_version, nonce, additional_authenticated_data, ciphertext,
                        envelope_digest, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setUuid(1, request.credentialVersionId.value)
                    statement.setUuid(2, request.workspaceId.value)
                    statement.setUuid(3, request.connectorId.value)
                    statement.setString(4, request.connectorType)
                    statement.setString(5, keyVersion)
                    statement.setBytes(6, nonce)
                    statement.setBytes(7, aad)
                    statement.setBytes(8, ciphertext)
                    statement.setString(9, envelopeDigest)
                    statement.setInstant(10, request.createdAt)
                    statement.executeUpdate()
                }
            }
            check(inserted == 1) { "Credential version was not stored" }
            return CredentialEnvelopeReference(
                credentialVersionId = request.credentialVersionId,
                keyVersion = keyVersion,
                envelopeDigest = ContentDigest(envelopeDigest),
            )
        } finally {
            Arrays.fill(document, 0)
            nonce?.let { Arrays.fill(it, 0) }
            aad?.let { Arrays.fill(it, 0) }
            ciphertext?.let { Arrays.fill(it, 0) }
        }
    }

    override fun matchesInOwnerTransaction(
        request: ManagedCredentialWriteRequest,
        credentialMaterial: CredentialMaterial,
    ): Boolean {
        requireOwnerTransaction()
        val stored = selectStored(request)
            ?: return false
        stored.use {
            val document = try {
                it.decrypt(keyRing)
            } catch (_: ManagedCredentialEnvelopeRejectedException) {
                return false
            } catch (_: ManagedCredentialKeyUnavailableException) {
                return false
            }
            val matches = document.useBytes { storedDocument ->
                val candidateDocument = ManagedCredentialDocumentCodec.encode(
                    credentialMaterial = credentialMaterial,
                    maximumDocumentBytes = maximumCredentialDocumentBytes,
                    maximumFields = maximumCredentialFields,
                    maximumFieldNameBytes = maximumFieldNameBytes,
                    maximumFieldValueBytes = maximumFieldValueBytes,
                )
                try {
                    MessageDigest.isEqual(storedDocument, candidateDocument)
                } finally {
                    Arrays.fill(candidateDocument, 0)
                }
            }
            return matches
        }
    }

    override fun destroyInOwnerTransaction(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        connectorId: ConnectorId,
    ): Int {
        requireOwnerTransaction()
        return withConnection { connection ->
            connection.prepareStatement(
                "DELETE FROM connector.credential_versions WHERE workspace_id = ? AND connector_id = ?",
            ).use { statement ->
                statement.setUuid(1, workspaceId.value)
                statement.setUuid(2, connectorId.value)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun readExact(request: ManagedCredentialReadRequest): ManagedCredentialReadOutcome =
        withContext(blockingContext) {
            requireBeforeDeadline(request.serverDeadline)
            val stored = selectStored(
                workspaceId = request.workspaceId,
                connectorId = request.connectorId,
                connectorType = request.connectorType,
                reference = request.reference,
                deadline = request.serverDeadline,
            ) ?: return@withContext ManagedCredentialReadOutcome.Rejected
            stored.use {
                val document = try {
                    it.decrypt(keyRing)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: ManagedCredentialEnvelopeRejectedException) {
                    return@withContext ManagedCredentialReadOutcome.Rejected
                } catch (_: ManagedCredentialKeyUnavailableException) {
                    return@withContext ManagedCredentialReadOutcome.Unavailable
                }
                try {
                    if (document.size > maximumCredentialDocumentBytes) {
                        return@withContext ManagedCredentialReadOutcome.Rejected
                    }
                    val material = try {
                        ManagedCredentialDocumentCodec.decode(
                            document = document,
                            maximumFields = maximumCredentialFields,
                            maximumFieldNameBytes = maximumFieldNameBytes,
                            maximumFieldValueBytes = maximumFieldValueBytes,
                        )
                    } catch (_: Exception) {
                        return@withContext ManagedCredentialReadOutcome.Rejected
                    }
                    val executionMaterial =
                        try {
                            material.normalizedForExecution(request.connectorType)
                        } finally {
                            material.close()
                        }
                    ManagedCredentialReadOutcome.Found(executionMaterial)
                } finally {
                    Arrays.fill(document, 0)
                }
            }
        }

    private fun selectStored(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        connectorId: ConnectorId,
        connectorType: String,
        reference: CredentialEnvelopeReference,
        deadline: Instant,
    ): StoredCredential? = withConnection { connection ->
        connection.prepareStatement(
            """
            SELECT credential_version_id, workspace_id, connector_id, connector_type,
                   key_version, nonce, additional_authenticated_data, ciphertext,
                   envelope_digest, created_at
            FROM connector.credential_versions
            WHERE workspace_id = ? AND connector_id = ? AND connector_type = ?
              AND credential_version_id = ? AND key_version = ? AND envelope_digest = ?
            """.trimIndent(),
        ).use { statement ->
            statement.queryTimeout = deadline.toQueryTimeoutSeconds()
            statement.setUuid(1, workspaceId.value)
            statement.setUuid(2, connectorId.value)
            statement.setString(3, connectorType)
            statement.setUuid(4, reference.credentialVersionId.value)
            statement.setString(5, reference.keyVersion)
            statement.setString(6, reference.envelopeDigest.value)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toStoredCredential() else null
            }
        }
    }

    private fun selectStored(request: ManagedCredentialWriteRequest): StoredCredential? =
        withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT credential_version_id, workspace_id, connector_id, connector_type,
                       key_version, nonce, additional_authenticated_data, ciphertext,
                       envelope_digest, created_at
                FROM connector.credential_versions
                WHERE workspace_id = ? AND connector_id = ? AND connector_type = ?
                  AND credential_version_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setUuid(1, request.workspaceId.value)
                statement.setUuid(2, request.connectorId.value)
                statement.setString(3, request.connectorType)
                statement.setUuid(4, request.credentialVersionId.value)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.toStoredCredential() else null
                }
            }
        }

    private fun requireOwnerTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Credential writes require an active owner transaction"
        }
        check(TransactionSynchronizationManager.hasResource(dataSource)) {
            "Credential writes require the transaction bound to the exact DataSource"
        }
    }

    private fun requireBeforeDeadline(deadline: Instant) {
        if (!deadline.isAfter(Instant.now(clock))) {
            throw ManagedCredentialVaultDeadlineExceededException()
        }
    }

    private fun <T> withConnection(block: (Connection) -> T): T {
        val connection = DataSourceUtils.getConnection(dataSource)
        return try {
            block(connection)
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }

    private fun ResultSet.toStoredCredential(): StoredCredential =
        StoredCredential(
            credentialVersionId = cc.monomer.connector.api.CredentialVersionId(getUuid("credential_version_id")),
            workspaceId = cc.monomer.identity.api.WorkspaceId(getUuid("workspace_id")),
            connectorId = ConnectorId(getUuid("connector_id")),
            connectorType = getString("connector_type"),
            keyVersion = getString("key_version"),
            nonce = getBytes("nonce"),
            additionalAuthenticatedData = getBytes("additional_authenticated_data"),
            ciphertext = getBytes("ciphertext"),
            envelopeDigest = ContentDigest(getString("envelope_digest")),
        )

    private class StoredCredential(
        val credentialVersionId: cc.monomer.connector.api.CredentialVersionId,
        val workspaceId: cc.monomer.identity.api.WorkspaceId,
        val connectorId: ConnectorId,
        val connectorType: String,
        val keyVersion: String,
        val nonce: ByteArray,
        val additionalAuthenticatedData: ByteArray,
        val ciphertext: ByteArray,
        val envelopeDigest: ContentDigest,
    ) : AutoCloseable {
        fun decrypt(keyRing: ManagedCredentialKeyRing): ByteArray {
            val expectedAad = canonicalCredentialAdditionalAuthenticatedData(
                workspaceId = workspaceId,
                connectorId = connectorId,
                credentialVersionId = credentialVersionId,
                connectorType = connectorType,
                keyVersion = keyVersion,
            )
            try {
                if (!MessageDigest.isEqual(additionalAuthenticatedData, expectedAad)) {
                    throw ManagedCredentialEnvelopeRejectedException()
                }
                val computedDigest = credentialEnvelopeDigest(
                    keyVersion = keyVersion,
                    nonce = nonce,
                    additionalAuthenticatedData = additionalAuthenticatedData,
                    ciphertext = ciphertext,
                )
                if (computedDigest != envelopeDigest.value) {
                    throw ManagedCredentialEnvelopeRejectedException()
                }
                return keyRing.useKey(keyVersion) { key ->
                    decryptCredential(key, nonce, additionalAuthenticatedData, ciphertext)
                } ?: throw ManagedCredentialKeyUnavailableException(null)
            } finally {
                Arrays.fill(expectedAad, 0)
            }
        }

        override fun close() {
            Arrays.fill(nonce, 0)
            Arrays.fill(additionalAuthenticatedData, 0)
            Arrays.fill(ciphertext, 0)
        }
    }
}

private fun <T> ByteArray.useBytes(block: (ByteArray) -> T): T {
    try {
        return block(this)
    } finally {
        Arrays.fill(this, 0)
    }
}

private fun Instant.toQueryTimeoutSeconds(): Int =
    ((toEpochMilli() - System.currentTimeMillis() + 999) / 1_000)
        .coerceIn(1, Int.MAX_VALUE.toLong())
        .toInt()

private const val MAXIMUM_ADDITIONAL_AUTHENTICATED_DATA_BYTES = 8_192
private const val MAXIMUM_SCHEMA_DOCUMENT_BYTES = 1_048_576
