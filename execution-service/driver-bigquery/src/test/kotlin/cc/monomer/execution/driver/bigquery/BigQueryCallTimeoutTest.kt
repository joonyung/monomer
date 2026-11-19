package cc.monomer.execution.driver.bigquery

import cc.monomer.execution.driver.spi.ArtifactUploadSink
import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.DriverPhysicalResult
import cc.monomer.execution.driver.spi.InlineResultBatch
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.QueryParameter
import cc.monomer.execution.driver.spi.QueryRequest
import cc.monomer.execution.driver.spi.QueryRequestCanonicalizer
import cc.monomer.execution.driver.spi.QueryResultTarget
import cc.monomer.execution.driver.spi.RawSqlValidationException
import cc.monomer.execution.driver.spi.RunAssignment
import cc.monomer.execution.driver.spi.RunIdentity
import cc.monomer.execution.driver.spi.RunResultSink
import cc.monomer.execution.driver.spi.SecretCharacters
import cc.monomer.execution.driver.spi.Sha256Digest
import cc.monomer.execution.driver.spi.StartExecutionContext
import com.google.cloud.bigquery.BigQuery
import com.google.cloud.bigquery.BigQueryException
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class BigQueryCallTimeoutTest {
    @Test
    fun startAndObserveDistinguishTheCallCapFromTheRunDeadline() = runTest {
        for (phase in listOf("START", "OBSERVE")) {
            for (remainingMillis in listOf(60_000L, 30_000L, 1_000L)) {
                val clock = FixtureClock { testScheduler.currentTime }
                var cancels = 0
                val gateway = object : FixtureGateway() {
                    override suspend fun start(job: BigQueryJobKey, sql: String, parameters: List<QueryParameter>, maximumRows: ULong, deadlineEpochMillis: Long) {
                        if (phase == "START") delay(30_001)
                    }

                    override suspend fun observe(job: BigQueryJobKey, deadlineEpochMillis: Long): BigQueryJobSnapshot {
                        if (phase == "OBSERVE") delay(30_001)
                        return BigQueryJobSnapshot.Running
                    }

                    override suspend fun cancel(job: BigQueryJobKey, deadlineEpochMillis: Long): Boolean {
                        cancels++
                        return true
                    }
                }
                val session = session(gateway, clock, StandardTestDispatcher(testScheduler))
                try {
                    val startedAt = clock.millis()
                    session.enter(context(startedAt + remainingMillis))
                    val failed = assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult())
                    assertEquals(if (remainingMillis > 30_000) "TIMEOUT" else "RUN_DEADLINE_EXCEEDED", failed.failureCode, phase)
                    assertEquals(minOf(remainingMillis, 30_000), clock.millis() - startedAt)
                    assertEquals(1, cancels)
                } finally {
                    session.close()
                }
            }
        }
    }

    @Test
    fun sdkSocketTimeoutsInStartObserveAndReadUseTheEffectiveCallBudget() = runTest {
        for (phase in listOf("START", "OBSERVE", "READ")) {
            for (remainingMillis in listOf(60_000L, 30_000L, 1_000L)) {
                var nowMillis = 1_000L
                val clock = FixtureClock { nowMillis }
                var observedTimeout: BigQueryRpcTimeout? = null
                val gateway = GoogleBigQueryGateway(
                    bigQueryClientExecutor = object : BigQueryClientExecutor {
                        override fun <T> execute(timeout: BigQueryRpcTimeout, operation: (BigQuery) -> T): T {
                            observedTimeout = timeout
                            nowMillis += timeout.totalTimeoutMillis
                            throw BigQueryException(SocketTimeoutException("Read timed out"))
                        }
                    },
                    projectId = "monomer-test",
                    location = "US",
                    maximumBytesBilled = 1_000_000,
                    jobTimeout = Duration.ofHours(1),
                    blockingDispatcher = StandardTestDispatcher(testScheduler),
                    clock = clock,
                    callTimeoutPolicy = BigQueryCallTimeoutPolicy(Duration.ofSeconds(30)),
                )
                val deadline = nowMillis + remainingMillis
                val operation: suspend () -> Unit = {
                    when (phase) {
                        "START" -> gateway.start(job, "SELECT 1", emptyList(), 1uL, deadline)
                        "OBSERVE" -> gateway.observe(job, deadline)
                        else -> gateway.readResultPage(job, null, 1, deadline)
                    }
                }
                if (remainingMillis > 30_000) {
                    assertFailsWith<BigQueryCallTimeoutException>(phase) { operation() }
                } else {
                    assertFailsWith<BigQueryCallDeadlineExceededException>(phase) { operation() }
                }
                val expected = minOf(remainingMillis, 30_000)
                assertEquals(BigQueryRpcTimeout(expected.toInt(), expected.toInt(), expected), observedTimeout)
            }
        }
    }

    @Test
    fun readTimeoutClassificationPrecedesTheCleanupBudget() = runTest {
        for (runDeadlineExpired in listOf(false, true)) {
            var nowMillis = 1_000L
            val deadline = nowMillis + 60_000
            val clock = FixtureClock { nowMillis }
            val gateway = object : FixtureGateway() {
                override suspend fun observe(job: BigQueryJobKey, deadlineEpochMillis: Long) = BigQueryJobSnapshot.Succeeded(0uL)

                override suspend fun readResultPage(job: BigQueryJobKey, pageToken: String?, maximumRows: Int, deadlineEpochMillis: Long): BigQueryResultPage {
                    nowMillis += if (runDeadlineExpired) 60_000 else 30_000
                    throw BigQueryCallTimeoutException()
                }

                override suspend fun cancel(job: BigQueryJobKey, deadlineEpochMillis: Long): Boolean {
                    nowMillis += 60_000
                    return true
                }
            }
            val session = session(gateway, clock, StandardTestDispatcher(testScheduler))
            try {
                session.enter(context(deadline))
                val failed = assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult())
                assertEquals(if (runDeadlineExpired) "RUN_DEADLINE_EXCEEDED" else "TIMEOUT", failed.failureCode)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun validationKeepsItsExistingDeadlineCodeForTheApiCallCap() = runTest {
        val provider = BigQueryExecutionDriverProvider(
            BigQueryGatewayFactory { _, _, _, _, _, _ ->
                object : FixtureGateway() {
                    override suspend fun validateRawSql(sql: String, parameters: List<QueryParameter>, deadlineEpochMillis: Long) {
                        throw BigQueryCallTimeoutException()
                    }
                }
            },
            Duration.ofMillis(1),
            StandardTestDispatcher(testScheduler),
            FixtureClock { 1_000 },
            BigQueryCallTimeoutPolicy(Duration.ofSeconds(30)),
        )
        credential().use { credential ->
            val failed = assertFailsWith<RawSqlValidationException> {
                provider.validateRawSql(connector, credential, "SELECT 1", emptyList(), 61_000, testTiming())
            }
            assertEquals("RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", failed.failureCode)
        }
    }

    private fun session(gateway: BigQueryGateway, clock: Clock, dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        BigQueryExecutionDriverSession(connector, credential(), NoOpSink, gateway, Duration.ofMillis(1), dispatcher, clock,
            BigQueryCallTimeoutPolicy(Duration.ofSeconds(30)), testTiming())

    private fun credential() = MaterializedCredential(mapOf("service_account_json" to SecretCharacters.copyOf("{}".toCharArray())))

    private fun context(deadlineEpochMillis: Long): StartExecutionContext {
        val unsigned = QueryRequest(1u, Sha256Digest("0".repeat(64)), "SELECT 1", emptyList(),
            QueryResultTarget.InlineStream(128, 65_536, 65_536), false, emptyList())
        return StartExecutionContext(RunIdentity(bigQueryRunId), RunAssignment(bigQueryWorkspaceId, connector,
            unsigned.copy(canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(unsigned)), deadlineEpochMillis))
    }

    private open class FixtureGateway : BigQueryGateway {
        override suspend fun validateRawSql(sql: String, parameters: List<QueryParameter>, deadlineEpochMillis: Long): Unit = error("not used")
        override suspend fun start(job: BigQueryJobKey, sql: String, parameters: List<QueryParameter>, maximumRows: ULong, deadlineEpochMillis: Long) = Unit
        override suspend fun observe(job: BigQueryJobKey, deadlineEpochMillis: Long): BigQueryJobSnapshot = error("not used")
        override suspend fun readResultPage(job: BigQueryJobKey, pageToken: String?, maximumRows: Int, deadlineEpochMillis: Long): BigQueryResultPage = error("not used")
        override suspend fun cancel(job: BigQueryJobKey, deadlineEpochMillis: Long) = true
        override fun close() = Unit
    }

    private class FixtureClock(private val currentMillis: () -> Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(currentMillis())
        override fun millis(): Long = currentMillis()
    }

    private object NoOpSink : RunResultSink {
        override suspend fun emitInlineResult(batch: InlineResultBatch) = Unit
        override suspend fun openObjectUpload(objectKey: String, mediaType: String): ArtifactUploadSink = error("not used")
    }

    private companion object {
        val connector = ConnectorRuntimeBinding(1u, bigQueryConnectorId, ConnectorType.BIGQUERY,
            mapOf("project_id" to ConnectorPropertyValue.Text("monomer-test"), "location" to ConnectorPropertyValue.Text("US")))
        val job = BigQueryJobKey("monomer-test", "US", "test-job")
    }
}
