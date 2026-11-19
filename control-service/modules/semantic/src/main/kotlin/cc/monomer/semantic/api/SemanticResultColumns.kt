package cc.monomer.semantic.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@JvmInline
value class SemanticResultColumnKey(val value: String) {
    init {
        val segments = value.split('.')
        val granularityNames = SemanticTimeGranularity.entries.map { it.name.lowercase() }
        when (segments.first()) {
            "metric" -> require(segments.size == 2)
            "dimension" -> require(segments.size == 3 || segments.size == 4 && segments.last() in granularityNames)
            "entity" -> require(segments.size == 3)
            "metric_time" -> require(segments.size == 2 && segments.last() in granularityNames)
            else -> throw IllegalArgumentException("invalid semantic result column key kind")
        }
        segments.drop(1).forEach(::SemanticElementName)
    }

    override fun toString(): String = value
}

data class SemanticResultColumn(
    val key: SemanticResultColumnKey,
    val sqlName: String,
)

fun SemanticElementName.metricResultColumnKey(): SemanticResultColumnKey =
    SemanticResultColumnKey("metric.$value")

fun SemanticGroupBy.resultColumnKey(): SemanticResultColumnKey = SemanticResultColumnKey(
    when (this) {
        is SemanticGroupBy.Dimension -> listOfNotNull(
            "dimension", semanticModelName.value, name.value, granularity?.name?.lowercase(),
        ).joinToString(".")
        is SemanticGroupBy.Entity -> "entity.${semanticModelName.value}.${name.value}"
        is SemanticGroupBy.MetricTime -> "metric_time.${granularity.name.lowercase()}"
    },
)

fun SemanticQuerySpec.resultColumns(): List<SemanticResultColumn> =
    SemanticResultColumns.projection(metrics, groupBys)

object SemanticResultColumns {
    fun projection(
        metrics: List<SemanticElementName>,
        groupBys: List<SemanticGroupBy>,
    ): List<SemanticResultColumn> {
        val keys = groupBys.map(SemanticGroupBy::resultColumnKey) +
            metrics.map(SemanticElementName::metricResultColumnKey)
        return fromKeys(keys)
    }

    fun fromKeys(keys: List<SemanticResultColumnKey>): List<SemanticResultColumn> {
        require(keys.toSet().size == keys.size) { "semantic result projections must be unique" }
        return keys.mapIndexed { index, key -> SemanticResultColumn(key, "__monomer_result_$index") }
    }

    fun keys(metrics: JsonArray, groupBys: JsonArray): List<SemanticResultColumnKey> =
        projection(
            metrics.map { SemanticElementName(it.requireResultSelectorText()) },
            groupBys.map { element ->
                val groupBy = element as? JsonObject
                    ?: throw IllegalArgumentException("semantic group-by must be an object")
                fun name(field: String): SemanticElementName =
                    SemanticElementName(groupBy.getValue(field).requireResultSelectorText())
                val granularity = groupBy["granularity"]?.takeUnless { it == JsonNull }?.let {
                    SemanticTimeGranularity.valueOf(it.requireResultSelectorText())
                }
                when (groupBy.getValue("kind").requireResultSelectorText()) {
                    "DIMENSION" -> SemanticGroupBy.Dimension(name("semanticModelName"), name("name"), granularity)
                    "ENTITY" -> SemanticGroupBy.Entity(name("semanticModelName"), name("name"))
                    "METRIC_TIME" -> SemanticGroupBy.MetricTime(requireNotNull(granularity))
                    else -> throw IllegalArgumentException("unknown semantic group-by kind")
                }
            },
        ).map(SemanticResultColumn::key)
}

private fun kotlinx.serialization.json.JsonElement.requireResultSelectorText(): String {
    val primitive = this as? JsonPrimitive
    require(primitive != null && primitive.isString) { "semantic selector must be a string" }
    return primitive.content
}
