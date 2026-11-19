package cc.monomer.control.auth.inbound.session

import cc.monomer.control.auth.AuthJwtProperties
import java.time.Duration
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component

/**
 * Builds the `refresh_token` cookie used across login, refresh, and logout. HttpOnly +
 * SameSite=Lax + path=/auth so the FE never reads it from JS and the browser only sends it
 * to refresh/logout/dev-login session endpoints.
 */
@Component
class RefreshCookieFactory(
    private val jwtProperties: AuthJwtProperties,
    private val refreshCookieProperties: RefreshCookieProperties,
) {

    fun build(rawRefreshToken: String): ResponseCookie =
        baseBuilder(rawRefreshToken)
            .maxAge(Duration.ofSeconds(jwtProperties.refreshTokenTtlSeconds))
            .build()

    fun expired(): ResponseCookie =
        baseBuilder("")
            .maxAge(Duration.ZERO)
            .build()

    private fun baseBuilder(value: String): ResponseCookie.ResponseCookieBuilder =
        ResponseCookie.from(COOKIE_NAME, value)
            .httpOnly(true)
            .secure(refreshCookieProperties.secure)
            .sameSite("Lax")
            .path("/auth")

    companion object {
        const val COOKIE_NAME = "refresh_token"
    }
}
