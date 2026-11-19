package cc.monomer.control.observability

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter
import org.springframework.web.server.adapter.WebHttpHandlerBuilder
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import reactor.netty.http.server.HttpServer
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestStreamFailureLoggingTest {
    @Test fun `committed stream failure aborts the connection and logs safe fields instead of raw framework errors`() {
        val properties = YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml")).single()
        val quietLoggers = listOf(
            "org.springframework.web.server.adapter.HttpWebHandlerAdapter",
            "reactor.netty.http.server.HttpServerOperations",
            "org.springframework.graphql.execution.ExceptionResolversExceptionHandler",
        ).map { name ->
            assertEquals("OFF", properties.getProperty("logging.level.$name"))
            LoggerFactory.getLogger(name) as Logger
        }
        val previousLevels = quietLoggers.map { it.level }
        val events = CopyOnWriteArrayList<ILoggingEvent>()
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) { event.prepareForDeferredProcessing(); events.add(event) }
        }.also { it.context = root.loggerContext; it.start(); root.addAppender(it) }
        quietLoggers.forEach { it.level = Level.OFF }
        try {
            val handler = WebHttpHandlerBuilder.webHandler { exchange ->
                exchange.response.writeAndFlushWith(Flux.concat(
                    Mono.just(Mono.just(exchange.response.bufferFactory().wrap("first chunk".toByteArray()))),
                    Mono.delay(Duration.ofMillis(50)).flatMap {
                        Mono.error<Mono<org.springframework.core.io.buffer.DataBuffer>>(
                            IllegalStateException("password-secret SELECT body-secret private@example.com"),
                        )
                    },
                ))
            }.filter(RequestCorrelationTestSupport.filter())
                .exceptionHandler(RequestProblemExceptionHandler(ObjectMapper())).build()
            val server = HttpServer.create().host("127.0.0.1").port(0)
                .handle(ReactorHttpHandlerAdapter(handler)).bindNow()
            try {
                var requestId: String? = null
                val chunks = CopyOnWriteArrayList<String>()
                assertFails {
                    HttpClient.create().get()
                        .uri("http://127.0.0.1:${server.port()}/private@example.com?token=query-secret")
                        .response { response, body ->
                            assertEquals(200, response.status().code())
                            requestId = response.responseHeaders().get("X-Request-Id")
                            body.asString().doOnNext { chunks.add(it) }
                        }.blockLast(Duration.ofSeconds(5))
                }
                assertTrue(chunks.joinToString("").contains("first chunk"))
                assertNotNull(requestId)
                val summary = events.single { it.formattedMessage == "http_response_stream_failed" }
                val fields = RequestLogTestSupport.fields(summary)
                assertEquals(requestId, fields["request_id"])
                assertEquals("<unmatched>", fields["route"])
                assertEquals(200, fields["status"])
                assertEquals("java.lang.IllegalStateException", fields["error.type"])
                assertTrue((fields["error.stack_trace"] as String).contains("RequestStreamFailureLoggingTest"))
                assertEquals(0, events.count { it.formattedMessage == "http_request_failed" })
                val output = events.joinToString { RequestLogTestSupport.encoded(it) }
                for (secret in listOf("password-secret", "body-secret", "private@example.com", "query-secret")) {
                    assertFalse(output.contains(secret), secret)
                }
            } finally { server.disposeNow() }
        } finally {
            quietLoggers.zip(previousLevels).forEach { (logger, level) -> logger.level = level }
            root.detachAppender(appender)
            appender.stop()
        }
    }
}
