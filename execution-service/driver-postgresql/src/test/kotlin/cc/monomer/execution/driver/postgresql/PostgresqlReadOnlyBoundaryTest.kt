package cc.monomer.execution.driver.postgresql

import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutBudget
import cc.monomer.execution.driver.spi.ConnectorId
import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.postgresql.core.BaseConnection
import org.postgresql.core.Parser
import org.postgresql.core.NativeQuery
import org.postgresql.core.QueryExecutor

class PostgresqlReadOnlyBoundaryTest {
    @Test
    fun `session and transaction barriers precede every query`() {
        val events = mutableListOf<String>()
        val rows = proxy<ResultSet> { method, _ -> if (method == "next") true else null }
        val statement = proxy<Statement> { method, arguments ->
            if (method == "execute") { events += arguments[0] as String; false } else null
        }
        val connection = proxy<Connection> { method, arguments ->
            when (method) {
                "createStatement" -> statement
                "setAutoCommit" -> { events += "autoCommit=${arguments[0]}"; null }
                "prepareStatement" -> {
                    val sql = arguments[0] as String
                    events += sql
                    proxy<PreparedStatement> { operation, _ -> if (operation == "executeQuery") rows else null }
                }
                else -> null
            }
        }
        PostgresqlDialect(Path.of("unused.pem"), 1_024).prepareConnection(connection, connector,
            JdbcRunTimeoutBudget(5_000, 2_000, 5_000, 2_000, 1_000))
        connection.prepareStatement("SELECT user_query").close()
        assertEquals(listOf("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY", "autoCommit=false",
            "SET TRANSACTION READ ONLY"), events.take(3))
        assertTrue(events[3].startsWith("SELECT set_config('statement_timeout'"))
        assertTrue(events[4].startsWith("SELECT set_config('search_path'"))
        assertEquals("SELECT user_query", events[5])
    }

    @Test
    fun `search path accepts every schema identifier the connector contract allows`() {
        val searchPaths = mutableListOf<String>()
        val rows = proxy<ResultSet> { method, _ -> if (method == "next") true else null }
        val statement = proxy<Statement> { method, _ -> if (method == "execute") false else null }
        val connection = proxy<Connection> { method, arguments ->
            when (method) {
                "createStatement" -> statement
                "prepareStatement" -> {
                    val sql = arguments[0] as String
                    proxy<PreparedStatement> { operation, values ->
                        when (operation) {
                            "setString" -> { if (sql.contains("search_path")) searchPaths += values[1] as String; null }
                            "executeQuery" -> rows
                            else -> null
                        }
                    }
                }
                else -> null
            }
        }
        val dialect = PostgresqlDialect(Path.of("unused.pem"), 1_024)
        val budget = JdbcRunTimeoutBudget(5_000, 2_000, 5_000, 2_000, 1_000)
        dialect.prepareConnection(connection, connectorWithSchema("sales${'$'}v2"), budget)
        assertEquals(listOf("pg_catalog,\"sales${'$'}v2\""), searchPaths)
        listOf("2sales", "sales\"x", "sales-v2", "a".repeat(129)).forEach { schema ->
            assertFailsWith<IllegalArgumentException>(schema) {
                dialect.prepareConnection(connection, connectorWithSchema(schema), budget)
            }
        }
    }

    @Test
    fun `execution parser slices must contain exactly one statement before Describe`() {
        val wrappedSql = mutableListOf<String>()
        val executor = proxy<QueryExecutor> { method, arguments ->
            if (method == "wrap") {
                @Suppress("UNCHECKED_CAST")
                val queries = arguments[0] as List<NativeQuery>
                wrappedSql += queries.map { it.nativeSql }
                throw DescribeReached()
            }
            null
        }
        val postgres = proxy<BaseConnection> { method, _ -> when (method) {
            "getStandardConformingStrings" -> true
            "getQueryExecutor" -> executor
            else -> null
        } }
        val connection = proxy<Connection> { method, _ -> if (method == "unwrap") postgres else null }
        val validator = PostgresqlRawSqlValidator(connection)
        listOf("", "SELECT 1; SELECT 2", "SELECT 1; COMMIT; DELETE FROM fixture",
            "SELECT 1; -- comment\nCOMMIT", "SELECT 1; SET application_name = 'unexpected'",
            "SELECT 1; /* comment */ SET application_name = 'unexpected'", "SELECT 1; /* unclosed",
            "SELECT 1; /* outer /* inner */", "SELECT 1; /", "SELECT 1; -", "/* comment */").forEach { sql ->
            assertFailsWith<IllegalArgumentException>(sql) { validator.validate(sql, emptyList()) }
        }
        assertTrue(wrappedSql.isEmpty())
        assertFailsWith<DescribeReached> { validator.validate("SELECT ';'::text, ?::int;", emptyList()) }
        assertEquals(listOf("SELECT ';'::text, $1::int"), wrappedSql)
        listOf("SELECT 1;", "SELECT 1; -- comment", "SELECT 1; /* comment */",
            "SELECT 1; /* nested /* comment */ end */ -- final", "/* leading */ SELECT 1",
            "SELECT 1 -- a;b", "SELECT ';';").forEach { sql ->
            wrappedSql.clear()
            assertFailsWith<DescribeReached>(sql) { validator.validate(sql, emptyList()) }
            assertEquals(1, wrappedSql.size, sql)
        }
    }

    @Test
    fun `pinned native parser retains trailing comment fragments`() {
        listOf("SELECT 1; -- comment", "SELECT 1; /* comment */").forEach { sql ->
            assertEquals(2, Parser.parseJdbcSql(sql, true, true, true, false, true).size, sql)
        }
    }

    private class DescribeReached : RuntimeException()

    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, arguments ->
            call(method.name, arguments.orEmpty())
        } as T

    private val connector = ConnectorRuntimeBinding(1u,
        ConnectorId.parse("019f0000-0000-7000-8000-000000000003"), ConnectorType.POSTGRESQL,
        emptyMap())

    private fun connectorWithSchema(schema: String) = ConnectorRuntimeBinding(1u,
        ConnectorId.parse("019f0000-0000-7000-8000-000000000003"), ConnectorType.POSTGRESQL,
        mapOf("schema" to ConnectorPropertyValue.Text(schema)))
}
