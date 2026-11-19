package cc.monomer.execution.driver.databricks

import cc.monomer.execution.driver.spi.RawSqlValidationException

internal enum class GatePhase(val sql: String) {
    TEXT("SELECT :text_value AS text_value"),
    BIGINT("SELECT :integer_value AS integer_value"),
    DOUBLE("SELECT :float_value AS float_value"),
    BOOLEAN("SELECT :boolean_value AS boolean_value"),
    DECIMAL("SELECT CAST(:decimal_value AS DECIMAL(8,6)) AS decimal_value"),
    DATE("SELECT :date_value AS date_value"),
    TIMESTAMP("SELECT :timestamp_value AS timestamp_value"),
    BINARY_NULL("SELECT :binary_null AS binary_null"),
    EMPTY_RESULT("SELECT 1 AS x WHERE false"),
    CATALOG_CATALOG("SELECT catalog_name FROM information_schema.schemata WHERE catalog_name = :catalog LIMIT 1"),
    CATALOG_SCHEMA("SELECT catalog_name, schema_name FROM information_schema.schemata WHERE catalog_name = :catalog AND schema_name = :schema LIMIT 1"),
    CATALOG_RELATION("SELECT table_catalog AS catalog_name, table_schema AS schema_name, table_name, table_type FROM information_schema.tables WHERE table_catalog = :catalog AND table_schema = :schema AND table_name = :relation LIMIT 1"),
    DESCRIBE_SUCCESS("DESCRIBE QUERY SELECT 1 AS monomer_probe"),
    DESCRIBE_REJECTION("DESCRIBE QUERY SELECT monomer_unresolved_column AS monomer_probe"),
}

internal data class GateCase(val phase: GatePhase, val action: suspend () -> GateOutcome)

internal data class GateOutcome(val passed: Boolean, val driverCode: String, val safeMessage: String, val gateCheck: String)

internal data class GateSummary(val total: Int, val passed: Int, val failed: Int)

internal class GateDriverFailure(val driverCode: String, val safeMessage: String) :
    RuntimeException("Databricks gate driver failed", null, false, false)

internal class GateAggregateFailure(summary: GateSummary) :
    RuntimeException("DATABRICKS_GATE_FAILED total=${summary.total} passed=${summary.passed} failed=${summary.failed}", null, false, false)

internal suspend fun runIndependentGateCases(cases: List<GateCase>, emitEvidence: (String) -> Unit): GateSummary {
    var passed = 0
    cases.forEach { case ->
        val outcome = try {
            case.action()
        } catch (failure: GateDriverFailure) {
            GateOutcome(false, failure.driverCode, failure.safeMessage, "NONE")
        } catch (failure: RawSqlValidationException) {
            GateOutcome(false, failure.failureCode, failure.message.orEmpty(), "NONE")
        } catch (failure: GateFailure) {
            GateOutcome(false, "NONE", "NONE", failure.code.name)
        } catch (_: Throwable) {
            GateOutcome(false, "UNKNOWN", REDACTED_MESSAGE, "UNEXPECTED_FAILURE")
        }
        if (outcome.passed) passed++
        emitEvidence(formatGateEvidence(case.phase, outcome))
    }
    val summary = GateSummary(cases.size, passed, cases.size - passed)
    emitEvidence("DATABRICKS_GATE_SUMMARY total=${summary.total} passed=${summary.passed} failed=${summary.failed}")
    return summary
}

internal fun requireAllGateCasesPassed(summary: GateSummary) {
    if (summary.failed != 0) throw GateAggregateFailure(summary)
}

internal fun formatGateEvidence(phase: GatePhase, outcome: GateOutcome): String {
    val code = outcome.driverCode.takeIf { it == "NONE" || it in KNOWN_DRIVER_FAILURE_CODES } ?: "UNKNOWN"
    val message = outcome.safeMessage.takeIf { it == "NONE" || it in KNOWN_DRIVER_MESSAGES } ?: REDACTED_MESSAGE
    val check = outcome.gateCheck.takeIf { it == "NONE" || it == "UNEXPECTED_FAILURE" || GateCode.entries.any { gateCode -> gateCode.name == it } } ?: "UNKNOWN"
    return "DATABRICKS_GATE_CASE phase=${phase.name} status=${if (outcome.passed) "PASSED" else "FAILED"} code=$code message=\"$message\" gate_check=$check"
}

