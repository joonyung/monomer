@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.dashboard.application.DashboardQueryRunPageResult
import cc.monomer.dashboard.application.DashboardResultReadPort
import cc.monomer.dashboard.application.DashboardSessionPageResult
import cc.monomer.dashboard.application.DashboardSessionUseCase
import cc.monomer.dashboard.application.SessionResolutionResult
import cc.monomer.dashboard.application.SessionResult
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
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@RestController
@RequestMapping("/v2/workspaces/{workspaceId}")
class DashboardSessionController(
    private val sessionUseCase: DashboardSessionUseCase,
    private val resultReadPort: DashboardResultReadPort,
    private val authorizer: DashboardRequestAuthorizer,
    private val eventHub: DashboardSessionEventHub,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
    private val principalDisplayNames: PrincipalDisplayNames,
    private val agentApps: cc.monomer.identity.api.AgentAppDisplays,
    @param:org.springframework.beans.factory.annotation.Qualifier("jdbcDispatcher") private val blockingDispatcher: kotlinx.coroutines.CoroutineDispatcher,
) {

    @PostMapping("/dashboards/{dashboardId}/sessions", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun openSession(
        @PathVariable workspaceId: String,
        @PathVariable dashboardId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody body: JsonNode,
        exchange: ServerWebExchange,
    ): ResponseEntity<DashboardSessionResponse> {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val parameters = requiredObject(body, "parameterBindings").toMap()
        return when (
            val result = sessionUseCase.open(
                DashboardSessionUseCase.OpenSessionCommand(
                    workspaceId = access.workspaceId,
                    credential = access.credential,
                    expectedOAuthResource = null,
                    dashboardId = parseDashboardId(dashboardId),
                    parameterBindings = parameters,
                    actorId = access.actorId,
                    idempotencyKey = requireKey(idempotencyKey),
                    openedAt = clock.instant(),
                ),
            )
        ) {
            is SessionResult.Found -> {
                withContext(Dispatchers.IO) { eventHub.emit(result.session.id, "SESSION_OPENED") }
                ResponseEntity.status(HttpStatus.ACCEPTED).body(result.toResponse(objectMapper, runProvenance(result.queryRuns)))
            }
            SessionResult.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
            is SessionResult.Rejected -> throw rejectionProblem(result.code, result.message)
        }
    }

    @GetMapping("/dashboards/{dashboardId}/sessions", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun listSessions(
        @PathVariable workspaceId: String,
        @PathVariable dashboardId: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) pageSize: Int?,
        exchange: ServerWebExchange,
    ): DashboardSessionPageResponse {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val parsedDashboardId = parseDashboardId(dashboardId)
        val parsedCursor = cursor?.let { decodeSessionCursor(it, parsedDashboardId) }
        val resolvedPageSize = resolvePageSize(pageSize)
        return when (
            val result = withContext(blockingDispatcher) { sessionUseCase.list(
                workspaceId = access.workspaceId,
                dashboardId = parsedDashboardId,
                cursor = parsedCursor,
                pageSize = resolvedPageSize,
            ) }
        ) {
            is DashboardSessionPageResult.Found -> result.page.toResponse(objectMapper)
            DashboardSessionPageResult.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
    }

    @GetMapping("/dashboard-sessions/{sessionId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun readSession(
        @PathVariable workspaceId: String,
        @PathVariable sessionId: String,
        exchange: ServerWebExchange,
    ): DashboardSessionResponse {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        return when (val result = withContext(blockingDispatcher) { sessionUseCase.get(access.workspaceId, parseDashboardSessionId(sessionId)) }) {
            is SessionResult.Found -> result.toResponse(objectMapper, runProvenance(result.queryRuns))
            SessionResult.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
            is SessionResult.Rejected -> throw rejectionProblem(result.code, result.message)
        }
    }

    @GetMapping("/dashboard-sessions/{sessionId}/query-runs", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun listSessionQueryRuns(
        @PathVariable workspaceId: String,
        @PathVariable sessionId: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) pageSize: Int?,
        exchange: ServerWebExchange,
    ): DashboardQueryRunPageResponse {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val parsedCursor = cursor?.let(::decodeQueryRunCursor)
        val resolvedPageSize = resolvePageSize(pageSize)
        return when (
            val result = withContext(blockingDispatcher) { sessionUseCase.listQueryRuns(
                workspaceId = access.workspaceId,
                sessionId = parseDashboardSessionId(sessionId),
                cursor = parsedCursor,
                pageSize = resolvedPageSize,
            ) }
        ) {
            is DashboardQueryRunPageResult.Found -> result.page.toResponse(runProvenance(result.page.items))
            DashboardQueryRunPageResult.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
    }

    @PostMapping("/dashboard-sessions/{sessionId}:resolve", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun resolveDashboardSession(
        @PathVariable workspaceId: String,
        @PathVariable sessionId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody body: JsonNode,
        exchange: ServerWebExchange,
    ): DashboardSessionResolutionResponse {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val parsedSessionId = parseDashboardSessionId(sessionId)
        val request = requiredBodyObject(body)
        if (request.properties().any { it.key != "variables" }) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown request field")
        val variables = requiredObject(request, "variables").toMap()
        val session = when (val result = withContext(blockingDispatcher) { sessionUseCase.get(access.workspaceId, parsedSessionId) }) {
            is SessionResult.Found -> result.session
            else -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        val response = sessionUseCase.resolve(DashboardSessionUseCase.ResolveCommand(
            access.workspaceId, access.credential, null, parsedSessionId, session.dashboardId,
            variables, access.actorId, requireKey(idempotencyKey), clock.instant(),
        )).toResponseOrThrow(objectMapper)
        withContext(Dispatchers.IO) { eventHub.emit(parsedSessionId, "QUERY_RESOLVED") }
        return response
    }

    @PostMapping("/dashboard-sessions/{sessionId}:refresh", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun refresh(
        @PathVariable workspaceId: String,
        @PathVariable sessionId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody body: JsonNode,
        exchange: ServerWebExchange,
    ): DashboardSessionResolutionResponse {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val parsedSessionId = parseDashboardSessionId(sessionId)
        val request = requiredBodyObject(body)
        if (request.properties().any { it.key !in setOf("variables", "queryNames") }) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown request field")
        val names = requiredStringArray(request, "queryNames").toSet()
        if (names.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "queryNames must not be empty")
        val variables = requiredObject(request, "variables").toMap()
        val session = when (val result = withContext(blockingDispatcher) { sessionUseCase.get(access.workspaceId, parsedSessionId) }) {
            is SessionResult.Found -> result.session
            else -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        val response = sessionUseCase.refresh(DashboardSessionUseCase.RefreshCommand(
            access.workspaceId, access.credential, null, session.dashboardId, parsedSessionId, names,
            variables, access.actorId, requireKey(idempotencyKey), clock.instant(),
        )).toResponseOrThrow(objectMapper)
        withContext(Dispatchers.IO) { eventHub.emit(parsedSessionId, "QUERY_REFRESHED") }
        return response
    }

    @GetMapping("/dashboard-sessions/{sessionId}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun events(
        @PathVariable workspaceId: String,
        @PathVariable sessionId: String,
        exchange: ServerWebExchange,
    ): Flow<ServerSentEvent<Any>> {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val parsedSessionId = parseDashboardSessionId(sessionId)
        when (withContext(blockingDispatcher) { sessionUseCase.get(access.workspaceId, parsedSessionId) }) {
            is SessionResult.Found -> Unit
            else -> throw ResponseStatusException(HttpStatus.NOT_FOUND, "Dashboard session was not found")
        }
        return channelFlow {
            val events = Channel<DashboardSessionInvalidation>(Channel.CONFLATED)
            val subscription = launch(start = CoroutineStart.UNDISPATCHED) {
                eventHub.subscribe(parsedSessionId).collect { events.send(it) }
            }
            try {
                send(
                    ServerSentEvent.builder<Any>()
                        .event("connected")
                        .data(mapOf("sessionId" to parsedSessionId.toString()))
                        .build(),
                )
                for (event in events) {
                    try {
                        val currentAccess = authorizer.requireWorkspace(exchange, workspaceId)
                        if (withContext(blockingDispatcher) { sessionUseCase.get(currentAccess.workspaceId, parsedSessionId) } !is SessionResult.Found) break
                    } catch (_: ResponseStatusException) {
                        break
                    }
                    send(
                        ServerSentEvent.builder<Any>()
                            .event("invalidate")
                            .data(
                                mapOf(
                                    "sessionId" to event.sessionId.toString(),
                                    "reason" to event.reason,
                                    "observedAt" to event.observedAt,
                                ),
                            )
                            .build(),
                    )
                }
            } finally {
                subscription.cancel()
                events.close()
            }
        }.flowOn(org.springframework.core.PropagationContextElement())
    }

    @GetMapping("/dashboard-sessions/{sessionId}/query-runs/{runId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun getDashboardSessionQueryRun(
        @PathVariable workspaceId: String,
        @PathVariable sessionId: String,
        @PathVariable runId: String,
        exchange: ServerWebExchange,
    ): QueryRunResponse {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val parsedSessionId = parseDashboardSessionId(sessionId)
        val session = when (val result = withContext(blockingDispatcher) { sessionUseCase.get(access.workspaceId, parsedSessionId) }) {
            is SessionResult.Found -> result.session
            else -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        return when (val result = withContext(blockingDispatcher) { sessionUseCase.getQueryRun(access.workspaceId, session.dashboardId, parsedSessionId, parseQueryRunId(runId)) }) {
            is cc.monomer.dashboard.application.QueryRunLookupResult.Found -> result.queryRun.toResponse(runProvenance(listOf(result.queryRun)))
            cc.monomer.dashboard.application.QueryRunLookupResult.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
    }

    @GetMapping("/dashboard-sessions/{sessionId}/query-runs/{runId}/result", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun result(
        @PathVariable workspaceId: String,
        @PathVariable sessionId: String,
        @PathVariable runId: String,
        exchange: ServerWebExchange,
    ): DashboardResultResponse {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val parsedSessionId = parseDashboardSessionId(sessionId)
        val parsedRunId = parseQueryRunId(runId)
        val session = when (val result = withContext(blockingDispatcher) { sessionUseCase.get(access.workspaceId, parsedSessionId) }) {
            is SessionResult.Found -> result.session
            else -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        val run = when (
            val result = withContext(blockingDispatcher) { sessionUseCase.getQueryRun(
                access.workspaceId,
                session.dashboardId,
                parsedSessionId,
                parsedRunId,
            ) }
        ) {
            is cc.monomer.dashboard.application.QueryRunLookupResult.Found -> result.queryRun
            cc.monomer.dashboard.application.QueryRunLookupResult.NotFound ->
                throw ResponseStatusException(HttpStatus.NOT_FOUND)
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

    private suspend fun runProvenance(runs: List<QueryRun>): DashboardProvenance = queryRunProvenance(runs, principalDisplayNames, agentApps)
}

private fun SessionResolutionResult.toResponseOrThrow(objectMapper: ObjectMapper): DashboardSessionResolutionResponse = when (this) {
    is SessionResolutionResult.Resolved -> DashboardSessionResolutionResponse(variables.mapValues { objectMapper.readTree(it.value.toString()) }, queryRuns.mapValues { it.value.toString() })
    SessionResolutionResult.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
    is SessionResolutionResult.Rejected -> throw rejectionProblem(code, message)
}
