package cc.monomer.execution.driver.postgresql

import cc.monomer.execution.driver.jdbc.isOnlyWhitespaceAndComments
import cc.monomer.execution.driver.spi.MAXIMUM_RAW_SQL_BYTES
import cc.monomer.execution.driver.spi.NullQueryParameterType
import cc.monomer.execution.driver.spi.QueryParameter
import java.sql.Connection
import org.postgresql.core.BaseConnection
import org.postgresql.core.Oid
import org.postgresql.core.Parser
import org.postgresql.core.QueryExecutor
import org.postgresql.core.ResultHandlerBase
import org.postgresql.core.Query
import org.postgresql.core.Field
import org.postgresql.core.Tuple
import org.postgresql.core.ResultCursor

internal class PostgresqlRawSqlValidator(private val connection: Connection) {
    fun validate(sql: String, parameters: List<QueryParameter>) {
        require(sql.toByteArray(Charsets.UTF_8).size <= MAXIMUM_RAW_SQL_BYTES) { "PostgreSQL SQL exceeds the size limit" }
        val postgres = connection.unwrap(BaseConnection::class.java)
        val executor = postgres.queryExecutor
        val nativeQueries = Parser.parseJdbcSql(
            sql, postgres.standardConformingStrings, true, true, false, true,
        ).filterNot {
            isOnlyWhitespaceAndComments(
                it.nativeSql,
                Parser::isSpace,
                Parser::parseLineComment,
                Parser::parseBlockComment,
            )
        }
        require(nativeQueries.size == 1) { "PostgreSQL validation requires one statement" }
        val query = executor.wrap(nativeQueries)
        try {
            val bindings = query.createParameterList()
            require(bindings.parameterCount == parameters.size) { "PostgreSQL parameter count differs" }
            parameters.forEachIndexed { index, parameter ->
                val oid = when (parameter) {
                    is QueryParameter.Text -> Oid.VARCHAR
                    is QueryParameter.Integer64 -> Oid.INT8
                    is QueryParameter.Float64 -> Oid.FLOAT8
                    is QueryParameter.BooleanValue -> Oid.BOOL
                    is QueryParameter.Bytes -> Oid.BYTEA
                    is QueryParameter.Decimal -> Oid.NUMERIC
                    is QueryParameter.Date -> Oid.DATE
                    is QueryParameter.Timestamp -> Oid.TIMESTAMPTZ
                    is QueryParameter.Null -> when (parameter.type) {
                        NullQueryParameterType.TEXT -> Oid.VARCHAR
                        NullQueryParameterType.INTEGER64 -> Oid.INT8
                        NullQueryParameterType.FLOAT64 -> Oid.FLOAT8
                        NullQueryParameterType.BOOLEAN -> Oid.BOOL
                        NullQueryParameterType.BYTES -> Oid.BYTEA
                        NullQueryParameterType.DECIMAL -> Oid.NUMERIC
                        NullQueryParameterType.DATE -> Oid.DATE
                        NullQueryParameterType.TIMESTAMP -> Oid.TIMESTAMPTZ
                    }
                }
                bindings.setNull(index + 1, oid)
            }
            var outputColumns = 0
            val handler = object : ResultHandlerBase() {
                override fun handleResultRows(query: Query, fields: Array<Field>, tuples: List<Tuple>, cursor: ResultCursor?) {
                    outputColumns = fields.size
                }
            }
            executor.execute(
                query, bindings, handler, 0, 0,
                QueryExecutor.QUERY_DESCRIBE_ONLY or QueryExecutor.QUERY_SUPPRESS_BEGIN,
            )
            require(outputColumns > 0) { "PostgreSQL validation returned no output columns" }
        } finally {
            query.close()
        }
    }

}
