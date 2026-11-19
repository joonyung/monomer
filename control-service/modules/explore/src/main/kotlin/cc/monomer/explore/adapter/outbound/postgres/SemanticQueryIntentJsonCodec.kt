@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.explore.adapter.outbound.postgres

import cc.monomer.semantic.api.SemanticBooleanFilterOperator
import cc.monomer.semantic.api.SemanticDateFilterOperator
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticElementReference
import cc.monomer.semantic.api.SemanticGroupBy
import cc.monomer.semantic.api.SemanticMetricTimeRange
import cc.monomer.semantic.api.SemanticNumberFilterOperator
import cc.monomer.semantic.api.SemanticQueryFilter
import cc.monomer.semantic.api.SemanticQueryOrder
import cc.monomer.semantic.api.SemanticQueryOrderDirection
import cc.monomer.semantic.api.SemanticQueryOrderTarget
import cc.monomer.semantic.api.SemanticQuerySpec
import cc.monomer.semantic.api.SemanticTextFilterOperator
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.api.SemanticTimeRangeEndpoint
import cc.monomer.semantic.api.SemanticTimestampFilterOperator
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Persistence-only codec for the closed Semantic query intent. */
internal object SemanticQueryIntentJsonCodec {
    fun encode(query: SemanticQuerySpec): String = jsonObject(
        "metrics" to jsonArray(query.metrics.map { string(it.value) }),
        "groupBys" to jsonArray(query.groupBys.map(::encodeGroupBy)),
        "metricTimeRange" to (query.metricTimeRange?.let(::encodeMetricTimeRange) ?: JsonNull),
        "filters" to jsonArray(query.filters.map(::encodeFilter)),
        "orderBy" to jsonArray(query.orderBy.map(::encodeOrder)),
        "rowLimit" to number(query.rowLimit),
    ).toString()

    fun decode(json: String): SemanticQuerySpec {
        val root = Json.parseToJsonElement(json).jsonObject
        return SemanticQuerySpec(
            metrics = root.requiredArray("metrics").map { SemanticElementName(it.stringValue()) },
            groupBys = root.requiredArray("groupBys").map(::decodeGroupBy),
            metricTimeRange = root.optional("metricTimeRange")?.let(::decodeMetricTimeRange),
            filters = root.requiredArray("filters").map(::decodeFilter),
            orderBy = root.requiredArray("orderBy").map(::decodeOrder),
            rowLimit = root.requiredInt("rowLimit"),
        )
    }

    private fun encodeElementReference(reference: SemanticElementReference): JsonObject = jsonObject(
        "semanticModelName" to string(reference.semanticModelName.value),
        "name" to string(reference.name.value),
        "granularity" to (reference.granularity?.let { string(it.name) } ?: JsonNull),
    )

    private fun decodeElementReference(element: JsonElement): SemanticElementReference {
        val value = element.jsonObject
        return SemanticElementReference(
            semanticModelName = SemanticElementName(value.requiredString("semanticModelName")),
            name = SemanticElementName(value.requiredString("name")),
            granularity = value.optionalString("granularity")?.let(SemanticTimeGranularity::valueOf),
        )
    }

    private fun encodeGroupBy(groupBy: SemanticGroupBy): JsonObject = when (groupBy) {
        is SemanticGroupBy.Dimension -> jsonObject(
            "kind" to string("DIMENSION"),
            "semanticModelName" to string(groupBy.semanticModelName.value),
            "name" to string(groupBy.name.value),
            "granularity" to (groupBy.granularity?.let { string(it.name) } ?: JsonNull),
        )
        is SemanticGroupBy.Entity -> jsonObject(
            "kind" to string("ENTITY"),
            "semanticModelName" to string(groupBy.semanticModelName.value),
            "name" to string(groupBy.name.value),
        )
        is SemanticGroupBy.MetricTime -> jsonObject(
            "kind" to string("METRIC_TIME"),
            "granularity" to string(groupBy.granularity.name),
        )
    }

