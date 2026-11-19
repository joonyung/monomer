package cc.monomer.execution.driver.jdbc

import cc.monomer.execution.driver.spi.NullQueryParameterType
import cc.monomer.execution.driver.spi.QueryParameter
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals

class QueryParameterBindingTest {
    @Test
    fun bindsExactValuesAndTypedNullsWithoutSqlInterpolation() {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val statement = Proxy.newProxyInstance(
            PreparedStatement::class.java.classLoader,
            arrayOf(PreparedStatement::class.java),
        ) { _, method, arguments -> calls += method.name to arguments.orEmpty().toList(); null } as PreparedStatement
        val timestamp = Instant.parse("2026-10-06T01:02:03.123456Z")
        val date = LocalDate.parse("2026-10-06")
        statement.bindQueryParameters(listOf(
            QueryParameter.Text("x' OR '1'='1", name = ""),
            QueryParameter.Decimal("12345678901234567890.123456789012345678", name = ""),
            QueryParameter.Date(date, name = ""),
            QueryParameter.Timestamp(timestamp, name = ""),
            QueryParameter.Null(NullQueryParameterType.DECIMAL, name = ""),
            QueryParameter.Null(NullQueryParameterType.DATE, name = ""),
            QueryParameter.Null(NullQueryParameterType.TIMESTAMP, name = ""),
        ))
        assertEquals(listOf("setString", "setBigDecimal", "setDate", "setTimestamp", "setNull", "setNull", "setNull"), calls.map { it.first })
        assertEquals("x' OR '1'='1", calls[0].second[1])
        assertEquals(BigDecimal("12345678901234567890.123456789012345678"), calls[1].second[1])
        assertEquals(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), (calls[2].second[1] as java.sql.Date).time)
        assertEquals(timestamp, (calls[3].second[1] as java.sql.Timestamp).toInstant())
        assertEquals("UTC", (calls[2].second[2] as Calendar).timeZone.id)
        assertEquals("UTC", (calls[3].second[2] as Calendar).timeZone.id)
        assertEquals(listOf(Types.DECIMAL, Types.DATE, Types.TIMESTAMP), calls.drop(4).map { it.second[1] })
    }
}
