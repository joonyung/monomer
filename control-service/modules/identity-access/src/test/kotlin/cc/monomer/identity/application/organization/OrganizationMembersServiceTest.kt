package cc.monomer.identity.application.organization

import cc.monomer.identity.application.AccessDeniedException
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.OrganizationMember
import cc.monomer.identity.domain.model.OrganizationRole
import cc.monomer.identity.domain.model.User
import cc.monomer.identity.domain.port.OrganizationMembersQueryPort
import cc.monomer.identity.domain.port.OrganizationMembersQueryResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OrganizationMembersServiceTest {

    private val organizationId = OrganizationId.generate()
    private val adminUserId = UserId.generate()

    private val members = listOf(
        OrganizationMember(
            user = User(adminUserId, "admin@example.com", "Admin"),
            role = OrganizationRole.ADMIN,
            joinedAt = java.time.Instant.parse("2026-10-01T00:00:00Z"),
            workspaces = emptyList(),
        ),
    )

    @Test
    fun `service invokes one operation port and returns organization members`() = runTest {
        val port = mockk<OrganizationMembersQueryPort>()
        coEvery {
            port.readOrganizationMembers(adminUserId, organizationId)
        } returns OrganizationMembersQueryResult.Authorized(members)
        val service = OrganizationMembersService(port)

        val result = service.list(OrganizationMembersUseCase.Command(adminUserId, organizationId))

        assertThat(result).isEqualTo(members)
        coVerify(exactly = 1) { port.readOrganizationMembers(adminUserId, organizationId) }
    }

    @Test
    fun `denied result preserves forbidden mapping`() = runTest {
        val port = mockk<OrganizationMembersQueryPort>()
        coEvery {
            port.readOrganizationMembers(adminUserId, organizationId)
        } returns OrganizationMembersQueryResult.Denied
        val service = OrganizationMembersService(port)

        assertThatThrownBy {
            runBlocking { service.list(OrganizationMembersUseCase.Command(adminUserId, organizationId)) }
        }.isInstanceOf(AccessDeniedException::class.java)
        coVerify(exactly = 1) { port.readOrganizationMembers(adminUserId, organizationId) }
    }
}
