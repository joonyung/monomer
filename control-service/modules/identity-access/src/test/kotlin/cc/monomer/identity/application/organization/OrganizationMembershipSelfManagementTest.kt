package cc.monomer.identity.application.organization

import cc.monomer.identity.application.AccessDeniedException
import cc.monomer.identity.application.AdminAuthorization
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.OrganizationMembership
import cc.monomer.identity.domain.model.OrganizationRole
import cc.monomer.identity.domain.port.OrganizationRepository
import cc.monomer.identity.domain.port.AuditLogRepository
import cc.monomer.identity.domain.port.TransactionRunner
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OrganizationMembershipSelfManagementTest {
    private val organizationId = OrganizationId.generate()
    private val actorUserId = UserId.generate()
    private val now = Instant.parse("2026-09-04T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `organization admin cannot remove their own organization membership`() {
        val fixture = fixture()
        val service = RemoveOrganizationMemberService(
            fixture.transactionRunner,
            fixture.adminAuthorization,
            fixture.organizationRepository,
            fixture.auditLogRepository,
            AuditEventFactory(clock),
        )

        assertSelfManagementDenied {
            service.remove(
                RemoveOrganizationMemberUseCase.Command(actorUserId.asPrincipal(), organizationId, actorUserId),
            )
        }

        verify(exactly = 0) { fixture.organizationRepository.deleteMember(any(), any()) }
        verify(exactly = 0) { fixture.auditLogRepository.insert(any()) }
    }

    @Test
    fun `organization admin cannot change their own organization role`() {
        val fixture = fixture()
        val service = UpdateOrganizationMemberRoleService(
            fixture.transactionRunner,
            fixture.adminAuthorization,
            fixture.organizationRepository,
            fixture.auditLogRepository,
            AuditEventFactory(clock),
        )

        assertSelfManagementDenied {
            service.update(
                UpdateOrganizationMemberRoleUseCase.Command(
                    actorUserId.asPrincipal(),
                    organizationId,
                    actorUserId,
                    OrganizationRole.MEMBER,
                ),
            )
        }

        verify(exactly = 0) { fixture.organizationRepository.updateMemberRole(any(), any(), any()) }
        verify(exactly = 0) { fixture.auditLogRepository.insert(any()) }
    }

    private fun fixture(): Fixture {
        val organizationRepository = mockk<OrganizationRepository>()
        every {
            organizationRepository.lockAndFindMembership(organizationId, actorUserId)
        } returns OrganizationMembership(
            organizationId = organizationId,
            userId = actorUserId,
            role = OrganizationRole.ADMIN,
            joinedAt = now,
        )
        val auditLogRepository = mockk<AuditLogRepository>()
        return Fixture(
            transactionRunner = object : TransactionRunner {
                override suspend fun <T> run(action: () -> T): T = action()
                override suspend fun <T> readOnly(action: () -> T): T = action()
            },
            adminAuthorization = AdminAuthorization(organizationRepository),
            organizationRepository = organizationRepository,
            auditLogRepository = auditLogRepository,
        )
    }

    private fun assertSelfManagementDenied(action: suspend () -> Unit) {
        assertThatThrownBy { runBlocking { action() } }
            .isInstanceOf(AccessDeniedException::class.java)
            .hasMessage("organization administrators cannot manage their own organization membership")
    }

    private data class Fixture(
        val transactionRunner: TransactionRunner,
        val adminAuthorization: AdminAuthorization,
        val organizationRepository: OrganizationRepository,
        val auditLogRepository: AuditLogRepository,
    )
}
