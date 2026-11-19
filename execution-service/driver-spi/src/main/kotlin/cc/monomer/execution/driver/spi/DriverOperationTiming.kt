package cc.monomer.execution.driver.spi

import java.util.logging.Level
import java.util.logging.Logger

enum class DriverOperation { QUERY, VALIDATE }

enum class DriverPhase {
    PREPARE, CREDENTIAL, CONNECT, SESSION_SETUP, VALIDATE, EXECUTE, WAIT, READ, UPLOAD, DELIVERY, CLEANUP,
}

class DriverOperationTiming(
    private val connectorType: ConnectorType?,
    private val operation: DriverOperation,
    runId: String?,
    workspaceId: String?,
) {
    private val runId = identifier(runId)
    private val workspaceId = identifier(workspaceId)
    private val startedNanos = System.nanoTime()
    private val phaseNanos = mutableMapOf<DriverPhase, Long>()
    private val activeMeasurements = mutableListOf<Measurement>()
    private var failedPhase: DriverPhase? = null
    private var lastFailure: Throwable? = null
    private var lastPhase = DriverPhase.PREPARE
    private var pollCount = 0L
    private var cleanupFailureCount = 0L
    private var logged = false

    class Measurement internal constructor(
        internal val phase: DriverPhase,
        internal val startedNanos: Long,
        internal val parent: Measurement?,
    ) {
        internal var childNanos = 0L
    }

    @Synchronized
    fun begin(phase: DriverPhase): Measurement {
        if (phase != DriverPhase.CLEANUP) lastPhase = phase
        return Measurement(phase, System.nanoTime(), activeMeasurements.lastOrNull()).also(activeMeasurements::add)
    }

    @Synchronized
    fun finish(measurement: Measurement) {
        val elapsed = System.nanoTime() - measurement.startedNanos
        phaseNanos[measurement.phase] = (phaseNanos[measurement.phase] ?: 0L) + (elapsed - measurement.childNanos).coerceAtLeast(0)
        measurement.parent?.let { it.childNanos += elapsed }
        activeMeasurements.remove(measurement)
    }

    @Synchronized
    fun failed(phase: DriverPhase, failure: Throwable) {
        if (failedPhase != null && activeMeasurements.any { it.phase == DriverPhase.CLEANUP }) return
        if (generateSequence(failure) { it.cause }.take(16).none { it === lastFailure }) {
            failedPhase = phase
            lastFailure = failure
        }
    }

    @Synchronized
    fun markFailure(phase: DriverPhase) {
        failedPhase = phase
    }

    @Synchronized
    fun interrupted() {
        if (failedPhase == null) failedPhase = activeMeasurements.lastOrNull()?.phase ?: lastPhase
    }

    @Synchronized
    fun cleanupFailed() { cleanupFailureCount += 1 }

    @Synchronized
    fun polled() { pollCount += 1 }

    inline fun <T> measure(phase: DriverPhase, block: () -> T): T {
        val measurement = begin(phase)
        return try {
            block()
        } catch (failure: Throwable) {
            failed(phase, failure)
            throw failure
        } finally {
            finish(measurement)
        }
    }

    @Synchronized
    fun log(result: DriverPhysicalResult) {
        if (logged) return
        logged = true
        val now = System.nanoTime()
        val durations = phaseNanos.toMutableMap()
        activeMeasurements.forEach { measurement ->
            val activeChildNanos = activeMeasurements.filter { it.parent === measurement }.sumOf { now - it.startedNanos }
            val elapsed = (now - measurement.startedNanos - measurement.childNanos - activeChildNanos).coerceAtLeast(0)
            durations[measurement.phase] = (durations[measurement.phase] ?: 0L) + elapsed
        }
        val outcome = when (result) {
            is DriverPhysicalResult.Succeeded -> "SUCCEEDED"
            is DriverPhysicalResult.Failed -> "FAILED"
            DriverPhysicalResult.Cancelled -> "CANCELLED"
        }
        val failureCode = when (result) {
            is DriverPhysicalResult.Failed -> result.failureCode.takeIf { FAILURE_CODE.matches(it) } ?: "UNCLASSIFIED_FAILURE"
            DriverPhysicalResult.Cancelled -> "CANCELLED"
            else -> "none"
        }
        val failurePhase = if (result is DriverPhysicalResult.Succeeded) "none" else (failedPhase ?: activeMeasurements.lastOrNull()?.phase ?: lastPhase).name
        val fields: Map<String, Any?> = buildMap {
            put("run_id", runId)
            put("workspace_id", workspaceId)
            put("driver", connectorType?.name ?: "UNKNOWN")
            put("operation", operation.name)
            put("outcome", outcome)
            put("failure_phase", failurePhase)
            put("failure_code", failureCode)
            put("total_ms", (now - startedNanos) / 1_000_000)
            put("poll_count", pollCount)
            put("cleanup_failure_count", cleanupFailureCount)
            DriverPhase.entries.forEach { phase ->
                put("${phase.name.lowercase(java.util.Locale.ROOT)}_ms", durations[phase]?.div(1_000_000))
            }
        }
        val originalText = "driver_operation_timing " + fields.entries.joinToString(" ") { (name, value) -> "$name=${value ?: "null"}" }
        LOGGER.log(Level.INFO, originalText, arrayOf(fields))
    }

    private companion object {
        val LOGGER: Logger = Logger.getLogger(DriverOperationTiming::class.java.name)
        val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val FAILURE_CODE = Regex("[A-Z][A-Z0-9_]{0,95}")
        fun identifier(value: String?): String = value?.takeIf { UUID.matches(it) }?.lowercase(java.util.Locale.ROOT) ?: "none"
    }
}
