@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package cc.monomer.agent.api

import cc.monomer.identity.api.OrganizationId
import cc.monomer.identity.api.AgentOAuthPort
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.OAuthOutcome
import cc.monomer.identity.api.PresentedCredential
import java.time.Instant
import kotlin.uuid.Uuid

class AgentOrganizationCaller private constructor(
    val organizationId: OrganizationId,
    val actorPrincipalId: String,
    val clientId: String,
    val presentedCredential: PresentedCredential,
    val expectedOAuthResource: ExpectedOAuthResource,
    val requestId: AgentRequestId,
) {
    companion object {
        suspend fun authenticate(organizationId: OrganizationId, oauthPort: AgentOAuthPort, credential: PresentedCredential, audience: AgentWorkspaceAdmissionAudience, requestId: AgentRequestId): OAuthOutcome<AgentOrganizationCaller> =
            when (val outcome = oauthPort.validateCredential(credential, audience.expectedOAuthResource)) {
                is OAuthOutcome.Failure -> outcome
                is OAuthOutcome.Success -> OAuthOutcome.Success(AgentOrganizationCaller(organizationId, outcome.value.userId.value.toString(), outcome.value.clientId, credential, audience.expectedOAuthResource, requestId))
            }
    }
    override fun toString(): String = "AgentOrganizationCaller([REDACTED])"
}

interface AgentManagementOwnerPort {
    suspend fun list(caller: AgentOrganizationCaller, request: AgentListRequest): AgentListingOutcome
    suspend fun read(caller: AgentOrganizationCaller, request: AgentReadRequest): AgentResourceOutcome
    suspend fun create(caller: AgentOrganizationCaller, request: AgentCreateRequest): AgentActionOutcome
    suspend fun invoke(caller: AgentOrganizationCaller, request: AgentInvokeRequest): AgentActionOutcome
    suspend fun list(caller: AgentWorkspaceCaller, request: AgentListRequest): AgentListingOutcome
    suspend fun read(caller: AgentWorkspaceCaller, request: AgentReadRequest): AgentResourceOutcome
    suspend fun invoke(caller: AgentWorkspaceCaller, request: AgentInvokeRequest): AgentActionOutcome
}

enum class AgentManagementResourceKind { RELATION, SEMANTIC_MODEL, METRIC }
data class AgentManagementResource(val kind: AgentManagementResourceKind, val id: Uuid)
enum class AgentOrganizationRole { ADMIN, MEMBER }
enum class AgentInvitationStatus { PENDING, EXPIRED }
data class AgentOrganizationSelf(val userId: Uuid, val displayName: String, val role: AgentOrganizationRole)
data class AgentOrganizationBody(val id: Uuid, val displayName: String, val caller: AgentOrganizationSelf) : AgentResourceBody { override val kind = AgentResourceKind.ORGANIZATION }
data class AgentOrganizationMemberBody(val userId: Uuid, val displayName: String, val email: String, val role: AgentOrganizationRole) : AgentResourceBody { override val kind = AgentResourceKind.ORGANIZATION_MEMBER }
data class AgentOrganizationInvitationBody(val id: Uuid, val email: String, val status: AgentInvitationStatus, val expiresAt: Instant, val createdAt: Instant, val lastDeliveryAttemptAt: Instant?, val lastSentAt: Instant?, val workspaceAccess: List<AgentInvitationWorkspaceAccessBody>) : AgentResourceBody { override val kind = AgentResourceKind.ORGANIZATION_INVITATION }
data class AgentInvitationWorkspaceAccessBody(val workspaceId: Uuid, val workspaceDisplayName: String, val role: cc.monomer.identity.api.WorkspaceRole)
sealed interface AgentWorkspaceMemberBody : AgentResourceBody {
    override val kind: AgentResourceKind get() = AgentResourceKind.WORKSPACE_MEMBER
    data class Member(val userId: Uuid, val displayName: String, val email: String, val role: cc.monomer.identity.api.WorkspaceRole) : AgentWorkspaceMemberBody
    data class Candidate(val userId: Uuid, val displayName: String, val email: String) : AgentWorkspaceMemberBody
}
data class AgentWorkspaceAccessBody(val organizationId: Uuid, val role: cc.monomer.identity.api.WorkspaceRole, val authorizationRevision: AgentRevision, val reportingTimeZone: java.time.ZoneId) : AgentResourceBody { override val kind = AgentResourceKind.WORKSPACE_ACCESS }
data class AgentWorkspaceAuthorizationBody(val authorizationRevision: AgentRevision, val members: List<AgentWorkspaceMemberBody.Member>) : AgentResourceBody { override val kind = AgentResourceKind.WORKSPACE_AUTHORIZATION }

