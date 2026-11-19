package cc.monomer.control.overview

import java.net.URI
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

@RestControllerAdvice(assignableTypes = [OverviewActivityController::class])
class OverviewActivityProblemHandler {
    @ExceptionHandler(ResponseStatusException::class)
    fun rejected(exception: ResponseStatusException, exchange: ServerWebExchange): ProblemDetail {
        val status = HttpStatus.valueOf(exception.statusCode.value())
        val code = exception.body.properties?.get("code") as? String ?: when (status) {
            HttpStatus.BAD_REQUEST -> "INVALID_REQUEST"
            HttpStatus.UNAUTHORIZED -> "AUTHENTICATION_REQUIRED"
            HttpStatus.NOT_FOUND -> "NOT_FOUND"
            HttpStatus.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
            else -> "REQUEST_FAILED"
        }
        val retryable = status == HttpStatus.SERVICE_UNAVAILABLE
        if (retryable) exchange.response.headers.set("Retry-After", "2")
        if (status == HttpStatus.UNAUTHORIZED) exchange.response.headers.set("WWW-Authenticate", "Bearer")
        cc.monomer.control.observability.RequestLogContext.problem(code)
        return ProblemDetail.forStatusAndDetail(status, exception.reason ?: status.reasonPhrase).also {
            it.type = URI.create("https://monomer.cc/problems/${code.lowercase().replace('_', '-')}")
            it.title = status.reasonPhrase
            it.instance = URI.create(exchange.request.uri.rawPath)
            it.setProperty("code", code)
            it.setProperty("requestId", exchange.request.id)
            it.setProperty("traceId", exchange.request.id)
            it.setProperty("retryable", retryable)
        }
    }
}
