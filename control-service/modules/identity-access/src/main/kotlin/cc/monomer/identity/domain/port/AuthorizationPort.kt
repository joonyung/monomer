package cc.monomer.identity.domain.port

import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId

interface AuthorizationAssignmentRepository {
    fun actorIsOwnerAfterLock(actorUserId: UserId, workspaceId: WorkspaceId): Boolean
    fun actorIsOrganizationAdminAfterLock(actorUserId: UserId, workspaceId: WorkspaceId): Boolean
    fun isEligibleMemberRoleTarget(workspaceId: WorkspaceId, userId: UserId, requireDirectMembership: Boolean): Boolean
    fun setMemberRole(workspaceId: WorkspaceId, userId: UserId, role: cc.monomer.identity.api.WorkspaceRole): AuthorizationAssignmentTransition
}

sealed interface AuthorizationAssignmentTransition {
    data object Changed : AuthorizationAssignmentTransition
    data object NoTransition : AuthorizationAssignmentTransition
}
