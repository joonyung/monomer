@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.semantic

import cc.monomer.catalog.api.CatalogCurrentObjectSet
import cc.monomer.catalog.api.CatalogCurrentObjectsReadPort
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorWorkspaceReadUseCase
import cc.monomer.connector.api.CurrentConnectorRead
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.*
import cc.monomer.semantic.application.*
import cc.monomer.semantic.domain.CurrentMetric
import cc.monomer.semantic.domain.CurrentSemanticModel
import cc.monomer.semantic.domain.CurrentTimeSpineConfiguration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class SemanticComputationTransactionBoundaryTest {
    @Test
    fun `render captures every read in the snapshot and resolves the captured definitions after closure`(): Unit = runBlocking {
        val fixture = Fixture()
        val inputs = fixture.transactions.inSnapshotTransaction(Instant.MAX) {
            fixture.queryService.readRenderInputs(fixture.renderRequest)
        }
        assertEquals(0, fixture.engineCalls.get())
        fixture.removeCurrentDefinitions()

        val result = assertIs<SemanticRenderResult.Rendered>(fixture.queryService.renderFromInputs(inputs))
        assertEquals("select 1", result.render.sql)
        assertEquals(listOf(MetricDisplay.Number(2, null)), result.render.outputColumns.map { it.display })
        assertTrue(fixture.engineCalls.get() > 0)
        assertEquals(0, fixture.transactions.active.get())
    }

    @Test
    fun `group-by discovery performs no engine work until its snapshot closes`(): Unit = runBlocking {
        val fixture = Fixture()
        val inputs = fixture.transactions.inSnapshotTransaction(Instant.MAX) {
            fixture.queryService.readGroupByInputs(fixture.groupByRequest)
        }
        assertEquals(0, fixture.engineCalls.get())
        fixture.removeCurrentDefinitions()

        assertIs<SemanticGroupByOptionsResult.Listed>(fixture.queryService.listGroupBysFromInputs(inputs))
        assertTrue(fixture.engineCalls.get() > 0)
        assertEquals(0, fixture.transactions.active.get())
    }

    @Test
    fun `metric check validates renders and lists only the captured snapshot after closure`(): Unit = runBlocking {
        val fixture = Fixture()
        val inputs = fixture.transactions.inSnapshotTransaction(Instant.MAX) {
            fixture.checkService.readInputs(fixture.metricCheckRequest)
        }
        assertEquals(0, fixture.engineCalls.get())
        fixture.removeCurrentDefinitions()

        val result = assertIs<SemanticCheckResult.Checked>(fixture.checkService.checkFromInputs(inputs))
        assertEquals(listOf(fixture.connector), result.runsOn)
        assertEquals(emptyList(), result.problems)
        assertTrue(fixture.engineCalls.get() > 0)
        assertEquals(0, fixture.transactions.active.get())
    }

    @Test
    fun `model check validates the captured source and dependent closure after closure`(): Unit = runBlocking {
        val fixture = Fixture()
        val inputs = fixture.transactions.inSnapshotTransaction(Instant.MAX) {
            fixture.checkService.readInputs(SemanticCheckRequest(fixture.workspace, SemanticCheckDraft.Model(fixture.modelId, fixture.model)))
        }
        assertEquals(0, fixture.engineCalls.get())
        fixture.removeCurrentDefinitions()

        val result = assertIs<SemanticCheckResult.Checked>(fixture.checkService.checkFromInputs(inputs))
        assertEquals(listOf(fixture.connector), result.runsOn)
        assertEquals(emptyList(), result.problems)
        assertTrue(fixture.engineCalls.get() > 0)
        assertEquals(0, fixture.transactions.active.get())
    }

    @Test
    fun `request convenience methods also finish reads before engine work`(): Unit = runBlocking {
        val fixture = Fixture()
        assertIs<SemanticRenderResult.Rendered>(fixture.queryService.render(fixture.renderRequest))
        assertIs<SemanticGroupByOptionsResult.Listed>(fixture.queryService.listGroupBys(fixture.groupByRequest))
        assertIs<SemanticCheckResult.Checked>(fixture.checkService.check(fixture.metricCheckRequest))
        assertTrue(fixture.engineCalls.get() > 0)
        assertEquals(0, fixture.transactions.active.get())
    }

    @Test
    fun `render computation remains cancellable without a database snapshot`(): Unit = runBlocking {
        val fixture = Fixture(blockValidation = true)
        val inputs = fixture.queryService.readRenderInputs(fixture.renderRequest)
        val computation = async { fixture.queryService.renderFromInputs(inputs) }
        assertTrue(withContext(Dispatchers.IO) { fixture.validationEntered.await(5, TimeUnit.SECONDS) })

        computation.cancelAndJoin()

        assertTrue(fixture.validationInterrupted.get())
        assertTrue(computation.isCancelled)
        assertEquals(0, fixture.transactions.active.get())
    }

    @Test
    fun `definition check computation remains cancellable without a database snapshot`(): Unit = runBlocking {
        val fixture = Fixture(blockValidation = true)
        val inputs = fixture.checkService.readInputs(fixture.metricCheckRequest)
        val computation = async { fixture.checkService.checkFromInputs(inputs) }
        assertTrue(withContext(Dispatchers.IO) { fixture.validationEntered.await(5, TimeUnit.SECONDS) })

        computation.cancelAndJoin()

        assertTrue(fixture.validationInterrupted.get())
        assertTrue(computation.isCancelled)
        assertEquals(0, fixture.transactions.active.get())
    }

    private class Fixture(private val blockValidation: Boolean = false) {
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val transactions = RecordingTransactions()
        val repository = InMemorySemanticRepository()
        val modelId = SemanticModelId.generate()
        private val metricId = MetricId.generate()
        val engineCalls = AtomicInteger()
        val validationEntered = CountDownLatch(1)
        val validationInterrupted = AtomicBoolean()
        private val connectorReadsAllowed = AtomicBoolean(true)
        private var catalog: CatalogCurrentObjectSet? = catalogObjects(workspace, connector, NOW)
        private val requestThread = Thread.currentThread()
        val model = SemanticModelDefinition(
            3u, SemanticElementName("orders"), null, null,
            listOf(SemanticSourceRelation(connector, null, "analytics", "orders")),
            SemanticElementName("ordered_at"),
            listOf(SemanticEntityDefinition(SemanticElementName("order_id"), SemanticEntityKind.PRIMARY, "order_id", null, null)),
            listOf(SemanticDimensionDefinition(SemanticElementName("ordered_at"), SemanticDimensionKind.TIME, "ordered_at", SemanticTimeGranularity.DAY, null, null, false, SemanticTimeKind.LOCAL)),
            listOf(SemanticMeasureDefinition(SemanticElementName("revenue"), SemanticMeasureAggregation.SUM, "amount", null, null, null, null)),
        )
        private val metric = SemanticMetricDefinition.Simple(
            3u, SemanticElementName("revenue"), null, null, null, emptyList(),
            model.name, SemanticElementName("revenue"), MetricDisplay.Number(2, null),
        )
        val renderRequest = SemanticRenderRequest(
            workspace, connector, SemanticQuerySpec(listOf(metric.name), emptyList(), null, emptyList(), emptyList(), 10),
        )
        val groupByRequest = SemanticGroupByOptionsRequest(workspace, connector, listOf(metric.name))
        val metricCheckRequest = SemanticCheckRequest(workspace, SemanticCheckDraft.Metric(metricId, metric, connector))
        private val guardedRepository = object : SemanticRepository by repository {
            override fun findModels(workspaceId: WorkspaceId): List<CurrentSemanticModel> {
                assertTrue(transactions.active.get() > 0)
                return repository.findModels(workspaceId)
            }
            override fun findMetrics(workspaceId: WorkspaceId): List<CurrentMetric> {
                assertTrue(transactions.active.get() > 0)
                return repository.findMetrics(workspaceId)
            }
        }
        private val spines = object : SemanticTimeSpineRepository by repository {
            override fun findTimeSpineConfigurations(workspaceId: WorkspaceId): List<CurrentTimeSpineConfiguration> {
                assertTrue(transactions.active.get() > 0)
                return repository.findTimeSpineConfigurations(workspaceId)
            }
        }
        private val connectors = object : ConnectorWorkspaceReadUseCase by StaticConnectorRead(workspace, connector, NOW) {
            private val current = StaticConnectorRead(workspace, connector, NOW)
            override suspend fun findByWorkspaceAndId(workspaceId: WorkspaceId, connectorId: ConnectorId): CurrentConnectorRead? {
                assertTrue(connectorReadsAllowed.get())
                return current.findByWorkspaceAndId(workspaceId, connectorId)
            }
            override suspend fun listByWorkspace(workspaceId: WorkspaceId): List<CurrentConnectorRead> {
                assertTrue(connectorReadsAllowed.get())
                return current.listByWorkspace(workspaceId)
            }
        }
        private val catalogs = object : CatalogCurrentObjectsReadPort by StaticCatalogRead(workspace, connector, NOW) {
            override fun readCurrent(workspaceId: WorkspaceId, connectorId: ConnectorId): CatalogCurrentObjectSet? {
                assertTrue(transactions.active.get() > 0)
                return catalog
            }
        }
        private val engine = object : SemanticMetricFlowPort by AcceptingMetricFlowPort {
            private fun record() {
                assertEquals(0, transactions.active.get())
                assertTrue(Thread.currentThread() !== requestThread)
                engineCalls.incrementAndGet()
            }
            override fun validate(manifest: SemanticMetricFlowManifest): SemanticMetricFlowValidationResult {
                record()
                if (blockValidation) {
                    validationEntered.countDown()
                    try {
                        check(CountDownLatch(1).await(10, TimeUnit.SECONDS))
                    } catch (failure: InterruptedException) {
                        validationInterrupted.set(true)
                        throw failure
                    }
                }
                return AcceptingMetricFlowPort.validate(manifest)
            }
            override fun render(manifest: SemanticMetricFlowManifest, querySpec: SemanticQuerySpec, metricIds: List<MetricId>): SemanticMetricFlowRenderResult {
                record()
                return AcceptingMetricFlowPort.render(manifest, querySpec, metricIds)
            }
            override fun listGroupBys(manifest: SemanticMetricFlowManifest, metricIds: List<MetricId>): SemanticMetricFlowGroupByResult {
                record()
                return AcceptingMetricFlowPort.listGroupBys(manifest, metricIds)
            }
        }
        val queryService = SemanticQueryService(guardedRepository, spines, transactions, engine, connectors, catalogs, FixedReportingTimeZone(UTC_REPORTING_TIME_ZONE))
        val checkService = SemanticDefinitionCheckService(guardedRepository, spines, transactions, engine, connectors, catalogs, FixedReportingTimeZone(UTC_REPORTING_TIME_ZONE))

        init {
            repository.models[modelId] = CurrentSemanticModel(modelId, workspace, model, NOW, NOW)
            repository.metrics[metricId] = CurrentMetric(metricId, workspace, metric, NOW, NOW)
        }

        fun removeCurrentDefinitions() {
            connectorReadsAllowed.set(false)
            repository.models.clear()
            repository.metrics.clear()
            catalog = null
        }
    }

    private class RecordingTransactions : SemanticTransactionPort {
        val active = AtomicInteger()
        override suspend fun <T> inSnapshotTransaction(deadline: Instant, block: suspend () -> T): T {
            active.incrementAndGet()
            return try { block() } finally { active.decrementAndGet() }
        }
        override suspend fun <T> inSnapshotTransaction(block: () -> T): T {
            active.incrementAndGet()
            return try { block() } finally { active.decrementAndGet() }
        }
        override suspend fun <T> inMutationTransaction(block: () -> T): T = error("unused")
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-12T00:00:00Z")
    }
}
