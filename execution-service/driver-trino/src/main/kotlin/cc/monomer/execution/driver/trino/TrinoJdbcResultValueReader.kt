package cc.monomer.execution.driver.trino

import cc.monomer.execution.arrow.JavaTimeJdbcResultValueReader
import cc.monomer.execution.arrow.JdbcResultValueReader
import com.fasterxml.jackson.databind.ObjectMapper
import io.trino.jdbc.`$internal`.client.Row
import java.sql.ResultSetMetaData
import cc.monomer.execution.driver.spi.ResultColumn
import cc.monomer.execution.driver.spi.UnsupportedResultTypeException
import io.trino.sql.parser.SqlParser
import io.trino.sql.parser.ParsingException
import io.trino.sql.tree.GenericDataType
import io.trino.sql.tree.Node
import io.trino.sql.tree.RowDataType
import io.trino.sql.tree.TypeParameter
import java.sql.ResultSet
import java.sql.SQLDataException
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalTime

internal object TrinoJdbcResultValueReader : JdbcResultValueReader by JavaTimeJdbcResultValueReader {
    private val jsonMapper = ObjectMapper()

    override fun resultColumns(metadata: ResultSetMetaData): List<ResultColumn> {
        val columns = JavaTimeJdbcResultValueReader.resultColumns(metadata)
        for (column in columns) {
            val typeName = metadata.getColumnTypeName(column.ordinal)
            if ("map(" !in typeName) continue
            val type = try {
                SqlParser().createType(typeName)
            } catch (_: ParsingException) {
                throw UnsupportedResultTypeException(column.name, typeName)
            }
            if (hasUnsupportedMapKey(type)) throw UnsupportedResultTypeException(column.name, typeName)
        }
        return columns
    }

    private fun hasUnsupportedMapKey(type: Node): Boolean {
        if (type is GenericDataType && type.name.value == "map") {
            val key = (type.arguments.firstOrNull() as? TypeParameter)?.value
            if (key is RowDataType || key is GenericDataType && key.name.value in setOf("array", "map")) return true
        }
        return type.children.any(::hasUnsupportedMapKey)
    }

    override fun readText(resultSet: ResultSet, column: Int): String? {
        val typeName = resultSet.metaData.getColumnTypeName(column)
        return if (typeName.substringBefore('(') in setOf("map", "row", "array")) {
            val value = resultSet.getObject(column, Any::class.java) ?: return null
            jsonMapper.writeValueAsString(jsonValue(value))
        } else JavaTimeJdbcResultValueReader.readText(resultSet, column)
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        is Row -> value.fields.map { field -> linkedMapOf("name" to field.name.orElse(null), "value" to jsonValue(field.value)) }
        is Map<*, *> -> value.entries.map { (key, entryValue) -> listOf(jsonValue(key), jsonValue(entryValue)) }
        is List<*> -> value.map(::jsonValue)
        else -> value
    }

    private val timeText = Regex("""(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,12}))?""")

    override fun readTimestampInstant(resultSet: ResultSet, column: Int): Instant? =
        resultSet.getObject(column, Instant::class.java)

    // Trino JDBC 471 converts `time` only to java.sql.Time (millisecond, JVM zone); its text keeps every server digit.
    override fun readTime(resultSet: ResultSet, column: Int): LocalTime? {
        val text = resultSet.getString(column) ?: return null
        val match = timeText.matchEntire(text) ?: throw invalidTime(null)
        val (hour, minute, second, fraction) = match.destructured
        return try {
            LocalTime.of(hour.toInt(), minute.toInt(), second.toInt(), fraction.padEnd(9, '0').take(9).toInt())
        } catch (failure: DateTimeException) {
            throw invalidTime(failure)
        }
    }

    private fun invalidTime(cause: Throwable?): SQLDataException =
        SQLDataException("Trino time value is not in HH:mm:ss[.fraction] form", "22007", cause)
}
