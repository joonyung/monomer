package cc.monomer.execution.driver.bigquery

import cc.monomer.execution.arrow.TabularColumn
import cc.monomer.execution.arrow.TabularPage
import cc.monomer.execution.arrow.TabularScalar
import cc.monomer.execution.arrow.TabularScalarType
import cc.monomer.execution.driver.spi.ArrowResultManifest
import cc.monomer.execution.driver.spi.ArtifactUploadSink
import cc.monomer.execution.driver.spi.BinaryValue
import cc.monomer.execution.driver.spi.InlineResultBatch
import cc.monomer.execution.driver.spi.DriverPhysicalResult
import cc.monomer.execution.driver.spi.QueryRequest
import cc.monomer.execution.driver.spi.QueryRequestCanonicalizer
import cc.monomer.execution.driver.spi.QueryResultTarget
import cc.monomer.execution.driver.spi.RunResultSink
import kotlinx.coroutines.test.runTest
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.ipc.SeekableReadChannel
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BigQueryArtifactResultTest {
    @Test
    fun declaredColumnMismatchFailsBeforeUploadIncludingEmptyResults() = runTest {
        listOf(page(1L), page(nextPageToken = null)).forEach { resultPage ->
            val sink = RecordingSink()
            val result = BigQueryResultEmitter(PageGateway(mapOf(null to resultPage)), sink, timing = testTiming()).emit(
                job = BigQueryJobKey("project", "US", "job"),
                snapshot = BigQueryJobSnapshot.Succeeded(0uL),
                request = queryRequest().copy(expectedColumnNames = listOf("wrong")),
                maximumRows = 10uL,
                deadlineEpochMillis = Long.MAX_VALUE,
                executionIsCurrent = { true },
            )
            assertEquals("RESULT_COLUMN_MISMATCH", assertIs<DriverPhysicalResult.Failed>(result).failureCode)
            assertEquals(0, sink.openCalls)
        }
    }

    @Test
    fun inlinePagesEmitArrowBatchesWithoutOpeningObjectStorage() =
        runTest {
            val sink = RecordingSink()
            val result =
                BigQueryResultEmitter(
                    PageGateway(
                        mapOf(
                            null to page(1L, nextPageToken = "next"),
                            "next" to page(2L),
                        ),
                    ),
                    sink,
                    timing = testTiming(),
                ).emit(
                    job = BigQueryJobKey("project", "US", "job"),
                    snapshot = BigQueryJobSnapshot.Succeeded(0uL),
                    request = inlineQueryRequest(),
                    maximumRows = 10uL,
                    deadlineEpochMillis = Long.MAX_VALUE,
                    executionIsCurrent = { true },
                )

            val succeeded = assertIs<DriverPhysicalResult.Succeeded>(result)
            assertEquals(null, succeeded.manifest)
            assertEquals(2, sink.inlineResults.size)
            assertEquals(listOf(1L, 2L), sink.inlineResults.flatMap(::readInlineFrame))
            assertEquals(0, sink.openCalls)
        }

    @Test
    fun emptyInlinePageStillProducesOneReadableArrowFile() =
        runTest {
            val sink = RecordingSink()
            val result =
                BigQueryResultEmitter(
                    PageGateway(mapOf(null to page(nextPageToken = null))),
                    sink,
                    timing = testTiming(),
                ).emit(
                    job = BigQueryJobKey("project", "US", "job"),
                    snapshot = BigQueryJobSnapshot.Succeeded(0uL),
                    request = inlineQueryRequest(),
                    maximumRows = 10uL,
                    deadlineEpochMillis = Long.MAX_VALUE,
                    executionIsCurrent = { true },
                )

            val succeeded = assertIs<DriverPhysicalResult.Succeeded>(result)
            assertEquals(0uL, succeeded.rowsProcessed)
            assertEquals(1, sink.inlineResults.size)
            assertEquals(emptyList(), sink.inlineResults.flatMap(::readInlineFrame))
            assertEquals(0, sink.openCalls)
        }

    @Test
    fun emptyRawSqlPageStillProducesAReadableArrowFile() =
        runTest {
            val sink = RecordingSink()
            val result =
                BigQueryResultEmitter(
                    PageGateway(mapOf(null to page(nextPageToken = null))),
                    sink,
                    timing = testTiming(),
                ).emit(
                    job = BigQueryJobKey("project", "US", "job"),
                    snapshot = BigQueryJobSnapshot.Succeeded(0uL),
                    request = queryRequest(),
                    maximumRows = 10uL,
                    deadlineEpochMillis = Long.MAX_VALUE,
                    executionIsCurrent = { true },
                )

            val succeeded = assertIs<DriverPhysicalResult.Succeeded>(result)
            assertEquals(0uL, succeeded.rowsProcessed)
            val bytes = sink.upload.bytes()
            try {
                RootAllocator().use { allocator ->
                    ArrowFileReader(
                        SeekableReadChannel(ByteArrayReadableSeekableByteChannel(bytes)),
                        allocator,
                    ).use { reader ->
                        assertEquals(0, reader.recordBlocks.size)
                        assertEquals("value", reader.vectorSchemaRoot.schema.fields.single().name)
                    }
                }
            } finally {
                bytes.fill(0)
            }
        }

    @Test
    fun rawSqlPagesProduceOneArrowFileAndManifest() =
        runTest {
            val gateway =
                PageGateway(
                    mapOf(
                        null to page(1L, 2L, nextPageToken = "next"),
                        "next" to page(3L),
                    ),
                )
            val sink = RecordingSink()
            val result =
                BigQueryResultEmitter(gateway, sink, timing = testTiming()).emit(
                    job = BigQueryJobKey("project", "US", "job"),
                    snapshot = BigQueryJobSnapshot.Succeeded(42uL),
                    request = queryRequest(),
                    maximumRows = 3uL,
                    deadlineEpochMillis = Long.MAX_VALUE,
                    executionIsCurrent = { true },
                )

            val succeeded = assertIs<DriverPhysicalResult.Succeeded>(result)
            assertEquals(3uL, succeeded.rowsProcessed)
            assertEquals(1, sink.upload.completed)
            assertEquals(0, sink.upload.aborted)
            val bytes = sink.upload.bytes()
            try {
                RootAllocator().use { allocator ->
                    ArrowFileReader(
                        SeekableReadChannel(ByteArrayReadableSeekableByteChannel(bytes)),
                        allocator,
                    ).use { reader ->
                        assertEquals(2, reader.recordBlocks.size)
                        val values = reader.recordBlocks.flatMap { block ->
                            reader.loadRecordBatch(block)
                            (reader.vectorSchemaRoot.getVector("value") as BigIntVector)
                                .let { vector -> (0 until vector.valueCount).map(vector::get) }
                        }
                        assertEquals(listOf(1L, 2L, 3L), values)
                    }
                }
            } finally {
                bytes.fill(0)
            }
            assertEquals(3uL, succeeded.manifest?.rowCount)
            assertEquals(1, succeeded.manifest?.columnCount)
        }

    @Test
    fun laterPageLimitFailureAbortsTheLiveMultipartUpload() =
        runTest {
            val gateway =
                PageGateway(
                    mapOf(
                        null to page(1L, nextPageToken = "next"),
                        "next" to page(2L, 3L),
                    ),
                )
            val sink = RecordingSink()
            val result =
                BigQueryResultEmitter(gateway, sink, timing = testTiming()).emit(
                    job = BigQueryJobKey("project", "US", "job"),
                    snapshot = BigQueryJobSnapshot.Succeeded(42uL),
                    request = queryRequest(),
                    maximumRows = 2uL,
                    deadlineEpochMillis = Long.MAX_VALUE,
                    executionIsCurrent = { true },
                )

            val failed = assertIs<DriverPhysicalResult.Failed>(result)
            assertEquals("RESULT_ROW_LIMIT_EXCEEDED", failed.failureCode)
            assertEquals(1, sink.upload.aborted)
            assertEquals(0, sink.upload.completed)
        }

    private fun queryRequest(): QueryRequest {
        val unsigned =
            QueryRequest(
                validateRawSqlBeforeRun = false,
                expectedColumnNames = emptyList(),
                schemaVersion = 1u,
                canonicalRequestDigest = digest('0'),
                sql = "SELECT value FROM dataset.table",
                parameters = emptyList(),
                resultTarget =
                    QueryResultTarget.ArtifactUpload(
                        objectKey = "artifacts/workspaces/w/explore/runs/r/result.arrow",
                        maximumArtifactRows = 10uL,
                        maximumArtifactBytes = 4_194_304uL,
                        maximumRecordBatchRows = 128,
                        maximumRecordBatchBytes = 64 * 1024,
                        maximumCellBytes = 64 * 1024,
                        maximumMultipartPartBytes = 64 * 1024,
                    ),
            )
        return unsigned.copy(
            canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(unsigned),
        )
    }

    private fun inlineQueryRequest(): QueryRequest {
        val unsigned =
            QueryRequest(
                validateRawSqlBeforeRun = false,
                expectedColumnNames = emptyList(),
                schemaVersion = 1u,
                canonicalRequestDigest = digest('0'),
                sql = "SELECT value FROM dataset.table",
                parameters = emptyList(),
                resultTarget =
                    QueryResultTarget.InlineStream(
                        maximumRecordBatchRows = 128,
                        maximumRecordBatchBytes = 64 * 1024,
                        maximumCellBytes = 64 * 1024,
                    ),
            )
        return unsigned.copy(
            canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(unsigned),
        )
    }

    private fun page(
        vararg values: Long,
        nextPageToken: String? = null,
    ): BigQueryResultPage =
        BigQueryResultPage(
            page =
                TabularPage(
                    columns = listOf(TabularColumn("value", TabularScalarType.INT64, false)),
                    rows = values.map { value -> listOf(TabularScalar.Integer64(value)) },
                ),
            nextPageToken = nextPageToken,
        )

    private class PageGateway(
        private val pages: Map<String?, BigQueryResultPage>,
    ) : BigQueryGateway {
        override suspend fun validateRawSql(
            sql: String,
            parameters: List<cc.monomer.execution.driver.spi.QueryParameter>,
            deadlineEpochMillis: Long,
        ): Unit = error("not used")

        override suspend fun start(
            job: BigQueryJobKey,
            sql: String,
            parameters: List<cc.monomer.execution.driver.spi.QueryParameter>,
            maximumRows: ULong,
            deadlineEpochMillis: Long,
        ) = error("not used")

        override suspend fun observe(
            job: BigQueryJobKey,
            deadlineEpochMillis: Long,
        ) = error("not used")

        override suspend fun readResultPage(
            job: BigQueryJobKey,
            pageToken: String?,
            maximumRows: Int,
            deadlineEpochMillis: Long,
        ): BigQueryResultPage = pages.getValue(pageToken)

        override suspend fun cancel(job: BigQueryJobKey, deadlineEpochMillis: Long) = true

        override fun close() = Unit
    }

    private class RecordingSink : RunResultSink {
        val upload = RecordingUpload()
        val inlineResults = mutableListOf<InlineResultBatch>()
        var openCalls = 0

        override suspend fun emitInlineResult(batch: InlineResultBatch) {
            inlineResults += batch
        }

        override suspend fun openObjectUpload(
            objectKey: String,
            mediaType: String,
        ): ArtifactUploadSink {
            openCalls += 1
            return upload
        }
    }

    private class RecordingUpload : ArtifactUploadSink {
        private val chunks = mutableListOf<ByteArray>()
        var completed = 0
        var aborted = 0

        override suspend fun append(payload: BinaryValue) {
            chunks += payload.copyBytes()
        }

        override suspend fun complete(rowCount: ULong, columnCount: Int): ArrowResultManifest {
            completed += 1
            return ArrowResultManifest(
                objectKey = "artifacts/workspaces/w/explore/runs/r/result.arrow",
                byteSize = chunks.sumOf { bytes -> bytes.size }.toULong(),
                objectEtag = "etag",
                rowCount = rowCount,
                columnCount = columnCount,
                mediaType = ArrowResultManifest.ARROW_FILE_MEDIA_TYPE,
            )
        }

        override suspend fun abort() {
            aborted += 1
        }

        fun bytes(): ByteArray {
            val combined = ByteArray(chunks.sumOf { bytes -> bytes.size })
            var offset = 0
            chunks.forEach { bytes ->
                bytes.copyInto(combined, offset)
                offset += bytes.size
            }
            return combined
        }
    }

    private fun digest(character: Char) =
        cc.monomer.execution.driver.spi.Sha256Digest(character.toString().repeat(64))

    private fun readInlineFrame(batch: InlineResultBatch): List<Long> {
        val bytes = batch.arrowIpc.copyBytes()
        try {
            RootAllocator().use { allocator ->
                ArrowFileReader(
                    SeekableReadChannel(ByteArrayReadableSeekableByteChannel(bytes)),
                    allocator,
                ).use { reader ->
                    assertEquals("value", reader.vectorSchemaRoot.schema.fields.single().name)
                    assertTrue(reader.recordBlocks.size <= 1)
                    return reader.recordBlocks.flatMap { block ->
                        reader.loadRecordBatch(block)
                        val vector = reader.vectorSchemaRoot.getVector("value") as BigIntVector
                        (0 until vector.valueCount).map(vector::get)
                    }
                }
            }
        } finally {
            bytes.fill(0)
        }
    }
}
