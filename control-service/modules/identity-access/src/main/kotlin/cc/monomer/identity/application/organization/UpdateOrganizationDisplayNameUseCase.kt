package cc.monomer.identity.application.organization

import cc.monomer.identity.application.normalizedDisplayName

import cc.monomer.identity.application.AdminAuthorization
import cc.monomer.identity.application.ScopedResourceNotFoundException
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.Organization
import cc.monomer.identity.domain.port.OrganizationRepository
import cc.monomer.identity.domain.port.AuditLogRepository
import cc.monomer.identity.domain.port.TransactionRunner

interface UpdateOrganizationDisplayNameUseCase {
    suspend fun update(command: Command): Organization
    data class Command(val actorUserId: UserId, val organizationId: OrganizationId, val displayName: String)
}

class UpdateOrganizationDisplayNameService(
    private val adminAuthorization: AdminAuthorization,
    private val organizationRepository: OrganizationRepository,
    private val transactionRunner: TransactionRunner,
    private val auditLogRepository: AuditLogRepository,
    private val auditEventFactory: AuditEventFactory,
) : UpdateOrganizationDisplayNameUseCase {
    override suspend fun update(command: UpdateOrganizationDisplayNameUseCase.Command): Organization {
        val displayName = normalizedDisplayName(command.displayName, "organization")
        return transactionRunner.run {
            adminAuthorization.requireOrganizationAdminForWrite(command.actorUserId.asPrincipal(), command.organizationId)
            val organization = organizationRepository.updateDisplayName(command.organizationId, displayName)
                ?: throw ScopedResourceNotFoundException("organization not found")
            auditLogRepository.insert(auditEventFactory.build(
                actorId = command.actorUserId.asPrincipal(), action = "organization.display-name.update",
                workspaceId = null, organizationId = command.organizationId, resourceType = "organization",
                resourceId = command.organizationId.value, details = "{}",
            ))
            organization
        }
    }
}
