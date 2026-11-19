package cc.monomer.execution.driver.spi

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialRedactionTest {
    @Test
    fun connectorRuntimeBindingRedactsPropertyValues() {
        val connector =
            ConnectorRuntimeBinding(
                schemaVersion = 1u,
                connectorId =
                    ConnectorId.parse(
                        "019f0000-0000-7000-8000-000000000003",
                    ),
                connectorType = ConnectorType.POSTGRESQL,
                properties =
                    mapOf(
                        "host" to ConnectorPropertyValue.Text("warehouse.internal"),
                    ),
            )
        val rendered = connector.toString()

        assertTrue("[REDACTED]" in rendered)
        assertFalse("warehouse.internal" in rendered)
    }

    @Test
    fun materializedCredentialRedactsSecretValues() {
        val credential =
            MaterializedCredential(
                mapOf(
                    "password" to SecretCharacters.copyOf("super-secret".toCharArray()),
                ),
            )
        credential.use {
            val rendered = credential.toString()
            assertTrue("password" in rendered)
            assertFalse("super-secret" in rendered)
        }
    }
}
