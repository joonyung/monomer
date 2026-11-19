package cc.monomer.execution.driver.databricks

import cc.monomer.execution.driver.spi.*
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.*
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel
import java.net.http.HttpRequest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DatabricksExecutionDriverTest {
    @Test fun `multichunk multiple links and batches stream once without download authentication`() = runBlocking {
        val recorded = snapshot(listOf(3,2,1), listOf(link(0,0,3),link(1,3,2,next=2)))
        val downloads = mapOf("/chunk/0" to integerStream(listOf(1L,2L,3L)), "/chunk/1" to integerStream(listOf(4L),listOf(5L)), "/chunk/2" to integerStream(listOf(6L)))
        val http = RecordedHttpClient { request ->
            when {
                request.uri().path == "/api/2.0/sql/statements" -> jsonResponse(request,200,recorded)
                request.uri().path.endsWith("/result/chunks/2") -> jsonResponse(request,200,jsonMapper.createObjectNode().set<ObjectNode>("external_links",jsonMapper.createArrayNode().add(link(2,5,1))))
                else -> httpResponse(request,200,downloads.getValue(request.uri().path))
            }
        }
        val sink = RecordingSink()
        val result = run(http,sink)
        assertEquals(6uL, assertIs<DriverPhysicalResult.Succeeded>(result).rowsProcessed)
        assertEquals(listOf(1L,2L,3L,4L,5L,6L), sink.frames.flatMap(::integerFileValues))
        assertEquals(1,http.requests.count { it.method()=="POST" })
        val submitted = requestJson(http.requests.first())
        assertEquals("ARROW_STREAM",submitted.path("format").asText())
        assertEquals("EXTERNAL_LINKS",submitted.path("disposition").asText())
        assertEquals("0s",submitted.path("wait_timeout").asText())
        assertEquals("main",submitted.path("catalog").asText())
        assertEquals("analytics",submitted.path("schema").asText())
        assertEquals("abcd1234",submitted.path("warehouse_id").asText())
        assertFalse(submitted.has("row_limit")); assertFalse(submitted.has("byte_limit"))
        http.requests.filter { it.uri().host == "storage.cloud.databricks.com" }.forEach {
            assertTrue(it.headers().firstValue("Authorization").isEmpty)
            assertTrue(it.headers().firstValue("Cookie").isEmpty)
        }
    }

    @Test fun `artifact streams batches with bounded parts and publishes only complete manifest`() = runBlocking {
        val http = successful(integerStream(listOf(1L,2L,3L),listOf(4L)),snapshot(listOf(4),listOf(link(0,0,4))))
        val sink = RecordingSink()
        val result = assertIs<DriverPhysicalResult.Succeeded>(run(http,sink,context(artifactTarget)))
        assertEquals(4uL,result.manifest!!.rowCount)
        assertTrue(sink.completed); assertFalse(sink.aborted)
        assertEquals(listOf(1L,2L,3L,4L),integerFileValues(BinaryValue.copyOf(sink.parts.flatMap { it.copyBytes().asList() }.toByteArray())))
        assertTrue(sink.parts.all { it.size <= artifactTarget.maximumMultipartPartBytes })
        assertEquals(11,requestJson(http.requests.first()).path("row_limit").asInt())
    }

    @Test fun `empty result preserves Arrow schema and compares expected names`() = runBlocking {
        val data = integerStream(emptyList())
        val response = snapshot(listOf(0),listOf(link(0,0,0)))
        val sink = RecordingSink()
        assertEquals(0uL,assertIs<DriverPhysicalResult.Succeeded>(run(successful(data,response),sink,context(expectedNames=listOf("value")))).rowsProcessed)
        assertEquals(emptyList(),sink.frames.flatMap(::integerFileValues))
        val failed = assertIs<DriverPhysicalResult.Failed>(run(successful(data,response),RecordingSink(),context(expectedNames=listOf("wrong"))))
        assertEquals("RESULT_COLUMN_MISMATCH",failed.failureCode)
        val artifactSink = RecordingSink()
        assertIs<DriverPhysicalResult.Succeeded>(run(successful(data,response),artifactSink,context(artifactTarget)))
        assertTrue(artifactSink.completed)
    }

    @Test fun `row boundary is exact and truncated under boundary fails generically`() = runBlocking {
        val target = artifactTarget.copy(maximumArtifactRows=2uL)
        assertIs<DriverPhysicalResult.Succeeded>(run(successful(integerStream(listOf(1L,2L)),snapshot(listOf(2),listOf(link(0,0,2)))),RecordingSink(),context(target)))
        val extra = assertIs<DriverPhysicalResult.Failed>(run(successful(integerStream(listOf(1L,2L,3L)),snapshot(listOf(3),listOf(link(0,0,3)))),RecordingSink(),context(target)))
        assertEquals("RESULT_ROW_LIMIT_EXCEEDED",extra.failureCode)
        val truncated = snapshot(listOf(2),listOf(link(0,0,2))) as ObjectNode
        (truncated.path("manifest") as ObjectNode).put("truncated",true)
        assertEquals("DATABRICKS_EXECUTION_FAILED",assertIs<DriverPhysicalResult.Failed>(run(successful(integerStream(listOf(1L,2L)),truncated),RecordingSink(),context(target))).failureCode)
    }

    @Test fun `manifest and chunk mismatches never complete and abort opened multipart`() = runBlocking {
        val changes: List<(ObjectNode)->Unit> = listOf(
            { (it.path("manifest").path("chunks")[0] as ObjectNode).put("chunk_index",1) },
            { (it.path("result").path("external_links")[0] as ObjectNode).put("row_offset",1) },
            { (it.path("manifest") as ObjectNode).put("total_row_count",99) },
            { (it.path("manifest").path("schema").path("columns")[0] as ObjectNode).put("type_name","STRING") },
        )
        changes.forEach { change ->
            val value = snapshot(listOf(2),listOf(link(0,0,2))) as ObjectNode
            change(value)
            assertIs<DriverPhysicalResult.Failed>(run(successful(integerStream(listOf(1L,2L)),value),RecordingSink(),context(artifactTarget)))
        }
        val sink = RecordingSink()
        val wrongCount = snapshot(listOf(2),listOf(link(0,0,2)))
        assertIs<DriverPhysicalResult.Failed>(run(successful(integerStream(listOf(1L)),wrongCount),sink,context(artifactTarget)))
        assertTrue(sink.aborted); assertFalse(sink.completed)
    }

    @Test fun `expired local link and download403 refresh once without SQL resubmission`() = runBlocking {
        listOf(true,false).forEach { localExpiry ->
            val initial = link(0,0,1,expiration=if(localExpiry) "2000-01-01T00:00:00Z" else "2099-01-01T00:00:00Z")
            var refreshes=0; var downloads=0
            val http = RecordedHttpClient { request -> when {
                request.uri().path == "/api/2.0/sql/statements" -> jsonResponse(request,200,snapshot(links=listOf(initial)))
                request.uri().path.endsWith("/result/chunks/0") -> { refreshes++; jsonResponse(request,200,jsonMapper.createObjectNode().set<ObjectNode>("external_links",jsonMapper.createArrayNode().add(link(0,0,1,url="https://storage.cloud.databricks.com/refreshed?new-secret")))) }
                else -> { downloads++; httpResponse(request,if(!localExpiry && downloads==1) 403 else 200,integerStream(listOf(1L))) }
            } }
            assertIs<DriverPhysicalResult.Succeeded>(run(http,RecordingSink()))
            assertEquals(1,refreshes); assertEquals(1,http.requests.count { it.method()=="POST" })
        }
        val http = RecordedHttpClient { request -> when {
            request.uri().path == "/api/2.0/sql/statements" -> jsonResponse(request,200,snapshot())
            request.uri().path.endsWith("/result/chunks/0") -> jsonResponse(request,200,jsonMapper.createObjectNode().set<ObjectNode>("external_links",jsonMapper.createArrayNode().add(link(0,0,1))))
            request.uri().path.endsWith("/cancel") -> httpResponse(request,200,byteArrayOf())
            else -> httpResponse(request,403,byteArrayOf())
        } }
        assertIs<DriverPhysicalResult.Failed>(run(http,RecordingSink()))
        assertEquals(1,http.requests.count { it.uri().path.endsWith("/result/chunks/0") })
    }

    @Test fun `failed HTTP and structured statement errors preserve codes and redact every cause`() = runBlocking {
        val httpCases = listOf(401 to "AUTHENTICATION_FAILED",403 to "PERMISSION_DENIED",429 to "RESOURCE_EXHAUSTED",500 to "DATABRICKS_EXECUTION_FAILED",400 to "DATABRICKS_EXECUTION_FAILED",404 to "DATABRICKS_EXECUTION_FAILED")
        for ((status,code) in httpCases) {
            val http = RecordedHttpClient { request -> jsonResponse(request,status,jsonMapper.readTree("{\"error_code\":\"BAD_REQUEST\",\"message\":\"synthetic-token https://evil.test/signed-secret bound-secret\"}")) }
            val result = assertIs<DriverPhysicalResult.Failed>(run(http,RecordingSink()))
            assertEquals(code,result.failureCode)
            assertFalse(result.safeMessage.contains("secret"))
            assertEquals(1,http.requests.size)
        }
        listOf("42601" to "SYNTAX_ERROR","42P01" to "OBJECT_NOT_FOUND","42501" to "PERMISSION_DENIED","28000" to "AUTHENTICATION_FAILED","08001" to "CONNECTION_FAILED","53300" to "RESOURCE_EXHAUSTED","XX000" to "DATABRICKS_EXECUTION_FAILED").forEach { (state,code) ->
            val response = jsonMapper.readTree("{\"statement_id\":\"statement-1\",\"status\":{\"state\":\"FAILED\",\"sql_state\":\"$state\",\"error\":{\"error_code\":\"BAD_REQUEST\",\"message\":\"synthetic-token signed-secret bound-secret\",\"sql_state\":\"42501\"}}}")
            val http = RecordedHttpClient { request -> if(request.uri().path.endsWith("/cancel")) httpResponse(request,200,byteArrayOf()) else jsonResponse(request,200,response) }
            assertEquals(code,assertIs<DriverPhysicalResult.Failed>(run(http,RecordingSink())).failureCode)
            credential("access_token" to "synthetic-token").use { credential ->
                val rejected = assertFailsWith<RawSqlValidationException> { provider(http).validateRawSql(connector(),credential,"SELECT :secret",listOf(QueryParameter.Text("bound-secret","secret")),System.currentTimeMillis()+10_000, operationTiming(DriverOperation.VALIDATE)) }
                assertEquals(if(code in setOf("SYNTAX_ERROR","OBJECT_NOT_FOUND")) "RAW_SQL_VALIDATION_REJECTED" else if(code=="DATABRICKS_EXECUTION_FAILED") "RAW_SQL_VALIDATION_FAILED" else code,rejected.failureCode)
                assertNull(rejected.cause)
                assertFalse(rejected.stackTraceToString().contains("signed-secret"))
            }
        }
    }

    @Test fun `validation consumes full metadata and main keeps namespace and exact parameters`() = runBlocking {
        val requests = mutableListOf<com.fasterxml.jackson.databind.JsonNode>()
        var submissions=0
        val http = RecordedHttpClient { request -> when {
            request.uri().path=="/api/2.0/sql/statements" -> {
                requests.add(requestJson(request)); submissions++
                jsonResponse(request,200,snapshot(listOf(3),listOf(link(0,0,3))))
            }
            else -> httpResponse(request,200,integerStream(listOf(1L,2L),listOf(3L)))
        } }
        val params = listOf(QueryParameter.Timestamp(Instant.parse("2026-10-07T00:00:00.123456Z"),"value"))
        credential("access_token" to "synthetic-token").use { material ->
            provider(http).validateRawSql(connector(),material,"SELECT :value",params,System.currentTimeMillis()+10_000, operationTiming(DriverOperation.VALIDATE))
        }
        val result = assertIs<DriverPhysicalResult.Succeeded>(run(http,RecordingSink(),context(parameters=params)))
        assertEquals(3uL,result.rowsProcessed)
        assertEquals(2,submissions)
        assertEquals("DESCRIBE QUERY SELECT :value",requests[0].path("statement").asText())
        assertEquals("SELECT :value",requests[1].path("statement").asText())
        listOf("warehouse_id","catalog","schema","parameters").forEach { assertEquals(requests[0].path(it),requests[1].path(it)) }
        credential("access_token" to "synthetic-token").use { credential ->
            val failure = assertFailsWith<RawSqlValidationException> {
                provider(successful(integerStream(emptyList()),snapshot(listOf(0),listOf(link(0,0,0)))))
                    .validateRawSql(connector(),credential,"SELECT 1",emptyList(),System.currentTimeMillis()+10_000, operationTiming(DriverOperation.VALIDATE))
            }
            assertEquals("RAW_SQL_VALIDATION_REJECTED",failure.failureCode)
        }
    }

    @Test fun `POST response loss never retries and late ID after cancel is cancelled once`() = runBlocking {
        val lost = RecordedHttpClient { CompletableFuture.failedFuture(java.io.IOException("signed-secret synthetic-token")) }
        assertEquals("CONNECTION_FAILED",assertIs<DriverPhysicalResult.Failed>(run(lost,RecordingSink())).failureCode)
        assertEquals(1,lost.requests.size)
        val pending = CompletableFuture<java.net.http.HttpResponse<java.io.InputStream>>()
        val sent = CompletableDeferred<HttpRequest>()
        val cancelled = CompletableDeferred<Unit>()
        val http = RecordedHttpClient { request ->
            if(request.uri().path.endsWith("/cancel")) { cancelled.complete(Unit); httpResponse(request,200,byteArrayOf()) }
            else { sent.complete(request); pending }
        }
        val material = credential("access_token" to "synthetic-token")
        val session = provider(http).openSession(connector(),material,RecordingSink(), operationTiming())
        session.enter(context())
        val request = withTimeout(2_000) { sent.await() }
        session.cancel()
        assertEquals(DriverPhysicalResult.Cancelled,session.awaitPhysicalResult())
        session.close()
        pending.complete(inputResponse(request,200,java.io.ByteArrayInputStream("{\"statement_id\":\"late-id\",\"status\":{\"state\":\"RUNNING\"}}".toByteArray())))
        withTimeout(2_000) { cancelled.await() }
        assertEquals(1,http.requests.count { it.uri().path.endsWith("/cancel") })
    }

    @Test fun `cold start respects local deadline and failed poll is not retried`() = runBlocking {
        val polling = AtomicInteger()
        val http = RecordedHttpClient { request -> when {
            request.uri().path.endsWith("/cancel") -> httpResponse(request,500,byteArrayOf())
            request.method()=="POST" -> jsonResponse(request,200,jsonMapper.readTree("{\"statement_id\":\"statement-1\",\"status\":{\"state\":\"PENDING\"}}"))
            else -> { polling.incrementAndGet(); jsonResponse(request,200,jsonMapper.readTree("{\"status\":{\"state\":\"RUNNING\"}}")) }
        } }
        val result=assertIs<DriverPhysicalResult.Failed>(run(http,RecordingSink(),context(deadline=System.currentTimeMillis()+80)))
        assertEquals("RUN_DEADLINE_EXCEEDED",result.failureCode)
        assertTrue(polling.get()>0)
        assertEquals(1,http.requests.count { it.uri().path.endsWith("/cancel") })
        val failedPoll = RecordedHttpClient { request -> if(request.uri().path.endsWith("/cancel")) httpResponse(request,200,byteArrayOf()) else if(request.method()=="POST") jsonResponse(request,200,jsonMapper.readTree("{\"statement_id\":\"statement-1\",\"status\":{\"state\":\"PENDING\"}}")) else CompletableFuture.failedFuture(java.net.http.HttpTimeoutException("signed-secret")) }
        assertEquals("TIMEOUT",assertIs<DriverPhysicalResult.Failed>(run(failedPoll,RecordingSink())).failureCode)
        assertEquals(1,failedPoll.requests.count { it.method()=="GET" })
    }

    @Test fun `CLOSED unknown missing results and fetch404 fail without object SQL misclassification`() = runBlocking {
        listOf("CLOSED","UNRECOGNIZED","SUCCEEDED").forEach { state ->
            val http = RecordedHttpClient { request -> if(request.uri().path.endsWith("/cancel")) httpResponse(request,200,byteArrayOf()) else jsonResponse(request,200,jsonMapper.readTree("{\"statement_id\":\"statement-1\",\"status\":{\"state\":\"$state\"}}")) }
            assertEquals("DATABRICKS_EXECUTION_FAILED",assertIs<DriverPhysicalResult.Failed>(run(http,RecordingSink())).failureCode)
        }
        val value = snapshot(listOf(1,1),listOf(link(0,0,1,next=1)))
        val sink=RecordingSink()
        val http = RecordedHttpClient { request -> when {
            request.uri().path.endsWith("/cancel") -> httpResponse(request,200,byteArrayOf())
            request.method()=="POST" -> jsonResponse(request,200,value)
            request.uri().path.endsWith("/result/chunks/1") -> jsonResponse(request,404,jsonMapper.readTree("{\"error_code\":\"NOT_FOUND\"}"))
            else -> httpResponse(request,200,integerStream(listOf(1L)))
        } }
        assertEquals("DATABRICKS_EXECUTION_FAILED",assertIs<DriverPhysicalResult.Failed>(run(http,sink,context(artifactTarget))).failureCode)
        assertTrue(sink.aborted)
    }

    @Test fun `binary values and typed null reject query and validation before PAT or OAuth HTTP`() = runBlocking {
        val binaryParameters = listOf(
            QueryParameter.Bytes(BinaryValue.copyOf(byteArrayOf(1)), "value"),
            QueryParameter.Null(NullQueryParameterType.BYTES, "value"),
        )
        val authentication = listOf(
            listOf("access_token" to "synthetic-token"),
            listOf("oauth_client_id" to "synthetic-client", "oauth_client_secret" to "synthetic-client-secret"),
        )
        val expectedMessage = "Databricks binary parameters, including typed nulls, are unsupported. Use a Semantic query."
        authentication.forEach { fields ->
            binaryParameters.forEach { parameter ->
                val http = RecordedHttpClient { error("binary binding must fail before HTTP") }
                val sink = RecordingSink()
                val session = provider(http).openSession(connector(), credential(*fields.toTypedArray()), sink, operationTiming())
                try {
                    session.enter(context(artifactTarget, parameters = listOf(parameter)))
                    val failure = assertIs<DriverPhysicalResult.Failed>(withTimeout(2_000) { session.awaitPhysicalResult() })
                    assertEquals("QUERY_BIND_UNSUPPORTED", failure.failureCode)
                    assertEquals(expectedMessage, failure.safeMessage)
                    assertFalse(sink.opened)
                    assertFalse(sink.completed)
                    assertFalse(sink.aborted)
                    assertTrue(sink.parts.isEmpty())
                    assertTrue(sink.frames.isEmpty())
                } finally { session.close() }
                assertTrue(http.requests.isEmpty())
                credential(*fields.toTypedArray()).use { material ->
                    val failure = assertFailsWith<RawSqlValidationException> {
                        provider(http).validateRawSql(connector(), material, "SELECT :value", listOf(parameter), System.currentTimeMillis() + 10_000, operationTiming(DriverOperation.VALIDATE))
                    }
                    assertEquals("QUERY_BIND_UNSUPPORTED", failure.failureCode)
                    assertEquals(expectedMessage, failure.message)
                    assertNull(failure.cause)
                }
                assertTrue(http.requests.isEmpty())
                assertEquals(0, http.requests.count { it.uri().path.endsWith("/cancel") })
            }
        }
    }

    @Test fun `unsupported bind and invalid parameter names fail before network`() = runBlocking {
        val http=RecordedHttpClient { error("must not send") }
        val bytes=listOf(QueryParameter.Bytes(BinaryValue.copyOf(byteArrayOf(1)),"value"))
        assertEquals("QUERY_BIND_UNSUPPORTED",assertIs<DriverPhysicalResult.Failed>(run(http,RecordingSink(),context(parameters=bytes))).failureCode)
        listOf(listOf(QueryParameter.Text("x","")),listOf(QueryParameter.Text("x","a"),QueryParameter.Text("y","A")),listOf(QueryParameter.Text("x","1bad"))).forEach { parameters ->
            val material=credential("access_token" to "token")
            val session=provider(http).openSession(connector(),material,RecordingSink(), operationTiming())
            try { assertFailsWith<IllegalArgumentException> { session.enter(context(parameters=parameters)) } } finally { session.close() }
        }
        assertTrue(http.requests.isEmpty())
    }

    @Test fun `OAuth requests exact M2M scope caches token and never authenticates external download`() = runBlocking {
        var tokenRequests=0
        val http=RecordedHttpClient { request -> when {
            request.uri().path=="/oidc/v1/token" -> { tokenRequests++; assertEquals("grant_type=client_credentials&scope=all-apis",requestText(request)); jsonResponse(request,200,jsonMapper.readTree("{\"access_token\":\"synthetic-oauth-token\",\"expires_in\":3600}")) }
            request.uri().path=="/api/2.0/sql/statements" -> { assertEquals("Bearer synthetic-oauth-token",request.headers().firstValue("Authorization").get()); jsonResponse(request,200,snapshot()) }
            else -> { assertTrue(request.headers().firstValue("Authorization").isEmpty); httpResponse(request,200,integerStream(listOf(1L))) }
        } }
        val material=credential("oauth_client_id" to "client-id","oauth_client_secret" to "client-secret")
        val session=provider(http).openSession(connector(),material,RecordingSink(), operationTiming())
        try { session.enter(context()); assertIs<DriverPhysicalResult.Succeeded>(session.awaitPhysicalResult()) } finally { session.close() }
        assertEquals(1,tokenRequests); assertTrue(material.isZeroized())
    }

    @Test fun `external link scheme userinfo redirect and global POST retry setting fail safely`() = runBlocking {
        listOf("http://storage.test/path?secret","https://user:secret@storage.test/path","file:///secret").forEach { url ->
            val http=successful(integerStream(listOf(1L)),snapshot(links=listOf(link(0,0,1,url=url))))
            val failed=assertIs<DriverPhysicalResult.Failed>(run(http,RecordingSink()))
            assertFalse(failed.safeMessage.contains("secret"))
            assertEquals(0,http.requests.count { it.uri().host=="storage.test" })
        }
        val redirect=RecordedHttpClient { request -> if(request.method()=="POST") jsonResponse(request,200,snapshot()) else httpResponse(request,302,byteArrayOf()) }
        assertIs<DriverPhysicalResult.Failed>(run(redirect,RecordingSink()))
        val previous=System.getProperty("jdk.httpclient.enableAllMethodRetry")
        try { listOf("true","").forEach { System.setProperty("jdk.httpclient.enableAllMethodRetry",it); assertFailsWith<IllegalArgumentException> { provider(successful(integerStream(listOf(1L)))) } } }
        finally { if(previous==null) System.clearProperty("jdk.httpclient.enableAllMethodRetry") else System.setProperty("jdk.httpclient.enableAllMethodRetry",previous) }
    }

    @Test fun `GET and OAuth blocked body is closed at deadline`() = runBlocking {
        listOf(false,true).forEach { oauth ->
            val started=CompletableDeferred<Unit>()
            val closed=CompletableDeferred<Unit>()
            val released=java.util.concurrent.CountDownLatch(1)
            val body=object : java.io.InputStream() {
                override fun read(): Int {
                    started.complete(Unit)
                    try { released.await() } catch (_: InterruptedException) { }
                    throw java.io.IOException("body closed synthetic-signed-secret")
                }
                override fun close() { released.countDown(); closed.complete(Unit) }
            }
            val http=RecordedHttpClient { request -> when {
                request.uri().path.endsWith("/cancel") -> httpResponse(request,200,byteArrayOf())
                !oauth && request.method()=="POST" -> jsonResponse(request,200,jsonMapper.readTree("{\"statement_id\":\"statement-1\",\"status\":{\"state\":\"PENDING\"}}"))
                else -> CompletableFuture.completedFuture(inputResponse(request,200,body))
            } }
            val material=if(oauth) credential("oauth_client_id" to "client","oauth_client_secret" to "secret") else credential("access_token" to "token")
            val session=provider(http).openSession(connector(),material,RecordingSink(), operationTiming())
            try {
                session.enter(context(deadline=System.currentTimeMillis()+250))
                withTimeout(2_000) { started.await() }
                val result=withTimeout(2_000) { session.awaitPhysicalResult() }
                assertEquals("RUN_DEADLINE_EXCEEDED",assertIs<DriverPhysicalResult.Failed>(result).failureCode)
                withTimeout(2_000) { closed.await() }
            } finally { released.countDown(); session.close() }
        }
    }

    @Test fun `safe native reason is preserved while binding values and signed URLs are removed`() = runBlocking {
        val errors=listOf(
            "Column expected_value does not exist" to "Column expected_value does not exist",
            "Failure at https://storage.test/path?synthetic-signed-secret" to "Failure at [redacted URL]",
            "Unexpected bound-secret" to "Databricks statement execution failed",
            "Unexpected synthetic-token" to "Databricks statement execution failed",
        )
        errors.forEach { (message,expected) ->
            val response=jsonMapper.createObjectNode().put("statement_id","statement-1")
            response.putObject("status").put("state","FAILED").put("sql_state","42P01").putObject("error").put("error_code","BAD_REQUEST").put("message",message)
            val http=RecordedHttpClient { request -> if(request.uri().path.endsWith("/cancel")) httpResponse(request,200,byteArrayOf()) else jsonResponse(request,200,response) }
            val result=assertIs<DriverPhysicalResult.Failed>(run(http,RecordingSink(),context(parameters=listOf(QueryParameter.Text("bound-secret","value")))))
            assertEquals(expected,result.safeMessage)
        }
    }

    @Test fun `refresh response containing multiple links consumes all chunks`() = runBlocking {
        val http=RecordedHttpClient { request -> when {
            request.uri().path.endsWith("/cancel") -> httpResponse(request,200,byteArrayOf())
            request.method()=="POST" -> jsonResponse(request,200,snapshot(listOf(1,1),listOf(link(0,0,1,expiration="2000-01-01T00:00:00Z",next=1))))
            request.uri().path.endsWith("/result/chunks/0") -> jsonResponse(request,200,jsonMapper.createObjectNode().set<ObjectNode>("external_links",jsonMapper.createArrayNode().add(link(0,0,1)).add(link(1,1,1))))
            request.uri().path=="/chunk/0" -> httpResponse(request,200,integerStream(listOf(1L)))
            else -> httpResponse(request,200,integerStream(listOf(2L)))
        } }
        val sink=RecordingSink(); assertEquals(2uL,assertIs<DriverPhysicalResult.Succeeded>(run(http,sink)).rowsProcessed)
        assertEquals(listOf(1L,2L),sink.frames.flatMap(::integerFileValues))
        assertEquals(1,http.requests.count { it.uri().path.contains("/result/chunks/") })
    }

    @Test fun `validation response loss and late ID cancellation do not resubmit SQL`() = runBlocking {
        val lost=RecordedHttpClient { CompletableFuture.failedFuture(java.io.IOException("synthetic-token signed-secret")) }
        credential("access_token" to "token").use { material ->
            val failure=assertFailsWith<RawSqlValidationException> { provider(lost).validateRawSql(connector(),material,"SELECT 1",emptyList(),System.currentTimeMillis()+10_000, operationTiming(DriverOperation.VALIDATE)) }
            assertEquals("CONNECTION_FAILED",failure.failureCode); assertEquals(1,lost.requests.size); assertNull(failure.cause)
        }
        val pending=CompletableFuture<java.net.http.HttpResponse<java.io.InputStream>>()
        val sent=CompletableDeferred<HttpRequest>(); val cancelled=CompletableDeferred<Unit>()
        val http=RecordedHttpClient { request -> if(request.uri().path.endsWith("/cancel")) { cancelled.complete(Unit); httpResponse(request,200,byteArrayOf()) } else { sent.complete(request); pending } }
        val material=credential("access_token" to "token")
        try {
            val validation=async { provider(http).validateRawSql(connector(),material,"SELECT 1",emptyList(),System.currentTimeMillis()+10_000, operationTiming(DriverOperation.VALIDATE)) }
            val request=withTimeout(2_000) { sent.await() }
            validation.cancelAndJoin()
            pending.complete(inputResponse(request,200,java.io.ByteArrayInputStream("{\"statement_id\":\"late-validation\",\"status\":{\"state\":\"RUNNING\"}}".toByteArray())))
            withTimeout(2_000) { cancelled.await() }
            assertEquals(1,http.requests.count { it.uri().path=="/api/2.0/sql/statements" })
            assertEquals(1,http.requests.count { it.uri().path.endsWith("/cancel") })
        } finally { material.close() }
    }

    @Test fun `OAuth refreshes expiry within one driver lifecycle`() = runBlocking {
        val mutableClock=object : Clock() {
            var epoch=Instant.parse("2026-10-07T00:00:00Z").toEpochMilli()
            override fun getZone()=ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock=this
            override fun instant()=Instant.ofEpochMilli(epoch)
        }
        var tokens=0
        val http=RecordedHttpClient { request -> when {
            request.uri().path=="/oidc/v1/token" -> { tokens++; jsonResponse(request,200,jsonMapper.readTree("{\"access_token\":\"synthetic-oauth-$tokens\",\"expires_in\":1}")) }
            request.uri().path=="/api/2.0/sql/statements" -> { mutableClock.epoch+=2_000; jsonResponse(request,200,jsonMapper.readTree("{\"statement_id\":\"statement-1\",\"status\":{\"state\":\"RUNNING\"}}")) }
            request.uri().path=="/api/2.0/sql/statements/statement-1" -> { assertEquals("Bearer synthetic-oauth-2",request.headers().firstValue("Authorization").get()); jsonResponse(request,200,snapshot()) }
            else -> httpResponse(request,200,integerStream(listOf(1L)))
        } }
        val material=credential("oauth_client_id" to "client","oauth_client_secret" to "secret")
        val session=provider(http,mutableClock).openSession(connector(),material,RecordingSink(), operationTiming())
        try { session.enter(context(deadline=mutableClock.millis()+10_000)); assertIs<DriverPhysicalResult.Succeeded>(session.awaitPhysicalResult()); assertEquals(2,tokens) } finally { session.close() }
    }

    @Test fun `failed OAuth diagnostic stays redacted when cancel refreshes expired token`() = runBlocking {
        val mutableClock = object : Clock() {
            @Volatile var epoch = Instant.parse("2026-10-07T00:00:00Z").toEpochMilli()
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant() = Instant.ofEpochMilli(epoch)
        }
        val oldToken = "synthetic-oauth-1"
        val failureStatus = jsonMapper.createObjectNode().put("state", "FAILED").put("sql_state", "42501")
        failureStatus.putObject("error").put("error_code", "BAD_REQUEST")
            .put("message", "Upstream rejected $oldToken")
        val vendorFailure = statementFailure(failureStatus)
        assertFalse(vendorFailure.stackTraceToString().contains(oldToken))
        val logged = java.util.concurrent.CopyOnWriteArrayList<String>()
        val logger = java.util.logging.Logger.getLogger(DriverOperationTiming::class.java.name)
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) {
                logged.add(record.message + record.thrown?.stackTraceToString().orEmpty())
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        var tokens = 0
        val http = RecordedHttpClient { request -> when {
            request.uri().path == "/oidc/v1/token" -> {
                tokens++
                jsonResponse(request, 200, jsonMapper.createObjectNode()
                    .put("access_token", "synthetic-oauth-$tokens").put("expires_in", 1))
            }
            request.uri().path == "/api/2.0/sql/statements" -> {
                assertEquals("Bearer $oldToken", request.headers().firstValue("Authorization").get())
                mutableClock.epoch += 2_000
                jsonResponse(request, 200, jsonMapper.createObjectNode().put("statement_id", "statement-1")
                    .set<ObjectNode>("status", failureStatus))
            }
            request.uri().path.endsWith("/cancel") -> {
                assertEquals("Bearer synthetic-oauth-2", request.headers().firstValue("Authorization").get())
                httpResponse(request, 200, byteArrayOf())
            }
            else -> error("unexpected fixture request")
        } }
        logger.addHandler(handler)
        val material = credential("oauth_client_id" to "client", "oauth_client_secret" to "secret")
        val timing = operationTiming()
        val session = provider(http, mutableClock).openSession(connector(), material, RecordingSink(), timing)
        try {
            session.enter(context(deadline = mutableClock.millis() + 10_000))
            val outcome = assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult())
            assertEquals("PERMISSION_DENIED", outcome.failureCode)
            assertEquals(2, tokens)
            assertEquals(1, http.requests.count { it.uri().path.endsWith("/cancel") })
            assertEquals("Databricks statement execution failed", outcome.safeMessage)
            assertFalse(outcome.safeMessage.contains(oldToken))
            timing.log(outcome)
            assertTrue(logged.isNotEmpty())
            assertFalse(logged.any { it.contains(oldToken) })
        } finally { session.close(); logger.removeHandler(handler) }
    }

    @Test fun `cancelled submission closes blocked body within cleanup budget before or after headers`() = runBlocking {
        listOf(false, true).forEach { headersAfterCancel ->
            val started = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            val body = object : java.io.InputStream() {
                override fun read(): Int {
                    started.complete(Unit)
                    while (release.count > 0) {
                        try { release.await() } catch (_: InterruptedException) { }
                    }
                    throw java.io.IOException("closed synthetic-signed-secret")
                }
                override fun close() {
                    release.countDown()
                    closed.complete(Unit)
                    if (headersAfterCancel) throw java.io.IOException("synthetic-close-secret")
                }
            }
            val submitted = CompletableDeferred<HttpRequest>()
            val response = CompletableFuture<java.net.http.HttpResponse<java.io.InputStream>>()
            val http = RecordedHttpClient { request ->
                submitted.complete(request)
                response
            }
            val session = provider(http).openSession(connector(), credential("access_token" to "token"), RecordingSink(), operationTiming())
            try {
                session.enter(context(deadline = System.currentTimeMillis() + 30_000))
                val request = withTimeout(2_000) { submitted.await() }
                if (!headersAfterCancel) {
                    response.complete(inputResponse(request, 200, body))
                    withTimeout(2_000) { started.await() }
                }
                session.cancel()
                session.close()
                if (headersAfterCancel) {
                    response.complete(inputResponse(request, 200, body))
                    withTimeout(2_000) { started.await() }
                }
                withTimeout(6_000) { closed.await() }
                assertFalse(response.isCancelled)
                assertEquals(1, http.requests.size)
            } finally { runCatching { body.close() }; session.close() }
        }
    }

    private suspend fun run(http: RecordedHttpClient,sink: RecordingSink,context: StartExecutionContext=context()): DriverPhysicalResult {
        val session=provider(http).openSession(connector(),credential("access_token" to "synthetic-token"),sink, operationTiming())
        return try { session.enter(context); withTimeout(5_000) { session.awaitPhysicalResult() } } finally { session.close() }
    }
    private fun successful(data: ByteArray,value: com.fasterxml.jackson.databind.JsonNode=snapshot()): RecordedHttpClient = RecordedHttpClient { request ->
        when { request.uri().path.endsWith("/cancel") -> httpResponse(request,200,byteArrayOf()); request.method()=="POST" -> jsonResponse(request,200,value); else -> httpResponse(request,200,data) }
    }
}

