package cc.monomer.control.semantic.inbound.rest

import cc.monomer.semantic.api.MetricDisplay
import cc.monomer.semantic.api.MetricDisplayCurrency
import cc.monomer.semantic.api.MetricDisplayUnit
import cc.monomer.semantic.api.MetricDisplayJsonCodec
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import kotlinx.serialization.json.Json

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(using = MetricDisplayDtoDeserializer::class)
data class MetricDisplayDto(val style: String, val decimals: Int, val currency: String?, val unit: String?) {
    fun toDomain(): MetricDisplay = when (style) {
        "NUMBER" -> {
            require(currency == null) { "display.currency is allowed only for CURRENCY" }
            MetricDisplay.Number(decimals, unit?.let { name ->
                requireNotNull(MetricDisplayUnit.entries.singleOrNull { it.name.lowercase() == name }) { "display.unit is invalid" }
            })
        }
        "CURRENCY" -> {
            require(unit == null) { "display.unit is allowed only for NUMBER" }
            MetricDisplay.Currency(decimals, MetricDisplayCurrency.valueOf(requireNotNull(currency) { "display.currency is required" }))
        }
        "PERCENT" -> {
            require(currency == null && unit == null) { "display.currency and display.unit are not allowed for PERCENT" }
            MetricDisplay.Percent(decimals)
        }
        else -> throw IllegalArgumentException("display.style is invalid")
    }

    companion object {
        fun fromDomain(display: MetricDisplay): MetricDisplayDto = when (display) {
            is MetricDisplay.Number -> MetricDisplayDto("NUMBER", display.decimals, null, display.unit?.name?.lowercase())
            is MetricDisplay.Currency -> MetricDisplayDto("CURRENCY", display.decimals, display.currency.name, null)
            is MetricDisplay.Percent -> MetricDisplayDto("PERCENT", display.decimals, null, null)
        }
    }
}

class MetricDisplayDtoDeserializer : JsonDeserializer<MetricDisplayDto>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): MetricDisplayDto {
        val node = parser.codec.readTree<JsonNode>(parser)
        return try {
            MetricDisplayDto.fromDomain(MetricDisplayJsonCodec.decode(Json.parseToJsonElement(node.toString())))
        } catch (failure: IllegalArgumentException) {
            context.reportInputMismatch(MetricDisplayDto::class.java, failure.message ?: "display is invalid")
        }
    }
}
