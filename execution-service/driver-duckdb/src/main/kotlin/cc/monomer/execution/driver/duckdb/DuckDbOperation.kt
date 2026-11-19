package cc.monomer.execution.driver.duckdb

import cc.monomer.execution.driver.spi.DriverOperationTiming
import cc.monomer.execution.driver.spi.DriverPhase
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.duckdb.DuckDBConnection
import org.duckdb.MonomerDuckDbInterrupt

internal suspend fun <T> duckDbOperation(datasets: DuckDbSampleDatasets, dataset: String, deadlineEpochMillis: Long, dispatcher: CoroutineDispatcher, clock: Clock, timing: DriverOperationTiming, operation: suspend (DuckDBConnection) -> T): T {
    val durationMillis = minOf(DUCKDB_EXECUTION_LIMIT_MILLIS, deadlineEpochMillis - clock.millis()).coerceAtLeast(1)
    val activeInterrupt = AtomicReference<MonomerDuckDbInterrupt?>()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val finished = CompletableDeferred<Unit>()
    var workerStarted = false
    try {
        return withTimeout(durationMillis) {
            suspendCancellableCoroutine { continuation ->
                workerStarted = true
                continuation.invokeOnCancellation {
                    timing.interrupted()
                    activeInterrupt.get()?.interrupt()
                    scope.cancel()
                }
                val worker = scope.launch {
                    var interrupt: MonomerDuckDbInterrupt? = null
                    try {
                        val connection = timing.measure(DriverPhase.CONNECT) { datasets.duplicate(dataset) }
                        interrupt = MonomerDuckDbInterrupt(connection)
                        activeInterrupt.set(interrupt)
                        currentCoroutineContext().ensureActive()
                        if (clock.millis() >= deadlineEpochMillis) throw DuckDbDeadlineExceeded()
                        val value = operation(connection)
                        currentCoroutineContext().ensureActive()
                        if (continuation.isActive) continuation.resumeWith(Result.success(value))
                    } catch (failure: Throwable) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
                    } finally {
                        timing.measure(DriverPhase.CLEANUP) {
                            activeInterrupt.set(null)
                            runCatching { interrupt?.close() }.onFailure { timing.cleanupFailed() }
                        }
                    }
                }
                worker.invokeOnCompletion { finished.complete(Unit) }
            }
        }
    } finally {
        withContext(NonCancellable) { if (workerStarted) finished.await() }
        scope.cancel()
    }
}

internal class DuckDbDeadlineExceeded : RuntimeException()
internal const val DUCKDB_EXECUTION_LIMIT_MILLIS = 60_000L
