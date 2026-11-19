package cc.monomer.control.auth.inbound.oauth

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider
import org.springframework.security.oauth2.client.oidc.authentication.ReactiveOidcIdTokenDecoderFactory
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.oauth2.core.converter.ClaimTypeConverter
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames
import org.springframework.security.oauth2.core.oidc.StandardClaimNames
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoderFactory

@Configuration
@EnableConfigurationProperties(GoogleOAuthProperties::class)
class GoogleOAuthClientConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "auth.google-oauth", name = ["enabled"], havingValue = "true")
    fun googleClientRegistrationRepository(
        properties: GoogleOAuthProperties,
    ): ReactiveClientRegistrationRepository {
        require(properties.clientId.isNotBlank()) {
            "MONOMER_CONTROL_GOOGLE_OAUTH_CLIENT_ID is required when MONOMER_CONTROL_GOOGLE_OAUTH_ENABLED=true"
        }
        require(properties.clientSecret.isNotBlank()) {
            "MONOMER_CONTROL_GOOGLE_OAUTH_CLIENT_SECRET is required when MONOMER_CONTROL_GOOGLE_OAUTH_ENABLED=true"
        }
        require(properties.scopes.contains("openid")) {
            "MONOMER_CONTROL_GOOGLE_OAUTH_SCOPES must include openid"
        }
        require(properties.scopes.contains("email")) {
            "MONOMER_CONTROL_GOOGLE_OAUTH_SCOPES must include email"
        }

        val registration = CommonOAuth2Provider.GOOGLE.getBuilder("google")
            .clientId(properties.clientId)
            .clientSecret(properties.clientSecret)
            .redirectUri(properties.redirectUri)
            .scope(*properties.scopes.toTypedArray())
            .build()

        return InMemoryReactiveClientRegistrationRepository(registration)
    }

    @Bean
    @ConditionalOnProperty(prefix = "auth.google-oauth", name = ["enabled"], havingValue = "true")
    fun googleIdTokenDecoderFactory(): ReactiveJwtDecoderFactory<ClientRegistration> =
        ReactiveOidcIdTokenDecoderFactory().apply {
            setClaimTypeConverterFactory { googleIdTokenClaimConverter() }
        }

    companion object {
        const val RAW_GOOGLE_ISSUER_CLAIM = "monomer_google_raw_issuer"

        fun googleIdTokenClaimConverter(): Converter<Map<String, Any>, Map<String, Any>> {
            val claimConverters = HashMap(ReactiveOidcIdTokenDecoderFactory.createDefaultClaimTypeConverters())
            claimConverters[StandardClaimNames.EMAIL_VERIFIED] = Converter { rawValue -> rawValue }
            val delegate = ClaimTypeConverter(claimConverters)
            return Converter { source ->
                val claims = HashMap(source)
                claims.remove(RAW_GOOGLE_ISSUER_CLAIM)
                val rawIssuer = source[IdTokenClaimNames.ISS] as? String
                if (rawIssuer == GoogleAuthenticationAssertionFactory.GOOGLE_ISSUER_HOST ||
                    rawIssuer == GoogleAuthenticationAssertionFactory.GOOGLE_ISSUER_HTTPS
                ) {
                    claims[RAW_GOOGLE_ISSUER_CLAIM] = rawIssuer
                    claims[IdTokenClaimNames.ISS] = GoogleAuthenticationAssertionFactory.GOOGLE_ISSUER_HTTPS
                }
                delegate.convert(claims)
            }
        }
    }
}
