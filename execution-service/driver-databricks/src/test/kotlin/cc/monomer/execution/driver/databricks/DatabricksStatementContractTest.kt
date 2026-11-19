package cc.monomer.execution.driver.databricks

import cc.monomer.execution.driver.spi.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.*
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import kotlin.test.*

class DatabricksStatementContractTest {
    @Test fun `recorded zero manifest without chunks supports empty Arrow delivery`() = runBlocking {
        val recorded = recordedZeroManifest()
        assertEquals("JSON_ARRAY", recorded.path("format").asText())
        assertEquals(setOf("format", "schema", "total_chunk_count", "total_row_count", "truncated"), recorded.fieldNames().asSequence().toSet())
        listOf(context(expectedNames = listOf("n")), context(artifactTarget, expectedNames = listOf("n"))).forEach { executionContext ->
            val sink = RecordingSink()
            val response = emptyResponse().also { (it.path("manifest") as ObjectNode).put("truncated", false) }
            val http = responseClient(response)
            val outcome = assertIs<DriverPhysicalResult.Succeeded>(execute(http, sink, executionContext))
            assertEquals(0uL, outcome.rowsProcessed)
            val bytes = if (sink.completed) sink.parts.flatMap { it.copyBytes().asList() }.toByteArray() else sink.frames.single().copyBytes()
            RootAllocator(ARROW_MEMORY_BYTES).use { allocator ->
                ArrowFileReader(ByteArrayReadableSeekableByteChannel(bytes), allocator).use { reader ->
                    assertEquals(listOf("n"), reader.vectorSchemaRoot.schema.fields.map { it.name })
                    assertFalse(reader.loadNextBatch())
                }
            }
            assertEquals(1, http.requests.size)
        }
    }

    @Test fun `recorded truncated zero manifest takes byte failure path without chunks`() = runBlocking {
        val sink = RecordingSink()
        val http = responseClient(emptyResponse())
        val outcome = assertIs<DriverPhysicalResult.Failed>(execute(http, sink, context(artifactTarget)))
        assertEquals("DATABRICKS_EXECUTION_FAILED", outcome.failureCode)
        assertEquals("Databricks result was truncated", outcome.safeMessage)
        assertFalse(sink.opened)
        assertFalse(sink.completed)
        assertEquals(1, http.requests.count { it.uri().path.endsWith("/cancel") })
    }

    @Test fun `manifest counts are required and chunks are optional only for zero`() = runBlocking {
        val malformed: List<(ObjectNode) -> Unit> = listOf(
            { it.remove("total_chunk_count") },
            { it.remove("total_row_count") },
            { it.putNull("total_chunk_count") },
            { it.put("total_row_count", "0") },
            { it.put("total_chunk_count", -1) },
            { it.put("total_row_count", -1) },
            { it.put("total_chunk_count", 1) },
            { it.putNull("chunks") },
            { it.putObject("chunks") },
            { it.put("chunks", "invalid") },
            { it.putArray("chunks").addObject().put("chunk_index", 0).put("row_offset", 0).put("row_count", 0) },
        )
        malformed.forEach { change ->
            val response = emptyResponse()
            (response.path("manifest") as ObjectNode).put("truncated", false).also(change)
            val sink = RecordingSink()
            val http = responseClient(response)
            assertEquals("DATABRICKS_EXECUTION_FAILED", assertIs<DriverPhysicalResult.Failed>(execute(http, sink, context(artifactTarget))).failureCode)
            assertFalse(sink.opened)
            assertFalse(sink.completed)
            assertEquals(1, http.requests.count { it.uri().path.endsWith("/cancel") })
        }
        val response = snapshot() as ObjectNode
        (response.path("manifest") as ObjectNode).putArray("chunks")
        assertEquals("DATABRICKS_EXECUTION_FAILED", assertIs<DriverPhysicalResult.Failed>(execute(responseClient(response), RecordingSink())).failureCode)
    }

