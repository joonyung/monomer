package cc.monomer.execution.adapter.outbound.s3

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider

class S3ExecutionArtifactDownloadTest {
    @Test
    fun `HEAD request uses remaining deadline and cancels slow response`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val release = java.util.concurrent.CountDownLatch(1)
        server.createContext("/bucket/artifacts/test.arrow") { request ->
            release.await(3, java.util.concurrent.TimeUnit.SECONDS)
            runCatching { request.sendResponseHeaders(200, -1); request.close() }
        }
        server.start()
        try {
            store(URI("http://127.0.0.1:${server.address.port}")).use { store ->
                val started = System.nanoTime()
                assertFailsWith<software.amazon.awssdk.core.exception.ApiCallTimeoutException> {
                    store.head("artifacts/test.arrow", Instant.now().plusMillis(150))
                }
                assertTrue(java.time.Duration.ofNanos(System.nanoTime() - started) < java.time.Duration.ofSeconds(2))
            }
        } finally { release.countDown(); server.stop(0) }
    }

    @Test
    fun `conditional range verifies exact response and uses If-Match`() = runBlocking<Unit> {
        val receivedEtag = AtomicReference<String>()
        val receivedRange = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/bucket/artifacts/test.arrow") { request ->
            receivedEtag.set(request.requestHeaders.getFirst("If-Match"))
            receivedRange.set(request.requestHeaders.getFirst("Range"))
            request.responseHeaders.add("ETag", "\"expected\"")
            request.responseHeaders.add("Content-Range", "bytes 2-4/10")
            request.sendResponseHeaders(206, 3)
            request.responseBody.use { it.write(byteArrayOf(2, 3, 4)) }
        }
        server.start()
        try {
            store(URI("http://127.0.0.1:${server.address.port}")).use { store ->
                assertContentEquals(byteArrayOf(2, 3, 4), store.readRange("artifacts/test.arrow", "expected", 2L..4L))
                assertEquals("expected", receivedEtag.get())
                assertEquals("bytes=2-4", receivedRange.get())
                assertFailsWith<IllegalArgumentException> {
                    store.readRange("artifacts/test.arrow", "expected", 0L..1_048_576L)
                }
            }
        } finally { server.stop(0) }
    }

    @Test
    fun `conditional range fails when returned object is different`() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/bucket/artifacts/test.arrow") { request ->
            request.responseHeaders.add("ETag", "\"different\"")
            request.responseHeaders.add("Content-Range", "bytes 0-2/3")
            request.sendResponseHeaders(206, 3)
            request.responseBody.use { it.write(byteArrayOf(1, 2, 3)) }
        }
        server.start()
        try {
            store(URI("http://127.0.0.1:${server.address.port}")).use { store ->
                assertFailsWith<IllegalStateException> {
                    store.readRange("artifacts/test.arrow", "expected", 0L..2L)
                }
            }
        } finally { server.stop(0) }
    }

    @Test
    fun `signing restricts key operation filename and lifetime without exposing URL in diagnostics`() = runBlocking<Unit> {
        store(URI("https://storage.example.test")).use { store ->
            val before = Instant.now()
            val link = store.presignGet("artifacts/result.arrow", before.plusSeconds(900), FILE_NAME)
            val uri = URI(link.url)
            val parameters = query(uri)
            assertEquals("/bucket/artifacts/result.arrow", uri.path)
            assertTrue(parameters.getValue("X-Amz-Expires").toLong() in 1..300)
            assertEquals("application/vnd.apache.arrow.file", parameters["response-content-type"])
            assertEquals("attachment; filename=\"$FILE_NAME\"", parameters["response-content-disposition"])
            assertTrue(link.expiresAt <= Instant.now().plusSeconds(300))
            assertFalse(link.toString().contains("X-Amz"))
            assertFailsWith<IllegalArgumentException> { store.presignGet("other/result.arrow", before.plusSeconds(60), FILE_NAME) }
            assertFailsWith<IllegalArgumentException> { store.presignGet("artifacts/result.arrow", before.minusSeconds(1), FILE_NAME) }
            assertFailsWith<IllegalArgumentException> { store.presignGet("artifacts/result.arrow", before.plusSeconds(60), "bad\r\nname.arrow") }
        }
    }

    @Test
    fun `signature cannot outlive retained result or temporary credential`() = runBlocking {
        val credentialExpiry = Instant.now().plusSeconds(90)
        val credentials = AwsSessionCredentials.builder()
            .accessKeyId("fixture-access-key")
            .secretAccessKey("fixture-secret-key")
            .sessionToken("fixture-session-token")
            .expirationTime(credentialExpiry).build()
        S3ExecutionArtifactStore.create(
            configuration(URI("https://storage.example.test")),
            StaticCredentialsProvider.create(credentials), Clock.systemUTC(), Dispatchers.IO,
        ).use { store ->
            val link = store.presignGet("artifacts/result.arrow", Instant.now().plusSeconds(600), FILE_NAME)
            assertTrue(link.expiresAt <= credentialExpiry)
            val retention = Instant.now().plusSeconds(30)
            assertTrue(store.presignGet("artifacts/result.arrow", retention, FILE_NAME).expiresAt <= retention)
        }
    }

    private fun store(endpoint: URI) = S3ExecutionArtifactStore.create(
        configuration(endpoint),
        StaticCredentialsProvider.create(AwsBasicCredentials.create("fixture-access-key", "fixture-secret-key")),
        Clock.systemUTC(), Dispatchers.IO,
    )

    private fun configuration(endpoint: URI) = S3ExecutionArtifactStoreConfiguration(
        endpoint, "us-east-1", "bucket", "artifacts/", true,
    )

    private fun query(uri: URI): Map<String, String> = uri.rawQuery.split('&').associate {
        val pair = it.split('=', limit = 2)
        URLDecoder.decode(pair[0], Charsets.UTF_8) to URLDecoder.decode(pair[1], Charsets.UTF_8)
    }

    private companion object {
        const val FILE_NAME = "explore-019e0000-0000-7000-8000-0000000000e1.arrow"
    }
}
