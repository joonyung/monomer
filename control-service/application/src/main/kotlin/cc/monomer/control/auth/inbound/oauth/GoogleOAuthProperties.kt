package cc.monomer.control.auth.inbound.oauth

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "auth.google-oauth")
data class GoogleOAuthProperties(
    val enabled: Boolean = false,
    val clientId: String = "",
    val clientSecret: String = "",
    val redirectUri: String = "{baseUrl}/login/oauth2/code/{registrationId}",
    val scopes: List<String> = listOf("openid", "email", "profile"),
) {
    override fun toString(): String =
        "GoogleOAuthProperties(enabled=$enabled, clientId=$clientId, clientSecret=[REDACTED], " +
            "redirectUri=$redirectUri, scopes=$scopes)"
}
