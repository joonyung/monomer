package cc.monomer.identity.application.grant

import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.application.audit.GovernanceAudit
import cc.monomer.identity.application.audit.withGovernanceAudit
import cc.monomer.identity.application.workspace.OwnerWorkspaceMutationRunner
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.port.AuditLogRepository
import cc.monomer.identity.domain.port.AuthorizationAssignmentRepository
import cc.monomer.identity.domain.port.AuthorizationAssignmentTransition
import cc.monomer.identity.domain.port.WorkspaceMemberRepository
import cc.monomer.identity.domain.port.WorkspaceMemberInsertion
import cc.monomer.identity.domain.model.WorkspaceMembership
import java.time.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class InvalidWorkspaceRoleTargetException(message: String) : RuntimeException(message)

interface SetWorkspaceMemberRoleUseCase {
    suspend fun set(command: Command): Boolean
    data class Command(val actorUserId: UserId, val workspaceId: WorkspaceId, val targetUserId: UserId, val role: cc.monomer.identity.api.WorkspaceRole, val audit: GovernanceAudit?)
}

class SetWorkspaceMemberRoleService(
    private val workspaceMutationRunner: OwnerWorkspaceMutationRunner,
    private val authorizationAssignmentRepository: AuthorizationAssignmentRepository,
    private val auditLogRepository: AuditLogRepository,
    private val auditEventFactory: AuditEventFactory,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val clock: Clock,
) : SetWorkspaceMemberRoleUseCase {
    override suspend fun set(command: SetWorkspaceMemberRoleUseCase.Command): Boolean =
        workspaceMutationRunner.runAsOrganizationAdminOrOwner(command.actorUserId, command.workspaceId) { actorIsOrganizationAdmin ->
            if (!authorizationAssignmentRepository.isEligibleMemberRoleTarget(command.workspaceId, command.targetUserId, !actorIsOrganizationAdmin)) {
                throw InvalidWorkspaceRoleTargetException("role target must be a direct workspace member")
            }
            val inserted = actorIsOrganizationAdmin && workspaceMemberRepository.insert(
                WorkspaceMembership(command.workspaceId, command.targetUserId, command.actorUserId, clock.instant(), command.role),
            ) == WorkspaceMemberInsertion.Inserted
            if (inserted) auditLogRepository.insert(auditEventFactory.build(
                actorId = command.actorUserId.asPrincipal(), action = "workspace.member.add", workspaceId = command.workspaceId, organizationId = null,
                resourceType = "user", resourceId = command.targetUserId.value,
                details = buildJsonObject { put("role", command.role.name) }.toString(),
            ).withGovernanceAudit(command.audit))
            val changed = authorizationAssignmentRepository.setMemberRole(command.workspaceId, command.targetUserId, command.role) == AuthorizationAssignmentTransition.Changed
            if (changed) auditLogRepository.insert(auditEventFactory.build(
                actorId = command.actorUserId.asPrincipal(), action = "workspace.member.role.set", workspaceId = command.workspaceId, organizationId = null,
                resourceType = "user", resourceId = command.targetUserId.value,
                details = buildJsonObject { put("role", command.role.name) }.toString(),
            ).withGovernanceAudit(command.audit))
            inserted || changed
        }
}
