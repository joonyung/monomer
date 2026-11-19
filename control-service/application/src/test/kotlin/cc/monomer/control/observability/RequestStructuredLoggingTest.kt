package cc.monomer.control.observability

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.boot.logging.logback.StructuredLogEncoder
import org.springframework.core.env.Environment
import org.springframework.mock.env.MockEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestStructuredLoggingTest {
    @Test fun `Boot ECS encodes MDC and fluent fields as one JSON line with numeric durations`() {
        val context = LoggerContext()
        context.mdcAdapter = MDC.getMDCAdapter()
        context.putObject(Environment::class.java.name, RequestLogTestSupport.environment())
        val encoder = StructuredLogEncoder().apply { this.context = context; setFormat("ecs"); start() }
        var output = ""
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) { output = encoder.encode(event).toString(Charsets.UTF_8) }
        }.apply { this.context = context; start() }
        try {
            val logger = context.getLogger("cc.monomer.control.observability.sample")
            logger.addAppender(appender)
            MDC.put("request_id", "srv_01942b04-6e00-7000-8000-000000000001")
            MDC.put("run_id", "01942b04-6e00-7000-8000-000000000002")
            logger.atWarn().addKeyValue("event", "http_request_failed")
                .addCorrelationId("request_id", "srv_01942b04-6e00-7000-8000-000000000001")
                .addCorrelationId("run_id", "01942b04-6e00-7000-8000-000000000002")
                .addKeyValue("status", 403).addKeyValue("code", "FORBIDDEN")
                .addKeyValue("route", "/v2/workspaces/{workspaceId}/agent/invoke")
                .addKeyValue("elapsed_ms", 12L).addKeyValue("error.type", "java.lang.IllegalStateException")
                .log("http_request_failed")
            assertEquals(1, output.count { it == '\n' })
            val parsed = ObjectMapper().readTree(output)
            assertEquals("srv_01942b04-6e00-7000-8000-000000000001", parsed.path("request_id").asText())
            assertEquals("01942b04-6e00-7000-8000-000000000002", parsed.path("run_id").asText())
            assertEquals("WARN", parsed.path("log").path("level").asText())
            assertEquals("cc.monomer.control.observability.sample", parsed.path("log").path("logger").asText())
            assertTrue(parsed.path("@timestamp").isTextual)
            assertEquals("8.11", parsed.path("ecs").path("version").asText())
            assertEquals("http_request_failed", parsed.path("message").asText())
            assertEquals("java.lang.IllegalStateException", parsed.path("error").path("type").asText())
            assertTrue(parsed.path("elapsed_ms").isIntegralNumber)
            assertEquals(403, parsed.path("status").asInt())
            assertFalse(output.contains("stack_trace"))
            MDC.clear()
            logger.atInfo().addKeyValue("event", "execution_run_accepted")
                .addCorrelationId("request_id", "srv_after-commit")
                .addCorrelationId("run_id", "01942b04-6e00-7000-8000-000000000002")
                .log("execution_run_accepted")
            val outsideContext = ObjectMapper().readTree(output)
            assertEquals("srv_after-commit", outsideContext.path("request_id").asText())
            assertEquals("01942b04-6e00-7000-8000-000000000002", outsideContext.path("run_id").asText())
        } finally { MDC.remove("request_id"); MDC.remove("run_id"); encoder.stop(); context.stop() }
    }
}
