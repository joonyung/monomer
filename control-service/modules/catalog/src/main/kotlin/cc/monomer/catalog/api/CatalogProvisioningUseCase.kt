package cc.monomer.catalog.api

import cc.monomer.identity.api.WorkspaceId

interface CatalogProvisioningUseCase {
    suspend fun saveSelection(command: SaveCatalogSelectionCommand): CatalogCommandResult<CatalogSelectionView>
    suspend fun startIngest(command: StartCatalogIngestCommand): CatalogCommandResult<CatalogIngestStarted>
    suspend fun readJob(workspaceId: WorkspaceId, jobId: CatalogIngestJobId): CatalogProvisioningJob?
}

data class CatalogProvisioningJob(
    val id: CatalogIngestJobId,
    val state: CatalogRefreshState,
    val errorCount: Int,
)
