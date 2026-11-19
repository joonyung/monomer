@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.agent.api

import cc.monomer.agent.api.AgentPathPatterns.ANY_VERSION_UUID
import cc.monomer.agent.api.AgentPathPatterns.SEMANTIC_NAME
import cc.monomer.agent.api.AgentPathPatterns.UUID_V7
import cc.monomer.identity.api.OrganizationId
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.AuthorizationRevision
import cc.monomer.identity.api.CanonicalPrincipal
import cc.monomer.identity.api.CredentialKind
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId

sealed interface AgentWorkspaceAdmissionRequest {
    val workspaceId: WorkspaceId
    val credential: PresentedCredential
    val audience: AgentWorkspaceAdmissionAudience
    val expectedOAuthResource: ExpectedOAuthResource
    val intent: AgentWorkspaceAdmissionIntent
}

class AgentWorkspaceRoleAdmissionRequest private constructor(
    override val workspaceId: WorkspaceId,
    override val credential: PresentedCredential,
    override val audience: AgentWorkspaceAdmissionAudience,
    override val expectedOAuthResource: ExpectedOAuthResource,
    override val intent: AgentWorkspaceAdmissionIntent,
) : AgentWorkspaceAdmissionRequest {
    companion object {
        @JvmSynthetic
        internal fun issue(
            workspaceId: WorkspaceId,
            credential: PresentedCredential,
            audience: AgentWorkspaceAdmissionAudience,
            intent: AgentWorkspaceAdmissionIntent,
        ): AgentWorkspaceRoleAdmissionRequest =
            AgentWorkspaceRoleAdmissionRequest(
                workspaceId = workspaceId,
                credential = credential,
                audience = audience,
                expectedOAuthResource = audience.expectedOAuthResource,
                intent = intent,
            )
    }
}

enum class AgentWorkspaceAdmissionAudience(
    val expectedOAuthResource: ExpectedOAuthResource,
) {
    REST(ExpectedOAuthResource.AGENT_REST),
    MCP(ExpectedOAuthResource.AGENT_MCP),
}

sealed interface AgentWorkspaceAdmissionIntent {
    val operation: AgentWorkspaceOperation
    val path: AgentResourcePath
    val action: String?

    data class List(
        override val path: AgentResourcePath,
    ) : AgentWorkspaceAdmissionIntent {
        override val operation: AgentWorkspaceOperation = AgentWorkspaceOperation.LIST
        override val action: String? = null
    }

    data class Search(
        override val path: AgentResourcePath,
        val kinds: Set<AgentResourceKind>?,
    ) : AgentWorkspaceAdmissionIntent {
        override val operation: AgentWorkspaceOperation = AgentWorkspaceOperation.SEARCH
        override val action: String? = null
    }

    data class Read(
        override val path: AgentResourcePath,
    ) : AgentWorkspaceAdmissionIntent {
        override val operation: AgentWorkspaceOperation = AgentWorkspaceOperation.READ
        override val action: String? = null
    }

    data class PagesCreate(override val path: AgentResourcePath) : AgentWorkspaceAdmissionIntent {
        override val operation = AgentWorkspaceOperation.CREATE
        override val action: String? = null
    }
    data class PagesEdit(override val path: AgentResourcePath) : AgentWorkspaceAdmissionIntent {
        override val operation = AgentWorkspaceOperation.EDIT
        override val action: String? = null
    }
    data class PagesInvoke(override val path: AgentResourcePath, override val action: String) : AgentWorkspaceAdmissionIntent {
        override val operation = AgentWorkspaceOperation.INVOKE
    }

    data class ConnectorCreate(override val path: AgentResourcePath) : AgentWorkspaceAdmissionIntent {
        override val operation = AgentWorkspaceOperation.CREATE
        override val action: String? = null
    }
    data class ConnectorEdit(override val path: AgentResourcePath) : AgentWorkspaceAdmissionIntent {
        override val operation = AgentWorkspaceOperation.EDIT
        override val action: String? = null
    }
    data class ConnectorRotate(override val path: AgentResourcePath, override val action: String) : AgentWorkspaceAdmissionIntent {
        override val operation = AgentWorkspaceOperation.INVOKE
    }
    data class ConnectorDelete(override val path: AgentResourcePath, override val action: String) : AgentWorkspaceAdmissionIntent {
        override val operation = AgentWorkspaceOperation.INVOKE
    }