data class AgentLineageConnector(val id: Uuid, val displayName: String, val type: cc.monomer.connector.api.ConnectorProductType)
data class AgentLineageResource(val ref: AgentManagementResource, val label: String, val address: String, val description: String?, val connector: AgentLineageConnector?, val missing: Boolean) {
    init { require((ref.kind == AgentManagementResourceKind.RELATION) == (connector != null)) }
}
data class AgentLineageEdge(val dependent: AgentManagementResource, val parent: AgentManagementResource)
data class AgentLineageEntityPeer(val resource: AgentLineageResource, val sharedEntities: List<String>, val alreadyInCone: Boolean)
sealed interface AgentLineageDetail {
    data class Relation(val columns: List<AgentLineageColumn>) : AgentLineageDetail
    data class SemanticModel(val entities: List<AgentLineageEntity>, val dimensions: List<AgentLineageDimension>, val measures: List<AgentLineageMeasure>) : AgentLineageDetail
    data class Metric(val type: AgentMetricType, val inputs: List<AgentLineageMetricInput>, val timeGranularity: AgentTimeGranularity?) : AgentLineageDetail
}
data class AgentLineageColumn(val name: String, val nativeType: String, val nullable: Boolean, val hasDefault: Boolean, val primaryKey: Boolean, val foreignKey: Boolean)
data class AgentLineageEntity(val name: String, val label: String?, val description: String?, val type: AgentSemanticEntityKind)
data class AgentLineageDimension(val name: String, val label: String?, val description: String?, val type: AgentSemanticDimensionKind, val granularity: AgentTimeGranularity?)
data class AgentLineageMeasure(val name: String, val label: String?, val description: String?, val aggregation: AgentSemanticAggregation)
enum class AgentLineageMetricInputKind { MEASURE, METRIC }
data class AgentLineageMetricInput(val kind: AgentLineageMetricInputKind, val name: String, val semanticModelName: String?) {
    init { require((kind == AgentLineageMetricInputKind.MEASURE) == (semanticModelName != null)) }
}
data class AgentLineageDashboardUsage(val dashboardId: Uuid, val title: String, val queryNames: List<String>, val metricIds: List<Uuid>)
data class AgentLineageResourceBody(val workspaceId: Uuid, val focus: AgentLineageResource, val ancestors: List<AgentLineageResource>, val descendants: List<AgentLineageResource>, val siblings: List<AgentLineageResource>, val edges: List<AgentLineageEdge>, val siblingEdges: List<AgentLineageEdge>, val entityPeers: List<AgentLineageEntityPeer>, val detail: AgentLineageDetail, val missingDefinitions: List<AgentLineageResource>, val dashboardUsages: List<AgentLineageDashboardUsage>) : AgentResourceBody {
    override val kind = AgentResourceKind.LINEAGE_RESOURCE
    init { require(when(focus.ref.kind) { AgentManagementResourceKind.RELATION -> detail is AgentLineageDetail.Relation; AgentManagementResourceKind.SEMANTIC_MODEL -> detail is AgentLineageDetail.SemanticModel; AgentManagementResourceKind.METRIC -> detail is AgentLineageDetail.Metric }) }
}

enum class AgentMetricType { SIMPLE, RATIO, DERIVED, CUMULATIVE, CONVERSION }
