package cc.monomer.identity.domain.port

import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.OrganizationMember
import cc.monomer.identity.domain.model.OrganizationUsage
import cc.monomer.identity.domain.model.Workspace
import java.time.Instant

interface OrganizationMembersQueryPort {
    suspend fun readOrganizationMembers(
        actorUserId: UserId,
        organizationId: OrganizationId,
    ): OrganizationMembersQueryResult
}

sealed interface OrganizationMembersQueryResult {
    data class Authorized(
        val members: List<OrganizationMember>,
    ) : OrganizationMembersQueryResult

    data object Denied : OrganizationMembersQueryResult
}

interface ManagedWorkspacesQueryPort {
    suspend fun readManagedWorkspaces(
        actorUserId: UserId,
        organizationId: OrganizationId,
    ): ManagedWorkspacesQueryResult
}

sealed interface ManagedWorkspacesQueryResult {
    data class Authorized(
        val workspaces: List<Workspace>,
    ) : ManagedWorkspacesQueryResult

    data object Denied : ManagedWorkspacesQueryResult
}

interface OrganizationUsageQueryPort {
    suspend fun readOrganizationUsage(
        actorUserId: UserId,
        organizationId: OrganizationId,
        now: Instant,
    ): OrganizationUsageQueryResult
}

sealed interface OrganizationUsageQueryResult {
    data class Authorized(
        val usage: OrganizationUsage,
    ) : OrganizationUsageQueryResult

    data object Denied : OrganizationUsageQueryResult
}
