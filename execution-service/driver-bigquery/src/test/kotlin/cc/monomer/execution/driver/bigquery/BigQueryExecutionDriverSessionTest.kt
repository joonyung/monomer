package cc.monomer.execution.driver.bigquery

import cc.monomer.execution.driver.spi.ArtifactUploadSink
import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.DriverCancellationResult
import cc.monomer.execution.driver.spi.DriverPhysicalResult
import cc.monomer.execution.driver.spi.InlineResultBatch
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.QueryParameter
import cc.monomer.execution.driver.spi.QueryRequest
import cc.monomer.execution.driver.spi.QueryRequestCanonicalizer
import cc.monomer.execution.driver.spi.QueryResultTarget
import cc.monomer.execution.driver.spi.RunAssignment
import cc.monomer.execution.driver.spi.RunIdentity
import cc.monomer.execution.driver.spi.RunResultSink
import cc.monomer.execution.driver.spi.SecretCharacters
import cc.monomer.execution.driver.spi.Sha256Digest
import cc.monomer.execution.driver.spi.StartExecutionContext
import com.google.cloud.bigquery.BigQueryException
import java.time.Clock
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class BigQueryExecutionDriverSessionTest {
    @Test
    fun `a job created while its start call is cancelled stays cancellable`() =
        runBlocking {
            val createEntered = CountDownLatch(1)
            val createMayFinish = CountDownLatch(1)
            val gateway =
                RecordingGateway(
                    blockingCreate = {
                        createEntered.countDown()
                        createMayFinish.await(5, TimeUnit.SECONDS)
                    },
                    observe = { BigQueryJobSnapshot.Running },
                )
            val session = session(gateway)
            try {
                val entering =
                    launch(Dispatchers.Default) {
                        session.enter(startContext(System.currentTimeMillis() + 60_000))
                    }
                assertTrue(withContext(Dispatchers.IO) { createEntered.await(5, TimeUnit.SECONDS) })
                entering.cancel()
                createMayFinish.countDown()
                entering.join()

                assertTrue(entering.isCancelled)
                assertEquals(DriverCancellationResult.REQUESTED, session.cancel())
                assertEquals(gateway.createdJobs.toList(), gateway.cancelledJobs.toList())
                assertEquals(1, gateway.cancelledJobs.size)
            } finally {
                session.close()
            }
        }

    @Test
    fun `a job created by a start that the gateway reports past the run deadline is cancelled`() =
        runBlocking {
            val gateway =
                RecordingGateway(
                    blockingCreate = {},
                    observe = { BigQueryJobSnapshot.Running },
                    afterCreate = { throw BigQueryCallDeadlineExceededException() },
                )
            val session = session(gateway)
            try {
                session.enter(startContext(System.currentTimeMillis() + 60_000))

                assertEquals("RUN_DEADLINE_EXCEEDED", assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult()).failureCode)
                assertEquals(1, gateway.createdJobs.size)
                assertEquals(gateway.createdJobs.toList(), gateway.cancelledJobs.toList())
            } finally {
                session.close()
            }
        }

    @Test
    fun `a job created by a start that overruns the session run deadline is cancelled`() =
        runBlocking {
            val gateway =
                RecordingGateway(
                    blockingCreate = { Thread.sleep(1_500) },
                    observe = { BigQueryJobSnapshot.Running },
                )
            val session = session(gateway)
            try {
                session.enter(startContext(System.currentTimeMillis() + 1_200))

                assertEquals("RUN_DEADLINE_EXCEEDED", assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult()).failureCode)
                assertEquals(1, gateway.createdJobs.size)
                assertEquals(gateway.createdJobs.toList(), gateway.cancelledJobs.toList())
            } finally {
                session.close()
            }
        }

    @Test
    fun `an assignment already past its run deadline fails as the run deadline without creating a job`() =
        runBlocking {
            val gateway =
                RecordingGateway(
                    blockingCreate = {},
                    observe = { BigQueryJobSnapshot.Running },
                )
            val session = session(gateway)
            try {
                session.enter(startContext(System.currentTimeMillis() - 1))

                assertEquals("RUN_DEADLINE_EXCEEDED", assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult()).failureCode)
                assertTrue(gateway.createdJobs.isEmpty())
            } finally {
                session.close()
            }
        }

    @Test
    fun `a job still running at the run deadline is cancelled after the deadline`() =
        runBlocking {
            val gateway =
                RecordingGateway(
                    blockingCreate = {},
                    observe = { BigQueryJobSnapshot.Running },
                )
            val session = session(gateway)
            try {
                session.enter(startContext(System.currentTimeMillis() + 1_200))
                val failed = assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult())

                assertEquals("RUN_DEADLINE_EXCEEDED", failed.failureCode)
                assertEquals(1, gateway.createdJobs.size)
                assertEquals(gateway.createdJobs.toList(), gateway.cancelledJobs.toList())
            } finally {
                session.close()
            }
        }

    @Test
    fun `a job whose observation fails is cancelled`() =
        runBlocking {
            val gateway =
                RecordingGateway(
                    blockingCreate = {},
                    observe = { throw BigQueryException(503, "backend unavailable") },
                )
            val session = session(gateway)
            try {
                session.enter(startContext(System.currentTimeMillis() + 60_000))
                val failed = assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult())

                assertEquals("BIGQUERY_OBSERVE_FAILED", failed.failureCode)
                assertEquals(1, gateway.createdJobs.size)
                assertEquals(gateway.createdJobs.toList(), gateway.cancelledJobs.toList())
            } finally {
                session.close()
            }
        }

    @Test
    fun `a job whose observation fails unexpectedly is cancelled`() =
        runBlocking {
            val gateway =
                RecordingGateway(
                    blockingCreate = {},
                    observe = { throw IllegalStateException("unexpected job state") },
                )
            val session = session(gateway)
            try {
                session.enter(startContext(System.currentTimeMillis() + 60_000))
                val failed = assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult())

                assertEquals("BIGQUERY_OBSERVE_FAILED", failed.failureCode)
                assertEquals("BigQuery observation failed", failed.safeMessage)
                assertEquals(1, gateway.createdJobs.size)
                assertEquals(gateway.createdJobs.toList(), gateway.cancelledJobs.toList())
            } finally {
                session.close()
            }
        }

    private fun session(gateway: BigQueryGateway): BigQueryExecutionDriverSession =
        BigQueryExecutionDriverSession(
            connector = connector,
            credential =
                MaterializedCredential(
                    mapOf("service_account_json" to SecretCharacters.copyOf("{}".toCharArray())),
                ),
            resultSink = NoOpResultSink,
            gateway = gateway,
            pollInterval = Duration.ofMillis(50),
            blockingDispatcher = Dispatchers.IO,
            clock = Clock.systemUTC(),
            callTimeoutPolicy = BigQueryCallTimeoutPolicy(Duration.ofSeconds(30)),
            timing = testTiming(),
        )

    private fun startContext(deadlineEpochMillis: Long): StartExecutionContext {
        val unsigned =
            QueryRequest(
                validateRawSqlBeforeRun = false,
                expectedColumnNames = emptyList(),
                schemaVersion = 1u,
                canonicalRequestDigest = Sha256Digest("0".repeat(64)),
                sql = "SELECT value FROM dataset.table",
                parameters = emptyList(),
                resultTarget =
                    QueryResultTarget.InlineStream(
                        maximumRecordBatchRows = 128,
                        maximumRecordBatchBytes = 64 * 1024,
                        maximumCellBytes = 64 * 1024,
                    ),
            )
        return StartExecutionContext(
            run = RunIdentity(bigQueryRunId),
            assignment =
                RunAssignment(
                    workspaceId = bigQueryWorkspaceId,
                    connector = connector,
                    request = unsigned.copy(canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(unsigned)),
                    deadlineEpochMillis = deadlineEpochMillis,
                ),
        )
    }

    private class RecordingGateway(
        private val blockingCreate: () -> Unit,
        private val observe: suspend () -> BigQueryJobSnapshot,
        private val afterCreate: () -> Unit = {},
    ) : BigQueryGateway {
        val createdJobs: MutableList<BigQueryJobKey> = Collections.synchronizedList(mutableListOf())
        val cancelledJobs: MutableList<BigQueryJobKey> = Collections.synchronizedList(mutableListOf())

        override suspend fun validateRawSql(
            sql: String,
            parameters: List<QueryParameter>,
            deadlineEpochMillis: Long,
        ): Unit = error("not used")

        override suspend fun start(
            job: BigQueryJobKey,
            sql: String,
            parameters: List<QueryParameter>,
            maximumRows: ULong,
            deadlineEpochMillis: Long,
        ) {
            withContext(Dispatchers.IO) {
                blockingCreate()
                createdJobs += job
                afterCreate()
            }
        }

        override suspend fun observe(
            job: BigQueryJobKey,
            deadlineEpochMillis: Long,
        ): BigQueryJobSnapshot = observe()

        override suspend fun readResultPage(
            job: BigQueryJobKey,
            pageToken: String?,
            maximumRows: Int,
            deadlineEpochMillis: Long,
        ): BigQueryResultPage = error("not used")

        override suspend fun cancel(
            job: BigQueryJobKey,
            deadlineEpochMillis: Long,
        ): Boolean {
            if (deadlineEpochMillis <= System.currentTimeMillis()) throw BigQueryCallDeadlineExceededException()
            if (job !in createdJobs) return false
            cancelledJobs += job
            return true
        }

        override fun close() = Unit
    }

    private object NoOpResultSink : RunResultSink {
        override suspend fun emitInlineResult(batch: InlineResultBatch) = Unit

        override suspend fun openObjectUpload(
            objectKey: String,
            mediaType: String,
        ): ArtifactUploadSink = error("not used")
    }

    private companion object {
        val connector =
            ConnectorRuntimeBinding(
                schemaVersion = 1u,
                connectorId = bigQueryConnectorId,
                connectorType = ConnectorType.BIGQUERY,
                properties =
                    mapOf(
                        "project_id" to ConnectorPropertyValue.Text("monomer-test"),
                        "location" to ConnectorPropertyValue.Text("US"),
                    ),
            )
    }
}
