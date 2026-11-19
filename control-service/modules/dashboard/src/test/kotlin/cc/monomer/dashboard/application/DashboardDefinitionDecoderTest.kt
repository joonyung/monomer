package cc.monomer.dashboard.application

import cc.monomer.dashboard.domain.DashboardResolvedQuery
import cc.monomer.dashboard.domain.DashboardNamedQuery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import cc.monomer.dashboard.domain.DashboardParameterDefinition
import cc.monomer.dashboard.domain.DashboardValueType
import cc.monomer.dashboard.domain.DashboardVariableDefinition
import cc.monomer.dashboard.domain.DashboardWidgetType

class DashboardDefinitionDecoderTest {
    private val json = Json { explicitNulls = true }

    @Test
    fun `decodes a closed current definition`() {
        val definition = DashboardDefinitionDecoder.decode(definitionWithQuery())

        assertNotNull(definition.queries["orders"])
        assertNotNull(definition.widgets["total"])
    }

    @Test
    fun `decodes serialized chart source at the UTF-8 size limit`() {
        val source = ("export default defineChart({ render() { return {}; } });\n" +
            "// \"quoted\" \\ path { [ : , ] }\n".repeat(8_000)).padEnd(256 * 1024, ' ')
        val document = definitionWithChart(source = source)

        assertEquals(256 * 1024, source.toByteArray(Charsets.UTF_8).size)
        val definition = DashboardDefinitionDecoder.decode(document.toString())

        assertEquals(document, definition.document)
        assertEquals(source, definition.widgets.getValue("chart").document
            .getValue("module").jsonObject.getValue("source").jsonPrimitive.content)
    }

    @Test
    fun `rejects duplicate keys in serialized definitions including escaped equivalents`() {
        for (duplicateKey in listOf("source", "\\u0073ource")) {
            val document = definitionWithChart().toString()
                .replace("\"source\":", "\"source\":\"first\",\"$duplicateKey\":")

            val error = assertFailsWith<IllegalArgumentException> {
                DashboardDefinitionDecoder.decode(document)
            }

            assertEquals("duplicate JSON key: source", error.message)
        }
    }

    @Test
    fun `rejects malformed strings in serialized definitions`() {
        val source = "export default defineChart({ render() { return {}; } });"
        for (malformedString in listOf("\"unterminated", "\"invalid\\q\"", "\"invalid\\uZZZZ\"", "\"trailing\\")) {
            val document = definitionWithChart(source = source).toString()
                .replace(JsonPrimitive(source).toString(), malformedString)

            assertFailsWith<IllegalArgumentException> {
                DashboardDefinitionDecoder.decode(document)
            }
        }
    }

    @Test
    fun `rejects removed KPI widget type`() {
        val document = JsonObject(
            definitionWithQuery() + ("widgets" to json.parseToJsonElement(
                """
                {
                  "total": {
                    "type": "KPI",
                    "queryName": "orders",
                    "field": { "kind": "METRIC", "name": "order_total" }
                  }
                }
                """.trimIndent(),
            )),
        )

        val error = assertFailsWith<IllegalArgumentException> { DashboardDefinitionDecoder.decode(document) }

        assertEquals("unknown widget type in total", error.message)
    }

    @Test
    fun `preserves TABLE projection and CHART source query bindings`() {
        val table = DashboardDefinitionDecoder.decode(definitionWithQuery()).widgets.getValue("total")
        val chartDocument = definitionWithChart()
        val chart = DashboardDefinitionDecoder.decode(chartDocument).widgets.getValue("chart")

        assertEquals(DashboardWidgetType.TABLE, table.type)
        assertEquals(listOf("orders"), table.orderedQueryNames)
        assertEquals("metric.order_total", table.document.getValue("columns").jsonArray.single().jsonObject.getValue("field").jsonPrimitive.content)
        assertEquals(DashboardWidgetType.CHART, chart.type)
        assertEquals(listOf("orders"), chart.orderedQueryNames)
        assertEquals(chartDocument.getValue("widgets").jsonObject.getValue("chart"), chart.document)
    }

