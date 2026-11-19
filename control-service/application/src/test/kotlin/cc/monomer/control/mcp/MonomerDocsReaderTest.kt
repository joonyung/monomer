package cc.monomer.control.mcp

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServer

class MonomerDocsReaderTest {
    @Test
    fun `reads fresh UTF8 docs without credentials and rejects redirects or invalid content`() = runBlocking {
        val reads = AtomicInteger()
        val server = HttpServer.create().host("127.0.0.1").port(0).handle { request, response ->
            assertFalse(request.requestHeaders().contains("Authorization"))
            assertFalse(request.requestHeaders().contains("Cookie"))
            response.header("Content-Type", "text/markdown; charset=utf-8")
            when (request.uri()) {
                "/en/llms.txt" -> response.sendString(Mono.just("# Docs ${reads.incrementAndGet()}\nRead 문서"))
                "/en/analyze/query/index.md" -> response.sendString(Mono.just("# Query\n\nKeep **all** text.\n"))
                "/en/missing/index.md" -> response.status(404).send()
                "/en/redirect/index.md" -> response.status(302).header("Location", "/en/llms.txt").send()
                "/en/html/index.md" -> response.header("Content-Type", "text/html").sendString(Mono.just("<html>Sign in</html>"))
                "/en/empty/index.md" -> response.sendString(Mono.just(" \n"))
                "/en/broken/index.md" -> response.sendByteArray(Mono.just(byteArrayOf(0xc3.toByte(), 0x28)))
                "/en/large/index.md" -> response.sendByteArray(Flux.just(ByteArray(40_000) { 97 }, ByteArray(40_000) { 98 }))
                "/en/declared-large/index.md" -> response.header("Content-Length", "100000").sendByteArray(Mono.just(ByteArray(100_000) { 97 }))
                else -> response.status(500).sendString(Mono.just("private upstream details"))
            }
        }.bindNow()
        try {
            val reader = MonomerDocsReader("http://127.0.0.1:${server.port()}")
            assertEquals(MonomerDocsResult.Read("# Docs 1\nRead 문서"), reader.read("/en/llms.txt"))
            assertEquals(MonomerDocsResult.Read("# Docs 2\nRead 문서"), reader.read("/en/llms.txt"))
            assertEquals(MonomerDocsResult.Read("# Query\n\nKeep **all** text.\n"), reader.read("/en/analyze/query/index.md"))
            assertEquals(MonomerDocsResult.NotFound, reader.read("/en/missing/index.md"))
            for (path in listOf("redirect", "html", "empty", "broken", "failure")) {
                assertEquals(MonomerDocsResult.Unavailable, reader.read("/en/$path/index.md"), path)
            }
            assertEquals(2, reads.get(), "Redirect must not be followed")
            assertEquals(MonomerDocsResult.TooLarge, reader.read("/en/large/index.md"))
            assertEquals(MonomerDocsResult.TooLarge, reader.read("/en/declared-large/index.md"))
        } finally {
            server.disposeNow()
        }
    }

    @Test
    fun `deadline includes a stalled response body`() = runBlocking {
        val server = HttpServer.create().host("127.0.0.1").port(0).handle { _, response ->
            response.header("Content-Type", "text/plain").sendString(
                Flux.concat(Mono.just("# Partial"), Mono.delay(Duration.ofSeconds(20)).map { " rest" }),
            )
        }.bindNow()
        try {
            assertEquals(MonomerDocsResult.Unavailable, MonomerDocsReader("http://127.0.0.1:${server.port()}").read("/en/llms.txt"))
        } finally {
            server.disposeNow()
        }
    }

    @Test
    fun `only configured origins and document paths are accepted`() {
        for (origin in listOf("http://example.com", "https://user:pass@example.com", "https://example.com/docs", "https://example.com?query", "file:///tmp", "https://example.com/#fragment")) {
            assertFailsWith<IllegalArgumentException>(origin) { MonomerDocsReader(origin) }
        }
        for (path in listOf("", "https://evil.test/en/llms.txt", "//evil.test/en/llms.txt", "/en/../llms.txt", "/en/%2e%2e/llms.txt", "/en/a%2fb/index.md", "/en/a/index.md?x=1", "/en/a/index.md#section", "/en/a\\b/index.md", "/en/foo.json", "/en/llms.txt\n", "/en/" + "a".repeat(512) + "/index.md")) {
            assertFalse(MonomerDocsReader.validPath(path), path)
        }
        for (path in listOf("/llms.txt", "/en/llms.txt", "/en/analyze/llms.txt", "/en/analyze/run-sql/index.md")) {
            assertTrue(MonomerDocsReader.validPath(path), path)
        }
    }
}
