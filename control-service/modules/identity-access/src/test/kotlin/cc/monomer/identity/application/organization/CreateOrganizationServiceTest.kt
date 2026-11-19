package cc.monomer.identity.application.organization

import cc.monomer.identity.application.InvalidDisplayNameException

import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.application.workspace.WorkspaceAuthorizationBootstrap
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.Organization
import cc.monomer.identity.domain.model.OrganizationMembership
import cc.monomer.identity.domain.model.OrganizationRole
import cc.monomer.identity.domain.model.Workspace
import cc.monomer.identity.domain.port.OrganizationRepository
import cc.monomer.identity.domain.port.AuditLogRepository
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.domain.port.WorkspaceRepository
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CreateOrganizationServiceTest {
    private val now = Instant.parse("2026-06-13T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val actorUserId = UserId.generate()
    private val initialOwnerUserId = UserId.generate()

    @Test
    fun `organization bootstrap belongs only to the nominated initial owner`() = runTest {
        val bootstrap = mockk<WorkspaceAuthorizationBootstrap>(relaxed = true)
        val organizations = mutableListOf<Organization>()
        val memberships = mutableListOf<OrganizationMembership>()
        val workspaces = mutableListOf<Workspace>()
        val service = service(bootstrap, organizations, memberships, workspaces)

        val response = service.create(
            CreateOrganizationUseCase.Command(
                actorUserId,
                initialOwnerUserId,
                "Acme Analytics",
            ),
        )

        assertThat(response.organization.displayName).isEqualTo("Acme Analytics")
        assertThat(response.membership.userId).isEqualTo(actorUserId)
        assertThat(response.membership.role).isEqualTo(OrganizationRole.ADMIN)
        assertThat(memberships.map { it.userId }).containsExactly(actorUserId, initialOwnerUserId)
        assertThat(memberships.last().role).isEqualTo(OrganizationRole.MEMBER)
        assertThat(organizations).containsExactly(response.organization)
        assertThat(workspaces).containsExactly(response.defaultWorkspace)
        verify(exactly = 1) {
            bootstrap.createInitialBundle(
                response.defaultWorkspace.id,
                initialOwnerUserId,
                actorUserId,
                now,
            )
        }
    }

    @Test
    fun `creator nominated as owner receives only one organization membership`() = runTest {
        val memberships = mutableListOf<OrganizationMembership>()
        val bootstrap = mockk<WorkspaceAuthorizationBootstrap>(relaxed = true)
        val response = service(bootstrap, memberships = memberships).create(
            CreateOrganizationUseCase.Command(actorUserId, actorUserId, "Solo"),
        )

        assertThat(memberships).containsExactly(response.membership)
    }

    @Test
    fun `Korean display names can repeat while generated organization identities remain distinct`() = runTest {
        val bootstrap = mockk<WorkspaceAuthorizationBootstrap>(relaxed = true)
        val organizations = mutableListOf<Organization>()
        val memberships = mutableListOf<OrganizationMembership>()
        val workspaces = mutableListOf<Workspace>()
        val service = service(bootstrap, organizations, memberships, workspaces)
        val command = CreateOrganizationUseCase.Command(actorUserId, actorUserId, "  모노머 분석팀  ")

        val first = service.create(command)
        val second = service.create(command)

        assertThat(first.organization.displayName).isEqualTo("모노머 분석팀")
        assertThat(second.organization.displayName).isEqualTo(first.organization.displayName)
        assertThat(second.organization.id).isNotEqualTo(first.organization.id)
        assertThat(second.defaultWorkspace.id).isNotEqualTo(first.defaultWorkspace.id)
        assertThat(organizations).containsExactly(first.organization, second.organization)
        assertThat(memberships.map { it.organizationId }).containsExactly(first.organization.id, second.organization.id)
        assertThat(workspaces.map { it.organizationId }).containsExactly(first.organization.id, second.organization.id)
        verify(exactly = 2) { bootstrap.createInitialBundle(any(), actorUserId, actorUserId, now) }
    }

    @Test
    fun `blank display name is rejected`() = runTest {
        val service = service(mockk(relaxed = true))

        assertThatThrownBy {
            kotlinx.coroutines.runBlocking {
                service.create(
                    CreateOrganizationUseCase.Command(actorUserId, actorUserId, " "),
                )
            }
        }.isInstanceOf(InvalidDisplayNameException::class.java)
    }

    private fun service(
        bootstrap: WorkspaceAuthorizationBootstrap,
        organizations: MutableList<Organization> = mutableListOf(),
        memberships: MutableList<OrganizationMembership> = mutableListOf(),
        workspaces: MutableList<Workspace> = mutableListOf(),
    ): CreateOrganizationService {
        val organizationRepository = object : OrganizationRepository {
            override fun requireFreeOrganizationAdminCapacity(userId: UserId) = Unit
            override fun updateDisplayName(organizationId: OrganizationId, displayName: String): Organization? = null
            override fun insert(organization: Organization) {
                organizations += organization
            }

            override fun insertMember(membership: OrganizationMembership) {
                memberships += membership
            }

            override fun lockAndFindMembership(organizationId: OrganizationId, userId: UserId): OrganizationMembership? = null
            override fun updateMemberRole(organizationId: OrganizationId, userId: UserId, role: OrganizationRole) =
                cc.monomer.identity.domain.port.OrganizationMemberRoleTransition.NotFound

            override fun deleteMember(organizationId: OrganizationId, userId: UserId) =
                cc.monomer.identity.domain.port.OrganizationMemberRemoval.NotFound
        }
        val workspaceRepository = object : WorkspaceRepository {
            override fun updatePublicPages(workspaceId: WorkspaceId, publicPages: Boolean) = true
            override fun updateReportingTimeZone(workspaceId: WorkspaceId, reportingTimeZone: cc.monomer.identity.domain.model.ReportingTimeZone) = true
            override fun insert(workspace: Workspace) { workspaces += workspace }
            override fun updateDisplayName(workspaceId: WorkspaceId, displayName: String) = Unit
        }
        return CreateOrganizationService(
            immediateTransactionRunner(),
            organizationRepository,
            workspaceRepository,
            bootstrap,
            mockk<AuditLogRepository>(relaxed = true),
            AuditEventFactory(clock),
            clock,
        )
    }

    private fun immediateTransactionRunner() = object : TransactionRunner {
        override suspend fun <T> run(action: () -> T): T = action()
        override suspend fun <T> readOnly(action: () -> T): T = action()
    }
}
