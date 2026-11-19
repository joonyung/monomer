package cc.monomer.control.dashboard

import cc.monomer.control.agentapp.responses
import cc.monomer.dashboard.api.DashboardOwnerQueryRun
import kotlin.uuid.Uuid
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import tools.jackson.databind.ObjectMapper

@RestController
class DashboardSessionEntryController(
    private val entries: DashboardSessionEntryReader,
    private val authorizer: DashboardRequestAuthorizer,
    private val mapper: ObjectMapper,
    private val agentApps: cc.monomer.identity.api.AgentAppDisplays,
    private val principalDisplayNames: cc.monomer.identity.api.PrincipalDisplayNames,
) {
    @GetMapping("/v2/workspaces/{workspaceId}/dashboards/{dashboardId}/sessions/entry")
    suspend fun entry(
        @PathVariable workspaceId: String,
        @PathVariable dashboardId: String,
        @RequestParam(required = false) sessionId: String?,
        exchange: ServerWebExchange,
    ): ResponseEntity<Map<String, Any?>> {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        val entry = entries.select(
            access.workspaceId, parseDashboardId(canonical(dashboardId)), sessionId?.let { parseDashboardSessionId(canonical(it)) },
            authorize = { authorizer.requireWorkspace(exchange, workspaceId) },
            verifyResults = {
                authorizer.requireWorkspace(exchange, workspaceId)
            },
        )
        val initialRuns = entry.initialQueryRuns.values
        val provenance = DashboardProvenance(
            principalDisplayNames.byActorId(initialRuns.map { it.actorId }),
            agentApps.responses(initialRuns.map { it.agentClientId }),
        )
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mapOf(
            "session" to entry.session?.let { session -> mapOf(
                "dashboardSessionId" to session.sessionId.toString(),
                "workspaceId" to session.workspaceId.toString(),
                "dashboardId" to session.dashboardId.toString(),
                "definition" to mapper.readTree(session.definition.toString()),
                "parameterBindings" to session.parameterBindings.mapValues { mapper.readTree(it.value.toString()) },
                "initialVariables" to session.initialVariables.mapValues { mapper.readTree(it.value.toString()) },
                "runningQueryRuns" to session.runningQueryRuns.map { run -> mapOf(
                    "queryRunId" to run.queryRunId.toString(), "queryName" to run.queryName,
                    "state" to run.state, "resultAvailable" to run.resultAvailable,
                    "failureCode" to run.failureCode, "failureMessage" to run.failureMessage,
                    "outputColumnNames" to run.outputColumnNames,
                    "createdAt" to run.createdAt, "updatedAt" to run.updatedAt,
                ) },
                "createdAt" to session.createdAt,
                "updatedAt" to session.updatedAt,
            ) },
            "initialQueryRuns" to entry.initialQueryRuns.mapValues { it.value.entryResponse(provenance) },
        ))
    }

    private fun canonical(value: String): String = value.takeIf { runCatching { Uuid.parse(it).toString() == it }.getOrDefault(false) }
        ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Identifier must be a canonical UUID.")
}

internal fun DashboardOwnerQueryRun.entryResponse(provenance: DashboardProvenance) = QueryRunResponse(
    queryRunId = queryRunId.toString(),
    queryName = queryName,
    queryType = queryType,
    connectorId = connectorId.toString(),
    state = state,
    resultAvailable = resultAvailable,
    failureCode = failureCode,
    failureMessage = dashboardFailureMessage(failureCode, failureMessage),
    createdAt = createdAt,
    updatedAt = updatedAt,
    outputColumnNames = outputColumnNames,
    startedBy = provenance.actor(actorId),
    credentialSurface = credentialSurface?.name,
    agentApp = provenance.agentApp(agentClientId),
)
