package cc.monomer.execution.application

import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.DriverOperation
import cc.monomer.execution.driver.spi.DriverOperationTiming
import cc.monomer.execution.driver.spi.DriverPhase
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Locale
import java.util.logging.ConsoleHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

internal fun configureExecutionLogging(environment: Map<String, String>) {
    val format = environment["LOGGING_STRUCTURED_FORMAT_CONSOLE"].orEmpty()
    require(format.isEmpty() || format == "ecs") { "Execution console logging supports ecs or the local text default" }
    val formatter = ExecutionLogFormatter(format == "ecs")
    val root = Logger.getLogger("")
    val consoleHandlers = root.handlers.filterIsInstance<ConsoleHandler>()
    if (consoleHandlers.isEmpty()) root.addHandler(ConsoleHandler().apply { this.formatter = formatter })
    else consoleHandlers.forEach { it.formatter = formatter }
    Thread.setDefaultUncaughtExceptionHandler { _, failure ->
        Logger.getLogger("cc.monomer.execution.application.ExecutionServiceMain")
            .log(Level.SEVERE, "execution_runtime_log", failure)
    }
}

internal class ExecutionLogFormatter(private val structured: Boolean) : Formatter() {
    private val mapper = ObjectMapper()

    override fun format(record: LogRecord): String {
        val fields = timingFields(record)
        val event = if (fields != null) "driver_operation_timing" else "execution_runtime_log"
        val level = when {
            record.level.intValue() >= Level.SEVERE.intValue() -> "ERROR"
            record.level.intValue() >= Level.WARNING.intValue() -> "WARN"
            record.level.intValue() >= Level.CONFIG.intValue() -> "INFO"
            record.level.intValue() >= Level.FINE.intValue() -> "DEBUG"
            else -> "TRACE"
        }
        if (!structured) {
            val message = if (fields == null) event else event + " " + fields.entries.joinToString(" ") { (name, value) -> "$name=${value ?: "null"}" }
            val error = record.thrown?.let {
                " error.type=${it.javaClass.name} error.stack_trace=${mapper.writeValueAsString(messageFreeStackTrace(it))}"
            }.orEmpty()
            return "${record.instant} $level ${record.loggerName ?: "unknown"}: $message$error\n"
        }
        val entry = linkedMapOf<String, Any?>(
            "@timestamp" to record.instant.toString(),
            "log" to mapOf("level" to level, "logger" to (record.loggerName ?: "unknown")),
            "message" to event,
            "event" to event,
            "ecs" to mapOf("version" to "8.11"),
        )
        if (fields != null) entry.putAll(fields)
        record.thrown?.let { entry["error"] = mapOf("type" to it.javaClass.name, "stack_trace" to messageFreeStackTrace(it)) }
        return mapper.writeValueAsString(entry) + "\n"
    }

    private fun timingFields(record: LogRecord): Map<String, Any?>? {
        if (record.loggerName != DriverOperationTiming::class.java.name || record.message?.startsWith("driver_operation_timing ") != true) return null
        val fields = record.parameters?.singleOrNull() as? Map<*, *> ?: return null
        if (fields.keys != TIMING_FIELDS || !fields.all { (name, value) -> validField(name as String, value) }) return null
        return TIMING_FIELDS.associateWith { fields[it] }
    }

    private fun validField(name: String, value: Any?): Boolean = when (name) {
        "run_id", "workspace_id" -> value is String && (value == "none" || UUID.matches(value))
        "driver" -> value is String && (value == "UNKNOWN" || ConnectorType.entries.any { it.name == value })
        "operation" -> value is String && DriverOperation.entries.any { it.name == value }
        "outcome" -> value in listOf("SUCCEEDED", "FAILED", "CANCELLED")
        "failure_phase" -> value is String && (value == "none" || DriverPhase.entries.any { it.name == value })
        "failure_code" -> value is String && (value == "none" || FAILURE_CODE.matches(value))
        "poll_count", "cleanup_failure_count", "total_ms" -> value is Long && value >= 0
        else -> value == null || (value is Long && value >= 0)
    }

    companion object {
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val FAILURE_CODE = Regex("[A-Z][A-Z0-9_]{0,95}")
        private val TIMING_FIELDS = linkedSetOf("run_id", "workspace_id", "driver", "operation", "outcome", "failure_phase", "failure_code", "total_ms", "poll_count", "cleanup_failure_count").apply {
            DriverPhase.entries.forEach { add("${it.name.lowercase(Locale.ROOT)}_ms") }
        }
    }
}
