package cc.monomer.identity.application.session

import cc.monomer.identity.domain.id.RefreshTokenId
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.model.AuthenticationAssertion
import cc.monomer.identity.domain.model.LoginIdentity
import cc.monomer.identity.domain.model.RefreshToken
import cc.monomer.identity.domain.model.Session
import cc.monomer.identity.domain.port.SessionRepository
import cc.monomer.identity.domain.port.RefreshTokenGenerator
import cc.monomer.identity.domain.port.SessionLifetimePolicy
import cc.monomer.identity.domain.port.TransactionRunner
import java.time.Clock

class StartSessionUseCase(
    private val transactionRunner: TransactionRunner,
    private val sessionRepository: SessionRepository,
    private val refreshTokenGenerator: RefreshTokenGenerator,
    private val sessionLifetimePolicy: SessionLifetimePolicy,
    private val clock: Clock,
) {
    data class Started(
        val sessionId: SessionId,
        val rawRefreshToken: String,
    )

    suspend fun start(loginIdentity: LoginIdentity, assertion: AuthenticationAssertion): Started {
        require(loginIdentity.key == assertion.identityKey) { "session assertion does not match login identity" }
        val now = clock.instant()
        val absoluteExpiresAt = sessionLifetimePolicy.absoluteExpiry(now)
        val session = Session(
            id = SessionId.generate(),
            userId = loginIdentity.userId,
            loginIdentityId = loginIdentity.id,
            protocol = assertion.identityKey.protocol,
            authenticationSourceRef = assertion.authenticationSourceRef,
            authenticationIssuer = assertion.authenticationIssuer,
            assertedEmail = assertion.assertedEmail,
            assertedEmailVerified = assertion.assertedEmailVerified,
            emailEvidence = assertion.emailEvidence,
            upstreamAuthenticatedAt = assertion.upstreamAuthenticatedAt,
            authenticationContext = assertion.authenticationContext,
            authenticationMethods = assertion.authenticationMethods,
            upstreamSessionId = assertion.upstreamSessionId,
            createdAt = now,
            lastRefreshedAt = now,
            absoluteExpiresAt = absoluteExpiresAt,
            revokedAt = null,
            revocationReason = null,
        )
        val generatedRefreshToken = refreshTokenGenerator.generate()
        val refreshToken = RefreshToken(
            id = RefreshTokenId.generate(),
            sessionId = session.id,
            tokenHash = generatedRefreshToken.hash,
            issuedAt = now,
            expiresAt = absoluteExpiresAt,
            revokedAt = null,
        )
        transactionRunner.run {
            sessionRepository.insert(session)
            sessionRepository.insertRefreshToken(refreshToken)
        }
        return Started(sessionId = session.id, rawRefreshToken = generatedRefreshToken.raw)
    }
}
