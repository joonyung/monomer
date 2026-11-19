package cc.monomer.control.auth

import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import ch.qos.logback.classic.spi.ILoggingEvent
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.User
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.sesv2.SesV2AsyncClient
import software.amazon.awssdk.services.sesv2.model.SesV2Exception
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SesInvitationDeliveryTest {
    private val settings = InvitationDeliveryProperties.Ses("us-east-1", "invite@example.test", "help@example.test", "feedback@example.test", Duration.ofSeconds(10))
    private val client = mockk<SesV2AsyncClient>()
    private val expiresAt = Instant.parse("2026-09-10T12:00:00Z")
    private fun delivery() = SesInvitationDelivery(client, settings, URI("https://app.example.test/auth/callback?ignored=yes"), false, null)

    @Test
    fun `maps single recipient safe headers escaped HTML and canonical token fragment`() = runTest {
        val request = slot<SendEmailRequest>()
        every { client.sendEmail(capture(request)) } returns CompletableFuture.completedFuture(SendEmailResponse.builder().messageId("accepted").build())
        assertThat(delivery().send("person@example.test", "<Org>\r\nBcc: x", User(UserId.generate(), "admin@example.test", "Admin & 'person'"), expiresAt, "a&b #c")).isTrue()
        val sent = request.captured
        assertThat(sent.destination().toAddresses()).containsExactly("person@example.test")
        assertThat(sent.destination().ccAddresses()).isEmpty()
        assertThat(sent.destination().bccAddresses()).isEmpty()
        assertThat(sent.configurationOverrides().tracking().openTrackingEnabledAsString()).isEqualTo("DISABLED")
        assertThat(sent.configurationOverrides().tracking().clickTrackingEnabledAsString()).isEqualTo("DISABLED")
        assertThat(sent.fromEmailAddress()).isEqualTo("Monomer <invite@example.test>")
        assertThat(sent.replyToAddresses()).containsExactly("help@example.test")
        assertThat(sent.feedbackForwardingEmailAddress()).isEqualTo("feedback@example.test")
        assertThat(sent.content().simple().subject().data()).isEqualTo("Admin & 'person' invited you to join <Org> Bcc: x on Monomer")
        assertThat(sent.content().simple().subject().charset()).isEqualTo("UTF-8")
        assertThat(sent.content().simple().body().text().data()).contains("https://app.example.test/invitations/accept#token=a%26b%20%23c")
        assertThat(sent.content().simple().body().html().data()).contains("&lt;Org&gt;", "Admin &amp; &#39;person&#39; (admin@example.test)",
            "https://app.example.test/invitations/accept#token=a%26b%20%23c").doesNotContain("<Org>", "ignored=yes")
        verify(exactly = 1) { client.sendEmail(any<SendEmailRequest>()) }
    }

    @Test
    fun `reply and feedback addresses are independently optional`() = runTest {
        for (reply in listOf("", "help@example.test")) {
            for (feedback in listOf("", "feedback@example.test")) {
                val request = slot<SendEmailRequest>()
                every { client.sendEmail(capture(request)) } returns CompletableFuture.completedFuture(SendEmailResponse.builder().messageId("accepted").build())
                val delivery = SesInvitationDelivery(client, settings.copy(replyToAddress = reply, feedbackForwardingEmailAddress = feedback), URI("https://app.example.test"), false, null)
                assertThat(delivery.send("person@example.test", "Org", null, expiresAt, "token")).isTrue()
                assertThat(request.captured.replyToAddresses()).isEqualTo(if (reply.isEmpty()) emptyList<String>() else listOf(reply))
                assertThat(request.captured.feedbackForwardingEmailAddress()).isEqualTo(feedback.takeIf { it.isNotEmpty() })
            }
        }
    }

    @Test
    fun `feedback configuration rejects malformed and injected addresses`() {
        for (address in listOf(" ", "Name <a@example.test>", "a@example.test,b@example.test", "a@example.test\r\nBcc: x@example.test", "a@")) {
            assertThrows<IllegalArgumentException> { InvitationDeliveryProperties("ses", settings.copy(feedbackForwardingEmailAddress = address)) }
        }
    }

    @Test
    fun `provider failure returns false and logs no invitation content`() = runTest {
        val logger = LoggerFactory.getLogger(SesInvitationDelivery::class.java) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            every { client.sendEmail(any<SendEmailRequest>()) } returns CompletableFuture.failedFuture(SdkClientException.create("recipient@example.test raw-secret-token"))
            assertThat(delivery().send("recipient@example.test", "Private organization", User(UserId.generate(), "inviter@example.test", "Private inviter"), expiresAt, "raw-secret-token")).isFalse()
            every { client.sendEmail(any<SendEmailRequest>()) } returns CompletableFuture.failedFuture(
                SesV2Exception.builder().statusCode(403).message("recipient@example.test raw-secret-token").build())
            assertThat(delivery().send("recipient@example.test", "Private organization", User(UserId.generate(), "inviter@example.test", "Private inviter"), expiresAt, "raw-secret-token")).isFalse()
            assertThat(logs.list).hasSize(2)
            assertThat(logs.list[0].keyValuePairs.associate { it.key to it.value })
                .containsEntry("stage", "CLIENT")
            assertThat(logs.list[1].keyValuePairs.associate { it.key to it.value })
                .containsEntry("stage", "PROVIDER").containsEntry("provider_status", 403).containsEntry("provider_code", "UNKNOWN")
            logs.list.forEach {
                assertThat(it.formattedMessage).isEqualTo("invitation_delivery_failed")
                assertThat(cc.monomer.control.observability.RequestLogTestSupport.encoded(it)).contains("stack_trace")
                assertThat(it.keyValuePairs.toString()).doesNotContain("recipient@example.test", "inviter@example.test", "raw-secret-token", "Private organization", "Private inviter")
            }
            verify(exactly = 2) { client.sendEmail(any<SendEmailRequest>()) }
        } finally { logger.detachAppender(logs) }
    }

    @Test
    fun `provider diagnostics allow only known codes and never log raw error data`() = runTest {
        val logger = LoggerFactory.getLogger(SesInvitationDelivery::class.java) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        val knownCodes = listOf("AccessDeniedException", "ExpiredTokenException", "IncompleteSignature",
            "InvalidSignatureException", "SignatureDoesNotMatch", "UnrecognizedClientException",
            "InvalidClientTokenId", "OptInRequired", "NotAuthorized")
        val unknownCodes = listOf(null, "", "FutureProviderError", "recipient@example.test raw-secret-token",
            "AccessDeniedException\r\nraw-secret-token", "AccessDeniedException raw-secret-token")
        val privateContent = "recipient@example.test raw-secret-token Private organization Private inviter"
        try {
            for (code in knownCodes + unknownCodes) {
                every { client.sendEmail(any<SendEmailRequest>()) } returns CompletableFuture.failedFuture(
                    SesV2Exception.builder().statusCode(403).message(privateContent)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage(privateContent)
                            .rawResponse(SdkBytes.fromUtf8String(privateContent)).build()).build())
                assertThat(delivery().send("recipient@example.test", "Private organization", User(UserId.generate(), "inviter@example.test", "Private inviter"), expiresAt, "raw-secret-token")).isFalse()
                val expectedCode = if (code in knownCodes) code else "UNKNOWN"
                assertThat(logs.list.last().keyValuePairs.associate { it.key to it.value })
                    .containsEntry("provider_code", expectedCode)
            }
            assertThat(logs.list).hasSize(knownCodes.size + unknownCodes.size)
            logs.list.forEach {
                assertThat(cc.monomer.control.observability.RequestLogTestSupport.encoded(it)).contains("stack_trace")
                assertThat(it.formattedMessage).isEqualTo("invitation_delivery_failed")
                assertThat(it.argumentArray).isNullOrEmpty()
                assertThat(it.keyValuePairs.associate { field -> field.key to field.value })
                    .containsEntry("event", "invitation_delivery_failed")
                    .containsEntry("provider", "SES")
                    .containsEntry("stage", "PROVIDER")
                    .containsEntry("provider_status", 403)
                assertThat(it.keyValuePairs.toString()).doesNotContain("recipient@example.test", "raw-secret-token", "Private organization", "Private inviter")
            }
            verify(exactly = knownCodes.size + unknownCodes.size) { client.sendEmail(any<SendEmailRequest>()) }
        } finally { logger.detachAppender(logs) }
    }

    @Test
    fun `cancelling coroutine cancels outstanding SDK future`() = runTest {
        val future = CompletableFuture<SendEmailResponse>()
        every { client.sendEmail(any<SendEmailRequest>()) } returns future
        val job = launch { delivery().send("a@example.test", "Org", null, expiresAt, "token") }
        runCurrent()
        job.cancel()
        job.join()
        assertThat(job.isCancelled).isTrue()
        assertThat(future.isCancelled).isTrue()
    }

    @Test
    fun `SDK cancellation propagates rather than recording delivery failure`() = runTest {
        every { client.sendEmail(any<SendEmailRequest>()) } returns CompletableFuture.failedFuture(CancellationException("cancelled"))
        assertThrows<CancellationException> { delivery().send("a@example.test", "Org", null, expiresAt, "token") }
    }

    @Test
    fun `invalid recipient never reaches SDK and empty provider acceptance is not sent`() = runTest {
        for (address in listOf("a@example.test\r\nBcc: x@example.test", "a@example.test,b@example.test", "Name <a@example.test>", "a@")) {
            assertThat(delivery().send(address, "Org", null, expiresAt, "token")).isFalse()
        }
        verify(exactly = 0) { client.sendEmail(any<SendEmailRequest>()) }
        every { client.sendEmail(any<SendEmailRequest>()) } returns CompletableFuture.completedFuture(SendEmailResponse.builder().build())
        assertThat(delivery().send("a@example.test", "Org", null, expiresAt, "token")).isFalse()
    }

    @Test
    fun `SES startup configuration rejects missing values unsafe callback and invalid timeout`() {
        for (bad in listOf(settings.copy(region = ""), settings.copy(fromAddress = ""), settings.copy(replyToAddress = "a@b.test\n"), settings.copy(timeout = Duration.ZERO), settings.copy(timeout = Duration.ofMinutes(2)))) {
            assertThrows<IllegalArgumentException> { InvitationDeliveryProperties("ses", bad) }
        }
        assertThrows<IllegalArgumentException> { InvitationDeliveryProperties("smtp", settings) }
        for (callback in listOf("http://app.example.test/auth/callback", "https://user@app.example.test/auth/callback", "http://localhost:3100/auth/callback")) {
            assertThrows<IllegalArgumentException> { SesInvitationDelivery(client, settings, URI(callback), false, null) }
        }
        SesInvitationDelivery(client, settings, URI("http://localhost:3100/auth/callback"), true, null)
        InvitationDeliveryProperties("local", settings.copy(region = "", fromAddress = ""))
    }

    @Test
    fun `close disposes client and credential chain`() {
        val credentials = mockk<AutoCloseable>()
        every { client.close() } returns Unit
        every { credentials.close() } returns Unit
        SesInvitationDelivery(client, settings, URI("https://app.example.test"), false, credentials).close()
        verify(exactly = 1) { client.close() }
        verify(exactly = 1) { credentials.close() }
    }
}
