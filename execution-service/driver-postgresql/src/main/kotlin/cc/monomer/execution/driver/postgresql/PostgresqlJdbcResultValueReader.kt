package cc.monomer.execution.driver.postgresql

import cc.monomer.execution.arrow.JavaTimeJdbcResultValueReader
import cc.monomer.execution.arrow.JdbcResultColumnReader
import cc.monomer.execution.arrow.JdbcResultValueReader
import cc.monomer.execution.arrow.integerBinaryOffsetTimeText
import cc.monomer.execution.driver.spi.UnsupportedResultTypeException
import org.postgresql.PGResultSetMetaData
import org.postgresql.core.Field
import org.postgresql.core.BaseConnection
import java.sql.ResultSet

internal object PostgresqlJdbcResultValueReader : JdbcResultValueReader by JavaTimeJdbcResultValueReader {
    override fun readText(resultSet: ResultSet, column: Int): String? {
        val metadata = resultSet.metaData
        if (JdbcResultColumnReader.isTimeWithTimeZone(metadata, column) &&
            metadata.unwrap(PGResultSetMetaData::class.java).getFormat(column) == Field.BINARY_FORMAT) {
            val bytes = resultSet.getBytes(column) ?: return null
            val connection = resultSet.statement.connection.unwrap(BaseConnection::class.java)
            if (!connection.queryExecutor.integerDateTimes) {
                throw UnsupportedResultTypeException(metadata.getColumnLabel(column), metadata.getColumnTypeName(column))
            }
            return integerBinaryOffsetTimeText(bytes, metadata.getColumnLabel(column), metadata.getColumnTypeName(column))
        }
        return JavaTimeJdbcResultValueReader.readText(resultSet, column)
    }
}
