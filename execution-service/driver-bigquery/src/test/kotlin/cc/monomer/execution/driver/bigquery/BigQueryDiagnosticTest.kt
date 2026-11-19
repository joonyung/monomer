package cc.monomer.execution.driver.bigquery

import cc.monomer.execution.driver.spi.*
import com.google.cloud.bigquery.BigQueryException
import com.google.cloud.bigquery.BigQueryError
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BigQueryDiagnosticTest {
    @Test
    fun `native job and SDK failures preserve partition diagnostics and suppress secrets`() = runBlocking {
        val reason = "Cannot query over table 'project.analytics.events' without a filter over column 'event_date' that can be used for partition elimination"
        listOf(
            Triple(reason, false, false),
            Triple(reason, true, false),
            Triple(reason, false, true),
            Triple("rejected opaque-credential", false, false),
            Triple(" ", false, false),
        ).forEach { (message, failStart, failObserve) ->
            val connector = ConnectorRuntimeBinding(
                1u, ConnectorId.parse("019f0000-0000-7000-8000-000000000003"), ConnectorType.BIGQUERY,
                mapOf("project_id" to ConnectorPropertyValue.Text("project"), "location" to ConnectorPropertyValue.Text("US")),
            )
            val credential = MaterializedCredential(mapOf("service_account_json" to SecretCharacters.copyOf("opaque-credential".toCharArray())))
            val gateway = FailureGateway(message, failStart, failObserve)
            val session = BigQueryExecutionDriverSession(
                connector, credential, UnusedSink, gateway, Duration.ofMillis(1), Dispatchers.Unconfined,
                Clock.systemUTC(), BigQueryCallTimeoutPolicy(Duration.ofSeconds(5)),
                timing = testTiming(),
            )
            val unsigned = QueryRequest(1u, Sha256Digest("0".repeat(64)), "SELECT 1", emptyList(),
                QueryResultTarget.InlineStream(2, 65536, 65536), false, emptyList())
            val request = unsigned.copy(canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(unsigned))
            val assignment = RunAssignment(WorkspaceId.parse("019f0000-0000-7000-8000-000000000002"), connector,
                request, System.currentTimeMillis() + 30_000)
            try {
                session.enter(StartExecutionContext(RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")), assignment))
                val failed = assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult())
                assertEquals(if (message == reason) reason else "BigQuery execution failed", failed.safeMessage)
                if (message == reason && failStart) assertEquals("PARTITION_FILTER_REQUIRED", failed.failureCode)
            } finally {
                session.close()
            }
        }
    }

    private class FailureGateway(val message: String, val failStart: Boolean, val failObserve: Boolean) : BigQueryGateway {
        override suspend fun validateRawSql(sql: String, parameters: List<QueryParameter>, deadlineEpochMillis: Long): Unit = error("unused")
        override suspend fun start(job: BigQueryJobKey, sql: String, parameters: List<QueryParameter>, maximumRows: ULong, deadlineEpochMillis: Long) {
            if (failStart) throw BigQueryException(400, message, BigQueryError("invalidQuery", null, message))
        }
        override suspend fun observe(job: BigQueryJobKey, deadlineEpochMillis: Long): BigQueryJobSnapshot {
            if (failObserve) throw BigQueryException(400, message)
            return BigQueryJobSnapshot.Failed("BIGQUERY_INVALID_QUERY", message)
        }
        override suspend fun readResultPage(job: BigQueryJobKey, pageToken: String?, maximumRows: Int, deadlineEpochMillis: Long): BigQueryResultPage = error("unused")
        override suspend fun cancel(job: BigQueryJobKey, deadlineEpochMillis: Long) = true
        override fun close() = Unit
    }

    private object UnusedSink : RunResultSink {
        override suspend fun emitInlineResult(batch: InlineResultBatch) = error("unused")
        override suspend fun openObjectUpload(objectKey: String, mediaType: String): ArtifactUploadSink = error("unused")
    }
}
