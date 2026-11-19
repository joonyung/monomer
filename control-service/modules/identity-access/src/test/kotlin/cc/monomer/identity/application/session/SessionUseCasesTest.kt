package cc.monomer.identity.application.session

import cc.monomer.identity.domain.model.DeveloperAccessPolicy

import cc.monomer.identity.domain.id.LoginIdentityId
import cc.monomer.identity.domain.id.RefreshTokenId
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.AuthenticationAssertion
import cc.monomer.identity.domain.model.AuthenticationProtocol
import cc.monomer.identity.domain.model.EmailEvidence
import cc.monomer.identity.domain.model.GeneratedRefreshToken
import cc.monomer.identity.domain.model.IssuedToken
import cc.monomer.identity.domain.model.LockedSessionRefreshToken
import cc.monomer.identity.domain.model.LoginIdentity
import cc.monomer.identity.domain.model.LoginIdentityKey
import cc.monomer.identity.domain.model.LoginIdentityLinkedVia
import cc.monomer.identity.domain.model.RefreshToken
import cc.monomer.identity.domain.model.Session
import cc.monomer.identity.domain.model.SessionRevocationReason
import cc.monomer.identity.domain.port.SessionRepository
import cc.monomer.identity.domain.port.RefreshTokenGenerator
import cc.monomer.identity.domain.port.SessionLifetimePolicy
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.domain.port.UserAccessTokenIssuer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SessionUseCasesTest {
    private val now = Instant.parse("2026-07-11T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val tokenGenerator = TestRefreshTokenGenerator()
    private val lifetimePolicy = FixedSessionLifetimePolicy(604800)
    private val transactionRunner = object : TransactionRunner {
        override suspend fun <T> run(action: () -> T): T = action()
        override suspend fun <T> readOnly(action: () -> T): T = action()
    }

    @Test
    fun `removed developer cannot rotate an existing browser refresh token`() = runTest {
        val repository = InMemorySessionRepository()
        val started = StartSessionUseCase(transactionRunner, repository, tokenGenerator, lifetimePolicy, clock)
            .start(identity(), assertion())
        val issuer = mockk<UserAccessTokenIssuer>()
        every { issuer.issueBrowserSession(any(), any()) } returns IssuedToken("jwt", now.plusSeconds(900))
        fun refreshWith(emails: Set<String>) = RefreshSessionUseCase(transactionRunner, repository,
            tokenGenerator, issuer, lifetimePolicy, clock, DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.RESTRICTED, emails))
        val allowed = refreshWith(setOf("alice@example.com")).refresh(started.rawRefreshToken)
        assertThat(allowed).isInstanceOf(RefreshSessionUseCase.Result.Rotated::class.java)
        val oldToken = (allowed as RefreshSessionUseCase.Result.Rotated).newRawRefreshToken
        val denied = refreshWith(emptySet()).refresh(oldToken)
        assertThat(denied).isEqualTo(RefreshSessionUseCase.Result.Invalid)
        assertThat(repository.refreshTokens).hasSize(2)
        verify(exactly = 1) { issuer.issueBrowserSession(any(), any()) }
    }

    @Test
    fun `start persists exact provenance and initial refresh hash`() = runTest {
        val repository = InMemorySessionRepository()
        val identity = identity()
        val assertion = assertion()
        val useCase = StartSessionUseCase(transactionRunner, repository, tokenGenerator, lifetimePolicy, clock)

        val started = useCase.start(identity, assertion)

        val session = repository.sessions.single()
        assertThat(started.sessionId).isEqualTo(session.id)
        assertThat(session.loginIdentityId).isEqualTo(identity.id)
        assertThat(session.authenticationSourceRef).isEqualTo("builtin/google")
        assertThat(session.authenticationIssuer).isEqualTo("accounts.google.com")
        assertThat(session.assertedEmail).isEqualTo("Alice@Example.com")
        assertThat(session.upstreamAuthenticatedAt).isEqualTo(Instant.parse("2026-07-11T02:59:00Z"))
        assertThat(session.authenticationContext).isEqualTo("urn:mfa")
        assertThat(session.authenticationMethods).containsExactly("pwd", "otp")
        assertThat(session.upstreamSessionId).isEqualTo("google-session")
        assertThat(repository.refreshTokens.single().tokenHash).isEqualTo(tokenGenerator.hash(started.rawRefreshToken))
    }

    @Test
    fun `refresh rotates within same Session and keeps fixed absolute expiry`() = runTest {
        val repository = InMemorySessionRepository()
        val started = StartSessionUseCase(transactionRunner, repository, tokenGenerator, lifetimePolicy, clock)
            .start(identity(), assertion())
        val userAccessTokenIssuer = mockk<UserAccessTokenIssuer>()
        every { userAccessTokenIssuer.issueBrowserSession(any(), started.sessionId) } returns
            IssuedToken("browser-jwt", now.plusSeconds(900))

        val result = RefreshSessionUseCase(
            transactionRunner,
            repository,
            tokenGenerator,
            userAccessTokenIssuer,
            lifetimePolicy,
            clock, developerAccessPolicy = DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.OPEN, emptySet())).refresh(started.rawRefreshToken)

        assertThat(result).isInstanceOf(RefreshSessionUseCase.Result.Rotated::class.java)
        assertThat(repository.refreshTokens).hasSize(2)
        assertThat(repository.refreshTokens.last().sessionId).isEqualTo(started.sessionId)
        assertThat(repository.refreshTokens.last().expiresAt).isEqualTo(repository.sessions.single().absoluteExpiresAt)
        verify { userAccessTokenIssuer.issueBrowserSession(repository.sessions.single().userId, started.sessionId) }
    }

    @Test
    fun `refresh reuse revokes only its Session and leaves second Session live`() = runTest {
        val repository = InMemorySessionRepository()
        val starter = StartSessionUseCase(transactionRunner, repository, tokenGenerator, lifetimePolicy, clock)
        val identity = identity()
        val first = starter.start(identity, assertion())
        val second = starter.start(identity, assertion())
        val userAccessTokenIssuer = mockk<UserAccessTokenIssuer>()
        every { userAccessTokenIssuer.issueBrowserSession(any(), any()) } returns
            IssuedToken("browser-jwt", now.plusSeconds(900))
        val refresh = RefreshSessionUseCase(
            transactionRunner,
            repository,
            tokenGenerator,
            userAccessTokenIssuer,
            lifetimePolicy,
            clock, developerAccessPolicy = DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.OPEN, emptySet()))
        refresh.refresh(first.rawRefreshToken)

        assertThat(refresh.refresh(first.rawRefreshToken)).isEqualTo(RefreshSessionUseCase.Result.Invalid)
        assertThat(repository.sessions.first { it.id == first.sessionId }.revocationReason)
            .isEqualTo(SessionRevocationReason.REFRESH_REUSE)
        assertThat(repository.sessions.first { it.id == second.sessionId }.revokedAt).isNull()
        assertThat(refresh.refresh(second.rawRefreshToken)).isInstanceOf(RefreshSessionUseCase.Result.Rotated::class.java)
    }

    @Test
    fun `logout closes only presented Session`() = runTest {
        val repository = InMemorySessionRepository()
        val starter = StartSessionUseCase(transactionRunner, repository, tokenGenerator, lifetimePolicy, clock)
        val identity = identity()
        val first = starter.start(identity, assertion())
        val second = starter.start(identity, assertion())

        LogoutUseCase(transactionRunner, repository, tokenGenerator, clock).logout(first.rawRefreshToken)

        assertThat(repository.sessions.first { it.id == first.sessionId }.revocationReason)
            .isEqualTo(SessionRevocationReason.LOGOUT)
        assertThat(repository.sessions.first { it.id == second.sessionId }.revokedAt).isNull()
    }

    @Test
    fun `logout all revokes every Session of the presenting user and leaves other users live`() = runTest {
        val repository = InMemorySessionRepository()
        val starter = StartSessionUseCase(transactionRunner, repository, tokenGenerator, lifetimePolicy, clock)
        val identity = identity()
        val first = starter.start(identity, assertion())
        val second = starter.start(identity, assertion())
        val other = starter.start(identity(), assertion())
        val logoutAll = LogoutAllSessionsUseCase(transactionRunner, repository, tokenGenerator, clock)

        assertThat(logoutAll.logoutAll(first.rawRefreshToken)).isEqualTo(LogoutAllSessionsUseCase.Result.Revoked)

        for (sessionId in listOf(first.sessionId, second.sessionId)) {
            assertThat(repository.sessions.single { it.id == sessionId }.revocationReason).isEqualTo(SessionRevocationReason.USER_WIDE)
        }
        assertThat(repository.refreshTokens.filter { it.sessionId != other.sessionId }).allMatch { it.revokedAt == now }
        assertThat(repository.sessions.single { it.id == other.sessionId }.revokedAt).isNull()
        assertThat(repository.refreshTokens.single { it.sessionId == other.sessionId }.revokedAt).isNull()
        assertThat(logoutAll.logoutAll(second.rawRefreshToken)).isEqualTo(LogoutAllSessionsUseCase.Result.Unauthenticated)
        assertThat(logoutAll.logoutAll("unknown")).isEqualTo(LogoutAllSessionsUseCase.Result.Unauthenticated)
        assertThat(repository.sessions.single { it.id == other.sessionId }.revokedAt).isNull()
    }

    @Test
    fun `logout all accepts a token rotated out of a live Session`() = runTest {
        val repository = InMemorySessionRepository()
        val started = StartSessionUseCase(transactionRunner, repository, tokenGenerator, lifetimePolicy, clock)
            .start(identity(), assertion())
        val presented = repository.refreshTokens.single()
        repository.markRefreshTokenRevoked(presented.id, now)

        assertThat(LogoutAllSessionsUseCase(transactionRunner, repository, tokenGenerator, clock).logoutAll(started.rawRefreshToken))
            .isEqualTo(LogoutAllSessionsUseCase.Result.Revoked)
        assertThat(repository.sessions.single().revocationReason).isEqualTo(SessionRevocationReason.USER_WIDE)
    }

    @Test
    fun `session currency requires an unrevoked unexpired Session and an unexpired refresh token at the instant`() = runTest {
        val repository = InMemorySessionRepository()
        val started = StartSessionUseCase(transactionRunner, repository, tokenGenerator, lifetimePolicy, clock).start(identity(), assertion())
        val current = checkNotNull(repository.lockAndFindByRefreshTokenHash(tokenGenerator.hash(started.rawRefreshToken)))

        assertThat(current.sessionCurrentAndRefreshTokenUnexpiredAt(now)).isTrue()
        assertThat(current.copy(refreshToken = current.refreshToken.copy(revokedAt = now)).sessionCurrentAndRefreshTokenUnexpiredAt(now)).isTrue()
        assertThat(current.copy(session = current.session.copy(revokedAt = now)).sessionCurrentAndRefreshTokenUnexpiredAt(now)).isFalse()
        assertThat(current.sessionCurrentAndRefreshTokenUnexpiredAt(current.session.absoluteExpiresAt)).isFalse()
        assertThat(current.copy(refreshToken = current.refreshToken.copy(expiresAt = now)).sessionCurrentAndRefreshTokenUnexpiredAt(now)).isFalse()
    }

    private fun identity(): LoginIdentity {
        val userId = UserId.generate()
        return LoginIdentity(
            id = LoginIdentityId.generate(),
            userId = userId,
            key = assertion().identityKey,
            linkedVia = LoginIdentityLinkedVia.SIGNUP,
            linkedAt = now,
        )
    }

    private fun assertion() = AuthenticationAssertion(
        identityKey = LoginIdentityKey(AuthenticationProtocol.OIDC, "https://accounts.google.com", "google-sub"),
        authenticationSourceRef = "builtin/google",
        authenticationIssuer = "accounts.google.com",
        assertedEmail = "Alice@Example.com",
        assertedEmailVerified = true,
        emailEvidence = EmailEvidence.AUTHORITATIVE,
        displayName = "Alice",
        upstreamAuthenticatedAt = Instant.parse("2026-07-11T02:59:00Z"),
        authenticationContext = "urn:mfa",
        authenticationMethods = listOf("pwd", "otp"),
        upstreamSessionId = "google-session",
    )
}

