package cc.monomer.identity.application.workspace

import cc.monomer.identity.application.AccessDeniedException
import cc.monomer.identity.application.ScopedResourceNotFoundException
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.OrganizationMember
import cc.monomer.identity.domain.model.User
import cc.monomer.identity.domain.model.WorkspaceMember
import cc.monomer.identity.domain.port.WorkspaceMemberCandidatesQueryPort
import cc.monomer.identity.domain.port.WorkspaceMemberCandidatesQueryResult
import cc.monomer.identity.domain.port.WorkspaceMembersQueryPort
import cc.monomer.identity.domain.port.WorkspaceMembersQueryResult
import cc.monomer.identity.domain.port.WorkspaceOwnerCandidatesQueryPort
import cc.monomer.identity.domain.port.WorkspaceOwnerCandidatesQueryResult

interface WorkspaceMembersUseCase {
    suspend fun list(command: Command): List<WorkspaceMember>
    data class Command(val actorUserId: UserId, val workspaceId: WorkspaceId)
}

class WorkspaceMembersService(
    private val workspaceMembersQueryPort: WorkspaceMembersQueryPort,
) : WorkspaceMembersUseCase {

    override suspend fun list(command: WorkspaceMembersUseCase.Command): List<WorkspaceMember> {
        return when (
            val result = workspaceMembersQueryPort.readWorkspaceMembers(
                command.actorUserId,
                command.workspaceId,
            )
        ) {
            is WorkspaceMembersQueryResult.Authorized -> result.members
            WorkspaceMembersQueryResult.Denied -> throw AccessDeniedException(
                "user ${command.actorUserId} cannot view members of workspace ${command.workspaceId}",
            )
        }
    }
}

interface WorkspaceOwnerCandidatesUseCase {
    suspend fun list(command: Command): List<User>
    data class Command(
        val actorUserId: UserId,
        val organizationId: OrganizationId,
        val workspaceId: WorkspaceId,
    )
}

class WorkspaceOwnerCandidatesService(
    private val workspaceOwnerCandidatesQueryPort: WorkspaceOwnerCandidatesQueryPort,
) : WorkspaceOwnerCandidatesUseCase {

    override suspend fun list(command: WorkspaceOwnerCandidatesUseCase.Command): List<User> {
        return when (
            val result = workspaceOwnerCandidatesQueryPort.readWorkspaceOwnerCandidates(
                command.actorUserId,
                command.organizationId,
                command.workspaceId,
            )
        ) {
            is WorkspaceOwnerCandidatesQueryResult.Authorized -> result.users
            is WorkspaceOwnerCandidatesQueryResult.WorkspaceNotFound ->
                throw ScopedResourceNotFoundException(SCOPED_WORKSPACE_NOT_FOUND_MESSAGE)
            WorkspaceOwnerCandidatesQueryResult.Denied -> throw AccessDeniedException(
                "user ${command.actorUserId} cannot list owner candidates for workspace ${command.workspaceId}",
            )
        }
    }

    private companion object {
        const val SCOPED_WORKSPACE_NOT_FOUND_MESSAGE = "workspace not found"
    }
}

data class WorkspaceMemberCandidates(
    val users: List<OrganizationMember>,
)

interface WorkspaceMemberCandidatesUseCase {
    suspend fun list(command: Command): WorkspaceMemberCandidates
    data class Command(val actorUserId: UserId, val workspaceId: WorkspaceId)
}

class WorkspaceMemberCandidatesService(
    private val workspaceMemberCandidatesQueryPort: WorkspaceMemberCandidatesQueryPort,
) : WorkspaceMemberCandidatesUseCase {

    override suspend fun list(command: WorkspaceMemberCandidatesUseCase.Command): WorkspaceMemberCandidates {
        return when (
            val result = workspaceMemberCandidatesQueryPort.readWorkspaceMemberCandidates(
                command.actorUserId,
                command.workspaceId,
            )
        ) {
            is WorkspaceMemberCandidatesQueryResult.Authorized -> WorkspaceMemberCandidates(
                users = result.users,
            )
            WorkspaceMemberCandidatesQueryResult.WorkspaceNotFound ->
                throw AccessDeniedException("workspace ${command.workspaceId} not found")
            WorkspaceMemberCandidatesQueryResult.Denied -> throw AccessDeniedException(
                "user ${command.actorUserId} must be a workspace owner in workspace ${command.workspaceId}",
            )
        }
    }
}
