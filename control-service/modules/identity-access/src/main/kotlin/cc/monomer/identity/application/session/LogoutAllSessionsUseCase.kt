package cc.monomer.identity.application.session

import cc.monomer.identity.domain.port.RefreshTokenGenerator
import cc.monomer.identity.domain.port.SessionRepository
import cc.monomer.identity.domain.port.TransactionRunner
import java.time.Clock

/**
 * Revokes every browser Session (refresh family) of the user who presents a refresh token
 * from a current Session. A token rotated out by a concurrent refresh still identifies its
 * live Session. Agent OAuth grants are a separate credential lifecycle and are not touched.
 */
class LogoutAllSessionsUseCase(
    private val transactionRunner: TransactionRunner,
    private val sessionRepository: SessionRepository,
    private val refreshTokenGenerator: RefreshTokenGenerator,
    private val clock: Clock,
) {
    sealed interface Result {
        data object Revoked : Result
        data object Unauthenticated : Result
    }

    suspend fun logoutAll(rawToken: String): Result {
        val tokenHash = refreshTokenGenerator.hash(rawToken)
        return transactionRunner.run {
            val credential = sessionRepository.lockAndFindByRefreshTokenHash(tokenHash)
                ?: return@run Result.Unauthenticated
            val now = clock.instant()
            if (!credential.sessionCurrentAndRefreshTokenUnexpiredAt(now)) return@run Result.Unauthenticated
            sessionRepository.revokeAllForUser(credential.session.userId, now)
            Result.Revoked
        }
    }
}
