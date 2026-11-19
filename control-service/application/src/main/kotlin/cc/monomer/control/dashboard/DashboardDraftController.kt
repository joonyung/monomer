@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.control.agentapp.responses
import cc.monomer.dashboard.application.DashboardDraftUseCase
import cc.monomer.dashboard.application.DashboardResultReadPort
import cc.monomer.dashboard.application.DraftApplyResult
import cc.monomer.dashboard.application.DraftResult
import cc.monomer.dashboard.application.QueryRunLookupResult
import cc.monomer.dashboard.domain.Dashboard
import cc.monomer.dashboard.domain.QueryRun
import cc.monomer.identity.api.PrincipalDisplayNames
import java.time.Clock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@RestController
@RequestMapping("/v2/workspaces/{workspaceId}")
class DashboardDraftController(
    private val draftUseCase: DashboardDraftUseCase,
    private val resultReadPort: DashboardResultReadPort,
    private val authorizer: DashboardRequestAuthorizer,
    private val draftEventHub: DashboardDraftEventHub,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
    private val principalDisplayNames: PrincipalDisplayNames,
    private val agentApps: cc.monomer.identity.api.AgentAppDisplays,
    @param:org.springframework.beans.factory.annotation.Qualifier("jdbcDispatcher") private val blockingDispatcher: kotlinx.coroutines.CoroutineDispatcher,
) {

    @PostMapping("/dashboards/{dashboardId}/draft", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun startDraft(
        @PathVariable workspaceId: String,
        @PathVariable dashboardId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody body: JsonNode,
        exchange: ServerWebExchange,
    ): ResponseEntity<DashboardDraftResponse> {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val parsedDashboardId = parseDashboardId(dashboardId)
        val result = draftUseCase.start(
            DashboardDraftUseCase.StartCommand(
                workspaceId = access.workspaceId,
                dashboardId = parsedDashboardId,
                parameterBindings = requiredObject(body, "parameterBindings").toMap(),
                actorId = access.actorId,
                credential = access.credential,
                expectedOAuthResource = null,
                idempotencyKey = requireKey(idempotencyKey),
                requestedAt = clock.instant(),
            ),
        )
        val found = result.asDraftFoundOrThrow()
        withContext(Dispatchers.IO) { draftEventHub.emit(parsedDashboardId, "DRAFT_STARTED") }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(draftResponse(found))
    }

    @GetMapping("/dashboards/{dashboardId}/draft", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getDraft(
        @PathVariable workspaceId: String,
        @PathVariable dashboardId: String,
        exchange: ServerWebExchange,
    ): DashboardDraftResponse {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        return withContext(blockingDispatcher) { draftUseCase.get(access.workspaceId, parseDashboardId(dashboardId)) }.asDraftFoundOrThrow().let { draftResponse(it) }
    }

    @PutMapping("/dashboards/{dashboardId}/draft", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun replaceDraft(
        @PathVariable workspaceId: String,
        @PathVariable dashboardId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody body: String,
        exchange: ServerWebExchange,
    ): DashboardDraftResponse {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val request = requiredBodyObject(strictBody(body))
        val result = draftUseCase.replace(
            DashboardDraftUseCase.ReplaceCommand(
                workspaceId = access.workspaceId,
                dashboardId = parseDashboardId(dashboardId),
                expectedRevision = request.requiredNonNegativeLong("expectedRevision"),
                definition = parseObject(request.get("definition") ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "definition is required")),
                parameterBindings = requiredObject(request, "parameterBindings").toMap(),
                actorId = access.actorId,
                credentialSurface = cc.monomer.identity.api.CredentialSurface.WEB,
                agentClientId = null,
                credential = access.credential,
                expectedOAuthResource = null,
                idempotencyKey = requireKey(idempotencyKey),
                requestedAt = clock.instant(),
            ),
        )
        val found = result.asDraftFoundOrThrow()
        withContext(Dispatchers.IO) { draftEventHub.emit(found.draft.dashboardId, "DRAFT_CHANGED") }
        return draftResponse(found)
    }

    @PostMapping("/dashboards/{dashboardId}/draft/parameters/{name}:set", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun setDraftParameter(
        @PathVariable workspaceId: String,
        @PathVariable dashboardId: String,
        @PathVariable name: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody body: JsonNode,
        exchange: ServerWebExchange,
    ): DashboardDraftResponse {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val request = requiredBodyObject(body)
        val result = draftUseCase.setParameter(
            DashboardDraftUseCase.SetValueCommand(
                workspaceId = access.workspaceId,
                dashboardId = parseDashboardId(dashboardId),
                name = name,
                value = parseJsonElement(request.get("value") ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "value is required")),
                expectedRevision = request.requiredNonNegativeLong("expectedRevision"),
                actorId = access.actorId,
                credential = access.credential,
                expectedOAuthResource = null,
                idempotencyKey = requireKey(idempotencyKey),
                requestedAt = clock.instant(),
            ),
        )
        val found = result.asDraftFoundOrThrow()
        withContext(Dispatchers.IO) { draftEventHub.emit(found.draft.dashboardId, "DRAFT_PARAMETER_CHANGED") }
        return draftResponse(found)
    }

    @PostMapping("/dashboards/{dashboardId}/draft/variables:set", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun setDraftVariables(
        @PathVariable workspaceId: String,
        @PathVariable dashboardId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody body: JsonNode,
        exchange: ServerWebExchange,
    ): DashboardDraftResponse {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val request = requiredBodyObject(body)
        val result = draftUseCase.setVariables(
            DashboardDraftUseCase.SetVariablesCommand(
                workspaceId = access.workspaceId,
                dashboardId = parseDashboardId(dashboardId),
                variables = requiredObject(request, "variables").toMap(),
                expectedRevision = request.requiredNonNegativeLong("expectedRevision"),
                actorId = access.actorId,
                credential = access.credential,
                expectedOAuthResource = null,
                idempotencyKey = requireKey(idempotencyKey),
                requestedAt = clock.instant(),
            ),
        )
        val found = result.asDraftFoundOrThrow()
        withContext(Dispatchers.IO) { draftEventHub.emit(found.draft.dashboardId, "DRAFT_VARIABLE_CHANGED") }
        return draftResponse(found)
    }

    @PostMapping("/dashboards/{dashboardId}/draft:apply", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun applyDraft(
        @PathVariable workspaceId: String,
        @PathVariable dashboardId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody body: JsonNode,
        exchange: ServerWebExchange,
    ): DashboardResponse {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val request = requiredBodyObject(body)
        return when (val result = draftUseCase.apply(
            DashboardDraftUseCase.ApplyCommand(
                workspaceId = access.workspaceId,
                dashboardId = parseDashboardId(dashboardId),
                expectedRevision = request.requiredNonNegativeLong("expectedRevision"),
                actorId = access.actorId,
                credential = access.credential,
                expectedOAuthResource = null,
                idempotencyKey = requireKey(idempotencyKey),
                requestedAt = clock.instant(),
                credentialSurface = access.credentialSurface,
                agentClientId = access.agentClientId,
            ),
        )) {
            is DraftApplyResult.Applied -> {
                withContext(Dispatchers.IO) { draftEventHub.emit(result.dashboard.id, "DRAFT_APPLIED") }
                dashboardResponse(result.dashboard)
            }
            DraftApplyResult.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
            is DraftApplyResult.Conflict -> throw DashboardRestProblem(
                HttpStatus.CONFLICT,
                "DRAFT_REVISION_CONFLICT",
                "Draft revision is stale: ${result.currentRevision}",
                mapOf("current" to mapOf("revision" to result.currentRevision.toString())),
            )
            is DraftApplyResult.Rejected -> throw rejectionProblem(result.code, result.message)
        }
    }

    @PostMapping("/dashboards/{dashboardId}/draft:discard", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun discardDraft(
        @PathVariable workspaceId: String,
        @PathVariable dashboardId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody body: JsonNode,
        exchange: ServerWebExchange,
    ): DashboardDraftResponse {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val request = requiredBodyObject(body)
        val result = draftUseCase.discard(
            DashboardDraftUseCase.DiscardCommand(
                workspaceId = access.workspaceId,
                dashboardId = parseDashboardId(dashboardId),
                expectedRevision = request.requiredNonNegativeLong("expectedRevision"),
                actorId = access.actorId,
                credentialSurface = access.credentialSurface,
                agentClientId = access.agentClientId,
                credential = access.credential,
                expectedOAuthResource = null,
                idempotencyKey = requireKey(idempotencyKey),
                requestedAt = clock.instant(),
            ),
        )
        val found = result.asDraftFoundOrThrow()
        withContext(Dispatchers.IO) { draftEventHub.emit(found.draft.dashboardId, "DRAFT_DISCARDED") }
        return draftResponse(found)
    }

    @PostMapping("/dashboards/{dashboardId}/draft:refresh", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun refreshDraft(
        @PathVariable workspaceId: String,
        @PathVariable dashboardId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody body: JsonNode,
        exchange: ServerWebExchange,
    ): DashboardDraftResponse {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val result = draftUseCase.refresh(
            DashboardDraftUseCase.RefreshCommand(
                workspaceId = access.workspaceId,
                dashboardId = parseDashboardId(dashboardId),
                queryNames = requiredStringArray(body, "queryNames").toSet(),
                actorId = access.actorId,
                credential = access.credential,
                expectedOAuthResource = null,
                idempotencyKey = requireKey(idempotencyKey),
                requestedAt = clock.instant(),
            ),
        )
        val found = result.asDraftFoundOrThrow()
        withContext(Dispatchers.IO) { draftEventHub.emit(found.draft.dashboardId, "DRAFT_REFRESHED") }
        return draftResponse(found)
    }

    @GetMapping("/dashboards/{dashboardId}/draft/query-runs/{runId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getDashboardDraftQueryRun(
        @PathVariable workspaceId: String,
        @PathVariable dashboardId: String,
        @PathVariable runId: String,
        exchange: ServerWebExchange,
    ): QueryRunResponse {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val dashboard = parseDashboardId(dashboardId)
        withContext(blockingDispatcher) { draftUseCase.get(access.workspaceId, dashboard) }.asDraftFoundOrThrow()
        val run = when (val result = withContext(blockingDispatcher) { draftUseCase.getQueryRun(access.workspaceId, dashboard, parseQueryRunId(runId)) }) {
            is QueryRunLookupResult.Found -> result.queryRun
            QueryRunLookupResult.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        return run.toResponse(runProvenance(listOf(run)))
    }

    @GetMapping("/dashboards/{dashboardId}/draft/query-runs/{runId}/result", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun draftResult(
        @PathVariable workspaceId: String,
        @PathVariable dashboardId: String,
        @PathVariable runId: String,
        exchange: ServerWebExchange,
    ): DashboardResultResponse {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val run = when (val result = withContext(blockingDispatcher) { draftUseCase.getQueryRun(access.workspaceId, parseDashboardId(dashboardId), parseQueryRunId(runId)) }) {
            is QueryRunLookupResult.Found -> result.queryRun
            QueryRunLookupResult.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        val manifest = run.result ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Query result is not available")
        val payload = try {
            resultReadPort.read(manifest).withQueryColumns(run.queryType.name, run.outputColumnNames, run.outputColumnDisplays)
        } catch (_: DashboardResultUnavailableException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Query result is temporarily unavailable")
        }
        return DashboardResultResponse(
            queryRunId = run.id.toString(),
            queryType = run.queryType.name,
            columns = payload.columns.map(DashboardResultColumnResponse::from),
            rows = payload.rows.map { row -> row.map { cell -> objectMapper.readTree(cell.toString()) } },
            reportingTimeZone = run.reportingTimeZone?.id,
        )
    }

    @PostMapping("/dashboards/{dashboardId}/draft/query-runs/{runId}:cancel", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun cancelDraftRun(
        @PathVariable workspaceId: String,
        @PathVariable dashboardId: String,
        @PathVariable runId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody(required = false) body: JsonNode?,
        exchange: ServerWebExchange,
    ): DashboardDraftResponse {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val result = draftUseCase.cancel(
            DashboardDraftUseCase.CancelCommand(
                workspaceId = access.workspaceId,
                dashboardId = parseDashboardId(dashboardId),
                queryRunId = parseQueryRunId(runId),
                actorId = access.actorId,
                credential = access.credential,
                expectedOAuthResource = null,
                idempotencyKey = requireKey(idempotencyKey),
                requestedAt = clock.instant(),
            ),
        )
        val found = result.asDraftFoundOrThrow()
        withContext(Dispatchers.IO) { draftEventHub.emit(found.draft.dashboardId, "DRAFT_QUERY_RUN_CANCEL_REQUESTED") }
        return draftResponse(found)
    }

    @GetMapping("/dashboards/{dashboardId}/draft/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun draftEvents(
        @PathVariable workspaceId: String,
        @PathVariable dashboardId: String,
        exchange: ServerWebExchange,
    ): Flow<ServerSentEvent<Any>> {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val parsedDashboardId = parseDashboardId(dashboardId)
        when (withContext(blockingDispatcher) { draftUseCase.get(access.workspaceId, parsedDashboardId) }) {
            is DraftResult.Found -> Unit
            else -> throw ResponseStatusException(HttpStatus.NOT_FOUND, "Dashboard draft was not found")
        }
        return channelFlow {
            val events = Channel<DashboardDraftInvalidation>(Channel.CONFLATED)
            val subscription = launch(start = CoroutineStart.UNDISPATCHED) {
                draftEventHub.subscribe(parsedDashboardId).collect { events.send(it) }
            }
            try {
                send(ServerSentEvent.builder<Any>().event("connected").data(mapOf("dashboardId" to parsedDashboardId.toString())).build())
                for (event in events) {
                    try {
                        val currentAccess = authorizer.requireWorkspace(exchange, workspaceId)
                        if (withContext(blockingDispatcher) { draftUseCase.get(currentAccess.workspaceId, parsedDashboardId) } !is DraftResult.Found) break
                    } catch (_: ResponseStatusException) {
                        break
                    }
                    send(
                        ServerSentEvent.builder<Any>().event("invalidate").data(
                            mapOf(
                                "dashboardId" to event.dashboardId.toString(),
                                "reason" to event.reason,
                                "observedAt" to event.observedAt,
                            ),
                        ).build(),
                    )
                }
            } finally {
                subscription.cancel()
                events.close()
            }
        }.flowOn(org.springframework.core.PropagationContextElement())
    }

    private suspend fun dashboardResponse(dashboard: Dashboard): DashboardResponse =
        dashboardResponseOf(dashboard, objectMapper, principalDisplayNames, agentApps)

    private suspend fun draftResponse(found: DraftResult.Found): DashboardDraftResponse =
        found.toResponse(
            objectMapper,
            DashboardProvenance(
                principalDisplayNames.byActorId(listOf(found.draft.lastEditedBy) + found.queryRuns.map(QueryRun::actorId)),
                agentApps.responses(listOf(found.draft.lastEditAgentClientId) + found.queryRuns.map(QueryRun::agentClientId)),
            ),
        )

    private suspend fun runProvenance(runs: List<QueryRun>): DashboardProvenance = queryRunProvenance(runs, principalDisplayNames, agentApps)
}

private fun DraftResult.asDraftFoundOrThrow(): DraftResult.Found = when (this) {
    is DraftResult.Found -> this
    DraftResult.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
    is DraftResult.Conflict -> throw DashboardRestProblem(
        HttpStatus.CONFLICT,
        "DRAFT_REVISION_CONFLICT",
        "Draft revision is stale: $currentRevision",
        mapOf("current" to mapOf("revision" to currentRevision.toString())),
    )
    is DraftResult.Rejected -> throw rejectionProblem(code, message)
}
