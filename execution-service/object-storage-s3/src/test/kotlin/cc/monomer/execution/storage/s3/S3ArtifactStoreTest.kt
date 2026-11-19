package cc.monomer.execution.storage.s3

import cc.monomer.execution.driver.spi.ArrowResultManifest
import cc.monomer.execution.driver.spi.BinaryValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import software.amazon.awssdk.awscore.AwsRequest
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.UploadPartResponse
import java.lang.reflect.Proxy
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class S3ArtifactStoreTest {
    @Test
    fun settingsRejectMalformedOriginsAndBlankCredentials() {
        assertFailsWith<IllegalArgumentException> { settings(endpoint = "ftp://minio:9000") }
        assertFailsWith<IllegalArgumentException> { settings(endpoint = "http://minio:9000") }
        assertFailsWith<IllegalArgumentException> { settings(endpoint = "http://user@127.0.0.1:9000") }
        assertFailsWith<IllegalArgumentException> { settings(endpoint = "http://127.0.0.1:9000/path") }
        assertFailsWith<IllegalArgumentException> { settings(accessKey = " ") }
        assertFailsWith<IllegalArgumentException> { settings(prefix = "/artifacts/") }
    }

    @Test
    fun directMultipartAdapterCreatesUploadsAndCompletesOrAbortsThem() =
        runTest {
            val calls = mutableListOf<String>()
            val timeoutOverrides = mutableListOf<Pair<Duration, Duration>>()
            val client = recordingClient(calls, timeoutOverrides)
            val now = Instant.parse("2026-09-02T00:00:00Z")
            val uploader =
                S3DirectArtifactUploader(
                    settings = settings(),
                    blockingDispatcher = Dispatchers.Unconfined,
                    clock = Clock.fixed(now, ZoneOffset.UTC),
                    client = client,
                )
            val objectKey = "artifacts/workspaces/w/explore/runs/r/result.arrow"
            val mediaType = ArrowResultManifest.ARROW_FILE_MEDIA_TYPE
            val deadlineEpochMillis = now.toEpochMilli() + 10_000

            val upload = uploader.open(objectKey, mediaType, deadlineEpochMillis)
            upload.append(1uL, BinaryValue.copyOf(byteArrayOf(1, 2)))
            upload.append(2uL, BinaryValue.copyOf(byteArrayOf(3)))
            val manifest = upload.complete(rowCount = 2uL, columnCount = 1)

            assertEquals(objectKey, manifest.objectKey)
            assertEquals(3uL, manifest.byteSize)
            assertEquals("final-etag", manifest.objectEtag)
            assertEquals(
                listOf("create", "upload:1", "upload:2", "complete"),
                calls,
            )
            assertEquals(
                listOf(
                    10_000L,
                    10_000L,
                    10_000L,
                    10_000L,
                ).map { timeout -> Duration.ofMillis(timeout) to Duration.ofMillis(timeout) },
                timeoutOverrides,
            )

            val aborted = uploader.open(objectKey, mediaType, deadlineEpochMillis)
            aborted.abort()
            assertTrue(calls.last() == "abort")
            assertEquals(
                Duration.ofSeconds(5) to Duration.ofSeconds(5),
                timeoutOverrides.last(),
            )
            uploader.close()
        }

    @Test
    fun adapterRejectsKeysOutsideTheConfiguredPrefixAndWrongMediaType() =
        runTest {
            val uploader =
                S3DirectArtifactUploader(
                    settings = settings(),
                    blockingDispatcher = Dispatchers.Unconfined,
                    clock = Clock.systemUTC(),
                    client = recordingClient(mutableListOf(), mutableListOf()),
                )
            assertFailsWith<IllegalArgumentException> {
                uploader.open(
                    "other/workspaces/w/result.arrow",
                    ArrowResultManifest.ARROW_FILE_MEDIA_TYPE,
                    Long.MAX_VALUE,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                uploader.open(
                    "artifacts/workspaces/w/result.arrow",
                    "application/octet-stream",
                    Long.MAX_VALUE,
                )
            }
            uploader.close()
        }

    @Test
    fun expiredDeadlineIsRejectedBeforeCallingS3() =
        runTest {
            val calls = mutableListOf<String>()
            val now = Instant.parse("2026-09-02T00:00:00Z")
            val uploader =
                S3DirectArtifactUploader(
                    settings = settings(),
                    blockingDispatcher = Dispatchers.Unconfined,
                    clock = Clock.fixed(now, ZoneOffset.UTC),
                    client = recordingClient(calls, mutableListOf()),
                )

            assertFailsWith<TimeoutException> {
                uploader.open(
                    "artifacts/workspaces/w/result.arrow",
                    ArrowResultManifest.ARROW_FILE_MEDIA_TYPE,
                    now.toEpochMilli(),
                )
            }
            assertEquals(emptyList(), calls)
            uploader.close()
        }

    @Test
    fun multipartUploadCreatedWhileTheCallerIsCancelledIsAborted() =
        runBlocking {
            val calls = Collections.synchronizedList(mutableListOf<String>())
            val createEntered = CountDownLatch(1)
            val createMayFinish = CountDownLatch(1)
            val uploader =
                S3DirectArtifactUploader(
                    settings = settings(),
                    blockingDispatcher = Dispatchers.IO,
                    clock = Clock.systemUTC(),
                    client =
                        recordingClient(calls, Collections.synchronizedList(mutableListOf())) {
                            createEntered.countDown()
                            createMayFinish.await(5, TimeUnit.SECONDS)
                        },
                )
            val opening =
                launch(Dispatchers.Default) {
                    uploader.open(
                        "artifacts/workspaces/w/explore/runs/r/result.arrow",
                        ArrowResultManifest.ARROW_FILE_MEDIA_TYPE,
                        System.currentTimeMillis() + 10_000,
                    )
                }
            assertTrue(withContext(Dispatchers.IO) { createEntered.await(5, TimeUnit.SECONDS) })
            opening.cancel()
            createMayFinish.countDown()
            opening.join()

            assertTrue(opening.isCancelled)
            assertEquals(listOf("create", "abort"), calls.toList())
            uploader.close()
        }

    private fun settings(
        endpoint: String = "http://127.0.0.1:9000",
        prefix: String = "artifacts/",
        accessKey: String = "access",
    ) =
        S3ArtifactStoreSettings(
            endpoint = URI.create(endpoint),
            region = "us-east-1",
            bucket = "monomer-execution-artifacts",
            prefix = prefix,
            credentialMode = S3ArtifactCredentialMode.LOCAL_TEST_STATIC,
            accessKey = accessKey,
            secretKey = "secret",
        )

    private fun recordingClient(
        calls: MutableList<String>,
        timeoutOverrides: MutableList<Pair<Duration, Duration>>,
        blockingCreate: () -> Unit = {},
    ): S3Client =
        Proxy.newProxyInstance(
            S3Client::class.java.classLoader,
            arrayOf(S3Client::class.java),
        ) { _, method, arguments ->
            (arguments?.firstOrNull() as? AwsRequest)
                ?.overrideConfiguration()
                ?.orElse(null)
                ?.let { override ->
                    timeoutOverrides +=
                        override.apiCallTimeout().orElseThrow() to
                            override.apiCallAttemptTimeout().orElseThrow()
                }
            when (method.name) {
                "createMultipartUpload" -> {
                    blockingCreate()
                    calls += "create"
                    CreateMultipartUploadResponse.builder().uploadId("upload-id").build()
                }
                "uploadPart" -> {
                    val request = arguments?.first()
                    calls +=
                        "upload:" +
                            request!!::class.java.getMethod("partNumber").invoke(request).toString()
                    UploadPartResponse.builder().eTag("part-etag").build()
                }
                "completeMultipartUpload" -> {
                    calls += "complete"
                    CompleteMultipartUploadResponse.builder().eTag("final-etag").build()
                }
                "abortMultipartUpload" -> {
                    calls += "abort"
                    AbortMultipartUploadResponse.builder().build()
                }
                "close" -> Unit
                "toString" -> "recording-s3-client"
                "hashCode" -> System.identityHashCode(calls)
                "equals" -> arguments?.firstOrNull() === calls
                else -> error("unexpected S3 call ${method.name}")
            }
        } as S3Client
}
