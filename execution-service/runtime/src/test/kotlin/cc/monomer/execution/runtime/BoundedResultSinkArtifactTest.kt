package cc.monomer.execution.runtime

import cc.monomer.execution.driver.spi.ArrowResultManifest
import cc.monomer.execution.driver.spi.BinaryValue
import cc.monomer.execution.driver.spi.ResultLimitExceededException
import cc.monomer.execution.driver.spi.QueryResultTarget
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class BoundedResultSinkArtifactTest {
    @Test
    fun completeFlushesMultipartPartsAndReturnsManifest() =
        runTest {
            val uploader = RecordingUploader()
            val sink =
                BoundedResultSink(
                    delivery = artifactTarget(),
                    frameEmitter = NoopFrameEmitter,
                    artifactUploader = uploader,
                    deadlineEpochMillis = 1_000,
                )
            val upload = sink.openObjectUpload(OBJECT_KEY, ArrowResultManifest.ARROW_FILE_MEDIA_TYPE)
            assertEquals(1_000, uploader.capturedDeadlineEpochMillis)
            upload.append(BinaryValue.copyOf(byteArrayOf(1, 2, 3, 4)))
            upload.append(BinaryValue.copyOf(byteArrayOf(5, 6)))
            val manifest = upload.complete(rowCount = 2uL, columnCount = 1)

            assertEquals(manifest, upload.complete(rowCount = 2uL, columnCount = 1))
            assertEquals(2, uploader.upload.appended.size)
            assertContentEquals(byteArrayOf(1, 2, 3, 4), uploader.upload.appended[0])
            assertContentEquals(byteArrayOf(5, 6), uploader.upload.appended[1])
            assertEquals(1, uploader.upload.completed)
            assertEquals(6uL, manifest.byteSize)
        }

    @Test
    fun abortStopsAnOpenMultipartUpload() =
        runTest {
            val uploader = RecordingUploader()
            val sink =
                BoundedResultSink(
                    delivery = artifactTarget(),
                    frameEmitter = NoopFrameEmitter,
                    artifactUploader = uploader,
                    deadlineEpochMillis = 1_000,
                )
            val upload = sink.openObjectUpload(OBJECT_KEY, ArrowResultManifest.ARROW_FILE_MEDIA_TYPE)
            upload.append(BinaryValue.copyOf(byteArrayOf(1, 2)))
            upload.abort()

            assertEquals(1, uploader.upload.aborted)
            assertFailsWith<IllegalStateException> {
                upload.complete(rowCount = 1uL, columnCount = 1)
            }
        }

    @Test
    fun artifactAndMultipartLimitsFailBeforeWriting() = runTest {
        val uploader = RecordingUploader()
        val sink = BoundedResultSink(artifactTarget(), NoopFrameEmitter, uploader, 1_000)
        val upload = sink.openObjectUpload(OBJECT_KEY, ArrowResultManifest.ARROW_FILE_MEDIA_TYPE)
        assertFailsWith<ResultLimitExceededException> { upload.append(BinaryValue.copyOf(ByteArray(5))) }
        assertEquals(0, uploader.upload.appended.size)
        upload.append(BinaryValue.copyOf(ByteArray(4)))
        upload.append(BinaryValue.copyOf(ByteArray(4)))
        assertFailsWith<ResultLimitExceededException> { upload.append(BinaryValue.copyOf(ByteArray(3))) }
        assertEquals(2, uploader.upload.appended.size)
        upload.abort()
        assertEquals(1, uploader.upload.aborted)
        assertEquals(0, uploader.upload.completed)
    }

    private class RecordingUploader : DirectArtifactUploader {
        val upload = RecordingUpload()
        var capturedDeadlineEpochMillis: Long? = null

        override suspend fun open(
            objectKey: String,
            mediaType: String,
            deadlineEpochMillis: Long,
        ): DirectArtifactUpload {
            capturedDeadlineEpochMillis = deadlineEpochMillis
            return upload
        }
    }

    private class RecordingUpload : DirectArtifactUpload {
        val appended = mutableListOf<ByteArray>()
        var completed = 0
        var aborted = 0

        override suspend fun append(sequence: ULong, payload: BinaryValue) {
            appended += payload.copyBytes()
        }

        override suspend fun complete(rowCount: ULong, columnCount: Int): ArrowResultManifest {
            completed += 1
            return ArrowResultManifest(
                objectKey = OBJECT_KEY,
                byteSize = appended.sumOf { bytes -> bytes.size }.toULong(),
                objectEtag = "etag",
                rowCount = rowCount,
                columnCount = columnCount,
                mediaType = ArrowResultManifest.ARROW_FILE_MEDIA_TYPE,
            )
        }

        override suspend fun abort() {
            aborted += 1
        }
    }

    private object NoopFrameEmitter : ResultFrameEmitter {
        override suspend fun emitInlineResult(
            batch: cc.monomer.execution.driver.spi.InlineResultBatch,
        ) = Unit
    }

    private fun artifactTarget() =
        QueryResultTarget.ArtifactUpload(
            objectKey = OBJECT_KEY,
            maximumArtifactRows = 10u,
            maximumArtifactBytes = 10u,
            maximumRecordBatchRows = 4,
            maximumRecordBatchBytes = 4,
            maximumCellBytes = 4,
            maximumMultipartPartBytes = 4,
        )

    private companion object {
        const val OBJECT_KEY = "artifacts/workspaces/w/explore/runs/r/result.arrow"
    }
}
