@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

import cc.monomer.dashboard.api.ApplyDraftCommand
import cc.monomer.dashboard.api.DiscardDraftCommand
import cc.monomer.dashboard.api.CancelDashboardDraftRunCommand
import cc.monomer.dashboard.api.CreateDashboardCommand
import cc.monomer.dashboard.api.DashboardOwnerDashboard
import cc.monomer.dashboard.api.DashboardOwnerOutcome
import cc.monomer.dashboard.api.DashboardOwnerPort
import cc.monomer.dashboard.api.DashboardOwnerDraft
import cc.monomer.dashboard.api.DashboardOwnerQueryRun
import cc.monomer.dashboard.api.DashboardOwnerReadyResult
import cc.monomer.dashboard.api.DashboardOwnerCurrentQueryRun
import cc.monomer.dashboard.api.DashboardOwnerDashboardListItem
import cc.monomer.dashboard.api.DashboardOwnerDashboardPage
import cc.monomer.dashboard.api.DashboardOwnerDashboardPageCursor
import cc.monomer.dashboard.api.DashboardOwnerQueryRunPage
import cc.monomer.dashboard.api.DashboardOwnerQueryRunPageCursor
import cc.monomer.dashboard.api.DashboardOwnerResultLookup
import cc.monomer.dashboard.api.DashboardOwnerResultLookupOutcome
import cc.monomer.dashboard.api.DashboardOwnerSession
import cc.monomer.dashboard.api.DashboardOwnerSessionPage
import cc.monomer.dashboard.api.DashboardOwnerSessionPageCursor
import cc.monomer.dashboard.api.DashboardOwnerSessionResolution
import cc.monomer.dashboard.application.SessionResolutionResult
import cc.monomer.dashboard.api.DashboardOwnerSessionSummary
import cc.monomer.dashboard.api.ResolveDashboardSessionCommand
import cc.monomer.dashboard.api.ListDashboardSessionQueryRunsCommand
import cc.monomer.dashboard.api.ListDashboardSessionsCommand
import cc.monomer.dashboard.api.ListDraftQueryRunsCommand
import cc.monomer.dashboard.api.OpenDashboardSessionCommand
import cc.monomer.dashboard.api.ReadDashboardDraftResultCommand
import cc.monomer.dashboard.api.ReadDashboardDraftRunCommand
import cc.monomer.dashboard.api.ReadDashboardSessionResultCommand
import cc.monomer.dashboard.api.ReadDashboardSessionQueryRunCommand
import cc.monomer.dashboard.api.RefreshDraftCommand
import cc.monomer.dashboard.api.RefreshDashboardSessionCommand
import cc.monomer.dashboard.api.ReplaceDraftDocumentCommand
import cc.monomer.dashboard.api.StartDraftCommand
import cc.monomer.dashboard.application.DashboardDraftUseCase
import cc.monomer.dashboard.application.DashboardResult
import cc.monomer.dashboard.application.DashboardPageCursor
import cc.monomer.dashboard.application.DashboardQueryRunPageCursor
import cc.monomer.dashboard.application.DashboardQueryRunPageResult
import cc.monomer.dashboard.application.DashboardSessionPageCursor
import cc.monomer.dashboard.application.DashboardSessionPageResult
import cc.monomer.dashboard.application.DashboardSessionUseCase
import cc.monomer.dashboard.application.DashboardUseCase
import cc.monomer.dashboard.application.DraftApplyResult
import cc.monomer.dashboard.application.QueryRunLookupResult
import cc.monomer.dashboard.application.DraftResult
import cc.monomer.dashboard.application.SessionResult
import cc.monomer.dashboard.domain.Dashboard
import cc.monomer.dashboard.domain.DashboardDraft
import cc.monomer.dashboard.domain.DashboardSession
import cc.monomer.dashboard.domain.QueryRun

import cc.monomer.dashboard.api.*
import cc.monomer.dashboard.application.DashboardLifecycleService

