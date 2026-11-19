package cc.monomer.execution.driver.redshift

import cc.monomer.execution.arrow.JavaTimeJdbcResultValueReader
import cc.monomer.execution.arrow.JdbcResultColumnReader
import cc.monomer.execution.arrow.integerBinaryOffsetTimeText
import cc.monomer.execution.arrow.JdbcResultValueReader
import cc.monomer.execution.driver.spi.UnsupportedResultTypeException
import com.amazon.redshift.RedshiftResultSetMetaData
import com.amazon.redshift.core.BaseConnection
import com.amazon.redshift.core.Field
import java.sql.ResultSet

internal object RedshiftJdbcResultValueReader : JdbcResultValueReader by JavaTimeJdbcResultValueReader {
    override fun readText(resultSet: ResultSet, column: Int): String? {
        val metadata = resultSet.metaData
        if (!JdbcResultColumnReader.isTimeWithTimeZone(metadata, column) ||
            metadata.unwrap(RedshiftResultSetMetaData::class.java).getFormat(column) != Field.BINARY_FORMAT) {
            return JavaTimeJdbcResultValueReader.readText(resultSet, column)
        }
        val bytes = resultSet.getBytes(column) ?: return null
        val connection = resultSet.statement.connection.unwrap(BaseConnection::class.java)
        if (!connection.queryExecutor.integerDateTimes) {
            throw UnsupportedResultTypeException(metadata.getColumnLabel(column), metadata.getColumnTypeName(column))
        }
        return integerBinaryOffsetTimeText(bytes, metadata.getColumnLabel(column), metadata.getColumnTypeName(column))
    }
}
