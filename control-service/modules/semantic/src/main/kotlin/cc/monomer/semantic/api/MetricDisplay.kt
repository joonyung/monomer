package cc.monomer.semantic.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

sealed interface MetricDisplay {
    val decimals: Int

    data class Number(override val decimals: Int, val unit: MetricDisplayUnit?) : MetricDisplay {
        init { require(decimals in 0..6) }
    }

    data class Currency(override val decimals: Int, val currency: MetricDisplayCurrency) : MetricDisplay {
        init { require(decimals in 0..6) }
    }

    data class Percent(override val decimals: Int) : MetricDisplay {
        init { require(decimals in 0..6) }
    }
}

enum class MetricDisplayUnit {
    PERCENT, SECOND, MILLISECOND, MINUTE, HOUR, DAY, BYTE, KILOBYTE, MEGABYTE, GIGABYTE,
}

data class SemanticOutputColumnDisplay(val key: SemanticResultColumnKey, val display: MetricDisplay?)

fun storedMetricDisplays(
    resultColumnKeys: List<String>,
    outputColumnDisplays: List<SemanticOutputColumnDisplay>?,
): List<MetricDisplay?> = if (outputColumnDisplays?.map { it.key.value } == resultColumnKeys) {
    outputColumnDisplays.map { it.display }
} else {
    List(resultColumnKeys.size) { null }
}

object MetricDisplayJsonCodec {
    fun encode(display: MetricDisplay): JsonObject = buildJsonObject {
        put("style", when (display) {
            is MetricDisplay.Number -> "NUMBER"
            is MetricDisplay.Currency -> "CURRENCY"
            is MetricDisplay.Percent -> "PERCENT"
        })
        put("decimals", display.decimals)
        when (display) {
            is MetricDisplay.Number -> display.unit?.let { put("unit", it.name.lowercase()) }
            is MetricDisplay.Currency -> put("currency", display.currency.name)
            is MetricDisplay.Percent -> Unit
        }
    }

    fun decode(value: JsonElement): MetricDisplay {
        val node = requireNotNull(value as? JsonObject) { "display must be an object" }
        val style = node.text("style")
        val decimals = requireNotNull((node["decimals"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull) {
            "display.decimals must be an integer"
        }
        return when (style) {
            "NUMBER" -> {
                require(node.keys.containsAll(setOf("style", "decimals")) && node.keys.all { it in setOf("style", "decimals", "unit") })
                MetricDisplay.Number(decimals, node["unit"]?.let {
                    val unit = node.text("unit")
                    requireNotNull(MetricDisplayUnit.entries.singleOrNull { it.name.lowercase() == unit }) {
                        "display.unit is invalid"
                    }
                })
            }
            "CURRENCY" -> {
                require(node.keys == setOf("style", "decimals", "currency"))
                MetricDisplay.Currency(decimals, MetricDisplayCurrency.valueOf(node.text("currency")))
            }
            "PERCENT" -> {
                require(node.keys == setOf("style", "decimals"))
                MetricDisplay.Percent(decimals)
            }
            else -> throw IllegalArgumentException("display.style is invalid")
        }
    }

    fun encodeColumns(columns: List<SemanticOutputColumnDisplay>): JsonArray = JsonArray(columns.map { column ->
        buildJsonObject {
            put("key", column.key.value)
            put("display", column.display?.let(::encode) ?: JsonNull)
        }
    })

    fun decodeColumns(value: JsonElement): List<SemanticOutputColumnDisplay> =
        requireNotNull(value as? JsonArray) { "output column displays must be an array" }.map { element ->
            val node = requireNotNull(element as? JsonObject)
            require(node.keys == setOf("key", "display"))
            SemanticOutputColumnDisplay(
                SemanticResultColumnKey(node.text("key")),
                node.getValue("display").takeUnless { it == JsonNull }?.let(::decode),
            )
        }

    private fun JsonObject.text(field: String): String =
        requireNotNull((get(field) as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "display.$field must be a string"
        }
}
