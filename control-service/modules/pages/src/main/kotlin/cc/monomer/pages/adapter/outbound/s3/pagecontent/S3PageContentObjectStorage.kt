package cc.monomer.pages.adapter.outbound.s3.pagecontent

import cc.monomer.pages.domain.content.PageContentHash
import cc.monomer.pages.domain.content.PageContentObjectStorage
import java.net.URI
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletionException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.asPublisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.core.async.SdkPublisher
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest

data class PageContentObjectStorageConfiguration(
    val endpoint: URI,
    val region: String,
    val bucket: String,
    val rootDirectory: String,
) {
    init {
        require(region.isNotBlank()) { "page content object-store region must not be blank" }
        require(bucket.isNotBlank()) { "page content object-store bucket must not be blank" }
        require(!rootDirectory.startsWith("/") && "//" !in rootDirectory && ".." !in rootDirectory) {
            "page content object-store root directory is invalid"
        }
    }
}

class S3PageContentObjectStorage(
    private val s3Client: S3AsyncClient,
    private val presigner: S3Presigner,
    private val bucket: String,
    private val rootDirectory: String,
) : PageContentObjectStorage, AutoCloseable {
    override suspend fun createMultipartUpload(
        objectRef: String,
        contentType: String,
    ): PageContentObjectStorage.MultipartUpload {
        val response = s3Client.createMultipartUpload(
            CreateMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(resolveKey(objectRef))
                .contentType(contentType)
                .build(),
        ).await()
        return PageContentObjectStorage.MultipartUpload(objectRef, response.uploadId())
    }

    override suspend fun presignUploadPart(
        upload: PageContentObjectStorage.MultipartUpload,
        partNumber: Int,
        contentLength: Long,
        signatureDuration: Duration,
    ): URI {
        require(partNumber > 0) { "part number must be positive" }
        require(contentLength > 0) { "part content length must be positive" }
        require(!signatureDuration.isNegative && !signatureDuration.isZero) { "signature duration must be positive" }
        val request = UploadPartRequest.builder()
            .bucket(bucket)
            .key(resolveKey(upload.objectRef))
            .uploadId(upload.uploadId)
            .partNumber(partNumber)
            .contentLength(contentLength)
            .build()
        return presigner.presignUploadPart(
            UploadPartPresignRequest.builder()
                .signatureDuration(signatureDuration)
                .uploadPartRequest(request)
                .build(),
        ).url().toURI()
    }

    override suspend fun completeMultipartUpload(
        upload: PageContentObjectStorage.MultipartUpload,
        parts: List<PageContentObjectStorage.CompletedPart>,
    ): PageContentObjectStorage.MultipartCompletionResult {
        require(parts.isNotEmpty()) { "completed multipart upload requires parts" }
        val completedParts = parts.map { part ->
            CompletedPart.builder().partNumber(part.partNumber).eTag(part.eTag).build()
        }
        return try {
            s3Client.completeMultipartUpload(
                CompleteMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(resolveKey(upload.objectRef))
                    .uploadId(upload.uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                    .build(),
            ).await()
            PageContentObjectStorage.MultipartCompletionResult.Completed
        } catch (exception: Throwable) {
            val cause = exception.unwrapCompletion()
            when ((cause as? S3Exception)?.awsErrorDetails()?.errorCode()) {
                "InvalidPart", "InvalidPartOrder", "EntityTooSmall" ->
                    PageContentObjectStorage.MultipartCompletionResult.PartsRejected
                "NoSuchUpload" -> PageContentObjectStorage.MultipartCompletionResult.UploadMissing
                else -> throw cause
            }
        }
    }

    override suspend fun abortMultipartUpload(upload: PageContentObjectStorage.MultipartUpload) {
        try {
            s3Client.abortMultipartUpload(
                AbortMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(resolveKey(upload.objectRef))
                    .uploadId(upload.uploadId)
                    .build(),
            ).await()
        } catch (exception: Throwable) {
            if (!exception.isNotFound()) throw exception.unwrapCompletion()
        }
    }

    override suspend fun downloadBounded(
        objectRef: String,
        maximumBytes: Long,
    ): PageContentObjectStorage.ObjectDownload? {
        require(maximumBytes > 0) { "maximum download size must be positive" }
        val request = GetObjectRequest.builder().bucket(bucket).key(resolveKey(objectRef)).build()
        return try {
            val publisher = s3Client.getObject(request, AsyncResponseTransformer.toPublisher()).await()
            val contentLength = publisher.response().contentLength()
            if (contentLength > maximumBytes) {
                publisher.cancelUnread()
                throw PageContentObjectSizeLimitExceeded(contentLength, maximumBytes)
            }
            PageContentObjectStorage.ObjectDownload(
                contentLength = contentLength,
                contentType = publisher.response().contentType(),
                content = publisher.asFlow(),
                discard = { publisher.cancelUnread() },
            )
        } catch (exception: Throwable) {
            if (exception.isNotFound()) null else throw exception.unwrapCompletion()
        }
    }

    override suspend fun writeImmutableIfAbsent(
        objectRef: String,
        contentLength: Long,
        contentHash: PageContentHash,
        content: Flow<ByteBuffer>,
    ): PageContentObjectStorage.ImmutableWriteResult {
        require(contentLength > 0) { "immutable object content length must be positive" }
        val request = PutObjectRequest.builder()
            .bucket(bucket)
            .key(resolveKey(objectRef))
            .contentLength(contentLength)
            .metadata(mapOf(SHA_256_METADATA_KEY to contentHash.value))
            .ifNoneMatch("*")
            .build()
        return try {
            s3Client.putObject(request, AsyncRequestBody.fromPublisher(content.asPublisher())).await()
            PageContentObjectStorage.ImmutableWriteResult.Written
        } catch (exception: Throwable) {
            val cause = exception.unwrapCompletion()
            if (!cause.isConditionalWriteCollision()) throw cause
            val existing = head(objectRef)
            if (existing?.contentHash == contentHash && existing.contentLength == contentLength) {
                PageContentObjectStorage.ImmutableWriteResult.AlreadyPresent
            } else {
                PageContentObjectStorage.ImmutableWriteResult.Collision(existing)
            }
        }
    }

    override suspend fun head(objectRef: String): PageContentObjectStorage.ObjectMetadata? = try {
        val response = s3Client.headObject(
            HeadObjectRequest.builder().bucket(bucket).key(resolveKey(objectRef)).build(),
        ).await()
        PageContentObjectStorage.ObjectMetadata(
            contentLength = response.contentLength(),
            contentHash = response.metadata()[SHA_256_METADATA_KEY]?.let(::PageContentHash),
        )
    } catch (exception: Throwable) {
        if (exception.isNotFound()) null else throw exception.unwrapCompletion()
    }

    override suspend fun delete(objectRef: String) {
        s3Client.deleteObject(
            DeleteObjectRequest.builder().bucket(bucket).key(resolveKey(objectRef)).build(),
        ).await()
    }

    override fun close() {
        presigner.close()
        s3Client.close()
    }

    private fun SdkPublisher<ByteBuffer>.cancelUnread() {
        subscribe(object : Subscriber<ByteBuffer> {
            override fun onSubscribe(subscription: Subscription) = subscription.cancel()
            override fun onNext(item: ByteBuffer) = Unit
            override fun onError(throwable: Throwable) = Unit
            override fun onComplete() = Unit
        })
    }

    private fun resolveKey(objectRef: String): String =
        if (rootDirectory.isBlank()) objectRef else "$rootDirectory/$objectRef"

    private fun Throwable.unwrapCompletion(): Throwable =
        if (this is CompletionException && cause != null) cause!! else this

    private fun Throwable.isNotFound(): Boolean = when (val cause = unwrapCompletion()) {
        is NoSuchKeyException -> true
        is S3Exception -> cause.statusCode() == 404
        else -> false
    }

    private fun Throwable.isConditionalWriteCollision(): Boolean =
        (unwrapCompletion() as? S3Exception)?.statusCode() in setOf(409, 412)

    companion object {
        private const val SHA_256_METADATA_KEY = "sha256"

        fun create(
            configuration: PageContentObjectStorageConfiguration,
            credentialsProvider: AwsCredentialsProvider,
        ): S3PageContentObjectStorage {
            val region = Region.of(configuration.region)
            val serviceConfiguration = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build()
            val s3Client = S3AsyncClient.builder()
                .endpointOverride(configuration.endpoint)
                .region(region)
                .credentialsProvider(credentialsProvider)
                .serviceConfiguration(serviceConfiguration)
                .build()
            val presigner = try {
                S3Presigner.builder()
                    .endpointOverride(configuration.endpoint)
                    .region(region)
                    .credentialsProvider(credentialsProvider)
                    .serviceConfiguration(serviceConfiguration)
                    .build()
            } catch (failure: Throwable) {
                s3Client.close()
                throw failure
            }
            return S3PageContentObjectStorage(
                s3Client = s3Client,
                presigner = presigner,
                bucket = configuration.bucket,
                rootDirectory = configuration.rootDirectory.trimEnd('/'),
            )
        }
    }
}

class PageContentObjectSizeLimitExceeded(
    val actualBytes: Long,
    val maximumBytes: Long,
) : RuntimeException("Page content object exceeds the bounded download limit")
