@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.semantic.inbound.rest

import cc.monomer.connector.api.ConnectorId
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticElementReference
import cc.monomer.semantic.api.SemanticGroupBy
import cc.monomer.semantic.api.SemanticGroupByOptionsResult
import cc.monomer.semantic.api.SemanticMetricTimeRange
import cc.monomer.semantic.api.SemanticQueryFilter
import cc.monomer.semantic.api.SemanticQueryOrder
import cc.monomer.semantic.api.SemanticQueryOrderTarget
import cc.monomer.semantic.api.SemanticQuerySpec
import cc.monomer.semantic.api.SemanticSqlRender
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.api.SemanticTimeRangeEndpoint
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.uuid.Uuid
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/** The transport envelope shared by Semantic preview and Explore execution. */
data class SemanticQueryHttpRequest(
    val connectorId: String?,
    val query: JsonNode?,
) {
    internal fun toPayload(): SemanticQueryHttpPayload = decodeSemanticQueryTransport {
        val connectorId = parseConnectorId(requireText(connectorId, "connectorId"))
        val queryNode = requireObject(requireNotNull(query) { "query is required" })
        SemanticQueryHttpPayload(
            connectorId = connectorId,
            schemaVersion = queryNode.requiredInt("schemaVersion"),
            query = parseQuery(queryNode),
            queryJson = queryNode.toString(),
        )
    }
}

data class SemanticGroupByOptionsHttpRequest(
    val connectorId: String?,
    val metrics: List<String>?,
) {
    internal fun toPayload(): SemanticGroupByOptionsHttpPayload = decodeSemanticQueryTransport {
        val metrics = requireNotNull(metrics) { "metrics is required" }
            .map { raw -> SemanticElementName(raw) }
        require(metrics.isNotEmpty()) { "metrics must select at least one metric" }
        require(metrics.toSet().size == metrics.size) { "metrics must be unique" }
        SemanticGroupByOptionsHttpPayload(
            connectorId = parseConnectorId(requireText(connectorId, "connectorId")),
            metrics = metrics,
        )
    }
}

data class SemanticQueryHttpPayload(
    val connectorId: ConnectorId,
    val schemaVersion: Int,
    val query: SemanticQuerySpec,
    val queryJson: String,
)

data class SemanticGroupByOptionsHttpPayload(
    val connectorId: ConnectorId,
    val metrics: List<SemanticElementName>,
)

data class SemanticGroupByHttpView(
    val kind: String,
    val semanticModelName: String?,
    val name: String?,
    val granularity: String?,
) {
    internal companion object {
        fun fromDomain(groupBy: SemanticGroupBy): SemanticGroupByHttpView =
            when (groupBy) {
                is SemanticGroupBy.Dimension ->
                    SemanticGroupByHttpView(
                        kind = "DIMENSION",
                        semanticModelName = groupBy.semanticModelName.value,
                        name = groupBy.name.value,
                        granularity = groupBy.granularity?.name,
                    )
                is SemanticGroupBy.Entity ->
                    SemanticGroupByHttpView(
                        kind = "ENTITY",
                        semanticModelName = groupBy.semanticModelName.value,
                        name = groupBy.name.value,
                        granularity = null,
                    )
                is SemanticGroupBy.MetricTime ->
                    SemanticGroupByHttpView(
                        kind = "METRIC_TIME",
                        semanticModelName = null,
                        name = null,
                        granularity = groupBy.granularity.name,
                    )
            }
    }
}

data class SemanticGroupByOptionsHttpView(
    val groupBys: List<SemanticGroupByHttpView>,
    val excludedSemanticModels: List<SemanticExcludedModelHttpView>,
    val dimensionFilterTypes: List<SemanticDimensionFilterTypeHttpView>,
) {
    internal companion object {
        fun fromDomain(listed: SemanticGroupByOptionsResult.Listed): SemanticGroupByOptionsHttpView =
            SemanticGroupByOptionsHttpView(
                groupBys = listed.groupBys.map(SemanticGroupByHttpView::fromDomain),
                excludedSemanticModels = listed.excludedSemanticModels.map { excluded ->
                    SemanticExcludedModelHttpView(semanticModelName = excluded.name.value, message = excluded.message)
                },
                dimensionFilterTypes = listed.dimensionFilterTypes.map { filterType ->
                    SemanticDimensionFilterTypeHttpView(
                        dimension = SemanticDimensionReferenceHttpView(filterType.dimension.semanticModelName.value, filterType.dimension.name.value),
                        kind = filterType.kind.name,
                    )
                },
            )
    }
}

data class SemanticDimensionFilterTypeHttpView(
    val dimension: SemanticDimensionReferenceHttpView,
    val kind: String,
)

data class SemanticDimensionReferenceHttpView(
    val semanticModelName: String,
    val name: String,
)

