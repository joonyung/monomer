package cc.monomer.execution.driver.databricks

import cc.monomer.execution.driver.spi.*
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.*
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.*

class DatabricksOperationTimingTest {
    @Test fun `artifact query summarizes OAuth submit polling read and upload without sensitive values`() = runBlocking {
        val schema = Schema(listOf(Field("result-value", FieldType.nullable(ArrowType.Utf8()), emptyList())))
        val data = arrowStream(schema, listOf(listOf(listOf("synthetic-result-secret"))))
        var polls = 0
        val http = RecordedHttpClient { request -> when {
            request.uri().path == "/oidc/v1/token" -> jsonResponse(request, 200, jsonMapper.createObjectNode()
                .put("access_token", "synthetic-oauth-secret").put("expires_in", 3_600))
            request.method() == "POST" -> jsonResponse(request, 200, pending())
            request.uri().host == "storage.cloud.databricks.com" -> httpResponse(request, 200, data)
            else -> jsonResponse(request, 200, if (++polls == 1) pending() else snapshot(schema = schema))
        } }
        val timing = operationTiming()
        val records = capture {
            val outcome = query(http, RecordingSink(), context(artifactTarget,
                parameters = listOf(QueryParameter.Text("synthetic-binding-secret", "value"))), timing,
                credential("oauth_client_id" to "synthetic-client-secret", "oauth_client_secret" to "synthetic-password-secret"))
            assertIs<DriverPhysicalResult.Succeeded>(outcome)
            timing.log(outcome)
            timing.log(outcome)
        }
        val log = assertSummary(records, "QUERY", "SUCCEEDED", "none", "none")
        assertEquals("2", fields(log).getValue("poll_count"))
        assertMeasured(log, "prepare", "credential", "connect", "execute", "wait", "read", "upload", "cleanup")
        assertEquals("null", fields(log).getValue("delivery_ms"))
    }

    @Test fun `inline query has one summary despite several frames`() = runBlocking {
        val sink = RecordingSink()
        val http = RecordedHttpClient { request -> if (request.method() == "POST") {
            jsonResponse(request, 200, snapshot(listOf(4), listOf(link(0, 0, 4))))
        } else httpResponse(request, 200, integerStream(listOf(1L, 2L, 3L, 4L))) }
        val timing = operationTiming()
        val records = capture {
            val outcome = query(http, sink, context(), timing)
            assertIs<DriverPhysicalResult.Succeeded>(outcome)
            timing.log(outcome)
        }
        assertTrue(sink.frames.size > 1)
        val log = assertSummary(records, "QUERY", "SUCCEEDED", "none", "none")
        assertMeasured(log, "execute", "wait", "read", "delivery")
        assertEquals("null", fields(log).getValue("upload_ms"))
    }

    @Test fun `query failure summary identifies prepare submit poll fetch upload and inline delivery`() = runBlocking {
        val cases = listOf("PREPARE", "CREDENTIAL", "EXECUTE", "WAIT", "READ", "UPLOAD", "DELIVERY")
        for (phase in cases) {
            val sink = RecordingSink().apply {
                if (phase == "UPLOAD") onAppend = { throw IOException("synthetic-upload-secret") }
                if (phase == "DELIVERY") onEmit = { throw IOException("synthetic-delivery-secret") }
            }
            val target = if (phase == "UPLOAD") artifactTarget else context().assignment.request.resultTarget
            val parameters = if (phase == "PREPARE") listOf(QueryParameter.Bytes(BinaryValue.copyOf(byteArrayOf(1)), "value")) else emptyList()
            val material = if (phase == "CREDENTIAL") credential("oauth_client_id" to "synthetic-client-secret", "oauth_client_secret" to "synthetic-password-secret")
                else credential("access_token" to "synthetic-token-secret")
            val http = RecordedHttpClient { request -> when {
                request.uri().path.endsWith("/cancel") -> httpResponse(request, 200, byteArrayOf())
                request.uri().path == "/oidc/v1/token" -> jsonResponse(request, 401, jsonMapper.createObjectNode().put("message", "synthetic-token-secret"))
                request.method() == "POST" && phase == "EXECUTE" -> CompletableFuture.failedFuture(IOException("synthetic-submit-secret"))
                request.method() == "POST" -> jsonResponse(request, 200, if (phase == "WAIT") pending() else snapshot())
                phase == "WAIT" -> jsonResponse(request, 403, jsonMapper.createObjectNode().put("message", "synthetic-binding-secret"))
                phase == "READ" -> httpResponse(request, 500, "synthetic-result-secret".toByteArray())
                else -> httpResponse(request, 200, integerStream(listOf(1L)))
            } }
            val timing = operationTiming()
            var outcome: DriverPhysicalResult? = null
            val records = capture {
                outcome = query(http, sink, context(target, parameters = parameters), timing, material)
                timing.log(requireNotNull(outcome))
            }
            val code = when (phase) {
                "PREPARE" -> "QUERY_BIND_UNSUPPORTED"
                "CREDENTIAL" -> "AUTHENTICATION_FAILED"
                "EXECUTE" -> "CONNECTION_FAILED"
                "WAIT" -> "PERMISSION_DENIED"
                else -> "DATABRICKS_EXECUTION_FAILED"
            }
            assertEquals(code, assertIs<DriverPhysicalResult.Failed>(outcome).failureCode)
            val log = assertSummary(records, "QUERY", "FAILED", phase, code)
            assertMeasured(log, phase.lowercase())
        }
    }

