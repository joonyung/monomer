package cc.monomer.catalog.api

import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import java.time.Instant

enum class CatalogRefreshState { RUNNING, CANCEL_REQUESTED, COMPLETED, COMPLETED_WITH_ERRORS, CANCELLED }

data class CatalogConnectorLastRefresh(
    val jobId: CatalogIngestJobId,
    val state: CatalogRefreshState,
    val catalogName: String?,
    val requestedAt: Instant,
    val finishedAt: Instant?,
    val errorCount: Int,
    val entryFailureCode: String?,
) {
    init {
        require(errorCount >= 0)
        require((finishedAt == null) == (state == CatalogRefreshState.RUNNING || state == CatalogRefreshState.CANCEL_REQUESTED))
    }
}

data class CatalogConnectorSummary(
    val connectorId: ConnectorId,
    val selectedCatalogCount: Int,
    val selectedSchemaCount: Int,
    val tableCount: Int,
    val lastRefresh: CatalogConnectorLastRefresh?,
    val lastSuccessfulRefreshAt: Instant?,
) {
    init {
        require(selectedCatalogCount >= 0)
        require(selectedSchemaCount >= 0)
        require(tableCount >= 0)
    }
}

interface CatalogConnectorSummaryReadPort {
    /** Reads every connector with Catalog state in the caller's Catalog owner transaction. */
    fun readConnectorSummaries(workspaceId: WorkspaceId): List<CatalogConnectorSummary>
}
