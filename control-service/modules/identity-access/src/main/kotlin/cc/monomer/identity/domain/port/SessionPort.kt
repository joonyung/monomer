package cc.monomer.identity.domain.port

import cc.monomer.identity.domain.id.RefreshTokenId
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.LockedSessionRefreshToken
import cc.monomer.identity.domain.model.RefreshToken
import cc.monomer.identity.domain.model.Session
import cc.monomer.identity.domain.model.SessionRevocationReason
import java.time.Instant

interface SessionRepository {
    fun findById(sessionId: SessionId): Session?
    fun findLatestForUser(userId: UserId): List<Session>
    fun insert(session: Session)
    fun insertRefreshToken(refreshToken: RefreshToken)
    fun lockAndFindByRefreshTokenHash(tokenHash: String): LockedSessionRefreshToken?
    fun markRefreshTokenRevoked(refreshTokenId: RefreshTokenId, revokedAt: Instant): Boolean
    fun updateLastRefreshedAt(sessionId: SessionId, refreshedAt: Instant)
    fun revokeSession(sessionId: SessionId, reason: SessionRevocationReason, revokedAt: Instant)
    fun revokeLiveRefreshTokens(sessionId: SessionId, revokedAt: Instant)
    fun revokeAllForUser(userId: UserId, revokedAt: Instant)
}
