@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.overview

import cc.monomer.control.human.auth.toHumanHttpException

import cc.monomer.catalog.api.CatalogActivityReadPort
import cc.monomer.connector.api.ConnectorWorkspaceReadUseCase
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.WorkspaceRoleAdmissionRequest
import cc.monomer.control.auth.inbound.oauth.JwtAuthentication
import cc.monomer.dashboard.api.DashboardActivityReadPort
import cc.monomer.execution.api.ExecutionOwnerId
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunQueryPort
import cc.monomer.execution.api.MAX_SQL_BATCH_RUNS
import cc.monomer.explore.api.ExploreActivity
import cc.monomer.explore.api.ExploreActivityReadPort
import cc.monomer.explore.api.ExploreRunKind
import cc.monomer.identity.api.AgentClientId
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.PrincipalDisplayNames
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.SemanticCurrentReadUseCase
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.pages.api.PageActivityReadPort
import cc.monomer.control.agentapp.responses
import cc.monomer.control.jdbc.causeTypeChain
import cc.monomer.control.jdbc.sqlStateChain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import java.net.URLEncoder
import java.time.Instant
import kotlin.uuid.Uuid

@RestController
@RequestMapping("/v2/workspaces/{workspaceId}/overview/activity")
class OverviewActivityController(
    private val admission: ControlAdmissionFacade,
    private val explore: ExploreActivityReadPort,
    private val catalog: CatalogActivityReadPort,
    private val pages: PageActivityReadPort,
    private val dashboards: DashboardActivityReadPort,
    private val actors: PrincipalDisplayNames,
    private val agentApps: cc.monomer.identity.api.AgentAppDisplays,
    private val semantic: SemanticCurrentReadUseCase,
    @param:org.springframework.beans.factory.annotation.Qualifier("connectorWorkspaceReadUseCase") private val connectors: ConnectorWorkspaceReadUseCase,
    private val executionRuns: ExecutionRunQueryPort,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val cursorKey = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @GetMapping
    suspend fun read(
        @PathVariable workspaceId: String,
        @RequestParam section: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) actor: String?,
        @RequestParam(required = false) surface: String?,
        @RequestParam(required = false) agentClient: String?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) watch: String?,
        exchange: ServerWebExchange,
    ): OverviewActivityPage {
        val workspace = invalidRequest { WorkspaceId.parse(workspaceId).also { require(it.toString() == workspaceId) } }
        val selectedSection = invalidRequest { OverviewActivitySection.valueOf(section.uppercase()) }
        val pageSize = limit ?: 20
        invalidRequest { require(pageSize in 1..50) }
        val member = explorer(workspace, exchange)
        val actorFilter = invalidRequest { when (actor) {
            null, "all" -> null
            "me" -> member.actor.id
            else -> PrincipalId.parse(actor)
        } }
        val surfaceFilter = invalidRequest { surface?.let(cc.monomer.identity.api.CredentialSurface::valueOf) }
        val agentClientFilter = invalidRequest {
            agentClient?.let(::AgentClientId)?.also { require(surfaceFilter == cc.monomer.identity.api.CredentialSurface.AGENT) }
        }
        val scope = "$workspaceId:${member.actor.id}:${selectedSection.name}:${actorFilter ?: "all"}:${surfaceFilter ?: "all"}:" +
            (agentClientFilter?.let { URLEncoder.encode(it.value, Charsets.UTF_8) } ?: "all")
        val boundary = cursor?.let { invalidRequest { ActivityCursor.decode(it, scope, cursorKey) } }
        val watchedIds = invalidRequest { parseWatch(watch) }
        invalidRequest { require(selectedSection == OverviewActivitySection.LIVE || watchedIds.isEmpty()) }
        val live = selectedSection == OverviewActivitySection.LIVE
        val unavailable = mutableSetOf<OverviewActivityKind>()
        val candidates = mutableListOf<ActivityCandidate>()
        val watched = mutableListOf<ActivityCandidate>()
        val base = "/workspaces/$workspaceId"
        val sources = OverviewActivityKind.entries.filter { !live || !it.recentOnly }
        for (source in sources) {
            suspend fun query(ids: Set<Uuid>): List<ActivityCandidate> {
                val before = if (ids.isEmpty()) boundary?.at else null
                val beforeId = if (ids.isEmpty()) boundary?.ownerId(source) else null
                val count = if (ids.isEmpty()) pageSize + 1 else ids.size
                fun item(id: Uuid, title: String, description: String, state: String, startedAt: Instant?, at: Instant, actorId: String?, surface: String?, href: String) =
                    OverviewActivityItem(id.toString(), source, title, description, state, startedAt, at, actorId, null,
                        if (actorId == null) OverviewActorKind.UNRECORDED else OverviewActorKind.USER, surface, null, null, href)
                return when (source) {
                    OverviewActivityKind.EXPLORE -> explore.readActivity(workspace, live, before, beforeId, actorFilter, surfaceFilter, agentClientFilter, count, ids).map { run ->
                        val failed = run.state.name == "FAILED"
                        ActivityCandidate(item(run.id, if (run.kind == ExploreRunKind.SQL) "SQL query" else "Semantic query", "Explore", run.state.name,
                            run.startedAt, run.terminalAt ?: run.startedAt, run.actorId.toString(), run.credentialSurface?.name,
                            "$base/explore?run=${run.id}").copy(failureReason = if (failed) failureReason(source, run.failureCode?.name) else null), run.agentClientId, run.connectorId, run)
                    }
                    OverviewActivityKind.CATALOG_INGEST, OverviewActivityKind.CATALOG_REFRESH -> catalog.readActivity(workspace, source == OverviewActivityKind.CATALOG_REFRESH, live, before, beforeId, actorFilter, surfaceFilter, agentClientFilter, count, ids).map { job ->
                        val names = listOfNotNull(job.catalogName, job.schemaName).joinToString(" / ")
                        val target = if (names.isEmpty()) "Catalog" else names
                        val params = buildString {
                            append("connector=${job.connectorId}")
                            job.catalogName?.let { append("&catalog=${URLEncoder.encode(it, Charsets.UTF_8)}") }
                            if (source == OverviewActivityKind.CATALOG_REFRESH) {
                                job.catalogName?.let { append("&refreshCatalog=${URLEncoder.encode(it, Charsets.UTF_8)}") }
                                job.schemaName?.let { append("&refreshSchema=${URLEncoder.encode(it, Charsets.UTF_8)}") }
                            }
                            append(if (source == OverviewActivityKind.CATALOG_INGEST) "&ingest=${job.id}" else "&refresh=${job.id}")
                        }
                        ActivityCandidate(item(job.id, target, if (source == OverviewActivityKind.CATALOG_INGEST) "Catalog sync" else "Catalog refresh", job.state,
                            job.startedAt, job.terminalAt ?: job.startedAt, job.actorId.toString(), job.credentialSurface?.name,
                            "$base/catalog?$params").copy(failureReason = if (job.state == "COMPLETED_WITH_ERRORS") failureReason(source, job.failureCode) else null), job.agentClientId, job.connectorId, null)
                    }
                    OverviewActivityKind.PAGE -> pages.readRecent(workspace.value, before, beforeId, actorFilter?.toString(), surfaceFilter?.let { if (it == cc.monomer.identity.api.CredentialSurface.AGENT) "AGENT" else "USER" }, agentClientFilter?.value, count).map { page ->
                        ActivityCandidate(item(page.pageId, page.title, if (page.contentVersion == null) "Page updated" else "Page saved · Version ${page.contentVersion}", "UPDATED",
                            null, page.occurredAt, page.actorId.takeIf { page.actorKind != "SYSTEM" }, when(page.actorKind) { "USER" -> "WEB"; "AGENT" -> "AGENT"; else -> null },
                            "$base/pages/${page.pageId}"), page.agentClientId?.let(::AgentClientId), null, null)
                    }
                    OverviewActivityKind.DASHBOARD -> dashboards.readChanges(workspace, before, beforeId, actorFilter?.toString(), surfaceFilter, agentClientFilter, count, true).map { dashboard ->
                        val draftDestination = dashboard.change == cc.monomer.dashboard.api.DashboardChangeKind.CREATED ||
                            dashboard.change == cc.monomer.dashboard.api.DashboardChangeKind.DRAFT_UPDATED
                        val description = when (dashboard.change) {
                            cc.monomer.dashboard.api.DashboardChangeKind.CREATED -> "Dashboard created"
                            cc.monomer.dashboard.api.DashboardChangeKind.DRAFT_UPDATED -> "Draft updated"
                            cc.monomer.dashboard.api.DashboardChangeKind.DRAFT_DISCARDED -> "Draft changes discarded"
                            cc.monomer.dashboard.api.DashboardChangeKind.APPLIED -> "Dashboard edited"
                            cc.monomer.dashboard.api.DashboardChangeKind.DELETED -> "Dashboard deleted"
                        }
                        ActivityCandidate(item(dashboard.id, dashboard.title, description, when (dashboard.change) {
                            cc.monomer.dashboard.api.DashboardChangeKind.DRAFT_UPDATED, cc.monomer.dashboard.api.DashboardChangeKind.DRAFT_DISCARDED -> "UPDATED"
                            else -> dashboard.change.name
                        }, null, dashboard.occurredAt,
                            dashboard.actorId, dashboard.credentialSurface?.name, "$base/dashboards/${dashboard.dashboardId}" + if (dashboard.deleted) "/history" else if (draftDestination) "/builder" else ""), dashboard.agentClientId, null, null)
                    }
                    OverviewActivityKind.SEMANTIC_MODEL, OverviewActivityKind.METRIC -> {
                        val isModel = source == OverviewActivityKind.SEMANTIC_MODEL
                        val changes = if (isModel) semantic.readRecentModels(workspace, before, beforeId, actorFilter, surfaceFilter, agentClientFilter, count)
                            else semantic.readRecentMetrics(workspace, before, beforeId, actorFilter, surfaceFilter, agentClientFilter, count)
                        changes.map { change ->
                            val resource = if (isModel) "Semantic Model" else "Metric"
                            val route = if (isModel) "semantic-models" else "metrics"
                            val verb = when (change.action) {
                                cc.monomer.semantic.api.SemanticDefinitionChangeAction.CREATED -> "created"
                                cc.monomer.semantic.api.SemanticDefinitionChangeAction.UPDATED -> "edited"
                                cc.monomer.semantic.api.SemanticDefinitionChangeAction.DELETED -> "deleted"
                            }
                            val history = if (change.currentlyDeleted || change.action == cc.monomer.semantic.api.SemanticDefinitionChangeAction.DELETED) "/history" else ""
                            ActivityCandidate(item(change.changeId, change.label ?: change.name, "$resource $verb · ${change.name}", change.action.name, null,
                                change.updatedAt, change.actorId?.toString(), change.credentialSurface?.name, "$base/$route/${change.id}$history"), change.agentClientId, null, null)
                        }
                    }
                    OverviewActivityKind.DASHBOARD_SESSION -> dashboards.readSessions(workspace, live, before, beforeId, actorFilter?.toString(), surfaceFilter, agentClientFilter, count, ids).map { session ->
                        val state = sessionState(session)
                        val actorKind = when (session.origin) {
                            cc.monomer.dashboard.api.DashboardSessionOrigin.MEMBER -> OverviewActorKind.USER
                            cc.monomer.dashboard.api.DashboardSessionOrigin.SCHEDULE -> OverviewActorKind.SCHEDULE
                            cc.monomer.dashboard.api.DashboardSessionOrigin.PUBLIC_READER -> OverviewActorKind.PUBLIC_READER
                            cc.monomer.dashboard.api.DashboardSessionOrigin.UNRECORDED -> OverviewActorKind.UNRECORDED
                        }
                        ActivityCandidate(item(session.sessionId, session.title, sessionDescription(session), state, session.startedAt,
                            if (state == "RUNNING") session.startedAt else session.updatedAt, session.actorId, session.credentialSurface?.name,
                            "$base/dashboards/${session.dashboardId}?session=${session.sessionId}").copy(
                            actorKind = actorKind,
                            failureReason = if (session.failedCount > 0) failureReason(source, session.failureCode) else null,
                        ), session.agentClientId, null, null)
                    }
                    OverviewActivityKind.DASHBOARD_RUN -> dashboards.readDraftRuns(workspace, live, before, beforeId, actorFilter?.toString(), surfaceFilter, agentClientFilter, count, ids).map { run ->
                        ActivityCandidate(item(run.id, run.title, "Draft run · ${run.queryName}", run.state, run.startedAt,
                            if (run.state == "RUNNING") run.startedAt else run.updatedAt, run.actorId, run.credentialSurface?.name,
                            "$base/dashboards/${run.dashboardId}/builder?run=${run.id}").copy(
                            failureReason = if (run.state == "FAILED") failureReason(source, run.failureCode) else null,
                        ), run.agentClientId, run.connectorId, null)
                    }
                }
            }
            try {
                candidates += query(emptySet())
                val ids = watchedIds[source].orEmpty()
                if (ids.isNotEmpty()) watched += query(ids)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                unavailable += source
                logger.atWarn()
                    .addKeyValue("event", "overview_activity_source_failed")
                    .addKeyValue("source", source.name)
                    .addKeyValue("workspace_id", workspaceId.toString())
                    .setCause(error)
                    .addKeyValue("failure_types", error.causeTypeChain())
                    .addKeyValue("sql_states", error.sqlStateChain())
                    .log("overview_activity_source_failed")
            }
        }
        if (unavailable.size == sources.size) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Activity could not be loaded.")
        val ordered = candidates.sortedWith(activityOrder)
        val scanned = ordered.take(pageSize)
        val visibleAll = scanned + watched
        val userIds = visibleAll.mapNotNull { it.item.actorId?.let { id -> runCatching { Uuid.parse(id) }.getOrNull() } }.toSet()
        val names = actors.read(userIds)
        val apps = agentApps.responses(visibleAll.map { it.agentClientId })
        val catalogCandidates = visibleAll.filter { it.item.kind in setOf(OverviewActivityKind.CATALOG_INGEST, OverviewActivityKind.CATALOG_REFRESH) }
        val connectorNames = connectors.findByWorkspaceAndIds(workspace, catalogCandidates.mapNotNull { it.connectorId }.toSet()).associate { it.connectorId to it.displayName }
        val exploreTitles = exploreTitles(workspace, visibleAll.mapNotNull { it.exploreRun }.distinctBy { it.id })
        fun project(candidate: ActivityCandidate): OverviewActivityItem {
            val actorName = candidate.item.actorId?.let { runCatching { names[Uuid.parse(it)] }.getOrNull() }
            val title = when (candidate.item.kind) {
                OverviewActivityKind.CATALOG_INGEST, OverviewActivityKind.CATALOG_REFRESH -> {
                    val connectorName = candidate.connectorId?.let { connectorNames[it] ?: DELETED_CONNECTOR_NAME }
                    if (candidate.item.title == "Catalog") connectorName ?: "Catalog" else listOfNotNull(connectorName, candidate.item.title).joinToString(" / ")
                }
                OverviewActivityKind.EXPLORE -> candidate.exploreRun?.let { exploreTitles[it.id] } ?: candidate.item.title
                else -> candidate.item.title
            }
            return candidate.item.copy(title = title, actorName = actorName, agentApp = candidate.agentClientId?.let(apps::get))
        }
        return OverviewActivityPage(
            items = scanned.map(::project), watched = watched.map(::project),
            nextCursor = if (unavailable.isEmpty() && ordered.size > pageSize) scanned.last().item.let { ActivityCursor(it.occurredAt, it.kind, Uuid.parse(it.id)).encode(scope, cursorKey) } else null,
            unavailableSources = unavailable.toList(), observedAt = Instant.now(),
        )
    }

    /**
     * Agent apps seen in the Workspace's recent work, for the Agents activity filter. Each owner reads only its latest
     * [AGENT_APP_SCAN] records in Recent order, at the cost of its Recent activity read. Runs count once finished, so an
     * app whose only work is still running, or that is absent from every recent window, is not offered.
     */
    @GetMapping("/agent-apps")
    suspend fun readAgentApps(@PathVariable workspaceId: String, exchange: ServerWebExchange): OverviewActivityAgentApps {
        val workspace = invalidRequest { WorkspaceId.parse(workspaceId).also { require(it.toString() == workspaceId) } }
        explorer(workspace, exchange)
        val seen = buildList {
            addAll(explore.readRecentAgentClients(workspace, AGENT_APP_SCAN))
            addAll(catalog.readRecentAgentClients(workspace, false, AGENT_APP_SCAN))
            addAll(catalog.readRecentAgentClients(workspace, true, AGENT_APP_SCAN))
            addAll(dashboards.readRecentAgentClients(workspace, AGENT_APP_SCAN))
            addAll(semantic.readRecentAgentClients(workspace, AGENT_APP_SCAN))
            pages.readRecentAgentClients(workspace.value, AGENT_APP_SCAN).forEach { use ->
                add(cc.monomer.identity.api.RecentAgentClient(AgentClientId(use.agentClientId), use.lastSeenAt))
            }
        }
        val latest = seen.groupBy { it.clientId }.mapValues { (_, uses) -> uses.maxOf { it.lastSeenAt } }
            .entries.sortedWith(compareByDescending<Map.Entry<AgentClientId, Instant>> { it.value }.thenBy { it.key.value })
            .take(MAX_AGENT_APPS).map { it.key }
        val apps = agentApps.responses(latest)
        return OverviewActivityAgentApps(latest.mapNotNull(apps::get))
    }

    private suspend fun explorer(workspace: WorkspaceId, exchange: ServerWebExchange): cc.monomer.identity.api.IdentityRoleEvidence {
        val authentication = exchange.getPrincipal<JwtAuthentication>().awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.")
        return when (val result = admission.authorizeWorkspaceRole(WorkspaceRoleAdmissionRequest(workspace, authentication.presentedCredential, null, WorkspaceRole.EXPLORER))) {
            is ControlAdmissionDecision.Admitted -> result.context
            is ControlAdmissionDecision.Denied -> throw result.toHumanHttpException()
        }
    }

    /**
     * Distinct Explore titles under the Web run-title rules. SQL titles read the recorded SQL that any Explorer may
     * already read by the exact run; only the first FROM table and a short prefix leave Control. A title that
     * cannot be built keeps the generic "SQL query" or "Semantic query".
     */
    private suspend fun exploreTitles(workspace: WorkspaceId, runs: List<ExploreActivity>): Map<Uuid, String> {
        val titles = mutableMapOf<Uuid, String>()
        val sqlRuns = runs.filter { it.kind == ExploreRunKind.SQL }
        try {
            for (chunk in sqlRuns.chunked(MAX_SQL_BATCH_RUNS)) {
                val outcome = executionRuns.readSqlBatch(ExecutionRunQueryPort.ReadSqlBatchCommand(workspace, chunk.map { run ->
                    ExecutionRunQueryPort.RunOwnerBinding(ExecutionRunId(run.id), ExecutionOwnerReference(ExecutionOwnerType.EXPLORE, ExecutionOwnerId(run.id)))
                }))
                outcome.recorded.forEach { recorded -> titles[recorded.runId.value] = sqlRunTitle(recorded.sql) }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            logger.atWarn()
                .addKeyValue("event", "overview_activity_titles_unavailable")
                .addKeyValue("source", "EXPLORE")
                .addKeyValue("workspace_id", workspace.toString())
                .setCause(error)
                .addKeyValue("failure_types", error.causeTypeChain())
                .addKeyValue("sql_states", error.sqlStateChain())
                .log("overview_activity_titles_unavailable")
        }
        val semanticRuns = runs.filter { it.query != null }
        if (semanticRuns.isEmpty()) return titles
        val metricLabels = mutableMapOf<String, String?>()
        val models = mutableMapOf<String, SemanticModelDefinition?>()
        try {
            for (run in semanticRuns) {
                val query = run.query!!
                query.metrics.forEach { name -> if (name.value !in metricLabels) metricLabels[name.value] = semantic.findMetricByName(workspace, name)?.definition?.label }
                query.groupBys.forEach { groupBy ->
                    val modelName = when (groupBy) {
                        is cc.monomer.semantic.api.SemanticGroupBy.Dimension -> groupBy.semanticModelName
                        is cc.monomer.semantic.api.SemanticGroupBy.Entity -> groupBy.semanticModelName
                        is cc.monomer.semantic.api.SemanticGroupBy.MetricTime -> null
                    }
                    if (modelName != null && modelName.value !in models) models[modelName.value] = semantic.findModelByName(workspace, modelName)?.definition
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            logger.atWarn()
                .addKeyValue("event", "overview_activity_labels_unavailable")
                .addKeyValue("source", "SEMANTIC")
                .addKeyValue("workspace_id", workspace.toString())
                .setCause(error)
                .addKeyValue("failure_types", error.causeTypeChain())
                .addKeyValue("sql_states", error.sqlStateChain())
                .log("overview_activity_labels_unavailable")
        }
        semanticRuns.forEach { run -> titles[run.id] = semanticQueryTitle(run.query!!, { metricLabels[it] }, { models[it] }) }
        return titles
    }
}

private const val DELETED_CONNECTOR_NAME = "Deleted connector"

/** Latest records each owner scans for agent apps; see [OverviewActivityController.readAgentApps]. */
private const val AGENT_APP_SCAN = 500

private const val MAX_AGENT_APPS = 20

private fun parseWatch(raw: String?): Map<OverviewActivityKind, Set<Uuid>> {
    if (raw.isNullOrEmpty()) return emptyMap()
    require(raw.length <= 6000)
    val entries = raw.split(',')
    require(entries.size <= 100)
    return entries.map { entry ->
        val parts = entry.split(':')
        require(parts.size == 2)
        val kind = OverviewActivityKind.valueOf(parts[0])
        require(!kind.recentOnly)
        kind to Uuid.parse(parts[1])
    }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
}

private inline fun <T> invalidRequest(block: () -> T): T = try { block() }
catch (error: IllegalArgumentException) { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid activity request.") }
