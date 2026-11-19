package cc.monomer.control.semantic.inbound.rest

import cc.monomer.control.explore.inbound.rest.ExploreRunController
import cc.monomer.control.observability.RequestLogContext
import cc.monomer.semantic.application.SemanticMetricFlowEngineFaultException
import cc.monomer.semantic.application.SemanticMetricFlowInvalidQueryException
import cc.monomer.semantic.application.SemanticMetricFlowUnsupportedException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebExchange

@RestControllerAdvice(assignableTypes = [SemanticQueryController::class, SemanticCheckController::class, ExploreRunController::class])
class SemanticMetricFlowProblemHandler {
    @ExceptionHandler(SemanticMetricFlowInvalidQueryException::class)
    fun invalidQuery(failure: SemanticMetricFlowInvalidQueryException, exchange: ServerWebExchange): ResponseEntity<Map<String, Any>> =
        response(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SEMANTIC_QUERY", failure.reason.name, failure, exchange)

    @ExceptionHandler(SemanticMetricFlowUnsupportedException::class)
    fun unsupported(failure: SemanticMetricFlowUnsupportedException, exchange: ServerWebExchange): ResponseEntity<Map<String, Any>> =
        response(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_SEMANTIC_QUERY",
            "The Semantic query uses a capability MetricFlow does not support.", failure, exchange)

    @ExceptionHandler(SemanticMetricFlowEngineFaultException::class)
    fun engineFault(failure: SemanticMetricFlowEngineFaultException, exchange: ServerWebExchange): ResponseEntity<Map<String, Any>> =
        response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "Monomer could not compute the Semantic query.", failure, exchange)

    private fun response(
        status: HttpStatus,
        code: String,
        detail: String,
        failure: Throwable,
        exchange: ServerWebExchange,
    ): ResponseEntity<Map<String, Any>> {
        RequestLogContext.problem(code)
        RequestLogContext.failure(failure)
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(mapOf(
            "type" to "https://monomer.cc/problems/${code.lowercase().replace('_', '-')}",
            "title" to status.reasonPhrase,
            "status" to status.value(),
            "detail" to detail,
            "instance" to exchange.request.uri.rawPath,
            "code" to code,
            "requestId" to exchange.request.id,
            "traceId" to exchange.request.id,
            "retryable" to false,
        ))
    }
}
