package cc.monomer.control.catalog.inbound.rest

import cc.monomer.catalog.api.CatalogConflictReason
import cc.monomer.control.catalog.toCatalogConflictDetails
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

@RestControllerAdvice(
    assignableTypes = [
        CatalogAuthorityController::class,
        CatalogBrowseController::class,
        CatalogWorkspaceController::class,
    ],
)
class CatalogProblemHandler {
    @ExceptionHandler(CatalogConflictProblem::class)
    fun catalogConflict(
        exception: CatalogConflictProblem,
        exchange: ServerWebExchange,
    ): ResponseEntity<CatalogProblemResponse> = problemResponse(
        status = HttpStatus.CONFLICT,
        code = exception.code,
        detail = exception.detail,
        exchange = exchange,
    )

    @ExceptionHandler(ResponseStatusException::class)
    fun responseStatus(
        exception: ResponseStatusException,
        exchange: ServerWebExchange,
    ): ResponseEntity<CatalogProblemResponse> {
        val status = HttpStatus.valueOf(exception.statusCode.value())
        val code = when (status) {
            HttpStatus.BAD_REQUEST -> "INVALID_REQUEST"
            HttpStatus.UNAUTHORIZED -> "AUTHENTICATION_REQUIRED"
            HttpStatus.FORBIDDEN -> "FORBIDDEN"
            HttpStatus.NOT_FOUND -> "NOT_FOUND"
            HttpStatus.CONFLICT -> "CONFLICT"
            else -> "CATALOG_REQUEST_FAILED"
        }
        return problemResponse(
            status = status,
            code = code,
            detail = exception.reason ?: status.reasonPhrase,
            exchange = exchange,
        )
    }

    private fun problemResponse(
        status: HttpStatus,
        code: String,
        detail: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<CatalogProblemResponse> {
        cc.monomer.control.observability.RequestLogContext.problem(code)
        val response = ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        if (status == HttpStatus.UNAUTHORIZED) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        }
        return response.body(
            CatalogProblemResponse(
                type = "https://monomer.cc/problems/${code.lowercase().replace('_', '-')}",
                title = status.reasonPhrase,
                status = status.value(),
                detail = detail,
                instance = exchange.request.uri.rawPath,
                code = code,
                requestId = exchange.request.id,
                traceId = exchange.request.id,
                retryable = false,
            ),
        )
    }
}

class CatalogConflictProblem(
    val code: String,
    val detail: String,
) : RuntimeException(detail)

fun CatalogConflictReason.toCatalogConflictProblem(): CatalogConflictProblem = toCatalogConflictDetails().let { conflict ->
    CatalogConflictProblem(code = conflict.code, detail = conflict.detail)
}

data class CatalogProblemResponse(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String,
    val code: String,
    val requestId: String,
    val traceId: String,
    val retryable: Boolean,
)
