package cc.monomer.execution.driver.bigquery

import cc.monomer.execution.arrow.*
import cc.monomer.execution.driver.spi.*
import com.google.cloud.bigquery.BigQueryException
import java.time.Clock
import java.time.Duration
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.test.assertFailsWith

class BigQueryTimingTest {
    @Test
    fun querySummaryIdentifiesEveryFailedAttemptAndNeverIncludesWarehouseData() = runBlocking {
        listOf(null, DriverPhase.EXECUTE, DriverPhase.WAIT, DriverPhase.READ, DriverPhase.UPLOAD, DriverPhase.DELIVERY).forEach { failedPhase ->
            val timing = testTiming()
            val gateway = Gateway(failedPhase)
            val artifact = failedPhase != DriverPhase.DELIVERY
            val sink = Sink(failedPhase)
            val line = capture {
                val session = provider(gateway).openSession(connector(), credential(), sink, timing)
                try {
                    session.enter(context(artifact))
                    val outcome = session.awaitPhysicalResult()
                    if (failedPhase == null) assertIs<DriverPhysicalResult.Succeeded>(outcome) else assertIs<DriverPhysicalResult.Failed>(outcome)
                    timing.log(outcome)
                    timing.log(outcome)
                } finally { session.close() }
            }
            assertTrue(line.contains("driver=BIGQUERY operation=QUERY"))
            assertTrue(line.contains("failure_phase=${failedPhase?.name ?: "none"}"))
            assertTrue(line.contains("outcome=${if (failedPhase == null) "SUCCEEDED" else "FAILED"}"))
            assertNumeric(line, DriverPhase.CREDENTIAL)
            assertNumeric(line, DriverPhase.CONNECT)
            assertNumeric(line, DriverPhase.EXECUTE)
            if (failedPhase != DriverPhase.EXECUTE) assertNumeric(line, DriverPhase.WAIT)
            if (failedPhase == null || failedPhase == DriverPhase.READ || failedPhase == DriverPhase.UPLOAD || failedPhase == DriverPhase.DELIVERY) assertNumeric(line, DriverPhase.READ)
            if (failedPhase == null || failedPhase == DriverPhase.UPLOAD) assertNumeric(line, DriverPhase.UPLOAD)
            if (failedPhase == DriverPhase.UPLOAD) {
                assertNumeric(line, DriverPhase.CLEANUP)
                assertTrue(line.contains("cleanup_failure_count=1"), line)
            }
            assertSecretsAbsent(line)
        }
    }

    @Test
    fun validationSuccessAndFailureHaveNativeAttemptDuration() = runBlocking {
        listOf(false, true).forEach { fail ->
            val timing = DriverOperationTiming(ConnectorType.BIGQUERY, DriverOperation.VALIDATE,
                "019f0000-0000-7000-8000-000000000001", "019f0000-0000-7000-8000-000000000002")
            credential().use { credential ->
                val line = capture {
                    val action: suspend () -> Unit = {
                        provider(Gateway(if (fail) DriverPhase.VALIDATE else null)).validateRawSql(
                            connector(), credential, SQL, listOf(QueryParameter.Text(SECRET, name = "")), System.currentTimeMillis() + 30_000, timing)
                    }
                    if (fail) {
                        val failure = assertFailsWith<RawSqlValidationException> { action() }
                        timing.log(DriverPhysicalResult.Failed(failure.failureCode, failure.safeMessage))
                    } else {
                        action()
                        timing.log(DriverPhysicalResult.Succeeded(0uL, 0uL, null))
                    }
                }
                assertTrue(line.contains("operation=VALIDATE"))
                assertTrue(line.contains("failure_phase=${if (fail) "VALIDATE" else "none"}"))
                assertNumeric(line, DriverPhase.CONNECT)
                assertNumeric(line, DriverPhase.VALIDATE)
                assertSecretsAbsent(line)
            }
        }
    }

    private fun provider(gateway: Gateway) = BigQueryExecutionDriverProvider(
        BigQueryGatewayFactory { _, _, _, _, _, _ -> gateway }, Duration.ofMillis(1), Dispatchers.Unconfined,
        Clock.systemUTC(), BigQueryCallTimeoutPolicy(Duration.ofSeconds(5)))

