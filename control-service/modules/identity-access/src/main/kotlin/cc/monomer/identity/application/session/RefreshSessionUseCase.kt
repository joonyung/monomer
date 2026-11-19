package cc.monomer.identity.application.session

import cc.monomer.identity.domain.model.DeveloperAccessPolicy

import cc.monomer.identity.domain.id.RefreshTokenId
import cc.monomer.identity.domain.model.RefreshToken
import cc.monomer.identity.domain.model.SessionRevocationReason
import cc.monomer.identity.domain.model.IssuedToken
import cc.monomer.identity.domain.port.RefreshTokenGenerator
import cc.monomer.identity.domain.port.SessionRepository
import cc.monomer.identity.domain.port.SessionLifetimePolicy
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.domain.port.UserAccessTokenIssuer
import java.time.Clock

/**
 * Validates a presented opaque refresh token and, when valid, rotates it: the presented
 * token is revoked, a new refresh token is issued, and a fresh access token is minted.
 *
 * Reuse detection: presenting an already-revoked token (a stolen copy of one that was
 * rotated out) revokes that Session and its live refresh-token successors without affecting
 * the user's other Sessions.
 */
class RefreshSessionUseCase(
    private val transactionRunner: TransactionRunner,
    private val sessionRepository: SessionRepository,
    private val refreshTokenGenerator: RefreshTokenGenerator,
    private val userAccessTokenIssuer: UserAccessTokenIssuer,
    private val sessionLifetimePolicy: SessionLifetimePolicy,
    private val clock: Clock,
    private val developerAccessPolicy: DeveloperAccessPolicy,
) {

    sealed interface Result {
        data class Rotated(
            val accessToken: IssuedToken,
            val newRawRefreshToken: String,
        ) : Result {
            override fun toString(): String = "Rotated(accessToken=$accessToken, newRawRefreshToken=[REDACTED])"
        }

        data object Invalid : Result
    }

    suspend fun refresh(rawToken: String): Result {
        val tokenHash = refreshTokenGenerator.hash(rawToken)
        return transactionRunner.run {
            val credential = sessionRepository.lockAndFindByRefreshTokenHash(tokenHash)
                ?: return@run Result.Invalid
            val session = credential.session
            if (!developerAccessPolicy.allows(session)) return@run Result.Invalid
            val stored = credential.refreshToken
            val now = clock.instant()
            if (stored.revokedAt != null) {
                if (session.revokedAt == null) {
                    sessionRepository.revokeSession(session.id, SessionRevocationReason.REFRESH_REUSE, now)
                    sessionRepository.revokeLiveRefreshTokens(session.id, now)
                }
                return@run Result.Invalid
            }
            if (!credential.sessionCurrentAndRefreshTokenUnexpiredAt(now)) return@run Result.Invalid

            val rotated = refreshTokenGenerator.generate()
            val replacement = RefreshToken(
                id = RefreshTokenId.generate(),
                sessionId = session.id,
                tokenHash = rotated.hash,
                issuedAt = now,
                expiresAt = sessionLifetimePolicy.rotatedRefreshTokenExpiry(now, session.absoluteExpiresAt),
                revokedAt = null,
            )
            check(sessionRepository.markRefreshTokenRevoked(stored.id, now)) {
                "locked refresh token was not consumed"
            }
            sessionRepository.insertRefreshToken(replacement)
            sessionRepository.updateLastRefreshedAt(session.id, now)
            val accessToken = userAccessTokenIssuer.issueBrowserSession(session.userId, session.id)
            Result.Rotated(accessToken = accessToken, newRawRefreshToken = rotated.raw)
        }
    }
}
