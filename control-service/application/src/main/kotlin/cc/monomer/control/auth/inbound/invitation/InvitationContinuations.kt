package cc.monomer.control.auth.inbound.invitation

import cc.monomer.control.auth.inbound.PendingAuthenticationBrowser
import cc.monomer.identity.application.organization.OrganizationInvitationService
import cc.monomer.identity.application.pending.*
import cc.monomer.identity.domain.id.OrganizationId
import java.time.Instant
import kotlin.uuid.Uuid
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange

@Component
class InvitationContinuations(private val requests: PendingAuthenticationRequests, private val browser: PendingAuthenticationBrowser) {
    suspend fun put(exchange: ServerWebExchange, proof: OrganizationInvitationService.Proof): String =
        requests.createInvitation(browser.start(exchange), proof)
    suspend fun get(exchange: ServerWebExchange, id: String): OrganizationInvitationService.Proof? {
        val binding = browser.read(exchange) ?: return null
        val payload = requests.find(id, binding, PendingAuthenticationKind.INVITATION)?.payload as? PendingAuthenticationPayload.Invitation ?: return null
        return OrganizationInvitationService.Proof(Uuid.parse(payload.invitationId), OrganizationId(Uuid.parse(payload.organizationId)),
            payload.tokenHash, Instant.parse(payload.proofExpiresAt))
    }
    suspend fun remove(exchange: ServerWebExchange, id: String) {
        browser.read(exchange)?.let { requests.removeInvitation(id, it) }
    }
    companion object { const val PARAMETER = "invitation_continuation" }
}