private class TestRefreshTokenGenerator : RefreshTokenGenerator {
    private var sequence = 0

    override fun generate(): GeneratedRefreshToken {
        val rawToken = "test-refresh-${++sequence}"
        return GeneratedRefreshToken(rawToken, hash(rawToken))
    }

    override fun hash(rawToken: String): String = "hash:$rawToken"
}

private class FixedSessionLifetimePolicy(private val lifetimeSeconds: Long) : SessionLifetimePolicy {
    override fun absoluteExpiry(startedAt: Instant): Instant = startedAt.plusSeconds(lifetimeSeconds)

    override fun rotatedRefreshTokenExpiry(rotatedAt: Instant, absoluteSessionExpiry: Instant): Instant =
        minOf(rotatedAt.plusSeconds(lifetimeSeconds), absoluteSessionExpiry)
}

private class InMemorySessionRepository : SessionRepository {
    override fun findById(sessionId: SessionId): Session? = sessions.firstOrNull { it.id == sessionId }
    override fun findLatestForUser(userId: UserId): List<Session> = sessions.filter { it.userId == userId }.groupBy { it.loginIdentityId }.values.map { records -> records.maxBy { it.createdAt } }

    val sessions = mutableListOf<Session>()
    val refreshTokens = mutableListOf<RefreshToken>()

