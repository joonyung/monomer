package cc.monomer.identity.api

interface WorkspacePublicPagesQuery {
    suspend fun pageReaderName(workspaceId: WorkspaceId): String?
    suspend fun isPublic(workspaceId: WorkspaceId): Boolean
}
