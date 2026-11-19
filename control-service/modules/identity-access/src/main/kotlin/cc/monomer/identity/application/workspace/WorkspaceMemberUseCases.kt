package cc.monomer.identity.application.workspace

import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.application.audit.GovernanceAudit
import cc.monomer.identity.application.audit.withGovernanceAudit
import cc.monomer.identity.application.grant.InvalidWorkspaceRoleTargetException
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.WorkspaceMembership
import cc.monomer.identity.domain.port.AuditLogRepository
import cc.monomer.identity.domain.port.AuthorizationAssignmentRepository
import cc.monomer.identity.domain.port.WorkspaceMemberInsertion
import cc.monomer.identity.domain.port.WorkspaceMemberRemoval
import cc.monomer.identity.domain.port.WorkspaceMemberRepository
import java.time.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class WorkspaceMemberRoleConflictException : RuntimeException(
    "Member already has a different role; use set-role to change it.",
)

interface AddWorkspaceMemberUseCase {
    suspend fun add(command: Command): WorkspaceMembership

    data class Command(
        val actorUserId: UserId,
        val workspaceId: WorkspaceId,
        val targetUserId: UserId,
        val audit: GovernanceAudit?,
        val role: cc.monomer.identity.api.WorkspaceRole,
    )
}

class AddWorkspaceMemberService(
    private val workspaceMutationRunner: OwnerWorkspaceMutationRunner,
    private val authorizationAssignmentRepository: AuthorizationAssignmentRepository,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val auditLogRepository: AuditLogRepository,
    private val auditEventFactory: AuditEventFactory,
    private val clock: Clock,
) : AddWorkspaceMemberUseCase {

    override suspend fun add(command: AddWorkspaceMemberUseCase.Command): WorkspaceMembership =
        workspaceMutationRunner.run(command.actorUserId, command.workspaceId) {
            if (!authorizationAssignmentRepository.isEligibleMemberRoleTarget(command.workspaceId, command.targetUserId, requireDirectMembership = false)) {
                throw InvalidWorkspaceRoleTargetException("member target must be a user of the workspace organization")
            }
            val membership = WorkspaceMembership(
                workspaceId = command.workspaceId,
                userId = command.targetUserId,
                invitedBy = command.actorUserId,
                invitedAt = clock.instant(),
                role = command.role,
            )
            val insertion = workspaceMemberRepository.insert(membership)
            if (insertion is WorkspaceMemberInsertion.AlreadyMember) {
                if (insertion.membership.role != command.role) throw WorkspaceMemberRoleConflictException()
                return@run insertion.membership
            }
            if (insertion == WorkspaceMemberInsertion.Inserted) {
                auditLogRepository.insert(
                    auditEventFactory.build(
                        actorId = command.actorUserId.asPrincipal(),
                        action = "workspace.member.add",
                        workspaceId = command.workspaceId,
                        organizationId = null,
                        resourceType = "user",
                        resourceId = command.targetUserId.value,
                        details = buildJsonObject { put("role", command.role.name) }.toString(),
                    ).withGovernanceAudit(command.audit),
                )
            }
            membership
        }
}

interface RemoveWorkspaceMemberUseCase {
    suspend fun remove(command: Command): Boolean

    data class Command(
        val actorUserId: UserId,
        val workspaceId: WorkspaceId,
        val targetUserId: UserId,
        val audit: GovernanceAudit?,
    )
}

class RemoveWorkspaceMemberService(
    private val workspaceMutationRunner: OwnerWorkspaceMutationRunner,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val auditLogRepository: AuditLogRepository,
    private val auditEventFactory: AuditEventFactory,
) : RemoveWorkspaceMemberUseCase {

    override suspend fun remove(command: RemoveWorkspaceMemberUseCase.Command): Boolean =
        workspaceMutationRunner.runAsOrganizationAdminOrOwner(command.actorUserId, command.workspaceId) { _ ->
            val removal = workspaceMemberRepository.delete(command.workspaceId, command.targetUserId)
            if (removal == WorkspaceMemberRemoval.Removed) {
                auditLogRepository.insert(
                    auditEventFactory.build(
                        actorId = command.actorUserId.asPrincipal(),
                        action = "workspace.member.remove",
                        workspaceId = command.workspaceId,
                        organizationId = null,
                        resourceType = "user",
                        resourceId = command.targetUserId.value,
                        details = "{}",
                    ).withGovernanceAudit(command.audit),
                )
            }
            removal == WorkspaceMemberRemoval.Removed
        }
}
