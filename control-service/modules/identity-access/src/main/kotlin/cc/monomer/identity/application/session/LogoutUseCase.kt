package cc.monomer.identity.application.session

import cc.monomer.identity.domain.model.SessionRevocationReason
import cc.monomer.identity.domain.port.RefreshTokenGenerator
import cc.monomer.identity.domain.port.SessionRepository
import cc.monomer.identity.domain.port.TransactionRunner
import java.time.Clock

/**
 * Revokes the presented refresh token. An unknown token is a no-op. Presenting a token that
 * was concurrently rotated revokes that Session's live successors so logout still closes the
 * same Session family without affecting the user's other Sessions.
 */
class LogoutUseCase(
    private val transactionRunner: TransactionRunner,
    private val sessionRepository: SessionRepository,
    private val refreshTokenGenerator: RefreshTokenGenerator,
    private val clock: Clock,
) {

    suspend fun logout(rawToken: String) {
        val tokenHash = refreshTokenGenerator.hash(rawToken)
        transactionRunner.run {
            val credential = sessionRepository.lockAndFindByRefreshTokenHash(tokenHash) ?: return@run
            val now = clock.instant()
            sessionRepository.revokeSession(credential.session.id, SessionRevocationReason.LOGOUT, now)
            sessionRepository.revokeLiveRefreshTokens(credential.session.id, now)
        }
    }
}
