package cc.monomer.control.auth.inbound

import cc.monomer.identity.api.PresentedCredential
import org.springframework.http.HttpHeaders

sealed interface BearerAuthorization {
    data object Missing : BearerAuthorization

    data object Malformed : BearerAuthorization

    data class Presented(val credential: PresentedCredential) : BearerAuthorization
}

object BearerCredentials {
    private const val BEARER_PREFIX = "Bearer "

    fun fromFirstAuthorizationHeader(headers: HttpHeaders): BearerAuthorization {
        val authorization = headers.getFirst(HttpHeaders.AUTHORIZATION) ?: return BearerAuthorization.Missing
        return fromAuthorizationValue(authorization)
    }

    fun fromSingleAuthorizationHeader(headers: HttpHeaders): BearerAuthorization {
        val authorizations = headers[HttpHeaders.AUTHORIZATION].orEmpty()
        if (authorizations.isEmpty()) return BearerAuthorization.Missing
        val authorization = authorizations.singleOrNull() ?: return BearerAuthorization.Malformed
        return fromAuthorizationValue(authorization)
    }

    private fun fromAuthorizationValue(authorization: String): BearerAuthorization {
        if (!authorization.startsWith(BEARER_PREFIX)) return BearerAuthorization.Malformed
        val token = authorization.removePrefix(BEARER_PREFIX)
        if (token.isBlank()) return BearerAuthorization.Malformed
        return BearerAuthorization.Presented(PresentedCredential(token))
    }
}
