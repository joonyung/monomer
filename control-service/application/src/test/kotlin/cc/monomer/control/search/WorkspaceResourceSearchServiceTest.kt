@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.search

import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardOwnerDashboard
import cc.monomer.dashboard.api.DashboardOwnerOutcome
import cc.monomer.dashboard.api.DashboardOwnerPort
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.search.api.SearchIndex
import cc.monomer.search.api.SearchMatch
import cc.monomer.search.api.SearchResourceKind
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.MetricView
import cc.monomer.semantic.api.SemanticCurrentReadUseCase
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticMetricType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class WorkspaceResourceSearchServiceTest {
    private val workspace = WorkspaceId(Uuid.generateV7())
    private val index = mockk<SearchIndex>()
    private val tableSearch = mockk<CatalogTableSearchUseCase>()
    private val semantic = mockk<SemanticCurrentReadUseCase>()
    private val dashboards = mockk<DashboardOwnerPort>()
    private val snapshots = object : ConsistentSnapshotTransactionRunner {
        override suspend fun <T> run(action: suspend () -> T): T = action()
    }
    private val service = WorkspaceResourceSearchService(index, snapshots, tableSearch, semantic, dashboards)

    @Test
    fun `each kind returns only resources its owner still has, up to the limit`() = runTest {
        val revenueOverview = DashboardId(Uuid.generateV7())
        val deletedDashboard = DashboardId(Uuid.generateV7())
        val metricId = MetricId.generate()
        coEvery { tableSearch.search(workspace, "reve", 2) } returns CatalogTableSearchOutcome.Found(emptyList())
        every {
            index.candidates(any(), "workspace/dashboards", setOf(SearchResourceKind.DASHBOARD), "reve", SearchMatch.TOKEN_PREFIX, 100, 0)
        } returns listOf("workspace/dashboards/$deletedDashboard", "workspace/dashboards/$revenueOverview")
        coEvery { dashboards.read(workspace, deletedDashboard) } returns DashboardOwnerOutcome.NotFound
        coEvery { dashboards.read(workspace, revenueOverview) } returns DashboardOwnerOutcome.Success(
            DashboardOwnerDashboard(
                revenueOverview, workspace,
                buildJsonObject { put("meta", buildJsonObject { put("title", JsonPrimitive("Revenue overview")) }) },
                Instant.EPOCH, Instant.EPOCH, null, null, null,
            ),
        )
        every {
            index.candidates(any(), "workspace/metrics", setOf(SearchResourceKind.METRIC), "reve", SearchMatch.TOKEN_PREFIX, 100, 0)
        } returns listOf("workspace/metrics/revenue", "workspace/metrics/revenue_net", "workspace/metrics/revenue_gross")
        coEvery { semantic.findMetricByName(workspace, SemanticElementName("revenue")) } returns metric(metricId, "revenue", "Revenue")
        coEvery { semantic.findMetricByName(workspace, SemanticElementName("revenue_net")) } returns metric(MetricId.generate(), "revenue_net", null)
        every {
            index.candidates(any(), "workspace/semantic-models", setOf(SearchResourceKind.SEMANTIC_MODEL), "reve", SearchMatch.TOKEN_PREFIX, 100, 0)
        } returns emptyList()

        val results = (service.search(workspace, "reve", 2) as WorkspaceResourceSearchOutcome.Found).results

        assertEquals(listOf(WorkspaceSearchDashboard(revenueOverview, "Revenue overview")), results.dashboards)
        assertEquals(listOf("revenue", "revenue_net"), results.metrics.map { it.name })
        assertEquals(metricId, results.metrics.first().metricId)
        assertEquals("Revenue", results.metrics.first().label)
        assertEquals(emptyList(), results.semanticModels)
        coVerify(exactly = 0) { semantic.findMetricByName(workspace, SemanticElementName("revenue_gross")) }
    }

    @Test
    fun `queries without letters or numbers never reach the index`() = runTest {
        assertEquals(WorkspaceResourceSearchOutcome.InvalidQuery, service.search(workspace, "!! :*", 5))
        assertEquals(WorkspaceResourceSearchOutcome.InvalidQuery, service.search(workspace, "a".repeat(201), 5))
        verify(exactly = 0) { index.candidates(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { tableSearch.search(workspace, any(), any()) }
    }

    private fun metric(id: MetricId, name: String, label: String?): MetricView {
        val definition = mockk<SemanticMetricDefinition>()
        every { definition.name } returns SemanticElementName(name)
        every { definition.label } returns label
        every { definition.type } returns SemanticMetricType.SIMPLE
        return MetricView(id, workspace, definition, Instant.EPOCH, Instant.EPOCH,
            emptyList(),
        )
    }
}
