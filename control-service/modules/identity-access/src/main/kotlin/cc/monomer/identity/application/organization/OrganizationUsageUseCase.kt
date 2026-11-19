package cc.monomer.identity.application.organization

import cc.monomer.identity.application.OrganizationAdminRequiredException
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.OrganizationUsage
import cc.monomer.identity.domain.port.OrganizationUsageQueryPort
import cc.monomer.identity.domain.port.OrganizationUsageQueryResult
import java.time.Clock

interface OrganizationUsageUseCase {
    suspend fun read(command: Command): OrganizationUsage
    data class Command(val actorUserId: UserId, val organizationId: OrganizationId)
}

/** Reads plan limits and the counts the free-limit checks use. Organization ADMIN only. */
class OrganizationUsageService(
    private val organizationUsageQueryPort: OrganizationUsageQueryPort,
    private val clock: Clock,
) : OrganizationUsageUseCase {

    override suspend fun read(command: OrganizationUsageUseCase.Command): OrganizationUsage =
        when (
            val result = organizationUsageQueryPort.readOrganizationUsage(
                actorUserId = command.actorUserId,
                organizationId = command.organizationId,
                now = clock.instant(),
            )
        ) {
            is OrganizationUsageQueryResult.Authorized -> result.usage
            OrganizationUsageQueryResult.Denied -> throw OrganizationAdminRequiredException(
                "user ${command.actorUserId} must be ADMIN of organization ${command.organizationId} to read usage",
            )
        }
}
