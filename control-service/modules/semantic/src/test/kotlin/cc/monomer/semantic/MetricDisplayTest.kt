package cc.monomer.semantic

import cc.monomer.semantic.api.MetricDisplay
import cc.monomer.semantic.api.MetricDisplayCurrency
import cc.monomer.semantic.api.MetricDisplayJsonCodec
import cc.monomer.semantic.api.MetricDisplayUnit
import cc.monomer.semantic.api.SemanticOutputColumnDisplay
import cc.monomer.semantic.api.SemanticResultColumnKey
import cc.monomer.semantic.api.storedMetricDisplays
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MetricDisplayTest {
    @Test
    fun `all display styles preserve exact contract shape`() {
        val displays = listOf(
            MetricDisplay.Number(0, null),
            MetricDisplay.Number(6, MetricDisplayUnit.MILLISECOND),
            MetricDisplay.Currency(2, MetricDisplayCurrency.USD),
            MetricDisplay.Percent(1),
        )
        displays.forEach { display -> assertEquals(display, MetricDisplayJsonCodec.decode(MetricDisplayJsonCodec.encode(display))) }
    }

    @Test
    fun `display decoder rejects unknown currency and inappropriate fields`() {
        listOf(
            """{"style":"CURRENCY","decimals":2,"currency":"ZZZ"}""",
            """{"style":"NUMBER","decimals":2,"currency":"USD"}""",
            """{"style":"PERCENT","decimals":2,"unit":"percent"}""",
            """{"style":"NUMBER","decimals":"2"}""",
            """{"style":"NUMBER","decimals":7}""",
            """{"style":"NUMBER","decimals":2,"unit":null}""",
        ).forEach { json ->
            assertFailsWith<IllegalArgumentException> { MetricDisplayJsonCodec.decode(Json.parseToJsonElement(json)) }
        }
    }

    @Test
    fun `stored displays join by the whole ordered result projection`() {
        val amount = MetricDisplay.Currency(2, MetricDisplayCurrency.USD)
        val columns = listOf(
            SemanticOutputColumnDisplay(SemanticResultColumnKey("dimension.orders.status"), null),
            SemanticOutputColumnDisplay(SemanticResultColumnKey("metric.revenue"), amount),
        )
        val keys = columns.map { it.key.value }
        assertEquals(listOf(null, amount), storedMetricDisplays(keys, columns))
        assertEquals(listOf(null, null), storedMetricDisplays(keys.reversed(), columns))
        assertEquals(listOf(null, null), storedMetricDisplays(keys, columns.take(1)))
        assertEquals(listOf(null, null), storedMetricDisplays(keys, null))
        assertEquals(columns, MetricDisplayJsonCodec.decodeColumns(MetricDisplayJsonCodec.encodeColumns(columns)))
    }
}
