@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.agent.application

import cc.monomer.agent.api.AgentManagementOwnerPort
import cc.monomer.agent.api.AgentManagementPaths
import cc.monomer.agent.api.AgentPathPatterns.ANY_VERSION_UUID
import cc.monomer.agent.api.AgentPathPatterns.UUID_V7
import cc.monomer.agent.api.AgentAvailableOperation
import cc.monomer.agent.api.AgentResourceEnvelope
import cc.monomer.agent.api.AgentResourceListingItem
import cc.monomer.agent.api.AgentResourceListingResponse
import cc.monomer.agent.api.AgentWorkspaceRootBody
import java.util.Base64
import cc.monomer.agent.api.AgentActionOutcome
import cc.monomer.agent.api.AgentCatalogDiscoveryPort
import cc.monomer.agent.api.AgentConnectorOwnerPort
import cc.monomer.agent.api.AgentCreateRequest
import cc.monomer.agent.api.AgentPagesOwnerPort
import cc.monomer.agent.api.AgentDashboardOwnerPort
import cc.monomer.agent.api.AgentEditRequest
import cc.monomer.agent.api.AgentExploreOwnerPort
import cc.monomer.agent.api.AgentInvokeRequest
import cc.monomer.agent.api.AgentListRequest
import cc.monomer.agent.api.AgentListingOutcome
import cc.monomer.agent.api.AgentReadRequest
import cc.monomer.agent.api.AgentResourceOutcome
import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.agent.api.AgentSearchScope
import cc.monomer.agent.api.AgentSearchOutcome
import cc.monomer.agent.api.AgentSearchPort
import cc.monomer.agent.api.AgentSearchRequest
import cc.monomer.agent.api.AgentSemanticOwnerPort
import cc.monomer.agent.api.AgentWorkspaceCaller
import cc.monomer.agent.api.AgentWorkspaceHandler
import cc.monomer.agent.api.AgentWorkspaceProblem
import cc.monomer.agent.api.AgentWorkspaceProblemStatus

