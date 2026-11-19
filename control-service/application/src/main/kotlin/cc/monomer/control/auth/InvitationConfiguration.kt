package cc.monomer.control.auth

import cc.monomer.identity.application.organization.OrganizationInvitationService
import cc.monomer.identity.domain.model.User
import cc.monomer.identity.domain.model.isDeliverableInvitationAddress
import cc.monomer.identity.domain.port.InvitationDelivery
import cc.monomer.control.auth.inbound.oauth.OAuthProperties
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.time.Instant
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment

@Configuration
@Import(OrganizationInvitationService::class)
@EnableConfigurationProperties(InvitationDeliveryProperties::class)
class InvitationConfiguration {
    @Bean
    fun invitationDelivery(environment: Environment, properties: OAuthProperties, delivery: InvitationDeliveryProperties): InvitationDelivery {
        val frontendCallback = URI.create(properties.frontendCallbackUrl)
        return when (delivery.deliveryMode) {
            "local" -> LocalInvitationDelivery(environment.activeProfiles.contains("local"), frontendCallback,
                Path.of(System.getProperty("user.home"), ".monomer", "private", "invitation-mail"))
            "ses" -> SesInvitationDelivery.create(delivery.ses, frontendCallback, environment.activeProfiles.contains("local"))
            else -> error("Unsupported invitation delivery mode")
        }
    }
}

class LocalInvitationDelivery(
    private val local: Boolean,
    private val frontendCallback: URI,
    private val directory: Path,
) : InvitationDelivery {
    override suspend fun send(deliveryEmail: String, organizationDisplayName: String, invitedBy: User?, expiresAt: Instant, rawToken: String): Boolean {
        if (!local || !isDeliverableInvitationAddress(deliveryEmail)) return false
        val origin = URI(frontendCallback.scheme, null, frontendCallback.host, frontendCallback.port, null, null, null)
        if (origin.scheme != "https" && !(origin.scheme == "http" && origin.host in setOf("127.0.0.1", "localhost", "[::1]"))) return false
        return try {
            if (directory.toAbsolutePath().let { path -> generateSequence(path) { it.parent }.any { Files.exists(it.resolve(".git")) } }) return false
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            if (generateSequence(directory.toAbsolutePath()) { it.parent }.any(Files::isSymbolicLink)) return false
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
            val file = directory.resolve("${UUID.randomUUID()}.eml")
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            val email = invitationEmail(deliveryEmail, organizationDisplayName, invitedBy, expiresAt, invitationAcceptanceLink(origin, rawToken))
            Files.writeString(file, "To: $deliveryEmail\nSubject: ${email.subject}\n\n${email.text}",
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
