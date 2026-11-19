package cc.monomer.execution.storage.s3

import cc.monomer.execution.driver.spi.ArrowResultManifest
import cc.monomer.execution.driver.spi.BinaryValue
import cc.monomer.execution.runtime.DirectArtifactUpload
import cc.monomer.execution.runtime.DirectArtifactUploader
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

enum class S3ArtifactCredentialMode {
    WORKLOAD,
    LOCAL_TEST_STATIC,
}

data class S3ArtifactStoreSettings(
    val endpoint: URI,
    val region: String,
    val bucket: String,
    val prefix: String,
    val credentialMode: S3ArtifactCredentialMode,
    val accessKey: String?,
    val secretKey: String?,
) {
    init {
        require(endpoint.scheme == "https" ||
            (endpoint.scheme == "http" && endpoint.host in setOf("localhost", "127.0.0.1", "[::1]"))) {
            "artifact store endpoint must use HTTPS unless it addresses a loopback host"
        }
        require(!endpoint.host.isNullOrBlank()) {
            "artifact store endpoint must contain a host"
        }
        require(endpoint.path.isEmpty() || endpoint.path == "/") {
            "artifact store endpoint must be an origin without a path"
        }
        require(endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null) {
            "artifact store endpoint must not contain query or fragment"
        }
        require(region.isNotBlank())
        require(bucket.matches(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"))) {
            "artifact store bucket is not a valid S3 bucket name"
        }
        require(prefix == prefix.trim() && prefix.isNotBlank())
        require(prefix.endsWith('/'))
        require(!prefix.startsWith('/'))
        when (credentialMode) {
            S3ArtifactCredentialMode.WORKLOAD -> require(accessKey == null && secretKey == null) {
                "artifact store WORKLOAD mode must not contain static credentials"
            }
            S3ArtifactCredentialMode.LOCAL_TEST_STATIC -> {
                require(endpoint.host in setOf("localhost", "127.0.0.1", "[::1]")) {
                    "artifact store LOCAL_TEST_STATIC mode requires a loopback endpoint"
                }
                require(!accessKey.isNullOrBlank() && !secretKey.isNullOrBlank()) {
                    "artifact store LOCAL_TEST_STATIC mode requires both access key and secret key"
                }
            }
        }
    }

    override fun toString(): String =
        "S3ArtifactStoreSettings(" +
            "endpoint=$endpoint, region=$region, bucket=$bucket, prefix=$prefix, " +
            "credentialMode=$credentialMode, accessKey=[REDACTED], secretKey=[REDACTED])"
}

class S3DirectArtifactUploader(
    private val settings: S3ArtifactStoreSettings,
    private val blockingDispatcher: CoroutineDispatcher,
    private val clock: Clock,
    private val client: S3Client,
) : DirectArtifactUploader, AutoCloseable {
    override suspend fun open(
        objectKey: String,
        mediaType: String,
        deadlineEpochMillis: Long,
    ): DirectArtifactUpload {
        require(objectKey.startsWith(settings.prefix)) {
            "artifact key is outside the configured result prefix"
        }
        require(objectKey.substringAfter(settings.prefix).isNotBlank())
        require(!objectKey.contains(".."))
        require(mediaType == ArrowResultManifest.ARROW_FILE_MEDIA_TYPE)
        val request =
            CreateMultipartUploadRequest.builder()
                .bucket(settings.bucket)
                .key(objectKey)
                .contentType(mediaType)
                .overrideConfiguration(
                    s3RequestOverride(
                        remainingRequestMillis(deadlineEpochMillis, clock),
                    ),
                )
                .build()
        // A dispatcher-changing withContext discards its result once the caller is cancelled; the outer
        // NonCancellable context keeps the created upload id so it can be aborted.
        val response =
            withContext(NonCancellable) {
                withContext(blockingDispatcher) {
                    client.createMultipartUpload(request)
                }
            }
        val upload =
            S3DirectArtifactUpload(
                client = client,
                bucket = settings.bucket,
                objectKey = objectKey,
                uploadId = requireNotNull(response.uploadId()),
                mediaType = mediaType,
                blockingDispatcher = blockingDispatcher,
                clock = clock,
                deadlineEpochMillis = deadlineEpochMillis,
            )
        if (!currentCoroutineContext().isActive) {
            upload.abort()
        }
        currentCoroutineContext().ensureActive()
        return upload
    }

    override fun close() {
        client.close()
    }

    companion object {
        fun create(
            settings: S3ArtifactStoreSettings,
            blockingDispatcher: CoroutineDispatcher,
            clock: Clock,
        ): S3DirectArtifactUploader =
            S3DirectArtifactUploader(
                settings = settings,
                blockingDispatcher = blockingDispatcher,
                clock = clock,
                client = createClient(settings),
            )

        private fun createClient(settings: S3ArtifactStoreSettings): S3Client =
            S3Client.builder()
                .endpointOverride(settings.endpoint)
                .region(Region.of(settings.region))
                .apply {
                    if (settings.credentialMode == S3ArtifactCredentialMode.LOCAL_TEST_STATIC) {
                        credentialsProvider(
                            StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(
                                    requireNotNull(settings.accessKey),
                                    requireNotNull(settings.secretKey),
                                ),
                            ),
                        )
                    }
                }
                .serviceConfiguration(
                    S3Configuration.builder().pathStyleAccessEnabled(true).build(),
                )
                .build()
    }
}

