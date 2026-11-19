package cc.monomer.execution.runtime

import cc.monomer.execution.driver.spi.BinaryValue
import cc.monomer.execution.driver.spi.ResultLimitExceededException
import cc.monomer.execution.driver.spi.InlineResultBatch
import cc.monomer.execution.driver.spi.QueryResultTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class InlineResultSinkTest {
    @Test
    fun inlineFramesAreBoundedAndSequenceOrderedWithoutOpeningObjectStorage() =
        runTest {
            val emitted = mutableListOf<InlineResultBatch>()
            val sink =
                BoundedResultSink(
                    delivery = QueryResultTarget.InlineStream(4, 4, 4),
                    frameEmitter =
                        object : ResultFrameEmitter {
                            override suspend fun emitInlineResult(batch: InlineResultBatch) {
                                emitted += batch
                            }
                        },
                    artifactUploader = erroringUploader,
                    deadlineEpochMillis = 1_000,
                )

            val first = InlineResultBatch.fromPayload(1u, BinaryValue.copyOf(byteArrayOf(1)))
            val second = InlineResultBatch.fromPayload(2u, BinaryValue.copyOf(byteArrayOf(2, 3)))
            sink.emitInlineResult(first)
            sink.emitInlineResult(second)

            assertEquals(listOf(first, second), emitted)
            var sequenceRejected = false
            try {
                sink.emitInlineResult(InlineResultBatch.fromPayload(4u, BinaryValue.copyOf(byteArrayOf(4))))
            } catch (_: IllegalArgumentException) {
                sequenceRejected = true
            }
            assertTrue(sequenceRejected)

            var objectStorageRejected = false
            try {
                sink.openObjectUpload("artifacts/result.arrow", "application/vnd.apache.arrow.file")
            } catch (_: IllegalStateException) {
                objectStorageRejected = true
            }
            assertTrue(objectStorageRejected)
        }

    @Test
    fun oversizedInlineFrameIsRejectedBeforeEmission() =
        runTest {
            val sink =
                BoundedResultSink(
                    delivery = QueryResultTarget.InlineStream(4, 2, 2),
                    frameEmitter =
                        object : ResultFrameEmitter {
                            override suspend fun emitInlineResult(batch: InlineResultBatch) =
                                error("must not emit")
                        },
                    artifactUploader = erroringUploader,
                    deadlineEpochMillis = 1_000,
                )

            var oversizedRejected = false
            try {
                sink.emitInlineResult(
                    InlineResultBatch.fromPayload(1u, BinaryValue.copyOf(byteArrayOf(1, 2, 3))),
                )
            } catch (_: ResultLimitExceededException) {
                oversizedRejected = true
            }
            assertTrue(oversizedRejected)
        }

    private companion object {
        val erroringUploader =
            object : DirectArtifactUploader {
                override suspend fun open(
                    objectKey: String,
                    mediaType: String,
                    deadlineEpochMillis: Long,
                ): DirectArtifactUpload =
                    error("inline delivery must not open object storage")
            }
    }
}