    private fun decodeGroupBy(element: JsonElement): SemanticGroupBy {
        val value = element.jsonObject
        return when (value.requiredString("kind")) {
            "DIMENSION" -> SemanticGroupBy.Dimension(
                semanticModelName = SemanticElementName(value.requiredString("semanticModelName")),
                name = SemanticElementName(value.requiredString("name")),
                granularity = value.optionalString("granularity")?.let(SemanticTimeGranularity::valueOf),
            )
            "ENTITY" -> SemanticGroupBy.Entity(
                semanticModelName = SemanticElementName(value.requiredString("semanticModelName")),
                name = SemanticElementName(value.requiredString("name")),
            )
            "METRIC_TIME" -> SemanticGroupBy.MetricTime(
                granularity = SemanticTimeGranularity.valueOf(value.requiredString("granularity")),
            )
            else -> error("unsupported semantic group-by kind")
        }
    }

    private fun encodeMetricTimeRange(range: SemanticMetricTimeRange): JsonObject = jsonObject(
        "start" to encodeTimeRangeEndpoint(range.start),
        "end" to encodeTimeRangeEndpoint(range.end),
    )

    private fun decodeMetricTimeRange(element: JsonElement): SemanticMetricTimeRange {
        val value = element.jsonObject
        return SemanticMetricTimeRange(
            start = decodeTimeRangeEndpoint(value.required("start")),
            end = decodeTimeRangeEndpoint(value.required("end")),
        )
    }

    private fun encodeTimeRangeEndpoint(endpoint: SemanticTimeRangeEndpoint): JsonObject = when (endpoint) {
        is SemanticTimeRangeEndpoint.Date -> jsonObject(
            "kind" to string("DATE"),
            "value" to string(endpoint.value.toString()),
        )
        is SemanticTimeRangeEndpoint.Timestamp -> jsonObject(
            "kind" to string("TIMESTAMP"),
            "value" to string(endpoint.value.toString()),
        )
    }

    private fun decodeTimeRangeEndpoint(element: JsonElement): SemanticTimeRangeEndpoint {
        val value = element.jsonObject
        return when (value.requiredString("kind")) {
            "DATE" -> SemanticTimeRangeEndpoint.Date(LocalDate.parse(value.requiredString("value")))
            "TIMESTAMP" -> SemanticTimeRangeEndpoint.Timestamp(Instant.parse(value.requiredString("value")))
            else -> error("unsupported semantic time range endpoint kind")
        }
    }

    private fun encodeFilter(filter: SemanticQueryFilter): JsonObject = when (filter) {
        is SemanticQueryFilter.Text -> jsonObject(
            "kind" to string("TEXT"),
            "dimension" to encodeElementReference(filter.dimension),
            "operator" to string(filter.operator.name),
            "values" to jsonArray(filter.values.map(::string)),
        )
        is SemanticQueryFilter.Number -> jsonObject(
            "kind" to string("NUMBER"),
            "dimension" to encodeElementReference(filter.dimension),
            "operator" to string(filter.operator.name),
            "values" to jsonArray(filter.values.map { string(it.toPlainString()) }),
        )
        is SemanticQueryFilter.Boolean -> jsonObject(
            "kind" to string("BOOLEAN"),
            "dimension" to encodeElementReference(filter.dimension),
            "operator" to string(filter.operator.name),
            "values" to jsonArray(filter.values.map(::boolean)),
        )
        is SemanticQueryFilter.Date -> jsonObject(
            "kind" to string("DATE"),
            "dimension" to encodeElementReference(filter.dimension),
            "operator" to string(filter.operator.name),
            "values" to jsonArray(filter.values.map { string(it.toString()) }),
        )
        is SemanticQueryFilter.Timestamp -> jsonObject(
            "kind" to string("TIMESTAMP"),
            "dimension" to encodeElementReference(filter.dimension),
            "operator" to string(filter.operator.name),
            "values" to jsonArray(filter.values.map { string(it.toString()) }),
        )
    }