    @Test fun `validation success and failure retain submit wait read phases and classified codes`() = runBlocking {
        for (phase in listOf("none", "VALIDATE", "WAIT", "READ")) {
            val timing = operationTiming(DriverOperation.VALIDATE)
            val http = RecordedHttpClient { request -> when {
                request.uri().path.endsWith("/cancel") -> httpResponse(request, 200, byteArrayOf())
                request.method() == "POST" && phase == "VALIDATE" -> jsonResponse(request, 401, jsonMapper.createObjectNode().put("message", "synthetic-password-secret"))
                request.method() == "POST" -> jsonResponse(request, 200, pending())
                request.uri().host == "storage.cloud.databricks.com" && phase == "READ" -> httpResponse(request, 500, "synthetic-result-secret".toByteArray())
                request.uri().host == "storage.cloud.databricks.com" -> httpResponse(request, 200, integerStream(listOf(1L)))
                phase == "WAIT" -> jsonResponse(request, 403, jsonMapper.createObjectNode().put("message", "synthetic-binding-secret"))
                else -> jsonResponse(request, 200, snapshot())
            } }
            var outcome: DriverPhysicalResult = DriverPhysicalResult.Succeeded(0uL, 0uL, null)
            val records = capture {
                credential("access_token" to "synthetic-token-secret").use { material ->
                    try {
                        provider(http).validateRawSql(connector(), material, "SELECT :value /* synthetic-sql-secret */",
                            listOf(QueryParameter.Text("synthetic-binding-secret", "value")), System.currentTimeMillis() + 10_000, timing)
                    } catch (failure: RawSqlValidationException) {
                        outcome = DriverPhysicalResult.Failed(failure.failureCode, requireNotNull(failure.message))
                    }
                }
                timing.log(outcome)
            }
            val code = when (phase) {
                "none" -> "none"
                "VALIDATE" -> "AUTHENTICATION_FAILED"
                "WAIT" -> "PERMISSION_DENIED"
                else -> "RAW_SQL_VALIDATION_FAILED"
            }
            val log = assertSummary(records, "VALIDATE", if (phase == "none") "SUCCEEDED" else "FAILED", phase, code)
            assertMeasured(log, "validate")
            if (phase != "VALIDATE") assertMeasured(log, "wait")
            if (phase in listOf("none", "READ")) assertMeasured(log, "read")
        }
    }

