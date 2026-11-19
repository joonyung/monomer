package cc.monomer.identity.domain.model

import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import java.time.Instant
import kotlin.uuid.Uuid

data class OrganizationMembershipInvitation(
    val id: Uuid,
    val organizationId: OrganizationId,
    val deliveryEmail: String,
    val normalizedEmail: String,
    val tokenHash: String,
    val invitedByUserId: UserId?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val lastDeliveryAttemptAt: Instant?,
    val lastSentAt: Instant?,
    val workspaceAccess: List<InvitationWorkspaceGrant>,
)

/** An address invitation mail can be sent to: an ASCII dot-atom local part at a hostname of letter-digit-hyphen labels. */
fun isDeliverableInvitationAddress(address: String): Boolean =
    address.length <= 254 && address.matches(DELIVERABLE_INVITATION_ADDRESS)

private val DELIVERABLE_INVITATION_ADDRESS =
    Regex("[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+")

/** Workspace access an Organization Admin attached to an invitation; accepting creates the membership. */
data class InvitationWorkspaceGrant(val workspaceId: WorkspaceId, val role: WorkspaceRole)

/** A grant with the Workspace's current name, or null when the Workspace is no longer in the Organization. */
data class InvitationWorkspaceAccess(val workspaceId: WorkspaceId, val workspaceDisplayName: String?, val role: WorkspaceRole)

data class InvitationPreview(
    val invitation: OrganizationMembershipInvitation,
    val organizationDisplayName: String,
    val invitedBy: User?,
    val workspaceAccess: List<InvitationWorkspaceAccess>,
)
