package cc.monomer.execution.driver.bigquery

import cc.monomer.execution.arrow.TabularScalar
import cc.monomer.execution.arrow.TabularScalarType
import cc.monomer.execution.driver.spi.UnsupportedResultTypeException
import com.google.cloud.bigquery.Field
import com.google.cloud.bigquery.FieldValue
import com.google.cloud.bigquery.FieldValueList
import com.google.cloud.bigquery.LegacySQLTypeName
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class BigQueryResultPageMapperTest {
    @Test
    fun mapsBigQueryWireValuesIntoTypedBoundedRows() {
        val fields =
            listOf(
                field("string", LegacySQLTypeName.STRING),
                field("int64", LegacySQLTypeName.INTEGER),
                field("float64", LegacySQLTypeName.FLOAT),
                field("bool", LegacySQLTypeName.BOOLEAN),
                field("bytes", LegacySQLTypeName.BYTES),
                field("numeric", LegacySQLTypeName.NUMERIC),
                field("bignumeric", LegacySQLTypeName.BIGNUMERIC),
                field("date", LegacySQLTypeName.DATE),
                field("time", LegacySQLTypeName.TIME),
                field("datetime", LegacySQLTypeName.DATETIME),
                field("timestamp", LegacySQLTypeName.TIMESTAMP),
                field("geography", LegacySQLTypeName.GEOGRAPHY),
                field("json", LegacySQLTypeName.JSON),
                field("interval", LegacySQLTypeName.INTERVAL),
            )
        val date = LocalDate.parse("2026-07-31")
        val time = LocalTime.parse("12:34:56.123456")
        val dateTime = LocalDateTime.parse("2026-07-31T12:34:56.123456")
        val timestampMicros = 1_754_050_896_654_321L
        val row =
            row(
                fields,
                "monomer",
                Long.MAX_VALUE.toString(),
                "-1.25",
                "true",
                Base64.getEncoder().encodeToString(byteArrayOf(0, 1, 2)),
                "123456789.123456789",
                "12345678901234567890123456789012345678." +
                    "12345678901234567890123456789012345678",
                date.toString(),
                time.toString(),
                "2026-07-31 12:34:56.123456",
                "1.754050896654321E9",
                "POINT(127.0 37.5)",
                """{"ready":true}""",
                "1-2 3 4:5:6.000007",
            )

        val result =
            BigQueryResultPageMapper.map(
                fields = fields,
                values = listOf(row, nullRow(fields)),
                nextPageToken = "next",
                maximumRows = 128,
            )

        assertEquals(TabularScalarType.entries, result.page.columns.map { it.type })
        assertEquals("next", result.nextPageToken)
        val values = result.page.rows.first()
        assertEquals(TabularScalar.Text("monomer"), values[0])
        assertEquals(TabularScalar.Integer64(Long.MAX_VALUE), values[1])
        assertEquals(TabularScalar.Float64(-1.25), values[2])
        assertEquals(TabularScalar.BooleanValue(true), values[3])
        assertContentEquals(
            byteArrayOf(0, 1, 2),
            assertIs<TabularScalar.Bytes>(values[4]).value.copyBytes(),
        )
        assertEquals(
            TabularScalar.DecimalValue(BigDecimal("123456789.123456789")),
            values[5],
        )
        assertEquals(
            TabularScalar.DecimalValue(
                BigDecimal(
                    "12345678901234567890123456789012345678." +
                        "12345678901234567890123456789012345678",
                ),
            ),
            values[6],
        )
        assertEquals(TabularScalar.DateDays(Math.toIntExact(date.toEpochDay())), values[7])
        assertEquals(
            TabularScalar.TimeMicroseconds(time.toNanoOfDay() / 1_000),
            values[8],
        )
        assertEquals(
            TabularScalar.DateTimeMicroseconds(
                dateTime.toEpochSecond(ZoneOffset.UTC) * 1_000_000 +
                    dateTime.nano / 1_000,
            ),
            values[9],
        )
        assertEquals(TabularScalar.TimestampMicroseconds(timestampMicros), values[10])
        assertEquals(TabularScalar.Text("POINT(127.0 37.5)"), values[11])
        assertEquals(TabularScalar.Text("""{"ready":true}"""), values[12])
        assertEquals(TabularScalar.Text("1-2 3 4:5:6.000007"), values[13])
        assertEquals(List(fields.size) { null }, result.page.rows[1])
    }

    @Test
    fun preservesTemporalBoundariesAndNullFromBigQueryMetadata() {
        val fields = listOf(
            field("date", LegacySQLTypeName.DATE),
            field("datetime", LegacySQLTypeName.DATETIME),
            field("timestamp", LegacySQLTypeName.TIMESTAMP),
        )
        val boundaries = listOf("1969-12-31", "1970-01-01", "2024-02-29", "2024-12-31", "2025-01-01")
        val page = BigQueryResultPageMapper.map(
            fields,
            boundaries.map { date ->
                val micros = LocalDate.parse(date).toEpochDay() * 86_400_000_000L - 1L
                row(fields, date, "$date 00:00:00.000001", BigDecimal.valueOf(micros, 6).toPlainString())
            } + listOf(nullRow(fields)),
            null,
            128,
        ).page

        assertEquals(listOf(TabularScalarType.DATE, TabularScalarType.DATETIME, TabularScalarType.TIMESTAMP), page.columns.map { it.type })
        boundaries.forEachIndexed { index, date ->
            val day = LocalDate.parse(date).toEpochDay()
            assertEquals(
                listOf(
                    TabularScalar.DateDays(Math.toIntExact(day)),
                    TabularScalar.DateTimeMicroseconds(day * 86_400_000_000L + 1L),
                    TabularScalar.TimestampMicroseconds(day * 86_400_000_000L - 1L),
                ),
                page.rows[index],
            )
        }
        assertEquals(listOf(null, null, null), page.rows.last())
    }

    @Test
    fun canonicalizesDuplicateAndBlankColumnNamesWithoutSecondaryCollisions() {
        val fields =
            listOf(
                field("a", LegacySQLTypeName.STRING),
                field("a_2", LegacySQLTypeName.STRING),
                field("a", LegacySQLTypeName.STRING),
                field("", LegacySQLTypeName.STRING),
                field("column_4", LegacySQLTypeName.STRING),
                field("", LegacySQLTypeName.STRING),
            )

        val result =
            BigQueryResultPageMapper.map(
                fields = fields,
                values = emptyList(),
                nextPageToken = null,
                maximumRows = 128,
            )

        assertEquals(
            listOf("a", "a_2", "a_3", "column_4", "column_4_2", "column_6"),
            result.page.columns.map { it.name },
        )
    }

    @Test
    fun normalizesColumnNamesWithTheSharedResultColumnRule() {
        val fields =
            listOf(
                field(" padded ", LegacySQLTypeName.STRING),
                field("cafe\u0301", LegacySQLTypeName.STRING),
                field("a\u0007b", LegacySQLTypeName.STRING),
                field("\u0000", LegacySQLTypeName.STRING),
                field("padded", LegacySQLTypeName.STRING),
            )

        val result =
            BigQueryResultPageMapper.map(
                fields = fields,
                values = emptyList(),
                nextPageToken = null,
                maximumRows = 128,
            )

        assertEquals(
            listOf("padded", "caf\u00e9", "ab", "column_4", "padded_2"),
            result.page.columns.map { it.name },
        )
    }

    @Test
    fun rejectsRepeatedRecordRangeAndAnOversizedPage() {
        val repeated =
            Field.newBuilder("values", LegacySQLTypeName.STRING)
                .setMode(Field.Mode.REPEATED)
                .build()
        val record =
            Field.newBuilder(
                "record",
                LegacySQLTypeName.RECORD,
                field("nested", LegacySQLTypeName.STRING),
            ).build()
        val range = Field.newBuilder("range", LegacySQLTypeName.RANGE).build()

        listOf(repeated to "ARRAY<STRING>", record to "RECORD", range to "RANGE").forEach { (unsupported, typeName) ->
            val failure = assertFailsWith<UnsupportedResultTypeException> {
                BigQueryResultPageMapper.map(
                    fields = listOf(unsupported),
                    values = emptyList(),
                    nextPageToken = null,
                    maximumRows = 128,
                )
            }
            assertEquals("Column '${unsupported.name}' has unsupported result type '$typeName'", failure.message)
        }

        val field = field("value", LegacySQLTypeName.STRING)
        assertFailsWith<IllegalStateException> {
            BigQueryResultPageMapper.map(
                fields = listOf(field),
                values = List(129) { row(listOf(field), "value") },
                nextPageToken = null,
                maximumRows = 128,
            )
        }
    }

    @Test
    fun rejectsNullInARequiredField() {
        val required =
            Field.newBuilder("value", LegacySQLTypeName.STRING)
                .setMode(Field.Mode.REQUIRED)
                .build()

        assertFailsWith<IllegalArgumentException> {
            BigQueryResultPageMapper.map(
                fields = listOf(required),
                values =
                    listOf(
                        FieldValueList.of(
                            listOf(FieldValue.of(FieldValue.Attribute.PRIMITIVE, null)),
                            required,
                        ),
                    ),
                nextPageToken = null,
                maximumRows = 128,
            )
        }
    }

    private fun field(
        name: String,
        type: LegacySQLTypeName,
    ): Field =
        Field.newBuilder(name, type)
            .setMode(Field.Mode.NULLABLE)
            .build()

    private fun row(
        fields: List<Field>,
        vararg values: String,
    ): FieldValueList =
        FieldValueList.of(
            values.map { value ->
                FieldValue.of(FieldValue.Attribute.PRIMITIVE, value)
            },
            *fields.toTypedArray(),
        )

    private fun nullRow(fields: List<Field>): FieldValueList =
        FieldValueList.of(
            fields.map {
                FieldValue.of(FieldValue.Attribute.PRIMITIVE, null)
            },
            *fields.toTypedArray(),
        )
}
