package cc.monomer.identity.application.organization

import cc.monomer.identity.api.OrganizationPlan
import cc.monomer.identity.application.AccessDeniedException
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.FreeOrganizationLimits
import cc.monomer.identity.domain.model.OrganizationResourceCounts
import cc.monomer.identity.domain.model.OrganizationUsage
import cc.monomer.identity.domain.port.OrganizationUsageQueryPort
import cc.monomer.identity.domain.port.OrganizationUsageQueryResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OrganizationUsageServiceTest {
    private val organizationId = OrganizationId.generate()
    private val actorUserId = UserId.generate()
    private val now = Instant.parse("2026-10-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val counts = OrganizationResourceCounts(members = 3, workspaces = 2, pendingInvitations = 1)

    @Test
    fun `free plan usage carries the enforced free limits`() {
        val usage = OrganizationUsage.of(OrganizationPlan.FREE, counts)

        assertThat(usage.limits).isEqualTo(OrganizationResourceCounts(
            members = FreeOrganizationLimits.MEMBERS,
            workspaces = FreeOrganizationLimits.WORKSPACES,
            pendingInvitations = FreeOrganizationLimits.PENDING_INVITATIONS,
        ))
        assertThat(usage.usage).isEqualTo(counts)
    }

    @Test
    fun `paid plans expose usage without free limits`() {
        for (plan in listOf(OrganizationPlan.PRO, OrganizationPlan.ENTERPRISE)) {
            assertThat(OrganizationUsage.of(plan, counts).limits).isNull()
        }
    }

    @Test
    fun `service reads usage at the current instant`() = runTest {
        val port = mockk<OrganizationUsageQueryPort>()
        val usage = OrganizationUsage.of(OrganizationPlan.FREE, counts)
        coEvery { port.readOrganizationUsage(actorUserId, organizationId, now) } returns OrganizationUsageQueryResult.Authorized(usage)

        assertThat(OrganizationUsageService(port, clock).read(OrganizationUsageUseCase.Command(actorUserId, organizationId))).isEqualTo(usage)
        coVerify(exactly = 1) { port.readOrganizationUsage(actorUserId, organizationId, now) }
    }

    @Test
    fun `non admin usage read is forbidden`() {
        val port = mockk<OrganizationUsageQueryPort>()
        coEvery { port.readOrganizationUsage(actorUserId, organizationId, now) } returns OrganizationUsageQueryResult.Denied

        assertThatThrownBy {
            runBlocking { OrganizationUsageService(port, clock).read(OrganizationUsageUseCase.Command(actorUserId, organizationId)) }
        }.isInstanceOf(AccessDeniedException::class.java)
    }
}