    override fun insert(session: Session) {
        sessions += session
    }

    override fun insertRefreshToken(refreshToken: RefreshToken) {
        refreshTokens += refreshToken
    }

    override fun lockAndFindByRefreshTokenHash(tokenHash: String): LockedSessionRefreshToken? {
        val token = refreshTokens.firstOrNull { it.tokenHash == tokenHash } ?: return null
        val session = sessions.first { it.id == token.sessionId }
        return LockedSessionRefreshToken(session, token)
    }

    override fun markRefreshTokenRevoked(refreshTokenId: RefreshTokenId, revokedAt: Instant): Boolean {
        val index = refreshTokens.indexOfFirst { it.id == refreshTokenId && it.revokedAt == null }
        if (index < 0) return false
        refreshTokens[index] = refreshTokens[index].copy(revokedAt = revokedAt)
        return true
    }

    override fun updateLastRefreshedAt(sessionId: SessionId, refreshedAt: Instant) {
        val index = sessions.indexOfFirst { it.id == sessionId }
        sessions[index] = sessions[index].copy(lastRefreshedAt = refreshedAt)
    }

    override fun revokeSession(sessionId: SessionId, reason: SessionRevocationReason, revokedAt: Instant) {
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (sessions[index].revokedAt == null) {
            sessions[index] = sessions[index].copy(revokedAt = revokedAt, revocationReason = reason)
        }
    }

    override fun revokeLiveRefreshTokens(sessionId: SessionId, revokedAt: Instant) {
        refreshTokens.indices.forEach { index ->
            if (refreshTokens[index].sessionId == sessionId && refreshTokens[index].revokedAt == null) {
                refreshTokens[index] = refreshTokens[index].copy(revokedAt = revokedAt)
            }
        }
    }

    override fun revokeAllForUser(userId: UserId, revokedAt: Instant) {
        sessions.filter { it.userId == userId }.forEach {
            revokeSession(it.id, SessionRevocationReason.USER_WIDE, revokedAt)
            revokeLiveRefreshTokens(it.id, revokedAt)
        }
    }
}
