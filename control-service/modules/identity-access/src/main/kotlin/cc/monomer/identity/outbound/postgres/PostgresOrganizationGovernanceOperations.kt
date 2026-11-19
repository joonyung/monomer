package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.api.OrganizationPlan
import cc.monomer.identity.domain.model.OrganizationMember
import cc.monomer.identity.domain.model.OrganizationMemberWorkspace
import cc.monomer.identity.domain.model.OrganizationResourceCounts
import cc.monomer.identity.domain.model.OrganizationRole
import cc.monomer.identity.domain.model.OrganizationUsage
import cc.monomer.identity.domain.model.User
import cc.monomer.identity.domain.model.Workspace
import cc.monomer.identity.domain.port.OrganizationMembersQueryPort
import cc.monomer.identity.domain.port.OrganizationMembersQueryResult
import cc.monomer.identity.domain.port.ManagedWorkspacesQueryPort
import cc.monomer.identity.domain.port.ManagedWorkspacesQueryResult
import cc.monomer.identity.domain.port.OrganizationUsageQueryPort
import cc.monomer.identity.domain.port.OrganizationUsageQueryResult
import cc.monomer.identity.domain.port.TransactionRunner
import java.sql.ResultSet
import java.time.Instant
import kotlin.uuid.toJavaUuid
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

