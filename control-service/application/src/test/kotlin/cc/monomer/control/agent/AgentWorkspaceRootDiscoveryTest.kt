@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.agent

import cc.monomer.agent.api.AgentCatalogDiscoveryPort
import cc.monomer.agent.api.AgentDashboardOwnerPort
import cc.monomer.agent.api.AgentExploreOwnerPort
import cc.monomer.agent.api.AgentListRequest
import cc.monomer.agent.api.AgentListingOutcome
import cc.monomer.agent.api.AgentReadRequest
import cc.monomer.agent.api.AgentResourceKind
import cc.monomer.agent.api.AgentResourceListingResponse
import cc.monomer.agent.api.AgentResourceOutcome
import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.agent.api.AgentSemanticOwnerPort
import cc.monomer.agent.api.AgentWorkspaceCaller
import cc.monomer.agent.api.AgentWorkspaceOperation
import cc.monomer.agent.api.AgentWorkspaceRootBody
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.every
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Base64

class AgentWorkspaceRootDiscoveryTest {
    private val connector = mockk<cc.monomer.agent.api.AgentConnectorOwnerPort>()
    private val explore = mockk<AgentExploreOwnerPort>()
    private val semantic = mockk<AgentSemanticOwnerPort>()
    private val dashboard = mockk<AgentDashboardOwnerPort>()
    private val catalog = mockk<AgentCatalogDiscoveryPort>()
    private val pages = mockk<cc.monomer.agent.api.AgentPagesOwnerPort>()
    private val caller = mockk<AgentWorkspaceCaller> { every { role } returns cc.monomer.identity.api.WorkspaceRole.OWNER }
    private val management = mockk<cc.monomer.agent.api.AgentManagementOwnerPort> {
        coEvery { read(any<AgentWorkspaceCaller>(), any()) } answers {
            cc.monomer.agent.api.AgentResourceOutcome.Found(cc.monomer.agent.api.AgentResourceEnvelope.WorkspaceAccess(
                AgentResourcePath.parse("workspace/access"), cc.monomer.agent.api.AgentWorkspaceAccessBody(
                    kotlin.uuid.Uuid.parse("01942b04-6e00-7000-8000-000000000001"), caller.role, cc.monomer.agent.api.AgentRevision(1),
                    java.time.ZoneId.of("UTC")), emptyList()))
        }
    }
    private val handler = AgentWorkspaceApplicationComposition().agentWorkspaceHandler(explore, connector, catalog, semantic, dashboard, pages, mockk { every { ifAvailable } returns mockk() }, managementOwnerPort = management)
    private val root = AgentResourcePath.parse("workspace")

    @Test
    fun `root read and paginated list discover only wired concrete collections without data owner work`() = runBlocking {
        val resource = (handler.read(caller, AgentReadRequest(root, null)) as AgentResourceOutcome.Found).resource
        val operations = resource.availableOperations
        assertThat(resource.kind).isEqualTo(AgentResourceKind.COLLECTION)
        assertThat((resource.body as AgentWorkspaceRootBody).resources).isEqualTo(operations)
        assertThat(operations.map { it.path.value }).containsExactlyInAnyOrder(
            "workspace/connectors", "workspace/dashboards", "workspace/explore/runs", "workspace/metrics", "workspace/pages", "workspace/semantic-models", "workspace/access",
            "workspace/lineage/relations", "workspace/lineage/semantic-models", "workspace/lineage/metrics",
        )
        assertThat(operations.all { it.operation == AgentWorkspaceOperation.LIST || it.path.value == "workspace/access" && it.operation == AgentWorkspaceOperation.READ }).isTrue()
        val paths = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = (handler.list(caller, AgentListRequest(root, cursor, 1)) as AgentListingOutcome.Found).listing
            assertThat(page.items).hasSize(1)
            assertThat(page.availableOperations).isEqualTo(operations)
            paths += page.items.single().path.value
            cursor = page.nextCursor
        } while (cursor != null)
        assertThat(paths).isEqualTo(operations.filter { it.operation == AgentWorkspaceOperation.LIST }.map { it.path.value }.sorted())
        coVerify { listOf(explore, semantic, dashboard, catalog, pages) wasNot Called }
        val emptyPage = AgentListingOutcome.Found(AgentResourceListingResponse(emptyList(), null, emptyList()))
        coEvery { connector.list(caller, any()) } returns emptyPage
        coEvery { explore.list(caller, any()) } returns emptyPage
        coEvery { semantic.list(caller, any()) } returns emptyPage
        coEvery { dashboard.list(caller, any()) } returns emptyPage
        coEvery { pages.list(caller, any()) } returns emptyPage
        coEvery { management.list(caller, any()) } returns emptyPage
        operations.filter { it.operation == AgentWorkspaceOperation.LIST }.forEach { operation ->
            assertThat(handler.list(caller, AgentListRequest(operation.path, null, 1))).isEqualTo(emptyPage)
        }
        coVerify(exactly = 1) { explore.list(caller, any()) }
        coVerify(exactly = 2) { semantic.list(caller, any()) }
        coVerify(exactly = 1) { dashboard.list(caller, any()) }
        coVerify(exactly = 1) { pages.list(caller, any()) }
    }

    @Test
    fun `root rejects malformed and foreign cursors without data owner calls`() = runBlocking {
        listOf("!", "", encode("1:workspace/metrics:orders"), encode("2:workspace:workspace/metrics"),
            encode("1:workspace:workspace/unknown"), encode("1:workspace:workspace/metrics") + "=").forEach { cursor ->
            val outcome = handler.list(caller, AgentListRequest(root, cursor, 1)) as AgentListingOutcome.Rejected
            assertThat(outcome.problem.code).isEqualTo("CURSOR_INVALID")
        }
        coVerify { listOf(explore, semantic, dashboard, catalog, pages) wasNot Called }
    }

    @Test
    fun `Viewer discovery exposes Pages and self access without probing data owners`() = runBlocking {
        every { caller.role } returns cc.monomer.identity.api.WorkspaceRole.VIEWER
        val resource = (handler.read(caller, AgentReadRequest(root, null)) as AgentResourceOutcome.Found).resource
        assertThat(resource.availableOperations.map { it.path.value }).containsExactly("workspace/pages", "workspace/pages/tree", "workspace/access")
        val listing = (handler.list(caller, AgentListRequest(root, null, 100)) as AgentListingOutcome.Found).listing
        assertThat(listing.items.map { it.path.value }).containsExactly("workspace/pages")
        coVerify { listOf(connector, explore, semantic, dashboard, catalog, pages) wasNot Called }
    }

    private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
}
