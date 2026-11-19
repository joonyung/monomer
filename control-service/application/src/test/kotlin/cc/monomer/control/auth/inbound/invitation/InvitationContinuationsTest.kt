package cc.monomer.control.auth.inbound.invitation

import cc.monomer.control.auth.PendingAuthenticationFixture
import cc.monomer.control.auth.inbound.oauth.MultiRequestOAuth2AuthorizationRequestRepository
import cc.monomer.identity.application.organization.OrganizationInvitationService
import cc.monomer.identity.domain.id.OrganizationId
import java.time.Clock
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpCookie
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest

class InvitationContinuationsTest {
    private fun proof() = OrganizationInvitationService.Proof(Uuid.generateV7(), OrganizationId.generate(), "a".repeat(64), Instant.now().plusSeconds(1800))
    private fun exchange(cookie: String?, path: String = "/auth/invitations/exchange") =
        MockServerWebExchange.from(MockServerHttpRequest.get(path).apply {
            if (cookie != null) cookie(HttpCookie(PendingAuthenticationFixture.COOKIE, cookie))
        })

    @Test
    fun `continuation is bounded browser scoped expired and removed at terminal result`() = runBlocking<Unit> {
        val fixture = PendingAuthenticationFixture(Clock.systemUTC())
        val continuations = fixture.invitations
        val start = exchange(null)
        val first = continuations.put(start, proof())
        val cookie = start.response.cookies.getFirst(PendingAuthenticationFixture.COOKIE)!!.value
        assertThat(continuations.get(exchange(null), first)).isNull()
        repeat(16) { continuations.put(exchange(cookie), proof()) }
        assertThat(continuations.get(exchange(cookie), first)).isNull()
        val current = continuations.put(exchange(cookie), proof())
        assertThat(continuations.get(exchange(cookie), current)).isNotNull()
        continuations.remove(exchange(cookie), current)
        assertThat(continuations.get(exchange(cookie), current)).isNull()
        val expired = continuations.put(exchange(cookie), proof().copy(expiresAt = Instant.now().minusSeconds(1)))
        assertThat(continuations.get(exchange(cookie), expired)).isNull()
    }

    @Test
    fun `OAuth correlation preserves tabs and returns id only for saved matching request`() = runBlocking<Unit> {
        val fixture = PendingAuthenticationFixture(Clock.systemUTC())
        val start = exchange(null)
        val first = fixture.invitations.put(start, proof())
        val cookie = start.response.cookies.getFirst(PendingAuthenticationFixture.COOKIE)!!.value
        val second = fixture.invitations.put(exchange(cookie), proof())
        val repo = MultiRequestOAuth2AuthorizationRequestRepository(fixture.requests, fixture.browser)
        val firstState = "first-state-" + "a".repeat(32)
        val secondState = "second-state-" + "b".repeat(32)
        fun auth(state: String) = OAuth2AuthorizationRequest.authorizationCode().authorizationUri("https://accounts.example/authorize")
            .clientId("test").redirectUri("http://localhost/callback").state(state)
            .attributes { it["registration_id"] = "google" }.build()
        repo.saveAuthorizationRequest(auth(firstState), exchange(cookie, "/oauth2/authorization/google?invitation_continuation=$first")).block()
        repo.saveAuthorizationRequest(auth(secondState), exchange(cookie, "/oauth2/authorization/google?invitation_continuation=$second")).block()
        val callback = exchange(cookie, "/login/oauth2/code/google?state=$firstState")
        repo.removeAuthorizationRequest(callback).block()
        assertThat(callback.getAttribute<String>(InvitationContinuations.PARAMETER)).isEqualTo(first)
        assertThat(fixture.invitations.get(exchange(cookie), first)).isNotNull()
        val replay = exchange(cookie, "/login/oauth2/code/google?state=$firstState")
        assertThat(repo.removeAuthorizationRequest(replay).block()).isNull()
        assertThat(replay.getAttribute<String>(InvitationContinuations.PARAMETER)).isNull()
        val other = exchange(cookie, "/login/oauth2/code/google?state=$secondState")
        repo.removeAuthorizationRequest(other).block()
        assertThat(other.getAttribute<String>(InvitationContinuations.PARAMETER)).isEqualTo(second)
        assertThatThrownBy {
            repo.saveAuthorizationRequest(auth("bad-state-" + "z".repeat(32)), exchange(cookie, "/oauth2/authorization/google?invitation_continuation=unknown")).block()
        }.hasMessageContaining("unavailable")
    }
}