/** A semantic model the engine rejected for this request, so its group-bys are not offered. */
data class SemanticExcludedModelHttpView(
    val semanticModelName: String,
    val message: String,
)

data class SemanticSqlRenderHttpView(
    val dialect: String,
    val sql: String,
) {
    internal companion object {
        fun fromDomain(render: SemanticSqlRender): SemanticSqlRenderHttpView =
            SemanticSqlRenderHttpView(
                dialect = render.dialect.name,
                sql = render.sql,
            )
    }
}

internal fun SemanticQuerySpec.toHttpValue(): Map<String, Any?> =
    linkedMapOf(
        "schemaVersion" to SEMANTIC_QUERY_SCHEMA_VERSION,
        "metrics" to metrics.map(SemanticElementName::toString),
        "groupBys" to groupBys.map(SemanticGroupBy::toHttpValue),
        "metricTimeRange" to metricTimeRange?.toHttpValue(),
        "filters" to filters.map(SemanticQueryFilter::toHttpValue),
        "orderBy" to orderBy.map(SemanticQueryOrder::toHttpValue),
        "rowLimit" to rowLimit,
    )

private fun SemanticGroupBy.toHttpValue(): Map<String, Any?> =
    when (this) {
        is SemanticGroupBy.Dimension -> linkedMapOf(
            "kind" to "DIMENSION",
            "semanticModelName" to semanticModelName.value,
            "name" to name.value,
            "granularity" to granularity?.name,
        )
        is SemanticGroupBy.Entity -> linkedMapOf(
            "kind" to "ENTITY",
            "semanticModelName" to semanticModelName.value,
            "name" to name.value,
        )
        is SemanticGroupBy.MetricTime -> linkedMapOf(
            "kind" to "METRIC_TIME",
            "granularity" to granularity.name,
        )
    }

private fun SemanticElementReference.toHttpValue(): Map<String, String> =
    linkedMapOf(
        "semanticModelName" to semanticModelName.value,
        "name" to name.value,
    )

private fun SemanticMetricTimeRange.toHttpValue(): Map<String, Any> =
    linkedMapOf("start" to start.toHttpValue(), "end" to end.toHttpValue())

private fun SemanticTimeRangeEndpoint.toHttpValue(): Map<String, String> =
    when (this) {
        is SemanticTimeRangeEndpoint.Date -> linkedMapOf("kind" to "DATE", "value" to value.toString())
        is SemanticTimeRangeEndpoint.Timestamp -> linkedMapOf("kind" to "TIMESTAMP", "value" to value.toString())
    }

private fun SemanticQueryFilter.toHttpValue(): Map<String, Any> =
    when (this) {
        is SemanticQueryFilter.Text -> filterHttpValue("TEXT", operator.name, values)
        is SemanticQueryFilter.Number -> filterHttpValue("NUMBER", operator.name, values.map(BigDecimal::toPlainString))
        is SemanticQueryFilter.Boolean -> filterHttpValue("BOOLEAN", operator.name, values)
        is SemanticQueryFilter.Date -> filterHttpValue("DATE", operator.name, values.map(LocalDate::toString))
        is SemanticQueryFilter.Timestamp -> filterHttpValue("TIMESTAMP", operator.name, values.map(Instant::toString))
    }

private fun SemanticQueryFilter.filterHttpValue(
    kind: String,
    operator: String,
    values: List<*>,
): Map<String, Any> = linkedMapOf(
    "kind" to kind,
    "dimension" to dimension.toHttpValue(),
    "operator" to operator,
    "values" to values,
)

private fun SemanticQueryOrder.toHttpValue(): Map<String, Any> =
    linkedMapOf(
        "target" to when (val orderTarget = target) {
            is SemanticQueryOrderTarget.Metric -> linkedMapOf(
                "kind" to "METRIC",
                "metricName" to orderTarget.metricName.value,
            )
            is SemanticQueryOrderTarget.GroupBy -> linkedMapOf(
                "kind" to "GROUP_BY",
                "groupBy" to orderTarget.groupBy.toHttpValue(),
            )
        },
        "direction" to direction.name,
    )

private fun parseQuery(node: ObjectNode): SemanticQuerySpec {
    node.requireExactFields(
        "schemaVersion",
        "metrics",
        "groupBys",
        "metricTimeRange",
        "filters",
        "orderBy",
        "rowLimit",
    )
    require(node.requiredInt("schemaVersion") == SEMANTIC_QUERY_SCHEMA_VERSION) {
        "semantic query schemaVersion must be $SEMANTIC_QUERY_SCHEMA_VERSION"
    }
    return SemanticQuerySpec(
        metrics = node.requiredArray("metrics").map { item ->
            SemanticElementName(item.requiredTextValue())
        },
        groupBys = node.requiredArray("groupBys").map(::parseGroupBy),
        metricTimeRange = node.requiredNullableNode("metricTimeRange")?.let(::parseTimeRange),
        filters = node.requiredArray("filters").mapIndexed { index, filter -> parseFilter(filter, "query.filters[$index]") },
        orderBy = node.requiredArray("orderBy").map(::parseOrder),
        rowLimit = node.requiredInt("rowLimit"),
    )
}

