package cc.monomer.control.connector.inbound.rest

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

@RestControllerAdvice(assignableTypes = [ConnectorMutationController::class])
class ConnectorMutationProblemHandler {
    @ExceptionHandler(ResponseStatusException::class)
    fun rejected(exception: ResponseStatusException, exchange: ServerWebExchange): ResponseEntity<Map<String, Any>> =
        response(HttpStatus.valueOf(exception.statusCode.value()), exchange, null, exception.body.properties?.get("code") as? String,
            exception.reason.takeIf { exception.statusCode == HttpStatus.UNPROCESSABLE_ENTITY })

    @ExceptionHandler(ConnectorLimitExceededException::class)
    fun limitExceeded(exception: ConnectorLimitExceededException, exchange: ServerWebExchange): ResponseEntity<Map<String, Any>> =
        response(HttpStatus.CONFLICT, exchange, ConnectorConflict.LIMIT_EXCEEDED, null, null)

    @ExceptionHandler(ConnectorRefreshActiveException::class)
    fun refreshActive(exception: ConnectorRefreshActiveException, exchange: ServerWebExchange): ResponseEntity<Map<String, Any>> =
        response(HttpStatus.CONFLICT, exchange, ConnectorConflict.REFRESH_ACTIVE, null, null)

    @ExceptionHandler(RuntimeException::class)
    fun failed(exception: RuntimeException, exchange: ServerWebExchange): ResponseEntity<Map<String, Any>> {
        cc.monomer.control.observability.RequestLogContext.failure(exception)
        return response(HttpStatus.INTERNAL_SERVER_ERROR, exchange, null, null, null)
    }

    private fun response(status: HttpStatus, exchange: ServerWebExchange, conflict: ConnectorConflict?, admissionCode: String?, inputDetail: String?): ResponseEntity<Map<String, Any>> {
        val code = admissionCode ?: conflict?.code ?: when (status) {
            HttpStatus.BAD_REQUEST, HttpStatus.UNPROCESSABLE_ENTITY -> "INVALID_REQUEST"
            HttpStatus.UNAUTHORIZED -> "AUTHENTICATION_REQUIRED"
            HttpStatus.FORBIDDEN -> "FORBIDDEN"
            HttpStatus.NOT_FOUND -> "NOT_FOUND"
            HttpStatus.CONFLICT -> "CONFLICT"
            else -> "CONNECTOR_REQUEST_FAILED"
        }
        val detail = inputDetail ?: conflict?.detail ?: when (status) {
            HttpStatus.BAD_REQUEST -> "Check the typed Connector fields. Settings contain only displayName and properties; use a new Connector to change its type or PostgreSQL/Redshift database."
            HttpStatus.CONFLICT -> "The request conflicts with the existing Connector mutation."
            HttpStatus.INTERNAL_SERVER_ERROR -> "The mutation outcome could not be confirmed. Do not automatically repeat the request."
            else -> "The Connector request could not be completed."
        }
        cc.monomer.control.observability.RequestLogContext.problem(code)
        val body = linkedMapOf<String, Any>(
            "type" to "https://monomer.cc/problems/${code.lowercase().replace('_', '-')}",
            "title" to status.reasonPhrase,
            "status" to status.value(),
            "detail" to detail,
            "instance" to exchange.request.uri.rawPath,
            "code" to code,
            "requestId" to exchange.request.id,
            "traceId" to exchange.request.id,
            "retryable" to false,
        )
        if (status == HttpStatus.BAD_REQUEST || status == HttpStatus.UNPROCESSABLE_ENTITY) body["violations"] = listOf(mapOf(
            "field" to "body", "code" to "CONNECTOR_INPUT_INVALID", "message" to detail,
        ))
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .headers { if (status == HttpStatus.UNAUTHORIZED) it.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer") }
            .body(body)
    }

    private companion object {
        val logger = org.slf4j.LoggerFactory.getLogger(ConnectorMutationProblemHandler::class.java)
    }
}

class ConnectorLimitExceededException : RuntimeException()

class ConnectorRefreshActiveException : RuntimeException()

private enum class ConnectorConflict(val code: String, val detail: String) {
    LIMIT_EXCEEDED(
        "CONNECTOR_LIMIT_EXCEEDED",
        "Free workspaces can have up to 10 connectors. Use an existing connector or choose another workspace.",
    ),
    REFRESH_ACTIVE(
        "CONNECTOR_REFRESH_ACTIVE",
        "A Catalog refresh is running for this connector. Cancel it in Catalog, then delete the connector.",
    ),
}