internal fun integerFileValues(payload: BinaryValue): List<Long?> = RootAllocator(32L*1_024*1_024).use { allocator ->
    ArrowFileReader(ByteArrayReadableSeekableByteChannel(payload.copyBytes()),allocator).use { reader ->
        buildList { while(reader.loadNextBatch()) { val root=reader.vectorSchemaRoot; val vector=root.fieldVectors[0] as BigIntVector; repeat(root.rowCount) { add(if(vector.isNull(it)) null else vector.get(it)) } } }
    }
}
internal fun requestJson(request: HttpRequest)=jsonMapper.readTree(requestText(request))
internal fun requestText(request: HttpRequest): String {
    val output=java.io.ByteArrayOutputStream(); val done=CompletableFuture<Unit>()
    request.bodyPublisher().orElseThrow().subscribe(object : java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
        override fun onSubscribe(subscription: java.util.concurrent.Flow.Subscription) { subscription.request(Long.MAX_VALUE) }
        override fun onNext(item: java.nio.ByteBuffer) { val bytes=ByteArray(item.remaining()); item.get(bytes); output.write(bytes) }
        override fun onError(failure: Throwable) { done.completeExceptionally(failure) }
        override fun onComplete() { done.complete(Unit) }
    })
    done.get(2,java.util.concurrent.TimeUnit.SECONDS)
    return output.toString(Charsets.UTF_8)
}
