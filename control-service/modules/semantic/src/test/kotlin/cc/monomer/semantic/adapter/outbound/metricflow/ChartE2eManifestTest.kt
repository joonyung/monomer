@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package cc.monomer.semantic.adapter.outbound.metricflow

import cc.monomer.catalog.api.CatalogPhysicalAddress
import cc.monomer.metricflow.application.engine.MetricFlowEngine
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.SemanticSqlDialect
import cc.monomer.semantic.application.SemanticMetricFlowManifest
import cc.monomer.semantic.UTC_REPORTING_TIME_ZONE
import cc.monomer.semantic.uncataloguedMetricFlowModel
import cc.monomer.semantic.adapter.outbound.postgres.SemanticPostgresJsonCodec
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

class ChartE2eManifestTest {
    @Test fun `live chart fixture satisfies pinned manifest validation`() {
        val codec = SemanticPostgresJsonCodec(ObjectMapper())
        val model = codec.parseModelDefinition(javaClass.getResource("/chart-e2e/model.json")!!.readText())
        val metric = codec.parseMetricDefinition(javaClass.getResource("/chart-e2e/metric.json")!!.readText())
        val manifest = SemanticMetricFlowManifest(
            connectorId = model.sourceRelations.single().connectorId,
            dialect = SemanticSqlDialect.POSTGRESQL,
            models = mapOf(model.name to uncataloguedMetricFlowModel(model, CatalogPhysicalAddress(null,"analytics","orders",null), SemanticSqlDialect.POSTGRESQL)),
            metrics = mapOf(MetricId(Uuid.parse("00000000-0000-7000-8000-000000000001")) to metric),
            timeSpines = emptyList(),
            reportingTimeZone = UTC_REPORTING_TIME_ZONE,
        )
        val result = MetricFlowEngine(MetricFlowManifestMapper.toEngineManifest(manifest), ControlMetricFlowSqlPlanRendererRegistry).validateManifest()
        assertFalse(result.hasBlockingIssues, result.toString())
    }
}
