package cc.monomer.dashboard.domain

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

data class DashboardSchedulePreview(
    val scheduledAt: Instant,
    val localTime: String,
    val parameters: Map<String, JsonElement>,
)

@ConsistentCopyVisibility
data class DashboardScheduleDefinition private constructor(
    val cron: String,
    val timeZone: String,
    val parameters: Map<String, JsonObject>,
) {
    private val zone = ZoneId.of(timeZone)
    private val calendar = DashboardCron(cron)

    fun nextAfter(scheduledAt: Instant): Instant = calendar.nextAfter(scheduledAt, zone)

    fun resolveParameters(scheduledAt: Instant): Map<String, JsonElement> {
        val date = scheduledAt.atZone(zone).toLocalDate()
        return parameters.mapValues { (_, binding) ->
            when (binding.getValue("type").jsonPrimitive.content) {
                "LITERAL" -> binding.getValue("value")
                "RELATIVE_DATE" -> JsonPrimitive(date.plusDays(binding.getValue("offsetDays").jsonPrimitive.content.toLong()).toString())
                "SCHEDULED_TIME" -> JsonPrimitive(scheduledAt.toString())
                "CALENDAR_RANGE" -> {
                    val offset = binding.getValue("offset").jsonPrimitive.content.toLong()
                    val (start, end) = when (binding.getValue("unit").jsonPrimitive.content) {
                        "DAY" -> date.plusDays(offset).let { it to it }
                        "WEEK" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset).let { it to it.plusDays(6) }
                        "MONTH" -> date.withDayOfMonth(1).plusMonths(offset).let { it to it.with(TemporalAdjusters.lastDayOfMonth()) }
                        else -> error("invalid calendar unit")
                    }
                    require(start.year in 0..9999 && end.year in 0..9999) { "schedule date is outside the supported ISO date range" }
                    JsonObject(mapOf("start" to JsonPrimitive(start.toString()), "end" to JsonPrimitive(end.toString())))
                }
                else -> error("invalid schedule binding")
            }.also { value ->
                if (binding.getValue("type").jsonPrimitive.content == "RELATIVE_DATE") DashboardJson.date(value, "schedule date")
            }
        }
    }

    fun preview(after: Instant): List<DashboardSchedulePreview> {
        var previous = after
        return List(3) {
            val next = nextAfter(previous)
            previous = next
            DashboardSchedulePreview(next, next.atZone(zone).toString(), resolveParameters(next))
        }
    }

    companion object {
        fun decode(node: JsonObject, declarations: Map<String, DashboardParameterDefinition>): DashboardScheduleDefinition {
            DashboardJson.exact(node, setOf("cron", "timeZone", "parameters"), "schedule")
            fun string(name: String): String {
                val value = node.getValue(name) as? JsonPrimitive
                require(value != null && value.isString) { "schedule $name must be string" }
                return value.content
            }
            val timeZone = string("timeZone")
            require(timeZone in ZoneId.getAvailableZoneIds()) { "schedule timeZone must be an IANA time zone" }
            val parameters = DashboardJson.objectNode(node.getValue("parameters"), "schedule parameters")
            require(parameters.keys == declarations.keys) { "schedule parameters must exactly match Dashboard parameters" }
            val bindings = parameters.mapValues { (name, raw) ->
                val binding = DashboardJson.objectNode(raw, "schedule parameter $name")
                val type = binding["type"] as? JsonPrimitive
                require(type != null && type.isString) { "schedule binding type must be string" }
                val declaration = declarations.getValue(name)
                val fields = when (type.content) {
                    "LITERAL" -> setOf("type", "value")
                    "RELATIVE_DATE" -> setOf("type", "offsetDays")
                    "CALENDAR_RANGE" -> setOf("type", "unit", "offset")
                    "SCHEDULED_TIME" -> setOf("type")
                    else -> throw IllegalArgumentException("unknown schedule binding type")
                }
                DashboardJson.exact(binding, fields, "schedule parameter $name")
                when (type.content) {
                    "LITERAL" -> DashboardValueValidator.requireValid(binding.getValue("value"), declaration.type, declaration.nullable, "schedule parameter $name")
                    "RELATIVE_DATE" -> require(declaration.type == DashboardValueType.DATE)
                    "CALENDAR_RANGE" -> {
                        require(declaration.type == DashboardValueType.DATE_RANGE)
                        val unit = binding.getValue("unit") as? JsonPrimitive
                        require(unit != null && unit.isString && unit.content in setOf("DAY", "WEEK", "MONTH")) { "invalid calendar unit" }
                    }
                    "SCHEDULED_TIME" -> require(declaration.type == DashboardValueType.TIMESTAMP)
                }
                listOf("offsetDays", "offset").forEach { field ->
                    binding[field]?.let { value ->
                        val integer = value as? JsonPrimitive
                        require(integer != null && !integer.isString && integer.intOrNull != null && Regex("-?(0|[1-9][0-9]*)").matches(integer.content)) { "$field must be JSON integer" }
                    }
                }
                binding
            }
            return DashboardScheduleDefinition(string("cron"), timeZone, bindings)
        }
    }
}

