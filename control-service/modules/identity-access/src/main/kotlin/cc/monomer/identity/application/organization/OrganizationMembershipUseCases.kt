package cc.monomer.identity.application.organization

import cc.monomer.identity.application.AccessDeniedException
import cc.monomer.identity.application.AdminAuthorization
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.PrincipalId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.OrganizationMembership
import cc.monomer.identity.domain.model.OrganizationRole
import cc.monomer.identity.domain.port.OrganizationRepository
import cc.monomer.identity.domain.port.OrganizationMemberRemoval
import cc.monomer.identity.domain.port.OrganizationMemberRoleTransition
import cc.monomer.identity.domain.port.AuditLogRepository
import cc.monomer.identity.domain.port.TransactionRunner
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface RemoveOrganizationMemberUseCase {
    suspend fun remove(command: Command): Result
    data class Command(
        val actorPrincipalId: PrincipalId,
        val organizationId: OrganizationId,
        val userId: UserId,
    )

    sealed interface Result {
        data object Removed : Result
        data object NotFound : Result
    }
}

class RemoveOrganizationMemberService(
    private val transactionRunner: TransactionRunner,
    private val adminAuthorization: AdminAuthorization,
    private val organizationRepository: OrganizationRepository,
    private val auditLogRepository: AuditLogRepository,
    private val auditEventFactory: AuditEventFactory,
) : RemoveOrganizationMemberUseCase {

    override suspend fun remove(command: RemoveOrganizationMemberUseCase.Command): RemoveOrganizationMemberUseCase.Result {
        return transactionRunner.run {
            val actorUserId = adminAuthorization.requireOrganizationAdminForWrite(command.actorPrincipalId, command.organizationId)
            requireOtherOrganizationMember(actorUserId, command.userId)
            when (organizationRepository.deleteMember(command.organizationId, command.userId)) {
                OrganizationMemberRemoval.NotFound -> RemoveOrganizationMemberUseCase.Result.NotFound
                OrganizationMemberRemoval.Removed -> {
                    auditLogRepository.insert(
                        auditEventFactory.build(
                            actorId = command.actorPrincipalId,
                            action = "organization.member.remove",
                            workspaceId = null,
                            organizationId = command.organizationId,
                            resourceId = command.userId.value,
                            resourceType = "user",
                            details = "{}",
                        ),
                    )
                    RemoveOrganizationMemberUseCase.Result.Removed
                }
            }
        }
    }
}

interface UpdateOrganizationMemberRoleUseCase {
    suspend fun update(command: Command): Result
    data class Command(
        val actorPrincipalId: PrincipalId,
        val organizationId: OrganizationId,
        val userId: UserId,
        val role: OrganizationRole,
    )

    sealed interface Result {
        data class Updated(val membership: OrganizationMembership) : Result
        data object NotFound : Result
        data object NoTransition : Result
    }
}

class UpdateOrganizationMemberRoleService(
    private val transactionRunner: TransactionRunner,
    private val adminAuthorization: AdminAuthorization,
    private val organizationRepository: OrganizationRepository,
    private val auditLogRepository: AuditLogRepository,
    private val auditEventFactory: AuditEventFactory,
) : UpdateOrganizationMemberRoleUseCase {

    override suspend fun update(
        command: UpdateOrganizationMemberRoleUseCase.Command,
    ): UpdateOrganizationMemberRoleUseCase.Result {
        return transactionRunner.run {
            val actorUserId = adminAuthorization.requireOrganizationAdminForWrite(command.actorPrincipalId, command.organizationId)
            requireOtherOrganizationMember(actorUserId, command.userId)
            when (val transition = organizationRepository.updateMemberRole(command.organizationId, command.userId, command.role)) {
                OrganizationMemberRoleTransition.NotFound -> UpdateOrganizationMemberRoleUseCase.Result.NotFound
                OrganizationMemberRoleTransition.NoTransition -> UpdateOrganizationMemberRoleUseCase.Result.NoTransition
                is OrganizationMemberRoleTransition.Updated -> {
                    auditLogRepository.insert(
                        auditEventFactory.build(
                            actorId = command.actorPrincipalId,
                            action = "organization.member.role.update",
                            workspaceId = null,
                            organizationId = command.organizationId,
                            resourceId = command.userId.value,
                            resourceType = "user",
                            details = buildJsonObject {
                                put("role", command.role.name)
                            }.toString(),
                        ),
                    )
                    UpdateOrganizationMemberRoleUseCase.Result.Updated(transition.membership)
                }
            }
        }
    }
}

private fun requireOtherOrganizationMember(actorUserId: UserId, targetUserId: UserId) {
    if (actorUserId == targetUserId) {
        throw AccessDeniedException("organization administrators cannot manage their own organization membership")
    }
}
