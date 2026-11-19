package cc.monomer.identity.domain.port

import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.OrganizationMember
import cc.monomer.identity.domain.model.User
import cc.monomer.identity.domain.model.WorkspaceActorAuthorizationDenial
import cc.monomer.identity.domain.model.WorkspaceActorAuthorizationSnapshot
import cc.monomer.identity.domain.model.WorkspaceMember

interface WorkspaceAccessQueryPort {
    suspend fun readWorkspaceAccess(
        actorUserId: UserId,
        workspaceId: WorkspaceId,
    ): WorkspaceAccessQueryResult
}

sealed interface WorkspaceAccessQueryResult {
    data class Authorized(
        val authorization: WorkspaceActorAuthorizationSnapshot,
        val projection: WorkspaceAccessProjection,
    ) : WorkspaceAccessQueryResult

    data class Denied(
        val reason: WorkspaceActorAuthorizationDenial,
    ) : WorkspaceAccessQueryResult
}

data class WorkspaceAccessProjection(
    val role: cc.monomer.identity.api.WorkspaceRole,
    val owners: List<WorkspaceOwnerSummary>,
)

data class WorkspaceOwnerSummary(
    val userId: UserId,
    val displayName: String,
)

interface WorkspaceAuthorizationAssignmentsQueryPort {
    suspend fun readWorkspaceAuthorizationAssignments(
        actorUserId: UserId,
        workspaceId: WorkspaceId,
    ): WorkspaceAuthorizationAssignmentsQueryResult
}

sealed interface WorkspaceAuthorizationAssignmentsQueryResult {
    data class Authorized(
        val authorization: WorkspaceActorAuthorizationSnapshot,
        val members: List<WorkspaceMember>,
    ) : WorkspaceAuthorizationAssignmentsQueryResult

    data object Denied : WorkspaceAuthorizationAssignmentsQueryResult
}

interface WorkspaceMembersQueryPort {
    suspend fun readWorkspaceMembers(
        actorUserId: UserId,
        workspaceId: WorkspaceId,
    ): WorkspaceMembersQueryResult
}

sealed interface WorkspaceMembersQueryResult {
    data class Authorized(
        val authorization: WorkspaceActorAuthorizationSnapshot,
        val members: List<WorkspaceMember>,
    ) : WorkspaceMembersQueryResult

    data object Denied : WorkspaceMembersQueryResult
}

interface WorkspaceMemberCandidatesQueryPort {
    suspend fun readWorkspaceMemberCandidates(
        actorUserId: UserId,
        workspaceId: WorkspaceId,
    ): WorkspaceMemberCandidatesQueryResult
}

sealed interface WorkspaceMemberCandidatesQueryResult {
    data class Authorized(
        val authorization: WorkspaceActorAuthorizationSnapshot,
        val users: List<OrganizationMember>,
    ) : WorkspaceMemberCandidatesQueryResult

    data object WorkspaceNotFound : WorkspaceMemberCandidatesQueryResult
    data object Denied : WorkspaceMemberCandidatesQueryResult
}

interface WorkspaceOwnerCandidatesQueryPort {
    suspend fun readWorkspaceOwnerCandidates(
        actorUserId: UserId,
        organizationId: OrganizationId,
        workspaceId: WorkspaceId,
    ): WorkspaceOwnerCandidatesQueryResult
}

sealed interface WorkspaceOwnerCandidatesQueryResult {
    data class Authorized(
        val authorization: WorkspaceActorAuthorizationSnapshot,
        val users: List<User>,
    ) : WorkspaceOwnerCandidatesQueryResult

    data class WorkspaceNotFound(
        val reason: WorkspaceScopeFailure,
    ) : WorkspaceOwnerCandidatesQueryResult

    data object Denied : WorkspaceOwnerCandidatesQueryResult
}

enum class WorkspaceScopeFailure {
    WORKSPACE_NOT_FOUND,
    ORGANIZATION_MISMATCH,
}
