package cc.monomer.execution.driver.databricks

import cc.monomer.execution.driver.spi.DriverPhysicalResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DatabricksGateEvidenceTest {
    @Test
    fun `approved plan contains eight scalar selects and six remaining cases without an extra probe`() {
        assertEquals(14, GatePhase.entries.size)
        assertEquals(listOf("TEXT", "BIGINT", "DOUBLE", "BOOLEAN", "DECIMAL", "DATE", "TIMESTAMP", "BINARY_NULL"),
            GatePhase.entries.take(8).map { it.name })
        assertEquals(12, GatePhase.entries.count { it.sql.startsWith("SELECT ") })
        assertEquals(2, GatePhase.entries.count { it.sql.startsWith("DESCRIBE QUERY ") })
        assertFalse(GatePhase.entries.any { it.sql == "SELECT 1 AS monomer_probe" })
        assertTrue(GatePhase.entries.filter { it.name.startsWith("CATALOG_") }.all { it.sql.endsWith("LIMIT 1") })
    }

    @Test
    fun `existing driver failure codes retain safe fixed local messages`() {
        val knownCodes = listOf(
            "AUTHENTICATION_FAILED", "PERMISSION_DENIED", "CONNECTION_FAILED", "RESOURCE_EXHAUSTED",
            "TIMEOUT", "SYNTAX_ERROR", "OBJECT_NOT_FOUND", "QUERY_BIND_UNSUPPORTED", "CANCELLED",
            "RUN_DEADLINE_EXCEEDED", "DATABRICKS_EXECUTION_FAILED", "RESULT_COLUMN_MISMATCH", "RESULT_ROW_LIMIT_EXCEEDED",
            "RAW_SQL_VALIDATION_REJECTED", "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", "RAW_SQL_VALIDATION_FAILED",
        )
        knownCodes.forEach { code ->
            assertEquals("DATABRICKS_GATE_CASE phase=TEXT status=FAILED code=$code message=\"Databricks HTTP request was rejected\" gate_check=NONE",
                formatGateEvidence(GatePhase.TEXT, GateOutcome(false, code, "Databricks HTTP request was rejected", "NONE")))
        }
    }

    @Test
    fun `reader and validation protocol messages remain useful without vendor text`() {
        listOf("Databricks Arrow column metadata is inconsistent", "Databricks Arrow type is unsupported or inconsistent",
            "Databricks response could not be read", "Databricks result type is unsupported",
            "Databricks Raw SQL validation failed", "Databricks HTTP response timed out").forEach { message ->
            assertTrue(formatGateEvidence(GatePhase.DECIMAL, GateOutcome(false, "DATABRICKS_EXECUTION_FAILED", message, "NONE"))
                .contains("message=\"$message\""))
        }
    }

    @Test
    fun `unknown codes and injected fields cannot escape`() {
        listOf("unknown_code", "AUTHENTICATION_FAILED\nAuthorization: synthetic-secret", "https://vendor.test/signed-secret").forEach { code ->
            assertEquals("DATABRICKS_GATE_CASE phase=BINARY_NULL status=FAILED code=UNKNOWN message=\"[REDACTED_UNLISTED_MESSAGE]\" gate_check=UNKNOWN",
                formatGateEvidence(GatePhase.BINARY_NULL, GateOutcome(false, code, PRIVATE_MESSAGE, "body-secret")))
        }
    }

    @Test
    fun `URLs opaque IDs plain body secrets credentials and altered known prose are redacted`() {
        listOf(
            "https://vendor.test/signed-secret", "00000000-0000-7000-8000-000000000001", "opaque-statement-id",
            "plain-body-secret", "Authorization: Bearer synthetic-token", "dapi_synthetic_credential",
            "Databricks response could not be read opaque-statement-id", "Databricks HTTP request was rejected\nplain-body-secret",
            PRIVATE_MESSAGE,
        ).forEach { message ->
            assertEquals("DATABRICKS_GATE_CASE phase=TIMESTAMP status=FAILED code=DATABRICKS_EXECUTION_FAILED message=\"[REDACTED_UNLISTED_MESSAGE]\" gate_check=NONE",
                formatGateEvidence(GatePhase.TIMESTAMP, GateOutcome(false, "DATABRICKS_EXECUTION_FAILED", message, "NONE")))
        }
    }

    @Test
    fun `all fourteen actions execute once after driver cancellation timeout and unexpected failures`() = runBlocking {
        val executed = mutableListOf<GatePhase>()
        val evidence = mutableListOf<String>()
        val cases = GatePhase.entries.mapIndexed { index, phase -> GateCase(phase) {
            executed.add(phase)
            when (index) {
                0 -> requireGateQuerySucceeded(DriverPhysicalResult.Failed("AUTHENTICATION_FAILED", "Databricks HTTP request was rejected"))
                1 -> requireGateQuerySucceeded(DriverPhysicalResult.Cancelled)
                2 -> requireGateQuerySucceeded(DriverPhysicalResult.Failed("TIMEOUT", "Databricks HTTP response timed out"))
                3 -> throw IllegalStateException(PRIVATE_MESSAGE)
                4 -> requireGateQuerySucceeded(DriverPhysicalResult.Failed("injected-code\nbody-secret", PRIVATE_MESSAGE))
            }
            success()
        } }
        val summary = runIndependentGateCases(cases, evidence::add)
        assertEquals(GatePhase.entries.toList(), executed)
        assertEquals(GateSummary(14, 9, 5), summary)
        assertEquals(15, evidence.size)
        assertEquals("DATABRICKS_GATE_SUMMARY total=14 passed=9 failed=5", evidence.last())
        assertTrue(evidence[1].contains("code=CANCELLED message=\"Databricks statement was cancelled\""))
        assertTrue(evidence[2].contains("code=TIMEOUT message=\"Databricks HTTP response timed out\""))
        assertFalse(evidence.joinToString().contains("secret"))
        val failure = assertFailsWith<GateAggregateFailure> { requireAllGateCasesPassed(summary) }
        assertEquals("DATABRICKS_GATE_FAILED total=14 passed=9 failed=5", failure.message)
        assertNull(failure.cause)
        assertEquals(0, failure.stackTrace.size)
        println("DATABRICKS_GATE_SYNTHETIC_INDEPENDENCE_PASSED total=14 passed=9 failed=5")
    }

    @Test
    fun `all successes produce a passing aggregate`() = runBlocking {
        val evidence = mutableListOf<String>()
        val summary = runIndependentGateCases(GatePhase.entries.map { phase -> GateCase(phase) { success() } }, evidence::add)
        assertEquals(GateSummary(14, 14, 0), summary)
        requireAllGateCasesPassed(summary)
        assertEquals(14, evidence.count { it.contains("status=PASSED") })
    }

    @Test
    fun `all failures still execute the complete matrix and aggregate once`() = runBlocking {
        var attempts = 0
        val evidence = mutableListOf<String>()
        val summary = runIndependentGateCases(GatePhase.entries.map { phase -> GateCase(phase) {
            attempts++
            requireGateQuerySucceeded(DriverPhysicalResult.Failed("DATABRICKS_EXECUTION_FAILED", PRIVATE_MESSAGE))
            success()
        } }, evidence::add)
        assertEquals(14, attempts)
        assertEquals(GateSummary(14, 0, 14), summary)
        assertFailsWith<GateAggregateFailure> { requireAllGateCasesPassed(summary) }
        assertEquals(1, evidence.count { it.startsWith("DATABRICKS_GATE_SUMMARY") })
    }

    @Test
    fun `expected rejection is passed with its existing code and safe validation message`() = runBlocking {
        val evidence = mutableListOf<String>()
        val summary = runIndependentGateCases(listOf(GateCase(GatePhase.DESCRIBE_REJECTION) {
            GateOutcome(true, "RAW_SQL_VALIDATION_REJECTED", "Databricks Raw SQL validation failed", "NONE")
        }), evidence::add)
        assertEquals(GateSummary(1, 1, 0), summary)
        assertEquals("DATABRICKS_GATE_CASE phase=DESCRIBE_REJECTION status=PASSED code=RAW_SQL_VALIDATION_REJECTED message=\"Databricks Raw SQL validation failed\" gate_check=NONE", evidence.first())
    }

    @Test
    fun `binary typed null preflight rejection is passed with safe contract evidence`() = runBlocking {
        val evidence = mutableListOf<String>()
        val summary = runIndependentGateCases(listOf(GateCase(GatePhase.BINARY_NULL) {
            GateOutcome(true, "QUERY_BIND_UNSUPPORTED", BYTES_UNSUPPORTED_MESSAGE, "NONE")
        }), evidence::add)
        assertEquals(GateSummary(1, 1, 0), summary)
        assertEquals("DATABRICKS_GATE_CASE phase=BINARY_NULL status=PASSED code=QUERY_BIND_UNSUPPORTED message=\"$BYTES_UNSUPPORTED_MESSAGE\" gate_check=NONE", evidence.first())
    }

    @Test
    fun `gate verification failures do not prevent subsequent cases`() = runBlocking {
        val evidence = mutableListOf<String>()
        val summary = runIndependentGateCases(listOf(
            GateCase(GatePhase.DATE) { throw GateFailure(GateCode.RESULT_TYPE_MISMATCH) },
            GateCase(GatePhase.TIMESTAMP) { success() },
        ), evidence::add)
        assertEquals(GateSummary(2, 1, 1), summary)
        assertTrue(evidence.first().endsWith("gate_check=RESULT_TYPE_MISMATCH"))
        assertTrue(evidence[1].contains("status=PASSED"))
    }

    @Test
    fun `successful driver result passes through without changing values`() {
        val result = DriverPhysicalResult.Succeeded(1uL, 1uL, null)
        assertSame(result, requireGateQuerySucceeded(result))
    }

    private fun success() = GateOutcome(true, "NONE", "NONE", "NONE")

    private companion object {
        const val PRIVATE_MESSAGE = "synthetic-body bound-value Authorization: synthetic-token https://vendor.test/signed-secret statement-id"
    }
}
