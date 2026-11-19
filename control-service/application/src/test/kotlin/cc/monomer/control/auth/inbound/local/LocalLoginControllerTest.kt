package cc.monomer.control.auth.inbound.local

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
import cc.monomer.control.auth.inbound.oauth.OAuthProperties
import cc.monomer.control.auth.inbound.oauth.OAUTH_STATE_PARAMETER
import cc.monomer.control.auth.inbound.session.RefreshCookieFactory
import cc.monomer.control.auth.inbound.session.RefreshCookieProperties
import cc.monomer.control.auth.AuthJwtProperties
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange

class LocalLoginControllerTest {
    @Test
    fun `local login uses synthetic identity and sets refresh cookie without access token`() = runTest {
        val properties = LocalLoginProperties(true, "monomer-local-dev", "dev@local.monomer", "Monomer Dev")
        val loginUseCase = mockk<LoginUseCase>()
        val startSessionUseCase = mockk<StartSessionUseCase>()
        val assertionSlot = slot<AuthenticationAssertion>()
        val response = response(properties)
        coEvery { loginUseCase.login(capture(assertionSlot), LoginUseCase.FirstSignIn.CREATE_PERSONAL_ORGANIZATION) } returns response
        coEvery { startSessionUseCase.start(response.loginIdentity, any()) } returns
            StartSessionUseCase.Started(SessionId.generate(), "raw-refresh-token")
        val controller = LocalLoginController(
            properties,
            loginUseCase,
            OAuthProperties("http://localhost:3000/auth/callback"),
            startSessionUseCase,
            RefreshCookieFactory(
                AuthJwtProperties("monomer-auth", "monomer-internal", 900, 604800, "", ""),
                RefreshCookieProperties(secure = false),
            ),
        )
        val oauthState = "dev-login-state-0123456789-abcdefghij"
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/auth/local/login?$OAUTH_STATE_PARAMETER=$oauthState"),
        )

        val httpResponse = controller.login(oauthState, exchange)

        assertThat(httpResponse.statusCode).isEqualTo(HttpStatus.FOUND)
        val query = parseQuery(httpResponse.headers.location.toString().substringAfter('?'))
        assertThat(query).containsEntry("new_user", "true").containsEntry("auth_provider", "local")
        assertThat(query).containsEntry(OAUTH_STATE_PARAMETER, oauthState)
        assertThat(query).doesNotContainKeys("token", "access_token", "expires_at")
        assertThat(exchange.response.cookies.getFirst("refresh_token")!!.value).isEqualTo("raw-refresh-token")
        assertThat(assertionSlot.captured.identityKey).isEqualTo(
            LoginIdentityKey(AuthenticationProtocol.DEVELOPMENT, "urn:monomer:auth:dev", properties.subject),
        )
        assertThat(assertionSlot.captured.authenticationSourceRef).isEqualTo("builtin/local-dev")
        assertThat(assertionSlot.captured.emailEvidence).isEqualTo(EmailEvidence.NON_AUTHORITATIVE)
    }

    private fun response(properties: LocalLoginProperties): LoginUseCase.Response {
        val now = Instant.parse("2026-07-11T03:00:00Z")
        val user = User(UserId.generate(), properties.email, properties.displayName)
        val key = LoginIdentityKey(AuthenticationProtocol.DEVELOPMENT, "urn:monomer:auth:dev", properties.subject)
        val identity = LoginIdentity(
            LoginIdentityId.generate(), user.userId, key, LoginIdentityLinkedVia.SIGNUP, now,
        )
        return LoginUseCase.Response(user, identity, newUser = true)
    }

    private fun parseQuery(query: String): Map<String, String> = query.split('&').associate { pair ->
        pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('='), StandardCharsets.UTF_8)
    }
}
