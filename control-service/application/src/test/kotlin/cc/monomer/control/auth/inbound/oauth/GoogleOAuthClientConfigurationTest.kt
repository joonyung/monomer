package cc.monomer.control.auth.inbound.oauth

import java.net.URI
import java.time.Instant
import java.util.Date
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtValidators

class GoogleOAuthClientConfigurationTest {

    @Test
    fun `google client registration uses configured web client values`() {
        val repository = GoogleOAuthClientConfiguration().googleClientRegistrationRepository(
            GoogleOAuthProperties(
                enabled = true,
                clientId = "client-id.apps.googleusercontent.com",
                clientSecret = "client-secret",
                redirectUri = "http://localhost:10100/login/oauth2/code/google",
                scopes = listOf("openid", "email", "profile"),
            ),
        )

        val registration = repository.findByRegistrationId("google").block()

        assertThat(registration).isNotNull
        assertThat(registration!!.clientId).isEqualTo("client-id.apps.googleusercontent.com")
        assertThat(registration.clientSecret).isEqualTo("client-secret")
        assertThat(registration.redirectUri).isEqualTo("http://localhost:10100/login/oauth2/code/google")
        assertThat(registration.scopes).containsExactlyInAnyOrder("openid", "email", "profile")
    }

    @Test
    fun `enabled google oauth requires client credentials`() {
        assertThatThrownBy {
            GoogleOAuthClientConfiguration().googleClientRegistrationRepository(
                GoogleOAuthProperties(
                    enabled = true,
                    clientId = "",
                    clientSecret = "",
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("MONOMER_CONTROL_GOOGLE_OAUTH_CLIENT_ID")
    }

    @Test
    fun `enabled google oauth requires openid and email scopes`() {
        assertThatThrownBy {
            GoogleOAuthClientConfiguration().googleClientRegistrationRepository(
                GoogleOAuthProperties(
                    enabled = true,
                    clientId = "client-id",
                    clientSecret = "client-secret",
                    scopes = listOf("profile"),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("openid")
    }

    @Test
    fun `claim conversion preserves raw issuer while exposing canonical typed URL`() {
        listOf("accounts.google.com", "https://accounts.google.com").forEach { rawIssuer ->
            val converted = GoogleOAuthClientConfiguration.googleIdTokenClaimConverter()
                .convert(rawClaims(rawIssuer))

            assertThat(converted[IdTokenClaimNames.ISS])
                .isEqualTo(URI("https://accounts.google.com").toURL())
            assertThat(converted[GoogleOAuthClientConfiguration.RAW_GOOGLE_ISSUER_CLAIM]).isEqualTo(rawIssuer)
        }
    }

    @Test
    fun `claim conversion does not coerce malformed email verified strings`() {
        val rawClaims = HashMap(rawClaims("https://accounts.google.com"))
        rawClaims["email_verified"] = "true"

        val converted = GoogleOAuthClientConfiguration.googleIdTokenClaimConverter().convert(rawClaims)

        assertThat(converted["email_verified"]).isEqualTo("true")
    }

    @Test
    fun `default OIDC validation accepts only both Google issuer forms after conversion`() {
        val registration = registration()
        val validator = JwtValidators.createDefaultWithValidators(OidcIdTokenValidator(registration))

        listOf("accounts.google.com", "https://accounts.google.com").forEach { issuer ->
            assertThat(validator.validate(jwt(issuer, registration.clientId)).hasErrors()).isFalse()
        }
        assertThat(validator.validate(jwt("https://issuer.example", registration.clientId)).hasErrors()).isTrue()
    }

    @Test
    fun `custom decoder seam retains default audience and authorized party validation`() {
        val registration = registration()
        val validator = JwtValidators.createDefaultWithValidators(OidcIdTokenValidator(registration))

        assertThat(validator.validate(jwt("https://accounts.google.com", "wrong-client")).hasErrors()).isTrue()
        assertThat(
            validator.validate(
                jwt(
                    issuer = "https://accounts.google.com",
                    audience = registration.clientId,
                    additionalAudience = "another-client",
                    authorizedParty = null,
                ),
            ).hasErrors(),
        ).isTrue()
        assertThat(
            validator.validate(
                jwt(
                    issuer = "https://accounts.google.com",
                    audience = registration.clientId,
                    additionalAudience = "another-client",
                    authorizedParty = "wrong-client",
                ),
            ).hasErrors(),
        ).isTrue()
    }

    private fun registration(): ClientRegistration =
        GoogleOAuthClientConfiguration().googleClientRegistrationRepository(
            GoogleOAuthProperties(
                enabled = true,
                clientId = "client-id.apps.googleusercontent.com",
                clientSecret = "client-secret",
                redirectUri = "http://localhost:10100/login/oauth2/code/google",
                scopes = listOf("openid", "email", "profile"),
            ),
        ).findByRegistrationId("google").block()!!

    private fun rawClaims(issuer: String, audience: List<String> = listOf("client-id.apps.googleusercontent.com")):
        Map<String, Any> {
        val now = Instant.now()
        return mapOf(
            IdTokenClaimNames.ISS to issuer,
            IdTokenClaimNames.SUB to "google-sub",
            IdTokenClaimNames.AUD to audience,
            IdTokenClaimNames.IAT to Date.from(now.minusSeconds(60)),
            IdTokenClaimNames.EXP to Date.from(now.plusSeconds(600)),
        )
    }

    private fun jwt(
        issuer: String,
        audience: String,
        additionalAudience: String? = null,
        authorizedParty: String? = audience,
    ): Jwt {
        val audiences = listOfNotNull(audience, additionalAudience)
        val rawClaims = HashMap(rawClaims(issuer, audiences))
        if (authorizedParty != null) rawClaims[IdTokenClaimNames.AZP] = authorizedParty
        val converted = GoogleOAuthClientConfiguration.googleIdTokenClaimConverter().convert(rawClaims)
        return Jwt(
            "id-token",
            converted[IdTokenClaimNames.IAT] as Instant,
            converted[IdTokenClaimNames.EXP] as Instant,
            mapOf("alg" to "RS256"),
            converted,
        )
    }
}
