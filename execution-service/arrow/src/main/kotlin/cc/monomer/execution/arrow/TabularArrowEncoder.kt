package cc.monomer.execution.arrow

import cc.monomer.execution.driver.spi.BinaryValue
import cc.monomer.execution.driver.spi.ResultColumn
import cc.monomer.execution.driver.spi.ResultLimitExceededException
import cc.monomer.execution.driver.spi.ResultLogicalType
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.BitVector
import org.apache.arrow.vector.DateDayVector
import org.apache.arrow.vector.Decimal256Vector
import org.apache.arrow.vector.DecimalVector
import org.apache.arrow.vector.FieldVector
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.TimeMicroVector
import org.apache.arrow.vector.TimeStampMicroTZVector
import org.apache.arrow.vector.TimeStampMicroVector
import org.apache.arrow.vector.VarBinaryVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowFileWriter
import org.apache.arrow.vector.types.DateUnit
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.channels.Channels

enum class TabularScalarType {
    STRING,
    INT64,
    FLOAT64,
    BOOL,
    BYTES,
    NUMERIC,
    BIGNUMERIC,
    DATE,
    TIME,
    DATETIME,
    TIMESTAMP,
    GEOGRAPHY,
    JSON,
    INTERVAL,
}

data class TabularColumn(
    val name: String,
    val type: TabularScalarType,
    val nullable: Boolean,
) {
    init {
        require(name.isNotBlank())
    }
}

sealed interface TabularScalar {
    data class Text(val value: String) : TabularScalar

    data class Integer64(val value: Long) : TabularScalar

    data class Float64(val value: Double) : TabularScalar

    data class BooleanValue(val value: Boolean) : TabularScalar

    data class Bytes(val value: BinaryValue) : TabularScalar

    data class DecimalValue(val value: BigDecimal) : TabularScalar

    data class DateDays(val value: Int) : TabularScalar

    data class TimeMicroseconds(val value: Long) : TabularScalar {
        init {
            require(value in 0 until MICROSECONDS_PER_DAY)
        }
    }

    data class DateTimeMicroseconds(val value: Long) : TabularScalar

    data class TimestampMicroseconds(val value: Long) : TabularScalar

    private companion object {
        const val MICROSECONDS_PER_DAY = 86_400_000_000L
    }
}

data class TabularPage(
    val columns: List<TabularColumn>,
    val rows: List<List<TabularScalar?>>,
) {
    init {
        require(columns.isNotEmpty())
        require(columns.map(TabularColumn::name).distinct().size == columns.size)
        require(rows.size <= MAXIMUM_ROWS)
        require(rows.all { row -> row.size == columns.size })
        rows.forEach { row ->
            row.forEachIndexed { index, value ->
                require(value != null || columns[index].nullable) {
                    "non-nullable tabular column contained a null value"
                }
            }
        }
    }

    companion object {
        const val MAXIMUM_ROWS = 1_024
    }
}

data class EncodedTabularArrowChunk(
    val payload: BinaryValue,
    val rowCount: ULong,
    val columnCount: Int,
)

class TabularArrowStreamEncoder(
    columns: List<TabularColumn>,
    private val maximumChunkBytes: Int,
    private val maximumCellBytes: Int,
) : AutoCloseable {
    private val acceptedColumns = columns.toList()
    private val schema = Schema(acceptedColumns.map(::arrowField))
    private val output = DrainableBoundedOutputStream(maximumChunkBytes)
    private val allocator = RootAllocator(ARROW_ENCODER_MEMORY_BYTES)
    private val root = VectorSchemaRoot.create(schema, allocator)
    private val writer = ArrowFileWriter(root, null, Channels.newChannel(output))
    private var finished = false
    private var closed = false

    val columnCount: Int
        get() = acceptedColumns.size

    init {
        require(maximumChunkBytes > 0)
        require(maximumCellBytes > 0)
        require(acceptedColumns.isNotEmpty())
        require(acceptedColumns.map(TabularColumn::name).distinct().size == acceptedColumns.size)
        writer.start()
    }

    fun append(page: TabularPage): EncodedTabularArrowChunk {
        check(!finished && !closed)
        require(page.columns == acceptedColumns) {
            "tabular schema changed between Arrow record batches"
        }
        root.clear()
        root.fieldVectors.forEach { vector ->
            vector.setInitialCapacity(page.rows.size)
            vector.allocateNew()
        }
        page.rows.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { columnIndex, value ->
                writeCell(
                    column = acceptedColumns[columnIndex],
                    value = value,
                    vector = root.fieldVectors[columnIndex],
                    row = rowIndex,
                    maximumCellBytes = maximumCellBytes,
                )
            }
        }
        root.fieldVectors.forEach { vector -> vector.valueCount = page.rows.size }
        root.rowCount = page.rows.size
        writer.writeBatch()
        return EncodedTabularArrowChunk(
            payload = output.drain(),
            rowCount = page.rows.size.toULong(),
            columnCount = columnCount,
        )
    }

    fun finish(): BinaryValue {
        check(!finished && !closed)
        writer.end()
        finished = true
        return output.drain()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching(writer::close)
        runCatching(root::close)
        runCatching(allocator::close)
    }
}

