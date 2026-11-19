package cc.monomer.control.auth.inbound.oauth

import cc.monomer.control.auth.PendingAuthenticationFixture
import java.time.Clock
import org.springframework.http.HttpCookie
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest

class OAuthAttemptCorrelationTest {
    private val firstState = "first-oauth-state-0123456789-abcdefghij"
    private val secondState = "second-oauth-state-0123456789-abcdefghi"

    @Test
    fun `authorization request uses validated UI state as OAuth state`() {
        val resolver = OAuthStateAuthorizationRequestResolver(
            InMemoryReactiveClientRegistrationRepository(clientRegistration()),
        )
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/oauth2/authorization/google?$OAUTH_STATE_PARAMETER=$firstState"),
        )

        val resolved = resolver.resolve(exchange).block()

        assertThat(resolved!!.state).isEqualTo(firstState)
    }

    @Test
    fun `authorization request rejects missing or weak UI state`() {
        val resolver = OAuthStateAuthorizationRequestResolver(
            InMemoryReactiveClientRegistrationRepository(clientRegistration()),
        )

        assertThatThrownBy {
            resolver.resolve(
                MockServerWebExchange.from(MockServerHttpRequest.get("/oauth2/authorization/google")),
            ).block()
        }.hasMessageContaining("oauth_state")

        assertThatThrownBy {
            resolver.resolve(
                MockServerWebExchange.from(
                    MockServerHttpRequest.get("/oauth2/authorization/google?$OAUTH_STATE_PARAMETER=short"),
                ),
            ).block()
        }.hasMessageContaining("oauth_state")
    }

    @Test
    fun `authorization request opens Google account chooser only when the web asks for it`() {
        val resolver = OAuthStateAuthorizationRequestResolver(
            InMemoryReactiveClientRegistrationRepository(clientRegistration()),
        )

        val plain = resolver.resolve(MockServerWebExchange.from(
            MockServerHttpRequest.get("/oauth2/authorization/google?$OAUTH_STATE_PARAMETER=$firstState"),
        )).block()!!
        val chooser = resolver.resolve(MockServerWebExchange.from(
            MockServerHttpRequest.get("/oauth2/authorization/google?$OAUTH_STATE_PARAMETER=$firstState&prompt=select_account"),
        )).block()!!

        assertThat(plain.additionalParameters).doesNotContainKey("prompt")
        assertThat(plain.authorizationRequestUri).doesNotContain("prompt=")
        assertThat(chooser.additionalParameters["prompt"]).isEqualTo("select_account")
        assertThat(chooser.authorizationRequestUri).contains("prompt=select_account").doesNotContain("login_hint")
    }

    @Test
    fun `authorization request rejects any other prompt value`() {
        val resolver = OAuthStateAuthorizationRequestResolver(
            InMemoryReactiveClientRegistrationRepository(clientRegistration()),
        )
        listOf("prompt=consent", "prompt=none", "prompt=select_account%20consent", "prompt=select_account&prompt=select_account", "prompt=").forEach { query ->
            assertThatThrownBy {
                resolver.resolve(MockServerWebExchange.from(
                    MockServerHttpRequest.get("/oauth2/authorization/google?$OAUTH_STATE_PARAMETER=$firstState&$query"),
                )).block()
            }.hasMessageContaining("select_account")
        }
    }

    @Test
    fun `repository stores an account chooser attempt and restores it for the callback`() {
        val pending = PendingAuthenticationFixture(Clock.systemUTC())
        val repository = MultiRequestOAuth2AuthorizationRequestRepository(pending.requests, pending.browser)
        val start = exchange(null, "/oauth2/authorization/google")
        val request = OAuth2AuthorizationRequest.from(authorizationRequest(firstState))
            .additionalParameters { it["prompt"] = "select_account" }.build()
        repository.saveAuthorizationRequest(request, start).block()
        val cookie = start.response.cookies.getFirst(PendingAuthenticationFixture.COOKIE)!!.value

        assertThat(repository.removeAuthorizationRequest(exchange(cookie, "/callback?state=$firstState")).block()!!.state).isEqualTo(firstState)
    }

    @Test
    fun `repository preserves concurrent tab attempts independently`() {
        val pending = PendingAuthenticationFixture(Clock.systemUTC())
        val repository = MultiRequestOAuth2AuthorizationRequestRepository(pending.requests, pending.browser)
        val start = exchange(null, "/oauth2/authorization/google")
        repository.saveAuthorizationRequest(authorizationRequest(firstState), start).block()
        val cookie = start.response.cookies.getFirst(PendingAuthenticationFixture.COOKIE)!!.value
        repository.saveAuthorizationRequest(authorizationRequest(secondState), exchange(cookie, "/oauth2/authorization/google")).block()

        assertThat(repository.loadAuthorizationRequest(exchange(cookie, "/callback?state=$firstState")).block()!!.state).isEqualTo(firstState)
        assertThat(repository.removeAuthorizationRequest(exchange(cookie, "/callback?state=$firstState")).block()!!.state).isEqualTo(firstState)
        assertThat(repository.removeAuthorizationRequest(exchange(cookie, "/callback?state=$firstState")).block()).isNull()
        assertThat(repository.loadAuthorizationRequest(exchange(cookie, "/callback?state=$secondState")).block()!!.state).isEqualTo(secondState)
    }

    @Test
    fun `repository rejects missing browser and unknown callback state without consuming pending attempt`() {
        val pending = PendingAuthenticationFixture(Clock.systemUTC())
        val repository = MultiRequestOAuth2AuthorizationRequestRepository(pending.requests, pending.browser)
        val start = exchange(null, "/oauth2/authorization/google")
        repository.saveAuthorizationRequest(authorizationRequest(firstState), start).block()
        val cookie = start.response.cookies.getFirst(PendingAuthenticationFixture.COOKIE)!!.value
        val missingBrowser = exchange(null, "/callback?state=$firstState")
        assertThat(repository.removeAuthorizationRequest(missingBrowser).block()).isNull()
        assertThat(missingBrowser.response.cookies).isEmpty()
        assertThat(repository.removeAuthorizationRequest(exchange("z".repeat(43), "/callback?state=$firstState")).block()).isNull()
        assertThat(repository.removeAuthorizationRequest(exchange(cookie, "/callback?state=$secondState")).block()).isNull()
        assertThat(repository.loadAuthorizationRequest(exchange(cookie, "/callback?state=$firstState")).block()!!.state).isEqualTo(firstState)
    }

    private fun clientRegistration(): ClientRegistration = ClientRegistration.withRegistrationId("google")
        .clientId("client")
        .clientSecret("secret")
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
        .scope("openid", "email")
        .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
        .tokenUri("https://oauth2.googleapis.com/token")
        .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
        .issuerUri("https://accounts.google.com")
        .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
        .userNameAttributeName("sub")
        .clientName("Google")
        .build()

    private fun authorizationRequest(state: String): OAuth2AuthorizationRequest =
        OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
            .clientId("client")
            .redirectUri("http://localhost/login/oauth2/code/google")
            .state(state)
            .attributes { it["registration_id"] = "google" }
            .build()

    private fun exchange(cookie: String?, path: String): MockServerWebExchange =
        MockServerWebExchange.from(MockServerHttpRequest.get(path).apply {
            if (cookie != null) cookie(HttpCookie(PendingAuthenticationFixture.COOKIE, cookie))
        })
}
