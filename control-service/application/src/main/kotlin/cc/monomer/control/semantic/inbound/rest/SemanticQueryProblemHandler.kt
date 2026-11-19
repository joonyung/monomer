package cc.monomer.control.semantic.inbound.rest

import java.net.URI
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException

@RestControllerAdvice(assignableTypes = [SemanticQueryController::class])
class SemanticQueryProblemHandler {
    @ExceptionHandler(ServerWebInputException::class)
    fun invalidInput(
        exception: ServerWebInputException,
        exchange: ServerWebExchange,
    ): ResponseEntity<ProblemDetail> = response(
        status = HttpStatus.BAD_REQUEST,
        detail = "Request body is invalid.",
        exchange = exchange,
        code = "INVALID_REQUEST",
    )

    @ExceptionHandler(ResponseStatusException::class)
    fun responseStatus(
        exception: ResponseStatusException,
        exchange: ServerWebExchange,
    ): ResponseEntity<ProblemDetail> {
        val status = HttpStatus.valueOf(exception.statusCode.value())
        return response(
            status = status,
            detail = exception.reason ?: status.reasonPhrase,
            exchange = exchange,
            code = exception.body.properties?.get("code") as? String ?: status.problemCode(),
        ).also { response -> response.body?.setViolations((exception as? SemanticQueryRejected)?.violations.orEmpty()) }
    }

    private fun response(
        status: HttpStatus,
        detail: String,
        exchange: ServerWebExchange,
        code: String,
    ): ResponseEntity<ProblemDetail> = ResponseEntity.status(status)
        .headers { headers ->
            if (status == HttpStatus.SERVICE_UNAVAILABLE) headers.set(HttpHeaders.RETRY_AFTER, "2")
            if (status == HttpStatus.UNAUTHORIZED) {
                headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            }
        }
        .body(problem(status, detail, exchange, code))

    private fun problem(
        status: HttpStatus,
        detail: String,
        exchange: ServerWebExchange,
        code: String,
    ): ProblemDetail {
        return ProblemDetail.forStatusAndDetail(
            status,
            detail.ifBlank { status.reasonPhrase }.take(MAX_PROBLEM_DETAIL_LENGTH),
        ).also { problem ->
            problem.type = URI.create("https://monomer.cc/problems/${code.lowercase().replace('_', '-')}")
            problem.title = status.reasonPhrase
            problem.instance = URI.create(exchange.request.uri.rawPath)
            cc.monomer.control.observability.RequestLogContext.problem(code)
            problem.setProperty("code", code)
            problem.setProperty("requestId", exchange.request.id)
            problem.setProperty("traceId", exchange.request.id)
            problem.setProperty("retryable", status == HttpStatus.SERVICE_UNAVAILABLE)
        }
    }
}

private const val MAX_PROBLEM_DETAIL_LENGTH = 4096

private fun HttpStatus.problemCode(): String = when (this) {
    HttpStatus.BAD_REQUEST -> "INVALID_REQUEST"
    HttpStatus.UNAUTHORIZED -> "AUTHENTICATION_REQUIRED"
    HttpStatus.FORBIDDEN -> "FORBIDDEN"
    HttpStatus.NOT_FOUND -> "NOT_FOUND"
    HttpStatus.CONFLICT -> "CONFLICT"
    HttpStatus.GONE -> "GONE"
    HttpStatus.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
    else -> "REQUEST_FAILED"
}
