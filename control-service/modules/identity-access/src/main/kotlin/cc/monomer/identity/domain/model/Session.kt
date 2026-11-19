package cc.monomer.identity.domain.model

import cc.monomer.identity.domain.id.LoginIdentityId
import cc.monomer.identity.domain.id.RefreshTokenId
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import java.time.Instant

enum class SessionRevocationReason(val databaseValue: String) {
    LOGOUT("logout"),
    REFRESH_REUSE("refresh_reuse"),
    USER_WIDE("user_wide"),
    ;

    companion object {
        fun fromDatabaseValue(value: String): SessionRevocationReason = entries.first { it.databaseValue == value }
    }
}

data class Session(
    val id: SessionId,
    val userId: UserId,
    val loginIdentityId: LoginIdentityId,
    val protocol: AuthenticationProtocol,
    val authenticationSourceRef: String,
    val authenticationIssuer: String,
    val assertedEmail: String,
    val assertedEmailVerified: Boolean,
    val emailEvidence: EmailEvidence,
    val upstreamAuthenticatedAt: Instant?,
    val authenticationContext: String?,
    val authenticationMethods: List<String>,
    val upstreamSessionId: String?,
    val createdAt: Instant,
    val lastRefreshedAt: Instant,
    val absoluteExpiresAt: Instant,
    val revokedAt: Instant?,
    val revocationReason: SessionRevocationReason?,
)

data class RefreshToken(
    val id: RefreshTokenId,
    val sessionId: SessionId,
    val tokenHash: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant?,
)

data class LockedSessionRefreshToken(
    val session: Session,
    val refreshToken: RefreshToken,
) {
    fun sessionCurrentAndRefreshTokenUnexpiredAt(now: Instant): Boolean =
        session.revokedAt == null && session.absoluteExpiresAt.isAfter(now) && refreshToken.expiresAt.isAfter(now)
}

sealed interface UserCredentialProvenance {
    val userId: UserId

    data class BrowserSession(
        override val userId: UserId,
        val sessionId: SessionId,
    ) : UserCredentialProvenance

}
