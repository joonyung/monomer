package cc.monomer.semantic

import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorProductType
import cc.monomer.connector.api.ConnectorSettingsProperties
import cc.monomer.connector.api.ConnectorWorkspaceReadUseCase
import cc.monomer.connector.api.CurrentConnectorRead
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.*
import cc.monomer.semantic.application.SemanticCurrentReadService
import cc.monomer.semantic.application.SemanticRepository
import cc.monomer.semantic.application.SemanticTransactionPort
import cc.monomer.semantic.domain.CurrentMetric
import cc.monomer.semantic.domain.CurrentSemanticModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SemanticMetricSourceConnectorsTest {
    private val workspace = WorkspaceId.generate()
    private val repository = InMemorySemanticRepository()
    private val first = connector("First warehouse", workspace)
    private val shared = connector("Shared warehouse", workspace)
    private val last = connector("Last warehouse", workspace)

    @Test
    fun `five metric types use source intersections and share one owner snapshot without execution validation`() = runBlocking {
        saveModel(model("orders", listOf(first.connectorId, shared.connectorId)), workspace)
        saveModel(model("shipments", listOf(shared.connectorId, last.connectorId)), workspace)
        val orderMetric = simple("order_count", "orders")
        val shipmentMetric = simple("shipment_count", "shipments")
        val ratioMetric = ratio("shipment_ratio", input("shipment_count", null), input("order_count", null))
        val definitions = listOf(
            orderMetric,
            shipmentMetric,
            ratioMetric,
            derived("derived_ratio", listOf(input("shipment_ratio", null), input("order_count", null))),
            cumulative("cumulative_ratio", input("shipment_ratio", null)),
            conversion("conversion_rate", input("order_count", null), input("shipment_count", null)),
        )
        definitions.forEach { saveMetric(it, workspace) }
        var snapshotActive = false
        var snapshots = 0
        val transactions = object : SemanticTransactionPort {
            override suspend fun <T> inSnapshotTransaction(deadline: Instant, block: suspend () -> T): T = error("Unused deadline overload")
            override suspend fun <T> inMutationTransaction(block: () -> T): T = error("Read must not mutate")
            override suspend fun <T> inSnapshotTransaction(block: () -> T): T {
                assertFalse(snapshotActive)
                snapshots++
                snapshotActive = true
                return try { block() } finally { snapshotActive = false }
            }
        }
        val snapshotRepository = object : SemanticRepository by repository {
            override fun findMetrics(workspaceId: WorkspaceId): List<CurrentMetric> {
                assertTrue(snapshotActive)
                return repository.findMetrics(workspaceId)
            }
            override fun findModels(workspaceId: WorkspaceId): List<CurrentSemanticModel> {
                assertTrue(snapshotActive)
                return repository.findModels(workspaceId)
            }
        }
        val connectorRead = mockk<ConnectorWorkspaceReadUseCase> {
            coEvery { listByWorkspace(workspace) } returns listOf(last, first, shared)
        }
        val read = SemanticCurrentReadService(snapshotRepository, repository, transactions, connectorRead,
            io.mockk.mockk(relaxed = true),
        )

        val result = read.findMetricSourceConnectors(workspace, definitions.map { it.name }.toSet())

        assertEquals(candidates(first, shared), result.getValue(orderMetric.name))
        assertEquals(candidates(shared, last), result.getValue(shipmentMetric.name))
        definitions.drop(2).forEach { assertEquals(candidates(shared), result.getValue(it.name), it.name.value) }
        assertEquals(definitions.map { it.name }.toSet(), result.keys)
        assertEquals(1, snapshots)
        assertEquals(1, repository.metricCollectionReads)
        assertEquals(1, repository.modelCollectionReads)
        assertEquals(0, repository.timeSpineCollectionReads)
        assertEquals(0, repository.metricNameReads)
        coVerify(exactly = 1) { connectorRead.listByWorkspace(workspace) }
        assertFalse(snapshotActive)
    }

    @Test
    fun `stored filters and input reference filters include their models across transitive dependencies`() = runBlocking {
        saveModel(model("orders", listOf(first.connectorId, shared.connectorId, last.connectorId)), workspace)
        saveModel(model("customers", listOf(first.connectorId, shared.connectorId)), workspace)
        saveModel(model("regions", listOf(shared.connectorId, last.connectorId)), workspace)
        val customerFilter = SemanticMetricFilter.Not(SemanticMetricFilter.Or(listOf(
            SemanticMetricFilter.And(listOf(filter("customers"))),
        )))
        val regionFilter = filter("regions")
        val definitions = listOf(
            simple("base", "orders"),
            simple("other_base", "orders"),
            simple("customer_filtered", "orders").copy(filters = listOf(customerFilter)),
            derived("transitive_filtered", listOf(input("customer_filtered", regionFilter))),
            ratio("filtered_ratio", input("base", regionFilter), input("other_base", null)).copy(filters = listOf(customerFilter)),
            cumulative("filtered_cumulative", input("base", regionFilter)),
            conversion("filtered_conversion", input("base", regionFilter), input("other_base", null)),
        )
        definitions.forEach { saveMetric(it, workspace) }
        val read = SemanticCurrentReadService(repository, repository, ImmediateTransactionPort, mockk {
            coEvery { listByWorkspace(workspace) } returns listOf(first, shared, last)
        },
            io.mockk.mockk(relaxed = true),
        )

        val result = read.findMetricSourceConnectors(workspace, definitions.map { it.name }.toSet())

        assertEquals(candidates(first, shared, last), result.getValue(SemanticElementName("base")))
        assertEquals(candidates(first, shared), result.getValue(SemanticElementName("customer_filtered")))
        listOf("transitive_filtered", "filtered_ratio").forEach {
            assertEquals(candidates(shared), result.getValue(SemanticElementName(it)))
        }
        listOf("filtered_cumulative", "filtered_conversion").forEach {
            assertEquals(candidates(shared, last), result.getValue(SemanticElementName(it)))
        }
    }

    @Test
    fun `missing metric model and filter references return empty candidates instead of crossing Workspace boundaries`() = runBlocking {
        val otherWorkspace = WorkspaceId.generate()
        saveModel(model("orders", listOf(shared.connectorId)), workspace)
        saveModel(model("foreign_model", listOf(shared.connectorId)), otherWorkspace)
        saveMetric(simple("foreign_metric", "foreign_model"), otherWorkspace)
        val definitions = listOf(
            simple("base", "orders"),
            simple("missing_model", "missing"),
            simple("outside_model", "foreign_model"),
            derived("missing_input", listOf(input("absent_metric", null))),
            derived("outside_input", listOf(input("foreign_metric", null))),
            simple("missing_filter_model", "orders").copy(filters = listOf(filter("missing"))),
            derived("missing_input_filter_model", listOf(input("base", filter("missing")))),
        )
        definitions.forEach { saveMetric(it, workspace) }
        val read = SemanticCurrentReadService(repository, repository, ImmediateTransactionPort, mockk {
            coEvery { listByWorkspace(workspace) } returns listOf(shared)
        },
            io.mockk.mockk(relaxed = true),
        )
        val names = definitions.map { it.name }.toSet() + SemanticElementName("not_saved")

        val result = read.findMetricSourceConnectors(workspace, names)

        assertEquals(names, result.keys)
        assertEquals(candidates(shared), result.getValue(SemanticElementName("base")))
        (names - SemanticElementName("base")).forEach { assertEquals(emptyList(), result.getValue(it), it.value) }
    }

    @Test
    fun `deleted and other Workspace Connector sources never appear and next request reflects current connectors`() = runBlocking {
        val deleted = connector("Deleted warehouse", workspace)
        val foreign = connector("Other Workspace secret name", WorkspaceId.generate())
        saveModel(model("orders", listOf(shared.connectorId, deleted.connectorId, foreign.connectorId)), workspace)
        saveMetric(simple("base", "orders"), workspace)
        var currentConnectors = listOf(shared, foreign)
        val connectorRead = mockk<ConnectorWorkspaceReadUseCase> {
            coEvery { listByWorkspace(workspace) } answers { currentConnectors }
        }
        val read = SemanticCurrentReadService(repository, repository, ImmediateTransactionPort, connectorRead,
            io.mockk.mockk(relaxed = true),
        )
        val requested = setOf(SemanticElementName("base"))

        assertEquals(candidates(shared), read.findMetricSourceConnectors(workspace, requested).getValue(requested.single()))
        currentConnectors = listOf(foreign)
        assertEquals(emptyList(), read.findMetricSourceConnectors(workspace, requested).getValue(requested.single()))
        coVerify(exactly = 2) { connectorRead.listByWorkspace(workspace) }
    }

    @Test
    fun `an empty batch performs no owner or Connector reads`() = runBlocking {
        val connectorRead = mockk<ConnectorWorkspaceReadUseCase>()
        val transactions = mockk<SemanticTransactionPort>()
        val read = SemanticCurrentReadService(repository, repository, transactions, connectorRead,
            io.mockk.mockk(relaxed = true),
        )

        assertEquals(emptyMap(), read.findMetricSourceConnectors(workspace, emptySet()))

        assertEquals(0, repository.metricCollectionReads)
        assertEquals(0, repository.modelCollectionReads)
        coVerify(exactly = 0) { connectorRead.listByWorkspace(workspace) }
    }

    private fun saveModel(definition: SemanticModelDefinition, owner: WorkspaceId) {
        val id = SemanticModelId.generate()
        repository.models[id] = CurrentSemanticModel(id, owner, definition, NOW, NOW)
    }

    private fun saveMetric(definition: SemanticMetricDefinition, owner: WorkspaceId) {
        val id = MetricId.generate()
        repository.metrics[id] = CurrentMetric(id, owner, definition, NOW, NOW)
    }

    private fun model(name: String, connectors: List<ConnectorId>): SemanticModelDefinition = SemanticModelDefinition(
        3u, SemanticElementName(name), null, null,
        connectors.map { SemanticSourceRelation(it, null, "analytics", name) }, null,
        listOf(SemanticEntityDefinition(SemanticElementName("id"), SemanticEntityKind.PRIMARY, "id", null, null)),
        emptyList(), emptyList(),
    )

    private fun simple(name: String, modelName: String): SemanticMetricDefinition.Simple = SemanticMetricDefinition.Simple(
        3u, SemanticElementName(name), null, null, null, emptyList(), SemanticElementName(modelName), SemanticElementName("unvalidated_measure"),
        display = null,
    )

    private fun ratio(name: String, numerator: SemanticMetricInput, denominator: SemanticMetricInput): SemanticMetricDefinition.Ratio =
        SemanticMetricDefinition.Ratio(3u, SemanticElementName(name), null, null, null, emptyList(), numerator, denominator, display = null)

    private fun derived(name: String, inputs: List<SemanticMetricInput>): SemanticMetricDefinition.Derived =
        SemanticMetricDefinition.Derived(3u, SemanticElementName(name), null, null, null, emptyList(), "unvalidated_expression", inputs, display = null)

    private fun cumulative(name: String, input: SemanticMetricInput): SemanticMetricDefinition.Cumulative =
        SemanticMetricDefinition.Cumulative(3u, SemanticElementName(name), null, null, null, emptyList(), input, null, null, null, display = null)

    private fun conversion(name: String, base: SemanticMetricInput, conversion: SemanticMetricInput): SemanticMetricDefinition.Conversion =
        SemanticMetricDefinition.Conversion(3u, SemanticElementName(name), null, null, null, emptyList(), SemanticElementName("unvalidated_entity"),
            SemanticConversionCalculation.CONVERSION_RATE, base, conversion, null, null, display = null)

    private fun input(name: String, filter: SemanticMetricFilter?): SemanticMetricInput =
        SemanticMetricInput(SemanticElementName(name), filter, null, null, null)

    private fun filter(modelName: String): SemanticMetricFilter = SemanticMetricFilter.Predicate(
        SemanticElementReference(SemanticElementName(modelName), SemanticElementName("unvalidated_dimension"), null),
        SemanticMetricFilterOperator.EQ, listOf("active"),
    )

    private fun connector(name: String, owner: WorkspaceId): CurrentConnectorRead = CurrentConnectorRead(
        ConnectorId.generate(), owner, ConnectorProductType.POSTGRESQL, name,
        ConnectorSettingsProperties.Postgresql("localhost", 5432, "warehouse", null, null), NOW, NOW,
    )

    private fun candidates(vararg connectors: CurrentConnectorRead): List<MetricSourceConnector> =
        connectors.sortedBy { it.connectorId.toString() }.map { MetricSourceConnector(it.connectorId, it.displayName) }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-08T00:00:00Z")
    }
}