class DashboardOwnerPortAdapter(
    private val dashboardUseCase: DashboardUseCase,
    private val draftUseCase: DashboardDraftUseCase,
    private val sessionUseCase: DashboardSessionUseCase,
    private val blockingDispatcher: CoroutineDispatcher,
    private val lifecycle: DashboardLifecycleService,
) : DashboardOwnerPort {
    override suspend fun delete(command: DeleteDashboardCommand): DashboardOwnerOutcome<Unit> = lifecycle.delete(command)
    override suspend fun listHistory(command: ListDashboardHistoryCommand): DashboardOwnerOutcome<DashboardOwnerHistoryPage> = lifecycle.listHistory(command)
    override suspend fun readHistory(command: ReadDashboardHistoryCommand): DashboardOwnerOutcome<DashboardOwnerDefinitionChange> = lifecycle.readHistory(command)
    override suspend fun restore(command: RestoreDashboardCommand): DashboardOwnerOutcome<DashboardOwnerDashboard> =
        when (val result = lifecycle.restore(command)) {
            is DashboardOwnerOutcome.Success -> DashboardOwnerOutcome.Success(result.value.toOwnerDashboard())
            DashboardOwnerOutcome.NotFound -> DashboardOwnerOutcome.NotFound
            is DashboardOwnerOutcome.Conflict -> result
            is DashboardOwnerOutcome.Rejected -> result
        }

    override suspend fun readCurrentSemanticReferences(workspaceId: cc.monomer.identity.api.WorkspaceId): List<cc.monomer.dashboard.api.DashboardCurrentSemanticReferences> =
        readBlocking { dashboardUseCase.readCurrentSemanticReferences(workspaceId) }

    private suspend fun <T> readBlocking(action: suspend () -> T): T =
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) action()
        else withContext(blockingDispatcher) { action() }

    override suspend fun create(command: CreateDashboardCommand): DashboardOwnerOutcome<DashboardOwnerDashboard> {
        val result = try {
            dashboardUseCase.create(
                DashboardUseCase.CreateCommand(
                    workspaceId = command.workspaceId,
                    document = command.definition,
                    actorId = command.metadata.actorId,
                    recordedActorId = command.metadata.actorId,
                    idempotencyKey = command.metadata.idempotencyKey,
                    createdAt = command.metadata.requestedAt,
                    credentialSurface = command.metadata.credentialSurface,
                    agentClientId = command.metadata.agentClientId,
                ),
            )
        } catch (error: cc.monomer.dashboard.application.InvalidDashboardDefinitionException) {
            return DashboardOwnerOutcome.Rejected("INVALID_DASHBOARD_DEFINITION", error.message)
        }
        return when (result) {
            is DashboardResult.Found -> DashboardOwnerOutcome.Success(result.dashboard.toOwnerDashboard())
            DashboardResult.NotFound -> DashboardOwnerOutcome.NotFound
            DashboardResult.Conflict -> DashboardOwnerOutcome.Conflict(null)
            DashboardResult.IdempotencyConflict -> DashboardOwnerOutcome.Rejected("IDEMPOTENCY_CONFLICT", null)
        }
    }

    override suspend fun read(workspaceId: cc.monomer.identity.api.WorkspaceId, dashboardId: cc.monomer.dashboard.api.DashboardId): DashboardOwnerOutcome<DashboardOwnerDashboard> =
        when (val result = readBlocking { dashboardUseCase.get(workspaceId, dashboardId) }) {
            is DashboardResult.Found -> DashboardOwnerOutcome.Success(result.dashboard.toOwnerDashboard())
            DashboardResult.NotFound -> DashboardOwnerOutcome.NotFound
            DashboardResult.Conflict -> DashboardOwnerOutcome.Conflict(null)
            DashboardResult.IdempotencyConflict -> DashboardOwnerOutcome.Rejected("IDEMPOTENCY_CONFLICT", null)
        }

    override suspend fun listDashboards(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        cursor: DashboardOwnerDashboardPageCursor?,
        pageSize: Int,
    ): DashboardOwnerOutcome<DashboardOwnerDashboardPage> {
        val page = readBlocking { dashboardUseCase.list(
            workspaceId,
            cursor?.let { DashboardPageCursor(it.updatedAt, it.dashboardId) },
            pageSize,
        ) }
        return DashboardOwnerOutcome.Success(
            DashboardOwnerDashboardPage(
                page.items.map { item ->
                    DashboardOwnerDashboardListItem(
                        item.id,
                        item.workspaceId,
                        item.title,
                        item.description,
                        item.updatedAt,
                    )
                },
                page.nextCursor?.let { DashboardOwnerDashboardPageCursor(it.updatedAt, it.dashboardId) },
            ),
        )
    }

    override suspend fun startDraft(command: StartDraftCommand): DashboardOwnerOutcome<DashboardOwnerDraft> =
        draftUseCase.start(
            DashboardDraftUseCase.StartCommand(
                workspaceId = command.workspaceId,
                dashboardId = command.dashboardId,
                parameterBindings = command.parameterBindings,
                credential = command.metadata.credential,
                expectedOAuthResource = command.metadata.expectedOAuthResource,
                actorId = command.metadata.actorId,
                idempotencyKey = command.metadata.idempotencyKey,
                requestedAt = command.metadata.requestedAt,
            ),
        ).toOwnerDraftOutcome()

    override suspend fun readDraft(workspaceId: cc.monomer.identity.api.WorkspaceId, dashboardId: cc.monomer.dashboard.api.DashboardId): DashboardOwnerOutcome<DashboardOwnerDraft> =
        readBlocking { draftUseCase.get(workspaceId, dashboardId) }.toOwnerDraftOutcome()

    override suspend fun readDraftStatus(workspaceId: cc.monomer.identity.api.WorkspaceId, dashboardId: cc.monomer.dashboard.api.DashboardId): DashboardOwnerOutcome<DashboardOwnerDraftStatus> =
        readBlocking { draftUseCase.getStatus(workspaceId, dashboardId) }?.let { status ->
            DashboardOwnerOutcome.Success(DashboardOwnerDraftStatus(
                dashboardId = dashboardId,
                authoringRevision = status.authoringRevision,
                targetQueryRuns = status.targetQueryRuns.mapValues { (_, target) -> DashboardOwnerDraftTargetQueryRun(target.queryRunId, target.state.name) },
                readyResults = status.readyResults.mapValues { (_, value) -> value.toOwnerReadyResult() },
            ))
        } ?: DashboardOwnerOutcome.NotFound

    override suspend fun listDraftQueryRuns(command: ListDraftQueryRunsCommand): DashboardOwnerOutcome<DashboardOwnerQueryRunPage> =
        readBlocking { draftUseCase.listQueryRuns(
            command.workspaceId,
            command.dashboardId,
            command.cursor?.let { DashboardQueryRunPageCursor(it.createdAt, it.queryRunId) },
            command.pageSize,
        ) }.toOwnerQueryRunPageOutcome()

    override suspend fun replaceDraftDocument(command: ReplaceDraftDocumentCommand): DashboardOwnerOutcome<DashboardOwnerDraft> =
        draftUseCase.replaceDocument(
            DashboardDraftUseCase.ReplaceDocumentCommand(
                workspaceId = command.workspaceId,
                dashboardId = command.dashboardId,
                credential = command.metadata.credential,
                expectedOAuthResource = command.metadata.expectedOAuthResource,
                expectedRevision = command.expectedRevision,
                definition = command.definition,
                parameterBindings = command.parameterBindings,
                variables = command.variables,
                actorId = command.metadata.actorId,
                credentialSurface = command.metadata.credentialSurface,
                agentClientId = command.metadata.agentClientId,
                idempotencyKey = command.metadata.idempotencyKey,
                requestedAt = command.metadata.requestedAt,
            ),
        ).toOwnerDraftOutcome()

    override suspend fun applyDraft(command: ApplyDraftCommand): DashboardOwnerOutcome<DashboardOwnerDashboard> =
        when (val result = draftUseCase.apply(
            DashboardDraftUseCase.ApplyCommand(
                workspaceId = command.workspaceId,
                dashboardId = command.dashboardId,
                expectedRevision = command.expectedRevision,
                credential = command.metadata.credential,
                expectedOAuthResource = command.metadata.expectedOAuthResource,
                actorId = command.metadata.actorId,
                idempotencyKey = command.metadata.idempotencyKey,
                requestedAt = command.metadata.requestedAt,
                credentialSurface = command.metadata.credentialSurface,
                agentClientId = command.metadata.agentClientId,
            ),
        )) {
            is DraftApplyResult.Applied -> DashboardOwnerOutcome.Success(result.dashboard.toOwnerDashboard())
            DraftApplyResult.NotFound -> DashboardOwnerOutcome.NotFound
            is DraftApplyResult.Conflict -> DashboardOwnerOutcome.Conflict(result.currentRevision)
            is DraftApplyResult.Rejected -> DashboardOwnerOutcome.Rejected(result.code, result.message)
        }

    override suspend fun discardDraft(command: DiscardDraftCommand): DashboardOwnerOutcome<DashboardOwnerDraft> =
        draftUseCase.discard(
            DashboardDraftUseCase.DiscardCommand(
                workspaceId = command.workspaceId,
                dashboardId = command.dashboardId,
                expectedRevision = command.expectedRevision,
                credential = command.metadata.credential,
                expectedOAuthResource = command.metadata.expectedOAuthResource,
                actorId = command.metadata.actorId,
                credentialSurface = command.metadata.credentialSurface,
                agentClientId = command.metadata.agentClientId,
                idempotencyKey = command.metadata.idempotencyKey,
                requestedAt = command.metadata.requestedAt,
            ),
        ).toOwnerDraftOutcome()

    override suspend fun refreshDraft(command: RefreshDraftCommand): DashboardOwnerOutcome<DashboardOwnerDraft> =
        draftUseCase.refresh(
            DashboardDraftUseCase.RefreshCommand(
                workspaceId = command.workspaceId,
                dashboardId = command.dashboardId,
                queryNames = command.queryNames,
                credential = command.metadata.credential,
                expectedOAuthResource = command.metadata.expectedOAuthResource,
                actorId = command.metadata.actorId,
                idempotencyKey = command.metadata.idempotencyKey,
                requestedAt = command.metadata.requestedAt,
            ),
        ).toOwnerDraftOutcome()

    override suspend fun readDashboardDraftRun(command: ReadDashboardDraftRunCommand): DashboardOwnerOutcome<DashboardOwnerQueryRun> =
        when (val result = readBlocking { draftUseCase.getQueryRun(command.workspaceId, command.dashboardId, command.queryRunId) }) {
            is QueryRunLookupResult.Found -> DashboardOwnerOutcome.Success(result.queryRun.toOwnerQueryRun())
            QueryRunLookupResult.NotFound -> DashboardOwnerOutcome.NotFound
        }

    override suspend fun readDashboardDraftResult(command: ReadDashboardDraftResultCommand): DashboardOwnerResultLookupOutcome =
        when (val result = readBlocking { draftUseCase.getQueryRun(command.workspaceId, command.dashboardId, command.queryRunId) }) {
            is QueryRunLookupResult.Found -> result.queryRun.toOwnerResultLookupOutcome()
            QueryRunLookupResult.NotFound -> DashboardOwnerResultLookupOutcome.NotFound
        }

    override suspend fun cancelDashboardDraftRun(command: CancelDashboardDraftRunCommand): DashboardOwnerOutcome<DashboardOwnerDraft> =
        draftUseCase.cancel(
            DashboardDraftUseCase.CancelCommand(
                workspaceId = command.workspaceId,
                dashboardId = command.dashboardId,
                queryRunId = command.queryRunId,
                credential = command.metadata.credential,
                expectedOAuthResource = command.metadata.expectedOAuthResource,
                actorId = command.metadata.actorId,
                idempotencyKey = command.metadata.idempotencyKey,
                requestedAt = command.metadata.requestedAt,
            ),
        ).toOwnerDraftOutcome()

    override suspend fun openSession(command: OpenDashboardSessionCommand): DashboardOwnerOutcome<DashboardOwnerSession> =
        sessionUseCase.open(
            DashboardSessionUseCase.OpenSessionCommand(
                workspaceId = command.workspaceId,
                credential = command.metadata.credential,
                expectedOAuthResource = command.metadata.expectedOAuthResource,
                dashboardId = command.dashboardId,
                parameterBindings = command.parameterBindings,
                actorId = command.metadata.actorId,
                idempotencyKey = command.metadata.idempotencyKey,
                openedAt = command.metadata.requestedAt,
            ),
        ).toOwnerSessionOutcome()

    override suspend fun readSession(
        workspaceId: cc.monomer.identity.api.WorkspaceId,
        dashboardId: cc.monomer.dashboard.api.DashboardId,
        sessionId: cc.monomer.dashboard.api.DashboardSessionId,
    ): DashboardOwnerOutcome<DashboardOwnerSession> =
        readBlocking { sessionUseCase.get(workspaceId, dashboardId, sessionId) }.toOwnerSessionOutcome()

    override suspend fun listSessions(command: ListDashboardSessionsCommand): DashboardOwnerOutcome<DashboardOwnerSessionPage> =
        readBlocking { sessionUseCase.list(
            command.workspaceId,
            command.dashboardId,
            command.cursor?.let { DashboardSessionPageCursor(it.dashboardId, it.createdAt, it.sessionId) },
            command.pageSize,
        ) }.toOwnerSessionPageOutcome()

    override suspend fun resolveSession(command: ResolveDashboardSessionCommand): DashboardOwnerOutcome<DashboardOwnerSessionResolution> =
        sessionUseCase.resolve(
            DashboardSessionUseCase.ResolveCommand(
                workspaceId = command.workspaceId,
                credential = command.metadata.credential,
                expectedOAuthResource = command.metadata.expectedOAuthResource,
                sessionId = command.sessionId,
                variables = command.variables,
                dashboardId = command.dashboardId,
                actorId = command.metadata.actorId,
                idempotencyKey = command.metadata.idempotencyKey,
                requestedAt = command.metadata.requestedAt,
            ),
        ).toOwnerResolutionOutcome()

    override suspend fun resolvePublicSession(command: cc.monomer.dashboard.api.ResolvePublicDashboardSessionCommand): DashboardOwnerOutcome<DashboardOwnerSessionResolution> =
        sessionUseCase.resolvePublic(command).toOwnerResolutionOutcome()

    override suspend fun readInitialSessionResults(command: cc.monomer.dashboard.api.ReadInitialDashboardSessionResultsCommand): DashboardOwnerOutcome<cc.monomer.dashboard.api.DashboardOwnerInitialSessionResults> =
        withTimeout(java.time.Duration.between(java.time.Instant.now(), command.deadline).toMillis().coerceAtLeast(1)) {
            val bridgeContext = kotlin.coroutines.coroutineContext.minusKey(kotlin.coroutines.ContinuationInterceptor)
            runInterruptible(blockingDispatcher) {
                runBlocking(bridgeContext) { readInitialSessionResultsWithinDeadline(command) }
            }
        }

    private suspend fun readInitialSessionResultsWithinDeadline(command: cc.monomer.dashboard.api.ReadInitialDashboardSessionResultsCommand): DashboardOwnerOutcome<cc.monomer.dashboard.api.DashboardOwnerInitialSessionResults> =
        when (val result = sessionUseCase.readInitialResults(command)) {
            is cc.monomer.dashboard.application.InitialSessionResultsResult.Found -> DashboardOwnerOutcome.Success(
                cc.monomer.dashboard.api.DashboardOwnerInitialSessionResults(
                    result.requiredQueryNames,
                    result.queryRuns.mapValues { (_, run) -> DashboardOwnerResultLookup(run.toOwnerQueryRun(), requireNotNull(run.result)) },
                    result.queriesChecked,
                    result.budgetExhausted,
                ),
            )
            cc.monomer.dashboard.application.InitialSessionResultsResult.NotFound -> DashboardOwnerOutcome.NotFound
        }

    override suspend fun refreshSession(command: RefreshDashboardSessionCommand): DashboardOwnerOutcome<DashboardOwnerSessionResolution> =
        sessionUseCase.refresh(
            DashboardSessionUseCase.RefreshCommand(
                workspaceId = command.workspaceId,
                credential = command.metadata.credential,
                expectedOAuthResource = command.metadata.expectedOAuthResource,
                dashboardId = command.dashboardId,
                sessionId = command.sessionId,
                queryNames = command.queryNames,
                variables = command.variables,
                actorId = command.metadata.actorId,
                idempotencyKey = command.metadata.idempotencyKey,
                requestedAt = command.metadata.requestedAt,
            ),
        ).toOwnerResolutionOutcome()

    override suspend fun listSessionQueryRuns(command: ListDashboardSessionQueryRunsCommand): DashboardOwnerOutcome<DashboardOwnerQueryRunPage> =
        readBlocking { sessionUseCase.listQueryRuns(
            command.workspaceId,
            command.dashboardId,
            command.sessionId,
            command.cursor?.let { DashboardQueryRunPageCursor(it.createdAt, it.queryRunId) },
            command.pageSize,
        ) }.toOwnerQueryRunPageOutcome()

    override suspend fun readSessionQueryRun(command: ReadDashboardSessionQueryRunCommand): DashboardOwnerOutcome<DashboardOwnerQueryRun> =
        when (val result = readBlocking { sessionUseCase.getQueryRun(command.workspaceId, command.dashboardId, command.sessionId, command.queryRunId) }) {
            is QueryRunLookupResult.Found -> DashboardOwnerOutcome.Success(result.queryRun.toOwnerQueryRun())
            QueryRunLookupResult.NotFound -> DashboardOwnerOutcome.NotFound
        }

    override suspend fun readSessionResult(command: ReadDashboardSessionResultCommand): DashboardOwnerResultLookupOutcome =
        when (val result = readBlocking { sessionUseCase.getQueryRun(command.workspaceId, command.dashboardId, command.sessionId, command.queryRunId) }) {
            is QueryRunLookupResult.Found -> result.queryRun.toOwnerResultLookupOutcome()
            QueryRunLookupResult.NotFound -> DashboardOwnerResultLookupOutcome.NotFound
        }
}

