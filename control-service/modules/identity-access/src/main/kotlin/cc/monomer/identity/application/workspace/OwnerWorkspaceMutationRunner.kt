package cc.monomer.identity.application.workspace

import cc.monomer.identity.application.AccessDeniedException
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.port.AuthorizationAssignmentRepository
import cc.monomer.identity.domain.port.TransactionRunner

class OwnerWorkspaceMutationRunner(
    private val transactionRunner: TransactionRunner,
    private val authorizationAssignmentRepository: AuthorizationAssignmentRepository,
) {
    suspend fun <T> run(
        actorUserId: UserId,
        workspaceId: WorkspaceId,
        mutation: () -> T,
    ): T = transactionRunner.run {
        if (!authorizationAssignmentRepository.actorIsOwnerAfterLock(actorUserId, workspaceId)) {
            throw AccessDeniedException(
                "user $actorUserId must have the Owner role in workspace $workspaceId",
            )
        }
        mutation()
    }

    suspend fun <T> runAsOrganizationAdminOrOwner(
        actorUserId: UserId,
        workspaceId: WorkspaceId,
        mutation: (actorIsOrganizationAdmin: Boolean) -> T,
    ): T = transactionRunner.run {
        val actorIsOrganizationAdmin = authorizationAssignmentRepository.actorIsOrganizationAdminAfterLock(actorUserId, workspaceId)
        if (!actorIsOrganizationAdmin && !authorizationAssignmentRepository.actorIsOwnerAfterLock(actorUserId, workspaceId)) {
            throw AccessDeniedException("user $actorUserId must be an Organization Admin or Workspace Owner")
        }
        mutation(actorIsOrganizationAdmin)
    }
}
