package cc.monomer.execution.arrow

import cc.monomer.execution.driver.spi.ResultColumn
import cc.monomer.execution.driver.spi.ResultLogicalType
import cc.monomer.execution.driver.spi.UnsupportedResultTypeException
import java.sql.ResultSetMetaData
import java.sql.Types
import java.util.Locale

// The single JDBC type switch keeps Arrow schema and value encoding aligned.
object JdbcResultColumnReader {
    fun resultColumns(metadata: ResultSetMetaData): List<ResultColumn> {
        val names =
            ResultColumn.acceptedNames(
                (1..metadata.columnCount).map { index ->
                    metadata.getColumnLabel(index) ?: metadata.getColumnName(index)
                },
            )
        return (1..metadata.columnCount).map { index ->
            ResultColumn(
                ordinal = index,
                name = names[index - 1],
                logicalType = try {
                    logicalTypeFor(metadata, index)
                } catch (_: UnsupportedResultTypeException) {
                    throw UnsupportedResultTypeException(names[index - 1], metadata.getColumnTypeName(index))
                },
                nullable = metadata.getColumnType(index) == Types.NULL || metadata.isNullable(index) != ResultSetMetaData.columnNoNulls,
            )
        }
    }

    fun logicalTypeFor(
        metadata: ResultSetMetaData,
        index: Int,
    ): ResultLogicalType {
        val jdbcType = metadata.getColumnType(index)
        val typeName = metadata.getColumnTypeName(index)
        if (jdbcType == Types.BIGINT && typeName.lowercase(Locale.ROOT) in setOf("number", "fixed", "decimal", "numeric") &&
            metadata.getPrecision(index) > 18 && metadata.getScale(index) == 0) {
            return ResultLogicalType.DECIMAL
        }
        return logicalTypeFor(jdbcType, typeName)
    }

    fun isTimeWithTimeZone(metadata: ResultSetMetaData, index: Int): Boolean =
        isTimeWithTimeZone(metadata.getColumnType(index), metadata.getColumnTypeName(index))

    private fun isTimeWithTimeZone(jdbcType: Int, typeName: String): Boolean =
        jdbcType == Types.TIME_WITH_TIMEZONE || typeName.trim().lowercase(Locale.ROOT) == "timetz" ||
            typeName.trim().lowercase(Locale.ROOT).matches(Regex("time(?:\\(\\d+\\))? with time zone"))

    fun isTimestampWithTimeZone(
        metadata: ResultSetMetaData,
        index: Int,
    ): Boolean =
        isTimestampWithTimeZone(
            jdbcType = metadata.getColumnType(index),
            jdbcTypeName = metadata.getColumnTypeName(index),
        )

    internal fun isTimestampWithTimeZone(
        jdbcType: Int,
        jdbcTypeName: String,
    ): Boolean =
        jdbcType == Types.TIMESTAMP_WITH_TIMEZONE ||
            jdbcTypeName.trim().lowercase(Locale.ROOT) in
            setOf("timestamptz", "timestamp with time zone", "timestampltz", "timestamp_ltz", "timestamp_tz")

    internal fun logicalTypeFor(
        jdbcType: Int,
        jdbcTypeName: String,
    ): ResultLogicalType =
        when {
            isTimeWithTimeZone(jdbcType, jdbcTypeName) -> ResultLogicalType.TEXT
            jdbcType in setOf(Types.JAVA_OBJECT, Types.STRUCT, Types.NULL) &&
                jdbcTypeName.substringBefore('(').trim().lowercase(Locale.ROOT) in
                setOf("json", "uuid", "ipaddress", "interval day to second", "interval year to month", "map", "row", "unknown") -> ResultLogicalType.TEXT
            else -> when (jdbcType) {
                Types.VARCHAR,
                Types.CHAR,
                Types.CLOB,
                Types.LONGVARCHAR,
                Types.NVARCHAR,
                Types.NCHAR,
                Types.NCLOB,
                Types.OTHER,
                Types.ARRAY,
                -> ResultLogicalType.TEXT
                Types.INTEGER,
                Types.BIGINT,
                Types.SMALLINT,
                Types.TINYINT,
                -> ResultLogicalType.INTEGER
                Types.FLOAT,
                Types.REAL,
                Types.DOUBLE,
                -> ResultLogicalType.FLOAT
                Types.BOOLEAN,
                Types.BIT,
                -> ResultLogicalType.BOOLEAN
                Types.DATE -> ResultLogicalType.DATE
                Types.TIME -> ResultLogicalType.TIME
                Types.TIMESTAMP,
                Types.TIMESTAMP_WITH_TIMEZONE,
                -> ResultLogicalType.TIMESTAMP
                Types.DECIMAL,
                Types.NUMERIC,
                -> ResultLogicalType.DECIMAL
                Types.BINARY,
                Types.VARBINARY,
                Types.BLOB,
                Types.LONGVARBINARY,
                -> ResultLogicalType.BINARY
                else ->
                throw UnsupportedResultTypeException("unknown", jdbcTypeName)
            }
        }
}
