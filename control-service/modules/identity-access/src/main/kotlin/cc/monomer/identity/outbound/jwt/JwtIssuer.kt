package cc.monomer.identity.outbound.jwt

import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.IssuedToken
import cc.monomer.identity.domain.port.UserAccessTokenIssuer
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

class JwtIssuer(
    private val keySource: JwtKeySource,
    private val properties: JwtProperties,
    private val clock: Clock,
) : UserAccessTokenIssuer {
    private val signer = RSASSASigner(keySource.privateKey)

    override fun issueBrowserSession(userId: UserId, sessionId: SessionId): IssuedToken {
        val (now, expiresAt) = issuanceWindow(properties.accessTokenTtlSeconds)
        val claimsBuilder = JWTClaimsSet.Builder()
            .subject(userId.value.toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(expiresAt))
            .issuer(properties.issuer)
            .audience(properties.audience)
            .claim("credential_type", "browser_session")
            .claim("sid", sessionId.toString())
        return sign(claimsBuilder.build(), expiresAt, JOSEObjectType.JWT)
    }

    private fun issuanceWindow(ttlSeconds: Long): Pair<Instant, Instant> {
        // JWT exp/iat are seconds-precision per RFC 7519; truncate so the returned
        // [IssuedToken.expiresAt] matches the signed claims exactly.
        val now = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        return now to now.plusSeconds(ttlSeconds)
    }

    private fun sign(
        claims: JWTClaimsSet,
        expiresAt: Instant,
        type: JOSEObjectType,
    ): IssuedToken {
        val header = JWSHeader.Builder(JWSAlgorithm.RS256)
            .type(type)
            .keyID(keySource.kid)
            .build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(signer)
        return IssuedToken(serialized = jwt.serialize(), expiresAt = expiresAt)
    }

}
