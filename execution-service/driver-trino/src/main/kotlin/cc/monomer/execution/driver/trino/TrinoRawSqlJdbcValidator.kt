package cc.monomer.execution.driver.trino

import cc.monomer.execution.driver.jdbc.bindQueryParameter
import cc.monomer.execution.driver.spi.NullQueryParameterType
import java.sql.PreparedStatement
import java.sql.Types
import cc.monomer.execution.driver.spi.QueryParameter
import java.sql.Connection

internal class TrinoRawSqlJdbcValidator(
    private val connection: Connection,
    private val statementTimeoutSeconds: () -> Int,
) {
    fun validate(
        sql: String,
        parameters: List<QueryParameter>,
    ) {
        connection.prepareStatement(sql).use { statement ->
            statement.queryTimeout = statementTimeoutSeconds()
            statement.bindTrinoQueryParameters(parameters)
            require(statement.parameterMetaData.parameterCount == parameters.size) {
                "Trino validation returned a different parameter count"
            }
            require(requireNotNull(statement.metaData).columnCount > 0) {
                "Trino validation returned no output columns"
            }
        }
    }
}

internal fun PreparedStatement.bindTrinoQueryParameters(parameters: List<QueryParameter>) {
    parameters.forEachIndexed { index, parameter ->
        when {
            // Trino 471 setTimestamp uses milliseconds; its typed Instant path preserves microseconds.
            parameter is QueryParameter.Timestamp -> setObject(index + 1, parameter.value, Types.TIMESTAMP_WITH_TIMEZONE)
            parameter is QueryParameter.Date -> setObject(index + 1, parameter.value, Types.DATE)
            parameter is QueryParameter.Null && parameter.type == NullQueryParameterType.TIMESTAMP ->
                setNull(index + 1, Types.TIMESTAMP_WITH_TIMEZONE)
            else -> bindQueryParameter(index + 1, parameter)
        }
    }
}
