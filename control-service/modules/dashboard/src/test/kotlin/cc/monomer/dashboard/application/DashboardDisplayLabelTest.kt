package cc.monomer.dashboard.application

import cc.monomer.dashboard.domain.DashboardResolvedQuery

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DashboardDisplayLabelTest {
    @Test
    fun `chart and table titles are optional display fields preserved in the definition document`() {
        val untitled = DashboardDefinitionDecoder.decode(document())
        assertNull(untitled.widgets.getValue("chart").document["title"])
        assertNull(untitled.widgets.getValue("table").document["title"])

        val titled = DashboardDefinitionDecoder.decode(document(chartTitle = "\"Revenue by region\"", tableTitle = "\"Top regions\""))
        assertEquals("Revenue by region", titled.widgets.getValue("chart").document.getValue("title").jsonPrimitive.content)
        assertEquals("Top regions", titled.widgets.getValue("table").document.getValue("title").jsonPrimitive.content)
        assertEquals("Top regions", titled.document.getValue("widgets").jsonObject.getValue("table").jsonObject.getValue("title").jsonPrimitive.content)
        DashboardDefinitionDecoder.decode(document(chartTitle = "\"${"x".repeat(120)}\""))
    }

    @Test
    fun `widget titles reject empty untrimmed multi-line oversized non-string and unsupported widget placement`() {
        val invalid = listOf("\"\"", "\" Revenue\"", "\"Revenue \"", "\"Revenue\\nby region\"", "\"${"x".repeat(121)}\"", "null", "1")
        for (title in invalid) {
            assertFailsWith<IllegalArgumentException>("chart $title") { DashboardDefinitionDecoder.decode(document(chartTitle = title)) }
            assertFailsWith<IllegalArgumentException>("table $title") { DashboardDefinitionDecoder.decode(document(tableTitle = title)) }
        }
        assertFailsWith<IllegalArgumentException> {
            DashboardDefinitionDecoder.decode(document().replace("\"type\":\"TEXT\",", "\"type\":\"TEXT\",\"title\":\"Notes\","))
        }
        assertFailsWith<IllegalArgumentException> {
            DashboardDefinitionDecoder.decode(document().replace("\"type\":\"INPUT\",", "\"type\":\"INPUT\",\"title\":\"Region\","))
        }
    }

    @Test
    fun `emptyLabel is accepted on choice controls of nullable parameters and INPUT variables`() {
        val definition = DashboardDefinitionDecoder.decode(
            document(parameterControl = choice("\"All regions\""), inputControl = choice("\"Any channel\"")),
        )
        assertEquals("All regions", definition.parameters.getValue("region").control!!.getValue("emptyLabel").jsonPrimitive.content)
        assertEquals(
            "Any channel",
            definition.widgets.getValue("input").document.getValue("control").jsonObject.getValue("emptyLabel").jsonPrimitive.content,
        )
        DashboardDefinitionDecoder.decode(document(parameterControl = choice(null), inputControl = choice(null)))
    }

    @Test
    fun `emptyLabel is rejected for non-nullable values free controls and invalid text`() {
        assertFailsWith<IllegalArgumentException> {
            DashboardDefinitionDecoder.decode(document(parameterControl = choice("\"All regions\""), parameterNullable = false))
        }
        assertFailsWith<IllegalArgumentException> {
            DashboardDefinitionDecoder.decode(document(inputControl = choice("\"Any channel\""), variableNullable = false))
        }
        assertFailsWith<IllegalArgumentException> {
            DashboardDefinitionDecoder.decode(document(parameterControl = """{"placeholder":"Region","emptyLabel":"All regions"}"""))
        }
        for (label in listOf("\"\"", "\" All\"", "\"All\\r\"", "\"${"x".repeat(121)}\"", "null", "true")) {
            assertFailsWith<IllegalArgumentException>(label) { DashboardDefinitionDecoder.decode(document(parameterControl = choice(label))) }
            assertFailsWith<IllegalArgumentException>(label) { DashboardDefinitionDecoder.decode(document(inputControl = choice(label))) }
        }
    }

    private fun choice(emptyLabel: String?): String =
        if (emptyLabel == null) """{"options":[{"value":"north"},{"value":"south"}]}"""
        else """{"options":[{"value":"north"},{"value":"south"}],"emptyLabel":$emptyLabel}"""

    private fun document(
        chartTitle: String? = null,
        tableTitle: String? = null,
        parameterControl: String = choice(null),
        parameterNullable: Boolean = true,
        inputControl: String = choice(null),
        variableNullable: Boolean = true,
    ): String {
        val chartTitleField = chartTitle?.let { "\"title\":$it," }.orEmpty()
        val tableTitleField = tableTitle?.let { "\"title\":$it," }.orEmpty()
        val variableInitial = if (variableNullable) "null" else "\"north\""
        return """
            {
              "schemaVersion":1,"meta":{"title":"Display labels"},
              "parameters":{"region":{"type":"TEXT","nullable":$parameterNullable,"control":$parameterControl}},
              "variables":{"channel":{"type":"TEXT","nullable":$variableNullable,"initial":$variableInitial}},
              "queries":{
                "byRegion":{"type": "SEMANTIC", "connectorId":"0195f3b2-5c84-7a44-8cc4-3e9bf4fd10de","query":{"schemaVersion":4,"metrics":["order_total"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":100}},
                "topRegions":{"type": "SEMANTIC", "connectorId":"0195f3b2-5c84-7a44-8cc4-3e9bf4fd10de","query":{"schemaVersion":4,"metrics":["order_count"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":100}}
              },
              "widgets":{
                "chart":{"type":"CHART",$chartTitleField"queryNames":["byRegion"],"module":{"inputs":{"parameters":["region"],"variables":["channel"]},"source":"export default defineChart({ render() { return {}; } });"}},
                "table":{"type":"TABLE",$tableTitleField"queryName":"topRegions","columns":[{"field":"metric.order_count"}]},
                "notes":{"type":"TEXT","content":"Notes"},
                "input":{"type":"INPUT","variableName":"channel","control":$inputControl}
              },
              "layout":{"sections":[
                {"id":"main","height":{"mode":"SCALED","scale":1.0},"root":{"type":"STACK","id":"stack","direction":"ROW","children":[
                  {"weight":1,"node":{"type":"WIDGET","id":"chartItem","widgetId":"chart"}},
                  {"weight":1,"node":{"type":"WIDGET","id":"tableItem","widgetId":"table"}},
                  {"weight":1,"node":{"type":"WIDGET","id":"notesItem","widgetId":"notes"}},
                  {"weight":1,"node":{"type":"WIDGET","id":"inputItem","widgetId":"input"}}
                ]}}
              ]}
            }
        """.trimIndent()
    }
}
