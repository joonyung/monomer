package cc.monomer.control.dashboard

import cc.monomer.dashboard.api.DashboardScheduleOwnerPort
import kotlinx.coroutines.*
import org.springframework.context.SmartLifecycle
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

class DashboardScheduleLifecycle(private val owner: DashboardScheduleOwnerPort) : SmartLifecycle {
    private val job = AtomicReference<Job?>()
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun start() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val runner = scope.launch(start = CoroutineStart.LAZY) {
            while (isActive) {
                try {
                    val result = owner.tick()
                    if (result.candidates > 0 || result.failed > 0 || result.truncated) {
                        (if (result.failed > 0 || result.truncated) logger.atWarn() else logger.atInfo())
                            .addKeyValue("event", "dashboard_schedule_tick")
                            .addKeyValue("candidates", result.candidates)
                            .addKeyValue("processed", result.processed)
                            .addKeyValue("failed", result.failed)
                            .addKeyValue("truncated", result.truncated)
                            .addKeyValue("oldest_candidate_delay_ms", result.oldestCandidateDelayMillis)
                            .log("dashboard_schedule_tick")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    logger.atWarn().addKeyValue("event", "dashboard_schedule_tick_failed")
                        .setCause(failure).log("dashboard_schedule_tick_failed")
                }
                delay(5_000)
            }
        }
        if (job.compareAndSet(null, runner)) runner.start() else { runner.cancel(); scope.cancel() }
    }

    override fun stop() {
        job.getAndSet(null)?.let { runner ->
            runner.cancel()
            runBlocking { withTimeoutOrNull(6_000) { runner.join() } }
        }
    }
    override fun isRunning(): Boolean = job.get()?.isActive == true
    override fun isAutoStartup(): Boolean = true
    override fun getPhase(): Int = Int.MAX_VALUE - 190
}
