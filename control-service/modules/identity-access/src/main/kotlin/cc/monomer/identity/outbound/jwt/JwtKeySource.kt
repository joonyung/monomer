package cc.monomer.identity.outbound.jwt

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import org.slf4j.LoggerFactory

/**
 * Single RS256 keypair used by both [JwtIssuer] and [JwtVerifier], and exposed via JWKS.
 *
 * Resolution order:
 *  1. `auth.jwt.private-key-pem` (inline PKCS#8 PEM body — env or yaml).
 *  2. `auth.jwt.private-key-path` (file path).
 *  3. `${user.home}/.monomer/auth-jwt.pem` — auto-generated on first boot.
 *
 * The kid is the JWK SHA-256 thumbprint, stable across restarts when the key persists.
 */
class JwtKeySource(
    val privateKey: RSAPrivateKey,
    val publicKey: RSAPublicKey,
    val kid: String,
) {
    val jwk: RSAKey = RSAKey.Builder(publicKey)
        .keyID(kid)
        .keyUse(KeyUse.SIGNATURE)
        .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
        .build()
}

class JwtKeyConfiguration(private val properties: JwtProperties) {

    private val log = LoggerFactory.getLogger(JwtKeyConfiguration::class.java)

    fun jwtKeySource(): JwtKeySource {
        val pem = when {
            properties.privateKeyPem.isNotBlank() -> properties.privateKeyPem
            properties.privateKeyPath.isNotBlank() -> loadOrGenerate(Path.of(properties.privateKeyPath))
            else -> loadOrGenerate(Path.of(System.getProperty("user.home"), ".monomer", "auth-jwt.pem"))
        }
        val privateKey = parsePkcs8(pem)
        val publicKey = derivePublicKey(privateKey)
        val rsaKey = RSAKey.Builder(publicKey).privateKey(privateKey).build()
        val kid = rsaKey.computeThumbprint().toString()
        return JwtKeySource(privateKey, publicKey, kid)
    }

    private fun loadOrGenerate(target: Path): String =
        if (Files.exists(target)) {
            log.info("Loading auth-service JWT key from {}", target)
            Files.readString(target)
        } else {
            log.warn("No JWT key found at {} — generating a new RSA-2048 keypair", target)
            target.parent?.let { Files.createDirectories(it) }
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val pem = encodePkcs8Pem(keyPair.private.encoded)
            writeNewOwnerOnlyFile(target, pem)
            pem
        }

    private fun writeNewOwnerOnlyFile(target: Path, content: String) = writeNewOwnerOnlyFile(
        target,
        content,
        { temporary, pem -> Files.writeString(temporary, pem, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING) },
        { linkTarget, temporary -> Files.createLink(linkTarget, temporary) },
    )

    internal fun writeNewOwnerOnlyFile(
        target: Path,
        content: String,
        writeTemporary: (Path, String) -> Unit,
        linkTemporary: (Path, Path) -> Unit,
    ) {
        val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        val attributes = if ("posix" in target.fileSystem.supportedFileAttributeViews()) arrayOf(permissions) else emptyArray()
        val temporary = Files.createTempFile(target.toAbsolutePath().parent, ".auth-jwt-", ".pem", *attributes)
        try {
            writeTemporary(temporary, content)
            try {
                linkTemporary(target, temporary)
            } catch (_: UnsupportedOperationException) {
                writeNewOwnerOnlyFileWithoutLink(target, content)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun writeNewOwnerOnlyFileWithoutLink(target: Path, content: String) {
        val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        val channel = if ("posix" in target.fileSystem.supportedFileAttributeViews()) {
            Files.newByteChannel(target, options, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            Files.newByteChannel(target, options)
        }
        channel.use {
            val buffer = ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
            while (buffer.hasRemaining()) it.write(buffer)
        }
    }

    private fun parsePkcs8(pem: String): RSAPrivateKey {
        val body = pem
            .replace("-----BEGIN PRIVATE KEY-----", "") // gitleaks:allow
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val bytes = Base64.getDecoder().decode(body)
        val spec = PKCS8EncodedKeySpec(bytes)
        return KeyFactory.getInstance("RSA").generatePrivate(spec) as RSAPrivateKey
    }

    private fun derivePublicKey(privateKey: RSAPrivateKey): RSAPublicKey {
        // For RSA private keys produced by KeyPairGenerator the key implements RSAPrivateCrtKey,
        // which exposes modulus + publicExponent directly — derive the matching public key.
        require(privateKey is java.security.interfaces.RSAPrivateCrtKey) {
            "expected RSAPrivateCrtKey to derive public exponent (got ${privateKey.javaClass.name})"
        }
        val spec = java.security.spec.RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)
        return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
    }

    private fun encodePkcs8Pem(der: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(der)
        val body = b64.chunked(64).joinToString("\n")
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
    }
}

/** Convenience for JWKS controller — fetches the public-only JWK. */
fun JwtKeySource.publicJwk(): JWK = jwk.toPublicJWK()
