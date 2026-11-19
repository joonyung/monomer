@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.explore.application

import cc.monomer.execution.api.ExecutionArtifactDownloadLink
import cc.monomer.execution.api.ExecutionArtifactHead
import cc.monomer.execution.api.ExecutionArtifactStore
import cc.monomer.explore.api.ExploreResultRetention
import cc.monomer.explore.api.ExploreResultView
import cc.monomer.explore.api.ExploreRunId
import cc.monomer.explore.api.ExploreResultCsvReadOutcome
import cc.monomer.explore.api.ExploreResultDownloadLinkReadOutcome
import cc.monomer.explore.api.ReadExploreResultArtifactCommand
import cc.monomer.explore.api.ExploreResultReadTransaction
import cc.monomer.explore.api.ExploreRunState
import java.time.Clock
import java.time.ZoneOffset
import cc.monomer.explore.domain.ExploreRun
import cc.monomer.identity.api.PrincipalId
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticQuerySpec
import com.google.flatbuffers.FlatBufferBuilder
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.*
import org.apache.arrow.vector.ipc.ArrowFileWriter
import org.apache.arrow.vector.ipc.message.ArrowBlock
import org.apache.arrow.vector.ipc.message.ArrowFooter
import org.apache.arrow.vector.types.DateUnit
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class ExploreCsvEncoderTest {
    @Test
    fun `all batches preserve order null empty duplicate header and precise decimals`() = runBlocking<Unit> {
        val fields = listOf(field("same", ArrowType.LargeUtf8.INSTANCE), field("same", ArrowType.Decimal(50, 10, 256)))
        val fixture = fixture(fields, 3, 2) { vectors, batch ->
            val text = vectors[0] as LargeVarCharVector
            if (batch == 0) text.setNull(0) else text.setSafe(0, "batch$batch".toByteArray())
            text.setSafe(1, "".toByteArray())
            val decimal = vectors[1] as Decimal256Vector
            decimal.setSafe(0, BigDecimal("123456789012345678901234567890.1234567890"))
            decimal.setSafe(1, BigDecimal("-1.0000000000"))
        }
        val text = fixture.csv()
        assertEquals("\uFEFFsame,same\r\n,123456789012345678901234567890.1234567890\r\n\"\",-1.0000000000\r\nbatch1,123456789012345678901234567890.1234567890\r\n\"\",-1.0000000000\r\nbatch2,123456789012345678901234567890.1234567890\r\n\"\",-1.0000000000\r\n", text)
        assertTrue(fixture.store.ranges.all { it.last - it.first + 1 <= 256 * 1024 })
    }

    @Test
    fun `formula policy only prefixes risky text and headers including whitespace controls and fullwidth`() = runBlocking<Unit> {
        val cases = listOf("=1", "+1", "-1", "@a", "＝1", "＋1", "－1", "＠a", "  =1", "\u00a0=1", "\uFEFF=1", "\ttext", "\rtext", "\ntext", "\u0000text")
        for (value in cases) {
            val output = ByteArrayOutputStream()
            val csv = CsvOutput(1024) { output.write(it.bytes()) }
            csv.field(value, true)
            csv.finish()
            assertEquals("\"\t$value\"", output.toString(Charsets.UTF_8), value)
        }
        val output = ByteArrayOutputStream()
        val csv = CsvOutput(1024) { output.write(it.bytes()) }
        csv.field("-123.45", false)
        csv.write(byteArrayOf(44))
        csv.field("a,\"b\"\r\nc", true)
        csv.finish()
        assertEquals("-123.45,\"a,\"\"b\"\"\r\nc\"", output.toString(Charsets.UTF_8))
        val fixture = fixture(listOf(field("=1+1", ArrowType.Utf8.INSTANCE)), 0, 0) { _, _ -> }
        assertEquals("\uFEFF\"\t=1+1\"\r\n", fixture.csv())
    }

    @Test
    fun `physical scalar bytes and temporal precision use original types`() = runBlocking<Unit> {
        val fields = listOf(
            field("integer", ArrowType.Int(64, true)), field("binary", ArrowType.FixedSizeBinary(3)),
            field("date", ArrowType.Date(DateUnit.DAY)), field("time", ArrowType.Time(TimeUnit.NANOSECOND, 64)),
            field("instant", ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
            field("local", ArrowType.Timestamp(TimeUnit.MICROSECOND, null)),
            field("bool", ArrowType.Bool.INSTANCE),
            Field("decimal", FieldType(true, ArrowType.Utf8.INSTANCE, null, mapOf("cc.monomer.logical_type" to "DECIMAL", "cc.monomer.physical_encoding" to "UTF8_CANONICAL_DECIMAL")), emptyList()),
        )
        val fixture = fixture(fields, 1, 1) { v, _ ->
            (v[0] as BigIntVector).setSafe(0, Long.MIN_VALUE)
            (v[1] as FixedSizeBinaryVector).setSafe(0, byteArrayOf(0, -1, 1))
            (v[2] as DateDayVector).setSafe(0, 1)
            (v[3] as TimeNanoVector).setSafe(0, 123456789)
            (v[4] as TimeStampNanoTZVector).setSafe(0, -1)
            (v[5] as TimeStampMicroVector).setSafe(0, 1234567)
            (v[6] as BitVector).setSafe(0, 1)
            (v[7] as VarCharVector).setSafe(0, "-12345678901234567890.0001".toByteArray())
        }
        assertEquals("\uFEFFinteger,binary,date,time,instant,local,bool,decimal\r\n-9223372036854775808,AP8B,1970-01-02,00:00:00.123456789,1969-12-31T23:59:59.999999999Z,1970-01-01T00:00:01.234567,true,-12345678901234567890.0001\r\n", fixture.csv())
    }

    @Test
    fun `special PostgreSQL numeric values stream as typed decimals`() = runBlocking<Unit> {
        val fields = listOf(
            Field("decimal", FieldType(true, ArrowType.Utf8.INSTANCE, null, mapOf("cc.monomer.logical_type" to "DECIMAL", "cc.monomer.physical_encoding" to "UTF8_CANONICAL_DECIMAL")), emptyList()),
        )
        val fixture = fixture(fields, 3, 1) { vectors, batch ->
            (vectors[0] as VarCharVector).setSafe(0, listOf("NaN", "Infinity", "-Infinity")[batch].toByteArray())
        }
        assertEquals("\uFEFFdecimal\r\nNaN\r\nInfinity\r\n-Infinity\r\n", fixture.csv())
    }

    @Test
    fun `second precision unsigned integer and float special values remain typed`() = runBlocking<Unit> {
        val fields = listOf(field("time", ArrowType.Time(TimeUnit.SECOND, 32)),
            field("local", ArrowType.Timestamp(TimeUnit.SECOND, null)), field("uint", ArrowType.Int(64, false)),
            field("half", ArrowType.FloatingPoint(org.apache.arrow.vector.types.FloatingPointPrecision.HALF)),
            field("float", ArrowType.FloatingPoint(org.apache.arrow.vector.types.FloatingPointPrecision.DOUBLE)))
        val fixture = fixture(fields, 3, 1) { vectors, batch ->
            (vectors[0] as TimeSecVector).setSafe(0, 1)
            (vectors[1] as TimeStampSecVector).setSafe(0, -1)
            (vectors[2] as UInt8Vector).setSafe(0, -1)
            (vectors[3] as Float2Vector).setSafe(0, java.lang.Float.floatToFloat16(1.5f))
            (vectors[4] as Float8Vector).setSafe(0, listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)[batch])
        }
        assertEquals("\uFEFFtime,local,uint,half,float\r\n" + listOf("NaN", "Infinity", "-Infinity").joinToString("") {
            "00:00:01,1969-12-31T23:59:59,18446744073709551615,1.5,$it\r\n"
        }, fixture.csv())
    }

    @Test
    fun `semantic aliases must match saved projection before any stream is returned`() = runBlocking<Unit> {
        val query = SemanticQuerySpec(listOf(SemanticElementName("revenue")), emptyList(), null, emptyList(), emptyList(), 10)
        val run = mockk<ExploreRun> { every { kind } returns ExploreRun.Kind.Sql }
        every { run.kind } returns ExploreRun.Kind.Semantic(query, null, java.time.ZoneId.of("UTC"))
        val valid = fixture(listOf(field("__monomer_result_0", ArrowType.Utf8.INSTANCE)), 0, 0) { _, _ -> }
        val output = ByteArrayOutputStream()
        ExploreCsvEncoder(valid.store).prepare(run, valid.result).collect { output.write(it.bytes()) }
        assertEquals("\uFEFFmetric.revenue\r\n", output.toString(Charsets.UTF_8))
        val invalid = fixture(listOf(field("revenue", ArrowType.Utf8.INSTANCE)), 0, 0) { _, _ -> }
        assertFailsWith<IllegalArgumentException> { ExploreCsvEncoder(invalid.store).prepare(run, invalid.result) }
    }

    @Test
    fun `native nested schema is rejected during preflight`() = runBlocking<Unit> {
        val nested = Field("nested", FieldType.nullable(ArrowType.List.INSTANCE), listOf(field("item", ArrowType.Int(32, true))))
        val fixture = fixture(listOf(nested), 0, 0) { _, _ -> }
        assertFailsWith<UnsupportedCsvSchemaException> { ExploreCsvEncoder(fixture.store).prepare(mockk<ExploreRun> { every { kind } returns ExploreRun.Kind.Sql }, fixture.result) }
    }

    @Test
    fun `oversized footer and batch metadata rejected before allocation`() = runBlocking<Unit> {
        val fixture = fixture(listOf(field("value", ArrowType.Utf8.INSTANCE)), 0, 0) { _, _ -> }
        ByteBuffer.wrap(fixture.store.bytes, fixture.store.bytes.size - 10, 4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(ExploreCsvEncoder.MAX_FOOTER_BYTES + 1)
        assertFailsWith<CsvBudgetExceededException> { ExploreCsvEncoder(fixture.store).prepare(mockk<ExploreRun> { every { kind } returns ExploreRun.Kind.Sql }, fixture.result) }
        val schema = Schema(listOf(field("value", ArrowType.Utf8.INSTANCE)))
        val footer = ArrowFooter(schema, emptyList(), listOf(ArrowBlock(8, 1024 * 1024 + 1, 0)))
        val builder = FlatBufferBuilder()
        builder.finish(footer.writeTo(builder))
        val bytes = "ARROW1\u0000\u0000".toByteArray() + builder.sizedByteArray() +
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(builder.sizedByteArray().size).array() + "ARROW1".toByteArray()
        val corrupt = Fixture(MemoryStore(bytes), fixture.result.copy(byteSize = bytes.size.toULong()))
        assertFailsWith<CsvBudgetExceededException> { ExploreCsvEncoder(corrupt.store).prepare(mockk<ExploreRun> { every { kind } returns ExploreRun.Kind.Sql }, corrupt.result) }
    }

    @Test
    fun `byte cap includes bom and header and fails without normal completion`() = runBlocking<Unit> {
        val emitted = ByteArrayOutputStream()
        val csv = CsvOutput(10) { emitted.write(it.bytes()) }
        csv.write(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
        csv.field("header", true)
        assertFailsWith<CsvBudgetExceededException> { csv.write(byteArrayOf(13, 10)) }
        assertEquals(0, emitted.size())
        assertEquals(1_000_000_000, ExploreCsvEncoder.MAX_OUTPUT_BYTES)
    }

    @Test
    fun `backpressure stops range progress and cancellation releases reader for next download`() = runBlocking<Unit> {
        val fixture = fixture(listOf(field("value", ArrowType.Utf8.INSTANCE)), 8, 1) { v, batch ->
            (v[0] as VarCharVector).setSafe(0, ("$batch" + "x".repeat(300_000)).toByteArray())
        }
        val prepared = ExploreCsvEncoder(fixture.store).prepare(mockk<ExploreRun> { every { kind } returns ExploreRun.Kind.Sql }, fixture.result)
        val reached = CompletableDeferred<Unit>()
        val held = CompletableDeferred<Unit>()
        val job = launch { prepared.collect { reached.complete(Unit); held.await() } }
        reached.await()
        val ranges = fixture.store.ranges.size
        kotlinx.coroutines.delay(50)
        assertEquals(ranges, fixture.store.ranges.size)
        assertTrue(fixture.store.ranges.sumOf { it.last - it.first + 1 } < fixture.store.bytes.size)
        job.cancelAndJoin()
        assertTrue(fixture.csv().endsWith("\r\n"))
    }

    @Test
    fun `changed etag and truncated body fail rather than finish a csv`() = runBlocking<Unit> {
        val fixture = fixture(listOf(field("value", ArrowType.Utf8.INSTANCE)), 1, 1) { v, _ -> (v[0] as VarCharVector).setSafe(0, "a".toByteArray()) }
        val flow = ExploreCsvEncoder(fixture.store).prepare(mockk<ExploreRun> { every { kind } returns ExploreRun.Kind.Sql }, fixture.result)
        fixture.store.etag = "changed"
        assertFailsWith<IOException> { flow.collect() }
        fixture.store.etag = "etag"
        fixture.store.truncate = true
        assertFailsWith<IOException> { flow.collect() }
    }

    @Test
    fun `owner csv and link enforce principal retention head and expiry without preview`() = runBlocking<Unit> {
        val fixture = fixture(listOf(field("value", ArrowType.Utf8.INSTANCE)), 0, 0) { _, _ -> }
        val result = fixture.result
        val run = mockk<ExploreRun> { every { kind } returns ExploreRun.Kind.Sql }
        every { run.state } returns ExploreRunState.SUCCEEDED
        every { run.visibleResult } returns result
        every { run.ownerPrincipalId } returns result.ownerPrincipalId
        val repository = mockk<ExploreRunRepository>()
        every { repository.lockRunForResultRead(result.runId) } returns run
        every { repository.lockVisibleResultForRead(result.runId) } returns result
        val transactions = object : ExploreResultReadTransactionPort {
            override suspend fun <T> inResultReadTransaction(block: (ExploreResultReadTransaction) -> T): T = block(ExploreResultReadTransaction())
        }
        val command = ReadExploreResultArtifactCommand(result.runId, result.ownerPrincipalId)
        fun reader(now: Instant) = ExploreResultReader(transactions, repository, fixture.store, Clock.fixed(now, ZoneOffset.UTC))
        val active = reader(result.activatedAt.plusSeconds(1))
        kotlin.test.assertIs<ExploreResultCsvReadOutcome.Visible>(active.readCsv(command))
        kotlin.test.assertIs<ExploreResultCsvReadOutcome.AccessDenied>(active.readCsv(command.copy(ownerPrincipalId = PrincipalId.generate())))
        kotlin.test.assertIs<ExploreResultDownloadLinkReadOutcome.AccessDenied>(active.readDownloadLink(command.copy(ownerPrincipalId = PrincipalId.generate())))
        kotlin.test.assertIs<ExploreResultCsvReadOutcome.RetentionExpired>(reader(result.retention.expiresAt).readCsv(command))
        kotlin.test.assertIs<ExploreResultDownloadLinkReadOutcome.RetentionExpired>(reader(result.retention.expiresAt).readDownloadLink(command))
        val linked = kotlin.test.assertIs<ExploreResultDownloadLinkReadOutcome.Visible>(active.readDownloadLink(command)).link
        assertEquals(result.retention.expiresAt, linked.expiresAt)
        assertEquals(result.byteSize, linked.byteSize)
        assertEquals("explore-${result.runId.value}.arrow", linked.fileName)
        fixture.store.etag = "changed"
        kotlin.test.assertIs<ExploreResultCsvReadOutcome.ArtifactUnavailable>(active.readCsv(command))
        kotlin.test.assertIs<ExploreResultDownloadLinkReadOutcome.ArtifactUnavailable>(active.readDownloadLink(command))
    }

    @Test
    fun `dictionary encoded text is decoded before csv rendering`() = runBlocking<Unit> {
        val output = ByteArrayOutputStream()
        RootAllocator().use { allocator ->
            val encoding = org.apache.arrow.vector.types.pojo.DictionaryEncoding(7, false, ArrowType.Int(32, true))
            VarCharVector("dictionary", allocator).use { dictionary ->
                dictionary.allocateNew()
                dictionary.setSafe(0, "=formula".toByteArray())
                dictionary.setSafe(1, "plain".toByteArray())
                dictionary.valueCount = 2
                val provider = org.apache.arrow.vector.dictionary.DictionaryProvider.MapDictionaryProvider(
                    org.apache.arrow.vector.dictionary.Dictionary(dictionary, encoding),
                )
                val schema = Schema(listOf(Field("value", FieldType(true, ArrowType.Int(32, true), encoding), emptyList())))
                VectorSchemaRoot.create(schema, allocator).use { root ->
                    root.allocateNew()
                    val indexes = root.fieldVectors.single() as IntVector
                    indexes.setSafe(0, 1)
                    indexes.setSafe(1, 0)
                    root.rowCount = 2
                    ArrowFileWriter(root, provider, Channels.newChannel(output)).use { writer ->
                        writer.start(); writer.writeBatch(); writer.end()
                    }
                }
            }
        }
        val base = fixture(listOf(field("value", ArrowType.Utf8.INSTANCE)), 0, 0) { _, _ -> }
        val bytes = output.toByteArray()
        val fixture = Fixture(MemoryStore(bytes), base.result.copy(byteSize = bytes.size.toULong(), rowCount = 2u))
        assertEquals("\uFEFFvalue\r\nplain\r\n\"\t=formula\"\r\n", fixture.csv())
    }

    @Test
    fun `actual one billion byte csv stream fails at cap with bounded range and heap`() = runBlocking<Unit> {
        val fixture = fixture(listOf(field("value", ArrowType.Utf8.INSTANCE)), 1, 1) { vectors, _ ->
            (vectors[0] as VarCharVector).setSafe(0, "x".repeat(1024 * 1024).toByteArray())
        }
        val source = fixture.store.bytes
        val footerLength = ByteBuffer.wrap(source, source.size - 10, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val footerStart = source.size - 10 - footerLength
        val original = ArrowFooter(org.apache.arrow.flatbuf.Footer.getRootAsFooter(ByteBuffer.wrap(source.copyOfRange(footerStart, source.size - 10))))
        val repeated = ArrowFooter(original.schema, emptyList(), List(1000) { original.recordBatches.single() })
        val builder = FlatBufferBuilder()
        builder.finish(repeated.writeTo(builder))
        val footer = builder.sizedByteArray()
        val bytes = source.copyOfRange(0, footerStart) + footer +
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(footer.size).array() + "ARROW1".toByteArray()
        val store = MemoryStore(bytes)
        val result = fixture.result.copy(byteSize = bytes.size.toULong(), rowCount = 1000u)
        var emittedBytes = 0L
        var maxChunkBytes = 0
        var maxHeapBytes = 0L
        val started = System.nanoTime()
        val stream = ExploreCsvEncoder(store).prepare(mockk<ExploreRun> { every { kind } returns ExploreRun.Kind.Sql }, result)
        assertFailsWith<CsvBudgetExceededException> {
            stream.collect { chunk ->
                emittedBytes += chunk.remaining()
                maxChunkBytes = maxOf(maxChunkBytes, chunk.remaining())
                maxHeapBytes = maxOf(maxHeapBytes, java.lang.management.ManagementFactory.getMemoryMXBean().heapMemoryUsage.used)
            }
        }
        assertTrue(emittedBytes in 999_000_000..1_000_000_000)
        assertTrue(maxChunkBytes <= 64 * 1024)
        assertTrue(store.ranges.all { it.last - it.first + 1 <= 256 * 1024 })
        println("CSV_STRESS emittedBytes=$emittedBytes maxChunkBytes=$maxChunkBytes maxRangeBytes=${store.ranges.maxOf { it.last - it.first + 1 }} sampledHeapBytes=$maxHeapBytes elapsedMillis=${(System.nanoTime() - started) / 1_000_000}")
    }

    private fun fixture(fields: List<Field>, batches: Int, rowsPerBatch: Int, set: (List<FieldVector>, Int) -> Unit): Fixture {
        val bytes = ByteArrayOutputStream()
        RootAllocator().use { allocator ->
            VectorSchemaRoot.create(Schema(fields), allocator).use { root ->
                ArrowFileWriter(root, null, Channels.newChannel(bytes)).use { writer ->
                    writer.start()
                    repeat(batches) { batch ->
                        root.allocateNew()
                        set(root.fieldVectors, batch)
                        root.fieldVectors.forEach { it.valueCount = rowsPerBatch }
                        root.rowCount = rowsPerBatch
                        writer.writeBatch()
                        root.clear()
                    }
                    writer.end()
                }
            }
        }
        val body = bytes.toByteArray()
        val now = Instant.parse("2026-09-07T00:00:00Z")
        return Fixture(MemoryStore(body), ExploreResultView(ExploreRunId.generate(), "artifacts/result.arrow", "etag", body.size.toULong(), "application/vnd.apache.arrow.file", (batches * rowsPerBatch).toULong(), fields.size, ExploreResultRetention(now.plusSeconds(300)), PrincipalId.generate(), now))
    }

    private data class Fixture(val store: MemoryStore, val result: ExploreResultView) {
        suspend fun csv(): String {
            val output = ByteArrayOutputStream()
            ExploreCsvEncoder(store).prepare(mockk<ExploreRun> { every { kind } returns ExploreRun.Kind.Sql }, result).collect { output.write(it.bytes()) }
            return output.toString(Charsets.UTF_8)
        }
    }

    private class MemoryStore(val bytes: ByteArray) : ExecutionArtifactStore {
        val ranges = java.util.Collections.synchronizedList(mutableListOf<LongRange>())
        var etag = "etag"
        var truncate = false
        override suspend fun head(objectKey: String, deadline: java.time.Instant?): ExecutionArtifactHead = ExecutionArtifactHead(objectKey, bytes.size.toULong(), etag, Instant.parse("2026-09-07T00:00:00Z"))
        override suspend fun read(objectKey: String): ByteArray = error("unconditional read forbidden")
        override suspend fun readRange(objectKey: String, expectedEtag: String, byteRange: LongRange): ByteArray {
            if (etag != expectedEtag) throw IOException("ETag mismatch")
            ranges += byteRange
            return bytes.copyOfRange(byteRange.first.toInt(), byteRange.last.toInt() + if (truncate) 0 else 1)
        }
        override fun readChunks(objectKey: String, expectedEtag: String, chunkSizeBytes: Int): Flow<ByteBuffer> = error("unused")
        override suspend fun presignGet(objectKey: String, expiresAt: Instant, fileName: String): ExecutionArtifactDownloadLink = ExecutionArtifactDownloadLink("https://example.test/result", expiresAt)
        override suspend fun delete(objectKey: String) = error("unused")
    }
}

private fun field(name: String, type: ArrowType): Field = Field(name, FieldType.nullable(type), emptyList())
private fun ByteBuffer.bytes(): ByteArray = ByteArray(remaining()).also { get(it) }
