package cc.monomer.pages.adapter.outbound.s3.pagecontent

import cc.monomer.pages.domain.content.PageContentHash
import cc.monomer.pages.domain.content.PageContentObjectStorage
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import java.nio.ByteBuffer
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.core.async.ResponsePublisher
import software.amazon.awssdk.core.async.SdkPublisher
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner

class S3PageContentObjectStorageTest {
    private val contentHash = PageContentHash("a".repeat(64))

    @Test
    fun `immutable write always sends If-None-Match star and trusted sha256 metadata`() = runTest {
        val client = mockk<S3AsyncClient>()
        val request = slot<PutObjectRequest>()
        every { client.putObject(capture(request), any<AsyncRequestBody>()) } returns
            CompletableFuture.completedFuture(PutObjectResponse.builder().build())
        every { client.close() } just Runs
        val storage = storage(client)

        val result = storage.writeImmutableIfAbsent(
            "workspace/ws/source/sha256/$contentHash",
            3,
            contentHash,
            flowOf(ByteBuffer.wrap(byteArrayOf(1, 2, 3))),
        )

        assertThat(result).isEqualTo(PageContentObjectStorage.ImmutableWriteResult.Written)
        assertThat(request.captured.ifNoneMatch()).isEqualTo("*")
        assertThat(request.captured.metadata()).containsEntry("sha256", contentHash.value)
        assertThat(request.captured.key()).isEqualTo("content/workspace/ws/source/sha256/$contentHash")
    }

    @Test
    fun `conditional collision reuses only identical trusted hash and length`() = runTest {
        val client = collisionClient(
            HeadObjectResponse.builder()
                .contentLength(3)
                .metadata(mapOf("sha256" to contentHash.value))
                .build(),
        )
        val result = storage(client).writeImmutableIfAbsent(
            "same",
            3,
            contentHash,
            flowOf(ByteBuffer.wrap(byteArrayOf(1, 2, 3))),
        )

        assertThat(result).isEqualTo(PageContentObjectStorage.ImmutableWriteResult.AlreadyPresent)
    }

    @Test
    fun `conditional collision never overwrites mismatched metadata`() = runTest {
        val client = collisionClient(
            HeadObjectResponse.builder()
                .contentLength(4)
                .metadata(mapOf("sha256" to "b".repeat(64)))
                .build(),
        )
        val result = storage(client).writeImmutableIfAbsent(
            "collision",
            3,
            contentHash,
            flowOf(ByteBuffer.wrap(byteArrayOf(1, 2, 3))),
        )

        assertThat(result).isInstanceOf(PageContentObjectStorage.ImmutableWriteResult.Collision::class.java)
        val collision = result as PageContentObjectStorage.ImmutableWriteResult.Collision
        assertThat(collision.existing!!.contentHash).isEqualTo(PageContentHash("b".repeat(64)))
        assertThat(collision.existing.contentLength).isEqualTo(4)
    }

    @Test
    fun `provider rejection other than conditional collision is not degraded to overwrite`() {
        val client = mockk<S3AsyncClient>()
        every { client.putObject(any<PutObjectRequest>(), any<AsyncRequestBody>()) } returns
            CompletableFuture.failedFuture(S3Exception.builder().statusCode(400).message("unsupported").build())
        every { client.close() } just Runs

        assertThatThrownBy {
            runTest {
                storage(client).writeImmutableIfAbsent(
                    "unsupported",
                    3,
                    contentHash,
                    flowOf(ByteBuffer.wrap(byteArrayOf(1, 2, 3))),
                )
            }
        }.isInstanceOf(S3Exception::class.java)
    }

    @Test
    fun `completion rejections of the parts or the upload become typed results`() = runTest {
        mapOf(
            "InvalidPart" to PageContentObjectStorage.MultipartCompletionResult.PartsRejected,
            "InvalidPartOrder" to PageContentObjectStorage.MultipartCompletionResult.PartsRejected,
            "EntityTooSmall" to PageContentObjectStorage.MultipartCompletionResult.PartsRejected,
            "NoSuchUpload" to PageContentObjectStorage.MultipartCompletionResult.UploadMissing,
        ).forEach { (errorCode, expected) ->
            val result = storage(completionClient(completionRejection(errorCode, 400))).completeMultipartUpload(
                PageContentObjectStorage.MultipartUpload("workspace/ws/uploads/source", "upload-1"),
                listOf(PageContentObjectStorage.CompletedPart(1, "etag-1")),
            )

            assertThat(result).describedAs(errorCode).isEqualTo(expected)
        }
    }

