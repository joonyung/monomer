package cc.monomer.identity.application.workspace

import cc.monomer.identity.application.grant.InvalidWorkspaceRoleTargetException
import cc.monomer.identity.domain.port.OrganizationRepository
import cc.monomer.identity.application.AdminAuthorization
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.application.normalizedDisplayName
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.ReportingTimeZone
import cc.monomer.identity.domain.model.Workspace
import cc.monomer.identity.domain.port.AuditLogRepository
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.domain.port.WorkspaceRepository
import java.time.Clock

interface CreateWorkspaceUseCase {
    suspend fun create(command: Command): Workspace
    data class Command(
        val actorUserId: UserId,
        val initialOwnerUserId: UserId,
        val organizationId: OrganizationId,
        val displayName: String,
    )
}

/**
 * Creates a workspace under an organization. Organization-ADMIN only.
 *
 * Atomically inserts the workspace and the nominated initial owner's authorization bundle.
 */
class CreateWorkspaceService(
    private val transactionRunner: TransactionRunner,
    private val adminAuthorization: AdminAuthorization,
    private val workspaceRepository: WorkspaceRepository,
    private val organizationRepository: OrganizationRepository,
    private val workspaceAuthorizationBootstrap: WorkspaceAuthorizationBootstrap,
    private val auditLogRepository: AuditLogRepository,
    private val auditEventFactory: AuditEventFactory,
    private val clock: Clock,
) : CreateWorkspaceUseCase {

    override suspend fun create(command: CreateWorkspaceUseCase.Command): Workspace {
        val now = clock.instant()
        val creatorPrincipalId = command.actorUserId.asPrincipal()
        val workspace = Workspace(
            id = WorkspaceId.generate(),
            organizationId = command.organizationId,
            displayName = normalizedDisplayName(command.displayName, "workspace"),
            createdAt = now,
            publicPages = false,
            reportingTimeZone = ReportingTimeZone.UTC,
            sample = null,
        )

        transactionRunner.run {
            adminAuthorization.requireOrganizationAdminForWrite(creatorPrincipalId, command.organizationId)
            if (organizationRepository.lockAndFindMembership(command.organizationId, command.initialOwnerUserId) == null) {
                throw InvalidWorkspaceRoleTargetException("initial Workspace Owner must be an same-organization user")
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
                    actorId = creatorPrincipalId,
                    action = "workspace.create",
                    organizationId = command.organizationId,
                    workspaceId = workspace.id,
                    resourceType = "workspace",
                    resourceId = workspace.id.value,
                    details = "{}",
                ),
            )
        }

        return workspace
    }
}
