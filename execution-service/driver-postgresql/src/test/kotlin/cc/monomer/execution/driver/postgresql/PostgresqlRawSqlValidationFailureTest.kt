package cc.monomer.execution.driver.postgresql

import cc.monomer.execution.driver.spi.DriverOperationTiming
import cc.monomer.execution.driver.spi.DriverOperation
import cc.monomer.execution.driver.jdbc.JdbcConnectionFactory
import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutPolicy
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.ConnectorId
import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.SecretCharacters
import cc.monomer.execution.driver.spi.RawSqlValidationException
import java.nio.file.Path
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PostgresqlRawSqlValidationFailureTest {
    @Test
    fun `actual failures use the same connection authentication and read only classification`() {
        val dialect = PostgresqlDialect(Path.of("unused.pem"), 1_024)
        listOf(
            SQLException("connect timeout", "08001", SocketTimeoutException()) to "CONNECTION_FAILED",
            SocketTimeoutException() to "CONNECTION_FAILED",
            SQLException("permission denied", "42501") to "PERMISSION_DENIED",
            SQLException("denied", "28P01") to "AUTHENTICATION_FAILED",
            SQLException("read only", "25006") to "READ_ONLY_VIOLATION",
            SQLException("cancelled", "57014") to "CANCELLED",
            SQLException("driver conversion mismatch", "42821") to "UNSUPPORTED_RESULT_TYPE",
            SQLException("syntax", "42601") to "SYNTAX_OR_OBJECT_ERROR",
        ).forEach { (cause, expectedCode) -> assertEquals(expectedCode, dialect.safeFailureCode(cause)) }
    }

    @Test
    fun `connection authentication and timeout failures retain their classifications`() = runBlocking {
        listOf(
            SQLException("unavailable", "08001") to "CONNECTION_FAILED",
            SQLException("login rejected", "08001", 390100) to "CONNECTION_FAILED",
            SocketTimeoutException() to "CONNECTION_FAILED",
            SQLException("resource", "53300") to "RESOURCE_EXHAUSTED",
            SQLException("internal server failure", "XX000") to "RAW_SQL_VALIDATION_FAILED",
            IllegalStateException("internal failure") to "RAW_SQL_VALIDATION_FAILED",
            SQLException("read-only preparation failed", "25006") to "READ_ONLY_VIOLATION",
            SQLException("syntax", "42601") to "RAW_SQL_VALIDATION_REJECTED",
            IllegalArgumentException("no output") to "RAW_SQL_VALIDATION_REJECTED",
            SQLException("permission denied", "42501") to "PERMISSION_DENIED",
            SQLException("denied", "28P01") to "AUTHENTICATION_FAILED",
            SQLTimeoutException("timeout") to "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED",
            SQLException("socket timeout", "08006", SocketTimeoutException()) to "CONNECTION_FAILED",
            SQLException("statement timeout", "57014") to "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED",
        ).forEach { (cause, code) ->
            credential().use { credential ->
                val failure = assertFailsWith<RawSqlValidationException> {
                    provider(JdbcConnectionFactory { _, _ -> throw cause })
                        .validateRawSql(connector, credential, "SELECT 1", emptyList(), 6_000, timing = DriverOperationTiming(ConnectorType.POSTGRESQL, DriverOperation.VALIDATE, null, null))
                }
                assertEquals(code, failure.failureCode)
            }
        }
    }

    @Test
    fun `expired validation never opens a warehouse connection`() = runBlocking {
        credential().use { credential ->
            val failure = assertFailsWith<RawSqlValidationException> {
                provider(JdbcConnectionFactory { _, _ -> error("must not connect") })
                    .validateRawSql(connector, credential, "SELECT 1", emptyList(), 1_000, timing = DriverOperationTiming(ConnectorType.POSTGRESQL, DriverOperation.VALIDATE, null, null))
            }
            assertEquals("RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", failure.failureCode)
        }
    }

    private fun provider(factory: JdbcConnectionFactory) = PostgresqlExecutionDriverProvider(
        5, 100, Path.of("unused.pem"), JdbcRunTimeoutPolicy(2_000, 5_000, 1_000),
        Dispatchers.IO, Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC), factory,
    )

    private fun credential() = MaterializedCredential(mapOf(
        "username" to SecretCharacters.copyOf("fixture".toCharArray()),
        "password" to SecretCharacters.copyOf("fixture".toCharArray()),
    ))

    private val connector = ConnectorRuntimeBinding(
        1u, ConnectorId.parse("019f0000-0000-7000-8000-000000000003"), ConnectorType.POSTGRESQL,
        mapOf("host" to ConnectorPropertyValue.Text("warehouse.invalid"),
            "database" to ConnectorPropertyValue.Text("fixture")),
    )
}
