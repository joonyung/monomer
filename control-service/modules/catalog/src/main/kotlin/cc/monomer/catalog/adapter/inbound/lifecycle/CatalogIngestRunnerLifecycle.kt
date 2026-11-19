package cc.monomer.catalog.adapter.inbound.lifecycle

import cc.monomer.catalog.application.CatalogIngestRepository
import cc.monomer.catalog.application.CatalogListRefreshRepository
import cc.monomer.catalog.application.CatalogTransactionPort
import cc.monomer.catalog.application.ingest.CatalogIngestApplicationService
import cc.monomer.catalog.application.ingest.CatalogListRefreshApplicationService
import java.time.Duration
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.springframework.context.SmartLifecycle

/**
 * Small process-local poller for the durable Catalog job table. Discovery and stale selection are
 * owner queries; this class never reads Catalog tables directly.
 */
class CatalogIngestRunnerLifecycle(
    private val service: CatalogIngestApplicationService,
    private val listRefreshService: CatalogListRefreshApplicationService,
    private val ingestRepository: CatalogIngestRepository,
    private val listRefreshRepository: CatalogListRefreshRepository,
    private val transactionPort: CatalogTransactionPort,
    private val clock: Clock,
    private val recoveryInterval: Duration,
    private val orphanTimeout: Duration,
) : SmartLifecycle {
    private val executorReference = AtomicReference<ScheduledExecutorService?>()
    private val cycleInProgress = AtomicBoolean(false)

    override fun start() {
        if (executorReference.get() != null) return
        val executor = Executors.newSingleThreadScheduledExecutor { command ->
            Thread(command, "monomer-catalog-ingest").also { it.isDaemon = true }
        }
        if (!executorReference.compareAndSet(null, executor)) {
            executor.shutdownNow()
            return
        }
        executor.scheduleWithFixedDelay(
            ::runCycleSafely,
            0,
            recoveryInterval.toMillis(),
            TimeUnit.MILLISECONDS,
        )
    }

    override fun stop() {
        executorReference.getAndSet(null)?.let { executor ->
            executor.shutdown()
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        }
    }

    override fun isRunning(): Boolean =
        executorReference.get()?.let { !it.isShutdown && !it.isTerminated } == true

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MAX_VALUE - 250

    internal fun runCycleSafely() {
        if (!cycleInProgress.compareAndSet(false, true)) return
        try {
            runBlocking {
                val now = clock.instant()
                val staleIngestJobs = transactionPort.inReadOnlyTransaction {
                    ingestRepository.findStaleIngestJobs(
                        heartbeatBefore = now.minus(orphanTimeout),
                        maximumJobs = MAXIMUM_JOBS_PER_CYCLE,
                    )
                }
                staleIngestJobs.forEach { job -> service.terminalizeStale(job.id, orphanTimeout) }
                val staleListRefreshJobs = transactionPort.inReadOnlyTransaction {
                    listRefreshRepository.findStaleListRefreshJobs(
                        heartbeatBefore = now.minus(orphanTimeout),
                        maximumJobs = MAXIMUM_JOBS_PER_CYCLE,
                    )
                }
                staleListRefreshJobs.forEach { job ->
                    listRefreshService.terminalizeStaleListRefresh(job.id, orphanTimeout)
                }
                transactionPort.inReadOnlyTransaction {
                    ingestRepository.findUnownedRunningIngestJobs(MAXIMUM_JOBS_PER_CYCLE)
                }.forEach { job -> service.launchClaimedJob(job) }
                transactionPort.inReadOnlyTransaction {
                    listRefreshRepository.findUnownedRunningListRefreshJobs(MAXIMUM_JOBS_PER_CYCLE)
                }.forEach { job -> listRefreshService.launchClaimedListRefresh(job) }
            }
        } finally {
            cycleInProgress.set(false)
        }
    }

    private companion object {
        const val MAXIMUM_JOBS_PER_CYCLE: Int = 64
    }
}
