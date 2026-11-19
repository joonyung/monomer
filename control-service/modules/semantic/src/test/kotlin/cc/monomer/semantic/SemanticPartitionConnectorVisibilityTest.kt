@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.semantic

import cc.monomer.catalog.api.*
import cc.monomer.connector.api.*
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.*
import cc.monomer.semantic.application.SemanticCurrentReadService
import cc.monomer.semantic.application.SemanticTransactionPort
import cc.monomer.semantic.domain.CurrentMetric
import cc.monomer.semantic.domain.CurrentSemanticModel
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class SemanticPartitionConnectorVisibilityTest {
    private val workspace = WorkspaceId.generate()
    private val otherWorkspace = WorkspaceId.generate()
    private val collected = ConnectorId.generate()
    private val uncollected = ConnectorId.generate()
    private val foreign = ConnectorId.generate()
    private val deleted = ConnectorId.generate()
    private val now = Instant.parse("2026-10-08T00:00:00Z")
    private val repository = InMemorySemanticRepository()
    private var inSnapshot = false
    private val catalogReadsByConnector = mutableMapOf<ConnectorId, Int>()
    private val definition = SemanticModelDefinition(
        3u, SemanticElementName("orders"), null, null,
        listOf(collected, uncollected, foreign, deleted).map { SemanticSourceRelation(it, null, "analytics", "orders") },
        SemanticElementName("ordered_at"),
        listOf(SemanticEntityDefinition(SemanticElementName("order_id"), SemanticEntityKind.PRIMARY, "order_id", null, null)),
        listOf(SemanticDimensionDefinition(SemanticElementName("ordered_at"), SemanticDimensionKind.TIME, "ordered_at", SemanticTimeGranularity.DAY, null, null, false, null)),
        listOf(SemanticMeasureDefinition(SemanticElementName("amount"), SemanticMeasureAggregation.SUM, "amount", null, null, null, null)),
    )
    private val model = CurrentSemanticModel(SemanticModelId.generate(), workspace, definition, now, now)
    private val metric = CurrentMetric(MetricId.generate(), workspace,
        SemanticMetricDefinition.Simple(3u, SemanticElementName("revenue"), null, null, null, emptyList(), definition.name, SemanticElementName("amount"), display = null), now, now)
    private val service = SemanticCurrentReadService(
        repository, repository,
        object : SemanticTransactionPort {
            override suspend fun <T> inSnapshotTransaction(deadline: Instant, block: suspend () -> T): T = error("unused")
            override suspend fun <T> inSnapshotTransaction(block: () -> T): T {
                assertFalse(inSnapshot)
                inSnapshot = true
                return try { block() } finally { inSnapshot = false }
            }
            override suspend fun <T> inMutationTransaction(block: () -> T): T = error("unused")
        },
        object : ConnectorWorkspaceReadUseCase by StaticConnectorRead(workspace, collected, now) {
            override suspend fun findByWorkspaceAndIds(workspaceId: WorkspaceId, connectorIds: Set<ConnectorId>): List<CurrentConnectorRead> {
                assertFalse(inSnapshot, "Connector lookup must stay outside the synchronous Semantic snapshot")
                assertEquals(workspace, workspaceId)
                return listOf(
                    requireNotNull(StaticConnectorRead(workspace, collected, now).findByWorkspaceAndId(workspace, collected)),
                    requireNotNull(StaticConnectorRead(workspace, uncollected, now).findByWorkspaceAndId(workspace, uncollected)),
                    requireNotNull(StaticConnectorRead(otherWorkspace, foreign, now).findByWorkspaceAndId(otherWorkspace, foreign)),
                ).filter { it.connectorId in connectorIds }
            }
        },
        object : CatalogCurrentObjectsReadPort by StaticCatalogRead(workspace, collected, now) {
            override fun readCurrent(workspaceId: WorkspaceId, connectorId: ConnectorId): CatalogCurrentObjectSet? {
                assertTrue(inSnapshot, "Model definition and Catalog facts must be captured atomically")
                assertEquals(workspace, workspaceId)
                catalogReadsByConnector.merge(connectorId, 1, Int::plus)
                if (connectorId != collected) return null
                return catalogObjects(workspaceId, connectorId, now).let { catalog ->
                    catalog.copy(objects = catalog.objects.map { current ->
                        if (current.kind == CatalogObjectKind.RELATION && current.address.objectName == "orders")
                            current.copy(partitionKeys = listOf("ordered_at"), clusteringKeys = emptyList(), requiresPartitionFilter = true)
                        else current
                    })
                }
            }
        },
    )

    init {
        repository.models[model.id] = model
        repository.metrics[metric.id] = metric
    }

    @Test
    fun `all model and metric projections exclude foreign and deleted connectors while retaining uncollected current sources`() = runBlocking {
        val models = listOf(
            assertNotNull(service.findModel(workspace, model.id)),
            assertNotNull(service.findModelByName(workspace, definition.name)),
            service.findModels(workspace).single(),
            service.listModels(workspace, null, 10).items.single(),
            service.searchModels(workspace, "orders", null, 10).items.single(),
            assertNotNull(service.findLatestModel(workspace)),
        )
        models.forEach { view ->
            assertEquals(definition.sourceRelations, view.definition.sourceRelations)
            assertCurrentFacts(view.sourcePartitionInfo)
        }
        val metrics = listOf(
            assertNotNull(service.findMetric(workspace, metric.id)),
            assertNotNull(service.findMetricByName(workspace, metric.definition.name)),
            service.findMetrics(workspace).single(),
            service.listMetrics(workspace, null, 10).items.single(),
            service.searchMetrics(workspace, "revenue", null, 10).items.single(),
            assertNotNull(service.findLatestMetric(workspace)),
        )
        metrics.forEach { assertCurrentFacts(it.sourcePartitionInfo) }
    }

    @Test
    fun `list reads load definitions once and each connector Catalog once per call`() = runBlocking {
        val secondModel = CurrentSemanticModel(SemanticModelId.generate(), workspace, definition.copy(name = SemanticElementName("refunds")), now, now)
        val secondMetric = CurrentMetric(MetricId.generate(), workspace,
            SemanticMetricDefinition.Simple(3u, SemanticElementName("refund_total"), null, null, null, emptyList(), secondModel.definition.name, SemanticElementName("amount"), display = null), now, now)
        repository.models[secondModel.id] = secondModel
        repository.metrics[secondMetric.id] = secondMetric
        val sourceConnectors = definition.sourceRelations.map { it.connectorId }.toSet()
        val reads = listOf<suspend () -> List<List<SemanticSourcePartitionInfo>>>(
            { service.findModels(workspace).map { it.sourcePartitionInfo } },
            { service.listModels(workspace, null, 10).items.map { it.sourcePartitionInfo } },
            { service.searchModels(workspace, "", null, 10).items.map { it.sourcePartitionInfo } },
            { service.findMetrics(workspace).map { it.sourcePartitionInfo } },
            { service.listMetrics(workspace, null, 10).items.map { it.sourcePartitionInfo } },
            { service.searchMetrics(workspace, "", null, 10).items.map { it.sourcePartitionInfo } },
        )
        val expectedCollectionReads = listOf(0 to 1, 0 to 0, 0 to 0, 2 to 1, 1 to 1, 1 to 1)
        reads.forEachIndexed { index, read ->
            catalogReadsByConnector.clear()
            repository.metricCollectionReads = 0
            repository.modelCollectionReads = 0
            val facts = read()
            assertEquals(2, facts.size)
            facts.forEach(::assertCurrentFacts)
            assertEquals(sourceConnectors.associateWith { 1 }, catalogReadsByConnector, "read $index")
            assertEquals(expectedCollectionReads[index], repository.metricCollectionReads to repository.modelCollectionReads, "read $index")
        }
    }

    @Test
    fun `non suspending authority reads remain plain definitions`() {
        assertEquals(emptyList(), service.findModelsInCurrentTransaction(workspace).single().sourcePartitionInfo)
        assertEquals(emptyList(), service.findMetricsInCurrentTransaction(workspace).single().sourcePartitionInfo)
    }

    private fun assertCurrentFacts(facts: List<SemanticSourcePartitionInfo>) {
        assertEquals(listOf(collected, uncollected), facts.map { it.connectorId })
        assertEquals(true, facts[0].requiresPartitionFilter)
        assertEquals(listOf("ordered_at"), facts[0].partitionKeys)
        assertNull(facts[1].requiresPartitionFilter)
        assertEquals(emptyList(), facts[1].partitionKeys)
        assertEquals(emptyList(), facts[1].dimensions)
        assertEquals(false, facts[1].timeRangeApplies)
    }
}