class AgentResourceRouteRegistry(
    private val exploreOwnerPort: AgentExploreOwnerPort,
    private val connectorOwnerPort: AgentConnectorOwnerPort,
    private val catalogDiscoveryPort: AgentCatalogDiscoveryPort,
    private val semanticOwnerPort: AgentSemanticOwnerPort,
    private val dashboardOwnerPort: AgentDashboardOwnerPort,
    private val pagesOwnerPort: AgentPagesOwnerPort?,
    workspaceCollections: List<AgentResourcePath>,
    private val searchPort: AgentSearchPort,
    private val managementOwnerPort: AgentManagementOwnerPort?,
) : AgentWorkspaceHandler {
    private val workspaceCollections = workspaceCollections.distinct().sortedBy { it.value }.also { collections ->
        require(collections.all { it.value in ROOT_COLLECTION_PATHS })
        require(pagesOwnerPort != null || collections.none { it.value == "workspace/pages" })
    }

    private suspend fun listWorkspace(caller: AgentWorkspaceCaller, request: AgentListRequest): AgentListingOutcome {
        val root = when (val outcome = readWorkspace(caller, request.path)) {
            is AgentResourceOutcome.Found -> outcome.resource
            is AgentResourceOutcome.Rejected -> return AgentListingOutcome.Rejected(outcome.problem)
            AgentResourceOutcome.NotFound -> return AgentListingOutcome.NotFound
            AgentResourceOutcome.Forbidden -> return AgentListingOutcome.Forbidden
            AgentResourceOutcome.Unavailable -> return AgentListingOutcome.Unavailable
        }
        val operations = root.availableOperations
        val collections = operations.filterIsInstance<AgentAvailableOperation.List>().map { it.path }.distinct().sortedBy { it.value }
        val afterPath = try {
            request.cursor?.let { cursor ->
                val decoded = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
                val path = decoded.removePrefix("1:workspace:")
                require(decoded == "1:workspace:$path" && (path in ROOT_COLLECTION_PATHS || AgentManagementPaths.workspaceCollection.matches(path)) && encodeRootCursor(path) == cursor)
                path
            }
        } catch (_: IllegalArgumentException) {
            return AgentListingOutcome.Rejected(
                AgentWorkspaceProblem(
                    code = "CURSOR_INVALID",
                    title = "Invalid Workspace cursor",
                    detail = "The Workspace list cursor is invalid.",
                    status = AgentWorkspaceProblemStatus.BAD_REQUEST,
                    retryable = false,
                    currentRevision = null,
                    violations = emptyList(),
                    hints = emptyList(),
                    nextActions = emptyList(),
                    retryAfterSeconds = null,
                    roleRequirement = null,
                    partitionFilters = emptyList(),
                ),
            )
        }
        val remaining = collections.filter { afterPath == null || it.value > afterPath }
        val page = remaining.take(request.limit)
        return AgentListingOutcome.Found(
            AgentResourceListingResponse(
                items = page.map { path ->
                    AgentResourceListingItem.Collection(path = path, name = path.value.removePrefix("workspace/"))
                },
                nextCursor = if (remaining.size > page.size) encodeRootCursor(page.last().value) else null,
                availableOperations = operations,
            ),
        )
    }

    private fun encodeRootCursor(path: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("1:workspace:$path".toByteArray(Charsets.UTF_8))

    private suspend fun readWorkspace(caller: AgentWorkspaceCaller, path: AgentResourcePath): AgentResourceOutcome {
        val accessPath = AgentResourcePath.parse("workspace/access")
        val access = managementOwnerPort?.let { owner ->
            when (val outcome = owner.read(caller, AgentReadRequest(accessPath, null))) {
                is AgentResourceOutcome.Found -> outcome.resource as? AgentResourceEnvelope.WorkspaceAccess
                    ?: return AgentResourceOutcome.Unavailable
                else -> return outcome
            }
        }
        val role = access?.body?.role ?: caller.role
        val installed = if (role == cc.monomer.identity.api.WorkspaceRole.VIEWER) {
            if (pagesOwnerPort != null) listOf(AgentAvailableOperation.List(AgentResourcePath.parse("workspace/pages")),
                AgentAvailableOperation.Read(AgentResourcePath.parse("workspace/pages/tree"))) else emptyList()
        } else workspaceCollections.map { AgentAvailableOperation.List(path = it) }
        val lineage = if (access != null && role.includes(cc.monomer.identity.api.WorkspaceRole.EXPLORER)) {
            listOf("relations", "semantic-models", "metrics").map { AgentAvailableOperation.List(AgentResourcePath.parse("workspace/lineage/$it")) }
        } else emptyList()
        val operations = (installed + lineage + if (access != null) listOf(AgentAvailableOperation.Read(accessPath)) + access.availableOperations else emptyList()).distinct()
        return AgentResourceOutcome.Found(AgentResourceEnvelope.Workspace(path, AgentWorkspaceRootBody(role, operations), operations))
    }

    override suspend fun list(
        caller: AgentWorkspaceCaller,
        request: AgentListRequest,
    ): AgentListingOutcome = when (ownerFor(request.path)) {
        RouteOwner.MANAGEMENT -> managementOwnerPort?.list(caller, request) ?: AgentListingOutcome.Unavailable
        RouteOwner.CONNECTOR -> connectorOwnerPort.list(caller, request)
        RouteOwner.CATALOG -> catalogDiscoveryPort.list(caller, request)
        RouteOwner.SEMANTIC -> semanticOwnerPort.list(caller, request)
        RouteOwner.EXPLORE -> exploreOwnerPort.list(caller, request)
        RouteOwner.DASHBOARD -> dashboardOwnerPort.list(caller, request)
        RouteOwner.PAGES -> pagesOwnerPort?.list(caller, request) ?: AgentListingOutcome.Unavailable
        RouteOwner.WORKSPACE -> listWorkspace(caller, request)
        else -> AgentListingOutcome.NotFound
    }

    override suspend fun search(
        caller: AgentWorkspaceCaller,
        request: AgentSearchRequest,
    ): AgentSearchOutcome = if (AgentSearchScope.supports(request.path)) {
        searchPort.search(caller, request)
    } else {
        AgentSearchOutcome.Rejected(
            AgentWorkspaceProblem(
                code = "AGENT_SEARCH_SCOPE_INVALID",
                title = "Invalid search scope",
                detail = "Search requires a supported collection or Catalog scope.",
                status = AgentWorkspaceProblemStatus.BAD_REQUEST,
                retryable = false,
                currentRevision = null,
                violations = emptyList(),
                hints = emptyList(),
                nextActions = emptyList(),
                retryAfterSeconds = null,
                roleRequirement = null,
                partitionFilters = emptyList(),
            ),
        )
    }

    override suspend fun read(
        caller: AgentWorkspaceCaller,
        request: AgentReadRequest,
    ): AgentResourceOutcome = when (ownerFor(request.path)) {
        RouteOwner.MANAGEMENT -> managementOwnerPort?.read(caller, request) ?: AgentResourceOutcome.Unavailable
        RouteOwner.CONNECTOR -> connectorOwnerPort.read(caller, request)
        RouteOwner.CATALOG -> catalogDiscoveryPort.read(caller, request)
        RouteOwner.SEMANTIC -> semanticOwnerPort.read(caller, request)
        RouteOwner.EXPLORE -> exploreOwnerPort.read(caller, request)
        RouteOwner.DASHBOARD -> dashboardOwnerPort.read(caller, request)
        RouteOwner.PAGES -> pagesOwnerPort?.read(caller, request) ?: AgentResourceOutcome.Unavailable
        RouteOwner.WORKSPACE -> readWorkspace(caller, request.path)
        else -> AgentResourceOutcome.NotFound
    }

    override suspend fun create(
        caller: AgentWorkspaceCaller,
        request: AgentCreateRequest,
    ): AgentActionOutcome = when (ownerFor(request.path)) {
        RouteOwner.CONNECTOR -> connectorOwnerPort.create(caller, request)
        RouteOwner.SEMANTIC -> semanticOwnerPort.create(caller, request)
        RouteOwner.DASHBOARD -> dashboardOwnerPort.create(caller, request)
        RouteOwner.PAGES -> pagesOwnerPort?.create(caller, request) ?: AgentActionOutcome.Unavailable
        else -> AgentActionOutcome.NotFound
    }

    override suspend fun edit(
        caller: AgentWorkspaceCaller,
        request: AgentEditRequest,
    ): AgentActionOutcome = when (ownerFor(request.path)) {
        RouteOwner.CONNECTOR -> connectorOwnerPort.edit(caller, request)
        RouteOwner.CATALOG -> catalogDiscoveryPort.edit(caller, request)
        RouteOwner.SEMANTIC -> semanticOwnerPort.edit(caller, request)
        RouteOwner.DASHBOARD -> dashboardOwnerPort.edit(caller, request)
        RouteOwner.PAGES -> pagesOwnerPort?.edit(caller, request) ?: AgentActionOutcome.Unavailable
        else -> AgentActionOutcome.NotFound
    }

    override suspend fun invoke(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
    ): AgentActionOutcome = when (ownerFor(request.path)) {
        RouteOwner.MANAGEMENT -> managementOwnerPort?.invoke(caller, request) ?: AgentActionOutcome.Unavailable
        RouteOwner.CONNECTOR -> connectorOwnerPort.invoke(caller, request)
        RouteOwner.CATALOG -> catalogDiscoveryPort.invoke(caller, request)
        RouteOwner.SEMANTIC -> semanticOwnerPort.invoke(caller, request)
        RouteOwner.EXPLORE -> exploreOwnerPort.invoke(caller, request)
        RouteOwner.DASHBOARD -> dashboardOwnerPort.invoke(caller, request)
        RouteOwner.PAGES -> pagesOwnerPort?.invoke(caller, request) ?: AgentActionOutcome.Unavailable
        else -> AgentActionOutcome.NotFound
    }

    private fun ownerFor(path: AgentResourcePath): RouteOwner? =
        if (cc.monomer.agent.api.AgentDashboardHistoryPaths.supports(path.value)) RouteOwner.DASHBOARD else if (cc.monomer.agent.api.AgentPagesPaths.reader(path.value)) RouteOwner.PAGES else if (cc.monomer.agent.api.AgentDashboardSchedulePaths.supports(path.value)) RouteOwner.DASHBOARD else if (AgentManagementPaths.workspace(path.value)) RouteOwner.MANAGEMENT else routePatterns.firstOrNull { pattern -> pattern.matches(path.value) }?.owner

    private enum class RouteOwner {
        MANAGEMENT,
        EXPLORE,
        CONNECTOR,
        CATALOG,
        SEMANTIC,
        DASHBOARD,
        WORKSPACE,
        PAGES,
    }

    private data class RoutePattern(
        val owner: RouteOwner,
        val matcher: Regex,
    ) {
        fun matches(path: String): Boolean = matcher.matches(path)
    }

    private companion object {
        val ROOT_COLLECTION_PATHS = setOf("workspace/pages", "workspace/connectors", "workspace/semantic-models", "workspace/metrics", "workspace/explore/runs", "workspace/dashboards")
        val routePatterns = listOf(
            RoutePattern(RouteOwner.PAGES, Regex("^workspace/(?:pages(?:/tree|/$UUID_V7(?:/versions(?:/[1-9][0-9]{0,9})?)?)?|page-content-uploads(?:/$UUID_V7)?)$")),
            RoutePattern(RouteOwner.WORKSPACE, Regex("^workspace$")),
            RoutePattern(RouteOwner.CONNECTOR, Regex("^workspace/connectors(?:/$ANY_VERSION_UUID)?$")),
            RoutePattern(RouteOwner.CATALOG, Regex("^workspace/connectors/$ANY_VERSION_UUID/catalog(?:/[\\s\\S]+)?$")),
            RoutePattern(RouteOwner.SEMANTIC, Regex("^workspace/(?:semantic-models|metrics)(?:/.+)?$")),
            RoutePattern(RouteOwner.EXPLORE, Regex("^workspace/explore/runs(?:/$UUID_V7(?:/result)?)?$")),
            RoutePattern(
                RouteOwner.DASHBOARD,
                Regex(
                    "^workspace/dashboards(?:" +
                        "(?:/$UUID_V7" +
                        "(?:/draft(?:/status|/query-runs(?:/$UUID_V7(?:/result)?)?)?" +
                        "|/sessions(?:/$UUID_V7(?:/query-runs(?:/$UUID_V7(?:/result)?)?)?)?" +
                        ")?" +
                        ")?" +
                        ")?$",
                ),
            ),
        )
    }
}
