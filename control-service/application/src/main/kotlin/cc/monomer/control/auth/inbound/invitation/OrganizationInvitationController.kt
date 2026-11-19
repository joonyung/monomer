package cc.monomer.control.auth.inbound.invitation

import cc.monomer.control.auth.inbound.oauth.JwtAuthentication
import cc.monomer.identity.application.organization.OrganizationInvitationService
import cc.monomer.identity.domain.model.InvitationWorkspaceAccess
import cc.monomer.identity.domain.model.UserCredentialProvenance
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.serialization.Serializable
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

@RestController
@RequestMapping("/auth/invitations")
class OrganizationInvitationController(private val invitations: OrganizationInvitationService, private val clock: Clock, private val continuations: InvitationContinuations) {
    private val attempts = LinkedHashMap<String, List<Instant>>()

    @PostMapping("/exchange")
    suspend fun exchange(@RequestBody request: ExchangeRequest, exchange: ServerWebExchange): InvitationResponse {
        protectResponse(exchange)
        if (!admit(exchange)) return response(outcome = "RATE_LIMITED", retryAfter = 60)
        val proof = invitations.exchange(request.token) ?: return response(outcome = "UNAVAILABLE")
        return response(outcome = "AVAILABLE", continuationId = continuations.put(exchange, proof))
    }

    @PostMapping("/resolve")
    suspend fun resolve(@RequestBody request: ContinuationRequest, exchange: ServerWebExchange): InvitationResponse =
        action(request, exchange, null)
    @PostMapping("/accept")
    suspend fun accept(@RequestBody request: ContinuationRequest, exchange: ServerWebExchange): InvitationResponse =
        action(request, exchange, true)
    @PostMapping("/decline")
    suspend fun decline(@RequestBody request: ContinuationRequest, exchange: ServerWebExchange): InvitationResponse =
        action(request, exchange, false)

    private suspend fun action(request: ContinuationRequest, exchange: ServerWebExchange, accept: Boolean?): InvitationResponse {
        protectResponse(exchange)
        if (!admit(exchange)) return response(outcome = "RATE_LIMITED", retryAfter = 60)
        val proof = continuations.get(exchange, request.continuationId) ?: return response(outcome = "UNAVAILABLE")
        val auth = ReactiveSecurityContextHolder.getContext().awaitSingleOrNull()?.authentication as? JwtAuthentication
        val credential = auth?.credential as? UserCredentialProvenance.BrowserSession
        val result = if (accept == null) invitations.resolve(proof, credential) else invitations.consume(proof, credential, accept)
        val terminal = result.outcome == OrganizationInvitationService.Outcome.UNAVAILABLE || (accept != null && result.outcome in setOf(
            OrganizationInvitationService.Outcome.ACCEPTED, OrganizationInvitationService.Outcome.DECLINED, OrganizationInvitationService.Outcome.ALREADY_MEMBER))
        if (terminal) continuations.remove(exchange, request.continuationId)
        val preview = result.invitation
        if (result.outcome == OrganizationInvitationService.Outcome.EMAIL_MISMATCH) {
            return response(outcome = result.outcome.name, deliveryEmail = preview?.invitation?.deliveryEmail)
        }
        val accepted = result.acceptedWorkspaceAccess
        val workspaceAccess = accepted?.granted ?: preview?.workspaceAccess?.filter { it.workspaceDisplayName != null }
        return response(outcome = result.outcome.name, organizationId = preview?.invitation?.organizationId?.toString(),
            organizationDisplayName = preview?.organizationDisplayName, deliveryEmail = preview?.invitation?.deliveryEmail,
            expiresAt = preview?.invitation?.expiresAt?.toString(), invitedByDisplayName = preview?.invitedBy?.displayName,
            workspaceAccess = workspaceAccess?.map(::view), skippedWorkspaceAccess = accepted?.skipped?.map(::view))
    }

    private fun view(access: InvitationWorkspaceAccess) =
        InvitationWorkspaceAccessResponse(access.workspaceId.toString(), access.workspaceDisplayName, access.role.name)

    private fun admit(exchange: ServerWebExchange): Boolean = synchronized(attempts) {
        val now = clock.instant()
        val key = exchange.request.remoteAddress?.address?.hostAddress ?: "unknown"
        attempts.entries.removeIf { entry -> entry.value.none { it > now.minusSeconds(60) } }
        val current = attempts[key].orEmpty().filter { it > now.minusSeconds(60) }
        if (current.size >= 30 || (key !in attempts && attempts.size >= 10000)) return false
        attempts[key] = current + now
        true
    }

    private fun response(
        outcome: String, continuationId: String? = null, organizationId: String? = null,
        organizationDisplayName: String? = null, deliveryEmail: String? = null, expiresAt: String? = null,
        invitedByDisplayName: String? = null, retryAfter: Int? = null,
        workspaceAccess: List<InvitationWorkspaceAccessResponse>? = null, skippedWorkspaceAccess: List<InvitationWorkspaceAccessResponse>? = null,
    ) = InvitationResponse(outcome, continuationId, organizationId, organizationDisplayName, deliveryEmail, expiresAt, invitedByDisplayName, retryAfter,
        workspaceAccess, skippedWorkspaceAccess)

    private fun protectResponse(exchange: ServerWebExchange) {
        exchange.response.headers[HttpHeaders.CACHE_CONTROL] = "no-store"
        exchange.response.headers["Referrer-Policy"] = "no-referrer"
    }
}

@Serializable
data class ExchangeRequest(val token: String)
@Serializable
data class ContinuationRequest(val continuationId: String)
@Serializable
data class InvitationResponse(
    val outcome: String, val continuationId: String?, val organizationId: String?,
    val organizationDisplayName: String?, val deliveryEmail: String?, val expiresAt: String?,
    val invitedByDisplayName: String?, val retryAfter: Int?,
    // Offered access before acceptance; granted access after an ACCEPTED outcome.
    val workspaceAccess: List<InvitationWorkspaceAccessResponse>?,
    val skippedWorkspaceAccess: List<InvitationWorkspaceAccessResponse>?,
)
@Serializable
data class InvitationWorkspaceAccessResponse(val workspaceId: String, val workspaceDisplayName: String?, val role: String)
