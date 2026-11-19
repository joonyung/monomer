package cc.monomer.semantic.adapter.outbound.metricflow

import cc.monomer.catalog.api.CatalogPhysicalAddress
import cc.monomer.connector.api.ConnectorId
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.SemanticConversionCalculation
import cc.monomer.semantic.api.SemanticDimensionDefinition
import cc.monomer.semantic.api.SemanticDimensionKind
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticElementReference
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
import cc.monomer.semantic.api.SemanticQueryFilter
import cc.monomer.semantic.api.SemanticQuerySpec
import cc.monomer.semantic.api.SemanticSourceRelation
import cc.monomer.semantic.api.SemanticSqlDialect
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.api.SemanticTimeKind
import cc.monomer.semantic.api.SemanticTimeRangeEndpoint
import cc.monomer.semantic.api.SemanticTimestampFilterOperator
import cc.monomer.semantic.application.SemanticInstantStorage
import cc.monomer.semantic.application.SemanticMetricFlowManifest
import cc.monomer.semantic.application.SemanticMetricFlowModel
import cc.monomer.semantic.application.SemanticMetricFlowRenderResult
import cc.monomer.semantic.application.SemanticMetricFlowTimeDimension
import cc.monomer.semantic.application.SemanticMetricFlowTimeSpine
import cc.monomer.semantic.application.SemanticMetricFlowValidationResult
import cc.monomer.semantic.application.SemanticTimeOperand
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Properties
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Executes production SQL on embedded DuckDB, the sample warehouse, with session TimeZone settings that
 * differ from the Asia/Seoul Reporting time zone, and expects identical Seoul dates and wall clocks.
 */
class MetricFlowReportingTimeZoneDuckDbIntegrationTest {
    private val schema = "reporting_time_zone_fixture"
    private val connectorId = ConnectorId.generate()
    private val sessionTimeZones = listOf("America/Los_Angeles", "Pacific/Kiritimati", "UTC")

    @Test
    fun `simple and ratio metrics bucket Monday weeks months and mixed offsets by Seoul dates`() {
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
        assertBuckets(
            query(listOf("purchase_revenue"), listOf(SemanticGroupBy.Dimension(USERS, SemanticElementName("region"), null), SemanticGroupBy.MetricTime(SemanticTimeGranularity.MONTH))),
            mapOf(listOf("KR", "2026-08-01") to "1100", listOf("US", "2026-07-01") to "10", listOf("US", "2026-08-01") to "12"),
        )
    }

