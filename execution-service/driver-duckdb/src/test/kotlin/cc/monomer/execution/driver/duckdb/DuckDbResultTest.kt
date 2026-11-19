package cc.monomer.execution.driver.duckdb

import cc.monomer.execution.driver.spi.*
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlin.test.*
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.ipc.SeekableReadChannel
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel

class DuckDbResultTest {
    @Test fun `all scalar parameters preserve values null and temporal precision in native validation and execution`() = runBlocking<Unit> {
        DuckDbFixture().use { fixture -> fixture.provider().use { provider ->
            val sql = "SELECT CAST(? AS VARCHAR) AS text_value, CAST(? AS BIGINT) AS int_value, CAST(? AS DOUBLE) AS double_value, CAST(? AS BOOLEAN) AS boolean_value, CAST(? AS BLOB) AS bytes_value, CAST(? AS DECIMAL(38,18)) AS decimal_value, CAST(? AS DATE) AS date_value, CAST(? AS TIMESTAMPTZ) AS timestamp_value, CAST(? AS VARCHAR) AS null_value"
            val parameters = listOf(QueryParameter.Text("sample", ""), QueryParameter.Integer64(42, ""), QueryParameter.Float64(1.25, ""), QueryParameter.BooleanValue(true, ""), QueryParameter.Bytes(BinaryValue.copyOf(byteArrayOf(1, 2)), ""), QueryParameter.Decimal("123456.123456789012345678", ""), QueryParameter.Date(LocalDate.parse("2026-09-30"), ""), QueryParameter.Timestamp(Instant.parse("1969-12-31T23:59:59.123456Z"), ""), QueryParameter.Null(NullQueryParameterType.TEXT, ""))
            sampleCredential().use { credential -> provider.validateRawSql(sampleConnector, credential, sql, parameters, System.currentTimeMillis() + 5000, timing(DriverOperation.VALIDATE)) }
            val batches = mutableListOf<InlineResultBatch>()
            val sink = object : RunResultSink {
                override suspend fun emitInlineResult(batch: InlineResultBatch) { batches += batch }
                override suspend fun openObjectUpload(objectKey: String, mediaType: String): ArtifactUploadSink = error("unused")
            }
            val initial = context(sql, 5000, QueryResultTarget.InlineStream(16, 65_536, 65_536))
            val unsigned = initial.assignment.request.copy(parameters = parameters)
            val request = unsigned.copy(canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(unsigned))
            val session = provider.openSession(sampleConnector, sampleCredential(), sink, timing(DriverOperation.QUERY))
            try {
                session.enter(initial.copy(assignment = initial.assignment.copy(request = request)))
                assertIs<DriverPhysicalResult.Succeeded>(session.awaitPhysicalResult())
                RootAllocator().use { allocator -> ArrowFileReader(SeekableReadChannel(ByteArrayReadableSeekableByteChannel(batches.single().arrowIpc.copyBytes())), allocator).use { reader ->
                    assertTrue(reader.loadNextBatch())
                    val vectors = reader.vectorSchemaRoot.fieldVectors
                    assertEquals("sample", vectors[0].getObject(0).toString())
                    assertEquals(42L, vectors[1].getObject(0))
                    assertEquals(1.25, vectors[2].getObject(0))
                    assertEquals(true, vectors[3].getObject(0))
                    assertContentEquals(byteArrayOf(1, 2), vectors[4].getObject(0) as ByteArray)
                    assertEquals("123456.123456789012345678", vectors[5].getObject(0).toString())
                    assertEquals(LocalDate.parse("2026-09-30").toEpochDay().toInt(), (vectors[6] as org.apache.arrow.vector.DateDayVector).get(0))
                    assertEquals(-876544L, (vectors[7] as org.apache.arrow.vector.TimeStampMicroTZVector).get(0))
                    assertNull(vectors[8].getObject(0))
                } }
            } finally { session.close() }
        } }
    }

