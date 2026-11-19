package cc.monomer.control.auth.inbound.oauth

import cc.monomer.control.auth.inbound.PendingAuthenticationBrowser
import cc.monomer.control.auth.inbound.invitation.InvitationContinuations
import cc.monomer.identity.application.pending.*
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.security.oauth2.client.web.server.ServerAuthorizationRequestRepository
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

class MultiRequestOAuth2AuthorizationRequestRepository(
    private val requests: PendingAuthenticationRequests,
    private val browser: PendingAuthenticationBrowser,
) : ServerAuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    override fun loadAuthorizationRequest(exchange: ServerWebExchange): Mono<OAuth2AuthorizationRequest> = mono(org.springframework.core.PropagationContextElement()) {
        val state = state(exchange) ?: return@mono null
        val binding = browser.read(exchange) ?: return@mono null
        val payload = requests.find(state, binding, PendingAuthenticationKind.GOOGLE)?.payload as? PendingAuthenticationPayload.Google
        payload?.toAuthorizationRequest()
    }
    override fun saveAuthorizationRequest(authorizationRequest: OAuth2AuthorizationRequest, exchange: ServerWebExchange): Mono<Void> = mono(org.springframework.core.PropagationContextElement()) {
        val binding = browser.start(exchange)
        val payload = authorizationRequest.toPayload(exchange.request.queryParams.getFirst(InvitationContinuations.PARAMETER))
        if (!requests.createGoogle(binding, payload)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "authentication request unavailable")
    }.then()
    override fun removeAuthorizationRequest(exchange: ServerWebExchange): Mono<OAuth2AuthorizationRequest> = mono(org.springframework.core.PropagationContextElement()) {
        val state = state(exchange) ?: return@mono null
        val binding = browser.read(exchange) ?: return@mono null
        val payload = requests.consumeGoogle(state, binding)?.payload as? PendingAuthenticationPayload.Google ?: return@mono null
        payload.invitationContinuation?.let { exchange.attributes[InvitationContinuations.PARAMETER] = it }
        payload.toAuthorizationRequest()
    }
    private fun state(exchange: ServerWebExchange): String? = exchange.request.queryParams["state"]?.singleOrNull()?.let(OAuthAttemptState::validOrNull)
    private fun OAuth2AuthorizationRequest.toPayload(invitation: String?): PendingAuthenticationPayload.Google {
        require(attributes.keys.all { it in setOf("registration_id", "nonce", "code_verifier") })
        require(additionalParameters.keys.all { it in setOf("nonce", "code_challenge", "code_challenge_method", PROMPT_PARAMETER) })
        require(additionalParameters[PROMPT_PARAMETER].let { it == null || it == SELECT_ACCOUNT })
        return PendingAuthenticationPayload.Google(authorizationUri, clientId, redirectUri, scopes,
            OAuthAttemptState.requireValid(state), getAttribute<String>("registration_id") ?: "google", getAttribute("nonce"),
            additionalParameters["nonce"] as? String, getAttribute("code_verifier"), additionalParameters["code_challenge"] as? String,
            additionalParameters["code_challenge_method"] as? String, invitation)
    }
    private fun PendingAuthenticationPayload.Google.toAuthorizationRequest(): OAuth2AuthorizationRequest =
        OAuth2AuthorizationRequest.authorizationCode().authorizationUri(authorizationUri).clientId(clientId).redirectUri(redirectUri)
            .scopes(scopes).state(state).attributes { attributes ->
                attributes["registration_id"] = registrationId
                nonce?.let { attributes["nonce"] = it }
                codeVerifier?.let { attributes["code_verifier"] = it }
            }.additionalParameters { parameters ->
                nonceHash?.let { parameters["nonce"] = it }
                codeChallenge?.let { parameters["code_challenge"] = it }
                codeChallengeMethod?.let { parameters["code_challenge_method"] = it }
            }.build()
}
