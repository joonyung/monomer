package cc.monomer.execution.driver.databricks

import cc.monomer.execution.driver.spi.*
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

class DatabricksQueryBindingsTest {
    @Test fun `seven supported scalar forms send exact types and strings`() {
        val parameters = listOf(
            QueryParameter.Timestamp(Instant.parse("2024-02-29T12:34:56.123456Z"), "timestamp_value"),
            QueryParameter.Date(LocalDate.parse("2024-02-29"), "date_value"),
            QueryParameter.Decimal("0.0001", "decimal_value"),
            QueryParameter.BooleanValue(true, "boolean_value"),
            QueryParameter.Float64(1.25, "float_value"),
            QueryParameter.Integer64(9_007_199_254_740_993L, "integer_value"),
            QueryParameter.Text("monomer_named_scalar", "text_value"),
        )
        val expected = jsonMapper.readTree("""[
            {"name":"timestamp_value","type":"TIMESTAMP","value":"2024-02-29T12:34:56.123456Z"},
            {"name":"date_value","type":"DATE","value":"2024-02-29"},
            {"name":"decimal_value","type":"DECIMAL(4,4)","value":"0.0001"},
            {"name":"boolean_value","type":"BOOLEAN","value":"true"},
            {"name":"float_value","type":"DOUBLE","value":"1.25"},
            {"name":"integer_value","type":"BIGINT","value":"9007199254740993"},
            {"name":"text_value","type":"STRING","value":"monomer_named_scalar"}
        ]""")
        assertEquals(expected, databricksParameters(parameters))
    }

    @Test fun `typed values preserve exact decimal timestamp and date`() {
        val values = databricksParameters(listOf(
            QueryParameter.Decimal("0.000000000000000001", "small"),
            QueryParameter.Decimal("-0.01", "negative"),
            QueryParameter.Timestamp(Instant.parse("1969-12-31T23:59:59.123456Z"), "instant"),
            QueryParameter.Date(LocalDate.parse("1969-12-31"), "date"),
            QueryParameter.Integer64(Long.MIN_VALUE, "integer"),
            QueryParameter.Float64(Double.MAX_VALUE, "float"),
            QueryParameter.BooleanValue(false, "boolean"),
            QueryParameter.Text("synthetic-secret", "text"),
        ))
        assertEquals("DECIMAL(18,18)", values[0].path("type").asText())
        assertEquals("0.000000000000000001", values[0].path("value").asText())
        assertEquals("DECIMAL(2,2)", values[1].path("type").asText())
        assertEquals("1969-12-31T23:59:59.123456Z", values[2].path("value").asText())
        assertEquals("1969-12-31", values[3].path("value").asText())
        assertEquals(Long.MIN_VALUE.toString(), values[4].path("value").asText())
        assertEquals(Double.MAX_VALUE, values[5].path("value").asText().toDouble())
        assertEquals("false", values[6].path("value").asText())
    }
    @Test fun `supported typed null omits value and every binary bind fails`() {
        val nulls = databricksParameters(NullQueryParameterType.entries.filterNot { it == NullQueryParameterType.BYTES }.map { QueryParameter.Null(it, it.name) })
        assertTrue(nulls.all { !it.has("value") })
        assertEquals(listOf("STRING", "BIGINT", "DOUBLE", "BOOLEAN", "DECIMAL(38,18)", "DATE", "TIMESTAMP"), nulls.map { it.path("type").asText() })
        listOf(QueryParameter.Bytes(BinaryValue.copyOf(byteArrayOf(0)), "bytes"), QueryParameter.Null(NullQueryParameterType.BYTES, "bytes")).forEach { parameter ->
            val failure = assertFailsWith<DatabricksFailure> { databricksParameters(listOf(parameter)) }
            assertEquals("QUERY_BIND_UNSUPPORTED", failure.code)
            assertEquals(BYTES_UNSUPPORTED_MESSAGE, failure.message)
            assertNull(failure.cause)
        }
    }
}