private fun parseGroupBy(node: JsonNode): SemanticGroupBy {
    val value = requireObject(node)
    return when (value.requiredText("kind")) {
        "DIMENSION" -> {
            val fields = value.properties().map(Map.Entry<String, JsonNode>::key).toSet()
            require(
                fields == setOf("kind", "semanticModelName", "name") ||
                    fields == setOf("kind", "semanticModelName", "name", "granularity"),
            ) { "unexpected semantic query fields" }
            SemanticGroupBy.Dimension(
                semanticModelName = SemanticElementName(value.requiredText("semanticModelName")),
                name = SemanticElementName(value.requiredText("name")),
                granularity = value.get("granularity")
                    ?.takeUnless(JsonNode::isNull)
                    ?.let(::parseGranularity),
            )
        }
        "ENTITY" -> {
            value.requireExactFields("kind", "semanticModelName", "name")
            SemanticGroupBy.Entity(
                semanticModelName = SemanticElementName(value.requiredText("semanticModelName")),
                name = SemanticElementName(value.requiredText("name")),
            )
        }
        "METRIC_TIME" -> {
            value.requireExactFields("kind", "granularity")
            SemanticGroupBy.MetricTime(parseGranularity(value.requiredNode("granularity")))
        }
        else -> invalidSemanticQuery("groupBy kind is not supported")
    }
}

private fun parseDimension(node: JsonNode): SemanticElementReference {
    val value = requireObject(node)
    value.requireExactFields("semanticModelName", "name")
    return SemanticElementReference(
        semanticModelName = SemanticElementName(value.requiredText("semanticModelName")),
        name = SemanticElementName(value.requiredText("name")),
    )
}

private fun parseTimeRange(node: JsonNode): SemanticMetricTimeRange {
    val value = requireObject(node)
    value.requireExactFields("start", "end")
    return SemanticMetricTimeRange(
        start = parseTimeRangeEndpoint(value.requiredNode("start")),
        end = parseTimeRangeEndpoint(value.requiredNode("end")),
    )
}

private fun parseTimeRangeEndpoint(node: JsonNode): SemanticTimeRangeEndpoint {
    val value = requireObject(node)
    value.requireExactFields("kind", "value")
    return when (value.requiredText("kind")) {
        "DATE" -> SemanticTimeRangeEndpoint.Date(parseSemanticDate(value.requiredText("value")))
        "TIMESTAMP" -> SemanticTimeRangeEndpoint.Timestamp(parseSemanticTimestamp(value.requiredText("value")))
        else -> invalidSemanticQuery("time range endpoint kind is not supported")
    }
}

private fun parseFilter(node: JsonNode, field: String): SemanticQueryFilter {
    val value = requireObject(node)
    value.requireExactFields("kind", "dimension", "operator", "values")
    val dimension = parseDimension(value.requiredNode("dimension"))
    val operator = value.requiredText("operator")
    val values = value.requiredArray("values")
    return when (value.requiredText("kind")) {
        "TEXT" -> SemanticQueryFilter.Text(
            dimension = dimension,
            operator = parseEnum(operator, "text filter operator"),
            values = values.map(JsonNode::requiredTextValue),
        )
        "NUMBER" -> SemanticQueryFilter.Number(
            dimension = dimension,
            operator = parseEnum(operator, "number filter operator"),
            values = values.map(::parseSemanticNumber),
        )
        "BOOLEAN" -> SemanticQueryFilter.Boolean(
            dimension = dimension,
            operator = parseEnum(operator, "boolean filter operator"),
            values = values.map(JsonNode::requiredBooleanValue),
        )
        "DATE" -> SemanticQueryFilter.Date(
            dimension = dimension,
            operator = parseEnum(operator, "date filter operator"),
            values = values.map { item -> parseSemanticDate(item.requiredTextValue()) },
        )
        "TIMESTAMP" -> SemanticQueryFilter.Timestamp(
            dimension = dimension,
            operator = parseEnum(operator, "timestamp filter operator"),
            values = values.mapIndexed { index, item ->
                val raw = item.requiredTextValue()
                if (Regex("\\.[0-9]{7}").containsMatchIn(raw)) {
                    val message = "Timestamp filter values must have at most 6 fractional second digits."
                    throw SemanticQueryRejected(
                        HttpStatus.BAD_REQUEST,
                        message,
                        listOf(SemanticProblemViolation("$field.values[$index]", "SCHEMA_MISMATCH", message)),
                    )
                }
                parseSemanticTimestamp(raw)
            },
        )
        else -> invalidSemanticQuery("filter kind is not supported")
    }
}

