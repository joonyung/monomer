package cc.monomer.agent.api

import cc.monomer.agent.api.AgentPathPatterns.UUID_V7

object AgentDashboardSchedulePaths {
    private const val DASHBOARD = "workspace/dashboards/$UUID_V7"
    val schedules = Regex("^$DASHBOARD/schedules$")
    val draftSchedules = Regex("^$DASHBOARD/draft/schedules$")
    val occurrences = Regex("^$DASHBOARD/occurrences$")
    val occurrence = Regex("^$DASHBOARD/occurrences/(?!__proto__/)[A-Za-z_][A-Za-z0-9_]{0,127}/[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z$")
    fun validOccurrence(path: String): Boolean = occurrence.matches(path) && runCatching {
        val timestamp = path.substringAfterLast('/')
        java.time.Instant.parse(timestamp)
        true
    }.getOrDefault(false)
    fun readable(path: String) = schedules.matches(path) || draftSchedules.matches(path) || validOccurrence(path)
    fun supports(path: String) = readable(path) || occurrences.matches(path)
}