    data class SemanticDelete(override val path: AgentResourcePath, override val action: String) : AgentWorkspaceAdmissionIntent {
        override val operation = AgentWorkspaceOperation.INVOKE
    }
    data class SemanticRestore(override val path: AgentResourcePath, override val action: String) : AgentWorkspaceAdmissionIntent {
        override val operation = AgentWorkspaceOperation.INVOKE
    }

    data class SemanticCheck(override val path: AgentResourcePath, override val action: String) : AgentWorkspaceAdmissionIntent {
        override val operation = AgentWorkspaceOperation.INVOKE
    }

    data class SemanticModelCreate(
        override val path: AgentResourcePath,
    ) : AgentWorkspaceAdmissionIntent {
        override val operation: AgentWorkspaceOperation = AgentWorkspaceOperation.CREATE
        override val action: String? = null
    }

    data class MetricCreate(
        override val path: AgentResourcePath,
    ) : AgentWorkspaceAdmissionIntent {
        override val operation: AgentWorkspaceOperation = AgentWorkspaceOperation.CREATE
        override val action: String? = null
    }

    data class DashboardDelete(override val path: AgentResourcePath, override val action: String) : AgentWorkspaceAdmissionIntent {
        override val operation = AgentWorkspaceOperation.INVOKE
    }
    data class DashboardRestore(override val path: AgentResourcePath, override val action: String) : AgentWorkspaceAdmissionIntent {
        override val operation = AgentWorkspaceOperation.INVOKE
    }

    data class DashboardCreate(
        override val path: AgentResourcePath,
    ) : AgentWorkspaceAdmissionIntent {
        override val operation: AgentWorkspaceOperation = AgentWorkspaceOperation.CREATE
        override val action: String? = null
    }

    data class SemanticModelEdit(
        override val path: AgentResourcePath,
    ) : AgentWorkspaceAdmissionIntent {
        override val operation: AgentWorkspaceOperation = AgentWorkspaceOperation.EDIT
        override val action: String? = null
    }

    data class MetricEdit(
        override val path: AgentResourcePath,
    ) : AgentWorkspaceAdmissionIntent {
        override val operation: AgentWorkspaceOperation = AgentWorkspaceOperation.EDIT
        override val action: String? = null
    }

    data class DashboardEdit(
        override val path: AgentResourcePath,
    ) : AgentWorkspaceAdmissionIntent {
        override val operation: AgentWorkspaceOperation = AgentWorkspaceOperation.EDIT
        override val action: String? = null
    }

    data class CatalogEdit(
        override val path: AgentResourcePath,
    ) : AgentWorkspaceAdmissionIntent {
        override val operation: AgentWorkspaceOperation = AgentWorkspaceOperation.EDIT
        override val action: String? = null
    }

    data class ExploreRun(
        override val path: AgentResourcePath,
        override val action: String,
    ) : AgentWorkspaceAdmissionIntent {
        override val operation: AgentWorkspaceOperation = AgentWorkspaceOperation.INVOKE
    }

    data class DashboardInvoke(
        override val path: AgentResourcePath,
        override val action: String,
    ) : AgentWorkspaceAdmissionIntent {
        override val operation: AgentWorkspaceOperation = AgentWorkspaceOperation.INVOKE
    }

    data class DashboardSessionInvoke(
        override val path: AgentResourcePath,
        override val action: String,
    ) : AgentWorkspaceAdmissionIntent {
        override val operation: AgentWorkspaceOperation = AgentWorkspaceOperation.INVOKE
    }

    data class ManagementInvoke(override val path: AgentResourcePath, override val action: String) : AgentWorkspaceAdmissionIntent { override val operation = AgentWorkspaceOperation.INVOKE }

    data class CatalogInvoke(
        override val path: AgentResourcePath,
        override val action: String,
    ) : AgentWorkspaceAdmissionIntent {
        override val operation: AgentWorkspaceOperation = AgentWorkspaceOperation.INVOKE
    }
}

sealed interface AgentWorkspaceAdmissionEvidence {
    val actor: CanonicalPrincipal
    val organizationId: OrganizationId
    val workspaceId: WorkspaceId
    val authorizationRevision: AuthorizationRevision
    val credentialKind: CredentialKind
    val credentialSurface: CredentialSurface
    val agentClientId: cc.monomer.identity.api.AgentClientId?
    val intent: AgentWorkspaceAdmissionIntent

