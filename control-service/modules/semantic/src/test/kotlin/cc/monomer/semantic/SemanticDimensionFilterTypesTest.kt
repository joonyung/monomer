@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.semantic

import cc.monomer.catalog.api.CatalogCurrentObject
import cc.monomer.catalog.api.CatalogCurrentObjectSet
import cc.monomer.catalog.api.CatalogCurrentObjectsReadPort
import cc.monomer.catalog.api.CatalogDigest
import cc.monomer.catalog.api.CatalogObjectId
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.catalog.api.CatalogPhysicalAddress
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.*
import cc.monomer.semantic.application.*
import cc.monomer.semantic.domain.CurrentMetric
import cc.monomer.semantic.domain.CurrentSemanticModel
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SemanticDimensionFilterTypesTest {
    @Test
    fun `scalar native types use exact type names and keep arrays complex and unknown types manual`() {
        mapOf(
            SemanticCategoricalFilterKind.TEXT to listOf("text", "character varying", "varchar(255)", "CHAR(4)", "STRING", "nvarchar(32)"),
            SemanticCategoricalFilterKind.NUMBER to listOf("smallint", "integer", "BIGINT", "int64", "FLOAT64", "double precision", "NUMBER(38, 0)", "DECIMAL(18,2)", "BIGNUMERIC", "TINYINT", "LONG"),
            SemanticCategoricalFilterKind.BOOLEAN to listOf("boolean", "BOOL"),
        ).forEach { (kind, types) -> types.forEach { assertEquals(kind, scalarComparisonKind(it), it) } }
        listOf("ARRAY<INT64>", "ARRAY", "INTEGER[]", "ROW(id bigint)", "STRUCT<price NUMERIC>", "MAP<STRING,INT>", "VARIANT", "SUPER", "USER-DEFINED", "POINT", "INTERVAL", "DATETIME", "TIMESTAMP", "DATE", "BYTES", "BINARY", "UNKNOWN", "my_integer_type", "BOOL(4)", "INTEGER(3)").forEach {
            assertNull(scalarComparisonKind(it), it)
        }
    }

    @Test
    fun `plain identifiers follow each dialect quoting and case rules while expressions stay manual`() {
        val fixture = Fixture()
        data class Example(val dialect: SemanticSqlDialect, val expression: String, val column: String)
        listOf(
            Example(SemanticSqlDialect.POSTGRESQL, "  STATE  ", "state"),
            Example(SemanticSqlDialect.POSTGRESQL, "\"State\"", "State"),
            Example(SemanticSqlDialect.REDSHIFT, "\"state label\"", "state label"),
            Example(SemanticSqlDialect.SNOWFLAKE, "state", "STATE"),
            Example(SemanticSqlDialect.SNOWFLAKE, "\"State\"", "State"),
            Example(SemanticSqlDialect.TRINO, "\"state\"", "STATE"),
            Example(SemanticSqlDialect.BIGQUERY, "`state`", "State"),
            Example(SemanticSqlDialect.BIGQUERY, "`state\\`label`", "state`label"),
            Example(SemanticSqlDialect.DATABRICKS, "`state``label`", "state`label"),
            Example(SemanticSqlDialect.POSTGRESQL, "\"state\"\"label\"", "state\"label"),
        ).forEach { example ->
            val model = fixture.modelWithDimensions(listOf(dimension("state", example.expression)))
            val catalog = fixture.catalogWithColumns(listOf(example.column to "VARCHAR"))
            assertEquals(
                listOf(SemanticDimensionFilterType(reference("state"), SemanticCategoricalFilterKind.TEXT)),
                dimensionFilterTypes(fixture.workspace, catalog, fixture.manifest(model, example.dialect)),
                example.toString(),
            )
        }
        listOf("lower(state)", "state || 'x'", "orders.state", "state::text", "state -- comment", "'state'", "\"state\" + \"other\"", "`state`", "(state)").forEach { expression ->
            assertEquals(emptyList(), dimensionFilterTypes(fixture.workspace, fixture.catalog, fixture.manifest(fixture.modelWithDimensions(listOf(dimension("state", expression))))), expression)
        }
        assertEquals(emptyList(), dimensionFilterTypes(fixture.workspace, fixture.catalogWithColumns(listOf("State" to "VARCHAR")), fixture.manifest(fixture.modelWithDimensions(listOf(dimension("state", "State"))))))
    }

    @Test
    fun `Redshift quoted uppercase names stay manual because Catalog does not capture identifier case settings`() {
        val fixture = Fixture()
        val model = fixture.modelWithDimensions(listOf(
            dimension("uppercase", "\"STATE\""),
            dimension("lowercase", "\"state\""),
            dimension("unquoted", "STATE"),
        ))
        val manifest = fixture.manifest(model, SemanticSqlDialect.REDSHIFT)
        val catalog = fixture.catalogWithColumns(listOf("state" to "VARCHAR", "STATE" to "INTEGER"))
        val filterTypes = dimensionFilterTypes(fixture.workspace, catalog, manifest)
        assertEquals(listOf(
            SemanticDimensionFilterType(reference("lowercase"), SemanticCategoricalFilterKind.TEXT),
            SemanticDimensionFilterType(reference("unquoted"), SemanticCategoricalFilterKind.TEXT),
        ), filterTypes)
        val query = fixture.request(listOf(SemanticQueryFilter.Number(reference("uppercase"), SemanticNumberFilterOperator.EQUALS, listOf(BigDecimal.ONE)))).query
        assertEquals(emptyList(), query.filterTypeMismatches(filterTypes, manifest))
        assertEquals(emptyList(), dimensionFilterTypes(fixture.workspace, fixture.catalogWithColumns(listOf("STATE" to "INTEGER")), manifest))
    }

    @Test
    fun `bare SQL values remain expressions even when a quoted catalog column has the same name`() {
        val fixture = Fixture()
        val catalog = fixture.catalogWithColumns(listOf("true" to "BOOLEAN", "current_date" to "TEXT"))
        val expressions = fixture.modelWithDimensions(listOf(dimension("literal", "TRUE"), dimension("today", "CURRENT_DATE")))
        assertEquals(emptyList(), dimensionFilterTypes(fixture.workspace, catalog, fixture.manifest(expressions)))
        val columns = fixture.modelWithDimensions(listOf(dimension("literal", "\"true\""), dimension("today", "\"current_date\"")))
        assertEquals(listOf(SemanticCategoricalFilterKind.BOOLEAN, SemanticCategoricalFilterKind.TEXT), dimensionFilterTypes(fixture.workspace, catalog, fixture.manifest(columns)).map { it.kind })
    }

    @Test
    fun `known text number and boolean dimensions reject wrong comparison kinds including null operators before rendering`(): Unit = runBlocking {
        val fixture = Fixture()
        val accepted = listOf(
            SemanticQueryFilter.Text(reference("state"), SemanticTextFilterOperator.EQUALS, listOf("CA")),
            SemanticQueryFilter.Number(reference("amount"), SemanticNumberFilterOperator.EQUALS, listOf(BigDecimal.TEN)),
            SemanticQueryFilter.Boolean(reference("active"), SemanticBooleanFilterOperator.EQUALS, listOf(true)),
            SemanticQueryFilter.Text(reference("state"), SemanticTextFilterOperator.IS_NULL, emptyList()),
            SemanticQueryFilter.Number(reference("amount"), SemanticNumberFilterOperator.IS_NOT_NULL, emptyList()),
            SemanticQueryFilter.Boolean(reference("active"), SemanticBooleanFilterOperator.IS_NULL, emptyList()),
        )
        assertIs<SemanticRenderResult.Rendered>(fixture.service.render(fixture.request(accepted)))
        val rendered = fixture.renderCalls.get()
        val mismatched = listOf(
            SemanticQueryFilter.Number(reference("state"), SemanticNumberFilterOperator.IS_NULL, emptyList()),
            SemanticQueryFilter.Boolean(reference("amount"), SemanticBooleanFilterOperator.IS_NOT_NULL, emptyList()),
            SemanticQueryFilter.Text(reference("active"), SemanticTextFilterOperator.EQUALS, listOf("true")),
            SemanticQueryFilter.Date(reference("state"), SemanticDateFilterOperator.EQUALS, listOf(LocalDate.parse("2026-10-12"))),
        )
        val invalid = assertIs<SemanticRenderResult.Invalid>(fixture.service.render(fixture.request(mismatched)))
        assertEquals(mismatched.indices.map { SemanticInvalidReason(SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE, "filters[$it].kind") }, invalid.reasons)
        assertEquals(emptyList(), invalid.definitionIssues)
        assertEquals(rendered, fixture.renderCalls.get())
    }

    @Test
    fun `computed unknown missing and time dimension types keep existing filter handling`(): Unit = runBlocking {
        val fixture = Fixture()
        val filters = listOf(
            SemanticQueryFilter.Number(reference("computed"), SemanticNumberFilterOperator.EQUALS, listOf(BigDecimal.ONE)),
            SemanticQueryFilter.Boolean(reference("unknown"), SemanticBooleanFilterOperator.EQUALS, listOf(true)),
            SemanticQueryFilter.Text(reference("missing"), SemanticTextFilterOperator.EQUALS, listOf("x")),
            SemanticQueryFilter.Date(reference("ordered_at"), SemanticDateFilterOperator.EQUALS, listOf(LocalDate.parse("2026-10-12"))),
        )
        assertIs<SemanticRenderResult.Rendered>(fixture.service.render(fixture.request(filters)))
        assertEquals(1, fixture.renderCalls.get())
    }

    @Test
    fun `discovery includes only known categorical types returned by the engine including joined candidates`(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.addCandidate()
        fixture.listedDimensions = listOf(reference("state"), reference("active"), reference("computed"), reference("unknown"), reference("ordered_at"), reference("state", "shipments"))
        val result = assertIs<SemanticGroupByOptionsResult.Listed>(fixture.service.listGroupBys(fixture.groupByRequest))
        assertEquals(
            listOf(
                SemanticDimensionFilterType(reference("state"), SemanticCategoricalFilterKind.TEXT),
                SemanticDimensionFilterType(reference("active"), SemanticCategoricalFilterKind.BOOLEAN),
                SemanticDimensionFilterType(reference("state", "shipments"), SemanticCategoricalFilterKind.NUMBER),
            ),
            result.dimensionFilterTypes,
        )
        val invalid = assertIs<SemanticRenderResult.Invalid>(fixture.service.render(fixture.request(listOf(
            SemanticQueryFilter.Text(reference("state", "shipments"), SemanticTextFilterOperator.EQUALS, listOf("CA")),
        ))))
        assertEquals(listOf(SemanticInvalidReason(SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE, "filters[0].kind")), invalid.reasons)
        assertEquals(0, fixture.renderCalls.get())
    }

    @Test
    fun `column lookup stays within selected workspace connector resolved relation and exact parent`() {
        val fixture = Fixture()
        val manifest = fixture.manifest(fixture.model)
        assertEquals(emptyList(), dimensionFilterTypes(WorkspaceId.generate(), fixture.catalog, manifest))
        assertEquals(emptyList(), dimensionFilterTypes(fixture.workspace, fixture.catalog.copy(connectorId = ConnectorId.generate()), manifest))
        val otherRelation = fixture.catalog.objects.single { it.kind == CatalogObjectKind.RELATION && it.address.objectName == "calendar" }
        val otherColumn = column(otherRelation, "state", "NUMBER", 30)
        val catalog = fixture.catalog.copy(objects = (fixture.catalog.objects.filter { it.kind != CatalogObjectKind.COLUMN } + otherColumn).sortedBy { it.identityDigest.value })
        assertEquals(emptyList(), dimensionFilterTypes(fixture.workspace, catalog, manifest))
    }

    @Test
    fun `compute uses captured catalog types after current catalog changes without rereading`(): Unit = runBlocking {
        val fixture = Fixture()
        val request = fixture.request(listOf(SemanticQueryFilter.Number(reference("state"), SemanticNumberFilterOperator.EQUALS, listOf(BigDecimal.ONE))))
        val renderInputs = fixture.service.readRenderInputs(request)
        val groupInputs = fixture.service.readGroupByInputs(fixture.groupByRequest)
        assertEquals(2, fixture.catalogReads.get())
        fixture.catalog = fixture.catalogWithColumns(listOf("state" to "NUMBER"))
        val invalid = assertIs<SemanticRenderResult.Invalid>(fixture.service.renderFromInputs(renderInputs))
        assertEquals("filters[0].kind", invalid.reasons.single().field)
        val discovered = assertIs<SemanticGroupByOptionsResult.Listed>(fixture.service.listGroupBysFromInputs(groupInputs))
        assertEquals(SemanticCategoricalFilterKind.TEXT, discovered.dimensionFilterTypes.single { it.dimension == reference("state") }.kind)
        assertEquals(2, fixture.catalogReads.get())
        assertIs<SemanticRenderResult.Rendered>(fixture.service.render(request))
        assertEquals(3, fixture.catalogReads.get())
    }

    @Test
    fun `batch validation uses one captured catalog and rejects mismatches independently`(): Unit = runBlocking {
        val fixture = Fixture()
        val results = fixture.service.renderBatch(listOf(
            fixture.request(listOf(SemanticQueryFilter.Text(reference("state"), SemanticTextFilterOperator.EQUALS, listOf("CA")))),
            fixture.request(listOf(SemanticQueryFilter.Number(reference("state"), SemanticNumberFilterOperator.IS_NULL, emptyList()))),
        ), Instant.now().plusSeconds(30))
        assertIs<SemanticRenderResult.Rendered>(results[0])
        assertEquals(listOf(SemanticInvalidReason(SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE, "filters[0].kind")), assertIs<SemanticRenderResult.Invalid>(results[1]).reasons)
        assertEquals(1, fixture.renderCalls.get())
        assertEquals(1, fixture.catalogReads.get())
        assertEquals(0, fixture.snapshotActive.get())
    }

    private class Fixture {
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val repository = InMemorySemanticRepository()
        val snapshotActive = AtomicInteger()
        val renderCalls = AtomicInteger()
        val catalogReads = AtomicInteger()
        val model = modelWithDimensions(listOf(
            dimension("state", "state"), dimension("amount", "amount"), dimension("active", "active"),
            dimension("computed", "lower(state)"), dimension("unknown", "unknown"), dimension("missing", "missing"),
            SemanticDimensionDefinition(SemanticElementName("ordered_at"), SemanticDimensionKind.TIME, "ordered_at", SemanticTimeGranularity.DAY, null, null, false, null),
        ))
        var catalog = catalogWithColumns(listOf("state" to "VARCHAR", "amount" to "NUMERIC(18, 2)", "active" to "BOOLEAN", "unknown" to "ARRAY<INT64>", "ordered_at" to "TEXT"))
        var listedDimensions = model.dimensions.map { SemanticElementReference(model.name, it.name) }
        private val metric = SemanticMetricDefinition.Simple(3u, SemanticElementName("revenue"), null, null, null, emptyList(), model.name, SemanticElementName("revenue"), null)
        val groupByRequest = SemanticGroupByOptionsRequest(workspace, connector, listOf(metric.name))
        private val transactions = object : SemanticTransactionPort {
            override suspend fun <T> inSnapshotTransaction(deadline: Instant, block: suspend () -> T): T {
                snapshotActive.incrementAndGet()
                return try { block() } finally { snapshotActive.decrementAndGet() }
            }
            override suspend fun <T> inSnapshotTransaction(block: () -> T): T {
                snapshotActive.incrementAndGet()
                return try { block() } finally { snapshotActive.decrementAndGet() }
            }
            override suspend fun <T> inMutationTransaction(block: () -> T): T = error("unused")
        }
        private val catalogs = object : CatalogCurrentObjectsReadPort by StaticCatalogRead(workspace, connector, NOW) {
            override fun readCurrent(workspaceId: WorkspaceId, connectorId: ConnectorId): CatalogCurrentObjectSet {
                assertTrue(snapshotActive.get() > 0)
                assertEquals(workspace, workspaceId)
                assertEquals(connector, connectorId)
                catalogReads.incrementAndGet()
                return catalog
            }
        }
        private val engine = object : SemanticMetricFlowPort by AcceptingMetricFlowPort {
            override fun validate(manifest: SemanticMetricFlowManifest): SemanticMetricFlowValidationResult {
                assertEquals(0, snapshotActive.get())
                return SemanticMetricFlowValidationResult.Valid
            }
            override fun render(manifest: SemanticMetricFlowManifest, querySpec: SemanticQuerySpec, metricIds: List<MetricId>): SemanticMetricFlowRenderResult {
                assertEquals(0, snapshotActive.get())
                renderCalls.incrementAndGet()
                return AcceptingMetricFlowPort.render(manifest, querySpec, metricIds)
            }
            override fun listGroupBys(manifest: SemanticMetricFlowManifest, metricIds: List<MetricId>): SemanticMetricFlowGroupByResult {
                assertEquals(0, snapshotActive.get())
                return SemanticMetricFlowGroupByResult.Listed(listedDimensions.map { SemanticGroupBy.Dimension(it.semanticModelName, it.name, it.granularity) })
            }
        }
        val service = SemanticQueryService(repository, repository, transactions, engine, StaticConnectorRead(workspace, connector, NOW), catalogs, FixedReportingTimeZone(UTC_REPORTING_TIME_ZONE))

        init {
            val modelId = SemanticModelId.generate()
            val metricId = MetricId.generate()
            repository.models[modelId] = CurrentSemanticModel(modelId, workspace, model, NOW, NOW)
            repository.metrics[metricId] = CurrentMetric(metricId, workspace, metric, NOW, NOW)
        }

        fun modelWithDimensions(dimensions: List<SemanticDimensionDefinition>): SemanticModelDefinition = SemanticModelDefinition(
            3u, SemanticElementName("orders"), null, null, listOf(SemanticSourceRelation(connector, null, "analytics", "orders")), null,
            listOf(SemanticEntityDefinition(SemanticElementName("order_id"), SemanticEntityKind.PRIMARY, "order_id", null, null)), dimensions,
            listOf(SemanticMeasureDefinition(SemanticElementName("revenue"), SemanticMeasureAggregation.SUM, "amount", null, null, null, null)),
        )

        fun catalogWithColumns(columns: List<Pair<String, String>>): CatalogCurrentObjectSet {
            val base = catalogObjects(workspace, connector, NOW)
            val relation = base.objects.single { it.kind == CatalogObjectKind.RELATION && it.address.objectName == "orders" }
            return base.copy(objects = (base.objects + columns.mapIndexed { index, (name, type) -> column(relation, name, type, index + 10) }).sortedBy { it.identityDigest.value })
        }

        fun manifest(definition: SemanticModelDefinition, dialect: SemanticSqlDialect = SemanticSqlDialect.POSTGRESQL): SemanticMetricFlowManifest = SemanticMetricFlowManifest(
            connector, dialect, mapOf(definition.name to uncataloguedMetricFlowModel(definition, CatalogPhysicalAddress(null, "analytics", "orders", null), dialect)), emptyMap(), emptyList(),
            UTC_REPORTING_TIME_ZONE,
        )

        fun request(filters: List<SemanticQueryFilter>) = SemanticRenderRequest(workspace, connector, SemanticQuerySpec(listOf(metric.name), emptyList(), null, filters, emptyList(), 10))

        fun addCandidate() {
            val candidate = model.copy(name = SemanticElementName("shipments"), sourceRelations = listOf(SemanticSourceRelation(connector, null, "analytics", "calendar")), dimensions = listOf(dimension("state", "state")))
            val id = SemanticModelId.generate()
            repository.models[id] = CurrentSemanticModel(id, workspace, candidate, NOW, NOW)
            val relation = catalog.objects.single { it.kind == CatalogObjectKind.RELATION && it.address.objectName == "calendar" }
            catalog = catalog.copy(objects = (catalog.objects + column(relation, "state", "INTEGER", 30)).sortedBy { it.identityDigest.value })
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-12T00:00:00Z")
        fun reference(name: String, model: String = "orders") = SemanticElementReference(SemanticElementName(model), SemanticElementName(name))
        fun dimension(name: String, expression: String) = SemanticDimensionDefinition(SemanticElementName(name), SemanticDimensionKind.CATEGORICAL, expression, null, null, null, false, null)

        fun column(relation: CatalogCurrentObject, name: String, type: String, suffix: Int): CatalogCurrentObject = relation.copy(
            id = CatalogObjectId(Uuid.parse("0197d83f-2c9e-8a3b-8c1d-${suffix.toString().padStart(12, '0')}")),
            identityDigest = CatalogDigest("sha256:" + suffix.toString().padStart(64, '0')),
            parentId = relation.id, kind = CatalogObjectKind.COLUMN, relationKind = null,
            address = relation.address.copy(memberName = name), qualifiedName = "${relation.qualifiedName}.$name", nativeType = type,
            definition = null, ordinal = suffix, nullable = true,
        )
    }
}
