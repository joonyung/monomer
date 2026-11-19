package cc.monomer.semantic.adapter.outbound.metricflow

import cc.monomer.catalog.api.CatalogPhysicalAddress
import cc.monomer.connector.api.ConnectorId
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.SemanticConversionCalculation
import cc.monomer.semantic.api.SemanticDimensionDefinition
import cc.monomer.semantic.api.SemanticDimensionKind
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticEntityDefinition
import cc.monomer.semantic.api.SemanticEntityKind
import cc.monomer.semantic.api.SemanticGroupBy
import cc.monomer.semantic.api.SemanticMeasureAggregation
import cc.monomer.semantic.api.SemanticMeasureDefinition
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticMetricInput
import cc.monomer.semantic.api.SemanticMetricTimeRange
import cc.monomer.semantic.api.SemanticMetricWindow
import cc.monomer.semantic.api.SemanticMetricWindowUnit
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticQuerySpec
import cc.monomer.semantic.api.SemanticSourceRelation
import cc.monomer.semantic.api.SemanticSqlDialect
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.api.SemanticTimeKind
import cc.monomer.semantic.api.SemanticTimeRangeEndpoint
import cc.monomer.semantic.application.SemanticInstantStorage
import cc.monomer.semantic.application.SemanticMetricFlowManifest
import cc.monomer.semantic.application.SemanticMetricFlowModel
import cc.monomer.semantic.application.SemanticMetricFlowRenderResult
import cc.monomer.semantic.application.SemanticMetricFlowTimeDimension
import cc.monomer.semantic.application.SemanticMetricFlowTimeSpine
import cc.monomer.semantic.application.SemanticMetricFlowValidationResult
import cc.monomer.semantic.application.SemanticTimeOperand
import java.math.BigDecimal
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
import kotlin.test.assertTrue

