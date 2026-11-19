package cc.monomer.execution.driver.snowflake

import cc.monomer.execution.driver.spi.QueryParameter
import cc.monomer.execution.driver.spi.NullQueryParameterType
import java.sql.Connection
import java.sql.SQLException
import net.snowflake.client.core.ExecTimeTelemetryData
import net.snowflake.client.core.ParameterBindingDTO
import net.snowflake.client.core.SFException
import net.snowflake.client.core.SFStatement
import net.snowflake.client.core.SFStatementType
import net.snowflake.client.jdbc.SnowflakeConnection
import net.snowflake.client.jdbc.SnowflakeSQLException
import net.snowflake.client.jdbc.SnowflakeUtil
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.JsonNode

internal class SnowflakeRawSqlValidationDeadlineExceededException : RuntimeException()

internal class SnowflakeRawSqlJdbcValidator(
    private val connection: Connection,
    private val statementTimeoutSeconds: () -> Int,
) {
    fun validate(sql: String, parameters: List<QueryParameter>) {
        val statement = connection.unwrap(SnowflakeConnection::class.java).handler.sfStatement as SFStatement
        try {
            statement.addProperty("query_timeout", statementTimeoutSeconds())
            val bindings = parameters.mapIndexed { index, parameter ->
                (index + 1).toString() to parameter.toSnowflakeBinding()
            }.toMap()
            // JDBC describe drops bindings; the pinned public helper preserves them without executing SQL.
            val response = statement.executeHelper(
                sql, "application/snowflake", bindings, true, true, false, ExecTimeTelemetryData(),
            ) as JsonNode
            check(response.path("success").isBoolean) { "Snowflake describe returned an invalid response" }
            if (!response.path("success").booleanValue()) {
                check(response.path("data").path("sqlState").isTextual || response.path("code").isValueNode) {
                    "Snowflake describe returned no error details"
                }
                SnowflakeUtil.checkErrorAndThrowException(response)
            }
            val data = response.path("data")
            val statementType = data.path("statementTypeId")
            check(statementType.isIntegralNumber) { "Snowflake describe returned no statement type" }
            require(SFStatementType.lookUpTypeById(statementType.longValue()).isSelect) {
                "Snowflake Raw SQL must return query results"
            }
            val numberOfBinds = data.path("numberOfBinds")
            check(numberOfBinds.isIntegralNumber) { "Snowflake describe returned no parameter count" }
            require(numberOfBinds.intValue() == parameters.size) { "Snowflake Raw SQL parameter count does not match" }
        } catch (failure: SQLException) {
            if (failure.sqlState == "0A000" && failure.errorCode in setOf(7, 8)) {
                throw IllegalArgumentException("Snowflake Raw SQL must contain one query statement", failure)
            }
            throw failure
        } catch (failure: SFException) {
            throw SnowflakeSQLException(failure)
        } finally {
            statement.close()
        }
    }
}

private fun QueryParameter.toSnowflakeBinding(): ParameterBindingDTO =
    when (this) {
        is QueryParameter.Text -> ParameterBindingDTO("TEXT", value)
        is QueryParameter.Integer64 -> ParameterBindingDTO("FIXED", value.toString())
        is QueryParameter.Float64 -> ParameterBindingDTO("REAL", value.toString())
        is QueryParameter.Decimal -> ParameterBindingDTO("FIXED", value)
        is QueryParameter.Date -> ParameterBindingDTO("DATE", value.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli().toString())
        is QueryParameter.Timestamp -> ParameterBindingDTO("TIMESTAMP_LTZ", java.math.BigInteger.valueOf(value.epochSecond).multiply(java.math.BigInteger.valueOf(1_000_000_000L)).add(java.math.BigInteger.valueOf(value.nano.toLong())).toString())
        is QueryParameter.BooleanValue -> ParameterBindingDTO("BOOLEAN", value.toString())
        is QueryParameter.Bytes -> ParameterBindingDTO("BINARY", value.copyBytes().toHexString())
        is QueryParameter.Null -> ParameterBindingDTO(when(type) {
            NullQueryParameterType.TEXT -> "TEXT"
            NullQueryParameterType.INTEGER64, NullQueryParameterType.DECIMAL -> "FIXED"
            NullQueryParameterType.FLOAT64 -> "REAL"
            NullQueryParameterType.BOOLEAN -> "BOOLEAN"
            NullQueryParameterType.BYTES -> "BINARY"
            NullQueryParameterType.DATE -> "DATE"
            NullQueryParameterType.TIMESTAMP -> "TIMESTAMP_LTZ"
        }, null)
    }
