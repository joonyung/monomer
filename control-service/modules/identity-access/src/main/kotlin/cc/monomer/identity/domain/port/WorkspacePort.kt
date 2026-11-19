package cc.monomer.identity.domain.port

import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.AccessibleWorkspace
import cc.monomer.identity.domain.model.ReportingTimeZone
import cc.monomer.identity.domain.model.Workspace

interface WorkspaceQuery {
    suspend fun countMembers(workspaceId: WorkspaceId): Int
    suspend fun find(id: WorkspaceId): Workspace?
    suspend fun listAccessibleByUser(userId: UserId): List<AccessibleWorkspace>
}

interface WorkspaceRepository {
    fun insert(workspace: Workspace)
    fun updatePublicPages(workspaceId: WorkspaceId, publicPages: Boolean): Boolean
    fun updateReportingTimeZone(workspaceId: WorkspaceId, reportingTimeZone: ReportingTimeZone): Boolean
    fun updateDisplayName(workspaceId: WorkspaceId, displayName: String)
}
