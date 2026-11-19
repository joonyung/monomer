@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.semantic

import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.*
import cc.monomer.semantic.application.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class SemanticRenderDisplaySnapshotTest {
    @Test
    fun `output source MetricId selects display from the manifest rendered in the same call`() {
        val firstId = MetricId.generate()
        val secondId = MetricId.generate()
        val input = SemanticMetricInput(SemanticElementName("base"), null, null, null, null)
        val first = SemanticMetricDefinition.Ratio(3u, SemanticElementName("rate"), null, null, null, emptyList(), input, input.copy(metricName = SemanticElementName("total")), MetricDisplay.Percent(2))
        val second = first.copy(name = SemanticElementName("amount"), display = MetricDisplay.Currency(2, MetricDisplayCurrency.USD))
        val connector = ConnectorId.generate()
        val manifest = SemanticMetricFlowManifest(connector, SemanticSqlDialect.BIGQUERY, emptyMap(), mapOf(firstId to first, secondId to second), emptyList(), UTC_REPORTING_TIME_ZONE)
        val groupBy = SemanticGroupBy.MetricTime(SemanticTimeGranularity.DAY)
        val port = object : SemanticMetricFlowPort {
            override fun validate(manifest: SemanticMetricFlowManifest): SemanticMetricFlowValidationResult = error("already validated")
            override fun listGroupBys(manifest: SemanticMetricFlowManifest, metricIds: List<MetricId>): SemanticMetricFlowGroupByResult = error("not used")
            override fun render(snapshot: SemanticMetricFlowManifest, querySpec: SemanticQuerySpec, metricIds: List<MetricId>): SemanticMetricFlowRenderResult {
                assertSame(manifest, snapshot)
                return SemanticMetricFlowRenderResult.Rendered(SemanticSqlDialect.BIGQUERY, "SELECT 1", metricIds, listOf(
                    SemanticMetricFlowOutputColumn(SemanticMetricFlowOutputColumnSource.GroupBy(groupBy), SemanticResultColumnKey("metric_time.day"), "__monomer_result_0"),
                    SemanticMetricFlowOutputColumn(SemanticMetricFlowOutputColumnSource.Metric(secondId), SemanticResultColumnKey("metric.amount"), "__monomer_result_1"),
                    SemanticMetricFlowOutputColumn(SemanticMetricFlowOutputColumnSource.Metric(firstId), SemanticResultColumnKey("metric.rate"), "__monomer_result_2"),
                ))
            }
        }
        val request = SemanticRenderRequest(WorkspaceId.generate(), connector, SemanticQuerySpec(listOf(first.name, second.name), listOf(groupBy), null, emptyList(), emptyList(), 10))
        val rendered = assertIs<SemanticRenderResult.Rendered>(renderValidated(port, request, manifest, listOf(firstId, secondId)))
        assertEquals(listOf(null, second.display, first.display), rendered.render.outputColumns.map { it.display })
    }
}
