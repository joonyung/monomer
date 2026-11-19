package cc.monomer.execution.driver.postgresql

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

class PostgresqlOperationTimingTest {
    @Test
    fun `query success and execution failure summarize phases without sensitive values`() = runBlocking<Unit> {
        listOf(false, true).forEach { failed ->
            credential().use { credential ->
                JdbcTimingFixture.summary(ConnectorType.POSTGRESQL, DriverOperation.QUERY,
                    if (failed) "SYNTAX_OR_OBJECT_ERROR" else null, if (failed) DriverPhase.EXECUTE else null,
                    if (failed) setOf(DriverPhase.CREDENTIAL, DriverPhase.CONNECT, DriverPhase.SESSION_SETUP, DriverPhase.EXECUTE)
                    else setOf(DriverPhase.CREDENTIAL, DriverPhase.CONNECT, DriverPhase.SESSION_SETUP, DriverPhase.EXECUTE, DriverPhase.READ, DriverPhase.DELIVERY, DriverPhase.CLEANUP)) { timing ->
                    val failure = if (failed) SQLException(JdbcTimingFixture.EXCEPTION + JdbcTimingFixture.PASSWORD, "42601") else null
                    val provider = provider(JdbcConnectionFactory { _, _ -> JdbcTimingFixture.connection(failure = failure) })
                    JdbcTimingFixture.query(provider, connector, credential, timing, JdbcTimingFixture.inlineTarget, JdbcTimingFixture.inlineSink)
                }
            }
        }
    }

    @Test
    fun `native validation and connection failure summarize phases without sensitive values`() = runBlocking<Unit> {
        val parameters = JdbcTimingFixture.proxy<org.postgresql.core.ParameterList> { method, _ ->
            if (method == "getParameterCount") 1 else null
        }
        val query = JdbcTimingFixture.proxy<org.postgresql.core.Query> { method, _ ->
            if (method == "createParameterList") parameters else null
        }
        val executor = JdbcTimingFixture.proxy<org.postgresql.core.QueryExecutor> { method, args ->
            when (method) {
                "wrap" -> query
                "execute" -> {
                    (args[2] as org.postgresql.core.ResultHandler).handleResultRows(query,
                        arrayOf(org.postgresql.core.Field("value", org.postgresql.core.Oid.VARCHAR)), emptyList(), null)
                    null
                }
                else -> null
            }
        }
        val native = JdbcTimingFixture.proxy<org.postgresql.core.BaseConnection> { method, _ ->
            when (method) {
                "getStandardConformingStrings" -> true
                "getQueryExecutor" -> executor
                else -> null
            }
        }
        credential().use { credential ->
            JdbcTimingFixture.summary(ConnectorType.POSTGRESQL, DriverOperation.VALIDATE, null, null,
                setOf(DriverPhase.CREDENTIAL, DriverPhase.CONNECT, DriverPhase.SESSION_SETUP, DriverPhase.VALIDATE, DriverPhase.CLEANUP)) { timing ->
                provider(JdbcConnectionFactory { _, _ -> JdbcTimingFixture.connection(native) })
                    .validateRawSql(connector, credential, JdbcTimingFixture.SQL,
                        listOf(QueryParameter.Text(JdbcTimingFixture.BINDING, name = "")), System.currentTimeMillis() + 30_000, timing)
                DriverPhysicalResult.Succeeded(0uL, 0uL, null)
            }
        }
        credential().use { credential ->
            JdbcTimingFixture.summary(ConnectorType.POSTGRESQL, DriverOperation.VALIDATE, "AUTHENTICATION_FAILED", DriverPhase.CONNECT,
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
    private fun provider(factory: JdbcConnectionFactory) = PostgresqlExecutionDriverProvider(30, 2, Path.of("unused.pem"), policy, Dispatchers.IO, Clock.systemUTC(), factory)
    private fun credential() = MaterializedCredential(mapOf(
        "username" to SecretCharacters.copyOf("timing-private-user".toCharArray()),
        "password" to SecretCharacters.copyOf(JdbcTimingFixture.PASSWORD.toCharArray()),
    ))
    private val connector = ConnectorRuntimeBinding(1u, ConnectorId.parse("019f0000-0000-7000-8000-000000000003"),
        ConnectorType.POSTGRESQL, mapOf(
            "host" to ConnectorPropertyValue.Text("warehouse.invalid"),
            "database" to ConnectorPropertyValue.Text("fixture"),
            "ssl_mode" to ConnectorPropertyValue.Text("disable"),
        ))
}
