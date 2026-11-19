package cc.monomer.identity.outbound.jwt

import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.UserCredentialProvenance
import cc.monomer.identity.domain.port.CurrentUserCredentialVerifier
import cc.monomer.identity.domain.port.VerifiedCurrentUserCredential
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import java.time.Clock
import java.time.Instant
import kotlin.uuid.Uuid

class JwtVerifier(
    private val keySource: JwtKeySource,
    private val properties: JwtProperties,
    private val clock: Clock,
) : CurrentUserCredentialVerifier {
    private val verifier = RSASSAVerifier(keySource.publicKey)

    sealed interface Outcome {
        data class Valid(val credential: UserCredentialProvenance, val expiresAt: Instant) : Outcome
        data class Invalid(val reason: String) : Outcome
    }

    override fun verifyCurrentCredential(serializedToken: String): VerifiedCurrentUserCredential? =
        when (val outcome = verify(serializedToken)) {
            is Outcome.Valid -> VerifiedCurrentUserCredential(outcome.credential, outcome.expiresAt)
            is Outcome.Invalid -> null
        }

    fun verify(token: String): Outcome = try {
        val jwt = SignedJWT.parse(token)
        when {
            jwt.header.algorithm != JWSAlgorithm.RS256 -> Outcome.Invalid("algorithm")
            jwt.header.type != JOSEObjectType.JWT -> Outcome.Invalid("type")
            jwt.header.keyID != keySource.kid -> Outcome.Invalid("kid")
            !jwt.verify(verifier) -> Outcome.Invalid("signature")
            jwt.jwtClaimsSet.issuer != properties.issuer -> Outcome.Invalid("issuer")
            jwt.jwtClaimsSet.audience != listOf(properties.audience) -> Outcome.Invalid("audience")
            else -> {
                val exp = jwt.jwtClaimsSet.expirationTime?.toInstant()
                    ?: return Outcome.Invalid("missing-exp")
                if (!clock.instant().isBefore(exp)) {
                    Outcome.Invalid("expired")
                } else {
                    val sub = jwt.jwtClaimsSet.subject
                        ?: return Outcome.Invalid("missing-sub")
                    val userId = UserId(Uuid.parse(sub))
                    val credential = when (jwt.jwtClaimsSet.getStringClaim("credential_type")) {
                        "browser_session" -> {
                            val sid = jwt.jwtClaimsSet.getStringClaim("sid")
                                ?: return Outcome.Invalid("missing-sid")
                            UserCredentialProvenance.BrowserSession(userId, SessionId(Uuid.parse(sid)))
                        }
                        null -> return Outcome.Invalid("missing-credential-type")
                        else -> return Outcome.Invalid("credential-type")
                    }
                    Outcome.Valid(credential, exp)
                }
            }
        }
    } catch (ex: Exception) {
        Outcome.Invalid("parse")
    }
}
