package cc.monomer.control.auth.inbound.local

import cc.monomer.identity.application.login.LoginUseCase
import cc.monomer.identity.application.session.StartSessionUseCase
import cc.monomer.identity.domain.model.AuthenticationAssertion
import cc.monomer.identity.domain.model.AuthenticationProtocol
import cc.monomer.identity.domain.model.EmailEvidence
import cc.monomer.identity.domain.model.LoginIdentityKey
import cc.monomer.control.auth.inbound.oauth.OAuthProperties
import cc.monomer.control.auth.inbound.oauth.OAuthAttemptState
import cc.monomer.control.auth.inbound.oauth.OAUTH_STATE_PARAMETER
import cc.monomer.control.auth.inbound.session.RefreshCookieFactory
import java.net.URI
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

@RestController
@Profile("local")
@ConditionalOnProperty(prefix = "auth.local-login", name = ["enabled"], havingValue = "true")
class LocalLoginController(
    private val properties: LocalLoginProperties,
    private val loginUseCase: LoginUseCase,
    private val oauthProperties: OAuthProperties,
    private val startSessionUseCase: StartSessionUseCase,
    private val refreshCookieFactory: RefreshCookieFactory,
) {

    @GetMapping("/auth/local/login")
    suspend fun login(
        @RequestParam(name = OAUTH_STATE_PARAMETER) rawOauthState: String?,
        exchange: ServerWebExchange,
    ): ResponseEntity<Void> {
        val oauthState = OAuthAttemptState.requireValid(rawOauthState)
        val assertion = AuthenticationAssertion(
            identityKey = LoginIdentityKey(
                protocol = AuthenticationProtocol.DEVELOPMENT,
                namespace = DEVELOPMENT_NAMESPACE,
                subject = properties.subject,
            ),
            authenticationSourceRef = "builtin/local-dev",
            authenticationIssuer = DEVELOPMENT_NAMESPACE,
            assertedEmail = properties.email,
            assertedEmailVerified = true,
            emailEvidence = EmailEvidence.NON_AUTHORITATIVE,
            displayName = properties.displayName,
            upstreamAuthenticatedAt = null,
            authenticationContext = null,
            authenticationMethods = emptyList(),
            upstreamSessionId = null,
        )
        val login = loginUseCase.login(assertion, LoginUseCase.FirstSignIn.CREATE_PERSONAL_ORGANIZATION)
        val startedSession = startSessionUseCase.start(login.loginIdentity, assertion)
        exchange.response.addCookie(refreshCookieFactory.build(startedSession.rawRefreshToken))
        val location = buildString {
            append(oauthProperties.frontendCallbackUrl)
            append("?new_user=").append(login.newUser)
            append("&auth_provider=local")
            append("&").append(OAUTH_STATE_PARAMETER).append("=").append(oauthState)
        }
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(location))
            .build()
    }

    private companion object {
        const val DEVELOPMENT_NAMESPACE = "urn:monomer:auth:dev"
    }
}
