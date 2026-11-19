@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.search

import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardOwnerOutcome
import cc.monomer.dashboard.api.DashboardOwnerPort
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.search.api.SearchIndex
import cc.monomer.search.api.SearchMatch
import cc.monomer.search.api.SearchResourceKind
import cc.monomer.search.api.SearchText
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.SemanticCurrentReadUseCase
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticMetricType
import cc.monomer.semantic.api.SemanticModelId
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class WorkspaceSearchDashboard(val dashboardId: DashboardId, val title: String?)

data class WorkspaceSearchMetric(val metricId: MetricId, val name: String, val label: String?, val type: SemanticMetricType)

data class WorkspaceSearchSemanticModel(val semanticModelId: SemanticModelId, val name: String, val label: String?)

data class WorkspaceSearchResults(
    val dashboards: List<WorkspaceSearchDashboard>,
    val metrics: List<WorkspaceSearchMetric>,
    val semanticModels: List<WorkspaceSearchSemanticModel>,
    val tables: List<CatalogTableSearchHit>,
)

sealed interface WorkspaceResourceSearchOutcome {
    data class Found(val results: WorkspaceSearchResults) : WorkspaceResourceSearchOutcome
    data object InvalidQuery : WorkspaceResourceSearchOutcome
}

interface WorkspaceResourceSearchUseCase {
    suspend fun search(workspaceId: WorkspaceId, query: String, limitPerKind: Int): WorkspaceResourceSearchOutcome
}

// Command palette search. Candidates come from the one metadata index; every hit is re-read from its owner in the
// same snapshot so deleted or renamed resources are skipped rather than shown from stale index text.
class WorkspaceResourceSearchService(
    private val searchIndex: SearchIndex,
    private val transactionRunner: ConsistentSnapshotTransactionRunner,
    private val tableSearch: CatalogTableSearchUseCase,
    private val semantic: SemanticCurrentReadUseCase,
    private val dashboards: DashboardOwnerPort,
) : WorkspaceResourceSearchUseCase {
    override suspend fun search(workspaceId: WorkspaceId, query: String, limitPerKind: Int): WorkspaceResourceSearchOutcome {
        require(limitPerKind in 1..MAXIMUM_LIMIT_PER_KIND)
        if (query.length > MAXIMUM_QUERY_LENGTH || !SearchText.hasTokens(query)) {
            return WorkspaceResourceSearchOutcome.InvalidQuery
        }
        return transactionRunner.run {
            val tables = when (val outcome = tableSearch.search(workspaceId, query, limitPerKind)) {
                is CatalogTableSearchOutcome.Found -> outcome.tables
                CatalogTableSearchOutcome.InvalidQuery -> return@run WorkspaceResourceSearchOutcome.InvalidQuery
            }
            WorkspaceResourceSearchOutcome.Found(
                WorkspaceSearchResults(
                    dashboards = current(workspaceId, DASHBOARDS_SCOPE, SearchResourceKind.DASHBOARD, query, limitPerKind) { path ->
                        readDashboard(workspaceId, path)
                    },
                    metrics = current(workspaceId, METRICS_SCOPE, SearchResourceKind.METRIC, query, limitPerKind) { path ->
                        semantic.findMetricByName(workspaceId, SemanticElementName(nameSegment(path)))?.let {
                            WorkspaceSearchMetric(it.metricId, it.definition.name.value, it.definition.label, it.definition.type)
                        }
                    },
                    semanticModels = current(workspaceId, SEMANTIC_MODELS_SCOPE, SearchResourceKind.SEMANTIC_MODEL, query, limitPerKind) { path ->
                        semantic.findModelByName(workspaceId, SemanticElementName(nameSegment(path)))?.let {
                            WorkspaceSearchSemanticModel(it.semanticModelId, it.definition.name.value, it.definition.label)
                        }
                    },
                    tables = tables,
                ),
            )
        }
    }

    private suspend fun <T> current(
        workspaceId: WorkspaceId,
        scope: String,
        kind: SearchResourceKind,
        query: String,
        limit: Int,
        read: suspend (String) -> T?,
    ): List<T> {
        val hits = mutableListOf<T>()
        var offset = 0
        while (hits.size < limit) {
            val candidates = searchIndex.candidates(
                workspaceId.value.toJavaUuid(), scope, setOf(kind), query, SearchMatch.TOKEN_PREFIX, CANDIDATE_PAGE_SIZE, offset,
            )
            for (path in candidates) {
                read(path)?.let(hits::add)
                if (hits.size == limit) break
            }
            if (candidates.size < CANDIDATE_PAGE_SIZE) break
            offset += CANDIDATE_PAGE_SIZE
        }
        return hits
    }

    private suspend fun readDashboard(workspaceId: WorkspaceId, path: String): WorkspaceSearchDashboard? {
        val dashboardId = DashboardId(Uuid.parse(nameSegment(path)))
        return when (val outcome = dashboards.read(workspaceId, dashboardId)) {
            is DashboardOwnerOutcome.Success -> WorkspaceSearchDashboard(
                dashboardId,
                outcome.value.definition["meta"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull,
            )
            DashboardOwnerOutcome.NotFound -> null
            else -> error("Dashboard search read failed")
        }
    }

    private fun nameSegment(path: String): String = AgentResourcePath.decodeNameSegment(path.substringAfterLast('/'))

    companion object {
        const val MAXIMUM_LIMIT_PER_KIND: Int = 10
        const val MAXIMUM_QUERY_LENGTH: Int = 200
        private const val CANDIDATE_PAGE_SIZE: Int = 100
        private const val DASHBOARDS_SCOPE: String = "workspace/dashboards"
        private const val METRICS_SCOPE: String = "workspace/metrics"
        private const val SEMANTIC_MODELS_SCOPE: String = "workspace/semantic-models"
    }
}
