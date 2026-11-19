package cc.monomer.identity.application.grant

import cc.monomer.identity.application.AccessDeniedException
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.AuthorizationRevision
import cc.monomer.identity.domain.port.WorkspaceAuthorizationAssignmentsQueryPort
import cc.monomer.identity.domain.port.WorkspaceAuthorizationAssignmentsQueryResult

interface WorkspaceAuthorizationAssignmentsUseCase {
    suspend fun read(command: Command): WorkspaceAuthorizationAssignments

    data class Command(
        val actorUserId: UserId,
        val workspaceId: WorkspaceId,
    )
}

data class WorkspaceAuthorizationAssignments(
    val authorizationRevision: AuthorizationRevision,
    val members: List<cc.monomer.identity.domain.model.WorkspaceMember>,
)

class WorkspaceAuthorizationAssignmentsService(
    private val workspaceAuthorizationAssignmentsQueryPort: WorkspaceAuthorizationAssignmentsQueryPort,
) : WorkspaceAuthorizationAssignmentsUseCase {

    override suspend fun read(
        command: WorkspaceAuthorizationAssignmentsUseCase.Command,
    ): WorkspaceAuthorizationAssignments {
        val result = workspaceAuthorizationAssignmentsQueryPort.readWorkspaceAuthorizationAssignments(
            actorUserId = command.actorUserId,
            workspaceId = command.workspaceId,
        )
        if (result is WorkspaceAuthorizationAssignmentsQueryResult.Denied) {
            throw AccessDeniedException("workspace owner required")
        }
        result as WorkspaceAuthorizationAssignmentsQueryResult.Authorized
        return WorkspaceAuthorizationAssignments(
            authorizationRevision = result.authorization.authorizationRevision,
            members = result.members,
        )
    }
}
