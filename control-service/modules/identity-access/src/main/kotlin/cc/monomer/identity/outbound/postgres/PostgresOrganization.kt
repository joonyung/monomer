package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.FreeOrganizationLimits
import cc.monomer.identity.domain.model.Organization
import cc.monomer.identity.domain.model.OrganizationMembership
import cc.monomer.identity.api.OrganizationPlan
import cc.monomer.identity.domain.model.OrganizationRole
import cc.monomer.identity.domain.port.FreeOrganizationAdminLimitException
import cc.monomer.identity.domain.port.FreeOrganizationMemberLimitException
import cc.monomer.identity.domain.port.OrganizationQuery
import cc.monomer.identity.domain.port.OrganizationRepository
import cc.monomer.identity.domain.port.OrganizationMemberRemoval
import cc.monomer.identity.domain.port.OrganizationMemberRoleTransition
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.domain.port.LastOrganizationAdminException
import cc.monomer.identity.domain.port.LastWorkspaceOwnerException
import java.sql.ResultSet
import java.sql.Timestamp
import kotlin.uuid.toJavaUuid
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.postgresql.util.PSQLException

@Repository
class PostgresOrganizationRepository(
    @param:Qualifier("postgresJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) : OrganizationRepository {

    override fun requireFreeOrganizationAdminCapacity(userId: UserId) {
        val parameters = mapOf("userId" to userId.value.toJavaUuid())
        jdbc.query("SELECT id FROM identity_runtime.users WHERE id = :userId FOR NO KEY UPDATE", parameters) { _, _ -> Unit }
        val adminCount = jdbc.queryForObject(
            """SELECT count(*) FROM identity_runtime.organization_members member
                JOIN identity_runtime.organizations organization ON organization.id = member.organization_id
                WHERE member.user_id = :userId AND member.role = 'ADMIN' AND organization.plan = 'free'""",
            parameters, Int::class.java,
        )!!
        if (adminCount >= FreeOrganizationLimits.ADMINISTERED_ORGANIZATIONS) throw FreeOrganizationAdminLimitException()
    }

    private fun requireMemberCapacity(membership: OrganizationMembership, addingMember: Boolean) {
        val organization = jdbc.query("SELECT * FROM identity_runtime.organizations WHERE id = :organizationId",
            mapOf("organizationId" to membership.organizationId.value.toJavaUuid())) { rs, _ -> mapOrganization(rs) }.single()
        if (organization.plan != OrganizationPlan.FREE) return
        if (addingMember) {
            if (countOrganizationMembers(jdbc, membership.organizationId) >= FreeOrganizationLimits.MEMBERS) {
                throw FreeOrganizationMemberLimitException()
            }
        }
        if (membership.role == OrganizationRole.ADMIN) requireFreeOrganizationAdminCapacity(membership.userId)
    }

    override fun updateDisplayName(organizationId: OrganizationId, displayName: String): Organization? = jdbc.query(
        "UPDATE identity_runtime.organizations SET display_name = :displayName WHERE id = :organizationId RETURNING *",
        mapOf("organizationId" to organizationId.value.toJavaUuid(), "displayName" to displayName),
    ) { rs, _ -> mapOrganization(rs) }.firstOrNull()

    override fun insert(organization: Organization) {
        jdbc.update(
            """
            INSERT INTO identity_runtime.organizations (id, display_name, billing_email, plan, created_at)
            VALUES (:id, :displayName, :billingEmail, :plan, :createdAt)
            """.trimIndent(),
            mapOf(
                "id" to organization.id.value.toJavaUuid(),
                "displayName" to organization.displayName,
                "billingEmail" to organization.billingEmail,
                "plan" to organization.plan.name.lowercase(),
                "createdAt" to Timestamp.from(organization.createdAt),
            ),
        )
    }

    override fun insertMember(membership: OrganizationMembership) {
        membershipWrite {
            lockOrganization(membership.organizationId)
            requireMemberCapacity(membership, addingMember = true)
            jdbc.update(
                """
                INSERT INTO identity_runtime.organization_members (organization_id, user_id, role, joined_at)
                VALUES (:organizationId, :userId, :role, :joinedAt)
                """.trimIndent(),
                mapOf(
                    "organizationId" to membership.organizationId.value.toJavaUuid(),
                    "userId" to membership.userId.value.toJavaUuid(),
                    "role" to membership.role.name,
                    "joinedAt" to Timestamp.from(membership.joinedAt),
                ),
            )
        }
    }

    override fun lockAndFindMembership(organizationId: OrganizationId, userId: UserId): OrganizationMembership? {
        lockOrganization(organizationId)
        return findMembershipForUpdate(organizationId, userId)
    }

    override fun updateMemberRole(
        organizationId: OrganizationId,
        userId: UserId,
        role: OrganizationRole,
    ): OrganizationMemberRoleTransition = membershipWrite {
        lockOrganization(organizationId)
        val membership = findMembershipForUpdate(organizationId, userId)
            ?: return@membershipWrite OrganizationMemberRoleTransition.NotFound
        if (membership.role == role) return@membershipWrite OrganizationMemberRoleTransition.NoTransition
        requireMemberCapacity(membership.copy(role = role), addingMember = false)
        val updatedRows = jdbc.update(
            "UPDATE identity_runtime.organization_members SET role = :role WHERE organization_id = :organizationId AND user_id = :userId",
            membershipParameters(organizationId, userId) + ("role" to role.name),
        )
        check(updatedRows == 1) { "organization member role transition updated $updatedRows rows" }
        OrganizationMemberRoleTransition.Updated(membership.copy(role = role))
    }

    override fun deleteMember(organizationId: OrganizationId, userId: UserId): OrganizationMemberRemoval = membershipWrite {
        lockOrganization(organizationId)
        lockWorkspaces(organizationId)
        if (findMembershipForUpdate(organizationId, userId) == null) {
            return@membershipWrite OrganizationMemberRemoval.NotFound
        }
        clearOrganizationAccess(organizationId, userId)
        val deletedRows = jdbc.update(
            "DELETE FROM identity_runtime.organization_members WHERE organization_id = :organizationId AND user_id = :userId",
            membershipParameters(organizationId, userId),
        )
        check(deletedRows == 1) { "organization member removal deleted $deletedRows rows" }
        OrganizationMemberRemoval.Removed
    }

    private fun clearOrganizationAccess(organizationId: OrganizationId, userId: UserId) {
        val parameters = membershipParameters(organizationId, userId)
        jdbc.update(
            """DELETE FROM identity_runtime.workspace_members member USING identity_runtime.workspaces workspace
               WHERE member.workspace_id = workspace.id AND workspace.organization_id = :organizationId AND member.user_id = :userId""", parameters,
        )
    }

    private fun lockOrganization(organizationId: OrganizationId) {
        jdbc.query(
            "SELECT id FROM identity_runtime.organizations WHERE id = :organizationId FOR UPDATE",
            mapOf("organizationId" to organizationId.value.toJavaUuid()),
        ) { _, _ -> Unit }
    }

    private fun lockWorkspaces(organizationId: OrganizationId): List<java.util.UUID> {
        return jdbc.query(
            """
            SELECT workspace.id
              FROM identity_runtime.workspaces workspace
             WHERE workspace.organization_id = :organizationId
             ORDER BY workspace.id
             FOR UPDATE OF workspace
            """.trimIndent(),
            mapOf("organizationId" to organizationId.value.toJavaUuid()),
        ) { resultSet, _ -> resultSet.getObject("id", java.util.UUID::class.java) }
    }

    private fun findMembershipForUpdate(organizationId: OrganizationId, userId: UserId): OrganizationMembership? =
        jdbc.query(
            """
            SELECT *
              FROM identity_runtime.organization_members
             WHERE organization_id = :organizationId
               AND user_id = :userId
             FOR UPDATE
            """.trimIndent(),
            membershipParameters(organizationId, userId),
        ) { resultSet, _ -> mapOrganizationMembership(resultSet) }.firstOrNull()

    private fun membershipParameters(organizationId: OrganizationId, userId: UserId): Map<String, Any> = mapOf(
        "organizationId" to organizationId.value.toJavaUuid(),
        "userId" to userId.value.toJavaUuid(),
    )

    private fun <T> membershipWrite(action: () -> T): T = try {
        action()
    } catch (exception: DataIntegrityViolationException) {
        when (postgresConstraint(exception)) {
            LAST_ADMIN_CONSTRAINT -> throw LastOrganizationAdminException()
            LAST_WORKSPACE_OWNER_CONSTRAINT -> throw LastWorkspaceOwnerException()
            else -> throw exception
        }
    }

    private fun postgresConstraint(exception: DataIntegrityViolationException): String? =
        generateSequence<Throwable>(exception) { it.cause }
            .filterIsInstance<PSQLException>()
            .firstOrNull()
            ?.serverErrorMessage
            ?.constraint

    private companion object {
        const val LAST_ADMIN_CONSTRAINT = "organization_members_last_admin"
        const val LAST_WORKSPACE_OWNER_CONSTRAINT = "workspace_last_owner"
    }
}

@Component
class PostgresOrganizationQuery(
    @param:Qualifier("postgresJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
    private val transactionRunner: TransactionRunner,
) : OrganizationQuery {

    override suspend fun find(id: OrganizationId): Organization? = transactionRunner.readOnly {
        jdbc.query(
            "SELECT * FROM identity_runtime.organizations WHERE id = :id",
            mapOf("id" to id.value.toJavaUuid()),
        ) { rs, _ -> mapOrganization(rs) }.firstOrNull()
    }

    override suspend fun listMembershipsByUser(userId: UserId): List<OrganizationMembership> = transactionRunner.readOnly {
        jdbc.query(
            "SELECT * FROM identity_runtime.organization_members WHERE user_id = :userId",
            mapOf("userId" to userId.value.toJavaUuid()),
        ) { rs, _ -> mapOrganizationMembership(rs) }
    }

    override suspend fun findMembership(organizationId: OrganizationId, userId: UserId): OrganizationMembership? =
        transactionRunner.readOnly {
            jdbc.query(
                "SELECT * FROM identity_runtime.organization_members WHERE organization_id = :organizationId AND user_id = :userId",
                mapOf(
                    "organizationId" to organizationId.value.toJavaUuid(),
                    "userId" to userId.value.toJavaUuid(),
                ),
            ) { rs, _ -> mapOrganizationMembership(rs) }.firstOrNull()
        }

}

private fun mapOrganization(rs: ResultSet): Organization = Organization(
    id = OrganizationId(rs.getUuid("id")),
    displayName = rs.getString("display_name"),
    billingEmail = rs.getString("billing_email"),
    plan = OrganizationPlan.valueOf(rs.getString("plan").uppercase()),
    createdAt = rs.getInstant("created_at"),
)

private fun mapOrganizationMembership(resultSet: ResultSet): OrganizationMembership = OrganizationMembership(
    organizationId = OrganizationId(resultSet.getUuid("organization_id")),
    userId = UserId(resultSet.getUuid("user_id")),
    role = OrganizationRole.valueOf(resultSet.getString("role")),
    joinedAt = resultSet.getInstant("joined_at"),
)
