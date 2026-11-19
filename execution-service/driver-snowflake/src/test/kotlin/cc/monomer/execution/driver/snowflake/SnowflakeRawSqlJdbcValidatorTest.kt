package cc.monomer.execution.driver.snowflake

import cc.monomer.execution.driver.spi.BinaryValue
import cc.monomer.execution.driver.spi.NullQueryParameterType
import cc.monomer.execution.driver.spi.QueryParameter
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.snowflake.client.core.ExecTimeTelemetryData
import net.snowflake.client.core.ParameterBindingDTO
import net.snowflake.client.core.SFPreparedStatementMetaData
import net.snowflake.client.core.SFStatement
import net.snowflake.client.core.SFStatementType
import net.snowflake.client.jdbc.SFConnectionHandler
import net.snowflake.client.jdbc.SnowflakeConnection
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.JsonNode
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.ObjectMapper

class SnowflakeRawSqlJdbcValidatorTest {
    @Test
    fun `preserves native SQL corpus and bound identifier through describe only`() {
        listOf(
            "SELECT 1;" to emptyList(),
            "SELECT ?::NUMBER" to listOf(QueryParameter.Integer64(1, name = "")),
            "SELECT * FROM IDENTIFIER(?)" to listOf(QueryParameter.Text("ORDERS", name = "")),
            "WITH x AS (SELECT 1 n) SELECT n FROM x" to emptyList(),
            "WITH RECURSIVE x(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM x WHERE n<3) SELECT * FROM x" to emptyList(),
            "SELECT ROW_NUMBER() OVER (ORDER BY 1) n QUALIFY n=1" to emptyList(),
            "/* initial ; comment */ SELECT ';' AS \"semi;colon\"; -- final comment" to emptyList(),
            "SELECT * FROM \"MixedCaseEvents\"" to emptyList(),
            "SELECT column1 FROM VALUES (1), (2)" to emptyList(),
        ).forEach { (sql, parameters) ->
            val jdbc = RecordingSnowflakeDescribe()
            SnowflakeRawSqlJdbcValidator(jdbc.connection()) { 9 }.validate(sql, parameters)
            assertEquals(listOf(sql), jdbc.sql)
            assertEquals(9, jdbc.statement.statementParameters["query_timeout"])
            assertTrue(jdbc.closed)
        }
    }

    @Test
    fun `maps bindings identically to pinned JDBC setters`() {
        val jdbc = RecordingSnowflakeDescribe()
        val parameters = listOf(
            QueryParameter.Text("ORDERS", name = ""), QueryParameter.Integer64(Long.MAX_VALUE, name = ""),
            QueryParameter.Float64(1.25, name = ""), QueryParameter.BooleanValue(true, name = ""),
            QueryParameter.Bytes(BinaryValue.copyOf(byteArrayOf(0, 127, -1)), name = ""),
        ) + NullQueryParameterType.entries.map { QueryParameter.Null(it, name = "") }
        SnowflakeRawSqlJdbcValidator(jdbc.connection()) { 9 }.validate("SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?", parameters)
        assertEquals(listOf("TEXT", "FIXED", "REAL", "BOOLEAN", "BINARY") + listOf("TEXT", "FIXED", "REAL", "BOOLEAN", "BINARY", "FIXED", "DATE", "TIMESTAMP_LTZ"), jdbc.bindings.values.map { it.type })
        assertEquals(listOf("ORDERS", Long.MAX_VALUE.toString(), "1.25", "true", "007fff") + List(8) { null }, jdbc.bindings.values.map { it.value })
    }

    @Test
    fun `rejects native mutation types including bound identifier DDL`() {
        listOf(
            "INSERT INTO t VALUES (1)" to SFStatementType.INSERT,
            "UPDATE t SET n=1" to SFStatementType.UPDATE,
            "DELETE FROM t" to SFStatementType.DELETE,
            "MERGE INTO t USING s ON t.n=s.n WHEN MATCHED THEN DELETE" to SFStatementType.MERGE,
            "CREATE TEMP TABLE IDENTIFIER(?) (id INT)" to SFStatementType.DDL,
            "COMMIT" to SFStatementType.TCL,
            "ALTER SESSION SET MULTI_STATEMENT_COUNT=0" to SFStatementType.ALTER_SESSION,
            "SHOW TABLES" to SFStatementType.SHOW,
            "GET @stage file:///tmp/guard" to SFStatementType.GET,
        ).forEach { (sql, type) ->
            val jdbc = RecordingSnowflakeDescribe().apply { statementTypeId = type.statementTypeId }
            assertFailsWith<IllegalArgumentException> {
                SnowflakeRawSqlJdbcValidator(jdbc.connection()) { 9 }.validate(sql, listOf(QueryParameter.Text("t", name = "")))
            }
            assertTrue(jdbc.closed)
        }
    }

