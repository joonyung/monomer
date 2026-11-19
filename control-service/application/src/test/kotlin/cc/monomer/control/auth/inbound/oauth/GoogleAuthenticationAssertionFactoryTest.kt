package cc.monomer.control.auth.inbound.oauth

import cc.monomer.identity.domain.model.EmailEvidence
import java.net.URL
import java.time.Instant
import java.util.Date
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser

class GoogleAuthenticationAssertionFactoryTest {
    private val factory = GoogleAuthenticationAssertionFactory()

    @Test
    fun `both verified Google issuer forms converge to one identity namespace`() {
        val host = factory.create(oidcUser("accounts.google.com", "alice@gmail.com", false, ""))
        val https = factory.create(oidcUser("https://accounts.google.com", "alice@gmail.com", false, ""))

        assertThat(host.identityKey).isEqualTo(https.identityKey)
        assertThat(host.identityKey.namespace).isEqualTo("https://accounts.google.com")
        assertThat(host.authenticationIssuer).isEqualTo("accounts.google.com")
        assertThat(https.authenticationIssuer).isEqualTo("https://accounts.google.com")
    }

    @Test
    fun `factory reads the URL issuer shape produced by Spring decoder`() {
        val user = oidcUser("https://accounts.google.com", "alice@gmail.com", false, "")

        assertThat(user.idToken.claims[IdTokenClaimNames.ISS]).isInstanceOf(URL::class.java)
        assertThat(factory.create(user).authenticationIssuer).isEqualTo("https://accounts.google.com")
    }

    @Test
    fun `Google authoritative email evidence follows exact gmail or verified hosted-domain truth table`() {
        val cases = listOf(
            Case("alice@gmail.com", false, "", EmailEvidence.AUTHORITATIVE),
            Case("alice@gmail.com", true, "example.com", EmailEvidence.AUTHORITATIVE),
            Case("alice@example.com", true, "example.com", EmailEvidence.AUTHORITATIVE),
            Case("alice@example.com", false, "example.com", EmailEvidence.NON_AUTHORITATIVE),
            Case("alice@example.com", true, "", EmailEvidence.NON_AUTHORITATIVE),
            Case("alice@example.com", false, "", EmailEvidence.NON_AUTHORITATIVE),
        )

        cases.forEach { case ->
            val assertion = factory.create(oidcUser("https://accounts.google.com", case.email, case.verified, case.hd))
            assertThat(assertion.emailEvidence)
                .describedAs("email=%s verified=%s hd=%s", case.email, case.verified, case.hd)
                .isEqualTo(case.expected)
        }
    }

    @Test
    fun `string email verified claim is not treated as authoritative evidence`() {
        val user = oidcUser(
            "https://accounts.google.com",
            "alice@example.com",
            false,
            "example.com",
            mapOf("email_verified" to "true"),
        )

        assertThat(factory.create(user).emailEvidence).isEqualTo(EmailEvidence.NON_AUTHORITATIVE)
    }

    @Test
    fun `factory preserves exact upstream authentication provenance`() {
        val assertion = factory.create(
            oidcUser(
                issuer = "https://accounts.google.com",
                email = "Alice@Example.com",
                verified = true,
                hd = "example.com",
                extraClaims = mapOf(
                    "auth_time" to Instant.parse("2026-07-11T02:59:00Z"),
                    "acr" to "urn:example:mfa",
                    "amr" to listOf("pwd", "otp"),
                    "sid" to "upstream-session",
                ),
            ),
        )

        assertThat(assertion.assertedEmail).isEqualTo("Alice@Example.com")
        assertThat(assertion.upstreamAuthenticatedAt).isEqualTo(Instant.parse("2026-07-11T02:59:00Z"))
        assertThat(assertion.authenticationContext).isEqualTo("urn:example:mfa")
        assertThat(assertion.authenticationMethods).containsExactly("pwd", "otp")
        assertThat(assertion.upstreamSessionId).isEqualTo("upstream-session")
    }

    @Test
    fun `factory rejects unsupported issuer and missing email claim`() {
        assertThatThrownBy {
            factory.create(oidcUser("https://issuer.example", "alice@example.com", true, "example.com"))
        }.isInstanceOf(OAuthProviderIssuerException::class.java)
        assertThatThrownBy {
            factory.create(oidcUser("https://accounts.google.com", "", true, "example.com"))
        }.isInstanceOf(OAuthProviderClaimException::class.java)

        assertThatThrownBy {
            factory.create(
                oidcUser(
                    "https://accounts.google.com",
                    "alice@gmail.com",
                    true,
                    "",
                    mapOf(IdTokenClaimNames.SUB to ""),
                ),
            )
        }
            .isInstanceOf(OAuthProviderClaimException::class.java)
    }

    private fun oidcUser(
        issuer: String,
        email: String,
        verified: Boolean,
        hd: String,
        extraClaims: Map<String, Any> = emptyMap(),
    ): DefaultOidcUser {
        val issuedAt = Instant.parse("2026-07-11T02:58:00Z")
        val expiresAt = Instant.parse("2026-07-11T03:58:00Z")
        val claims = mutableMapOf<String, Any>(
            IdTokenClaimNames.ISS to issuer,
            IdTokenClaimNames.SUB to "google-sub",
            IdTokenClaimNames.IAT to Date.from(issuedAt),
            IdTokenClaimNames.EXP to Date.from(expiresAt),
            "email" to email,
            "email_verified" to verified,
            "name" to "Alice",
        )
        if (hd.isNotBlank()) claims["hd"] = hd
        claims.putAll(extraClaims)
        val convertedClaims = GoogleOAuthClientConfiguration.googleIdTokenClaimConverter().convert(claims)
        val idToken = OidcIdToken("id-token", issuedAt, expiresAt, convertedClaims)
        return DefaultOidcUser(emptyList(), idToken, IdTokenClaimNames.SUB)
    }

    private data class Case(
        val email: String,
        val verified: Boolean,
        val hd: String,
        val expected: EmailEvidence,
    )
}
