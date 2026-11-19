package cc.monomer.identity.application.organization

import cc.monomer.identity.application.InvalidDisplayNameException

import cc.monomer.identity.application.AccessDeniedException
import cc.monomer.identity.application.AdminAuthorization
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.Organization
import cc.monomer.identity.domain.model.OrganizationMembership
import cc.monomer.identity.api.OrganizationPlan
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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class UpdateOrganizationDisplayNameServiceTest {
    private val organizationId = OrganizationId.generate()
    private val actor = UserId.generate()
    private val now = Instant.parse("2026-09-05T00:00:00Z")
    private var inTransaction = false
    private val repository = mockk<OrganizationRepository>()
    private val audit = mockk<AuditLogRepository>(relaxed = true)
    private val transaction = object : TransactionRunner {
        override suspend fun <T> run(action: () -> T): T {
            inTransaction = true
            try { return action() } finally { inTransaction = false }
        }
        override suspend fun <T> readOnly(action: () -> T): T = error("unexpected read")
    }
    private val service = UpdateOrganizationDisplayNameService(AdminAuthorization(repository),
        repository, transaction, audit, AuditEventFactory(Clock.fixed(now, ZoneOffset.UTC)))

    @Test
    fun `admin updates trimmed name inside current transaction and returns actual metadata`(): Unit = runBlocking {
        val updated = Organization(organizationId, "New name", null, OrganizationPlan.PRO, now)
        every { repository.lockAndFindMembership(organizationId, actor) } answers {
            check(inTransaction)
            OrganizationMembership(organizationId, actor, OrganizationRole.ADMIN, now)
        }
        every { repository.updateDisplayName(organizationId, "New name") } answers { check(inTransaction); updated }
        assertThat(service.update(UpdateOrganizationDisplayNameUseCase.Command(actor, organizationId, " New name "))).isEqualTo(updated)
        verify(exactly = 1) { audit.insert(match { it.action == "organization.display-name.update" && it.organizationId == organizationId }) }
    }

    @Test
    fun `nonadmin and missing membership cannot rename organization`() {
        for (membership in listOf(OrganizationMembership(organizationId, actor, OrganizationRole.MEMBER, now), null)) {
            every { repository.lockAndFindMembership(organizationId, actor) } returns membership
            assertThatThrownBy { runBlocking { service.update(UpdateOrganizationDisplayNameUseCase.Command(actor, organizationId, "Name")) } }
                .isInstanceOf(AccessDeniedException::class.java)
        }
        verify(exactly = 0) { repository.updateDisplayName(any(), any()) }
    }

    @Test
    fun `blank name is rejected before mutation`() {
        assertThatThrownBy { runBlocking { service.update(UpdateOrganizationDisplayNameUseCase.Command(actor, organizationId, "  ")) } }
            .isInstanceOf(InvalidDisplayNameException::class.java)
        verify(exactly = 0) { repository.updateDisplayName(any(), any()) }
    }
}
