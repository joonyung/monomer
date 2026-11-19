package cc.monomer.execution.driver.bigquery

import cc.monomer.execution.arrow.TabularColumn
import cc.monomer.execution.arrow.TabularPage
import cc.monomer.execution.arrow.TabularScalar
import cc.monomer.execution.arrow.TabularScalarType
import cc.monomer.execution.driver.spi.ArtifactUploadSink
import cc.monomer.execution.driver.spi.BinaryValue
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
import cc.monomer.execution.driver.spi.ResultLimitExceededException
import cc.monomer.execution.driver.spi.RunAssignment
import cc.monomer.execution.driver.spi.RunIdentity
import cc.monomer.execution.driver.spi.RunResultSink
import cc.monomer.execution.driver.spi.SecretCharacters
import cc.monomer.execution.driver.spi.Sha256Digest
import cc.monomer.execution.driver.spi.StartExecutionContext
import com.google.cloud.bigquery.Field
import com.google.cloud.bigquery.LegacySQLTypeName
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class BigQueryResultFailureTest {
    @Test
    fun frameTextAndBinaryCellAndSinkLimitsReturnNamedFailures() = runTest {
        val cases = listOf(
            Triple(TabularScalarType.STRING, TabularScalar.Text("hello"), QueryResultTarget.InlineStream(128, 128, 65_536)),
            Triple(TabularScalarType.STRING, TabularScalar.Text("héllo"), QueryResultTarget.InlineStream(128, 65_536, 5)),
            Triple(TabularScalarType.BYTES, TabularScalar.Bytes(BinaryValue.copyOf(ByteArray(6))), QueryResultTarget.InlineStream(128, 65_536, 5)),
            Triple(TabularScalarType.STRING, TabularScalar.Text("hello"), QueryResultTarget.InlineStream(128, 65_536, 65_536)),
        )
        for ((index, fixture) in cases.withIndex()) {
            val (type, value, target) = fixture
            val gateway = PageGateway {
                BigQueryResultPage(TabularPage(listOf(TabularColumn("value", type, false)), listOf(listOf(value))), null)
            }
            val session = session(gateway, if (index == 3) LimitSink else NoOpSink, StandardTestDispatcher(testScheduler))
            try {
                session.enter(context(target, 61_000))
                val failed = assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult())
                assertEquals("RESULT_LIMIT_EXCEEDED", failed.failureCode)
                assertTrue(failed.safeMessage.contains("byte limit"))
                assertEquals(1, gateway.cancels)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun unsupportedResultHasItsColumnAndVendorTypeInTheNamedFailure() = runTest {
        val field = Field.newBuilder(" nested\u0007_column ", LegacySQLTypeName.RECORD,
            Field.newBuilder("value", LegacySQLTypeName.STRING).build()).build()
        val gateway = PageGateway { BigQueryResultPageMapper.map(listOf(field), emptyList(), null, 128) }
        val session = session(gateway, NoOpSink, StandardTestDispatcher(testScheduler))
        try {
            session.enter(context(QueryResultTarget.InlineStream(128, 65_536, 65_536), 61_000))
            val failed = assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult())
            assertEquals("UNSUPPORTED_RESULT_TYPE", failed.failureCode)
            assertEquals("Column 'nested_column' has unsupported result type 'RECORD'", failed.safeMessage)
            assertEquals(1, gateway.cancels)
        } finally {
            session.close()
        }
    }

    private fun session(gateway: BigQueryGateway, sink: RunResultSink, dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        BigQueryExecutionDriverSession(connector,
            MaterializedCredential(mapOf("service_account_json" to SecretCharacters.copyOf("{}".toCharArray()))),
            sink, gateway, Duration.ofMillis(1), dispatcher, Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
            BigQueryCallTimeoutPolicy(Duration.ofSeconds(30)), testTiming())

    private fun context(target: QueryResultTarget, deadlineEpochMillis: Long): StartExecutionContext {
        val unsigned = QueryRequest(1u, Sha256Digest("0".repeat(64)), "SELECT 1", emptyList(), target, false, emptyList())
        return StartExecutionContext(RunIdentity(bigQueryRunId), RunAssignment(bigQueryWorkspaceId, connector,
            unsigned.copy(canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(unsigned)), deadlineEpochMillis))
    }

    private class PageGateway(private val page: () -> BigQueryResultPage) : BigQueryGateway {
        var cancels = 0
        override suspend fun validateRawSql(sql: String, parameters: List<QueryParameter>, deadlineEpochMillis: Long): Unit = error("not used")
        override suspend fun start(job: BigQueryJobKey, sql: String, parameters: List<QueryParameter>, maximumRows: ULong, deadlineEpochMillis: Long) = Unit
        override suspend fun observe(job: BigQueryJobKey, deadlineEpochMillis: Long) = BigQueryJobSnapshot.Succeeded(0uL)
        override suspend fun readResultPage(job: BigQueryJobKey, pageToken: String?, maximumRows: Int, deadlineEpochMillis: Long) = page()
        override suspend fun cancel(job: BigQueryJobKey, deadlineEpochMillis: Long): Boolean {
            cancels++
            return true
        }
        override fun close() = Unit
    }

    private object NoOpSink : RunResultSink {
        override suspend fun emitInlineResult(batch: InlineResultBatch) = Unit
        override suspend fun openObjectUpload(objectKey: String, mediaType: String): ArtifactUploadSink = error("not used")
    }

    private object LimitSink : RunResultSink {
        override suspend fun emitInlineResult(batch: InlineResultBatch): Unit = throw ResultLimitExceededException(32uL)
        override suspend fun openObjectUpload(objectKey: String, mediaType: String): ArtifactUploadSink = error("not used")
    }

    private companion object {
        val connector = ConnectorRuntimeBinding(1u, bigQueryConnectorId, ConnectorType.BIGQUERY,
            mapOf("project_id" to ConnectorPropertyValue.Text("monomer-test"), "location" to ConnectorPropertyValue.Text("US")))
    }
}
