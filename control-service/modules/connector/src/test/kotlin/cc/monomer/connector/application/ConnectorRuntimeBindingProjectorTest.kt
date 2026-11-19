@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.connector.application

import cc.monomer.connector.api.ConnectorExecutionBinding
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ContentDigest
import cc.monomer.connector.api.CredentialEnvelopeReference
import cc.monomer.connector.api.CredentialVersionId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.CredentialVersionReference
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class ConnectorRuntimeBindingProjectorTest {
    @Test
    fun `projects the stable connector identity and credential envelope reference`() {
        val connectorId = ConnectorId(Uuid.generateV7())
        val credentialVersionId = CredentialVersionId(Uuid.generateV7())
        val binding = ConnectorExecutionBinding(
            connectorId = connectorId,
            workspaceId = WorkspaceId(Uuid.generateV7()),
            connectorType = "trino",
            displayName = "analytics",
            properties = mapOf(
                "host" to "trino.internal",
                "port" to "8443",
                "catalog" to "warehouse",
                "schema" to "analytics",
                "ssl" to "true",
                "sslVerification" to "FULL",
            ),
            credentialReference = CredentialEnvelopeReference(
                credentialVersionId = credentialVersionId,
                keyVersion = "mainline-primary",
                envelopeDigest = ContentDigest("sha256:" + "a".repeat(64)),
            ),
            updatedAt = Instant.parse("2026-08-19T00:00:00Z"),
        )

        val projected = ConnectorRuntimeBindingProjector().project(binding)

        assertEquals(ConnectorReference(connectorId.value), projected.connectorId)
        assertEquals(ConnectorType.TRINO, projected.connectorType)
        assertEquals(ConnectorRuntimePropertyValue.Integer(8443), projected.properties["port"])
        assertEquals(ConnectorRuntimePropertyValue.Text("FULL"), projected.properties["ssl_verification"])
        assertEquals(
            CredentialVersionReference(credentialVersionId.value),
            projected.managedCredential.credentialVersion,
        )
        assertEquals("mainline-primary", projected.managedCredential.keyVersion)
        assertEquals("a".repeat(64), projected.managedCredential.envelopeDigest.value)
    }
}
