package cc.monomer.execution.driver.snowflake

import cc.monomer.execution.driver.spi.DriverOperationTiming
import cc.monomer.execution.driver.spi.DriverOperation
import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutBudget
import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutPolicy
import cc.monomer.execution.driver.jdbc.JdbcConnectionFactory
import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.ConnectorId
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.RawSqlValidationException
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.SecretCharacters
import cc.monomer.execution.driver.spi.Sha256Digest
import java.security.KeyPairGenerator
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Clock
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

class SnowflakeExecutionDriverTest {
    @Test
    fun `observed Snowflake login rejection is an authentication failure during validation`() = runBlocking {
        val rejectedLogin = observedAuthenticationFailure()
        credential("username" to "reader", "password" to "secret").use { materializedCredential ->
            val failure = assertFailsWith<RawSqlValidationException> {
                provider(JdbcConnectionFactory { _, _ -> throw rejectedLogin }).validateRawSql(
                    connector(), materializedCredential, "SELECT 1", emptyList(), System.currentTimeMillis() + 10_000,
                    timing = DriverOperationTiming(ConnectorType.SNOWFLAKE, DriverOperation.VALIDATE, null, null),
                )
            }
            assertEquals("AUTHENTICATION_FAILED", failure.failureCode)
            assertSame(rejectedLogin, failure.cause)
            assertEquals("Snowflake Raw SQL validation failed", failure.safeMessage)
        }
    }

