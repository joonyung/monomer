package cc.monomer.control.auth.inbound.graphql

import cc.monomer.identity.application.organization.OrganizationInvitationService
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.InvitationPreview
import cc.monomer.identity.domain.model.InvitationWorkspaceGrant
import java.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Controller

@Controller
class OrganizationInvitationResolver(private val invitations: OrganizationInvitationService, private val clock: Clock) {
    @QueryMapping
    suspend fun organizationMembershipInvitations(@Argument organizationId: String): List<OrganizationMembershipInvitationView> =
        invitations.list(actor(), OrganizationId(parseCanonicalUuid(organizationId, "organization id"))).map(::view)

    @MutationMapping
    suspend fun createOrganizationMembershipInvitation(
        @Argument organizationId: String, @Argument email: String, @Argument workspaceAccess: List<InvitationWorkspaceAccessInput>,
    ): InvitationDeliveryResultView =
        result(invitations.create(actor(), OrganizationId(parseCanonicalUuid(organizationId, "organization id")), email,
            workspaceAccess.map { InvitationWorkspaceGrant(WorkspaceId(parseCanonicalUuid(it.workspaceId, "workspace id")), it.role) }, governanceAudit = null))

    @MutationMapping
    suspend fun resendOrganizationMembershipInvitation(@Argument organizationId: String, @Argument invitationId: String): InvitationDeliveryResultView =
        result(invitations.resend(actor(), OrganizationId(parseCanonicalUuid(organizationId, "organization id")), parseCanonicalUuid(invitationId, "invitation id"), governanceAudit = null))

    @MutationMapping
    suspend fun cancelOrganizationMembershipInvitation(@Argument organizationId: String, @Argument invitationId: String): InvitationDeliveryResultView =
        result(invitations.cancel(actor(), OrganizationId(parseCanonicalUuid(organizationId, "organization id")), parseCanonicalUuid(invitationId, "invitation id"), governanceAudit = null))

    private suspend fun actor(): UserId = UserId(Uuid.parse(checkNotNull(ReactiveSecurityContextHolder.getContext().awaitSingle().authentication).name))
    private fun result(result: OrganizationInvitationService.Result) = InvitationDeliveryResultView(result.outcome.name, result.invitation?.let(::view), result.retryAfter)
    private fun view(preview: InvitationPreview): OrganizationMembershipInvitationView = with(preview.invitation) {
        OrganizationMembershipInvitationView(id.toString(), organizationId.toString(), deliveryEmail, createdAt.toString(), expiresAt.toString(),
            lastDeliveryAttemptAt?.toString(), lastSentAt?.toString(), if (clock.instant() < expiresAt) "PENDING" else "EXPIRED",
            preview.invitedBy?.let { UserView(it.userId.toString(), it.primaryEmail, it.displayName) },
            preview.workspaceAccess.mapNotNull { access ->
                access.workspaceDisplayName?.let { InvitationWorkspaceAccessView(access.workspaceId.toString(), it, access.role) }
            })
    }
}

data class OrganizationMembershipInvitationView(
    val id: String, val organizationId: String, val deliveryEmail: String, val createdAt: String, val expiresAt: String,
    val lastDeliveryAttemptAt: String?, val lastSentAt: String?, val status: String, val invitedBy: UserView?,
    val workspaceAccess: List<InvitationWorkspaceAccessView>,
)
data class InvitationWorkspaceAccessView(val workspaceId: String, val workspaceDisplayName: String, val role: WorkspaceRole)
data class InvitationWorkspaceAccessInput(val workspaceId: String, val role: WorkspaceRole)
data class InvitationDeliveryResultView(val outcome: String, val invitation: OrganizationMembershipInvitationView?, val retryAfter: Int?)
