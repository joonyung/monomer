package cc.monomer.control.semantic.inbound.rest

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException

/** A declared Semantic definition failure with a safe detail and the fields a client can repair. */
class SemanticDefinitionProblem(
    val status: HttpStatus,
    val code: String,
    val detail: String,
    val violations: List<SemanticProblemViolation>,
) : RuntimeException(code)

data class SemanticProblemViolation(
    val field: String,
    val code: String,
    val message: String,
)

@RestControllerAdvice(
    assignableTypes = [
        SemanticModelController::class,
        MetricController::class,
        SemanticTimeSpineController::class,
        SemanticCheckController::class,
    ],
)
class SemanticDefinitionProblemHandler {
    @ExceptionHandler(SemanticDefinitionProblem::class)
    fun declared(problem: SemanticDefinitionProblem, exchange: ServerWebExchange): ResponseEntity<Map<String, Any>> =
        response(problem.status, problem.code, problem.detail, problem.violations, exchange)

    @ExceptionHandler(ServerWebInputException::class)
    fun invalidInput(exception: ServerWebInputException, exchange: ServerWebExchange): ResponseEntity<Map<String, Any>> =
        response(
            HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST",
            "The request body or headers are invalid.",
            listOf(SemanticProblemViolation(aggregateInputField(exception), "INVALID_REQUEST", "The request body or headers are invalid.")),
            exchange,
        )

    private fun aggregateInputField(exception: ServerWebInputException): String {
        val mapping = generateSequence(exception.cause) { it.cause }
            .filterIsInstance<com.fasterxml.jackson.databind.JsonMappingException>().firstOrNull() ?: return "body"
        val path = mapping.path.joinToString("") { reference ->
            reference.fieldName?.let { ".$it" } ?: "[${reference.index}]"
        }.removePrefix(".")
        return path.takeIf { Regex("^aggregateMetrics(?:\\[[0-9]+\\])?(?:\\.(?:measure|name|label|description|timeGranularity|filters))?$").matches(it) } ?: "body"
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun responseStatus(exception: ResponseStatusException, exchange: ServerWebExchange): ResponseEntity<Map<String, Any>> {
        val status = HttpStatus.valueOf(exception.statusCode.value())
        val code = exception.body.properties?.get("code") as? String ?: when (status) {
            HttpStatus.BAD_REQUEST -> "INVALID_REQUEST"
            HttpStatus.UNAUTHORIZED -> "AUTHENTICATION_REQUIRED"
            HttpStatus.FORBIDDEN -> "FORBIDDEN"
            HttpStatus.NOT_FOUND -> "NOT_FOUND"
            HttpStatus.CONFLICT -> "CONFLICT"
            HttpStatus.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
            else -> "REQUEST_FAILED"
        }
        val detail = when (status) {
            HttpStatus.BAD_REQUEST -> exception.reason ?: "The request is invalid."
            HttpStatus.UNAUTHORIZED -> "Sign in to continue."
            HttpStatus.FORBIDDEN -> "Your workspace role does not allow this."
            HttpStatus.NOT_FOUND -> "The semantic resource was not found in this workspace."
            else -> status.reasonPhrase
        }
        return response(status, code, detail, emptyList(), exchange)
    }

    private fun response(
        status: HttpStatus,
        code: String,
        detail: String,
        violations: List<SemanticProblemViolation>,
        exchange: ServerWebExchange,
    ): ResponseEntity<Map<String, Any>> {
        cc.monomer.control.observability.RequestLogContext.problem(code)
        val body = linkedMapOf<String, Any>(
            "type" to "https://monomer.cc/problems/${code.lowercase().replace('_', '-')}",
            "title" to status.reasonPhrase,
            "status" to status.value(),
            "detail" to detail.ifBlank { status.reasonPhrase }.take(MAX_DETAIL_LENGTH),
            "instance" to exchange.request.uri.rawPath,
            "code" to code,
            "requestId" to exchange.request.id,
            "traceId" to exchange.request.id,
            "retryable" to (status == HttpStatus.SERVICE_UNAVAILABLE),
        )
        if (violations.isNotEmpty()) {
            body["violations"] = violations.take(MAX_VIOLATIONS).map { violation ->
                mapOf(
                    "field" to violation.field.take(MAX_VIOLATION_TEXT),
                    "code" to violation.code,
                    "message" to violation.message.take(MAX_VIOLATION_TEXT),
                )
            }
        }
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .headers { headers ->
                if (status == HttpStatus.UNAUTHORIZED) headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                if (status == HttpStatus.SERVICE_UNAVAILABLE) headers.set(HttpHeaders.RETRY_AFTER, "2") }
            .body(body)
    }

    private companion object {
        const val MAX_DETAIL_LENGTH = 4096
        const val MAX_VIOLATIONS = 100
        const val MAX_VIOLATION_TEXT = 512
    }
}
