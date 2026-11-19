package cc.monomer.identity.application.me

import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.Organization
import cc.monomer.identity.domain.model.OrganizationMembership
import cc.monomer.identity.api.OrganizationPlan
import cc.monomer.identity.domain.model.OrganizationRole
import cc.monomer.identity.domain.model.User
import cc.monomer.identity.domain.port.OrganizationQuery
import cc.monomer.identity.domain.port.UserQuery
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MeServiceTest {

    private val now = Instant.parse("2026-06-13T10:00:00Z")
    private val user = User(UserId.generate(), "alice@example.com", "Alice")
    private val firstOrganization = organization("alice")
    private val secondOrganization = organization("acme")
    @Test
    fun `me returns organization memberships without workspace inventory`() = runTest {
        val service = MeService(
            userQuery = userQuery(user),
            organizationQuery = organizationQuery(
                organizations = listOf(firstOrganization, secondOrganization),
                memberships = listOf(
                    membership(firstOrganization.id, OrganizationRole.ADMIN),
                    membership(secondOrganization.id, OrganizationRole.MEMBER),
                ),
            ),
        )

        val result = service.me(MeUseCase.Command(user.userId))

        assertThat(result.user).isEqualTo(user)
        assertThat(result.organizationMemberships.map { it.organization.id })
            .containsExactly(firstOrganization.id, secondOrganization.id)
        assertThat(result.organizationMemberships[0].role).isEqualTo(OrganizationRole.ADMIN)
        assertThat(result.organizationMemberships[1].role).isEqualTo(OrganizationRole.MEMBER)
    }

    private fun organization(displayName: String): Organization = Organization(
        id = OrganizationId.generate(),
        displayName = displayName.replaceFirstChar { it.uppercase() },
        billingEmail = null,
        plan = OrganizationPlan.FREE,
        createdAt = now,
    )

    private fun membership(
        organizationId: OrganizationId,
        role: OrganizationRole,
    ): OrganizationMembership = OrganizationMembership(
        organizationId = organizationId,
        userId = user.userId,
        role = role,
        joinedAt = now,
    )

    private fun userQuery(user: User) = object : UserQuery {
        override suspend fun find(id: UserId): User? = user.takeIf { it.userId == id }
    }

    private fun organizationQuery(
        organizations: List<Organization>,
        memberships: List<OrganizationMembership>,
    ) = object : OrganizationQuery {
        override suspend fun find(id: OrganizationId): Organization? = organizations.firstOrNull { it.id == id }
        override suspend fun listMembershipsByUser(userId: UserId): List<OrganizationMembership> =
            memberships.filter { it.userId == userId }
        override suspend fun findMembership(organizationId: OrganizationId, userId: UserId): OrganizationMembership? =
            memberships.firstOrNull { it.organizationId == organizationId && it.userId == userId }
    }

}
