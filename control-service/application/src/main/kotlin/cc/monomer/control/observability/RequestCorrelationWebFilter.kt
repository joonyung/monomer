package cc.monomer.control.observability

import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.stereotype.Component
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
internal class RequestCorrelationWebFilter(
    private val handlerMappings: ObjectProvider<RequestMappingHandlerMapping>,
) : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> = Mono.defer {
        val requestId = "srv_${UUID.randomUUID()}"
        val path = exchange.request.path.value()
        val actor = if (path == "/mcp" || path.contains("/agent/") || path.startsWith("/v2/agent/")) "agent" else "user"
        val observation = RequestLogContext(requestId, actor)
        exchange.attributes[RequestLogContext.KEY] = observation
        val request = object : ServerHttpRequestDecorator(exchange.request) {
            override fun getId(): String = requestId
        }
        val correlatedExchange = exchange.mutate().request(request).build()
        val startedAt = System.nanoTime()
        exchange.response.headers.set(REQUEST_ID_HEADER, requestId)
        exchange.response.beforeCommit {
            exchange.response.headers.set(REQUEST_ID_HEADER, requestId)
            val status = exchange.response.statusCode?.value() ?: 200
            if (status in 400..599) {
                val record = (if (status >= 500) logger.atError() else logger.atWarn())
                    .addKeyValue("event", "http_request_failed")
                    .addCorrelationId("request_id", requestId)
                    .addKeyValue("method", request.method.name())
                    .addKeyValue("route", route(correlatedExchange))
                    .addKeyValue("status", status)
                    .addKeyValue("code", observation.code ?: "HTTP_$status")
                    .addKeyValue("actor", actor)
                    .addKeyValue("elapsed_ms", (System.nanoTime() - startedAt) / 1_000_000)
                workspaceId(path)?.let { record.addKeyValue("workspace_id", it) }
                observation.clientId?.let { record.addKeyValue("client_id", it) }
                if (status >= 500 && observation.exception != null) record.setCause(observation.exception)
                else observation.errorType?.let { record.addKeyValue("error.type", it) }
                record.log("http_request_failed")
            }
            Mono.empty()
        }
        chain.filter(correlatedExchange).contextWrite { it.put(RequestLogContext.KEY, observation) }
    }

    private fun route(exchange: ServerWebExchange): String {
        exchange.getAttribute<Any>(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.let { return it.toString() }
        exchange.getAttribute<Any>(RouterFunctions.MATCHING_PATTERN_ATTRIBUTE)?.let { return it.toString() }
        handlerMappings.orderedStream().toList().forEach { mapping ->
            mapping.handlerMethods.keys.forEach { info ->
                info.patternsCondition.patterns.firstOrNull { it.matches(exchange.request.path.pathWithinApplication()) }
                    ?.let { return it.patternString }
            }
        }
        return if (exchange.request.path.value() == "/mcp") "/mcp" else "<unmatched>"
    }

    private fun workspaceId(path: String): String? = WORKSPACE_PATH.find(path)?.groupValues?.get(1)
        ?.let { runCatching { UUID.fromString(it).toString() }.getOrNull() }

    companion object {
        const val REQUEST_ID_HEADER = "X-Request-Id"
        private val WORKSPACE_PATH = Regex("/workspaces/([^/]+)(?:/|$)")
        private val logger = LoggerFactory.getLogger(RequestCorrelationWebFilter::class.java)
    }
}
