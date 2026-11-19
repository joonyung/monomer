package cc.monomer.identity.api

import java.time.Instant

enum class WorkspaceSampleState { PROVISIONING, READY }

data class WorkspaceSampleMarker(
    val dataset: String,
    val state: WorkspaceSampleState,
    val initiator: PrincipalId,
) {
    init { require(dataset in setOf("acme-cloud@1", "acme-cloud@2", "acme-cloud@3")) { "Unsupported sample dataset" } }
}

data class SampleWorkspace(
    val workspaceId: WorkspaceId,
    val organizationId: OrganizationId,
    val displayName: String,
    val createdAt: Instant,
    val sample: WorkspaceSampleMarker,
)

fun interface WorkspaceSampleQuery {
    suspend fun readMarker(workspaceId: WorkspaceId): WorkspaceSampleMarker?
}

interface SampleWorkspaceProvisioningUseCase : WorkspaceSampleQuery {
    suspend fun create(actorUserId: PrincipalId, organizationId: OrganizationId, dataset: String): SampleWorkspace
    suspend fun findByOrganization(actorUserId: PrincipalId, organizationId: OrganizationId): SampleWorkspace?
    suspend fun readInitiatorEvidence(workspaceId: WorkspaceId, dataset: String): SampleWorkspaceInitiatorEvidence?
    suspend fun markReady(workspaceId: WorkspaceId, dataset: String)
    suspend fun addAdminOwner(actorUserId: PrincipalId, workspaceId: WorkspaceId)
}

data class SampleWorkspaceInitiatorEvidence(
    val organizationPlan: OrganizationPlan,
    val actor: CanonicalPrincipal,
    val organizationId: OrganizationId,
    val workspaceId: WorkspaceId,
    val authorizationRevision: AuthorizationRevision,
    val role: WorkspaceRole,
)

class SampleWorkspaceAdminRequiredException : RuntimeException("Organization Admin is required")
class SampleWorkspaceLimitExceededException : RuntimeException("The free Workspace limit has been reached")
