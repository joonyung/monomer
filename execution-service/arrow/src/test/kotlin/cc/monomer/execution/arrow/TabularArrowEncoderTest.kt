package cc.monomer.execution.arrow

import cc.monomer.execution.driver.spi.BinaryValue
import cc.monomer.execution.driver.spi.ResultLimitExceededException
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.BitVector
import org.apache.arrow.vector.DateDayVector
import org.apache.arrow.vector.Decimal256Vector
import org.apache.arrow.vector.DecimalVector
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.TimeMicroVector
import org.apache.arrow.vector.TimeStampMicroTZVector
import org.apache.arrow.vector.TimeStampMicroVector
import org.apache.arrow.vector.VarBinaryVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.ipc.SeekableReadChannel
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel
import org.apache.arrow.vector.types.pojo.ArrowType
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.LocalDate

class TabularArrowEncoderTest {
    @Test
    fun rejectsTextAndByteCellsOverTheCellByteLimit() {
        val text = TabularColumn("text", TabularScalarType.STRING, nullable = false)
        val bytes = TabularColumn("bytes", TabularScalarType.BYTES, nullable = false)
        assertFailsWith<ResultLimitExceededException> {
            TabularArrowEncoder().encode(TabularPage(listOf(text), listOf(listOf(TabularScalar.Text("héllo")))), 64uL * 1024uL, 5)
        }
        assertFailsWith<ResultLimitExceededException> {
            TabularArrowEncoder().encode(
                TabularPage(listOf(bytes), listOf(listOf(TabularScalar.Bytes(BinaryValue.copyOf(ByteArray(6)))))),
                64uL * 1024uL,
                5,
            )
        }
        val encoded = TabularArrowEncoder().encode(
            TabularPage(listOf(text, bytes), listOf(listOf(TabularScalar.Text("hello"), TabularScalar.Bytes(BinaryValue.copyOf(ByteArray(5)))))),
            64uL * 1024uL,
            5,
        )
        assertEquals(1uL, encoded.rowCount)
    }

    @Test
    fun rejectsFramesAboveTheOutputByteLimit() {
        val page = TabularPage(
            listOf(TabularColumn("text", TabularScalarType.STRING, nullable = false)),
            listOf(listOf(TabularScalar.Text("hello"))),
        )
        val encodedBytes = TabularArrowEncoder().encode(page, 64uL * 1024uL, MAXIMUM_CELL_BYTES).payload.size.toULong()
        assertFailsWith<ResultLimitExceededException> {
            TabularArrowEncoder().encode(page, encodedBytes - 1uL, MAXIMUM_CELL_BYTES)
        }
        assertEquals(encodedBytes, TabularArrowEncoder().encode(page, encodedBytes, MAXIMUM_CELL_BYTES).payload.size.toULong())
    }

    @Test
    fun reservesVectorMemoryForThePageRowsNotTheDefaultCapacity() {
        val columns = (1..100).map { TabularColumn("text_$it", TabularScalarType.STRING, nullable = false) }
        val page = TabularPage(columns, listOf(columns.map { TabularScalar.Text("value") }))
        val encoded = TabularArrowEncoder().encode(page, 2uL * 1024uL * 1024uL, MAXIMUM_CELL_BYTES)
        assertEquals(1uL, encoded.rowCount)
    }

    @Test
    fun preservesTemporalBoundariesAndNullInPhysicalArrowVectors() {
        val columns = listOf(TabularScalarType.DATE, TabularScalarType.DATETIME, TabularScalarType.TIMESTAMP)
            .map { TabularColumn(it.name.lowercase(), it, nullable = true) }
        val days = listOf("1969-12-31", "1970-01-01", "2024-02-29", "2024-12-31", "2025-01-01")
            .map { LocalDate.parse(it).toEpochDay() }
        val rows = days.map { day ->
            listOf(
                TabularScalar.DateDays(Math.toIntExact(day)),
                TabularScalar.DateTimeMicroseconds(day * 86_400_000_000L + 1L),
                TabularScalar.TimestampMicroseconds(day * 86_400_000_000L - 1L),
            )
        } + listOf(listOf(null, null, null))
        val bytes = TabularArrowEncoder().encode(TabularPage(columns, rows), 4uL * 1024uL * 1024uL, MAXIMUM_CELL_BYTES).payload.copyBytes()
        RootAllocator().use { allocator ->
            ArrowFileReader(SeekableReadChannel(ByteArrayReadableSeekableByteChannel(bytes)), allocator).use { reader ->
                reader.loadRecordBatch(reader.recordBlocks.single())
                val root = reader.vectorSchemaRoot
                val date = assertIs<DateDayVector>(root.getVector("date"))
                val datetime = assertIs<TimeStampMicroVector>(root.getVector("datetime"))
                val timestamp = assertIs<TimeStampMicroTZVector>(root.getVector("timestamp"))
                assertNull((datetime.field.type as ArrowType.Timestamp).timezone)
                assertEquals("UTC", (timestamp.field.type as ArrowType.Timestamp).timezone)
                days.forEachIndexed { index, day ->
                    assertEquals(Math.toIntExact(day), date.get(index))
                    assertEquals(day * 86_400_000_000L + 1L, datetime.get(index))
                    assertEquals(day * 86_400_000_000L - 1L, timestamp.get(index))
                }
                root.fieldVectors.forEach { assertTrue(it.isNull(days.size)) }
            }
        }
    }

