package cc.monomer.execution.driver.trino

import cc.monomer.execution.driver.spi.DriverOperationTiming
import cc.monomer.execution.driver.spi.DriverOperation
import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutBudget
import cc.monomer.execution.driver.jdbc.JdbcConnectionFactory
import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.ConnectorId
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.RawSqlValidationException
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.SecretCharacters
import java.nio.file.Path
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin

class TrinoExecutionDriverTest {
    @Test
    fun `execution classifies SQLSTATE class 08 as a connection failure without dropping message matches`() = runBlocking {
        listOf(
            java.sql.SQLException("Unable to connect to Trino server", "08001") to "CONNECTION_FAILED",
            java.sql.SQLException("Invalid authentication to Trino server", "28000") to "AUTHENTICATION_FAILED",
            java.sql.SQLException("Authentication failed: Unauthorized", "08001") to "AUTHENTICATION_FAILED",
            java.sql.SQLException("connection refused") to "CONNECTION_FAILED",
            java.sql.SQLException("unexpected server failure") to "TRINO_EXECUTION_FAILED",
        ).forEach { (cause, expectedCode) ->
            listOf(true, false).forEach { duringConnect ->
                credential().use { materializedCredential ->
                    val factory = if (duringConnect) JdbcConnectionFactory { _, _ -> throw cause }
                        else JdbcConnectionFactory { _, _ -> cc.monomer.execution.driver.jdbc.JdbcTimingFixture.connection(failure = cause) }
                    val outcome = cc.monomer.execution.driver.jdbc.JdbcTimingFixture.query(
                        provider(factory), connector(), materializedCredential,
                        cc.monomer.execution.driver.jdbc.JdbcTimingFixture.timing(ConnectorType.TRINO, DriverOperation.QUERY),
                        cc.monomer.execution.driver.jdbc.JdbcTimingFixture.inlineTarget,
                        cc.monomer.execution.driver.jdbc.JdbcTimingFixture.inlineSink,
                    )
                    assertEquals(
                        expectedCode,
                        kotlin.test.assertIs<cc.monomer.execution.driver.spi.DriverPhysicalResult.Failed>(outcome).failureCode,
                        "$cause duringConnect=$duringConnect",
                    )
                }
            }
        }
    }

    @Test
    fun `validation preserves connection authentication timeout and SQL rejection codes`() = runBlocking {
        listOf(
            java.sql.SQLException("connection unavailable", "08001") to "CONNECTION_FAILED",
            java.sql.SQLException("login rejected", "08001", 390100) to "CONNECTION_FAILED",
            java.sql.SQLException("Authentication failed: Unauthorized", "08001") to "AUTHENTICATION_FAILED",
            java.sql.SQLException("authentication denied", "28P01") to "AUTHENTICATION_FAILED",
            java.sql.SQLException("permission denied", "42501") to "PERMISSION_DENIED",
            java.net.SocketTimeoutException() to "CONNECTION_FAILED",
            java.sql.SQLTimeoutException("statement timeout") to "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED",
            java.sql.SQLException("syntax error", "42601") to "RAW_SQL_VALIDATION_REJECTED",
            IllegalStateException("unexpected driver failure") to "RAW_SQL_VALIDATION_FAILED",
            java.sql.SQLException("unexpected server failure", "XX000") to "RAW_SQL_VALIDATION_FAILED",
        ).forEach { (cause, expectedCode) ->
            credential().use { materializedCredential ->
                val failure = assertFailsWith<RawSqlValidationException> {
                    provider(JdbcConnectionFactory { _, _ -> throw cause }).validateRawSql(
                        connector(), materializedCredential, "SELECT 1", emptyList(), System.currentTimeMillis() + 10_000,
                        timing = DriverOperationTiming(ConnectorType.TRINO, DriverOperation.VALIDATE, null, null),
                    )
                }
                assertEquals(expectedCode, failure.failureCode)
            }
        }
    }

    @Test
    fun `treats absent and blank optional schema as absent`() {
        val dialect = TrinoDialect(warehouseTrustCertificatePath)
        val withoutSchema = connector().copy(properties = connector().properties - "schema")
        val withBlankSchema =
            connector().copy(
                properties = connector().properties + ("schema" to ConnectorPropertyValue.Text("  ")),
            )

        val expected = "jdbc:trino://trino.example.com:8443/lake?SSL=true&SSLVerification=CA"
        assertEquals(expected, dialect.jdbcUrl(withoutSchema))
        assertEquals(expected, dialect.jdbcUrl(withBlankSchema))
    }

