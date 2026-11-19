package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.OrganizationRole
import cc.monomer.identity.domain.model.AuthorizationRevision
import cc.monomer.identity.domain.model.WorkspaceActorAuthorizationFacts
import kotlin.uuid.toJavaUuid
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

internal data class WorkspaceAuthorizationContext(
    val organizationId: OrganizationId,
    val authorizationRevision: AuthorizationRevision,
)

internal class PostgresWorkspaceAuthorizationReader(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun readWorkspaceContext(workspaceId: WorkspaceId): WorkspaceAuthorizationContext? = jdbc.query(
        """
        SELECT organization_id, authorization_revision
          FROM identity_runtime.workspaces
         WHERE id = :workspaceId
        """.trimIndent(),
        workspaceParameters(workspaceId),
    ) { resultSet, _ ->
        WorkspaceAuthorizationContext(
            organizationId = OrganizationId(resultSet.getUuid("organization_id")),
            authorizationRevision = AuthorizationRevision(resultSet.getLong("authorization_revision")),

        )
    }.firstOrNull()

    fun readActorFacts(
        actorUserId: UserId,
        workspaceId: WorkspaceId,
        workspaceContext: WorkspaceAuthorizationContext?,
    ): WorkspaceActorAuthorizationFacts {
        if (workspaceContext == null) {
            return WorkspaceActorAuthorizationFacts(
                actorUserId = actorUserId,
                workspaceId = workspaceId,
                organizationId = null,
                authorizationRevision = null,
                organizationRole = null,
                role = null,
            )
        }
        val organizationMembership = readActorOrganizationMembership(workspaceContext.organizationId, actorUserId)
        val role = readWorkspaceRole(actorUserId, workspaceId)
        return WorkspaceActorAuthorizationFacts(
            actorUserId = actorUserId,
            workspaceId = workspaceId,
            organizationId = workspaceContext.organizationId,
            authorizationRevision = workspaceContext.authorizationRevision,
            organizationRole = organizationMembership?.role,
            role = role,
        )
    }

    private fun readActorOrganizationMembership(
        organizationId: OrganizationId,
        actorUserId: UserId,
    ): ActorOrganizationMembership? = jdbc.query(
        """
        SELECT role
          FROM identity_runtime.organization_members
         WHERE organization_id = :organizationId AND user_id = :actorUserId
        """.trimIndent(),
        mapOf(
            "organizationId" to organizationId.value.toJavaUuid(),
            "actorUserId" to actorUserId.value.toJavaUuid(),
        ),
    ) { resultSet, _ ->
        ActorOrganizationMembership(
            role = OrganizationRole.valueOf(resultSet.getString("role")),
        )
    }.firstOrNull()

    fun readWorkspaceRole(actorUserId: UserId, workspaceId: WorkspaceId): cc.monomer.identity.api.WorkspaceRole? = jdbc.query(
        "SELECT role FROM identity_runtime.workspace_members WHERE workspace_id = :workspaceId AND user_id = :userId",
        mapOf("workspaceId" to workspaceId.value.toJavaUuid(), "userId" to actorUserId.value.toJavaUuid()),
    ) { rs, _ -> cc.monomer.identity.api.WorkspaceRole.valueOf(rs.getString("role")) }.firstOrNull()

    private fun workspaceParameters(workspaceId: WorkspaceId): Map<String, Any> =
        mapOf("workspaceId" to workspaceId.value.toJavaUuid())

    private data class ActorOrganizationMembership(
        val role: OrganizationRole,
    )

}
