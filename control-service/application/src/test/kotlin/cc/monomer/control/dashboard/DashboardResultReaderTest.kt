@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.execution.api.ArrowResultManifest
import cc.monomer.execution.api.ExecutionArtifactHead
import cc.monomer.execution.api.ExecutionArtifactStore
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowFileWriter
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.time.LocalDate
import kotlinx.serialization.json.JsonNull
import org.apache.arrow.vector.DateDayVector
import org.apache.arrow.vector.TimeStampMicroTZVector
import org.apache.arrow.vector.TimeStampMicroVector
import org.apache.arrow.vector.types.DateUnit
import org.apache.arrow.vector.types.TimeUnit

class DashboardResultReaderTest {
    @Test
    fun `date buckets and UTC timestamps preserve boundaries and null`() = runTest {
        val bytes = temporalArrowFile(includeDatetimeValue = false)
        val reference = manifest(bytes).copy(rowCount = 6u, columnCount = 3)
        val payload = DashboardResultReader(
            MemoryArtifactStore(bytes, ExecutionArtifactHead(reference.objectKey, reference.byteSize, reference.objectEtag, Instant.EPOCH)),
        ).read(reference)
        assertEquals(listOf("DATE", "TIMESTAMP", "DATETIME"), payload.columns.map { it.logicalType })
        listOf("1969-12-31", "1970-01-01", "2024-02-29", "2024-12-31", "2025-01-01").forEachIndexed { index, date ->
            assertEquals(
                listOf(JsonPrimitive(date), JsonPrimitive("${LocalDate.parse(date).minusDays(1)}T23:59:59.999999Z"), JsonNull),
                payload.rows[index],
            )
        }
        assertEquals(List(3) { JsonNull }, payload.rows.last())
    }

    @Test
    fun `timezone-free datetime result preserves fields and microseconds on repeated read`() = runTest {
        val bytes = temporalArrowFile(includeDatetimeValue = true)
        val reference = manifest(bytes).copy(rowCount = 6u, columnCount = 3)
        val reader = DashboardResultReader(
            MemoryArtifactStore(bytes, ExecutionArtifactHead(reference.objectKey, reference.byteSize, reference.objectEtag, Instant.EPOCH)),
        )
        repeat(2) {
            val payload = reader.read(reference)
            assertEquals(listOf("DATE", "TIMESTAMP", "DATETIME"), payload.columns.map { it.logicalType })
            listOf("1969-12-31", "1970-01-01", "2024-02-29", "2024-12-31", "2025-01-01").forEachIndexed { index, date ->
                assertEquals(JsonPrimitive("${date}T00:00:00.000001"), payload.rows[index][2])
            }
            assertEquals(List(3) { JsonNull }, payload.rows.last())
        }
    }