    @Test
    fun `rejects URL fields outside the connector contract before building the JDBC URL`() {
        val dialect = TrinoDialect(warehouseTrustCertificatePath)
        listOf(
            "host" to "trino.example.com/x?SSL=false",
            "host" to "trino..example.com",
            "catalog" to "lake?SSLVerification=NONE",
            "catalog" to "lake/other",
            "schema" to "sales&hostnameInCertificate=evil",
            "schema" to "sales;x",
        ).forEach { (field, value) ->
            assertFailsWith<IllegalArgumentException>("$field=$value") {
                dialect.jdbcUrl(connector().copy(properties = connector().properties + (field to ConnectorPropertyValue.Text(value))))
            }
        }
        assertEquals(
            "jdbc:trino://[2001:db8::1]:8443/lake${'$'}raw?schema=sales${'$'}v2&SSL=true&SSLVerification=CA",
            dialect.jdbcUrl(connector().copy(properties = connector().properties +
                ("host" to ConnectorPropertyValue.Text("[2001:db8::1]")) +
                ("catalog" to ConnectorPropertyValue.Text("lake${'$'}raw")) +
                ("schema" to ConnectorPropertyValue.Text("sales${'$'}v2")))),
        )
    }

    @Test
    fun `maps source-shaped properties and credential fields`() {
        val credential = credential()
        val dialect = TrinoDialect(warehouseTrustCertificatePath)
        credential.use {
            val connector = connector()
            assertEquals(
                "jdbc:trino://trino.example.com:8443/lake?schema=sales&SSL=true&SSLVerification=CA",
                dialect.jdbcUrl(connector),
            )
            val properties = dialect.connectionProperties(connector, credential, timeoutBudget)
            assertEquals("reader", properties.getProperty("user"))
            assertEquals("secret", properties.getProperty("password"))
            assertEquals("monomer-execution", properties.getProperty("applicationNamePrefix"))
            assertEquals("7000ms", properties.getProperty("timeout"))
            assertEquals(
                warehouseTrustCertificatePath.toString(),
                properties.getProperty("SSLTrustStorePath"),
            )
        }
    }

    @Test
    fun `applies and refreshes the Trino network timeout from the JDBC budget`() {
        val appliedTimeouts = mutableListOf<Int>()
        val connection =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, arguments ->
                when (method.name) {
                    "setNetworkTimeout" -> {
                        appliedTimeouts += arguments!![1] as Int
                        null
                    }
                    else -> error("unexpected Connection method: ${method.name}")
                }
            } as Connection
        val dialect = TrinoDialect(warehouseTrustCertificatePath)

        dialect.prepareConnection(connection, connector(), timeoutBudget)
        dialect.refreshTimeouts(
            connection,
            JdbcRunTimeoutBudget(
                remainingMillis = 2_500,
                connectTimeoutMillis = 2_500,
                socketTimeoutMillis = 2_000,
                statementTimeoutMillis = 2_500,
                lockTimeoutMillis = 1_000,
            ),
        )

