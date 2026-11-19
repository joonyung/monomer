package cc.monomer.identity.application

import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.PrincipalId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.OrganizationRole
import cc.monomer.identity.domain.port.OrganizationRepository

class AdminAuthorization(
    private val organizationRepository: OrganizationRepository,
) {

    fun requireOrganizationAdminForWrite(actorPrincipalId: PrincipalId, organizationId: OrganizationId): UserId {
        val actorUserId = UserId(actorPrincipalId.value)
        val membership = organizationRepository.lockAndFindMembership(organizationId, actorUserId)
        if (membership?.role != OrganizationRole.ADMIN) {
            throw OrganizationAdminRequiredException(
                "principal $actorPrincipalId must be ADMIN of organization $organizationId",
            )
        }
        return actorUserId
    }
}
