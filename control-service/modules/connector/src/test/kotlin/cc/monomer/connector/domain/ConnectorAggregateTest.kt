@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.connector.domain

import cc.monomer.connector.api.*
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ContentDigest
import cc.monomer.connector.api.CredentialEnvelopeReference
import cc.monomer.connector.api.CredentialVersionId
import cc.monomer.identity.api.WorkspaceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid
import java.time.Instant

class ConnectorAggregateTest {
    @Test
    fun `two depth database is fixed while connection maintenance remains editable`() {
        for (type in listOf("postgresql", "redshift")) {
            val connectorType = ConnectorType(type)
            val createdAt = Instant.parse("2026-08-19T00:00:00Z")
            val originalProperties = mapOf("database" to "warehouse", "host" to "db.example", "port" to "5432")
            val connector = Connector(
                id = ConnectorId(Uuid.generateV7()),
                workspaceId = WorkspaceId(Uuid.generateV7()),
                connectorType = connectorType,
                displayName = "Warehouse",
                properties = properties(connectorType, originalProperties),
                credentialReference = reference(),
                authorityRevision = 1,
                createdAt = createdAt,
                updatedAt = createdAt,
                deletedAt = null,
            )
            val replacementCredential = reference()
            val revised = connector.revise(
                displayName = "Renamed",
                properties = properties(connectorType, originalProperties + ("port" to "5439")),
                credentialReference = replacementCredential,
                committedAt = createdAt.plusSeconds(1),
            )
            assertEquals("5439", revised.properties.storageValues()["port"])
            assertEquals(replacementCredential, revised.credentialReference)
            for (database in listOf("production", "Warehouse")) {
                assertFailsWith<IllegalArgumentException> {
                    connector.revise(
                        displayName = "Renamed",
                        properties = properties(connectorType, originalProperties + ("database" to database)),
                        credentialReference = replacementCredential,
                        committedAt = createdAt.plusSeconds(1),
                    )
                }
            }
            assertEquals("warehouse", connector.properties.storageValues()["database"])
        }
    }

    @Test
    fun `revise keeps connector type and advances only authority ordering`() {
        val createdAt = Instant.parse("2026-08-19T00:00:00Z")
        val connector = Connector(
            id = ConnectorId(Uuid.generateV7()),
            workspaceId = WorkspaceId(Uuid.generateV7()),
            connectorType = ConnectorType("postgresql"),
            displayName = "Warehouse",
            properties = properties(
                ConnectorType("postgresql"),
                mapOf("database" to "warehouse", "host" to "db.example", "port" to "5432"),
            ),
            credentialReference = reference(),
            authorityRevision = 1,
            createdAt = createdAt,
            updatedAt = createdAt,
            deletedAt = null,
        )

        val revised = connector.revise(
            displayName = "Warehouse 2",
            properties = connector.properties,
            credentialReference = reference(),
            committedAt = createdAt.plusSeconds(1),
        )

        assertEquals(ConnectorType("postgresql"), revised.connectorType)
        assertEquals(2, revised.authorityRevision)
        assertEquals("Warehouse 2", revised.displayName)
        assertNotEquals(connector.updatedAt, revised.updatedAt)
    }

    private fun reference(): CredentialEnvelopeReference = CredentialEnvelopeReference(
        credentialVersionId = CredentialVersionId(Uuid.generateV7()),
        keyVersion = "local-v1",
        envelopeDigest = ContentDigest("sha256:" + "a".repeat(64)),
    )
}

private fun properties(type: ConnectorType, values: Map<String, String>) =
    connectorPropertiesFromStorage(ConnectorProductType.fromStorageValue(type.value), values)
