package cc.monomer.identity.application.me

import cc.monomer.identity.domain.port.OrganizationQuery
import cc.monomer.identity.domain.port.UserQuery

class MeService(
    private val userQuery: UserQuery,
    private val organizationQuery: OrganizationQuery,
) : MeUseCase {

    override suspend fun me(command: MeUseCase.Command): MeUseCase.Response {
        val user = userQuery.find(command.userId)
            ?: error("user not found: ${command.userId}")
        val organizationMemberships = organizationQuery.listMembershipsByUser(user.userId)
            .mapNotNull { m ->
                val organization = organizationQuery.find(m.organizationId) ?: return@mapNotNull null
                MeUseCase.OrganizationMembershipView(
                    organization = organization,
                    role = m.role,
                )
            }
        return MeUseCase.Response(user = user, organizationMemberships = organizationMemberships)
    }
}
