package cc.monomer.control.observability

import cc.monomer.control.dashboard.DashboardProblemHandler
import cc.monomer.control.dashboard.DashboardRestProblem
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestCorrelationWebFilterTest {
    private val events = CopyOnWriteArrayList<ILoggingEvent>()
    private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = object : AppenderBase<ILoggingEvent>() {
        override fun append(event: ILoggingEvent) {
            event.prepareForDeferredProcessing()
            events.add(event)
        }
    }.also { it.context = root.loggerContext; it.start(); root.addAppender(it) }
    private val filter = RequestCorrelationTestSupport.filter()

    @AfterEach fun release() { root.detachAppender(appender); appender.stop() }

    @Test fun `annotation coroutine IO and Reactor logs use one server ID without request data`() {
        val client = WebTestClient.bindToController(CorrelationController()).webFilter<WebTestClient.ControllerSpec>(filter).build()
        val result = client.get().uri("/v2/workspaces/01942b04-6e00-7000-8000-000000000001/test/private@example.com?token=query-secret")
            .header("X-Request-Id", "untrusted-secret")
            .header("Authorization", "Bearer token-secret").cookie("session", "cookie-secret")
            .exchange().expectStatus().isBadRequest.expectBody().returnResult()
        val body = ObjectMapper().readTree(result.responseBody)
        val id = result.responseHeaders.getFirst("X-Request-Id")!!
        assertTrue(id.matches(Regex("srv_[0-9a-f-]{36}")))
        assertEquals(id, body.path("requestId").asText())
        assertEquals(id, body.path("traceId").asText())
        val requestLogs = events.filter { it.formattedMessage in setOf("controller", "io", "reactor") }
        assertEquals(3, requestLogs.size)
        assertTrue(requestLogs.all { it.mdcPropertyMap["request_id"] == id })
        val summaries = events.filter { it.formattedMessage == "http_request_failed" }
        assertEquals(1, summaries.size)
        val fields = summaries.single().fields()
        assertEquals(id, fields["request_id"])
        assertEquals("VALIDATION_FAILED", fields["code"])
        assertEquals("/v2/workspaces/{workspaceId}/test/{value}", fields["route"])
        assertEquals("01942b04-6e00-7000-8000-000000000001", fields["workspace_id"])
        assertEquals("user", fields["actor"])
        assertTrue((fields["elapsed_ms"] as Long) >= 0)
        assertSafe(events)
        assertNull(MDC.get("request_id"))
    }

    @Test fun `every 4xx and 5xx response logs exactly once with no success summary`() {
        for (status in listOf(200, 202, 302) + (400..599)) {
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/unknown/private@example.com?token=query-secret").body("body-secret"))
            filter.filter(exchange) { correlated ->
                correlated.response.statusCode = org.springframework.http.HttpStatusCode.valueOf(status)
                correlated.response.setComplete()
            }.block()
        }
        val summaries = events.filter { it.formattedMessage == "http_request_failed" }
        assertEquals(200, summaries.size)
        assertEquals((400..599).toSet(), summaries.map { it.fields()["status"] }.toSet())
        assertTrue(summaries.all { it.fields()["route"] == "<unmatched>" })
        assertSafe(summaries)
    }

    @Test fun `unhandled failures redact exception text and keep the header Problem and summary ID`() {
        val handler = RequestProblemExceptionHandler(ObjectMapper())
        for (failure in listOf(IllegalStateException("password-secret SELECT body-secret"), ResponseStatusException(HttpStatus.NOT_FOUND, "private@example.com"))) {
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/unknown"))
            filter.filter(exchange) { Mono.error(failure) }.onErrorResume { handler.handle(exchange, it) }.block()
            val body = ObjectMapper().readTree(exchange.response.bodyAsString.block())
            val id = exchange.response.headers.getFirst("X-Request-Id")
            assertEquals(id, body.path("requestId").asText())
            assertEquals(id, body.path("traceId").asText())
            assertEquals(id, events.last { it.formattedMessage == "http_request_failed" }.fields()["request_id"])
            val summary = events.last { it.formattedMessage == "http_request_failed" }
            if ((exchange.response.statusCode?.value() ?: 200) >= 500) {
                assertTrue((summary.fields()["error.stack_trace"] as String).contains("RequestCorrelationWebFilterTest"))
            } else {
                assertNull(summary.fields()["error.stack_trace"])
                assertNull(summary.throwableProxy)
            }
            assertFalse(body.toString().contains("password-secret"))
        }
        assertEquals(2, events.count { it.formattedMessage == "http_request_failed" })
        assertSafe(events)
    }

    @Test fun `concurrent functional coroutines cannot leak an ID to another request or thread`() {
        val started = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { index -> pool.submit<String> {
                val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test/$index"))
                filter.filter(exchange) { correlated ->
                    kotlinx.coroutines.reactor.mono(org.springframework.core.PropagationContextElement()) {
                        started.countDown()
                        withContext(Dispatchers.IO) { started.await() }
                        delay(5)
                        val id = MDC.get("request_id")
                        assertEquals(correlated.request.id, id)
                        RequestLogContext.problem("CONCURRENT_$index")
                        correlated.response.statusCode = HttpStatus.CONFLICT
                        correlated.response.setComplete().awaitSingleOrNull()
                    }.then()
                }.block()
                assertNull(MDC.get("request_id"))
                exchange.response.headers.getFirst("X-Request-Id")!!
            } }
            val ids = futures.map { it.get() }
            assertNotEquals(ids[0], ids[1])
            assertEquals(ids.toSet(), events.filter { it.formattedMessage == "http_request_failed" }.map { it.fields()["request_id"] }.toSet())
        } finally { pool.shutdownNow() }
    }

    @Test fun `GraphQL annotated coroutine keeps the HTTP ID through IO and Reactor`() {
        val context = org.springframework.context.support.GenericApplicationContext()
        context.beanFactory.registerSingleton("graphQlCorrelationController", GraphQlCorrelationController())
        context.refresh()
        try {
            val configurer = org.springframework.graphql.data.method.annotation.support.AnnotatedControllerConfigurer()
            configurer.setApplicationContext(context)
            configurer.afterPropertiesSet()
            val resolvers = mutableListOf<org.springframework.graphql.execution.DataFetcherExceptionResolver>(
                RequestGraphQlExceptionResolver(),
                cc.monomer.control.auth.inbound.graphql.InvalidCanonicalGraphqlInputExceptionResolver(),
            )
            org.springframework.core.annotation.AnnotationAwareOrderComparator.sort(resolvers)
            val source = org.springframework.graphql.execution.GraphQlSource.schemaResourceBuilder()
                .schemaResources(org.springframework.core.io.ByteArrayResource("type Query { probe: String! failure: String badCanonical(raw: String!): String }".toByteArray()))
                .exceptionResolvers(resolvers)
                .configureRuntimeWiring(configurer).build()
            val handler = org.springframework.graphql.server.webflux.GraphQlHttpHandler(
                org.springframework.graphql.server.WebGraphQlHandler.builder(
                    org.springframework.graphql.execution.DefaultExecutionGraphQlService(source),
                ).build(),
            )
            val router = org.springframework.web.reactive.function.server.RouterFunctions.route()
                .POST("/v2/graphql", handler::handleRequest).build()
            val client = WebTestClient.bindToRouterFunction(router)
                .webFilter<WebTestClient.RouterFunctionSpec>(filter).build()
            val result = client.post().uri("/v2/graphql").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(mapOf("query" to "{ probe }", "variables" to mapOf("secret" to "body-secret")))
                .exchange().expectStatus().isOk.expectBody().returnResult()
            val id = result.responseHeaders.getFirst("X-Request-Id")
            assertEquals(id, ObjectMapper().readTree(result.responseBody).at("/data/probe").asText())
            val logs = events.filter { it.formattedMessage.startsWith("graphql_") }
            assertEquals(3, logs.size)
            assertTrue(logs.all { it.mdcPropertyMap["request_id"] == id })
            assertEquals(0, events.count { it.formattedMessage == "http_request_failed" })
            assertSafe(logs)
            val failed = client.post().uri("/v2/graphql").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(mapOf("query" to "{ failure }", "variables" to mapOf("secret" to "body-secret")))
                .exchange().expectStatus().isOk.expectBody().returnResult()
            val failureId = failed.responseHeaders.getFirst("X-Request-Id")
            val body = ObjectMapper().readTree(failed.responseBody)
            assertEquals("INTERNAL_ERROR", body.at("/errors/0/extensions/classification").asText())
            val failureLog = events.single { it.formattedMessage == "graphql_data_fetch_failed" }
            assertEquals(failureId, failureLog.fields()["request_id"])
            assertTrue((failureLog.fields()["error.stack_trace"] as String).contains("GraphQlCorrelationController"))
            assertEquals(0, events.count { it.formattedMessage == "http_request_failed" })
            assertSafe(events)
            for (invalidId in listOf("not-a-uuid", "01942B04-6E00-7000-8000-000000000001")) {
                val invalid = client.post().uri("/v2/graphql").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(mapOf("query" to "query(\$raw: String!) { badCanonical(raw: \$raw) }", "variables" to mapOf("raw" to invalidId)))
                    .exchange().expectStatus().isOk.expectBody().returnResult()
                assertEquals("BAD_REQUEST", ObjectMapper().readTree(invalid.responseBody).at("/errors/0/extensions/classification").asText())
            }
            assertEquals(1, events.count { it.formattedMessage == "graphql_data_fetch_failed" })
        } finally { context.close() }
    }

    @Test fun `HTTP Flow collection preserves the request ID after suspension`() {
        val result = WebTestClient.bindToController(CorrelationController())
            .webFilter<WebTestClient.ControllerSpec>(filter).build().get().uri("/stream")
            .exchange().expectStatus().isOk.expectBody(String::class.java).returnResult()
        val id = result.responseHeaders.getFirst("X-Request-Id")
        assertEquals(id, result.responseBody)
        val logs = events.filter { it.formattedMessage.startsWith("flow_") }
        assertEquals(2, logs.size)
        assertTrue(logs.all { it.mdcPropertyMap["request_id"] == id })
        assertEquals(0, events.count { it.formattedMessage == "http_request_failed" })
    }

    @org.springframework.stereotype.Controller
    class GraphQlCorrelationController {
        @org.springframework.graphql.data.method.annotation.QueryMapping
        fun badCanonical(@org.springframework.graphql.data.method.annotation.Argument raw: String): String =
            cc.monomer.control.auth.inbound.graphql.parseCanonicalUuid(raw, "workspaceId").toString()

        @org.springframework.graphql.data.method.annotation.QueryMapping
        suspend fun failure(): String = withContext(Dispatchers.IO) {
            delay(2)
            throw IllegalStateException("password-secret SELECT body-secret private@example.com")
        }

        @org.springframework.graphql.data.method.annotation.QueryMapping
        suspend fun probe(): String {
            val logger = LoggerFactory.getLogger(GraphQlCorrelationController::class.java)
            logger.info("graphql_controller")
            withContext(Dispatchers.IO) { delay(2); logger.info("graphql_io") }
            Mono.just(1).publishOn(Schedulers.parallel()).map { logger.info("graphql_reactor"); it }.awaitSingle()
            return checkNotNull(MDC.get("request_id"))
        }
    }

    private fun ILoggingEvent.fields() = RequestLogTestSupport.fields(this)
    private fun assertSafe(records: List<ILoggingEvent>) {
        val text = records.joinToString { RequestLogTestSupport.encoded(it) }
        for (secret in listOf("private@example.com", "token-secret", "cookie-secret", "query-secret", "untrusted-secret", "password-secret", "body-secret")) {
            assertFalse(text.contains(secret), secret)
        }
    }

    @RestController
    class CorrelationController {
        @GetMapping("/stream")
        suspend fun stream(): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow {
            val logger = LoggerFactory.getLogger(CorrelationController::class.java)
            logger.info("flow_before")
            withContext(Dispatchers.IO) { delay(2); logger.info("flow_io") }
            emit(checkNotNull(MDC.get("request_id")))
        }.flowOn(org.springframework.core.PropagationContextElement())

        @GetMapping("/v2/workspaces/{workspaceId}/test/{value}")
        suspend fun problem(@PathVariable workspaceId: String, @PathVariable value: String, exchange: ServerWebExchange): ProblemDetail {
            val logger = LoggerFactory.getLogger(CorrelationController::class.java)
            logger.info("controller")
            withContext(Dispatchers.IO) { delay(2); logger.info("io") }
            Mono.just(1).publishOn(Schedulers.parallel()).map { logger.info("reactor"); it }.awaitSingle()
            return DashboardProblemHandler().handle(DashboardRestProblem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "body-secret", emptyMap()), exchange)
        }
    }
}