    fun proves(request: AgentWorkspaceAdmissionRequest): Boolean
}

data class AgentWorkspaceRoleEvidence(
    val organizationPlan: cc.monomer.identity.api.OrganizationPlan,
    override val actor: CanonicalPrincipal,
    override val organizationId: OrganizationId,
    override val workspaceId: WorkspaceId,
    override val authorizationRevision: AuthorizationRevision,
    override val credentialKind: CredentialKind,
    override val credentialSurface: CredentialSurface,
    override val agentClientId: cc.monomer.identity.api.AgentClientId?,
    val role: WorkspaceRole,
    override val intent: AgentWorkspaceAdmissionIntent,
) : AgentWorkspaceAdmissionEvidence {
    init {
        cc.monomer.identity.api.requireAgentClientMatchesSurface(credentialSurface, agentClientId)
    }

    override fun proves(request: AgentWorkspaceAdmissionRequest): Boolean {
        val roleRequest = request as? AgentWorkspaceRoleAdmissionRequest
            ?: return false
        return workspaceId == roleRequest.workspaceId &&
            intent == roleRequest.intent &&
            role.includes(roleRequest.intent.requiredRole) &&
            credentialKind == CredentialKind.AGENT_OAUTH &&
            credentialSurface == CredentialSurface.AGENT &&
            roleRequest.expectedOAuthResource == roleRequest.audience.expectedOAuthResource
    }
}

sealed interface AgentWorkspaceAdmissionDecision {
    data class Authorized(
        val evidence: AgentWorkspaceAdmissionEvidence,
    ) : AgentWorkspaceAdmissionDecision

    /** [problem] explains a FORBIDDEN role denial on an accessible target; it is null for every other denial. */
    data class Denied(
        val reason: AgentWorkspaceAdmissionDenial,
        val problem: AgentWorkspaceProblem?,
    ) : AgentWorkspaceAdmissionDecision {
        init {
            require(problem == null || reason == AgentWorkspaceAdmissionDenial.FORBIDDEN && problem.roleRequirement != null)
        }
    }
}

enum class AgentWorkspaceAdmissionDenial {
    NOT_FOUND,
    UNAUTHENTICATED,
    FORBIDDEN,
    UNAVAILABLE,
}

interface AgentWorkspaceAdmissionPort {
    suspend fun decide(
        request: AgentWorkspaceAdmissionRequest,
    ): AgentWorkspaceAdmissionDecision
}

internal object AgentWorkspaceAdmissionIntents {
    fun list(request: AgentListRequest): AgentWorkspaceAdmissionIntent? =
        AgentWorkspaceAdmissionIntent.List(request.path).takeIf(AgentWorkspaceAdmissionIntent::isSupported)

    fun search(request: AgentSearchRequest): AgentWorkspaceAdmissionIntent? =
        AgentWorkspaceAdmissionIntent.Search(request.path, request.kinds).takeIf(AgentWorkspaceAdmissionIntent::isSupported)

    fun read(request: AgentReadRequest): AgentWorkspaceAdmissionIntent? =
        AgentWorkspaceAdmissionIntent.Read(request.path).takeIf(AgentWorkspaceAdmissionIntent::isSupported)

    fun create(request: AgentCreateRequest): AgentWorkspaceAdmissionIntent? =
        when (request.input) {
            is AgentCreateInput.OrganizationInvitation -> null
            is AgentCreateInput.FolderPage, is AgentCreateInput.DocumentPage, is AgentCreateInput.DashboardPage ->
                if (request.path.value == "workspace/pages") AgentWorkspaceAdmissionIntent.PagesCreate(request.path) else null
            is AgentCreateInput.PageVersion -> if (AgentPagesPaths.versions.matches(request.path.value)) AgentWorkspaceAdmissionIntent.PagesCreate(request.path) else null
            is AgentCreateInput.PageContentUpload -> if (request.path.value == "workspace/page-content-uploads") AgentWorkspaceAdmissionIntent.PagesCreate(request.path) else null
            is AgentCreateInput.Connector -> AgentWorkspaceAdmissionIntent.ConnectorCreate(request.path)
            is AgentCreateInput.SemanticModel -> AgentWorkspaceAdmissionIntent.SemanticModelCreate(request.path)
            is AgentCreateInput.Metric -> AgentWorkspaceAdmissionIntent.MetricCreate(request.path)
            is AgentCreateInput.Dashboard -> AgentWorkspaceAdmissionIntent.DashboardCreate(request.path)
        }?.takeIf(AgentWorkspaceAdmissionIntent::isSupported)

