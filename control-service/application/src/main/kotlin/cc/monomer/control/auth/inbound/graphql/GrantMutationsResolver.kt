package cc.monomer.control.auth.inbound.graphql

import cc.monomer.identity.application.organization.RemoveOrganizationMemberUseCase
import cc.monomer.identity.application.organization.UpdateOrganizationMemberRoleUseCase
import cc.monomer.identity.application.workspace.AddWorkspaceMemberUseCase
import cc.monomer.identity.application.workspace.SetWorkspacePublicPagesUseCase
import cc.monomer.identity.application.workspace.SetWorkspaceReportingTimeZoneUseCase
import cc.monomer.identity.application.workspace.RenameWorkspaceUseCase
import cc.monomer.identity.application.workspace.RemoveWorkspaceMemberUseCase
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.OrganizationRole
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.application.grant.SetWorkspaceMemberRoleUseCase
import kotlin.uuid.Uuid
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Controller

@Controller
class GrantMutationsResolver(
    private val removeOrganizationMemberUseCase: RemoveOrganizationMemberUseCase,
    private val updateOrganizationMemberRoleUseCase: UpdateOrganizationMemberRoleUseCase,
    private val addWorkspaceMemberUseCase: AddWorkspaceMemberUseCase,
    private val removeWorkspaceMemberUseCase: RemoveWorkspaceMemberUseCase,
    private val setWorkspacePublicPagesUseCase: SetWorkspacePublicPagesUseCase,
    private val renameWorkspaceUseCase: RenameWorkspaceUseCase,
    private val setWorkspaceMemberRoleUseCase: SetWorkspaceMemberRoleUseCase,
    private val setWorkspaceReportingTimeZoneUseCase: SetWorkspaceReportingTimeZoneUseCase,
) {

    private suspend fun actorUserId(): UserId {
        val auth = ReactiveSecurityContextHolder.getContext()
            .map { securityContext -> checkNotNull(securityContext.authentication) }
            .awaitSingle()
        return UserId(Uuid.parse(auth.name))
    }

    @MutationMapping
    suspend fun removeOrganizationMember(
        @Argument organizationId: String,
        @Argument userId: String,
    ): Boolean {
        val parsedOrganizationId = OrganizationId(parseCanonicalUuid(organizationId, "organization id"))
        val parsedUserId = UserId(parseCanonicalUuid(userId, "user id"))
        return when (
            removeOrganizationMemberUseCase.remove(
                RemoveOrganizationMemberUseCase.Command(
                    actorPrincipalId = actorUserId().asPrincipal(),
                    organizationId = parsedOrganizationId,
                    userId = parsedUserId,
                ),
            )
        ) {
            RemoveOrganizationMemberUseCase.Result.Removed -> true
            RemoveOrganizationMemberUseCase.Result.NotFound ->
                throw OrganizationMemberNotFoundException(parsedOrganizationId, parsedUserId)
        }
    }

    @MutationMapping
    suspend fun updateOrganizationMemberRole(
        @Argument organizationId: String,
        @Argument userId: String,
        @Argument role: String,
    ): OrganizationMembershipResultView {
        val parsedOrganizationId = OrganizationId(parseCanonicalUuid(organizationId, "organization id"))
        val parsedUserId = UserId(parseCanonicalUuid(userId, "user id"))
        return when (
            val result = updateOrganizationMemberRoleUseCase.update(
                UpdateOrganizationMemberRoleUseCase.Command(
                    actorPrincipalId = actorUserId().asPrincipal(),
                    organizationId = parsedOrganizationId,
                    userId = parsedUserId,
                    role = OrganizationRole.valueOf(role),
                ),
            )
        ) {
            is UpdateOrganizationMemberRoleUseCase.Result.Updated -> OrganizationMembershipResultView.from(result.membership)
            UpdateOrganizationMemberRoleUseCase.Result.NotFound ->
                throw OrganizationMemberNotFoundException(parsedOrganizationId, parsedUserId)
            UpdateOrganizationMemberRoleUseCase.Result.NoTransition ->
                throw OrganizationMemberNoTransitionException(parsedOrganizationId, parsedUserId, "role")
        }
    }

    @MutationMapping
    suspend fun addWorkspaceMember(
        @Argument workspaceId: String,
        @Argument principalId: String,
        @Argument role: String,
    ): WorkspaceMembershipResultView {
        val membership = addWorkspaceMemberUseCase.add(
            AddWorkspaceMemberUseCase.Command(
                actorUserId = actorUserId(),
                workspaceId = WorkspaceId(parseCanonicalUuid(workspaceId, "workspace id")),
                targetUserId = UserId(parseCanonicalUuid(principalId, "principal id")), role = WorkspaceRole.valueOf(role), audit = null
            ),
        )
        return WorkspaceMembershipResultView(
            workspaceId = membership.workspaceId.toString(),
            principalId = membership.userId.toString(),
            role = membership.role.name,
        )
    }

    @MutationMapping
    suspend fun removeWorkspaceMember(
        @Argument workspaceId: String,
        @Argument principalId: String,
    ): Boolean = removeWorkspaceMemberUseCase.remove(
        RemoveWorkspaceMemberUseCase.Command(
            actorUserId = actorUserId(),
            workspaceId = WorkspaceId(parseCanonicalUuid(workspaceId, "workspace id")),
            targetUserId = UserId(parseCanonicalUuid(principalId, "principal id")), audit = null
        ),
    )

    @MutationMapping
    suspend fun setWorkspacePublicPages(
        @Argument workspaceId: String,
        @Argument publicPages: Boolean,
    ): WorkspaceView = WorkspaceView.from(setWorkspacePublicPagesUseCase.set(
        SetWorkspacePublicPagesUseCase.Command(actorUserId(), WorkspaceId(parseCanonicalUuid(workspaceId, "workspace id")), publicPages),
    ))

    @MutationMapping
    suspend fun setWorkspaceReportingTimeZone(
        @Argument workspaceId: String,
        @Argument timeZone: String,
    ): WorkspaceView = when (val result = setWorkspaceReportingTimeZoneUseCase.set(
        SetWorkspaceReportingTimeZoneUseCase.Command(actorUserId(), WorkspaceId(parseCanonicalUuid(workspaceId, "workspace id")), timeZone),
    )) {
        is SetWorkspaceReportingTimeZoneUseCase.Result.Set -> WorkspaceView.from(result.workspace)
        is SetWorkspaceReportingTimeZoneUseCase.Result.UnsupportedTimeZone -> throw UnsupportedReportingTimeZoneException()
    }

    @MutationMapping
    suspend fun renameWorkspace(
        @Argument workspaceId: String,
        @Argument displayName: String,
    ): WorkspaceView {
        val workspace = renameWorkspaceUseCase.rename(
            RenameWorkspaceUseCase.Command(
                actorUserId = actorUserId(),
                workspaceId = WorkspaceId(parseCanonicalUuid(workspaceId, "workspace id")),
                displayName = displayName,
            ),
        )
        return WorkspaceView.from(workspace)
    }

    @MutationMapping
    suspend fun setWorkspaceMemberRole(
        @Argument workspaceId: String,
        @Argument userId: String,
        @Argument role: String,
    ): Boolean = setWorkspaceMemberRoleUseCase.set(
        SetWorkspaceMemberRoleUseCase.Command(
            actorUserId(), WorkspaceId(parseCanonicalUuid(workspaceId, "workspace id")),
            UserId(parseCanonicalUuid(userId, "user id")), WorkspaceRole.valueOf(role), null,
        ),
    )


}

data class OrganizationMembershipResultView(
    val organizationId: String,
    val userId: String,
    val role: String,
) {
    companion object {
        fun from(membership: cc.monomer.identity.domain.model.OrganizationMembership): OrganizationMembershipResultView =
            OrganizationMembershipResultView(
                organizationId = membership.organizationId.toString(),
                userId = membership.userId.toString(),
                role = membership.role.name,
            )
    }
}
data class WorkspaceMembershipResultView(val workspaceId: String, val principalId: String, val role: String)
