package cc.monomer.control.search

import cc.monomer.control.human.auth.ConnectorCatalogRequestAccess
import cc.monomer.identity.api.WorkspaceId
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

@RestController
@RequestMapping("/v2/workspaces/{workspaceId}/search")
class WorkspaceSearchController(
    private val resourceSearch: WorkspaceResourceSearchUseCase,
    private val requestAccess: ConnectorCatalogRequestAccess,
) {
    @GetMapping
    suspend fun search(
        @PathVariable workspaceId: String,
        @RequestParam query: String,
        @RequestParam limit: Int,
        exchange: ServerWebExchange,
    ): WorkspaceSearchResponse {
        val access = requestAccess.requireWorkspace(exchange, workspaceId)
        if (limit !in 1..WorkspaceResourceSearchService.MAXIMUM_LIMIT_PER_KIND) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid search limit")
        }
        return when (val outcome = resourceSearch.search(WorkspaceId.parse(access.workspaceId), query, limit)) {
            is WorkspaceResourceSearchOutcome.Found -> outcome.results.toResponse()
            WorkspaceResourceSearchOutcome.InvalidQuery ->
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Search using letters or numbers.")
        }
    }

    data class WorkspaceSearchResponse(
        val dashboards: List<DashboardHit>,
        val metrics: List<MetricHit>,
        val semanticModels: List<SemanticModelHit>,
        val tables: List<TableHit>,
    )

    data class DashboardHit(val dashboardId: String, val title: String?)

    data class MetricHit(val metricId: String, val name: String, val label: String?, val type: String)

    data class SemanticModelHit(val semanticModelId: String, val name: String, val label: String?)

    data class TableHit(
        val connectorId: String,
        val connectorName: String,
        val connectorType: String,
        val catalogName: String?,
        val schemaName: String,
        val tableName: String,
        val relationKind: String,
    )
}

private fun WorkspaceSearchResults.toResponse() = WorkspaceSearchController.WorkspaceSearchResponse(
    dashboards = dashboards.map { WorkspaceSearchController.DashboardHit(it.dashboardId.toString(), it.title) },
    metrics = metrics.map { WorkspaceSearchController.MetricHit(it.metricId.toString(), it.name, it.label, it.type.name) },
    semanticModels = semanticModels.map { WorkspaceSearchController.SemanticModelHit(it.semanticModelId.toString(), it.name, it.label) },
    tables = tables.map {
        WorkspaceSearchController.TableHit(
            connectorId = it.connectorId.toString(),
            connectorName = it.connectorName,
            connectorType = it.connectorType.name,
            catalogName = it.catalogName,
            schemaName = it.schemaName,
            tableName = it.tableName,
            relationKind = it.relationKind.name,
        )
    },
)
