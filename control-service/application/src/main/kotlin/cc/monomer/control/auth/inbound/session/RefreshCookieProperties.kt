package cc.monomer.control.auth.inbound.session

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Controls the `Secure` attribute of the refresh-token cookie. Default false for local
 * http development; set true in any https deployment so the cookie is never sent over
 * plaintext.
 */
@ConfigurationProperties(prefix = "auth.refresh-cookie")
data class RefreshCookieProperties(
    val secure: Boolean,
)
