package cc.monomer.control.observability

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.classic.spi.ILoggingEvent
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.core.env.Environment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageFreeLoggingConfigurationTest {
    @Test fun `actual console configuration uses one safe printer for ECS and local exceptions`() {
        val failure = IllegalStateException("password=stack-sentinel SELECT private@example.test", NullPointerException("token=cause-sentinel")).apply {
            stackTrace = arrayOf(StackTraceElement("cc.monomer.Example", "launch", "Example.kt", 42))
            cause!!.stackTrace = arrayOf(StackTraceElement("cc.monomer.Example", "read", "Example.kt", 17))
            addSuppressed(IllegalArgumentException("cookie=suppressed-sentinel"))
        }
        for (format in listOf("", "ecs")) {
            val context = LoggerContext().apply {
                mdcAdapter = MDC.getMDCAdapter()
                putObject(Environment::class.java.name, RequestLogTestSupport.environment())
                putProperty("CONSOLE_LOG_STRUCTURED_FORMAT", format)
                putProperty("CONSOLE_LOG_PATTERN", "%msg%n%safeEx")
            }
            val bytes = ByteArrayOutputStream()
            try {
                JoranConfigurator().apply { this.context = context }
                    .doConfigure(javaClass.getResource("/logback-spring.xml"))
                assertFalse(context.statusManager.copyOfStatusList.any { it.level == 2 }, context.statusManager.copyOfStatusList.toString())
                val appender = context.getLogger("ROOT").getAppender("CONSOLE") as ConsoleAppender<ILoggingEvent>
                appender.outputStream = bytes
                context.getLogger("cc.monomer.logging.example").atError().setCause(failure)
                    .addKeyValue("event", "safe_stack_probe").log("safe_stack_probe")
                val output = bytes.toString(Charsets.UTF_8)
                for (sentinel in listOf("stack-sentinel", "cause-sentinel", "suppressed-sentinel", "SELECT", "private@example.test")) {
                    assertFalse(output.contains(sentinel), "$format leaked $sentinel")
                }
                val trace = if (format == "ecs") {
                    assertEquals(1, output.count { it == '\n' })
                    val json = ObjectMapper().readTree(output)
                    assertFalse(json.path("error").has("message"))
                    assertEquals("java.lang.IllegalStateException", json.path("error").path("type").asText())
                    assertTrue(json.path("error").path("stack_trace").isTextual)
                    json.path("error").path("stack_trace").asText()
                } else output
                assertTrue(trace.contains("at cc.monomer.Example.launch(Example.kt:42)"))
                assertTrue(trace.contains("Caused by: java.lang.NullPointerException"))
                assertTrue(trace.contains("at cc.monomer.Example.read(Example.kt:17)"))
                assertTrue(trace.contains("Suppressed: java.lang.IllegalArgumentException"))
            } finally { context.stop() }
        }
    }
}