/**
 * Executes production SQL for timestamptz sources on disposable PostgreSQL with session time zones that
 * differ from the Asia/Seoul Reporting time zone, and expects identical Seoul calendar buckets.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetricFlowReportingTimeZonePostgresIntegrationTest {
    private val postgres = PostgreSQLContainer(
        DockerImageName.parse(
            "postgres:16.14-alpine3.24@" +
                "sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777",
        ).asCompatibleSubstituteFor("postgres"),
    )
        .withDatabaseName("reporting_time_zone_gate_ephemeral")
        .withUsername("reporting_time_zone_gate")
        .withPassword("reporting_time_zone_gate_test_only")
    private val schema = "reporting_time_zone_fixture"
    private val connectorId = ConnectorId.generate()
    private val sessionTimeZones = listOf("America/Los_Angeles", "Pacific/Kiritimati", "UTC")

    @BeforeAll
    fun startDisposableFixture() {
        postgres.start()
        assertTrue(postgres.isRunning)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT current_database(), current_user").use { result ->
                    assertTrue(result.next())
                    assertEquals("reporting_time_zone_gate_ephemeral", result.getString(1))
                    assertEquals("reporting_time_zone_gate", result.getString(2))
                }
                statement.execute(
                    """
                    CREATE SCHEMA $schema;
                    CREATE TABLE $schema.users (user_id BIGINT PRIMARY KEY, region TEXT NOT NULL);
                    CREATE TABLE $schema.purchases (
                        purchase_id BIGINT PRIMARY KEY,
                        user_id BIGINT NOT NULL,
                        purchased_at TIMESTAMPTZ NOT NULL,
                        amount NUMERIC NOT NULL
                    );
                    CREATE TABLE $schema.visits (
                        visit_id BIGINT PRIMARY KEY,
                        user_id BIGINT NOT NULL,
                        visited_at TIMESTAMPTZ NOT NULL
                    );
                    CREATE TABLE $schema.calendar (date_day DATE PRIMARY KEY);
                    INSERT INTO $schema.users VALUES (1, 'KR'), (2, 'US');
                    INSERT INTO $schema.purchases VALUES
                        (1, 1, TIMESTAMPTZ '2026-07-31 15:30:00+00', 100),
                        (2, 2, TIMESTAMPTZ '2026-07-31 14:30:00+00', 10),
                        (3, 1, TIMESTAMPTZ '2026-08-02 15:30:00+00', 1000),
                        (4, 2, TIMESTAMPTZ '2026-08-10 23:30:00-05', 5),
                        (5, 2, TIMESTAMPTZ '2026-08-11 08:00:00+09', 7);
                    INSERT INTO $schema.visits VALUES
                        (1, 1, TIMESTAMPTZ '2026-08-01 15:10:00+00'),
                        (2, 2, TIMESTAMPTZ '2026-07-30 14:00:00+00');
                    INSERT INTO $schema.calendar
                        SELECT day::date FROM generate_series(DATE '2026-06-01', DATE '2026-09-30', INTERVAL '1 day') AS day;
                    """.trimIndent(),
                )
            }
        }
    }

    @AfterAll
    fun stopDisposableFixture() {
        postgres.stop()
        assertFalse(postgres.isRunning)
    }

    @Test
    fun `simple and ratio metrics bucket week and month boundaries and mixed offsets by Seoul dates`() {
        assertBuckets(
            query(listOf("purchase_revenue"), listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.MONTH))),
            mapOf(listOf("2026-07-01") to "10", listOf("2026-08-01") to "1112"),
        )
        assertBuckets(
            query(listOf("purchase_revenue"), listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.WEEK))),
            mapOf(listOf("2026-07-27") to "110", listOf("2026-08-03") to "1000", listOf("2026-08-10") to "12"),
        )
        assertBuckets(
            query(listOf("purchase_revenue"), listOf(SemanticGroupBy.Dimension(PURCHASES, PURCHASED_AT, SemanticTimeGranularity.DAY))),
            mapOf(
                listOf("2026-07-31") to "10", listOf("2026-08-01") to "100", listOf("2026-08-03") to "1000",
                listOf("2026-08-11") to "12",
            ),
        )
        assertBuckets(
            query(listOf("revenue_per_purchase"), listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.MONTH))),
            mapOf(listOf("2026-07-01") to "10", listOf("2026-08-01") to "278"),
        )
    }

    @Test
    fun `a metric grouped by another model's dimension keeps Seoul month buckets`() {
        assertBuckets(
            query(
                listOf("purchase_revenue"),
                listOf(SemanticGroupBy.Dimension(USERS, SemanticElementName("region"), null), SemanticGroupBy.MetricTime(SemanticTimeGranularity.MONTH)),
            ),
            mapOf(listOf("KR", "2026-08-01") to "1100", listOf("US", "2026-07-01") to "10", listOf("US", "2026-08-01") to "12"),
        )
    }

    @Test
    fun `cumulative offset and conversion metrics compare Seoul dates on the date spine`() {
        val cumulative = execute(
            query(listOf("month_to_date_revenue"), listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.DAY))).copy(
                metricTimeRange = SemanticMetricTimeRange(
                    SemanticTimeRangeEndpoint.Date(LocalDate.parse("2026-07-31")),
                    SemanticTimeRangeEndpoint.Date(LocalDate.parse("2026-08-11")),
                ),
            ),
        )
        listOf("2026-07-31" to "10", "2026-08-01" to "100", "2026-08-02" to "100", "2026-08-03" to "1100", "2026-08-10" to "1100", "2026-08-11" to "1112")
            .forEach { (day, value) -> assertNumber(value, cumulative.getValue(listOf(day)), "month to date $day") }

        val offset = execute(query(listOf("prior_week_revenue"), listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.WEEK))))
        assertNumber("110", offset.getValue(listOf("2026-08-03")), "prior week of 2026-08-03")
        assertNumber("1000", offset.getValue(listOf("2026-08-10")), "prior week of 2026-08-10")
        assertNumber("12", offset.getValue(listOf("2026-08-17")), "prior week of 2026-08-17")

        assertBuckets(
            query(listOf("visit_conversion_rate"), listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.DAY))),
            mapOf(listOf("2026-07-30") to "1", listOf("2026-08-02") to "1"),
        )
    }

    private fun assertBuckets(query: SemanticQuerySpec, expected: Map<List<String>, String>) {
        val rows = execute(query)
        assertEquals(expected.keys, rows.keys, "buckets of ${query.metrics}")
        expected.forEach { (bucket, value) -> assertNumber(value, rows.getValue(bucket), "$bucket of ${query.metrics}") }
    }

    private fun assertNumber(expected: String, actual: BigDecimal?, message: String) {
        assertTrue(actual != null && BigDecimal(expected).compareTo(actual) == 0, "$message: expected $expected but was $actual")
    }

    /** Runs the SQL once per session time zone and returns the one result they all agree on. */
    private fun execute(query: SemanticQuerySpec): Map<List<String>, BigDecimal?> {
        val manifest = manifest()
        val adapter = MetricFlowSemanticRenderer.production()
        assertIs<SemanticMetricFlowValidationResult.Valid>(adapter.validate(manifest))
        val metricIds = query.metrics.map { name -> manifest.metrics.entries.single { it.value.name == name }.key }
        val rendered = assertIs<SemanticMetricFlowRenderResult.Rendered>(adapter.render(manifest, query, metricIds))
        assertTrue("AT TIME ZONE 'Asia/Seoul'" in rendered.sql, rendered.sql)
        val results = sessionTimeZones.map { sessionTimeZone -> sessionTimeZone to run(rendered, query.groupBys.size, sessionTimeZone) }
        results.forEach { (sessionTimeZone, rows) ->
            assertEquals(results.first().second, rows, "session $sessionTimeZone differs from ${results.first().first}:\n${rendered.sql}")
        }
        return results.first().second
    }

    private fun run(rendered: SemanticMetricFlowRenderResult.Rendered, groupByCount: Int, sessionTimeZone: String): Map<List<String>, BigDecimal?> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET TIME ZONE '$sessionTimeZone'")
                statement.executeQuery("SHOW TIME ZONE").use { result ->
                    assertTrue(result.next())
                    assertEquals(sessionTimeZone, result.getString(1))
                }
                statement.executeQuery(rendered.sql).use { result ->
                    val metadata = result.metaData
                    buildMap {
                        while (result.next()) {
                            val bucket = (1..groupByCount).map { column ->
                                if (metadata.getColumnTypeName(column) == "date") {
                                    result.getObject(column, LocalDate::class.java).toString()
                                } else {
                                    result.getString(column)
                                }
                            }
                            put(bucket, result.getBigDecimal(groupByCount + 1))
                        }
                    }
                }
            }
        }

    private fun query(metrics: List<String>, groupBys: List<SemanticGroupBy>) = SemanticQuerySpec(
        metrics = metrics.map(::SemanticElementName),
        groupBys = groupBys,
        metricTimeRange = null,
        filters = emptyList(),
        orderBy = emptyList(),
        rowLimit = 1_000,
    )

    private fun manifest(): SemanticMetricFlowManifest {
        val users = model(
            USERS, "users",
            listOf(entity("user_id", SemanticEntityKind.PRIMARY)),
            listOf(SemanticDimensionDefinition(SemanticElementName("region"), SemanticDimensionKind.CATEGORICAL, "region", null, null, null, false, null)),
            emptyList(), null,
        )
        val purchases = model(
            PURCHASES, "purchases",
            listOf(entity("purchase_id", SemanticEntityKind.PRIMARY), entity("user_id", SemanticEntityKind.FOREIGN)),
            listOf(timeDimension(PURCHASED_AT)),
            listOf(measure("revenue", SemanticMeasureAggregation.SUM, "amount"), measure("purchase_count", SemanticMeasureAggregation.COUNT, "1")),
            PURCHASED_AT,
        )
        val visits = model(
            VISITS, "visits",
            listOf(entity("visit_id", SemanticEntityKind.PRIMARY), entity("user_id", SemanticEntityKind.FOREIGN)),
            listOf(timeDimension(VISITED_AT)),
            listOf(measure("visit_count", SemanticMeasureAggregation.COUNT, "1")),
            VISITED_AT,
        )
        val metrics = listOf(
            simple("purchase_revenue", PURCHASES, "revenue"),
            simple("purchases_made", PURCHASES, "purchase_count"),
            simple("visits_made", VISITS, "visit_count"),
            SemanticMetricDefinition.Ratio(3u, SemanticElementName("revenue_per_purchase"), null, null, null, emptyList(), input("purchase_revenue", null), input("purchases_made", null), null),
            SemanticMetricDefinition.Cumulative(
                3u, SemanticElementName("month_to_date_revenue"), null, null, null, emptyList(),
                input("purchase_revenue", null), null, SemanticTimeGranularity.MONTH, null, null,
            ),
            SemanticMetricDefinition.Derived(
                3u, SemanticElementName("prior_week_revenue"), null, null, null, emptyList(), "prior_revenue",
                listOf(SemanticMetricInput(SemanticElementName("purchase_revenue"), null, SemanticElementName("prior_revenue"), SemanticMetricWindow(1, SemanticMetricWindowUnit.WEEK), null)),
                null,
            ),
            SemanticMetricDefinition.Conversion(
                3u, SemanticElementName("visit_conversion_rate"), null, null, null, emptyList(), SemanticElementName("user_id"),
                SemanticConversionCalculation.CONVERSION_RATE, input("visits_made", null), input("purchases_made", null),
                SemanticMetricWindow(7, SemanticMetricWindowUnit.DAY), null, null,
            ),
        )
        return SemanticMetricFlowManifest(
            connectorId = connectorId,
            dialect = SemanticSqlDialect.POSTGRESQL,
            models = listOf(users, purchases, visits).associateBy { it.definition.name },
            metrics = metrics.associateBy { MetricId.generate() },
            timeSpines = listOf(SemanticMetricFlowTimeSpine(CatalogPhysicalAddress(null, schema, "calendar", null), "date_day", SemanticTimeGranularity.DAY)),
            reportingTimeZone = ZoneId.of("Asia/Seoul"),
        )
    }

    private fun model(
        name: SemanticElementName,
        relation: String,
        entities: List<SemanticEntityDefinition>,
        dimensions: List<SemanticDimensionDefinition>,
        measures: List<SemanticMeasureDefinition>,
        aggregationTimeDimension: SemanticElementName?,
    ): SemanticMetricFlowModel {
        val definition = SemanticModelDefinition(
            3u, name, null, null, listOf(SemanticSourceRelation(connectorId, null, schema, relation)),
            aggregationTimeDimension, entities, dimensions, measures,
        )
        val timeDimensions = dimensions.filter { it.type == SemanticDimensionKind.TIME }.associate { dimension ->
            dimension.name to SemanticMetricFlowTimeDimension(
                SemanticTimeKind.INSTANT, SemanticInstantStorage.ZONED, SemanticTimeOperand.Column(dimension.expression, false, null),
            )
        }
        return SemanticMetricFlowModel(definition, CatalogPhysicalAddress(null, schema, relation, null), timeDimensions)
    }

    private fun entity(name: String, kind: SemanticEntityKind) =
        SemanticEntityDefinition(SemanticElementName(name), kind, name, null, null)

    private fun timeDimension(name: SemanticElementName) =
        SemanticDimensionDefinition(name, SemanticDimensionKind.TIME, name.value, SemanticTimeGranularity.DAY, null, null, false, null)

    private fun measure(name: String, aggregation: SemanticMeasureAggregation, expression: String) =
        SemanticMeasureDefinition(SemanticElementName(name), aggregation, expression, null, null, null, null)

    private fun simple(name: String, model: SemanticElementName, measure: String) =
        SemanticMetricDefinition.Simple(3u, SemanticElementName(name), null, null, null, emptyList(), model, SemanticElementName(measure), null)

    private fun input(metric: String, alias: String?) =
        SemanticMetricInput(SemanticElementName(metric), null, alias?.let(::SemanticElementName), null, null)

    private companion object {
        val USERS = SemanticElementName("users")
        val PURCHASES = SemanticElementName("purchases")
        val VISITS = SemanticElementName("visits")
        val PURCHASED_AT = SemanticElementName("purchased_at")
        val VISITED_AT = SemanticElementName("visited_at")
    }
}
