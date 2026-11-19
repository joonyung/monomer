package cc.monomer.execution.arrow

import cc.monomer.execution.driver.spi.BinaryValue
import cc.monomer.execution.driver.spi.ResultColumn
import cc.monomer.execution.driver.spi.ResultLogicalType
import cc.monomer.execution.driver.spi.ResultLimitExceededException
import cc.monomer.execution.driver.spi.UnsupportedResultTypeException
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.BitVector
import org.apache.arrow.vector.DateDayVector
import org.apache.arrow.vector.DecimalVector
import org.apache.arrow.vector.FieldVector
import org.apache.arrow.vector.Float4Vector
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.IntVector
import org.apache.arrow.vector.SmallIntVector
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
import java.nio.channels.Channels
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Types
import java.time.ZoneOffset

data class EncodedArrowResult(
    val payload: BinaryValue,
    val rowCount: ULong,
    val columns: List<ResultColumn>,
) {
    val columnCount: Int
        get() = columns.size
}

data class StreamedArrowResult(
    val rowCount: ULong,
    val byteCount: ULong,
    val columns: List<ResultColumn>,
) {
    val columnCount: Int
        get() = columns.size
}

class JdbcArrowEncoder(
    private val batchSize: Int,
    private val decimalPhysicalEncoding: JdbcDecimalPhysicalEncoding,
    private val valueReader: JdbcResultValueReader,
) {
    init {
        require(batchSize > 0)
    }

    suspend fun stream(
        resultSet: ResultSet,
        maximumRows: ULong,
        maximumRecordBatchRows: Int,
        maximumRecordBatchBytes: Int,
        maximumCellBytes: Int,
        emit: suspend (payload: BinaryValue) -> Unit,
    ): StreamedArrowResult {
        require(maximumRows > 0uL)
        require(maximumRecordBatchRows > 0)
        require(maximumRecordBatchBytes > 0)
        require(maximumCellBytes > 0)
        val metadata = resultSet.metaData
        val columns = valueReader.resultColumns(metadata)
        val schema = buildSchema(metadata, columns)
        val allocator = RootAllocator(ARROW_ENCODER_MEMORY_BYTES)
        val root = VectorSchemaRoot.create(schema, allocator)
        val output = DrainableBoundedOutputStream(maximumRecordBatchBytes)
        val writer = ArrowFileWriter(root, null, Channels.newChannel(output))
        var totalRows = 0uL
        var totalBytes = 0uL
        try {
            writer.start()
            val header = output.drain()
            emit(header)
            totalBytes += header.size.toULong()
            while (totalRows < maximumRows) {
                val rowsThisBatch =
                    fillBatch(
                        resultSet = resultSet,
                        root = root,
                        columns = columns,
                        maximumRows =
                            minOf(
                                batchSize.toULong(),
                                maximumRecordBatchRows.toULong(),
                                maximumRows - totalRows,
                            ).toInt(),
                        maximumCellBytes = maximumCellBytes,
                    )
                if (rowsThisBatch == 0) {
                    break
                }
                root.rowCount = rowsThisBatch
                val bytesBeforeBatch = output.size
                writer.writeBatch()
                if (output.size - bytesBeforeBatch > maximumRecordBatchBytes) {
                    throw ResultLimitExceededException(maximumRecordBatchBytes.toULong())
                }
                val payload = output.drain()
                emit(payload)
                totalBytes += payload.size.toULong()
                totalRows += rowsThisBatch.toULong()
                root.clear()
            }
            if (totalRows == maximumRows && resultSet.next()) {
                throw ResultRowLimitExceededException(maximumRows)
            }
            writer.end()
            val terminalPayload = output.drain()
            emit(terminalPayload)
            totalBytes += terminalPayload.size.toULong()
            return StreamedArrowResult(
                rowCount = totalRows,
                byteCount = totalBytes,
                columns = columns,
            )
        } finally {
            runCatching(writer::close)
            runCatching(root::close)
            runCatching(allocator::close)
        }
    }

    /**
     * Emits complete Arrow files. The JDBC cursor is advanced by at most one
     * bounded batch per emitted file, so every payload can be parsed without
     * any bytes from another payload.
     */
    suspend fun streamInline(
        resultSet: ResultSet,
        maximumRecordBatchRows: Int,
        maximumRecordBatchBytes: Int,
        maximumCellBytes: Int,
        emit: suspend (payload: BinaryValue) -> Unit,
    ): StreamedArrowResult {
        require(maximumRecordBatchRows > 0)
        require(maximumRecordBatchBytes > 0)
        require(maximumCellBytes > 0)
        val metadata = resultSet.metaData
        val columns = valueReader.resultColumns(metadata)
        val schema = buildSchema(metadata, columns)
        var totalRows = 0uL
        var totalBytes = 0uL
        while (true) {
            val output = DrainableBoundedOutputStream(maximumRecordBatchBytes)
            val allocator = RootAllocator(ARROW_ENCODER_MEMORY_BYTES)
            val root = VectorSchemaRoot.create(schema, allocator)
            val writer = ArrowFileWriter(root, null, Channels.newChannel(output))
            var rowsThisBatch = 0
            try {
                writer.start()
                rowsThisBatch =
                    fillBatch(
                        resultSet = resultSet,
                        root = root,
                        columns = columns,
                        maximumRows = minOf(batchSize, maximumRecordBatchRows),
                        maximumCellBytes = maximumCellBytes,
                    )
                if (rowsThisBatch > 0) {
                    root.rowCount = rowsThisBatch
                    writer.writeBatch()
                    root.clear()
                }
                writer.end()
                if (rowsThisBatch > 0 || totalRows == 0uL) {
                    val payload = output.drain()
                    emit(payload)
                    totalBytes += payload.size.toULong()
                }
            } finally {
                runCatching(writer::close)
                runCatching(root::close)
                runCatching(allocator::close)
            }
            totalRows += rowsThisBatch.toULong()
            if (rowsThisBatch == 0) {
                break
            }
        }
        return StreamedArrowResult(
            rowCount = totalRows,
            byteCount = totalBytes,
            columns = columns,
        )
    }

    private fun buildSchema(
        metadata: ResultSetMetaData,
        columns: List<ResultColumn>,
    ): Schema =
        Schema(
            columns.map { column ->
                Field(
                    column.name,
                    FieldType(
                        column.nullable,
                        arrowTypeFor(metadata, column),
                        null,
                        fieldMetadata(column),
                    ),
                    null,
                )
            },
        )

    private fun arrowTypeFor(
        metadata: ResultSetMetaData,
        column: ResultColumn,
    ): ArrowType =
        when (column.logicalType) {
            ResultLogicalType.TEXT,
            ResultLogicalType.JSON,
            -> ArrowType.Utf8()
            ResultLogicalType.INTEGER ->
                when (metadata.getColumnType(column.ordinal)) {
                    Types.BIGINT -> ArrowType.Int(64, true)
                    Types.INTEGER -> ArrowType.Int(32, true)
                    else -> ArrowType.Int(16, true)
                }
            ResultLogicalType.FLOAT ->
                if (metadata.getColumnType(column.ordinal) == Types.DOUBLE) {
                    ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)
                } else {
                    ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)
                }
            ResultLogicalType.BOOLEAN -> ArrowType.Bool()
            ResultLogicalType.DATE -> ArrowType.Date(DateUnit.DAY)
            ResultLogicalType.TIME -> ArrowType.Time(TimeUnit.MICROSECOND, 64)
            ResultLogicalType.TIMESTAMP ->
                if (JdbcResultColumnReader.isTimestampWithTimeZone(metadata, column.ordinal)) {
                    ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC")
                } else {
                    ArrowType.Timestamp(TimeUnit.MICROSECOND, null)
                }
            ResultLogicalType.DECIMAL ->
                when (decimalPhysicalEncoding) {
                    JdbcDecimalPhysicalEncoding.UTF8_CANONICAL_DECIMAL -> ArrowType.Utf8()
                    JdbcDecimalPhysicalEncoding.NATIVE_DECIMAL -> {
                        val precision = if (metadata.getColumnType(column.ordinal) == Types.BIGINT) 38 else metadata.getPrecision(column.ordinal)
                        val scale = metadata.getScale(column.ordinal)
                        if (precision !in 1..38 || scale !in 0..precision) {
                            throw UnsupportedResultTypeException(column.name, metadata.getColumnTypeName(column.ordinal))
                        }
                        ArrowType.Decimal(precision, scale, 128)
                    }
                }
            ResultLogicalType.BINARY -> ArrowType.Binary()
        }

    private fun fieldMetadata(column: ResultColumn): Map<String, String> =
        if (
            column.logicalType == ResultLogicalType.DECIMAL &&
            decimalPhysicalEncoding == JdbcDecimalPhysicalEncoding.UTF8_CANONICAL_DECIMAL
        ) {
            mapOf(
                TabularArrowEncoder.LOGICAL_TYPE_METADATA_KEY to "DECIMAL",
                TabularArrowEncoder.PHYSICAL_ENCODING_METADATA_KEY to "UTF8_CANONICAL_DECIMAL",
            )
        } else {
            emptyMap()
        }

    private fun fillBatch(
        resultSet: ResultSet,
        root: VectorSchemaRoot,
        columns: List<ResultColumn>,
        maximumRows: Int,
        maximumCellBytes: Int,
    ): Int {
        root.fieldVectors.forEach(FieldVector::allocateNew)
        var row = 0
        while (row < maximumRows && resultSet.next()) {
            root.fieldVectors.forEachIndexed { index, vector ->
                writeCell(
                    resultSet = resultSet,
                    column = index + 1,
                    logicalType = columns[index].logicalType,
                    vector = vector,
                    row = row,
                    maximumCellBytes = maximumCellBytes,
                )
            }
            row += 1
        }
        root.fieldVectors.forEach { vector -> vector.valueCount = row }
        return row
    }

    private fun writeCell(
        resultSet: ResultSet,
        column: Int,
        logicalType: ResultLogicalType,
        vector: FieldVector,
        row: Int,
        maximumCellBytes: Int,
    ) {
        if (
            logicalType == ResultLogicalType.DECIMAL &&
            decimalPhysicalEncoding == JdbcDecimalPhysicalEncoding.UTF8_CANONICAL_DECIMAL
        ) {
            require(vector is VarCharVector)
            val value = resultSet.getString(column)
            if (resultSet.wasNull() || value == null) {
                vector.setNull(row)
            } else {
                val canonical = canonicalDecimalText(value)
                val bytes = canonical.toByteArray(Charsets.UTF_8)
                try {
                    if (bytes.size > maximumCellBytes) throw ResultLimitExceededException(maximumCellBytes.toULong())
                    vector.setSafe(row, bytes)
                } finally {
                    bytes.fill(0)
                }
            }
            return
        }
        when (vector) {
            is IntVector -> resultSet.getInt(column).writeNullable(resultSet, row, vector)
            is BigIntVector -> resultSet.getLong(column).writeNullable(resultSet, row, vector)
            is SmallIntVector -> resultSet.getShort(column).writeNullable(resultSet, row, vector)
            is Float4Vector -> resultSet.getFloat(column).writeNullable(resultSet, row, vector)
            is Float8Vector -> resultSet.getDouble(column).writeNullable(resultSet, row, vector)
            is BitVector -> {
                val value = resultSet.getBoolean(column)
                if (resultSet.wasNull()) vector.setNull(row) else vector.setSafe(row, if (value) 1 else 0)
            }
            is DateDayVector -> {
                val value = valueReader.readDate(resultSet, column)
                if (resultSet.wasNull() || value == null) {
                    vector.setNull(row)
                } else {
                    vector.setSafe(row, Math.toIntExact(value.toEpochDay()))
                }
            }
            is TimeMicroVector -> {
                val value = valueReader.readTime(resultSet, column)
                if (resultSet.wasNull() || value == null) {
                    vector.setNull(row)
                } else {
                    vector.setSafe(row, value.toNanoOfDay() / 1_000L)
                }
            }
            is TimeStampMicroVector -> {
                val value = valueReader.readTimestamp(resultSet, column)
                if (resultSet.wasNull() || value == null) {
                    vector.setNull(row)
                } else {
                    val micros =
                        Math.addExact(
                            Math.multiplyExact(
                                value.toEpochSecond(ZoneOffset.UTC),
                                1_000_000L,
                            ),
                            value.nano / 1_000L,
                        )
                    vector.setSafe(row, micros)
                }
            }
            is TimeStampMicroTZVector -> {
                val instant = valueReader.readTimestampInstant(resultSet, column)
                if (resultSet.wasNull() || instant == null) {
                    vector.setNull(row)
                } else {
                    val micros =
                        Math.addExact(
                            Math.multiplyExact(instant.epochSecond, 1_000_000L),
                            instant.nano / 1_000L,
                        )
                    vector.setSafe(row, micros)
                }
            }
            is DecimalVector -> {
                val value: BigDecimal? = resultSet.getBigDecimal(column)
                if (resultSet.wasNull() || value == null) {
                    vector.setNull(row)
                } else {
                    if (value.toPlainString().toByteArray(Charsets.UTF_8).size > maximumCellBytes) {
                        throw ResultLimitExceededException(maximumCellBytes.toULong())
                    }
                    val scale = (vector.field.type as ArrowType.Decimal).scale
                    vector.setSafe(row, value.setScale(scale))
                }
            }
            is VarBinaryVector -> {
                val value = resultSet.getBytes(column)
                if (resultSet.wasNull() || value == null) {
                    vector.setNull(row)
                } else {
                    if (value.size > maximumCellBytes) throw ResultLimitExceededException(maximumCellBytes.toULong())
                    vector.setSafe(row, value)
                }
            }
            is VarCharVector -> {
                val value = valueReader.readText(resultSet, column)
                if (resultSet.wasNull() || value == null) {
                    vector.setNull(row)
                } else {
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    if (bytes.size > maximumCellBytes) throw ResultLimitExceededException(maximumCellBytes.toULong())
                    vector.setSafe(row, bytes)
                }
            }
            else -> error("unhandled Arrow vector type ${vector::class.simpleName}")
        }
    }

    private fun Int.writeNullable(
        resultSet: ResultSet,
        row: Int,
        vector: IntVector,
    ) = if (resultSet.wasNull()) vector.setNull(row) else vector.setSafe(row, this)

    private fun Long.writeNullable(
        resultSet: ResultSet,
        row: Int,
        vector: BigIntVector,
    ) = if (resultSet.wasNull()) vector.setNull(row) else vector.setSafe(row, this)

    private fun Short.writeNullable(
        resultSet: ResultSet,
        row: Int,
        vector: SmallIntVector,
    ) = if (resultSet.wasNull()) vector.setNull(row) else vector.setSafe(row, this)

    private fun Float.writeNullable(
        resultSet: ResultSet,
        row: Int,
        vector: Float4Vector,
    ) = if (resultSet.wasNull()) vector.setNull(row) else vector.setSafe(row, this)

    private fun Double.writeNullable(
        resultSet: ResultSet,
        row: Int,
        vector: Float8Vector,
    ) = if (resultSet.wasNull()) vector.setNull(row) else vector.setSafe(row, this)

}

internal fun canonicalDecimalText(token: String): String =
    when (token) {
        "NaN",
        "Infinity",
        "-Infinity",
        -> token
        else -> BigDecimal(token).toPlainString()
    }

class ResultRowLimitExceededException(
    maximumRows: ULong,
) : IllegalStateException("Arrow result exceeds the configured $maximumRows row limit")
