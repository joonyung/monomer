@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.dashboard.api.*
import cc.monomer.dashboard.application.DashboardLifecycleService
import cc.monomer.dashboard.application.DashboardResult
import cc.monomer.dashboard.application.DashboardUseCase
import cc.monomer.dashboard.application.InvalidDashboardDefinitionException
import cc.monomer.dashboard.domain.Dashboard
import cc.monomer.control.agentapp.responses
import cc.monomer.identity.api.PrincipalDisplayNames
import cc.monomer.pages.api.PagesCurrentReadPort
import java.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.withContext
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
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
import tools.jackson.databind.ObjectMapper

@RestController
@RequestMapping("/v2/workspaces/{workspaceId}")
class DashboardController(
    private val dashboardUseCase: DashboardUseCase,
    private val authorizer: DashboardRequestAuthorizer,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
    private val pages: PagesCurrentReadPort,
    private val principalDisplayNames: PrincipalDisplayNames,
    private val agentApps: cc.monomer.identity.api.AgentAppDisplays,
    @param:org.springframework.beans.factory.annotation.Qualifier("jdbcDispatcher") private val blockingDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    private val lifecycle: DashboardLifecycleService,
) {

    @DeleteMapping("/dashboards/{dashboardId}")
    suspend fun delete(@PathVariable workspaceId: String, @PathVariable dashboardId: String, exchange: ServerWebExchange): ResponseEntity<Void> {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        lifecycle.delete(DeleteDashboardCommand(access.workspaceId, parseDashboardId(dashboardId), DashboardDeletionMetadata(
            access.actorId, access.organizationId, access.credential, null, clock.instant(), access.credentialSurface, access.agentClientId,
        ))).requiredLifecycle()
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/dashboards/{dashboardId}/history", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun history(@PathVariable workspaceId: String, @PathVariable dashboardId: String,
        @RequestParam(required = false) cursor: String?, @RequestParam(defaultValue = "20") limit: Int, exchange: ServerWebExchange): DashboardHistoryResponse {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        if (limit !in 1..100) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "History limit must be between 1 and 100.")
        val id = parseDashboardId(dashboardId)
        val history = lifecycle.listHistory(ListDashboardHistoryCommand(access.workspaceId, id, decodeDashboardHistoryCursor(cursor, access.workspaceId, id), limit)).requiredLifecycle()
        return DashboardHistoryResponse(history.definitionId.toString(), history.name, history.deleted,
            history.changes.map { it.historyResponse(principalDisplayNames, agentApps, objectMapper) }, history.nextCursor?.let(::encodeDashboardHistoryCursor))
    }

    @GetMapping("/dashboards/{dashboardId}/history/{changeId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun historyVersion(@PathVariable workspaceId: String, @PathVariable dashboardId: String, @PathVariable changeId: String, exchange: ServerWebExchange): DashboardDefinitionVersionResponse {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        return lifecycle.readHistory(ReadDashboardHistoryCommand(access.workspaceId, parseDashboardId(dashboardId), parseChangeId(changeId))).requiredLifecycle()
            .historyResponse(principalDisplayNames, agentApps, objectMapper)
    }

    @PostMapping("/dashboards/{dashboardId}/history/{changeId}/restore", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun restore(@PathVariable workspaceId: String, @PathVariable dashboardId: String, @PathVariable changeId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String, exchange: ServerWebExchange): DashboardResponse {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val restored = lifecycle.restore(RestoreDashboardCommand(access.workspaceId, parseDashboardId(dashboardId), parseChangeId(changeId), DashboardOwnerCommandMetadata(
            access.actorId, access.credential, null, requireKey(idempotencyKey), clock.instant(), access.credentialSurface, access.agentClientId,
        ))).requiredLifecycle()
        return dashboardResponse(restored)
    }

    private fun parseChangeId(raw: String): Uuid = try {
        Uuid.parse(raw).also { require(it.toString() == raw && raw[14] == '7') }
    } catch (_: IllegalArgumentException) { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "changeId must be a canonical UUIDv7.") }

    private fun <T> DashboardOwnerOutcome<T>.requiredLifecycle(): T = when (this) {
        is DashboardOwnerOutcome.Success -> value
        DashboardOwnerOutcome.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
        is DashboardOwnerOutcome.Conflict -> throw ResponseStatusException(HttpStatus.CONFLICT)
        is DashboardOwnerOutcome.Rejected -> throw DashboardRestProblem(
            when (code) {
                "DASHBOARD_DELETE_RETRY_REQUIRED" -> HttpStatus.SERVICE_UNAVAILABLE
                "IDEMPOTENCY_CONFLICT", "DASHBOARD_NOT_RESTORABLE" -> HttpStatus.CONFLICT
                "INVALID_DASHBOARD_DEFINITION" -> HttpStatus.UNPROCESSABLE_ENTITY
                else -> HttpStatus.BAD_REQUEST
            },
            code, message, if (code == "DASHBOARD_DELETE_RETRY_REQUIRED") mapOf("retryable" to true) else emptyMap(),
        )
    }

    @GetMapping("/dashboards", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun list(
        @PathVariable workspaceId: String,
        exchange: ServerWebExchange,
    ): DashboardListResponse {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val entries = withContext(blockingDispatcher) { dashboardUseCase.list(access.workspaceId) }
        val placementCounts = pages.tree(access.workspaceId.value).dashboardPlacementCounts()
        val names = displayNames(entries.flatMap { listOf(it.updatedBy, it.draftLastEditedBy) })
        val apps = agentApps.responses(entries.flatMap { listOf(it.agentClientId, it.draftLastEditAgentClientId) })
        return DashboardListResponse(entries.map { entry -> entry.toResponse(placementCounts[entry.id.value] ?: 0, names, apps) })
    }

    @PostMapping("/dashboards", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun create(
        @PathVariable workspaceId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody document: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<DashboardResponse> {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val result = try {
            dashboardUseCase.create(
                DashboardUseCase.CreateCommand(
                    workspaceId = access.workspaceId,
                    document = parseObject(strictBody(document)),
                    actorId = access.actorId,
                    recordedActorId = access.actorId,
                    idempotencyKey = requireKey(idempotencyKey),
                    createdAt = clock.instant(),
                    credentialSurface = access.credentialSurface,
                    agentClientId = access.agentClientId,
                ),
            )
        } catch (error: InvalidDashboardDefinitionException) {
            throw DashboardRestProblem(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DASHBOARD_DEFINITION", error.message, emptyMap())
        }
        return when (result) {
            is DashboardResult.Found -> ResponseEntity.status(HttpStatus.CREATED).body(dashboardResponse(result.dashboard))
            DashboardResult.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
            DashboardResult.Conflict -> throw ResponseStatusException(HttpStatus.CONFLICT)
            DashboardResult.IdempotencyConflict -> throw DashboardRestProblem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", null, emptyMap())
        }
    }

    @GetMapping("/dashboards/{dashboardId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun get(
        @PathVariable workspaceId: String,
        @PathVariable dashboardId: String,
        exchange: ServerWebExchange,
    ): DashboardResponse {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        return when (val result = withContext(blockingDispatcher) { dashboardUseCase.get(access.workspaceId, parseDashboardId(dashboardId)) }) {
            is DashboardResult.Found -> dashboardResponse(result.dashboard)
            DashboardResult.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
            DashboardResult.Conflict -> throw ResponseStatusException(HttpStatus.CONFLICT)
            DashboardResult.IdempotencyConflict -> throw ResponseStatusException(HttpStatus.CONFLICT)
        }
    }

    private suspend fun displayNames(actorIds: List<String?>): Map<String, String> = principalDisplayNames.byActorId(actorIds)

    private suspend fun dashboardResponse(dashboard: Dashboard): DashboardResponse =
        dashboardResponseOf(dashboard, objectMapper, principalDisplayNames, agentApps)
}
