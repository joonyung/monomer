package cc.monomer.dashboard.application

import cc.monomer.dashboard.domain.DashboardResolvedQuery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DashboardTableDefinitionTest {
    private val json = Json { explicitNulls = true }

    @Test
    fun `primary and ordered auxiliary queries form the complete table reference set`() {
        val definition = DashboardDefinitionDecoder.decode(document())
        val table = definition.widgets.getValue("table")
        assertEquals(listOf("orders", "daily"), table.orderedQueryNames)
        assertEquals(setOf("orders", "daily"), table.queryNames)
        assertEquals(document().getValue("widgets").jsonObject.getValue("table"), table.document)
    }

    @Test
    fun `table without module remains a raw table`() {
        val definition = DashboardDefinitionDecoder.decode(document(module = "", auxiliaryQuery = false))
        assertEquals(listOf("orders"), definition.widgets.getValue("table").orderedQueryNames)
    }

    @Test
    fun `rejects duplicate primary auxiliary and undeclared inputs`() {
        listOf(
            module(queries = "\"orders\""),
            module(queries = "\"daily\",\"daily\""),
            module(queries = "\"missing\""),
            module(parameters = "\"missing\""),
            module(variables = "\"missing\""),
            module(parameters = "\"period\",\"period\""),
            module(variables = "\"threshold\",\"threshold\""),
        ).forEach { invalidModule ->
            assertFailsWith<IllegalArgumentException>(invalidModule) {
                DashboardDefinitionDecoder.decode(document(module = invalidModule))
            }
        }
    }

    @Test
    fun `requires complete closed module declarations and nonempty source`() {
        listOf(
            "null",
            "{}",
            """{"inputs":{"parameters":[],"variables":[]},"source":"source"}""",
            """{"inputs":{"queries":["daily"],"parameters":[],"variables":[],"actions":[]},"source":"source"}""",
            """{"inputs":{"queries":["daily"],"parameters":[],"variables":[]},"source":"","setup":{}}""",
            module(source = ""),
        ).forEach { invalidModule ->
            assertFailsWith<IllegalArgumentException>(invalidModule) {
                DashboardDefinitionDecoder.decode(document(module = invalidModule))
            }
        }
    }

    @Test
    fun `bounds host size hints and measures source in UTF-8 bytes`() {
        DashboardDefinitionDecoder.decode(document(width = "80", height = "32"))
        DashboardDefinitionDecoder.decode(document(width = "640", height = "160"))
        DashboardDefinitionDecoder.decode(document(module = module(source = "é".repeat(131_072))))
        listOf("79", "641", "80.5", "null", "\"80\"").forEach { width ->
            assertFailsWith<IllegalArgumentException> { DashboardDefinitionDecoder.decode(document(width = width)) }
        }
        listOf("31", "161", "32.5", "null", "\"32\"").forEach { height ->
            assertFailsWith<IllegalArgumentException> { DashboardDefinitionDecoder.decode(document(height = height)) }
        }
        assertFailsWith<IllegalArgumentException> {
            DashboardDefinitionDecoder.decode(document(module = module(source = "é".repeat(131_073))))
        }
    }

    private fun module(
        queries: String = "\"daily\"",
        parameters: String = "\"period\"",
        variables: String = "\"threshold\"",
        source: String = "export default defineTable({ render() { return {}; } });",
    ): String = """{"inputs":{"queries":[$queries],"parameters":[$parameters],"variables":[$variables]},"source":${JsonPrimitive(source)}}"""

    private fun document(
        module: String = module(),
        auxiliaryQuery: Boolean = true,
        width: String = "180",
        height: String = "56",
    ): JsonObject {
        val query = """{"type": "SEMANTIC", "connectorId":"0195f3b2-5c84-7a44-8cc4-3e9bf4fd10de","query":{"schemaVersion":4,"metrics":["order_total"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":100}}"""
        return json.parseToJsonElement(
            """{
              "schemaVersion":1,"meta":{"title":"Table"},
              "parameters":{"period":{"type":"TEXT","nullable":false}},
              "variables":{"threshold":{"type":"NUMBER","nullable":false,"initial":"10"}},
              "queries":{"orders":$query ${if (auxiliaryQuery) ",\"daily\":$query" else ""}},
              "widgets":{"table":{"type":"TABLE","queryName":"orders","columns":[{"field":"metric.order_total","header":"Total","width":$width}],"rowHeight":$height ${if (module.isNotEmpty()) ",\"module\":$module" else ""}}},
              "layout":{"sections":[{"id":"main","height": { "mode": "SCALED", "scale": 1.0 },"root":{"type": "WIDGET","id":"tableItem","widgetId": "table"}}]}
            }""",
        ).jsonObject
    }
}