private class S3DirectArtifactUpload(
    private val client: S3Client,
    private val bucket: String,
    private val objectKey: String,
    private val uploadId: String,
    private val mediaType: String,
    private val blockingDispatcher: CoroutineDispatcher,
    private val clock: Clock,
    private val deadlineEpochMillis: Long,
) : DirectArtifactUpload {
    private val parts = mutableListOf<CompletedPart>()
    private var byteSize = 0uL
    private var completed = false
    private var aborted = false

    override suspend fun append(
        sequence: ULong,
        payload: BinaryValue,
    ) {
        check(!completed && !aborted)
        require(sequence == parts.size.toULong() + 1uL)
        require(payload.size > 0)
        val bytes = payload.copyBytes()
        val response =
            try {
                withContext(blockingDispatcher) {
                    client.uploadPart(
                        UploadPartRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .uploadId(uploadId)
                            .partNumber(sequence.toInt())
                            .contentLength(payload.size.toLong())
                            .overrideConfiguration(
                                s3RequestOverride(
                                    remainingRequestMillis(deadlineEpochMillis, clock),
                                ),
                            )
                            .build(),
                        RequestBody.fromBytes(bytes),
                    )
                }
            } finally {
                bytes.fill(0)
            }
        parts +=
            CompletedPart.builder()
                .partNumber(sequence.toInt())
                .eTag(requireNotNull(response.eTag()))
                .build()
        byteSize += payload.size.toULong()
    }

    override suspend fun complete(
        rowCount: ULong,
        columnCount: Int,
    ): ArrowResultManifest {
        check(!completed && !aborted)
        require(parts.isNotEmpty())
        val response =
            withContext(blockingDispatcher) {
                client.completeMultipartUpload(
                    CompleteMultipartUploadRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .uploadId(uploadId)
                        .multipartUpload(
                            CompletedMultipartUpload.builder().parts(parts).build(),
                        )
                        .overrideConfiguration(
                            s3RequestOverride(
                                remainingRequestMillis(deadlineEpochMillis, clock),
                            ),
                        )
                        .build(),
                )
            }
        completed = true
        return ArrowResultManifest(
            objectKey = objectKey,
            byteSize = byteSize,
            objectEtag = requireNotNull(response.eTag()),
            rowCount = rowCount,
            columnCount = columnCount,
            mediaType = mediaType,
        )
    }

    override suspend fun abort() {
        if (completed || aborted) return
        aborted = true
        withContext(NonCancellable + blockingDispatcher) {
            runCatching {
                client.abortMultipartUpload(
                    AbortMultipartUploadRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .uploadId(uploadId)
                        .overrideConfiguration(
                            s3RequestOverride(
                                abortRequestMillis(deadlineEpochMillis, clock),
                            ),
                        )
                        .build(),
                )
            }
        }
    }
}

internal fun s3RequestOverride(timeoutMillis: Long): AwsRequestOverrideConfiguration {
    require(timeoutMillis > 0)
    val timeout = Duration.ofMillis(timeoutMillis)
    return AwsRequestOverrideConfiguration.builder()
        .apiCallTimeout(timeout)
        .apiCallAttemptTimeout(timeout)
        .build()
}

private suspend fun remainingRequestMillis(
    deadlineEpochMillis: Long,
    clock: Clock,
): Long {
    val remainingMillis = deadlineEpochMillis - clock.millis()
    if (remainingMillis <= 0) {
        throw TimeoutException("artifact upload deadline exceeded")
    }
    return remainingMillis
}

private fun abortRequestMillis(
    deadlineEpochMillis: Long,
    clock: Clock,
): Long {
    val remainingMillis = deadlineEpochMillis - clock.millis()
    return if (remainingMillis > 0) {
        minOf(remainingMillis, ABORT_CLEANUP_TIMEOUT_MILLIS)
    } else {
        ABORT_CLEANUP_TIMEOUT_MILLIS
    }
}

private const val ABORT_CLEANUP_TIMEOUT_MILLIS = 5_000L
