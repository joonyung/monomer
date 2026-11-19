package cc.monomer.control.auth.inbound.oauth

import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

class OAuthStateAuthorizationRequestResolver(
    clientRegistrationRepository: ReactiveClientRegistrationRepository,
) : ServerOAuth2AuthorizationRequestResolver {
    private val delegate = DefaultServerOAuth2AuthorizationRequestResolver(clientRegistrationRepository)

    override fun resolve(exchange: ServerWebExchange): Mono<OAuth2AuthorizationRequest> =
        delegate.resolve(exchange).map { authorizationRequest -> withUiState(exchange, authorizationRequest) }

    override fun resolve(
        exchange: ServerWebExchange,
        clientRegistrationId: String,
    ): Mono<OAuth2AuthorizationRequest> = delegate.resolve(exchange, clientRegistrationId)
        .map { authorizationRequest -> withUiState(exchange, authorizationRequest) }

    private fun withUiState(
        exchange: ServerWebExchange,
        authorizationRequest: OAuth2AuthorizationRequest,
    ): OAuth2AuthorizationRequest {
        val state = OAuthAttemptState.requireValid(exchange.request.queryParams.getFirst(OAUTH_STATE_PARAMETER))
        val chooseAccount = accountChooserRequested(exchange)
        return OAuth2AuthorizationRequest.from(authorizationRequest)
            .state(state)
            .additionalParameters { parameters -> if (chooseAccount) parameters[PROMPT_PARAMETER] = SELECT_ACCOUNT }
            .build()
    }

    private fun accountChooserRequested(exchange: ServerWebExchange): Boolean {
        val values = exchange.request.queryParams[PROMPT_PARAMETER] ?: return false
        if (values != listOf(SELECT_ACCOUNT)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt must be select_account when present")
        }
        return true
    }
}

const val OAUTH_STATE_PARAMETER = "oauth_state"
const val PROMPT_PARAMETER = "prompt"
const val SELECT_ACCOUNT = "select_account"
