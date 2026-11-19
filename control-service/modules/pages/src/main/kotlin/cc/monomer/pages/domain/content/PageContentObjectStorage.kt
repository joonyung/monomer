package cc.monomer.pages.domain.content

import java.net.URI
import java.nio.ByteBuffer
import java.time.Duration
import kotlinx.coroutines.flow.Flow

interface PageContentObjectStorage {
    suspend fun createMultipartUpload(objectRef: String, contentType: String): MultipartUpload

    suspend fun presignUploadPart(
        upload: MultipartUpload,
        partNumber: Int,
        contentLength: Long,
        signatureDuration: Duration,
    ): URI

    suspend fun completeMultipartUpload(upload: MultipartUpload, parts: List<CompletedPart>): MultipartCompletionResult

    suspend fun abortMultipartUpload(upload: MultipartUpload)

    suspend fun downloadBounded(objectRef: String, maximumBytes: Long): ObjectDownload?

    suspend fun writeImmutableIfAbsent(
        objectRef: String,
        contentLength: Long,
        contentHash: PageContentHash,
        content: Flow<ByteBuffer>,
    ): ImmutableWriteResult

    suspend fun head(objectRef: String): ObjectMetadata?

    suspend fun delete(objectRef: String)

    data class MultipartUpload(
        val objectRef: String,
        val uploadId: String,
    )

    data class CompletedPart(
        val partNumber: Int,
        val eTag: String,
    )

    data class ObjectDownload(
        val contentLength: Long,
        val contentType: String?,
        val content: Flow<ByteBuffer>,
        val discard: () -> Unit,
    )

    data class ObjectMetadata(
        val contentLength: Long,
        val contentHash: PageContentHash?,
    )

    sealed interface MultipartCompletionResult {
        data object Completed : MultipartCompletionResult
        data object PartsRejected : MultipartCompletionResult
        data object UploadMissing : MultipartCompletionResult
    }

    sealed interface ImmutableWriteResult {
        data object Written : ImmutableWriteResult
        data object AlreadyPresent : ImmutableWriteResult
        data class Collision(val existing: ObjectMetadata?) : ImmutableWriteResult
    }
}
