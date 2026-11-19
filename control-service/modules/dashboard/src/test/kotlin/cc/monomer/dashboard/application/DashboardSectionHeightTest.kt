package cc.monomer.dashboard.application

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DashboardSectionHeightTest {
    private val text = Json.parseToJsonElement("""{"type":"TEXT","content":"Introduction"}""").jsonObject
    private val input = Json.parseToJsonElement("""{"type":"INPUT","variableName":"label"}""").jsonObject
    private val chart = Json.parseToJsonElement("""{"type":"CHART","queryNames":["orders"],"module":{"inputs":{"parameters":[],"variables":[]},"source":"export default defineChart({ render() { return {}; } });"}}""").jsonObject
    private val table = Json.parseToJsonElement("""{"type":"TABLE","queryName":"orders","columns":[{"field":"metric.order_total"}]}""").jsonObject
    private val content = Json.parseToJsonElement("""{"mode":"CONTENT"}""")
    private val scaled = Json.parseToJsonElement("""{"mode":"SCALED","scale":1}""")

    @Test
    fun `content accepts text input and their row and column combinations`() {
        accepted(definition(mapOf("a" to text), content, widget("a")))
        accepted(definition(mapOf("a" to input), content, widget("a")))
        val row = stack("row", "ROW", listOf(child(widget("a"), 7), child(widget("b"), 5)))
        val column = stack("column", "COLUMN", listOf(child(row), child(widget("c"))))
        accepted(definition(mapOf("a" to text, "b" to input, "c" to text), content, column))
        accepted(definition(mapOf("a" to text), content, stack("row", "ROW", listOf(child(widget("a"), 1), child(spacer("space"), 1)))))
    }

    @Test
    fun `height mode follows allotted descendants and spacer direct parent`() {
        for (box in listOf(chart, table)) {
            val widgets = mapOf("a" to box)
            rejected(definition(widgets, content, widget("a")), "layout.sections[0].height", "a SPACER directly inside a COLUMN; use SCALED")
            for (scale in listOf("0.3", "1", "1.0", "1.50", "10.0")) {
                accepted(definition(widgets, Json.parseToJsonElement("""{"mode":"SCALED","scale":$scale}"""), widget("a")))
            }
        }
        val widgets = mapOf("a" to text)
        rejected(definition(widgets, scaled, widget("a")), "layout.sections[0].height", "a SPACER directly inside a COLUMN; use CONTENT")
        val columnSpacer = stack("column", "COLUMN", listOf(child(widget("a")), child(spacer("space"), 1)))
        rejected(definition(widgets, content, columnSpacer), "layout.sections[0].height", "a SPACER directly inside a COLUMN; use SCALED")
        accepted(definition(widgets, scaled, columnSpacer))
        val outerRow = stack("outerRow", "ROW", listOf(child(columnSpacer, 1), child(widget("b"), 1)))
        rejected(definition(mapOf("a" to text, "b" to input), content, outerRow), "layout.sections[0].height", "a SPACER directly inside a COLUMN; use SCALED")
        accepted(definition(mapOf("a" to text, "b" to input), scaled, outerRow))
        val chartAndTable = stack("boxes", "ROW", listOf(child(widget("a"), 1), child(widget("b"), 1)))
        rejected(definition(mapOf("a" to chart, "b" to table), content, chartAndTable), "layout.sections[0].height", "a SPACER directly inside a COLUMN; use SCALED")
        accepted(definition(mapOf("a" to chart, "b" to table), scaled, chartAndTable))
        val rowSpacer = stack("row", "ROW", listOf(child(widget("a"), 1), child(spacer("space"), 1)))
        val outerColumn = stack("column", "COLUMN", listOf(child(rowSpacer), child(widget("b"))))
        accepted(definition(mapOf("a" to text, "b" to input), content, outerColumn))
        rejected(definition(mapOf("a" to text, "b" to input), scaled, outerColumn), "layout.sections[0].height", "a SPACER directly inside a COLUMN; use CONTENT")
    }

    @Test
    fun `row and column weight errors identify the child and fix`() {
        val widgets = mapOf("a" to text, "b" to chart)
        rejected(definition(widgets, scaled, stack("column", "COLUMN", listOf(child(widget("a"), 1), child(widget("b"), 1)))), "layout stack column.children[0]", "remove weight")
        rejected(definition(widgets, scaled, stack("column", "COLUMN", listOf(child(widget("a")), child(widget("b"))))), "layout stack column.children[1]", "a SPACER directly inside a COLUMN needs weight")
        rejected(definition(widgets, scaled, stack("row", "ROW", listOf(child(widget("a"), 1), child(widget("b"))))), "layout stack row.children[1]", "need weight")
    }

    @Test
    fun `zero sole and multiple weights use canonical form per stack`() {
        accepted(definition(mapOf("a" to text, "b" to input), content, stack("column", "COLUMN", listOf(child(widget("a")), child(widget("b"))))))
        val mixed = mapOf("a" to text, "b" to chart)
        accepted(definition(mixed, scaled, stack("column", "COLUMN", listOf(child(widget("a")), child(widget("b"), 1)))))
        rejected(definition(mixed, scaled, stack("column", "COLUMN", listOf(child(widget("a")), child(widget("b"), 3)))), "layout stack column", "sole weight must be 1")
        val boxes = mapOf("a" to chart, "b" to table)
        accepted(definition(boxes, scaled, stack("column", "COLUMN", listOf(child(widget("a"), 1), child(widget("b"), 4)))))
        rejected(definition(boxes, scaled, stack("column", "COLUMN", listOf(child(widget("a"), 2), child(widget("b"), 4)))), "layout stack column", "divide all weights")
    }

    @Test
    fun `height is required closed discriminated and bounded`() {
        for (height in listOf(
            "null", "1", "{}", """{"mode":"AUTO"}""", """{"mode":null}""",
            """{"mode":"CONTENT","scale":1}""", """{"mode":"CONTENT","extra":true}""",
            """{"mode":"SCALED"}""", """{"mode":"SCALED","scale":1,"extra":true}""",
        ) + listOf("null", "true", "\"1\"", "0.2", "10.1", "0.35").map { """{"mode":"SCALED","scale":$it}""" }) {
            assertFails(height) { DashboardDefinitionDecoder.decode(definition(mapOf("a" to chart), Json.parseToJsonElement(height), widget("a"))) }
        }
        val valid = definition(mapOf("a" to text), content, widget("a")).toString()
        for (invalid in listOf(
            valid.replace("\"height\":{\"mode\":\"CONTENT\"},", ""),
            valid.replace("\"height\":{\"mode\":\"CONTENT\"}", "\"heightScale\":1"),
            valid.replace("\"height\":", "\"heightScale\":1,\"height\":"),
        )) assertFails { DashboardDefinitionDecoder.decode(invalid) }
    }

    private fun accepted(document: JsonObject) = assertEquals(document, DashboardDefinitionDecoder.decode(document).document)
    private fun rejected(document: JsonObject, location: String, fix: String) {
        val message = assertFailsWith<InvalidDashboardDefinitionException> { DashboardDefinitionDecoder.decode(document) }.message.orEmpty()
        assertTrue(location in message, message)
        assertTrue(fix in message, message)
    }
    private fun widget(id: String) = Json.parseToJsonElement("""{"type":"WIDGET","id":"${id}Node","widgetId":"$id"}""").jsonObject
    private fun spacer(id: String) = Json.parseToJsonElement("""{"type":"SPACER","id":"$id"}""").jsonObject
    private fun child(node: JsonObject, weight: Int? = null) = JsonObject(mapOf("node" to node) + if (weight == null) emptyMap() else mapOf("weight" to JsonPrimitive(weight)))
    private fun stack(id: String, direction: String, children: List<JsonObject>) = JsonObject(mapOf("type" to JsonPrimitive("STACK"), "id" to JsonPrimitive(id), "direction" to JsonPrimitive(direction), "children" to JsonArray(children)))
    private fun definition(widgets: Map<String, JsonObject>, height: JsonElement, root: JsonObject): JsonObject {
        val document = Json.parseToJsonElement("""{
          "schemaVersion":1,"meta":{"title":"Height"},"parameters":{},
          "variables":{"label":{"type":"TEXT","nullable":false,"initial":"initial"}},
          "queries":{"orders":{"type":"SEMANTIC","connectorId":"0195f3b2-5c84-7a44-8cc4-3e9bf4fd10de","query":{"schemaVersion":4,"metrics":["order_total"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":100}}},
          "widgets":{},"layout":{"sections":[]}
        }""").jsonObject
        val section = JsonObject(mapOf("id" to JsonPrimitive("main"), "height" to height, "root" to root))
        val layout = JsonObject(mapOf("sections" to JsonArray(listOf(section))))
        val queries = if (widgets.values.any { it["type"] == JsonPrimitive("CHART") || it["type"] == JsonPrimitive("TABLE") }) document.getValue("queries") else JsonObject(emptyMap())
        return JsonObject(document + mapOf("widgets" to JsonObject(widgets), "queries" to queries, "layout" to layout))
    }
}
