@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.control.agentapp.AgentAppResponse
import cc.monomer.control.agentapp.responses
import cc.monomer.dashboard.application.DashboardCurrentQueryRun
import cc.monomer.dashboard.application.DashboardSessionSummary
import cc.monomer.dashboard.application.DraftResult
import cc.monomer.dashboard.application.SessionResult
import cc.monomer.dashboard.domain.Dashboard
import cc.monomer.dashboard.domain.DashboardListEntry
import cc.monomer.dashboard.domain.QueryRun
import cc.monomer.dashboard.domain.QueryRunState
import cc.monomer.identity.api.AgentAppDisplays
import cc.monomer.identity.api.AgentClientId
import cc.monomer.identity.api.PrincipalDisplayNames
import cc.monomer.pages.api.CurrentPagePlacement
import cc.monomer.pages.api.CurrentPagesTree
import kotlin.uuid.Uuid
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** PrincipalDisplayNames reads at most this many people per call. */
private const val DISPLAY_NAME_BATCH = 200

/** Current names of the recorded actors that are people, keyed by actor id; an id with no readable name is absent. */
internal suspend fun PrincipalDisplayNames.byActorId(actorIds: Collection<String?>): Map<String, String> {
    val userIds = actorIds.mapNotNull { id -> id?.let { runCatching { Uuid.parse(it) }.getOrNull() } }.toSet()
    val names = mutableMapOf<String, String>()
    for (chunk in userIds.chunked(DISPLAY_NAME_BATCH)) {
        read(chunk.toSet()).forEach { (id, name) -> names[id.toString()] = name }
    }
    return names
}

/** Names and agent apps for the actors and clients recorded on the resources of one response. */
internal class DashboardProvenance(
    private val actorNames: Map<String, String>,
    private val agentAppsByClient: Map<AgentClientId, AgentAppResponse>,
) {
    fun actor(actorId: String?): DashboardActorResponse? = actorId?.let { DashboardActorResponse(it, actorNames[it]) }

    fun agentApp(clientId: AgentClientId?): AgentAppResponse? = clientId?.let(agentAppsByClient::get)
}

data class DashboardListResponse(val items: List<DashboardListItemResponse>)
data class DashboardListItemResponse(
    val dashboardId: String,
    val workspaceId: String,
    val title: String,
    val description: String?,
    val updatedAt: java.time.Instant,
    val updatedBy: DashboardActorResponse?,
    val credentialSurface: String?,
    val agentApp: AgentAppResponse?,
    val latestResultsAt: java.time.Instant?,
    val schedules: List<DashboardListScheduleResponse>,
    val draftHasChanges: Boolean,
    val draftLastEditedBy: DashboardActorResponse?,
    val draftLastEditedAt: java.time.Instant?,
    val draftLastEditSurface: String?,
    val draftLastEditAgentApp: AgentAppResponse?,
    val pagePlacementCount: Int,
)
/** A recorded person; [displayName] is null when the account no longer has a readable name. */
data class DashboardActorResponse(val id: String, val displayName: String?)
data class DashboardListScheduleResponse(val key: String, val cron: String, val timeZone: String)
data class DashboardResponse(
    val dashboardId: String,
    val workspaceId: String,
    val definition: JsonNode,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant,
    val updatedBy: DashboardActorResponse?,
    val credentialSurface: String?,
    val agentApp: AgentAppResponse?,
)
data class DashboardDraftResponse(
    val workspaceId: String,
    val dashboardId: String,
    val definition: JsonNode,
    val parameterBindings: Map<String, JsonNode>,
    val variables: JsonNode,
    val authoringRevision: Long,
    val targetQueryRuns: Map<String, String>,
    val readyResults: Map<String, DraftReadyResultResponse>,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant,
    val lastEditedBy: DashboardActorResponse?,
    val lastEditedAt: java.time.Instant?,
    val lastEditSurface: String?,
    val lastEditAgentApp: AgentAppResponse?,
    val recentQueryRuns: List<QueryRunResponse>,
)
data class DashboardSessionResolutionResponse(val variables: Map<String, JsonNode>, val queryRuns: Map<String, String>)
data class DashboardSessionResponse(
    val dashboardSessionId: String,
    val workspaceId: String,
    val dashboardId: String,
    val definition: JsonNode,
    val parameterBindings: Map<String, JsonNode>,
    val initialVariables: Map<String, JsonNode>,
    val runningQueryRuns: List<QueryRunResponse>,
)
data class DashboardSessionPageResponse(val items: List<DashboardSessionSummaryResponse>, val nextCursor: String?)
data class DashboardSessionSummaryResponse(
    val dashboardSessionId: String,
    val dashboardId: String,
    val parameterBindings: Map<String, JsonNode>,
    val runningQueryRuns: List<DashboardCurrentQueryRunResponse>,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant,
    val lastQueryRunAt: java.time.Instant?,
)
data class DashboardCurrentQueryRunResponse(val queryName: String, val queryRunId: String, val state: String)
data class DashboardQueryRunPageResponse(val items: List<QueryRunResponse>, val nextCursor: String?)
data class QueryRunResponse(
    val queryRunId: String,
    val queryName: String,
    val queryType: String,
    val connectorId: String,
    val state: String,
    val resultAvailable: Boolean,
    val failureCode: String?,
    val failureMessage: String?,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant,
    val outputColumnNames: List<String>,
    /** The person whose Web session or agent connection started the run; null for scheduled and public Page reader runs. */
    val startedBy: DashboardActorResponse?,
    val credentialSurface: String?,
    val agentApp: AgentAppResponse?,
)
data class DraftReadyResultResponse(
    val authoringRevision: Long,
    val parameterBindings: Map<String, JsonNode>,
    val variables: JsonNode,
    val queryRuns: Map<String, String>,
)
/** [reportingTimeZone] is the IANA zone a Semantic run's SQL was rendered in; null for SQL runs and runs recorded before zones were kept. */
data class DashboardResultResponse(
    val queryRunId: String,
    val queryType: String,
    val columns: List<DashboardResultColumnResponse>,
    val rows: List<List<JsonNode>>,
    val reportingTimeZone: String?,
)