    private fun decodeFilter(element: JsonElement): SemanticQueryFilter {
        val value = element.jsonObject
        val dimension = decodeElementReference(value.required("dimension"))
        val values = value.requiredArray("values")
        return when (value.requiredString("kind")) {
            "TEXT" -> SemanticQueryFilter.Text(
                dimension = dimension,
                operator = SemanticTextFilterOperator.valueOf(value.requiredString("operator")),
                values = values.map { it.stringValue() },
            )
            "NUMBER" -> SemanticQueryFilter.Number(
                dimension = dimension,
                operator = SemanticNumberFilterOperator.valueOf(value.requiredString("operator")),
                values = values.map { BigDecimal(it.stringValue()) },
            )
            "BOOLEAN" -> SemanticQueryFilter.Boolean(
                dimension = dimension,
                operator = SemanticBooleanFilterOperator.valueOf(value.requiredString("operator")),
                values = values.map { it.booleanValue() },
            )
            "DATE" -> SemanticQueryFilter.Date(
                dimension = dimension,
                operator = SemanticDateFilterOperator.valueOf(value.requiredString("operator")),
                values = values.map { LocalDate.parse(it.stringValue()) },
            )
            "TIMESTAMP" -> SemanticQueryFilter.Timestamp(
                dimension = dimension,
                operator = SemanticTimestampFilterOperator.valueOf(value.requiredString("operator")),
                values = values.map { Instant.parse(it.stringValue()) },
            )
            else -> error("unsupported semantic query filter kind")
        }
    }

    private fun encodeOrder(order: SemanticQueryOrder): JsonObject = jsonObject(
        "target" to encodeOrderTarget(order.target),
        "direction" to string(order.direction.name),
    )

    private fun decodeOrder(element: JsonElement): SemanticQueryOrder {
        val value = element.jsonObject
        return SemanticQueryOrder(
            target = decodeOrderTarget(value.required("target")),
            direction = SemanticQueryOrderDirection.valueOf(value.requiredString("direction")),
        )
    }

    private fun encodeOrderTarget(target: SemanticQueryOrderTarget): JsonObject = when (target) {
        is SemanticQueryOrderTarget.Metric -> jsonObject(
            "kind" to string("METRIC"),
            "metricName" to string(target.metricName.value),
        )
        is SemanticQueryOrderTarget.GroupBy -> jsonObject(
            "kind" to string("GROUP_BY"),
            "groupBy" to encodeGroupBy(target.groupBy),
        )
    }

    private fun decodeOrderTarget(element: JsonElement): SemanticQueryOrderTarget {
        val value = element.jsonObject
        return when (value.requiredString("kind")) {
            "METRIC" -> SemanticQueryOrderTarget.Metric(
                SemanticElementName(value.requiredString("metricName")),
            )
            "GROUP_BY" -> SemanticQueryOrderTarget.GroupBy(decodeGroupBy(value.required("groupBy")))
            else -> error("unsupported semantic query order target kind")
        }
    }

    private fun jsonObject(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*entries))
    private fun jsonArray(elements: List<JsonElement>): JsonArray = JsonArray(elements)
    private fun string(value: String): JsonPrimitive = JsonPrimitive(value)
    private fun number(value: Int): JsonPrimitive = JsonPrimitive(value)
    private fun boolean(value: Boolean): JsonPrimitive = JsonPrimitive(value)

    private fun JsonObject.required(name: String): JsonElement = get(name) ?: error("semantic query intent is missing '$name'")
    private fun JsonObject.requiredString(name: String): String = required(name).stringValue()
    private fun JsonObject.requiredInt(name: String): Int = required(name).jsonPrimitive.content.toInt()
    private fun JsonObject.requiredArray(name: String): JsonArray = required(name).jsonArray
    private fun JsonObject.optional(name: String): JsonElement? = get(name)?.takeUnless { it is JsonNull }
    private fun JsonObject.optionalString(name: String): String? = optional(name)?.stringValue()
    private fun JsonElement.stringValue(): String = jsonPrimitive.content
    private fun JsonElement.booleanValue(): Boolean = jsonPrimitive.content.toBooleanStrict()
}
