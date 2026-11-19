package cc.monomer.identity.domain.model

import cc.monomer.identity.application.session.RefreshSessionUseCase
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse

class IssuedTokenRedactionTest {
    @Test
    fun `token holders never print the token values`() {
        val accessToken = IssuedToken("serialized-access-token", Instant.parse("2026-10-04T00:00:00Z"))
        val refreshToken = GeneratedRefreshToken("raw-refresh-token", "refresh-hash")
        val rotated = RefreshSessionUseCase.Result.Rotated(accessToken, "rotated-raw-refresh-token")

        val jwt = cc.monomer.identity.outbound.jwt.JwtProperties("issuer", "audience", 900, 86_400, "jwt-private-key-pem", "")
        assertFalse("jwt-private-key-pem" in jwt.toString())
        listOf(accessToken.toString(), refreshToken.toString(), rotated.toString()).forEach { printed ->
            listOf("serialized-access-token", "raw-refresh-token", "rotated-raw-refresh-token").forEach { secret ->
                assertFalse(secret in printed, printed)
            }
        }
    }
}