    @Test
    fun `observed Snowflake login rejection reaches settlement as an authentication failure`() = runBlocking {
        val rejectedLogin = observedAuthenticationFailure()
        credential("username" to "reader", "password" to "secret").use { materializedCredential ->
            val sink = object : cc.monomer.execution.driver.spi.RunResultSink {
                override suspend fun emitInlineResult(batch: cc.monomer.execution.driver.spi.InlineResultBatch) = error("login rejected")
                override suspend fun openObjectUpload(objectKey: String, mediaType: String): cc.monomer.execution.driver.spi.ArtifactUploadSink = error("login rejected")
            }
            val session = provider(JdbcConnectionFactory { _, _ -> throw rejectedLogin })
                .openSession(connector(), materializedCredential, sink, timing = DriverOperationTiming(ConnectorType.SNOWFLAKE, DriverOperation.QUERY, null, null))
            try {
                val unsigned = cc.monomer.execution.driver.spi.QueryRequest(
                    1u, digest('0'), "SELECT 1", emptyList(),
                    cc.monomer.execution.driver.spi.QueryResultTarget.InlineStream(2, 65536, 65536),
                    false, emptyList(),
                )
                val request = unsigned.copy(canonicalRequestDigest = cc.monomer.execution.driver.spi.QueryRequestCanonicalizer.canonicalDigest(unsigned))
                session.enter(cc.monomer.execution.driver.spi.StartExecutionContext(
                    cc.monomer.execution.driver.spi.RunIdentity(cc.monomer.execution.driver.spi.RunId.parse("019f0000-0000-7000-8000-000000000001")),
                    cc.monomer.execution.driver.spi.RunAssignment(
                        cc.monomer.execution.driver.spi.WorkspaceId.parse("019f0000-0000-7000-8000-000000000002"),
                        connector(), request, System.currentTimeMillis() + 10_000,
                    ),
                ))
                val outcome = assertIs<cc.monomer.execution.driver.spi.DriverPhysicalResult.Failed>(session.awaitPhysicalResult())
                assertEquals("AUTHENTICATION_FAILED", outcome.failureCode)
                assertEquals(rejectedLogin.message, outcome.safeMessage)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun exactTimestampBindingRejectsPinnedDriverRounding() {
        val statement = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.PreparedStatement::class.java)) { _, _, _ -> null } as java.sql.PreparedStatement
        assertFailsWith<java.sql.SQLException> {
            SnowflakeDialect.bindQueryParameters(statement, listOf(cc.monomer.execution.driver.spi.QueryParameter.Timestamp(java.time.Instant.parse("1969-12-31T23:59:59.123456Z"), name = "")))
        }
        SnowflakeDialect.bindQueryParameters(statement, listOf(cc.monomer.execution.driver.spi.QueryParameter.Timestamp(java.time.Instant.parse("1969-12-31T23:59:59.000001Z"), name = "")))
    }

    @Test
    fun `execution classifies SQLSTATE class 08 as a connection failure without dropping message matches`() = runBlocking {
        listOf(
            java.sql.SQLException("JDBC driver encountered communication error", "08001") to "CONNECTION_FAILED",
            java.sql.SQLException("Authenticator request timed out", "08000") to "CONNECTION_FAILED",
            observedAuthenticationFailure() to "AUTHENTICATION_FAILED",
            java.sql.SQLException("authentication denied", "28P01") to "AUTHENTICATION_FAILED",
            java.sql.SQLException("connection refused") to "CONNECTION_FAILED",
            java.sql.SQLException("statement timeout") to "TIMEOUT",
            java.sql.SQLException("unexpected server failure", "XX000") to "SNOWFLAKE_EXECUTION_FAILED",
        ).forEach { (cause, expectedCode) ->
            listOf(true, false).forEach { duringConnect ->
                credential("username" to "reader", "password" to "secret").use { materializedCredential ->
                    val factory = if (duringConnect) JdbcConnectionFactory { _, _ -> throw cause }
                        else JdbcConnectionFactory { _, _ -> cc.monomer.execution.driver.jdbc.JdbcTimingFixture.connection(failure = cause) }
                    val outcome = cc.monomer.execution.driver.jdbc.JdbcTimingFixture.query(
                        provider(factory), connector(), materializedCredential,
                        cc.monomer.execution.driver.jdbc.JdbcTimingFixture.timing(ConnectorType.SNOWFLAKE, DriverOperation.QUERY),
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
            net.snowflake.client.jdbc.SnowflakeSQLException("", "other login failure", "08001", 390101) to "CONNECTION_FAILED",
            java.sql.SQLException("Incorrect username or password was specified.", "08001") to "CONNECTION_FAILED",
            java.sql.SQLException("authentication denied", "28P01") to "AUTHENTICATION_FAILED",
            java.sql.SQLException("insufficient privileges", "42501") to "PERMISSION_DENIED",
            java.net.SocketTimeoutException() to "CONNECTION_FAILED",
            java.sql.SQLTimeoutException("statement timeout") to "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED",
            java.sql.SQLException("syntax error", "42601") to "RAW_SQL_VALIDATION_REJECTED",
            IllegalStateException("unexpected driver failure") to "RAW_SQL_VALIDATION_FAILED",
            java.sql.SQLException("unexpected server failure", "XX000") to "RAW_SQL_VALIDATION_FAILED",
        ).forEach { (cause, expectedCode) ->
            credential("username" to "reader", "password" to "secret").use { materializedCredential ->
                val failure = assertFailsWith<RawSqlValidationException> {
                    provider(JdbcConnectionFactory { _, _ -> throw cause }).validateRawSql(
                        connector(), materializedCredential, "SELECT 1", emptyList(), System.currentTimeMillis() + 10_000,
                        timing = DriverOperationTiming(ConnectorType.SNOWFLAKE, DriverOperation.VALIDATE, null, null),
                    )
                }
                assertEquals(expectedCode, failure.failureCode)
            }
        }
    }

    @Test
    fun `rejects JDBC property injection through SQL identifiers`() {
        listOf("warehouse", "database", "schema", "role").forEach { field ->
            listOf("WH&insecureMode=true", "WH?host=127.0.0.1", "WH;ssl=off", "WH%26insecureMode=true").forEach { value ->
                val binding = connector().copy(properties = connector().properties + (field to ConnectorPropertyValue.Text(value)))
                assertFailsWith<IllegalArgumentException> { SnowflakeDialect.jdbcUrl(binding) }
            }
        }
    }

    @Test
    fun `accepts official public Snowflake entrypoints`() {
        listOf(
            "org-account",
            "ORG-ACCOUNT",
            "ab12345",
            "ab12345.eu-central-1",
            "ab12345.eu-central-1.aws",
            "ab12345.west-europe.azure",
            "ab12345.us-central1.gcp",
            "org-account-with-hyphens",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        ).forEach { value ->
            val binding = connector().copy(properties = connector().properties + ("account" to ConnectorPropertyValue.Text(value)))
            assertTrue(SnowflakeDialect.jdbcUrl(binding).contains(value))
        }
    }

    @Test
    fun `rejects unofficial and injected public Snowflake entrypoints`() {
        listOf(
            "org-account\n",
            "org-account.snowflakecomputing.com",
            "org-account.snowflakecomputing.com.evil.com",
            "https://org-account",
            "org-account:443",
            "org-account/",
            "user@org-account",
            "org-account?insecureMode=true",
            "org-account%2fevil",
            "org-account.",
            "org_account",
            "örg-account",
            "127.0.0.1",
            "[::1]",
            "privatelink",
            "org-account.privatelink",
            "org-account.PRIVATELINK",
            "ab12345.region.privatelink",
            "ab12345.region.evil",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "ab12345.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "-org-account",
            "org-account-",
        ).forEach { value ->
            val binding = connector().copy(properties = connector().properties + ("account" to ConnectorPropertyValue.Text(value)))
            assertFailsWith<IllegalArgumentException>(value) { SnowflakeDialect.jdbcUrl(binding) }
        }
    }

    @Test
    fun `omits absent optional schema and role`() {
        val connector = connector().copy(properties = connector().properties - "schema" - "role")

        assertEquals(
            "jdbc:snowflake://ab12345.snowflakecomputing.com/?warehouse=WH&db=DB",
            SnowflakeDialect.jdbcUrl(connector),
        )
    }

    @Test
    fun `rejects blank optional schema and role`() {
        listOf("schema", "role").forEach { propertyName ->
            val connector =
                connector().copy(
                    properties = connector().properties + (propertyName to ConnectorPropertyValue.Text("  ")),
                )

            assertFailsWith<IllegalArgumentException> {
                SnowflakeDialect.jdbcUrl(connector)
            }
        }
    }

    @Test
    fun `file transfers stay disabled despite connector overrides`() {
        val binding = connector().copy(properties = connector().properties + mapOf(
            "enablePutGet" to ConnectorPropertyValue.Text("true"),
            "ENABLEPUTGET" to ConnectorPropertyValue.Text("true"),
            "MULTI_STATEMENT_COUNT" to ConnectorPropertyValue.Text("0"),
            "multi_statement_count" to ConnectorPropertyValue.Text("0"),
        ))
        credential("username" to "reader", "password" to "secret").use { credential ->
            val properties = SnowflakeDialect.connectionProperties(binding, credential, timeoutBudget)
            assertEquals("false", properties.getProperty("enablePutGet"))
            assertEquals("1", properties.getProperty("MULTI_STATEMENT_COUNT"))
            assertEquals(listOf("enablePutGet"), properties.stringPropertyNames().filter { it.equals("enablePutGet", true) })
            assertFalse(SnowflakeDialect.jdbcUrl(binding).contains("enablePutGet", ignoreCase = true))
            val session = net.snowflake.client.core.SFSession()
            session.addSFSessionProperty("enablePutGet", properties.getProperty("enablePutGet"))
            val statement = net.snowflake.client.core.SFStatement(session)
            listOf("PUT file:///nonexistent/monomer-infrastructure-guard @~/guard", "GET @~/guard file:///nonexistent/monomer-infrastructure-guard").forEach { sql ->
                val failure = assertFailsWith<java.sql.SQLException> { statement.describe(sql) }
                assertEquals("File transfers have been disabled.", failure.message)
                val executionFailure = assertFailsWith<java.sql.SQLException> {
                    statement.execute(sql, emptyMap(), null, net.snowflake.client.core.ExecTimeTelemetryData())
                }
                assertEquals("File transfers have been disabled.", executionFailure.message)
            }
        }
    }

    @Test
    fun `maps source-shaped password connection properties`() {
        val credential = credential("username" to "reader", "password" to "secret")
        credential.use {
            val properties = SnowflakeDialect.connectionProperties(connector(), credential, timeoutBudget)
            assertEquals("reader", properties.getProperty("user"))
            assertEquals("secret", properties.getProperty("password"))
            assertEquals("monomer-execution", properties.getProperty("application"))
            assertEquals("false", properties.getProperty("enablePutGet"))
            assertEquals("1", properties.getProperty("MULTI_STATEMENT_COUNT"))
            assertEquals("3", properties.getProperty("loginTimeout"))
            assertEquals("7000", properties.getProperty("networkTimeout"))
            assertEquals("9", properties.getProperty("queryTimeout"))
            assertEquals("3000", properties.getProperty("HTTP_CLIENT_CONNECTION_TIMEOUT"))
            assertEquals("7000", properties.getProperty("HTTP_CLIENT_SOCKET_TIMEOUT"))
        }
    }

    @Test
    fun `maps unencrypted key-pair credential material`() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        val pem =
            "-----BEGIN PRIVATE KEY-----\n" + // gitleaks:allow
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded) +
                "\n-----END PRIVATE KEY-----\n"
        val credential = credential("username" to "reader", "private_key" to pem)
        credential.use {
            val properties = SnowflakeDialect.connectionProperties(connector(), credential, timeoutBudget)
            assertNotNull(properties["privateKey"])
            assertEquals("false", properties.getProperty("enablePutGet"))
            assertEquals("1", properties.getProperty("MULTI_STATEMENT_COUNT"))
            assertFalse(properties.containsKey("password"))
        }
    }

    @Test
    fun `requires a credential for Raw SQL validation`() = runBlocking<Unit> {
        val provider =
            SnowflakeExecutionDriverProvider(
                maximumQuerySeconds = 300,
                arrowBatchSize = 1_024,
                timeoutPolicy = timeoutPolicy,
                blockingDispatcher = Dispatchers.IO,
                clock = Clock.systemUTC(),
                connectionFactory = cc.monomer.execution.driver.jdbc.DriverManagerJdbcConnectionFactory,
            )

        assertFailsWith<IllegalArgumentException> {
            provider.validateRawSql(
                connector = connector(),
                credential = null,
                sql = "SELECT 1",
                parameters = emptyList(),
                deadlineEpochMillis = System.currentTimeMillis() + 10_000,
                timing = DriverOperationTiming(ConnectorType.SNOWFLAKE, DriverOperation.VALIDATE, null, null),
            )
        }
    }

    @Test
    fun `analyzes Raw SQL through the injected Snowflake connection factory`() = runBlocking {
        val jdbc = FakeSnowflakeJdbc()
        val provider = provider(jdbc.connectionFactory())
        val credential = credential("username" to "reader", "password" to "secret")

        credential.use {
            assertEquals(
                Unit,
                provider.validateRawSql(
                    connector = connector(),
                    credential = credential,
                    sql = "SELECT * FROM orders WHERE status = ?",
                    parameters = listOf(cc.monomer.execution.driver.spi.QueryParameter.Text("paid", name = "")),
                    deadlineEpochMillis = System.currentTimeMillis() + 10_000,
                    timing = DriverOperationTiming(ConnectorType.SNOWFLAKE, DriverOperation.VALIDATE, null, null),
                ),
            )
        }
        assertEquals(
            "SELECT * FROM orders WHERE status = ?",
            jdbc.describe.sql.single(),
        )
    }

    @Test
    fun `validation never executes original SQL and run submits it once`() = runBlocking {
        val describe = RecordingSnowflakeDescribe()
        val submittedSql = mutableListOf<String>()
        var executions = 0
        val sql = "SELECT ?::NUMBER;"
        val factory = JdbcConnectionFactory { _, properties ->
            assertEquals("false", properties.getProperty("enablePutGet"))
            assertEquals("1", properties.getProperty("MULTI_STATEMENT_COUNT"))
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                when (method.name) {
                    "unwrap" -> describe.nativeConnection()
                    "getAutoCommit" -> true
                    "setReadOnly", "setNetworkTimeout", "close" -> null
                    "prepareStatement" -> {
                        submittedSql += args!![0] as String
                        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.PreparedStatement::class.java)) { _, call, _ ->
                            when (call.name) {
                                "setQueryTimeout", "setLong", "close", "cancel" -> null
                                "execute" -> { executions++; throw java.sql.SQLException("execution sentinel", "XX000") }
                                else -> error(call.name)
                            }
                        }
                    }
                    else -> error(method.name)
                }
            } as Connection
        }
        val provider = provider(factory)
        val credential = credential("username" to "reader", "password" to "secret")
        val parameters = listOf(cc.monomer.execution.driver.spi.QueryParameter.Integer64(1, name = ""))
        val deadline = System.currentTimeMillis() + 10_000
        provider.validateRawSql(connector(), credential, sql, parameters, deadline, timing = DriverOperationTiming(ConnectorType.SNOWFLAKE, DriverOperation.VALIDATE, null, null))
        assertEquals(0, executions)
        assertTrue(submittedSql.isEmpty())
        val sink = object : cc.monomer.execution.driver.spi.RunResultSink {
            override suspend fun emitInlineResult(batch: cc.monomer.execution.driver.spi.InlineResultBatch) = error("sentinel returns no result")
            override suspend fun openObjectUpload(objectKey: String, mediaType: String): cc.monomer.execution.driver.spi.ArtifactUploadSink = error("not used")
        }
        val session = provider.openSession(connector(), credential, sink, timing = DriverOperationTiming(ConnectorType.SNOWFLAKE, DriverOperation.QUERY, null, null))
        try {
            val unsigned = cc.monomer.execution.driver.spi.QueryRequest(
                1u, digest('0'), sql, parameters,
                cc.monomer.execution.driver.spi.QueryResultTarget.InlineStream(2, 65536, 65536),
                false, emptyList(),
            )
            val request = unsigned.copy(canonicalRequestDigest = cc.monomer.execution.driver.spi.QueryRequestCanonicalizer.canonicalDigest(unsigned))
            session.enter(cc.monomer.execution.driver.spi.StartExecutionContext(
                cc.monomer.execution.driver.spi.RunIdentity(cc.monomer.execution.driver.spi.RunId.parse("019f0000-0000-7000-8000-000000000001")),
                cc.monomer.execution.driver.spi.RunAssignment(
                    cc.monomer.execution.driver.spi.WorkspaceId.parse("019f0000-0000-7000-8000-000000000002"),
                    connector(), request, deadline,
                ),
            ))
            kotlin.test.assertIs<cc.monomer.execution.driver.spi.DriverPhysicalResult.Failed>(session.awaitPhysicalResult())
            assertEquals(1, executions)
            assertEquals(listOf(sql), submittedSql)
        } finally {
            session.close()
        }
    }

    @Test
    fun `rejects oversized UTF8 SQL before opening a connection`() = runBlocking {
        val provider = provider(JdbcConnectionFactory { _, _ -> error("connection must not open") })
        credential("username" to "reader", "password" to "secret").use { credential ->
            val failure = assertFailsWith<RawSqlValidationException> {
                provider.validateRawSql(connector(), credential, "SELECT '" + "한".repeat(400_000) + "'", emptyList(), System.currentTimeMillis() + 10_000, timing = DriverOperationTiming(ConnectorType.SNOWFLAKE, DriverOperation.VALIDATE, null, null))
            }
            assertEquals("RAW_SQL_VALIDATION_REJECTED", failure.failureCode)
        }
    }

    @Test
    fun `maps an expired Raw SQL validation deadline without opening a connection`() = runBlocking {
        val fixedClock = Clock.fixed(java.time.Instant.parse("2026-09-02T00:00:00Z"), java.time.ZoneOffset.UTC)
        var opened = false
        val provider =
            provider(
                JdbcConnectionFactory { _, _ ->
                    opened = true
                    error("connection must not open")
                },
                clock = fixedClock,
            )
        val credential = credential("username" to "reader", "password" to "secret")

        credential.use {
            val failure =
                assertFailsWith<RawSqlValidationException> {
                    provider.validateRawSql(
                        connector = connector(),
                        credential = credential,
                        sql = "SELECT 1",
                        parameters = emptyList(),
                        deadlineEpochMillis = fixedClock.millis(),
                        timing = DriverOperationTiming(ConnectorType.SNOWFLAKE, DriverOperation.VALIDATE, null, null),
                    )
                }
            assertEquals("RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", failure.failureCode)
        }
        assertFalse(opened)
    }

    @Test
    fun `aborts and closes an in-flight Raw SQL validation when the caller cancels`() {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val aborted = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        val provider = provider(blockingConnectionFactory(started, released, aborted, closed))
        val credential = credential("username" to "reader", "password" to "secret")

        credential.use {
            runBlocking {
                val analysis =
                    async(Dispatchers.Default) {
                        provider.validateRawSql(
                            connector = connector(),
                            credential = credential,
                            sql = "SELECT 1",
                            parameters = emptyList(),
                            deadlineEpochMillis = System.currentTimeMillis() + 10_000,
                            timing = DriverOperationTiming(ConnectorType.SNOWFLAKE, DriverOperation.VALIDATE, null, null),
                        )
                    }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                analysis.cancelAndJoin()
                assertTrue(aborted.get())
                assertTrue(released.await(2, TimeUnit.SECONDS))
                assertTrue(closed.get())
            }
        }
    }

    @Test
    fun `registers the connector type`() {
        val provider =
            SnowflakeExecutionDriverProvider(
                maximumQuerySeconds = 300,
                arrowBatchSize = 1_024,
                timeoutPolicy = timeoutPolicy,
                blockingDispatcher = Dispatchers.IO,
                clock = Clock.systemUTC(),
                connectionFactory = cc.monomer.execution.driver.jdbc.DriverManagerJdbcConnectionFactory,
            )

        assertEquals(ConnectorType.SNOWFLAKE, provider.capability.connectorType)
    }

    private fun connector(): ConnectorRuntimeBinding =
        ConnectorRuntimeBinding(
            schemaVersion = 1u,
            connectorId = ConnectorId.parse("019f0000-0000-7000-8000-000000000003"),
            connectorType = ConnectorType.SNOWFLAKE,
            properties = mapOf(
                    "account" to ConnectorPropertyValue.Text("ab12345"),
                    "warehouse" to ConnectorPropertyValue.Text("WH"),
                    "database" to ConnectorPropertyValue.Text("DB"),
                    "schema" to ConnectorPropertyValue.Text("PUBLIC"),
                    "role" to ConnectorPropertyValue.Text("ANALYST"),
                ),
        )

    private fun credential(vararg values: Pair<String, String>): MaterializedCredential =
        MaterializedCredential(
            values.associate { (name, value) -> name to SecretCharacters.copyOf(value.toCharArray()) },
        )

    private fun digest(character: Char): Sha256Digest = Sha256Digest(character.toString().repeat(64))

    // Captured with JDBC 3.20.0 and a nonexistent synthetic user on 2026-10-07; cause and nextException were null.
    private fun observedAuthenticationFailure() = net.snowflake.client.jdbc.SnowflakeSQLException(
        "",
        "Incorrect username or password was specified. [03c083b8-19f9-449e-9021-772d7dc25de9]",
        "08001",
        390100,
    )

    private fun provider(
        connectionFactory: JdbcConnectionFactory,
        clock: Clock = Clock.systemUTC(),
    ): SnowflakeExecutionDriverProvider =
        SnowflakeExecutionDriverProvider(
            maximumQuerySeconds = 300,
            arrowBatchSize = 1_024,
            timeoutPolicy = timeoutPolicy,
            blockingDispatcher = Dispatchers.IO,
            clock = clock,
            connectionFactory = connectionFactory,
        )

    private fun blockingConnectionFactory(
        started: CountDownLatch,
        released: CountDownLatch,
        aborted: AtomicBoolean,
        closed: AtomicBoolean,
    ): JdbcConnectionFactory =
        JdbcConnectionFactory { _, _ ->
            val describe = RecordingSnowflakeDescribe().apply {
                beforeDescribe = {
                    started.countDown()
                    released.await(5, TimeUnit.SECONDS)
                    throw java.sql.SQLException("analysis was aborted")
                }
            }
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "setReadOnly", "setNetworkTimeout" -> null
                    "unwrap" -> describe.nativeConnection()
                    "getAutoCommit" -> true
                    "abort" -> {
                        aborted.set(true)
                        released.countDown()
                        null
                    }
                    "close" -> {
                        closed.set(true)
                        null
                    }
                    else -> error("unexpected Connection method: ${method.name}")
                }
            } as Connection
        }

    private class FakeSnowflakeJdbc {
        val describe = RecordingSnowflakeDescribe()

        fun connectionFactory(): JdbcConnectionFactory =
            JdbcConnectionFactory { _, _ ->
                Proxy.newProxyInstance(
                    Connection::class.java.classLoader,
                    arrayOf(Connection::class.java),
                ) { _, method, _ ->
                    when (method.name) {
                        "unwrap" -> describe.nativeConnection()
                        "setReadOnly", "setNetworkTimeout", "close" -> null
                        "getAutoCommit" -> true
                        else -> error("unexpected Connection method: ${method.name}")
                    }
                } as Connection
            }
    }

    private val timeoutPolicy =
        JdbcRunTimeoutPolicy(
            maximumConnectMillis = 3_000,
            maximumSocketMillis = 7_000,
            maximumLockMillis = 1_000,
        )

    private val timeoutBudget =
        JdbcRunTimeoutBudget(
            remainingMillis = 10_000,
            connectTimeoutMillis = 3_000,
            socketTimeoutMillis = 7_000,
            statementTimeoutMillis = 9_000,
            lockTimeoutMillis = 1_000,
        )
}
