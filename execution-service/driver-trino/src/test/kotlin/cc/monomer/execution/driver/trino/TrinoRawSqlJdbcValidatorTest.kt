package cc.monomer.execution.driver.trino

import cc.monomer.execution.driver.spi.QueryParameter
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ParameterMetaData
import java.sql.ResultSetMetaData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class TrinoRawSqlJdbcValidatorTest {
    @Test
    fun timestampUsesExactInstantBindingInsteadOfMillisecondTimestampSetter() {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val statement = proxy<PreparedStatement> { name, args -> calls += name to args.toList(); null }
        val instant = java.time.Instant.parse("2026-10-06T01:02:03.123456Z")
        statement.bindTrinoQueryParameters(listOf(
            QueryParameter.Timestamp(instant, name = ""),
            QueryParameter.Null(cc.monomer.execution.driver.spi.NullQueryParameterType.TIMESTAMP, name = ""),
        ))
        assertEquals("setObject", calls[0].first)
        assertEquals(listOf(1, instant, java.sql.Types.TIMESTAMP_WITH_TIMEZONE), calls[0].second)
        assertEquals("setNull", calls[1].first)
        assertEquals(listOf(2, java.sql.Types.TIMESTAMP_WITH_TIMEZONE), calls[1].second)
    }

    @Test
    fun `validates SQL with bound parameters without relation metadata`() {
        val calls = mutableListOf<String>()
        var parameter: String? = null
        var timeout = 0
        var next = true
        val rows = proxy<ResultSet> { name, _ ->
            when (name) {
                "next" -> next.also { next = false }
                "close" -> null
                else -> error(name)
            }
        }
        val statement = proxy<PreparedStatement> { name, args ->
            calls += name
            when (name) {
                "setString" -> { assertEquals(1, args[0]); parameter = args[1] as String; null }
                "setQueryTimeout" -> { timeout = args[0] as Int; null }
                "executeQuery" -> rows
                "getParameterMetaData" -> proxy<ParameterMetaData> { method, _ ->
                    if (method == "getParameterCount") 1 else error(method)
                }
                "getMetaData" -> proxy<ResultSetMetaData> { method, _ ->
                    if (method == "getColumnCount") 1 else error(method)
                }
                "close" -> null
                else -> error(name)
            }
        }
        val sql = "SELECT * FROM unseen.database.relation WHERE status = ?"
        val connection = proxy<Connection> { name, args ->
            assertEquals("prepareStatement", name)
            assertEquals("" + sql, args[0])
            statement
        }
        TrinoRawSqlJdbcValidator(connection, { 9 }).validate(sql, listOf(QueryParameter.Text("paid", name = "")))
        assertEquals("paid", parameter)
        assertEquals(9, timeout)
        assertTrue("close" in calls)
        assertTrue("executeQuery" !in calls)
    }

    @Test
    fun `forwards native SQL corpus and scalar bindings unchanged to JDBC`() {
        val corpus: List<Pair<String, List<QueryParameter>>> =
            listOf(
                "SELECT 1 -- trailing comment" to emptyList(),
                "-- leading comment\n/* block ; comment */ SELECT ';' AS \"semi;colon\"" to emptyList(),
                "SELECT 'it''s;valid' AS value" to emptyList(),
                "SELECT 1 AS \"MixedCase\"" to emptyList(),
                "WITH RECURSIVE source(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM source WHERE n < 3) SELECT n FROM source" to emptyList(),
                "SELECT row_number() OVER (ORDER BY n) FROM UNNEST(ARRAY[1, 2]) t(n)" to emptyList(),
                "SELECT array_agg(n) FROM UNNEST(ARRAY[1, 2]) t(n)" to emptyList(),
                "VALUES (1)" to emptyList(),
                "TABLE orders" to emptyList(),
                "SELECT ? AS status" to listOf(QueryParameter.Text("paid'; DELETE FROM orders; --", name = "")),
            )

        corpus.forEach { (sql, parameters) ->
            val preparedSql = mutableListOf<String>()
            val bindings = mutableMapOf<Int, Any>()
            val calls = mutableListOf<String>()
            val statement = proxy<PreparedStatement> { name, args ->
                calls += name
                when (name) {
                    "setString" -> {
                        bindings[args[0] as Int] = args[1] as String
                        null
                    }
                    "setQueryTimeout" -> null
                    "getParameterMetaData" -> proxy<ParameterMetaData> { method, _ ->
                        if (method == "getParameterCount") parameters.size else error(method)
                    }
                    "getMetaData" -> proxy<ResultSetMetaData> { method, _ ->
                        if (method == "getColumnCount") 1 else error(method)
                    }
                    "close" -> null
                    else -> error(name)
                }
            }
            val connection = proxy<Connection> { name, args ->
                assertEquals("prepareStatement", name)
                preparedSql += args[0] as String
                statement
            }

            TrinoRawSqlJdbcValidator(connection, { 9 }).validate(sql, parameters)

            assertEquals(listOf(sql), preparedSql)
            val expectedBindings: Map<Int, Any> =
                parameters.mapIndexed { index, parameter -> index + 1 to (parameter as QueryParameter.Text).value }.toMap()
            assertEquals(
                expectedBindings,
                bindings,
            )
            assertTrue("executeQuery" !in calls)
        }
    }

    @Test
    fun `propagates native validation failure`() {
        val connection = proxy<Connection> { _, _ -> throw java.sql.SQLException("invalid SQL") }
        assertFailsWith<java.sql.SQLException> {
            TrinoRawSqlJdbcValidator(connection, { 9 }).validate("SELECT 1", emptyList())
        }
    }

    @Test
    fun `propagates native trailing semicolon rejection without rewriting SQL`() {
        val sql = "SELECT 1;"
        val preparedSql = mutableListOf<String>()
        val connection = proxy<Connection> { name, args ->
            assertEquals("prepareStatement", name)
            preparedSql += args[0] as String
            throw java.sql.SQLException("mismatched input ';'", "SYNTAX_ERROR")
        }
        val failure = assertFailsWith<java.sql.SQLException> {
            TrinoRawSqlJdbcValidator(connection, { 9 }).validate(sql, emptyList())
        }
        assertEquals("SYNTAX_ERROR", failure.sqlState)
        assertEquals(listOf(sql), preparedSql)
    }

    private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            invoke(method.name, args.orEmpty())
        } as T
}
