package cc.monomer.dashboard.domain

import cc.monomer.dashboard.domain.DashboardResolvedQuery

import cc.monomer.dashboard.application.DashboardDefinitionDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DashboardScheduleDefinitionTest {
    private fun schedule(cron: String, zone: String = "UTC") = DashboardScheduleDefinition.decode(
        JsonObject(mapOf("cron" to JsonPrimitive(cron), "timeZone" to JsonPrimitive(zone), "parameters" to JsonObject(emptyMap()))), emptyMap(),
    )
    private fun next(cron: String, after: String, expected: String, zone: String = "UTC") =
        assertEquals(Instant.parse(expected), schedule(cron, zone).nextAfter(Instant.parse(after)))

    @Test fun `pinned cron utils UNIX parser and next candidate contract`() {
        val parser = com.cronutils.parser.CronParser(com.cronutils.model.definition.CronDefinitionBuilder.instanceDefinitionFor(com.cronutils.model.CronType.UNIX))
        val cron = parser.parse("*/15 9-17 * JAN MON-FRI").also { it.validate() }
        val time = com.cronutils.model.time.ExecutionTime.forCron(cron)
        assertEquals(Instant.parse("2026-01-02T09:15:00Z"), time.nextExecution(Instant.parse("2026-01-02T09:00:00Z").atZone(java.time.ZoneOffset.UTC)).get().toInstant())
        assertFailsWith<IllegalArgumentException> { parser.parse("*/0 * * * *").validate() }
    }

    @Test fun `cron numeric names lists ranges steps and Sunday aliases`() {
        next("*/15 9-17 * JAN,MAR MON-FRI", "2026-01-02T09:00:00Z", "2026-01-02T09:15:00Z")
        next("0,35 9,18 * * *", "2026-01-02T09:35:00Z", "2026-01-02T18:00:00Z")
        next("*/100 9 * * *", "2026-01-02T00:00:00Z", "2026-01-02T09:00:00Z")
        next("0 9 * * 5-7", "2026-09-19T09:00:00Z", "2026-09-20T09:00:00Z")
        next("*/35 9 * * *", "2026-01-02T09:00:00Z", "2026-01-02T09:35:00Z")
        next("0 0 * JAN-MAR/2 *", "2026-01-31T00:00:00Z", "2026-03-01T00:00:00Z")
        next("0-45/15 9 * * *", "2026-01-02T09:16:00Z", "2026-01-02T09:30:00Z")
        for (day in listOf("0", "7", "SUN")) next("0 9 * * $day", "2026-09-19T00:00:00Z", "2026-09-20T09:00:00Z")
    }
    @Test fun `Unix date OR and wildcard intersection`() {
        next("0 9 1 * MON", "2026-09-01T09:00:00Z", "2026-09-07T09:00:00Z")
        next("0 9 1 * MON", "2026-08-31T09:00:00Z", "2026-09-01T09:00:00Z")
        next("0 9 */2 * MON", "2026-09-07T09:00:00Z", "2026-09-21T09:00:00Z")
        next("0 9 1 * *", "2026-09-01T09:00:00Z", "2026-10-01T09:00:00Z")
    }
    @Test fun `missing dates leap days and century gaps`() {
        next("0 0 31 * *", "2026-04-01T00:00:00Z", "2026-05-31T00:00:00Z")
        next("0 0 29 FEB *", "2096-02-29T00:00:00Z", "2104-02-29T00:00:00Z")
        next("0 0 29 FEB *", "2396-02-29T00:00:00Z", "2400-02-29T00:00:00Z")
    }
    @Test fun `DST gap skips and overlap fires earliest instant once`() {
        next("30 2 * * *", "2026-03-07T07:30:00Z", "2026-03-09T06:30:00Z", "America/New_York")
        next("30 1 * * *", "2026-11-01T00:00:00Z", "2026-11-01T05:30:00Z", "America/New_York")
        next("30 1 * * *", "2026-11-01T05:30:00Z", "2026-11-02T06:30:00Z", "America/New_York")
        next("30 1 * * *", "2026-11-01T06:15:00Z", "2026-11-02T06:30:00Z", "America/New_York")
    }
    @Test fun `invalid cron and impossible calendars fail`() {
        for (cron in listOf("0 0 30 FEB *", "0 0 31 APR *", "0 0 0 * *", "60 * * * *", "0 24 * * *", "0 0 * 13 *", "0 0 * * 8", "*/0 * * * *", "5/2 * * * *", "9-1 * * * *", "0 0 * * ?", "0 0 L * *", "0 0 * * MON#2", "@daily", "0 0 0 * * *", "0 0 * * * # comment")) {
            assertFailsWith<IllegalArgumentException>(cron) { schedule(cron) }
        }
        assertFailsWith<IllegalArgumentException> { schedule("0 0 * * *", "+09:00") }
    }
    @Test fun `bindings use scheduled local date complete months weeks and exact literals`() {
        val declarationTypes = mapOf("day" to DashboardValueType.DATE, "week" to DashboardValueType.DATE_RANGE, "month" to DashboardValueType.DATE_RANGE, "time" to DashboardValueType.TIMESTAMP, "amount" to DashboardValueType.NUMBER, "optional" to DashboardValueType.TEXT)
        val declarations = declarationTypes.mapValues { (name, type) -> DashboardParameterDefinition(name, type, name == "optional", null, null, null) }
        val definition = DashboardScheduleDefinition.decode(Json.parseToJsonElement("""{"cron":"0 9 * * *","timeZone":"Asia/Seoul","parameters":{"day":{"type":"RELATIVE_DATE","offsetDays":-1},"week":{"type":"CALENDAR_RANGE","unit":"WEEK","offset":-1},"month":{"type":"CALENDAR_RANGE","unit":"MONTH","offset":-1},"time":{"type":"SCHEDULED_TIME"},"amount":{"type":"LITERAL","value":"100.5"},"optional":{"type":"LITERAL","value":null}}}""").jsonObject, declarations)
        val at = Instant.parse("2024-03-04T00:00:00Z")
        assertEquals(Json.parseToJsonElement("""{"day":"2024-03-03","week":{"start":"2024-02-26","end":"2024-03-03"},"month":{"start":"2024-02-01","end":"2024-02-29"},"time":"2024-03-04T00:00:00Z","amount":"100.5","optional":null}"""), JsonObject(definition.resolveParameters(at)))
        val preview = definition.preview(at)
        assertEquals(3, preview.size)
        assertEquals(definition.nextAfter(at), preview.first().scheduledAt)
        preview.forEach { assertEquals(definition.resolveParameters(it.scheduledAt), it.parameters) }
    }
    @Test fun `out of range parameter dates fail explicitly`() {
        val declarations = mapOf("day" to DashboardParameterDefinition("day", DashboardValueType.DATE, false, null, null, null))
        val definition = DashboardScheduleDefinition.decode(Json.parseToJsonElement("""{"cron":"0 9 * * *","timeZone":"UTC","parameters":{"day":{"type":"RELATIVE_DATE","offsetDays":2147483647}}}""").jsonObject, declarations)
        assertFailsWith<IllegalArgumentException> { definition.resolveParameters(Instant.parse("2026-01-01T00:00:00Z")) }
    }

    @Test fun `duplicate JSON keys are rejected including escaped equivalent names`() {
        assertFailsWith<IllegalArgumentException> { DashboardDefinitionDecoder.decode("""{"schedules":{"daily":{},"d\u0061ily":{}}}""") }
    }
}
