package cc.monomer.control.auth

import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.User
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InvitationEmailTest {
    private val expiresAt = Instant.parse("2026-10-08T04:37:51.574949Z")
    private val link = "https://app.example.test/invitations/accept#token=abc"
    private val inviter = User(UserId.generate(), "dana@example.test", "Dana Lee")

    @Test
    fun `text and html carry the inviter, organization, sign-in account, readable expiry, and fallback link`() {
        val email = invitationEmail("person@example.test", "Acme Analytics", inviter, expiresAt, link)
        assertThat(email.subject).isEqualTo("Dana Lee invited you to join Acme Analytics on Monomer")
        val required = listOf(
            "Dana Lee (dana@example.test) invited you to join Acme Analytics on Monomer.",
            "Monomer gives every agent on a team one shared foundation.",
            "Sign in with the Google account for person@example.test to accept. Other accounts can&#39;t use this invitation.",
            "This invitation expires on Oct 8, 2026, 04:37 UTC.",
            "Nothing changes unless you accept.",
            "Sent by Monomer on behalf of Acme Analytics.",
            link,
        )
        assertThat(email.text).contains(required.map { it.replace("&#39;", "'") })
        assertThat(email.html).contains(required).contains("<html lang=\"en\">", ">Accept invitation</a>", "Accept by Oct 8, 2026, 04:37 UTC to join Acme Analytics.")
        assertThat(email.html.split("href=\"$link\"")).hasSize(3)
        assertThat(email.html).doesNotContain("<img", "2026-10-08T04:37:51")
        assertThat(email.text).doesNotContain("2026-10-08T04:37:51")
    }

    @Test
    fun `missing or duplicated inviter identity reads naturally`() {
        val anonymous = invitationEmail("person@example.test", "Acme", null, expiresAt, link)
        assertThat(anonymous.subject).isEqualTo("You're invited to join Acme on Monomer")
        assertThat(anonymous.text).startsWith("An administrator invited you to join Acme on Monomer.")
        val emailOnly = invitationEmail("person@example.test", "Acme", User(UserId.generate(), "dana@example.test", "Dana@Example.test"), expiresAt, link)
        assertThat(emailOnly.subject).isEqualTo("Dana@Example.test invited you to join Acme on Monomer")
        assertThat(emailOnly.text).startsWith("Dana@Example.test invited you to join Acme on Monomer.")
    }

    @Test
    fun `user supplied names cannot break headers or markup and long names are shortened in the subject`() {
        val hostile = User(UserId.generate(), "dana@example.test", "Dana\r\nBcc: x\u2028<script>\u202E\u0085")
        val email = invitationEmail("person@example.test", "<b>Org</b>\nSubject: y", hostile, expiresAt, link)
        assertThat(email.subject).doesNotContain("\r", "\n", "\u2028", "\u202E", "\u0085")
        assertThat(email.subject).isEqualTo("Dana Bcc: x <script> invited you to join <b>Org</b> Subject: y on Monomer")
        assertThat(email.html).contains("Dana Bcc: x &lt;script&gt;", "&lt;b&gt;Org&lt;/b&gt;").doesNotContain("<script>", "<b>Org")
        val longName = "😀".repeat(80)
        val long = invitationEmail("person@example.test", longName, inviter, expiresAt, link)
        assertThat(long.subject).isEqualTo("Dana Lee invited you to join ${"😀".repeat(59)}… on Monomer")
        assertThat(long.text).contains("join $longName on Monomer.")
    }
}
