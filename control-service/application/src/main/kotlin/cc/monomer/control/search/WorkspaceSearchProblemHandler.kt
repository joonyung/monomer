package cc.monomer.control.search

import java.net.URI
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

@RestControllerAdvice(assignableTypes = [WorkspaceSearchController::class])
class WorkspaceSearchProblemHandler {
    @ExceptionHandler(ResponseStatusException::class)
    fun responseStatus(
        exception: ResponseStatusException,
        exchange: ServerWebExchange,
    ): ResponseEntity<ProblemDetail> {
        val status = HttpStatus.valueOf(exception.statusCode.value())
        val code = when (status) {
            HttpStatus.BAD_REQUEST -> "INVALID_REQUEST"
            HttpStatus.UNAUTHORIZED -> "AUTHENTICATION_REQUIRED"
            HttpStatus.FORBIDDEN -> "FORBIDDEN"
            HttpStatus.NOT_FOUND -> "NOT_FOUND"
            else -> "SEARCH_REQUEST_FAILED"
        }
        cc.monomer.control.observability.RequestLogContext.problem(code)
        val problem = ProblemDetail.forStatusAndDetail(status, exception.reason ?: status.reasonPhrase).also { body ->
            body.type = URI.create("https://monomer.cc/problems/${code.lowercase().replace('_', '-')}")
            body.title = status.reasonPhrase
            body.instance = URI.create(exchange.request.uri.rawPath)
            body.setProperty("code", code)
            body.setProperty("requestId", exchange.request.id)
            body.setProperty("traceId", exchange.request.id)
            body.setProperty("retryable", false)
        }
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .headers { headers -> if (status == HttpStatus.UNAUTHORIZED) headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer") }
            .body(problem)
    }
}
