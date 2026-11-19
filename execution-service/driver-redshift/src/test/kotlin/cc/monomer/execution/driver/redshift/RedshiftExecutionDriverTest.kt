package cc.monomer.execution.driver.redshift

import cc.monomer.execution.driver.spi.DriverOperationTiming
import cc.monomer.execution.driver.spi.DriverOperation
import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutBudget
import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutPolicy
import cc.monomer.execution.driver.jdbc.DriverManagerJdbcConnectionFactory
import cc.monomer.execution.driver.jdbc.JdbcConnectionFactory
import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.ConnectorId
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.RawSqlValidationException
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.SecretCharacters
import cc.monomer.execution.driver.spi.Sha256Digest
import com.amazon.redshift.RedshiftConnection
import com.amazon.redshift.RedshiftProperty
import com.amazon.redshift.core.BaseConnection
import cc.monomer.execution.driver.spi.ArtifactUploadSink
import cc.monomer.execution.driver.spi.DriverPhysicalResult
import cc.monomer.execution.driver.spi.InlineResultBatch
import cc.monomer.execution.driver.spi.QueryParameter
import cc.monomer.execution.driver.spi.QueryRequest
import cc.monomer.execution.driver.spi.QueryRequestCanonicalizer
import cc.monomer.execution.driver.spi.QueryResultTarget
import cc.monomer.execution.driver.spi.RunAssignment
import cc.monomer.execution.driver.spi.RunId
import cc.monomer.execution.driver.spi.RunIdentity
import cc.monomer.execution.driver.spi.RunResultSink
import cc.monomer.execution.driver.spi.StartExecutionContext
import cc.monomer.execution.driver.spi.WorkspaceId
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import kotlin.test.assertIs
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

class RedshiftExecutionDriverTest {
    @Test
    fun `provider validates without execution then executes original SQL once in read only transaction`() = runBlocking {
        val sql = "/* unchanged */ SELECT ? AS value; -- trailing comment"
        val parameters = listOf(QueryParameter.Text("data;not SQL", name = ""))
        val prepared = mutableListOf<String>()
        val bindings = mutableListOf<String>()
        val readOnly = mutableListOf<Boolean>()
        val autoCommit = mutableListOf<Boolean>()
        var executions = 0
        var descriptions = 0
        val metadata = jdbcProxy<ResultSetMetaData> { name, _ ->
            when (name) {
                "getColumnCount" -> 1
                "getColumnLabel", "getColumnName" -> "value"
                "getColumnType" -> java.sql.Types.VARCHAR
                "getColumnTypeName" -> "VARCHAR"
                "isNullable" -> ResultSetMetaData.columnNoNulls
                "getPrecision", "getScale" -> 0
                else -> throw java.sql.SQLException(name)
            }
        }
        var nextRow = true
        val result = jdbcProxy<ResultSet> { name, _ ->
            when (name) {
                "next" -> nextRow.also { nextRow = false }
                "getString" -> "data;not SQL"
                "wasNull" -> false
                "getMetaData" -> metadata
                "close" -> null
                else -> throw java.sql.SQLException(name)
            }
        }
        val native = jdbcProxy<BaseConnection> { name, _ ->
            if (name == "getStandardConformingStrings") true else error(name)
        }
        val driver = provider(JdbcConnectionFactory { _, _ ->
            val statement = jdbcProxy<PreparedStatement> { name, args ->
                when (name) {
                    "getMetaData" -> { descriptions++; metadata }
                    "execute" -> { executions++; true }
                    "getResultSet" -> result
                    "setString" -> { assertEquals(1, args[0]); bindings += args[1] as String; null }
                    "setQueryTimeout", "close", "cancel" -> null
                    else -> throw java.sql.SQLException(name)
                }
            }
            jdbcProxy<Connection> { name, args ->
                when (name) {
                    "unwrap" -> native
                    "prepareStatement" -> { prepared += args[0] as String; statement }
                    "setReadOnly" -> { readOnly += args[0] as Boolean; null }
                    "setAutoCommit" -> { autoCommit += args[0] as Boolean; null }
                    "getAutoCommit" -> false
                    "setNetworkTimeout", "rollback", "close" -> null
                    else -> throw java.sql.SQLException(name)
                }
            }
        })
        credential(mapOf("username" to "reader", "password" to "secret")).use { secrets ->
            driver.validateRawSql(connector("require"), secrets, sql, parameters, System.currentTimeMillis() + 10_000, timing = DriverOperationTiming(ConnectorType.REDSHIFT, DriverOperation.VALIDATE, null, null))
            assertEquals(0, executions)
            assertEquals(1, descriptions)
            val sink = object : RunResultSink {
                override suspend fun emitInlineResult(batch: InlineResultBatch) = Unit
                override suspend fun openObjectUpload(objectKey: String, mediaType: String): ArtifactUploadSink = error("unused")
            }
            val unsigned = QueryRequest(1u, digest('0'), sql, parameters, QueryResultTarget.InlineStream(2, 64 * 1024, 64 * 1024), false, emptyList())
            val request = unsigned.copy(canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(unsigned))
            val session = driver.openSession(connector("require"), secrets, sink, timing = DriverOperationTiming(ConnectorType.REDSHIFT, DriverOperation.QUERY, null, null))
            try {
                session.enter(StartExecutionContext(
                    RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")),
                    RunAssignment(
                        WorkspaceId.parse("019f0000-0000-7000-8000-000000000002"),
                        connector("require"), request, System.currentTimeMillis() + 10_000,
                    ),
                ))
                val outcome = session.awaitPhysicalResult()
                assertEquals(1uL, assertIs<DriverPhysicalResult.Succeeded>(outcome, outcome.toString()).rowsProcessed)
            } finally {
                session.close()
            }
        }
        assertEquals(1, executions)
        assertEquals(1, descriptions)
        assertEquals(listOf(sql, sql), prepared)
        assertEquals(listOf("data;not SQL", "data;not SQL"), bindings)
        assertEquals(listOf(true, true), readOnly)
        assertEquals(listOf(false, false), autoCommit)
    }