private fun Dashboard.toOwnerDashboard() = DashboardOwnerDashboard(
    id, workspaceId, definition.document, createdAt, updatedAt, updatedBy, credentialSurface, agentClientId,
)

private fun DraftResult.toOwnerDraftOutcome(): DashboardOwnerOutcome<DashboardOwnerDraft> = when (this) {
    is DraftResult.Found -> DashboardOwnerOutcome.Success(draft.toOwnerDraft(queryRuns))
    DraftResult.NotFound -> DashboardOwnerOutcome.NotFound
    is DraftResult.Conflict -> DashboardOwnerOutcome.Conflict(currentRevision)
    is DraftResult.Rejected -> DashboardOwnerOutcome.Rejected(code, message)
}

private fun DashboardQueryRunPageResult.toOwnerQueryRunPageOutcome(): DashboardOwnerOutcome<DashboardOwnerQueryRunPage> = when (this) {
    is DashboardQueryRunPageResult.Found -> DashboardOwnerOutcome.Success(
        DashboardOwnerQueryRunPage(
            page.items.map(QueryRun::toOwnerQueryRun),
            page.nextCursor?.let { DashboardOwnerQueryRunPageCursor(it.createdAt, it.queryRunId) },
        ),
    )
    DashboardQueryRunPageResult.NotFound -> DashboardOwnerOutcome.NotFound
}

