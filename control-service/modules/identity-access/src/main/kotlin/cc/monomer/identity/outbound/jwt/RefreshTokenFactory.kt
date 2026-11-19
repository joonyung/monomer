package cc.monomer.identity.outbound.jwt

import cc.monomer.identity.application.OpaqueCredentials
import cc.monomer.identity.domain.model.GeneratedRefreshToken
import cc.monomer.identity.domain.port.RefreshTokenGenerator
import org.springframework.stereotype.Component

/**
 * Mints opaque refresh tokens. The raw token is returned to the caller exactly once and never
 * persisted; only its SHA-256 hex hash is stored. Lookups re-hash the presented raw token via [hash].
 */
@Component
class RefreshTokenFactory : RefreshTokenGenerator {
    override fun generate(): GeneratedRefreshToken {
        val raw = OpaqueCredentials.generate()
        return GeneratedRefreshToken(raw = raw, hash = hash(raw))
    }

    override fun hash(rawToken: String): String = OpaqueCredentials.hash(rawToken)
}