    private fun temporalArrowFile(includeDatetimeValue: Boolean): ByteArray {
        val fields = listOf(
            Field("date", FieldType.nullable(ArrowType.Date(DateUnit.DAY)), null),
            Field("timestamp", FieldType.nullable(ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC")), null),
            Field("datetime", FieldType.nullable(ArrowType.Timestamp(TimeUnit.MICROSECOND, null)), null),
        )
        val output = ByteArrayOutputStream()
        RootAllocator().use { allocator ->
            VectorSchemaRoot.create(Schema(fields), allocator).use { root ->
                root.allocateNew()
                listOf("1969-12-31", "1970-01-01", "2024-02-29", "2024-12-31", "2025-01-01").forEachIndexed { index, date ->
                    val day = LocalDate.parse(date).toEpochDay()
                    (root.getVector("date") as DateDayVector).setSafe(index, Math.toIntExact(day))
                    (root.getVector("timestamp") as TimeStampMicroTZVector).setSafe(index, day * 86_400_000_000L - 1L)
                    val datetime = root.getVector("datetime") as TimeStampMicroVector
                    if (includeDatetimeValue) datetime.setSafe(index, day * 86_400_000_000L + 1L) else datetime.setNull(index)
                }
                root.fieldVectors.forEach {
                    it.setNull(5)
                    it.valueCount = 6
                }
                root.rowCount = 6
                ArrowFileWriter(root, null, Channels.newChannel(output)).use { writer ->
                    writer.start()
                    writer.writeBatch()
                    writer.end()
                }
            }
        }
        return output.toByteArray()
    }

    @Test
    fun `reader verifies manifest head and decodes every Arrow record batch`() = runTest {
        val bytes = arrowFile()
        val manifest = manifest(bytes)
        val store = MemoryArtifactStore(
            bytes = bytes,
            head = ExecutionArtifactHead(
                objectKey = manifest.objectKey,
                byteSize = manifest.byteSize,
                etag = "\"etag-1\"",
                observedAt = Instant.parse("2026-08-22T00:00:00Z"),
            ),
        )

        val payload = DashboardResultReader(store).read(manifest)

        assertEquals(listOf("amount", "label"), payload.columns.map { it.name })
        assertEquals(listOf("DECIMAL", "TEXT"), payload.columns.map { it.logicalType })
        assertEquals(
            listOf(
                listOf(JsonPrimitive("12.30"), JsonPrimitive("first")),
                listOf(JsonPrimitive("0"), JsonPrimitive("second")),
                listOf(JsonPrimitive("-1.2"), JsonPrimitive("third")),
            ),
            payload.rows,
        )
    }

    @Test
    fun `reader preserves schema without any record batches`() = runTest {
        val bytes = arrowFile(includeBatches = false)
        val manifest = manifest(bytes).copy(rowCount = 0u)
        val reader = DashboardResultReader(
            MemoryArtifactStore(
                bytes,
                ExecutionArtifactHead(manifest.objectKey, manifest.byteSize, manifest.objectEtag, Instant.EPOCH),
            ),
        )
        val payload = reader.read(manifest)
        assertEquals(listOf("amount", "label"), payload.columns.map { it.name })
        assertEquals(listOf("DECIMAL", "TEXT"), payload.columns.map { it.logicalType })
        assertTrue(payload.columns.all { it.nullable })
        assertTrue(payload.rows.isEmpty())
        assertFailsWith<DashboardResultUnavailableException> { reader.read(manifest.copy(columnCount = 1)) }
        assertFailsWith<DashboardResultUnavailableException> { reader.read(manifest.copy(rowCount = 1u)) }
    }

    @Test
    fun `reader rejects object head identity size or etag mismatch`() = runTest {
        val bytes = arrowFile()
        val manifest = manifest(bytes)
        val validHead = ExecutionArtifactHead(
            objectKey = manifest.objectKey,
            byteSize = manifest.byteSize,
            etag = manifest.objectEtag,
            observedAt = Instant.parse("2026-08-22T00:00:00Z"),
        )

        listOf(
            validHead.copy(objectKey = "artifacts/other.arrow"),
            validHead.copy(byteSize = validHead.byteSize + 1u),
            validHead.copy(etag = "different"),
        ).forEach { head ->
            val error = assertFailsWith<DashboardResultUnavailableException> {
                DashboardResultReader(MemoryArtifactStore(bytes, head)).read(manifest)
            }
            assertEquals("dashboard result is unavailable", error.message)
        }
    }

    @Test
    fun `reader rejects duplicate columns`() = runTest {
        val bytes = duplicateColumnArrowFile()
        val manifest = manifest(bytes).copy(columnCount = 2, rowCount = 1u)
        val error = assertFailsWith<DashboardResultUnavailableException> {
            DashboardResultReader(
                MemoryArtifactStore(
                    bytes,
                    ExecutionArtifactHead(manifest.objectKey, manifest.byteSize, manifest.objectEtag, Instant.EPOCH),
                ),
            ).read(manifest)
        }
        assertEquals("dashboard result is unavailable", error.message)
    }

    @Test
    fun `reader rejects truncated Arrow file`() = runTest {
        val complete = arrowFile()
        val bytes = complete.copyOf(complete.size - 7)
        val manifest = manifest(bytes)
        val error = assertFailsWith<DashboardResultUnavailableException> {
            DashboardResultReader(
                MemoryArtifactStore(
                    bytes,
                    ExecutionArtifactHead(manifest.objectKey, manifest.byteSize, manifest.objectEtag, Instant.EPOCH),
                ),
            ).read(manifest)
        }
        assertEquals("dashboard result is unavailable", error.message)
    }

    @Test
    fun `reader keeps a cell admitted at the execution byte limit`() = runTest {
        val text = "a".repeat(65_536)
        assertTrue(JsonPrimitive(text).toString().toByteArray(Charsets.UTF_8).size > 65_536)
        val bytes = largeTextArrowFile(text)
        val manifest = manifest(bytes).copy(rowCount = 1u, columnCount = 1)

        val payload = DashboardResultReader(
            MemoryArtifactStore(
                bytes,
                ExecutionArtifactHead(manifest.objectKey, manifest.byteSize, manifest.objectEtag, Instant.EPOCH),
            ),
        ).read(manifest)

        assertEquals(JsonPrimitive(text), payload.rows.single().single())
    }

    private fun manifest(bytes: ByteArray): ArrowResultManifest = ArrowResultManifest(
        objectKey = "artifacts/workspaces/w/dashboard/runs/r/result.arrow",
        byteSize = bytes.size.toULong(),
        objectEtag = "etag-1",
        rowCount = 3u,
        columnCount = 2,
        mediaType = "application/vnd.apache.arrow.file",
    )

    private fun arrowFile(includeBatches: Boolean = true): ByteArray {
        val output = ByteArrayOutputStream()
        RootAllocator().use { allocator ->
            val schema = Schema(
                listOf(
                    Field(
                        "amount",
                        FieldType(
                            true,
                            ArrowType.Utf8(),
                            null,
                            mapOf(
                                "cc.monomer.logical_type" to "DECIMAL",
                                "cc.monomer.physical_encoding" to "UTF8_CANONICAL_DECIMAL",
                            ),
                        ),
                        null,
                    ),
                    Field("label", FieldType(true, ArrowType.Utf8(), null), null),
                ),
            )
            VectorSchemaRoot.create(schema, allocator).use { root ->
                val amount = root.getVector("amount") as VarCharVector
                val label = root.getVector("label") as VarCharVector
                amount.allocateNew()
                label.allocateNew()
                ArrowFileWriter(root, null, Channels.newChannel(output)).use { writer ->
                    writer.start()
                    if (includeBatches) {
                        writeBatch(root, amount, label, listOf("12.30", "0"), listOf("first", "second"))
                        writer.writeBatch()
                        writeBatch(root, amount, label, listOf("-1.2"), listOf("third"))
                        writer.writeBatch()
                    }
                    writer.end()
                }
            }
        }
        return output.toByteArray()
    }

    private fun duplicateColumnArrowFile(): ByteArray {
        val output = ByteArrayOutputStream()
        RootAllocator().use { allocator ->
            val schema = Schema(
                listOf(
                    Field("duplicate", FieldType(false, ArrowType.Utf8(), null), null),
                    Field("duplicate", FieldType(false, ArrowType.Utf8(), null), null),
                ),
            )
            VectorSchemaRoot.create(schema, allocator).use { root ->
                val first = root.fieldVectors[0] as VarCharVector
                val second = root.fieldVectors[1] as VarCharVector
                first.allocateNew()
                second.allocateNew()
                first.setSafe(0, "one".toByteArray())
                second.setSafe(0, "two".toByteArray())
                first.valueCount = 1
                second.valueCount = 1
                root.rowCount = 1
                ArrowFileWriter(root, null, Channels.newChannel(output)).use { writer ->
                    writer.start()
                    writer.writeBatch()
                    writer.end()
                }
            }
        }
        return output.toByteArray()
    }

    private fun largeTextArrowFile(text: String): ByteArray {
        val output = ByteArrayOutputStream()
        RootAllocator().use { allocator ->
            val schema = Schema(listOf(Field("label", FieldType(false, ArrowType.Utf8(), null), null)))
            VectorSchemaRoot.create(schema, allocator).use { root ->
                val label = root.getVector("label") as VarCharVector
                label.allocateNew()
                label.setSafe(0, text.toByteArray(Charsets.UTF_8))
                label.valueCount = 1
                root.rowCount = 1
                ArrowFileWriter(root, null, Channels.newChannel(output)).use { writer ->
                    writer.start()
                    writer.writeBatch()
                    writer.end()
                }
            }
        }
        return output.toByteArray()
    }

    private fun writeBatch(
        root: VectorSchemaRoot,
        amount: VarCharVector,
        label: VarCharVector,
        amounts: List<String>,
        labels: List<String>,
    ) {
        amounts.forEachIndexed { index, value -> amount.setSafe(index, value.toByteArray()) }
        labels.forEachIndexed { index, value -> label.setSafe(index, value.toByteArray()) }
        amount.valueCount = amounts.size
        label.valueCount = labels.size
        root.rowCount = amounts.size
    }

    private class MemoryArtifactStore(
        private val bytes: ByteArray,
        private val head: ExecutionArtifactHead,
    ) : ExecutionArtifactStore {
        override suspend fun head(objectKey: String, deadline: java.time.Instant?): ExecutionArtifactHead? = head

        override suspend fun read(objectKey: String): ByteArray = bytes.copyOf()

        override fun readChunks(
            objectKey: String,
            expectedEtag: String,
            chunkSizeBytes: Int,
        ): Flow<ByteBuffer> = flowOf(ByteBuffer.wrap(bytes).asReadOnlyBuffer())

        override suspend fun readRange(objectKey: String, expectedEtag: String, byteRange: LongRange): ByteArray =
        error("Conditional result ranges are outside this fixture")

    override suspend fun presignGet(objectKey: String, expiresAt: java.time.Instant, fileName: String): cc.monomer.execution.api.ExecutionArtifactDownloadLink =
        error("Download signing is outside this fixture")

    override suspend fun delete(objectKey: String) = Unit
    }
}
