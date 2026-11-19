package cc.monomer.identity.application.organization

import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.application.normalizedDisplayName
import cc.monomer.identity.application.workspace.WorkspaceAuthorizationBootstrap
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.Organization
import cc.monomer.identity.domain.model.OrganizationMembership
import cc.monomer.identity.api.OrganizationPlan
import cc.monomer.identity.domain.model.OrganizationRole
import cc.monomer.identity.domain.model.ReportingTimeZone
import cc.monomer.identity.domain.model.Workspace
import cc.monomer.identity.domain.port.OrganizationRepository
import cc.monomer.identity.domain.port.AuditLogRepository
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.domain.port.WorkspaceRepository
import java.time.Clock

interface CreateOrganizationUseCase {
    suspend fun create(command: Command): Response

    data class Command(
        val actorUserId: UserId,
        val initialOwnerUserId: UserId,
        val displayName: String,
    )

    data class Response(
        val organization: Organization,
        val defaultWorkspace: Workspace,
        val membership: OrganizationMembership,
    )
}

class CreateOrganizationService(
    private val transactionRunner: TransactionRunner,
    private val organizationRepository: OrganizationRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceAuthorizationBootstrap: WorkspaceAuthorizationBootstrap,
    private val auditLogRepository: AuditLogRepository,
    private val auditEventFactory: AuditEventFactory,
    private val clock: Clock,
) : CreateOrganizationUseCase {

    override suspend fun create(command: CreateOrganizationUseCase.Command): CreateOrganizationUseCase.Response {
        val displayName = normalizedDisplayName(command.displayName, "organization")
        val now = clock.instant()
        val organization = Organization(
            id = OrganizationId.generate(),
            displayName = displayName,
            billingEmail = null,
            plan = OrganizationPlan.FREE,
            createdAt = now,
        )
        val membership = OrganizationMembership(
            organizationId = organization.id,
            userId = command.actorUserId,
            role = OrganizationRole.ADMIN,
            joinedAt = now,
        )
        val workspace = Workspace(
            id = WorkspaceId.generate(),
            organizationId = organization.id,
            displayName = "Default Workspace",
            createdAt = now,
            publicPages = false,
            reportingTimeZone = ReportingTimeZone.UTC,
            sample = null,
        )

        transactionRunner.run {
            organizationRepository.requireFreeOrganizationAdminCapacity(command.actorUserId)
            organizationRepository.insert(organization)
            organizationRepository.insertMember(membership)
            if (command.initialOwnerUserId != command.actorUserId) {
                organizationRepository.insertMember(
                    OrganizationMembership(
                        organizationId = organization.id,
                        userId = command.initialOwnerUserId,
                        role = OrganizationRole.MEMBER,
                        joinedAt = now,
                    ),
                )
            }
            workspaceRepository.insert(workspace)
            workspaceAuthorizationBootstrap.createInitialBundle(
                workspaceId = workspace.id,
                initialOwnerUserId = command.initialOwnerUserId,
                assignedByUserId = command.actorUserId,
                assignedAt = now,
            )
            auditLogRepository.insert(
                auditEventFactory.build(
                    actorId = command.actorUserId.asPrincipal(),
                    action = "organization.create",
                    organizationId = organization.id,
                    workspaceId = null,
                    resourceType = "organization",
                    resourceId = organization.id.value,
                    details = "{}",
                ),
            )
        }

        return CreateOrganizationUseCase.Response(
            organization = organization,
            defaultWorkspace = workspace,
            membership = membership,
        )
    }
}
