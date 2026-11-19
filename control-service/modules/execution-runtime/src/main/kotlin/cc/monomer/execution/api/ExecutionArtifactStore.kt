package cc.monomer.execution.api

import java.nio.ByteBuffer
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Control's read/cleanup boundary for an Execution-uploaded, Control-owned result object.
 * Multipart upload and its opaque upload id never cross this port.
 */
interface ExecutionArtifactStore {
    suspend fun head(objectKey: String): ExecutionArtifactHead? = head(objectKey, null)

    suspend fun head(objectKey: String, deadline: Instant?): ExecutionArtifactHead?

    suspend fun read(objectKey: String): ByteArray

    suspend fun readRange(
        objectKey: String,
        expectedEtag: String,
        byteRange: LongRange,
    ): ByteArray

    fun readChunks(
        objectKey: String,
        expectedEtag: String,
        chunkSizeBytes: Int,
    ): Flow<ByteBuffer>

    suspend fun presignGet(
        objectKey: String,
        expiresAt: Instant,
        fileName: String,
    ): ExecutionArtifactDownloadLink

    suspend fun delete(objectKey: String)
}

data class ExecutionArtifactHead(
    val objectKey: String,
    val byteSize: ULong,
    val etag: String,
    val observedAt: Instant,
) {
    init {
        require(objectKey.isNotBlank())
        require(byteSize > 0uL)
        require(etag.isNotBlank())
    }
}

class ExecutionArtifactDownloadLink(val url: String, val expiresAt: Instant) {
    override fun toString(): String = "ExecutionArtifactDownloadLink(url=<redacted>, expiresAt=$expiresAt)"
}