private fun DashboardSessionPageResult.toOwnerSessionPageOutcome(): DashboardOwnerOutcome<DashboardOwnerSessionPage> = when (this) {
    is DashboardSessionPageResult.Found -> DashboardOwnerOutcome.Success(
        DashboardOwnerSessionPage(
            page.items.map { item ->
                DashboardOwnerSessionSummary(
                    sessionId = item.id,
                    dashboardId = item.dashboardId,
                    parameterBindings = item.parameterBindings,
                    runningQueryRuns = item.runningQueryRuns.map { run ->
                        DashboardOwnerCurrentQueryRun(run.queryName, run.queryRunId, run.state.name)
                    },
                    createdAt = item.createdAt,
                    updatedAt = item.updatedAt,
                    lastQueryRunAt = item.lastQueryRunAt,
                )
            },
            page.nextCursor?.let { DashboardOwnerSessionPageCursor(it.dashboardId, it.createdAt, it.sessionId) },
        ),
    )
    DashboardSessionPageResult.NotFound -> DashboardOwnerOutcome.NotFound
}

private fun SessionResult.toOwnerSessionOutcome(): DashboardOwnerOutcome<DashboardOwnerSession> = when (this) {
    is SessionResult.Found -> DashboardOwnerOutcome.Success(session.toOwnerSession(queryRuns))
    SessionResult.NotFound -> DashboardOwnerOutcome.NotFound
    is SessionResult.Rejected -> DashboardOwnerOutcome.Rejected(code, message)
}