private fun parseOrder(node: JsonNode): SemanticQueryOrder {
    val value = requireObject(node)
    value.requireExactFields("target", "direction")
    return SemanticQueryOrder(
        target = parseOrderTarget(value.requiredNode("target")),
        direction = parseEnum(value.requiredText("direction"), "order direction"),
    )
}

private fun parseOrderTarget(node: JsonNode): SemanticQueryOrderTarget {
    val value = requireObject(node)
    return when (value.requiredText("kind")) {
        "METRIC" -> {
            value.requireExactFields("kind", "metricName")
            SemanticQueryOrderTarget.Metric(
                SemanticElementName(value.requiredText("metricName")),
            )
        }
        "GROUP_BY" -> {
            value.requireExactFields("kind", "groupBy")
            SemanticQueryOrderTarget.GroupBy(parseGroupBy(value.requiredNode("groupBy")))
        }
        else -> invalidSemanticQuery("order target kind is not supported")
    }
}

private fun parseGranularity(node: JsonNode): SemanticTimeGranularity =
    parseEnum(node.requiredTextValue(), "time granularity")

private fun parseSemanticNumber(node: JsonNode): BigDecimal {
    val value = node.requiredTextValue()
    require(SEMANTIC_NUMBER_PATTERN.matches(value)) {
        "Number filter values must be canonical decimal strings with at most 20 integer digits and 18 fractional digits."
    }
    return BigDecimal(value)
}

private fun parseSemanticDate(value: String): LocalDate =
    try {
        LocalDate.parse(value)
    } catch (_: DateTimeParseException) {
        invalidSemanticQuery("Use a valid ISO 8601 date.")
    }

private fun parseSemanticTimestamp(value: String): Instant =
    try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) {
        invalidSemanticQuery("Use a valid ISO 8601 timestamp with an offset.")
    }

private fun parseConnectorId(raw: String): ConnectorId =
    ConnectorId(parseUuid(raw, "connector id"))

private fun parseUuid(raw: String, label: String): Uuid =
    try {
        Uuid.parse(raw)
    } catch (_: IllegalArgumentException) {
        invalidSemanticQuery("$label is invalid")
    }

private fun requireText(value: String?, label: String): String =
    value?.takeIf(String::isNotBlank) ?: invalidSemanticQuery("$label is required")

private fun requireObject(node: JsonNode): ObjectNode =
    node as? ObjectNode ?: invalidSemanticQuery("value must be an object")

private fun ObjectNode.requireExactFields(vararg names: String) {
    require(properties().map(Map.Entry<String, JsonNode>::key).toSet() == names.toSet()) {
        "unexpected semantic query fields"
    }
}

private fun JsonNode.requiredNode(name: String): JsonNode =
    get(name) ?: invalidSemanticQuery("$name is required")

private fun JsonNode.requiredNullableNode(name: String): JsonNode? =
    requiredNode(name).takeUnless(JsonNode::isNull)

private fun JsonNode.requiredArray(name: String): List<JsonNode> {
    val value = requiredNode(name)
    require(value.isArray) { "$name must be an array" }
    return value.toList()
}

private fun JsonNode.requiredText(name: String): String =
    requiredNode(name).requiredTextValue()

private fun JsonNode.requiredTextValue(): String {
    require(isString) { "value must be text" }
    return stringValue()
}

private fun JsonNode.requiredBooleanValue(): Boolean {
    require(isBoolean) { "value must be boolean" }
    return booleanValue()
}

private fun JsonNode.requiredInt(name: String): Int {
    val value = requiredNode(name)
    require(value.isIntegralNumber && value.canConvertToInt()) { "$name must be an integer" }
    return value.intValue()
}

private inline fun <reified T : Enum<T>> parseEnum(raw: String, label: String): T =
    enumValues<T>().firstOrNull { candidate -> candidate.name == raw } ?:
        invalidSemanticQuery("$label is invalid")

private inline fun <T> decodeSemanticQueryTransport(block: () -> T): T =
    try {
        block()
    } catch (exception: ResponseStatusException) {
        throw exception
    } catch (exception: IllegalArgumentException) {
        throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            exception.message ?: "semantic query is invalid",
            exception,
        )
    }

private fun invalidSemanticQuery(message: String): Nothing =
    throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)

private const val SEMANTIC_QUERY_SCHEMA_VERSION = 4

private val SEMANTIC_NUMBER_PATTERN = Regex(
    "^(?:0|-?(?:(?:[1-9][0-9]{0,19})(?:\\.[0-9]{0,17}[1-9])?|0\\.[0-9]{0,17}[1-9]))$",
)
