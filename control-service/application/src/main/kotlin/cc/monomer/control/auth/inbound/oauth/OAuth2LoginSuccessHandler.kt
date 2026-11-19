package cc.monomer.control.auth.inbound.oauth

import cc.monomer.identity.domain.model.DeveloperAccessDeniedException

import cc.monomer.control.auth.inbound.invitation.InvitationContinuations
import cc.monomer.identity.application.identity.EmailChallengeRequiredException
import cc.monomer.identity.application.identity.IdentityLinkRequiredException
import cc.monomer.identity.application.login.LoginUseCase
import cc.monomer.identity.application.session.StartSessionUseCase
import cc.monomer.control.auth.inbound.session.RefreshCookieFactory
import cc.monomer.control.jdbc.causeTypeChain
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2AuthorizationException
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.server.WebFilterExchange
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
class OAuth2LoginSuccessHandler(
    private val loginUseCase: LoginUseCase,
    private val assertionFactory: GoogleAuthenticationAssertionFactory,
    private val oauthProperties: OAuthProperties,
    private val startSessionUseCase: StartSessionUseCase,
    private val refreshCookieFactory: RefreshCookieFactory,
) : ServerAuthenticationSuccessHandler, ServerAuthenticationFailureHandler {

    override fun onAuthenticationSuccess(
        webFilterExchange: WebFilterExchange,
        authentication: Authentication,
    ): Mono<Void> = mono(org.springframework.core.PropagationContextElement()) {
        val oidcUser = authentication.principal as? OidcUser
            ?: throw OAuthProviderClaimException("id_token")
        val assertion = assertionFactory.create(oidcUser)
        val firstSignIn = if (webFilterExchange.exchange.getAttribute<String>(InvitationContinuations.PARAMETER) == null) {
            LoginUseCase.FirstSignIn.CREATE_PERSONAL_ORGANIZATION
        } else {
            LoginUseCase.FirstSignIn.JOIN_BY_INVITATION
        }
        val response = loginUseCase.login(assertion, firstSignIn)
        val startedSession = startSessionUseCase.start(response.loginIdentity, assertion)
        val redirectUrl = callbackUrl(
            callbackParameters(
                webFilterExchange.exchange,
                mapOf(
                    "new_user" to response.newUser.toString(),
                    "auth_provider" to "google",
                ),
            ),
        )

        val exchange = webFilterExchange.exchange
        exchange.response.addCookie(refreshCookieFactory.build(startedSession.rawRefreshToken))
        exchange.response.statusCode = HttpStatus.FOUND
        exchange.response.headers.location = URI.create(redirectUrl)
    }.then().onErrorResume { failure ->
        redirectFailure(webFilterExchange.exchange, failure)
    }

    override fun onAuthenticationFailure(
        webFilterExchange: WebFilterExchange,
        exception: AuthenticationException,
    ): Mono<Void> = redirectFailure(webFilterExchange.exchange, exception)

    private fun redirectFailure(exchange: ServerWebExchange, failure: Throwable): Mono<Void> {
        val providerCode = when (failure) {
            is OAuth2AuthenticationException -> failure.error.errorCode
            is OAuth2AuthorizationException -> failure.error.errorCode
            else -> null
        }
        val diagnosticCode = providerCode?.takeIf { it in setOf(
            "authorization_request_not_found", "invalid_token_response", "invalid_id_token",
            "invalid_user_info_response", "invalid_state_parameter", "invalid_redirect_uri_parameter",
            "invalid_client", "invalid_grant", "access_denied",
        ) } ?: "other"
        LoggerFactory.getLogger(OAuth2LoginSuccessHandler::class.java).atWarn()
            .addKeyValue("event", "google_sign_in_failed")
            .setCause(failure)
            .addKeyValue("failure_types", failure.causeTypeChain())
            .addKeyValue("provider_code", diagnosticCode)
            .log("google_sign_in_failed")
        exchange.response.statusCode = HttpStatus.FOUND
        exchange.response.headers.location = URI.create(
            callbackUrl(
                callbackParameters(
                    exchange,
                    mapOf(
                        "error" to errorCode(failure),
                        "error_description" to errorDescription(failure),
                        "auth_provider" to "google",
                    ),
                ),
            ),
        )
        return exchange.response.setComplete()
    }

    private fun callbackParameters(
        exchange: ServerWebExchange,
        parameters: Map<String, String>,
    ): Map<String, String> {
        val oauthState = OAuthAttemptState.validOrNull(exchange.request.queryParams.getFirst("state"))
        val correlated = exchange.getAttribute<String>(InvitationContinuations.PARAMETER)
        val result = if (oauthState == null) parameters else parameters + (OAUTH_STATE_PARAMETER to oauthState)
        return if (correlated == null) result else result + (InvitationContinuations.PARAMETER to correlated)
    }

    private fun callbackUrl(parameters: Map<String, String>): String = buildString {
        append(oauthProperties.frontendCallbackUrl)
        append(if (oauthProperties.frontendCallbackUrl.contains("?")) "&" else "?")
        append(
            parameters.entries.joinToString("&") { (key, value) ->
                key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)
            },
        )
    }

    private fun errorCode(failure: Throwable): String = when (failure) {
        is DeveloperAccessDeniedException -> "access_denied"
        is IdentityLinkRequiredException -> "identity_link_required"
        is EmailChallengeRequiredException -> "email_challenge_required"
        is OAuthProviderClaimException -> "provider_missing_claim"
        else -> "oauth_failed"
    }

    private fun errorDescription(failure: Throwable): String = when (failure) {
        is DeveloperAccessDeniedException ->
            "This Google account is not allowed in this development environment."
        is IdentityLinkRequiredException ->
            "This login identity cannot be linked automatically. Sign in with a linked provider or contact your Organization administrator."
        is EmailChallengeRequiredException ->
            "Email ownership must be verified before this Google account can be linked."
        is OAuthProviderClaimException -> "Google did not return the required ${failure.claimName} claim."
        else -> "Google sign-in could not be completed."
    }
}
