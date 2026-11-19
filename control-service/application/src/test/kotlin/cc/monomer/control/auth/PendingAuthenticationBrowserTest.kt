package cc.monomer.control.auth

import cc.monomer.control.auth.inbound.PendingAuthenticationBrowser
import cc.monomer.control.auth.inbound.session.RefreshCookieProperties
import cc.monomer.identity.application.organization.OrganizationInvitationService
import cc.monomer.identity.domain.id.OrganizationId
import java.time.Clock
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpCookie
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertFailsWith

class PendingAuthenticationBrowserTest {
    private val fixture = PendingAuthenticationFixture(Clock.systemUTC())
    private val browser = PendingAuthenticationBrowser(fixture.requests, RefreshCookieProperties(true))
    private val cookieName = "__Host-monomer_auth_pending"

    @Test fun `HTTPS browser binding uses host restricted attributes and replaces unknown chosen value`() = runBlocking<Unit> {
        val chosen = "a".repeat(43)
        val start = MockServerWebExchange.from(MockServerHttpRequest.get("https://control.example.test/oauth/authorize")
            .cookie(HttpCookie(cookieName, chosen)))
        val binding = browser.start(start)
        assertThat(binding == chosen).isFalse()
        val cookie = start.response.cookies.getFirst(cookieName)!!
        assertThat(cookie.isSecure && cookie.isHttpOnly).isTrue()
        assertThat(cookie.sameSite).isEqualTo("Lax")
        assertThat(cookie.path).isEqualTo("/")
        assertThat(cookie.domain).isNull()
        fixture.requests.createInvitation(binding, OrganizationInvitationService.Proof(Uuid.generateV7(), OrganizationId.generate(),
            "b".repeat(64), Instant.now().plusSeconds(1800)))
        val next = MockServerWebExchange.from(MockServerHttpRequest.get("https://control.example.test/oauth/authorize")
            .cookie(HttpCookie(cookieName, binding)))
        assertThat(browser.start(next) == binding).isTrue()
        assertThat(next.response.cookies).isEmpty()
    }

    @Test fun `duplicate cookie injection is rejected instead of choosing first or last`() = runBlocking<Unit> {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("https://control.example.test/oauth/authorize")
            .cookie(HttpCookie(cookieName, "a".repeat(43)), HttpCookie(cookieName, "b".repeat(43))))
        assertFailsWith<ResponseStatusException> { browser.start(exchange) }
        assertThat(exchange.response.cookies).isEmpty()
    }

    @Test fun `callback never creates a missing binding and ignores weaker local cookie on HTTPS`() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("https://control.example.test/login/oauth2/code/google")
            .cookie(HttpCookie(PendingAuthenticationFixture.COOKIE, "a".repeat(43))))
        assertThat(browser.read(exchange)).isNull()
        assertThat(exchange.response.cookies).isEmpty()
    }
}