    fun edit(request: AgentEditRequest): AgentWorkspaceAdmissionIntent? =
        when {
            AgentPagesPaths.editable(request.path.value) && request.commandId != null && request.revision != null -> AgentWorkspaceAdmissionIntent.PagesEdit(request.path)
            request.commandId != null -> null
            AgentPathPatterns.connector.matches(request.path.value) -> AgentWorkspaceAdmissionIntent.ConnectorEdit(request.path)
            SEMANTIC_MODEL_PATH.matches(request.path.value) -> AgentWorkspaceAdmissionIntent.SemanticModelEdit(request.path)
            METRIC_PATH.matches(request.path.value) -> AgentWorkspaceAdmissionIntent.MetricEdit(request.path)
            AgentPathPatterns.dashboardDraft.matches(request.path.value) -> AgentWorkspaceAdmissionIntent.DashboardEdit(request.path)
            CATALOG_SELECTION_PATH.matches(request.path.value) -> AgentWorkspaceAdmissionIntent.CatalogEdit(request.path)
            else -> null
        }?.takeIf(AgentWorkspaceAdmissionIntent::isSupported)

    fun invoke(request: AgentInvokeRequest): AgentWorkspaceAdmissionIntent? =
        when {
            AgentPagesPaths.session.matches(request.path.value) && request.action == "resolve" && request.input is AgentInvokeInput.DashboardSessionResolve -> AgentWorkspaceAdmissionIntent.DashboardSessionInvoke(request.path, request.action)
            AgentManagementPaths.workspace(request.path.value) && AgentManagementPaths.invocation(request) -> AgentWorkspaceAdmissionIntent.ManagementInvoke(request.path, request.action)
            AgentPagesPaths.page.matches(request.path.value) && request.action == "delete" && request.input is AgentInvokeInput.PageDelete -> AgentWorkspaceAdmissionIntent.PagesInvoke(request.path, request.action)
            AgentPagesPaths.upload.matches(request.path.value) && ((request.action == "complete" && request.input is AgentInvokeInput.PageContentUploadComplete) || (request.action == "abort" && request.input is AgentInvokeInput.None)) -> AgentWorkspaceAdmissionIntent.PagesInvoke(request.path, request.action)
            AgentPathPatterns.connector.matches(request.path.value) && request.action == "rotate-credentials" && request.input is AgentInvokeInput.ConnectorCredentialRotate -> AgentWorkspaceAdmissionIntent.ConnectorRotate(request.path, request.action)
            AgentPathPatterns.connector.matches(request.path.value) && request.action == "delete" && request.input is AgentInvokeInput.None -> AgentWorkspaceAdmissionIntent.ConnectorDelete(request.path, request.action)
            AgentPathPatterns.dashboard.matches(request.path.value) && request.action == "delete" && request.input is AgentInvokeInput.None ->
                AgentWorkspaceAdmissionIntent.DashboardDelete(request.path, request.action)
            AgentDashboardHistoryPaths.version.matches(request.path.value) && request.action == "restore" && request.input is AgentInvokeInput.None ->
                AgentWorkspaceAdmissionIntent.DashboardRestore(request.path, request.action)
            AgentSemanticHistoryPaths.definition.matches(request.path.value) && request.action == "delete" && request.input is AgentInvokeInput.None ->
                AgentWorkspaceAdmissionIntent.SemanticDelete(request.path, request.action)
            AgentSemanticHistoryPaths.version.matches(request.path.value) && request.action == "restore" && request.input is AgentInvokeInput.None ->
                AgentWorkspaceAdmissionIntent.SemanticRestore(request.path, request.action)
            request.action == "check" && ((request.input is AgentInvokeInput.SemanticModelCheck && (SEMANTIC_MODEL_COLLECTION_PATH.matches(request.path.value) || SEMANTIC_MODEL_PATH.matches(request.path.value))) ||
                (request.input is AgentInvokeInput.MetricCheck && (METRIC_COLLECTION_PATH.matches(request.path.value) || METRIC_PATH.matches(request.path.value)))) ->
                AgentWorkspaceAdmissionIntent.SemanticCheck(request.path, request.action)
            request.path.value == "workspace/explore/runs" &&
                request.action == "run" &&
                request.input is AgentInvokeInput.ExploreRun ->
                AgentWorkspaceAdmissionIntent.ExploreRun(request.path, request.action)

            AgentPathPatterns.exploreRun.matches(request.path.value) &&
                request.action == "cancel" &&
                request.input is AgentInvokeInput.None ->
                AgentWorkspaceAdmissionIntent.ExploreRun(request.path, request.action)

            AgentPathPatterns.dashboardDraft.matches(request.path.value) &&
                request.action == "start" &&
                request.input is AgentInvokeInput.DashboardDraftStart ->
                AgentWorkspaceAdmissionIntent.DashboardInvoke(request.path, request.action)

            AgentPathPatterns.dashboardDraft.matches(request.path.value) &&
                request.action == "apply" &&
                request.input is AgentInvokeInput.DashboardDraftApply ->
                AgentWorkspaceAdmissionIntent.DashboardInvoke(request.path, request.action)

            AgentPathPatterns.dashboardDraft.matches(request.path.value) &&
                request.action == "discard" &&
                request.input is AgentInvokeInput.DashboardDraftDiscard ->
                AgentWorkspaceAdmissionIntent.DashboardInvoke(request.path, request.action)

            AgentPathPatterns.dashboardDraft.matches(request.path.value) &&
                request.action == "refresh" &&
                request.input is AgentInvokeInput.DashboardQueryRefresh ->
                AgentWorkspaceAdmissionIntent.DashboardInvoke(request.path, request.action)

            AgentPathPatterns.dashboardDraftQueryRun.matches(request.path.value) &&
                request.action == "cancel" &&
                request.input is AgentInvokeInput.None ->
                AgentWorkspaceAdmissionIntent.DashboardInvoke(request.path, request.action)

            AgentPathPatterns.dashboardSessions.matches(request.path.value) &&
                request.action == "open" &&
                request.input is AgentInvokeInput.DashboardSessionOpen ->
                AgentWorkspaceAdmissionIntent.DashboardSessionInvoke(request.path, request.action)

            AgentPathPatterns.dashboardSession.matches(request.path.value) &&
                ((request.action == "resolve" && request.input is AgentInvokeInput.DashboardSessionResolve) ||
                    (request.action == "refresh" && request.input is AgentInvokeInput.DashboardSessionRefresh)) ->
                AgentWorkspaceAdmissionIntent.DashboardSessionInvoke(request.path, request.action)

            AgentPathPatterns.catalogInvokeDeclared(request.path.value, request.action) &&
                request.input is AgentInvokeInput.None ->
                AgentWorkspaceAdmissionIntent.CatalogInvoke(request.path, request.action)

            else -> null
        }?.takeIf(AgentWorkspaceAdmissionIntent::isSupported)
}