    private fun connector() = ConnectorRuntimeBinding(1u,
        ConnectorId.parse("019f0000-0000-7000-8000-000000000003"), ConnectorType.BIGQUERY,
        mapOf("project_id" to ConnectorPropertyValue.Text("private-project"), "location" to ConnectorPropertyValue.Text("US")))

    private fun credential() = MaterializedCredential(mapOf("service_account_json" to SecretCharacters.copyOf(SECRET.toCharArray())))

    private fun context(artifact: Boolean): StartExecutionContext {
        val target = if (artifact) QueryResultTarget.ArtifactUpload("artifacts/private-key.arrow", 10uL, 65536uL, 2, 65536, 65536, 65536)
            else QueryResultTarget.InlineStream(2, 65536, 65536)
        val unsigned = QueryRequest(1u, Sha256Digest("0".repeat(64)), SQL, listOf(QueryParameter.Text(SECRET, name = "")), target, false, emptyList())
        val request = unsigned.copy(canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(unsigned))
        return StartExecutionContext(RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")),
            RunAssignment(WorkspaceId.parse("019f0000-0000-7000-8000-000000000002"), connector(), request, System.currentTimeMillis() + 30_000))
    }

    private class Gateway(private val failedPhase: DriverPhase?) : BigQueryGateway {
        private var polls = 0
        private fun attempt(phase: DriverPhase) { if (phase == failedPhase) throw BigQueryException(503, "$SQL $SECRET https://private.example/?token=$SECRET") }
        override suspend fun validateRawSql(sql: String, parameters: List<QueryParameter>, deadlineEpochMillis: Long) = attempt(DriverPhase.VALIDATE)
        override suspend fun start(job: BigQueryJobKey, sql: String, parameters: List<QueryParameter>, maximumRows: ULong, deadlineEpochMillis: Long) = attempt(DriverPhase.EXECUTE)
        override suspend fun observe(job: BigQueryJobKey, deadlineEpochMillis: Long): BigQueryJobSnapshot {
            attempt(DriverPhase.WAIT)
            return if (++polls == 1) BigQueryJobSnapshot.Running else BigQueryJobSnapshot.Succeeded(0uL)
        }
        override suspend fun readResultPage(job: BigQueryJobKey, pageToken: String?, maximumRows: Int, deadlineEpochMillis: Long): BigQueryResultPage {
            attempt(DriverPhase.READ)
            return BigQueryResultPage(TabularPage(listOf(TabularColumn("private-column", TabularScalarType.STRING, false)), listOf(listOf(TabularScalar.Text(SECRET)))), null)
        }
        override suspend fun cancel(job: BigQueryJobKey, deadlineEpochMillis: Long) = true
        override fun close() = Unit
    }

    private class Sink(private val failedPhase: DriverPhase?) : RunResultSink {
        override suspend fun emitInlineResult(batch: InlineResultBatch) { if (failedPhase == DriverPhase.DELIVERY) throw IllegalStateException(SECRET) }
        override suspend fun openObjectUpload(objectKey: String, mediaType: String) = object : ArtifactUploadSink {
            override suspend fun append(payload: BinaryValue) { if (failedPhase == DriverPhase.UPLOAD) throw IllegalStateException(SECRET) }
            override suspend fun complete(rowCount: ULong, columnCount: Int) = ArrowResultManifest("artifacts/private-key.arrow", 100uL, SECRET, rowCount, columnCount, ArrowResultManifest.ARROW_FILE_MEDIA_TYPE)
            override suspend fun abort() { if (failedPhase == DriverPhase.UPLOAD) throw IllegalStateException(SECRET) }
        }
    }

    private suspend fun capture(block: suspend () -> Unit): String {
        val records = mutableListOf<LogRecord>()
        val logger = Logger.getLogger(DriverOperationTiming::class.java.name)
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { records += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        return try { block(); assertEquals(null, records.single().thrown); records.single().message }
        finally { logger.removeHandler(handler) }
    }

    private fun assertNumeric(line: String, phase: DriverPhase) {
        assertTrue(Regex("${phase.name.lowercase()}_ms=\\d+").containsMatchIn(line), line)
    }
    private fun assertSecretsAbsent(line: String) {
        listOf(SECRET, SQL, "private-project", "private-column", "private-key", "https://").forEach { assertTrue(!line.contains(it), line) }
    }
    private companion object {
        const val SECRET = "private-warehouse-secret"
        const val SQL = "SELECT private_customer FROM private_dataset WHERE token = ?"
    }
}