    @Test fun `external link forwards exact vendor headers without ambient credentials`() = runBlocking {
        val headers = mapOf("X-Databricks-Result" to "synthetic-opaque-header", "x-ms-version" to "2025-01-05")
        val external = link(0, 0, 1) as ObjectNode
        external.putObject("http_headers").also { node -> headers.forEach(node::put) }
        val http = responseClient(snapshot(links = listOf(external)), integerStream(listOf(1L)))
        assertIs<DriverPhysicalResult.Succeeded>(execute(http, RecordingSink()))
        val request = http.requests.single { it.uri().host == "storage.cloud.databricks.com" }
        headers.forEach { (name, value) -> assertEquals(listOf(value), request.headers().allValues(name)) }
        assertEquals(headers.size, request.headers().map().size)
        listOf("Authorization", "Proxy-Authorization", "Cookie").forEach { assertTrue(request.headers().firstValue(it).isEmpty) }
    }

    @Test fun `forbidden and malformed external headers fail before download or expiry refresh`() = runBlocking {
        val forbidden = listOf("Authorization", "Proxy-Authorization", "Cookie", "connection", "content-length", "expect", "host", "upgrade")
        val forbiddenCases = forbidden.flatMap { name -> listOf(name, name.uppercase(), name.lowercase()).map { variant ->
            jsonMapper.createObjectNode().put(variant, "synthetic-private-header") as JsonNode to "Databricks result headers contain a forbidden name"
        } }
        val malformed = listOf(
            jsonMapper.createArrayNode(), jsonMapper.nullNode(),
            jsonMapper.createObjectNode().put("Bad Header", "synthetic-private-header"),
            jsonMapper.createObjectNode().put("", "synthetic-private-header"),
            jsonMapper.createObjectNode().put("X-Result", "synthetic-private-header\r\nInjected: true"),
            jsonMapper.createObjectNode().put("X-Result", "synthetic-private-header\u0000"),
            jsonMapper.createObjectNode().put("X-Result", 1),
            jsonMapper.createObjectNode().putNull("X-Result"),
        ).map { it to "Databricks result headers are invalid" }
        (forbiddenCases + malformed).forEach { (headers, reason) ->
            val external = link(0, 0, 1, expiration = "2000-01-01T00:00:00Z") as ObjectNode
            external.set<ObjectNode>("http_headers", headers)
            val http = responseClient(snapshot(links = listOf(external)))
            val sink = RecordingSink()
            val failure = assertIs<DriverPhysicalResult.Failed>(execute(http, sink, context(artifactTarget)))
            assertEquals("DATABRICKS_EXECUTION_FAILED", failure.failureCode)
            assertEquals(reason, failure.safeMessage)
            assertFalse(sink.opened)
            assertFalse(sink.completed)
            assertEquals(0, http.requests.count { it.uri().host == "storage.cloud.databricks.com" || it.uri().path.contains("/result/chunks/") })
            assertEquals(1, http.requests.count { it.uri().path == "/api/2.0/sql/statements" })
            assertEquals(1, http.requests.count { it.uri().path.endsWith("/cancel") })
        }
    }

