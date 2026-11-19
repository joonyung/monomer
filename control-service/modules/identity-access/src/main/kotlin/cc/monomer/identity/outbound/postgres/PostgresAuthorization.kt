package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.port.AuthorizationAssignmentRepository
import cc.monomer.identity.domain.port.AuthorizationAssignmentTransition
import kotlin.uuid.toJavaUuid
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PostgresAuthorizationAssignmentRepository(
    @param:Qualifier("postgresJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) : AuthorizationAssignmentRepository {

    override fun actorIsOwnerAfterLock(actorUserId: UserId, workspaceId: WorkspaceId): Boolean {
        lockWorkspace(jdbc, workspaceId)
        val reader = PostgresWorkspaceAuthorizationReader(jdbc)
        val facts = reader.readActorFacts(actorUserId, workspaceId, reader.readWorkspaceContext(workspaceId))
        return cc.monomer.identity.domain.model.WorkspaceAuthorizationPolicy.authorizeOwner(facts) != null
    }

    override fun actorIsOrganizationAdminAfterLock(actorUserId: UserId, workspaceId: WorkspaceId): Boolean {
        val parameters = mapOf("workspaceId" to workspaceId.value.toJavaUuid(), "userId" to actorUserId.value.toJavaUuid())
        jdbc.query(
            """
            SELECT organization.id FROM identity_runtime.organizations organization
            JOIN identity_runtime.workspaces workspace ON workspace.organization_id = organization.id
            WHERE workspace.id = :workspaceId FOR UPDATE OF organization
            """.trimIndent(), parameters,
        ) { _, _ -> Unit }
        lockWorkspace(jdbc, workspaceId)
        return jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM identity_runtime.organization_members member
                JOIN identity_runtime.workspaces workspace ON workspace.organization_id = member.organization_id
                WHERE workspace.id = :workspaceId AND member.user_id = :userId
                  AND member.role = 'ADMIN'
            )
            """.trimIndent(), parameters, Boolean::class.java,
        ) == true
    }

    override fun isEligibleMemberRoleTarget(
        workspaceId: WorkspaceId, userId: UserId, requireDirectMembership: Boolean,
    ): Boolean = jdbc.queryForObject(
        """
        SELECT EXISTS (
            SELECT 1 FROM identity_runtime.organization_members member
            JOIN identity_runtime.users user_row ON user_row.id = member.user_id
            JOIN identity_runtime.workspaces workspace ON workspace.organization_id = member.organization_id
            WHERE workspace.id = :workspaceId AND member.user_id = :userId
              AND (NOT :requireDirectMembership OR EXISTS (
                  SELECT 1 FROM identity_runtime.workspace_members direct_member
                  WHERE direct_member.workspace_id = workspace.id AND direct_member.user_id = member.user_id
              ))
        )
        """.trimIndent(),
        mapOf("workspaceId" to workspaceId.value.toJavaUuid(), "userId" to userId.value.toJavaUuid(),
              "requireDirectMembership" to requireDirectMembership), Boolean::class.java,
    ) == true

    override fun setMemberRole(workspaceId: WorkspaceId, userId: UserId, role: cc.monomer.identity.api.WorkspaceRole): AuthorizationAssignmentTransition = authorizationAssignmentWrite {
        lockWorkspace(jdbc, workspaceId)
        transition(jdbc.update(
            "UPDATE identity_runtime.workspace_members SET role = :role WHERE workspace_id = :workspaceId AND user_id = :userId AND role IS DISTINCT FROM :role",
            mapOf("workspaceId" to workspaceId.value.toJavaUuid(), "userId" to userId.value.toJavaUuid(), "role" to role.name),
        ))
    }

    private fun transition(affectedRows: Int): AuthorizationAssignmentTransition =
        if (affectedRows == 1) {
            AuthorizationAssignmentTransition.Changed
        } else {
            AuthorizationAssignmentTransition.NoTransition
        }

}
