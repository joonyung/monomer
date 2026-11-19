package cc.monomer.control.auth.inbound.oauth

import cc.monomer.identity.application.identity.EmailChallengeRequiredException
import cc.monomer.identity.application.identity.IdentityLinkRequiredException
import cc.monomer.control.auth.inbound.invitation.InvitationContinuations
import cc.monomer.identity.application.login.LoginUseCase
import cc.monomer.identity.application.session.StartSessionUseCase
import cc.monomer.identity.domain.id.LoginIdentityId
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.AuthenticationAssertion
import cc.monomer.identity.domain.model.AuthenticationProtocol
import cc.monomer.identity.domain.model.EmailEvidence
import cc.monomer.identity.domain.model.LoginIdentity
import cc.monomer.identity.domain.model.LoginIdentityKey
import cc.monomer.identity.domain.model.LoginIdentityLinkedVia
import cc.monomer.identity.domain.model.User
import cc.monomer.control.auth.inbound.session.RefreshCookieFactory
import cc.monomer.control.auth.inbound.session.RefreshCookieProperties
import cc.monomer.control.auth.AuthJwtProperties
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.web.server.WebFilterExchange
import reactor.core.publisher.Mono

class OAuth2LoginSuccessHandlerTest {
    private val oauthState = "google-login-state-0123456789-abcdefgh"
    private val loginUseCase = mockk<LoginUseCase>()
    private val assertionFactory = mockk<GoogleAuthenticationAssertionFactory>()
    private val startSessionUseCase = mockk<StartSessionUseCase>()
    private val assertion = assertion()
    private val handler = OAuth2LoginSuccessHandler(
        loginUseCase,
        assertionFactory,
        OAuthProperties("http://localhost:3000/auth/callback"),
        startSessionUseCase,
        RefreshCookieFactory(
            AuthJwtProperties("monomer-auth", "monomer-internal", 900, 604800, "", ""),
            RefreshCookieProperties(secure = false),
        ),
    )

