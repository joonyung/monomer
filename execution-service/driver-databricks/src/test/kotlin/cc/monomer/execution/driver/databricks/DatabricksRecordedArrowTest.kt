package cc.monomer.execution.driver.databricks

import cc.monomer.execution.driver.spi.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.*
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.apache.arrow.vector.types.pojo.Schema
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel
import java.time.Clock
import java.time.Duration
import java.security.MessageDigest
import kotlin.test.*

class DatabricksRecordedArrowTest {
    @Test fun `recorded bound date timestamp decimal and typed null edges replay exactly`() = runBlocking {
        replay("arrow-bind-edges")
    }

    @Test fun `recorded small decimal and timestamp rounding response replays exactly`() = runBlocking {
        replay("arrow-bind-rounding")
    }

    @Test fun `recorded three row integer chunk replays exactly across bounded output batches`() = runBlocking {
        replay("arrow-chunk-small")
    }

    @Test fun `recorded calendar decimal and timestamp boundary response replays exactly`() = runBlocking {
        replay("arrow-edges")
    }

    @Test fun `recorded minimum int64 timestamp and NTZ replay exactly`() = runBlocking {
        replay("arrow-long-min")
    }

    @Test fun `recorded zero truncated JSON manifest retains unsupported format failure`() = runBlocking {
        val response = jsonMapper.readTree(resource("zero-byte-truncated.json")) as ObjectNode
        val manifest = response.path("manifest")
        assertEquals("JSON_ARRAY", manifest.path("format").asText())
        assertTrue(manifest.path("truncated").asBoolean())
        assertEquals(0, manifest.path("total_chunk_count").asInt())
        assertEquals(0, manifest.path("total_row_count").asInt())
        assertFalse(manifest.has("chunks"))
        val failure = failedResponse(response)
        assertEquals("DATABRICKS_EXECUTION_FAILED", failure.failureCode)
        assertEquals("Databricks result format is invalid", failure.safeMessage)
    }

    @Test fun `all recorded JSON manifests retain format counts and truncation and fail without delivery`() = runBlocking {
        val fixtures = jsonMapper.readTree(resource("json-array-manifests.json"))
        assertEquals(24, fixtures.size())
        assertEquals(1, fixtures.count { it.path("response").path("manifest").path("truncated").asBoolean() && it.path("response").path("manifest").path("total_chunk_count").asInt() == 0 })
        fixtures.forEach { fixture ->
            val response = (fixture.path("response") as ObjectNode).deepCopy()
            val manifest = response.path("manifest")
            assertEquals("JSON_ARRAY", manifest.path("format").asText(), fixture.path("label").asText())
            assertTrue(manifest.has("total_chunk_count"))
            assertTrue(manifest.has("total_row_count"))
            assertTrue(manifest.has("truncated"))
            assertFalse(response.has("result"))
            val failure = failedResponse(response)
            assertEquals("DATABRICKS_EXECUTION_FAILED", failure.failureCode, fixture.path("label").asText())
            assertEquals("Databricks result format is invalid", failure.safeMessage, fixture.path("label").asText())
        }
    }

    @Test fun `recorded native object failure without manifest remains native SQL failure`() = runBlocking {
        val response = jsonMapper.readTree(resource("missing-manifest-object-error.json")) as ObjectNode
        assertFalse(response.has("manifest"))
        assertEquals("FAILED", response.path("status").path("state").asText())
        assertEquals("42703", response.path("status").path("sql_state").asText())
        assertEquals("OBJECT_NOT_FOUND", failedResponse(response).failureCode)
    }

    private suspend fun failedResponse(response: ObjectNode): DriverPhysicalResult.Failed {
        assertFalse(response.has("statement_id"))
        response.put("statement_id", "recorded-statement")
        val http = RecordedHttpClient { request -> when {
            request.uri().path.endsWith("/cancel") -> httpResponse(request, 200, byteArrayOf())
            request.uri().path == "/api/2.0/sql/statements" -> jsonResponse(request, 200, response)
            else -> error("failed recorded response must not download")
        } }
        val sink = RecordingSink()
        val session = provider(http).openSession(connector(), credential("access_token" to "synthetic-token"), sink, operationTiming())
        return try {
            session.enter(context(artifactTarget))
            val failure = assertIs<DriverPhysicalResult.Failed>(withTimeout(5_000) { session.awaitPhysicalResult() })
            assertEquals(1, http.requests.count { it.uri().path == "/api/2.0/sql/statements" })
            assertEquals(1, http.requests.count { it.uri().path.endsWith("/cancel") })
            assertFalse(sink.opened)
            assertFalse(sink.completed)
            assertTrue(sink.frames.isEmpty())
            failure
        } finally { session.close() }
    }

