package cc.monomer.execution.driver.bigquery

import cc.monomer.execution.arrow.TabularColumn
import cc.monomer.execution.arrow.TabularPage
import cc.monomer.execution.arrow.TabularScalar
import cc.monomer.execution.arrow.TabularScalarType
import cc.monomer.execution.driver.spi.BinaryValue
import cc.monomer.execution.driver.spi.ResultColumn
import cc.monomer.execution.driver.spi.UnsupportedResultTypeException
import com.google.cloud.bigquery.Field
import com.google.cloud.bigquery.FieldValue
import com.google.cloud.bigquery.FieldValueList
import com.google.cloud.bigquery.LegacySQLTypeName
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

internal object BigQueryResultPageMapper {
    fun map(
        fields: List<Field>,
        values: Iterable<FieldValueList>,
        nextPageToken: String?,
        maximumRows: Int,
    ): BigQueryResultPage {
        require(maximumRows in 1..TabularPage.MAXIMUM_ROWS)
        val columnNames = ResultColumn.acceptedNames(fields.map(Field::getName))
        val columns =
            fields.mapIndexed { index, field ->
                toColumn(columnNames[index], field)
            }
        val rows =
            values.asSequence()
                .take(maximumRows + 1)
                .map { row ->
                    check(row.size == fields.size) {
                        "BigQuery result row does not match its schema"
                    }
                    fields.mapIndexed { index, field ->
                        toScalar(columns[index].name, field, row[index])
                    }
                }
                .toList()
        check(rows.size <= maximumRows) {
            "BigQuery page exceeded the requested row bound"
        }
        return BigQueryResultPage(
            page = TabularPage(columns, rows),
            nextPageToken = nextPageToken?.takeIf(String::isNotBlank),
        )
    }

    private fun toColumn(
        name: String,
        field: Field,
    ): TabularColumn {
        if (field.mode == Field.Mode.REPEATED) {
            throw UnsupportedResultTypeException(name, "ARRAY<${field.type}>")
        }
        return TabularColumn(
            name = name,
            type =
                when (field.type) {
                    LegacySQLTypeName.STRING -> TabularScalarType.STRING
                    LegacySQLTypeName.INTEGER -> TabularScalarType.INT64
                    LegacySQLTypeName.FLOAT -> TabularScalarType.FLOAT64
                    LegacySQLTypeName.BOOLEAN -> TabularScalarType.BOOL
                    LegacySQLTypeName.BYTES -> TabularScalarType.BYTES
                    LegacySQLTypeName.NUMERIC -> TabularScalarType.NUMERIC
                    LegacySQLTypeName.BIGNUMERIC -> TabularScalarType.BIGNUMERIC
                    LegacySQLTypeName.DATE -> TabularScalarType.DATE
                    LegacySQLTypeName.TIME -> TabularScalarType.TIME
                    LegacySQLTypeName.DATETIME -> TabularScalarType.DATETIME
                    LegacySQLTypeName.TIMESTAMP -> TabularScalarType.TIMESTAMP
                    LegacySQLTypeName.GEOGRAPHY -> TabularScalarType.GEOGRAPHY
                    LegacySQLTypeName.JSON -> TabularScalarType.JSON
                    LegacySQLTypeName.INTERVAL -> TabularScalarType.INTERVAL
                    else -> throw UnsupportedResultTypeException(name, field.type.toString())
                },
            nullable = field.mode != Field.Mode.REQUIRED,
        )
    }

    private fun toScalar(
        columnName: String,
        field: Field,
        value: FieldValue,
    ): TabularScalar? {
        if (value.isNull) {
            return null
        }
        return when (field.type) {
            LegacySQLTypeName.STRING,
            LegacySQLTypeName.GEOGRAPHY,
            LegacySQLTypeName.JSON,
            LegacySQLTypeName.INTERVAL,
            -> TabularScalar.Text(value.stringValue)
            LegacySQLTypeName.INTEGER -> TabularScalar.Integer64(value.longValue)
            LegacySQLTypeName.FLOAT -> TabularScalar.Float64(value.doubleValue)
            LegacySQLTypeName.BOOLEAN -> TabularScalar.BooleanValue(value.booleanValue)
            LegacySQLTypeName.BYTES -> {
                val bytes = value.bytesValue
                try {
                    TabularScalar.Bytes(BinaryValue.copyOf(bytes))
                } finally {
                    bytes.fill(0)
                }
            }
            LegacySQLTypeName.NUMERIC,
            LegacySQLTypeName.BIGNUMERIC,
            -> TabularScalar.DecimalValue(value.numericValue)
            LegacySQLTypeName.DATE ->
                TabularScalar.DateDays(
                    Math.toIntExact(LocalDate.parse(value.stringValue).toEpochDay()),
                )
            LegacySQLTypeName.TIME -> {
                val time = LocalTime.parse(value.stringValue)
                require(time.nano % NANOSECONDS_PER_MICROSECOND == 0) {
                    "BigQuery TIME exceeds microsecond precision"
                }
                TabularScalar.TimeMicroseconds(
                    time.toNanoOfDay() / NANOSECONDS_PER_MICROSECOND,
                )
            }
            LegacySQLTypeName.DATETIME -> {
                val dateTime = parseDateTime(value.stringValue)
                require(dateTime.nano % NANOSECONDS_PER_MICROSECOND == 0) {
                    "BigQuery DATETIME exceeds microsecond precision"
                }
                TabularScalar.DateTimeMicroseconds(
                    Math.addExact(
                        Math.multiplyExact(
                            dateTime.toEpochSecond(ZoneOffset.UTC),
                            MICROSECONDS_PER_SECOND,
                        ),
                        (dateTime.nano / NANOSECONDS_PER_MICROSECOND).toLong(),
                    ),
                )
            }
            LegacySQLTypeName.TIMESTAMP ->
                TabularScalar.TimestampMicroseconds(value.timestampValue)
            else -> throw UnsupportedResultTypeException(columnName, field.type.toString())
        }
    }

    private fun parseDateTime(value: String): LocalDateTime {
        val normalized =
            when (value.getOrNull(DATE_TIME_SEPARATOR_INDEX)) {
                ' ' -> value.replaceRange(DATE_TIME_SEPARATOR_INDEX, DATE_TIME_SEPARATOR_INDEX + 1, "T")
                'T' -> value
                else -> throw IllegalArgumentException("BigQuery DATETIME has an invalid separator")
            }
        return LocalDateTime.parse(normalized)
    }

    private const val NANOSECONDS_PER_MICROSECOND = 1_000
    private const val MICROSECONDS_PER_SECOND = 1_000_000L
    private const val DATE_TIME_SEPARATOR_INDEX = 10
}
