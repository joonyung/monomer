package cc.monomer.execution.arrow

import java.nio.ByteBuffer
import java.sql.ResultSetMetaData
import cc.monomer.execution.driver.spi.ResultColumn
import java.sql.ResultSet
import java.time.ZoneOffset
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime

interface JdbcResultValueReader {
    fun resultColumns(metadata: ResultSetMetaData): List<ResultColumn>

    fun readText(resultSet: ResultSet, column: Int): String?

    fun readDate(resultSet: ResultSet, column: Int): LocalDate?

    fun readTime(resultSet: ResultSet, column: Int): LocalTime?

    fun readTimestamp(resultSet: ResultSet, column: Int): LocalDateTime?

    fun readTimestampInstant(resultSet: ResultSet, column: Int): Instant?
}

object JavaTimeJdbcResultValueReader : JdbcResultValueReader {
    override fun resultColumns(metadata: ResultSetMetaData): List<ResultColumn> = JdbcResultColumnReader.resultColumns(metadata)

    override fun readText(resultSet: ResultSet, column: Int): String? =
        resultSet.getString(column)?.let { text ->
            if (JdbcResultColumnReader.isTimeWithTimeZone(resultSet.metaData, column)) {
                offsetTimeText(text, resultSet.metaData.getColumnLabel(column), resultSet.metaData.getColumnTypeName(column))
            } else text
        }

    override fun readDate(resultSet: ResultSet, column: Int): LocalDate? =
        resultSet.getObject(column, LocalDate::class.java)

    override fun readTime(resultSet: ResultSet, column: Int): LocalTime? =
        resultSet.getObject(column, LocalTime::class.java)

    override fun readTimestamp(resultSet: ResultSet, column: Int): LocalDateTime? =
        resultSet.getObject(column, LocalDateTime::class.java)

    override fun readTimestampInstant(resultSet: ResultSet, column: Int): Instant? =
        resultSet.getObject(column, OffsetDateTime::class.java)?.toInstant()
}

private val offsetTimePattern = Regex("""(\d{2}:\d{2}:\d{2}(?:\.\d{1,12})?)\s*([+-]\d{2})(?::?(\d{2}))?(?::?(\d{2}))?""")

fun offsetTimeText(text: String, columnName: String, typeName: String): String {
    val match = offsetTimePattern.matchEntire(text)
        ?: throw cc.monomer.execution.driver.spi.UnsupportedResultTypeException(columnName, typeName)
    val (time, hours, minutes, seconds) = match.destructured
    return time + hours + ":" + minutes.ifEmpty { "00" } + seconds.takeIf(String::isNotEmpty).orEmpty().let {
        if (it.isEmpty()) "" else ":$it"
    }
}

// PostgreSQL and Redshift integer timetz wire values share microseconds and a west-of-UTC seconds offset.
fun integerBinaryOffsetTimeText(bytes: ByteArray, columnName: String, typeName: String): String {
    if (bytes.size != 12) throw cc.monomer.execution.driver.spi.UnsupportedResultTypeException(columnName, typeName)
    val binary = ByteBuffer.wrap(bytes)
    val microseconds = binary.long
    if (microseconds !in 0..86_400_000_000L) {
        throw cc.monomer.execution.driver.spi.UnsupportedResultTypeException(columnName, typeName)
    }
    val offset = try {
        ZoneOffset.ofTotalSeconds(Math.negateExact(binary.int)).id.let { if (it == "Z") "+00:00" else it }
    } catch (_: RuntimeException) {
        throw cc.monomer.execution.driver.spi.UnsupportedResultTypeException(columnName, typeName)
    }
    val seconds = microseconds / 1_000_000
    val hour = (seconds / 3600).toString().padStart(2, '0')
    val minute = (seconds / 60 % 60).toString().padStart(2, '0')
    val second = (seconds % 60).toString().padStart(2, '0')
    val fraction = (microseconds % 1_000_000).toString().padStart(6, '0').trimEnd('0')
    return "$hour:$minute:$second" + fraction.takeIf(String::isNotEmpty)?.let { ".$it" }.orEmpty() + offset
}
