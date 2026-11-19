package cc.monomer.execution.driver.trino

import cc.monomer.execution.driver.jdbc.JdbcConnectionFactory
import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutPolicy
import cc.monomer.execution.driver.jdbc.JdbcTimingFixture
import cc.monomer.execution.driver.spi.*
import java.nio.file.Path
import java.sql.SQLException
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TrinoOperationTimingTest {
    @Test
    fun `query success and execution failure summarize phases without sensitive values`() = runBlocking<Unit> {
        listOf(false, true).forEach { failed ->
            credential().use { credential ->
                JdbcTimingFixture.summary(ConnectorType.TRINO, DriverOperation.QUERY,
                    if (failed) "TRINO_EXECUTION_FAILED" else null, if (failed) DriverPhase.EXECUTE else null,
                    if (failed) setOf(DriverPhase.CREDENTIAL, DriverPhase.CONNECT, DriverPhase.SESSION_SETUP, DriverPhase.EXECUTE)
                    else setOf(DriverPhase.CREDENTIAL, DriverPhase.CONNECT, DriverPhase.SESSION_SETUP, DriverPhase.EXECUTE, DriverPhase.READ, DriverPhase.DELIVERY, DriverPhase.CLEANUP)) { timing ->
                    val failure = if (failed) SQLException(JdbcTimingFixture.EXCEPTION + JdbcTimingFixture.PASSWORD, "42000") else null
                    val provider = provider(JdbcConnectionFactory { _, _ -> JdbcTimingFixture.connection(failure = failure) })
                    JdbcTimingFixture.query(provider, connector, credential, timing, JdbcTimingFixture.inlineTarget, JdbcTimingFixture.inlineSink)
                }
            }
        }
    }

    @Test
    fun `native validation and connection failure summarize phases without sensitive values`() = runBlocking<Unit> {
        val native: Any? = null
        credential().use { credential ->
            JdbcTimingFixture.summary(ConnectorType.TRINO, DriverOperation.VALIDATE, null, null,
                setOf(DriverPhase.CREDENTIAL, DriverPhase.CONNECT, DriverPhase.SESSION_SETUP, DriverPhase.VALIDATE, DriverPhase.CLEANUP)) { timing ->
                provider(JdbcConnectionFactory { _, _ -> JdbcTimingFixture.connection(native) })
                    .validateRawSql(connector, credential, JdbcTimingFixture.SQL,
                        listOf(QueryParameter.Text(JdbcTimingFixture.BINDING, name = "")), System.currentTimeMillis() + 30_000, timing)
                DriverPhysicalResult.Succeeded(0uL, 0uL, null)
            }
        }
        credential().use { credential ->
            JdbcTimingFixture.summary(ConnectorType.TRINO, DriverOperation.VALIDATE, "AUTHENTICATION_FAILED", DriverPhase.CONNECT,
                setOf(DriverPhase.CREDENTIAL, DriverPhase.CONNECT)) { timing ->
                val failure = assertFailsWith<RawSqlValidationException> {
                    provider(JdbcConnectionFactory { _, _ -> throw SQLException(JdbcTimingFixture.PASSWORD + JdbcTimingFixture.EXCEPTION, "28000") })
                        .validateRawSql(connector, credential, JdbcTimingFixture.SQL,
                            listOf(QueryParameter.Text(JdbcTimingFixture.BINDING, name = "")), System.currentTimeMillis() + 30_000, timing)
                }
                DriverPhysicalResult.Failed(failure.failureCode, failure.safeMessage)
            }
        }
    }

    private val policy = JdbcRunTimeoutPolicy(2_000, 20_000, 1_000)
    private fun provider(factory: JdbcConnectionFactory) = TrinoExecutionDriverProvider(30, 2, Path.of("unused.pem"), policy, Dispatchers.IO, Clock.systemUTC(), factory)
    private fun credential() = MaterializedCredential(mapOf(
        "username" to SecretCharacters.copyOf("timing-private-user".toCharArray()),
        "password" to SecretCharacters.copyOf(JdbcTimingFixture.PASSWORD.toCharArray()),
    ))
    private val connector = ConnectorRuntimeBinding(1u, ConnectorId.parse("019f0000-0000-7000-8000-000000000003"),
        ConnectorType.TRINO, mapOf(
            "host" to ConnectorPropertyValue.Text("warehouse.invalid"),
            "port" to ConnectorPropertyValue.Integer(8443),
            "catalog" to ConnectorPropertyValue.Text("fixture"),
        ))
}
