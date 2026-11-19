package cc.monomer.control.pages.publicpages

import cc.monomer.control.pagereader.*
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.server.PathContainer
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import org.springframework.web.util.pattern.PathPatternParser
import reactor.core.publisher.Mono

object PublicPagesHttpPaths {
    private const val HTML = "/v2/public/workspaces/*/pages/*/html"
    val reads = arrayOf(
        "/v2/workspaces/*/pages/access",
        "/v2/public/workspaces/*/pages/tree",
        "/v2/public/workspaces/*/pages/*",
        "/v2/public/workspaces/*/pages/*/content",
        HTML,
        "/v2/public/workspaces/*/pages/*/sessions",
        "/v2/public/workspaces/*/pages/*/sessions/entry",
        "/v2/public/workspaces/*/pages/*/sessions/*",
        "/v2/public/workspaces/*/pages/*/sessions/*/query-runs",
        "/v2/public/workspaces/*/pages/*/sessions/*/query-runs/*",
        "/v2/public/workspaces/*/pages/*/sessions/*/query-runs/*/result",
    )
    val heads = arrayOf(HTML)
    val resolves = arrayOf("/v2/public/workspaces/*/pages/*/sessions/*:resolve")
    private val jsonProblemReads = reads.filter { it != HTML }.map(PathPatternParser.defaultInstance::parse)
    private val jsonProblemResolves = resolves.map(PathPatternParser.defaultInstance::parse)

    fun contains(path: String): Boolean = path.startsWith("/v2/public/workspaces/") ||
        Regex("^/v2/workspaces/[^/]+/pages/access$").matches(path)

    fun declaresJsonProblem(method: HttpMethod, path: PathContainer): Boolean = when (method) {
        HttpMethod.GET -> jsonProblemReads.any { it.matches(path) }
        HttpMethod.POST -> jsonProblemResolves.any { it.matches(path) }
        else -> false
    }
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class PublicPagesNoStoreFilter : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (PublicPagesHttpPaths.contains(exchange.request.path.value())) {
            exchange.response.headers.cacheControl = "no-store"
            exchange.response.beforeCommit {
                exchange.response.headers.cacheControl = "no-store"
                Mono.empty()
            }
        }
        return chain.filter(exchange)
    }
}

@RestControllerAdvice(assignableTypes = [PublicPagesController::class, PagesAccessController::class])
class PublicPagesProblemHandler {
    @ExceptionHandler(PageReaderProblem::class)
    fun readerProblem(error: PageReaderProblem, exchange: ServerWebExchange): ResponseEntity<Map<String, Any>> = problem(when (error.failure) {
        PageReaderFailure.NotFound -> PublicPagesProblem(HttpStatus.NOT_FOUND, "NOT_FOUND")
        PageReaderFailure.Conflict -> PublicPagesProblem(HttpStatus.CONFLICT, "RESULT_UNAVAILABLE")
        PageReaderFailure.IdempotencyConflict -> PublicPagesProblem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT")
        PageReaderFailure.Backpressured -> PublicPagesProblem(HttpStatus.SERVICE_UNAVAILABLE, "EXECUTION_BACKPRESSURED")
        PageReaderFailure.Unavailable -> PublicPagesProblem(HttpStatus.SERVICE_UNAVAILABLE, "PAGE_READER_UNAVAILABLE")
        PageReaderFailure.InvalidCursor -> PublicPagesProblem(
            if (exchange.request.path.value().endsWith("/sessions")) HttpStatus.UNPROCESSABLE_ENTITY else HttpStatus.BAD_REQUEST,
            "CURSOR_INVALID",
        )
        PageReaderFailure.InvalidRequest -> PublicPagesProblem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST")
        PageReaderFailure.Unauthorized -> PublicPagesProblem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED")
    })

    @ExceptionHandler(PageReaderOwnerRejected::class)
    fun inputRejected(error: PageReaderOwnerRejected): ResponseEntity<Map<String, Any>> {
        cc.monomer.control.observability.RequestLogContext.problem(error.code)
        val retryable = error.code in setOf("SESSION_CONFLICT", "QUERY_RUN_CONFLICT", "DRAFT_CONFLICT", "EXECUTION_ADMISSION_CONFLICT", "QUERY_RUN_CANCEL_CONFLICT")
        return ResponseEntity.status(if (retryable) HttpStatus.CONFLICT else HttpStatus.UNPROCESSABLE_ENTITY)
            .header("Cache-Control", "no-store")
            .body(mapOf("code" to error.code, "detail" to error.detail, "retryable" to retryable))
    }

    @ExceptionHandler(PublicPagesProblem::class)
    fun problem(error: PublicPagesProblem): ResponseEntity<Map<String, Any>> {
        cc.monomer.control.observability.RequestLogContext.problem(error.code)
        val retryable = error.status == HttpStatus.SERVICE_UNAVAILABLE
        return ResponseEntity.status(error.status).header("Cache-Control", "no-store")
            .headers { if (retryable) it.set("Retry-After", "2") }
            .body(mapOf("code" to error.code, "retryable" to retryable))
    }

    @ExceptionHandler(org.springframework.web.server.ServerWebInputException::class, IllegalArgumentException::class)
    fun invalidInput(): ResponseEntity<Map<String, Any>> =
        problem(PublicPagesProblem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"))

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException::class)
    fun status(error: org.springframework.web.server.ResponseStatusException): ResponseEntity<Map<String, Any>> =
        problem(PublicPagesProblem(HttpStatus.valueOf(error.statusCode.value()),
            if (error.statusCode.value() == 404) "NOT_FOUND" else "PAGE_READER_UNAVAILABLE"))

    @ExceptionHandler(Exception::class)
    fun unavailable(error: Exception): ResponseEntity<Map<String, Any>> {
        cc.monomer.control.observability.RequestLogContext.failure(error)
        return problem(PublicPagesProblem(HttpStatus.SERVICE_UNAVAILABLE, "PAGE_READER_UNAVAILABLE"))
    }
}

class PublicPagesProblem(val status: HttpStatus, val code: String) : RuntimeException(code)
