package cc.monomer.execution.driver.duckdb

import cc.monomer.execution.driver.spi.RawSqlValidationException
import com.fasterxml.jackson.databind.ObjectMapper
import org.duckdb.DuckDBConnection

internal object DuckDbRawSqlValidator {
    private val mapper = ObjectMapper()

    fun validate(connection: DuckDBConnection, sql: String) {
        connection.prepareStatement("SELECT json_serialize_sql(CAST(? AS VARCHAR))").use { statement ->
            statement.setString(1, sql)
            statement.executeQuery().use { result ->
                check(result.next())
                val parsed = mapper.readTree(result.getString(1))
                if (parsed.path("error").asBoolean(true) || !parsed.path("statements").isArray || parsed.path("statements").size() != 1) {
                    throw RawSqlValidationException("RAW_SQL_VALIDATION_REJECTED", "Sample queries must contain exactly one read-only SELECT statement.", null)
                }
            }
        }
    }
}