    @Test
    fun `Google OIDC success preserves callback shape and sets refresh cookie`() {
        val oidcUser = mockk<OidcUser>()
        val response = loginResponse()
        every { assertionFactory.create(oidcUser) } returns assertion
        coEvery { loginUseCase.login(assertion, LoginUseCase.FirstSignIn.CREATE_PERSONAL_ORGANIZATION) } returns response
        coEvery { startSessionUseCase.start(response.loginIdentity, assertion) } returns
            StartSessionUseCase.Started(SessionId.generate(), "raw-refresh-token")
        val exchange = exchange()

        handler.onAuthenticationSuccess(
            WebFilterExchange(exchange) { Mono.empty() },
            TestingAuthenticationToken(oidcUser, ""),
        ).block()

        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.FOUND)
        val query = parseQuery(exchange.response.headers.location.toString().substringAfter('?'))
        assertThat(query).containsEntry("new_user", "true").containsEntry("auth_provider", "google")
        assertThat(query).containsEntry(OAUTH_STATE_PARAMETER, oauthState)
        assertThat(query).doesNotContainKeys("token", "access_token", "expires_at")
        assertThat(exchange.response.cookies.getFirst("refresh_token")!!.value).isEqualTo("raw-refresh-token")
    }

    @Test
    fun `sign-in correlated with an invitation continuation skips the personal Organization bootstrap`() {
        val oidcUser = mockk<OidcUser>()
        val response = loginResponse()
        every { assertionFactory.create(oidcUser) } returns assertion
        coEvery { loginUseCase.login(assertion, LoginUseCase.FirstSignIn.JOIN_BY_INVITATION) } returns response
        coEvery { startSessionUseCase.start(response.loginIdentity, assertion) } returns
            StartSessionUseCase.Started(SessionId.generate(), "raw-refresh-token")
        val exchange = exchange()
        val continuation = "c".repeat(43)
        exchange.attributes[InvitationContinuations.PARAMETER] = continuation

        handler.onAuthenticationSuccess(
            WebFilterExchange(exchange) { Mono.empty() },
            TestingAuthenticationToken(oidcUser, ""),
        ).block()

        coVerify(exactly = 1) { loginUseCase.login(assertion, LoginUseCase.FirstSignIn.JOIN_BY_INVITATION) }
        val query = parseQuery(exchange.response.headers.location.toString().substringAfter('?'))
        assertThat(query).containsEntry(InvitationContinuations.PARAMETER, continuation)
    }

    @Test
    fun `developer denial has stable access denied callback and no refresh cookie`() {
        assertFailure(cc.monomer.identity.domain.model.DeveloperAccessDeniedException(), "access_denied", "not allowed")
    }

    @Test
    fun `identity link required has stable callback code and recovery description`() {
        assertFailure(IdentityLinkRequiredException(), "identity_link_required", "cannot be linked automatically")
    }

    @Test
    fun `email challenge required has stable callback code and recovery description`() {
        assertFailure(EmailChallengeRequiredException(), "email_challenge_required", "Email ownership")
    }

    @Test
    fun `plain OAuth2User is rejected instead of using name as subject`() {
        val oauthUser = DefaultOAuth2User(emptyList(), mapOf("sub" to "fallback-sub"), "sub")
        val exchange = exchange()

        handler.onAuthenticationSuccess(
            WebFilterExchange(exchange) { Mono.empty() },
            TestingAuthenticationToken(oauthUser, ""),
        ).block()

        assertThat(parseQuery(exchange.response.headers.location.toString().substringAfter('?')))
            .containsEntry("error", "provider_missing_claim")
    }

    @Test
    fun `Google failure logs safe types and allowlisted provider code without raw details`() {
        val logger = LoggerFactory.getLogger(OAuth2LoginSuccessHandler::class.java) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            for ((providerCode, expectedCode) in listOf("invalid_grant" to "invalid_grant", "user@example.test raw-secret-token" to "other")) {
                val failure = OAuth2AuthenticationException(OAuth2Error(providerCode), "user@example.test raw-secret-token")
                handler.onAuthenticationFailure(WebFilterExchange(exchange()) { Mono.empty() }, failure).block()
                val event = logs.list.last()
                assertThat(event.formattedMessage).isEqualTo("google_sign_in_failed")
                assertThat(cc.monomer.control.observability.RequestLogTestSupport.encoded(event)).contains("stack_trace")
                assertThat(event.keyValuePairs.associate { it.key to it.value })
                    .containsEntry("event", "google_sign_in_failed")
                    .containsEntry("provider_code", expectedCode)
                    .containsEntry("failure_types", OAuth2AuthenticationException::class.java.name)
                assertThat(event.keyValuePairs.toString()).doesNotContain("user@example.test", "raw-secret-token")
            }
            assertThat(logs.list).hasSize(2)
        } finally {
            logger.detachAppender(logs)
        }
    }

    private fun assertFailure(failure: RuntimeException, code: String, descriptionFragment: String) {
        val oidcUser = mockk<OidcUser>()
        every { assertionFactory.create(oidcUser) } returns assertion
        coEvery { loginUseCase.login(assertion, any()) } throws failure
        val exchange = exchange()
        handler.onAuthenticationSuccess(
            WebFilterExchange(exchange) { Mono.empty() },
            TestingAuthenticationToken(oidcUser, ""),
        ).block()
        val query = parseQuery(exchange.response.headers.location.toString().substringAfter('?'))
        assertThat(query["error"]).isEqualTo(code)
        assertThat(query["error_description"]).contains(descriptionFragment)
        assertThat(query["auth_provider"]).isEqualTo("google")
        assertThat(query[OAUTH_STATE_PARAMETER]).isEqualTo(oauthState)
        assertThat(exchange.response.cookies.getFirst("refresh_token")).isNull()
        coVerify(exactly = 0) { startSessionUseCase.start(any(), any()) }
    }

    private fun exchange() = MockServerWebExchange.from(
        MockServerHttpRequest.get("/login/oauth2/code/google?state=$oauthState"),
    )

    private fun loginResponse(): LoginUseCase.Response {
        val now = Instant.parse("2026-07-11T03:00:00Z")
        val user = User(UserId.generate(), "alice@gmail.com", "Alice")
        val identity = LoginIdentity(
            LoginIdentityId.generate(),
            user.userId,
            assertion.identityKey,
            LoginIdentityLinkedVia.SIGNUP,
            now,
        )
        return LoginUseCase.Response(user, identity, newUser = true)
    }

    private fun assertion() = AuthenticationAssertion(
        LoginIdentityKey(AuthenticationProtocol.OIDC, "https://accounts.google.com", "google-sub"),
        "builtin/google",
        "https://accounts.google.com",
        "alice@gmail.com",
        true,
        EmailEvidence.AUTHORITATIVE,
        "Alice",
        null,
        null,
        emptyList(),
        null,
    )

    private fun parseQuery(query: String): Map<String, String> = query.split('&').associate { pair ->
        pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('='), StandardCharsets.UTF_8)
    }
}
