package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.OrganizationMember
import cc.monomer.identity.domain.model.OrganizationRole
import cc.monomer.identity.domain.model.User
import cc.monomer.identity.domain.model.WorkspaceActorAuthorizationDecision
import cc.monomer.identity.domain.model.WorkspaceAuthorizationPolicy
import cc.monomer.identity.domain.model.WorkspaceMember
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.domain.port.WorkspaceAccessProjection
import cc.monomer.identity.domain.port.WorkspaceAccessQueryPort
import cc.monomer.identity.domain.port.WorkspaceAccessQueryResult
import cc.monomer.identity.domain.port.WorkspaceAuthorizationAssignmentsQueryPort
import cc.monomer.identity.domain.port.WorkspaceAuthorizationAssignmentsQueryResult
import cc.monomer.identity.domain.port.WorkspaceMemberCandidatesQueryPort
import cc.monomer.identity.domain.port.WorkspaceMemberCandidatesQueryResult
import cc.monomer.identity.domain.port.WorkspaceMembersQueryPort
import cc.monomer.identity.domain.port.WorkspaceMembersQueryResult
import cc.monomer.identity.domain.port.WorkspaceOwnerCandidatesQueryPort
import cc.monomer.identity.domain.port.WorkspaceOwnerCandidatesQueryResult
import cc.monomer.identity.domain.port.WorkspaceOwnerSummary
import cc.monomer.identity.domain.port.WorkspaceScopeFailure
import java.sql.ResultSet
import kotlin.uuid.toJavaUuid
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