        assertEquals(listOf(7_000, 2_000), appliedTimeouts)
    }

    @Test
    fun `injects warehouse CA only for SSL with CA or FULL verification`() {
        val credential = credential()
        val dialect = TrinoDialect(warehouseTrustCertificatePath)
        credential.use {
            val cases =
                listOf(
                    Triple("false", "ca", null),
                    Triple("true", "none", null),
                    Triple("true", "ca", warehouseTrustCertificatePath.toString()),
                    Triple("true", "full", warehouseTrustCertificatePath.toString()),
                )
            cases.forEach { (ssl, verification, expectedTrustPath) ->
                val properties =
                    dialect.connectionProperties(
                        connector(ssl = ssl, sslVerification = verification),
                        credential,
                        timeoutBudget,
                    )

                assertEquals(expectedTrustPath, properties.getProperty("SSLTrustStorePath"))
            }
        }
    }

    @Test
    fun `registers the Trino connector type`() {
        assertEquals(
            ConnectorType.TRINO,
            TrinoExecutionDriverProvider(
                maximumQuerySeconds = 300,
                arrowBatchSize = 1_024,
                warehouseTrustCertificatePath = warehouseTrustCertificatePath,
                timeoutPolicy =
                    cc.monomer.execution.driver.jdbc.JdbcRunTimeoutPolicy(
                        maximumConnectMillis = 3_000,
                        maximumSocketMillis = 7_000,
                        maximumLockMillis = 1_000,
                    ),
                blockingDispatcher = kotlinx.coroutines.Dispatchers.IO,
                clock = java.time.Clock.systemUTC(),
                connectionFactory = cc.monomer.execution.driver.jdbc.DriverManagerJdbcConnectionFactory,
            ).capability.connectorType,
        )
    }

    @Test
    fun `maps an expired Raw SQL validation deadline without opening a connection`() {
        val fixedClock = java.time.Clock.fixed(java.time.Instant.parse("2026-09-02T00:00:00Z"), java.time.ZoneOffset.UTC)
        val provider =
            TrinoExecutionDriverProvider(
                maximumQuerySeconds = 300,
                arrowBatchSize = 1_024,
                warehouseTrustCertificatePath = warehouseTrustCertificatePath,
                timeoutPolicy =
                    cc.monomer.execution.driver.jdbc.JdbcRunTimeoutPolicy(
                        maximumConnectMillis = 3_000,
                        maximumSocketMillis = 7_000,
                        maximumLockMillis = 1_000,
                ),
                blockingDispatcher = kotlinx.coroutines.Dispatchers.IO,
                clock = fixedClock,
                connectionFactory = cc.monomer.execution.driver.jdbc.DriverManagerJdbcConnectionFactory,
            )

        credential().use { credential ->
            val failure =
                assertFailsWith<RawSqlValidationException> {
                    runBlocking {
                        provider.validateRawSql(
                            connector = connector(),
                            credential = credential,
                            sql = "SELECT 1",
                            parameters = emptyList(),
                            deadlineEpochMillis = fixedClock.millis(),
                            timing = DriverOperationTiming(ConnectorType.TRINO, DriverOperation.VALIDATE, null, null),
                        )
                    }
                }
            assertEquals("RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", failure.failureCode)
        }
    }

    @Test
    fun `maps a parser subset rejection without opening a connection`() {
        val provider =
            TrinoExecutionDriverProvider(
                maximumQuerySeconds = 300,
                arrowBatchSize = 1_024,
                warehouseTrustCertificatePath = warehouseTrustCertificatePath,
                timeoutPolicy =
                    cc.monomer.execution.driver.jdbc.JdbcRunTimeoutPolicy(
                        maximumConnectMillis = 3_000,
                        maximumSocketMillis = 7_000,
                        maximumLockMillis = 1_000,
                    ),
                blockingDispatcher = kotlinx.coroutines.Dispatchers.IO,
                clock = java.time.Clock.systemUTC(),
                connectionFactory = cc.monomer.execution.driver.jdbc.DriverManagerJdbcConnectionFactory,
            )

        credential().use { credential ->
            val failure =
                assertFailsWith<RawSqlValidationException> {
                    runBlocking {
                        provider.validateRawSql(
                            connector = connector(),
                            credential = credential,
                            sql = "DELETE FROM lake.sales.orders",
                            parameters = emptyList(),
                            deadlineEpochMillis = System.currentTimeMillis() + 10_000,
                            timing = DriverOperationTiming(ConnectorType.TRINO, DriverOperation.VALIDATE, null, null),
                        )
                    }
                }
            assertEquals("RAW_SQL_VALIDATION_REJECTED", failure.failureCode)
        }
    }

    @Test
    fun `aborts an in-flight JDBC analysis when the caller cancels`() {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val aborted = AtomicBoolean(false)
        val provider = provider(blockingConnectionFactory(started, released, aborted, null))

        credential().use { credential ->
            runBlocking {
                val analysis =
                    async(kotlinx.coroutines.Dispatchers.Default) {
                        provider.validateRawSql(
                            connector = connector(),
                            credential = credential,
                            sql = "SELECT 1",
                            parameters = emptyList(),
                            deadlineEpochMillis = System.currentTimeMillis() + 10_000,
                            timing = DriverOperationTiming(ConnectorType.TRINO, DriverOperation.VALIDATE, null, null),
                        )
                    }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                analysis.cancelAndJoin()
                assertTrue(aborted.get())
                assertTrue(released.await(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `caller cancellation does not wait for a blocking JDBC abort implementation`() {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val abortMayFinish = CountDownLatch(1)
        val aborted = AtomicBoolean(false)
        val provider = provider(blockingConnectionFactory(started, released, aborted, abortMayFinish))

        credential().use { credential ->
            runBlocking {
                val analysis =
                    async(kotlinx.coroutines.Dispatchers.Default) {
                        provider.validateRawSql(
                            connector = connector(),
                            credential = credential,
                            sql = "SELECT 1",
                            parameters = emptyList(),
                            deadlineEpochMillis = System.currentTimeMillis() + 10_000,
                            timing = DriverOperationTiming(ConnectorType.TRINO, DriverOperation.VALIDATE, null, null),
                        )
                    }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                val startedAt = System.nanoTime()
                analysis.cancelAndJoin()
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 500)
                abortMayFinish.countDown()
                assertTrue(released.await(2, TimeUnit.SECONDS))
                assertTrue(aborted.get())
            }
        }
    }

    @Test
    fun `aborts an in-flight JDBC analysis at the total deadline`() {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val aborted = AtomicBoolean(false)
        val provider = provider(blockingConnectionFactory(started, released, aborted, null))
        val startedAt = System.nanoTime()

        credential().use { credential ->
            val failure =
                assertFailsWith<RawSqlValidationException> {
                    runBlocking {
                        provider.validateRawSql(
                            connector = connector(),
                            credential = credential,
                            sql = "SELECT 1",
                            parameters = emptyList(),
                            deadlineEpochMillis = System.currentTimeMillis() + 1_100,
                            timing = DriverOperationTiming(ConnectorType.TRINO, DriverOperation.VALIDATE, null, null),
                        )
                    }
                }
            assertEquals("RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", failure.failureCode)
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertTrue(aborted.get())
        assertTrue(released.await(2, TimeUnit.SECONDS))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 2_500)
    }

    @Test
    fun `maps authentication failures to a safe typed failure`() {
        val dialect = TrinoDialect(warehouseTrustCertificatePath)

        assertEquals(
            "AUTHENTICATION_FAILED",
            dialect.safeFailureCode(SQLException("Authentication failed: Unauthorized")),
        )
    }

    private fun connector(
        ssl: String = "true",
        sslVerification: String = "ca",
    ): ConnectorRuntimeBinding =
        ConnectorRuntimeBinding(
            schemaVersion = 1u,
            connectorId = ConnectorId.parse("019f0000-0000-7000-8000-000000000003"),
            connectorType = ConnectorType.TRINO,
            properties = mapOf(
                    "host" to ConnectorPropertyValue.Text("trino.example.com"),
                    "port" to ConnectorPropertyValue.Integer(8443),
                    "catalog" to ConnectorPropertyValue.Text("lake"),
                    "schema" to ConnectorPropertyValue.Text("sales"),
                    "ssl" to ConnectorPropertyValue.Text(ssl),
                    "ssl_verification" to ConnectorPropertyValue.Text(sslVerification),
                ),
        )

    private fun credential(): MaterializedCredential =
        MaterializedCredential(
            mapOf(
                "username" to SecretCharacters.copyOf("reader".toCharArray()),
                "password" to SecretCharacters.copyOf("secret".toCharArray()),
            ),
        )

    private fun provider(connectionFactory: JdbcConnectionFactory): TrinoExecutionDriverProvider =
        TrinoExecutionDriverProvider(
            maximumQuerySeconds = 300,
            arrowBatchSize = 1_024,
            warehouseTrustCertificatePath = warehouseTrustCertificatePath,
            timeoutPolicy =
                cc.monomer.execution.driver.jdbc.JdbcRunTimeoutPolicy(
                    maximumConnectMillis = 3_000,
                    maximumSocketMillis = 7_000,
                    maximumLockMillis = 1_000,
                ),
            blockingDispatcher = kotlinx.coroutines.Dispatchers.IO,
            clock = java.time.Clock.systemUTC(),
            connectionFactory = connectionFactory,
        )

    private fun blockingConnectionFactory(
        started: CountDownLatch,
        released: CountDownLatch,
        aborted: AtomicBoolean,
        abortMayFinish: CountDownLatch?,
    ): JdbcConnectionFactory =
        JdbcConnectionFactory { _, _ ->
            val statement =
                Proxy.newProxyInstance(
                    javaClass.classLoader,
                    arrayOf(PreparedStatement::class.java),
                ) { _, method, _ ->
                    when (method.name) {
                        "setQueryTimeout", "close" -> null
                        "getParameterMetaData" -> {
                            started.countDown()
                            released.await(5, TimeUnit.SECONDS)
                            throw SQLException("analysis was aborted")
                        }
                        else -> error("unexpected PreparedStatement method: ${method.name}")
                    }
                } as PreparedStatement
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "setReadOnly", "setNetworkTimeout" -> null
                    "prepareStatement" -> statement
                    "abort" -> {
                        abortMayFinish?.await(5, TimeUnit.SECONDS)
                        aborted.set(true)
                        released.countDown()
                        null
                    }
                    "close" -> {
                        released.countDown()
                        null
                    }
                    "getAutoCommit" -> true
                    else -> error("unexpected Connection method: ${method.name}")
                }
            } as Connection
        }

    private val timeoutBudget =
        JdbcRunTimeoutBudget(
            remainingMillis = 10_000,
            connectTimeoutMillis = 3_000,
            socketTimeoutMillis = 7_000,
            statementTimeoutMillis = 9_000,
            lockTimeoutMillis = 1_000,
        )

    private val warehouseTrustCertificatePath = Path.of("/operator/trust/ca.pem")
}