    @Test fun `poll deadline and explicit cancellation retain waiting phase`() = runBlocking {
        val mutableClock = object : Clock() {
            var epoch = Instant.parse("2026-10-08T00:00:00Z").toEpochMilli()
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant() = Instant.ofEpochMilli(epoch)
        }
        val deadline = mutableClock.millis() + 10_000
        val deadlineHttp = RecordedHttpClient { request -> when {
            request.uri().path.endsWith("/cancel") -> httpResponse(request, 200, byteArrayOf())
            request.method() == "POST" -> jsonResponse(request, 200, pending())
            else -> { mutableClock.epoch = deadline; jsonResponse(request, 200, pending()) }
        } }
        val deadlineTiming = operationTiming()
        val deadlineRecords = capture {
            val outcome = query(deadlineHttp, RecordingSink(), context(deadline = deadline), deadlineTiming, clock = mutableClock)
            assertEquals("RUN_DEADLINE_EXCEEDED", assertIs<DriverPhysicalResult.Failed>(outcome).failureCode)
            deadlineTiming.log(outcome)
        }
        assertSummary(deadlineRecords, "QUERY", "FAILED", "WAIT", "RUN_DEADLINE_EXCEEDED")

        val observing = CompletableDeferred<Unit>()
        val cancelHttp = RecordedHttpClient { request -> when {
            request.uri().path.endsWith("/cancel") -> httpResponse(request, 200, byteArrayOf())
            request.method() == "POST" -> jsonResponse(request, 200, pending())
            else -> { observing.complete(Unit); CompletableFuture() }
        } }
        val cancelTiming = operationTiming()
        val cancelRecords = capture {
            val session = provider(cancelHttp).openSession(connector(), credential("access_token" to "synthetic-token-secret"), RecordingSink(), cancelTiming)
            try {
                session.enter(context())
                withTimeout(5_000) { observing.await() }
                session.cancel()
                val outcome = withTimeout(5_000) { session.awaitPhysicalResult() }
                assertEquals(DriverPhysicalResult.Cancelled, outcome)
                session.close()
                cancelTiming.log(outcome)
            } finally { session.close() }
        }
        assertSummary(cancelRecords, "QUERY", "CANCELLED", "WAIT", "CANCELLED")
        Unit
    }

    private suspend fun query(
        http: RecordedHttpClient,
        sink: RecordingSink,
        context: StartExecutionContext,
        timing: DriverOperationTiming,
        material: MaterializedCredential = credential("access_token" to "synthetic-token-secret"),
        clock: Clock = Clock.systemUTC(),
    ): DriverPhysicalResult {
        val unsigned = context.assignment.request.copy(sql = "SELECT :value /* synthetic-sql-secret */")
        val request = unsigned.copy(canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(unsigned))
        val session = provider(http, clock).openSession(connector(), material, sink, timing)
        return try {
            session.enter(context.copy(assignment = context.assignment.copy(request = request)))
            withTimeout(5_000) { session.awaitPhysicalResult() }
        } finally { session.close() }
    }

    private fun pending(): ObjectNode = jsonMapper.createObjectNode().put("statement_id", "statement-1")
        .set("status", jsonMapper.createObjectNode().put("state", "RUNNING"))

    private suspend fun capture(block: suspend () -> Unit): List<LogRecord> {
        val records = java.util.concurrent.CopyOnWriteArrayList<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { records.add(record) }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val loggers = listOf(Logger.getLogger(DriverOperationTiming::class.java.name), Logger.getLogger(DatabricksStatementClient::class.java.name))
        loggers.forEach { it.addHandler(handler) }
        return try { block(); records.toList() } finally { loggers.forEach { it.removeHandler(handler) } }
    }

    private fun assertSummary(records: List<LogRecord>, operation: String, outcome: String, phase: String, code: String): String {
        val record = records.single()
        assertEquals(java.util.logging.Level.INFO, record.level)
        assertNull(record.thrown)
        val log = record.message
        assertTrue(log.startsWith("driver_operation_timing "))
        val fields = fields(log)
        assertEquals("DATABRICKS", fields.getValue("driver"))
        assertEquals(operation, fields.getValue("operation"))
        assertEquals(outcome, fields.getValue("outcome"))
        assertEquals(phase, fields.getValue("failure_phase"))
        assertEquals(code, fields.getValue("failure_code"))
        assertEquals("019f0000-0000-7000-8000-000000000001", fields.getValue("run_id"))
        assertEquals("019f0000-0000-7000-8000-000000000002", fields.getValue("workspace_id"))
        assertTrue(fields.getValue("total_ms").toLong() >= 0)
        listOf("synthetic-", "SELECT", "DESCRIBE", "result-value", "https://", "dbc-1234", "artifacts/", "Bearer", "Basic").forEach { secret ->
            assertFalse(log.contains(secret), "logged sensitive fixture: $secret")
        }
        return log
    }

    private fun assertMeasured(log: String, vararg phases: String) {
        val fields = fields(log)
        phases.forEach { assertTrue(fields.getValue("${it}_ms").toLong() >= 0, "unmeasured phase: $it") }
    }

    private fun fields(log: String): Map<String, String> = log.split(' ').drop(1).associate {
        val pair = it.split('=', limit = 2)
        pair[0] to pair[1]
    }
}
