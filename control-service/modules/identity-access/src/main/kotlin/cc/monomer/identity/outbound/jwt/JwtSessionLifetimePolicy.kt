package cc.monomer.identity.outbound.jwt

import cc.monomer.identity.domain.port.SessionLifetimePolicy
import java.time.Instant

class JwtSessionLifetimePolicy(
    private val properties: JwtProperties,
) : SessionLifetimePolicy {
    override fun absoluteExpiry(startedAt: Instant): Instant =
        startedAt.plusSeconds(properties.refreshTokenTtlSeconds)

    override fun rotatedRefreshTokenExpiry(rotatedAt: Instant, absoluteSessionExpiry: Instant): Instant =
        minOf(rotatedAt.plusSeconds(properties.refreshTokenTtlSeconds), absoluteSessionExpiry)
}
