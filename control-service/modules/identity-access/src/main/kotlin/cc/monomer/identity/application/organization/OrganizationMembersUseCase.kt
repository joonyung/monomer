package cc.monomer.identity.application.organization

import cc.monomer.identity.application.OrganizationAdminRequiredException
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.OrganizationMember
import cc.monomer.identity.domain.port.OrganizationMembersQueryPort
import cc.monomer.identity.domain.port.OrganizationMembersQueryResult

interface OrganizationMembersUseCase {
    suspend fun list(command: Command): List<OrganizationMember>
    data class Command(val actorUserId: UserId, val organizationId: OrganizationId)
}

/**
 * Lists the members of an organization. Organization-ADMIN only — the member roster (including roles)
 * is governance data, so a plain member cannot enumerate it.
 */
class OrganizationMembersService(
    private val organizationMembersQueryPort: OrganizationMembersQueryPort,
) : OrganizationMembersUseCase {

    override suspend fun list(command: OrganizationMembersUseCase.Command): List<OrganizationMember> =
        when (
            val result = organizationMembersQueryPort.readOrganizationMembers(
                actorUserId = command.actorUserId,
                organizationId = command.organizationId,
            )
        ) {
            is OrganizationMembersQueryResult.Authorized -> result.members
            OrganizationMembersQueryResult.Denied -> throw OrganizationAdminRequiredException(
                "user ${command.actorUserId} must be ADMIN of organization ${command.organizationId} to list members",
            )
        }
}