    @Test
    fun `rejects unknown root fields`() {
        val document = JsonObject(definitionWithQuery() + ("publication" to json.parseToJsonElement("{}")))

        assertFailsWith<IllegalArgumentException> { DashboardDefinitionDecoder.decode(document) }
    }

    @Test
    fun `rejects json numbers for exact decimal filter values`() {
        val document = definitionWithQuery(
            filter = """
                {
                  "kind": "NUMBER",
                  "dimension": { "semanticModelName": "orders", "name": "amount" },
                  "operator": "EQUALS",
                  "value": 12.50
                }
            """.trimIndent(),
        )

        assertFailsWith<IllegalArgumentException> { DashboardDefinitionDecoder.decode(document) }
    }

    @Test
    fun `rejects filter references with a mismatched declared type`() {
        val document = definitionWithQuery(
            parameters = """
                "regionNumber": { "type": "NUMBER", "nullable": false }
            """.trimIndent(),
            filter = """
                {
                  "kind": "TEXT",
                  "dimension": { "semanticModelName": "orders", "name": "region" },
                  "operator": "EQUALS",
                  "value": { "kind": "PARAMETER", "name": "regionNumber" }
                }
            """.trimIndent(),
        )

        assertFailsWith<IllegalArgumentException> { DashboardDefinitionDecoder.decode(document) }
    }

    @Test
    fun `requires empty behavior for nullable filter references`() {
        val document = definitionWithQuery(
            parameters = """
                "region": { "type": "TEXT", "nullable": true }
            """.trimIndent(),
            filter = """
                {
                  "kind": "TEXT",
                  "dimension": { "semanticModelName": "orders", "name": "region" },
                  "operator": "EQUALS",
                  "value": { "kind": "PARAMETER", "name": "region" }
                }
            """.trimIndent(),
        )

        assertFailsWith<IllegalArgumentException> { DashboardDefinitionDecoder.decode(document) }
    }

    @Test
    fun `rejects duplicate chart input names`() {
        val document = definitionWithChart(
            parameters = """
                "period": { "type": "DATE", "nullable": false }
            """.trimIndent(),
            parameterInputs = "period,period",
        )

        assertFailsWith<IllegalArgumentException> { DashboardDefinitionDecoder.decode(document) }
    }

    @Test
    fun `accepts divider separation and defaults omitted separation to gap`() {
        DashboardDefinitionDecoder.decode(definitionWithLayoutSeparation(null))
        DashboardDefinitionDecoder.decode(definitionWithLayoutSeparation("DIVIDER"))
    }

    @Test
    fun `rejects unknown layout separation`() {
        assertFailsWith<IllegalArgumentException> {
            DashboardDefinitionDecoder.decode(definitionWithLayoutSeparation("NONE"))
        }
    }

    @Test
    fun `reports an unknown layout node as invalid input`() {
        val document = json.parseToJsonElement(
            """
            {
              "schemaVersion": 1,
              "meta": { "title": "Invalid layout" },
              "parameters": {},
              "variables": {},
              "queries": {},
              "widgets": {},
              "layout": {
                "sections": [{
                  "id": "main",
                  "height": { "mode": "SCALED", "scale": 1.0 },
                  "root": { "type": "COLUMN", "id": "invalid" }
                }]
              }
            }
            """.trimIndent(),
        ).jsonObject

        val error = assertFailsWith<IllegalArgumentException> {
            DashboardDefinitionDecoder.decode(document)
        }

        assertEquals("unknown layout node type: COLUMN", error.message)
    }

    @Test
    fun `rejects non raster chart data uri`() {
        val document = definitionWithChart(source = "const resource = 'data:text/plain;base64,AAAA';")

        assertFailsWith<IllegalArgumentException> { DashboardDefinitionDecoder.decode(document) }
    }

    @Test
    fun `allows echarts data properties`() {
        DashboardDefinitionDecoder.decode(
            definitionWithChart(
                source = "export default defineChart({ render({ queries }) { return { series: [{ data: queries.orders.rows }] }; } });",
            ),
        )
    }

    @Test
    fun `accepts inline raster chart data uri`() {
        DashboardDefinitionDecoder.decode(
            definitionWithChart(source = "const logo = 'image://data:image/png;base64,AAAA';"),
        )
    }

