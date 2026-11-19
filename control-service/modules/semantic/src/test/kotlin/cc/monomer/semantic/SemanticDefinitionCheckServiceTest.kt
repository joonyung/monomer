@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.semantic

import cc.monomer.catalog.api.CatalogCurrentObjectSet
import cc.monomer.catalog.api.CatalogCurrentObjectsReadPort
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.adapter.outbound.metricflow.MetricFlowSemanticRenderer
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.SemanticCheckDraft
import cc.monomer.semantic.api.SemanticCheckProblemCode
import cc.monomer.semantic.api.SemanticDefinitionReference
import cc.monomer.semantic.api.SemanticCheckRequest
import cc.monomer.semantic.api.SemanticCheckResult
import cc.monomer.semantic.api.SemanticDimensionDefinition
import cc.monomer.semantic.api.SemanticDimensionKind
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticEntityDefinition
import cc.monomer.semantic.api.SemanticEntityKind
import cc.monomer.semantic.api.SemanticMeasureAggregation
import cc.monomer.semantic.api.SemanticMeasureDefinition
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticMetricInput
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticModelId
import cc.monomer.semantic.api.SemanticSourceRelation
import cc.monomer.semantic.api.SemanticSqlDialect
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.api.SemanticTimeKind
import cc.monomer.semantic.application.SemanticDefinitionCheckService
import cc.monomer.semantic.application.SemanticMetricFlowPort
import cc.monomer.semantic.domain.CurrentMetric
import cc.monomer.semantic.domain.CurrentSemanticModel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SemanticDefinitionCheckServiceTest {
    private val workspace = WorkspaceId.generate()
    private val connector = ConnectorId.generate()
    private val repository = InMemorySemanticRepository()

    @Test
    fun `a resolvable metric draft runs on its connector with a rendered preview and nothing is saved`(): Unit = runBlocking {
        saveModel(model())
        val result = checked(metricDraft(simple("revenue", "orders", "revenue")))

        assertEquals(emptyList(), result.problems)
        assertEquals(listOf(connector), result.runsOn)
        val preview = assertNotNull(result.preview)
        assertEquals(connector, preview.connectorId)
        assertEquals(SemanticSqlDialect.POSTGRESQL, preview.dialect)
        assertTrue(preview.sql.contains("analytics.orders"), preview.sql)
        assertTrue(preview.sql.contains("ordered_at"), preview.sql)
        assertTrue(preview.groupBys.isNotEmpty())
        assertTrue(repository.metrics.isEmpty())
        assertEquals(0, repository.metricInsertCount + repository.metricUpdateCount)
    }

    @Test
    fun `missing references point at the draft field that names them`(): Unit = runBlocking {
        saveModel(model())
        val missingModel = checked(metricDraft(simple("revenue", "billing", "revenue")))
        assertEquals(SemanticCheckProblemCode.METRIC_MODEL_NOT_FOUND, missingModel.problems.single().code)
        assertEquals("semanticModelName", missingModel.problems.single().field)
        assertEquals(emptyList(), missingModel.runsOn)

        val missingInput = checked(metricDraft(derived("net", listOf("revenue", "refunds"))))
        assertEquals(SemanticCheckProblemCode.METRIC_DEPENDENCY_NOT_FOUND, missingInput.problems.single().code)
        assertEquals("inputMetrics[0].metricName", missingInput.problems.single().field)
    }

    @Test
    fun `a missing measure surfaces the engine finding`(): Unit = runBlocking {
        saveModel(model())
        val result = checked(metricDraft(simple("invoices", "orders", "invoice_cnt")))

        assertEquals(setOf(SemanticCheckProblemCode.METRICFLOW_MANIFEST_INVALID), result.problems.map { it.code }.toSet())
        assertTrue(result.problems.first().detail.orEmpty().contains("invoice_cnt"), result.toString())
        assertEquals(emptyList(), result.runsOn)
        assertNull(result.preview)
    }

    @Test
    fun `the draft replaces the saved definition of the same name`(): Unit = runBlocking {
        saveModel(model())
        val savedId = MetricId.generate()
        repository.metrics[savedId] = CurrentMetric(savedId, workspace, simple("revenue", "orders", "missing"), NOW, NOW)
        saveMetric(derived("doubled", listOf("revenue")))

        val edited = checked(SemanticCheckDraft.Metric(savedId, simple("revenue", "orders", "revenue"), null))
        assertEquals(emptyList(), edited.problems)

        val dependent = checked(metricDraft(derived("doubled", listOf("revenue"))))
        assertEquals(listOf(SemanticCheckProblemCode.NAME_CONFLICT), dependent.problems.map { it.code }.filter { it == SemanticCheckProblemCode.NAME_CONFLICT })
        assertEquals("name", dependent.problems.first { it.code == SemanticCheckProblemCode.NAME_CONFLICT }.field)

        val renamed = checked(SemanticCheckDraft.Metric(savedId, simple("income", "orders", "revenue"), null))
        assertEquals(SemanticCheckProblemCode.NAME_IMMUTABLE, renamed.problems.single().code)
    }

    @Test
    fun `a cumulative metric without a compatible time spine reports it`(): Unit = runBlocking {
        saveModel(model())
        saveMetric(simple("revenue", "orders", "revenue"))
        val cumulative = SemanticMetricDefinition.Cumulative(
            schemaVersion = 3u,
            name = SemanticElementName("running_revenue"),
            label = null,
            description = null,
            timeGranularity = null,
            filters = emptyList(),
            inputMetric = input("revenue"),
            window = null,
            grainToDate = null,
            periodAggregation = null,
            display = null,
        )
        val result = checked(metricDraft(cumulative.copy(window = cc.monomer.semantic.api.SemanticMetricWindow.parse("7 days"))))

        val problem = result.problems.single()
        assertEquals(SemanticCheckProblemCode.TIME_SPINE_UNAVAILABLE, problem.code, result.toString())
        assertEquals(connector, problem.connectorId)
    }

    @Test
    fun `a model draft runs on each connector whose current Catalog has its source table`(): Unit = runBlocking {
        val valid = checked(SemanticCheckDraft.Model(null, model()))
        assertEquals(emptyList(), valid.problems)
        assertEquals(listOf(connector), valid.runsOn)
        assertNull(valid.preview)

        val unknownConnector = ConnectorId.generate()
        val missingTable = model().copy(
            sourceRelations = listOf(
                SemanticSourceRelation(connector, null, "analytics", "invoices"),
                SemanticSourceRelation(unknownConnector, null, "analytics", "orders"),
            ),
        )
        val sources = checked(SemanticCheckDraft.Model(null, missingTable))
        assertEquals(
            listOf(
                SemanticCheckProblemCode.CATALOG_RELATION_NOT_FOUND to "sourceRelations[0]",
                SemanticCheckProblemCode.CONNECTOR_NOT_FOUND to "sourceRelations[1]",
            ),
            sources.problems.map { it.code to it.field },
        )

    }

    @Test
    fun `a model draft is validated with every model the connector's queries project`(): Unit = runBlocking {
        saveModel(model())
        val shipments = model().copy(
            name = SemanticElementName("shipments"),
            sourceRelations = listOf(SemanticSourceRelation(connector, null, "analytics", "calendar")),
            aggregationTimeDimension = SemanticElementName("status"),
            entities = listOf(SemanticEntityDefinition(SemanticElementName("shipment_id"), SemanticEntityKind.PRIMARY, "shipment_id", null, null)),
            dimensions = listOf(
                SemanticDimensionDefinition(SemanticElementName("status"), SemanticDimensionKind.TIME, "shipped_at", SemanticTimeGranularity.DAY, null, null,
                    false,
                    SemanticTimeKind.LOCAL,
                ),
            ),
            measures = emptyList(),
        )

        val conflict = checked(SemanticCheckDraft.Model(null, shipments))

        assertEquals(emptyList(), conflict.runsOn, conflict.toString())
        val problem = conflict.problems.first()
        assertEquals(SemanticCheckProblemCode.METRICFLOW_MANIFEST_INVALID, problem.code)
        assertTrue(problem.detail.orEmpty().contains("status"), problem.toString())
        assertTrue(conflict.problems.none { it.detail.orEmpty().contains("No metrics present") }, conflict.toString())
        assertTrue(conflict.problems.none { it.definition == SemanticDefinitionReference.SemanticModel(SemanticElementName("shipments")) }, conflict.toString())
        assertEquals(
            setOf(SemanticDefinitionReference.SemanticModel(SemanticElementName("orders"))),
            conflict.problems.mapNotNull { it.definition }.toSet(),
            conflict.toString(),
        )

        val compatible = checked(SemanticCheckDraft.Model(null, shipments.copy(
            aggregationTimeDimension = null,
            dimensions = listOf(SemanticDimensionDefinition(SemanticElementName("status"), SemanticDimensionKind.CATEGORICAL, "status", null, null, null,
                false,
                null,
            )),
        )))
        assertEquals(emptyList(), compatible.problems)
        assertEquals(listOf(connector), compatible.runsOn)
    }

    @Test
    fun `a model draft is validated with the saved metrics that use it`(): Unit = runBlocking {
        val savedId = SemanticModelId.generate()
        repository.models[savedId] = CurrentSemanticModel(savedId, workspace, model(), NOW, NOW)
        saveMetric(simple("revenue", "orders", "revenue"))

        assertEquals(emptyList(), checked(SemanticCheckDraft.Model(savedId, model())).problems)

        val withoutMeasure = checked(SemanticCheckDraft.Model(savedId, model().copy(measures = emptyList())))
        assertTrue(withoutMeasure.problems.any { it.detail.orEmpty().contains("revenue") }, withoutMeasure.toString())
        assertTrue(repository.models.getValue(savedId).definition.measures.isNotEmpty())
    }

    @Test
    fun `unknown stable ids and connectors are not found`(): Unit = runBlocking {
        saveModel(model())
        assertIs<SemanticCheckResult.NotFoundOrOutsideWorkspace>(
            service().check(SemanticCheckRequest(workspace, SemanticCheckDraft.Metric(MetricId.generate(), simple("revenue", "orders", "revenue"), null))),
        )
        assertIs<SemanticCheckResult.NotFoundOrOutsideWorkspace>(
            service().check(SemanticCheckRequest(workspace, SemanticCheckDraft.Metric(null, simple("revenue", "orders", "revenue"), ConnectorId.generate()))),
        )
        assertIs<SemanticCheckResult.NotFoundOrOutsideWorkspace>(
            service().check(SemanticCheckRequest(workspace, SemanticCheckDraft.Model(SemanticModelId.generate(), model()))),
        )
    }

    @Test
    fun `partition condition keeps runsOn and reports a missing modeled key`() = runBlocking {
        val catalog = catalogObjects(workspace, connector, NOW).let { set -> set.copy(objects = set.objects.map { current ->
            if (current.kind == cc.monomer.catalog.api.CatalogObjectKind.RELATION && current.address.objectName == "orders")
                current.copy(partitionKeys = listOf("snapshot_day"), clusteringKeys = emptyList(), requiresPartitionFilter = true)
            else current
        }) }
        val service = SemanticDefinitionCheckService(
            repository, repository, ImmediateTransactionPort, MetricFlowSemanticRenderer.production(),
            StaticConnectorRead(workspace, connector, NOW),
            object : CatalogCurrentObjectsReadPort by StaticCatalogRead(workspace, connector, NOW) {
                override fun readCurrent(workspaceId: WorkspaceId, connectorId: ConnectorId) = catalog
            },
            FixedReportingTimeZone(UTC_REPORTING_TIME_ZONE),
        )
        val result = assertIs<SemanticCheckResult.Checked>(service.check(SemanticCheckRequest(workspace, SemanticCheckDraft.Model(null, model()))))
        assertEquals(listOf(connector), result.runsOn)
        assertEquals(listOf(SemanticCheckProblemCode.PARTITION_COLUMN_NOT_MODELED), result.problems.map { it.code })
        val filter = result.partitionFilters.single()
        assertEquals(listOf("snapshot_day"), filter.partitionKeys)
        assertEquals(emptyList(), filter.dimensions)
        assertEquals(false, filter.timeRangeApplies)
        assertNull(filter.relation.catalog)
    }

    @Test
    fun `source partition facts distinguish a modeled key from string literals and comments`() {
        val catalog = catalogObjects(workspace, connector, NOW).let { set -> set.copy(objects = set.objects.map { current ->
            if (current.kind == cc.monomer.catalog.api.CatalogObjectKind.RELATION && current.address.objectName == "orders")
                current.copy(partitionKeys = listOf("ordered_at"), requiresPartitionFilter = true)
            else current
        }) }
        val definition = model().copy(dimensions = listOf(
            model().dimensions.first().copy(expression = "DATE(`ORDERED_AT`)"),
            model().dimensions.last().copy(expression = "'ordered_at' /* ordered_at */ -- ordered_at\nstatus"),
        ))
        val info = assertNotNull(cc.monomer.semantic.application.sourcePartitionInfo(definition, connector, catalog))
        assertEquals(listOf(SemanticElementName("ordered_at")), info.dimensions)
        assertEquals(true, info.timeRangeApplies)
        assertNull(info.relation.catalog)
        val ingestion = catalog.copy(objects = catalog.objects.map { current ->
            if (current.kind == cc.monomer.catalog.api.CatalogObjectKind.RELATION) current.copy(partitionKeys = listOf("_PARTITIONTIME")) else current
        })
        val pseudo = definition.copy(dimensions = listOf(definition.dimensions.first().copy(expression = "DATE(_PARTITIONDATE)")))
        assertEquals(pseudo.dimensions.map { it.name }, cc.monomer.semantic.application.sourcePartitionInfo(pseudo, connector, ingestion)?.dimensions)
    }

    @Test
    fun `partition key date is not inferred from the DATE function name`() {
        val catalog = catalogObjects(workspace, connector, NOW).let { set -> set.copy(objects = set.objects.map { current ->
            if (current.kind == cc.monomer.catalog.api.CatalogObjectKind.RELATION && current.address.objectName == "orders")
                current.copy(partitionKeys = listOf("date"), requiresPartitionFilter = true)
            else current
        }) }
        val dimension = model().dimensions.first()
        listOf("DATE(created_at)", "DATE  (created_at)").forEach { expression ->
            val definition = model().copy(dimensions = listOf(dimension.copy(expression = expression)))
            val info = assertNotNull(cc.monomer.semantic.application.sourcePartitionInfo(definition, connector, catalog))
            assertEquals(emptyList(), info.dimensions)
            assertEquals(false, info.timeRangeApplies)
        }
        val definition = model().copy(dimensions = listOf(dimension.copy(expression = "DATE(`date`)")))
        val info = assertNotNull(cc.monomer.semantic.application.sourcePartitionInfo(definition, connector, catalog))
        assertEquals(listOf(dimension.name), info.dimensions)
        assertEquals(true, info.timeRangeApplies)
    }

    private suspend fun checked(draft: SemanticCheckDraft): SemanticCheckResult.Checked =
        assertIs<SemanticCheckResult.Checked>(service().check(SemanticCheckRequest(workspace, draft)))

    private fun service(metricFlowPort: SemanticMetricFlowPort = MetricFlowSemanticRenderer.production()) =
        SemanticDefinitionCheckService(
            repository = repository,
            timeSpineRepository = repository,
            transactionPort = ImmediateTransactionPort,
            metricFlowPort = metricFlowPort,
            connectorReadUseCase = StaticConnectorRead(workspace, connector, NOW),
            catalogReadPort = object : CatalogCurrentObjectsReadPort by StaticCatalogRead(workspace, connector, NOW) {
                override fun readCurrent(workspaceId: WorkspaceId, connectorId: ConnectorId): CatalogCurrentObjectSet? =
                    catalogObjects(workspaceId, connectorId, NOW).takeIf { workspaceId == workspace && connectorId == connector }
            },
            reportingTimeZoneQuery = FixedReportingTimeZone(UTC_REPORTING_TIME_ZONE),
        )

    private fun metricDraft(definition: SemanticMetricDefinition) = SemanticCheckDraft.Metric(null, definition, null)

    private fun saveModel(definition: SemanticModelDefinition) {
        val id = SemanticModelId.generate()
        repository.models[id] = CurrentSemanticModel(id, workspace, definition, NOW, NOW)
    }

    private fun saveMetric(definition: SemanticMetricDefinition) {
        val id = MetricId.generate()
        repository.metrics[id] = CurrentMetric(id, workspace, definition, NOW, NOW)
    }

    private fun model(): SemanticModelDefinition = SemanticModelDefinition(
        schemaVersion = 3u,
        name = SemanticElementName("orders"),
        label = null,
        description = null,
        sourceRelations = listOf(SemanticSourceRelation(connector, null, "analytics", "orders")),
        aggregationTimeDimension = SemanticElementName("ordered_at"),
        entities = listOf(SemanticEntityDefinition(SemanticElementName("order_id"), SemanticEntityKind.PRIMARY, "order_id", null, null)),
        dimensions = listOf(
            SemanticDimensionDefinition(SemanticElementName("ordered_at"), SemanticDimensionKind.TIME, "ordered_at", SemanticTimeGranularity.DAY, null, null,
                false,
                SemanticTimeKind.LOCAL,
            ),
            SemanticDimensionDefinition(SemanticElementName("status"), SemanticDimensionKind.CATEGORICAL, "status", null, null, null,
                false,
                null,
            ),
        ),
        measures = listOf(
            SemanticMeasureDefinition(SemanticElementName("revenue"), SemanticMeasureAggregation.SUM, "amount", null, null, null, null),
        ),
    )

    private fun simple(name: String, model: String, measure: String) = SemanticMetricDefinition.Simple(
        schemaVersion = 3u,
        name = SemanticElementName(name),
        label = null,
        description = null,
        timeGranularity = null,
        filters = emptyList(),
        semanticModelName = SemanticElementName(model),
        measure = SemanticElementName(measure),
        display = null,
    )

    private fun derived(name: String, inputs: List<String>) = SemanticMetricDefinition.Derived(
        schemaVersion = 3u,
        name = SemanticElementName(name),
        label = null,
        description = null,
        timeGranularity = null,
        filters = emptyList(),
        expression = inputs.joinToString(" + "),
        inputMetrics = inputs.map(::input),
        display = null,
    )

    private fun input(name: String) = SemanticMetricInput(SemanticElementName(name), null, null, null, null)

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-01T00:00:00Z")
    }
}