internal fun AgentWorkspaceAdmissionIntent.isSupported(): Boolean =
    when (this) {
        is AgentWorkspaceAdmissionIntent.List, is AgentWorkspaceAdmissionIntent.Read -> readRequiredRole() != null
        is AgentWorkspaceAdmissionIntent.Search -> AgentSearchScope.supports(path)

        is AgentWorkspaceAdmissionIntent.PagesCreate -> AgentPagesPaths.creatable(path.value)
        is AgentWorkspaceAdmissionIntent.PagesEdit -> AgentPagesPaths.editable(path.value)
        is AgentWorkspaceAdmissionIntent.PagesInvoke -> AgentPagesPaths.invokable(path.value, action)
        is AgentWorkspaceAdmissionIntent.ConnectorCreate -> CONNECTOR_INDEX_PATH.matches(path.value)
        is AgentWorkspaceAdmissionIntent.ConnectorEdit -> AgentPathPatterns.connector.matches(path.value)
        is AgentWorkspaceAdmissionIntent.ConnectorRotate -> AgentPathPatterns.connector.matches(path.value) && action == "rotate-credentials"
        is AgentWorkspaceAdmissionIntent.ConnectorDelete -> AgentPathPatterns.connector.matches(path.value) && action == "delete"
        is AgentWorkspaceAdmissionIntent.DashboardDelete -> action == "delete" && AgentPathPatterns.dashboard.matches(path.value)
        is AgentWorkspaceAdmissionIntent.DashboardRestore -> action == "restore" && AgentDashboardHistoryPaths.version.matches(path.value)
        is AgentWorkspaceAdmissionIntent.SemanticDelete -> action == "delete" && AgentSemanticHistoryPaths.definition.matches(path.value)
        is AgentWorkspaceAdmissionIntent.SemanticRestore -> action == "restore" && AgentSemanticHistoryPaths.version.matches(path.value)
        is AgentWorkspaceAdmissionIntent.SemanticCheck -> action == "check" && semanticCheckPath(path.value)
        is AgentWorkspaceAdmissionIntent.SemanticModelCreate -> path.value == SEMANTIC_MODEL_COLLECTION_PATH_VALUE
        is AgentWorkspaceAdmissionIntent.MetricCreate -> path.value == METRIC_COLLECTION_PATH_VALUE
        is AgentWorkspaceAdmissionIntent.DashboardCreate -> path.value == AgentPathPatterns.DASHBOARD_COLLECTION
        is AgentWorkspaceAdmissionIntent.SemanticModelEdit -> SEMANTIC_MODEL_PATH.matches(path.value)
        is AgentWorkspaceAdmissionIntent.MetricEdit -> METRIC_PATH.matches(path.value)
        is AgentWorkspaceAdmissionIntent.DashboardEdit -> AgentPathPatterns.dashboardDraft.matches(path.value)
        is AgentWorkspaceAdmissionIntent.CatalogEdit -> CATALOG_SELECTION_PATH.matches(path.value)
        is AgentWorkspaceAdmissionIntent.ExploreRun ->
            (path.value == EXPLORE_RUN_COLLECTION_PATH_VALUE && action == "run") ||
                (AgentPathPatterns.exploreRun.matches(path.value) && action == "cancel")
        is AgentWorkspaceAdmissionIntent.DashboardInvoke ->
            AgentPathPatterns.dashboardDraft.matches(path.value) ||
                AgentPathPatterns.dashboardDraftQueryRun.matches(path.value)
        is AgentWorkspaceAdmissionIntent.DashboardSessionInvoke ->
            (AgentPagesPaths.session.matches(path.value) && action == "resolve") ||
            (AgentPathPatterns.dashboardSessions.matches(path.value) && action == "open") ||
                (AgentPathPatterns.dashboardSession.matches(path.value) && action in setOf("resolve", "refresh"))
        is AgentWorkspaceAdmissionIntent.ManagementInvoke -> AgentManagementPaths.workspace(path.value) && when {
            path.value == "workspace/members" -> action == "add-member"
            AgentManagementPaths.workspaceMember.matches(path.value) -> action in setOf("remove-member", "set-role")
            else -> false
        }
        is AgentWorkspaceAdmissionIntent.CatalogInvoke -> AgentPathPatterns.catalogInvokeDeclared(path.value, action)
    }

