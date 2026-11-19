@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.connector.adapter.outbound.postgres

import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.CredentialVersionId
import cc.monomer.identity.api.WorkspaceId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class ManagedCredentialCryptographyTest {
    @Test
    fun `authenticated credential identity rejects substitution`() {
        val key = ByteArray(32) { 7 }
        val nonce = ByteArray(GCM_NONCE_BYTES) { 3 }
        val workspaceId = WorkspaceId(Uuid.generateV7())
        val connectorId = ConnectorId(Uuid.generateV7())
        val credentialVersionId = CredentialVersionId(Uuid.generateV7())
        val aad = canonicalCredentialAdditionalAuthenticatedData(
            workspaceId = workspaceId,
            connectorId = connectorId,
            credentialVersionId = credentialVersionId,
            connectorType = "postgresql",
            keyVersion = "local-v1",
        )
        val encrypted = encryptCredential(key, nonce, aad, "secret".toByteArray())
        val changedAad = canonicalCredentialAdditionalAuthenticatedData(
            workspaceId = workspaceId,
            connectorId = connectorId,
            credentialVersionId = CredentialVersionId(Uuid.generateV7()),
            connectorType = "postgresql",
            keyVersion = "local-v1",
        )

        assertFailsWith<ManagedCredentialEnvelopeRejectedException> {
            decryptCredential(key, nonce, changedAad, encrypted)
        }
        assertContentEquals("secret".toByteArray(), decryptCredential(key, nonce, aad, encrypted))
    }

    @Test
    fun `envelope digest does not mutate encrypted envelope inputs`() {
        val nonce = ByteArray(GCM_NONCE_BYTES) { 3 }
        val aad = byteArrayOf(1, 2, 3)
        val ciphertext = byteArrayOf(4, 5, 6)
        val originalNonce = nonce.copyOf()
        val originalAad = aad.copyOf()
        val originalCiphertext = ciphertext.copyOf()

        credentialEnvelopeDigest(
            keyVersion = "local-v1",
            nonce = nonce,
            additionalAuthenticatedData = aad,
            ciphertext = ciphertext,
        )

        assertContentEquals(originalNonce, nonce)
        assertContentEquals(originalAad, aad)
        assertContentEquals(originalCiphertext, ciphertext)
    }
}
