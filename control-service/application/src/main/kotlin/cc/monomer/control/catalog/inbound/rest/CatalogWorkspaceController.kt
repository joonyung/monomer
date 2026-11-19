package cc.monomer.control.catalog.inbound.rest

import cc.monomer.catalog.api.CatalogConnectorSummary
import cc.monomer.catalog.application.CatalogConnectorSummaryUseCase
import cc.monomer.control.human.auth.ConnectorCatalogRequestAccess
import cc.monomer.control.search.CatalogTableSearchHit
import cc.monomer.control.search.CatalogTableSearchOutcome
import cc.monomer.control.search.CatalogTableSearchService
import cc.monomer.control.search.CatalogTableSearchUseCase
import cc.monomer.identity.api.WorkspaceId
import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

@RestController
@RequestMapping("/v2/workspaces/{workspaceId}/catalog")
class CatalogWorkspaceController(
    private val connectorSummaries: CatalogConnectorSummaryUseCase,
    private val tableSearch: CatalogTableSearchUseCase,
    private val requestAccess: ConnectorCatalogRequestAccess,
) {
    @GetMapping("/connector-summaries")
    suspend fun listConnectorSummaries(
        @PathVariable workspaceId: String,
        exchange: ServerWebExchange,
    ): ConnectorSummaryListResponse {
        val access = requestAccess.requireWorkspace(exchange, workspaceId)
        return ConnectorSummaryListResponse(
            connectorSummaries.connectorSummaries(WorkspaceId.parse(access.workspaceId)).map { it.toResponse() },
        )
    }

    @GetMapping("/table-search")
    suspend fun searchTables(
        @PathVariable workspaceId: String,
        @RequestParam query: String,
        @RequestParam limit: Int,
        exchange: ServerWebExchange,
    ): TableSearchResponse {
        val access = requestAccess.requireWorkspace(exchange, workspaceId)
        if (limit !in 1..CatalogTableSearchService.MAXIMUM_TABLE_SEARCH_LIMIT) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid table search limit")
        }
        return when (val outcome = tableSearch.search(WorkspaceId.parse(access.workspaceId), query, limit)) {
            is CatalogTableSearchOutcome.Found -> TableSearchResponse(outcome.tables.map { it.toResponse() })
            CatalogTableSearchOutcome.InvalidQuery ->
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Search for a table or column name using letters or numbers.")
        }
    }

    data class ConnectorSummaryListResponse(val connectors: List<ConnectorSummaryResponse>)

    data class ConnectorSummaryResponse(
        val connectorId: String,
        val selectedCatalogCount: Int,
        val selectedSchemaCount: Int,
        val tableCount: Int,
        val lastRefresh: LastRefreshResponse?,
        val lastSuccessfulRefreshAt: Instant?,
    )

    data class LastRefreshResponse(
        val jobId: String,
        val state: String,
        val catalogName: String?,
        val requestedAt: Instant,
        val finishedAt: Instant?,
        val errorCount: Int,
        val entryFailureCode: String?,
    )

    data class TableSearchResponse(val tables: List<TableSearchHitResponse>)

    data class TableSearchHitResponse(
        val connectorId: String,
        val connectorName: String,
        val connectorType: String,
        val catalogName: String?,
        val schemaName: String,
        val tableName: String,
        val relationKind: String,
        val matchedColumns: List<String>,
    )
}

private fun CatalogConnectorSummary.toResponse() = CatalogWorkspaceController.ConnectorSummaryResponse(
    connectorId = connectorId.toString(),
    selectedCatalogCount = selectedCatalogCount,
    selectedSchemaCount = selectedSchemaCount,
    tableCount = tableCount,
    lastRefresh = lastRefresh?.let { refresh ->
        CatalogWorkspaceController.LastRefreshResponse(
            jobId = refresh.jobId.toString(),
            state = refresh.state.name,
            catalogName = refresh.catalogName,
            requestedAt = refresh.requestedAt,
            finishedAt = refresh.finishedAt,
            errorCount = refresh.errorCount,
            entryFailureCode = refresh.entryFailureCode,
        )
    },
    lastSuccessfulRefreshAt = lastSuccessfulRefreshAt,
)

private fun CatalogTableSearchHit.toResponse() = CatalogWorkspaceController.TableSearchHitResponse(
    connectorId = connectorId.toString(),
    connectorName = connectorName,
    connectorType = connectorType.name,
    catalogName = catalogName,
    schemaName = schemaName,
    tableName = tableName,
    relationKind = relationKind.name,
    matchedColumns = matchedColumns,
)