private const val SEMANTIC_MODEL_COLLECTION_PATH_VALUE = "workspace/semantic-models"
private const val METRIC_COLLECTION_PATH_VALUE = "workspace/metrics"
private const val EXPLORE_RUN_COLLECTION_PATH_VALUE = "workspace/explore/runs"
private val CONNECTOR_INDEX_PATH = Regex("^workspace/connectors$")
private val CATALOG_COLLECTION_PATH = Regex(
    "^workspace/connectors/$ANY_VERSION_UUID/catalog/(?:" +
        "catalogs|(?:catalogs/[^/]+/)?schemas(?:/[^/]+/relations)?|" +
        "(?:catalogs/[^/]+/)?ingestions(?:/$UUID_V7/failures)?|" +
        "catalog-selection/(?:options|option-refreshes)/catalogs|" +
        "(?:catalogs/[^/]+/)?selection/(?:options|option-refreshes)/schemas(?:/[^/]+/relations)?)$",
)
private val CATALOG_RESOURCE_PATH = Regex(
    "^workspace/connectors/$ANY_VERSION_UUID/catalog(?:" +
        "/catalogs/[^/]+|/catalog-selection|/(?:catalogs/[^/]+/)?selection|" +
        "/(?:catalogs/[^/]+/)?schemas/[^/]+(?:/relations/[^/]+)?|" +
        "/(?:catalogs/[^/]+/)?ingestions/$UUID_V7(?:/failures/$UUID_V7)?|" +
        "/(?:catalog-selection/options/catalogs|(?:catalogs/[^/]+/)?selection/options/schemas(?:/[^/]+/relations)?)/[^/]+|" +
        "/(?:catalog-selection/option-refreshes/catalogs|(?:catalogs/[^/]+/)?selection/option-refreshes/schemas(?:/[^/]+/relations)?)/$UUID_V7)?$",
)
private val CATALOG_SELECTION_PATH = Regex(
    "^workspace/connectors/$ANY_VERSION_UUID/catalog/(?:catalog-selection|selection|catalogs/[^/]+/selection)$",
)

