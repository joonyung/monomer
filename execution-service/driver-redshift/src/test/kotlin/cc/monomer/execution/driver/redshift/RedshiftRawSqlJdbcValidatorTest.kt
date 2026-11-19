package cc.monomer.execution.driver.redshift

import cc.monomer.execution.driver.spi.QueryParameter
import com.amazon.redshift.core.BaseConnection
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSetMetaData
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RedshiftRawSqlJdbcValidatorTest {
    @Test
    fun timestampBindingsPreserveInstantAndTypedNullOid() {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val statement = Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java)) {
            _, method, args -> calls += method.name to args.orEmpty().toList(); null
        } as PreparedStatement
        val instant = java.time.Instant.parse("2026-10-06T01:02:03.123456Z")
        statement.bindRedshiftQueryParameters(listOf(
            QueryParameter.Timestamp(instant, name = ""),
            QueryParameter.Null(cc.monomer.execution.driver.spi.NullQueryParameterType.TIMESTAMP, name = ""),
        ))
        assertEquals(listOf(1, instant.atOffset(java.time.ZoneOffset.UTC)), calls[0].second)
        assertEquals(listOf(2, java.sql.Types.TIMESTAMP_WITH_TIMEZONE, "timestamptz"), calls[1].second)
    }

    @Test
    fun `describes original SQL and bound values without executing it`() {
        val fixture = DescribeFixture()
        val sql = "SELECT * FROM unseen.database.relation WHERE status = ?;"
        val value = "paid'; DELETE FROM orders; --"
        fixture.validator.validate(sql, listOf(QueryParameter.Text(value, name = "")))
        assertEquals(listOf(sql), fixture.preparedSql)
        assertEquals(listOf(1 to value), fixture.bindings)
        assertEquals(listOf(9), fixture.timeouts)
        assertEquals(1, fixture.descriptions)
        assertEquals(1, fixture.closes)
    }

    @Test
    fun `accepts native SQL corpus including trailing delimiters and Redshift dialect`() {
        listOf(
            "SELECT 1;",
            "SELECT 1; -- trailing comment",
            "SELECT 1; /* trailing comment */",
            "SELECT 1; /* nested /* comment */ still comment */ -- end",
            "SELECT 1 -- a; b",
            "-- leading comment\nSELECT 1;",
            "SELECT ';';",
            "/* leading ; comment */ SELECT ';' AS \"semi;colon\";",
            "SELECT 'it''s;valid';",
            "SELECT \$\$semi;colon\$\$;",
            "WITH source AS (SELECT 1 AS n) SELECT n FROM source;",
            "WITH RECURSIVE source(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM source WHERE n < 3) SELECT n FROM source;",
            "SELECT n, row_number() OVER (ORDER BY n) FROM source;",
            "SELECT * FROM source s QUALIFY row_number() OVER (ORDER BY n) = 1;",
            "SELECT payload.attribute FROM events;",
            "SELECT set_config('transaction_read_only', 'off', false);",
        ).forEach { sql ->
            val fixture = DescribeFixture()
            fixture.validator.validate(sql, emptyList())
            assertEquals(listOf(sql), fixture.preparedSql)
            assertEquals(1, fixture.descriptions)
        }
    }

    @Test
    fun `rejects multiple statements before preparing or describing`() {
        listOf(
            "SELECT 1; SELECT 2", "SELECT 1; DELETE FROM orders", "SELECT ';'; SET search_path TO public",
            "SELECT 1; /* comment */ SET search_path TO public",
            "SELECT 1; -- comment\n SET search_path TO public",
            "SELECT 1; /* nested /* comment */ end */ SELECT 2",
            "SELECT 1; /", "SELECT 1; -", "SELECT 1; /* unfinished", "SELECT 1; /* nested /* unfinished */",
        ).forEach { sql ->
            val fixture = DescribeFixture()
            assertFailsWith<IllegalArgumentException> { fixture.validator.validate(sql, emptyList()) }
            assertEquals(emptyList(), fixture.preparedSql)
            assertEquals(0, fixture.descriptions)
        }
    }

    @Test
    fun `uses the actual connection string escaping mode`() {
        val sql = "SELECT 'a\\'; SELECT 2; --'"
        val standard = DescribeFixture(standardConformingStrings = true)
        assertFailsWith<IllegalArgumentException> { standard.validator.validate(sql, emptyList()) }
        assertEquals(emptyList(), standard.preparedSql)
        val escaped = DescribeFixture(standardConformingStrings = false)
        escaped.validator.validate(sql, emptyList())
        assertEquals(listOf(sql), escaped.preparedSql)
    }

    @Test
    fun `rejects native no-result statements without executing them`() {
        listOf(
            "INSERT INTO orders VALUES (1)", "UPDATE orders SET n = 1", "DELETE FROM orders",
            "CREATE TABLE accidental (n integer)", "DROP TABLE orders", "SET search_path TO public",
            "BEGIN", "COMMIT", "ROLLBACK", "SELECT 1 INTO accidental",
        ).forEach { sql ->
            listOf(null, 0).forEach { columnCount ->
                val fixture = DescribeFixture(columnCount = columnCount)
                assertFailsWith<IllegalArgumentException> { fixture.validator.validate(sql, emptyList()) }
                assertEquals(listOf(sql), fixture.preparedSql)
                assertEquals(1, fixture.descriptions)
                assertEquals(1, fixture.closes)
            }
        }
    }

    @Test
    fun `rejects missing or surplus bindings before describing`() {
        listOf("SELECT ?" to emptyList(), "SELECT 1" to listOf(QueryParameter.Text("extra", name = ""))).forEach { (sql, bindings) ->
            val fixture = DescribeFixture()
            assertFailsWith<IllegalArgumentException> { fixture.validator.validate(sql, bindings) }
            assertEquals(emptyList(), fixture.preparedSql)
        }
    }

    @Test
    fun `rejects blank and oversized SQL before preparing`() {
        listOf("", " ", "-- comment", "/* comment */", "SELECT '" + "가".repeat(350_000) + "'").forEach { sql ->
            val fixture = DescribeFixture()
            assertFailsWith<IllegalArgumentException> { fixture.validator.validate(sql, emptyList()) }
            assertEquals(emptyList(), fixture.preparedSql)
        }
    }

    @Test
    fun `propagates native describe failure and closes the statement`() {
        val fixture = DescribeFixture(describeFailure = SQLException("invalid SQL", "42601"))
        assertFailsWith<SQLException> { fixture.validator.validate("SELECT invalid", emptyList()) }
        assertEquals(1, fixture.closes)
    }

    private class DescribeFixture(
        standardConformingStrings: Boolean = true,
        columnCount: Int? = 1,
        describeFailure: SQLException? = null,
    ) {
        val preparedSql = mutableListOf<String>()
        val bindings = mutableListOf<Pair<Int, String>>()
        val timeouts = mutableListOf<Int>()
        var descriptions = 0
        var closes = 0
        private val statement = proxy<PreparedStatement> { name, args ->
            when (name) {
                "setString" -> { bindings += (args[0] as Int) to (args[1] as String); null }
                "setQueryTimeout" -> { timeouts += args[0] as Int; null }
                "getMetaData" -> {
                    descriptions++
                    describeFailure?.let { throw it }
                    columnCount?.let { count -> proxy<ResultSetMetaData> { method, _ ->
                        if (method == "getColumnCount") count else error(method)
                    } }
                }
                "close" -> { closes++; null }
                else -> error("Unexpected statement call: $name")
            }
        }
        private val nativeConnection = proxy<BaseConnection> { name, _ ->
            if (name == "getStandardConformingStrings") standardConformingStrings else error(name)
        }
        private val connection = proxy<Connection> { name, args ->
            when (name) {
                "unwrap" -> { assertEquals(BaseConnection::class.java, args[0]); nativeConnection }
                "prepareStatement" -> { preparedSql += args[0] as String; statement }
                else -> error(name)
            }
        }
        val validator = RedshiftRawSqlJdbcValidator(connection, { 9 })
    }

    companion object {
        private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                invoke(method.name, args.orEmpty())
            } as T
    }
}
