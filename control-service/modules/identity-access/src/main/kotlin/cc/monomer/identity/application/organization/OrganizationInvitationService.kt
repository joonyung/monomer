package cc.monomer.identity.application.organization

import cc.monomer.identity.application.AccessDeniedException
import cc.monomer.identity.application.AdminAuthorization
import cc.monomer.identity.application.OpaqueCredentials
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.application.audit.GovernanceAudit
import cc.monomer.identity.application.audit.withGovernanceAudit
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.*
import cc.monomer.identity.domain.port.*
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OrganizationInvitationService(
    private val transactions: TransactionRunner,
    private val invitations: OrganizationInvitationRepository,
    private val organizations: OrganizationRepository,
    private val workspaceMembers: WorkspaceMemberRepository,
    private val admin: AdminAuthorization,
    private val audit: AuditLogRepository,
    private val auditFactory: AuditEventFactory,
    private val delivery: InvitationDelivery,
    private val clock: Clock,
) {
    private val deliveryAttempts = LinkedHashMap<Pair<OrganizationId, UserId>, List<Instant>>()

    enum class Outcome {
        SENT, DELIVERY_FAILED, INVITATION_PENDING, ALREADY_MEMBER,
        RATE_LIMITED, CANCELLED, UNAVAILABLE, INVALID_EMAIL, AVAILABLE, AUTHENTICATION_REQUIRED,
        EMAIL_MISMATCH, ACCEPTED, DECLINED, MEMBER_LIMIT_REACHED, INVALID_WORKSPACE_ACCESS,
    }
    data class Result(val outcome: Outcome, val invitation: InvitationPreview?, val retryAfter: Int?, val acceptedWorkspaceAccess: AcceptedWorkspaceAccess?)
    // Accepting grants each attached Workspace access that is still valid and reports the rest as skipped.
    data class AcceptedWorkspaceAccess(val granted: List<InvitationWorkspaceAccess>, val skipped: List<InvitationWorkspaceAccess>)
    data class Proof(val invitationId: Uuid, val organizationId: OrganizationId, val expectedTokenHash: String, val expiresAt: Instant)

    suspend fun list(actor: UserId, organizationId: OrganizationId): List<InvitationPreview> = transactions.run {
        admin.requireOrganizationAdminForWrite(actor.asPrincipal(), organizationId)
        requireOrganization(organizationId)
        invitations.list(organizationId)
    }

    suspend fun create(
        actor: UserId, organizationId: OrganizationId, email: String,
        workspaceAccess: List<InvitationWorkspaceGrant>, governanceAudit: GovernanceAudit?,
    ): Result {
        val deliveryEmail = email.trim()
        if (!isDeliverableInvitationAddress(deliveryEmail)) return result(Outcome.INVALID_EMAIL)
        val requestedWorkspaces = workspaceAccess.map { it.workspaceId }.toSet()
        if (requestedWorkspaces.size != workspaceAccess.size || workspaceAccess.size > MAX_WORKSPACE_ACCESS) return result(Outcome.INVALID_WORKSPACE_ACCESS)
        val normalizedEmail = deliveryEmail.lowercase()
        val rawToken = OpaqueCredentials.generate()
        val stored = transactions.run {
            admin.requireOrganizationAdminForWrite(actor.asPrincipal(), organizationId)
            requireOrganization(organizationId)
            if (requestedWorkspaces.isNotEmpty() && invitations.lockWorkspacesInOrganization(organizationId, requestedWorkspaces).keys != requestedWorkspaces) {
                return@run result(Outcome.INVALID_WORKSPACE_ACCESS)
            }
            if (invitations.membershipExistsByEmail(organizationId, normalizedEmail)) return@run result(Outcome.ALREADY_MEMBER)
            invitations.findByEmail(organizationId, normalizedEmail)?.let { return@run result(Outcome.INVITATION_PENDING, it) }
            val now = clock.instant()
            invitations.requirePendingCapacity(organizationId, null, now)
            reserveDelivery(organizationId, actor, now)?.let { return@run result(Outcome.RATE_LIMITED, retryAfter = it) }
            val invitation = OrganizationMembershipInvitation(Uuid.generateV7(), organizationId, deliveryEmail, normalizedEmail,
                OpaqueCredentials.hash(rawToken), actor, now, now.plus(Duration.ofDays(7)), now, null, workspaceAccess)
            invitations.insert(invitation)
            record(actor, invitation, "create", null, governanceAudit)
            result(Outcome.AVAILABLE, checkNotNull(invitations.find(organizationId, invitation.id)))
        }
        return deliver(stored, rawToken)
    }

    suspend fun resend(actor: UserId, organizationId: OrganizationId, invitationId: Uuid, governanceAudit: GovernanceAudit?): Result {
        val rawToken = OpaqueCredentials.generate()
        val stored = transactions.run {
            admin.requireOrganizationAdminForWrite(actor.asPrincipal(), organizationId)
            requireOrganization(organizationId)
            val preview = invitations.find(organizationId, invitationId) ?: return@run result(Outcome.UNAVAILABLE)
            val now = clock.instant()
            invitations.requirePendingCapacity(organizationId, invitationId, now)
            val cooldown = preview.invitation.lastDeliveryAttemptAt?.plusSeconds(60)
            if (cooldown != null && now < cooldown) return@run result(Outcome.RATE_LIMITED, preview, secondsUntil(now, cooldown))
            reserveDelivery(organizationId, actor, now)?.let { return@run result(Outcome.RATE_LIMITED, preview, it) }
            val rotated = preview.invitation.copy(tokenHash = OpaqueCredentials.hash(rawToken), expiresAt = now.plus(Duration.ofDays(7)),
                lastDeliveryAttemptAt = now, lastSentAt = null)
            invitations.replace(rotated)
            record(actor, rotated, "resend", null, governanceAudit)
            result(Outcome.AVAILABLE, preview.copy(invitation = rotated))
        }
        return deliver(stored, rawToken)
    }

    suspend fun cancel(actor: UserId, organizationId: OrganizationId, invitationId: Uuid, governanceAudit: GovernanceAudit?): Result = transactions.run {
        admin.requireOrganizationAdminForWrite(actor.asPrincipal(), organizationId)
        requireOrganization(organizationId)
        val preview = invitations.find(organizationId, invitationId) ?: return@run result(Outcome.UNAVAILABLE)
        invitations.delete(invitationId)
        record(actor, preview.invitation, "cancel", null, governanceAudit)
        result(Outcome.CANCELLED)
    }

    suspend fun exchange(rawToken: String): Proof? {
        if (!TOKEN.matches(rawToken)) return null
        return transactions.readOnly {
            val invitation = invitations.findByHash(OpaqueCredentials.hash(rawToken))?.invitation ?: return@readOnly null
            val now = clock.instant()
            if (now >= invitation.expiresAt) return@readOnly null
            Proof(invitation.id, invitation.organizationId, invitation.tokenHash, minOf(invitation.expiresAt, now.plusSeconds(1800)))
        }
    }

    suspend fun resolve(proof: Proof, credential: UserCredentialProvenance.BrowserSession?): Result = transactions.run {
        if (!invitations.lockOrganization(proof.organizationId)) return@run result(Outcome.UNAVAILABLE)
        val preview = valid(proof) ?: return@run result(Outcome.UNAVAILABLE)
        authentication(preview, credential)?.let { return@run result(it, preview) }
        val member = organizations.lockAndFindMembership(proof.organizationId, checkNotNull(credential).userId) != null
        result(if (member) Outcome.ALREADY_MEMBER else Outcome.AVAILABLE, preview)
    }

    suspend fun consume(proof: Proof, credential: UserCredentialProvenance.BrowserSession?, accept: Boolean): Result = transactions.run {
        if (!invitations.lockOrganization(proof.organizationId)) return@run result(Outcome.UNAVAILABLE)
        val preview = valid(proof) ?: return@run result(Outcome.UNAVAILABLE)
        authentication(preview, credential)?.let { return@run result(it, preview) }
        val actor = checkNotNull(credential).userId
        var acceptedWorkspaceAccess: AcceptedWorkspaceAccess? = null
        val outcome = if (!accept) Outcome.DECLINED else {
            if (organizations.lockAndFindMembership(proof.organizationId, actor) != null) {
                Outcome.ALREADY_MEMBER
            } else {
                try {
                    organizations.insertMember(OrganizationMembership(proof.organizationId, actor, OrganizationRole.MEMBER, clock.instant()))
                } catch (_: FreeOrganizationMemberLimitException) {
                    return@run result(Outcome.MEMBER_LIMIT_REACHED, preview)
                }
                acceptedWorkspaceAccess = grantWorkspaceAccess(actor, preview)
                Outcome.ACCEPTED
            }
        }
        invitations.delete(proof.invitationId)
        record(actor, preview.invitation, if (accept) "accept" else "decline", credential, null)
        Result(outcome, preview, null, acceptedWorkspaceAccess)
    }

    private fun grantWorkspaceAccess(actor: UserId, preview: InvitationPreview): AcceptedWorkspaceAccess {
        val grants = preview.invitation.workspaceAccess
        if (grants.isEmpty()) return AcceptedWorkspaceAccess(emptyList(), emptyList())
        // Attached access is granted on the inviter's authority, so it lapses if they are no longer an Admin here.
        val inviter = preview.invitation.invitedByUserId
        val inviterIsAdmin = inviter != null &&
            organizations.lockAndFindMembership(preview.invitation.organizationId, inviter)?.role == OrganizationRole.ADMIN
        if (!inviterIsAdmin) {
            val names = preview.workspaceAccess.associate { it.workspaceId to it.workspaceDisplayName }
            return AcceptedWorkspaceAccess(emptyList(), grants.map { InvitationWorkspaceAccess(it.workspaceId, names[it.workspaceId], it.role) })
        }
        val available = invitations.lockWorkspacesInOrganization(preview.invitation.organizationId, grants.map { it.workspaceId }.toSet())
        val now = clock.instant()
        val granted = mutableListOf<InvitationWorkspaceAccess>()
        val skipped = mutableListOf<InvitationWorkspaceAccess>()
        grants.forEach { grant ->
            val name = available[grant.workspaceId]
            val access = InvitationWorkspaceAccess(grant.workspaceId, name, grant.role)
            if (name == null) {
                skipped += access
                return@forEach
            }
            val insertion = workspaceMembers.insert(WorkspaceMembership(grant.workspaceId, actor, preview.invitation.invitedByUserId, now, grant.role))
            if (insertion != WorkspaceMemberInsertion.Inserted) {
                skipped += access
                return@forEach
            }
            granted += access
            audit.insert(auditFactory.build(actorId = actor.asPrincipal(), action = "workspace.member.add", workspaceId = grant.workspaceId, organizationId = null,
                resourceType = "user", resourceId = actor.value, details = buildJsonObject {
                    put("role", grant.role.name)
                    put("invitationId", preview.invitation.id.toString())
                }.toString()))
        }
        return AcceptedWorkspaceAccess(granted, skipped)
    }

    private fun valid(proof: Proof): InvitationPreview? {
        val now = clock.instant()
        if (now >= proof.expiresAt) return null
        return invitations.find(proof.organizationId, proof.invitationId)?.takeIf {
            it.invitation.tokenHash == proof.expectedTokenHash && now < it.invitation.expiresAt
        }
    }

    private fun authentication(preview: InvitationPreview, credential: UserCredentialProvenance.BrowserSession?): Outcome? {
        if (credential == null) return Outcome.AUTHENTICATION_REQUIRED
        val email = invitations.currentAuthoritativeEmail(credential, clock.instant()) ?: return Outcome.AUTHENTICATION_REQUIRED
        return if (email.trim().lowercase() == preview.invitation.normalizedEmail) null else Outcome.EMAIL_MISMATCH
    }

    private suspend fun deliver(stored: Result, rawToken: String): Result {
        if (stored.outcome != Outcome.AVAILABLE) return stored
        val preview = checkNotNull(stored.invitation)
        val sent = delivery.send(preview.invitation.deliveryEmail, preview.organizationDisplayName, preview.invitedBy, preview.invitation.expiresAt, rawToken)
        val current = transactions.run {
            invitations.lockOrganization(preview.invitation.organizationId)
            if (sent) invitations.markSent(preview.invitation.id, preview.invitation.tokenHash, clock.instant())
            invitations.find(preview.invitation.organizationId, preview.invitation.id)
        }
        if (current == null) return result(Outcome.UNAVAILABLE)
        if (current.invitation.tokenHash != preview.invitation.tokenHash) return result(Outcome.INVITATION_PENDING, current)
        return result(if (sent) Outcome.SENT else Outcome.DELIVERY_FAILED, current)
    }

    private fun requireOrganization(organizationId: OrganizationId) {
        if (!invitations.exists(organizationId)) throw AccessDeniedException("organization not found")
    }

    private fun record(actor: UserId, invitation: OrganizationMembershipInvitation, action: String, credential: UserCredentialProvenance.BrowserSession?, governanceAudit: GovernanceAudit?) = audit.insert(
        auditFactory.build(actorId = actor.asPrincipal(), action = "organization.membership_invitation.$action", workspaceId = null, organizationId = invitation.organizationId,
            resourceId = invitation.id, resourceType = "organization_membership_invitation", details = buildJsonObject {
                credential?.let { put("sessionId", it.sessionId.toString()) }
            }.toString()).withGovernanceAudit(governanceAudit),
    )

    private fun reserveDelivery(organizationId: OrganizationId, actor: UserId, now: Instant): Int? = synchronized(deliveryAttempts) {
        deliveryAttempts.replaceAll { _, attempts -> attempts.filter { it > now.minusSeconds(86400) } }
        deliveryAttempts.entries.removeIf { it.value.isEmpty() }
        val organizationTimes = deliveryAttempts.filterKeys { it.first == organizationId }.values.flatten().sorted()
        val actorTimes = deliveryAttempts.filterKeys { it.second == actor }.values.flatten().filter { it > now.minusSeconds(3600) }.sorted()
        val retryAt = listOfNotNull(organizationTimes.takeIf { it.size >= 50 }?.get(organizationTimes.size - 50)?.plusSeconds(86400),
            actorTimes.takeIf { it.size >= 30 }?.get(actorTimes.size - 30)?.plusSeconds(3600)).maxOrNull()
        if (retryAt != null) return secondsUntil(now, retryAt)
        val key = organizationId to actor
        if (key !in deliveryAttempts && deliveryAttempts.size >= 10000) return 60
        deliveryAttempts[key] = deliveryAttempts[key].orEmpty() + now
        null
    }

    private fun result(outcome: Outcome, invitation: InvitationPreview? = null, retryAfter: Int? = null) = Result(outcome, invitation, retryAfter, null)

    companion object {
        private val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
        const val MAX_WORKSPACE_ACCESS = 20
        private fun secondsUntil(now: Instant, end: Instant): Int = ((Duration.between(now, end).toMillis() + 999) / 1000).toInt().coerceAtLeast(1)
    }
}
