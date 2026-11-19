package cc.monomer.execution.driver.redshift

import cc.monomer.execution.driver.jdbc.bindQueryParameter
import cc.monomer.execution.driver.jdbc.isOnlyWhitespaceAndComments
import cc.monomer.execution.driver.spi.MAXIMUM_RAW_SQL_BYTES
import cc.monomer.execution.driver.spi.QueryParameter
import com.amazon.redshift.core.BaseConnection
import com.amazon.redshift.core.Parser
import java.sql.Connection

internal class RedshiftRawSqlJdbcValidator(
    private val connection: Connection,
    private val statementTimeoutSeconds: () -> Int,
) {
    fun validate(
        sql: String,
        parameters: List<QueryParameter>,
    ) {
        require(sql.toByteArray(Charsets.UTF_8).size <= MAXIMUM_RAW_SQL_BYTES) {
            "Redshift Raw SQL exceeds the maximum UTF-8 byte length"
        }
        val redshift = connection.unwrap(BaseConnection::class.java)
        val statements = Parser.parseJdbcSql(
            sql, redshift.standardConformingStrings, true, true, false, true,
        ).filterNot {
            isOnlyWhitespaceAndComments(
                it.nativeSql,
                Parser::isSpace,
                Parser::parseLineComment,
                Parser::parseBlockComment,
            )
        }
        require(statements.size == 1) { "Redshift Raw SQL must contain exactly one statement" }
        require(statements.single().bindPositions.size == parameters.size) { "Redshift parameter count differs" }
        connection.prepareStatement(sql).use { statement ->
            statement.queryTimeout = statementTimeoutSeconds()
            statement.bindRedshiftQueryParameters(parameters)
            require((statement.metaData?.columnCount ?: 0) > 0) {
                "Redshift Raw SQL must return result columns"
            }
        }
    }

}

internal fun java.sql.PreparedStatement.bindRedshiftQueryParameters(parameters: List<QueryParameter>) {
    parameters.forEachIndexed { index, parameter ->
        when {
            parameter is QueryParameter.Timestamp -> setObject(index + 1, parameter.value.atOffset(java.time.ZoneOffset.UTC))
            parameter is QueryParameter.Date -> setObject(index + 1, parameter.value)
            parameter is QueryParameter.Null && parameter.type == cc.monomer.execution.driver.spi.NullQueryParameterType.TIMESTAMP ->
                setNull(index + 1, java.sql.Types.TIMESTAMP_WITH_TIMEZONE, "timestamptz")
            else -> bindQueryParameter(index + 1, parameter)
        }
    }
}
