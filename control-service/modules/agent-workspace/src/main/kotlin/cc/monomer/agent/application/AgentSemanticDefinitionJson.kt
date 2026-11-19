@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.agent.application

import cc.monomer.agent.api.AgentMetricDefinition
import cc.monomer.agent.api.AgentMetricFilter
import cc.monomer.agent.api.AgentMetricInput
import cc.monomer.agent.api.AgentMetricWindow
import cc.monomer.agent.api.AgentSemanticModelDefinition

object AgentSemanticDefinitionJson {
    fun display(display: cc.monomer.agent.api.AgentMetricDisplay): Map<String, Any> = when (display) {
        is cc.monomer.agent.api.AgentMetricDisplay.Number -> buildMap {
            put("style", "NUMBER")
            put("decimals", display.decimals)
            display.unit?.let { put("unit", it.name.lowercase()) }
        }
        is cc.monomer.agent.api.AgentMetricDisplay.Currency -> mapOf("style" to "CURRENCY", "decimals" to display.decimals, "currency" to display.currency.name)
        is cc.monomer.agent.api.AgentMetricDisplay.Percent -> mapOf("style" to "PERCENT", "decimals" to display.decimals)
    }

    fun aggregateMetric(metric: cc.monomer.agent.api.AgentSemanticAggregateMetric): Map<String, Any?> = linkedMapOf<String, Any?>(
        "name" to metric.name, "measure" to metric.measure,
        "filters" to metric.filters.map(::filter),
    ).apply {
        putOptional("label", metric.label)
        putOptional("description", metric.description)
        putOptional("timeGranularity", metric.timeGranularity?.name?.lowercase())
    }

    fun model(definition: AgentSemanticModelDefinition): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>(
            "schemaVersion" to definition.schemaVersion.toInt(),
            "name" to definition.name,
            "sourceRelations" to definition.sourceRelations.map { relation ->
                linkedMapOf<String, Any?>(
                    "connectorId" to relation.connectorId.toString(),
                    "schema" to relation.schema,
                    "relation" to relation.relation,
                ).apply { putOptional("catalog", relation.catalog) }
            },
            "entities" to definition.entities.map { entity ->
                linkedMapOf<String, Any?>(
                    "name" to entity.name,
                    "type" to entity.kind.name.lowercase(),
                    "expression" to entity.expression,
                ).apply {
                    putOptional("label", entity.label)
                    putOptional("description", entity.description)
                }
            },
            "dimensions" to definition.dimensions.map { dimension ->
                linkedMapOf<String, Any?>(
                    "name" to dimension.name,
                    "type" to dimension.kind.name.lowercase(),
                    "expression" to dimension.expression,
                    "isPartition" to dimension.isPartition,
                ).apply {
                    putOptional("granularity", dimension.granularity?.name?.lowercase())
                    putOptional("timeKind", dimension.timeKind?.name?.lowercase())
                    putOptional("label", dimension.label)
                    putOptional("description", dimension.description)
                }
            },
            "measures" to definition.measures.map { measure ->
                linkedMapOf<String, Any?>(
                    "name" to measure.name,
                    "expression" to measure.expression,
                    "aggregation" to measure.aggregation.name.lowercase(),
                ).apply {
                    putOptional("aggregationTimeDimension", measure.aggregationTimeDimension)
                    putOptional(
                        "nonAdditiveDimension",
                        measure.nonAdditiveDimension?.let { nonAdditive ->
                            linkedMapOf<String, Any?>(
                                "name" to nonAdditive.name,
                                "windowChoice" to nonAdditive.windowChoice.name.lowercase(),
                            ).apply { putOptional("windowGroupings", nonAdditive.windowGroupings) }
                        },
                    )
                    putOptional("label", measure.label)
                    putOptional("description", measure.description)
                }
            },
        )
        result.putOptional("label", definition.label)
        result.putOptional("description", definition.description)
        result.putOptional("aggregationTimeDimension", definition.aggregationTimeDimension)
        return result
    }

    fun metric(definition: AgentMetricDefinition): Map<String, Any?> {
        fun common(type: String) = linkedMapOf<String, Any?>(
            "schemaVersion" to definition.schemaVersion.toInt(),
            "name" to definition.name,
            "type" to type,
        ).apply {
            put("display", definition.display?.let(::display))
            putOptional("label", definition.label)
            putOptional("description", definition.description)
            putOptional("timeGranularity", definition.timeGranularity?.name?.lowercase())
            if (definition.filters.isNotEmpty()) {
                put("filters", definition.filters.map(::filter))
            }
        }
        return when (definition) {
            is AgentMetricDefinition.Simple -> common("simple").apply {
                put("semanticModelName", definition.semanticModelName)
                put("measure", definition.measure)
            }
            is AgentMetricDefinition.Ratio -> common("ratio").apply {
                put("numerator", input(definition.numerator))
                put("denominator", input(definition.denominator))
            }
            is AgentMetricDefinition.Derived -> common("derived").apply {
                put("expression", definition.expression)
                put("inputMetrics", definition.inputMetrics.map(::input))
            }
            is AgentMetricDefinition.Cumulative -> common("cumulative").apply {
                put("inputMetric", input(definition.inputMetric))
                putOptional("window", definition.window?.let(::window))
                putOptional("grainToDate", definition.grainToDate?.name?.lowercase())
                putOptional("periodAggregation", definition.periodAggregation?.name?.lowercase())
            }
            is AgentMetricDefinition.Conversion -> common("conversion").apply {
                put("entity", definition.entity)
                put("calculation", definition.calculation.name.lowercase())
                put("baseMetric", input(definition.baseMetric))
                put("conversionMetric", input(definition.conversionMetric))
                putOptional("window", definition.window?.let(::window))
                putOptional(
                    "constantProperties",
                    definition.constantProperties?.map { property ->
                        mapOf(
                            "baseProperty" to property.baseProperty,
                            "conversionProperty" to property.conversionProperty,
                        )
                    },
                )
            }
        }
    }

    private fun input(input: AgentMetricInput): Map<String, Any?> = linkedMapOf<String, Any?>(
        "metricName" to input.metricName,
    ).apply {
        putOptional("filter", input.filter?.let(::filter))
        putOptional("alias", input.alias)
        putOptional("offsetWindow", input.offsetWindow?.let(::window))
        putOptional("offsetToGrain", input.offsetToGrain?.name?.lowercase())
    }

    private fun window(window: AgentMetricWindow): String =
        "${window.amount} ${window.unit.name.lowercase()}" + if (window.amount == 1) "" else "s"

    private fun filter(filter: AgentMetricFilter): Map<String, Any?> = when (filter) {
        is AgentMetricFilter.Predicate -> mapOf(
            "dimension" to linkedMapOf<String, Any?>(
                "semanticModelName" to filter.dimension.semanticModelName,
                "name" to filter.dimension.name,
            ).apply { putOptional("granularity", filter.dimension.granularity?.name?.lowercase()) },
            "operator" to filter.operator.name.lowercase(),
            "values" to filter.values,
        )
        is AgentMetricFilter.And -> mapOf("and" to filter.filters.map(::filter))
        is AgentMetricFilter.Or -> mapOf("or" to filter.filters.map(::filter))
        is AgentMetricFilter.Not -> mapOf("not" to filter(filter.filter))
    }

    private fun MutableMap<String, Any?>.putOptional(key: String, value: Any?) {
        if (value != null) put(key, value)
    }
}
