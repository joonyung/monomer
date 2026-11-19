package cc.monomer.control.auth

import cc.monomer.control.auth.inbound.PendingAuthenticationBrowser
import cc.monomer.control.auth.inbound.invitation.InvitationContinuations
import cc.monomer.control.auth.inbound.oauth.MultiRequestOAuth2AuthorizationRequestRepository
import cc.monomer.control.auth.inbound.session.RefreshCookieProperties
import cc.monomer.identity.application.organization.OrganizationInvitationService
import cc.monomer.identity.application.pending.PendingAuthenticationRequests
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.outbound.postgres.PostgresPendingAuthenticationRepository
import cc.monomer.identity.outbound.postgres.SpringTransactionRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.http.HttpCookie
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.transaction.support.TransactionTemplate

class PendingGoogleHttpPersistenceIntegrationTest {
    private val now = Instant.parse("2026-09-11T00:00:00Z")
    private val cookieName = "__Host-monomer_auth_pending"
    private class Instance(val repository: MultiRequestOAuth2AuthorizationRequestRepository, val invitations: InvitationContinuations)
    private fun instance(time: Instant): Instance {
        val jdbc = NamedParameterJdbcTemplate(database.dataSource)
        val manager = DataSourceTransactionManager(database.dataSource)
        val transactions = SpringTransactionRunner(TransactionTemplate(manager), TransactionTemplate(manager).apply { isReadOnly = true }, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        val requests = PendingAuthenticationRequests(PostgresPendingAuthenticationRepository(jdbc), transactions, Clock.fixed(time, ZoneOffset.UTC))
        val browser = PendingAuthenticationBrowser(requests, RefreshCookieProperties(true))
        return Instance(MultiRequestOAuth2AuthorizationRequestRepository(requests, browser), InvitationContinuations(requests, browser))
    }
    @BeforeEach fun schema() {
        database.dataSource.connection.use { connection -> connection.createStatement().use {
            it.execute(checkNotNull(javaClass.classLoader.getResource("db/identity-runtime/schema.sql")).readText())
        } }
    }
    private fun exchange(binding: String?, path: String) = MockServerWebExchange.from(
        MockServerHttpRequest.get("https://control.example.test$path").apply {
            if (binding != null) cookie(HttpCookie(cookieName, binding))
        })
    private fun request(state: String) = OAuth2AuthorizationRequest.authorizationCode()
        .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth").clientId("fixture-google-client")
        .redirectUri("https://control.example.test/login/oauth2/code/google").scopes(setOf("openid", "email"))
        .state(state).attributes { it["registration_id"] = "google"; it["nonce"] = "fixture-nonce"; it["code_verifier"] = "v".repeat(43) }
        .additionalParameters { it["nonce"] = "fixture-nonce-hash"; it["code_challenge"] = "c".repeat(43); it["code_challenge_method"] = "S256" }.build()

    @Test fun `Google start A callback B preserves PKCE nonce invitation and independent tabs`() = runBlocking<Unit> {
        val first = instance(now)
        val second = instance(now)
        val start = exchange(null, "/auth/invitations/exchange")
        val invitation = first.invitations.put(start, OrganizationInvitationService.Proof(Uuid.generateV7(), OrganizationId.generate(), "a".repeat(64), now.plusSeconds(1800)))
        val binding = checkNotNull(start.response.cookies.getFirst(cookieName)).value
        val firstState = "first-" + "a".repeat(43)
        val secondState = "second-" + "b".repeat(43)
        first.repository.saveAuthorizationRequest(request(firstState), exchange(binding, "/oauth2/authorization/google?invitation_continuation=$invitation")).block()
        second.repository.saveAuthorizationRequest(request(secondState), exchange(binding, "/oauth2/authorization/google")).block()
        val missing = exchange(null, "/login/oauth2/code/google?state=$firstState")
        assertNull(second.repository.removeAuthorizationRequest(missing).block())
        assertTrue(missing.response.cookies.isEmpty())
        assertNull(second.repository.removeAuthorizationRequest(exchange("x".repeat(43), "/login/oauth2/code/google?state=$firstState")).block())
        val callback = exchange(binding, "/login/oauth2/code/google?state=$firstState")
        val restored = assertNotNull(second.repository.removeAuthorizationRequest(callback).block())
        val original = request(firstState)
        assertTrue(original.attributes == restored.attributes, "OIDC nonce and PKCE attributes must survive storage")
        assertTrue(original.additionalParameters == restored.additionalParameters, "OIDC and PKCE provider parameters must survive storage")
        assertEquals(original.redirectUri, restored.redirectUri)
        assertEquals(original.authorizationRequestUri, restored.authorizationRequestUri)
        assertTrue(callback.getAttribute<String>(InvitationContinuations.PARAMETER) == invitation)
        assertNotNull(second.invitations.get(exchange(binding, "/auth/invitations/resolve"), invitation))
        assertNull(first.repository.removeAuthorizationRequest(exchange(binding, "/login/oauth2/code/google?state=$firstState")).block())
        assertNotNull(first.repository.removeAuthorizationRequest(exchange(binding, "/login/oauth2/code/google?state=$secondState")).block())
    }

    @Test fun `expired invitation correlation is omitted and Google fixed expiry is never extended by reads`() = runBlocking<Unit> {
        val first = instance(now)
        val start = exchange(null, "/auth/invitations/exchange")
        val invitation = first.invitations.put(start, OrganizationInvitationService.Proof(Uuid.generateV7(), OrganizationId.generate(), "a".repeat(64), now.plusSeconds(20)))
        val binding = checkNotNull(start.response.cookies.getFirst(cookieName)).value
        val state = "expiring-" + "a".repeat(43)
        first.repository.saveAuthorizationRequest(request(state), exchange(binding, "/oauth2/authorization/google?invitation_continuation=$invitation")).block()
        val afterProofExpiry = instance(now.plusSeconds(20))
        val callback = exchange(binding, "/login/oauth2/code/google?state=$state")
        assertNotNull(afterProofExpiry.repository.removeAuthorizationRequest(callback).block())
        assertNull(callback.getAttribute<String>(InvitationContinuations.PARAMETER))
        val otherState = "fixed-expiry-" + "b".repeat(43)
        first.repository.saveAuthorizationRequest(request(otherState), exchange(binding, "/oauth2/authorization/google")).block()
        val createdBinding = binding
        assertNotNull(instance(now.plusSeconds(1799)).repository.loadAuthorizationRequest(exchange(createdBinding, "/callback?state=$otherState")).block())
        assertNull(instance(now.plusSeconds(1800)).repository.removeAuthorizationRequest(exchange(createdBinding, "/callback?state=$otherState")).block())
    }
    companion object { @JvmField @RegisterExtension val database = OAuthHttpPostgres() }
}
