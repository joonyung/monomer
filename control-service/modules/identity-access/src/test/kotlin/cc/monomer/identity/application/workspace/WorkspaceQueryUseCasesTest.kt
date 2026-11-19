package cc.monomer.identity.application.workspace

import cc.monomer.identity.application.AccessDeniedException
import cc.monomer.identity.application.ScopedResourceNotFoundException
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.OrganizationMember
import cc.monomer.identity.domain.model.OrganizationRole
import cc.monomer.identity.domain.model.AuthorizationRevision
import cc.monomer.identity.domain.model.User
import cc.monomer.identity.domain.model.AccessibleWorkspace
import cc.monomer.identity.domain.model.Workspace
import cc.monomer.identity.domain.model.WorkspaceActorAuthorizationSnapshot
import cc.monomer.identity.domain.model.WorkspaceMember
import cc.monomer.identity.domain.port.WorkspaceMemberCandidatesQueryPort
import cc.monomer.identity.domain.port.WorkspaceMemberCandidatesQueryResult
import cc.monomer.identity.domain.port.WorkspaceMembersQueryPort
import cc.monomer.identity.domain.port.WorkspaceMembersQueryResult
import cc.monomer.identity.domain.port.ManagedWorkspacesQueryPort
import cc.monomer.identity.domain.port.ManagedWorkspacesQueryResult
import cc.monomer.identity.domain.port.WorkspaceOwnerCandidatesQueryPort
import cc.monomer.identity.domain.port.WorkspaceOwnerCandidatesQueryResult
import cc.monomer.identity.domain.port.WorkspaceQuery
import cc.monomer.identity.domain.port.WorkspaceScopeFailure
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WorkspaceQueryUseCasesTest {
    private val now = Instant.parse("2026-06-04T10:00:00Z")
    private val organizationId = OrganizationId.generate()
    private val workspaceId = WorkspaceId.generate()
    private val adminUserId = UserId.generate()
    private val memberUserId = UserId.generate()
    private val revision = AuthorizationRevision(9)

    @Test
    fun `accessible inventory is independent of organization admin expansion`() = runTest {
        val accessible = listOf(AccessibleWorkspace(workspace(WorkspaceId.generate()), cc.monomer.identity.api.WorkspaceRole.EXPLORER))
        val result = AccessibleWorkspacesService(
            workspaceQuery(accessible),
        ).list(AccessibleWorkspacesUseCase.Command(adminUserId))

        assertThat(result).isEqualTo(accessible)
    }

    @Test
    fun `managed workspace service invokes one operation port and returns inventory`() = runTest {
        val workspaces = listOf(workspace(WorkspaceId.generate()), workspace(WorkspaceId.generate()))
        val port = mockk<ManagedWorkspacesQueryPort>()
        coEvery {
            port.readManagedWorkspaces(adminUserId, organizationId)
        } returns ManagedWorkspacesQueryResult.Authorized(workspaces)

        val result = ManagedWorkspacesService(
            port,
        ).list(ManagedWorkspacesUseCase.Command(adminUserId, organizationId))

        assertThat(result).isEqualTo(workspaces)
        coVerify(exactly = 1) { port.readManagedWorkspaces(adminUserId, organizationId) }
    }

    @Test
    fun `managed workspace denial preserves forbidden mapping`() = runTest {
        val port = mockk<ManagedWorkspacesQueryPort>()
        coEvery {
            port.readManagedWorkspaces(adminUserId, organizationId)
        } returns ManagedWorkspacesQueryResult.Denied
        val service = ManagedWorkspacesService(port)

        assertThatThrownBy {
            kotlinx.coroutines.runBlocking {
                service.list(ManagedWorkspacesUseCase.Command(adminUserId, organizationId))
            }
        }.isInstanceOf(AccessDeniedException::class.java)
        coVerify(exactly = 1) { port.readManagedWorkspaces(adminUserId, organizationId) }
    }

    @Test
    fun `workspace member service invokes one operation port and returns roster`() = runTest {
        val owner = User(memberUserId, "member@example.com", "Member")
        val roster = listOf(workspaceMember(owner, true))
        val port = mockk<WorkspaceMembersQueryPort>()
        coEvery {
            port.readWorkspaceMembers(memberUserId, workspaceId)
        } returns WorkspaceMembersQueryResult.Authorized(
            authorization = authorization(),
            members = roster,
        )
        val service = WorkspaceMembersService(port)

        val result = service.list(WorkspaceMembersUseCase.Command(memberUserId, workspaceId))

        assertThat(result).containsExactlyElementsOf(roster)
        coVerify(exactly = 1) { port.readWorkspaceMembers(memberUserId, workspaceId) }
    }

    @Test
    fun `workspace member denial preserves forbidden mapping`() = runTest {
        val port = mockk<WorkspaceMembersQueryPort>()
        coEvery {
            port.readWorkspaceMembers(adminUserId, workspaceId)
        } returns WorkspaceMembersQueryResult.Denied
        val service = WorkspaceMembersService(port)

        assertThatThrownBy {
            kotlinx.coroutines.runBlocking {
                service.list(WorkspaceMembersUseCase.Command(adminUserId, workspaceId))
            }
        }.isInstanceOf(AccessDeniedException::class.java)
        coVerify(exactly = 1) { port.readWorkspaceMembers(adminUserId, workspaceId) }
    }

    @Test
    fun `owner candidates service invokes one operation port and returns stable candidates`() = runTest {
        val alpha = User(UserId.generate(), "alpha@example.com", "Alpha")
        val zulu = User(UserId.generate(), "zulu@example.com", "Zulu")
        val port = mockk<WorkspaceOwnerCandidatesQueryPort>()
        coEvery {
            port.readWorkspaceOwnerCandidates(memberUserId, organizationId, workspaceId)
        } returns WorkspaceOwnerCandidatesQueryResult.Authorized(
            authorization = authorization( owner = true),
            users = listOf(alpha, zulu),
        )
        val service = WorkspaceOwnerCandidatesService(port)

        val result = service.list(
            WorkspaceOwnerCandidatesUseCase.Command(memberUserId, organizationId, workspaceId),
        )

        assertThat(result).containsExactly(alpha, zulu)
        coVerify(exactly = 1) {
            port.readWorkspaceOwnerCandidates(memberUserId, organizationId, workspaceId)
        }
    }

    @Test
    fun `owner candidate scope failures stay masked`() = runTest {
        val port = mockk<WorkspaceOwnerCandidatesQueryPort>()
        coEvery {
            port.readWorkspaceOwnerCandidates(adminUserId, organizationId, workspaceId)
        } returns WorkspaceOwnerCandidatesQueryResult.WorkspaceNotFound(
            WorkspaceScopeFailure.ORGANIZATION_MISMATCH,
        )
        val service = WorkspaceOwnerCandidatesService(port)

        assertThatThrownBy {
            kotlinx.coroutines.runBlocking {
                service.list(
                    WorkspaceOwnerCandidatesUseCase.Command(
                        adminUserId,
                        organizationId,
                        workspaceId,
                    ),
                )
            }
        }.isInstanceOf(ScopedResourceNotFoundException::class.java)
            .hasMessage("workspace not found")
        coVerify(exactly = 1) {
            port.readWorkspaceOwnerCandidates(adminUserId, organizationId, workspaceId)
        }
    }

    @Test
    fun `owner candidate denial preserves forbidden mapping`() = runTest {
        val port = mockk<WorkspaceOwnerCandidatesQueryPort>()
        coEvery {
            port.readWorkspaceOwnerCandidates(memberUserId, organizationId, workspaceId)
        } returns WorkspaceOwnerCandidatesQueryResult.Denied
        val service = WorkspaceOwnerCandidatesService(port)

        assertThatThrownBy {
            kotlinx.coroutines.runBlocking {
                service.list(
                    WorkspaceOwnerCandidatesUseCase.Command(
                        memberUserId,
                        organizationId,
                        workspaceId,
                    ),
                )
            }
        }.isInstanceOf(AccessDeniedException::class.java)
        coVerify(exactly = 1) {
            port.readWorkspaceOwnerCandidates(memberUserId, organizationId, workspaceId)
        }
    }

    @Test
    fun `member candidate service invokes one operation port and maps inventory`() = runTest {
        val organizationMember = OrganizationMember(
            user = User(memberUserId, "member@example.com", "Member"),
            role = OrganizationRole.MEMBER,
            joinedAt = now,
            workspaces = emptyList(),
        )
        val port = mockk<WorkspaceMemberCandidatesQueryPort>()
        coEvery {
            port.readWorkspaceMemberCandidates(memberUserId, workspaceId)
        } returns WorkspaceMemberCandidatesQueryResult.Authorized(
            authorization = authorization(

                owner = true,
            ),
            users = listOf(organizationMember),
        )
        val service = WorkspaceMemberCandidatesService(port)

        val result = service.list(
            WorkspaceMemberCandidatesUseCase.Command(memberUserId, workspaceId),
        )

        assertThat(result.users).containsExactly(organizationMember)
        coVerify(exactly = 1) {
            port.readWorkspaceMemberCandidates(memberUserId, workspaceId)
        }
    }

    @Test
    fun `member candidate denial preserves forbidden mapping`() = runTest {
        val port = mockk<WorkspaceMemberCandidatesQueryPort>()
        coEvery {
            port.readWorkspaceMemberCandidates(adminUserId, workspaceId)
        } returns WorkspaceMemberCandidatesQueryResult.Denied
        val service = WorkspaceMemberCandidatesService(port)

        assertThatThrownBy {
            kotlinx.coroutines.runBlocking {
                service.list(
                    WorkspaceMemberCandidatesUseCase.Command(adminUserId, workspaceId),
                )
            }
        }.isInstanceOf(AccessDeniedException::class.java)
        coVerify(exactly = 1) {
            port.readWorkspaceMemberCandidates(adminUserId, workspaceId)
        }
    }

    private fun authorization(
        owner: Boolean = false,
    ): WorkspaceActorAuthorizationSnapshot =
        WorkspaceActorAuthorizationSnapshot(
            actorUserId = memberUserId,
            workspaceId = workspaceId,
            organizationId = organizationId,
            authorizationRevision = revision,
            organizationRole = OrganizationRole.MEMBER,
            role = if (owner) cc.monomer.identity.api.WorkspaceRole.OWNER else cc.monomer.identity.api.WorkspaceRole.EXPLORER,
        )

    private fun workspaceMember(user: User, workspaceOwner: Boolean): WorkspaceMember =
        WorkspaceMember(
            user = user,
            role = if (workspaceOwner) cc.monomer.identity.api.WorkspaceRole.OWNER else cc.monomer.identity.api.WorkspaceRole.EXPLORER,
        )

    private fun workspace(id: WorkspaceId): Workspace =
        Workspace(id, organizationId, "WS", now, false, cc.monomer.identity.domain.model.ReportingTimeZone.UTC, null)

    private fun workspaceQuery(accessible: List<AccessibleWorkspace>): WorkspaceQuery =
        object : WorkspaceQuery {
            override suspend fun countMembers(workspaceId: WorkspaceId): Int = 0
            override suspend fun find(id: WorkspaceId): Workspace? = null
            override suspend fun listAccessibleByUser(userId: UserId): List<AccessibleWorkspace> = accessible
        }
}
