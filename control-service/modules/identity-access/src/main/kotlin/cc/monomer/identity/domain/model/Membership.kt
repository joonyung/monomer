package cc.monomer.identity.domain.model

import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import java.time.Instant

enum class OrganizationRole {
    ADMIN,
    MEMBER,
}

/** Organization-level membership and administration role. */
data class OrganizationMembership(
    val organizationId: OrganizationId,
    val userId: UserId,
    val role: OrganizationRole,
    val joinedAt: Instant,
)

data class WorkspaceMembership(
    val workspaceId: WorkspaceId,
    val userId: UserId,
    val invitedBy: UserId?,
    val invitedAt: Instant,
    val role: cc.monomer.identity.api.WorkspaceRole,
)

/**
 * Organization-roster read model — an organization member paired with the resolved [User].
 * Roster-only; not a write target.
 */
data class OrganizationMember(
    val user: User,
    val role: OrganizationRole,
    val joinedAt: Instant,
    val workspaces: List<OrganizationMemberWorkspace>,
)

/** Workspace-roster read model. Workspace admission is direct-user only. */
data class WorkspaceMember(
    val user: User,
    val role: cc.monomer.identity.api.WorkspaceRole,
)

data class OrganizationMemberWorkspace(
    val id: WorkspaceId,
    val name: String,
    val role: cc.monomer.identity.api.WorkspaceRole,
    val memberCount: Int,
    val ownerCount: Int,
    val sample: cc.monomer.identity.api.WorkspaceSampleMarker?,
)
