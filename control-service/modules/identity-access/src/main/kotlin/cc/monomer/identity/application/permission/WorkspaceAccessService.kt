package cc.monomer.identity.application.permission

import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.port.WorkspaceAccessQueryPort
import cc.monomer.identity.domain.port.WorkspaceAccessQueryResult

class WorkspaceAccessService(
    private val workspaceAccessQueryPort: WorkspaceAccessQueryPort,
) {
    suspend fun read(
        userId: UserId,
        workspaceId: WorkspaceId,
    ): WorkspaceAccessQueryResult =
        workspaceAccessQueryPort.readWorkspaceAccess(userId, workspaceId)
}
