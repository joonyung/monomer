@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.application

import cc.monomer.dashboard.domain.DashboardResolvedQuery

import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardSessionId
import cc.monomer.dashboard.domain.DashboardDefinition
import cc.monomer.dashboard.domain.DashboardSession
import cc.monomer.dashboard.domain.DashboardValueType
import cc.monomer.dashboard.domain.DashboardValueValidator
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal data class ControlUiOnlyCase(val type: String, val control: String, val value: String)

internal val controlUiOnlyCases = listOf(
    ControlUiOnlyCase("TEXT", """{"options":[{"value":"KR"},{"value":"JP"}]}""", "\"CN\""),
    ControlUiOnlyCase("NUMBER", """{"min":"0","max":"100"}""", "\"150\""),
    ControlUiOnlyCase("TEXT_SET", """{"options":[{"value":"KR"},{"value":"JP"}]}""", """["JP","CN","KR"]"""),
    ControlUiOnlyCase("NUMBER_SET", """{"options":[{"value":"1"},{"value":"2"}]}""", """["2","150","1"]"""),
    ControlUiOnlyCase("TIMESTAMP_SET", """{"options":[{"value":"2026-01-01T00:00:00Z"}]}""", """["2026-01-02T09:00:00+09:00","2026-01-01T09:00:00+09:00"]"""),
    ControlUiOnlyCase("DATE_RANGE", """{"min":"2026-06-01","max":"2026-06-30"}""", """{"start":"2026-01-01","end":"2026-12-31"}"""),
    ControlUiOnlyCase("TIMESTAMP_RANGE", """{"min":"2026-06-01T00:00:00Z","max":"2026-06-30T00:00:00Z"}""", """{"start":"2026-01-01T09:00:00+09:00","end":"2026-12-31T00:00:00Z"}"""),
)

internal fun controlUiOnlyDefinition(case: ControlUiOnlyCase): DashboardDefinition {
    fun query(binding: String, kind: String): String {
        val reference = """{"kind":"$kind","name":"$binding"}"""
        val range = if (case.type.endsWith("_RANGE")) reference else "null"
        val filters = if (case.type.endsWith("_RANGE")) "" else {
            val scalar = case.type.removeSuffix("_SET")
            val operator = if (case.type.endsWith("_SET")) "IN" else "EQUALS"
            """{"kind":"$scalar","dimension":{"semanticModelName":"orders","name":"value"},"operator":"$operator","value":$reference}"""
        }
        return """{"type": "SEMANTIC", "connectorId":"0195f3b2-5c84-7a44-8cc4-3e9bf4fd10de","query":{"schemaVersion":4,"metrics":["order_total"],"groupBys":[],"metricTimeRange":$range,"filters":[$filters],"orderBy":[],"rowLimit":100}}"""
    }
    return DashboardDefinitionDecoder.decode(Json.parseToJsonElement("""
        {
          "schemaVersion":1,"meta":{"title":"UI-only controls"},
          "parameters":{"p":{"type":"${case.type}","nullable":false,"control":${case.control}}},
          "variables":{"v":{"type":"${case.type}","nullable":false,"initial":${case.value}}},
          "queries":{"parameterQuery":${query("p", "PARAMETER")},"variableQuery":${query("v", "VARIABLE")}},
          "widgets":{
            "chart":{"type":"CHART","queryNames":["parameterQuery","variableQuery"],"module":{"inputs":{"parameters":["p"],"variables":["v"]},"source":"export default defineChart({ render() { return {}; } });"}},
            "input":{"type":"INPUT","variableName":"v","control":${case.control}}
          },
          "layout":{"sections":[
            {"id":"chartSection","height": { "mode": "SCALED", "scale": 1.0 },"root":{"type": "WIDGET","id":"chartItem","widgetId": "chart"}},
            {"id":"inputSection","height": { "mode": "CONTENT" },"root":{"type": "WIDGET","id":"inputItem","widgetId": "input"}}
          ]}
        }
    """).jsonObject)
}

internal fun assertControlUiOnlyQueryValue(query: JsonObject, value: JsonElement, type: String) {
    if (type.endsWith("_RANGE")) {
        val range = query.getValue("metricTimeRange").jsonObject
        assertEquals(value.jsonObject.getValue("start"), range.getValue("start").jsonObject.getValue("value"))
        assertEquals(value.jsonObject.getValue("end"), range.getValue("end").jsonObject.getValue("value"))
    } else {
        val expected = if (value is JsonArray) value else JsonArray(listOf(value))
        assertEquals(expected, query.getValue("filters").jsonArray.single().jsonObject.getValue("values"))
    }
}

