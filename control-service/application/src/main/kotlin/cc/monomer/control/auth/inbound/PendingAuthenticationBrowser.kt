package cc.monomer.control.auth.inbound

import cc.monomer.identity.application.OpaqueCredentials
import cc.monomer.control.auth.inbound.session.RefreshCookieProperties
import cc.monomer.identity.application.pending.PendingAuthenticationRequests
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

@Component
class PendingAuthenticationBrowser(private val requests: PendingAuthenticationRequests, private val cookies: RefreshCookieProperties) {
    suspend fun start(exchange: ServerWebExchange): String {
        val existing = read(exchange)
        if (existing != null && requests.knownBrowser(existing)) return existing
        val binding = OpaqueCredentials.generate()
        exchange.response.addCookie(ResponseCookie.from(cookieName(), binding).httpOnly(true).secure(cookies.secure)
            .sameSite("Lax").path("/").build())
        return binding
    }
    fun read(exchange: ServerWebExchange): String? {
        val values = exchange.request.cookies[cookieName()] ?: return null
        if (values.size != 1) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "ambiguous authentication browser")
        return values.single().value.takeIf(PendingAuthenticationRequests::validReference)
    }
    private fun cookieName() = if (cookies.secure) "__Host-monomer_auth_pending" else "monomer_auth_pending_local"
}