    @Test
    fun writesMultiplePagesAsRecordBatchesInOneArrowFile() {
        val columns =
            listOf(
                TabularColumn("value", TabularScalarType.INT64, nullable = false),
            )
        val first =
            TabularPage(
                columns,
                listOf(
                    listOf(TabularScalar.Integer64(1)),
                    listOf(TabularScalar.Integer64(2)),
                ),
            )
        val second =
            TabularPage(
                columns,
                listOf(listOf(TabularScalar.Integer64(3))),
            )
        val chunks =
            TabularArrowStreamEncoder(columns, 64 * 1024, MAXIMUM_CELL_BYTES).use { encoder ->
                listOf(
                    encoder.append(first).payload,
                    encoder.append(second).payload,
                    encoder.finish(),
                )
            }
        val payloads = chunks.map { it.copyBytes() }
        val streamBytes = ByteArray(payloads.sumOf { it.size })
        try {
            var offset = 0
            payloads.forEach { payload ->
                payload.copyInto(streamBytes, offset)
                offset += payload.size
            }
            val allocator = RootAllocator()
            try {
                ArrowFileReader(
                    SeekableReadChannel(ByteArrayReadableSeekableByteChannel(streamBytes)),
                    allocator,
                ).use { reader ->
                    reader.loadRecordBatch(reader.recordBlocks[0])
                    assertEquals(
                        listOf(1L, 2L),
                        (reader.vectorSchemaRoot.getVector("value") as BigIntVector)
                            .let { vector -> (0 until vector.valueCount).map(vector::get) },
                    )
                    reader.loadRecordBatch(reader.recordBlocks[1])
                    assertEquals(
                        listOf(3L),
                        (reader.vectorSchemaRoot.getVector("value") as BigIntVector)
                            .let { vector -> (0 until vector.valueCount).map(vector::get) },
                    )
                }
            } finally {
                allocator.close()
            }
        } finally {
            payloads.forEach { it.fill(0) }
            streamBytes.fill(0)
        }
    }

