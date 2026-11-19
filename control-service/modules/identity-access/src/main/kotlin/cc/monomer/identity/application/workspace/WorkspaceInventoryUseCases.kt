package cc.monomer.identity.application.workspace

import cc.monomer.identity.application.OrganizationAdminRequiredException
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.AccessibleWorkspace
import cc.monomer.identity.domain.model.Workspace
import cc.monomer.identity.domain.port.ManagedWorkspacesQueryPort
import cc.monomer.identity.domain.port.ManagedWorkspacesQueryResult
import cc.monomer.identity.domain.port.WorkspaceQuery

interface AccessibleWorkspacesUseCase {
    suspend fun list(command: Command): List<AccessibleWorkspace>

    data class Command(val actorUserId: UserId)
}

class AccessibleWorkspacesService(
    private val workspaceQuery: WorkspaceQuery,
) : AccessibleWorkspacesUseCase {
    override suspend fun list(command: AccessibleWorkspacesUseCase.Command): List<AccessibleWorkspace> =
        workspaceQuery.listAccessibleByUser(command.actorUserId)
}

interface ManagedWorkspacesUseCase {
    suspend fun list(command: Command): List<Workspace>

    data class Command(
        val actorUserId: UserId,
        val organizationId: OrganizationId,
    )
}

class ManagedWorkspacesService(
    private val managedWorkspacesQueryPort: ManagedWorkspacesQueryPort,
) : ManagedWorkspacesUseCase {
    override suspend fun list(command: ManagedWorkspacesUseCase.Command): List<Workspace> =
        when (
            val result = managedWorkspacesQueryPort.readManagedWorkspaces(
                actorUserId = command.actorUserId,
                organizationId = command.organizationId,
            )
        ) {
            is ManagedWorkspacesQueryResult.Authorized -> result.workspaces
            ManagedWorkspacesQueryResult.Denied -> throw OrganizationAdminRequiredException(
                "principal ${command.actorUserId.asPrincipal()} must be ADMIN of organization ${command.organizationId}",
            )
        }
}