    @Test
    fun `propagates server multistatement refusal without executing or rewriting SQL`() {
        val jdbc = RecordingSnowflakeDescribe().apply { failure = SQLException("multiple statements", "0A000", 8) }
        val failure = assertFailsWith<IllegalArgumentException> {
            SnowflakeRawSqlJdbcValidator(jdbc.connection()) { 9 }.validate("SELECT 1; SELECT 2", emptyList())
        }
        assertEquals(8, (failure.cause as SQLException).errorCode)
        assertEquals(listOf("SELECT 1; SELECT 2"), jdbc.sql)
        assertTrue(jdbc.closed)
    }

    @Test
    fun `native SELECT classification retains accepted indirect execution limits`() {
        listOf("CALL some_procedure()", "EXECUTE IMMEDIATE 'SELECT 1'").forEach { sql ->
            val jdbc = RecordingSnowflakeDescribe()
            SnowflakeRawSqlJdbcValidator(jdbc.connection()) { 9 }.validate(sql, emptyList())
            assertEquals(listOf(sql), jdbc.sql)
        }
    }

    @Test
    fun `preserves native server error details from unsuccessful describe`() {
        val jdbc = RecordingSnowflakeDescribe().apply {
            response = ObjectMapper().readTree(
                """{"success":false,"message":"permission denied","data":{"sqlState":"42501","errorCode":3001}}""",
            )
        }
        val failure = assertFailsWith<SQLException> {
            SnowflakeRawSqlJdbcValidator(jdbc.connection()) { 9 }.validate("SELECT 1", emptyList())
        }
        assertEquals("42501", failure.sqlState)
        assertEquals(3001, failure.errorCode)
        assertTrue(jdbc.closed)
    }

    @Test
    fun `rejects malformed response and mismatched parameter count`() {
        val malformed = RecordingSnowflakeDescribe().apply { response = ObjectMapper().readTree("{\"success\":true,\"data\":{}}") }
        assertFailsWith<IllegalStateException> {
            SnowflakeRawSqlJdbcValidator(malformed.connection()) { 9 }.validate("SELECT 1", emptyList())
        }
        val wrongCount = RecordingSnowflakeDescribe().apply { parameterCount = 1 }
        assertFailsWith<IllegalArgumentException> {
            SnowflakeRawSqlJdbcValidator(wrongCount.connection()) { 9 }.validate("SELECT ?", emptyList())
        }
        assertTrue(malformed.closed)
        assertTrue(wrongCount.closed)
    }
}

internal class RecordingSnowflakeDescribe {
    val sql = mutableListOf<String>()
    var bindings = emptyMap<String, ParameterBindingDTO>()
    var statementTypeId = SFStatementType.SELECT.statementTypeId
    var parameterCount: Int? = null
    var response: JsonNode? = null
    var failure: Throwable? = null
    var beforeDescribe: (() -> Unit)? = null
    var closed = false
    val statement = object : SFStatement(null) {
        override fun executeHelper(
            text: String, mediaType: String, bindValues: Map<String, ParameterBindingDTO>,
            describeOnly: Boolean, internal: Boolean, asyncExec: Boolean, telemetry: ExecTimeTelemetryData,
        ): Any {
            sql += text
            assertEquals("application/snowflake", mediaType)
            assertTrue(describeOnly)
            assertTrue(internal)
            assertFalse(asyncExec)
            bindings = bindValues
            beforeDescribe?.invoke()
            failure?.let { throw it }
            return response ?: ObjectMapper().readTree(
                """{"success":true,"data":{"statementTypeId":$statementTypeId,"numberOfBinds":${parameterCount ?: bindValues.size}}}""",
            )
        }
        override fun describe(sql: String): SFPreparedStatementMetaData = error("unbound describe must not be used")
        override fun close() { closed = true }
    }

    fun nativeConnection(): SnowflakeConnection = proxy { name, _ ->
        check(name == "getHandler")
        proxy<SFConnectionHandler> { method, _ ->
            check(method == "getSFStatement")
            statement
        }
    }

    fun connection(): Connection = proxy { name, args ->
        check(name == "unwrap")
        assertEquals(SnowflakeConnection::class.java, args[0])
        nativeConnection()
    }

    private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            invoke(method.name, args.orEmpty())
        } as T
}
