package cc.monomer.identity.application.workspace

import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.WorkspaceMembership
import cc.monomer.identity.domain.port.WorkspaceMemberRepository
import java.time.Instant

class WorkspaceAuthorizationBootstrap(private val workspaceMemberRepository: WorkspaceMemberRepository) {
    fun createInitialBundle(workspaceId: WorkspaceId, initialOwnerUserId: UserId, assignedByUserId: UserId, assignedAt: Instant) {
        workspaceMemberRepository.insert(WorkspaceMembership(workspaceId, initialOwnerUserId, assignedByUserId, assignedAt, WorkspaceRole.OWNER))
    }
}
