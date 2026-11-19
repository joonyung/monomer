package cc.monomer.execution.driver.jdbc

import cc.monomer.execution.arrow.JdbcArrowEncoder
import cc.monomer.execution.arrow.JavaTimeJdbcResultValueReader
import cc.monomer.execution.arrow.JdbcDecimalPhysicalEncoding
import cc.monomer.execution.driver.spi.*
import java.sql.Connection
import java.sql.Statement
import java.time.Clock
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JdbcOperationTimingTest {
    @Test
    fun `every failing JDBC phase retains its own phase and never logs sensitive values`() = runBlocking<Unit> {
        listOf(DriverPhase.CREDENTIAL, DriverPhase.CONNECT, DriverPhase.SESSION_SETUP,
            DriverPhase.EXECUTE, DriverPhase.READ, DriverPhase.DELIVERY, DriverPhase.UPLOAD).forEach { phase ->
            val failure = java.sql.SQLException(JdbcTimingFixture.EXCEPTION + JdbcTimingFixture.PASSWORD + JdbcTimingFixture.SQL, "XX000")
            val dialect = dialect(phase, failure)
            val provider = provider(dialect, JdbcConnectionFactory { _, _ ->
                if (phase == DriverPhase.CONNECT) throw failure
                JdbcTimingFixture.connection(failure = failure.takeIf { phase == DriverPhase.EXECUTE },
                    readFailure = failure.takeIf { phase == DriverPhase.READ })
            })
            val sink = object : RunResultSink {
                override suspend fun emitInlineResult(batch: InlineResultBatch) {
                    if (phase == DriverPhase.DELIVERY) throw failure
                }
                override suspend fun openObjectUpload(objectKey: String, mediaType: String): ArtifactUploadSink =
                    throw failure
            }
            val target = if (phase == DriverPhase.UPLOAD) artifactTarget else JdbcTimingFixture.inlineTarget
            JdbcTimingFixture.summary(ConnectorType.POSTGRESQL, DriverOperation.QUERY,
                "JDBC_TEST_FAILURE", phase, setOf(phase)) { timing ->
                JdbcTimingFixture.query(provider, connector, MaterializedCredential(emptyMap()), timing, target, sink)
            }
        }
    }

    @Test
    fun `artifact success and completion failure include read upload and cleanup once`() = runBlocking<Unit> {
        listOf(false, true).forEach { failComplete ->
            var appends = 0
            var completions = 0
            var aborts = 0
            val sink = object : RunResultSink {
                override suspend fun emitInlineResult(batch: InlineResultBatch) = error("unused")
                override suspend fun openObjectUpload(objectKey: String, mediaType: String): ArtifactUploadSink =
                    object : ArtifactUploadSink {
                        override suspend fun append(payload: BinaryValue) { appends++ }
                        override suspend fun complete(rowCount: ULong, columnCount: Int): ArrowResultManifest {
                            completions++
                            if (failComplete) throw IllegalStateException(JdbcTimingFixture.EXCEPTION + JdbcTimingFixture.PASSWORD)
                            return ArrowResultManifest(objectKey, 1uL, "etag", rowCount, columnCount, mediaType)
                        }
                        override suspend fun abort() {
                            aborts++
                            throw IllegalStateException(JdbcTimingFixture.EXCEPTION + JdbcTimingFixture.PASSWORD)
                        }
                    }
            }
            val summary = JdbcTimingFixture.summary(ConnectorType.POSTGRESQL, DriverOperation.QUERY,
                if (failComplete) "JDBC_TEST_FAILURE" else null,
                if (failComplete) DriverPhase.UPLOAD else null,
                setOf(DriverPhase.READ, DriverPhase.UPLOAD, DriverPhase.CLEANUP)) { timing ->
                JdbcTimingFixture.query(provider(dialect(null, null), JdbcConnectionFactory { _, _ -> JdbcTimingFixture.connection() }),
                    connector, MaterializedCredential(emptyMap()), timing, artifactTarget, sink)
            }
            assertTrue(summary.contains("cleanup_failure_count=${if (failComplete) 1 else 0}"), summary)
            assertTrue(appends > 0)
            assertEquals(1, completions)
            assertEquals(if (failComplete) 1 else 0, aborts)
        }
    }

    private fun dialect(failedPhase: DriverPhase?, failure: Throwable?) = object : JdbcConnectorDialect {
        override val serializeConnectionLifecycle = false
        override fun jdbcUrl(connector: ConnectorRuntimeBinding) = "jdbc:test:timing-private-password"
        override fun connectionProperties(connector: ConnectorRuntimeBinding, credential: MaterializedCredential,
            timeoutBudget: JdbcRunTimeoutBudget): Properties {
            if (failedPhase == DriverPhase.CREDENTIAL) throw requireNotNull(failure)
            return Properties().apply { setProperty("password", JdbcTimingFixture.PASSWORD) }
        }
        override fun prepareConnection(connection: Connection, connector: ConnectorRuntimeBinding, timeoutBudget: JdbcRunTimeoutBudget) {
            if (failedPhase == DriverPhase.SESSION_SETUP) throw requireNotNull(failure)
        }
        override fun refreshTimeouts(connection: Connection, timeoutBudget: JdbcRunTimeoutBudget) = Unit
        override fun cancelRunningStatement(statement: Statement) = Unit
        override fun safeFailureCode(failure: Throwable) = "JDBC_TEST_FAILURE"
    }

    private fun provider(dialect: JdbcConnectorDialect, factory: JdbcConnectionFactory) = object : ExecutionDriverProvider {
        override val capability = DriverCapabilityDescriptor(connectorType = ConnectorType.POSTGRESQL)
        override fun openSession(connector: ConnectorRuntimeBinding, credential: MaterializedCredential?, resultSink: RunResultSink,
            timing: DriverOperationTiming) = JdbcExecutionDriverSession(ConnectorType.POSTGRESQL, dialect, connector,
                requireNotNull(credential), resultSink, JdbcArrowEncoder(2, JdbcDecimalPhysicalEncoding.NATIVE_DECIMAL, JavaTimeJdbcResultValueReader),
                30, JdbcRunTimeoutPolicy(2_000, 20_000, 1_000), Dispatchers.IO, Clock.systemUTC(), factory, timing)
        override suspend fun validateRawSql(connector: ConnectorRuntimeBinding, credential: MaterializedCredential?, sql: String,
            parameters: List<QueryParameter>, deadlineEpochMillis: Long, timing: DriverOperationTiming) = error("unused")
    }

    private val connector = ConnectorRuntimeBinding(1u, ConnectorId.parse("019f0000-0000-7000-8000-000000000003"),
        ConnectorType.POSTGRESQL, emptyMap())
    private val artifactTarget = QueryResultTarget.ArtifactUpload("timing-private-object-key", 100uL, 1048576uL, 2, 65536, 65536, 1048576)
}
