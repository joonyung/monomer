package cc.monomer.catalog.application

import cc.monomer.catalog.api.CatalogConnectorSummary
import cc.monomer.catalog.api.CatalogConnectorSummaryReadPort
import cc.monomer.catalog.application.CatalogTransactionPort
import cc.monomer.identity.api.WorkspaceId

interface CatalogConnectorSummaryUseCase {
    suspend fun connectorSummaries(workspaceId: WorkspaceId): List<CatalogConnectorSummary>
}

class CatalogConnectorSummaryService(
    private val summaryReadPort: CatalogConnectorSummaryReadPort,
    private val transactionPort: CatalogTransactionPort,
) : CatalogConnectorSummaryUseCase {
    override suspend fun connectorSummaries(workspaceId: WorkspaceId): List<CatalogConnectorSummary> =
        transactionPort.inReadOnlyTransaction { summaryReadPort.readConnectorSummaries(workspaceId) }
}
