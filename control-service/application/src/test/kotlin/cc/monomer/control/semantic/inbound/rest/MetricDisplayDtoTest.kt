package cc.monomer.control.semantic.inbound.rest

import cc.monomer.semantic.api.MetricDisplay
import cc.monomer.semantic.api.MetricDisplayCurrency
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MetricDisplayDtoTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `REST display serializes closed styles and rejects inappropriate or unknown fields`() {
        listOf(MetricDisplay.Number(0, null), MetricDisplay.Percent(2), MetricDisplay.Currency(2, MetricDisplayCurrency.USD)).forEach { display ->
            val text = mapper.writeValueAsString(MetricDisplayDto.fromDomain(display))
            assertEquals(display, mapper.readValue(text, MetricDisplayDto::class.java).toDomain())
        }
        listOf(
            """{"style":"NUMBER","decimals":2,"currency":null}""",
            """{"style":"PERCENT","decimals":2,"unit":"percent"}""",
            """{"style":"CURRENCY","decimals":2,"currency":"ZZZ"}""",
            """{"style":"PERCENT","decimals":7}""",
            """{"style":"NUMBER","decimals":"2"}""",
        ).forEach { invalid -> assertFailsWith<com.fasterxml.jackson.databind.JsonMappingException> { mapper.readValue(invalid, MetricDisplayDto::class.java) } }
    }
}
