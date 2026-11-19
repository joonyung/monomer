package cc.monomer.control.auth.inbound.session

import cc.monomer.identity.application.session.LogoutAllSessionsUseCase
import cc.monomer.identity.application.session.LogoutUseCase
import cc.monomer.identity.application.session.RefreshSessionUseCase
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

@RestController
class RefreshTokenController(
    private val refreshSessionUseCase: RefreshSessionUseCase,
    private val logoutUseCase: LogoutUseCase,
    private val logoutAllSessionsUseCase: LogoutAllSessionsUseCase,
    private val refreshCookieFactory: RefreshCookieFactory,
) {

    @PostMapping("/auth/refresh")
    suspend fun refresh(exchange: ServerWebExchange): ResponseEntity<RefreshResponse> {
        val rawToken = exchange.request.cookies.getFirst(RefreshCookieFactory.COOKIE_NAME)?.value
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return when (val result = refreshSessionUseCase.refresh(rawToken)) {
            is RefreshSessionUseCase.Result.Invalid ->
                ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

            is RefreshSessionUseCase.Result.Rotated -> {
                exchange.response.addCookie(refreshCookieFactory.build(result.newRawRefreshToken))
                ResponseEntity.ok(
                    RefreshResponse(
                        accessToken = result.accessToken.serialized,
                        expiresAt = result.accessToken.expiresAt.toString(),
                    ),
                )
            }
        }
    }

    @PostMapping("/auth/logout")
    suspend fun logout(exchange: ServerWebExchange): ResponseEntity<Void> {
        exchange.request.cookies.getFirst(RefreshCookieFactory.COOKIE_NAME)?.value?.let { rawToken ->
            logoutUseCase.logout(rawToken)
        }
        exchange.response.addCookie(refreshCookieFactory.expired())
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/auth/logout-all")
    suspend fun logoutAll(exchange: ServerWebExchange): ResponseEntity<Void> {
        val result = exchange.request.cookies.getFirst(RefreshCookieFactory.COOKIE_NAME)?.value
            ?.let { rawToken -> logoutAllSessionsUseCase.logoutAll(rawToken) }
            ?: LogoutAllSessionsUseCase.Result.Unauthenticated
        exchange.response.addCookie(refreshCookieFactory.expired())
        return when (result) {
            LogoutAllSessionsUseCase.Result.Revoked -> ResponseEntity.noContent().build()
            LogoutAllSessionsUseCase.Result.Unauthenticated -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
    }
}
