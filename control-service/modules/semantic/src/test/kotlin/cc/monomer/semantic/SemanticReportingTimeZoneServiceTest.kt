package cc.monomer.semantic

import cc.monomer.catalog.api.CatalogCurrentObjectSet
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.adapter.outbound.metricflow.MetricFlowSemanticRenderer
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.SemanticCheckDraft
import cc.monomer.semantic.api.SemanticCheckProblem
import cc.monomer.semantic.api.SemanticCheckProblemCode
import cc.monomer.semantic.api.SemanticCheckRequest
import cc.monomer.semantic.api.SemanticCheckResult
import cc.monomer.semantic.api.SemanticDateFilterOperator
import cc.monomer.semantic.api.SemanticDefinitionReference
import cc.monomer.semantic.api.SemanticDimensionDefinition
import cc.monomer.semantic.api.SemanticDimensionKind
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticElementReference
import cc.monomer.semantic.api.SemanticEntityDefinition
import cc.monomer.semantic.api.SemanticEntityKind
import cc.monomer.semantic.api.SemanticGroupBy
import cc.monomer.semantic.api.SemanticInvalidReason
import cc.monomer.semantic.api.SemanticInvalidReasonCode
import cc.monomer.semantic.api.SemanticMeasureAggregation
import cc.monomer.semantic.api.SemanticMeasureDefinition
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticModelId
import cc.monomer.semantic.api.SemanticQueryFilter
import cc.monomer.semantic.api.SemanticQuerySpec
import cc.monomer.semantic.api.SemanticRenderRequest
import cc.monomer.semantic.api.SemanticRenderResult
import cc.monomer.semantic.api.SemanticSourceRelation
import cc.monomer.semantic.api.SemanticSqlDialect
import cc.monomer.semantic.api.SemanticTextFilterOperator
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.api.SemanticTimeKind
import cc.monomer.semantic.api.SemanticTimeSpine
import cc.monomer.semantic.api.SemanticTimeSpineDefinition
import cc.monomer.semantic.api.SemanticTimeSpinePrimaryColumn
import cc.monomer.semantic.api.SemanticTimeSpineRelation
import cc.monomer.semantic.api.SemanticTimestampFilterOperator
import cc.monomer.semantic.application.SemanticCatalogTimeClass
import cc.monomer.semantic.application.SemanticClosureProjection
import cc.monomer.semantic.application.SemanticDefinitionCheckService
import cc.monomer.semantic.application.SemanticInstantStorage
import cc.monomer.semantic.application.SemanticMetricFlowTimeDimension
import cc.monomer.semantic.application.SemanticQueryResolution
import cc.monomer.semantic.application.SemanticQueryService
import cc.monomer.semantic.application.SemanticTimeDimensionResolution
import cc.monomer.semantic.application.SemanticTimeOperand
import cc.monomer.semantic.application.catalogTimeType
import cc.monomer.semantic.application.projectCurrentClosure
import cc.monomer.semantic.application.resolveQuery
import cc.monomer.semantic.application.timeDimensionResolutions
import cc.monomer.semantic.application.timeSpineColumnTypeAllowed
import cc.monomer.semantic.domain.CurrentMetric
import cc.monomer.semantic.domain.CurrentSemanticModel
import cc.monomer.semantic.domain.CurrentTimeSpineConfiguration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticReportingTimeZoneServiceTest {
    private val workspace = WorkspaceId.generate()
    private val connector = ConnectorId.generate()
    private val seoul = ZoneId.of("Asia/Seoul")

    @Test
    fun `Catalog native types map to date wall clock and instant classes per warehouse`() {
        data class Case(val dialect: SemanticSqlDialect, val nativeType: String, val timeClass: SemanticCatalogTimeClass?, val precision: Int?)
        listOf(
            Case(SemanticSqlDialect.POSTGRESQL, "date", SemanticCatalogTimeClass.DATE, null),
            Case(SemanticSqlDialect.POSTGRESQL, "timestamp without time zone", SemanticCatalogTimeClass.NAIVE, null),
            Case(SemanticSqlDialect.POSTGRESQL, "timestamp with time zone", SemanticCatalogTimeClass.ZONED, null),
            Case(SemanticSqlDialect.POSTGRESQL, "timestamptz", SemanticCatalogTimeClass.ZONED, null),
            Case(SemanticSqlDialect.REDSHIFT, "TIMESTAMP WITHOUT TIME ZONE", SemanticCatalogTimeClass.NAIVE, null),
            Case(SemanticSqlDialect.REDSHIFT, "timestamp with time zone", SemanticCatalogTimeClass.ZONED, null),
            Case(SemanticSqlDialect.SNOWFLAKE, "DATE", SemanticCatalogTimeClass.DATE, null),
            Case(SemanticSqlDialect.SNOWFLAKE, "TIMESTAMP_NTZ", SemanticCatalogTimeClass.NAIVE, null),
            Case(SemanticSqlDialect.SNOWFLAKE, "TIMESTAMP_LTZ", SemanticCatalogTimeClass.ZONED, null),
            Case(SemanticSqlDialect.SNOWFLAKE, "TIMESTAMP_TZ(9)", SemanticCatalogTimeClass.ZONED, 9),
            Case(SemanticSqlDialect.SNOWFLAKE, "TIMESTAMP", null, null),
            Case(SemanticSqlDialect.BIGQUERY, "DATE", SemanticCatalogTimeClass.DATE, null),
            Case(SemanticSqlDialect.BIGQUERY, "DATETIME", SemanticCatalogTimeClass.NAIVE, null),
            Case(SemanticSqlDialect.BIGQUERY, "TIMESTAMP", SemanticCatalogTimeClass.ZONED, null),
            Case(SemanticSqlDialect.DATABRICKS, "DATE", SemanticCatalogTimeClass.DATE, null),
            Case(SemanticSqlDialect.DATABRICKS, "TIMESTAMP_NTZ", SemanticCatalogTimeClass.NAIVE, null),
            Case(SemanticSqlDialect.DATABRICKS, "TIMESTAMP", SemanticCatalogTimeClass.ZONED, null),
            Case(SemanticSqlDialect.TRINO, "date", SemanticCatalogTimeClass.DATE, null),
            Case(SemanticSqlDialect.TRINO, "timestamp(9)", SemanticCatalogTimeClass.NAIVE, 9),
            Case(SemanticSqlDialect.TRINO, "timestamp(6) with time zone", SemanticCatalogTimeClass.ZONED, 6),
            Case(SemanticSqlDialect.TRINO, "timestamp with time zone", SemanticCatalogTimeClass.ZONED, 3),
            Case(SemanticSqlDialect.DUCKDB, "DATE", SemanticCatalogTimeClass.DATE, null),
            Case(SemanticSqlDialect.DUCKDB, "TIMESTAMP", SemanticCatalogTimeClass.NAIVE, null),
            Case(SemanticSqlDialect.DUCKDB, "TIMESTAMP_NS", SemanticCatalogTimeClass.NAIVE, null),
            Case(SemanticSqlDialect.DUCKDB, "TIMESTAMP_MS", SemanticCatalogTimeClass.NAIVE, null),
            Case(SemanticSqlDialect.DUCKDB, "TIMESTAMP_S", SemanticCatalogTimeClass.NAIVE, null),
            Case(SemanticSqlDialect.DUCKDB, "TIMESTAMP WITH TIME ZONE", SemanticCatalogTimeClass.ZONED, null),
            Case(SemanticSqlDialect.DUCKDB, "TIME", null, null),
            Case(SemanticSqlDialect.POSTGRESQL, "text", null, null),
            Case(SemanticSqlDialect.BIGQUERY, "INT64", null, null),
        ).forEach { case ->
            val type = catalogTimeType(case.nativeType, case.dialect)
            assertEquals(case.timeClass, type?.timeClass, case.toString())
            assertEquals(case.precision, type?.precision, case.toString())
        }
    }

    @Test
    fun `a declaration wins over inference and contradicting a known Catalog type is a mismatch`() {
        data class Case(val nativeType: String?, val expression: String, val declared: SemanticTimeKind?, val expected: SemanticTimeDimensionResolution)
        fun resolved(kind: SemanticTimeKind, storage: SemanticInstantStorage?, operand: SemanticTimeOperand, declarationNeeded: Boolean) =
            SemanticTimeDimensionResolution.Resolved(SemanticMetricFlowTimeDimension(kind, storage, operand), declarationNeeded)
        val column = SemanticTimeOperand.Column("ordered_at", false, null)
        listOf(
            Case("date", "ordered_at", null, resolved(SemanticTimeKind.DATE, null, column, false)),
            Case("timestamp without time zone", "ordered_at", null, resolved(SemanticTimeKind.LOCAL, null, column, false)),
            Case("timestamp with time zone", "ordered_at", null, resolved(SemanticTimeKind.INSTANT, SemanticInstantStorage.ZONED, column, false)),
            Case("timestamp without time zone", "ordered_at", SemanticTimeKind.INSTANT, resolved(SemanticTimeKind.INSTANT, SemanticInstantStorage.UTC_NAIVE, column, false)),
            Case("timestamp with time zone", "ordered_at", SemanticTimeKind.INSTANT, resolved(SemanticTimeKind.INSTANT, SemanticInstantStorage.ZONED, column, false)),
            Case("date", "ordered_at", SemanticTimeKind.DATE, resolved(SemanticTimeKind.DATE, null, column, false)),
            Case("timestamp without time zone", "ordered_at", SemanticTimeKind.LOCAL, resolved(SemanticTimeKind.LOCAL, null, column, false)),
            Case(null, "ordered_at", null, resolved(SemanticTimeKind.LOCAL, null, column, true)),
            Case("text", "ordered_at", null, resolved(SemanticTimeKind.LOCAL, null, column, true)),
            Case(null, "ordered_at", SemanticTimeKind.INSTANT, resolved(SemanticTimeKind.INSTANT, SemanticInstantStorage.ZONED, column, false)),
            Case("timestamp with time zone", "ordered_at + INTERVAL '1 hour'", null, resolved(SemanticTimeKind.LOCAL, null, SemanticTimeOperand.Expression, true)),
            Case("timestamp with time zone", "ordered_at + INTERVAL '1 hour'", SemanticTimeKind.INSTANT, resolved(SemanticTimeKind.INSTANT, SemanticInstantStorage.ZONED, SemanticTimeOperand.Expression, false)),
            Case("timestamp with time zone", "ordered_at", SemanticTimeKind.LOCAL, SemanticTimeDimensionResolution.Mismatch(SemanticTimeKind.LOCAL, "timestamp with time zone")),
            Case("timestamp with time zone", "ordered_at", SemanticTimeKind.DATE, SemanticTimeDimensionResolution.Mismatch(SemanticTimeKind.DATE, "timestamp with time zone")),
            Case("date", "ordered_at", SemanticTimeKind.LOCAL, SemanticTimeDimensionResolution.Mismatch(SemanticTimeKind.LOCAL, "date")),
            Case("date", "ordered_at", SemanticTimeKind.INSTANT, SemanticTimeDimensionResolution.Mismatch(SemanticTimeKind.INSTANT, "date")),
            Case("timestamp without time zone", "ordered_at", SemanticTimeKind.DATE, SemanticTimeDimensionResolution.Mismatch(SemanticTimeKind.DATE, "timestamp without time zone")),
        ).forEach { case ->
            val dimension = timeDimension("ordered_at", case.expression, SemanticTimeGranularity.DAY, case.declared)
            val catalog = catalogWithTypedColumns(workspace, connector, mapOf("orders" to listOfNotNull(case.nativeType?.let { "ordered_at" to it })))
            val resolution = timeDimensionResolutions(model(listOf(dimension)), ordersRelation(catalog), catalog, SemanticSqlDialect.POSTGRESQL).values.single()
            assertEquals(case.expected, resolution, case.toString())
        }
    }

    @Test
    fun `a declaration that contradicts the Catalog fails the render on every dialect`() {
        data class Case(val dialect: SemanticSqlDialect, val column: String, val nativeType: String, val declared: SemanticTimeKind)
        listOf(
            Case(SemanticSqlDialect.POSTGRESQL, "ordered_at", "timestamp with time zone", SemanticTimeKind.LOCAL),
            Case(SemanticSqlDialect.REDSHIFT, "ordered_at", "date", SemanticTimeKind.INSTANT),
            Case(SemanticSqlDialect.SNOWFLAKE, "ORDERED_AT", "TIMESTAMP_LTZ", SemanticTimeKind.DATE),
            Case(SemanticSqlDialect.BIGQUERY, "ordered_at", "DATETIME", SemanticTimeKind.DATE),
            Case(SemanticSqlDialect.DATABRICKS, "ordered_at", "TIMESTAMP", SemanticTimeKind.LOCAL),
            Case(SemanticSqlDialect.TRINO, "ordered_at", "date", SemanticTimeKind.LOCAL),
            Case(SemanticSqlDialect.DUCKDB, "ordered_at", "TIMESTAMP WITH TIME ZONE", SemanticTimeKind.DATE),
        ).forEach { case ->
            val definition = model(listOf(timeDimension("ordered_at", "ordered_at", SemanticTimeGranularity.DAY, case.declared)))
            val catalog = catalogWithTypedColumns(workspace, connector, mapOf("orders" to listOf(case.column to case.nativeType)))
            val projection = project(definition, catalog, case.dialect, emptyList())
            val invalid = assertIs<SemanticQueryResolution.Invalid>(AcceptingMetricFlowPort.resolveQuery(projection), case.toString())
            assertEquals(listOf(SemanticInvalidReason(SemanticInvalidReasonCode.TIME_KIND_MISMATCH, null)), invalid.reasons)
            val issue = invalid.definitionIssues.single()
            assertEquals(SemanticDefinitionReference.SemanticModel(ORDERS), issue.definition)
            assertEquals(
                "Semantic model `orders` declares timeKind `${case.declared.name.lowercase()}` for time dimension `ordered_at`, " +
                    "but its Catalog type is `${case.nativeType}`.",
                issue.message,
            )
        }
    }

    @Test
    fun `a candidate model whose declaration contradicts the Catalog is left out with its reason`() {
        val definition = model(listOf(timeDimension("ordered_at", "ordered_at", SemanticTimeGranularity.DAY, null)))
        val candidate = definition.copy(
            name = SemanticElementName("shipments"),
            sourceRelations = listOf(SemanticSourceRelation(connector, null, "analytics", "calendar")),
            dimensions = listOf(timeDimension("shipped_at", "shipped_at", SemanticTimeGranularity.DAY, SemanticTimeKind.LOCAL)),
            measures = emptyList(),
            aggregationTimeDimension = null,
        )
        val catalog = catalogWithTypedColumns(workspace, connector, mapOf(
            "orders" to listOf("ordered_at" to "timestamp with time zone"),
            "calendar" to listOf("shipped_at" to "date"),
        ))
        val projection = assertIs<SemanticClosureProjection.Projected>(project(definition, catalog, SemanticSqlDialect.POSTGRESQL, listOf(candidate)))
        assertEquals(emptyMap(), projection.candidateModels)
        val resolved = assertIs<SemanticQueryResolution.Resolved>(AcceptingMetricFlowPort.resolveQuery(projection))
        assertEquals(listOf(SemanticElementName("shipments")), resolved.excludedModels.map { it.name })
        assertTrue("timeKind `local`" in resolved.excludedModels.single().message, resolved.excludedModels.single().message)
    }

    @Test
    fun `render reads the Workspace Reporting time zone once and reports it with the SQL`(): Unit = runBlocking {
        val zones = FixedReportingTimeZone(seoul)
        val service = queryService(defaultCatalog(), zones)
        val rendered = assertIs<SemanticRenderResult.Rendered>(service.render(request(listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.WEEK)), emptyList())))
        assertEquals(seoul, rendered.render.reportingTimeZone)
        assertTrue("CAST((\"ordered_at\" AT TIME ZONE 'Asia/Seoul') AS DATE)" in rendered.render.sql, rendered.render.sql)
        assertEquals(1, zones.reads.get())

        val batch = service.renderBatch(
            listOf(request(emptyList(), emptyList()), request(listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.DAY)), emptyList())),
            Instant.now().plusSeconds(60),
        )
        assertEquals(listOf(seoul, seoul), batch.map { assertIs<SemanticRenderResult.Rendered>(it).render.reportingTimeZone })
        assertEquals(2, zones.reads.get())

        val unknownConnector = SemanticRenderRequest(workspace, ConnectorId.generate(), request(emptyList(), emptyList()).query)
        assertEquals(SemanticRenderResult.NotFoundOrOutsideWorkspace, service.render(unknownConnector))
        assertEquals(2, zones.reads.get())
    }

    @Test
    fun `time dimension filters must use DATE at day or coarser grain and TIMESTAMP at finer grain`(): Unit = runBlocking {
        val service = queryService(defaultCatalog(), FixedReportingTimeZone(seoul))
        val day = SemanticElementReference(ORDERS, SemanticElementName("ordered_at"))
        val hour = SemanticElementReference(ORDERS, SemanticElementName("ordered_hour"))
        val instant = Instant.parse("2026-08-01T00:00:00Z")
        val date = LocalDate.parse("2026-08-01")
        listOf(
            SemanticQueryFilter.Timestamp(day, SemanticTimestampFilterOperator.GREATER_THAN_OR_EQUAL, listOf(instant)),
            SemanticQueryFilter.Date(hour, SemanticDateFilterOperator.GREATER_THAN_OR_EQUAL, listOf(date)),
            SemanticQueryFilter.Text(day, SemanticTextFilterOperator.EQUALS, listOf("2026-08-01")),
        ).forEach { filter ->
            val invalid = assertIs<SemanticRenderResult.Invalid>(service.render(request(emptyList(), listOf(
                SemanticQueryFilter.Date(day, SemanticDateFilterOperator.LESS_THAN, listOf(date.plusDays(30))),
                filter,
            ))), filter.toString())
            assertEquals(listOf(SemanticInvalidReason(SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE, "filters[1].kind")), invalid.reasons)
        }
        val rendered = assertIs<SemanticRenderResult.Rendered>(service.render(request(emptyList(), listOf(
            SemanticQueryFilter.Date(day, SemanticDateFilterOperator.GREATER_THAN_OR_EQUAL, listOf(date)),
            SemanticQueryFilter.Timestamp(hour, SemanticTimestampFilterOperator.LESS_THAN, listOf(instant)),
        ))))
        assertTrue("CAST(order_id__ordered_at__day AS DATE) >= DATE '2026-08-01'" in rendered.render.sql, rendered.render.sql)
        assertTrue("order_id__ordered_hour__hour < TIMESTAMP '2026-08-01 09:00:00'" in rendered.render.sql, rendered.render.sql)
    }

    @Test
    fun `model Check warns about an undeclared unknown kind and reports a contradicting declaration on its dimension`(): Unit = runBlocking {
        val dimensions = listOf(
            timeDimension("ordered_at", "ordered_at", SemanticTimeGranularity.DAY, null),
            timeDimension("shifted_at", "ordered_at + INTERVAL '1 hour'", SemanticTimeGranularity.HOUR, null),
            timeDimension("declared_at", "ordered_at + INTERVAL '1 hour'", SemanticTimeGranularity.HOUR, SemanticTimeKind.INSTANT),
        )
        val warned = checkService(defaultCatalog()).check(SemanticCheckRequest(workspace, SemanticCheckDraft.Model(null, model(dimensions))))
        val checked = assertIs<SemanticCheckResult.Checked>(warned)
        assertEquals(listOf(connector), checked.runsOn)
        assertEquals(
            listOf(SemanticCheckProblem(SemanticCheckProblemCode.TIME_KIND_UNDECLARED, "dimensions[1]", connector, null, null)),
            checked.problems,
        )

        val contradicting = model(listOf(timeDimension("ordered_at", "ordered_at", SemanticTimeGranularity.DAY, SemanticTimeKind.LOCAL)))
        val rejected = assertIs<SemanticCheckResult.Checked>(
            checkService(defaultCatalog()).check(SemanticCheckRequest(workspace, SemanticCheckDraft.Model(null, contradicting))),
        )
        assertEquals(emptyList(), rejected.runsOn)
        assertEquals(
            listOf(SemanticCheckProblem(
                SemanticCheckProblemCode.TIME_KIND_MISMATCH, "dimensions[0]", connector, null,
                "timeKind `local` doesn't match this column's Catalog type `timestamp with time zone`.",
            )),
            rejected.problems,
        )
    }

    @Test
    fun `metric Check names the saved model whose declaration contradicts the Catalog`(): Unit = runBlocking {
        val repository = repositoryWith(model(listOf(timeDimension("ordered_at", "ordered_at", SemanticTimeGranularity.DAY, SemanticTimeKind.DATE))))
        val service = SemanticDefinitionCheckService(
            repository, repository, ImmediateTransactionPort, MetricFlowSemanticRenderer.production(),
            StaticConnectorRead(workspace, connector, NOW), FixedCatalogRead(defaultCatalog()), FixedReportingTimeZone(seoul),
        )
        val checked = assertIs<SemanticCheckResult.Checked>(
            service.check(SemanticCheckRequest(workspace, SemanticCheckDraft.Metric(null, metric().copy(name = SemanticElementName("revenue_draft")), connector))),
        )
        assertEquals(emptyList(), checked.runsOn)
        val problem = checked.problems.single()
        assertEquals(SemanticCheckProblemCode.TIME_KIND_MISMATCH, problem.code)
        assertEquals(connector, problem.connectorId)
        assertEquals(SemanticDefinitionReference.SemanticModel(ORDERS), problem.definition)
    }

    @Test
    fun `a time spine column with a time zone fails renders and Check on its connector`(): Unit = runBlocking {
        val catalog = catalogWithTypedColumns(workspace, connector, mapOf(
            "orders" to listOf("ordered_at" to "timestamp with time zone"),
            "calendar" to listOf("date_day" to "timestamp with time zone"),
        ))
        val spines = CurrentTimeSpineConfiguration(workspace, connector, SemanticTimeSpineDefinition(1u, listOf(
            SemanticTimeSpine(SemanticTimeSpineRelation(null, "analytics", "calendar"), SemanticTimeSpinePrimaryColumn("date_day", SemanticTimeGranularity.DAY)),
        )))
        val repository = repositoryWith(defaultModel())
        repository.upsertTimeSpineConfiguration(spines)
        val service = SemanticQueryService(
            repository, repository, ImmediateTransactionPort, MetricFlowSemanticRenderer.production(),
            StaticConnectorRead(workspace, connector, NOW), FixedCatalogRead(catalog), FixedReportingTimeZone(seoul),
        )
        val invalid = assertIs<SemanticRenderResult.Invalid>(service.render(request(emptyList(), emptyList())))
        assertEquals(listOf(SemanticInvalidReason(SemanticInvalidReasonCode.TIME_SPINE_COLUMN_TYPE_INVALID, null)), invalid.reasons)
        assertEquals(
            "Time spine column `date_day` of analytics.calendar is `timestamp with time zone`. " +
                "Use a date column or a timestamp column without a time zone.",
            invalid.definitionIssues.single().message,
        )
        assertNull(invalid.definitionIssues.single().definition)

        val checkRepository = InMemorySemanticRepository()
        checkRepository.upsertTimeSpineConfiguration(spines)
        val checks = SemanticDefinitionCheckService(
            checkRepository, checkRepository, ImmediateTransactionPort, MetricFlowSemanticRenderer.production(),
            StaticConnectorRead(workspace, connector, NOW), FixedCatalogRead(catalog), FixedReportingTimeZone(seoul),
        )
        val checked = assertIs<SemanticCheckResult.Checked>(checks.check(SemanticCheckRequest(workspace, SemanticCheckDraft.Model(null, defaultModel()))))
        assertEquals(emptyList(), checked.runsOn)
        assertEquals(listOf(SemanticCheckProblemCode.TIME_SPINE_COLUMN_TYPE_INVALID), checked.problems.map { it.code })
        assertEquals(connector, checked.problems.single().connectorId)
    }

    @Test
    fun `time spine columns must be dates or timestamps without a time zone when their Catalog type is known`() {
        listOf(
            SemanticSqlDialect.POSTGRESQL to "date",
            SemanticSqlDialect.POSTGRESQL to "timestamp without time zone",
            SemanticSqlDialect.BIGQUERY to "DATETIME",
            SemanticSqlDialect.SNOWFLAKE to "TIMESTAMP_NTZ",
            SemanticSqlDialect.TRINO to "timestamp(6)",
            SemanticSqlDialect.DUCKDB to "DATE",
            SemanticSqlDialect.DUCKDB to "TIMESTAMP_NS",
            SemanticSqlDialect.POSTGRESQL to "USER-DEFINED",
        ).forEach { (dialect, nativeType) -> assertTrue(timeSpineColumnTypeAllowed(nativeType, dialect), "$dialect $nativeType") }
        listOf(
            SemanticSqlDialect.POSTGRESQL to "timestamp with time zone",
            SemanticSqlDialect.BIGQUERY to "TIMESTAMP",
            SemanticSqlDialect.SNOWFLAKE to "TIMESTAMP_LTZ",
            SemanticSqlDialect.DATABRICKS to "TIMESTAMP",
            SemanticSqlDialect.TRINO to "timestamp(3) with time zone",
            SemanticSqlDialect.DUCKDB to "TIMESTAMP WITH TIME ZONE",
            SemanticSqlDialect.POSTGRESQL to "text",
            SemanticSqlDialect.POSTGRESQL to "integer",
        ).forEach { (dialect, nativeType) -> assertTrue(!timeSpineColumnTypeAllowed(nativeType, dialect), "$dialect $nativeType") }
    }

    private fun project(
        definition: SemanticModelDefinition,
        catalog: CatalogCurrentObjectSet,
        dialect: SemanticSqlDialect,
        candidates: List<SemanticModelDefinition>,
    ): SemanticClosureProjection {
        val metricId = MetricId.generate()
        val models = (listOf(definition) + candidates).associate { model ->
            val id = SemanticModelId.generate()
            model.name to CurrentSemanticModel(id, workspace, model, NOW, NOW)
        }
        return projectCurrentClosure(
            workspace, connector, listOf(METRIC), catalog, dialect, emptyList(),
            mapOf(METRIC to CurrentMetric(metricId, workspace, metric(), NOW, NOW)), models, seoul,
        )
    }

    private fun queryService(catalog: CatalogCurrentObjectSet, zones: FixedReportingTimeZone): SemanticQueryService {
        val repository = repositoryWith(defaultModel())
        return SemanticQueryService(
            repository, repository, ImmediateTransactionPort, MetricFlowSemanticRenderer.production(),
            StaticConnectorRead(workspace, connector, NOW), FixedCatalogRead(catalog), zones,
        )
    }

    private fun checkService(catalog: CatalogCurrentObjectSet): SemanticDefinitionCheckService {
        val repository = InMemorySemanticRepository()
        return SemanticDefinitionCheckService(
            repository, repository, ImmediateTransactionPort, MetricFlowSemanticRenderer.production(),
            StaticConnectorRead(workspace, connector, NOW), FixedCatalogRead(catalog), FixedReportingTimeZone(seoul),
        )
    }

    private fun repositoryWith(definition: SemanticModelDefinition): InMemorySemanticRepository {
        val repository = InMemorySemanticRepository()
        val modelId = SemanticModelId.generate()
        val metricId = MetricId.generate()
        repository.models[modelId] = CurrentSemanticModel(modelId, workspace, definition, NOW, NOW)
        repository.metrics[metricId] = CurrentMetric(metricId, workspace, metric(), NOW, NOW)
        return repository
    }

    private fun defaultCatalog(): CatalogCurrentObjectSet =
        catalogWithTypedColumns(workspace, connector, mapOf("orders" to listOf("ordered_at" to "timestamp with time zone")))

    private fun defaultModel(): SemanticModelDefinition = model(listOf(
        timeDimension("ordered_at", "ordered_at", SemanticTimeGranularity.DAY, null),
        timeDimension("ordered_hour", "ordered_at", SemanticTimeGranularity.HOUR, null),
    ))

    private fun ordersRelation(catalog: CatalogCurrentObjectSet) =
        catalog.objects.single { it.kind == CatalogObjectKind.RELATION && it.address.objectName == "orders" }

    private fun request(groupBys: List<SemanticGroupBy>, filters: List<SemanticQueryFilter>) = SemanticRenderRequest(
        workspace, connector, SemanticQuerySpec(listOf(METRIC), groupBys, null, filters, emptyList(), 10),
    )

    private fun model(dimensions: List<SemanticDimensionDefinition>) = SemanticModelDefinition(
        schemaVersion = 3u, name = ORDERS, label = null, description = null,
        sourceRelations = listOf(SemanticSourceRelation(connector, null, "analytics", "orders")),
        aggregationTimeDimension = dimensions.first().name,
        entities = listOf(SemanticEntityDefinition(SemanticElementName("order_id"), SemanticEntityKind.PRIMARY, "order_id", null, null)),
        dimensions = dimensions,
        measures = listOf(SemanticMeasureDefinition(SemanticElementName("revenue"), SemanticMeasureAggregation.SUM, "revenue", null, null, null, null)),
    )

    private fun metric() = SemanticMetricDefinition.Simple(3u, METRIC, null, null, null, emptyList(), ORDERS, SemanticElementName("revenue"), null)

    private fun timeDimension(name: String, expression: String, grain: SemanticTimeGranularity, declared: SemanticTimeKind?) =
        SemanticDimensionDefinition(SemanticElementName(name), SemanticDimensionKind.TIME, expression, grain, null, null, false, declared)

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-12T00:00:00Z")
        val ORDERS = SemanticElementName("orders")
        val METRIC = SemanticElementName("revenue_metric")
    }
}