private fun DashboardDraft.toOwnerDraft(queryRuns: List<QueryRun>) = DashboardOwnerDraft(
    dashboardId = dashboardId,
    workspaceId = workspaceId,
    definition = definition.document,
    parameterBindings = parameterBindings,
    variables = variables,
    authoringRevision = authoringRevision,
    targetQueryRuns = targetQueryRuns,
    readyResults = readyResults.mapValues { (_, value) -> value.toOwnerReadyResult() },
    recentQueryRuns = queryRuns.map(QueryRun::toOwnerQueryRun),
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastEditedBy = lastEditedBy,
    lastEditedAt = lastEditedAt,
    lastEditSurface = lastEditSurface,
    lastEditAgentClientId = lastEditAgentClientId,
)

private fun cc.monomer.dashboard.domain.DashboardDraftReadyResult.toOwnerReadyResult() =
    DashboardOwnerReadyResult(authoringRevision, parameterBindings, variables, queryRuns)

private fun SessionResolutionResult.toOwnerResolutionOutcome(): DashboardOwnerOutcome<DashboardOwnerSessionResolution> = when (this) {
    is SessionResolutionResult.Resolved -> DashboardOwnerOutcome.Success(DashboardOwnerSessionResolution(variables, queryRuns))
    SessionResolutionResult.NotFound -> DashboardOwnerOutcome.NotFound
    is SessionResolutionResult.Rejected -> DashboardOwnerOutcome.Rejected(code, message)
}

