package cc.monomer.explore.application

import cc.monomer.execution.api.ExecutionArtifactStore
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking

internal class ExecutionArtifactRangeChannel(
    private val store: ExecutionArtifactStore,
    private val objectKey: String,
    private val expectedEtag: String,
    private val objectSizeBytes: Long,
    private val coroutineContext: CoroutineContext,
) : SeekableByteChannel {
    private var cursor = 0L
    private var open = true
    private var cachedStart = 0L
    private var cachedBytes = ByteArray(0)

    override fun read(destination: ByteBuffer): Int {
        coroutineContext.ensureActive()
        check(open) { "channel is closed" }
        if (cursor >= objectSizeBytes) return -1
        var read = 0
        while (destination.hasRemaining() && cursor < objectSizeBytes) {
            if (!isCached(cursor)) fetch(cursor)
            val offset = (cursor - cachedStart).toInt()
            val available = minOf(cachedBytes.size - offset, destination.remaining())
            destination.put(cachedBytes, offset, available)
            cursor += available
            read += available
        }
        return read
    }

    private fun isCached(position: Long): Boolean =
        cachedBytes.isNotEmpty() &&
            position >= cachedStart &&
            position < cachedStart + cachedBytes.size

    private fun fetch(requestedStart: Long) {
        val last =
            minOf(
                objectSizeBytes - 1,
                requestedStart + RANGE_COALESCE_BYTES - 1,
            )
        val first =
            if (last == objectSizeBytes - 1) {
                maxOf(0L, minOf(requestedStart, objectSizeBytes - RANGE_COALESCE_BYTES))
            } else {
                requestedStart
            }
        cachedBytes = runBlocking(coroutineContext) { store.readRange(objectKey, expectedEtag, first..last) }
        if (cachedBytes.size.toLong() != last - first + 1) throw IOException("Incomplete artifact range")
        cachedStart = first
    }

    override fun write(source: ByteBuffer): Int = throw NonWritableChannelException()

    override fun position(): Long = cursor

    override fun position(newPosition: Long): SeekableByteChannel {
        require(newPosition >= 0)
        cursor = newPosition
        return this
    }

    override fun size(): Long = objectSizeBytes

    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()

    override fun isOpen(): Boolean = open

    override fun close() {
        open = false
        cachedBytes = ByteArray(0)
    }

    companion object {
        private const val RANGE_COALESCE_BYTES = 256L * 1024
    }
}
