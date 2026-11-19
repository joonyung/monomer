@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.search

import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.agent.api.AgentWorkspaceCaller
import cc.monomer.connector.api.ConnectorSearchReadPort
import cc.monomer.identity.api.WorkspaceId
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SearchCurrentListingsTest {
    @Test
    fun `metric search reuses current source connector metadata for its listing items`() = runBlocking<Unit> {
        val workspace = WorkspaceId.generate()
        val semantic = mockk<cc.monomer.semantic.api.SemanticCurrentReadUseCase>()
        val caller = mockk<AgentWorkspaceCaller> { every { workspaceId } returns workspace }
        val name = cc.monomer.semantic.api.SemanticElementName("revenue")
        val time = java.time.Instant.parse("2026-10-08T00:00:00Z")
        val metric = cc.monomer.semantic.api.MetricView(cc.monomer.semantic.api.MetricId.generate(), workspace,
            mockk { every { this@mockk.name } returns name }, time, time,
                emptyList(),
            )
        val connector = cc.monomer.semantic.api.MetricSourceConnector(cc.monomer.connector.api.ConnectorId.generate(), "Analytics")
        coEvery { semantic.findMetricByName(workspace, name) } returns metric
        coEvery { semantic.findMetricSourceConnectors(workspace, setOf(name)) } returns mapOf(name to listOf(connector))
        val listings = SearchCurrentListings(mockk(), mockk(), semantic, mockk())

        val item = assertIs<cc.monomer.agent.api.AgentResourceListingItem.Metric>(
            listings.read(caller, listOf(AgentResourcePath.parse("workspace/metrics/revenue"))).single(),
        )

        assertEquals(listOf(cc.monomer.agent.api.AgentMetricSourceConnector(connector.connectorId.value, "Analytics")), item.sourceConnectors)
        coVerify(exactly = 1) { semantic.findMetricSourceConnectors(workspace, setOf(name)) }
    }

    @Test
    fun `a well-formed connector id that cannot exist is an absent scope`() = runBlocking<Unit> {
        val connectors = mockk<ConnectorSearchReadPort>()
        val listings = SearchCurrentListings(connectors, mockk(), mockk(), mockk())
        val workspace = WorkspaceId.generate()
        val caller = mockk<AgentWorkspaceCaller> { every { workspaceId } returns workspace }

        assertFalse(listings.scopeExists(caller, AgentResourcePath.parse("workspace/connectors/123e4567-e89b-42d3-a456-426614174000")))
        verify(exactly = 0) { connectors.readCurrentListings(workspace, any()) }
    }
}
