package cc.monomer.identity.domain.port

import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.Organization
import cc.monomer.identity.domain.model.OrganizationMembership
import cc.monomer.identity.domain.model.OrganizationRole

interface OrganizationQuery {
    suspend fun find(id: OrganizationId): Organization?
    suspend fun listMembershipsByUser(userId: UserId): List<OrganizationMembership>
    suspend fun findMembership(organizationId: OrganizationId, userId: UserId): OrganizationMembership?
}

interface OrganizationRepository {
    fun requireFreeOrganizationAdminCapacity(userId: UserId)
    fun updateDisplayName(organizationId: OrganizationId, displayName: String): Organization?
    fun insert(organization: Organization)
    fun insertMember(membership: OrganizationMembership)
    fun lockAndFindMembership(organizationId: OrganizationId, userId: UserId): OrganizationMembership?
    fun updateMemberRole(organizationId: OrganizationId, userId: UserId, role: OrganizationRole): OrganizationMemberRoleTransition
    fun deleteMember(organizationId: OrganizationId, userId: UserId): OrganizationMemberRemoval
}

sealed interface OrganizationMemberRoleTransition {
    data class Updated(val membership: OrganizationMembership) : OrganizationMemberRoleTransition
    data object NotFound : OrganizationMemberRoleTransition
    data object NoTransition : OrganizationMemberRoleTransition
}

sealed interface OrganizationMemberRemoval {
    data object Removed : OrganizationMemberRemoval
    data object NotFound : OrganizationMemberRemoval
}