    @Test
    fun `UTC instants stored without a time zone and cumulative offset and conversion metrics read Seoul dates`() {
        val cumulative = execute(
            query(listOf("month_to_date_revenue"), listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.DAY))).copy(
                metricTimeRange = SemanticMetricTimeRange(
                    SemanticTimeRangeEndpoint.Date(LocalDate.parse("2026-07-31")),
                    SemanticTimeRangeEndpoint.Date(LocalDate.parse("2026-08-11")),
                ),
            ),
            "timezone('Asia/Seoul', \"purchased_at\")",
        )
        listOf("2026-07-31" to "10", "2026-08-01" to "100", "2026-08-02" to "100", "2026-08-03" to "1100", "2026-08-10" to "1100", "2026-08-11" to "1112")
            .forEach { (day, value) -> assertNumber(value, cumulative.getValue(listOf(day)), "month to date $day") }

        val offset = execute(query(listOf("prior_week_revenue"), listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.WEEK))), "timezone('Asia/Seoul', \"purchased_at\")")
        assertNumber("110", offset.getValue(listOf("2026-08-03")), "prior week of 2026-08-03")
        assertNumber("1000", offset.getValue(listOf("2026-08-10")), "prior week of 2026-08-10")
        assertNumber("12", offset.getValue(listOf("2026-08-17")), "prior week of 2026-08-17")

        val conversion = execute(
            query(listOf("visit_conversion_rate"), listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.DAY))),
            "timezone('Asia/Seoul', timezone('UTC', \"visited_at\"))",
        )
        assertEquals(setOf(listOf("2026-07-30"), listOf("2026-08-02")), conversion.keys)
        conversion.forEach { (day, value) -> assertNumber("1", value, "conversion on $day") }
    }

    @Test
    fun `hour grain reads Seoul wall clocks and TIMESTAMP filters compare them for a mixed case column`() {
        assertBuckets(
            query(listOf("events_started"), listOf(SemanticGroupBy.Dimension(EVENTS, STARTED_AT, SemanticTimeGranularity.HOUR))),
            mapOf(listOf("2026-08-01T23:00") to "1", listOf("2026-08-02T00:00") to "2"),
        )
        assertBuckets(
            query(listOf("events_started"), listOf(SemanticGroupBy.Dimension(EVENTS, STARTED_AT, SemanticTimeGranularity.HOUR))).copy(
                filters = listOf(
                    SemanticQueryFilter.Timestamp(
                        SemanticElementReference(EVENTS, STARTED_AT),
                        SemanticTimestampFilterOperator.GREATER_THAN_OR_EQUAL,
                        listOf(Instant.parse("2026-08-01T15:00:00Z")),
                    ),
                ),
            ),
            mapOf(listOf("2026-08-02T00:00") to "2"),
        )
    }

    @Test
    fun `every supported Reporting time zone converts like the JDK in embedded DuckDB`() {
        val contract = Path.of(System.getProperty("control.service.root"), "../contracts/time-zones.json")
        val zones = Json.parseToJsonElement(Files.readString(contract)).jsonObject.getValue("timeZones").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(zones.size > 400)
        val instants = listOf(Instant.parse("2026-01-15T12:00:00Z"), Instant.parse("2026-07-15T12:00:00Z"))
        openFixture().use { connection ->
            val known = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM pg_timezone_names()").use { result ->
                    buildSet { while (result.next()) add(result.getString(1)) }
                }
            }
            assertEquals(emptyList(), zones.filterNot(known::contains))
            connection.prepareStatement(
                "SELECT timezone(?, CAST(? AS TIMESTAMP WITH TIME ZONE)), timezone(?, timezone('UTC', CAST(? AS TIMESTAMP)))",
            ).use { statement ->
                zones.forEach { zone ->
                    instants.forEach { instant ->
                        statement.setString(1, zone)
                        statement.setString(2, "$instant")
                        statement.setString(3, zone)
                        statement.setString(4, LocalDateTime.ofInstant(instant, ZoneOffset.UTC).toString())
                        statement.executeQuery().use { result ->
                            assertTrue(result.next())
                            val expected = LocalDateTime.ofInstant(instant, ZoneId.of(zone))
                            assertEquals(expected, result.getObject(1, LocalDateTime::class.java), "$zone zoned $instant")
                            assertEquals(expected, result.getObject(2, LocalDateTime::class.java), "$zone UTC naive $instant")
                        }
                    }
                }
            }
        }
    }

    private fun assertBuckets(query: SemanticQuerySpec, expected: Map<List<String>, String>) {
        val rows = execute(query, "timezone('Asia/Seoul', ")
        assertEquals(expected.keys, rows.keys, "buckets of ${query.metrics}")
        expected.forEach { (bucket, value) -> assertNumber(value, rows.getValue(bucket), "$bucket of ${query.metrics}") }
    }

    private fun assertNumber(expected: String, actual: BigDecimal?, message: String) {
        assertTrue(actual != null && BigDecimal(expected).compareTo(actual) == 0, "$message: expected $expected but was $actual")
    }

    /** Runs the SQL once per session TimeZone and returns the one result they all agree on. */
    private fun execute(query: SemanticQuerySpec, expectedConversion: String): Map<List<String>, BigDecimal?> {
        val manifest = manifest()
        val adapter = MetricFlowSemanticRenderer.production()
        assertIs<SemanticMetricFlowValidationResult.Valid>(adapter.validate(manifest))
        val metricIds = query.metrics.map { name -> manifest.metrics.entries.single { it.value.name == name }.key }
        val rendered = assertIs<SemanticMetricFlowRenderResult.Rendered>(adapter.render(manifest, query, metricIds))
        assertTrue(expectedConversion in rendered.sql, rendered.sql)
        val results = sessionTimeZones.map { sessionTimeZone -> sessionTimeZone to run(rendered, query.groupBys.size, sessionTimeZone) }
        results.forEach { (sessionTimeZone, rows) ->
            assertEquals(results.first().second, rows, "session $sessionTimeZone differs from ${results.first().first}:\n${rendered.sql}")
        }
        return results.first().second
    }

    private fun run(rendered: SemanticMetricFlowRenderResult.Rendered, groupByCount: Int, sessionTimeZone: String): Map<List<String>, BigDecimal?> =
        openFixture().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET TimeZone = '$sessionTimeZone'")
                statement.executeQuery("SELECT current_setting('TimeZone'), version()").use { result ->
                    assertTrue(result.next())
                    assertEquals(sessionTimeZone, result.getString(1))
                    assertEquals("v1.5.4", result.getString(2))
                }
                statement.executeQuery(rendered.sql).use { result ->
                    val metadata = result.metaData
                    buildMap {
                        while (result.next()) {
                            val bucket = (1..groupByCount).map { column ->
                                when (metadata.getColumnTypeName(column)) {
                                    "DATE" -> result.getObject(column, LocalDate::class.java).toString()
                                    "TIMESTAMP" -> result.getObject(column, LocalDateTime::class.java).toString()
                                    else -> result.getString(column)
                                }
                            }
                            put(bucket, result.getBigDecimal(groupByCount + 1))
                        }
                    }
                }
            }
        }

    /** A fresh in-memory sample-style database: no external access, extensions, or persistent state. */
    private fun openFixture(): Connection {
        val properties = Properties().apply {
            setProperty("enable_external_access", "false")
            setProperty("autoinstall_known_extensions", "false")
            setProperty("autoload_known_extensions", "false")
            setProperty("allow_community_extensions", "false")
            setProperty("extension_directory", "disabled")
            setProperty("threads", "1")
        }
        val connection = DriverManager.getConnection("jdbc:duckdb:", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE SCHEMA $schema;
                CREATE TABLE $schema.users (user_id BIGINT PRIMARY KEY, region VARCHAR NOT NULL);
                CREATE TABLE $schema.purchases (
                    purchase_id BIGINT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    purchased_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    amount DECIMAL(18, 2) NOT NULL
                );
                CREATE TABLE $schema.visits (
                    visit_id BIGINT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    visited_at TIMESTAMP NOT NULL
                );
                CREATE TABLE $schema.events (event_id BIGINT PRIMARY KEY, "Started_At" TIMESTAMP WITH TIME ZONE NOT NULL);
                CREATE TABLE $schema.calendar (date_day DATE PRIMARY KEY);
                INSERT INTO $schema.users VALUES (1, 'KR'), (2, 'US');
                INSERT INTO $schema.purchases VALUES
                    (1, 1, TIMESTAMPTZ '2026-07-31 15:30:00+00', 100),
                    (2, 2, TIMESTAMPTZ '2026-07-31 14:30:00+00', 10),
                    (3, 1, TIMESTAMPTZ '2026-08-02 15:30:00+00', 1000),
                    (4, 2, TIMESTAMPTZ '2026-08-10 23:30:00-05', 5),
                    (5, 2, TIMESTAMPTZ '2026-08-11 08:00:00+09', 7);
                INSERT INTO $schema.visits VALUES
                    (1, 1, TIMESTAMP '2026-08-01 15:10:00'),
                    (2, 2, TIMESTAMP '2026-07-30 14:00:00');
                INSERT INTO $schema.events VALUES
                    (1, TIMESTAMPTZ '2026-08-01 14:30:00+00'),
                    (2, TIMESTAMPTZ '2026-08-01 15:10:00+00'),
                    (3, TIMESTAMPTZ '2026-08-01 15:50:00+00');
                INSERT INTO $schema.calendar
                    SELECT CAST(day AS DATE) FROM generate_series(DATE '2026-06-01', DATE '2026-09-30', INTERVAL 1 DAY) AS days(day);
                """.trimIndent(),
            )
        }
        return connection
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
            emptyList(), null, emptyMap(),
        )
        val purchases = model(
            PURCHASES, "purchases",
            listOf(entity("purchase_id", SemanticEntityKind.PRIMARY), entity("user_id", SemanticEntityKind.FOREIGN)),
            listOf(timeDimension(PURCHASED_AT, "purchased_at", SemanticTimeGranularity.DAY)),
            listOf(measure("revenue", SemanticMeasureAggregation.SUM, "amount"), measure("purchase_count", SemanticMeasureAggregation.COUNT, "1")),
            PURCHASED_AT,
            mapOf(PURCHASED_AT to instant("purchased_at", SemanticInstantStorage.ZONED)),
        )
        val visits = model(
            VISITS, "visits",
            listOf(entity("visit_id", SemanticEntityKind.PRIMARY), entity("user_id", SemanticEntityKind.FOREIGN)),
            listOf(timeDimension(VISITED_AT, "visited_at", SemanticTimeGranularity.DAY)),
            listOf(measure("visit_count", SemanticMeasureAggregation.COUNT, "1")),
            VISITED_AT,
            mapOf(VISITED_AT to instant("visited_at", SemanticInstantStorage.UTC_NAIVE)),
        )
        val events = model(
            EVENTS, "events",
            listOf(entity("event_id", SemanticEntityKind.PRIMARY)),
            listOf(timeDimension(STARTED_AT, "started_at", SemanticTimeGranularity.HOUR)),
            listOf(measure("event_count", SemanticMeasureAggregation.COUNT, "1")),
            STARTED_AT,
            mapOf(STARTED_AT to instant("started_at", SemanticInstantStorage.ZONED)),
        )
        val metrics = listOf(
            simple("purchase_revenue", PURCHASES, "revenue"),
            simple("purchases_made", PURCHASES, "purchase_count"),
            simple("visits_made", VISITS, "visit_count"),
            simple("events_started", EVENTS, "event_count"),
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
            dialect = SemanticSqlDialect.DUCKDB,
            models = listOf(users, purchases, visits, events).associateBy { it.definition.name },
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
        timeDimensions: Map<SemanticElementName, SemanticMetricFlowTimeDimension>,
    ): SemanticMetricFlowModel {
        val definition = SemanticModelDefinition(
            3u, name, null, null, listOf(SemanticSourceRelation(connectorId, null, schema, relation)),
            aggregationTimeDimension, entities, dimensions, measures,
        )
        return SemanticMetricFlowModel(definition, CatalogPhysicalAddress(null, schema, relation, null), timeDimensions)
    }

    private fun instant(column: String, storage: SemanticInstantStorage) =
        SemanticMetricFlowTimeDimension(SemanticTimeKind.INSTANT, storage, SemanticTimeOperand.Column(column, false, null))

    private fun entity(name: String, kind: SemanticEntityKind) =
        SemanticEntityDefinition(SemanticElementName(name), kind, name, null, null)

    private fun timeDimension(name: SemanticElementName, expression: String, granularity: SemanticTimeGranularity) =
        SemanticDimensionDefinition(name, SemanticDimensionKind.TIME, expression, granularity, null, null, false, null)

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
        val EVENTS = SemanticElementName("events")
        val PURCHASED_AT = SemanticElementName("purchased_at")
        val VISITED_AT = SemanticElementName("visited_at")
        val STARTED_AT = SemanticElementName("started_at")
    }
}
