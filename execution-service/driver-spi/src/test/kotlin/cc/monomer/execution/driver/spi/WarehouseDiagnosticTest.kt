package cc.monomer.execution.driver.spi

import kotlin.test.Test
import kotlin.test.assertEquals

class WarehouseDiagnosticTest {
    @Test
    fun `preserves actionable warehouse diagnostics and bounds output`() {
        val reason = "Cannot query over table 'project.analytics.events' without a filter over column 'event_date' that can be used for partition elimination"
        assertEquals(reason, warehouseDiagnostic(reason, null, "fallback"))
        assertEquals("$reason See [redacted URL]", warehouseDiagnostic("$reason See https://host/help?token=abc", null, "fallback"))
        assertEquals("column unknown does not exist", warehouseDiagnostic("column unknown does not exist", null, "fallback"))
        assertEquals("x".repeat(4096), warehouseDiagnostic("x".repeat(5000), null, "fallback"))
        assertEquals("fallback", warehouseDiagnostic(" ", null, "fallback"))
        assertEquals("fallback", warehouseDiagnostic(null, null, "fallback"))
    }

    @Test
    fun `suppresses credential values and credential bearing messages`() {
        MaterializedCredential(mapOf("password" to SecretCharacters.copyOf("opaque-value".toCharArray()))).use { credential ->
            assertEquals("fallback", warehouseDiagnostic("rejected opaque-value", credential, "fallback"))
        }
        MaterializedCredential(mapOf("service_account_json" to SecretCharacters.copyOf(
            """{"project_id":"example-project","private_key":"opaque-private-key-material","client_email":"service@example.test"}""".toCharArray(),
        ))).use { credential ->
            assertEquals("rejected [redacted]", warehouseDiagnostic("rejected opaque-private-key-material", credential, "fallback"))
            assertEquals("Cannot query [redacted].dataset.events without a partition filter", warehouseDiagnostic(
                "Cannot query example-project.dataset.events without a partition filter", credential, "fallback",
            ))
        }
        listOf("password=hunter2", "Authorization: Bearer abc", "access_token=abc", "-----BEGIN PRIVATE KEY-----").forEach {
            assertEquals("fallback", warehouseDiagnostic(it, null, "fallback"))
        }
    }
}