class TabularArrowEncoder {
    fun encode(
        page: TabularPage,
        maximumBytes: ULong,
        maximumCellBytes: Int,
    ): EncodedArrowResult {
        require(maximumBytes in 1uL..Int.MAX_VALUE.toULong())
        val maximumBytesAsInt = maximumBytes.toInt()
        return TabularArrowStreamEncoder(page.columns, maximumBytesAsInt, maximumCellBytes).use { encoder ->
            val batch = encoder.append(page)
            val terminal = encoder.finish()
            val payload = concatenate(batch.payload, terminal, maximumBytesAsInt)
            EncodedArrowResult(
                payload = payload,
                rowCount = batch.rowCount,
                columns = tabularResultColumns(page.columns),
            )
        }
    }

    private fun concatenate(
        batch: BinaryValue,
        terminal: BinaryValue,
        maximumBytes: Int,
    ): BinaryValue {
        val batchBytes = batch.copyBytes()
        val terminalBytes = terminal.copyBytes()
        try {
            if (batchBytes.size > maximumBytes - terminalBytes.size) {
                throw ResultLimitExceededException(maximumBytes.toULong())
            }
            val combined = ByteArray(batchBytes.size + terminalBytes.size)
            try {
                batchBytes.copyInto(combined)
                terminalBytes.copyInto(combined, batchBytes.size)
                return BinaryValue.copyOf(combined)
            } finally {
                combined.fill(0)
            }
        } finally {
            batchBytes.fill(0)
            terminalBytes.fill(0)
        }
    }

    companion object {
        const val LOGICAL_TYPE_METADATA_KEY = "cc.monomer.logical_type"
        const val PHYSICAL_ENCODING_METADATA_KEY = "cc.monomer.physical_encoding"
    }
}

private fun arrowField(column: TabularColumn): Field =
    Field(
        column.name,
        FieldType(
            column.nullable,
            arrowType(column.type),
            null,
            emptyMap(),
        ),
        null,
    )

private fun arrowType(type: TabularScalarType): ArrowType =
    when (type) {
        TabularScalarType.STRING,
        TabularScalarType.GEOGRAPHY,
        TabularScalarType.JSON,
        TabularScalarType.INTERVAL,
        -> ArrowType.Utf8()
        TabularScalarType.INT64 -> ArrowType.Int(64, true)
        TabularScalarType.FLOAT64 ->
            ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)
        TabularScalarType.BOOL -> ArrowType.Bool()
        TabularScalarType.BYTES -> ArrowType.Binary()
        TabularScalarType.NUMERIC -> ArrowType.Decimal(38, 9, 128)
        TabularScalarType.BIGNUMERIC -> ArrowType.Decimal(76, 38, 256)
        TabularScalarType.DATE -> ArrowType.Date(DateUnit.DAY)
        TabularScalarType.TIME -> ArrowType.Time(TimeUnit.MICROSECOND, 64)
        TabularScalarType.DATETIME -> ArrowType.Timestamp(TimeUnit.MICROSECOND, null)
        TabularScalarType.TIMESTAMP -> ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC")
    }

