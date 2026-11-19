@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package cc.monomer.control.agent

import cc.monomer.authority.api.*
import cc.monomer.control.*
import cc.monomer.control.lineage.*
import cc.monomer.identity.api.*
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import io.mockk.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class AgentManagementLineageAudienceTest {
    @Test fun `every lineage entry preserves audience and requires Explorer before reading topology`() = runTest {
        val workspace = WorkspaceId.generate()
        val credential = PresentedCredential("test-token")
        val admission = mockk<ControlAdmissionFacade>()
        val requests = mutableListOf<WorkspaceRoleAdmissionRequest>()
        coEvery { admission.authorizeWorkspaceRole(capture(requests)) } returns ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY, ControlAdmissionDenial.Identity(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE))
        val focus = mockk<CurrentResourceFocusConePort>()
        val service = LineageGraphQueryService(focus, mockk(), mockk(), mockk(), mockk(), admission, object : ConsistentSnapshotTransactionRunner {
            override suspend fun <T> run(action: suspend () -> T): T = action()
        })
        ExpectedOAuthResource.entries.forEach { audience ->
            service.readDefaultFocus(workspace, credential, audience)
            service.listResources(workspace, ResourceKind.METRIC, "", null, 20, credential, audience)
            service.read(workspace, ResourceRef(ResourceKind.METRIC, Uuid.generateV7()), credential, audience)
            assertEquals(listOf(audience, audience, audience), requests.takeLast(3).map { it.expectedOAuthResource })
            assertEquals(List(3) { WorkspaceRole.EXPLORER }, requests.takeLast(3).map { it.requiredRole })
        }
        verify { focus wasNot Called }
    }
}