    @Test
    fun `rejects malformed inline raster chart data uri`() {
        val document = definitionWithChart(source = "const logo = 'data:image/png;base64,not base64';")

        assertFailsWith<IllegalArgumentException> { DashboardDefinitionDecoder.decode(document) }
    }

    @Test
    fun `measures chart source in UTF-8 bytes`() {
        DashboardDefinitionDecoder.decode(definitionWithChart(source = "é".repeat(131_072)))

        assertFailsWith<IllegalArgumentException> {
            DashboardDefinitionDecoder.decode(definitionWithChart(source = "é".repeat(131_073)))
        }
    }

    @Test
    fun `rejects the removed table format field`() {
        val document = definitionWithQuery()
        val table = json.parseToJsonElement(
            """
            {
              "type": "TABLE",
              "queryName": "orders",
              "columns": [{"field": "metric.order_total", "format": "currency"}]
            }
            """.trimIndent(),
        ).jsonObject
        val widgets = JsonObject(document.getValue("widgets").jsonObject + ("total" to table))

        assertFailsWith<IllegalArgumentException> {
            DashboardDefinitionDecoder.decode(JsonObject(document + ("widgets" to widgets)))
        }
    }

    @Test
    fun `rejects query without Semantic v4 schema version`() {
        val document = definitionWithQuery()
        val queries = document.getValue("queries").jsonObject
        val query = queries.getValue("orders").jsonObject
        val semanticQuery = query.getValue("query").jsonObject.filterKeys { it != "schemaVersion" }
        val replacementQuery = JsonObject(query + ("query" to JsonObject(semanticQuery)))
        val replacementQueries = JsonObject(queries + ("orders" to replacementQuery))
        val replacement = JsonObject(document + ("queries" to replacementQueries))

        assertFailsWith<IllegalArgumentException> { DashboardDefinitionDecoder.decode(replacement) }
    }

    @Test
    fun `rejects non canonical layout weights and adjacent spacers`() {
        val document = json.parseToJsonElement(
            """
            {
              "schemaVersion": 1,
              "meta": { "title": "Layout" },
              "parameters": {},
              "variables": {},
              "queries": {},
              "widgets": { "one": { "type": "TEXT", "content": "one" } },
              "layout": { "sections": [{
                "id": "main", "height": { "mode": "SCALED", "scale": 1.0 },
                "root": { "type": "STACK", "id": "row", "direction": "ROW", "children": [
                  { "weight": 2, "node": { "type": "WIDGET", "id": "item", "widgetId": "one" } },
                  { "weight": 4, "node": { "type": "SPACER", "id": "space" } }
                ] }
              }] }
            }
            """.trimIndent(),
        ).jsonObject
        assertFailsWith<IllegalArgumentException> { DashboardDefinitionDecoder.decode(document) }

        val adjacentSpacers = JsonObject(document + ("layout" to json.parseToJsonElement(
            """
            { "sections": [{ "id": "main", "height": { "mode": "SCALED", "scale": 1.0 }, "root": { "type": "STACK", "id": "row", "direction": "ROW", "children": [
              { "weight": 1, "node": { "type": "SPACER", "id": "space1" } },
              { "weight": 1, "node": { "type": "SPACER", "id": "space2" } },
              { "weight": 1, "node": { "type": "WIDGET", "id": "item", "widgetId": "one" } }
            ] } }] }
            """.trimIndent(),
        )))
        assertFailsWith<IllegalArgumentException> { DashboardDefinitionDecoder.decode(adjacentSpacers) }
    }

