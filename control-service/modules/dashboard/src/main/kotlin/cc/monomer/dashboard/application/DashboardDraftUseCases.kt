@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.application

import cc.monomer.dashboard.domain.DashboardNamedQuery
import cc.monomer.dashboard.domain.DashboardResolvedQuery
import cc.monomer.dashboard.domain.DashboardQueryType

import cc.monomer.dashboard.api.DashboardSearchProjection
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.dashboard.domain.Dashboard
import cc.monomer.dashboard.domain.DashboardDefinition
import cc.monomer.dashboard.domain.DashboardDraft
import cc.monomer.dashboard.domain.DashboardDraftReadyResult
import cc.monomer.dashboard.domain.DraftMutationResult
import cc.monomer.dashboard.domain.QueryRun
import cc.monomer.dashboard.domain.QueryRunState
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

interface DashboardDraftUseCase {
    suspend fun start(command: StartCommand): DraftResult
    suspend fun get(workspaceId: WorkspaceId, dashboardId: DashboardId): DraftResult
    suspend fun getStatus(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardDraftStatus?
    suspend fun replace(command: ReplaceCommand): DraftResult
    suspend fun replaceDocument(command: ReplaceDocumentCommand): DraftResult
    suspend fun setParameter(command: SetValueCommand): DraftResult
    suspend fun setVariables(command: SetVariablesCommand): DraftResult
    suspend fun apply(command: ApplyCommand): DraftApplyResult
    suspend fun discard(command: DiscardCommand): DraftResult
    suspend fun refresh(command: RefreshCommand): DraftResult
    suspend fun getQueryRun(workspaceId: WorkspaceId, dashboardId: DashboardId, queryRunId: QueryRunId): QueryRunLookupResult
    suspend fun listQueryRuns(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        cursor: DashboardQueryRunPageCursor?,
        pageSize: Int,
    ): DashboardQueryRunPageResult
    suspend fun cancel(command: CancelCommand): DraftResult

    data class StartCommand(
        override val workspaceId: WorkspaceId,
        override val dashboardId: DashboardId,
        override val credential: PresentedCredential,
        override val expectedOAuthResource: ExpectedOAuthResource?,
        val parameterBindings: Map<String, JsonElement>,
        override val actorId: String,
        override val idempotencyKey: String,
        override val requestedAt: Instant,
    ) : ReceiptCommand {
        override val commandKind: String = "START_DRAFT"
    }

    data class ReplaceCommand(
        override val workspaceId: WorkspaceId,
        override val dashboardId: DashboardId,
        override val credential: PresentedCredential,
        override val expectedOAuthResource: ExpectedOAuthResource?,
        val expectedRevision: Long,
        val definition: JsonObject,
        val parameterBindings: Map<String, JsonElement>,
        override val actorId: String,
        val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
        val agentClientId: cc.monomer.identity.api.AgentClientId?,
        override val idempotencyKey: String,
        override val requestedAt: Instant,
    ) : ReceiptCommand {
        override val commandKind: String = "REPLACE_DRAFT_DEFINITION"
    }

    data class ReplaceDocumentCommand(
        override val workspaceId: WorkspaceId,
        override val dashboardId: DashboardId,
        override val credential: PresentedCredential,
        override val expectedOAuthResource: ExpectedOAuthResource?,
        val expectedRevision: Long,
        val definition: JsonObject,
        val parameterBindings: Map<String, JsonElement>,
        val variables: Map<String, JsonElement>,
        override val actorId: String,
        val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
        val agentClientId: cc.monomer.identity.api.AgentClientId?,
        override val idempotencyKey: String,
        override val requestedAt: Instant,
    ) : ReceiptCommand {
        override val commandKind: String = "REPLACE_DRAFT_DOCUMENT"
    }

    data class SetValueCommand(
        override val workspaceId: WorkspaceId,
        override val dashboardId: DashboardId,
        override val credential: PresentedCredential,
        override val expectedOAuthResource: ExpectedOAuthResource?,
        val name: String,
        val value: JsonElement,
        val expectedRevision: Long,
        override val actorId: String,
        override val idempotencyKey: String,
        override val requestedAt: Instant,
    ) : ReceiptCommand {
        override val commandKind: String = "SET_DRAFT_VALUE"
    }

    data class SetVariablesCommand(
        override val workspaceId: WorkspaceId,
        override val dashboardId: DashboardId,
        override val credential: PresentedCredential,
        override val expectedOAuthResource: ExpectedOAuthResource?,
        val variables: Map<String, JsonElement>,
        val expectedRevision: Long,
        override val actorId: String,
        override val idempotencyKey: String,
        override val requestedAt: Instant,
    ) : ReceiptCommand {
        override val commandKind: String = "SET_DRAFT_VARIABLES"
    }

    data class ApplyCommand(
        override val workspaceId: WorkspaceId,
        override val dashboardId: DashboardId,
        override val credential: PresentedCredential,
        override val expectedOAuthResource: ExpectedOAuthResource?,
        val expectedRevision: Long,
        override val actorId: String,
        override val idempotencyKey: String,
        override val requestedAt: Instant,
        val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
        val agentClientId: cc.monomer.identity.api.AgentClientId?,
    ) : ReceiptCommand {
        override val commandKind: String = "APPLY_DRAFT"
    }

    data class DiscardCommand(
        override val workspaceId: WorkspaceId,
        override val dashboardId: DashboardId,
        override val credential: PresentedCredential,
        override val expectedOAuthResource: ExpectedOAuthResource?,
        val expectedRevision: Long,
        override val actorId: String,
        val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
        val agentClientId: cc.monomer.identity.api.AgentClientId?,
        override val idempotencyKey: String,
        override val requestedAt: Instant,
    ) : ReceiptCommand {
        override val commandKind: String = "DISCARD_DRAFT"
    }

    data class RefreshCommand(
        override val workspaceId: WorkspaceId,
        override val dashboardId: DashboardId,
        override val credential: PresentedCredential,
        override val expectedOAuthResource: ExpectedOAuthResource?,
        val queryNames: Set<String>,
        override val actorId: String,
        override val idempotencyKey: String,
        override val requestedAt: Instant,
    ) : ReceiptCommand {
        override val commandKind: String = "REFRESH_DRAFT"
        init { require(queryNames.isNotEmpty()) }
    }

    data class CancelCommand(
        override val workspaceId: WorkspaceId,
        override val dashboardId: DashboardId,
        override val credential: PresentedCredential,
        override val expectedOAuthResource: ExpectedOAuthResource?,
        val queryRunId: QueryRunId,
        override val actorId: String,
        override val idempotencyKey: String,
        override val requestedAt: Instant,
    ) : ReceiptCommand {
        override val commandKind: String = "CANCEL_DRAFT_QUERY_RUN"
    }
}

interface ReceiptCommand {
    val workspaceId: WorkspaceId
    val dashboardId: DashboardId
    val credential: PresentedCredential
    val expectedOAuthResource: ExpectedOAuthResource?
    val actorId: String
    val idempotencyKey: String
    val requestedAt: Instant
    val commandKind: String
}

sealed interface DraftResult {
    data class Found(val draft: DashboardDraft, val queryRuns: List<QueryRun>) : DraftResult
    data object NotFound : DraftResult
    data class Conflict(val currentRevision: Long) : DraftResult
    data class Rejected(val code: String, val message: String?) : DraftResult
}

sealed interface DraftApplyResult {
    data class Applied(val dashboard: Dashboard) : DraftApplyResult
    data object NotFound : DraftApplyResult
    data class Conflict(val currentRevision: Long) : DraftApplyResult
    data class Rejected(val code: String, val message: String?) : DraftApplyResult
}

sealed interface QueryRunLookupResult {
    data class Found(val queryRun: QueryRun) : QueryRunLookupResult
    data object NotFound : QueryRunLookupResult
}

class DashboardDraftService(
    private val searchProjection: DashboardSearchProjection,
    private val transactionPort: DashboardTransactionPort,
    private val dashboardRepository: DashboardRepository,
    private val draftRepository: DashboardDraftRepository,
    private val queryRunRepository: QueryRunRepository,
    private val commandReceiptRepository: DashboardCommandReceiptRepository,
    private val queryPlanningPort: DashboardQueryPlanningPort,
    private val executionAdmissionPort: DashboardExecutionAdmissionPort,
    private val executionCancellationPort: DashboardExecutionCancellationPort,
) : DashboardDraftUseCase {
    override suspend fun start(command: DashboardDraftUseCase.StartCommand): DraftResult {
        val request = buildJsonObject {
            put("dashboardId", command.dashboardId.toString())
            put("parameterBindings", buildJsonObject {
                command.parameterBindings.forEach { (name, value) -> put(name, value) }
            })
        }
        replay(command, request)?.let { return it }
        val dashboard = dashboardRepository.find(command.workspaceId, command.dashboardId) ?: return DraftResult.NotFound
        draftRepository.findDraft(command.workspaceId, command.dashboardId)?.let { return found(it) }
        val draft = try {
            DashboardDraft.start(
                workspaceId = command.workspaceId,
                dashboardId = command.dashboardId,
                definition = dashboard.definition,
                parameterBindings = command.parameterBindings,
                startedAt = command.requestedAt,
            )
        } catch (error: IllegalArgumentException) {
            return DraftResult.Rejected("INVALID_DRAFT_PARAMETERS", error.message)
        }
        val plans = when (val result = planQueries(
            draft,
            draft.definition.queries.keys,
            forceRefresh = true,
            credential = command.credential,
            expectedOAuthResource = command.expectedOAuthResource,
        )) {
            is QueryPlanResult.Planned -> result.plans
            is QueryPlanResult.Rejected -> return DraftResult.Rejected(result.code, result.message)
        }
        return try {
            transactionPort.inTransaction {
                executionAdmissionPort.lockWorkspace(command.workspaceId)
                commandReceiptRepository.lock(command.workspaceId, command.actorId, command.idempotencyKey)
                commandReceiptRepository.find(command.workspaceId, command.actorId, command.idempotencyKey)?.let { receipt ->
                    if (receipt.normalizedRequest != request) return@inTransaction DraftResult.Rejected("IDEMPOTENCY_CONFLICT", null)
                    return@inTransaction replayDraftReceipt(receipt)
                }
                if (dashboardRepository.find(command.workspaceId, command.dashboardId) == null) return@inTransaction DraftResult.NotFound
                draftRepository.findDraft(command.workspaceId, command.dashboardId)?.let { existing ->
                    val result = found(existing)
                    insertReceipt(command, request, result, emptyList())
                    return@inTransaction result
                }
                val runs = mutableListOf<QueryRun>()
                val targets = linkedMapOf<String, QueryRunId>()
                plans.forEach { plan ->
                    val run = admitNewRun(draft, plan, command.requestedAt, null)
                    targets[plan.query.name] = run.id
                    runs += run
                }
                val withTargets = draft.withTargets(targets, command.requestedAt)
                if (!draftRepository.insert(withTargets)) throw Rollback("DRAFT_CONFLICT")
                runs.forEach { run -> if (!queryRunRepository.insert(run)) throw Rollback("QUERY_RUN_CONFLICT") }
                val result = found(withTargets)
                insertReceipt(command, request, result, runs.map(QueryRun::id))
                result
            }
        } catch (rollback: Rollback) {
            DraftResult.Rejected(rollback.code, rollback.message)
        }
    }

    override suspend fun get(workspaceId: WorkspaceId, dashboardId: DashboardId): DraftResult =
        draftRepository.findDraft(workspaceId, dashboardId)?.let(::found) ?: DraftResult.NotFound

    override suspend fun getStatus(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardDraftStatus? =
        draftRepository.findDraftStatus(workspaceId, dashboardId)

    override suspend fun replace(command: DashboardDraftUseCase.ReplaceCommand): DraftResult {
        val request = buildJsonObject {
            put("dashboardId", command.dashboardId.toString())
            put("expectedRevision", command.expectedRevision)
            put("definition", command.definition)
            put("parameterBindings", buildJsonObject {
                command.parameterBindings.forEach { (name, value) -> put(name, value) }
            })
        }
        replay(command, request)?.let { return it }
        val current = draftRepository.findDraft(command.workspaceId, command.dashboardId) ?: return DraftResult.NotFound
        val definition = try {
            DashboardDefinitionDecoder.decode(command.definition)
        } catch (error: IllegalArgumentException) {
            return DraftResult.Rejected("INVALID_DASHBOARD_DEFINITION", error.message)
        }
        val candidate = try {
            current.replaceDefinition(command.expectedRevision, definition, command.parameterBindings, command.requestedAt)
        } catch (error: IllegalArgumentException) {
            return DraftResult.Rejected("INVALID_DRAFT_PARAMETERS", error.message)
        }
        if (candidate is DraftMutationResult.Conflict) return DraftResult.Conflict(candidate.current.authoringRevision)
        val next = (candidate as DraftMutationResult.Applied).draft
        val affected = affectedQueries(current, next)
        val plans = when (val result = planQueries(
            next,
            affected,
            forceRefresh = false,
            credential = command.credential,
            expectedOAuthResource = command.expectedOAuthResource,
        )) {
            is QueryPlanResult.Planned -> result.plans
            is QueryPlanResult.Rejected -> return DraftResult.Rejected(result.code, result.message)
        }
        return reconcile(command, request, current, next, plans, affected, null)
    }

    override suspend fun replaceDocument(command: DashboardDraftUseCase.ReplaceDocumentCommand): DraftResult {
        val request = buildJsonObject {
            put("dashboardId", command.dashboardId.toString())
            put("expectedRevision", command.expectedRevision)
            put("definition", command.definition)
            put("parameterBindings", buildJsonObject { command.parameterBindings.toSortedMap().forEach { (name, value) -> put(name, value) } })
            put("variables", buildJsonObject { command.variables.toSortedMap().forEach { (name, value) -> put(name, value) } })
        }
        replay(command, request)?.let { return it }
        val current = draftRepository.findDraft(command.workspaceId, command.dashboardId) ?: return DraftResult.NotFound
        val definition = try {
            DashboardDefinitionDecoder.decode(command.definition)
        } catch (error: IllegalArgumentException) {
            return DraftResult.Rejected("INVALID_DASHBOARD_DEFINITION", error.message)
        }
        val candidate = try {
            current.replaceDocument(
                command.expectedRevision,
                definition,
                command.parameterBindings,
                command.variables,
                command.requestedAt,
            )
        } catch (error: IllegalArgumentException) {
            return DraftResult.Rejected("INVALID_DRAFT_DOCUMENT", error.message)
        }
        if (candidate is DraftMutationResult.Conflict) return DraftResult.Conflict(candidate.current.authoringRevision)
        val next = (candidate as DraftMutationResult.Applied).draft
        val affected = affectedQueries(current, next)
        val plans = when (val result = planQueries(next, affected, false, command.credential, command.expectedOAuthResource)) {
            is QueryPlanResult.Planned -> result.plans
            is QueryPlanResult.Rejected -> return DraftResult.Rejected(result.code, result.message)
        }
        return reconcile(command, request, current, next, plans, affected, null)
    }

    override suspend fun setVariables(command: DashboardDraftUseCase.SetVariablesCommand): DraftResult {
        val request = buildJsonObject {
            put("dashboardId", command.dashboardId.toString())
            put("expectedRevision", command.expectedRevision)
            put("variables", buildJsonObject { command.variables.toSortedMap().forEach { (name, value) -> put(name, value) } })
        }
        replay(command, request)?.let { return it }
        val current = draftRepository.findDraft(command.workspaceId, command.dashboardId) ?: return DraftResult.NotFound
        if (current.authoringRevision != command.expectedRevision) return DraftResult.Conflict(current.authoringRevision)
        if (command.variables.isEmpty()) return DraftResult.Rejected("INVALID_DRAFT_VALUE", "At least one Variable is required.")
        if (command.variables.keys.any { it !in current.definition.variables }) {
            return DraftResult.Rejected("UNKNOWN_DRAFT_VALUE", "An undeclared Variable cannot be updated.")
        }
        val mutation = try {
            current.replaceDocument(
                command.expectedRevision, current.definition, current.parameterBindings,
                current.variables + command.variables, command.requestedAt,
            )
        } catch (error: IllegalArgumentException) {
            return DraftResult.Rejected("INVALID_DRAFT_VALUE", error.message)
        }
        if (mutation is DraftMutationResult.Conflict) return DraftResult.Conflict(mutation.current.authoringRevision)
        val next = (mutation as DraftMutationResult.Applied).draft
        val affected = affectedQueries(current, next)
        val plans = when (val result = planQueries(next, affected, false, command.credential, command.expectedOAuthResource)) {
            is QueryPlanResult.Planned -> result.plans
            is QueryPlanResult.Rejected -> return DraftResult.Rejected(result.code, result.message)
        }
        return reconcile(command, request, current, next, plans, affected, null)
    }

    override suspend fun setParameter(command: DashboardDraftUseCase.SetValueCommand): DraftResult {
        val request = buildJsonObject {
            put("dashboardId", command.dashboardId.toString())
            put("name", command.name)
            put("value", command.value)
            put("expectedRevision", command.expectedRevision)
        }
        replay(command, request)?.let { return it }
        val current = draftRepository.findDraft(command.workspaceId, command.dashboardId) ?: return DraftResult.NotFound
        val mutation = try {
            current.setParameter(command.name, command.value, command.expectedRevision, command.requestedAt)
        } catch (error: IllegalArgumentException) {
            return DraftResult.Rejected("INVALID_DRAFT_VALUE", error.message)
        } catch (error: IllegalStateException) {
            return DraftResult.Rejected("UNKNOWN_DRAFT_VALUE", error.message)
        }
        if (mutation is DraftMutationResult.Conflict) return DraftResult.Conflict(mutation.current.authoringRevision)
        val next = (mutation as DraftMutationResult.Applied).draft
        if (next == current) return reconcile(command, request, current, next, emptyList(), emptySet(), null)
        val affected = current.definition.queries.values.filter {
            DashboardQueryValueResolver.referencesParameter(it, command.name)
        }.map { it.name }.toSet()
        val plans = when (val result = planQueries(
            next,
            affected,
            forceRefresh = false,
            credential = command.credential,
            expectedOAuthResource = command.expectedOAuthResource,
        )) {
            is QueryPlanResult.Planned -> result.plans
            is QueryPlanResult.Rejected -> return DraftResult.Rejected(result.code, result.message)
        }
        return reconcile(command, request, current, next, plans, affected, null)
    }

    override suspend fun apply(command: DashboardDraftUseCase.ApplyCommand): DraftApplyResult {
        val request = buildJsonObject {
            put("dashboardId", command.dashboardId.toString())
            put("expectedRevision", command.expectedRevision)
        }
        replayApply(command, request)?.let { return it }
        return transactionPort.inTransaction {
            executionAdmissionPort.lockWorkspace(command.workspaceId)
            commandReceiptRepository.lock(command.workspaceId, command.actorId, command.idempotencyKey)
            commandReceiptRepository.find(command.workspaceId, command.actorId, command.idempotencyKey)?.let { receipt ->
                if (receipt.normalizedRequest != request) return@inTransaction DraftApplyResult.Rejected("IDEMPOTENCY_CONFLICT", null)
                return@inTransaction replayApplyReceipt(receipt)
            }
            val draft = draftRepository.findDraftForUpdate(command.workspaceId, command.dashboardId)
                ?: return@inTransaction DraftApplyResult.NotFound
            if (draft.authoringRevision != command.expectedRevision) {
                return@inTransaction DraftApplyResult.Conflict(draft.authoringRevision)
            }
            if (!dashboardRepository.applyCurrent(
                    workspaceId = command.workspaceId,
                    dashboardId = command.dashboardId,
                    definition = draft.definition,
                    updatedAt = command.requestedAt,
                    updatedBy = command.actorId,
                    credentialSurface = command.credentialSurface,
                    agentClientId = command.agentClientId,
                )
            ) return@inTransaction DraftApplyResult.NotFound
            val dashboard = dashboardRepository.find(command.workspaceId, command.dashboardId)
                ?: return@inTransaction DraftApplyResult.NotFound
            searchProjection.upsert(dashboard.toSearchFields())
            val result = DraftApplyResult.Applied(dashboard)
            insertReceipt(command, request, result)
            result
        }
    }

    override suspend fun discard(command: DashboardDraftUseCase.DiscardCommand): DraftResult {
        val request = buildJsonObject {
            put("action", "discard")
            put("dashboardId", command.dashboardId.toString())
            put("expectedRevision", command.expectedRevision)
        }
        replay(command, request)?.let { return it }
        val current = draftRepository.findDraft(command.workspaceId, command.dashboardId) ?: return DraftResult.NotFound
        val applied = dashboardRepository.find(command.workspaceId, command.dashboardId) ?: return DraftResult.NotFound
        val mutation = try {
            current.discard(command.expectedRevision, applied.definition, command.requestedAt)
        } catch (error: IllegalArgumentException) {
            return DraftResult.Rejected("DRAFT_DISCARD_INPUT_MISSING", error.message)
        }
        if (mutation is DraftMutationResult.Conflict) return DraftResult.Conflict(mutation.current.authoringRevision)
        val next = (mutation as DraftMutationResult.Applied).draft
        val affected = affectedQueries(current, next)
        val plans = when (val result = planQueries(next, affected, false, command.credential, command.expectedOAuthResource)) {
            is QueryPlanResult.Planned -> result.plans
            is QueryPlanResult.Rejected -> return DraftResult.Rejected(result.code, result.message)
        }
        return reconcile(command, request, current, next, plans, affected, applied.definition)
    }

    override suspend fun refresh(command: DashboardDraftUseCase.RefreshCommand): DraftResult {
        val request = buildJsonObject {
            put("dashboardId", command.dashboardId.toString())
            put("queryNames", JsonArray(command.queryNames.sorted().map(::JsonPrimitive)))
        }
        replay(command, request)?.let { return it }
        val current = draftRepository.findDraft(command.workspaceId, command.dashboardId) ?: return DraftResult.NotFound
        if (!command.queryNames.all { it in current.definition.queries }) {
            return DraftResult.Rejected("UNKNOWN_QUERY", "refresh references an unknown query")
        }
        val plans = when (val result = planQueries(
            current,
            command.queryNames,
            forceRefresh = true,
            credential = command.credential,
            expectedOAuthResource = command.expectedOAuthResource,
        )) {
            is QueryPlanResult.Planned -> result.plans
            is QueryPlanResult.Rejected -> return DraftResult.Rejected(result.code, result.message)
        }
        return reconcile(command, request, current, current, plans, command.queryNames, null)
    }

    override suspend fun getQueryRun(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        queryRunId: QueryRunId,
    ): QueryRunLookupResult = queryRunRepository.find(workspaceId, dashboardId, null, queryRunId)
        ?.let(QueryRunLookupResult::Found)
        ?: QueryRunLookupResult.NotFound

    override suspend fun listQueryRuns(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        cursor: DashboardQueryRunPageCursor?,
        pageSize: Int,
    ): DashboardQueryRunPageResult {
        require(pageSize in 1..100)
        if (draftRepository.findDraft(workspaceId, dashboardId) == null) return DashboardQueryRunPageResult.NotFound
        val rows = queryRunRepository.findDraftPage(workspaceId, dashboardId, cursor, pageSize + 1)
        val items = rows.take(pageSize)
        return DashboardQueryRunPageResult.Found(
            DashboardQueryRunPage(
                items,
                if (rows.size > pageSize) items.lastOrNull()?.let { DashboardQueryRunPageCursor(it.createdAt, it.id) } else null,
            ),
        )
    }

    override suspend fun cancel(command: DashboardDraftUseCase.CancelCommand): DraftResult {
        val request = buildJsonObject {
            put("dashboardId", command.dashboardId.toString())
            put("queryRunId", command.queryRunId.toString())
        }
        replay(command, request)?.let { return it }
        return try {
            transactionPort.inTransaction {
                executionAdmissionPort.lockWorkspace(command.workspaceId)
                commandReceiptRepository.lock(command.workspaceId, command.actorId, command.idempotencyKey)
                commandReceiptRepository.find(command.workspaceId, command.actorId, command.idempotencyKey)?.let { receipt ->
                    if (receipt.normalizedRequest != request) return@inTransaction DraftResult.Rejected("IDEMPOTENCY_CONFLICT", null)
                    return@inTransaction replayDraftReceipt(receipt)
                }
                val draft = draftRepository.findDraftForUpdate(command.workspaceId, command.dashboardId)
                    ?: return@inTransaction DraftResult.NotFound
                val run = queryRunRepository.find(command.queryRunId)
                    ?.takeIf { it.workspaceId == command.workspaceId && it.dashboardId == command.dashboardId && it.viewerSessionId == null }
                    ?: return@inTransaction DraftResult.NotFound
                if (run.state == QueryRunState.RUNNING) {
                    when (executionCancellationPort.requestCancel(
                        DashboardExecutionCancellationPort.CancelCommand(
                            queryRunId = run.id,
                            workspaceId = command.workspaceId,
                            reason = "USER_REQUESTED",
                        ),
                    )) {
                        DashboardExecutionCancellationPort.CancellationOutcome.NotFound ->
                            return@inTransaction DraftResult.Rejected("QUERY_RUN_NOT_FOUND", null)
                        else -> Unit
                    }
                }
                val result = found(draft)
                insertReceipt(command, request, result, listOf(run.id))
                result
            }
        } catch (rollback: Rollback) {
            DraftResult.Rejected(rollback.code, rollback.message)
        }
    }

    private suspend fun reconcile(
        command: ReceiptCommand,
        request: JsonObject,
        previous: DashboardDraft,
        candidate: DashboardDraft,
        plans: List<QueryPlan>,
        affected: Set<String>,
        requiredApplied: DashboardDefinition?,
    ): DraftResult = try {
        transactionPort.inTransaction {
            executionAdmissionPort.lockWorkspace(command.workspaceId)
            commandReceiptRepository.lock(command.workspaceId, command.actorId, command.idempotencyKey)
            commandReceiptRepository.find(command.workspaceId, command.actorId, command.idempotencyKey)?.let { receipt ->
                if (receipt.normalizedRequest != request) return@inTransaction DraftResult.Rejected("IDEMPOTENCY_CONFLICT", null)
                return@inTransaction replayDraftReceipt(receipt)
            }
            val fresh = draftRepository.findDraftForUpdate(command.workspaceId, command.dashboardId)
                ?: return@inTransaction DraftResult.NotFound
            if (fresh.authoringRevision != previous.authoringRevision) {
                return@inTransaction DraftResult.Conflict(fresh.authoringRevision)
            }
            if (requiredApplied != null &&
                dashboardRepository.find(command.workspaceId, command.dashboardId)?.definition != requiredApplied
            ) {
                return@inTransaction DraftResult.Conflict(fresh.authoringRevision)
            }
            fresh.targetQueryRuns.filterKeys { it !in candidate.definition.queries }.values.forEach { runId ->
                cancelIfRunning(queryRunRepository.find(runId), null, fresh.workspaceId)
            }
            var targets = fresh.targetQueryRuns.filterKeys { it in candidate.definition.queries }.toMutableMap()
            val runs = mutableListOf<QueryRun>()
            plans.forEach { plan ->
                val old = targets[plan.query.name]?.let(queryRunRepository::find)
                if (plan.existing != null) {
                    targets[plan.query.name] = plan.existing.id
                    runs += plan.existing
                    cancelIfRunning(old, plan.existing.id, fresh.workspaceId)
                } else {
                    val run = admitNewRun(candidate, plan, command.requestedAt, null)
                    if (!queryRunRepository.insert(run)) throw Rollback("QUERY_RUN_CONFLICT")
                    targets[plan.query.name] = run.id
                    runs += run
                    cancelIfRunning(old, run.id, fresh.workspaceId)
                }
            }
            val retainedReady = retainDraftReadyResults(fresh, candidate)
            var updated = fresh.copy(
                definition = candidate.definition,
                parameterBindings = candidate.parameterBindings,
                variables = candidate.variables,
                authoringRevision = candidate.authoringRevision,
                updatedAt = command.requestedAt,
                targetQueryRuns = targets,
                readyResults = retainedReady,
            )
            val definitionEditSurface = when (command) {
                is DashboardDraftUseCase.ReplaceCommand -> command.credentialSurface
                is DashboardDraftUseCase.ReplaceDocumentCommand -> command.credentialSurface
                is DashboardDraftUseCase.DiscardCommand -> command.credentialSurface
                else -> null
            }
            val definitionEditAgentClientId = when (command) {
                is DashboardDraftUseCase.ReplaceCommand -> command.agentClientId
                is DashboardDraftUseCase.ReplaceDocumentCommand -> command.agentClientId
                is DashboardDraftUseCase.DiscardCommand -> command.agentClientId
                else -> null
            }
            val editsDefinition = command is DashboardDraftUseCase.ReplaceCommand ||
                command is DashboardDraftUseCase.ReplaceDocumentCommand ||
                command is DashboardDraftUseCase.DiscardCommand
            if (candidate.definition != fresh.definition && editsDefinition) {
                updated = updated.copy(lastEditedAt = command.requestedAt, lastEditedBy = command.actorId, lastEditSurface = definitionEditSurface,
                    lastEditAgentClientId = definitionEditAgentClientId)
            }
            updated = promoteReadyResults(updated, queryRunRepository, command.requestedAt)
            if (updated != fresh && !draftRepository.updateAtAuthoringRevision(fresh.authoringRevision, updated)) {
                throw Rollback("DRAFT_CONFLICT")
            }
            val result = found(updated)
            insertReceipt(command, request, result, runs.distinctBy(QueryRun::id).map(QueryRun::id))
            result
        }
    } catch (rollback: Rollback) {
        DraftResult.Rejected(rollback.code, rollback.message)
    }

    private suspend fun planQueries(
        draft: DashboardDraft,
        names: Set<String>,
        forceRefresh: Boolean,
        credential: PresentedCredential,
        expectedOAuthResource: ExpectedOAuthResource?,
    ): QueryPlanResult {
        val plans = mutableListOf<QueryPlan>()
        names.forEach { name ->
            val query = draft.definition.query(name)
            val resolved = try { DashboardQueryValueResolver.resolve(
                query,
                draft.parameterBindings,
                draft.variables,
                draft.definition.parameters,
                draft.definition.variables,
            ) } catch (error: IllegalArgumentException) {
                return QueryPlanResult.Rejected("INVALID_QUERY_BINDING", error.message)
            } ?: return QueryPlanResult.Rejected("EMPTY_VALUE_REJECTED", "query $name contains an omitted value")
            when (val result = queryPlanningPort.resolve(
                DashboardQueryPlanningPort.ResolveQueryCommand(
                    workspaceId = draft.workspaceId,
                    query = query,
                    resolvedQuery = resolved,
                    credential = credential,
                    expectedOAuthResource = expectedOAuthResource,
                    deadline = null,
                    dashboardId = draft.dashboardId,
                    readerEvidence = null,
                ),
            )) {
                is DashboardQueryResolution.Resolved -> {
                    val existing = if (!forceRefresh) {
                        queryRunRepository.findEquivalent(
                            workspaceId = draft.workspaceId,
                            dashboardId = draft.dashboardId,
                            viewerSessionId = null,
                            queryName = query.name,
                            connectorId = query.connectorId,
                            resolvedQuery = result.resolvedQuery,

                            renderedSql = result.sql,
                            outputColumnNames = result.outputColumns,
                            allowedStates = setOf(QueryRunState.SUCCEEDED),
                        )
                    } else null
                    plans += QueryPlan(query, result.takeUnless { existing != null }, existing)
                }
                is DashboardQueryResolution.Rejected -> return QueryPlanResult.Rejected(result.code, result.message)
            }
        }
        return QueryPlanResult.Planned(plans)
    }

    private fun admitNewRun(
        draft: DashboardDraft,
        plan: QueryPlan,
        at: Instant,
        viewerSessionId: cc.monomer.dashboard.api.DashboardSessionId?,
    ): QueryRun {
        val rendered = plan.rendered ?: throw Rollback("DASHBOARD_QUERY_NOT_PLANNED")
        val runId = QueryRunId.generate()
        val run = QueryRun.start(
            id = runId,
            workspaceId = draft.workspaceId,
            dashboardId = draft.dashboardId,
            viewerSessionId = viewerSessionId,
            query = plan.query,
            resolvedQuery = plan.resolvedQuery,
            renderedSql = rendered.sql,

            outputColumnNames = rendered.outputColumns,
            startedAt = at,
            actorId = rendered.actorId,
            credentialSurface = rendered.credentialSurface,
            agentClientId = rendered.agentClientId,
            outputColumnDisplays = rendered.outputColumnDisplays,
            reportingTimeZone = rendered.reportingTimeZone,
        )
        val outcome = executionAdmissionPort.admit(
            DashboardExecutionAdmissionPort.AdmissionCommand(
                queryRunId = runId,
                workspaceId = draft.workspaceId,
                connectorId = plan.query.connectorId,
                sql = rendered.sql,

                idempotencyKey = "dashboard-draft:${draft.dashboardId}:${plan.query.name}:$runId",
                resultObjectKey = "artifacts/workspaces/${draft.workspaceId}/dashboard/${draft.dashboardId}/query-runs/$runId/result.arrow",
                rowLimit = plan.resolvedQuery.rowLimit,
                bindings = plan.resolvedQuery.bindings,
                validateRawSqlBeforeRun = plan.query.type == DashboardQueryType.SQL,
                expectedColumnNames = expectedColumnNames(plan.query),
                parameterNames = rendered.parameterNames,
            ),
        )
        return when (outcome) {
            DashboardExecutionAdmissionPort.AdmissionOutcome.Admitted -> run
            DashboardExecutionAdmissionPort.AdmissionOutcome.Backpressured ->
                run.fail("EXECUTION_BACKPRESSURED", null, at)
            DashboardExecutionAdmissionPort.AdmissionOutcome.Existing ->
                run.fail("EXECUTION_ADMISSION_CONFLICT", null, at)
            is DashboardExecutionAdmissionPort.AdmissionOutcome.Rejected ->
                run.fail(outcome.code, null, at)
        }
    }

    private fun cancelIfRunning(old: QueryRun?, replacementId: QueryRunId?, workspaceId: WorkspaceId) {
        if (old?.state != QueryRunState.RUNNING || old.id == replacementId) return
        if (executionCancellationPort.requestCancel(
                DashboardExecutionCancellationPort.CancelCommand(old.id, workspaceId, "QUERY_REPLACED"),
            ) == DashboardExecutionCancellationPort.CancellationOutcome.NotFound
        ) throw Rollback("QUERY_RUN_CANCEL_CONFLICT")
    }

    private fun affectedQueries(previous: DashboardDraft, candidate: DashboardDraft): Set<String> =
        candidate.definition.queries.values.filter { query ->
            val previousQuery = previous.definition.queries[query.name]
            previousQuery == null || previousQuery != query ||
                resolveForComparison(previous, query.name) != resolveForComparison(candidate, query.name)
        }.map { it.name }.toSet()

    private fun resolveForComparison(draft: DashboardDraft, queryName: String): DashboardResolvedQuery? =
        try { DashboardQueryValueResolver.resolve(
            draft.definition.query(queryName),
            draft.parameterBindings,
            draft.variables,
            draft.definition.parameters,
            draft.definition.variables,
        ) } catch (_: IllegalArgumentException) { null }

    private fun found(draft: DashboardDraft): DraftResult.Found =
        DraftResult.Found(draft, queryRunRepository.findRecentDraftRuns(draft.workspaceId, draft.dashboardId, RECENT_RUN_LIMIT))

    private fun replay(command: ReceiptCommand, request: JsonObject): DraftResult? {
        val receipt = commandReceiptRepository.find(command.workspaceId, command.actorId, command.idempotencyKey) ?: return null
        if (receipt.normalizedRequest != request) return DraftResult.Rejected("IDEMPOTENCY_CONFLICT", null)
        return replayDraftReceipt(receipt)
    }

    private fun replayApply(command: DashboardDraftUseCase.ApplyCommand, request: JsonObject): DraftApplyResult? {
        val receipt = commandReceiptRepository.find(command.workspaceId, command.actorId, command.idempotencyKey) ?: return null
        if (receipt.normalizedRequest != request) return DraftApplyResult.Rejected("IDEMPOTENCY_CONFLICT", null)
        return replayApplyReceipt(receipt)
    }

    private fun replayDraftReceipt(receipt: CommandReceipt): DraftResult {
        if (receipt.dashboardId?.let { dashboardRepository.find(receipt.workspaceId, it) } == null) return DraftResult.Rejected("IDEMPOTENCY_CONFLICT", null)
        return when (val response = receipt.response) {
            is CommandReceiptResponse.Draft -> DraftResult.Found(response.draft, response.queryRuns)
            else -> DraftResult.Rejected("IDEMPOTENCY_RECEIPT_INVALID", null)
        }
    }

    private fun replayApplyReceipt(receipt: CommandReceipt): DraftApplyResult {
        if (receipt.dashboardId?.let { dashboardRepository.find(receipt.workspaceId, it) } == null) return DraftApplyResult.Rejected("IDEMPOTENCY_CONFLICT", null)
        return when (val response = receipt.response) {
            is CommandReceiptResponse.DraftApplied -> DraftApplyResult.Applied(response.dashboard)
            else -> DraftApplyResult.Rejected("IDEMPOTENCY_RECEIPT_INVALID", null)
        }
    }

    private fun insertReceipt(
        command: ReceiptCommand,
        request: JsonObject,
        result: DraftResult.Found,
        runIds: List<QueryRunId>,
    ) {
        if (!commandReceiptRepository.insert(
                CommandReceipt(
                    workspaceId = command.workspaceId,
                    actorId = command.actorId,
                    idempotencyKey = command.idempotencyKey,
                    commandKind = command.commandKind,
                    subjectId = command.dashboardId.toString(),
                    normalizedRequest = request,
                    dashboardId = command.dashboardId,
                    sessionId = null,
                    queryRunIds = runIds,
                    response = CommandReceiptResponse.Draft(result.draft, result.queryRuns),
                    createdAt = command.requestedAt,
                ),
            )
        ) throw Rollback("IDEMPOTENCY_CONFLICT")
    }

    private fun insertReceipt(command: DashboardDraftUseCase.ApplyCommand, request: JsonObject, result: DraftApplyResult.Applied) {
        if (!commandReceiptRepository.insert(
                CommandReceipt(
                    workspaceId = command.workspaceId,
                    actorId = command.actorId,
                    idempotencyKey = command.idempotencyKey,
                    commandKind = command.commandKind,
                    subjectId = command.dashboardId.toString(),
                    normalizedRequest = request,
                    dashboardId = command.dashboardId,
                    sessionId = null,
                    queryRunIds = emptyList(),
                    response = CommandReceiptResponse.DraftApplied(result.dashboard),
                    createdAt = command.requestedAt,
                ),
            )
        ) throw Rollback("IDEMPOTENCY_CONFLICT")
    }

    private data class QueryPlan(
        val query: cc.monomer.dashboard.domain.DashboardNamedQuery,
        val rendered: DashboardQueryResolution.Resolved?,
        val existing: QueryRun?,
    ) {
        val resolvedQuery: DashboardResolvedQuery get() = rendered?.resolvedQuery ?: requireNotNull(existing).resolvedQuery
    }

    private sealed interface QueryPlanResult {
        data class Planned(val plans: List<QueryPlan>) : QueryPlanResult
        data class Rejected(val code: String, val message: String?) : QueryPlanResult
    }

    private class Rollback(val code: String, override val message: String? = null) : RuntimeException(message)

    private companion object {
        const val RECENT_RUN_LIMIT = 20
    }
}

/** Returns only the exact target IDs whose named query runs are successful. */
internal fun resolveSuccessfulTargetQueryRuns(
    queryNames: Set<String>,
    targetQueryRuns: Map<String, QueryRunId>,
    queryRunsById: Map<QueryRunId, QueryRun>,
): Map<String, QueryRunId>? {
    if (queryNames.isEmpty()) return null
    val targetIds = linkedMapOf<String, QueryRunId>()
    queryNames.forEach { queryName ->
        targetIds[queryName] = targetQueryRuns[queryName] ?: return null
    }
    val targetRuns = linkedMapOf<String, QueryRun>()
    targetIds.forEach { (queryName, queryRunId) ->
        targetRuns[queryName] = queryRunsById[queryRunId] ?: return null
    }
    if (targetRuns.values.any { it.state != QueryRunState.SUCCEEDED || it.result == null }) return null
    if (targetRuns.any { (queryName, queryRun) -> queryRun.queryName != queryName || queryRun.id != targetIds.getValue(queryName) }) {
        return null
    }
    return targetIds
}

private fun promoteReadyResults(
    draft: DashboardDraft,
    queryRuns: QueryRunRepository,
    at: Instant,
): DashboardDraft {
    val ready = draft.readyResults.toMutableMap()
    draft.definition.widgets.values.forEach { widget ->
        val targetRunsById = widget.queryNames
            .mapNotNull { draft.targetQueryRuns[it] }
            .mapNotNull(queryRuns::find)
            .associateBy(QueryRun::id)
        val targetIds = resolveSuccessfulTargetQueryRuns(widget.queryNames, draft.targetQueryRuns, targetRunsById)
        if (targetIds != null) {
            ready[widget.id] = DashboardDraftReadyResult(
                authoringRevision = draft.authoringRevision,
                parameterBindings = draft.parameterBindings,
                variables = draft.variables,
                queryRuns = targetIds,
            )
        }
    }
    return draft.withReadyResults(ready, at)
}

internal fun retainDraftReadyResults(
    previous: DashboardDraft,
    candidate: DashboardDraft,
): Map<String, DashboardDraftReadyResult> = previous.readyResults.filter { (widgetId, ready) ->
    val oldWidget = previous.definition.widgets[widgetId]
    val nextWidget = candidate.definition.widgets[widgetId]
    oldWidget != null && nextWidget != null && oldWidget.queryNames == nextWidget.queryNames &&
        ready.parameterBindings.keys == candidate.definition.parameters.keys &&
        ready.variables.keys == candidate.definition.variables.keys
}

/** Shared terminal settlement for Viewer and Draft QueryRuns. */
class DashboardQueryRunSettlementService(
    private val queryRunRepository: QueryRunRepository,
    private val draftRepository: DashboardDraftRepository,
) : DashboardQueryRunSettlementUseCase {
    override fun settleInOwnerTransaction(command: DashboardQueryRunSettlementUseCase.Command): DashboardQueryRunSettlementUseCase.Outcome {
        val run = queryRunRepository.find(command.queryRunId) ?: return DashboardQueryRunSettlementUseCase.Outcome.NotFound
        if (run.workspaceId != command.workspaceId) return DashboardQueryRunSettlementUseCase.Outcome.NotFound
        val draft = if (run.viewerSessionId == null) {
            draftRepository.findDraftForUpdate(run.workspaceId, run.dashboardId)
                ?: return DashboardQueryRunSettlementUseCase.Outcome.Conflict
        } else null
        if (run.state != QueryRunState.RUNNING) return DashboardQueryRunSettlementUseCase.Outcome.Duplicate
        val terminal = when (command.state) {
            DashboardQueryRunSettlementUseCase.State.SUCCEEDED -> {
                val result = command.result ?: return DashboardQueryRunSettlementUseCase.Outcome.Conflict
                if (command.failureCode != null || command.failureMessage != null) return DashboardQueryRunSettlementUseCase.Outcome.Conflict
                run.succeed(result, command.settledAt)
            }
            DashboardQueryRunSettlementUseCase.State.FAILED -> {
                if (command.result != null) return DashboardQueryRunSettlementUseCase.Outcome.Conflict
                val code = command.failureCode?.takeUnless(String::isBlank)
                    ?: return DashboardQueryRunSettlementUseCase.Outcome.Conflict
                run.fail(code, command.failureMessage, command.settledAt)
            }
            DashboardQueryRunSettlementUseCase.State.CANCELLED -> {
                if (command.result != null || command.failureCode != null || command.failureMessage != null) {
                    return DashboardQueryRunSettlementUseCase.Outcome.Conflict
                }
                run.cancel(command.settledAt)
            }
        }
        if (!queryRunRepository.update(run.updatedAt, terminal)) return DashboardQueryRunSettlementUseCase.Outcome.Conflict
        if (draft != null && command.state == DashboardQueryRunSettlementUseCase.State.SUCCEEDED) {
            val updated = promoteReadyResults(draft, queryRunRepository, command.settledAt)
            if (updated != draft && !draftRepository.updateAtAuthoringRevision(draft.authoringRevision, updated)) {
                return DashboardQueryRunSettlementUseCase.Outcome.Conflict
            }
        }
        return DashboardQueryRunSettlementUseCase.Outcome.Applied
    }
}
