package cc.monomer.identity.application

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object OpaqueCredentials {
    private const val RANDOM_BYTES = 32
    private val random = SecureRandom()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    fun generate(): String = urlEncoder.encodeToString(ByteArray(RANDOM_BYTES).also(random::nextBytes))

    fun hash(rawValue: String): String =
        sha256(rawValue.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun s256CodeChallenge(codeVerifier: String): String =
        urlEncoder.encodeToString(sha256(codeVerifier.toByteArray(Charsets.US_ASCII)))

    fun matchesS256CodeChallenge(codeVerifier: String, codeChallenge: String): Boolean =
        MessageDigest.isEqual(s256CodeChallenge(codeVerifier).toByteArray(Charsets.UTF_8), codeChallenge.toByteArray(Charsets.UTF_8))

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
