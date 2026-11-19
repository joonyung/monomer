package cc.monomer.pages.adapter.outbound.s3.pagecontent

import cc.monomer.pages.domain.content.PageContentHash
import cc.monomer.pages.domain.content.PageContentObjectStorage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest

class PageContentObjectStorageMinioTest {
    @Test
    fun `minio integration image is pinned to an immutable digest`() {
        assertTrue(
            MINIO_IMAGE.matches(PINNED_MINIO_IMAGE),
            "MinIO test image must name a release and pin an immutable digest, got " + MINIO_IMAGE,
        )
    }

    @Test
    fun `an immutable write is idempotent for the same content and rejects a different one`() =
        withMinio { bootstrapClient, storage ->
        val content = "page one".toByteArray()

        val written = storage.writeImmutableIfAbsent(
            objectRef = OBJECT_REF,
            contentLength = content.size.toLong(),
            contentHash = sha256(content),
            content = content.asFlow(),
        )
        val replayed = storage.writeImmutableIfAbsent(
            objectRef = OBJECT_REF,
            contentLength = content.size.toLong(),
            contentHash = sha256(content),
            content = content.asFlow(),
        )
        val colliding = "page two".toByteArray()
        val collision = storage.writeImmutableIfAbsent(
            objectRef = OBJECT_REF,
            contentLength = colliding.size.toLong(),
            contentHash = sha256(colliding),
            content = colliding.asFlow(),
        )

        assertEquals(PageContentObjectStorage.ImmutableWriteResult.Written, written)
        assertEquals(PageContentObjectStorage.ImmutableWriteResult.AlreadyPresent, replayed)
        val existing = assertIs<PageContentObjectStorage.ImmutableWriteResult.Collision>(collision).existing
        assertEquals(sha256(content), existing?.contentHash)

        val storedHead = bootstrapClient.headObject(
            HeadObjectRequest.builder().bucket(BUCKET).key("page-content/$OBJECT_REF").build(),
        ).await()
        assertEquals(content.size.toLong(), storedHead.contentLength())

        val metadata = storage.head(OBJECT_REF)
        assertEquals(content.size.toLong(), metadata?.contentLength)
        assertEquals(sha256(content), metadata?.contentHash)

        val download = storage.downloadBounded(OBJECT_REF, maximumBytes = 1_024)
        assertEquals(content.size.toLong(), download?.contentLength)
        assertTrue(content.contentEquals(download?.content?.readBytes()))

        assertFailsWith<PageContentObjectSizeLimitExceeded> {
            storage.downloadBounded(OBJECT_REF, maximumBytes = 1)
        }

        storage.delete(OBJECT_REF)
        assertNull(storage.head(OBJECT_REF))
        assertNull(storage.downloadBounded(OBJECT_REF, maximumBytes = 1_024))
    }

    @Test
    fun `a presigned part upload completes a multipart page version`() = withMinio { _, storage ->
        val part = ByteArray(1_024) { index -> (index % 97).toByte() }
        val upload = storage.createMultipartUpload("workspace/ws/page/version-2", "text/markdown")

        val presignedUrl = storage.presignUploadPart(
            upload = upload,
            partNumber = 1,
            contentLength = part.size.toLong(),
            signatureDuration = Duration.ofMinutes(5),
        )
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(presignedUrl)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(part))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode())
        val eTag = requireNotNull(response.headers().firstValue("ETag").orElse(null))

        val completion = storage.completeMultipartUpload(
            upload,
            listOf(PageContentObjectStorage.CompletedPart(partNumber = 1, eTag = eTag)),
        )

        assertEquals(PageContentObjectStorage.MultipartCompletionResult.Completed, completion)

        val download = storage.downloadBounded("workspace/ws/page/version-2", maximumBytes = 8_192)
        assertTrue(part.contentEquals(download?.content?.readBytes()))
    }

    @Test
    fun `completion rejected by the object store is reported as a typed result`() = withMinio { _, storage ->
        val upload = storage.createMultipartUpload("workspace/ws/page/version-4", "text/markdown")
        val unknownPart = listOf(
            PageContentObjectStorage.CompletedPart(partNumber = 1, eTag = "\"${"0".repeat(32)}\""),
        )

        val rejected = storage.completeMultipartUpload(upload, unknownPart)
        storage.abortMultipartUpload(upload)
        val missing = storage.completeMultipartUpload(upload, unknownPart)

        assertEquals(PageContentObjectStorage.MultipartCompletionResult.PartsRejected, rejected)
        assertEquals(PageContentObjectStorage.MultipartCompletionResult.UploadMissing, missing)
        assertNull(storage.head("workspace/ws/page/version-4"))
    }

    @Test
    fun `an aborted multipart upload publishes nothing`() = withMinio { _, storage ->
        val upload = storage.createMultipartUpload("workspace/ws/page/version-3", "text/markdown")

        storage.abortMultipartUpload(upload)

        assertNull(storage.head("workspace/ws/page/version-3"))
    }

    private fun ByteArray.asFlow(): Flow<ByteBuffer> = flowOf(ByteBuffer.wrap(this))

    private suspend fun Flow<ByteBuffer>.readBytes(): ByteArray =
        toList().fold(ByteArray(0)) { accumulated, buffer ->
            val chunk = ByteArray(buffer.remaining())
            buffer.get(chunk)
            accumulated + chunk
        }

    private fun sha256(content: ByteArray): PageContentHash =
        PageContentHash(
            MessageDigest.getInstance("SHA-256")
                .digest(content)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) },
        )

    private fun withMinio(block: suspend (S3AsyncClient, PageContentObjectStorage) -> Unit) {
        val container = GenericContainer(DockerImageName.parse(MINIO_IMAGE))
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data", "--address", ":9000")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000))
        container.start()
        try {
            val endpoint = URI("http://localhost:${container.getMappedPort(9000)}")
            val credentialsProvider =
                StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY))
            val bootstrapClient = S3AsyncClient.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(credentialsProvider)
                .forcePathStyle(true)
                .build()
            bootstrapClient.use { client ->
                runBlocking {
                    client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build()).await()
                    S3PageContentObjectStorage.create(
                        configuration = PageContentObjectStorageConfiguration(
                            endpoint = endpoint,
                            region = "us-east-1",
                            bucket = BUCKET,
                            rootDirectory = "page-content",
                        ),
                        credentialsProvider = credentialsProvider,
                    ).use { storage -> block(client, storage) }
                }
            }
        } finally {
            container.stop()
        }
    }

    private companion object {
        val PINNED_MINIO_IMAGE = Regex("minio/minio:RELEASE[.][0-9TZ-]+@sha256:[0-9a-f]{64}")
        const val MINIO_IMAGE =
            "minio/minio:RELEASE.2024-08-17T01-24-54Z@" +
                "sha256:6f23072e3e222e64fe6f86b31a7f7aca971e5129e55cbccef649b109b8e651a1"
        const val BUCKET = "monomer-page-content"
        const val ACCESS_KEY = "monomer-local-test"
        const val SECRET_KEY = "monomer-local-test-secret"
        const val OBJECT_REF = "workspace/ws/page/version-1"
    }
}
