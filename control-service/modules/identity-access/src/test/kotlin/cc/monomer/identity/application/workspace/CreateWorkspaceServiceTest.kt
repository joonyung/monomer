package cc.monomer.identity.application.workspace

import cc.monomer.identity.application.AccessDeniedException
import cc.monomer.identity.application.AdminAuthorization
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.application.InvalidDisplayNameException
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.OrganizationMembership
import cc.monomer.identity.domain.model.OrganizationRole
import cc.monomer.identity.domain.model.Workspace
import cc.monomer.identity.domain.port.OrganizationRepository
import cc.monomer.identity.domain.port.AuditLogRepository
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.domain.port.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CreateWorkspaceServiceTest {
    private val now = Instant.parse("2026-06-04T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val organizationId = OrganizationId.generate()
    private val actorUserId = UserId.generate()
    private val initialOwnerUserId = UserId.generate()

    @Test
    fun `admin creation sends the entire bootstrap to the nominated owner`() = runTest {
        val bootstrap = mockk<WorkspaceAuthorizationBootstrap>(relaxed = true)
        val inserted = mutableListOf<Workspace>()
        val service = service(OrganizationRole.ADMIN, bootstrap, inserted)

        val workspace = service.create(
            CreateWorkspaceUseCase.Command(actorUserId, initialOwnerUserId, organizationId, "Analytics"),
        )

        assertThat(inserted).containsExactly(workspace)
        assertThat(workspace.reportingTimeZone).isEqualTo(cc.monomer.identity.domain.model.ReportingTimeZone.UTC)
        verify(exactly = 1) {
            bootstrap.createInitialBundle(
                workspace.id,
                initialOwnerUserId,
                actorUserId,
                now,
            )
        }
        verify(exactly = 0) {
            bootstrap.createInitialBundle(workspace.id, actorUserId, any(), any())
        }
    }

    @Test
    fun `non admin cannot create a workspace`() = runTest {
        val bootstrap = mockk<WorkspaceAuthorizationBootstrap>(relaxed = true)
        val inserted = mutableListOf<Workspace>()
        val service = service(OrganizationRole.MEMBER, bootstrap, inserted)

        assertThatThrownBy {
            kotlinx.coroutines.runBlocking {
                service.create(
                    CreateWorkspaceUseCase.Command(actorUserId, initialOwnerUserId, organizationId, "Analytics"),
                )
            }
        }.isInstanceOf(AccessDeniedException::class.java)
        assertThat(inserted).isEmpty()
        verify(exactly = 0) { bootstrap.createInitialBundle(any(), any(), any(), any()) }
    }

    @Test
    fun `creation stores the trimmed name and rejects a blank name before any write`() = runTest {
        val bootstrap = mockk<WorkspaceAuthorizationBootstrap>(relaxed = true)
        val inserted = mutableListOf<Workspace>()
        val service = service(OrganizationRole.ADMIN, bootstrap, inserted)

        val workspace = service.create(
            CreateWorkspaceUseCase.Command(actorUserId, initialOwnerUserId, organizationId, "  Analytics  "),
        )
        assertThat(workspace.displayName).isEqualTo("Analytics")
        assertThat(inserted.single().displayName).isEqualTo("Analytics")

        assertThatThrownBy {
            kotlinx.coroutines.runBlocking {
                service.create(CreateWorkspaceUseCase.Command(actorUserId, initialOwnerUserId, organizationId, "   "))
            }
        }.isInstanceOf(InvalidDisplayNameException::class.java)
            .hasMessage("workspace displayName must not be blank")
        assertThat(inserted).hasSize(1)
    }

    private fun service(
        actorRole: OrganizationRole?,
        bootstrap: WorkspaceAuthorizationBootstrap,
        inserted: MutableList<Workspace>,
    ): CreateWorkspaceService {
        val organizationRepository = mockk<OrganizationRepository>(relaxed = true)
        every { organizationRepository.lockAndFindMembership(organizationId, actorUserId) } returns actorRole?.let {
            OrganizationMembership(
                organizationId,
                actorUserId,
                it,
                now,
            )
        }
        every { organizationRepository.lockAndFindMembership(organizationId, initialOwnerUserId) } returns OrganizationMembership(
            organizationId, initialOwnerUserId, OrganizationRole.MEMBER, now,
        )
        val workspaceRepository = object : WorkspaceRepository {
            override fun updatePublicPages(workspaceId: cc.monomer.identity.domain.id.WorkspaceId, publicPages: Boolean) = true
            override fun updateReportingTimeZone(
                workspaceId: cc.monomer.identity.domain.id.WorkspaceId,
                reportingTimeZone: cc.monomer.identity.domain.model.ReportingTimeZone,
            ) = true

            override fun insert(workspace: Workspace) {
                inserted += workspace
            }

            override fun updateDisplayName(
                workspaceId: cc.monomer.identity.domain.id.WorkspaceId,
                displayName: String,
            ) = Unit
        }
        return CreateWorkspaceService(
            transactionRunner = immediateTransactionRunner(),
            adminAuthorization = AdminAuthorization(organizationRepository),
            workspaceRepository = workspaceRepository,
            organizationRepository = organizationRepository,
            workspaceAuthorizationBootstrap = bootstrap,
            auditLogRepository = mockk<AuditLogRepository>(relaxed = true),
            auditEventFactory = AuditEventFactory(clock),
            clock = clock,
        )
    }

    private fun immediateTransactionRunner() = object : TransactionRunner {
        override suspend fun <T> run(action: () -> T): T = action()
        override suspend fun <T> readOnly(action: () -> T): T = action()
    }
}