private fun DashboardSession.toOwnerSession(queryRuns: List<QueryRun>) = DashboardOwnerSession(
    sessionId = id,
    workspaceId = workspaceId,
    dashboardId = dashboardId,
    definition = definition.document,
    parameterBindings = parameterBindings,
    initialVariables = initialVariables,
    runningQueryRuns = queryRuns.map(QueryRun::toOwnerQueryRun),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun QueryRun.toOwnerResultLookupOutcome(): DashboardOwnerResultLookupOutcome =
    result?.let { manifest ->
        DashboardOwnerResultLookupOutcome.Success(
            DashboardOwnerResultLookup(toOwnerQueryRun(), manifest),
        )
    } ?: DashboardOwnerResultLookupOutcome.Rejected("DASHBOARD_RESULT_UNAVAILABLE", "The Dashboard QueryRun has no result.")

private fun QueryRun.toOwnerQueryRun() = DashboardOwnerQueryRun(
    queryRunId = id,
    dashboardId = dashboardId,
    viewerSessionId = viewerSessionId,
    queryName = queryName,
    queryType = queryType.name,
    connectorId = connectorId,
    state = state.name,
    resultAvailable = result != null,
    failureCode = failureCode,
    failureMessage = dashboardFailureMessage(failureCode, failureMessage),
    outputColumnNames = outputColumnNames,
    createdAt = createdAt,
    updatedAt = updatedAt,
    actorId = actorId,
    credentialSurface = credentialSurface,
    agentClientId = agentClientId,
    outputColumnDisplays = outputColumnDisplays,
    reportingTimeZone = reportingTimeZone,
)
