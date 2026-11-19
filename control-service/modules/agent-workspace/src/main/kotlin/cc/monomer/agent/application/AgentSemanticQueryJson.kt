package cc.monomer.agent.application

import cc.monomer.agent.api.AgentSemanticFilter
import cc.monomer.agent.api.AgentSemanticGroupBy
import cc.monomer.agent.api.AgentSemanticOrderTarget
import cc.monomer.agent.api.AgentSemanticQuery
import cc.monomer.agent.api.AgentSemanticTimeRangeEndpoint

object AgentSemanticQueryJson {
    fun query(query: AgentSemanticQuery): Map<String, Any?> = mapOf(
        "schemaVersion" to query.schemaVersion.toInt(),
        "metrics" to query.metrics,
        "groupBys" to query.groupBys.map(::groupBy),
        "metricTimeRange" to query.metricTimeRange?.let { range ->
            mapOf("start" to endpoint(range.start), "end" to endpoint(range.end))
        },
        "filters" to query.filters.map(::filter),
        "orderBy" to query.orderBy.map { order ->
            mapOf(
                "target" to when (val target = order.target) {
                    is AgentSemanticOrderTarget.Metric -> mapOf("kind" to "METRIC", "metricName" to target.metricName)
                    is AgentSemanticOrderTarget.GroupBy -> mapOf("kind" to "GROUP_BY", "groupBy" to groupBy(target.groupBy))
                },
                "direction" to order.direction.name,
            )
        },
        "rowLimit" to query.rowLimit,
    )

    private fun groupBy(groupBy: AgentSemanticGroupBy): Map<String, Any?> = when (groupBy) {
        is AgentSemanticGroupBy.Dimension -> linkedMapOf<String, Any?>(
            "kind" to "DIMENSION", "semanticModelName" to groupBy.semanticModelName, "name" to groupBy.name,
        ).apply { groupBy.granularity?.let { put("granularity", it.name) } }
        is AgentSemanticGroupBy.Entity -> mapOf(
            "kind" to "ENTITY", "semanticModelName" to groupBy.semanticModelName, "name" to groupBy.name,
        )
        is AgentSemanticGroupBy.MetricTime -> mapOf("kind" to "METRIC_TIME", "granularity" to groupBy.granularity.name)
    }

    private fun endpoint(endpoint: AgentSemanticTimeRangeEndpoint): Map<String, String> = when (endpoint) {
        is AgentSemanticTimeRangeEndpoint.Date -> mapOf("kind" to "DATE", "value" to endpoint.value.toString())
        is AgentSemanticTimeRangeEndpoint.Timestamp -> mapOf("kind" to "TIMESTAMP", "value" to endpoint.value.toString())
    }

    private fun filter(filter: AgentSemanticFilter): Map<String, Any?> {
        val value = when (filter) {
            is AgentSemanticFilter.Text -> mapOf("kind" to "TEXT", "operator" to filter.operator.name, "values" to filter.values)
            is AgentSemanticFilter.Number -> mapOf(
                "kind" to "NUMBER", "operator" to filter.operator.name, "values" to filter.values.map { it.toPlainString() },
            )
            is AgentSemanticFilter.Boolean -> mapOf("kind" to "BOOLEAN", "operator" to filter.operator.name, "values" to filter.values)
            is AgentSemanticFilter.Date -> mapOf(
                "kind" to "DATE", "operator" to filter.operator.name, "values" to filter.values.map { it.toString() },
            )
            is AgentSemanticFilter.Timestamp -> mapOf(
                "kind" to "TIMESTAMP", "operator" to filter.operator.name, "values" to filter.values.map { it.toString() },
            )
        }
        return value + ("dimension" to mapOf("semanticModelName" to filter.dimension.semanticModelName, "name" to filter.dimension.name))
    }
}
