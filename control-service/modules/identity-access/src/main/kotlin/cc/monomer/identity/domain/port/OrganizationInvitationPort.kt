package cc.monomer.identity.domain.port

import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.OrganizationMembershipInvitation
import cc.monomer.identity.domain.model.InvitationPreview
import cc.monomer.identity.domain.model.User
import cc.monomer.identity.domain.model.UserCredentialProvenance
import java.time.Instant
import kotlin.uuid.Uuid

interface OrganizationInvitationRepository {
    fun requirePendingCapacity(organizationId: OrganizationId, replacingInvitationId: Uuid?, now: Instant)
    fun lockOrganization(organizationId: OrganizationId): Boolean
    fun exists(organizationId: OrganizationId): Boolean
    fun list(organizationId: OrganizationId): List<InvitationPreview>
    fun findByHash(tokenHash: String): InvitationPreview?
    fun find(organizationId: OrganizationId, invitationId: Uuid): InvitationPreview?
    fun findByEmail(organizationId: OrganizationId, normalizedEmail: String): InvitationPreview?
    fun membershipExistsByEmail(organizationId: OrganizationId, normalizedEmail: String): Boolean
    fun insert(invitation: OrganizationMembershipInvitation)
    fun replace(invitation: OrganizationMembershipInvitation)
    fun delete(invitationId: Uuid)
    // Locks the named Workspaces that belong to the Organization against deletion and returns their names.
    fun lockWorkspacesInOrganization(organizationId: OrganizationId, workspaceIds: Set<WorkspaceId>): Map<WorkspaceId, String>
    fun markSent(invitationId: Uuid, expectedTokenHash: String, sentAt: Instant)
    fun currentAuthoritativeEmail(credential: UserCredentialProvenance.BrowserSession, now: Instant): String?
}

interface InvitationDelivery {
    suspend fun send(deliveryEmail: String, organizationDisplayName: String, invitedBy: User?, expiresAt: Instant, rawToken: String): Boolean
}