    @Test
    fun `resolves template values to the Semantic v4 shape`() {
        val query = json.parseToJsonElement(
            """
            {
              "schemaVersion": 4,
              "metrics": ["order_total"],
              "groupBys": [],
              "metricTimeRange": {
                "start": { "kind": "PARAMETER", "name": "from" },
                "end": { "kind": "PARAMETER", "name": "to" }
              },
              "filters": [
                { "kind": "NUMBER", "dimension": { "semanticModelName": "orders", "name": "amount" }, "operator": "EQUALS", "value": { "kind": "VARIABLE", "name": "threshold" } },
                { "kind": "TEXT", "dimension": { "semanticModelName": "orders", "name": "region" }, "operator": "IN", "value": { "kind": "VARIABLE", "name": "regions" }, "emptyBehavior": "OMIT" }
              ],
              "orderBy": [],
              "rowLimit": 100
            }
            """.trimIndent(),
        ).jsonObject
        val resolved = DashboardQueryValueResolver.resolve(
            query = query,
            parameters = mapOf("from" to JsonPrimitive("2026-01-01"), "to" to JsonPrimitive("2026-01-31")),
            variables = mapOf("threshold" to JsonPrimitive("12.5"), "regions" to kotlinx.serialization.json.JsonNull),
            parameterDefinitions = mapOf(
                "from" to DashboardParameterDefinition("from", DashboardValueType.DATE, false, null, null, null),
                "to" to DashboardParameterDefinition("to", DashboardValueType.DATE, false, null, null, null),
            ),
            variableDefinitions = mapOf(
                "threshold" to DashboardVariableDefinition("threshold", DashboardValueType.NUMBER, false, null, null, JsonPrimitive("0")),
                "regions" to DashboardVariableDefinition("regions", DashboardValueType.TEXT_SET, true, null, null, kotlinx.serialization.json.JsonNull),
            ),
        ) ?: error("query should resolve")

        assertEquals(4, resolved.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("DATE", resolved.getValue("metricTimeRange").jsonObject.getValue("start").jsonObject.getValue("kind").jsonPrimitive.content)
        val filters = resolved.getValue("filters").jsonArray
        assertEquals(1, filters.size)
        assertEquals("12.5", filters.single().jsonObject.getValue("values").jsonArray.single().jsonPrimitive.content)
        assertFalse("value" in filters.single().jsonObject)
        assertFalse("emptyBehavior" in filters.single().jsonObject)
    }

    @Test
    fun `resolves a whole nullable range reference to Semantic endpoints`() {
        val query = json.parseToJsonElement(
            """
            {
              "schemaVersion": 4,
              "metrics": ["order_total"],
              "groupBys": [],
              "metricTimeRange": { "kind": "VARIABLE", "name": "period" },
              "filters": [],
              "orderBy": [],
              "rowLimit": 100
            }
            """.trimIndent(),
        ).jsonObject
        val resolved = DashboardQueryValueResolver.resolve(
            query = query,
            parameters = emptyMap(),
            variables = mapOf(
                "period" to json.parseToJsonElement("{\"start\":\"2026-08-01\",\"end\":\"2026-08-25\"}"),
            ),
            parameterDefinitions = emptyMap(),
            variableDefinitions = mapOf(
                "period" to DashboardVariableDefinition(
                    "period",
                    DashboardValueType.DATE_RANGE,
                    true,
                    null,
                    null,
                    json.parseToJsonElement("{\"start\":\"2026-01-01\",\"end\":\"2026-01-02\"}"),
                ),
            ),
        ) ?: error("query should resolve")

        val range = resolved.getValue("metricTimeRange").jsonObject
        assertEquals("DATE", range.getValue("start").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("2026-08-25", range.getValue("end").jsonObject.getValue("value").jsonPrimitive.content)
    }

    @Test
    fun `distinguishes metric and dimension with the same element name`() {
        val document = definitionWithQuery(
            metrics = "\"status\"",
            groupBys = """{"kind":"DIMENSION","semanticModelName":"orders","name":"status"}""",
        )

        assertEquals(listOf("dimension.orders.status", "metric.status"), DashboardDefinitionDecoder.decode(document).query("orders").resultColumnNames())
    }

    @Test
    fun `distinguishes same named dimensions from different models`() {
        val document = definitionWithQuery(
            groupBys = """
                {"kind":"DIMENSION","semanticModelName":"orders","name":"status"},
                {"kind":"DIMENSION","semanticModelName":"shipments","name":"status"}
            """.trimIndent(),
        )

        assertEquals(listOf("dimension.orders.status", "dimension.shipments.status", "metric.order_total"), DashboardDefinitionDecoder.decode(document).query("orders").resultColumnNames())
    }

    @Test
    fun `distinguishes metric time from a same named metric`() {
        val document = definitionWithQuery(
            metrics = "\"metric_time\"",
            groupBys = """{"kind":"METRIC_TIME","granularity":"DAY"}""",
        )

        assertEquals(listOf("metric_time.day", "metric.metric_time"), DashboardDefinitionDecoder.decode(document).query("orders").resultColumnNames())
    }

    @Test
    fun `projects result column names in Semantic output order`() {
        val definition = DashboardDefinitionDecoder.decode(
            definitionWithQuery(
                metrics = "\"order_total\", \"order_count\"",
                groupBys = """
                    {"kind":"DIMENSION","semanticModelName":"orders","name":"status"},
                    {"kind":"ENTITY","semanticModelName":"orders","name":"customer"},
                    {"kind":"METRIC_TIME","granularity":"MONTH"}
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf("dimension.orders.status", "entity.orders.customer", "metric_time.month", "metric.order_total", "metric.order_count"),
            definition.query("orders").resultColumnNames(),
        )
    }

    @Test
    fun `accepts typed parameter control and whole range reference`() {
        val definition = DashboardDefinitionDecoder.decode(
            definitionWithQuery(
                parameters = """
                    "period": {
                      "type": "DATE_RANGE",
                      "nullable": true,
                      "control": {
                        "min": "2026-01-01",
                        "max": "2026-12-31",
                        "startPlaceholder": "Start",
                        "endPlaceholder": "End"
                      }
                    }
                """.trimIndent(),
                metricTimeRange = "{ \"kind\": \"PARAMETER\", \"name\": \"period\" }",
            ),
        )

        assertEquals(DashboardValueType.DATE_RANGE, definition.parameters.getValue("period").type)
        assertNotNull(definition.parameters.getValue("period").control)
    }

    @Test
    fun `rejects removed value types and oversized controls`() {
        assertFailsWith<IllegalArgumentException> {
            DashboardDefinitionDecoder.decode(
                definitionWithQuery(parameters = "\"grain\": { \"type\": \"TIME_GRAIN\", \"nullable\": false }"),
            )
        }

        val options = (1..501).joinToString(",") { "{\"value\":\"$it\"}" }
        assertFailsWith<IllegalArgumentException> {
            DashboardDefinitionDecoder.decode(
                definitionWithQuery(
                    parameters = "\"region\": { \"type\": \"TEXT\", \"nullable\": false, \"control\": { \"options\": [$options] } }",
                ),
            )
        }
    }

    @Test
    fun `rejects invalid range ordering`() {
        assertFailsWith<IllegalArgumentException> {
            DashboardDefinitionDecoder.decode(
                definitionWithQuery(
                    variables = "\"period\": { \"type\": \"DATE_RANGE\", \"nullable\": false, \"initial\": { \"start\": \"2026-08-25\", \"end\": \"2026-08-01\" } }",
                ),
            )
        }
    }

    @Test
    fun `rejects semantically duplicate number set values`() {
        assertFailsWith<IllegalArgumentException> {
            DashboardDefinitionDecoder.decode(
                definitionWithQuery(
                    variables = "\"thresholds\": { \"type\": \"NUMBER_SET\", \"nullable\": false, \"initial\": [\"1\", \"1.0\"] }",
                ),
            )
        }
    }

    @Test
    fun `range endpoints resolve both reference kinds and temporal types to scalar literals`() {
        for (referenceKind in listOf("PARAMETER", "VARIABLE")) {
            for (scalarType in listOf("DATE", "TIMESTAMP")) {
                val suffix = if (scalarType == "DATE") "" else "T12:30:00Z"
                val start = "2026-09-01$suffix"
                val end = "2026-09-07$suffix"
                val range = json.parseToJsonElement("""{"start":"$start","end":"$end"}""")
                val values = mapOf("period" to range)
                val startRef = """{"kind":"$referenceKind","name":"period","endpoint":"start"}"""
                val endRef = """{"kind":"$referenceKind","name":"period","endpoint":"end"}"""
                val definition = DashboardDefinitionDecoder.decode(endpointDefinition(
                    referenceKind, "${scalarType}_RANGE", false,
                    "${endpointFilter(scalarType, startRef)},${endpointFilter(scalarType, endRef, "LESS_THAN_OR_EQUAL")}",
                    """{"start":$startRef,"end":$endRef}""",
                ))
                val query = (definition.queries.getValue("orders") as DashboardNamedQuery.Semantic).query
                val resolved = assertNotNull(DashboardQueryValueResolver.resolve(
                    query,
                    if (referenceKind == "PARAMETER") values else emptyMap(),
                    if (referenceKind == "VARIABLE") values else emptyMap(),
                    definition.parameters, definition.variables,
                ))
                assertEquals(listOf(start, end), resolved.getValue("filters").jsonArray.map {
                    it.jsonObject.getValue("values").jsonArray.single().jsonPrimitive.content
                })
                for ((endpoint, expected) in listOf("start" to start, "end" to end)) {
                    assertEquals(json.parseToJsonElement("""{"kind":"$scalarType","value":"$expected"}"""),
                        resolved.getValue("metricTimeRange").jsonObject.getValue(endpoint))
                }
                assertEquals(referenceKind == "PARAMETER", DashboardQueryValueResolver.referencesParameter(query, "period"))
                assertFalse(DashboardQueryValueResolver.referencesParameter(query, "period.start"))
                assertFalse(DashboardQueryValueResolver.referencesParameter(query, "unknown"))
                val metricOnlyQuery = JsonObject(query + ("filters" to kotlinx.serialization.json.JsonArray(emptyList())))
                assertEquals(referenceKind == "PARAMETER", DashboardQueryValueResolver.referencesParameter(metricOnlyQuery, "period"))
                val whole = DashboardDefinitionDecoder.decode(endpointDefinition(
                    referenceKind, "${scalarType}_RANGE", false, "",
                    """{"kind":"$referenceKind","name":"period"}""",
                ))
                assertEquals(resolved.getValue("metricTimeRange"), assertNotNull(DashboardQueryValueResolver.resolve(
                    (whole.queries.getValue("orders") as DashboardNamedQuery.Semantic).query,
                    if (referenceKind == "PARAMETER") values else emptyMap(),
                    if (referenceKind == "VARIABLE") values else emptyMap(),
                    whole.parameters, whole.variables,
                )).getValue("metricTimeRange"))
            }
        }
    }

    @Test
    fun `endpoint selectors reject invalid syntax source and consumer types in definitions`() {
        for (referenceKind in listOf("PARAMETER", "VARIABLE")) {
            val ref = """{"kind":"$referenceKind","name":"period","endpoint":"start"}"""
            for (invalidRef in listOf(
                ref.replace("\"start\"", "\"middle\""), ref.replace("\"start\"", "null"),
                ref.replace("\"start\"", "0"), ref.replace("\"start\"", "[\"start\"]"),
                ref.replace("\"endpoint\"", "\"field\""), ref.replace("\"endpoint\"", "\"path\""),
                ref.replace("\"period\"", "\"unknown\""), ref.replace("\"period\"", "\"period.start\""),
            )) {
                assertFailsWith<IllegalArgumentException>(invalidRef) {
                    DashboardDefinitionDecoder.decode(endpointDefinition(referenceKind, "DATE_RANGE", false,
                        endpointFilter("DATE", invalidRef), "null"))
                }
                assertFailsWith<IllegalArgumentException>(invalidRef) {
                    DashboardDefinitionDecoder.decode(endpointDefinition(referenceKind, "DATE_RANGE", false,
                        "", """{"start":$invalidRef,"end":$ref}"""))
                }
            }
            for (type in DashboardValueType.entries.filter { it !in setOf(DashboardValueType.DATE_RANGE, DashboardValueType.TIMESTAMP_RANGE) }) {
                assertFailsWith<IllegalArgumentException>(type.name) {
                    DashboardDefinitionDecoder.decode(endpointDefinition(referenceKind, type.name, true,
                        endpointFilter("DATE", ref, emptyBehavior = "OMIT"), "null"))
                }
            }
            for (consumer in listOf("TEXT", "NUMBER", "BOOLEAN", "TIMESTAMP")) {
                assertFailsWith<IllegalArgumentException>(consumer) {
                    DashboardDefinitionDecoder.decode(endpointDefinition(referenceKind, "DATE_RANGE", false,
                        endpointFilter(consumer, ref, "EQUALS"), "null"))
                }
            }
            for (operator in listOf("IN", "NOT_IN")) {
                assertFailsWith<IllegalArgumentException>(operator) {
                    DashboardDefinitionDecoder.decode(endpointDefinition(referenceKind, "DATE_RANGE", false,
                        endpointFilter("DATE", ref, operator), "null"))
                }
            }
            assertFailsWith<IllegalArgumentException> {
                DashboardDefinitionDecoder.decode(endpointDefinition(referenceKind, "DATE_RANGE", false, "", ref))
            }
            assertFailsWith<IllegalArgumentException> {
                DashboardDefinitionDecoder.decode(endpointDefinition(referenceKind, "DATE_RANGE", false, "",
                    """{"start":$ref,"end":{"kind":"TIMESTAMP","value":"2026-09-07T00:00:00Z"}}"""))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            DashboardDefinitionDecoder.decode(definitionWithQuery(
                parameters = """"period":{"type":"DATE_RANGE","nullable":false}""",
                variables = """"periodStart":{"type":"DATE","nullable":false,"initial":{"kind":"PARAMETER","name":"period","endpoint":"start"}}""",
            ))
        }
    }

    @Test
    fun `nullable range endpoint uses filter empty behavior and cannot supply metric time endpoints`() {
        for (referenceKind in listOf("PARAMETER", "VARIABLE")) {
            for (scalarType in listOf("DATE", "TIMESTAMP")) {
                val ref = """{"kind":"$referenceKind","name":"period","endpoint":"end"}"""
                for (behavior in listOf("OMIT", "ERROR")) {
                    val definition = DashboardDefinitionDecoder.decode(endpointDefinition(referenceKind, "${scalarType}_RANGE", true,
                        endpointFilter(scalarType, ref, emptyBehavior = behavior), "null"))
                    val resolved = DashboardQueryValueResolver.resolve((definition.queries.getValue("orders") as DashboardNamedQuery.Semantic).query,
                        if (referenceKind == "PARAMETER") mapOf("period" to JsonNull) else emptyMap(),
                        if (referenceKind == "VARIABLE") mapOf("period" to JsonNull) else emptyMap(),
                        definition.parameters, definition.variables)
                    if (behavior == "OMIT") assertTrue(assertNotNull(resolved).getValue("filters").jsonArray.isEmpty())
                    else assertNull(resolved)
                }
                assertFailsWith<IllegalArgumentException> {
                    DashboardDefinitionDecoder.decode(endpointDefinition(referenceKind, "${scalarType}_RANGE", true,
                        endpointFilter(scalarType, ref), "null"))
                }
                assertFailsWith<IllegalArgumentException> {
                    DashboardDefinitionDecoder.decode(endpointDefinition(referenceKind, "${scalarType}_RANGE", true,
                        "", """{"start":$ref,"end":$ref}"""))
                }
            }
        }
    }

    private fun endpointDefinition(
        referenceKind: String,
        type: String,
        nullable: Boolean,
        filters: String,
        metricTimeRange: String,
    ): JsonObject {
        val suffix = if (type == "TIMESTAMP_RANGE") "T00:00:00Z" else ""
        val initial = if (nullable) "null" else """{"start":"2026-09-01$suffix","end":"2026-09-07$suffix"}"""
        val declaration = """"period":{"type":"$type","nullable":$nullable${if (referenceKind == "VARIABLE") ",\"initial\":$initial" else ""}}"""
        return definitionWithQuery(
            parameters = if (referenceKind == "PARAMETER") declaration else "",
            variables = if (referenceKind == "VARIABLE") declaration else "",
            filter = filters,
            metricTimeRange = metricTimeRange,
        )
    }

    private fun endpointFilter(
        type: String,
        reference: String,
        operator: String = "GREATER_THAN_OR_EQUAL",
        emptyBehavior: String? = null,
    ): String = """{"kind":"$type","dimension":{"semanticModelName":"orders","name":"delivery_date"},"operator":"$operator","value":$reference${emptyBehavior?.let { ",\"emptyBehavior\":\"$it\"" } ?: ""}}"""

    private fun definitionWithQuery(
        parameters: String = "",
        variables: String = "",
        filter: String? = null,
        metrics: String = "\"order_total\"",
        groupBys: String = "",
        metricTimeRange: String = "null",
    ): JsonObject = json.parseToJsonElement(
        """
        {
          "schemaVersion": 1,
          "meta": { "title": "Orders" },
          "parameters": { $parameters },
          "variables": { $variables },
          "queries": {
            "orders": {
              "type": "SEMANTIC", "connectorId": "0195f3b2-5c84-7a44-8cc4-3e9bf4fd10de",
              "query": {
                "schemaVersion": 4,
                "metrics": [$metrics],
                "groupBys": [$groupBys],
                "metricTimeRange": $metricTimeRange,
                "filters": [${filter ?: ""}],
                "orderBy": [],
                "rowLimit": 100
              }
            }
          },
          "widgets": {
            "total": {
              "type": "TABLE",
              "queryName": "orders",
              "columns": [{ "field": "metric.order_total" }]
            }
          },
          "layout": {
            "sections": [{
              "id": "main",
              "height": { "mode": "SCALED", "scale": 1.0 },
              "root": {
                "type": "WIDGET",
                "id": "totalItem",
                "widgetId": "total"
              }
            }]
          }
        }
        """.trimIndent(),
    ).jsonObject

    private fun definitionWithLayoutSeparation(separation: String?): JsonObject {
        val separationField = separation?.let { "\"separation\": \"$it\"," } ?: ""
        return json.parseToJsonElement(
            """
            {
              "schemaVersion": 1,
              "meta": { "title": "Separated summary" },
              "parameters": {},
              "variables": {},
              "queries": {},
              "widgets": {
                "first": { "type": "TEXT", "content": "one" },
                "second": { "type": "TEXT", "content": "two" }
              },
              "layout": {
                "sections": [{
                  "id": "summary",
                  "height": { "mode": "CONTENT" },
                  "root": {
                    "type": "STACK",
                    "id": "summaryRow",
                    "direction": "ROW",
                    $separationField
                    "children": [
                      { "weight": 1, "node": { "type": "WIDGET", "id": "firstItem", "widgetId": "first" } },
                      { "weight": 1, "node": { "type": "WIDGET", "id": "secondItem", "widgetId": "second" } }
                    ]
                  }
                }]
              }
            }
            """.trimIndent(),
        ).jsonObject
    }

    private fun definitionWithChart(
        parameters: String = "",
        parameterInputs: String = "",
        source: String = "export default defineChart({ render() { return {}; } });",
    ): JsonObject = json.parseToJsonElement(
        """
        {
          "schemaVersion": 1,
          "meta": { "title": "Chart" },
          "parameters": { $parameters },
          "variables": {},
          "queries": {
            "orders": {
              "type": "SEMANTIC", "connectorId": "0195f3b2-5c84-7a44-8cc4-3e9bf4fd10de",
              "query": {
                "schemaVersion": 4,
                "metrics": ["order_total"],
                "groupBys": [],
                "metricTimeRange": null,
                "filters": [],
                "orderBy": [],
                "rowLimit": 100
              }
            }
          },
          "widgets": {
            "chart": {
              "type": "CHART",
              "queryNames": ["orders"],
              "module": {
                "inputs": { "parameters": [${parameterInputs.let { if (it.isBlank()) "" else it.split(',').joinToString(",") { "\"$it\"" } }}], "variables": [] },
                "source": ${JsonPrimitive(source)}
              }
            }
          },
          "layout": {
            "sections": [{
              "id": "main",
              "height": { "mode": "SCALED", "scale": 1.0 },
              "root": {
                "type": "WIDGET",
                "id": "chartItem",
                "widgetId": "chart"
              }
            }]
          }
        }
        """.trimIndent(),
    ).jsonObject
}
