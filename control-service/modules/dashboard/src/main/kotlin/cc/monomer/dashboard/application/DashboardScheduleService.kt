package cc.monomer.dashboard.application

import cc.monomer.dashboard.api.*
import cc.monomer.dashboard.domain.DashboardSession
import cc.monomer.dashboard.domain.QueryRunState
import cc.monomer.identity.api.WorkspaceId
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class DashboardScheduleService(
    private val transactionPort: DashboardTransactionPort,
    private val repository: DashboardScheduleRepository,
    private val sessionRepository: DashboardSessionRepository,
    private val queryRunRepository: QueryRunRepository,
    private val executionAdmissionPort: DashboardExecutionAdmissionPort,
    private val sessionService: DashboardSessionService,
) : DashboardScheduleOwnerPort {
    private val logger = org.slf4j.LoggerFactory.getLogger(DashboardScheduleService::class.java)
    private var afterWorkspace: WorkspaceId? = null
    private val tickMutex = Mutex()

    override suspend fun tick(): DashboardScheduleTickResult {
        if (!tickMutex.tryLock()) return DashboardScheduleTickResult(0, 0, 0, false, 0)
        try {
            var candidates = 0
            val processed = AtomicInteger()
            val failed = AtomicInteger()
            var truncated = false
            var oldestCandidateDelayMillis = 0L
            withTimeoutOrNull(30_000) {
                val (batch, observedAt) = transactionPort.inTransaction {
                    repository.configureTransactionTimeouts()
                    repository.dueCandidates(afterWorkspace, 32) to repository.databaseNow()
                }
                oldestCandidateDelayMillis = batch.maxOfOrNull { Duration.between(it.scheduledAt, observedAt).toMillis().coerceAtLeast(0) } ?: 0
                candidates = batch.size
                truncated = batch.size == 32
                afterWorkspace = batch.lastOrNull()?.workspaceId ?: afterWorkspace
                val semaphore = Semaphore(4)
                supervisorScope {
                    batch.map { candidate -> async(Dispatchers.IO) {
                        semaphore.withPermit {
                            try {
                                if (process(candidate)) processed.incrementAndGet()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                logger.atWarn()
                                    .addKeyValue("event", "dashboard_schedule_candidate_failed")
                                    .addKeyValue("workspace_id", candidate.workspaceId.toString())
                                    .addKeyValue("dashboard_id", candidate.dashboardId.toString())
                                    .addKeyValue("schedule_key", candidate.scheduleKey)
                                    .addKeyValue("scheduled_at", candidate.scheduledAt.toString())
                                    .setCause(failure)
                                    .log("dashboard_schedule_candidate_failed")
                                failed.incrementAndGet()
                            }
                        }
                    } }.awaitAll()
                }
            } ?: run { truncated = true }
            return DashboardScheduleTickResult(candidates, processed.get(), failed.get(), truncated, oldestCandidateDelayMillis)
        } finally {
            tickMutex.unlock()
        }
    }

    private suspend fun process(candidate: DashboardScheduleCandidate): Boolean {
        if (candidate.scheduleKey !in candidate.dashboard.definition.schedules) return false
        val observedAt = transactionPort.inTransaction { repository.configureTransactionTimeouts(); repository.databaseNow() }
        val prepared = if (Duration.between(candidate.scheduledAt, observedAt) > Duration.ofMinutes(15)) {
            Prepared(null, null, emptyList(), null)
        } else {
            prepare(candidate)
        }
        try {
            return decide(candidate, prepared, null)
        } catch (failure: DashboardSessionService.Rollback) {
            // Only an explicit admission rejection is a known rollback. Unknown DB/commit failures re-observe next tick.
            return decide(candidate, prepared, failure.code)
        } catch (failure: cc.monomer.execution.api.ExecutionRunAdmissionRollbackException) {
            return decide(candidate, prepared, "EXECUTION_ADMISSION_CONFLICT")
        }
    }

    private suspend fun prepare(candidate: DashboardScheduleCandidate): Prepared {
        val parameters = try {
            candidate.dashboard.definition.schedules.getValue(candidate.scheduleKey).resolveParameters(candidate.scheduledAt)
        } catch (_: IllegalArgumentException) {
            return Prepared(null, null, emptyList(), "INVALID_SCHEDULE_PARAMETERS")
        } catch (_: java.time.DateTimeException) {
            return Prepared(null, null, emptyList(), "INVALID_SCHEDULE_PARAMETERS")
        } catch (_: ArithmeticException) {
            return Prepared(null, null, emptyList(), "INVALID_SCHEDULE_PARAMETERS")
        }
        val session = try {
            DashboardSession.open(DashboardSessionId.generate(), candidate.workspaceId, candidate.dashboardId,
                candidate.dashboard.definition, parameters, candidate.scheduledAt)
        } catch (_: IllegalArgumentException) {
            return Prepared(parameters, null, emptyList(), "INVALID_SCHEDULE_PARAMETERS")
        }
        return when (val result = withTimeoutOrNull(10_000) { sessionService.planQueries(session, session.initialVariables, null, null, null) }) {
            null -> Prepared(parameters, null, emptyList(), "PLANNING_TIMEOUT")
            is DashboardSessionService.QueryPlanResult.Planned -> Prepared(parameters, session, result.plans, null)
            is DashboardSessionService.QueryPlanResult.Rejected -> Prepared(parameters, null, emptyList(), "QUERY_PLANNING_REJECTED")
        }
    }

    private suspend fun decide(candidate: DashboardScheduleCandidate, prepared: Prepared, admissionFailure: String?): Boolean {
        val context = currentCoroutineContext()
        val occurrence = transactionPort.inTransaction {
            context.ensureActive()
            repository.configureTransactionTimeouts()
            executionAdmissionPort.lockWorkspace(candidate.workspaceId)
            val current = repository.lockCurrent(candidate.workspaceId, candidate.dashboardId) ?: return@inTransaction null
            val schedule = current.definition.schedules[candidate.scheduleKey] ?: return@inTransaction null
            if (current.definition.document != candidate.dashboard.definition.document || repository.lockCursor(candidate) != candidate.scheduledAt) return@inTransaction null
            if (repository.readOccurrence(candidate.workspaceId, candidate.dashboardId, candidate.scheduleKey, candidate.scheduledAt) != null) return@inTransaction null
            val now = repository.databaseNow()
            if (now < candidate.scheduledAt) return@inTransaction null
            val previousRuns = repository.previousInitialRuns(candidate)
            val reason = when {
                Duration.between(candidate.scheduledAt, now) > Duration.ofMinutes(15) -> "LATE"
                previousRuns.any { queryRunRepository.find(it)?.state == QueryRunState.RUNNING } ||
                    executionAdmissionPort.hasUnreleasedOccupancy(candidate.workspaceId, previousRuns) -> "OVERLAP"
                admissionFailure != null -> "EXECUTION_ADMISSION_REJECTED"
                prepared.failure != null -> prepared.failure
                else -> null
            }
            val outcome = when (reason) {
                null -> "ACCEPTED"
                "LATE", "OVERLAP" -> "SKIPPED"
                else -> "ADMISSION_FAILED"
            }
            val session = if (outcome == "ACCEPTED") requireNotNull(prepared.session).copy(createdAt = now, updatedAt = now) else null
            val runs = if (session != null) {
                if (!sessionRepository.insert(session)) throw DashboardSessionService.Rollback("SESSION_CONFLICT", null)
                prepared.plans.associate { context.ensureActive(); it.query.name to sessionService.admitNewRun(session, it, now).id }
            } else emptyMap()
            val decided = DashboardScheduleOccurrenceView(candidate.scheduleKey, candidate.scheduledAt, now, outcome, reason,
                current.definition.document, prepared.parameters, session?.id, runs)
            repository.insertOccurrence(candidate, decided)
            repository.advance(candidate, schedule.nextAfter(candidate.scheduledAt))
            context.ensureActive()
            decided
        } ?: return false
        logger.atInfo()
            .addKeyValue("event", "dashboard_schedule_occurrence")
            .addKeyValue("workspace_id", candidate.workspaceId.toString())
            .addKeyValue("dashboard_id", candidate.dashboardId.toString())
            .addKeyValue("schedule_key", occurrence.scheduleKey)
            .addKeyValue("scheduled_at", occurrence.scheduledAt.toString())
            .addKeyValue("outcome", occurrence.outcome)
            .addKeyValue("reason", occurrence.reason)
            .addKeyValue("delay_ms", Duration.between(occurrence.scheduledAt, occurrence.decidedAt).toMillis().coerceAtLeast(0))
            .log("dashboard_schedule_occurrence")
        return true
    }

    override suspend fun preview(document: JsonObject, after: Instant): List<DashboardSchedulePreviewView> =
        DashboardDefinitionDecoder.decode(document).schedules.flatMap { (key, schedule) ->
            schedule.preview(after).map { DashboardSchedulePreviewView(key, it.scheduledAt, it.localTime, it.parameters) }
        }

    override suspend fun listSchedules(workspaceId: WorkspaceId, dashboardId: DashboardId): List<DashboardScheduleStatus> = repository.listSchedules(workspaceId, dashboardId)

    override suspend fun listOccurrences(workspaceId: WorkspaceId, dashboardId: DashboardId, before: DashboardScheduleOccurrenceCursor?, limit: Int): DashboardScheduleOccurrencePage {
        require(limit in 1..100)
        val rows = repository.listOccurrences(workspaceId, dashboardId, before, limit + 1)
        val items = rows.take(limit)
        return DashboardScheduleOccurrencePage(items, if (rows.size > limit) items.last().let { DashboardScheduleOccurrenceCursor(it.scheduledAt, it.scheduleKey) } else null)
    }

    override suspend fun readOccurrence(workspaceId: WorkspaceId, dashboardId: DashboardId, scheduleKey: String, scheduledAt: Instant): DashboardScheduleOccurrenceView? =
        repository.readOccurrence(workspaceId, dashboardId, scheduleKey, scheduledAt)

    private data class Prepared(val parameters: Map<String, JsonElement>?, val session: DashboardSession?, val plans: List<DashboardSessionService.QueryPlan>, val failure: String?)
}
