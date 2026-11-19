package cc.monomer.execution.driver.snowflake

import cc.monomer.execution.arrow.JdbcResultValueReader
import cc.monomer.execution.arrow.JdbcResultColumnReader
import cc.monomer.execution.driver.spi.ResultColumn
import java.sql.ResultSetMetaData
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Calendar
import java.util.TimeZone

internal object SnowflakeJdbcResultValueReader : JdbcResultValueReader {
    override fun resultColumns(metadata: ResultSetMetaData): List<ResultColumn> = JdbcResultColumnReader.resultColumns(metadata)

    override fun readText(resultSet: ResultSet, column: Int): String? = resultSet.getString(column)

    override fun readDate(resultSet: ResultSet, column: Int): LocalDate? =
        resultSet.getDate(column)?.toLocalDate()

    override fun readTime(resultSet: ResultSet, column: Int): LocalTime? =
        resultSet.getTimestamp(column, utcCalendar())?.let {
            // TIME-to-Timestamp stays UTC-based in both JDBC_USE_SESSION_TIMEZONE modes; getTime can apply the JVM timezone.
            LocalTime.ofNanoOfDay(it.time * 1_000_000L)
        }

    override fun readTimestamp(resultSet: ResultSet, column: Int): LocalDateTime? =
        resultSet.getTimestamp(column, utcCalendar())?.toInstant()?.let {
            LocalDateTime.ofInstant(it, ZoneOffset.UTC)
        }

    override fun readTimestampInstant(resultSet: ResultSet, column: Int): Instant? =
        resultSet.getTimestamp(column, utcCalendar())?.toInstant()

    // An explicit UTC Calendar preserves NTZ wallclock fields without the JVM timezone or DST.
    private fun utcCalendar(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
}