internal suspend fun dashboardResponseOf(
    dashboard: Dashboard,
    objectMapper: ObjectMapper,
    principalDisplayNames: PrincipalDisplayNames,
    agentApps: AgentAppDisplays,
): DashboardResponse =
    dashboard.toResponse(objectMapper, principalDisplayNames.byActorId(listOf(dashboard.updatedBy)), agentApps.responses(listOf(dashboard.agentClientId)))

internal suspend fun queryRunProvenance(
    runs: List<QueryRun>,
    principalDisplayNames: PrincipalDisplayNames,
    agentApps: AgentAppDisplays,
): DashboardProvenance =
    DashboardProvenance(principalDisplayNames.byActorId(runs.map(QueryRun::actorId)), agentApps.responses(runs.map(QueryRun::agentClientId)))

internal fun DashboardListEntry.toResponse(
    pagePlacementCount: Int,
    names: Map<String, String>,
    apps: Map<AgentClientId, AgentAppResponse>,
) = DashboardListItemResponse(
    dashboardId = id.toString(),
    workspaceId = workspaceId.toString(),
    title = title,
    description = description,
    updatedAt = updatedAt,
    updatedBy = actorResponse(updatedBy, names),
    credentialSurface = credentialSurface?.name,
    agentApp = agentClientId?.let(apps::get),
    latestResultsAt = latestResultsAt,
    schedules = schedules.map { DashboardListScheduleResponse(it.key, it.cron, it.timeZone) },
    draftHasChanges = draftHasChanges,
    draftLastEditedBy = actorResponse(draftLastEditedBy, names),
    draftLastEditedAt = draftLastEditedAt,
    draftLastEditSurface = draftLastEditSurface?.name,
    draftLastEditAgentApp = draftLastEditAgentClientId?.let(apps::get),
    pagePlacementCount = pagePlacementCount,
)

private fun actorResponse(actorId: String?, names: Map<String, String>): DashboardActorResponse? =
    actorId?.let { DashboardActorResponse(it, names[it]) }

