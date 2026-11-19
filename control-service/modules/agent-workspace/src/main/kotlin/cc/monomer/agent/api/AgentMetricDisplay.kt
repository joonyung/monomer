package cc.monomer.agent.api

sealed interface AgentMetricDisplay {
    val decimals: Int

    data class Number(override val decimals: Int, val unit: AgentMetricDisplayUnit?) : AgentMetricDisplay {
        init { require(decimals in 0..6) }
    }

    data class Currency(override val decimals: Int, val currency: AgentMetricDisplayCurrency) : AgentMetricDisplay {
        init { require(decimals in 0..6) }
    }

    data class Percent(override val decimals: Int) : AgentMetricDisplay {
        init { require(decimals in 0..6) }
    }
}

enum class AgentMetricDisplayUnit {
    PERCENT, SECOND, MILLISECOND, MINUTE, HOUR, DAY, BYTE, KILOBYTE, MEGABYTE, GIGABYTE,
}
