package cc.monomer.control.explore.inbound.rest

import cc.monomer.control.semantic.inbound.rest.toHttpValue
import cc.monomer.control.semantic.inbound.rest.SemanticQueryRejected
import cc.monomer.control.semantic.inbound.rest.setViolations
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

@RestControllerAdvice(assignableTypes = [ExploreRunController::class])
class ExploreProblemHandler(
    @param:org.springframework.beans.factory.annotation.Value("\${monomer.control.oauth.issuer}")
    private val oauthIssuer: String,
) {
    @ExceptionHandler(ServerWebInputException::class)
    fun invalidInput(
        exception: ServerWebInputException,
        exchange: ServerWebExchange,
    ): ResponseEntity<ProblemDetail> = response(
        status = HttpStatus.BAD_REQUEST,
        detail = "Request body is invalid.",
        exchange = exchange,
        retryable = false,
        code = "INVALID_REQUEST",
    )

    @ExceptionHandler(ResponseStatusException::class)
    fun handle(
        exception: ResponseStatusException,
        exchange: ServerWebExchange,
    ): ResponseEntity<ProblemDetail> {
        val status = HttpStatus.valueOf(exception.statusCode.value())
        return response(
            status,
            exception.reason ?: status.reasonPhrase,
            exchange,
            (exception as? ExploreExecutionUnavailable)?.retryable ?: (status == HttpStatus.SERVICE_UNAVAILABLE),
            when (exception) {
                is ExploreSqlRejected -> exception.code
                is ExploreSemanticValidationRejected -> exception.code
                is ExploreExecutionUnavailable -> if (exception.failureCode == "EXECUTION_UNAVAILABLE") {
                    "SERVICE_UNAVAILABLE"
                } else {
                    exception.failureCode
                }
                else -> exception.body.properties?.get("code") as? String ?: status.problemCode()
            },
         ).also { response ->
            response.body?.setViolations((exception as? SemanticQueryRejected)?.violations.orEmpty())
            (exception as? ExploreSemanticValidationRejected)?.partitionFilters?.takeIf { it.isNotEmpty() }?.let { filters ->
                response.body?.setProperty("partitionFilters", filters.map { it.toHttpValue() })
            }
        }
    }

    private fun response(
        status: HttpStatus,
        detail: String,
        exchange: ServerWebExchange,
        retryable: Boolean,
        code: String,
    ): ResponseEntity<ProblemDetail> = ResponseEntity.status(status)
        .headers { headers ->
            if (status == HttpStatus.SERVICE_UNAVAILABLE && retryable) headers.set(HttpHeaders.RETRY_AFTER, "2")
            if (status == HttpStatus.UNAUTHORIZED) {
                headers.set(HttpHeaders.WWW_AUTHENTICATE,
                    if (exchange.request.path.value().contains("/agent/explore/runs/"))
                        "Bearer resource_metadata=\"${oauthIssuer.trimEnd('/')}/.well-known/oauth-protected-resource/v2/workspaces\", scope=\"agent.workspace\""
                    else "Bearer",
                )
            }
        }
        .body(problem(status, detail, exchange, retryable, code))

    private fun problem(
        status: HttpStatus,
        detail: String,
        exchange: ServerWebExchange,
        retryable: Boolean,
        code: String,
    ): ProblemDetail = ProblemDetail.forStatusAndDetail(
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
        problem.setProperty("retryable", retryable)
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

internal class ExploreExecutionUnavailable(val failureCode: String, message: String, val retryable: Boolean) :
    ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message)

internal class ExploreSqlRejected(val code: String, message: String) :
    ResponseStatusException(HttpStatus.CONFLICT, message)

internal class ExploreSemanticValidationRejected(
    val code: String,
    message: String,
    val partitionFilters: List<cc.monomer.semantic.api.SemanticPartitionFilter>,
) : ResponseStatusException(HttpStatus.CONFLICT, message)
