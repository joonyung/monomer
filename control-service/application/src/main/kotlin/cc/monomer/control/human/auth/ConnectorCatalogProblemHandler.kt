package cc.monomer.control.human.auth

import java.net.URI
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebExchange

@RestControllerAdvice
class ConnectorCatalogProblemHandler {
    @ExceptionHandler(ConnectorCatalogRequestProblem::class)
    fun rejected(
        exception: ConnectorCatalogRequestProblem,
        exchange: ServerWebExchange,
    ): ResponseEntity<ProblemDetail> {
        cc.monomer.control.observability.RequestLogContext.problem(exception.code)
        val problem = ProblemDetail.forStatusAndDetail(exception.status, exception.detail).also { body ->
            body.type = URI.create("https://monomer.cc/problems/${exception.code.lowercase().replace('_', '-')}")
            body.title = exception.status.reasonPhrase
            body.instance = URI.create(exchange.request.uri.rawPath)
            body.setProperty("code", exception.code)
            body.setProperty("requestId", exchange.request.id)
            body.setProperty("traceId", exchange.request.id)
            body.setProperty("retryable", false)
        }
        return ResponseEntity.status(exception.status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .headers { headers -> if (exception.status == HttpStatus.UNAUTHORIZED) headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer") }
            .body(problem)
    }
}