private const val REDACTED_MESSAGE = "[REDACTED_UNLISTED_MESSAGE]"

private val KNOWN_DRIVER_FAILURE_CODES = setOf(
    "AUTHENTICATION_FAILED", "PERMISSION_DENIED", "CONNECTION_FAILED", "RESOURCE_EXHAUSTED",
    "TIMEOUT", "SYNTAX_ERROR", "OBJECT_NOT_FOUND", "QUERY_BIND_UNSUPPORTED", "CANCELLED",
    "RUN_DEADLINE_EXCEEDED", "DATABRICKS_EXECUTION_FAILED", "RESULT_COLUMN_MISMATCH", "RESULT_ROW_LIMIT_EXCEEDED",
    "RAW_SQL_VALIDATION_REJECTED", "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", "RAW_SQL_VALIDATION_FAILED",
)

private val KNOWN_DRIVER_MESSAGES = setOf(
    BYTES_UNSUPPORTED_MESSAGE,
    "Databricks Arrow column count is inconsistent",
    "Databricks Arrow column metadata is inconsistent",
    "Databricks Arrow schema changed between chunks",
    "Databricks Arrow type is unsupported or inconsistent",
    "Databricks DESCRIBE QUERY returned no output columns",
    "Databricks HTTP client cannot enable retry of all HTTP methods",
    "Databricks HTTP client must not add ambient authentication or cookies",
    "Databricks HTTP client must not follow redirects",
    "Databricks HTTP request failed",
    "Databricks HTTP request was rejected",
    "Databricks HTTP response timed out",
    "Databricks OAuth response is invalid",
    "Databricks Raw SQL exceeds the maximum UTF-8 byte length",
    "Databricks Raw SQL validation deadline was exceeded",
    "Databricks Raw SQL validation failed",
    "Databricks chunk index is invalid",
    "Databricks chunk manifest is incomplete",
    "Databricks chunk manifest is inconsistent",
    "Databricks chunk row count is inconsistent",
    "Databricks connector property is invalid",
    "Databricks connector property is unavailable",
    "Databricks connector property must be text",
    "Databricks credential fields do not match the driver contract",
    "Databricks deadline was exceeded",
    "Databricks decimal metadata is invalid",
    "Databricks execution exceeded the run deadline",
    "Databricks execution failed",
    "Databricks host must be an official public workspace hostname",
    "Databricks next chunk index is inconsistent",
    "Databricks refreshed chunk indices are inconsistent",
    "Databricks refreshed chunk is missing",
    "Databricks response could not be read",
    "Databricks response is missing",
    "Databricks result chunk manifest is incomplete",
    "Databricks result column count is inconsistent",
    "Databricks result contains duplicate chunks",
    "Databricks result contains extra chunks",
    "Databricks result count is invalid",
    "Databricks result download exceeded the byte limit",
    "Databricks result download failed",
    "Databricks result exceeded the artifact byte limit",
    "Databricks result exceeded the authorized row limit",
    "Databricks result exceeded the cell byte limit",
    "Databricks result exceeded the multipart part limit",
    "Databricks result exceeded the record batch byte limit",
    "Databricks result format is invalid",
    "Databricks result headers are invalid",
    "Databricks result headers contain a forbidden name",
    "Databricks result link expired",
    "Databricks result link expiry is invalid",
    "Databricks result link expiry is missing",
    "Databricks result link is invalid",
    "Databricks result link metadata is inconsistent",
    "Databricks result links are missing",
    "Databricks result row total is inconsistent",
    "Databricks result scalar is unsupported",
    "Databricks result schema is missing",
    "Databricks result type is unsupported",
    "Databricks result was truncated",
    "Databricks row limit is outside the supported range",
    "Databricks runs remain attached",
    "Databricks statement ID is invalid",
    "Databricks statement ID is missing",
    "Databricks statement execution failed",
    "Databricks statement state is invalid or unavailable",
    "Databricks statement was cancelled",
    "Databricks warehouse ID is invalid",
    "query result columns do not match declared columns",
)
