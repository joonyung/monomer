package cc.monomer.agent.api

import cc.monomer.agent.api.AgentPathPatterns.UUID_V7

object AgentDashboardHistoryPaths {
    val history = Regex("^workspace/dashboards/$UUID_V7/history$")
    val version = Regex("^workspace/dashboards/$UUID_V7/history/$UUID_V7$")
    fun supports(path: String): Boolean = history.matches(path) || version.matches(path)
}
