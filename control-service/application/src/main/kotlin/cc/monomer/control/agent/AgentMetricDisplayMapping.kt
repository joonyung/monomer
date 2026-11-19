package cc.monomer.control.agent

import cc.monomer.agent.api.AgentMetricDisplay
import cc.monomer.agent.api.AgentMetricDisplayCurrency
import cc.monomer.agent.api.AgentMetricDisplayUnit
import cc.monomer.semantic.api.MetricDisplay

internal fun MetricDisplay?.toAgentDisplay(): AgentMetricDisplay? = when (this) {
    null -> null
    is MetricDisplay.Number -> AgentMetricDisplay.Number(decimals, unit?.let { AgentMetricDisplayUnit.valueOf(it.name) })
    is MetricDisplay.Currency -> AgentMetricDisplay.Currency(decimals, AgentMetricDisplayCurrency.valueOf(currency.name))
    is MetricDisplay.Percent -> AgentMetricDisplay.Percent(decimals)
}
