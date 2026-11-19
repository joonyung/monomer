@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.catalog.application

import cc.monomer.catalog.application.CatalogConnectorStateRemoval
import cc.monomer.catalog.application.CatalogConnectorStateRepository
import cc.monomer.connector.api.ConnectorDependentStateRemoval
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test

class CatalogConnectorStateRemovalTest {
    private val workspaceId = WorkspaceId(Uuid.generateV7())
    private val connectorId = ConnectorId(Uuid.generateV7())

    @Test
    fun `active refresh blocks removal after taking the catalog connector lock`() {
        val repository = mockk<CatalogConnectorStateRepository>(relaxed = true)
        every { repository.hasActiveConnectorWork(workspaceId, connectorId) } returns true

        assertEquals(
            ConnectorDependentStateRemoval.REFRESH_ACTIVE,
            CatalogConnectorStateRemoval(repository).removeInOwnerTransaction(workspaceId, connectorId),
        )

        verifyOrder {
            repository.lockConnector(workspaceId, connectorId)
            repository.hasActiveConnectorWork(workspaceId, connectorId)
        }
        verify(exactly = 0) { repository.removeConnectorState(workspaceId, connectorId) }
    }

    @Test
    fun `idle connector state is removed under the catalog connector lock`() {
        val repository = mockk<CatalogConnectorStateRepository>(relaxed = true)
        every { repository.hasActiveConnectorWork(workspaceId, connectorId) } returns false

        assertEquals(
            ConnectorDependentStateRemoval.REMOVED,
            CatalogConnectorStateRemoval(repository).removeInOwnerTransaction(workspaceId, connectorId),
        )

        verifyOrder {
            repository.lockConnector(workspaceId, connectorId)
            repository.hasActiveConnectorWork(workspaceId, connectorId)
            repository.removeConnectorState(workspaceId, connectorId)
        }
    }
}
