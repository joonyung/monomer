package cc.monomer.catalog.application.ingest

import cc.monomer.catalog.api.CatalogProvisioningJob
import cc.monomer.catalog.api.CatalogProvisioningUseCase
import cc.monomer.catalog.api.CatalogRefreshState
import cc.monomer.catalog.api.CatalogIngestJobId
import cc.monomer.catalog.api.SaveCatalogSelectionCommand
import cc.monomer.catalog.api.StartCatalogIngestCommand
import cc.monomer.identity.api.WorkspaceId

class CatalogProvisioningService(private val ingest: CatalogIngestApplicationPort) : CatalogProvisioningUseCase {
    override suspend fun saveSelection(command: SaveCatalogSelectionCommand) = ingest.saveSelection(command)
    override suspend fun startIngest(command: StartCatalogIngestCommand) = ingest.startIngest(command)
    override suspend fun readJob(workspaceId: WorkspaceId, jobId: CatalogIngestJobId): CatalogProvisioningJob? =
        ingest.findJob(workspaceId, jobId)?.let {
            CatalogProvisioningJob(it.job.id, CatalogRefreshState.valueOf(it.job.state.name), it.errorCount)
        }
}
