package cc.monomer.execution.adapter.outbound.s3

import cc.monomer.execution.api.ExecutionArtifactDownloadLink
import cc.monomer.execution.api.ExecutionArtifactHead
import cc.monomer.execution.api.ExecutionArtifactStore
import java.net.URI
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException

data class S3ExecutionArtifactStoreConfiguration(
    val endpoint: URI,
    val region: String,
    val bucket: String,
    val objectPrefix: String,
    val pathStyleAccessEnabled: Boolean,
) {
    init {
        require(endpoint.scheme == "https" ||
            (endpoint.scheme == "http" && endpoint.host in LOOPBACK_ENDPOINT_HOSTS))
        require(region.isNotBlank())
        require(bucket.isNotBlank())
        require(objectPrefix.isNotBlank() && objectPrefix.endsWith('/'))
        require(!objectPrefix.startsWith('/') && ".." !in objectPrefix)
    }

    companion object {
        private val LOOPBACK_ENDPOINT_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")
    }
}

class S3ExecutionArtifactStore private constructor(
    private val client: S3Client,
    private val credentialsProvider: AwsCredentialsProvider,
    private val configuration: S3ExecutionArtifactStoreConfiguration,
    private val clock: Clock,
    private val blockingDispatcher: CoroutineDispatcher,
    private val httpClient: SdkHttpClient,
) : ExecutionArtifactStore, AutoCloseable {
    override suspend fun head(objectKey: String, deadline: Instant?): ExecutionArtifactHead? =
        runInterruptible(blockingDispatcher) {
            val remaining = deadline?.let { Duration.between(clock.instant(), it) }
            if (remaining != null && (remaining.isNegative || remaining.isZero)) {
                throw java.util.concurrent.TimeoutException("Artifact inspection deadline exceeded")
            }
            try {
                val response =
                    client.headObject(
                        HeadObjectRequest.builder()
                            .bucket(configuration.bucket)
                            .key(requireObjectKey(objectKey))
                            .apply {
                                remaining?.let { timeout ->
                                    overrideConfiguration { it.apiCallTimeout(timeout).apiCallAttemptTimeout(timeout) }
                                }
                            }
                            .build(),
                    )
                ExecutionArtifactHead(
                    objectKey = objectKey,
                    byteSize = response.contentLength().toULong(),
                    etag = requireNotNull(response.eTag()).trim(),
                    observedAt = clock.instant(),
                )
            } catch (_: NoSuchKeyException) {
                null
            } catch (failure: software.amazon.awssdk.services.s3.model.S3Exception) {
                if (failure.statusCode() == 404) null else throw failure
            }
        }

    override suspend fun read(objectKey: String): ByteArray =
        withContext(blockingDispatcher) {
            val request = GetObjectRequest.builder()
                .bucket(configuration.bucket)
                .key(requireObjectKey(objectKey))
                .build()
            client.getObjectAsBytes(request).asByteArray()
        }

    override suspend fun readRange(
        objectKey: String,
        expectedEtag: String,
        byteRange: LongRange,
    ): ByteArray = runInterruptible(blockingDispatcher) {
        require(expectedEtag.isNotBlank())
        require(byteRange.first >= 0 && byteRange.last >= byteRange.first)
        val length = byteRange.last - byteRange.first + 1
        require(length in 1..MAX_DOWNLOAD_CHUNK_BYTES.toLong())
        val request = GetObjectRequest.builder()
            .bucket(configuration.bucket)
            .key(requireObjectKey(objectKey))
            .ifMatch(expectedEtag)
            .range("bytes=${byteRange.first}-${byteRange.last}")
            .build()
        val response = client.getObject(request)
        try {
            check(response.response().contentLength() == length) { "Artifact range length mismatch" }
            check(response.response().eTag().trim().trim('"') == expectedEtag.trim().trim('"')) {
                "Artifact range ETag mismatch"
            }
            check(response.response().contentRange()?.startsWith("bytes ${byteRange.first}-${byteRange.last}/") == true) {
                "Artifact range response mismatch"
            }
            val bytes = response.readNBytes(length.toInt())
            check(bytes.size.toLong() == length && response.read() == -1) { "Incomplete artifact range" }
            bytes
        } finally {
            response.abort()
            response.close()
        }
    }

    override fun readChunks(
        objectKey: String,
        expectedEtag: String,
        chunkSizeBytes: Int,
    ): Flow<ByteBuffer> {
        require(expectedEtag.isNotBlank())
        require(chunkSizeBytes in 1..MAX_DOWNLOAD_CHUNK_BYTES)
        val key = requireObjectKey(objectKey)
        return flow {
            val request = GetObjectRequest.builder()
                .bucket(configuration.bucket)
                .key(key)
                .ifMatch(expectedEtag)
                .build()
            client.getObject(request).use { response ->
                val chunk = ByteArray(chunkSizeBytes)
                while (true) {
                    val bytesRead = response.read(chunk)
                    if (bytesRead < 0) break
                    if (bytesRead == 0) continue
                    emit(ByteBuffer.wrap(chunk.copyOf(bytesRead)).asReadOnlyBuffer())
                }
            }
        }.flowOn(blockingDispatcher)
    }

    override suspend fun presignGet(
        objectKey: String,
        expiresAt: Instant,
        fileName: String,
    ): ExecutionArtifactDownloadLink = runInterruptible(blockingDispatcher) {
        val key = requireObjectKey(objectKey)
        require(fileName.matches(Regex("explore-[0-9a-f-]{36}\\.arrow")))
        val credentials = credentialsProvider.resolveCredentials()
        val now = clock.instant()
        val expiration = minOf(
            expiresAt.truncatedTo(ChronoUnit.SECONDS),
            now.plusSeconds(300),
            credentials.expirationTime().orElse(Instant.MAX),
        ).truncatedTo(ChronoUnit.SECONDS)
        require(expiration.isAfter(now.plusSeconds(1))) { "Result download is no longer available" }
        S3Presigner.builder()
            .endpointOverride(configuration.endpoint)
            .region(Region.of(configuration.region))
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(configuration.pathStyleAccessEnabled)
                .build())
            .build().use { presigner ->
                val signed = presigner.presignGetObject(
                    GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.between(now, expiration).minusSeconds(1))
                        .getObjectRequest(GetObjectRequest.builder()
                            .bucket(configuration.bucket)
                            .key(key)
                            .responseContentType("application/vnd.apache.arrow.file")
                            .responseContentDisposition("attachment; filename=\"$fileName\"")
                            .responseCacheControl("no-store")
                            .build())
                        .build(),
                )
                check(!signed.expiration().isAfter(expiration)) { "Download signature exceeded the authorized lifetime" }
                ExecutionArtifactDownloadLink(signed.url().toExternalForm(), signed.expiration())
            }
    }

    override suspend fun delete(objectKey: String) {
        withContext(blockingDispatcher) {
            client.deleteObject(
                DeleteObjectRequest.builder()
                    .bucket(configuration.bucket)
                    .key(requireObjectKey(objectKey))
                    .build(),
            )
        }
    }

    private fun requireObjectKey(objectKey: String): String {
        require(objectKey.startsWith(configuration.objectPrefix))
        require(objectKey.length <= 1_024)
        require(".." !in objectKey && "//" !in objectKey)
        return objectKey
    }

    override fun close() {
        client.close()
        httpClient.close()
    }

    companion object {
        private const val MAX_DOWNLOAD_CHUNK_BYTES = 1024 * 1024

        fun create(
            configuration: S3ExecutionArtifactStoreConfiguration,
            credentialsProvider: AwsCredentialsProvider,
            clock: Clock,
            blockingDispatcher: CoroutineDispatcher,
        ): S3ExecutionArtifactStore {
            val httpClient = UrlConnectionHttpClient.create()
            val client =
                S3Client.builder()
                    .endpointOverride(configuration.endpoint)
                    .region(Region.of(configuration.region))
                    .credentialsProvider(credentialsProvider)
                    .serviceConfiguration(
                        S3Configuration.builder()
                            .pathStyleAccessEnabled(configuration.pathStyleAccessEnabled)
                            .build(),
                    )
                    .httpClient(httpClient)
                    .build()
            return S3ExecutionArtifactStore(
                client = client,
                credentialsProvider = credentialsProvider,
                configuration = configuration,
                clock = clock,
                blockingDispatcher = blockingDispatcher,
                httpClient = httpClient,
            )
        }
    }
}
