package cc.monomer.explore.application

import cc.monomer.execution.api.ResultColumn
import cc.monomer.execution.api.ResultLogicalType
import cc.monomer.execution.api.ResultPreviewCell
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Base64
import org.apache.arrow.vector.Decimal256Vector
import org.apache.arrow.vector.DateDayVector
import org.apache.arrow.vector.DecimalVector
import org.apache.arrow.vector.FieldVector
import org.apache.arrow.vector.TimeStampMicroTZVector
import org.apache.arrow.vector.TimeMicroVector
import org.apache.arrow.vector.VarBinaryVector
import org.apache.arrow.vector.types.pojo.ArrowType

internal fun org.apache.arrow.vector.types.pojo.Field.toResultLogicalType(): ResultLogicalType {
    val metadata = getMetadata()
    val logicalType = metadata[DECIMAL_LOGICAL_TYPE_KEY]
    val physicalEncoding = metadata[DECIMAL_PHYSICAL_ENCODING_KEY]
    if (
        logicalType == DECIMAL_LOGICAL_TYPE_VALUE ||
        physicalEncoding == DECIMAL_PHYSICAL_ENCODING_VALUE
    ) {
        require(
            type is ArrowType.Utf8 &&
                logicalType == DECIMAL_LOGICAL_TYPE_VALUE &&
                physicalEncoding == DECIMAL_PHYSICAL_ENCODING_VALUE,
        ) { "Unsupported or malformed decimal field metadata" }
        return ResultLogicalType.DECIMAL
    }
    return type.toResultLogicalType()
}

private fun ArrowType.toResultLogicalType(): ResultLogicalType =
    when (this) {
        is ArrowType.Bool -> ResultLogicalType.BOOLEAN
        is ArrowType.Int -> ResultLogicalType.INTEGER
        is ArrowType.FloatingPoint -> ResultLogicalType.FLOAT
        is ArrowType.Decimal -> ResultLogicalType.DECIMAL
        is ArrowType.Date -> ResultLogicalType.DATE
        is ArrowType.Timestamp -> if (timezone == null) ResultLogicalType.DATETIME else ResultLogicalType.TIMESTAMP
        is ArrowType.Time -> ResultLogicalType.TIME
        is ArrowType.Binary,
        is ArrowType.FixedSizeBinary,
        -> ResultLogicalType.BINARY
        is ArrowType.Utf8,
        is ArrowType.LargeUtf8,
        is ArrowType.Utf8View,
        -> ResultLogicalType.TEXT
        else -> ResultLogicalType.JSON
    }

internal fun FieldVector.toPreviewCell(rowIndex: Int, column: ResultColumn): ResultPreviewCell {
    if (isNull(rowIndex)) return ResultPreviewCell.Null
    return when (column.logicalType) {
        ResultLogicalType.BOOLEAN -> ResultPreviewCell.BooleanValue(getObject(rowIndex) as Boolean)
        ResultLogicalType.DATE ->
            ResultPreviewCell.LocalDateValue(
                when (this) {
                    is DateDayVector -> LocalDate.ofEpochDay(get(rowIndex).toLong()).toString()
                    else -> getObject(rowIndex).toString()
                },
            )
        ResultLogicalType.DATETIME -> ResultPreviewCell.LocalDateTimeValue(
            (getObject(rowIndex) as java.time.LocalDateTime).also {
                require(it.nano % 1_000 == 0) { "DATETIME exceeds microsecond precision" }
            }.format(canonicalMicrosecondLocalDateTimeFormatter),
        )
        ResultLogicalType.TIMESTAMP ->
            ResultPreviewCell.InstantValue(
                when (this) {
                    is TimeStampMicroTZVector ->
                        canonicalMicrosecondInstantFormatter.format(
                            Instant.ofEpochSecond(
                                Math.floorDiv(get(rowIndex), 1_000_000L),
                                Math.floorMod(get(rowIndex), 1_000_000L) * 1_000L,
                            ),
                        )
                    else ->
                        canonicalMicrosecondInstantFormatter.format(
                            getObject(rowIndex) as? Instant
                                ?: Instant.parse(getObject(rowIndex).toString()),
                        )
                },
            )
        ResultLogicalType.INTEGER,
        ResultLogicalType.DECIMAL,
        ResultLogicalType.FLOAT,
        -> ResultPreviewCell.Number(
            when (this) {
                is DecimalVector,
                is Decimal256Vector,
                -> (getObject(rowIndex) as java.math.BigDecimal).toPlainString()
                else -> getObject(rowIndex).toString().withoutExponent()
            },
        )
        ResultLogicalType.BINARY ->
            ResultPreviewCell.Text(
                Base64.getEncoder().encodeToString(
                    when (this) {
                        is VarBinaryVector -> getObject(rowIndex)
                        else -> getObject(rowIndex).toString().toByteArray(Charsets.UTF_8)
                    },
                ),
            )
        ResultLogicalType.TIME ->
            ResultPreviewCell.Text(
                if (this is TimeMicroVector) {
                    LocalTime.ofNanoOfDay(get(rowIndex) * 1_000).format(microsecondTimeFormatter)
                } else {
                    getObject(rowIndex).toString()
                },
            )
        ResultLogicalType.TEXT,
        ResultLogicalType.JSON,
        -> ResultPreviewCell.Text(getObject(rowIndex).toString())
    }
}

private val microsecondTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS")

// Floating point text uses exponent form outside 1e-3..1e7; the preview number contract is exponent-free.
private fun String.withoutExponent(): String =
    if ('E' in this) java.math.BigDecimal(this).stripTrailingZeros().toPlainString() else this

internal fun canonicalPreviewText(cell: ResultPreviewCell): String? =
    when (cell) {
        ResultPreviewCell.Null -> null
        is ResultPreviewCell.BooleanValue -> if (cell.value) "true" else "false"
        is ResultPreviewCell.Text -> cell.value
        is ResultPreviewCell.Number -> cell.canonicalValue
        is ResultPreviewCell.LocalDateValue -> cell.canonicalValue
        is ResultPreviewCell.LocalDateTimeValue -> cell.canonicalValue
        is ResultPreviewCell.InstantValue -> cell.canonicalValue
    }

private const val DECIMAL_LOGICAL_TYPE_KEY = "cc.monomer.logical_type"
private const val DECIMAL_LOGICAL_TYPE_VALUE = "DECIMAL"
private const val DECIMAL_PHYSICAL_ENCODING_KEY = "cc.monomer.physical_encoding"
private const val DECIMAL_PHYSICAL_ENCODING_VALUE = "UTF8_CANONICAL_DECIMAL"
private val canonicalMicrosecondInstantFormatter: DateTimeFormatter =
    DateTimeFormatterBuilder().appendInstant(6).toFormatter()

private val canonicalMicrosecondLocalDateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS")