    private inline fun <reified T> jdbcProxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            invoke(method.name, args.orEmpty())
        } as T

    @Test
    fun `execution classifies SQLSTATE class 28 as an authentication failure like validation`() = runBlocking {
        listOf(
            java.sql.SQLException("password authentication failed", "28P01") to "AUTHENTICATION_FAILED",
            java.sql.SQLException("invalid authorization specification", "28000") to "AUTHENTICATION_FAILED",
            java.sql.SQLException("connection unavailable", "08001") to "CONNECTION_FAILED",
            java.sql.SQLException("unexpected server failure", "XX000") to "REDSHIFT_EXECUTION_FAILED",
        ).forEach { (cause, expectedCode) ->
            listOf(true, false).forEach { duringConnect ->
                credential(mapOf("username" to "reader", "password" to "secret")).use { materializedCredential ->
                    val factory = if (duringConnect) JdbcConnectionFactory { _, _ -> throw cause }
                        else JdbcConnectionFactory { _, _ -> cc.monomer.execution.driver.jdbc.JdbcTimingFixture.connection(failure = cause) }
                    val outcome = cc.monomer.execution.driver.jdbc.JdbcTimingFixture.query(
                        provider(factory), connector("require"), materializedCredential,
                        cc.monomer.execution.driver.jdbc.JdbcTimingFixture.timing(ConnectorType.REDSHIFT, DriverOperation.QUERY),
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
            java.sql.SQLException("authentication denied", "28P01") to "AUTHENTICATION_FAILED",
            java.sql.SQLException("permission denied", "42501") to "PERMISSION_DENIED",
            java.net.SocketTimeoutException() to "CONNECTION_FAILED",
            java.sql.SQLTimeoutException("statement timeout") to "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED",
            java.sql.SQLException("syntax error", "42601") to "RAW_SQL_VALIDATION_REJECTED",
            IllegalStateException("unexpected driver failure") to "RAW_SQL_VALIDATION_FAILED",
            java.sql.SQLException("unexpected server failure", "XX000") to "RAW_SQL_VALIDATION_FAILED",
        ).forEach { (cause, expectedCode) ->
            credential(mapOf("username" to "reader", "password" to "secret")).use { materializedCredential ->
                val failure = assertFailsWith<RawSqlValidationException> {
                    provider(JdbcConnectionFactory { _, _ -> throw cause }).validateRawSql(
                        connector("require"), materializedCredential, "SELECT 1", emptyList(), System.currentTimeMillis() + 10_000,
                        timing = DriverOperationTiming(ConnectorType.REDSHIFT, DriverOperation.VALIDATE, null, null),
                    )
                }
                assertEquals(expectedCode, failure.failureCode)
            }
        }
    }

    @Test
    fun `artifact upload uses the generic JDBC artifact path`() = runBlocking {
        val appended = mutableListOf<Int>()
        var completed: Pair<ULong, Int>? = null
        val sink = object : cc.monomer.execution.driver.spi.RunResultSink {
            override suspend fun emitInlineResult(batch: cc.monomer.execution.driver.spi.InlineResultBatch) =
                error("artifact query must not emit inline batches")
            override suspend fun openObjectUpload(objectKey: String, mediaType: String): ArtifactUploadSink {
                assertEquals("artifacts/workspaces/w/r/result.arrow", objectKey)
                return object : ArtifactUploadSink {
                    override suspend fun append(payload: cc.monomer.execution.driver.spi.BinaryValue) { appended += payload.size }
                    override suspend fun complete(rowCount: ULong, columnCount: Int): cc.monomer.execution.driver.spi.ArrowResultManifest {
                        completed = rowCount to columnCount
                        return cc.monomer.execution.driver.spi.ArrowResultManifest(
                            objectKey, appended.sum().toULong(), "etag", rowCount, columnCount, mediaType,
                        )
                    }
                    override suspend fun abort() = error("successful upload must not abort")
                }
            }
        }
        val target = cc.monomer.execution.driver.spi.QueryResultTarget.ArtifactUpload(
            "artifacts/workspaces/w/r/result.arrow", 10u, 1_048_576u, 16, 65_536, 65_536, 65_536,
        )
        credential(mapOf("username" to "reader", "password" to "secret")).use { materializedCredential ->
            val result = cc.monomer.execution.driver.jdbc.JdbcTimingFixture.query(
                provider(JdbcConnectionFactory { _, _ -> cc.monomer.execution.driver.jdbc.JdbcTimingFixture.connection() }),
                connector("require"), materializedCredential,
                cc.monomer.execution.driver.jdbc.JdbcTimingFixture.timing(ConnectorType.REDSHIFT, DriverOperation.QUERY),
                target, sink,
            )
            val succeeded = assertIs<DriverPhysicalResult.Succeeded>(result)
            assertEquals(1uL, succeeded.rowsProcessed)
            assertEquals(1uL to 1, completed)
            assertEquals(1uL, succeeded.manifest?.rowCount)
        }
    }

    @Test
    fun `registers the connector type`() {
        val provider =
            RedshiftExecutionDriverProvider(
                maximumQuerySeconds = 300,
                arrowBatchSize = 1_024,
                timeoutPolicy = timeoutPolicy,
                blockingDispatcher = Dispatchers.IO,
                clock = Clock.systemUTC(),
                connectionFactory = DriverManagerJdbcConnectionFactory,
                iamCredentialsClient = DefaultRedshiftIamCredentialsClient,
            )

        assertEquals(ConnectorType.REDSHIFT, provider.capability.connectorType)
    }

    @Test
    fun `maps password credential and source-shaped SSL settings`() {
        val credential = credential(mapOf("username" to "reader", "password" to "secret"))
        credential.use {
            val properties =
                RedshiftDialect(DefaultRedshiftIamCredentialsClient, 1_024).connectionProperties(
                    connector("verify-full"),
                    credential,
                    timeoutBudget,
                )

            assertEquals("reader", properties.getProperty("user"))
            assertEquals("secret", properties.getProperty("password"))
            assertEquals("true", properties.getProperty("ssl"))
            assertEquals("verify-full", properties.getProperty("sslmode"))
            assertEquals("3", properties.getProperty("logintimeout"))
        }
    }

    @Test
    fun `result rows stream through a server cursor of one Arrow batch`() {
        val credential = credential(mapOf("username" to "reader", "password" to "secret"))
        credential.use {
            val properties =
                RedshiftDialect(DefaultRedshiftIamCredentialsClient, 512).connectionProperties(
                    connector("require"),
                    credential,
                    timeoutBudget,
                )

            assertEquals(false, RedshiftProperty.ENABLE_FETCH_RING_BUFFER.getBoolean(properties))
            assertEquals(512, RedshiftProperty.DEFAULT_ROW_FETCH_SIZE.getInt(properties))
        }
        assertFailsWith<IllegalArgumentException> {
            RedshiftDialect(DefaultRedshiftIamCredentialsClient, 0)
        }
    }

    @Test
    fun `cancel stops a running statement through the Redshift connection`() {
        val events = mutableListOf<String>()
        val connection =
            Proxy.newProxyInstance(
                RedshiftConnection::class.java.classLoader,
                arrayOf(Connection::class.java, RedshiftConnection::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "unwrap" -> if (args[0] == RedshiftConnection::class.java) proxy else error("unwrap ${args[0]}")
                    "cancelQuery" -> {
                        events += "connection cancel"
                        null
                    }
                    else -> error(method.name)
                }
            } as Connection
        val statement = jdbcProxy<Statement> { name, _ ->
            when (name) {
                "getConnection" -> connection
                "cancel" -> {
                    events += "statement cancel"
                    null
                }
                else -> error(name)
            }
        }

        RedshiftDialect(DefaultRedshiftIamCredentialsClient, 1_024).cancelRunningStatement(statement)

        assertEquals(listOf("connection cancel"), events)
    }

    @Test
    fun `preserves absent and nonblank optional schema behavior`() {
        val executedStatements = mutableListOf<String>()
        val dialect = RedshiftDialect(DefaultRedshiftIamCredentialsClient, 1_024)

        dialect.prepareConnection(recordingConnection(executedStatements), connector("require"), timeoutBudget)
        assertEquals(emptyList(), executedStatements)

        val connectorWithSchema =
            connector("require").copy(
                properties = connector("require").properties + ("schema" to ConnectorPropertyValue.Text("analytics")),
            )
        dialect.prepareConnection(recordingConnection(executedStatements), connectorWithSchema, timeoutBudget)
        assertEquals(listOf("SET search_path TO \"analytics\""), executedStatements)
    }

    @Test
    fun `rejects blank optional schema before issuing a SET statement`() {
        val connector =
            connector("require").copy(
                properties = connector("require").properties + ("schema" to ConnectorPropertyValue.Text("  ")),
            )

        assertFailsWith<IllegalArgumentException> {
            RedshiftDialect(DefaultRedshiftIamCredentialsClient, 1_024).prepareConnection(
                throwingConnection(),
                connector,
                timeoutBudget,
            )
        }
    }

    @Test
    fun `maps every required IAM secret field to the IAM client`() {
        val iamClient = RecordingIamCredentialsClient()
        val credential = credential(validIamCredentialValues)
        credential.use {
            val properties =
                RedshiftDialect(iamClient, 1_024).connectionProperties(
                    connector("require"),
                    credential,
                    timeoutBudget,
                )

            assertEquals(
                RedshiftIamCredential(
                    awsAccessKeyId = "AKIAEXAMPLE",
                    awsSecretAccessKey = "secret-access-key",
                    awsRegion = "us-east-1",
                    clusterId = "analytics-cluster",
                    dbUser = "reader",
                ),
                iamClient.capturedCredential,
            )
            assertEquals(timeoutBudget, iamClient.capturedTimeoutBudget)
            assertEquals("temporary-reader", properties.getProperty("user"))
            assertEquals("temporary-password", properties.getProperty("password"))
        }
    }

    @Test
    fun `derives IAM client and request API deadlines from the JDBC budget`() {
        val clientOverride = redshiftIamClientOverride(timeoutBudget)
        val requestOverride = redshiftIamRequestOverride(timeoutBudget)

        assertEquals(Duration.ofSeconds(10), clientOverride.apiCallTimeout().orElseThrow())
        assertEquals(Duration.ofSeconds(3), clientOverride.apiCallAttemptTimeout().orElseThrow())
        assertEquals(Duration.ofSeconds(10), requestOverride.apiCallTimeout().orElseThrow())
        assertEquals(Duration.ofSeconds(3), requestOverride.apiCallAttemptTimeout().orElseThrow())
    }

    @Test
    fun `rejects each missing IAM secret field before calling IAM`() {
        validIamCredentialValues.keys.forEach { missingField ->
            val iamClient = RecordingIamCredentialsClient()
            val credential = credential(validIamCredentialValues - missingField)
            credential.use {
                assertFailsWith<IllegalArgumentException> {
                    RedshiftDialect(iamClient, 1_024).connectionProperties(connector("require"), credential, timeoutBudget)
                }
                assertEquals(null, iamClient.capturedCredential)
            }
        }
    }

    @Test
    fun `rejects each blank IAM secret field before calling IAM`() {
        validIamCredentialValues.keys.forEach { blankField ->
            val iamClient = RecordingIamCredentialsClient()
            val credential = credential(validIamCredentialValues + (blankField to "  "))
            credential.use {
                assertFailsWith<IllegalArgumentException> {
                    RedshiftDialect(iamClient, 1_024).connectionProperties(connector("require"), credential, timeoutBudget)
                }
                assertEquals(null, iamClient.capturedCredential)
            }
        }
    }

    @Test
    fun `maps an expired Raw SQL validation deadline without opening a connection`() = runBlocking {
        val fixedClock = Clock.fixed(java.time.Instant.parse("2026-09-02T00:00:00Z"), java.time.ZoneOffset.UTC)
        val provider =
            provider(
                connectionFactory = JdbcConnectionFactory { _, _ -> error("connection must not be opened") },
                clock = fixedClock,
            )

        credential(mapOf("username" to "reader", "password" to "secret")).use { materializedCredential ->
            val failure =
                assertFailsWith<RawSqlValidationException> {
                    provider.validateRawSql(
                        connector = connector("require"),
                        credential = materializedCredential,
                        sql = "SELECT 1",
                        parameters = emptyList(),
                        deadlineEpochMillis = fixedClock.millis(),
                        timing = DriverOperationTiming(ConnectorType.REDSHIFT, DriverOperation.VALIDATE, null, null),
                    )
                }
            assertEquals("RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", failure.failureCode)
        }
    }

    @Test
    fun `aborts an in-flight JDBC analysis when the caller cancels`() {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val aborted = AtomicBoolean(false)
        val provider =
            provider(
                connectionFactory = blockingConnectionFactory(started, released, aborted, null),
            )

        credential(mapOf("username" to "reader", "password" to "secret")).use { materializedCredential ->
            runBlocking {
                val analysis =
                    async(Dispatchers.Default) {
                        provider.validateRawSql(
                            connector = connector("require"),
                            credential = materializedCredential,
                            sql = "SELECT 1",
                            parameters = emptyList(),
                            deadlineEpochMillis = System.currentTimeMillis() + 10_000,
                            timing = DriverOperationTiming(ConnectorType.REDSHIFT, DriverOperation.VALIDATE, null, null),
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
    fun `aborts an in-flight JDBC analysis at the total deadline`() {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val aborted = AtomicBoolean(false)
        val provider =
            provider(
                connectionFactory = blockingConnectionFactory(started, released, aborted, null),
            )

        credential(mapOf("username" to "reader", "password" to "secret")).use { materializedCredential ->
            val failure =
                assertFailsWith<RawSqlValidationException> {
                    runBlocking {
                        provider.validateRawSql(
                            connector = connector("require"),
                            credential = materializedCredential,
                            sql = "SELECT 1",
                            parameters = emptyList(),
                            deadlineEpochMillis = System.currentTimeMillis() + 1_100,
                            timing = DriverOperationTiming(ConnectorType.REDSHIFT, DriverOperation.VALIDATE, null, null),
                        )
                    }
                }
            assertEquals("RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", failure.failureCode)
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertTrue(aborted.get())
        assertTrue(released.await(2, TimeUnit.SECONDS))
    }

    private fun provider(
        connectionFactory: JdbcConnectionFactory,
        clock: Clock = Clock.systemUTC(),
    ): RedshiftExecutionDriverProvider =
        RedshiftExecutionDriverProvider(
            maximumQuerySeconds = 300,
            arrowBatchSize = 1_024,
            timeoutPolicy = timeoutPolicy,
            blockingDispatcher = Dispatchers.IO,
            clock = clock,
            connectionFactory = connectionFactory,
            iamCredentialsClient = DefaultRedshiftIamCredentialsClient,
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
                        "getMetaData" -> {
                            started.countDown()
                            released.await(5, TimeUnit.SECONDS)
                            throw java.sql.SQLException("analysis was aborted")
                        }
                        else -> error("unexpected PreparedStatement method: ${method.name}")
                    }
                } as PreparedStatement
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "unwrap" -> Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BaseConnection::class.java)) { _, nativeMethod, _ ->
                        if (nativeMethod.name == "getStandardConformingStrings") true else error(nativeMethod.name)
                    }
                    "setReadOnly", "setNetworkTimeout", "setAutoCommit" -> null
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

    private fun connector(sslMode: String): ConnectorRuntimeBinding =
        ConnectorRuntimeBinding(
            schemaVersion = 1u,
            connectorId = ConnectorId.parse("019f0000-0000-7000-8000-000000000003"),
            connectorType = ConnectorType.REDSHIFT,
            properties = mapOf(
                    "host" to ConnectorPropertyValue.Text("example.us-east-1.redshift.amazonaws.com"),
                    "port" to ConnectorPropertyValue.Integer(5439),
                    "database" to ConnectorPropertyValue.Text("analytics"),
                    "ssl_mode" to ConnectorPropertyValue.Text(sslMode),
                ),
        )

    private fun credential(values: Map<String, String>): MaterializedCredential =
        MaterializedCredential(
            values.mapValues { (_, value) -> SecretCharacters.copyOf(value.toCharArray()) },
        )

    private fun digest(character: Char): Sha256Digest = Sha256Digest(character.toString().repeat(64))

    private fun throwingConnection(): Connection =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Connection::class.java),
        ) { _, _, _ -> error("the connection should not be used") } as Connection

    private fun recordingConnection(executedStatements: MutableList<String>): Connection =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "setNetworkTimeout", "setAutoCommit" -> null
                "createStatement" -> recordingStatement(executedStatements)
                else -> error("unexpected Connection method: ${method.name}")
            }
        } as Connection

    private fun recordingStatement(executedStatements: MutableList<String>): Statement =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Statement::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "setQueryTimeout", "close" -> null
                "execute" -> {
                    executedStatements += arguments.single() as String
                    true
                }
                else -> error("unexpected Statement method: ${method.name}")
            }
        } as Statement

    private class RecordingIamCredentialsClient : RedshiftIamCredentialsClient {
        var capturedCredential: RedshiftIamCredential? = null
        var capturedTimeoutBudget: JdbcRunTimeoutBudget? = null

        override fun fetch(
            credential: RedshiftIamCredential,
            timeoutBudget: JdbcRunTimeoutBudget,
        ): RedshiftTemporaryCredential {
            capturedCredential = credential
            capturedTimeoutBudget = timeoutBudget
            return RedshiftTemporaryCredential("temporary-reader", "temporary-password")
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

    private val validIamCredentialValues =
        mapOf(
            "aws_access_key_id" to "AKIAEXAMPLE",
            "aws_secret_access_key" to "secret-access-key",
            "aws_region" to "us-east-1",
            "cluster_id" to "analytics-cluster",
            "db_user" to "reader",
        )
}
