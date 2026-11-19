package cc.monomer.control.auth

import cc.monomer.identity.application.OpaqueCredentials
import cc.monomer.identity.application.organization.OrganizationInvitationService
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.User
import java.net.URI
import java.time.Instant
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalInvitationDeliveryTest {
    @TempDir lateinit var directory: Path
    @Test
    fun `local sink stores protected mail with configured frontend fragment URL`() = runBlocking<Unit> {
        val sink = directory.toRealPath().resolve("mail")
        val token = OpaqueCredentials.generate()
        assertThat(LocalInvitationDelivery(true, URI("http://127.0.0.1:3100/auth/callback"), sink)
            .send("invitee@example.test", "Example", User(UserId.generate(), "admin@example.test", "Admin"), Instant.parse("2026-10-08T04:37:51.574949Z"), token)).isTrue()
        val file = Files.list(sink).use { it.findFirst().orElseThrow() }
        assertThat(Files.getPosixFilePermissions(sink)).isEqualTo(PosixFilePermissions.fromString("rwx------"))
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"))
        assertThat(Files.readString(file)).startsWith("To: invitee@example.test\nSubject: Admin invited you to join Example on Monomer\n\n")
            .contains("http://127.0.0.1:3100/invitations/accept#token=$token", "This invitation expires on Oct 8, 2026, 04:37 UTC.")
    }
    @Test
    fun `IPv6 loopback frontend callback is a local origin`() = runBlocking<Unit> {
        val sink = directory.toRealPath().resolve("mail")
        assertThat(LocalInvitationDelivery(true, URI("http://[::1]:3100/auth/callback"), sink)
            .send("invitee@example.test", "Example", null, Instant.parse("2026-10-08T04:37:51Z"), "token")).isTrue()
    }
    @Test
    fun `nonlocal unconfigured delivery and insecure public origin fail without file`() = runBlocking<Unit> {
        val sink = directory.resolve("mail")
        assertThat(LocalInvitationDelivery(false, URI("https://app.example/auth/callback"), sink).send("a@b.test", "Example", null, Instant.now(), "token")).isFalse()
        assertThat(LocalInvitationDelivery(true, URI("http://app.example/auth/callback"), sink).send("a@b.test", "Example", null, Instant.now(), "token")).isFalse()
        assertThat(Files.exists(sink)).isFalse()
    }
}
