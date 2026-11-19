package cc.monomer.identity.domain.port

import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.WorkspaceMembership

interface WorkspaceMemberRepository {
    fun insert(membership: WorkspaceMembership): WorkspaceMemberInsertion
    fun delete(workspaceId: WorkspaceId, userId: UserId): WorkspaceMemberRemoval
}

sealed interface WorkspaceMemberInsertion {
    data object Inserted : WorkspaceMemberInsertion
    data class AlreadyMember(val membership: WorkspaceMembership) : WorkspaceMemberInsertion
}

sealed interface WorkspaceMemberRemoval {
    data object Removed : WorkspaceMemberRemoval
    data object NotFound : WorkspaceMemberRemoval
}