    private suspend fun replay(name: String) {
        val data = resource("$name-chunk-0.arrow")
        val expected = jsonMapper.readTree(resource("$name-expected.json"))
        val response = jsonMapper.readTree(resource("$name.json")) as ObjectNode
        assertFalse(response.has("statement_id"))
        response.put("statement_id", "recorded-statement")
        response.path("result").path("external_links").forEachIndexed { index, link ->
            assertFalse(link.has("external_link"))
            assertFalse(link.has("http_headers"))
            (link as ObjectNode).put("external_link", "https://storage.cloud.databricks.com/recorded/$index")
                .put("expiration", "2099-01-01T00:00:00Z")
        }
        assertEquals(expected.path("sha256").asText(), MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) })
        val inputSchema = RootAllocator(ARROW_MEMORY_BYTES).use { allocator ->
            ArrowStreamReader(data.inputStream(), allocator).use { reader ->
                val schema = reader.vectorSchemaRoot.schema
                val values = schema.fields.map { mutableListOf<String?>() }
                while (reader.loadNextBatch()) collectValues(reader.vectorSchemaRoot, values)
                assertValues(expected, schema, values)
                schema
            }
        }
        listOf<QueryResultTarget>(QueryResultTarget.InlineStream(2, 64 * 1_024, 16 * 1_024), artifactTarget).forEach { target ->
            val executionContext = context(target, expectedNames = inputSchema.fields.map { it.name })
            val directSink = RecordingSink()
            val directHttp = RecordedHttpClient { request -> httpResponse(request, 200, data) }
            credential("access_token" to "synthetic-token").use { material ->
                val client = DatabricksStatementClient(connector(), material, directHttp, Dispatchers.IO, Clock.systemUTC(), Duration.ofSeconds(3), operationTiming())
                try {
                    val outcome = DatabricksArrowResults(client, Dispatchers.IO, Clock.systemUTC(), 1_024, operationTiming()).emit(
                        "recorded-statement", response, executionContext.assignment.deadlineEpochMillis,
                        executionContext.assignment.request, directSink,
                    )
                    assertEquals(response.path("manifest").path("total_row_count").asLong().toULong(), outcome.rowsProcessed)
                    assertOutput(expected, inputSchema, directSink, target)
                } finally { client.close() }
            }
            val http = RecordedHttpClient { request -> when {
                request.uri().path.endsWith("/cancel") -> httpResponse(request, 200, byteArrayOf())
                request.uri().path == "/api/2.0/sql/statements" -> jsonResponse(request, 200, response)
                request.uri().host == "storage.cloud.databricks.com" -> httpResponse(request, 200, data)
                else -> error("unexpected recorded fixture request")
            } }
            val sink = RecordingSink()
            val session = provider(http).openSession(connector(), credential("access_token" to "synthetic-token"), sink, operationTiming())
            try {
                session.enter(executionContext)
                val outcome = assertIs<DriverPhysicalResult.Succeeded>(withTimeout(5_000) { session.awaitPhysicalResult() }, name)
                assertEquals(response.path("manifest").path("total_row_count").asLong().toULong(), outcome.rowsProcessed)
                assertOutput(expected, inputSchema, sink, target)
                assertEquals(1, http.requests.count { it.uri().path == "/api/2.0/sql/statements" })
                assertEquals(1, http.requests.count { it.uri().host == "storage.cloud.databricks.com" })
                assertEquals(0, http.requests.count { it.uri().path.endsWith("/cancel") })
            } finally { session.close() }
        }
    }

    private fun assertOutput(expected: JsonNode, schema: Schema, sink: RecordingSink, target: QueryResultTarget) {
        val files = when (target) {
            is QueryResultTarget.InlineStream -> sink.frames.map(BinaryValue::copyBytes)
            is QueryResultTarget.ArtifactUpload -> {
                assertTrue(sink.completed)
                assertFalse(sink.aborted)
                listOf(sink.parts.flatMap { it.copyBytes().asList() }.toByteArray())
            }
        }
        val values = schema.fields.map { mutableListOf<String?>() }
        files.forEach { file ->
            RootAllocator(ARROW_MEMORY_BYTES).use { allocator ->
                ArrowFileReader(ByteArrayReadableSeekableByteChannel(file), allocator).use { reader ->
                    assertEquals(schema.fields, reader.vectorSchemaRoot.schema.fields)
                    while (reader.loadNextBatch()) collectValues(reader.vectorSchemaRoot, values)
                }
            }
        }
        assertValues(expected, schema, values)
    }

    private fun assertValues(expected: JsonNode, schema: Schema, values: List<List<String?>>) {
        assertEquals(expected.path("columns").size(), schema.fields.size)
        schema.fields.forEachIndexed { index, field ->
            val column = expected.path("columns")[index]
            assertEquals(column.path("name").asText(), field.name)
            assertEquals(column.path("type").asText(), field.type.toString(), field.name)
            assertEquals(column.path("nullable").asBoolean(), field.isNullable, field.name)
            assertEquals(column.path("values").map { if (it.isNull) null else it.asText() }, values[index], field.name)
        }
    }

    private fun collectValues(root: VectorSchemaRoot, values: List<MutableList<String?>>) {
        root.fieldVectors.forEachIndexed { column, vector ->
            repeat(root.rowCount) { row ->
                values[column].add(if (vector.isNull(row)) null else when (vector) {
                    is TimeStampVector -> vector.get(row).toString()
                    is DateDayVector -> vector.get(row).toString()
                    is DecimalVector -> vector.getObject(row).toPlainString()
                    is BigIntVector -> vector.get(row).toString()
                    is IntVector -> vector.get(row).toString()
                    is VarCharVector -> vector.getObject(row).toString()
                    else -> error("unsupported recorded fixture type")
                })
            }
        }
    }

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/databricks/recorded-arrow/$name")!!.use { it.readBytes() }
}
