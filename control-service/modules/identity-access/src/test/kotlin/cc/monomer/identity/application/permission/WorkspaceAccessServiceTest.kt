package cc.monomer.identity.application.permission

import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.WorkspaceActorAuthorizationDenial
import cc.monomer.identity.domain.port.WorkspaceAccessQueryPort
import cc.monomer.identity.domain.port.WorkspaceAccessQueryResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkspaceAccessServiceTest {
    @Test
    fun `read delegates to the workspace access query port exactly once`() = runTest {
        val userId = UserId.generate()
        val workspaceId = WorkspaceId.generate()
        val expected = WorkspaceAccessQueryResult.Denied(
            WorkspaceActorAuthorizationDenial.WORKSPACE_NOT_FOUND,
        )
        val port = mockk<WorkspaceAccessQueryPort>()
        coEvery { port.readWorkspaceAccess(userId, workspaceId) } returns expected
        val service = WorkspaceAccessService(port)

        val result = service.read(userId, workspaceId)

        assertThat(result).isSameAs(expected)
        coVerify(exactly = 1) { port.readWorkspaceAccess(userId, workspaceId) }
    }
}