private class DashboardCron(expression: String) {
    private val fields = expression.trim().split(Regex("\\s+"))
    private val parser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))
    private val time: ExecutionTime
    private val daysOfMonth: Set<Int>
    private val months: Set<Int>
    private val daysOfWeek: Set<Int>
    private val useIntersection: Boolean

    init {
        require(fields.size == 5) { "cron must have five fields" }
        fields.forEach { field ->
            require(field.split(',').all { part ->
                Regex("(?:\\*|[0-9]+|[A-Za-z]{3}|(?:[0-9]+|[A-Za-z]{3})-(?:[0-9]+|[A-Za-z]{3}))(?:/[1-9][0-9]*)?").matches(part) &&
                    ('/' !in part || part.substringBefore('/') == "*" || '-' in part.substringBefore('/'))
            }) { "unsupported cron syntax" }
        }
        val minutes = values(fields[0], 0..59, emptyList())
        val hours = values(fields[1], 0..23, emptyList())
        time = execution("${minutes.sorted().joinToString(",")} ${hours.sorted().joinToString(",")} * * *")
        daysOfMonth = values(fields[2], 1..31, emptyList())
        months = values(fields[3], 1..12, listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"))
        daysOfWeek = values(fields[4], 0..7, listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")).map { it % 7 }.toSet()
        useIntersection = '*' in fields[2] || '*' in fields[4]
        require((0L until 146097L).any { matchesDate(LocalDate.of(2000, 1, 1).plusDays(it)) }) { "cron has no possible calendar date" }
    }

    private fun execution(expression: String): ExecutionTime = ExecutionTime.forCron(parser.parse(expression).also { it.validate() })

    private fun matchesDate(date: LocalDate): Boolean {
        if (date.monthValue !in months) return false
        val dom = date.dayOfMonth in daysOfMonth
        val dow = date.dayOfWeek.value % 7 in daysOfWeek
        return if (useIntersection) dom && dow else dom || dow
    }

    private fun values(field: String, bounds: IntRange, names: List<String>): Set<Int> =
        field.split(',').flatMap { part ->
            fun number(raw: String): Int = raw.toIntOrNull() ?: names.indexOf(raw.uppercase()).let {
                require(it >= 0) { "invalid cron name" }
                it + bounds.first
            }
            val expression = part.substringBefore('/')
            val step = if ('/' in part) (part.substringAfter('/').toIntOrNull() ?: Int.MAX_VALUE).coerceAtMost(bounds.last - bounds.first + 1) else 1
            val range = when {
                expression == "*" -> bounds
                '-' in expression -> number(expression.substringBefore('-'))..number(expression.substringAfter('-'))
                else -> number(expression).let { it..it }
            }
            require(range.first in bounds && range.last in bounds && range.first <= range.last) { "invalid cron range" }
            (range step step).toList()
        }.toSet()

    fun nextAfter(after: Instant, zone: ZoneId): Instant {
        val localAfter = after.atZone(zone).toLocalDateTime()
        var date = localAfter.toLocalDate()
        repeat(146098) {
            if (matchesDate(date)) {
                var cursor = if (date == localAfter.toLocalDate()) localAfter.atZone(ZoneOffset.UTC).minusSeconds(1) else date.atStartOfDay(ZoneOffset.UTC).minusSeconds(1)
                while (true) {
                    val candidate = time.nextExecution(cursor).orElseThrow { IllegalArgumentException("cron has no next time") }
                    if (candidate.toLocalDate() != date) break
                    val local = candidate.toLocalDateTime()
                    val offsets = zone.rules.getValidOffsets(local)
                    if (offsets.isNotEmpty()) {
                        val instant = local.toInstant(offsets.first())
                        if (instant > after) return instant
                    }
                    cursor = candidate
                }
            }
            date = date.plusDays(1)
        }
        throw IllegalArgumentException("cron has no next occurrence within a complete Gregorian calendar cycle")
    }
}
