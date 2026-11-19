package cc.monomer.control.dashboard

import cc.monomer.dashboard.api.*
import cc.monomer.execution.api.ArrowResultManifest
import cc.monomer.execution.api.ExecutionArtifactHead
import cc.monomer.execution.api.ExecutionArtifactStore
import cc.monomer.identity.api.WorkspaceId
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

data class DashboardSessionEntry(
    val session: DashboardOwnerSession?,
    val initialQueryRuns: Map<String, DashboardOwnerQueryRun>,
)

@Component
class DashboardSessionEntryReader(
    private val owner: DashboardOwnerPort,
    private val artifacts: ExecutionArtifactStore,
    private val clock: Clock,
) {
    suspend fun select(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        sessionId: DashboardSessionId?,
        authorize: suspend () -> Unit,
        verifyResults: suspend (List<DashboardOwnerResultLookup>) -> Unit,
    ): DashboardSessionEntry {
        val started = System.nanoTime()
        var authorizeCalls = 0
        var authorizeNanos = 0L
        var candidateListNanos = 0L
        var inspectionNanos = 0L
        var headBatchNanos = 0L
        var finalReadNanos = 0L
        var verifiedCount = 0
        var budgetExhausted = false
        var outcome = "success"
        suspend fun timedAuthorize() {
            authorizeCalls++
            val stageStarted = System.nanoTime()
            try { authorize() } finally { authorizeNanos += System.nanoTime() - stageStarted }
        }
        suspend fun finish(id: DashboardSessionId, results: Map<String, DashboardOwnerQueryRun>): DashboardSessionEntry {
            verifiedCount = results.size
            val stageStarted = System.nanoTime()
            val session = try { owner.readSession(workspaceId, dashboardId, id).required() }
                finally { finalReadNanos += System.nanoTime() - stageStarted }
            return DashboardSessionEntry(session, results)
        }
        try {
            timedAuthorize()
            val candidates = if (sessionId != null) listOf(sessionId) else {
                val listStarted = System.nanoTime()
                try {
                    owner.listSessions(ListDashboardSessionsCommand(workspaceId, dashboardId, null, 20))
                        .required().items.map { it.sessionId }
                } finally { candidateListNanos += System.nanoTime() - listStarted }
            }
            if (candidates.isEmpty()) return DashboardSessionEntry(null, emptyMap())
            val latestId = candidates.first()
            val deadline = clock.instant().plusSeconds(2)
            var remainingQueries = 64
            var remainingHeads = 64
            var latestResults: Map<String, DashboardOwnerQueryRun> = emptyMap()
            val heads = mutableMapOf<QueryRunId, ExecutionArtifactHead?>()
            for (candidateId in candidates) {
                timedAuthorize()
                val verified = linkedMapOf<String, DashboardOwnerQueryRun>()
                try {
                    val remainingMillis = Duration.between(clock.instant(), deadline).toMillis()
                    if (remainingMillis <= 0) {
                        budgetExhausted = true
                        outcome = "timeout"
                        break
                    }
                    val inspectionStarted = System.nanoTime()
                    val inspection = try {
                        withTimeout(remainingMillis) {
                            owner.readInitialSessionResults(ReadInitialDashboardSessionResultsCommand(
                                workspaceId, dashboardId, candidateId, remainingQueries, deadline,
                            )).required()
                        }
                    } finally { inspectionNanos += System.nanoTime() - inspectionStarted }
                    remainingQueries -= inspection.queriesChecked
                    val authorizationStarted = System.nanoTime()
                    authorizeCalls++
                    try { verifyResults(inspection.results.values.toList()) }
                        finally { authorizeNanos += System.nanoTime() - authorizationStarted }
                    if (inspection.results.isNotEmpty()) {
                        val missing = inspection.results.values.distinctBy { it.queryRun.queryRunId }
                            .filter { !heads.containsKey(it.queryRun.queryRunId) }
                        if (missing.size > remainingHeads) throw TimeoutException()
                        remainingHeads -= missing.size
                        val headMillis = Duration.between(clock.instant(), deadline).toMillis()
                        if (headMillis <= 0) throw TimeoutException()
                        val headsStarted = System.nanoTime()
                        try {
                            withTimeout(headMillis) {
                                coroutineScope {
                                    val headPermits = Semaphore(MAXIMUM_CONCURRENT_HEADS)
                                    missing.forEach { result -> launch {
                                        headPermits.withPermit {
                                            val head = try {
                                                artifacts.head(result.resultManifest.objectKey, deadline)
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (failure: Exception) {
                                                if (!clock.instant().isBefore(deadline)) throw TimeoutException()
                                                null
                                            }
                                            synchronized(heads) { heads[result.queryRun.queryRunId] = head }
                                        }
                                    } }
                                }
                            }
                        } finally {
                            headBatchNanos += System.nanoTime() - headsStarted
                            synchronized(heads) {
                                for ((name, result) in inspection.results) {
                                    if (heads[result.queryRun.queryRunId].matches(result.resultManifest)) verified[name] = result.queryRun
                                }
                            }
                        }
                    }
                    if (candidateId == latestId) latestResults = verified.toMap()
                    if (inspection.budgetExhausted) {
                        budgetExhausted = true
                        break
                    }
                    if (sessionId != null || verified.keys.containsAll(inspection.requiredQueryNames)) {
                        timedAuthorize()
                        return finish(candidateId, verified)
                    }
                } catch (_: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    budgetExhausted = true
                    outcome = "timeout"
                    if (candidateId == latestId) latestResults = verified.toMap()
                    break
                } catch (_: cc.monomer.dashboard.application.DashboardReadDeadlineExceeded) {
                    budgetExhausted = true
                    outcome = "timeout"
                    if (candidateId == latestId) latestResults = verified.toMap()
                    break
                } catch (_: TimeoutException) {
                    budgetExhausted = true
                    outcome = "timeout"
                    if (candidateId == latestId) latestResults = verified.toMap()
                    break
                }
            }
            timedAuthorize()
            return finish(latestId, latestResults)
        } catch (cancelled: CancellationException) {
            outcome = "cancelled"
            throw cancelled
        } catch (failure: Exception) {
            outcome = "exception"
            throw failure
        } finally {
            logger.atInfo()
                .addKeyValue("event", "dashboard_entry_timing")
                .addKeyValue("outcome", outcome)
                .addKeyValue("budget_exhausted", budgetExhausted)
                .addKeyValue("total_ms", (System.nanoTime() - started) / 1_000_000)
                .addKeyValue("authorize_calls", authorizeCalls)
                .addKeyValue("authorize_cumulative_ms", authorizeNanos / 1_000_000)
                .addKeyValue("candidate_list_ms", candidateListNanos / 1_000_000)
                .addKeyValue("owner_inspection_cumulative_ms", inspectionNanos / 1_000_000)
                .addKeyValue("artifact_head_batch_wall_ms", headBatchNanos / 1_000_000)
                .addKeyValue("final_session_read_ms", finalReadNanos / 1_000_000)
                .addKeyValue("verified_count", verifiedCount)
                .log("dashboard_entry_timing")
        }
    }

    private fun ExecutionArtifactHead?.matches(manifest: ArrowResultManifest): Boolean =
        this != null && objectKey == manifest.objectKey && byteSize == manifest.byteSize &&
            byteSize <= MAXIMUM_RESULT_BYTES && etag.trim().trim('"') == manifest.objectEtag.trim().trim('"')

    private fun <T> DashboardOwnerOutcome<T>.required(): T = when (this) {
        is DashboardOwnerOutcome.Success -> value
        DashboardOwnerOutcome.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
        is DashboardOwnerOutcome.Conflict -> throw ResponseStatusException(HttpStatus.CONFLICT)
        is DashboardOwnerOutcome.Rejected -> throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
    }

    private companion object {
        val logger = LoggerFactory.getLogger(DashboardSessionEntryReader::class.java)
        const val MAXIMUM_RESULT_BYTES: ULong = 67_108_864uL
        const val MAXIMUM_CONCURRENT_HEADS = 4
    }
}
