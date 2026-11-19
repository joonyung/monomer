@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.identity.application.grant

import cc.monomer.identity.application.AccessDeniedException
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.OrganizationRole
import cc.monomer.identity.domain.model.AuthorizationRevision
import cc.monomer.identity.domain.model.WorkspaceActorAuthorizationSnapshot
import cc.monomer.identity.domain.port.WorkspaceAuthorizationAssignmentsQueryPort
import cc.monomer.identity.domain.port.WorkspaceAuthorizationAssignmentsQueryResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WorkspaceAuthorizationAssignmentsServiceTest {
    private val revision = AuthorizationRevision(7)
    private val organizationId = OrganizationId.generate()
    private val workspaceId = WorkspaceId.generate()
    private val actorUserId = UserId.generate()

    @Test
    fun `query denial maps to owner denial after one port call`() = runTest {
        val port = mockk<WorkspaceAuthorizationAssignmentsQueryPort>()
        coEvery {
            port.readWorkspaceAuthorizationAssignments(actorUserId, workspaceId)
        } returns WorkspaceAuthorizationAssignmentsQueryResult.Denied
        val service = WorkspaceAuthorizationAssignmentsService(port)

        assertThatThrownBy {
            kotlinx.coroutines.runBlocking {
                service.read(
                    WorkspaceAuthorizationAssignmentsUseCase.Command(actorUserId, workspaceId),
                )
            }
        }.isInstanceOf(AccessDeniedException::class.java)
        coVerify(exactly = 1) {
            port.readWorkspaceAuthorizationAssignments(actorUserId, workspaceId)
        }
    }

    @Test
    fun `query projects members at one revision`() = runTest {
        val port = mockk<WorkspaceAuthorizationAssignmentsQueryPort>()
        val snapshot = WorkspaceActorAuthorizationSnapshot(actorUserId, workspaceId, organizationId, revision, OrganizationRole.MEMBER, cc.monomer.identity.api.WorkspaceRole.OWNER)
        coEvery { port.readWorkspaceAuthorizationAssignments(actorUserId, workspaceId) } returns WorkspaceAuthorizationAssignmentsQueryResult.Authorized(snapshot, emptyList())
        val result = WorkspaceAuthorizationAssignmentsService(port).read(WorkspaceAuthorizationAssignmentsUseCase.Command(actorUserId, workspaceId))
        assertThat(result.authorizationRevision).isEqualTo(revision)
    }
}
