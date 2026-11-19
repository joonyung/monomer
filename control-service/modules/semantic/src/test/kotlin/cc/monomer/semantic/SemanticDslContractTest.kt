@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.semantic

import cc.monomer.connector.api.ConnectorId
import cc.monomer.semantic.adapter.outbound.postgres.SemanticPostgresJsonCodec
import cc.monomer.semantic.api.SemanticDimensionDefinition
import cc.monomer.semantic.api.SemanticDimensionKind
import cc.monomer.semantic.api.SemanticConstantProperty
import cc.monomer.semantic.api.SemanticConversionCalculation
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticEntityDefinition
import cc.monomer.semantic.api.SemanticEntityKind
import cc.monomer.semantic.api.SemanticGroupBy
import cc.monomer.semantic.api.SemanticMeasureAggregation
import cc.monomer.semantic.api.SemanticMeasureDefinition
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticMetricFilter
import cc.monomer.semantic.api.SemanticMetricFilterOperator
import cc.monomer.semantic.api.SemanticMetricInput
import cc.monomer.semantic.api.SemanticMetricPeriodAggregation
import cc.monomer.semantic.api.SemanticMetricTimeRange
import cc.monomer.semantic.api.SemanticMetricWindow
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticNonAdditiveDimension
import cc.monomer.semantic.api.SemanticNonAdditiveWindowChoice
import cc.monomer.semantic.api.SemanticQuerySpec
import cc.monomer.semantic.api.SemanticSourceRelation
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.api.SemanticTimeKind
import cc.monomer.semantic.api.SemanticTimeRangeEndpoint
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SemanticDslContractTest {
    private val codec = SemanticPostgresJsonCodec(ObjectMapper())

    @Test
    fun `definition equality ignores JSON object key order while keeping arrays ordered`() {
        val mapper = ObjectMapper()
        fun reverseObjectKeys(node: JsonNode): JsonNode = when {
            node.isObject -> mapper.createObjectNode().also { result ->
                node.fields().asSequence().toList().reversed().forEach { (key, value) ->
                    result.set<JsonNode>(key, reverseObjectKeys(value))
                }
            }
            node.isArray -> mapper.createArrayNode().also { result ->
                node.forEach { result.add(reverseObjectKeys(it)) }
            }
            else -> node
        }
        val model = model(ConnectorId.generate())
        val modelJson = codec.modelDefinitionJson(model)
        assertEquals(model, codec.parseModelDefinition(reverseObjectKeys(mapper.readTree(modelJson)).toString()))
        val metric = SemanticMetricDefinition.Simple(3u, SemanticElementName("revenue"), "Revenue", null, null,
            listOf(SemanticMetricFilter.Predicate(
                cc.monomer.semantic.api.SemanticElementReference(SemanticElementName("orders"), SemanticElementName("status")),
                SemanticMetricFilterOperator.IN, listOf("paid", "pending"))), SemanticElementName("orders"), SemanticElementName("revenue"), display = null)
        val metricJson = codec.metricDefinitionJson(metric)
        assertEquals(metric, codec.parseMetricDefinition(reverseObjectKeys(mapper.readTree(metricJson)).toString()))
        val reversedValues = metric.copy(filters = listOf((metric.filters.single() as SemanticMetricFilter.Predicate).copy(values = listOf("pending", "paid"))))
        assertTrue(metric != reversedValues)
    }

    @Test
    fun `model codec preserves connector relation list and optional text`() {
        val connector = ConnectorId.generate()
        val model = model(connector)

        val roundTripped = codec.parseModelDefinition(codec.modelDefinitionJson(model))

        assertEquals(model, roundTripped)
        assertTrue(codec.modelDefinitionJson(model).contains("sourceRelations"))
        assertTrue(codec.modelDefinitionJson(model).contains("expression"))
    }

    @Test
    fun `metric codec preserves recursive filters and current name inputs`() {
        val filter = SemanticMetricFilter.And(
            listOf(
                SemanticMetricFilter.Predicate(
                    dimension = cc.monomer.semantic.api.SemanticElementReference(
                        SemanticElementName("orders"),
                        SemanticElementName("status"),
                    ),
                    operator = SemanticMetricFilterOperator.EQ,
                    values = listOf(""),
                ),
            ),
        )
        val metric = SemanticMetricDefinition.Ratio(
            schemaVersion = 3u,
            name = SemanticElementName("conversion_rate"),
            label = "Conversion rate",
            description = null,
            timeGranularity = null,
            filters = listOf(filter),
            numerator = SemanticMetricInput(
                SemanticElementName("converted_orders"),
                null,
                SemanticElementName("converted"),
                null,
                null,
            ),
            denominator = SemanticMetricInput(
                SemanticElementName("all_orders"),
                null,
                SemanticElementName("all"),
                null,
                null,
            ),
            display = null,
        )

        assertEquals(metric, codec.parseMetricDefinition(codec.metricDefinitionJson(metric)))
        val json = codec.metricDefinitionJson(metric)
        assertTrue(json.contains("metricName"))
        assertTrue(json.contains("\"and\""))
    }

    @Test
    fun `metric codec round trips all five current metric types`() {
        val input = { name: String ->
            SemanticMetricInput(
                metricName = SemanticElementName(name),
                filter = null,
                alias = null,
                offsetWindow = null,
                offsetToGrain = null,
            )
        }
        val definitions = listOf<SemanticMetricDefinition>(
            SemanticMetricDefinition.Simple(
                schemaVersion = 3u,
                name = SemanticElementName("simple_orders"),
                label = null,
                description = null,
                timeGranularity = null,
                filters = emptyList(),
                semanticModelName = SemanticElementName("orders"),
                measure = SemanticElementName("revenue"),
                display = cc.monomer.semantic.api.MetricDisplay.Currency(2, cc.monomer.semantic.api.MetricDisplayCurrency.USD),
            ),
            SemanticMetricDefinition.Ratio(
                schemaVersion = 3u,
                name = SemanticElementName("ratio_orders"),
                label = null,
                description = null,
                timeGranularity = null,
                filters = emptyList(),
                numerator = input("converted_orders"),
                denominator = input("all_orders"),
                display = cc.monomer.semantic.api.MetricDisplay.Currency(2, cc.monomer.semantic.api.MetricDisplayCurrency.USD),
            ),
            SemanticMetricDefinition.Derived(
                schemaVersion = 3u,
                name = SemanticElementName("derived_orders"),
                label = null,
                description = null,
                timeGranularity = null,
                filters = emptyList(),
                expression = "current_orders - prior_orders",
                inputMetrics = listOf(input("current_orders"), input("prior_orders")),
                display = cc.monomer.semantic.api.MetricDisplay.Currency(2, cc.monomer.semantic.api.MetricDisplayCurrency.USD),
            ),
            SemanticMetricDefinition.Cumulative(
                schemaVersion = 3u,
                name = SemanticElementName("cumulative_orders"),
                label = null,
                description = null,
                timeGranularity = null,
                filters = emptyList(),
                inputMetric = input("simple_orders"),
                window = SemanticMetricWindow(7, cc.monomer.semantic.api.SemanticMetricWindowUnit.DAY),
                grainToDate = null,
                periodAggregation = SemanticMetricPeriodAggregation.LAST,
                display = cc.monomer.semantic.api.MetricDisplay.Currency(2, cc.monomer.semantic.api.MetricDisplayCurrency.USD),
            ),
            SemanticMetricDefinition.Conversion(
                schemaVersion = 3u,
                name = SemanticElementName("conversion_orders"),
                label = null,
                description = null,
                timeGranularity = null,
                filters = emptyList(),
                entity = SemanticElementName("order_id"),
                calculation = SemanticConversionCalculation.CONVERSION_RATE,
                baseMetric = input("all_orders"),
                conversionMetric = input("converted_orders"),
                window = null,
                constantProperties = listOf(
                    SemanticConstantProperty(
                        baseProperty = SemanticElementName("country"),
                        conversionProperty = SemanticElementName("country"),
                    ),
                ),
                display = cc.monomer.semantic.api.MetricDisplay.Currency(2, cc.monomer.semantic.api.MetricDisplayCurrency.USD),
            ),
        )

        definitions.forEach { definition ->
            val encoded = codec.metricDefinitionJson(definition)
            assertTrue(encoded.contains("\"display\""))
            val withoutDisplayJson = ObjectMapper().readTree(encoded) as com.fasterxml.jackson.databind.node.ObjectNode
            withoutDisplayJson.remove("display")
            val withoutDisplay = codec.parseMetricDefinition(withoutDisplayJson.toString())
            assertEquals(null, withoutDisplay.display)
            assertTrue(!codec.metricDefinitionJson(withoutDisplay).contains("\"display\""))
            withoutDisplayJson.putNull("display")
            assertEquals(withoutDisplay, codec.parseMetricDefinition(withoutDisplayJson.toString()))
            assertEquals(
                definition,
                codec.parseMetricDefinition(codec.metricDefinitionJson(definition)),
                "metric type ${definition.type}",
            )
        }
    }

    @Test
    fun `absent and null metric display decode to null and do not enter stored definitions`() {
        val json = """{"schemaVersion":3,"name":"orders","type":"simple","semanticModelName":"orders","measure":"revenue"}"""
        listOf(json, json.dropLast(1) + ",\"display\":null}").forEach { source ->
            val definition = codec.parseMetricDefinition(source)
            assertEquals(null, definition.display)
            assertTrue(!codec.metricDefinitionJson(definition).contains("\"display\""))
        }
    }

    @Test
    fun `window syntax is exact and derived alias validation belongs to the owner`() {
        assertEquals(SemanticMetricWindow(1, cc.monomer.semantic.api.SemanticMetricWindowUnit.MONTH), SemanticMetricWindow.parse("1 month"))
        assertFailsWith<IllegalArgumentException> { SemanticMetricWindow.parse(" 1 month") }
        val definition = SemanticMetricDefinition.Derived(
            schemaVersion = 3u,
            name = SemanticElementName("broken_growth"),
            label = null,
            description = null,
            timeGranularity = null,
            filters = emptyList(),
            expression = "current_only",
            inputMetrics = listOf(
                SemanticMetricInput(SemanticElementName("current"), null, SemanticElementName("current_only"), null, null),
                SemanticMetricInput(SemanticElementName("prior"), null, SemanticElementName("prior_only"), null, null),
            ),
            display = null,
        )
        assertEquals("current_only", definition.expression)
    }

    @Test
    fun `closed contracts reject invalid semantic references aliases and metric-time query forms`() {
        val baseModel = model(ConnectorId.generate())
        val categoricalDimension = SemanticElementName("status")
        val missingDimension = SemanticElementName("missing_time")

        assertFailsWith<IllegalArgumentException> {
            baseModel.copy(aggregationTimeDimension = categoricalDimension)
        }
        assertFailsWith<IllegalArgumentException> {
            baseModel.copy(aggregationTimeDimension = missingDimension)
        }
        assertFailsWith<IllegalArgumentException> {
            baseModel.copy(
                measures = baseModel.measures.map {
                    it.copy(aggregationTimeDimension = categoricalDimension)
                },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            baseModel.copy(
                measures = baseModel.measures.map {
                    it.copy(
                        nonAdditiveDimension = SemanticNonAdditiveDimension(
                            name = categoricalDimension,
                            windowChoice = SemanticNonAdditiveWindowChoice.MAX,
                            windowGroupings = null,
                        ),
                    )
                },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            baseModel.copy(
                measures = baseModel.measures.map {
                    it.copy(
                        nonAdditiveDimension = SemanticNonAdditiveDimension(
                            name = SemanticElementName("ordered_at"),
                            windowChoice = SemanticNonAdditiveWindowChoice.MAX,
                            windowGroupings = listOf(categoricalDimension),
                        ),
                    )
                },
            )
        }

        fun input(metricName: String, alias: String? = null): SemanticMetricInput = SemanticMetricInput(
            metricName = SemanticElementName(metricName),
            filter = null,
            alias = alias?.let(::SemanticElementName),
            offsetWindow = null,
            offsetToGrain = null,
        )

        fun derived(expression: String, inputs: List<SemanticMetricInput>) = SemanticMetricDefinition.Derived(
            schemaVersion = 3u,
            name = SemanticElementName("derived_metric"),
            label = null,
            description = null,
            timeGranularity = null,
            filters = emptyList(),
            expression = expression,
            inputMetrics = inputs,
            display = null,
        )

        assertFailsWith<IllegalArgumentException> {
            derived("", listOf(input("base")))
        }
        assertFailsWith<IllegalArgumentException> {
            derived("base", emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            derived("left + right", listOf(input("left", "same"), input("right", "same")))
        }

        val metricTimeDay = SemanticGroupBy.MetricTime(SemanticTimeGranularity.DAY)
        val metricTimeMonth = SemanticGroupBy.MetricTime(SemanticTimeGranularity.MONTH)
        fun query(groupBys: List<SemanticGroupBy>, range: SemanticMetricTimeRange? = null) = SemanticQuerySpec(
            metrics = listOf(SemanticElementName("revenue_metric")),
            groupBys = groupBys,
            metricTimeRange = range,
            filters = emptyList(),
            orderBy = emptyList(),
            rowLimit = 1,
        )

        assertFailsWith<IllegalArgumentException> {
            query(listOf(metricTimeDay, metricTimeMonth))
        }
        val categoricalGroupBy = SemanticGroupBy.Dimension(
            semanticModelName = SemanticElementName("orders"),
            name = categoricalDimension,
            granularity = null,
        )
        assertFailsWith<IllegalArgumentException> {
            query(listOf(categoricalGroupBy, categoricalGroupBy))
        }
        assertFailsWith<IllegalArgumentException> {
            SemanticMetricTimeRange(
                start = SemanticTimeRangeEndpoint.Date(LocalDate.of(2026, 9, 3)),
                end = SemanticTimeRangeEndpoint.Date(LocalDate.of(2026, 9, 1)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SemanticMetricTimeRange(
                start = SemanticTimeRangeEndpoint.Date(LocalDate.of(2026, 9, 1)),
                end = SemanticTimeRangeEndpoint.Timestamp(Instant.parse("2026-09-03T00:00:00Z")),
            )
        }
    }

    @Test
    fun `model entity kinds and primary cardinality stay closed`() {
        val baseModel = model(ConnectorId.generate())
        val entities = listOf(
            SemanticEntityDefinition(SemanticElementName("order_id"), SemanticEntityKind.PRIMARY, "order_id", null, null),
            SemanticEntityDefinition(SemanticElementName("customer_id"), SemanticEntityKind.UNIQUE, "customer_id", null, null),
            SemanticEntityDefinition(SemanticElementName("order_ref"), SemanticEntityKind.FOREIGN, "order_ref", null, null),
            SemanticEntityDefinition(SemanticElementName("natural_order"), SemanticEntityKind.NATURAL, "natural_order", null, null),
        )
        val definition = baseModel.copy(entities = entities)

        assertEquals(
            listOf(SemanticEntityKind.PRIMARY, SemanticEntityKind.UNIQUE, SemanticEntityKind.FOREIGN, SemanticEntityKind.NATURAL),
            definition.entities.map { it.type },
        )
        assertEquals(
            listOf("order_id", "customer_id", "order_ref", "natural_order"),
            definition.entities.map { it.name.value },
        )
        assertFailsWith<IllegalArgumentException> {
            definition.copy(entities = entities.filter { it.type != SemanticEntityKind.PRIMARY })
        }
        assertFailsWith<IllegalArgumentException> {
            definition.copy(
                entities = entities + entities.first().copy(name = SemanticElementName("second_order_id")),
            )
        }
    }

    @Test
    fun `derived metric expressions reject NUL like every other expression`() {
        listOf("current\u0000- prior", "\u0000").forEach { expression ->
            assertFailsWith<IllegalArgumentException>("derived expression") {
                SemanticMetricDefinition.Derived(
                    schemaVersion = 3u,
                    name = SemanticElementName("nul_expression"),
                    label = null,
                    description = null,
                    timeGranularity = null,
                    filters = emptyList(),
                    expression = expression,
                    inputMetrics = listOf(SemanticMetricInput(SemanticElementName("current"), null, null, null, null)),
                    display = null,
                )
            }
        }
    }

    @Test
    fun `model element names expressions and cross-kind duplicates are rejected`() {
        listOf("Orders", "has space", "has-hyphen", "", "\u0000").forEach { invalidName ->
            assertFailsWith<IllegalArgumentException>("invalid name $invalidName") {
                SemanticElementName(invalidName)
            }
        }

        listOf("", " ", "\u0000").forEach { invalidExpression ->
            assertFailsWith<IllegalArgumentException>("invalid entity expression") {
                SemanticEntityDefinition(
                    SemanticElementName("entity_expression"),
                    SemanticEntityKind.PRIMARY,
                    invalidExpression,
                    null,
                    null,
                )
            }
            assertFailsWith<IllegalArgumentException>("invalid dimension expression") {
                SemanticDimensionDefinition(
                    SemanticElementName("dimension_expression"),
                    SemanticDimensionKind.TIME,
                    invalidExpression,
                    SemanticTimeGranularity.DAY,
                    null,
                    null,
                    false,
                    null,
                )
            }
            assertFailsWith<IllegalArgumentException>("invalid measure expression") {
                SemanticMeasureDefinition(
                    SemanticElementName("measure_expression"),
                    SemanticMeasureAggregation.SUM,
                    invalidExpression,
                    null,
                    null,
                    null,
                    null,
                )
            }
        }

        val baseModel = model(ConnectorId.generate())
        listOf<() -> SemanticModelDefinition>(
            {
                baseModel.copy(
                    entities = baseModel.entities.map { it.copy(name = baseModel.dimensions.first().name) },
                )
            },
            {
                baseModel.copy(
                    entities = baseModel.entities.map { it.copy(name = baseModel.measures.first().name) },
                )
            },
            {
                baseModel.copy(
                    dimensions = baseModel.dimensions.mapIndexed { index, dimension ->
                        if (index == 0) dimension.copy(name = baseModel.measures.first().name) else dimension
                    },
                )
            },
        ).forEach { duplicateDefinition ->
            assertFailsWith<IllegalArgumentException> {
                duplicateDefinition()
            }
        }
    }

    @Test
    fun `time kind is an optional lower case time dimension field in the codec and schema`() {
        val base = model(ConnectorId.generate())
        val declared = base.copy(dimensions = base.dimensions.map { dimension ->
            if (dimension.type == SemanticDimensionKind.TIME) dimension.copy(timeKind = SemanticTimeKind.INSTANT) else dimension
        })
        val json = codec.modelDefinitionJson(declared)
        val stored = ObjectMapper().readTree(json).path("dimensions")
        assertEquals("instant", stored[0].path("timeKind").asText())
        assertTrue(!stored[1].has("timeKind"))
        assertEquals(declared, codec.parseModelDefinition(json))
        assertTrue(!codec.modelDefinitionJson(base).contains("timeKind"))
        SemanticTimeKind.entries.forEach { kind ->
            val roundTripped = codec.parseModelDefinition(json.replace("\"instant\"", "\"${kind.name.lowercase()}\""))
            assertEquals(kind, roundTripped.dimensions.first().timeKind)
        }
        assertFailsWith<IllegalArgumentException> { codec.parseModelDefinition(json.replace("\"instant\"", "\"zoned\"")) }
        assertFailsWith<IllegalArgumentException> {
            base.dimensions.single { it.type == SemanticDimensionKind.CATEGORICAL }.copy(timeKind = SemanticTimeKind.LOCAL)
        }

        val schema = ObjectMapper().readTree(Files.readString(Path.of("src/main/resources/schema/semantic/model.schema.json")))
        val dimension = schema.path("\$defs").path("dimension")
        val timeKind = dimension.path("properties").path("timeKind")
        assertEquals(listOf("date", "local", "instant"), timeKind.path("enum").map { it.asText() })
        assertTrue(dimension.path("required").none { it.asText() == "timeKind" })
        listOf("Reporting time zone", "Check warns", "stores UTC", "AT TIME ZONE 'UTC'").forEach { phrase ->
            assertTrue(phrase in timeKind.path("description").asText(), phrase)
        }
        val categoricalRule = dimension.path("allOf").single { it.path("if").path("properties").path("type").path("const").asText() == "categorical" }
        assertEquals(
            listOf("granularity", "timeKind"),
            categoricalRule.path("then").path("not").path("anyOf").map { it.path("required").single().asText() },
        )
    }

    @Test
    fun `schemas expose current definition shape and reject removed identities`() {
        val root = Path.of("src/main/resources/schema/semantic")
        val modelSchema = Files.readString(root.resolve("model.schema.json"))
        val metricSchema = Files.readString(root.resolve("metric.schema.json"))
        val querySchema = Files.readString(root.resolve("query.schema.json"))

        listOf(modelSchema, metricSchema, querySchema).forEach { schema ->
            assertTrue(schema.contains("schemaVersion"))
            assertTrue(!schema.contains("sourceRelationId"))
            assertTrue(!schema.contains("scalarKind"))
            assertTrue(!schema.contains("definitionDigest"))
        }
        assertTrue(modelSchema.contains("sourceRelations"))
        assertTrue(modelSchema.contains("expression"))
        assertTrue(metricSchema.contains("inputMetrics"))
        assertTrue(metricSchema.contains("constantProperties"))
        assertTrue(querySchema.contains("metricTimeRange"))
        assertTrue(querySchema.contains("METRIC_TIME"))
        assertTrue(!querySchema.contains("timeDimension"))
        assertTrue(!querySchema.contains("categoricalGroupBy"))
    }

    @Test
    fun `current definition JSON has no publication version or digest fields`() {
        val modelJson = codec.modelDefinitionJson(model(ConnectorId.generate()))
        val metricJson = codec.metricDefinitionJson(
            SemanticMetricDefinition.Simple(
                schemaVersion = 3u,
                name = SemanticElementName("orders_metric"),
                label = null,
                description = null,
                timeGranularity = null,
                filters = emptyList(),
                semanticModelName = SemanticElementName("orders"),
                measure = SemanticElementName("revenue"),
                display = null,
            ),
        )

        listOf(modelJson, metricJson).forEach { json ->
            listOf(
                "\"publication\"",
                "\"revision\"",
                "\"definitionDigest\"",
                "\"artifactVersion\"",
            ).forEach { removedField ->
                assertTrue(!json.contains(removedField), "unexpected field $removedField in $json")
            }
        }
    }

    private fun model(connector: ConnectorId): SemanticModelDefinition = SemanticModelDefinition(
        schemaVersion = 3u,
        name = SemanticElementName("orders"),
        label = "Orders",
        description = "Current orders",
        sourceRelations = listOf(
            SemanticSourceRelation(connector, null, "analytics", "orders"),
            SemanticSourceRelation(ConnectorId.generate(), "warehouse", "analytics", "orders"),
        ),
        aggregationTimeDimension = SemanticElementName("ordered_at"),
        entities = listOf(
            SemanticEntityDefinition(
                SemanticElementName("order_id"),
                SemanticEntityKind.PRIMARY,
                "order_id",
                "Order ID",
                null,
            ),
        ),
        dimensions = listOf(
            SemanticDimensionDefinition(
                SemanticElementName("ordered_at"),
                SemanticDimensionKind.TIME,
                "ordered_at",
                SemanticTimeGranularity.DAY,
                null,
                null,
                false,
                null,
            ),
            SemanticDimensionDefinition(
                SemanticElementName("status"),
                SemanticDimensionKind.CATEGORICAL,
                "status",
                null,
                null,
                null,
                false,
                null,
            ),
        ),
        measures = listOf(
            SemanticMeasureDefinition(
                SemanticElementName("revenue"),
                SemanticMeasureAggregation.SUM,
                "amount",
                SemanticElementName("ordered_at"),
                null,
                "Revenue",
                null,
            ),
        ),
    )
}
