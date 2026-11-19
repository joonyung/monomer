package cc.monomer.identity.application.me

import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.Organization
import cc.monomer.identity.domain.model.OrganizationRole
import cc.monomer.identity.domain.model.User

interface MeUseCase {
    suspend fun me(command: Command): Response

    data class Command(val userId: UserId)

    data class Response(
        val user: User,
        val organizationMemberships: List<OrganizationMembershipView>,
    )

    data class OrganizationMembershipView(
        val organization: Organization,
        val role: OrganizationRole,
    )
}
