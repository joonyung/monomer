package cc.monomer.connector

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectorSchemaContractTest {
    @Test
    fun `connector schema stores current state and immutable credential versions`() {
        val ddl = javaClass.classLoader
            .getResource("db/connector/schema.sql")!!
            .readText()
            .lowercase()

        assertTrue("create table if not exists connector.connectors" in ddl)
        assertTrue("connector_type text not null" in ddl)
        assertTrue("authority_revision bigint not null" in ddl)
        assertTrue("create table if not exists connector.credential_versions" in ddl)
        assertTrue("credential_versions_immutable" in ddl)
        assertTrue("connector_identity_immutable" in ddl)
        assertTrue("connector_mutation_receipts" in ddl)
        assertFalse("credential_vault" in ddl)
        assertFalse("stage_claims" in ddl)
        assertFalse("activation_intent" in ddl)
        assertFalse("owner_events" in ddl)
        assertFalse("vault_locator" in ddl)
    }
}
