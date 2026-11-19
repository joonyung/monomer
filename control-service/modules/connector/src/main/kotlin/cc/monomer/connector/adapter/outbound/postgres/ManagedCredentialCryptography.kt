@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.connector.adapter.outbound.postgres

import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.CredentialVersionId
import cc.monomer.identity.api.WorkspaceId
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ManagedCredentialKeyRing(
    val activeKeyVersion: String,
    keysByVersion: Map<String, ByteArray>,
) : AutoCloseable {
    private var ownedKeysByVersion: Map<String, ByteArray>? = validateAndCopyKeys(activeKeyVersion, keysByVersion)

    fun <T> useKey(keyVersion: String, block: (ByteArray) -> T): T? {
        val keyCopy = synchronized(this) {
            val ownedKey = checkNotNull(ownedKeysByVersion) { "Managed credential key ring is closed" }[keyVersion]
                ?: return null
            ownedKey.copyOf()
        }
        return try {
            block(keyCopy)
        } finally {
            Arrays.fill(keyCopy, 0)
        }
    }

    @Synchronized
    override fun close() {
        ownedKeysByVersion?.values?.forEach { key -> Arrays.fill(key, 0) }
        ownedKeysByVersion = null
    }

    override fun toString(): String = "ManagedCredentialKeyRing(activeKeyVersion=[REDACTED], keys=[REDACTED])"

    private companion object {
        const val AES_256_KEY_BYTES = 32
        val KEY_VERSION_PATTERN = Regex("^[A-Za-z0-9._~:/-]{1,512}$")

        fun validateAndCopyKeys(
            activeKeyVersion: String,
            keysByVersion: Map<String, ByteArray>,
        ): Map<String, ByteArray> {
            require(activeKeyVersion.matches(KEY_VERSION_PATTERN)) {
                "Active credential key version is not canonical"
            }
            require(keysByVersion.isNotEmpty()) { "Managed credential key ring must not be empty" }
            require(activeKeyVersion in keysByVersion) { "Managed credential key ring does not contain its active version" }
            require(keysByVersion.keys.all { keyVersion ->
                keyVersion.matches(KEY_VERSION_PATTERN) && keyVersion.split('/').none { it == "." || it == ".." }
            }) { "Managed credential key versions must be canonical" }
            require(keysByVersion.values.all { key -> key.size == AES_256_KEY_BYTES }) {
                "Managed credential keys must be exactly 256 bits"
            }
            return keysByVersion.mapValues { (_, key) -> key.copyOf() }
        }
    }
}

fun interface CredentialVaultNonceSource {
    fun nextNonce(): ByteArray
}

class SecureRandomCredentialVaultNonceSource(
    private val secureRandom: SecureRandom,
) : CredentialVaultNonceSource {
    override fun nextNonce(): ByteArray = ByteArray(GCM_NONCE_BYTES).also(secureRandom::nextBytes)
}

class ManagedCredentialVaultDeadlineExceededException :
    RuntimeException("Managed credential store deadline has expired")

class ManagedCredentialKeyUnavailableException(cause: Throwable?) :
    RuntimeException("Managed credential key material is unavailable", cause)

internal class ManagedCredentialEnvelopeRejectedException(cause: Throwable? = null) :
    RuntimeException("Managed credential envelope authentication failed", cause)

internal fun canonicalCredentialAdditionalAuthenticatedData(
    workspaceId: WorkspaceId,
    connectorId: ConnectorId,
    credentialVersionId: CredentialVersionId,
    connectorType: String,
    keyVersion: String,
): ByteArray = ByteArrayOutputStream().use { output ->
    DataOutputStream(output).use { data ->
        data.writeInt(AAD_MAGIC)
        data.writeCanonical(workspaceId.value.toString())
        data.writeCanonical(connectorId.toString())
        data.writeCanonical(credentialVersionId.toString())
        data.writeCanonical(connectorType)
        data.writeCanonical(keyVersion)
    }
    output.toByteArray()
}

internal fun encryptCredential(
    key: ByteArray,
    nonce: ByteArray,
    additionalAuthenticatedData: ByteArray,
    document: ByteArray,
): ByteArray = Cipher.getInstance(AES_GCM_TRANSFORMATION).run {
    init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, AES_ALGORITHM), GCMParameterSpec(GCM_TAG_BITS, nonce))
    updateAAD(additionalAuthenticatedData)
    doFinal(document)
}

internal fun decryptCredential(
    key: ByteArray,
    nonce: ByteArray,
    additionalAuthenticatedData: ByteArray,
    ciphertext: ByteArray,
): ByteArray = try {
    Cipher.getInstance(AES_GCM_TRANSFORMATION).run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, AES_ALGORITHM), GCMParameterSpec(GCM_TAG_BITS, nonce))
        updateAAD(additionalAuthenticatedData)
        doFinal(ciphertext)
    }
} catch (failure: AEADBadTagException) {
    throw ManagedCredentialEnvelopeRejectedException(failure)
}

internal fun credentialEnvelopeDigest(
    keyVersion: String,
    nonce: ByteArray,
    additionalAuthenticatedData: ByteArray,
    ciphertext: ByteArray,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val keyVersionBytes = keyVersion.toByteArray(Charsets.UTF_8)
    digest.update(ENVELOPE_DIGEST_DOMAIN)
    try {
        digest.updateCanonical(keyVersionBytes)
        digest.updateCanonical(nonce)
        digest.updateCanonical(additionalAuthenticatedData)
        digest.updateCanonical(ciphertext)
        return "sha256:" + digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    } finally {
        Arrays.fill(keyVersionBytes, 0)
    }
}

private fun DataOutputStream.writeCanonical(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    try {
        writeInt(bytes.size)
        write(bytes)
    } finally {
        Arrays.fill(bytes, 0)
    }
}

private fun MessageDigest.updateCanonical(value: ByteArray) {
    update(
        byteArrayOf(
            (value.size ushr 24).toByte(),
            (value.size ushr 16).toByte(),
            (value.size ushr 8).toByte(),
            value.size.toByte(),
        ),
    )
    update(value)
}

const val GCM_NONCE_BYTES = 12
private const val GCM_TAG_BITS = 128
private const val AES_ALGORITHM = "AES"
private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
private const val AAD_MAGIC = 0x4d564132
private val ENVELOPE_DIGEST_DOMAIN = "MONOMER-CREDENTIAL-ENVELOPE-V2".toByteArray(Charsets.US_ASCII)
