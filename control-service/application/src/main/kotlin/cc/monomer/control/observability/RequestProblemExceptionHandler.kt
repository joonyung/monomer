package cc.monomer.control.observability

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.function.server.RouterFunctions
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

@Component
@Order(-2)
internal class RequestProblemExceptionHandler(private val mapper: ObjectMapper) : ErrorWebExceptionHandler {
    override fun handle(exchange: ServerWebExchange, failure: Throwable): Mono<Void> {
        if (exchange.response.isCommitted) {
            logger.atError()
                .addKeyValue("event", "http_response_stream_failed")
                .addCorrelationId("request_id", exchange.getAttribute<RequestLogContext>(RequestLogContext.KEY)?.requestId ?: exchange.request.id)
                .addKeyValue("method", exchange.request.method.name())
                .addKeyValue("route", exchange.getAttribute<Any>(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString()
                    ?: exchange.getAttribute<Any>(RouterFunctions.MATCHING_PATTERN_ATTRIBUTE)?.toString() ?: "<unmatched>")
                .addKeyValue("status", exchange.response.statusCode?.value() ?: 200)
                .setCause(failure)
                .log("http_response_stream_failed")
            return Mono.error(failure)
        }
        val status = (failure as? ResponseStatusException)?.statusCode ?: HttpStatus.INTERNAL_SERVER_ERROR
        val code = "HTTP_${status.value()}"
        val observation = exchange.getAttribute<RequestLogContext>(RequestLogContext.KEY)
        observation?.code = code
        observation?.errorType = failure.javaClass.name
        observation?.exception = failure
        val requestId = observation?.requestId ?: exchange.request.id
        val title = HttpStatus.resolve(status.value())?.reasonPhrase ?: "Request failed"
        val body = mapOf(
            "type" to "https://monomer.cc/problems/http-${status.value()}",
            "title" to title, "status" to status.value(), "detail" to title,
            "instance" to exchange.request.uri.rawPath, "code" to code,
            "requestId" to requestId, "traceId" to requestId, "retryable" to (status.value() >= 500),
        )
        exchange.response.statusCode = status
        if (failure is org.springframework.web.ErrorResponse) exchange.response.headers.addAll(failure.headers)
        exchange.response.headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
        return exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(mapper.writeValueAsBytes(body))))
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RequestProblemExceptionHandler::class.java)
    }
}
