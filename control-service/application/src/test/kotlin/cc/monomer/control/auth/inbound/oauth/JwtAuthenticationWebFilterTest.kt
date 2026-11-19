package cc.monomer.control.auth.inbound.oauth

import cc.monomer.identity.domain.id.LoginIdentityId
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.AuthenticationProtocol
import cc.monomer.identity.domain.model.DeveloperAccessPolicy
import cc.monomer.identity.domain.model.EmailEvidence
import cc.monomer.identity.domain.model.Session
import cc.monomer.identity.domain.model.SessionRevocationReason
import cc.monomer.identity.domain.port.SessionRepository
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.outbound.jwt.JwtIssuer
import cc.monomer.identity.outbound.jwt.JwtKeySource
import cc.monomer.identity.outbound.jwt.JwtProperties
import cc.monomer.identity.outbound.jwt.JwtVerifier
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.web.server.WebFilterChainProxy
import org.springframework.test.web.reactive.server.WebTestClient

class JwtAuthenticationWebFilterTest {
    private val now = Instant.now()
    private val userId = UserId.generate()
    private val sessionId = SessionId.generate()
    private val sessionRepository = mockk<SessionRepository>()
    private val handlerCalls = AtomicInteger()
    private val readTransactions = AtomicInteger()
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val keySource = JwtKeySource(keyPair.private as RSAPrivateKey, keyPair.public as RSAPublicKey, "test-kid")
    private val properties = JwtProperties("monomer-auth", "monomer-internal", 900, 604800, "", "")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val token = JwtIssuer(keySource, properties, clock).issueBrowserSession(userId, sessionId).serialized
    private val session = Session(
        sessionId, userId, LoginIdentityId.generate(), AuthenticationProtocol.OIDC,
        "builtin/google", "https://accounts.google.com", "developer@example.com", true,
        EmailEvidence.AUTHORITATIVE, null, null, emptyList(), null,
        now.minusSeconds(60), now.minusSeconds(60), now.plusSeconds(3600), null, null,
    )

    @ParameterizedTest
    @EnumSource(DeveloperAccessPolicy.Mode::class)
    fun `current signed session reaches protected and public HTTP handlers`(mode: DeveloperAccessPolicy.Mode) {
        every { sessionRepository.findById(sessionId) } returns session
        val client = client(mode)
        for (path in browserPaths) {
            client.get().uri(path).headers { it.setBearerAuth(token) }.exchange().expectStatus().isOk
        }
        assertThat(handlerCalls.get()).isEqualTo(browserPaths.size)
        assertThat(readTransactions.get()).isEqualTo(browserPaths.size)
    }

    @ParameterizedTest
    @EnumSource(DeveloperAccessPolicy.Mode::class)
    fun `all revocation reasons reject still signed browser JWTs`(mode: DeveloperAccessPolicy.Mode) {
        val client = client(mode)
        for (reason in SessionRevocationReason.entries) {
            every { sessionRepository.findById(sessionId) } returns session.copy(revokedAt = now, revocationReason = reason)
            assertRejected(client)
        }
    }

    @ParameterizedTest
    @EnumSource(DeveloperAccessPolicy.Mode::class)
    fun `expired missing and differently bound sessions reject still signed browser JWTs`(mode: DeveloperAccessPolicy.Mode) {
        val client = client(mode)
        for (storedSession in listOf(null, session.copy(absoluteExpiresAt = now), session.copy(userId = UserId.generate()))) {
            every { sessionRepository.findById(sessionId) } returns storedSession
            assertRejected(client)
        }
    }

    @ParameterizedTest
    @EnumSource(DeveloperAccessPolicy.Mode::class)
    fun `current developer policy is applied after session validation`(mode: DeveloperAccessPolicy.Mode) {
        every { sessionRepository.findById(sessionId) } returns session.copy(assertedEmail = "other@example.com")
        val client = client(mode)
        for (path in browserPaths) {
            client.get().uri(path).headers { it.setBearerAuth(token) }.exchange()
                .expectStatus().isEqualTo(if (mode == DeveloperAccessPolicy.Mode.OPEN) 200 else 401)
        }
    }

    @ParameterizedTest
    @EnumSource(DeveloperAccessPolicy.Mode::class)
    fun `storage failures never reach the application handler`(mode: DeveloperAccessPolicy.Mode) {
        every { sessionRepository.findById(sessionId) } throws IllegalStateException("Session storage unavailable")
        val client = client(mode)
        for (path in browserPaths) {
            client.get().uri(path).headers { it.setBearerAuth(token) }.exchange().expectStatus().is5xxServerError
        }
        assertThat(handlerCalls.get()).isZero()
    }

    @ParameterizedTest
    @EnumSource(DeveloperAccessPolicy.Mode::class)
    fun `invalid JWTs are rejected without storage access`(mode: DeveloperAccessPolicy.Mode) {
        val expiredToken = JwtIssuer(keySource, properties, Clock.fixed(now.minusSeconds(1000), ZoneOffset.UTC))
            .issueBrowserSession(userId, sessionId).serialized
        val client = client(mode)
        for (invalidToken in listOf("invalid", expiredToken)) {
            for (path in browserPaths) {
                client.get().uri(path).headers { it.setBearerAuth(invalidToken) }.exchange().expectStatus().isUnauthorized
            }
        }
        verify(exactly = 0) { sessionRepository.findById(any()) }
        assertThat(handlerCalls.get()).isZero()
    }

    @ParameterizedTest
    @EnumSource(DeveloperAccessPolicy.Mode::class)
    fun `anonymous public pages and independent opaque auth routes retain their ingress`(mode: DeveloperAccessPolicy.Mode) {
        val client = client(mode)
        client.get().uri(publicPath).exchange().expectStatus().isOk
        client.get().uri("/v2/graphql").exchange().expectStatus().isUnauthorized
        for (path in listOf("/v2/agent/list", "/ops/v1/status", "/mcp")) {
            client.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer opaque-token")
                .exchange().expectStatus().isOk
        }
        verify(exactly = 0) { sessionRepository.findById(any()) }
    }

    private fun assertRejected(client: WebTestClient) {
        for (path in browserPaths) {
            val response = client.get().uri(path).headers { it.setBearerAuth(token) }.exchange()
                .expectStatus().isUnauthorized
            if (path == publicPath) response.expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
        }
        assertThat(handlerCalls.get()).isZero()
    }

    private fun client(mode: DeveloperAccessPolicy.Mode): WebTestClient {
        val transactions = object : TransactionRunner {
            override suspend fun <T> run(action: () -> T): T = error("Authentication must not write")
            override suspend fun <T> readOnly(action: () -> T): T {
                readTransactions.incrementAndGet()
                return action()
            }
        }
        val filter = JwtAuthenticationWebFilter(
            JwtVerifier(keySource, properties, clock),
            DeveloperAccessPolicy(mode, setOf("developer@example.com")), sessionRepository, transactions,
        )
        val chain = SecurityConfiguration("https://app.monomer.test").buildSecurityWebFilterChain(
            ServerHttpSecurity.http(), filter, mockk(), mockk(),
            StaticListableBeanFactory().getBeanProvider(ReactiveClientRegistrationRepository::class.java),
        )
        return WebTestClient.bindToWebHandler { exchange ->
            handlerCalls.incrementAndGet()
            exchange.response.setComplete()
        }.webFilter(WebFilterChainProxy(chain)).build()
    }

    private companion object {
        const val publicPath = "/v2/public/workspaces/workspace/pages/page/content"
        val browserPaths = listOf("/v2/graphql", "/v2/workspaces/workspace/pages/tree", publicPath)
    }
}