internal fun semanticCheckPath(path: String): Boolean =
    SEMANTIC_MODEL_COLLECTION_PATH.matches(path) || SEMANTIC_MODEL_PATH.matches(path) ||
        METRIC_COLLECTION_PATH.matches(path) || METRIC_PATH.matches(path)

private val SEMANTIC_MODEL_COLLECTION_PATH = Regex("^workspace/semantic-models$")
private val METRIC_COLLECTION_PATH = Regex("^workspace/metrics$")
private val SEMANTIC_MODEL_PATH = Regex("^workspace/semantic-models/$SEMANTIC_NAME$")
private val METRIC_PATH = Regex("^workspace/metrics/$SEMANTIC_NAME$")

val AgentWorkspaceAdmissionIntent.requiredRole: WorkspaceRole
    get() = when (this) {
        is AgentWorkspaceAdmissionIntent.ConnectorCreate -> cc.monomer.identity.api.MutationIntent.CONNECTOR_CREATE.requiredRole
        is AgentWorkspaceAdmissionIntent.ConnectorEdit,
        is AgentWorkspaceAdmissionIntent.ConnectorRotate -> cc.monomer.identity.api.MutationIntent.CONNECTOR_UPDATE.requiredRole
        is AgentWorkspaceAdmissionIntent.ConnectorDelete -> cc.monomer.identity.api.MutationIntent.CONNECTOR_DELETE.requiredRole
        is AgentWorkspaceAdmissionIntent.ManagementInvoke -> WorkspaceRole.OWNER
        is AgentWorkspaceAdmissionIntent.SemanticModelCreate -> cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE.requiredRole
        is AgentWorkspaceAdmissionIntent.MetricCreate -> cc.monomer.identity.api.MutationIntent.METRIC_CREATE.requiredRole
        is AgentWorkspaceAdmissionIntent.SemanticDelete -> if (path.value.startsWith("workspace/semantic-models/"))
            cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_DELETE.requiredRole else cc.monomer.identity.api.MutationIntent.METRIC_DELETE.requiredRole
        is AgentWorkspaceAdmissionIntent.SemanticRestore -> cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE.requiredRole
        is AgentWorkspaceAdmissionIntent.SemanticModelEdit -> cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE.requiredRole
        is AgentWorkspaceAdmissionIntent.MetricEdit -> cc.monomer.identity.api.MutationIntent.METRIC_UPDATE.requiredRole
        is AgentWorkspaceAdmissionIntent.CatalogEdit -> cc.monomer.identity.api.MutationIntent.CATALOG_SELECTION_UPDATE.requiredRole
        is AgentWorkspaceAdmissionIntent.Read, is AgentWorkspaceAdmissionIntent.List ->
            requireNotNull(readRequiredRole()) { "Unsupported Agent read operation and path" }
        is AgentWorkspaceAdmissionIntent.DashboardSessionInvoke ->
            if (AgentPagesPaths.session.matches(path.value) && action == "resolve") WorkspaceRole.VIEWER
            else WorkspaceRole.EXPLORER
        is AgentWorkspaceAdmissionIntent.Search ->
            if (AgentSearchScope.pagesOnly(path, kinds)) WorkspaceRole.VIEWER else WorkspaceRole.EXPLORER
        is AgentWorkspaceAdmissionIntent.PagesCreate,
        is AgentWorkspaceAdmissionIntent.PagesEdit,
        is AgentWorkspaceAdmissionIntent.PagesInvoke,
        is AgentWorkspaceAdmissionIntent.DashboardDelete,
        is AgentWorkspaceAdmissionIntent.DashboardRestore,
        is AgentWorkspaceAdmissionIntent.DashboardCreate,
        is AgentWorkspaceAdmissionIntent.DashboardEdit,
        is AgentWorkspaceAdmissionIntent.ExploreRun,
        is AgentWorkspaceAdmissionIntent.SemanticCheck,
        is AgentWorkspaceAdmissionIntent.DashboardInvoke,
        is AgentWorkspaceAdmissionIntent.CatalogInvoke -> WorkspaceRole.EXPLORER
    }

