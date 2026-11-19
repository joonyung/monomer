package cc.monomer.control.auth.inbound.oauth

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for the OAuth login callback.
 *
 * After Spring Security completes the Google OAuth handshake, [OAuth2LoginSuccessHandler]
 * sets the HttpOnly refresh cookie and redirects to [frontendCallbackUrl] with non-sensitive
 * callback metadata. The frontend exchanges the cookie through `/auth/refresh`.
 */
@ConfigurationProperties(prefix = "auth.oauth")
data class OAuthProperties(
    val frontendCallbackUrl: String,
)