@Component
class PostgresOrganizationGovernanceOperations(
    @param:Qualifier("postgresJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
    private val transactionRunner: TransactionRunner,
) : OrganizationMembersQueryPort,
    ManagedWorkspacesQueryPort,
    OrganizationUsageQueryPort {

    override suspend fun readOrganizationMembers(
        actorUserId: UserId,
        organizationId: OrganizationId,
    ): OrganizationMembersQueryResult = transactionRunner.readOnly {
        if (!isOrganizationAdmin(actorUserId, organizationId)) {
            return@readOnly OrganizationMembersQueryResult.Denied
        }
        OrganizationMembersQueryResult.Authorized(readOrganizationMembers(organizationId))
    }

    override suspend fun readManagedWorkspaces(
        actorUserId: UserId,
        organizationId: OrganizationId,
    ): ManagedWorkspacesQueryResult = transactionRunner.readOnly {
        if (!isOrganizationAdmin(actorUserId, organizationId)) {
            return@readOnly ManagedWorkspacesQueryResult.Denied
        }
        ManagedWorkspacesQueryResult.Authorized(readManagedWorkspaces(organizationId))
    }

    override suspend fun readOrganizationUsage(
        actorUserId: UserId,
        organizationId: OrganizationId,
        now: Instant,
    ): OrganizationUsageQueryResult = transactionRunner.readOnly {
        if (!isOrganizationAdmin(actorUserId, organizationId)) {
            return@readOnly OrganizationUsageQueryResult.Denied
        }
        val plan = jdbc.query(
            "SELECT plan FROM identity_runtime.organizations WHERE id = :organizationId",
            organizationParameters(organizationId),
        ) { resultSet, _ -> OrganizationPlan.valueOf(resultSet.getString("plan").uppercase()) }.single()
        OrganizationUsageQueryResult.Authorized(
            OrganizationUsage.of(
                plan = plan,
                usage = OrganizationResourceCounts(
                    members = countOrganizationMembers(jdbc, organizationId),
                    workspaces = countOrganizationWorkspaces(jdbc, organizationId),
                    pendingInvitations = countPendingOrganizationInvitations(jdbc, organizationId, now, null),
                ),
            ),
        )
    }

    private fun isOrganizationAdmin(actorUserId: UserId, organizationId: OrganizationId): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                  FROM identity_runtime.organization_members
                 WHERE organization_id = :organizationId
                   AND user_id = :actorUserId
                   AND role = 'ADMIN'

            )
            """.trimIndent(),
            mapOf(
                "organizationId" to organizationId.value.toJavaUuid(),
                "actorUserId" to actorUserId.value.toJavaUuid(),
            ),
            Boolean::class.java,
        ) == true

    private fun readOrganizationMembers(organizationId: OrganizationId): List<OrganizationMember> =
        jdbc.query(
            """
            SELECT organization_member.user_id,
                   organization_member.role,
                   organization_member.joined_at,
                   user_row.primary_email,
                   principal.display_name
              FROM identity_runtime.organization_members organization_member
              JOIN identity_runtime.users user_row ON user_row.id = organization_member.user_id
              JOIN identity_runtime.principals principal ON principal.id = user_row.id
             WHERE organization_member.organization_id = :organizationId
             ORDER BY organization_member.joined_at, organization_member.user_id
            """.trimIndent(),
            organizationParameters(organizationId),
        ) { resultSet, _ ->
            OrganizationMember(
                user = mapUser(resultSet, "user_id"),
                role = OrganizationRole.valueOf(resultSet.getString("role")),
                joinedAt = resultSet.getInstant("joined_at"),
                workspaces = readMemberWorkspaces(organizationId, UserId(resultSet.getUuid("user_id"))),
            )
        }

    private fun readMemberWorkspaces(organizationId: OrganizationId, userId: UserId): List<OrganizationMemberWorkspace> = jdbc.query(
        """
        SELECT workspace.id, workspace.display_name, workspace.sample_dataset, workspace.sample_state, workspace.sample_initiator, member.role,
               (SELECT count(*) FROM identity_runtime.workspace_members members
                JOIN identity_runtime.organization_members organization_member ON organization_member.organization_id = workspace.organization_id
                     AND organization_member.user_id = members.user_id
                WHERE members.workspace_id = workspace.id) AS member_count,
               (SELECT count(*) FROM identity_runtime.workspace_members owners
                JOIN identity_runtime.organization_members organization_member ON organization_member.organization_id = workspace.organization_id
                     AND organization_member.user_id = owners.user_id
                JOIN identity_runtime.workspace_members direct_member ON direct_member.workspace_id = workspace.id
                     AND direct_member.user_id = owners.user_id
                WHERE owners.workspace_id = workspace.id AND owners.role = 'OWNER') AS owner_count
        FROM identity_runtime.workspaces workspace
        JOIN identity_runtime.workspace_members member ON member.workspace_id = workspace.id AND member.user_id = :userId
        WHERE workspace.organization_id = :organizationId ORDER BY workspace.created_at, workspace.id
        """.trimIndent(), organizationParameters(organizationId) + ("userId" to userId.value.toJavaUuid()),
    ) { rs, _ -> OrganizationMemberWorkspace(WorkspaceId(rs.getUuid("id")), rs.getString("display_name"),
        cc.monomer.identity.api.WorkspaceRole.valueOf(rs.getString("role")), rs.getInt("member_count"), rs.getInt("owner_count"), mapWorkspaceSample(rs)) }

    private fun readManagedWorkspaces(organizationId: OrganizationId): List<Workspace> =
        jdbc.query(
            """
            SELECT id,
                   organization_id,
                   display_name,
                   created_at, public_pages, reporting_time_zone, sample_dataset, sample_state, sample_initiator
              FROM identity_runtime.workspaces
             WHERE organization_id = :organizationId
             ORDER BY created_at, id
            """.trimIndent(),
            organizationParameters(organizationId),
        ) { resultSet, _ ->
            Workspace(
                id = WorkspaceId(resultSet.getUuid("id")),
                organizationId = OrganizationId(resultSet.getUuid("organization_id")),
                displayName = resultSet.getString("display_name"),
                createdAt = resultSet.getInstant("created_at"),
                publicPages = resultSet.getBoolean("public_pages"),
                reportingTimeZone = storedReportingTimeZone(resultSet.getString("reporting_time_zone")),
                sample = mapWorkspaceSample(resultSet),
            )
        }

    private fun mapUser(resultSet: ResultSet, userIdColumn: String): User =
        User(
            userId = UserId(resultSet.getUuid(userIdColumn)),
            primaryEmail = resultSet.getString("primary_email"),
            displayName = resultSet.getString("display_name"),
        )

    private fun organizationParameters(organizationId: OrganizationId): Map<String, Any> =
        mapOf("organizationId" to organizationId.value.toJavaUuid())
}
