package cc.monomer.control.dashboard

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import java.net.URI

class DashboardRestProblem(
    val status: HttpStatus,
    val code: String,
    message: String?,
    val properties: Map<String, Any?>,
) : RuntimeException(message ?: code)

internal fun rejectionProblem(code: String, message: String?): DashboardRestProblem =
    DashboardRestProblem(
        status = when (code) {
            "IDEMPOTENCY_CONFLICT", "SESSION_CONFLICT", "QUERY_RUN_CONFLICT", "DRAFT_CONFLICT",
            "EXECUTION_ADMISSION_CONFLICT", "QUERY_RUN_CANCEL_CONFLICT" -> HttpStatus.CONFLICT
            "EXECUTION_BACKPRESSURED" -> HttpStatus.SERVICE_UNAVAILABLE
            else -> HttpStatus.UNPROCESSABLE_ENTITY
        },
        code = code,
        message = message,
        properties = emptyMap(),
    )

@RestControllerAdvice(
    assignableTypes = [
        DashboardController::class, DashboardDraftController::class, DashboardSessionController::class,
        DashboardScheduleController::class, DashboardSessionEntryController::class,
    ],
)
class DashboardProblemHandler {
    @ExceptionHandler(DashboardRestProblem::class)
    fun handle(
        exception: DashboardRestProblem,
        exchange: ServerWebExchange,
    ): ProblemDetail = problem(
        status = exception.status,
        code = exception.code,
        detail = exception.message ?: exception.code,
        properties = exception.properties,
        exchange = exchange,
    )

    @ExceptionHandler(ResponseStatusException::class)
    fun handle(
        exception: ResponseStatusException,
        exchange: ServerWebExchange,
    ): ProblemDetail {
        val status = HttpStatus.valueOf(exception.statusCode.value())
        return problem(
            status = status,
            code = exception.body.properties?.get("code") as? String ?: status.problemCode(),
            detail = exception.reason ?: status.reasonPhrase,
            properties = emptyMap(),
            exchange = exchange,
        )
    }

    private fun problem(
        status: HttpStatus,
        code: String,
        detail: String,
        properties: Map<String, Any?>,
        exchange: ServerWebExchange,
    ): ProblemDetail = ProblemDetail.forStatusAndDetail(status, detail).also { problem ->
        problem.type = URI.create("https://monomer.cc/problems/${code.lowercase().replace('_', '-')}")
        problem.title = status.reasonPhrase
        problem.instance = URI.create(exchange.request.uri.rawPath)
        cc.monomer.control.observability.RequestLogContext.problem(code)
        problem.setProperty("code", code)
        problem.setProperty("requestId", exchange.request.id)
        problem.setProperty("traceId", exchange.request.id)
        val retryable = properties["retryable"] as? Boolean ?: (status == HttpStatus.SERVICE_UNAVAILABLE ||
            code in setOf("SESSION_CONFLICT", "QUERY_RUN_CONFLICT", "DRAFT_CONFLICT", "EXECUTION_ADMISSION_CONFLICT", "QUERY_RUN_CANCEL_CONFLICT"))
        problem.setProperty("retryable", retryable)
        if (status == HttpStatus.SERVICE_UNAVAILABLE && retryable) exchange.response.headers.set("Retry-After", "2")
        properties.forEach { (name, value) -> problem.setProperty(name, value) }
    }
}

private fun HttpStatus.problemCode(): String = when (this) {
    HttpStatus.BAD_REQUEST -> "INVALID_REQUEST"
    HttpStatus.UNAUTHORIZED -> "AUTHENTICATION_REQUIRED"
    HttpStatus.FORBIDDEN -> "FORBIDDEN"
    HttpStatus.NOT_FOUND -> "NOT_FOUND"
    HttpStatus.CONFLICT -> "CONFLICT"
    HttpStatus.GONE -> "GONE"
    HttpStatus.UNPROCESSABLE_ENTITY -> "UNPROCESSABLE_ENTITY"
    HttpStatus.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
    else -> "DASHBOARD_REQUEST_FAILED"
}
