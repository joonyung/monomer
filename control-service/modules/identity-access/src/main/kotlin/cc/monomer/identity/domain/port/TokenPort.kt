package cc.monomer.identity.domain.port

import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.GeneratedRefreshToken
import cc.monomer.identity.domain.model.IssuedToken
import cc.monomer.identity.domain.model.UserCredentialProvenance
import java.time.Instant

interface UserAccessTokenIssuer {
    fun issueBrowserSession(userId: UserId, sessionId: SessionId): IssuedToken
}

interface CurrentUserCredentialVerifier {
    fun verifyCurrentCredential(serializedToken: String): VerifiedCurrentUserCredential?
}

data class VerifiedCurrentUserCredential(
    val provenance: UserCredentialProvenance,
    val expiresAt: Instant,
)

interface RefreshTokenGenerator {
    fun generate(): GeneratedRefreshToken
    fun hash(rawToken: String): String
}

interface SessionLifetimePolicy {
    fun absoluteExpiry(startedAt: Instant): Instant
    fun rotatedRefreshTokenExpiry(rotatedAt: Instant, absoluteSessionExpiry: Instant): Instant
}
