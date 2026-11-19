package cc.monomer.dashboard.application

import cc.monomer.dashboard.domain.DashboardResolvedQuery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DashboardScheduleDecoderTest {
    private fun document(type: String, nullable: Boolean, binding: String): String = """
        {"schemaVersion":1,"meta":{"title":"Schedules"},"parameters":{"input":{"type":"$type","nullable":$nullable}},"variables":{},"queries":{},"widgets":{},"layout":{"sections":[]},"schedules":{"daily":{"cron":"0 9 * * *","timeZone":"UTC","parameters":{"input":$binding}}}}
    """.trimIndent()

    @Test fun `schedule map is optional or empty and valid binding types decode`() {
        val raw = Json.parseToJsonElement(document("TEXT", false, """{"type":"LITERAL","value":"US"}""")).jsonObject
        assertEquals(emptyMap(), DashboardDefinitionDecoder.decode(JsonObject(raw - "schedules")).schedules)
        assertEquals(emptyMap(), DashboardDefinitionDecoder.decode(JsonObject(raw + ("schedules" to JsonObject(emptyMap())))).schedules)
        for ((type, binding) in listOf(
            "DATE" to """{"type":"RELATIVE_DATE","offsetDays":0}""",
            "DATE_RANGE" to """{"type":"CALENDAR_RANGE","unit":"DAY","offset":1}""",
            "TIMESTAMP" to """{"type":"SCHEDULED_TIME"}""",
            "NUMBER" to """{"type":"LITERAL","value":"99999999999999999999.123456789012345678"}""",
            "TIMESTAMP_RANGE" to """{"type":"LITERAL","value":{"start":"2026-01-01T00:00:00Z","end":"2026-01-02T00:00:00Z"}}""",
        )) assertEquals(setOf("daily"), DashboardDefinitionDecoder.decode(document(type, false, binding)).schedules.keys)
    }

    @Test fun `strict bindings reject wrong fields enum integer type null decimal and dates`() {
        for ((type, binding) in listOf(
            "TEXT" to """{"type":"RELATIVE_DATE","offsetDays":0}""",
            "DATE" to """{"type":"RELATIVE_DATE","offsetDays":"1"}""",
            "DATE" to """{"type":"RELATIVE_DATE","offsetDays":1.0}""",
            "DATE" to """{"type":"RELATIVE_DATE","offsetDays":1e0}""",
            "DATE" to """{"type":"RELATIVE_DATE","offsetDays":2147483648}""",
            "DATE" to """{"type":"RELATIVE_DATE"}""",
            "DATE" to """{"type":"RELATIVE_DATE","offsetDays":0,"value":"today"}""",
            "DATE_RANGE" to """{"type":"CALENDAR_RANGE","unit":"YEAR","offset":0}""",
            "DATE" to """{"type":"CALENDAR_RANGE","unit":"DAY","offset":0}""",
            "DATE" to """{"type":"SCHEDULED_TIME"}""",
            "TIMESTAMP" to """{"type":"SCHEDULED_TIME","offset":0}""",
            "TEXT" to """{"type":"UNKNOWN"}""",
            "TEXT" to """{"source":"LITERAL","value":"US"}""",
            "TEXT" to """{"type":"LITERAL","value":null}""",
            "NUMBER" to """{"type":"LITERAL","value":100.5}""",
            "NUMBER" to """{"type":"LITERAL","value":"100.50"}""",
            "NUMBER" to """{"type":"LITERAL","value":"-0"}""",
            "DATE" to """{"type":"LITERAL","value":"2026-02-29"}""",
        )) assertFailsWith<IllegalArgumentException>("$type $binding") { DashboardDefinitionDecoder.decode(document(type, false, binding)) }
        DashboardDefinitionDecoder.decode(document("TEXT", true, """{"type":"LITERAL","value":null}"""))
    }

    @Test fun `closed schedule shape names and complete parameter map`() {
        val original = document("TEXT", true, """{"type":"LITERAL","value":null}""")
        for (invalid in listOf(
            original.replace("\"daily\"", "\"__proto__\""),
            original.replace("\"daily\"", "\"bad-name\""),
            original.replace("\"daily\"", "\"${"a".repeat(129)}\""),
            original.replace("\"cron\":", "\"enabled\":true,\"cron\":"),
            original.replace("\"cron\":\"0 9 * * *\"", "\"cron\":9"),
            original.replace("\"timeZone\":\"UTC\"", "\"timeZone\":\"Invalid/Zone\""),
            original.replace("\"input\":{\"type\":\"LITERAL\",\"value\":null}", "\"other\":{\"type\":\"LITERAL\",\"value\":null}"),
            original.replace("\"input\":{\"type\":\"LITERAL\",\"value\":null}", ""),
            original.replace("\"schedules\":{", "\"schedules\":null,\"bad\":{")
        )) assertFailsWith<IllegalArgumentException> { DashboardDefinitionDecoder.decode(invalid) }
    }

    @Test fun `duplicate keys are rejected throughout raw document without confusing objects in arrays`() {
        val original = document("TEXT", false, """{"type":"LITERAL","value":"{} [ ] : \\\""}""")
        DashboardDefinitionDecoder.decode(original)
        assertFailsWith<IllegalArgumentException> { DashboardDefinitionDecoder.decode(original.replace("\"cron\":", "\"cron\":\"0 0 * * *\",\"cron\":")) }
        assertFailsWith<IllegalArgumentException> { DashboardDefinitionDecoder.decode(original.replace("\"daily\":", "\"daily\":{},\"d\\u0061ily\":")) }
    }
}
