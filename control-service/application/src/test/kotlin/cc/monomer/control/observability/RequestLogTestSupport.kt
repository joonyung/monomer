package cc.monomer.control.observability

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import org.slf4j.MDC
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.logging.logback.StructuredLogEncoder
import org.springframework.core.env.Environment
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment

object RequestLogTestSupport {
    fun environment(): MockEnvironment = MockEnvironment().apply {
        YamlPropertySourceLoader().load("logging", ClassPathResource("application.yaml"))
            .forEach { propertySources.addLast(it) }
    }

    fun fields(event: ILoggingEvent): Map<String, Any?> {
        val proxy = event.throwableProxy as? ThrowableProxy
        return event.mdcPropertyMap + event.keyValuePairs.orEmpty().associate { it.key to it.value } +
            if (proxy == null) emptyMap() else mapOf("error.type" to proxy.className, "error.stack_trace" to messageFreeStackTrace(proxy.throwable))
    }

    fun encoded(event: ILoggingEvent): String {
        val context = LoggerContext().apply {
            mdcAdapter = MDC.getMDCAdapter()
            putObject(Environment::class.java.name, environment())
        }
        val encoder = StructuredLogEncoder().apply { this.context = context; setFormat("ecs"); start() }
        try { return encoder.encode(event).toString(Charsets.UTF_8) }
        finally { encoder.stop(); context.stop() }
    }
}