private fun AgentWorkspaceAdmissionIntent.readRequiredRole(): WorkspaceRole? = when (this) {
    is AgentWorkspaceAdmissionIntent.List -> when {
        path.value in setOf("workspace", "workspace/pages") || AgentPagesPaths.sessions.matches(path.value) ||
            AgentPagesPaths.runs.matches(path.value) -> WorkspaceRole.VIEWER
        path.value in setOf("workspace/members", "workspace/member-candidates") -> WorkspaceRole.OWNER
        path.value in setOf("workspace/lineage/relations", "workspace/lineage/semantic-models", "workspace/lineage/metrics") ||
            AgentPagesPaths.versions.matches(path.value) || CONNECTOR_INDEX_PATH.matches(path.value) ||
            AgentDashboardHistoryPaths.history.matches(path.value) || AgentSemanticHistoryPaths.history.matches(path.value) || CATALOG_COLLECTION_PATH.matches(path.value) || SEMANTIC_MODEL_COLLECTION_PATH.matches(path.value) ||
            METRIC_COLLECTION_PATH.matches(path.value) || path.value == EXPLORE_RUN_COLLECTION_PATH_VALUE ||
            path.value == AgentPathPatterns.DASHBOARD_COLLECTION || AgentDashboardSchedulePaths.occurrences.matches(path.value) ||
            AgentPathPatterns.dashboardSessions.matches(path.value) || AgentPathPatterns.dashboardDraftQueryRuns.matches(path.value) ||
            AgentPathPatterns.dashboardSessionQueryRuns.matches(path.value) -> WorkspaceRole.EXPLORER
        else -> null
    }
    is AgentWorkspaceAdmissionIntent.Read -> when {
        path.value in setOf("workspace", "workspace/access", "workspace/pages/tree") ||
            AgentPagesPaths.page.matches(path.value) || AgentPagesPaths.content.matches(path.value) ||
            AgentPagesPaths.session.matches(path.value) || AgentPagesPaths.run.matches(path.value) ||
            AgentPagesPaths.result.matches(path.value) -> WorkspaceRole.VIEWER
        path.value == "workspace/authorization" || AgentManagementPaths.workspaceMember.matches(path.value) ||
            AgentManagementPaths.memberCandidate.matches(path.value) -> WorkspaceRole.OWNER
        AgentManagementPaths.lineageResource.matches(path.value) || AgentPagesPaths.version.matches(path.value) ||
            AgentPathPatterns.connector.matches(path.value) || CATALOG_RESOURCE_PATH.matches(path.value) ||
            AgentDashboardHistoryPaths.version.matches(path.value) || AgentSemanticHistoryPaths.version.matches(path.value) || SEMANTIC_MODEL_PATH.matches(path.value) || METRIC_PATH.matches(path.value) ||
            AgentPathPatterns.exploreRun.matches(path.value) || AgentPathPatterns.exploreResult.matches(path.value) ||
            AgentPathPatterns.dashboard.matches(path.value) || AgentDashboardSchedulePaths.readable(path.value) ||
            AgentPathPatterns.dashboardDraft.matches(path.value) || AgentPathPatterns.dashboardDraftStatus.matches(path.value) || AgentPathPatterns.dashboardDraftQueryRun.matches(path.value) ||
            AgentPathPatterns.dashboardDraftResult.matches(path.value) || AgentPathPatterns.dashboardSession.matches(path.value) ||
            AgentPathPatterns.dashboardSessionQueryRun.matches(path.value) || AgentPathPatterns.dashboardSessionResult.matches(path.value) -> WorkspaceRole.EXPLORER
        else -> null
    }
    else -> null
}
