package cc.monomer.control.auth

import cc.monomer.control.auth.inbound.agentoauth.AgentOAuthProperties
import cc.monomer.control.auth.inbound.oauth.GoogleOAuthProperties
import cc.monomer.control.auth.inbound.oauth.OAuthProperties
import cc.monomer.control.auth.inbound.session.RefreshCookieProperties
import cc.monomer.control.publicapi.ControlPublicApiProperties
import java.net.URI
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration(proxyBeanMethods = false)
@Profile("!local")
@EnableConfigurationProperties(
    ControlPublicApiProperties::class,
    OAuthProperties::class,
    GoogleOAuthProperties::class,
    RefreshCookieProperties::class,
    AgentOAuthProperties::class,
)
class AuthDeploymentConfiguration(
    publicApi: ControlPublicApiProperties,
    oauth: OAuthProperties,
    google: GoogleOAuthProperties,
    refreshCookie: RefreshCookieProperties,
    agentOAuth: AgentOAuthProperties,
    @Value("\${auth.cors.allowed-origins}") corsAllowedOrigins: String,
    @Value("\${auth.local-login.enabled:false}") localLoginEnabled: Boolean,
) {
    init {
        require(!localLoginEnabled) { "auth.local-login.enabled requires the local profile" }
        require(refreshCookie.secure) { "auth.refresh-cookie.secure must be true outside the local profile" }
        val webOrigin = httpsUri(publicApi.webOrigin.toString(), "monomer.control.public-api.web-origin", "")
        val frontendCallback = httpsUri(oauth.frontendCallbackUrl, "auth.oauth.frontend-callback-url", "/auth/callback")
        require(origin(frontendCallback) == origin(webOrigin)) {
            "auth.oauth.frontend-callback-url must use monomer.control.public-api.web-origin"
        }
        val corsOrigins = corsAllowedOrigins.split(",").map {
            httpsUri(it.trim(), "auth.cors.allowed-origins", "")
        }
        val browserOrigin = URI("https", null, webOrigin.host.lowercase(),
            if (webOrigin.port == 443) -1 else webOrigin.port, null, null, null).toString()
        require(webOrigin.toString() == browserOrigin) {
            "monomer.control.public-api.web-origin must use canonical browser origin serialization"
        }
        require(corsOrigins.any { it.toString() == browserOrigin }) {
            "auth.cors.allowed-origins must include monomer.control.public-api.web-origin"
        }
        val issuer = httpsUri(agentOAuth.issuer, "monomer.control.oauth.issuer", "")
        val mcpResource = httpsUri(agentOAuth.mcpResource, "monomer.control.oauth.mcp-resource", "/mcp")
        require(origin(mcpResource) == origin(issuer)) {
            "monomer.control.oauth.mcp-resource must use monomer.control.oauth.issuer"
        }
        if (google.enabled) {
            val redirect = httpsUri(google.redirectUri, "auth.google-oauth.redirect-uri", "/login/oauth2/code/google")
            require(origin(redirect) == origin(issuer)) {
                "auth.google-oauth.redirect-uri must use monomer.control.oauth.issuer"
            }
        }
    }

    private fun httpsUri(value: String, property: String, path: String): URI {
        val message = "$property must be an explicit HTTPS URL with path '$path', without credentials, query, or fragment"
        val uri = try {
            URI(value)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException(message)
        } catch (_: java.net.URISyntaxException) {
            throw IllegalArgumentException(message)
        }
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawPath == path && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535)
        ) { message }
        return uri
    }

    private fun origin(uri: URI): Triple<String, String, Int> =
        Triple(uri.scheme, uri.host.lowercase(), if (uri.port == -1) 443 else uri.port)
}
