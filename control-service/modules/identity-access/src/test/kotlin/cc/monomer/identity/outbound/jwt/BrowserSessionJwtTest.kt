package cc.monomer.identity.outbound.jwt

import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.UserCredentialProvenance
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BrowserSessionJwtTest {
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val keySource = JwtKeySource(keyPair.private as RSAPrivateKey, keyPair.public as RSAPublicKey, "test-kid")
    private val properties = JwtProperties("monomer-auth", "monomer-internal", 900, 604800, "", "")
    private val now = Instant.parse("2026-09-05T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val issuer = JwtIssuer(keySource, properties, clock)
    private val verifier = JwtVerifier(keySource, properties, clock)
    private val userId = UserId.generate()
    private val sessionId = SessionId.generate()

    @Test
    fun `Browser JWT preserves issuer audience session and exact expiry`() {
        val token = issuer.issueBrowserSession(userId, sessionId)
        val claims = SignedJWT.parse(token.serialized).jwtClaimsSet
        assertThat(claims.issuer).isEqualTo(properties.issuer)
        assertThat(claims.audience).containsExactly(properties.audience)
        assertThat(claims.getStringClaim("credential_type")).isEqualTo("browser_session")
        assertThat(claims.getStringClaim("sid")).isEqualTo(sessionId.toString())
        assertThat(claims.getStringClaim("scope")).isNull()
        assertThat(claims.getStringClaim("oauth_client_id")).isNull()
        assertThat(token.expiresAt).isEqualTo(now.plusSeconds(900))
        assertThat(verifier.verify(token.serialized)).isEqualTo(
            JwtVerifier.Outcome.Valid(UserCredentialProvenance.BrowserSession(userId, sessionId), token.expiresAt),
        )
    }

    @Test
    fun `validly signed retired Agent delegation JWT is rejected`() {
        val claims = JWTClaimsSet.Builder(browserClaims())
            .claim("credential_type", "agent_oauth")
            .claim("sid", null)
            .claim("scope", "agent.workspace")
            .claim("oauth_client_id", "retired-client")
            .build()
        assertThat(verifier.verify(sign(claims))).isEqualTo(JwtVerifier.Outcome.Invalid("credential-type"))
    }

    @Test
    fun `Browser JWT rejects wrong issuer audience missing session and expiration`() {
        val cases = listOf(
            JWTClaimsSet.Builder(browserClaims()).issuer("other-issuer").build() to "issuer",
            JWTClaimsSet.Builder(browserClaims()).audience("other-audience").build() to "audience",
            JWTClaimsSet.Builder(browserClaims()).claim("sid", null).build() to "missing-sid",
            JWTClaimsSet.Builder(browserClaims()).expirationTime(Date.from(now)).build() to "expired",
        )
        cases.forEach { (claims, reason) ->
            assertThat(verifier.verify(sign(claims))).isEqualTo(JwtVerifier.Outcome.Invalid(reason))
        }
    }

    private fun browserClaims(): JWTClaimsSet = SignedJWT.parse(issuer.issueBrowserSession(userId, sessionId).serialized).jwtClaimsSet

    private fun sign(claims: JWTClaimsSet): String = SignedJWT(
        JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(keySource.kid).build(),
        claims,
    ).apply { sign(RSASSASigner(keySource.privateKey)) }.serialize()
}