    @Test fun `workspace and download failures cannot echo opaque result header`() = runBlocking {
        val headerSecret = "synthetic-opaque-header-value"
        val external = link(0, 0, 1, next = 1) as ObjectNode
        external.putObject("http_headers").put("X-Result-Key", headerSecret)
        val error = jsonMapper.createObjectNode().put("sql_state", "42501").put("message", "Upstream rejected $headerSecret")
        assertFalse(httpFailure(400, error).stackTraceToString().contains(headerSecret))
        val logged = java.util.concurrent.CopyOnWriteArrayList<String>()
        val logger = java.util.logging.Logger.getLogger(DriverOperationTiming::class.java.name)
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) { logged.add(record.message + record.thrown?.stackTraceToString().orEmpty()) }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val http = RecordedHttpClient { request -> when {
            request.uri().path.endsWith("/cancel") -> httpResponse(request, 200, byteArrayOf())
            request.uri().path == "/api/2.0/sql/statements" -> jsonResponse(request, 200, snapshot(listOf(1, 1), listOf(external)))
            request.uri().path.endsWith("/result/chunks/1") -> jsonResponse(request, 400, error)
            else -> httpResponse(request, 200, integerStream(listOf(1L)))
        } }
        logger.addHandler(handler)
        try {
            val sink = RecordingSink()
            val failure = assertIs<DriverPhysicalResult.Failed>(execute(http, sink, context(artifactTarget)))
            assertEquals("PERMISSION_DENIED", failure.failureCode)
            assertEquals("Databricks HTTP request was rejected", failure.safeMessage)
            assertFalse(failure.safeMessage.contains(headerSecret))
            assertTrue(logged.isNotEmpty())
            assertFalse(logged.any { it.contains(headerSecret) })
            assertTrue(sink.aborted)
            assertFalse(sink.completed)
            val downloadFailure = RecordedHttpClient { request -> when {
                request.uri().path.endsWith("/cancel") -> httpResponse(request, 200, byteArrayOf())
                request.uri().path == "/api/2.0/sql/statements" -> jsonResponse(request, 200, snapshot(links = listOf(external)))
                else -> CompletableFuture.failedFuture(IOException("Upstream rejected $headerSecret"))
            } }
            val downloadOutcome = assertIs<DriverPhysicalResult.Failed>(execute(downloadFailure, RecordingSink()))
            assertEquals("CONNECTION_FAILED", downloadOutcome.failureCode)
            assertEquals("Databricks HTTP request failed", downloadOutcome.safeMessage)
            assertFalse(logged.any { it.contains(headerSecret) })
            assertEquals(1, downloadFailure.requests.count { it.uri().host == "storage.cloud.databricks.com" })
            val transport = RecordedHttpClient { CompletableFuture.failedFuture(IOException("Upstream rejected $headerSecret")) }
            credential("access_token" to "synthetic-token").use { material ->
                val client = DatabricksStatementClient(connector(), material, transport, Dispatchers.IO, java.time.Clock.systemUTC(), java.time.Duration.ofSeconds(3), operationTiming())
                try {
                    val headers = client.externalHeaders(external.path("http_headers"))
                    val rejected = assertFailsWith<DatabricksFailure> {
                        client.download(external.path("external_link").asText(), headers, System.currentTimeMillis() + 10_000)
                    }
                    assertFalse(rejected.stackTraceToString().contains(headerSecret))
                    assertNull(rejected.cause)
                } finally { client.close() }
            }
        } finally { logger.removeHandler(handler) }
    }

    @Test fun `structured POST rejection preserves SQL state safe reason and HTTP precedence`() = runBlocking {
        val cases = listOf(
            Triple(400, "42601", "SYNTAX_ERROR"), Triple(400, "42P01", "OBJECT_NOT_FOUND"),
            Triple(400, "XX000", "DATABRICKS_EXECUTION_FAILED"), Triple(401, "42601", "AUTHENTICATION_FAILED"),
            Triple(403, "42601", "PERMISSION_DENIED"), Triple(429, "42601", "RESOURCE_EXHAUSTED"),
        )
        cases.forEach { (status, state, code) ->
            val error = jsonMapper.createObjectNode().put("error_code", "BAD_REQUEST").put("sql_state", state).put("message", "Column expected_value does not exist")
            val http = RecordedHttpClient { request -> jsonResponse(request, status, error) }
            val failure = assertIs<DriverPhysicalResult.Failed>(execute(http, RecordingSink()))
            assertEquals(code, failure.failureCode)
            assertEquals("Column expected_value does not exist", failure.safeMessage)
            assertEquals(1, http.requests.size)
        }
        listOf("synthetic-token", "bound-secret", "https://storage.test/path?synthetic-signed-secret").forEach { secret ->
            val error = jsonMapper.createObjectNode().put("sql_state", "42601").put("message", "Rejected $secret")
            val http = RecordedHttpClient { request -> jsonResponse(request, 400, error) }
            val failure = assertIs<DriverPhysicalResult.Failed>(execute(http, RecordingSink(), context(parameters = listOf(QueryParameter.Text("bound-secret", "value")))))
            assertEquals("SYNTAX_ERROR", failure.failureCode)
            assertFalse(failure.safeMessage.contains(secret))
            assertFalse(httpFailure(400, error).stackTraceToString().contains(secret))
        }
    }

    @Test fun `deadline closes blocked download and aborts earlier emitted multipart batch`() = runBlocking {
        val sink = RecordingSink()
        val started = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val data = integerStream(listOf(1L), listOf(2L))
        val body = object : ByteArrayInputStream(data) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (sink.parts.isNotEmpty()) {
                    started.complete(Unit)
                    while (release.count > 0) { try { release.await() } catch (_: InterruptedException) { } }
                    throw IOException("closed synthetic-download-secret")
                }
                return super.read(bytes, offset, length)
            }
            override fun close() { release.countDown(); closed.complete(Unit) }
        }
        val http = RecordedHttpClient { request -> when {
            request.uri().path.endsWith("/cancel") -> httpResponse(request, 200, byteArrayOf())
            request.method() == "POST" -> jsonResponse(request, 200, snapshot(listOf(2), listOf(link(0, 0, 2))))
            else -> CompletableFuture.completedFuture(inputResponse(request, 200, body))
        } }
        val session = provider(http).openSession(connector(), credential("access_token" to "synthetic-token"), sink, operationTiming())
        try {
            session.enter(context(artifactTarget, deadline = System.currentTimeMillis() + 1_000))
            withTimeout(2_000) { started.await() }
            val failure = withTimeout(3_000) { assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult()) }
            withTimeout(2_000) { closed.await() }
            assertEquals("RUN_DEADLINE_EXCEEDED", failure.failureCode)
            assertTrue(sink.opened)
            assertTrue(sink.parts.isNotEmpty())
            assertTrue(sink.aborted)
            assertFalse(sink.completed)
            assertFalse(failure.safeMessage.contains("synthetic-download-secret"))
            assertEquals(1, http.requests.count { it.uri().path.endsWith("/cancel") })
            assertEquals(0, http.requests.count { it.uri().path.contains("/result/chunks/") })
        } finally { release.countDown(); session.close() }
    }

    @Test fun `actual chunk input byte limit closes stream and aborts without manifest`() = runBlocking {
        val data = integerStream(listOf(1L), *Array(100) { emptyList<Long?>() })
        val maximumBytes = 2_048uL
        assertTrue(data.size.toULong() > maximumBytes)
        var closed = false
        val body = object : ByteArrayInputStream(data) { override fun close() { closed = true } }
        val http = RecordedHttpClient { request -> when {
            request.uri().path.endsWith("/cancel") -> httpResponse(request, 200, byteArrayOf())
            request.method() == "POST" -> jsonResponse(request, 200, snapshot())
            else -> CompletableFuture.completedFuture(inputResponse(request, 200, body))
        } }
        val sink = RecordingSink()
        val failure = assertIs<DriverPhysicalResult.Failed>(execute(http, sink, context(artifactTarget.copy(maximumArtifactBytes = maximumBytes))))
        assertEquals("RESULT_LIMIT_EXCEEDED", failure.failureCode)
        assertEquals("Databricks result download exceeded the byte limit", failure.safeMessage)
        assertTrue(closed)
        assertTrue(sink.opened)
        assertTrue(sink.parts.isNotEmpty())
        assertTrue(sink.aborted)
        assertFalse(sink.completed)
        assertEquals(1, http.requests.count { it.uri().path.endsWith("/cancel") })
        assertEquals(0, http.requests.count { it.uri().path.contains("/result/chunks/") })
    }

    private fun recordedZeroManifest(): ObjectNode = javaClass.getResourceAsStream("/databricks/zero-chunk-manifest.json")!!.use { jsonMapper.readTree(it) as ObjectNode }
    private fun emptyResponse(): ObjectNode = jsonMapper.createObjectNode().put("statement_id", "statement-1").apply {
        putObject("status").put("state", "SUCCEEDED")
        set<ObjectNode>("manifest", recordedZeroManifest().put("format", "ARROW_STREAM"))
    }
    private fun responseClient(response: JsonNode, data: ByteArray = byteArrayOf()) = RecordedHttpClient { request -> when {
        request.uri().path.endsWith("/cancel") -> httpResponse(request, 200, byteArrayOf())
        request.uri().path == "/api/2.0/sql/statements" -> jsonResponse(request, 200, response)
        request.uri().host == "storage.cloud.databricks.com" -> httpResponse(request, 200, data)
        else -> error("unexpected fixture request")
    } }
    private suspend fun execute(http: RecordedHttpClient, sink: RecordingSink, executionContext: StartExecutionContext = context()): DriverPhysicalResult {
        val timing = operationTiming()
        val session = provider(http).openSession(connector(), credential("access_token" to "synthetic-token"), sink, timing)
        val outcome = try { session.enter(executionContext); withTimeout(5_000) { session.awaitPhysicalResult() } } finally { session.close() }
        timing.log(outcome)
        return outcome
    }
}
