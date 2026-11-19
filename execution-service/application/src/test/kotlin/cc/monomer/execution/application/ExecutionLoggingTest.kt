package cc.monomer.execution.application

import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.DriverOperation
import cc.monomer.execution.driver.spi.DriverOperationTiming
import cc.monomer.execution.driver.spi.DriverPhase
import cc.monomer.execution.driver.spi.DriverPhysicalResult
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.logging.ConsoleHandler
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExecutionLoggingTest {
    private val mapper = ObjectMapper()
    private val runId = "019f0000-0000-7000-8000-000000000001"
    private val workspaceId = "019f0000-0000-7000-8000-000000000002"

    @Test fun driverSummaryIsOneEcsJsonLineWithNumericDurationsAndNoRequestOrWarehouseData() {
        val record = captureTiming {
            val timing = DriverOperationTiming(ConnectorType.POSTGRESQL, DriverOperation.QUERY, runId, workspaceId)
            repeat(3) { timing.polled() }
            timing.cleanupFailed()
            timing.measure(DriverPhase.READ) { timing.measure(DriverPhase.UPLOAD) {} }
            timing.markFailure(DriverPhase.READ)
            timing.log(DriverPhysicalResult.Failed("QUERY_FAILED", "SELECT secret-value private@example.com password=secret"))
        }
        val line = ExecutionLogFormatter(true).format(record)
        val json = mapper.readTree(line)
        assertEquals(1, line.count { it == '\n' })
        assertEquals(record.instant.toString(), json.path("@timestamp").asText())
        assertEquals("INFO", json.path("log").path("level").asText())
        assertEquals(DriverOperationTiming::class.java.name, json.path("log").path("logger").asText())
        assertEquals("8.11", json.path("ecs").path("version").asText())
        assertEquals("driver_operation_timing", json.path("message").asText())
        assertEquals("driver_operation_timing", json.path("event").asText())
        assertEquals(runId, json.path("run_id").asText())
        assertEquals(workspaceId, json.path("workspace_id").asText())
        assertEquals("READ", json.path("failure_phase").asText())
        assertEquals("QUERY_FAILED", json.path("failure_code").asText())
        assertEquals(3, json.path("poll_count").asInt())
        assertEquals(1, json.path("cleanup_failure_count").asInt())
        for (field in listOf("total_ms", "poll_count", "cleanup_failure_count", "read_ms", "upload_ms")) {
            assertTrue(json.path(field).isIntegralNumber, field)
        }
        assertTrue(json.path("connect_ms").isNull)
        assertFalse(json.has("request_id"))
        assertFalse(json.has("error"))
        assertSafe(line)
    }

    @Test fun localTextRetainsTheSameSafeTimingFieldsAndRuntimeLogsNeverExposeTheirRawMessage() {
        val record = captureTiming {
            DriverOperationTiming(ConnectorType.BIGQUERY, DriverOperation.VALIDATE, runId, workspaceId)
                .log(DriverPhysicalResult.Succeeded(0uL, 0uL, null))
        }
        val line = ExecutionLogFormatter(false).format(record)
        assertTrue(line.contains(record.message))
        assertTrue(line.contains("run_id=$runId workspace_id=$workspaceId"))
        assertTrue(line.contains("operation=VALIDATE outcome=SUCCEEDED failure_phase=none failure_code=none"))
        assertTrue(line.contains("connect_ms=null"))
        assertEquals(1, line.count { it == '\n' })
        val unknown = LogRecord(Level.WARNING, "SELECT secret-value private@example.com password=secret").apply {
            loggerName = "vendor.driver"
            parameters = arrayOf("Bearer parameter-secret", "SELECT parameter_sql", "parameter@example.com", "password=parameter-secret")
            thrown = syntheticFailure()
        }
        val runtime = ExecutionLogFormatter(false).format(unknown)
        assertTrue(runtime.contains("execution_runtime_log error.type=java.lang.IllegalStateException"))
        val stackTrace = mapper.readTree(runtime.substringAfter(" error.stack_trace=").trimEnd()).asText()
        assertSafeStackTrace(stackTrace)
        assertEquals(1, runtime.count { it == '\n' })
        assertSafe(runtime)
    }

    @Test fun externalJulRecordsAreSafeWithTheSameEcsLevelLoggerAndErrorTypeNames() {
        for ((level, expected) in listOf(Level.SEVERE to "ERROR", Level.WARNING to "WARN", Level.INFO to "INFO", Level.CONFIG to "INFO", Level.FINE to "DEBUG", Level.FINER to "TRACE")) {
            val record = LogRecord(level, "SELECT secret-value private@example.com password=secret\n").apply {
                loggerName = "vendor.driver"
                parameters = arrayOf(mapOf("run_id" to "secret-value", "body" to "private@example.com"))
                thrown = syntheticFailure()
            }
            val line = ExecutionLogFormatter(true).format(record)
            val json = mapper.readTree(line)
            assertEquals(expected, json.path("log").path("level").asText())
            assertEquals("vendor.driver", json.path("log").path("logger").asText())
            assertEquals("execution_runtime_log", json.path("message").asText())
            assertEquals("java.lang.IllegalStateException", json.path("error").path("type").asText())
            assertFalse(json.path("error").has("message"))
            assertTrue(json.path("error").path("stack_trace").isTextual)
            assertSafeStackTrace(json.path("error").path("stack_trace").asText())
            assertFalse(json.has("run_id"))
            assertEquals(1, line.count { it == '\n' })
            assertSafe(line)
        }
    }

    @Test fun malformedStructuredPayloadCannotInjectWarehouseFieldsOrOverwriteTheEcsEnvelope() {
        val record = captureTiming {
            DriverOperationTiming(ConnectorType.POSTGRESQL, DriverOperation.QUERY, runId, workspaceId)
                .log(DriverPhysicalResult.Cancelled)
        }
        val fields = record.parameters.single() as Map<*, *>
        for (corrupt in listOf(fields + mapOf("message" to "secret-value"), fields + mapOf("run_id" to "private@example.com"), fields + mapOf("read_ms" to "SELECT secret-value"))) {
            record.parameters = arrayOf(corrupt)
            val line = ExecutionLogFormatter(true).format(record)
            assertEquals("execution_runtime_log", mapper.readTree(line).path("event").asText())
            assertSafe(line)
        }
    }

    @Test fun environmentSelectsEcsOrLocalTextAndUncaughtFailuresUseTheConfiguredSafeLogger() {
        val root = Logger.getLogger("")
        val originalHandlers = root.handlers.toList()
        val consoleFormatters = originalHandlers.filterIsInstance<ConsoleHandler>().associateWith { it.formatter }
        val previousUncaught = Thread.getDefaultUncaughtExceptionHandler()
        val failures = mutableListOf<LogRecord>()
        val capture = object : Handler() {
            override fun publish(record: LogRecord) { failures += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        root.addHandler(capture)
        try {
            configureExecutionLogging(mapOf("LOGGING_STRUCTURED_FORMAT_CONSOLE" to "ecs"))
            val ecs = root.handlers.filterIsInstance<ConsoleHandler>().first().formatter
            assertTrue(mapper.readTree(ecs.format(LogRecord(Level.INFO, "private@example.com"))).has("@timestamp"))
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), syntheticFailure())
            val failure = failures.single()
            assertEquals(Level.SEVERE, failure.level)
            val json = ecs.format(failure)
            val error = mapper.readTree(json).path("error")
            assertEquals("java.lang.IllegalStateException", error.path("type").asText())
            assertTrue(error.path("stack_trace").isTextual)
            assertSafeStackTrace(error.path("stack_trace").asText())
            assertEquals(1, json.count { it == '\n' })
            assertSafe(json)
            configureExecutionLogging(emptyMap())
            val localFormatter = root.handlers.filterIsInstance<ConsoleHandler>().first().formatter
            val text = localFormatter.format(LogRecord(Level.INFO, "private@example.com"))
            assertTrue(text.contains("execution_runtime_log"))
            assertFalse(text.startsWith("{"))
            assertSafe(text)
            val localFailure = localFormatter.format(failure)
            assertSafeStackTrace(mapper.readTree(localFailure.substringAfter(" error.stack_trace=").trimEnd()).asText())
            assertEquals(1, localFailure.count { it == '\n' })
            assertSafe(localFailure)
            val unsupported = assertFailsWith<IllegalArgumentException> { configureExecutionLogging(mapOf("LOGGING_STRUCTURED_FORMAT_CONSOLE" to "secret-value")) }
            assertSafe(unsupported.message.orEmpty())
        } finally {
            root.removeHandler(capture)
            root.handlers.filter { it !in originalHandlers }.forEach { root.removeHandler(it); it.close() }
            consoleFormatters.forEach { (handler, formatter) -> handler.formatter = formatter }
            Thread.setDefaultUncaughtExceptionHandler(previousUncaught)
        }
    }

    private fun captureTiming(block: () -> Unit): LogRecord {
        val records = mutableListOf<LogRecord>()
        val logger = Logger.getLogger(DriverOperationTiming::class.java.name)
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { records += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try { block(); return records.single().also { assertNull(it.thrown) } }
        finally { logger.removeHandler(handler) }
    }

    private fun assertSafe(text: String) {
        for (secret in listOf("SELECT", "secret-value", "private@example.com", "password=", "Bearer", "outer-secret", "cause-secret", "suppressed-secret", "parameter-secret", "parameter@example.com", "outer_sql", "cause_sql", "suppressed_sql", "parameter_sql")) {
            assertFalse(text.contains(secret), secret)
        }
    }

    private fun assertSafeStackTrace(trace: String) {
        assertTrue(trace.contains("\tat sample.Runtime.execute(File.kt:17)"))
        assertTrue(trace.contains("Caused by: java.lang.IllegalArgumentException\n\tat sample.Cause.read(Cause.kt:23)"))
        assertTrue(trace.contains("\tSuppressed: java.lang.UnsupportedOperationException"))
        assertFalse(trace.contains("sample.Suppressed"))
        assertFalse(trace.contains("Suppressed.kt"))
        assertFalse(trace.endsWith('\n'))
        assertSafe(trace)
    }

    private fun syntheticFailure(): Throwable {
        val cause = IllegalArgumentException("Bearer cause-secret SELECT cause_sql private@example.com password=cause-secret").apply {
            stackTrace = arrayOf(StackTraceElement("sample.Cause", "read", "Cause.kt", 23))
        }
        return IllegalStateException("Bearer outer-secret SELECT outer_sql private@example.com password=outer-secret", cause).apply {
            stackTrace = arrayOf(StackTraceElement("sample.Runtime", "execute", "File.kt", 17))
            addSuppressed(UnsupportedOperationException("Bearer suppressed-secret SELECT suppressed_sql private@example.com password=suppressed-secret").apply {
                stackTrace = arrayOf(StackTraceElement("sample.Suppressed", "hidden", "Suppressed.kt", 99))
            })
        }
    }
}
