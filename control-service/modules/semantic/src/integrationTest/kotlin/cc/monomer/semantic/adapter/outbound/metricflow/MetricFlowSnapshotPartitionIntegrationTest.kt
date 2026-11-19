package cc.monomer.semantic.adapter.outbound.metricflow

import cc.monomer.catalog.api.CatalogPhysicalAddress
import cc.monomer.connector.api.ConnectorId
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.SemanticDimensionDefinition
import cc.monomer.semantic.api.SemanticDimensionKind
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticEntityDefinition
import cc.monomer.semantic.api.SemanticEntityKind
import cc.monomer.semantic.api.SemanticGroupBy
import cc.monomer.semantic.api.SemanticMeasureAggregation
import cc.monomer.semantic.api.SemanticMeasureDefinition
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticQuerySpec
import cc.monomer.semantic.api.SemanticSourceRelation
import cc.monomer.semantic.api.SemanticSqlDialect
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.application.SemanticMetricFlowManifest
import cc.monomer.semantic.api.SemanticTimeKind
import cc.monomer.semantic.application.SemanticMetricFlowModel
import cc.monomer.semantic.application.SemanticMetricFlowTimeDimension
import cc.monomer.semantic.application.SemanticTimeOperand
import cc.monomer.semantic.application.SemanticMetricFlowRenderResult
import cc.monomer.semantic.application.SemanticMetricFlowValidationResult
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetricFlowSnapshotPartitionIntegrationTest {
    private val postgres = PostgreSQLContainer(
        DockerImageName.parse(
            "postgres:16.14-alpine3.24@" +
                "sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777",
        ).asCompatibleSubstituteFor("postgres"),
    )
        .withDatabaseName("snapshot_partition_gate_ephemeral")
        .withUsername("snapshot_partition_gate")
        .withPassword("snapshot_partition_gate_test_only")
    private val schema = "snapshot_partition_fixture"
    private val firstDate = LocalDate.parse("2026-10-01")
    private val secondDate = LocalDate.parse("2026-10-02")

    @BeforeAll
    fun startDisposableSnapshotFixture() {
        postgres.start()
        assertTrue(postgres.isRunning)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT current_database(), current_user").use { result ->
                    assertTrue(result.next())
                    assertEquals("snapshot_partition_gate_ephemeral", result.getString(1))
                    assertEquals("snapshot_partition_gate", result.getString(2))
                }
                statement.execute(
                    """
                    CREATE SCHEMA $schema;
                    CREATE TABLE $schema.orders (
                        order_id BIGINT NOT NULL,
                        snapshot_date DATE NOT NULL,
                        country TEXT NOT NULL,
                        PRIMARY KEY (order_id, snapshot_date)
                    );
                    CREATE TABLE $schema.shipments (
                        shipment_id BIGINT NOT NULL,
                        order_id BIGINT NOT NULL,
                        snapshot_date DATE NOT NULL,
                        revenue BIGINT NOT NULL,
                        PRIMARY KEY (shipment_id, snapshot_date)
                    );
                    INSERT INTO $schema.orders VALUES
                        (1, DATE '2026-10-01', 'US'),
                        (1, DATE '2026-10-02', 'CA'),
                        (2, DATE '2026-10-01', 'GB'),
                        (2, DATE '2026-10-02', 'GB');
                    INSERT INTO $schema.shipments VALUES
                        (101, 1, DATE '2026-10-01', 10),
                        (101, 1, DATE '2026-10-02', 20),
                        (102, 2, DATE '2026-10-01', 30),
                        (102, 2, DATE '2026-10-02', 40);
                    """.trimIndent(),
                )
            }
        }
    }

    @AfterAll
    fun stopDisposableSnapshotFixture() {
        postgres.stop()
        assertFalse(postgres.isRunning)
    }

    @Test
    fun `production SQL joins matching date snapshots and disabling partition exposes aggregate fanout`() {
        val connectorId = ConnectorId.generate()
        val metricId = MetricId.generate()
        val orders = orders(connectorId)
        val shipments = orders.copy(
            name = SemanticElementName("shipments"),
            label = "Shipments",
            sourceRelations = listOf(SemanticSourceRelation(connectorId, null, schema, "shipments")),
            entities = listOf(
                orders.entities.single().copy(type = SemanticEntityKind.FOREIGN),
                orders.entities.single().copy(name = SemanticElementName("shipment_id"), expression = "shipment_id"),
            ),
            dimensions = orders.dimensions.filter { it.type == SemanticDimensionKind.TIME },
            measures = listOf(orders.measures.single().copy(name = SemanticElementName("shipment_revenue"), expression = "revenue")),
        )
        val metric = SemanticMetricDefinition.Simple(
            schemaVersion = 3u,
            name = SemanticElementName("shipment_revenue_metric"),
            label = "Shipment revenue",
            description = null,
            timeGranularity = SemanticTimeGranularity.DAY,
            filters = emptyList(),
            semanticModelName = shipments.name,
            measure = SemanticElementName("shipment_revenue"),
            display = null,
        )
        val manifest = SemanticMetricFlowManifest(
            connectorId = connectorId,
            dialect = SemanticSqlDialect.POSTGRESQL,
            models = listOf(orders, shipments).associate { model ->
                model.name to SemanticMetricFlowModel(
                    model,
                    CatalogPhysicalAddress(null, schema, model.name.value, null),
                    mapOf(
                        SemanticElementName("snapshot_date") to SemanticMetricFlowTimeDimension(
                            SemanticTimeKind.DATE, null, SemanticTimeOperand.Column("snapshot_date", false, null),
                        ),
                    ),
                )
            },
            metrics = mapOf(metricId to metric),
            timeSpines = emptyList(),
            reportingTimeZone = ZoneId.of("UTC"),
        )
        val query = SemanticQuerySpec(
            metrics = listOf(metric.name),
            groupBys = listOf(
                SemanticGroupBy.Dimension(orders.name, SemanticElementName("country"), null),
                SemanticGroupBy.Dimension(shipments.name, SemanticElementName("snapshot_date"), SemanticTimeGranularity.DAY),
            ),
            metricTimeRange = null,
            filters = emptyList(),
            orderBy = emptyList(),
            rowLimit = 100,
        )
        fun render(snapshotPartition: Boolean): SemanticMetricFlowRenderResult.Rendered {
            val partitionManifest = manifest.copy(models = manifest.models.mapValues { (_, model) ->
                model.copy(definition = model.definition.copy(dimensions = model.definition.dimensions.map { dimension ->
                    if (dimension.type == SemanticDimensionKind.TIME) dimension.copy(isPartition = snapshotPartition) else dimension
                }))
            })
            val adapter = MetricFlowSemanticRenderer.production()
            assertIs<SemanticMetricFlowValidationResult.Valid>(adapter.validate(partitionManifest))
            val rendered = assertIs<SemanticMetricFlowRenderResult.Rendered>(adapter.render(partitionManifest, query, listOf(metricId)))
            assertEquals(SemanticSqlDialect.POSTGRESQL, rendered.dialect)
            assertEquals(listOf(metricId), rendered.requestedMetricIds)
            assertEquals(
                listOf("dimension.orders.country", "dimension.shipments.snapshot_date.day", "metric.shipment_revenue_metric"),
                rendered.outputColumns.map { it.name.value },
            )
            println("Snapshot partition enabled=$snapshotPartition SQL:\n${rendered.sql}")
            return rendered
        }

        val partitioned = render(true)
        val ordinary = render(false)
        val dateJoin = Regex("shipments_src_10000\\.snapshot_date[^\\n]*=[^\\n]*orders_src_10000\\.snapshot_date")
        assertTrue(dateJoin.containsMatchIn(partitioned.sql), partitioned.sql)
        assertFalse(dateJoin.containsMatchIn(ordinary.sql), ordinary.sql)
        assertNotEquals(partitioned.sql, ordinary.sql)

        val partitionedRows = execute(partitioned)
        val ordinaryRows = execute(ordinary)
        assertEquals(
            setOf(SnapshotRevenue("US", firstDate, 10), SnapshotRevenue("GB", firstDate, 30),
                SnapshotRevenue("CA", secondDate, 20), SnapshotRevenue("GB", secondDate, 40)),
            partitionedRows.toSet(),
        )
        assertEquals(4, partitionedRows.size)
        assertEquals(100L, partitionedRows.sumOf { it.revenue })
        assertEquals(
            setOf(SnapshotRevenue("US", firstDate, 10), SnapshotRevenue("CA", firstDate, 10), SnapshotRevenue("GB", firstDate, 60),
                SnapshotRevenue("US", secondDate, 20), SnapshotRevenue("CA", secondDate, 20), SnapshotRevenue("GB", secondDate, 80)),
            ordinaryRows.toSet(),
        )
        assertEquals(6, ordinaryRows.size)
        assertEquals(200L, ordinaryRows.sumOf { it.revenue })
        assertNotEquals(partitionedRows.toSet(), ordinaryRows.toSet())
        println("Snapshot partition enabled=true rows=$partitionedRows; enabled=false rows=$ordinaryRows")
    }

    private fun execute(rendered: SemanticMetricFlowRenderResult.Rendered): List<SnapshotRevenue> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(rendered.sql).use { result ->
                    assertEquals(rendered.outputColumns.map { it.sqlName },
                        (1..result.metaData.columnCount).map { result.metaData.getColumnLabel(it) })
                    buildList {
                        while (result.next()) {
                            add(SnapshotRevenue(result.getString(1), result.getDate(2).toLocalDate(), result.getBigDecimal(3).longValueExact()))
                        }
                    }
                }
            }
        }

    private fun orders(connectorId: ConnectorId) = SemanticModelDefinition(
        schemaVersion = 3u,
        name = SemanticElementName("orders"),
        label = "Orders",
        description = null,
        sourceRelations = listOf(SemanticSourceRelation(connectorId, null, schema, "orders")),
        aggregationTimeDimension = SemanticElementName("snapshot_date"),
        entities = listOf(SemanticEntityDefinition(SemanticElementName("order_id"), SemanticEntityKind.PRIMARY, "order_id", null, null)),
        dimensions = listOf(
            SemanticDimensionDefinition(SemanticElementName("snapshot_date"), SemanticDimensionKind.TIME, "snapshot_date", SemanticTimeGranularity.DAY, null, null, false, null),
            SemanticDimensionDefinition(SemanticElementName("country"), SemanticDimensionKind.CATEGORICAL, "country", null, null, null, false, null),
        ),
        measures = listOf(SemanticMeasureDefinition(SemanticElementName("order_revenue"), SemanticMeasureAggregation.SUM, "0", SemanticElementName("snapshot_date"), null, null, null)),
    )

    private data class SnapshotRevenue(val country: String, val date: LocalDate, val revenue: Long)
}