    @Test fun `artifact row byte and cell limits abort instead of publishing a successful manifest`() = runBlocking<Unit> {
        DuckDbFixture().use { fixture -> fixture.provider().use { provider ->
            val cases = listOf(
                Triple("SELECT i FROM range(1000) t(i)", QueryResultTarget.ArtifactUpload("artifacts/sample.arrow", 10uL, 1_000_000uL, 16, 65_536, 65_536, 65_536), "RESULT_ROW_LIMIT_EXCEEDED"),
                Triple("SELECT i FROM range(1000) t(i)", QueryResultTarget.ArtifactUpload("artifacts/sample.arrow", 1000uL, 300uL, 16, 65_536, 65_536, 65_536), "RESULT_LIMIT_EXCEEDED"),
                Triple("SELECT repeat('x', 1024) AS value", QueryResultTarget.ArtifactUpload("artifacts/sample.arrow", 10uL, 1_000_000uL, 16, 65_536, 128, 65_536), "RESULT_LIMIT_EXCEEDED"),
            )
            cases.forEach { (sql, target, code) ->
                var aborted = false
                var completed = false
                val sink = object : RunResultSink {
                    override suspend fun emitInlineResult(batch: InlineResultBatch) = error("unused")
                    override suspend fun openObjectUpload(objectKey: String, mediaType: String) = object : ArtifactUploadSink {
                        override suspend fun append(payload: BinaryValue) = Unit
                        override suspend fun complete(rowCount: ULong, columnCount: Int): ArrowResultManifest { completed = true; error("must not publish") }
                        override suspend fun abort() { aborted = true }
                    }
                }
                val session = provider.openSession(sampleConnector, sampleCredential(), sink, timing(DriverOperation.QUERY))
                try {
                    session.enter(context(sql, 5000, target))
                    assertEquals(code, assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult()).failureCode)
                    assertTrue(aborted)
                    assertFalse(completed)
                } finally { session.close() }
            }
            assertIs<DriverPhysicalResult.Succeeded>(runQuery(provider, "SELECT 1", 5000, discardSink))
        } }
    }

    @Test fun `managed sort exhaustion fails within the resource boundary with no spill file`() = runBlocking<Unit> {
        DuckDbFixture().use { fixture -> fixture.provider().use { provider ->
            val outcome = runQuery(provider, "SELECT i FROM range(100000000) t(i) ORDER BY sin(i)", 30_000, discardSink)
            assertEquals("RESOURCE_EXHAUSTED", assertIs<DriverPhysicalResult.Failed>(outcome).failureCode)
            assertEquals(setOf("acme_cloud.duckdb"), java.nio.file.Files.list(fixture.database.parent).use { paths -> paths.map { it.fileName.toString() }.toList().toSet() })
        } }
    }

    @Test fun `large inline query streams bounded batches with backpressure and expected columns are checked before delivery`() = runBlocking<Unit> {
        DuckDbFixture().use { fixture -> fixture.provider().use { provider ->
            var rowCount = 0L
            var batches = 0
            val sink = object : RunResultSink {
                override suspend fun emitInlineResult(batch: InlineResultBatch) {
                    RootAllocator().use { allocator -> ArrowFileReader(SeekableReadChannel(ByteArrayReadableSeekableByteChannel(batch.arrowIpc.copyBytes())), allocator).use { reader ->
                        while (reader.loadNextBatch()) { assertTrue(reader.vectorSchemaRoot.rowCount <= 16); rowCount += reader.vectorSchemaRoot.rowCount }
                    } }
                    assertTrue(batch.arrowIpc.size <= 65_536)
                    batches++
                }
                override suspend fun openObjectUpload(objectKey: String, mediaType: String): ArtifactUploadSink = error("unused")
            }
            assertIs<DriverPhysicalResult.Succeeded>(runQuery(provider, "SELECT i FROM range(10000) t(i)", 5000, sink))
            assertEquals(10000, rowCount)
            assertTrue(batches > 500)
            val initial = context("SELECT 1 AS actual WHERE false", 5000, QueryResultTarget.InlineStream(16, 65_536, 65_536))
            val unsigned = initial.assignment.request.copy(expectedColumnNames = listOf("expected"))
            val request = unsigned.copy(canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(unsigned))
            val session = provider.openSession(sampleConnector, sampleCredential(), sink, timing(DriverOperation.QUERY))
            try {
                session.enter(initial.copy(assignment = initial.assignment.copy(request = request)))
                assertEquals("RESULT_COLUMN_MISMATCH", assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult()).failureCode)
                assertEquals(10000, rowCount)
            } finally { session.close() }
        } }
    }
}