    @Test
    fun `completion failures other than part or upload rejection still fail the call`() {
        assertThatThrownBy {
            runTest {
                storage(completionClient(completionRejection("InternalError", 500))).completeMultipartUpload(
                    PageContentObjectStorage.MultipartUpload("workspace/ws/uploads/source", "upload-1"),
                    listOf(PageContentObjectStorage.CompletedPart(1, "etag-1")),
                )
            }
        }.isInstanceOf(S3Exception::class.java)
    }

    @Test
    fun `MinIO presign keeps bucket and key in the path instead of a bucket subdomain`() = runTest {
        val client = mockk<S3AsyncClient>()
        every { client.close() } just Runs
        val presigner = S3Presigner.builder()
            .endpointOverride(URI.create("http://localhost:9000"))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("minioadmin", "minioadmin")),
            )
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()
        val storage = S3PageContentObjectStorage(client, presigner, "bucket", "content")

        val url = storage.presignUploadPart(
            PageContentObjectStorage.MultipartUpload("workspace/ws/upload/source", "upload-1"),
            1,
            3,
            Duration.ofMinutes(5),
        )

        assertThat(url.host).isEqualTo("localhost")
        assertThat(url.path).isEqualTo("/bucket/content/workspace/ws/upload/source")
        storage.close()
    }

    @Test
    fun `oversized and discarded downloads cancel the unread response body`() = runTest {
        val oversized = RecordingPublisher()
        val oversizedClient = downloadClient(oversized, contentLength = 4)

        assertThatThrownBy { runBlocking { storage(oversizedClient).downloadBounded("oversized", 3) } }
            .isInstanceOf(PageContentObjectSizeLimitExceeded::class.java)
        assertThat(oversized.subscriptions).isEqualTo(1)
        assertThat(oversized.cancelled).isTrue()

        val unread = RecordingPublisher()
        val download = storage(downloadClient(unread, contentLength = 3)).downloadBounded("unread", 3)!!
        assertThat(unread.subscriptions).isZero()
        download.discard()
        assertThat(unread.subscriptions).isEqualTo(1)
        assertThat(unread.cancelled).isTrue()
    }

    private class RecordingPublisher : SdkPublisher<ByteBuffer> {
        var subscriptions = 0
        var cancelled = false

        override fun subscribe(subscriber: Subscriber<in ByteBuffer>) {
            subscriptions += 1
            subscriber.onSubscribe(object : Subscription {
                override fun request(n: Long) = Unit
                override fun cancel() {
                    cancelled = true
                }
            })
        }
    }

    private fun downloadClient(publisher: RecordingPublisher, contentLength: Long): S3AsyncClient =
        mockk<S3AsyncClient>().also { client ->
            every {
                client.getObject(
                    any<GetObjectRequest>(),
                    any<AsyncResponseTransformer<GetObjectResponse, ResponsePublisher<GetObjectResponse>>>(),
                )
            } returns CompletableFuture.completedFuture(
                ResponsePublisher(GetObjectResponse.builder().contentLength(contentLength).build(), publisher),
            )
            every { client.close() } just Runs
        }

    private fun completionRejection(errorCode: String, statusCode: Int): S3Exception =
        S3Exception.builder()
            .statusCode(statusCode)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).build())
            .message(errorCode)
            .build() as S3Exception

    private fun completionClient(failure: S3Exception): S3AsyncClient = mockk<S3AsyncClient>().also { client ->
        every { client.completeMultipartUpload(any<CompleteMultipartUploadRequest>()) } returns
            CompletableFuture.failedFuture(failure)
        every { client.close() } just Runs
    }

    private fun collisionClient(headResponse: HeadObjectResponse): S3AsyncClient = mockk<S3AsyncClient>().also { client ->
        every { client.putObject(any<PutObjectRequest>(), any<AsyncRequestBody>()) } returns
            CompletableFuture.failedFuture(S3Exception.builder().statusCode(412).message("precondition").build())
        every { client.headObject(any<HeadObjectRequest>()) } returns CompletableFuture.completedFuture(headResponse)
        every { client.close() } just Runs
    }

    private fun storage(client: S3AsyncClient): S3PageContentObjectStorage {
        val presigner = mockk<S3Presigner>()
        every { presigner.close() } just Runs
        return S3PageContentObjectStorage(client, presigner, "bucket", "content")
    }
}
