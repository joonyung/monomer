package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.*
import cc.monomer.identity.domain.port.FreePendingInvitationLimitException
import cc.monomer.identity.domain.port.OrganizationInvitationRepository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PostgresOrganizationInvitationRepository(
    @param:Qualifier("postgresJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) : OrganizationInvitationRepository {
    override fun lockOrganization(organizationId: OrganizationId): Boolean = jdbc.query(
        "SELECT id FROM identity_runtime.organizations WHERE id = :organizationId FOR UPDATE", organizationParameters(organizationId),
    ) { _, _ -> true }.firstOrNull() == true

    override fun exists(organizationId: OrganizationId): Boolean = jdbc.query(
        "SELECT id FROM identity_runtime.organizations WHERE id = :organizationId", organizationParameters(organizationId),
    ) { _, _ -> true }.firstOrNull() == true

    override fun list(organizationId: OrganizationId): List<InvitationPreview> = withWorkspaceAccess(jdbc.query(
        "$SELECT WHERE invitation.organization_id = :organizationId ORDER BY invitation.created_at DESC, invitation.id", organizationParameters(organizationId),
    ) { rs, _ -> preview(rs) })
    override fun findByHash(tokenHash: String): InvitationPreview? = query("invitation.token_hash = :hash", mapOf("hash" to tokenHash))
    override fun find(organizationId: OrganizationId, invitationId: Uuid): InvitationPreview? = query(
        "invitation.organization_id = :organizationId AND invitation.id = :id FOR UPDATE OF invitation", organizationParameters(organizationId) + ("id" to invitationId.toJavaUuid()),
    )
    override fun findByEmail(organizationId: OrganizationId, normalizedEmail: String): InvitationPreview? = query(
        "invitation.organization_id = :organizationId AND invitation.normalized_email = :email", organizationParameters(organizationId) + ("email" to normalizedEmail),
    )
    override fun membershipExistsByEmail(organizationId: OrganizationId, normalizedEmail: String): Boolean = jdbc.queryForObject(
        """SELECT EXISTS (SELECT 1 FROM identity_runtime.organization_members member
            JOIN identity_runtime.users member_user ON member_user.id = member.user_id
            WHERE member.organization_id = :organizationId AND member_user.primary_email = :email)""",
        organizationParameters(organizationId) + ("email" to normalizedEmail), Boolean::class.java,
    )!!

    override fun insert(invitation: OrganizationMembershipInvitation) {
        jdbc.update("""INSERT INTO identity_runtime.organization_membership_invitations
            (id, organization_id, delivery_email, normalized_email, token_hash, invited_by_user_id, created_at, expires_at, last_delivery_attempt_at, last_sent_at)
            VALUES (:id, :organizationId, :email, :normalizedEmail, :hash, :invitedBy, :createdAt, :expiresAt, :attemptAt, :sentAt)""", parameters(invitation))
        invitation.workspaceAccess.forEach { grant ->
            jdbc.update("""INSERT INTO identity_runtime.organization_membership_invitation_workspaces (invitation_id, workspace_id, role)
                VALUES (:id, :workspaceId, :role)""", mapOf("id" to invitation.id.toJavaUuid(),
                "workspaceId" to grant.workspaceId.value.toJavaUuid(), "role" to grant.role.name))
        }
    }
    override fun replace(invitation: OrganizationMembershipInvitation) {
        check(jdbc.update("""UPDATE identity_runtime.organization_membership_invitations SET token_hash = :hash, expires_at = :expiresAt,
            last_delivery_attempt_at = :attemptAt, last_sent_at = NULL WHERE id = :id""", parameters(invitation)) == 1)
    }
    override fun requirePendingCapacity(organizationId: OrganizationId, replacingInvitationId: Uuid?, now: Instant) {
        val plan = jdbc.query("SELECT plan FROM identity_runtime.organizations WHERE id = :organizationId FOR UPDATE",
            organizationParameters(organizationId)) { rs, _ -> rs.getString("plan") }.single()
        if (plan != "free") return
        if (countPendingOrganizationInvitations(jdbc, organizationId, now, replacingInvitationId) >= FreeOrganizationLimits.PENDING_INVITATIONS) {
            throw FreePendingInvitationLimitException()
        }
    }

    override fun delete(invitationId: Uuid) {
        jdbc.update("DELETE FROM identity_runtime.organization_membership_invitations WHERE id = :id", mapOf("id" to invitationId.toJavaUuid()))
    }
    override fun lockWorkspacesInOrganization(organizationId: OrganizationId, workspaceIds: Set<WorkspaceId>): Map<WorkspaceId, String> {
        if (workspaceIds.isEmpty()) return emptyMap()
        return jdbc.query(
            """SELECT id, display_name FROM identity_runtime.workspaces
                WHERE organization_id = :organizationId AND id IN (:workspaceIds) ORDER BY id FOR KEY SHARE""",
            organizationParameters(organizationId) + ("workspaceIds" to workspaceIds.map { it.value.toJavaUuid() }),
        ) { rs, _ -> WorkspaceId(rs.getUuid("id")) to rs.getString("display_name") }.toMap()
    }
    override fun markSent(invitationId: Uuid, expectedTokenHash: String, sentAt: Instant) {
        jdbc.update("""UPDATE identity_runtime.organization_membership_invitations SET last_sent_at = :sentAt
            WHERE id = :id AND token_hash = :hash""", mapOf("id" to invitationId.toJavaUuid(), "hash" to expectedTokenHash, "sentAt" to Timestamp.from(sentAt)))
    }
    override fun currentAuthoritativeEmail(credential: UserCredentialProvenance.BrowserSession, now: Instant): String? = jdbc.query(
        """SELECT asserted_email FROM identity_runtime.sessions WHERE id = :sessionId AND user_id = :userId
            AND revoked_at IS NULL AND absolute_expires_at > :now AND asserted_email_verified
            AND protocol = 'oidc' AND authentication_source_ref = 'builtin/google'
            AND authentication_issuer IN ('https://accounts.google.com', 'accounts.google.com')
            AND email_evidence = 'authoritative' FOR SHARE""",
        mapOf("sessionId" to credential.sessionId.value.toJavaUuid(), "userId" to credential.userId.value.toJavaUuid(), "now" to Timestamp.from(now)),
    ) { rs, _ -> rs.getString("asserted_email") }.firstOrNull()

    private fun query(where: String, parameters: Map<String, Any?>): InvitationPreview? =
        withWorkspaceAccess(jdbc.query("$SELECT WHERE $where", parameters) { rs, _ -> preview(rs) }).firstOrNull()
    private fun withWorkspaceAccess(previews: List<InvitationPreview>): List<InvitationPreview> {
        if (previews.isEmpty()) return previews
        val access = jdbc.query(
            """SELECT grant_row.invitation_id, grant_row.workspace_id, grant_row.role, workspace.display_name
                FROM identity_runtime.organization_membership_invitation_workspaces grant_row
                JOIN identity_runtime.organization_membership_invitations invitation ON invitation.id = grant_row.invitation_id
                LEFT JOIN identity_runtime.workspaces workspace
                  ON workspace.id = grant_row.workspace_id AND workspace.organization_id = invitation.organization_id
                WHERE grant_row.invitation_id IN (:ids)
                ORDER BY grant_row.invitation_id, workspace.display_name NULLS LAST, grant_row.workspace_id""",
            mapOf("ids" to previews.map { it.invitation.id.toJavaUuid() }),
        ) { rs, _ ->
            rs.getUuid("invitation_id") to InvitationWorkspaceAccess(WorkspaceId(rs.getUuid("workspace_id")), rs.getString("display_name"),
                WorkspaceRole.valueOf(rs.getString("role")))
        }.groupBy({ it.first }, { it.second })
        return previews.map { preview ->
            val grants = access[preview.invitation.id].orEmpty()
            preview.copy(invitation = preview.invitation.copy(workspaceAccess = grants.map { InvitationWorkspaceGrant(it.workspaceId, it.role) }),
                workspaceAccess = grants)
        }
    }
    private fun organizationParameters(organizationId: OrganizationId): Map<String, Any?> = mapOf("organizationId" to organizationId.value.toJavaUuid())
    private fun parameters(invitation: OrganizationMembershipInvitation): Map<String, Any?> = mapOf(
        "id" to invitation.id.toJavaUuid(), "organizationId" to invitation.organizationId.value.toJavaUuid(), "email" to invitation.deliveryEmail,
        "normalizedEmail" to invitation.normalizedEmail, "hash" to invitation.tokenHash, "invitedBy" to invitation.invitedByUserId?.value?.toJavaUuid(),
        "createdAt" to Timestamp.from(invitation.createdAt), "expiresAt" to Timestamp.from(invitation.expiresAt),
        "attemptAt" to invitation.lastDeliveryAttemptAt?.let(Timestamp::from), "sentAt" to invitation.lastSentAt?.let(Timestamp::from),
    )
    private fun preview(rs: ResultSet): InvitationPreview {
        val inviterId = rs.getString("invited_by_user_id")?.let { UserId(Uuid.parse(it)) }
        return InvitationPreview(
            OrganizationMembershipInvitation(rs.getUuid("id"), OrganizationId(rs.getUuid("organization_id")), rs.getString("delivery_email"),
                rs.getString("normalized_email"), rs.getString("token_hash"), inviterId, rs.getInstant("created_at"), rs.getInstant("expires_at"),
                rs.getTimestamp("last_delivery_attempt_at")?.toInstant(), rs.getTimestamp("last_sent_at")?.toInstant(), emptyList()),
            rs.getString("organization_display_name"),
            inviterId?.let { User(it, rs.getString("inviter_email"), rs.getString("inviter_display_name")) },
            emptyList(),
        )
    }
    companion object {
        private const val SELECT = """SELECT invitation.*, organization.display_name AS organization_display_name,
            inviter.primary_email AS inviter_email, inviter_principal.display_name AS inviter_display_name
            FROM identity_runtime.organization_membership_invitations invitation
            JOIN identity_runtime.organizations organization ON organization.id = invitation.organization_id
            LEFT JOIN identity_runtime.users inviter ON inviter.id = invitation.invited_by_user_id
            LEFT JOIN identity_runtime.principals inviter_principal ON inviter_principal.id = inviter.id"""
    }
}
