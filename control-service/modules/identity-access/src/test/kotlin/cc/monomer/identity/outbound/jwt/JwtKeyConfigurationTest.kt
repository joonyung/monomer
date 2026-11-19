package cc.monomer.identity.outbound.jwt

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JwtKeyConfigurationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `generated private key file is created owner-only and reloads the same key`() {
        assumeTrue("posix" in directory.fileSystem.supportedFileAttributeViews())
        val target = directory.resolve("keys").resolve("auth-jwt.pem")

        val generated = configuration(target).jwtKeySource()

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target))).isEqualTo("rw-------")
        assertThat(Files.readString(target)).startsWith("-----BEGIN PRIVATE KEY-----") // gitleaks:allow
        val reloaded = configuration(target).jwtKeySource()
        assertThat(reloaded.kid).isEqualTo(generated.kid)
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target))).isEqualTo("rw-------")
    }

    @Test
    fun `incomplete temporary write leaves no key file and the next attempt succeeds`() {
        val target = directory.resolve("auth-jwt.pem")
        assertThatThrownBy {
            configuration(target).writeNewOwnerOnlyFile(
                target,
                "complete-key",
                { temporary, _ ->
                    assertThat(temporary.parent).isEqualTo(target.parent)
                    if ("posix" in temporary.fileSystem.supportedFileAttributeViews()) {
                        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(temporary))).isEqualTo("rw-------")
                    }
                    Files.writeString(temporary, "partial-key", StandardOpenOption.WRITE)
                    throw IOException("Simulated partial write")
                },
                { linkTarget, temporary -> Files.createLink(linkTarget, temporary) },
            )
        }.isInstanceOf(IOException::class.java)

        assertThat(Files.exists(target)).isFalse()
        Files.list(directory).use { assertThat(it.toList()).isEmpty() }
        val generated = configuration(target).jwtKeySource()
        assertThat(configuration(target).jwtKeySource().kid).isEqualTo(generated.kid)
    }

    @Test
    fun `key published by another writer is never overwritten`() {
        val target = directory.resolve("auth-jwt.pem")
        assertThatThrownBy {
            configuration(target).writeNewOwnerOnlyFile(
                target,
                "complete-key",
                ::writeTemporary,
                { linkTarget, temporary ->
                    Files.writeString(linkTarget, "existing-key", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    Files.createLink(linkTarget, temporary)
                },
            )
        }.isInstanceOf(FileAlreadyExistsException::class.java)

        assertThat(Files.readString(target)).isEqualTo("existing-key")
        Files.list(directory).use { assertThat(it.toList()).containsExactly(target) }
    }

    @Test
    fun `unsupported hard links fall back to owner-only create-new key file`() {
        assumeTrue("posix" in directory.fileSystem.supportedFileAttributeViews())
        val target = directory.resolve("auth-jwt.pem")
        configuration(target).writeNewOwnerOnlyFile(
            target,
            "complete-key",
            ::writeTemporary,
            { _, _ -> throw UnsupportedOperationException("Hard links unsupported") },
        )

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target))).isEqualTo("rw-------")
        assertThat(Files.readString(target)).isEqualTo("complete-key")
        Files.list(directory).use { assertThat(it.toList()).containsExactly(target) }
    }

    @Test
    fun `unsupported hard link fallback never overwrites an existing key`() {
        val target = directory.resolve("auth-jwt.pem")
        assertThatThrownBy {
            configuration(target).writeNewOwnerOnlyFile(
                target,
                "complete-key",
                ::writeTemporary,
                { linkTarget, _ ->
                    Files.writeString(linkTarget, "existing-key", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    throw UnsupportedOperationException("Hard links unsupported")
                },
            )
        }.isInstanceOf(FileAlreadyExistsException::class.java)

        assertThat(Files.readString(target)).isEqualTo("existing-key")
        Files.list(directory).use { assertThat(it.toList()).containsExactly(target) }
    }

    private fun writeTemporary(temporary: Path, content: String) {
        Files.writeString(temporary, content, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
    }

    private fun configuration(target: Path) = JwtKeyConfiguration(
        JwtProperties("https://issuer.example", "audience", 900, 86400, "", target.toString()),
    )
}
