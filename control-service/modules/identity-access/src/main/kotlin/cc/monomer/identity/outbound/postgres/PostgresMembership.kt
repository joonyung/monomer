package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.WorkspaceMembership
import cc.monomer.identity.domain.port.LastWorkspaceOwnerException
import cc.monomer.identity.domain.port.WorkspaceMemberInsertion
import cc.monomer.identity.domain.port.WorkspaceMemberRemoval
import cc.monomer.identity.domain.port.WorkspaceMemberRepository
import java.sql.Timestamp
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import org.postgresql.util.PSQLException
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PostgresWorkspaceMemberRepository(
    @param:Qualifier("postgresJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) : WorkspaceMemberRepository {

    override fun insert(membership: WorkspaceMembership): WorkspaceMemberInsertion =
        if (jdbc.update(
                """
                INSERT INTO identity_runtime.workspace_members (workspace_id, user_id, invited_by, invited_at, role)
                VALUES (:workspaceId, :userId, :invitedBy, :invitedAt, :role)
                ON CONFLICT (workspace_id, user_id) DO NOTHING
                """.trimIndent(),
                mapOf(
                    "workspaceId" to membership.workspaceId.value.toJavaUuid(),
                    "userId" to membership.userId.value.toJavaUuid(),
                    "invitedBy" to membership.invitedBy?.value?.toJavaUuid(),
                    "invitedAt" to Timestamp.from(membership.invitedAt),
                    "role" to membership.role.name,
                ),
            ) > 0
        ) {
            WorkspaceMemberInsertion.Inserted
        } else {
            WorkspaceMemberInsertion.AlreadyMember(jdbc.query(
                "SELECT * FROM identity_runtime.workspace_members WHERE workspace_id = :workspaceId AND user_id = :userId",
                mapOf("workspaceId" to membership.workspaceId.value.toJavaUuid(), "userId" to membership.userId.value.toJavaUuid()),
            ) { row, _ -> WorkspaceMembership(
                membership.workspaceId, membership.userId,
                (row.getObject("invited_by") as java.util.UUID?)?.let { UserId(it.toKotlinUuid()) },
                row.getInstant("invited_at"), cc.monomer.identity.api.WorkspaceRole.valueOf(row.getString("role")),
            ) }.single())
        }

    override fun delete(workspaceId: WorkspaceId, userId: UserId): WorkspaceMemberRemoval =
        authorizationAssignmentWrite {
            lockWorkspace(jdbc, workspaceId)
            val membershipExists = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                      FROM identity_runtime.workspace_members
                     WHERE workspace_id = :workspaceId AND user_id = :userId
                )
                """.trimIndent(),
                mapOf(
                    "workspaceId" to workspaceId.value.toJavaUuid(),
                    "userId" to userId.value.toJavaUuid(),
                ),
                Boolean::class.java,
            )!!
            if (!membershipExists) {
                return@authorizationAssignmentWrite WorkspaceMemberRemoval.NotFound
            }
            if (jdbc.update(
                    """
                    DELETE FROM identity_runtime.workspace_members
                     WHERE workspace_id = :workspaceId AND user_id = :userId
                    """.trimIndent(),
                    mapOf(
                        "workspaceId" to workspaceId.value.toJavaUuid(),
                        "userId" to userId.value.toJavaUuid(),
                    ),
                ) > 0
            ) {
                WorkspaceMemberRemoval.Removed
            } else {
                WorkspaceMemberRemoval.NotFound
            }
        }
}

internal fun <T> authorizationAssignmentWrite(action: () -> T): T = try {
    action()
} catch (exception: DataIntegrityViolationException) {
    when (postgresConstraint(exception)) {
        "workspace_last_owner",
        -> throw LastWorkspaceOwnerException()
        else -> throw exception
    }
}

internal fun lockWorkspace(jdbc: NamedParameterJdbcTemplate, workspaceId: WorkspaceId) {
    jdbc.query(
        "SELECT id FROM identity_runtime.workspaces WHERE id = :workspaceId FOR UPDATE",
        mapOf("workspaceId" to workspaceId.value.toJavaUuid()),
    ) { _, _ -> Unit }
}

private fun postgresConstraint(exception: DataIntegrityViolationException): String? =
    generateSequence<Throwable>(exception) { it.cause }
        .filterIsInstance<PSQLException>()
        .firstOrNull()
        ?.serverErrorMessage
        ?.constraint
