package cc.monomer.execution.driver.databricks

import cc.monomer.execution.driver.spi.*
import kotlinx.coroutines.*
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.*
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.types.*
import org.apache.arrow.vector.types.pojo.*
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel
import java.math.BigDecimal
import java.time.Instant
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class DatabricksArrowResultsTest {
    @Test fun `raw Arrow preserves decimals trailing zeros UTC epoch NTZ wall micros dates and scalar nulls`() = runBlocking {
        val schema=Schema(listOf(
            field("instant",ArrowType.Timestamp(TimeUnit.MICROSECOND,"America/Los_Angeles")),
            field("wall",ArrowType.Timestamp(TimeUnit.MICROSECOND,null)),
            field("date",ArrowType.Date(DateUnit.DAY)),
            field("decimal",ArrowType.Decimal(30,18,128)),
            field("integer",ArrowType.Int(64,true)),
            field("double",ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
            field("boolean",ArrowType.Bool()),
            field("text",ArrowType.Utf8()),
            field("binary",ArrowType.Binary()),
        ))
        val numbers=listOf(Long.MIN_VALUE,-1L,0L,Long.MAX_VALUE)
        val rows=numbers.mapIndexed { index,number -> listOf<Any?>(number,number,if(index==0) Int.MIN_VALUE else if(index==3) Int.MAX_VALUE else -1,BigDecimal("0.010000000000000000"),number,if(index==0) Double.MIN_VALUE else Double.MAX_VALUE,index%2==0,"unicode 한글",byteArrayOf(0,-1,1)) } + listOf(List<Any?>(9) { null })
        val response=snapshot(listOf(rows.size.toLong()),listOf(link(0,0,rows.size.toLong())),schema)
        val sink=RecordingSink()
        val result=execute(arrowStream(schema,listOf(rows)),response,sink)
        assertIs<DriverPhysicalResult.Succeeded>(result)
        var rowIndex=0
        sink.frames.forEach { frame -> RootAllocator(ARROW_MEMORY_BYTES).use { allocator ->
            ArrowFileReader(ByteArrayReadableSeekableByteChannel(frame.copyBytes()),allocator).use { reader ->
                assertEquals("America/Los_Angeles",(reader.vectorSchemaRoot.schema.fields[0].type as ArrowType.Timestamp).timezone)
                assertNull((reader.vectorSchemaRoot.schema.fields[1].type as ArrowType.Timestamp).timezone)
                while(reader.loadNextBatch()) { val root=reader.vectorSchemaRoot; repeat(root.rowCount) { row ->
                    if(rowIndex==4) root.fieldVectors.forEach { assertTrue(it.isNull(row)) }
                    else {
                        assertEquals(numbers[rowIndex],(root.fieldVectors[0] as TimeStampMicroTZVector).get(row))
                        assertEquals(numbers[rowIndex],(root.fieldVectors[1] as TimeStampMicroVector).get(row))
                        assertEquals(if(rowIndex==0) Int.MIN_VALUE else if(rowIndex==3) Int.MAX_VALUE else -1,(root.fieldVectors[2] as DateDayVector).get(row))
                        assertEquals(BigDecimal("0.010000000000000000"),(root.fieldVectors[3] as DecimalVector).getObject(row))
                        assertEquals(numbers[rowIndex],(root.fieldVectors[4] as BigIntVector).get(row))
                        assertEquals(if(rowIndex==0) Double.MIN_VALUE else Double.MAX_VALUE,(root.fieldVectors[5] as Float8Vector).get(row))
                        assertEquals("unicode 한글",(root.fieldVectors[7] as VarCharVector).getObject(row).toString())
                        assertContentEquals(byteArrayOf(0,-1,1),(root.fieldVectors[8] as VarBinaryVector).get(row))
                    }
                    rowIndex++
                } }
            }
        } }
        assertEquals(5,rowIndex)
    }

    @Test fun `names normalize empty control characters Unicode and duplicates even on empty result`() = runBlocking {
        val schema=Schema(listOf(field("  ",ArrowType.Int(64,true)),field("x\u0000",ArrowType.Int(64,true)),field("x",ArrowType.Int(64,true)),field("e\u0301",ArrowType.Int(64,true))))
        val sink=RecordingSink()
        assertIs<DriverPhysicalResult.Succeeded>(execute(arrowStream(schema,listOf(emptyList())),snapshot(listOf(0),listOf(link(0,0,0)),schema),sink,context(expectedNames=listOf("column_1","x","x_2","é"))))
        RootAllocator(ARROW_MEMORY_BYTES).use { allocator -> ArrowFileReader(ByteArrayReadableSeekableByteChannel(sink.frames.single().copyBytes()),allocator).use { reader -> assertEquals(listOf("column_1","x","x_2","é"),reader.vectorSchemaRoot.schema.fields.map { it.name }) } }
    }

    @Test fun `unsupported nanoseconds complex and manifest decimal mismatch fail explicitly`() = runBlocking {
        val nanoSchema=Schema(listOf(field("value",ArrowType.Timestamp(TimeUnit.NANOSECOND,null))))
        val nano=execute(arrowStream(nanoSchema,listOf(listOf(listOf(1L)))),snapshot(schema=nanoSchema),RecordingSink())
        assertEquals("UNSUPPORTED_RESULT_TYPE",assertIs<DriverPhysicalResult.Failed>(nano).failureCode)
        assertTrue(nano.safeMessage.contains("value"))
        val listSchema=Schema(listOf(Field("value",FieldType.nullable(ArrowType.List()),listOf(field("item",ArrowType.Int(32,true))))))
        val complex=execute(arrowStream(listSchema,listOf(emptyList())),snapshot(listOf(0),listOf(link(0,0,0)),listSchema),RecordingSink())
        assertIs<DriverPhysicalResult.Failed>(complex)
        val decimal=Schema(listOf(field("value",ArrowType.Decimal(9,3,128))))
        val manifest=snapshot(schema=decimal)
        (manifest.path("manifest").path("schema").path("columns")[0] as com.fasterxml.jackson.databind.node.ObjectNode).put("type_text","DECIMAL(10,3)")
        assertIs<DriverPhysicalResult.Failed>(execute(arrowStream(decimal,listOf(listOf(listOf(BigDecimal("1.000"))))),manifest,RecordingSink()))
    }

    @Test fun `large cell record file artifact and allocator limits fail without publishing`() = runBlocking {
        val schema=Schema(listOf(field("value",ArrowType.Utf8())))
        val rows=listOf(listOf<Any?>("x".repeat(20_000)))
        val data=arrowStream(schema,listOf(rows))
        val response=snapshot(schema=schema)
        val cell=assertIs<DriverPhysicalResult.Failed>(execute(data,response,RecordingSink(),context(target=QueryResultTarget.InlineStream(10,64*1_024,128))))
        assertEquals("RESULT_LIMIT_EXCEEDED", cell.failureCode)
        assertTrue(cell.safeMessage.contains("cell byte"))
        val frame=assertIs<DriverPhysicalResult.Failed>(execute(data,response,RecordingSink(),context(target=QueryResultTarget.InlineStream(10,1_024,64*1_024))))
        assertEquals("RESULT_LIMIT_EXCEEDED", frame.failureCode)
        assertTrue(frame.safeMessage.contains("record batch byte"))
        val artifactSink=RecordingSink()
        val artifactData = integerStream(listOf(1L,2L,3L))
        val artifact=assertIs<DriverPhysicalResult.Failed>(execute(artifactData,snapshot(listOf(3),listOf(link(0,0,3))),artifactSink,context(target=artifactTarget.copy(maximumArtifactBytes=artifactData.size.toULong()+1uL))))
        assertEquals("RESULT_LIMIT_EXCEEDED", artifact.failureCode)
        assertFalse(artifactSink.completed)
        assertTrue(artifact.safeMessage.contains("byte limit"))
        val huge=arrowStream(schema,listOf(listOf(listOf<Any?>("x".repeat(40*1_024*1_024)))))
        val failed=execute(huge,response,RecordingSink(),context(target=QueryResultTarget.InlineStream(1,64*1_024,64*1_024)))
        assertEquals("DATABRICKS_EXECUTION_FAILED",assertIs<DriverPhysicalResult.Failed>(failed).failureCode)
    }

    @Test fun `output splits large variable records by byte budget instead of accumulating whole input batch`() = runBlocking {
        val schema=Schema(listOf(field("value",ArrowType.Utf8())))
        val rows=(1..20).map { listOf<Any?>("x".repeat(1_000)) }
        val sink=RecordingSink()
        assertIs<DriverPhysicalResult.Succeeded>(execute(arrowStream(schema,listOf(rows)),snapshot(listOf(20),listOf(link(0,0,20)),schema),sink,context(target=QueryResultTarget.InlineStream(1_024,8_192,2_048))))
        assertTrue(sink.frames.size>1); assertTrue(sink.frames.all { it.size<=8_192 })
    }

    @Test fun `slow sink backpressure stops next download and cancellation aborts open multipart`() = runBlocking {
        val started=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val sink=RecordingSink().apply { onAppend={ started.complete(Unit); release.await() } }
        val http=RecordedHttpClient { request -> when {
            request.method()=="POST" && !request.uri().path.endsWith("/cancel") -> jsonResponse(request,200,snapshot(listOf(1,1),listOf(link(0,0,1,next=1))))
            request.uri().path.endsWith("/cancel") -> httpResponse(request,200,byteArrayOf())
            request.uri().path.endsWith("/result/chunks/1") -> error("must not fetch while first batch is blocked")
            else -> httpResponse(request,200,integerStream(listOf(1L)))
        } }
        val session=provider(http).openSession(connector(),credential("access_token" to "token"),sink, operationTiming())
        try {
            session.enter(context(artifactTarget)); withTimeout(2_000) { started.await() }
            assertEquals(1,http.requests.count { it.uri().host=="storage.cloud.databricks.com" })
            session.cancel()
            assertEquals(DriverPhysicalResult.Cancelled,session.awaitPhysicalResult())
            assertTrue(sink.aborted); assertFalse(sink.completed)
        } finally { release.complete(Unit); session.close() }
    }

    @Test fun `decoder failure after emitted chunk batch aborts and never refreshes or replays`() = runBlocking {
        val data=integerStream(listOf(1L),listOf(2L))
        val sink=RecordingSink()
        val http=RecordedHttpClient { request -> when {
            request.uri().path.endsWith("/cancel") -> httpResponse(request,200,byteArrayOf())
            request.method()=="POST" -> jsonResponse(request,200,snapshot(listOf(2),listOf(link(0,0,2))))
            else -> CompletableFuture.completedFuture(inputResponse(request,200,object : java.io.ByteArrayInputStream(data) {
                override fun read(bytes: ByteArray,offset: Int,length: Int): Int {
                    if(sink.parts.isNotEmpty()) throw java.io.IOException("expired synthetic-signed-secret")
                    return super.read(bytes,offset,length)
                }
            }))
        } }
        val session=provider(http).openSession(connector(),credential("access_token" to "token"),sink, operationTiming())
        try {
            session.enter(context(artifactTarget)); val failed=assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult())
            assertEquals("DATABRICKS_EXECUTION_FAILED",failed.failureCode)
            assertFalse(failed.safeMessage.contains("secret"))
            assertTrue(sink.aborted); assertFalse(sink.completed)
            assertEquals(0,http.requests.count { it.uri().path.contains("/result/chunks/") })
        } finally { session.close() }
    }

    @Test fun `zero chunk zero rows yields schema file and expected column checks`() = runBlocking {
        val response = snapshot(listOf(0),listOf(link(0,0,0))) as com.fasterxml.jackson.databind.node.ObjectNode
        val manifest = response.path("manifest") as com.fasterxml.jackson.databind.node.ObjectNode
        manifest.put("total_chunk_count",0).putArray("chunks")
        response.putObject("result")
        val sink = RecordingSink()
        assertEquals(0uL,assertIs<DriverPhysicalResult.Succeeded>(execute(byteArrayOf(),response,sink,context(expectedNames=listOf("value")))).rowsProcessed)
        assertEquals(emptyList(),sink.frames.flatMap(::integerFileValues))
        val artifactSink=RecordingSink()
        assertIs<DriverPhysicalResult.Succeeded>(execute(byteArrayOf(),response,artifactSink,context(artifactTarget)))
        assertTrue(artifactSink.completed)
        val absentResult = response.deepCopy().also { it.remove("result") }
        val emptyLinks = response.deepCopy().also { it.putObject("result").putArray("external_links") }
        listOf(absentResult, emptyLinks).forEach { emptyResponse ->
            listOf(context(), context(artifactTarget)).forEach { executionContext ->
                assertIs<DriverPhysicalResult.Succeeded>(execute(byteArrayOf(), emptyResponse, RecordingSink(), executionContext))
            }
        }
        assertEquals("RESULT_COLUMN_MISMATCH",assertIs<DriverPhysicalResult.Failed>(execute(byteArrayOf(),response,RecordingSink(),context(expectedNames=listOf("wrong")))).failureCode)
    }

    @Test fun `all integer widths float negative zero and special result floats retain bits`() = runBlocking {
        val schema=Schema(listOf(field("byte",ArrowType.Int(8,true)),field("short",ArrowType.Int(16,true)),field("int",ArrowType.Int(32,true)),field("float",ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)),field("double",ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE))))
        val rows=listOf(listOf<Any?>(Byte.MIN_VALUE,Short.MIN_VALUE,Int.MIN_VALUE,-0.0f,Double.POSITIVE_INFINITY),listOf<Any?>(Byte.MAX_VALUE,Short.MAX_VALUE,Int.MAX_VALUE,Float.NaN,Double.NaN))
        val sink=RecordingSink()
        assertIs<DriverPhysicalResult.Succeeded>(execute(arrowStream(schema,listOf(rows)),snapshot(listOf(2),listOf(link(0,0,2)),schema),sink))
        RootAllocator(ARROW_MEMORY_BYTES).use { allocator -> ArrowFileReader(ByteArrayReadableSeekableByteChannel(sink.frames.single().copyBytes()),allocator).use { reader ->
            assertTrue(reader.loadNextBatch()); val vectors=reader.vectorSchemaRoot.fieldVectors
            assertEquals(Byte.MIN_VALUE,(vectors[0] as TinyIntVector).get(0)); assertEquals(Short.MIN_VALUE,(vectors[1] as SmallIntVector).get(0)); assertEquals(Int.MAX_VALUE,(vectors[2] as IntVector).get(1))
            assertEquals((-0.0f).toRawBits(),(vectors[3] as Float4Vector).get(0).toRawBits()); assertTrue((vectors[3] as Float4Vector).get(1).isNaN())
            assertEquals(Double.POSITIVE_INFINITY,(vectors[4] as Float8Vector).get(0)); assertTrue((vectors[4] as Float8Vector).get(1).isNaN())
        } }
    }

    @Test fun `schema changing on later chunk aborts existing upload`() = runBlocking {
        val secondSchema=Schema(listOf(field("value",ArrowType.Int(32,true))))
        val http=RecordedHttpClient { request -> when {
            request.uri().path.endsWith("/cancel") -> httpResponse(request,200,byteArrayOf())
            request.method()=="POST" -> jsonResponse(request,200,snapshot(listOf(1,1),listOf(link(0,0,1),link(1,1,1))))
            request.uri().path=="/chunk/0" -> httpResponse(request,200,integerStream(listOf(1L)))
            else -> httpResponse(request,200,arrowStream(secondSchema,listOf(listOf(listOf(2)))))
        } }
        val sink=RecordingSink(); val session=provider(http).openSession(connector(),credential("access_token" to "token"),sink, operationTiming())
        try { session.enter(context(artifactTarget)); assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult()); assertTrue(sink.aborted); assertFalse(sink.completed) } finally { session.close() }
    }

    @Test fun `deadline during blocked upload aborts multipart`() = runBlocking {
        val started=CompletableDeferred<Unit>()
        val sink=RecordingSink().apply { onAppend={ started.complete(Unit); awaitCancellation() } }
        val http=RecordedHttpClient { request -> if(request.uri().path.endsWith("/cancel")) httpResponse(request,200,byteArrayOf()) else if(request.method()=="POST") jsonResponse(request,200,snapshot()) else httpResponse(request,200,integerStream(listOf(1L))) }
        val session=provider(http).openSession(connector(),credential("access_token" to "token"),sink, operationTiming())
        try { session.enter(context(artifactTarget,deadline=System.currentTimeMillis()+250)); withTimeout(2_000) { started.await() }; assertEquals("RUN_DEADLINE_EXCEEDED",assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult()).failureCode); assertTrue(sink.aborted) } finally { session.close() }
    }

    @Test fun `zero chunk manifest rejects supplied extra link or next chunk before delivery`() = runBlocking {
        val extras: List<(com.fasterxml.jackson.databind.node.ObjectNode) -> Unit> = listOf(
            { it.putArray("external_links").add(link(0, 0, 1)) },
            { it.put("next_chunk_index", 0) },
        )
        listOf(context(), context(artifactTarget)).forEach { executionContext ->
            extras.forEach { addExtra ->
                val response = snapshot(listOf(0), listOf(link(0, 0, 0))) as com.fasterxml.jackson.databind.node.ObjectNode
                val manifest = response.path("manifest") as com.fasterxml.jackson.databind.node.ObjectNode
                manifest.put("total_chunk_count", 0).putArray("chunks")
                addExtra(response.putObject("result"))
                val sink = RecordingSink()
                val http = RecordedHttpClient { request -> when {
                    request.uri().path.endsWith("/cancel") -> httpResponse(request, 200, byteArrayOf())
                    request.uri().path == "/api/2.0/sql/statements" -> jsonResponse(request, 200, response)
                    else -> error("extra chunk must be rejected before download")
                } }
                val session = provider(http).openSession(connector(), credential("access_token" to "token"), sink, operationTiming())
                try {
                    session.enter(executionContext)
                    val outcome = assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult())
                    assertEquals("DATABRICKS_EXECUTION_FAILED", outcome.failureCode)
                    assertEquals(1, http.requests.count { it.uri().path.endsWith("/cancel") })
                    assertTrue(sink.frames.isEmpty())
                    assertFalse(sink.opened)
                    assertFalse(sink.completed)
                } finally { session.close() }
            }
        }
    }

    private suspend fun execute(data: ByteArray,response: com.fasterxml.jackson.databind.JsonNode,sink: RecordingSink,context: StartExecutionContext=context()): DriverPhysicalResult {
        val http=RecordedHttpClient { request -> if(request.uri().path.endsWith("/cancel")) httpResponse(request,200,byteArrayOf()) else if(request.method()=="POST") jsonResponse(request,200,response) else httpResponse(request,200,data) }
        val session=provider(http).openSession(connector(),credential("access_token" to "token"),sink, operationTiming())
        return try { session.enter(context); withTimeout(5_000) { session.awaitPhysicalResult() } } finally { session.close() }
    }
    private fun field(name: String,type: ArrowType)=Field(name,FieldType.nullable(type),emptyList())
}
