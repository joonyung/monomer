package cc.monomer.execution.runtime

import cc.monomer.execution.driver.spi.ArrowResultManifest
import cc.monomer.execution.driver.spi.ArtifactUploadSink
import cc.monomer.execution.driver.spi.BinaryValue
import cc.monomer.execution.driver.spi.InlineResultBatch
import cc.monomer.execution.driver.spi.QueryResultTarget
import cc.monomer.execution.driver.spi.RunResultSink
import cc.monomer.execution.driver.spi.ResultLimitExceededException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream

interface ResultFrameEmitter {
    suspend fun emitInlineResult(batch: InlineResultBatch)
}

interface DirectArtifactUploader {
    suspend fun open(
        objectKey: String,
        mediaType: String,
        deadlineEpochMillis: Long,
    ): DirectArtifactUpload
}

interface DirectArtifactUpload {
    suspend fun append(
        sequence: ULong,
        payload: BinaryValue,
    )

    suspend fun complete(
        rowCount: ULong,
        columnCount: Int,
    ): ArrowResultManifest

    suspend fun abort()
}

class BoundedResultSink(
    private val delivery: QueryResultTarget,
    private val frameEmitter: ResultFrameEmitter,
    private val artifactUploader: DirectArtifactUploader,
    private val deadlineEpochMillis: Long,
) : RunResultSink {
    private val mutex = Mutex()
    private var expectedSequence = 1uL
    private var streamFailed = false
    private var objectUpload: BoundedArtifactUpload? = null

    init {
        require(deadlineEpochMillis > 0)
    }

    override suspend fun emitInlineResult(batch: InlineResultBatch) {
        require(delivery is QueryResultTarget.InlineStream) {
            "inline result batches require INLINE_STREAM delivery"
        }
        if (batch.arrowIpc.size > delivery.maximumRecordBatchBytes) {
            throw ResultLimitExceededException(delivery.maximumRecordBatchBytes.toULong())
        }
        mutex.withLock {
            check(!streamFailed) { "inline result stream is poisoned after an emission failure" }
            require(batch.sequence == expectedSequence) {
                "inline result sequence must be strictly increasing from one"
            }
            try {
                frameEmitter.emitInlineResult(batch)
                expectedSequence += 1uL
            } catch (failure: Throwable) {
                streamFailed = true
                throw failure
            }
        }
    }

    override suspend fun openObjectUpload(
        objectKey: String,
        mediaType: String,
    ): ArtifactUploadSink {
        val artifact = delivery as? QueryResultTarget.ArtifactUpload
            ?: error("artifact upload is unavailable for inline delivery")
        require(objectKey == artifact.objectKey) { "artifact key differs from the assignment" }
        require(mediaType == ArrowResultManifest.ARROW_FILE_MEDIA_TYPE) {
            "query artifacts must use the Arrow IPC file media type"
        }
        return mutex.withLock {
            objectUpload ?: BoundedArtifactUpload(
                objectKey = objectKey,
                mediaType = mediaType,
                maximumBytes = artifact.maximumArtifactBytes,
                maximumPartBytes = artifact.maximumMultipartPartBytes,
                directUpload = artifactUploader.open(objectKey, mediaType, deadlineEpochMillis),
            ).also { objectUpload = it }
        }
    }
}

private class BoundedArtifactUpload(
    private val objectKey: String,
    private val mediaType: String,
    private val maximumBytes: ULong,
    private val maximumPartBytes: Int,
    private val directUpload: DirectArtifactUpload,
) : ArtifactUploadSink {
    private val mutex = Mutex()
    private val partBuffer = ByteArrayOutputStream(maximumPartBytes)
    private var byteSize = 0uL
    private var sequence = 0uL
    private var terminal: ArrowResultManifest? = null
    private var aborted = false

    override suspend fun append(payload: BinaryValue) {
        mutex.withLock {
            check(terminal == null && !aborted) { "artifact upload is no longer writable" }
            require(payload.size > 0)
            if (payload.size > maximumPartBytes) throw ResultLimitExceededException(maximumPartBytes.toULong())
            val bytes = payload.copyBytes()
            try {
                if (bytes.size.toULong() > maximumBytes - byteSize) throw ResultLimitExceededException(maximumBytes)
                val nextSize = byteSize + bytes.size.toULong()
                var offset = 0
                while (offset < bytes.size) {
                    val writable = maximumPartBytes - partBuffer.size()
                    val copied = minOf(writable, bytes.size - offset)
                    partBuffer.write(bytes, offset, copied)
                    offset += copied
                    if (partBuffer.size() == maximumPartBytes) flushPart()
                }
                byteSize = nextSize
            } finally {
                bytes.fill(0)
            }
        }
    }

    override suspend fun complete(rowCount: ULong, columnCount: Int): ArrowResultManifest =
        mutex.withLock {
            terminal?.let { manifest ->
                require(manifest.rowCount == rowCount && manifest.columnCount == columnCount)
                return@withLock manifest
            }
            check(!aborted)
            require(columnCount > 0)
            if (partBuffer.size() > 0) flushPart()
            require(sequence > 0uL) { "an Arrow file must contain at least one part" }
            val manifest = directUpload.complete(rowCount, columnCount)
            require(manifest.objectKey == objectKey)
            require(manifest.byteSize == byteSize)
            require(manifest.rowCount == rowCount)
            require(manifest.columnCount == columnCount)
            require(manifest.mediaType == mediaType)
            terminal = manifest
            manifest
        }

    override suspend fun abort() {
        mutex.withLock {
            if (terminal != null || aborted) return
            aborted = true
            directUpload.abort()
        }
    }

    private suspend fun flushPart() {
        val bytes = partBuffer.toByteArray()
        require(bytes.isNotEmpty())
        try {
            sequence += 1uL
            directUpload.append(sequence, BinaryValue.copyOf(bytes))
            partBuffer.reset()
        } finally {
            bytes.fill(0)
        }
    }
}
