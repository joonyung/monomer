package cc.monomer.execution.driver.redshift

import cc.monomer.execution.driver.jdbc.JdbcConnectionFactory
import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutPolicy
import cc.monomer.execution.driver.jdbc.JdbcTimingFixture
import cc.monomer.execution.driver.spi.*
import java.sql.SQLException
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RedshiftOperationTimingTest {
    @Test
    fun `query success and execution failure summarize phases without sensitive values`() = runBlocking<Unit> {
        listOf(false, true).forEach { failed ->
            credential().use { credential ->
                JdbcTimingFixture.summary(ConnectorType.REDSHIFT, DriverOperation.QUERY,
                    if (failed) "SYNTAX_ERROR" else null, if (failed) DriverPhase.EXECUTE else null,
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
        val native = JdbcTimingFixture.proxy<com.amazon.redshift.core.BaseConnection> { method, _ ->
            if (method == "getStandardConformingStrings") true else null
        }
        credential().use { credential ->
            JdbcTimingFixture.summary(ConnectorType.REDSHIFT, DriverOperation.VALIDATE, null, null,
                setOf(DriverPhase.CREDENTIAL, DriverPhase.CONNECT, DriverPhase.SESSION_SETUP, DriverPhase.VALIDATE, DriverPhase.CLEANUP)) { timing ->
                provider(JdbcConnectionFactory { _, _ -> JdbcTimingFixture.connection(native) })
                    .validateRawSql(connector, credential, JdbcTimingFixture.SQL,
                        listOf(QueryParameter.Text(JdbcTimingFixture.BINDING, name = "")), System.currentTimeMillis() + 30_000, timing)
                DriverPhysicalResult.Succeeded(0uL, 0uL, null)
            }
        }
        credential().use { credential ->
            JdbcTimingFixture.summary(ConnectorType.REDSHIFT, DriverOperation.VALIDATE, "AUTHENTICATION_FAILED", DriverPhase.CONNECT,
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

    @Test
    fun `IAM issuance success and failure stay in credential phase and omit temporary secrets`() = runBlocking<Unit> {
        val secrets = mapOf("aws_access_key_id" to "timing-private-access-key",
            "aws_secret_access_key" to "timing-private-aws-secret", "aws_region" to "us-east-1",
            "cluster_id" to "fixture-cluster", "db_user" to "timing-private-db-user")
        val native = JdbcTimingFixture.proxy<com.amazon.redshift.core.BaseConnection> { method, _ ->
            if (method == "getStandardConformingStrings") true else null
        }
        listOf(false, true).forEach { failed ->
            var fetches = 0
            val client = RedshiftIamCredentialsClient { _, _ ->
                fetches++
                if (failed) throw SQLException(JdbcTimingFixture.EXCEPTION + secrets.getValue("aws_secret_access_key"), "28000")
                RedshiftTemporaryCredential("timing-private-temporary-user", "timing-private-temporary-password")
            }
            MaterializedCredential(secrets.mapValues { SecretCharacters.copyOf(it.value.toCharArray()) }).use { credential ->
                JdbcTimingFixture.summary(ConnectorType.REDSHIFT, DriverOperation.VALIDATE,
                    if (failed) "AUTHENTICATION_FAILED" else null, if (failed) DriverPhase.CREDENTIAL else null,
                    setOf(DriverPhase.CREDENTIAL), additionalSecrets = secrets.values.filter { it.startsWith("timing-private-") } +
                        listOf("timing-private-temporary-user", "timing-private-temporary-password")) { timing ->
                    val driver = RedshiftExecutionDriverProvider(30, 2, policy, Dispatchers.IO, Clock.systemUTC(),
                        JdbcConnectionFactory { _, _ -> JdbcTimingFixture.connection(native) }, client)
                    if (failed) {
                        val failure = assertFailsWith<RawSqlValidationException> {
                            driver.validateRawSql(connector, credential, JdbcTimingFixture.SQL,
                                listOf(QueryParameter.Text(JdbcTimingFixture.BINDING, name = "")), System.currentTimeMillis() + 30_000, timing)
                        }
                        DriverPhysicalResult.Failed(failure.failureCode, failure.safeMessage)
                    } else {
                        driver.validateRawSql(connector, credential, JdbcTimingFixture.SQL,
                            listOf(QueryParameter.Text(JdbcTimingFixture.BINDING, name = "")), System.currentTimeMillis() + 30_000, timing)
                        DriverPhysicalResult.Succeeded(0uL, 0uL, null)
                    }
                }
            }
            kotlin.test.assertEquals(1, fetches)
        }
    }

    private val policy = JdbcRunTimeoutPolicy(2_000, 20_000, 1_000)
    private fun provider(factory: JdbcConnectionFactory) = RedshiftExecutionDriverProvider(30, 2, policy, Dispatchers.IO, Clock.systemUTC(), factory, DefaultRedshiftIamCredentialsClient)
    private fun credential() = MaterializedCredential(mapOf(
        "username" to SecretCharacters.copyOf("timing-private-user".toCharArray()),
        "password" to SecretCharacters.copyOf(JdbcTimingFixture.PASSWORD.toCharArray()),
    ))
    private val connector = ConnectorRuntimeBinding(1u, ConnectorId.parse("019f0000-0000-7000-8000-000000000003"),
        ConnectorType.REDSHIFT, mapOf(
            "host" to ConnectorPropertyValue.Text("warehouse.invalid"),
            "port" to ConnectorPropertyValue.Integer(5439),
            "database" to ConnectorPropertyValue.Text("fixture"),
            "ssl_mode" to ConnectorPropertyValue.Text("require"),
        ))
}
