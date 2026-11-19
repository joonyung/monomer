package cc.monomer.control.auth

import cc.monomer.identity.domain.model.User
import cc.monomer.identity.domain.model.isDeliverableInvitationAddress
import cc.monomer.identity.domain.port.InvitationDelivery
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.retries.StandardRetryStrategy
import software.amazon.awssdk.services.sesv2.SesV2AsyncClient
import software.amazon.awssdk.services.sesv2.model.Body
import software.amazon.awssdk.services.sesv2.model.ConfigurationOverrides
import software.amazon.awssdk.services.sesv2.model.FeatureStatus
import software.amazon.awssdk.services.sesv2.model.Content
import software.amazon.awssdk.services.sesv2.model.Destination
import software.amazon.awssdk.services.sesv2.model.EmailContent
import software.amazon.awssdk.services.sesv2.model.Message
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest
import software.amazon.awssdk.services.sesv2.model.TrackingConfigurationOverrides

@ConfigurationProperties("auth.invitation")
data class InvitationDeliveryProperties(val deliveryMode: String, val ses: Ses) {
    init {
        require(deliveryMode in setOf("local", "ses")) { "Unsupported invitation delivery mode" }
        if (deliveryMode == "ses") ses.validate()
    }

    data class Ses(val region: String, val fromAddress: String, val replyToAddress: String, val feedbackForwardingEmailAddress: String, val timeout: Duration) {
        fun validate() {
            require(region.matches(Regex("[a-z]{2}(-[a-z]+)+-[0-9]+"))) { "Invalid invitation SES region" }
            require(isDeliverableInvitationAddress(fromAddress)) { "Invalid invitation SES from address" }
            require(replyToAddress.isEmpty() || isDeliverableInvitationAddress(replyToAddress)) { "Invalid invitation SES reply-to address" }
            require(feedbackForwardingEmailAddress.isEmpty() || isDeliverableInvitationAddress(feedbackForwardingEmailAddress)) { "Invalid invitation SES feedback forwarding email address" }
            require(timeout >= Duration.ofMillis(1) && timeout <= Duration.ofMinutes(1)) { "Invitation SES timeout must be between 1ms and 1m" }
        }
    }
}

internal fun invitationAcceptanceLink(origin: URI, rawToken: String): String =
    "$origin/invitations/accept#token=${URLEncoder.encode(rawToken, StandardCharsets.UTF_8).replace("+", "%20")}"

class SesInvitationDelivery(
    private val client: SesV2AsyncClient,
    private val settings: InvitationDeliveryProperties.Ses,
    frontendCallback: URI,
    local: Boolean,
    private val credentials: AutoCloseable?,
) : InvitationDelivery, AutoCloseable {
    private val origin: URI

    init {
        settings.validate()
        require(frontendCallback.host != null && frontendCallback.userInfo == null) { "Invalid invitation frontend callback" }
        require(frontendCallback.scheme == "https" || (local && frontendCallback.scheme == "http" &&
            frontendCallback.host in setOf("localhost", "127.0.0.1", "[::1]", "::1"))) { "Invitation frontend callback requires HTTPS" }
        origin = URI(frontendCallback.scheme, null, frontendCallback.host, frontendCallback.port, null, null, null)
    }

    override suspend fun send(deliveryEmail: String, organizationDisplayName: String, invitedBy: User?, expiresAt: Instant, rawToken: String): Boolean {
        if (!isDeliverableInvitationAddress(deliveryEmail)) return false
        val email = invitationEmail(deliveryEmail, organizationDisplayName, invitedBy, expiresAt, invitationAcceptanceLink(origin, rawToken))
        val request = SendEmailRequest.builder()
            .fromEmailAddress("$INVITATION_SENDER_DISPLAY_NAME <${settings.fromAddress}>")
            .destination(Destination.builder().toAddresses(deliveryEmail).build())
            .configurationOverrides(ConfigurationOverrides.builder().tracking(TrackingConfigurationOverrides.builder()
                .openTrackingEnabled(FeatureStatus.DISABLED).clickTrackingEnabled(FeatureStatus.DISABLED).build()).build())
            .replyToAddresses(if (settings.replyToAddress.isEmpty()) emptyList() else listOf(settings.replyToAddress))
            .feedbackForwardingEmailAddress(settings.feedbackForwardingEmailAddress.takeIf { it.isNotEmpty() })
            .content(EmailContent.builder().simple(Message.builder()
                .subject(content(email.subject))
                .body(Body.builder()
                    .text(content(email.text))
                    .html(content(email.html))
                    .build())
                .build()).build())
            .build()
        return try {
            val response = client.sendEmail(request).await()
            !response.messageId().isNullOrBlank()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AwsServiceException) {
            val providerCode = when (val code = failure.awsErrorDetails()?.errorCode()) {
                "AccessDeniedException", "ExpiredTokenException", "IncompleteSignature", "InvalidSignatureException",
                "SignatureDoesNotMatch", "UnrecognizedClientException", "InvalidClientTokenId", "OptInRequired",
                "NotAuthorized" -> code
                else -> "UNKNOWN"
            }
            logger.atWarn()
                .addKeyValue("event", "invitation_delivery_failed")
                .addKeyValue("provider", "SES")
                .setCause(failure)
                .addKeyValue("stage", "PROVIDER")
                .addKeyValue("provider_status", failure.statusCode())
                .addKeyValue("provider_code", providerCode)
                .log("invitation_delivery_failed")
            false
        } catch (failure: SdkException) {
            logger.atWarn()
                .addKeyValue("event", "invitation_delivery_failed")
                .addKeyValue("provider", "SES")
                .setCause(failure)
                .addKeyValue("stage", "CLIENT")
                .log("invitation_delivery_failed")
            false
        }
    }

    override fun close() {
        try {
            client.close()
        } finally {
            credentials?.close()
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SesInvitationDelivery::class.java)

        fun create(settings: InvitationDeliveryProperties.Ses, frontendCallback: URI, local: Boolean): SesInvitationDelivery {
            settings.validate()
            val credentials = DefaultCredentialsProvider.builder().build()
            return try {
                val client = SesV2AsyncClient.builder()
                    .region(Region.of(settings.region))
                    .credentialsProvider(credentials)
                    .overrideConfiguration {
                        it.apiCallTimeout(settings.timeout)
                            .apiCallAttemptTimeout(settings.timeout)
                            .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                    }
                    .build()
                try {
                    SesInvitationDelivery(client, settings, frontendCallback, local, credentials)
                } catch (failure: Exception) {
                    client.close()
                    throw failure
                }
            } catch (failure: Exception) {
                credentials.close()
                throw failure
            }
        }

        private fun content(value: String): Content = Content.builder().charset("UTF-8").data(value).build()
    }
}