class DashboardControlUiOnlyTest {
    @Test
    fun `stored SET values restore in submitted or old option order without normalization`() {
        for (values in listOf("""["JP","KR"]""", """["KR","JP"]""", """["CN","JP","KR"]""")) {
            val definition = controlUiOnlyDefinition(controlUiOnlyCases.first { it.type == "TEXT_SET" }.copy(value = values))
            val value = Json.parseToJsonElement(values)
            val at = Instant.parse("2026-09-01T00:00:00Z")
            val restored = DashboardSession(
                DashboardSessionId.generate(), WorkspaceId.generate(), DashboardId.generate(), definition,
                mapOf("p" to value), at, at,
            )
            assertEquals(value, restored.parameterBindings.getValue("p"))
            assertEquals(value, restored.initialVariables.getValue("v"))
        }
    }

    @Test
    fun `typed malformed null empty duplicate and reversed values remain rejected`() {
        val invalidValues = listOf(
            "BOOLEAN" to "\"true\"", "NUMBER_SET" to "[150]",
            "TEXT" to "150", "NUMBER" to "150", "NUMBER" to "\"hello\"", "NUMBER" to "\"1.0\"", "NUMBER" to "\"NaN\"", "TEXT" to "null",
            "DATE" to "\"2026-02-30\"", "TIMESTAMP" to "\"2026-01-01\"",
            "TEXT_SET" to "[]", "TEXT_SET" to "[\"KR\",\"KR\"]",
            "NUMBER_SET" to "[\"1\",\"1\"]",
            "TIMESTAMP_SET" to "[\"2026-01-01T00:00:00Z\",\"2026-01-01T09:00:00+09:00\"]",
            "DATE_RANGE" to "{\"start\":\"2026-02-02\",\"end\":\"2026-02-01\"}",
            "DATE_RANGE" to "{\"start\":\"2026-02-01\"}",
            "TIMESTAMP_RANGE" to "{\"start\":\"2026-01-01T10:00:00+09:00\",\"end\":\"2026-01-01T00:00:00Z\"}",
            "DATE_RANGE" to "\"2026-01-01\"", "TIMESTAMP_RANGE" to "[\"2026-01-01T00:00:00Z\"]",
            "DATE_SET" to "\"2026-01-01\"", "NUMBER_SET" to "{\"value\":\"1\"}",
        )
        for ((type, value) in invalidValues) {
            assertFailsWith<IllegalArgumentException>("$type $value") {
                DashboardValueValidator.requireValid(Json.parseToJsonElement(value), DashboardValueType.valueOf(type), false, "test value")
            }
        }
    }

    @Test
    fun `unknown Variable bindings and INPUT references remain rejected`() {
        val definition = controlUiOnlyDefinition(controlUiOnlyCases.first())
        val at = Instant.parse("2026-09-01T00:00:00Z")
        val opened = DashboardSession.open(
            DashboardSessionId.generate(), WorkspaceId.generate(), DashboardId.generate(), definition,
            mapOf("p" to Json.parseToJsonElement("\"CN\"")), at,
        )
        assertFailsWith<IllegalArgumentException> {
            opened.validateVariables(opened.initialVariables + ("unknown" to Json.parseToJsonElement("\"CN\"")))
        }
        assertFailsWith<IllegalArgumentException> {
            DashboardDefinitionDecoder.decode(definition.document.toString().replace("\"variableName\":\"v\"", "\"variableName\":\"unknown\""))
        }
        assertFailsWith<IllegalArgumentException> {
            controlUiOnlyDefinition(controlUiOnlyCases.first().copy(control = """{"options":[{"value":150}]}"""))
        }
    }

    @Test
    fun `control definitions still reject incompatible mixed duplicate or reversed fields`() {
        val invalidControls = listOf(
            "TEXT" to "{\"options\":[{\"value\":\"KR\"}],\"placeholder\":\"Region\"}",
            "NUMBER" to "{\"min\":\"100\",\"max\":\"0\"}",
            "NUMBER" to "{\"options\":[{\"value\":\"1\"},{\"value\":\"1\"}]}",
            "TIMESTAMP" to "{\"options\":[{\"value\":\"2026-01-01T00:00:00Z\"},{\"value\":\"2026-01-01T09:00:00+09:00\"}]}",
            "TEXT" to "{\"min\":\"A\"}", "DATE" to "{\"min\":\"2026-02-30\"}",
            "TEXT" to "{\"options\":{\"value\":\"KR\"}}", "TEXT" to "{\"options\":[\"KR\"]}",
        )
        for ((type, control) in invalidControls) {
            assertFailsWith<IllegalArgumentException>("$type $control") {
                DashboardValueValidator.requireControl(Json.parseToJsonElement(control).jsonObject, DashboardValueType.valueOf(type), false, "test control")
            }
        }
        DashboardValueValidator.requireControl(
            Json.parseToJsonElement("""{"min":"2026-01-01T09:00:00+09:00","max":"2026-01-01T00:00:00Z"}""").jsonObject,
            DashboardValueType.TIMESTAMP, false, "equal instant bounds",
        )
        DashboardValueValidator.requireValid(
            Json.parseToJsonElement("""{"start":"2026-01-01T09:00:00+09:00","end":"2026-01-01T00:00:00Z"}"""),
            DashboardValueType.TIMESTAMP_RANGE, false, "equal instant range",
        )
    }
}
