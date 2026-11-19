package cc.monomer.semantic.adapter.outbound.metricflow

import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.metricflow.application.engine.MetricFlowExplainRequest
import cc.monomer.semantic.api.MetricId
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
import cc.monomer.semantic.api.SemanticMetricTimeRange
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticQueryFilter
import cc.monomer.semantic.api.SemanticQuerySpec
import cc.monomer.semantic.api.SemanticSourceRelation
import cc.monomer.semantic.api.SemanticSqlDialect
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.api.SemanticTimeKind
import cc.monomer.semantic.api.SemanticTimeRangeEndpoint
import cc.monomer.semantic.api.SemanticTimestampFilterOperator
import cc.monomer.semantic.application.SemanticMetricFlowManifest
import cc.monomer.semantic.application.SemanticMetricFlowModel
import cc.monomer.semantic.application.SemanticMetricFlowRenderResult
import cc.monomer.semantic.application.SemanticTimeDimensionResolution
import cc.monomer.semantic.application.timeDimensionResolutions
import cc.monomer.semantic.catalogWithTypedColumns
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Seven-dialect evidence that Semantic SQL reads time values in the Workspace Reporting time zone. */
class MetricFlowReportingTimeZoneTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun `instant dimensions convert to Reporting time zone dates or wall clocks while dates and local times stay unchanged`() {
        SemanticSqlDialect.entries.forEach { dialect ->
            val types = CATALOG_TYPES.getValue(dialect)
            data class Case(val label: String, val nativeType: String, val declared: SemanticTimeKind?, val grain: SemanticTimeGranularity, val expected: String)
            val cases = listOf(
                Case("date", types.date, null, SemanticTimeGranularity.DAY, "ordered_at"),
                Case("declared date", types.date, SemanticTimeKind.DATE, SemanticTimeGranularity.MONTH, "ordered_at"),
                Case("local day", types.naive, null, SemanticTimeGranularity.DAY, "ordered_at"),
                Case("local hour", types.naive, SemanticTimeKind.LOCAL, SemanticTimeGranularity.HOUR, "ordered_at"),
                Case("zoned day", types.zoned, null, SemanticTimeGranularity.DAY, ZONED_DATE.getValue(dialect)),
                Case("zoned week", types.zoned, SemanticTimeKind.INSTANT, SemanticTimeGranularity.WEEK, ZONED_DATE.getValue(dialect)),
                Case("zoned hour", types.zoned, null, SemanticTimeGranularity.HOUR, ZONED_WALL_CLOCK.getValue(dialect)),
                Case("UTC naive day", types.naive, SemanticTimeKind.INSTANT, SemanticTimeGranularity.DAY, UTC_NAIVE_DATE.getValue(dialect)),
                Case("UTC naive minute", types.naive, SemanticTimeKind.INSTANT, SemanticTimeGranularity.MINUTE, UTC_NAIVE_WALL_CLOCK.getValue(dialect)),
            )
            cases.forEach { case ->
                val manifest = manifest(dialect, seoul, listOf(timeDimension("ordered_at", "ordered_at", case.grain, case.declared)), listOf(columnName(dialect) to case.nativeType))
                assertEquals(case.expected, engineExpression(manifest, "ordered_at"), "$dialect ${case.label}")
                val rendered = render(manifest, query(listOf(SemanticGroupBy.MetricTime(case.grain), SemanticGroupBy.Dimension(ORDERS, ORDERED_AT, case.grain))))
                assertTrue(case.expected in rendered.sql, "$dialect ${case.label}: ${rendered.sql}")
                listOf(0, 1).forEach { index ->
                    assertEquals(case.grain >= SemanticTimeGranularity.DAY, projection(rendered.sql, dialect, index).isCalendarCast(), "$dialect ${case.label}: ${rendered.sql}")
                }
            }
        }
    }

    @Test
    fun `UTC is converted like every other Reporting time zone`() {
        val expected = mapOf(
            SemanticSqlDialect.POSTGRESQL to "CAST((\"ordered_at\" AT TIME ZONE 'UTC') AS DATE)",
            SemanticSqlDialect.REDSHIFT to "CAST((\"ordered_at\" AT TIME ZONE 'UTC') AS DATE)",
            SemanticSqlDialect.SNOWFLAKE to "CAST(CONVERT_TIMEZONE('UTC', \"ORDERED_AT\") AS DATE)",
            SemanticSqlDialect.BIGQUERY to "DATE(`ordered_at`, 'UTC')",
            SemanticSqlDialect.DATABRICKS to "CAST(convert_timezone('UTC', 'UTC', timestampadd(MICROSECOND, unix_micros(`ordered_at`), TIMESTAMP_NTZ'1970-01-01 00:00:00')) AS DATE)",
            SemanticSqlDialect.TRINO to "CAST(\"ordered_at\" AT TIME ZONE 'UTC' AS date)",
            SemanticSqlDialect.DUCKDB to "CAST(timezone('UTC', \"ordered_at\") AS DATE)",
        )
        SemanticSqlDialect.entries.forEach { dialect ->
            val manifest = manifest(dialect, ZoneId.of("UTC"), listOf(timeDimension("ordered_at", "ordered_at", SemanticTimeGranularity.DAY, null)), listOf(columnName(dialect) to CATALOG_TYPES.getValue(dialect).zoned))
            assertEquals(expected.getValue(dialect), engineExpression(manifest, "ordered_at"), dialect.name)
        }
    }

    @Test
    fun `calendar grain results are cast to DATE in the final projection while finer grains stay wall clock`() {
        SemanticSqlDialect.entries.forEach { dialect ->
            val manifest = manifest(
                dialect, seoul,
                listOf(timeDimension("ordered_at", "ordered_at", SemanticTimeGranularity.HOUR, null)),
                listOf(columnName(dialect) to CATALOG_TYPES.getValue(dialect).zoned),
            )
            val groupBys = listOf(
                SemanticGroupBy.MetricTime(SemanticTimeGranularity.WEEK),
                SemanticGroupBy.Dimension(ORDERS, ORDERED_AT, SemanticTimeGranularity.HOUR),
                SemanticGroupBy.Dimension(ORDERS, ORDERED_AT, SemanticTimeGranularity.MONTH),
            )
            val sql = render(manifest, query(groupBys)).sql
            assertTrue(projection(sql, dialect, 0).isCalendarCast(), "$dialect: $sql")
            assertTrue(!projection(sql, dialect, 1).isCalendarCast(), "$dialect: $sql")
            assertTrue(projection(sql, dialect, 2).isCalendarCast(), "$dialect: $sql")
            assertTrue(!projection(sql, dialect, 3).isCalendarCast(), "$dialect: $sql")
        }
    }

    @Test
    fun `user expressions convert as zoned values and Trino keeps the source column precision`() {
        val expression = "ordered_at + INTERVAL '1' HOUR"
        val instant = timeDimension("shifted_at", expression, SemanticTimeGranularity.HOUR, SemanticTimeKind.INSTANT)
        val expected = mapOf(
            SemanticSqlDialect.POSTGRESQL to "(($expression) AT TIME ZONE 'Asia/Seoul')",
            SemanticSqlDialect.REDSHIFT to "(($expression) AT TIME ZONE 'Asia/Seoul')",
            SemanticSqlDialect.SNOWFLAKE to "CONVERT_TIMEZONE('Asia/Seoul', ($expression))::TIMESTAMP_NTZ",
            SemanticSqlDialect.BIGQUERY to "DATETIME(($expression), 'Asia/Seoul')",
            SemanticSqlDialect.DATABRICKS to "convert_timezone('UTC', 'Asia/Seoul', timestampadd(MICROSECOND, unix_micros(($expression)), TIMESTAMP_NTZ'1970-01-01 00:00:00'))",
            SemanticSqlDialect.TRINO to "CAST(($expression) AT TIME ZONE 'Asia/Seoul' AS timestamp(12))",
            SemanticSqlDialect.DUCKDB to "timezone('Asia/Seoul', ($expression))",
        )
        SemanticSqlDialect.entries.forEach { dialect ->
            val manifest = manifest(dialect, seoul, listOf(instant), emptyList())
            assertEquals(expected.getValue(dialect), engineExpression(manifest, "shifted_at"), dialect.name)
        }
        listOf(
            "timestamp(3) with time zone" to "CAST(\"ordered_at\" AT TIME ZONE 'Asia/Seoul' AS timestamp(3))",
            "timestamp with time zone" to "CAST(\"ordered_at\" AT TIME ZONE 'Asia/Seoul' AS timestamp(3))",
            "timestamp(12) with time zone" to "CAST(\"ordered_at\" AT TIME ZONE 'Asia/Seoul' AS timestamp(12))",
        ).forEach { (nativeType, wallClock) ->
            val manifest = manifest(SemanticSqlDialect.TRINO, seoul, listOf(timeDimension("ordered_at", "ordered_at", SemanticTimeGranularity.SECOND, null)), listOf("ordered_at" to nativeType))
            assertEquals(wallClock, engineExpression(manifest, "ordered_at"), nativeType)
        }
        val day = manifest(SemanticSqlDialect.TRINO, seoul, listOf(timeDimension("ordered_at", "ordered_at", SemanticTimeGranularity.DAY, null)), listOf("ordered_at" to "timestamp(9) with time zone"))
        assertEquals("CAST(\"ordered_at\" AT TIME ZONE 'Asia/Seoul' AS date)", engineExpression(day, "ordered_at"))
    }

    @Test
    fun `wrapped plain columns are quoted with each warehouse's identifier rules including reserved words`() {
        data class Case(val dialect: SemanticSqlDialect, val expression: String, val catalogColumn: String, val quoted: String)
        listOf(
            Case(SemanticSqlDialect.POSTGRESQL, "order", "order", "\"order\""),
            Case(SemanticSqlDialect.POSTGRESQL, "  Ordered_At ", "ordered_at", "\"ordered_at\""),
            Case(SemanticSqlDialect.POSTGRESQL, "\"Order\"\"Time\"", "Order\"Time", "\"Order\"\"Time\""),
            Case(SemanticSqlDialect.REDSHIFT, "ORDER", "order", "\"order\""),
            Case(SemanticSqlDialect.SNOWFLAKE, "order", "ORDER", "\"ORDER\""),
            Case(SemanticSqlDialect.SNOWFLAKE, "\"Order\"", "Order", "\"Order\""),
            Case(SemanticSqlDialect.BIGQUERY, "order", "order", "`order`"),
            Case(SemanticSqlDialect.BIGQUERY, "`order\\`time`", "order`time", "`order\\`time`"),
            Case(SemanticSqlDialect.DATABRICKS, "`order``time`", "order`time", "`order``time`"),
            Case(SemanticSqlDialect.DATABRICKS, "Order", "order", "`Order`"),
            Case(SemanticSqlDialect.TRINO, "ORDER", "order", "\"order\""),
            Case(SemanticSqlDialect.DUCKDB, "Order", "order", "\"Order\""),
            Case(SemanticSqlDialect.DUCKDB, "\"Order\"\"Time\"", "order\"time", "\"Order\"\"Time\""),
        ).forEach { case ->
            val manifest = manifest(
                case.dialect, seoul,
                listOf(timeDimension("ordered_at", case.expression, SemanticTimeGranularity.DAY, null)),
                listOf(case.catalogColumn to CATALOG_TYPES.getValue(case.dialect).zoned),
            )
            val expression = engineExpression(manifest, "ordered_at")
            assertTrue(case.quoted in expression && "Asia/Seoul" in expression, "$case: $expression")
            render(manifest, query(listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.DAY))))
        }
    }

    @Test
    fun `metric time ranges read TIMESTAMP endpoints as Reporting time zone wall clocks and keep DATE endpoints`() {
        data class Case(val zone: ZoneId, val start: String, val end: String, val expectedStart: String, val expectedEnd: String)
        listOf(
            Case(seoul, "2026-09-01T15:00:00Z", "2026-09-10T14:59:59.123456Z", "2026-09-02T00:00", "2026-09-10T23:59:59.123456"),
            Case(newYork, "2026-09-01T04:00:00Z", "2026-09-10T12:34:56Z", "2026-09-01T00:00", "2026-09-10T08:34:56"),
            Case(ZoneId.of("UTC"), "2026-09-01T09:30:01.5+09:00", "2026-09-10T00:00:00Z", "2026-09-01T00:30:01.500", "2026-09-10T00:00"),
        ).forEach { case ->
            SemanticSqlDialect.entries.forEach { dialect ->
                val manifest = manifest(dialect, case.zone, listOf(timeDimension("ordered_at", "ordered_at", SemanticTimeGranularity.DAY, null)), listOf(columnName(dialect) to CATALOG_TYPES.getValue(dialect).zoned))
                val captured = AtomicReference<MetricFlowExplainRequest>()
                render(manifest, query(emptyList()).copy(metricTimeRange = SemanticMetricTimeRange(
                    SemanticTimeRangeEndpoint.Timestamp(Instant.parse(case.start)),
                    SemanticTimeRangeEndpoint.Timestamp(Instant.parse(case.end)),
                )), captured)
                assertEquals(case.expectedStart, captured.get().timeConstraintStart, "$dialect ${case.zone}")
                assertEquals(case.expectedEnd, captured.get().timeConstraintEnd, "$dialect ${case.zone}")
            }
        }
        val dates = manifest(SemanticSqlDialect.POSTGRESQL, seoul, listOf(timeDimension("ordered_at", "ordered_at", SemanticTimeGranularity.DAY, null)), listOf("ordered_at" to "timestamp with time zone"))
        val captured = AtomicReference<MetricFlowExplainRequest>()
        val sql = render(dates, query(emptyList()).copy(metricTimeRange = SemanticMetricTimeRange(
            SemanticTimeRangeEndpoint.Date(LocalDate.parse("2026-09-01")),
            SemanticTimeRangeEndpoint.Date(LocalDate.parse("2026-09-10")),
        )), captured).sql
        assertEquals("2026-09-01", captured.get().timeConstraintStart)
        assertEquals("2026-09-10", captured.get().timeConstraintEnd)
        assertTrue("CAST((\"ordered_at\" AT TIME ZONE 'Asia/Seoul') AS DATE)" in sql, sql)
    }

    @Test
    fun `TIMESTAMP filters compare Reporting time zone wall clocks as typed literals without a time zone`() {
        val literals = mapOf(
            SemanticSqlDialect.POSTGRESQL to "TIMESTAMP '%s'",
            SemanticSqlDialect.REDSHIFT to "TIMESTAMP '%s'",
            SemanticSqlDialect.TRINO to "TIMESTAMP '%s'",
            SemanticSqlDialect.BIGQUERY to "DATETIME '%s'",
            SemanticSqlDialect.SNOWFLAKE to "'%s'::TIMESTAMP_NTZ",
            SemanticSqlDialect.DATABRICKS to "TIMESTAMP_NTZ '%s'",
            SemanticSqlDialect.DUCKDB to "TIMESTAMP '%s'",
        )
        data class Case(val zone: ZoneId, val value: String, val wallClock: String)
        val cases = listOf(
            Case(seoul, "2026-08-01T00:00:00Z", "2026-08-01 09:00:00"),
            Case(seoul, "2026-09-30T15:00:00.123456Z", "2026-10-01 00:00:00.123456"),
            Case(newYork, "2026-08-01T04:00:00Z", "2026-08-01 00:00:00"),
            Case(newYork, "2026-12-01T05:00:00.1Z", "2026-12-01 00:00:00.1"),
            Case(ZoneId.of("UTC"), "1900-01-01T09:00:01+09:00", "1900-01-01 00:00:01"),
        )
        SemanticSqlDialect.entries.forEach { dialect ->
            cases.forEach { case ->
                val manifest = manifest(dialect, case.zone, listOf(timeDimension("ordered_at", "ordered_at", SemanticTimeGranularity.HOUR, null)), listOf(columnName(dialect) to CATALOG_TYPES.getValue(dialect).zoned))
                val literal = literals.getValue(dialect).format(case.wallClock)
                val captured = AtomicReference<MetricFlowExplainRequest>()
                val sql = render(manifest, query(emptyList()).copy(filters = listOf(
                    SemanticQueryFilter.Timestamp(ORDERED_AT_REFERENCE, SemanticTimestampFilterOperator.GREATER_THAN_OR_EQUAL, listOf(Instant.parse(case.value))),
                )), captured).sql
                assertEquals(listOf("{{ Dimension('order_id__ordered_at__hour') }} >= $literal"), captured.get().whereConstraints, "$dialect ${case.zone}")
                assertTrue("order_id__ordered_at__hour >= $literal" in sql, "$dialect: $sql")
            }
        }
    }

    @Test
    fun `the repeated New York hour compares wall clocks so an instant range across it can be empty`() {
        val manifest = manifest(SemanticSqlDialect.POSTGRESQL, newYork, listOf(timeDimension("ordered_at", "ordered_at", SemanticTimeGranularity.MINUTE, null)), listOf("ordered_at" to "timestamp with time zone"))
        val earlier = Instant.parse("2026-11-01T05:45:00Z")
        val later = Instant.parse("2026-11-01T06:15:00Z")
        val captured = AtomicReference<MetricFlowExplainRequest>()
        render(manifest, query(emptyList()).copy(filters = listOf(
            SemanticQueryFilter.Timestamp(ORDERED_AT_REFERENCE, SemanticTimestampFilterOperator.GREATER_THAN_OR_EQUAL, listOf(earlier)),
            SemanticQueryFilter.Timestamp(ORDERED_AT_REFERENCE, SemanticTimestampFilterOperator.LESS_THAN, listOf(later)),
            SemanticQueryFilter.Timestamp(ORDERED_AT_REFERENCE, SemanticTimestampFilterOperator.IN, listOf(earlier, later)),
        )), captured)
        assertEquals(
            listOf(
                "{{ Dimension('order_id__ordered_at__minute') }} >= TIMESTAMP '2026-11-01 01:45:00'",
                "{{ Dimension('order_id__ordered_at__minute') }} < TIMESTAMP '2026-11-01 01:15:00'",
                "{{ Dimension('order_id__ordered_at__minute') }} IN (TIMESTAMP '2026-11-01 01:45:00', TIMESTAMP '2026-11-01 01:15:00')",
            ),
            captured.get().whereConstraints,
        )
        render(manifest, query(listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.MINUTE))).copy(metricTimeRange = SemanticMetricTimeRange(
            SemanticTimeRangeEndpoint.Timestamp(earlier), SemanticTimeRangeEndpoint.Timestamp(later),
        )), captured)
        assertEquals("2026-11-01T01:45", captured.get().timeConstraintStart)
        assertEquals("2026-11-01T01:15", captured.get().timeConstraintEnd)
    }

    @Test
    fun `the missing New York hour never produces a nonexistent wall clock and renders without error`() {
        val manifest = manifest(SemanticSqlDialect.POSTGRESQL, newYork, listOf(timeDimension("ordered_at", "ordered_at", SemanticTimeGranularity.HOUR, null)), listOf("ordered_at" to "timestamp with time zone"))
        val beforeGap = Instant.parse("2026-03-08T06:59:59Z")
        val afterGap = Instant.parse("2026-03-08T07:00:00Z")
        val captured = AtomicReference<MetricFlowExplainRequest>()
        render(manifest, query(listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.HOUR))).copy(
            metricTimeRange = SemanticMetricTimeRange(SemanticTimeRangeEndpoint.Timestamp(beforeGap), SemanticTimeRangeEndpoint.Timestamp(afterGap)),
            filters = listOf(
                SemanticQueryFilter.Timestamp(ORDERED_AT_REFERENCE, SemanticTimestampFilterOperator.GREATER_THAN_OR_EQUAL, listOf(beforeGap)),
                SemanticQueryFilter.Timestamp(ORDERED_AT_REFERENCE, SemanticTimestampFilterOperator.LESS_THAN_OR_EQUAL, listOf(afterGap)),
            ),
        ), captured)
        assertEquals("2026-03-08T01:59:59", captured.get().timeConstraintStart)
        assertEquals("2026-03-08T03:00", captured.get().timeConstraintEnd)
        assertEquals(
            listOf(
                "{{ Dimension('order_id__ordered_at__hour') }} >= TIMESTAMP '2026-03-08 01:59:59'",
                "{{ Dimension('order_id__ordered_at__hour') }} <= TIMESTAMP '2026-03-08 03:00:00'",
            ),
            captured.get().whereConstraints,
        )
    }

    private fun projection(sql: String, dialect: SemanticSqlDialect, index: Int): String {
        val quote = if (dialect == SemanticSqlDialect.BIGQUERY || dialect == SemanticSqlDialect.DATABRICKS) "`" else "\""
        return sql.lineSequence().single { "AS ${quote}__monomer_result_$index$quote" in it }
    }

    private fun String.isCalendarCast(): Boolean = Regex("^\\s*,?\\s*CAST\\(.* AS DATE\\) AS \\S+$").matches(this)

    private fun columnName(dialect: SemanticSqlDialect): String =
        if (dialect == SemanticSqlDialect.SNOWFLAKE) "ORDERED_AT" else "ordered_at"

    private fun engineExpression(manifest: SemanticMetricFlowManifest, dimension: String): String =
        requireNotNull(
            MetricFlowManifestMapper.toEngineManifest(manifest).semanticModels.single()
                .dimensions.single { it.name == dimension }.expr,
        )

    private fun render(
        manifest: SemanticMetricFlowManifest,
        query: SemanticQuerySpec,
        captured: AtomicReference<MetricFlowExplainRequest> = AtomicReference(),
    ): SemanticMetricFlowRenderResult.Rendered {
        val adapter = MetricFlowSemanticRenderer(MetricFlowExplainQuery { engine, request ->
            captured.set(request)
            engine.explain(request)
        })
        val result = adapter.render(manifest, query, manifest.metrics.keys.toList())
        return assertIs<SemanticMetricFlowRenderResult.Rendered>(result, "${manifest.dialect}: $result")
    }

    private fun query(groupBys: List<SemanticGroupBy>) = SemanticQuerySpec(
        metrics = listOf(METRIC_NAME), groupBys = groupBys, metricTimeRange = null,
        filters = emptyList(), orderBy = emptyList(), rowLimit = 10,
    )

    private fun timeDimension(name: String, expression: String, grain: SemanticTimeGranularity, declared: SemanticTimeKind?) =
        SemanticDimensionDefinition(SemanticElementName(name), SemanticDimensionKind.TIME, expression, grain, null, null, false, declared)

    private fun manifest(
        dialect: SemanticSqlDialect,
        zone: ZoneId,
        timeDimensions: List<SemanticDimensionDefinition>,
        columns: List<Pair<String, String>>,
    ): SemanticMetricFlowManifest {
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val definition = SemanticModelDefinition(
            schemaVersion = 3u, name = ORDERS, label = null, description = null,
            sourceRelations = listOf(SemanticSourceRelation(connector, null, "analytics", "orders")),
            aggregationTimeDimension = timeDimensions.first().name,
            entities = listOf(SemanticEntityDefinition(SemanticElementName("order_id"), SemanticEntityKind.PRIMARY, "order_id", null, null)),
            dimensions = timeDimensions,
            measures = listOf(SemanticMeasureDefinition(SemanticElementName("revenue"), SemanticMeasureAggregation.SUM, "revenue", null, null, null, null)),
        )
        val catalog = catalogWithTypedColumns(workspace, connector, mapOf("orders" to columns))
        val relation = catalog.objects.single { it.kind == CatalogObjectKind.RELATION && it.address.objectName == "orders" }
        val resolved = timeDimensionResolutions(definition, relation, catalog, dialect).mapValues { (_, resolution) ->
            assertIs<SemanticTimeDimensionResolution.Resolved>(resolution).timeDimension
        }
        val metric = SemanticMetricDefinition.Simple(3u, METRIC_NAME, null, null, null, emptyList(), ORDERS, SemanticElementName("revenue"), null)
        return SemanticMetricFlowManifest(
            connectorId = connector,
            dialect = dialect,
            models = mapOf(ORDERS to SemanticMetricFlowModel(definition, relation.address, resolved)),
            metrics = mapOf(MetricId.generate() to metric),
            timeSpines = emptyList(),
            reportingTimeZone = zone,
        )
    }

    private data class CatalogTypes(val date: String, val naive: String, val zoned: String)

    private companion object {
        val ORDERS = SemanticElementName("orders")
        val ORDERED_AT = SemanticElementName("ordered_at")
        val ORDERED_AT_REFERENCE = SemanticElementReference(ORDERS, ORDERED_AT)
        val METRIC_NAME = SemanticElementName("revenue_metric")
        const val DATABRICKS_UTC_WALL_CLOCK = "timestampadd(MICROSECOND, unix_micros(`ordered_at`), TIMESTAMP_NTZ'1970-01-01 00:00:00')"

        val CATALOG_TYPES = mapOf(
            SemanticSqlDialect.POSTGRESQL to CatalogTypes("date", "timestamp without time zone", "timestamp with time zone"),
            SemanticSqlDialect.REDSHIFT to CatalogTypes("date", "timestamp without time zone", "timestamp with time zone"),
            SemanticSqlDialect.SNOWFLAKE to CatalogTypes("DATE", "TIMESTAMP_NTZ", "TIMESTAMP_TZ"),
            SemanticSqlDialect.BIGQUERY to CatalogTypes("DATE", "DATETIME", "TIMESTAMP"),
            SemanticSqlDialect.DATABRICKS to CatalogTypes("DATE", "TIMESTAMP_NTZ", "TIMESTAMP"),
            SemanticSqlDialect.TRINO to CatalogTypes("date", "timestamp(9)", "timestamp(6) with time zone"),
            SemanticSqlDialect.DUCKDB to CatalogTypes("DATE", "TIMESTAMP", "TIMESTAMP WITH TIME ZONE"),
        )
        val ZONED_DATE = mapOf(
            SemanticSqlDialect.POSTGRESQL to "CAST((\"ordered_at\" AT TIME ZONE 'Asia/Seoul') AS DATE)",
            SemanticSqlDialect.REDSHIFT to "CAST((\"ordered_at\" AT TIME ZONE 'Asia/Seoul') AS DATE)",
            SemanticSqlDialect.SNOWFLAKE to "CAST(CONVERT_TIMEZONE('Asia/Seoul', \"ORDERED_AT\") AS DATE)",
            SemanticSqlDialect.BIGQUERY to "DATE(`ordered_at`, 'Asia/Seoul')",
            SemanticSqlDialect.DATABRICKS to "CAST(convert_timezone('UTC', 'Asia/Seoul', $DATABRICKS_UTC_WALL_CLOCK) AS DATE)",
            SemanticSqlDialect.TRINO to "CAST(\"ordered_at\" AT TIME ZONE 'Asia/Seoul' AS date)",
            SemanticSqlDialect.DUCKDB to "CAST(timezone('Asia/Seoul', \"ordered_at\") AS DATE)",
        )
        val ZONED_WALL_CLOCK = mapOf(
            SemanticSqlDialect.POSTGRESQL to "(\"ordered_at\" AT TIME ZONE 'Asia/Seoul')",
            SemanticSqlDialect.REDSHIFT to "(\"ordered_at\" AT TIME ZONE 'Asia/Seoul')",
            SemanticSqlDialect.SNOWFLAKE to "CONVERT_TIMEZONE('Asia/Seoul', \"ORDERED_AT\")::TIMESTAMP_NTZ",
            SemanticSqlDialect.BIGQUERY to "DATETIME(`ordered_at`, 'Asia/Seoul')",
            SemanticSqlDialect.DATABRICKS to "convert_timezone('UTC', 'Asia/Seoul', $DATABRICKS_UTC_WALL_CLOCK)",
            SemanticSqlDialect.TRINO to "CAST(\"ordered_at\" AT TIME ZONE 'Asia/Seoul' AS timestamp(6))",
            SemanticSqlDialect.DUCKDB to "timezone('Asia/Seoul', \"ordered_at\")",
        )
        val UTC_NAIVE_DATE = mapOf(
            SemanticSqlDialect.POSTGRESQL to "CAST(((\"ordered_at\" AT TIME ZONE 'UTC') AT TIME ZONE 'Asia/Seoul') AS DATE)",
            SemanticSqlDialect.REDSHIFT to "CAST(CONVERT_TIMEZONE('UTC', 'Asia/Seoul', \"ordered_at\") AS DATE)",
            SemanticSqlDialect.SNOWFLAKE to "CAST(CONVERT_TIMEZONE('UTC', 'Asia/Seoul', \"ORDERED_AT\") AS DATE)",
            SemanticSqlDialect.BIGQUERY to "DATE(TIMESTAMP(`ordered_at`, 'UTC'), 'Asia/Seoul')",
            SemanticSqlDialect.DATABRICKS to "CAST(convert_timezone('UTC', 'Asia/Seoul', `ordered_at`) AS DATE)",
            SemanticSqlDialect.TRINO to "CAST(with_timezone(\"ordered_at\", 'UTC') AT TIME ZONE 'Asia/Seoul' AS date)",
            SemanticSqlDialect.DUCKDB to "CAST(timezone('Asia/Seoul', timezone('UTC', \"ordered_at\")) AS DATE)",
        )
        val UTC_NAIVE_WALL_CLOCK = mapOf(
            SemanticSqlDialect.POSTGRESQL to "((\"ordered_at\" AT TIME ZONE 'UTC') AT TIME ZONE 'Asia/Seoul')",
            SemanticSqlDialect.REDSHIFT to "CONVERT_TIMEZONE('UTC', 'Asia/Seoul', \"ordered_at\")",
            SemanticSqlDialect.SNOWFLAKE to "CONVERT_TIMEZONE('UTC', 'Asia/Seoul', \"ORDERED_AT\")",
            SemanticSqlDialect.BIGQUERY to "DATETIME(TIMESTAMP(`ordered_at`, 'UTC'), 'Asia/Seoul')",
            SemanticSqlDialect.DATABRICKS to "convert_timezone('UTC', 'Asia/Seoul', `ordered_at`)",
            SemanticSqlDialect.TRINO to "CAST(with_timezone(\"ordered_at\", 'UTC') AT TIME ZONE 'Asia/Seoul' AS timestamp(9))",
            SemanticSqlDialect.DUCKDB to "timezone('Asia/Seoul', timezone('UTC', \"ordered_at\"))",
        )
    }
}