    @Test
    fun preservesEverySupportedScalarTypeAndLogicalMetadata() {
        val columns =
            TabularScalarType.entries.map { type ->
                TabularColumn(type.name.lowercase(), type, nullable = true)
            }
        val values =
            listOf(
                TabularScalar.Text("monomer"),
                TabularScalar.Integer64(Long.MAX_VALUE),
                TabularScalar.Float64(-1.25),
                TabularScalar.BooleanValue(true),
                TabularScalar.Bytes(BinaryValue.copyOf(byteArrayOf(0, 1, 2))),
                TabularScalar.DecimalValue(BigDecimal("123456789.123456789")),
                TabularScalar.DecimalValue(
                    BigDecimal(
                        "12345678901234567890123456789012345678." +
                            "12345678901234567890123456789012345678",
                    ),
                ),
                TabularScalar.DateDays(20_300),
                TabularScalar.TimeMicroseconds(45_296_123_456L),
                TabularScalar.DateTimeMicroseconds(1_754_050_896_123_456L),
                TabularScalar.TimestampMicroseconds(1_754_050_896_654_321L),
                TabularScalar.Text("POINT(127.0 37.5)"),
                TabularScalar.Text("""{"ready":true}"""),
                TabularScalar.Text("1-2 3 4:5:6.000007"),
            )
        val page =
            TabularPage(
                columns = columns,
                rows = listOf(values, List(columns.size) { null }),
            )

        val encoded = TabularArrowEncoder().encode(page, 4uL * 1024uL * 1024uL, MAXIMUM_CELL_BYTES)

        assertEquals(2uL, encoded.rowCount)
        assertEquals(columns.size, encoded.columnCount)
        val payload = encoded.payload.copyBytes()
        val allocator = RootAllocator()
        try {
            ArrowFileReader(
                SeekableReadChannel(ByteArrayReadableSeekableByteChannel(payload)),
                allocator,
            ).use { reader ->
                val root = reader.vectorSchemaRoot
                reader.loadRecordBatch(reader.recordBlocks[0])
                assertEquals(2, root.rowCount)
                assertEquals("monomer", (root.getVector("string") as VarCharVector).text(0))
                assertEquals(Long.MAX_VALUE, (root.getVector("int64") as BigIntVector).get(0))
                assertEquals(-1.25, (root.getVector("float64") as Float8Vector).get(0))
                assertEquals(1, (root.getVector("bool") as BitVector).get(0))
                assertContentEquals(
                    byteArrayOf(0, 1, 2),
                    (root.getVector("bytes") as VarBinaryVector).get(0),
                )
                assertEquals(
                    BigDecimal("123456789.123456789"),
                    (root.getVector("numeric") as DecimalVector).getObject(0),
                )
                assertEquals(
                    BigDecimal(
                        "12345678901234567890123456789012345678." +
                            "12345678901234567890123456789012345678",
                    ),
                    (root.getVector("bignumeric") as Decimal256Vector).getObject(0),
                )
                assertEquals(20_300, (root.getVector("date") as DateDayVector).get(0))
                assertEquals(
                    45_296_123_456L,
                    (root.getVector("time") as TimeMicroVector).get(0),
                )
                assertEquals(
                    1_754_050_896_123_456L,
                    (root.getVector("datetime") as TimeStampMicroVector).get(0),
                )
                assertEquals(
                    1_754_050_896_654_321L,
                    (root.getVector("timestamp") as TimeStampMicroTZVector).get(0),
                )
                assertEquals(
                    "POINT(127.0 37.5)",
                    (root.getVector("geography") as VarCharVector).text(0),
                )
                assertEquals(
                    """{"ready":true}""",
                    (root.getVector("json") as VarCharVector).text(0),
                )
                assertEquals(
                    "1-2 3 4:5:6.000007",
                    (root.getVector("interval") as VarCharVector).text(0),
                )
                root.fieldVectors.forEach { vector -> assertTrue(vector.isNull(1)) }

                assertIs<ArrowType.Decimal>(root.getVector("numeric").field.type).also {
                    assertEquals(128, it.bitWidth)
                    assertEquals(38, it.precision)
                    assertEquals(9, it.scale)
                }
                assertIs<ArrowType.Decimal>(root.getVector("bignumeric").field.type).also {
                    assertEquals(256, it.bitWidth)
                    assertEquals(76, it.precision)
                    assertEquals(38, it.scale)
                }
                assertEquals("UTC", (root.getVector("timestamp").field.type as ArrowType.Timestamp).timezone)
                assertNull((root.getVector("datetime").field.type as ArrowType.Timestamp).timezone)
                listOf("geography", "json", "interval").forEach { name ->
                    val field = root.getVector(name).field
                    assertIs<ArrowType.Utf8>(field.type)
                    assertTrue(field.metadata.isEmpty())
                }
            }
        } finally {
            payload.fill(0)
            allocator.close()
        }
    }

    @Test
    fun rejectsAnUnboundedPageAndAnOutOfRangeDecimal() {
        val column = TabularColumn("value", TabularScalarType.NUMERIC, nullable = false)
        assertFailsWith<IllegalArgumentException> {
            TabularPage(
                columns = listOf(column),
                rows =
                    List(TabularPage.MAXIMUM_ROWS + 1) {
                        listOf(TabularScalar.DecimalValue(BigDecimal.ZERO))
                    },
            )
        }
        val page =
            TabularPage(
                columns = listOf(column),
                rows =
                    listOf(
                        listOf(
                            TabularScalar.DecimalValue(
                                BigDecimal("123456789012345678901234567890.123456789"),
                            ),
                        ),
                    ),
            )

        assertFailsWith<IllegalArgumentException> {
            TabularArrowEncoder().encode(page, 64uL * 1024uL, MAXIMUM_CELL_BYTES)
        }
    }

    private fun VarCharVector.text(index: Int): String =
        String(get(index), Charsets.UTF_8)
}

private const val MAXIMUM_CELL_BYTES = 4 * 1024 * 1024
