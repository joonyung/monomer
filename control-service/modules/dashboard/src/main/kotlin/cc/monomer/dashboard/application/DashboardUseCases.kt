@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.application

import cc.monomer.dashboard.domain.DashboardNamedQuery
import cc.monomer.dashboard.domain.DashboardResolvedQuery
import cc.monomer.dashboard.domain.DashboardBindValue
import cc.monomer.dashboard.domain.DashboardQueryType
import cc.monomer.dashboard.domain.DashboardValueRefKind
import cc.monomer.dashboard.domain.DashboardRangeEndpoint
import cc.monomer.dashboard.domain.DashboardJson

import cc.monomer.dashboard.api.DashboardSearchProjection
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardSessionId
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.dashboard.domain.Dashboard
import cc.monomer.dashboard.domain.DashboardSession
import cc.monomer.dashboard.domain.QueryRun
import cc.monomer.dashboard.domain.QueryRunState
import cc.monomer.execution.api.ArrowResultManifest
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.ExpectedOAuthResource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

interface DashboardUseCase {
    suspend fun readCurrentSemanticReferences(workspaceId: WorkspaceId): List<cc.monomer.dashboard.api.DashboardCurrentSemanticReferences>
    suspend fun create(command: CreateCommand): DashboardResult
    suspend fun get(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardResult
    suspend fun list(workspaceId: WorkspaceId): List<cc.monomer.dashboard.domain.DashboardListEntry>
    suspend fun list(
        workspaceId: WorkspaceId,
        cursor: DashboardPageCursor?,
        pageSize: Int,
    ): DashboardPage

    /** [actorId] keys the idempotency receipt; [recordedActorId] is the principal the definition records, null for a Monomer seed. */
    data class CreateCommand(
        val workspaceId: WorkspaceId,
        val document: JsonObject,
        val actorId: String,
        val recordedActorId: String?,
        val idempotencyKey: String,
        val createdAt: Instant,
        val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
        val agentClientId: cc.monomer.identity.api.AgentClientId?,
    ) {
        init { require(actorId.isNotBlank()); require(idempotencyKey.isNotBlank()) }
    }

}

data class DashboardPageCursor(
    val updatedAt: Instant,
    val dashboardId: DashboardId,
)

data class DashboardPage(
    val items: List<cc.monomer.dashboard.domain.DashboardListItem>,
    val nextCursor: DashboardPageCursor?,
)

sealed interface DashboardResult {
    data class Found(val dashboard: Dashboard) : DashboardResult
    data object NotFound : DashboardResult
    data object Conflict : DashboardResult
    data object IdempotencyConflict : DashboardResult
}

class DashboardService(
    private val searchProjection: DashboardSearchProjection,
    private val transactionPort: DashboardTransactionPort,
    private val repository: DashboardRepository,
    private val commandReceiptRepository: DashboardCommandReceiptRepository,
) : DashboardUseCase {
    override suspend fun readCurrentSemanticReferences(workspaceId: WorkspaceId) = repository.readCurrentSemanticReferences(workspaceId)

    override suspend fun create(command: DashboardUseCase.CreateCommand): DashboardResult {
        val definition = DashboardDefinitionDecoder.decode(command.document)
        val request = buildJsonObject { put("definition", command.document) }
        val dashboard = Dashboard(
            id = DashboardId.generate(),
            workspaceId = command.workspaceId,
            definition = definition,
            createdAt = command.createdAt,
            updatedAt = command.createdAt,
            updatedBy = command.recordedActorId,
            credentialSurface = command.credentialSurface,
            agentClientId = command.agentClientId,
        )
        return transactionPort.inTransaction {
            commandReceiptRepository.lock(command.workspaceId, command.actorId, command.idempotencyKey)
            commandReceiptRepository.find(command.workspaceId, command.actorId, command.idempotencyKey)?.let { receipt ->
                if (receipt.normalizedRequest != request) return@inTransaction DashboardResult.IdempotencyConflict
                return@inTransaction replayCreatedDashboard(receipt)
            }
            if (!repository.insert(dashboard)) return@inTransaction DashboardResult.Conflict
            searchProjection.upsert(dashboard.toSearchFields())
            if (!commandReceiptRepository.insert(
                    CommandReceipt(
                        workspaceId = command.workspaceId,
                        actorId = command.actorId,
                        idempotencyKey = command.idempotencyKey,
                        commandKind = "CREATE_DASHBOARD",
                        subjectId = dashboard.id.toString(),
                        normalizedRequest = request,
                        dashboardId = dashboard.id,
                        sessionId = null,
                        queryRunIds = emptyList(),
                        response = CommandReceiptResponse.DashboardCreated(dashboard),
                        createdAt = command.createdAt,
                    ),
                )
            ) return@inTransaction DashboardResult.Conflict
            DashboardResult.Found(dashboard)
        }
    }

    private fun replayCreatedDashboard(receipt: CommandReceipt): DashboardResult {
        if (receipt.dashboardId?.let { repository.find(receipt.workspaceId, it) } == null) return DashboardResult.IdempotencyConflict
        return when (val response = receipt.response) {
            is CommandReceiptResponse.DashboardCreated -> DashboardResult.Found(response.dashboard)
            else -> DashboardResult.Conflict
        }
    }

    override suspend fun get(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardResult =
        repository.find(workspaceId, dashboardId)?.let(DashboardResult::Found) ?: DashboardResult.NotFound

    override suspend fun list(workspaceId: WorkspaceId) = repository.list(workspaceId)

    override suspend fun list(
        workspaceId: WorkspaceId,
        cursor: DashboardPageCursor?,
        pageSize: Int,
    ): DashboardPage {
        require(pageSize in 1..MAX_PAGE_SIZE)
        val rows = repository.findPage(workspaceId, cursor, pageSize + 1)
        val items = rows.take(pageSize)
        return DashboardPage(
            items = items,
            nextCursor = if (rows.size > pageSize) {
                items.lastOrNull()?.let { DashboardPageCursor(it.updatedAt, it.id) }
            } else {
                null
            },
        )
    }

    private companion object {
        const val MAX_PAGE_SIZE = 100
    }
}

interface DashboardSessionUseCase {
    suspend fun resolvePublic(command: cc.monomer.dashboard.api.ResolvePublicDashboardSessionCommand): SessionResolutionResult
    suspend fun readInitialResults(command: cc.monomer.dashboard.api.ReadInitialDashboardSessionResultsCommand): InitialSessionResultsResult
    suspend fun open(command: OpenSessionCommand): SessionResult
    suspend fun get(workspaceId: WorkspaceId, sessionId: DashboardSessionId): SessionResult
    suspend fun get(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        sessionId: DashboardSessionId,
    ): SessionResult
    suspend fun list(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        cursor: DashboardSessionPageCursor?,
        pageSize: Int,
    ): DashboardSessionPageResult
    suspend fun listQueryRuns(
        workspaceId: WorkspaceId,
        sessionId: DashboardSessionId,
        cursor: DashboardQueryRunPageCursor?,
        pageSize: Int,
    ): DashboardQueryRunPageResult
    suspend fun listQueryRuns(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        sessionId: DashboardSessionId,
        cursor: DashboardQueryRunPageCursor?,
        pageSize: Int,
    ): DashboardQueryRunPageResult
    suspend fun getQueryRun(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        sessionId: DashboardSessionId,
        queryRunId: QueryRunId,
    ): QueryRunLookupResult
    suspend fun resolve(command: ResolveCommand): SessionResolutionResult
    suspend fun refresh(command: RefreshCommand): SessionResolutionResult

    data class OpenSessionCommand(
        val workspaceId: WorkspaceId,
        val credential: PresentedCredential,
        val expectedOAuthResource: ExpectedOAuthResource?,
        val dashboardId: DashboardId,
        val parameterBindings: Map<String, JsonElement>,
        val actorId: String,
        val idempotencyKey: String,
        val openedAt: Instant,
    ) {
        init { require(actorId.isNotBlank()); require(idempotencyKey.isNotBlank()) }
    }

    data class ResolveCommand(
        val workspaceId: WorkspaceId,
        val credential: PresentedCredential,
        val expectedOAuthResource: ExpectedOAuthResource?,
        val sessionId: DashboardSessionId,
        val dashboardId: DashboardId,
        val variables: Map<String, JsonElement>,
        val actorId: String,
        val idempotencyKey: String,
        val requestedAt: Instant,
    ) {
        init { require(actorId.isNotBlank()); require(idempotencyKey.isNotBlank()) }
    }

    data class RefreshCommand(
        val workspaceId: WorkspaceId,
        val credential: PresentedCredential,
        val expectedOAuthResource: ExpectedOAuthResource?,
        val dashboardId: DashboardId,
        val sessionId: DashboardSessionId,
        val queryNames: Set<String>,
        val variables: Map<String, JsonElement>,
        val actorId: String,
        val idempotencyKey: String,
        val requestedAt: Instant,
    ) {
        init { require(queryNames.isNotEmpty()); require(actorId.isNotBlank()); require(idempotencyKey.isNotBlank()) }
    }
}

data class DashboardSessionPageCursor(
    val dashboardId: DashboardId,
    val createdAt: Instant,
    val sessionId: DashboardSessionId,
)

data class DashboardQueryRunPageCursor(
    val createdAt: Instant,
    val queryRunId: QueryRunId,
)

data class DashboardCurrentQueryRun(
    val queryName: String,
    val queryRunId: QueryRunId,
    val state: QueryRunState,
)

data class DashboardSessionSummary(
    val id: DashboardSessionId,
    val dashboardId: DashboardId,
    val parameterBindings: Map<String, JsonElement>,
    val runningQueryRuns: List<DashboardCurrentQueryRun>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastQueryRunAt: Instant?,
)

data class DashboardSessionPage(
    val items: List<DashboardSessionSummary>,
    val nextCursor: DashboardSessionPageCursor?,
)

data class DashboardQueryRunPage(
    val items: List<QueryRun>,
    val nextCursor: DashboardQueryRunPageCursor?,
)

sealed interface DashboardSessionPageResult {
    data class Found(val page: DashboardSessionPage) : DashboardSessionPageResult
    data object NotFound : DashboardSessionPageResult
}

sealed interface DashboardQueryRunPageResult {
    data class Found(val page: DashboardQueryRunPage) : DashboardQueryRunPageResult
    data object NotFound : DashboardQueryRunPageResult
}

/** Called by the existing ExecutionRunOwnerSettlementPort inside its owner transaction. */
interface DashboardQueryRunSettlementUseCase {
    fun settleInOwnerTransaction(command: Command): Outcome

    data class Command(
        val workspaceId: WorkspaceId,
        val queryRunId: QueryRunId,
        val state: State,
        val result: ArrowResultManifest?,
        val failureCode: String?,
        val failureMessage: String?,
        val settledAt: Instant,
    )

    enum class State { SUCCEEDED, FAILED, CANCELLED }

    sealed interface Outcome {
        data object Applied : Outcome
        data object Duplicate : Outcome
        data object Conflict : Outcome
        data object NotFound : Outcome
    }
}

sealed interface SessionResult {
    data class Found(val session: DashboardSession, val queryRuns: List<QueryRun>) : SessionResult
    data object NotFound : SessionResult
    data class Rejected(val code: String, val message: String?) : SessionResult
}

sealed interface SessionResolutionResult {
    data class Resolved(val variables: Map<String, JsonElement>, val queryRuns: Map<String, QueryRunId>) : SessionResolutionResult
    data object NotFound : SessionResolutionResult
    data class Rejected(val code: String, val message: String?) : SessionResolutionResult
}

sealed interface InitialSessionResultsResult {
    data class Found(
        val requiredQueryNames: Set<String>,
        val queryRuns: Map<String, QueryRun>,
        val queriesChecked: Int,
        val budgetExhausted: Boolean,
    ) : InitialSessionResultsResult
    data object NotFound : InitialSessionResultsResult
}

class DashboardSessionService(
    private val transactionPort: DashboardTransactionPort,
    private val dashboardRepository: DashboardRepository,
    private val sessionRepository: DashboardSessionRepository,
    private val queryRunRepository: QueryRunRepository,
    private val commandReceiptRepository: DashboardCommandReceiptRepository,
    private val queryPlanningPort: DashboardQueryPlanningPort,
    private val executionAdmissionPort: DashboardExecutionAdmissionPort,
) : DashboardSessionUseCase {
    override suspend fun open(command: DashboardSessionUseCase.OpenSessionCommand): SessionResult {
        val request = buildJsonObject {
            put("dashboardId", command.dashboardId.toString())
            put("parameterBindings", JsonObject(command.parameterBindings))
        }
        commandReceiptRepository.find(command.workspaceId, command.actorId, command.idempotencyKey)?.let {
            return replayOpen(it, request)
        }
        val dashboard = dashboardRepository.find(command.workspaceId, command.dashboardId) ?: return SessionResult.NotFound
        val session = try {
            DashboardSession.open(DashboardSessionId.generate(), command.workspaceId, command.dashboardId, dashboard.definition, command.parameterBindings, command.openedAt)
        } catch (error: IllegalArgumentException) {
            return SessionResult.Rejected("INVALID_SESSION_PARAMETERS", error.message)
        }
        val plans = when (val planned = planQueries(session, session.initialVariables, command.credential, command.expectedOAuthResource, null)) {
            is QueryPlanResult.Planned -> planned.plans
            is QueryPlanResult.Rejected -> return SessionResult.Rejected(planned.code, planned.message)
        }
        return try {
            transactionPort.inTransaction {
                executionAdmissionPort.lockWorkspace(command.workspaceId)
                commandReceiptRepository.lock(command.workspaceId, command.actorId, command.idempotencyKey)
                commandReceiptRepository.find(command.workspaceId, command.actorId, command.idempotencyKey)?.let { receipt ->
                    return@inTransaction replayOpen(receipt, request)
                }
                val current = dashboardRepository.findForUpdate(command.workspaceId, command.dashboardId)
                    ?: return@inTransaction SessionResult.NotFound
                if (current.updatedAt != dashboard.updatedAt || current.definition.document != dashboard.definition.document) {
                    return@inTransaction SessionResult.Rejected("SESSION_CONFLICT", null)
                }
                if (!sessionRepository.insert(session)) throw Rollback("SESSION_CONFLICT", null)
                val runs = plans.map { admitNewRun(session, it, command.openedAt) }
                if (!commandReceiptRepository.insert(CommandReceipt(
                    command.workspaceId, command.actorId, command.idempotencyKey, "OPEN_SESSION", command.dashboardId.toString(),
                    request, command.dashboardId, session.id, runs.map(QueryRun::id), CommandReceiptResponse.Session(session, runs), command.openedAt,
                ))) throw Rollback("IDEMPOTENCY_CONFLICT", null)
                SessionResult.Found(session, runs)
            }
        } catch (rollback: Rollback) {
            SessionResult.Rejected(rollback.code, rollback.message)
        }
    }

    private fun replayOpen(receipt: CommandReceipt, request: JsonObject): SessionResult {
        if (receipt.normalizedRequest != request) return SessionResult.Rejected("IDEMPOTENCY_CONFLICT", null)
        if (receipt.dashboardId?.let { dashboardRepository.find(receipt.workspaceId, it) } == null) {
            return SessionResult.Rejected("IDEMPOTENCY_CONFLICT", null)
        }
        val response = receipt.response as? CommandReceiptResponse.Session
            ?: return SessionResult.Rejected("IDEMPOTENCY_RECEIPT_INVALID", null)
        return SessionResult.Found(response.session, response.queryRuns)
    }

    override suspend fun get(workspaceId: WorkspaceId, sessionId: DashboardSessionId): SessionResult =
        sessionRepository.find(workspaceId, sessionId)?.let { session ->
            val runs = visibleQueryRuns(session)
            SessionResult.Found(session, runs)
        } ?: SessionResult.NotFound

    override suspend fun get(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        sessionId: DashboardSessionId,
    ): SessionResult = sessionRepository.find(workspaceId, dashboardId, sessionId)?.let { session ->
        SessionResult.Found(session, visibleQueryRuns(session))
    } ?: SessionResult.NotFound

    override suspend fun list(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        cursor: DashboardSessionPageCursor?,
        pageSize: Int,
    ): DashboardSessionPageResult {
        require(pageSize in 1..MAX_PAGE_SIZE)
        require(cursor == null || cursor.dashboardId == dashboardId) { "cursor belongs to another Dashboard" }
        if (dashboardRepository.find(workspaceId, dashboardId) == null) {
            return DashboardSessionPageResult.NotFound
        }
        val rows = sessionRepository.findPage(workspaceId, dashboardId, cursor, pageSize + 1)
        val items = rows.take(pageSize)
        return DashboardSessionPageResult.Found(
            DashboardSessionPage(
                items = items,
                nextCursor = if (rows.size > pageSize) {
                    items.lastOrNull()?.let { DashboardSessionPageCursor(dashboardId, it.createdAt, it.id) }
                } else {
                    null
                },
            ),
        )
    }

    override suspend fun listQueryRuns(
        workspaceId: WorkspaceId,
        sessionId: DashboardSessionId,
        cursor: DashboardQueryRunPageCursor?,
        pageSize: Int,
    ): DashboardQueryRunPageResult {
        require(pageSize in 1..MAX_PAGE_SIZE)
        val session = sessionRepository.find(workspaceId, sessionId)
            ?: return DashboardQueryRunPageResult.NotFound
        return queryRunPage(workspaceId, session.dashboardId, sessionId, cursor, pageSize)
    }

    override suspend fun listQueryRuns(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        sessionId: DashboardSessionId,
        cursor: DashboardQueryRunPageCursor?,
        pageSize: Int,
    ): DashboardQueryRunPageResult {
        require(pageSize in 1..MAX_PAGE_SIZE)
        if (sessionRepository.find(workspaceId, dashboardId, sessionId) == null) {
            return DashboardQueryRunPageResult.NotFound
        }
        return queryRunPage(workspaceId, dashboardId, sessionId, cursor, pageSize)
    }

    private fun queryRunPage(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        sessionId: DashboardSessionId,
        cursor: DashboardQueryRunPageCursor?,
        pageSize: Int,
    ): DashboardQueryRunPageResult {
        val rows = queryRunRepository.findSessionPage(workspaceId, dashboardId, sessionId, cursor, pageSize + 1)
        val items = rows.take(pageSize)
        return DashboardQueryRunPageResult.Found(
            DashboardQueryRunPage(
                items = items,
                nextCursor = if (rows.size > pageSize) {
                    items.lastOrNull()?.let { DashboardQueryRunPageCursor(it.createdAt, it.id) }
                } else {
                    null
                },
            ),
        )
    }

    override suspend fun getQueryRun(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        sessionId: DashboardSessionId,
        queryRunId: QueryRunId,
    ): QueryRunLookupResult = queryRunRepository.find(workspaceId, dashboardId, sessionId, queryRunId)
        ?.let(QueryRunLookupResult::Found)
        ?: QueryRunLookupResult.NotFound

    override suspend fun resolve(command: DashboardSessionUseCase.ResolveCommand): SessionResolutionResult = reconcile(
        command.workspaceId, command.dashboardId, command.sessionId, command.variables, emptySet(), "RESOLVE_SESSION",
        command.requestedAt, command.actorId, command.idempotencyKey, command.credential, command.expectedOAuthResource, null,
    )

    override suspend fun resolvePublic(command: cc.monomer.dashboard.api.ResolvePublicDashboardSessionCommand): SessionResolutionResult = reconcile(
        command.workspaceId, command.dashboardId, command.sessionId, command.variables, emptySet(), if (command.actorId == null) "RESOLVE_PUBLIC_SESSION" else "RESOLVE_SESSION",
        command.requestedAt, command.actorId, command.idempotencyKey, command.credential, command.expectedOAuthResource, command.pageId,
    )

    override suspend fun readInitialResults(command: cc.monomer.dashboard.api.ReadInitialDashboardSessionResultsCommand): InitialSessionResultsResult {
        val session = sessionRepository.findInitial(command.workspaceId, command.dashboardId, command.sessionId, command.deadline)
            ?: return InitialSessionResultsResult.NotFound
        val results = linkedMapOf<String, QueryRun>()
        var checked = 0
        var exhausted = false
        val queries = session.definition.queries.values.take(command.remainingQueryCount)
        if (!Instant.now().isBefore(command.deadline)) {
            return InitialSessionResultsResult.Found(session.definition.queries.keys, results, 0, true)
        }
        val resolvedQueries = queries.map { query ->
            try { DashboardQueryValueResolver.resolve(query, session.parameterBindings, session.initialVariables, session.definition.parameters, session.definition.variables) }
            catch (_: IllegalArgumentException) { null }
        }
        val commands = queries.mapIndexedNotNull { index, query -> resolvedQueries[index]?.let { resolved ->
            DashboardQueryPlanningPort.ResolveQueryCommand(session.workspaceId, query, resolved, null, null, command.deadline, session.dashboardId, null)
        } }
        val resolutions = queryPlanningPort.resolveBatch(commands).iterator()
        val plans = queries.mapIndexed { index, query ->
            val resolved = resolvedQueries[index]
            val rendered = if (resolved == null) null else resolutions.next() as? DashboardQueryResolution.Resolved
            if (rendered == null) null
            else QueryPlan(query, rendered)
        }
        checked = queries.size
        val lookupPlans = plans.filterNotNull()
        if (lookupPlans.isNotEmpty()) {
            if (!Instant.now().isBefore(command.deadline)) {
                exhausted = true
            } else {
                try {
                    val equivalentRuns = queryRunRepository.findEquivalentRuns(
                        session.workspaceId, session.dashboardId, session.id,
                        lookupPlans.map { plan ->
                            EquivalentQueryRunLookup(plan.query.name, plan.query.connectorId, plan.resolvedQuery, plan.rendered.sql, plan.rendered.outputColumns)
                        },
                        setOf(QueryRunState.SUCCEEDED), command.deadline,
                    )
                    lookupPlans.zip(equivalentRuns).forEach { (plan, run) -> if (run?.result != null) results[plan.query.name] = run }
                } catch (_: DashboardReadDeadlineExceeded) {
                    exhausted = true
                }
            }
        }
        exhausted = exhausted || checked < session.definition.queries.size || !Instant.now().isBefore(command.deadline)
        return InitialSessionResultsResult.Found(session.definition.queries.keys, results, checked, exhausted)
    }

    override suspend fun refresh(command: DashboardSessionUseCase.RefreshCommand): SessionResolutionResult = reconcile(
        command.workspaceId, command.dashboardId, command.sessionId, command.variables, command.queryNames, "REFRESH_SESSION",
        command.requestedAt, command.actorId, command.idempotencyKey, command.credential, command.expectedOAuthResource, null,
    )

    private suspend fun reconcile(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        sessionId: DashboardSessionId,
        variables: Map<String, JsonElement>,
        refreshQueryNames: Set<String>,
        commandKind: String,
        at: Instant,
        actorId: String?,
        idempotencyKey: String,
        credential: PresentedCredential?,
        expectedOAuthResource: ExpectedOAuthResource?,
        readerPageId: kotlin.uuid.Uuid?,
    ): SessionResolutionResult {
        val request = requestForResolution(commandKind, dashboardId, sessionId, variables, refreshQueryNames)
        if (readerPageId == null) {
            commandReceiptRepository.find(workspaceId, actorId, idempotencyKey)?.let { return replayResolution(it, request) }
        }
        val session = sessionRepository.find(workspaceId, dashboardId, sessionId) ?: run {
            return SessionResolutionResult.NotFound
        }
        val readerEvidence = if (readerPageId == null) null else {
            when (val admission = queryPlanningPort.admitPageReader(workspaceId, dashboardId, readerPageId, credential, expectedOAuthResource)) {
                is cc.monomer.dashboard.api.DashboardOwnerOutcome.Success -> admission.value
                else -> return SessionResolutionResult.Rejected("DASHBOARD_QUERY_UNAUTHORIZED", null)
            }
        }

        try {
            session.validateVariables(variables)
        } catch (error: IllegalArgumentException) {
            return SessionResolutionResult.Rejected("INVALID_VARIABLE_VALUE", error.message)
        }
        if (!refreshQueryNames.all { it in session.definition.queries }) return SessionResolutionResult.Rejected("UNKNOWN_QUERY", "refresh references an unknown query")
        if (commandKind == "RESOLVE_PUBLIC_SESSION") {
            commandReceiptRepository.find(workspaceId, actorId, idempotencyKey)?.let { receipt ->
                return replayResolution(receipt, request)
            }
        }
        val plans = when (val planned = planQueries(session, variables, credential, expectedOAuthResource, readerEvidence)) {
            is QueryPlanResult.Planned -> planned.plans
            is QueryPlanResult.Rejected -> return SessionResolutionResult.Rejected(planned.code, planned.message)
        }
        return try {
            transactionPort.inTransaction {
                executionAdmissionPort.lockWorkspace(workspaceId)
                commandReceiptRepository.lock(workspaceId, actorId, idempotencyKey)
                if (readerPageId != null && sessionRepository.findForUpdate(workspaceId, dashboardId, sessionId) == null) return@inTransaction SessionResolutionResult.NotFound
                commandReceiptRepository.find(workspaceId, actorId, idempotencyKey)?.let { receipt ->
                    return@inTransaction replayResolution(receipt, request)
                }
                val fresh = sessionRepository.findForUpdate(workspaceId, dashboardId, sessionId) ?: return@inTransaction SessionResolutionResult.NotFound
                val reusableRuns = plans.associate { plan ->
                    val allowedStates = if (plan.query.name in refreshQueryNames) setOf(QueryRunState.RUNNING)
                        else setOf(QueryRunState.SUCCEEDED, QueryRunState.RUNNING)
                    val equivalent = findEquivalent(fresh, plan, allowedStates)
                    val selected = if (refreshQueryNames.isNotEmpty() && plan.query.name !in refreshQueryNames) {
                        equivalent ?: findEquivalent(fresh, plan, setOf(QueryRunState.FAILED, QueryRunState.CANCELLED))
                            ?: throw Rollback("REFRESH_QUERY_RUN_UNAVAILABLE", "An unselected query has no existing run matching the current inputs and plan. Resolve inputs before retrying.")
                    } else equivalent
                    plan.query.name to selected
                }
                val runs = plans.associate { plan ->
                    val selected = reusableRuns[plan.query.name] ?: admitNewRun(fresh, plan, at)
                    plan.query.name to selected.id
                }
                if (!commandReceiptRepository.insert(CommandReceipt(
                    workspaceId, actorId, idempotencyKey, commandKind, sessionId.toString(), request, dashboardId, sessionId,
                    runs.values.toList(), CommandReceiptResponse.SessionResolution(variables.toMap(), runs), at,
                ))) throw Rollback("IDEMPOTENCY_CONFLICT", null)
                SessionResolutionResult.Resolved(variables.toMap(), runs)
            }
        } catch (rollback: Rollback) {
            SessionResolutionResult.Rejected(rollback.code, rollback.message)
        }
    }

    private fun requestForResolution(commandKind: String, dashboardId: DashboardId, sessionId: DashboardSessionId, variables: Map<String, JsonElement>, refreshQueryNames: Set<String>) = buildJsonObject {
        put("kind", commandKind)
        put("dashboardId", dashboardId.toString())
        put("sessionId", sessionId.toString())
        put("variables", JsonObject(variables))
        put("queryNames", JsonArray(refreshQueryNames.sorted().map(::JsonPrimitive)))
    }

    private fun replayResolution(receipt: CommandReceipt, request: JsonObject): SessionResolutionResult {
        if (receipt.dashboardId?.let { dashboardRepository.find(receipt.workspaceId, it) } == null) {
            return if (receipt.commandKind == "RESOLVE_PUBLIC_SESSION") SessionResolutionResult.NotFound else SessionResolutionResult.Rejected("IDEMPOTENCY_CONFLICT", null)
        }
        if (receipt.normalizedRequest != request) return SessionResolutionResult.Rejected("IDEMPOTENCY_CONFLICT", null)
        val response = receipt.response as? CommandReceiptResponse.SessionResolution
            ?: return SessionResolutionResult.Rejected("IDEMPOTENCY_RECEIPT_INVALID", null)
        return SessionResolutionResult.Resolved(response.variables, response.queryRuns)
    }

    private fun findEquivalent(session: DashboardSession, plan: QueryPlan, allowedStates: Set<QueryRunState>): QueryRun? = queryRunRepository.findEquivalent(
        session.workspaceId, session.dashboardId, session.id, plan.query.name, plan.query.connectorId, plan.resolvedQuery,
        plan.rendered.sql, plan.rendered.outputColumns, allowedStates,
    )

    internal fun admitNewRun(session: DashboardSession, plan: QueryPlan, at: Instant): QueryRun {
        val runId = QueryRunId.generate()
        val run = QueryRun.start(
            runId, session.workspaceId, session.dashboardId, session.id, plan.query, plan.resolvedQuery,
            plan.rendered.sql, plan.rendered.outputColumns, at,
            actorId = plan.rendered.actorId,
            credentialSurface = plan.rendered.credentialSurface,
            agentClientId = plan.rendered.agentClientId,
            outputColumnDisplays = plan.rendered.outputColumnDisplays,
            reportingTimeZone = plan.rendered.reportingTimeZone,
        )
        when (val outcome = executionAdmissionPort.admit(DashboardExecutionAdmissionPort.AdmissionCommand(
            runId, session.workspaceId, plan.query.connectorId, plan.rendered.sql,
            "dashboard-session:${session.id}:${plan.query.name}:$runId",
            "artifacts/workspaces/${session.workspaceId}/dashboard/query-runs/${session.id}/$runId/result.arrow",
            plan.resolvedQuery.rowLimit,
            plan.resolvedQuery.bindings,
            plan.query.type == DashboardQueryType.SQL,
            expectedColumnNames(plan.query),
            plan.rendered.parameterNames,
        ))) {
            DashboardExecutionAdmissionPort.AdmissionOutcome.Admitted -> Unit
            DashboardExecutionAdmissionPort.AdmissionOutcome.Backpressured -> throw Rollback("EXECUTION_BACKPRESSURED", null)
            DashboardExecutionAdmissionPort.AdmissionOutcome.Existing -> throw Rollback("EXECUTION_ADMISSION_CONFLICT", null)
            is DashboardExecutionAdmissionPort.AdmissionOutcome.Rejected -> throw Rollback(outcome.code, null)
        }
        if (!queryRunRepository.insert(run)) throw Rollback("QUERY_RUN_CONFLICT", null)
        return run
    }

    internal suspend fun planQueries(
        session: DashboardSession,
        variables: Map<String, JsonElement>,
        credential: PresentedCredential?,
        expectedOAuthResource: ExpectedOAuthResource?,
        readerEvidence: cc.monomer.identity.api.IdentityCredentialEvidence?,
    ): QueryPlanResult {
        val plans = mutableListOf<QueryPlan>()
        for (query in session.definition.queries.values) {
            when (val result = planQuery(session, query, variables, credential, expectedOAuthResource, null, readerEvidence)) {
                is QueryPlanResult.Planned -> plans += result.plans
                is QueryPlanResult.Rejected -> return result
            }
        }
        return QueryPlanResult.Planned(plans)
    }

    private suspend fun planQuery(
        session: DashboardSession,
        query: cc.monomer.dashboard.domain.DashboardNamedQuery,
        variables: Map<String, JsonElement>,
        credential: PresentedCredential?,
        expectedOAuthResource: ExpectedOAuthResource?,
        deadline: Instant?,
        readerEvidence: cc.monomer.identity.api.IdentityCredentialEvidence?,
    ): QueryPlanResult {
        val resolved = try {
            DashboardQueryValueResolver.resolve(query, session.parameterBindings, variables, session.definition.parameters, session.definition.variables)
        } catch (error: IllegalArgumentException) {
            return QueryPlanResult.Rejected("INVALID_QUERY_BINDING", error.message)
        } ?: return QueryPlanResult.Rejected("EMPTY_VALUE_REJECTED", "query ${query.name} contains an omitted value")
        return when (val result = queryPlanningPort.resolve(DashboardQueryPlanningPort.ResolveQueryCommand(
            session.workspaceId, query, resolved, credential, expectedOAuthResource, deadline, session.dashboardId, readerEvidence,
        ))) {
            is DashboardQueryResolution.Resolved -> {
                QueryPlanResult.Planned(listOf(QueryPlan(query, result)))
            }
            is DashboardQueryResolution.Rejected -> QueryPlanResult.Rejected(result.code, result.message)
        }
    }

    private fun visibleQueryRuns(session: DashboardSession): List<QueryRun> =
        queryRunRepository.findRunningSessionRuns(session.workspaceId, session.dashboardId, session.id)

    internal data class QueryPlan(val query: DashboardNamedQuery, val rendered: DashboardQueryResolution.Resolved) {
        val resolvedQuery: DashboardResolvedQuery get() = rendered.resolvedQuery
    }
    internal sealed interface QueryPlanResult {
        data class Planned(val plans: List<QueryPlan>) : QueryPlanResult
        data class Rejected(val code: String, val message: String?) : QueryPlanResult
    }
    internal class Rollback(val code: String, override val message: String?) : RuntimeException(message)
    private companion object { const val MAX_PAGE_SIZE = 100 }
}

internal object DashboardQueryValueResolver {
    fun referencesParameter(query: DashboardNamedQuery, parameterName: String): Boolean = when (query) {
        is DashboardNamedQuery.Semantic -> referencesParameter(query.query, parameterName)
        is DashboardNamedQuery.Sql -> query.bindings.values.any { it.kind == DashboardValueRefKind.PARAMETER && it.name == parameterName }
    }

    fun resolve(
        query: DashboardNamedQuery,
        parameters: Map<String, JsonElement>,
        variables: Map<String, JsonElement>,
        parameterDefinitions: Map<String, cc.monomer.dashboard.domain.DashboardParameterDefinition>,
        variableDefinitions: Map<String, cc.monomer.dashboard.domain.DashboardVariableDefinition>,
    ): DashboardResolvedQuery? = when (query) {
        is DashboardNamedQuery.Semantic -> resolve(query.query, parameters, variables, parameterDefinitions, variableDefinitions)?.let(DashboardResolvedQuery::Semantic)
        is DashboardNamedQuery.Sql -> DashboardResolvedQuery.Sql(query.template.aliasesInAppearanceOrder.map { alias ->
            val ref = query.bindings.getValue(alias)
            val declared = when (ref.kind) {
                DashboardValueRefKind.PARAMETER -> parameterDefinitions.getValue(ref.name).type
                DashboardValueRefKind.VARIABLE -> variableDefinitions.getValue(ref.name).type
            }
            val type = if (ref.endpoint == null) declared else when (declared) {
                cc.monomer.dashboard.domain.DashboardValueType.DATE_RANGE -> cc.monomer.dashboard.domain.DashboardValueType.DATE
                cc.monomer.dashboard.domain.DashboardValueType.TIMESTAMP_RANGE -> cc.monomer.dashboard.domain.DashboardValueType.TIMESTAMP
                else -> error("SQL endpoint requires a range")
            }
            val source = when (ref.kind) {
                DashboardValueRefKind.PARAMETER -> parameters.getValue(ref.name)
                DashboardValueRefKind.VARIABLE -> variables.getValue(ref.name)
            }
            val value = if (source is JsonNull || ref.endpoint == null) source else
                (source as JsonObject).getValue(if (ref.endpoint == DashboardRangeEndpoint.START) "start" else "end")
            if (value is JsonNull) DashboardBindValue.Null(type) else when (type) {
                cc.monomer.dashboard.domain.DashboardValueType.TEXT -> DashboardBindValue.Text(value.jsonPrimitive.content)
                cc.monomer.dashboard.domain.DashboardValueType.NUMBER -> DashboardBindValue.Number(value.jsonPrimitive.content)
                cc.monomer.dashboard.domain.DashboardValueType.BOOLEAN -> DashboardBindValue.Boolean(DashboardJson.boolean(value, "SQL binding $alias"))
                cc.monomer.dashboard.domain.DashboardValueType.DATE -> DashboardBindValue.Date(DashboardJson.date(value, "SQL binding $alias"))
                cc.monomer.dashboard.domain.DashboardValueType.TIMESTAMP -> DashboardBindValue.Timestamp(DashboardJson.timestamp(value, "SQL binding $alias"))
                else -> error("SQL binding must be scalar")
            }
        }, query.rowLimit)
    }

    fun referencesParameter(query: JsonObject, parameterName: String): Boolean {
        val filters = query["filters"] as? JsonArray ?: error("query filters must be an array")
        if (filters.any { filter ->
                val value = (filter as? JsonObject)?.get("value")
                value?.isReference("PARAMETER", parameterName) == true
            }
        ) return true
        val range = query["metricTimeRange"] as? JsonObject ?: return false
        return range.isReference("PARAMETER", parameterName) || range.values.any { it.isReference("PARAMETER", parameterName) }
    }

    fun resolve(
        query: JsonObject,
        parameters: Map<String, JsonElement>,
        variables: Map<String, JsonElement>,
        parameterDefinitions: Map<String, cc.monomer.dashboard.domain.DashboardParameterDefinition>,
        variableDefinitions: Map<String, cc.monomer.dashboard.domain.DashboardVariableDefinition>,
    ): JsonObject? {
        val filters = query.getValue("filters") as? JsonArray ?: error("query filters must be an array")
        val resolvedFilters = buildList {
            filters.forEach { rawFilter ->
                val filter = rawFilter as? JsonObject ?: error("query filter must be an object")
                val operator = filter.getValue("operator").jsonPrimitive.content
                val resolved = resolveValueSource(
                    element = filter.getValue("value"),
                    parameters = parameters,
                    variables = variables,
                    emptyBehavior = filter["emptyBehavior"]?.jsonPrimitive?.content,
                )
                when (resolved) {
                    is ValueResolution.Omitted -> Unit
                    is ValueResolution.Rejected -> return null
                    is ValueResolution.Value -> {
                        val values = when {
                            operator == "IS_NULL" || operator == "IS_NOT_NULL" -> JsonArray(emptyList())
                            operator == "IN" || operator == "NOT_IN" -> resolved.value as? JsonArray
                                ?: return null
                            resolved.value is JsonNull -> return null
                            else -> JsonArray(listOf(resolved.value))
                        }
                        add(buildJsonObject {
                            filter.forEach { (key, value) ->
                                if (key != "value" && key != "emptyBehavior") put(key, value)
                            }
                            put("values", values)
                        })
                    }
                }
            }
        }
        val range = query["metricTimeRange"]
        val resolvedRange = when {
            range == null || range is JsonNull -> range
            else -> {
                val rangeObject = range as? JsonObject ?: error("metricTimeRange must be an object or null")
                if (rangeObject.isValueReference()) {
                    resolveWholeMetricTimeRange(
                        rangeObject,
                        parameters,
                        variables,
                        parameterDefinitions,
                        variableDefinitions,
                        "metricTimeRange",
                    )
                } else {
                    val start = resolveMetricTimeEndpoint(
                        rangeObject.getValue("start"),
                        parameters,
                        variables,
                        parameterDefinitions,
                        variableDefinitions,
                        "metricTimeRange.start",
                    ) ?: return null
                    val end = resolveMetricTimeEndpoint(
                        rangeObject.getValue("end"),
                        parameters,
                        variables,
                        parameterDefinitions,
                        variableDefinitions,
                        "metricTimeRange.end",
                    ) ?: return null
                    JsonObject(rangeObject + ("start" to start) + ("end" to end))
                }
            }
        }
        return JsonObject(query + ("filters" to JsonArray(resolvedFilters)) + ("metricTimeRange" to (resolvedRange ?: JsonNull)))
    }

    private fun resolveMetricTimeEndpoint(
        element: JsonElement,
        parameters: Map<String, JsonElement>,
        variables: Map<String, JsonElement>,
        parameterDefinitions: Map<String, cc.monomer.dashboard.domain.DashboardParameterDefinition>,
        variableDefinitions: Map<String, cc.monomer.dashboard.domain.DashboardVariableDefinition>,
        context: String,
    ): JsonObject? {
        if (element !is JsonObject || element["kind"]?.jsonPrimitive?.content !in setOf("PARAMETER", "VARIABLE")) {
            return element as? JsonObject ?: error("$context must be an endpoint object")
        }
        val kind = element.getValue("kind").jsonPrimitive.content
        val name = element.getValue("name").jsonPrimitive.content
        val declarationType = (if (kind == "PARAMETER") parameterDefinitions[name]?.type else variableDefinitions[name]?.type)
            ?: error("$context references unknown $kind $name")
        val type = if ("endpoint" in element) {
            when (declarationType) {
                cc.monomer.dashboard.domain.DashboardValueType.DATE_RANGE -> cc.monomer.dashboard.domain.DashboardValueType.DATE
                cc.monomer.dashboard.domain.DashboardValueType.TIMESTAMP_RANGE -> cc.monomer.dashboard.domain.DashboardValueType.TIMESTAMP
                else -> error("$context.endpoint requires a DATE_RANGE or TIMESTAMP_RANGE value")
            }
        } else declarationType
        require(type == cc.monomer.dashboard.domain.DashboardValueType.DATE || type == cc.monomer.dashboard.domain.DashboardValueType.TIMESTAMP) {
            "$context must reference a DATE or TIMESTAMP value"
        }
        val value = when (val resolved = resolveValueSource(element, parameters, variables, "ERROR")) {
            is ValueResolution.Value -> resolved.value
            else -> return null
        }
        require(value is JsonPrimitive && value.isString) { "$context must resolve to a string" }
        return buildJsonObject {
            put("kind", if (type == cc.monomer.dashboard.domain.DashboardValueType.DATE) "DATE" else "TIMESTAMP")
            put("value", value)
        }
    }

    private fun resolveWholeMetricTimeRange(
        element: JsonObject,
        parameters: Map<String, JsonElement>,
        variables: Map<String, JsonElement>,
        parameterDefinitions: Map<String, cc.monomer.dashboard.domain.DashboardParameterDefinition>,
        variableDefinitions: Map<String, cc.monomer.dashboard.domain.DashboardVariableDefinition>,
        context: String,
    ): JsonElement {
        val kind = element.getValue("kind").jsonPrimitive.content
        val name = element.getValue("name").jsonPrimitive.content
        val type = (if (kind == "PARAMETER") parameterDefinitions[name]?.type else variableDefinitions[name]?.type)
            ?: error("$context references unknown $kind $name")
        require(type == cc.monomer.dashboard.domain.DashboardValueType.DATE_RANGE ||
            type == cc.monomer.dashboard.domain.DashboardValueType.TIMESTAMP_RANGE) {
            "$context must reference a DATE_RANGE or TIMESTAMP_RANGE value"
        }
        val value = (if (kind == "PARAMETER") parameters[name] else variables[name])
            ?: error("missing $kind value: $name")
        if (value is JsonNull) return JsonNull
        val range = value as? JsonObject ?: error("$context must resolve to a range object")
        val start = range.getValue("start")
        val end = range.getValue("end")
        require(start is JsonPrimitive && start.isString && end is JsonPrimitive && end.isString) {
            "$context must resolve to string endpoints"
        }
        val endpointKind = if (type == cc.monomer.dashboard.domain.DashboardValueType.DATE_RANGE) "DATE" else "TIMESTAMP"
        return buildJsonObject {
            put("start", buildJsonObject {
                put("kind", endpointKind)
                put("value", start)
            })
            put("end", buildJsonObject {
                put("kind", endpointKind)
                put("value", end)
            })
        }
    }

    private fun resolveValueSource(
        element: JsonElement,
        parameters: Map<String, JsonElement>,
        variables: Map<String, JsonElement>,
        emptyBehavior: String?,
    ): ValueResolution {
        if (element is JsonObject && element["kind"]?.jsonPrimitive?.content in setOf("PARAMETER", "VARIABLE")) {
            val kind = element.getValue("kind").jsonPrimitive.content
            val name = element.getValue("name").jsonPrimitive.content
            val value = (if (kind == "PARAMETER") parameters[name] else variables[name])
                ?: error("missing $kind value: $name")
            if (value is JsonNull && emptyBehavior == "OMIT") return ValueResolution.Omitted
            if (value is JsonNull) return ValueResolution.Rejected
            val endpoint = element["endpoint"]?.jsonPrimitive?.content
            val resolvedValue = if (endpoint == null) value else {
                require(endpoint in setOf("start", "end")) { "reference endpoint must be start or end" }
                (value as? JsonObject ?: error("endpoint reference must resolve from a range object")).getValue(endpoint)
            }
            return ValueResolution.Value(resolvedValue)
        }
        return ValueResolution.Value(element)
    }

    private sealed interface ValueResolution {
        data class Value(val value: JsonElement) : ValueResolution
        data object Omitted : ValueResolution
        data object Rejected : ValueResolution
    }

    private fun JsonElement.isReference(kind: String, name: String): Boolean =
        this is JsonObject && this["kind"]?.jsonPrimitive?.content == kind && this["name"]?.jsonPrimitive?.content == name

    private fun JsonObject.isValueReference(): Boolean =
        this["kind"]?.jsonPrimitive?.content in setOf("PARAMETER", "VARIABLE") && "name" in this
}

internal fun expectedColumnNames(query: DashboardNamedQuery): List<String> = when (query) {
    is DashboardNamedQuery.Sql -> query.columns
    is DashboardNamedQuery.Semantic -> cc.monomer.semantic.api.SemanticResultColumns.fromKeys(query.resultColumnNames().map { cc.monomer.semantic.api.SemanticResultColumnKey(it) }).map { it.sqlName }
}