/** Counts DASHBOARD Pages that are currently placed in the Pages tree, keyed by the referenced Dashboard ID. */
internal fun CurrentPagesTree.dashboardPlacementCounts(): Map<Uuid, Int> {
    val placedPageIds = buildSet {
        fun visit(placement: CurrentPagePlacement) {
            add(placement.pageId)
            placement.children.forEach(::visit)
        }
        placements.forEach(::visit)
    }
    return pages
        .filter { it.kind == "DASHBOARD" && it.pageId in placedPageIds }
        .mapNotNull { it.dashboardId }
        .groupingBy { it }
        .eachCount()
}
internal fun Dashboard.toResponse(objectMapper: ObjectMapper, names: Map<String, String>, apps: Map<AgentClientId, AgentAppResponse>) = DashboardResponse(
    id.toString(), workspaceId.toString(), objectMapper.readTree(definition.document.toString()), createdAt, updatedAt,
    actorResponse(updatedBy, names), credentialSurface?.name, agentClientId?.let(apps::get),
)
internal fun DraftResult.Found.toResponse(
    objectMapper: ObjectMapper,
    provenance: DashboardProvenance,
): DashboardDraftResponse = DashboardDraftResponse(
    workspaceId = draft.workspaceId.toString(),
    dashboardId = draft.dashboardId.toString(),
    definition = objectMapper.readTree(draft.definition.document.toString()),
    parameterBindings = draft.parameterBindings.mapValues { (_, value) -> objectMapper.readTree(value.toString()) },
    variables = objectMapper.readTree(draft.currentVariablesObject().toString()),
    authoringRevision = draft.authoringRevision,
    targetQueryRuns = draft.targetQueryRuns.mapValues { it.value.toString() },
    readyResults = draft.readyResults.mapValues { (_, value) ->
        DraftReadyResultResponse(
            authoringRevision = value.authoringRevision,
            parameterBindings = value.parameterBindings.mapValues { (_, parameter) -> objectMapper.readTree(parameter.toString()) },
            variables = objectMapper.readTree(value.variables.let { buildJsonObject { it.forEach { (name, element) -> put(name, element) } } }.toString()),
            queryRuns = value.queryRuns.mapValues { it.value.toString() },
        )
    },
    createdAt = draft.createdAt,
    updatedAt = draft.updatedAt,
    lastEditedBy = provenance.actor(draft.lastEditedBy),
    lastEditedAt = draft.lastEditedAt,
    lastEditSurface = draft.lastEditSurface?.name,
    lastEditAgentApp = provenance.agentApp(draft.lastEditAgentClientId),
    recentQueryRuns = queryRuns.map { it.toResponse(provenance) },
)
internal fun SessionResult.Found.toResponse(objectMapper: ObjectMapper, provenance: DashboardProvenance) = DashboardSessionResponse(
    dashboardSessionId = session.id.toString(), workspaceId = session.workspaceId.toString(), dashboardId = session.dashboardId.toString(),
    definition = objectMapper.readTree(session.definition.document.toString()),
    parameterBindings = session.parameterBindings.mapValues { (_, value) -> objectMapper.readTree(value.toString()) },
    initialVariables = session.initialVariables.mapValues { (_, value) -> objectMapper.readTree(value.toString()) },
    runningQueryRuns = queryRuns.map { it.toResponse(provenance) },
)
internal fun cc.monomer.dashboard.application.DashboardSessionPage.toResponse(objectMapper: ObjectMapper): DashboardSessionPageResponse =
    DashboardSessionPageResponse(
        items = items.map { it.toResponse(objectMapper) },
        nextCursor = nextCursor?.let { encodeSessionCursor(it) },
    )

internal fun DashboardSessionSummary.toResponse(objectMapper: ObjectMapper) = DashboardSessionSummaryResponse(
    dashboardSessionId = id.toString(),
    dashboardId = dashboardId.toString(),
    parameterBindings = parameterBindings.mapValues { (_, value) -> objectMapper.readTree(value.toString()) },
    runningQueryRuns = runningQueryRuns.map(DashboardCurrentQueryRun::toResponse),
    lastQueryRunAt = lastQueryRunAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun DashboardCurrentQueryRun.toResponse() = DashboardCurrentQueryRunResponse(
    queryName = queryName,
    queryRunId = queryRunId.toString(),
    state = state.name,
)

internal fun cc.monomer.dashboard.application.DashboardQueryRunPage.toResponse(provenance: DashboardProvenance): DashboardQueryRunPageResponse =
    DashboardQueryRunPageResponse(
        items = items.map { it.toResponse(provenance) },
        nextCursor = nextCursor?.let { encodeCursor(it.createdAt, it.queryRunId.value) },
    )

internal fun QueryRun.toResponse(provenance: DashboardProvenance) = QueryRunResponse(
    queryRunId = id.toString(),
    queryName = queryName,
    queryType = queryType.name,
    connectorId = connectorId.toString(),
    state = state.name,
    resultAvailable = state == QueryRunState.SUCCEEDED && result != null,
    failureCode = failureCode,
    failureMessage = dashboardFailureMessage(failureCode, failureMessage),
    createdAt = createdAt,
    updatedAt = updatedAt,
    outputColumnNames = outputColumnNames,
    startedBy = provenance.actor(actorId),
    credentialSurface = credentialSurface?.name,
    agentApp = provenance.agentApp(agentClientId),
)