@Component
class PostgresWorkspaceAuthorizationOperations(
    @param:Qualifier("postgresJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
    private val transactionRunner: TransactionRunner,
) : WorkspaceAccessQueryPort,
    WorkspaceAuthorizationAssignmentsQueryPort,
    WorkspaceMembersQueryPort,
    WorkspaceMemberCandidatesQueryPort,
    WorkspaceOwnerCandidatesQueryPort {
    private val authorizationReader = PostgresWorkspaceAuthorizationReader(jdbc)

    override suspend fun readWorkspaceAccess(
        actorUserId: UserId,
        workspaceId: WorkspaceId,
    ): WorkspaceAccessQueryResult = transactionRunner.readOnly {
        val workspaceContext = authorizationReader.readWorkspaceContext(workspaceId)
        val facts = authorizationReader.readActorFacts(
            actorUserId = actorUserId,
            workspaceId = workspaceId,
            workspaceContext = workspaceContext,

        )
        when (val decision = WorkspaceAuthorizationPolicy.authorize(facts)) {
            is WorkspaceActorAuthorizationDecision.Authorized ->
                WorkspaceAccessQueryResult.Authorized(
                    authorization = decision.snapshot,
                    projection = WorkspaceAccessProjection(
                        role = checkNotNull(decision.snapshot.role),
                        owners = readWorkspaceOwners(workspaceId),
                    ),
                )
            is WorkspaceActorAuthorizationDecision.Denied ->
                WorkspaceAccessQueryResult.Denied(decision.reason)
        }
    }

    override suspend fun readWorkspaceAuthorizationAssignments(
        actorUserId: UserId,
        workspaceId: WorkspaceId,
    ): WorkspaceAuthorizationAssignmentsQueryResult = transactionRunner.readOnly {
        val workspaceContext = authorizationReader.readWorkspaceContext(workspaceId)
            ?: return@readOnly WorkspaceAuthorizationAssignmentsQueryResult.Denied
        val facts = authorizationReader.readActorFacts(
            actorUserId = actorUserId,
            workspaceId = workspaceId,
            workspaceContext = workspaceContext,

        )
        val authorization = WorkspaceAuthorizationPolicy.authorizeOwner(facts)
            ?: return@readOnly WorkspaceAuthorizationAssignmentsQueryResult.Denied
        WorkspaceAuthorizationAssignmentsQueryResult.Authorized(
            authorization = authorization,
            members = readWorkspaceRoster(workspaceId),
        )
    }

    override suspend fun readWorkspaceMembers(
        actorUserId: UserId,
        workspaceId: WorkspaceId,
    ): WorkspaceMembersQueryResult = transactionRunner.readOnly {
        val workspaceContext = authorizationReader.readWorkspaceContext(workspaceId)
        val facts = authorizationReader.readActorFacts(
            actorUserId = actorUserId,
            workspaceId = workspaceId,
            workspaceContext = workspaceContext,

        )
        val authorization = WorkspaceAuthorizationPolicy.authorizeOwnerGovernance(facts)
            ?: return@readOnly WorkspaceMembersQueryResult.Denied
        WorkspaceMembersQueryResult.Authorized(
            authorization = authorization,
            members = readWorkspaceRoster(workspaceId),
        )
    }

    override suspend fun readWorkspaceMemberCandidates(
        actorUserId: UserId,
        workspaceId: WorkspaceId,
    ): WorkspaceMemberCandidatesQueryResult = transactionRunner.readOnly {
        val workspaceContext = authorizationReader.readWorkspaceContext(workspaceId)
            ?: return@readOnly WorkspaceMemberCandidatesQueryResult.WorkspaceNotFound
        val facts = authorizationReader.readActorFacts(
            actorUserId = actorUserId,
            workspaceId = workspaceId,
            workspaceContext = workspaceContext,

        )
        val authorization = WorkspaceAuthorizationPolicy.authorizeOwner(facts)
            ?: return@readOnly WorkspaceMemberCandidatesQueryResult.Denied
        WorkspaceMemberCandidatesQueryResult.Authorized(
            authorization = authorization,
            users = readOrganizationMembers(authorization.organizationId),
        )
    }

    override suspend fun readWorkspaceOwnerCandidates(
        actorUserId: UserId,
        organizationId: OrganizationId,
        workspaceId: WorkspaceId,
    ): WorkspaceOwnerCandidatesQueryResult = transactionRunner.readOnly {
        val workspaceContext = authorizationReader.readWorkspaceContext(workspaceId)
            ?: return@readOnly WorkspaceOwnerCandidatesQueryResult.WorkspaceNotFound(
                WorkspaceScopeFailure.WORKSPACE_NOT_FOUND,
            )
        if (workspaceContext.organizationId != organizationId) {
            return@readOnly WorkspaceOwnerCandidatesQueryResult.WorkspaceNotFound(
                WorkspaceScopeFailure.ORGANIZATION_MISMATCH,
            )
        }
        val facts = authorizationReader.readActorFacts(
            actorUserId = actorUserId,
            workspaceId = workspaceId,
            workspaceContext = workspaceContext,

        )
        val authorization = WorkspaceAuthorizationPolicy.authorizeOwnerGovernance(facts)
            ?: return@readOnly WorkspaceOwnerCandidatesQueryResult.Denied
        WorkspaceOwnerCandidatesQueryResult.Authorized(
            authorization = authorization,
            users = if (authorization.organizationRole == OrganizationRole.ADMIN) {
                val ownerIds = readWorkspaceRoster(workspaceId).filter { it.role == cc.monomer.identity.api.WorkspaceRole.OWNER }.map { it.user.userId }.toSet()
                readOrganizationMembers(organizationId).map { it.user }.filter { it.userId !in ownerIds }
            } else readOwnerCandidates(workspaceContext, workspaceId),
        )
    }

    private fun readWorkspaceRoster(workspaceId: WorkspaceId): List<WorkspaceMember> =
        jdbc.query(
            """
            SELECT workspace_member.user_id,
                   user_row.primary_email,
                   principal.display_name,
                   workspace_member.role
              FROM identity_runtime.workspace_members workspace_member
              JOIN identity_runtime.users user_row ON user_row.id = workspace_member.user_id
              JOIN identity_runtime.principals principal ON principal.id = user_row.id
             WHERE workspace_member.workspace_id = :workspaceId
             ORDER BY workspace_member.invited_at, workspace_member.user_id
            """.trimIndent(),
            workspaceParameters(workspaceId),
        ) { resultSet, _ ->
            WorkspaceMember(
                user = mapUser(resultSet, "user_id"),
                role = cc.monomer.identity.api.WorkspaceRole.valueOf(resultSet.getString("role")),
            )
        }

    private fun readWorkspaceOwners(workspaceId: WorkspaceId): List<WorkspaceOwnerSummary> =
        jdbc.query(
            """
            SELECT workspace_member.user_id,
                   principal.display_name
              FROM identity_runtime.workspace_members workspace_member
              JOIN identity_runtime.principals principal ON principal.id = workspace_member.user_id
             WHERE workspace_member.workspace_id = :workspaceId
               AND workspace_member.role = 'OWNER'
             ORDER BY principal.display_name, workspace_member.user_id
            """.trimIndent(),
            workspaceParameters(workspaceId),
        ) { resultSet, _ ->
            WorkspaceOwnerSummary(
                userId = UserId(resultSet.getUuid("user_id")),
                displayName = resultSet.getString("display_name"),
            )
        }

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
            mapOf("organizationId" to organizationId.value.toJavaUuid()),
        ) { resultSet, _ ->
            OrganizationMember(
                user = mapUser(resultSet, "user_id"),
                role = OrganizationRole.valueOf(resultSet.getString("role")),
                joinedAt = resultSet.getInstant("joined_at"),
                workspaces = emptyList(),
            )
        }

    private fun readOwnerCandidates(
        workspaceContext: WorkspaceAuthorizationContext,
        workspaceId: WorkspaceId,
    ): List<User> =
        jdbc.query(
            """
            SELECT user_row.id AS user_id,
                   user_row.primary_email,
                   principal.display_name
              FROM identity_runtime.workspace_members workspace_member
              JOIN identity_runtime.principals principal
                ON principal.id = workspace_member.user_id
               AND principal.kind = 'user'
              JOIN identity_runtime.users user_row ON user_row.id = principal.id
              JOIN identity_runtime.organization_members organization_member
                ON organization_member.organization_id = :organizationId
               AND organization_member.user_id = user_row.id
             WHERE workspace_member.workspace_id = :workspaceId
               AND workspace_member.role <> 'OWNER'
            """.trimIndent(),
            mapOf(
                "organizationId" to workspaceContext.organizationId.value.toJavaUuid(),
                "workspaceId" to workspaceId.value.toJavaUuid(),
            ),
        ) { resultSet, _ -> mapUser(resultSet, "user_id") }
            .distinctBy(User::userId)
            .sortedWith(
                compareBy<User> { it.displayName }
                    .thenBy { it.primaryEmail }
                    .thenBy { it.userId.toString() },
            )

    private fun mapUser(resultSet: ResultSet, userIdColumn: String): User =
        User(
            userId = UserId(resultSet.getUuid(userIdColumn)),
            primaryEmail = resultSet.getString("primary_email"),
            displayName = resultSet.getString("display_name"),
        )

    private fun workspaceParameters(workspaceId: WorkspaceId): Map<String, Any> =
        mapOf("workspaceId" to workspaceId.value.toJavaUuid())
}
