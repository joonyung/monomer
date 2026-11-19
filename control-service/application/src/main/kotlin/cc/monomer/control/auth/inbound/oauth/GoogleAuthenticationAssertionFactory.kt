package cc.monomer.control.auth.inbound.oauth

import cc.monomer.identity.domain.model.AuthenticationAssertion
import cc.monomer.identity.domain.model.AuthenticationProtocol
import cc.monomer.identity.domain.model.EmailEvidence
import cc.monomer.identity.domain.model.LoginIdentityKey
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component

@Component
class GoogleAuthenticationAssertionFactory {
    fun create(oidcUser: OidcUser): AuthenticationAssertion {
        val claims = oidcUser.claims
        val canonicalIssuer = oidcUser.idToken.issuer?.toExternalForm()?.takeIf(String::isNotBlank)
            ?: throw OAuthProviderClaimException("iss")
        if (canonicalIssuer != GOOGLE_ISSUER_HTTPS) {
            throw OAuthProviderIssuerException(canonicalIssuer)
        }
        val issuer = oidcUser.idToken
            .getClaimAsString(GoogleOAuthClientConfiguration.RAW_GOOGLE_ISSUER_CLAIM)
            ?.takeIf(String::isNotBlank)
            ?: canonicalIssuer
        if (issuer != GOOGLE_ISSUER_HOST && issuer != GOOGLE_ISSUER_HTTPS) {
            throw OAuthProviderIssuerException(issuer)
        }
        val subject = oidcUser.idToken.subject?.takeIf(String::isNotBlank)
            ?: throw OAuthProviderClaimException("sub")
        val assertedEmail = (claims["email"] as? String)?.trim()?.takeIf(String::isNotBlank)
            ?: throw OAuthProviderClaimException("email")
        val assertedEmailVerified = claims["email_verified"] == true
        val hostedDomain = (claims["hd"] as? String)?.trim().orEmpty()
        val authoritative = assertedEmail.lowercase().endsWith("@gmail.com") ||
            assertedEmailVerified && hostedDomain.isNotBlank()
        val displayName = (claims["name"] as? String)?.trim()?.takeIf(String::isNotBlank)
            ?: assertedEmail.substringBefore('@')
        val authenticationMethods = when (val amr = claims["amr"]) {
            null -> emptyList()
            is Collection<*> -> amr.map {
                it as? String ?: throw OAuthProviderClaimException("amr")
            }
            else -> throw OAuthProviderClaimException("amr")
        }

        return AuthenticationAssertion(
            identityKey = LoginIdentityKey(
                protocol = AuthenticationProtocol.OIDC,
                namespace = canonicalIssuer,
                subject = subject,
            ),
            authenticationSourceRef = "builtin/google",
            authenticationIssuer = issuer,
            assertedEmail = assertedEmail,
            assertedEmailVerified = assertedEmailVerified,
            emailEvidence = if (authoritative) EmailEvidence.AUTHORITATIVE else EmailEvidence.NON_AUTHORITATIVE,
            displayName = displayName,
            upstreamAuthenticatedAt = oidcUser.idToken.authenticatedAt,
            authenticationContext = claims["acr"] as? String,
            authenticationMethods = authenticationMethods,
            upstreamSessionId = claims["sid"] as? String,
        )
    }

    companion object {
        const val GOOGLE_ISSUER_HOST = "accounts.google.com"
        const val GOOGLE_ISSUER_HTTPS = "https://accounts.google.com"
    }
}

class OAuthProviderClaimException(val claimName: String) :
    RuntimeException("OAuth provider returned no valid $claimName")

class OAuthProviderIssuerException(issuer: String) : RuntimeException("Unexpected OAuth issuer: $issuer")