private fun writeCell(
    column: TabularColumn,
    value: TabularScalar?,
    vector: FieldVector,
    row: Int,
    maximumCellBytes: Int,
) {
    if (value == null) {
        vector.setNull(row)
        return
    }
    when (column.type) {
        TabularScalarType.STRING,
        TabularScalarType.GEOGRAPHY,
        TabularScalarType.JSON,
        TabularScalarType.INTERVAL,
        -> {
            require(value is TabularScalar.Text)
            require(vector is VarCharVector)
            val bytes = value.value.toByteArray(Charsets.UTF_8)
            if (bytes.size > maximumCellBytes) {
                throw ResultLimitExceededException(maximumCellBytes.toULong())
            }
            vector.setSafe(row, bytes)
        }
        TabularScalarType.INT64 -> {
            require(value is TabularScalar.Integer64)
            require(vector is BigIntVector)
            vector.setSafe(row, value.value)
        }
        TabularScalarType.FLOAT64 -> {
            require(value is TabularScalar.Float64)
            require(vector is Float8Vector)
            vector.setSafe(row, value.value)
        }
        TabularScalarType.BOOL -> {
            require(value is TabularScalar.BooleanValue)
            require(vector is BitVector)
            vector.setSafe(row, if (value.value) 1 else 0)
        }
        TabularScalarType.BYTES -> {
            require(value is TabularScalar.Bytes)
            require(vector is VarBinaryVector)
            if (value.value.size > maximumCellBytes) {
                throw ResultLimitExceededException(maximumCellBytes.toULong())
            }
            val bytes = value.value.copyBytes()
            try {
                vector.setSafe(row, bytes)
            } finally {
                bytes.fill(0)
            }
        }
        TabularScalarType.NUMERIC -> {
            require(value is TabularScalar.DecimalValue)
            require(vector is DecimalVector)
            vector.setSafe(row, decimal(value.value, precision = 38, scale = 9))
        }
        TabularScalarType.BIGNUMERIC -> {
            require(value is TabularScalar.DecimalValue)
            require(vector is Decimal256Vector)
            vector.setSafe(row, decimal(value.value, precision = 76, scale = 38))
        }
        TabularScalarType.DATE -> {
            require(value is TabularScalar.DateDays)
            require(vector is DateDayVector)
            vector.setSafe(row, value.value)
        }
        TabularScalarType.TIME -> {
            require(value is TabularScalar.TimeMicroseconds)
            require(vector is TimeMicroVector)
            vector.setSafe(row, value.value)
        }
        TabularScalarType.DATETIME -> {
            require(value is TabularScalar.DateTimeMicroseconds)
            require(vector is TimeStampMicroVector)
            vector.setSafe(row, value.value)
        }
        TabularScalarType.TIMESTAMP -> {
            require(value is TabularScalar.TimestampMicroseconds)
            require(vector is TimeStampMicroTZVector)
            vector.setSafe(row, value.value)
        }
    }
}

private fun decimal(
    value: BigDecimal,
    precision: Int,
    scale: Int,
): BigDecimal {
    val normalized = value.setScale(scale, RoundingMode.UNNECESSARY)
    require(normalized.precision() <= precision) {
        "decimal value exceeds the declared precision"
    }
    return normalized
}

private fun tabularResultColumns(columns: List<TabularColumn>): List<ResultColumn> =
    columns.mapIndexed { index, column ->
        ResultColumn(
            ordinal = index + 1,
            name = column.name,
            logicalType =
                when (column.type) {
                    TabularScalarType.STRING,
                    TabularScalarType.GEOGRAPHY,
                    TabularScalarType.INTERVAL,
                    -> ResultLogicalType.TEXT
                    TabularScalarType.INT64 -> ResultLogicalType.INTEGER
                    TabularScalarType.FLOAT64 -> ResultLogicalType.FLOAT
                    TabularScalarType.BOOL -> ResultLogicalType.BOOLEAN
                    TabularScalarType.BYTES -> ResultLogicalType.BINARY
                    TabularScalarType.NUMERIC,
                    TabularScalarType.BIGNUMERIC,
                    -> ResultLogicalType.DECIMAL
                    TabularScalarType.DATE -> ResultLogicalType.DATE
                    TabularScalarType.TIME -> ResultLogicalType.TIME
                    TabularScalarType.DATETIME,
                    TabularScalarType.TIMESTAMP,
                    -> ResultLogicalType.TIMESTAMP
                    TabularScalarType.JSON -> ResultLogicalType.JSON
                },
            nullable = column.nullable,
        )
    }

// Bounds an encoder's Arrow vectors; the encoded output has its own byte bound.
internal const val ARROW_ENCODER_MEMORY_BYTES = 32L * 1024L * 1024L

internal class DrainableBoundedOutputStream(
    private val maximumBytes: Int,
) : java.io.OutputStream() {
    private val delegate = java.io.ByteArrayOutputStream(minOf(maximumBytes, 64 * 1024))

    val size: Int
        get() = delegate.size()

    override fun write(value: Int) {
        requireCapacity(1)
        delegate.write(value)
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        requireCapacity(length)
        delegate.write(bytes, offset, length)
    }

    fun drain(): BinaryValue {
        require(delegate.size() > 0)
        val bytes = delegate.toByteArray()
        return try {
            BinaryValue.copyOf(bytes)
        } finally {
            bytes.fill(0)
            delegate.reset()
        }
    }

    private fun requireCapacity(additionalBytes: Int) {
        if (delegate.size() > maximumBytes - additionalBytes) {
            throw ResultLimitExceededException(maximumBytes.toULong())
        }
    }
}
