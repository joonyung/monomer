package cc.monomer.control.auth.inbound.oauth

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

object OAuthAttemptState {
    private val pattern = Regex("^[A-Za-z0-9_-]{32,256}$")

    fun requireValid(rawState: String?): String = rawState
        ?.takeIf { pattern.matches(it) }
        ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "oauth_state must be a 32-256 character opaque token")

    fun validOrNull(rawState: String?): String? = rawState?.takeIf { pattern.matches(it) }
}
