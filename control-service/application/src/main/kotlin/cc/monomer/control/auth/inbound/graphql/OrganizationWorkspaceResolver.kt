package cc.monomer.control.auth.inbound.graphql

import cc.monomer.identity.application.organization.UpdateOrganizationDisplayNameUseCase
import cc.monomer.identity.domain.port.WorkspaceQuery
import cc.monomer.identity.application.organization.OrganizationMembersUseCase
import cc.monomer.identity.application.organization.OrganizationUsageUseCase
import cc.monomer.identity.application.organization.CreateOrganizationUseCase
import cc.monomer.identity.application.workspace.CreateWorkspaceUseCase
import cc.monomer.identity.application.workspace.AccessibleWorkspacesUseCase
import cc.monomer.identity.application.workspace.ManagedWorkspacesUseCase
import cc.monomer.identity.application.workspace.WorkspaceMemberCandidates
import cc.monomer.identity.application.workspace.WorkspaceMemberCandidatesUseCase
import cc.monomer.identity.application.workspace.WorkspaceMembersUseCase
import cc.monomer.identity.application.workspace.WorkspaceOwnerCandidatesUseCase
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.AccessibleWorkspace
import cc.monomer.identity.domain.model.OrganizationMember
import cc.monomer.identity.domain.model.OrganizationResourceCounts
import cc.monomer.identity.domain.model.OrganizationUsage
import cc.monomer.identity.domain.model.Workspace
import cc.monomer.identity.domain.model.WorkspaceMember
import kotlin.uuid.Uuid
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Controller

@Controller
class OrganizationWorkspaceResolver(
    private val accessibleWorkspacesUseCase: AccessibleWorkspacesUseCase,
    private val managedWorkspacesUseCase: ManagedWorkspacesUseCase,
    private val organizationMembersUseCase: OrganizationMembersUseCase,
    private val organizationUsageUseCase: OrganizationUsageUseCase,
    private val workspaceMembersUseCase: WorkspaceMembersUseCase,
    private val workspaceOwnerCandidatesUseCase: WorkspaceOwnerCandidatesUseCase,
    private val workspaceMemberCandidatesUseCase: WorkspaceMemberCandidatesUseCase,
    private val createWorkspaceUseCase: CreateWorkspaceUseCase,
    private val createOrganizationUseCase: CreateOrganizationUseCase,
    private val updateOrganizationDisplayNameUseCase: UpdateOrganizationDisplayNameUseCase,
    private val workspaceQuery: WorkspaceQuery,
) {

    private suspend fun actorUserId(): UserId {
        val auth = ReactiveSecurityContextHolder.getContext()
            .map { securityContext -> checkNotNull(securityContext.authentication) }
            .awaitSingle()
        return UserId(Uuid.parse(auth.name))
    }

    @QueryMapping
    suspend fun accessibleWorkspaces(): List<AccessibleWorkspaceView> {
        val workspaces = accessibleWorkspacesUseCase.list(
            AccessibleWorkspacesUseCase.Command(actorUserId = actorUserId()),
        )
        return workspaces.map { AccessibleWorkspaceView.from(it) }
    }

    @QueryMapping
    suspend fun managedWorkspaces(@Argument organizationId: String): List<WorkspaceView> {
        val workspaces = managedWorkspacesUseCase.list(
            ManagedWorkspacesUseCase.Command(
                actorUserId = actorUserId(),
                organizationId = OrganizationId(parseCanonicalUuid(organizationId, "organization id")),
            ),
        )
        return workspaces.map { WorkspaceView.from(it) }
    }

    @QueryMapping
    suspend fun organizationMembers(@Argument organizationId: String): List<OrganizationMemberView> {
        val members = organizationMembersUseCase.list(
            OrganizationMembersUseCase.Command(
                actorUserId = actorUserId(),
                organizationId = OrganizationId(parseCanonicalUuid(organizationId, "organization id")),
            ),
        )
        return members.map { OrganizationMemberView.from(it) }
    }

    @QueryMapping
    suspend fun organizationUsage(@Argument organizationId: String): OrganizationUsageView =
        OrganizationUsageView.from(
            organizationUsageUseCase.read(
                OrganizationUsageUseCase.Command(
                    actorUserId = actorUserId(),
                    organizationId = OrganizationId(parseCanonicalUuid(organizationId, "organization id")),
                ),
            ),
        )

    @QueryMapping
    suspend fun workspaceMembers(@Argument workspaceId: String): List<WorkspaceMemberView> {
        val members = workspaceMembersUseCase.list(
            WorkspaceMembersUseCase.Command(
                actorUserId = actorUserId(),
                workspaceId = WorkspaceId(parseCanonicalUuid(workspaceId, "workspace id")),
            ),
        )
        return members.map { WorkspaceMemberView.from(it) }
    }

    @QueryMapping
    suspend fun workspaceOwnerCandidates(
        @Argument organizationId: String,
        @Argument workspaceId: String,
    ): List<UserView> = workspaceOwnerCandidatesUseCase.list(
        WorkspaceOwnerCandidatesUseCase.Command(
            actorUserId = actorUserId(),
            organizationId = OrganizationId(parseCanonicalUuid(organizationId, "organization id")),
            workspaceId = WorkspaceId(parseCanonicalUuid(workspaceId, "workspace id")),
        ),
    ).map {
        UserView(
            id = it.userId.toString(),
            email = it.primaryEmail,
            displayName = it.displayName,
        )
    }

    @QueryMapping
    suspend fun workspaceMemberCandidates(@Argument workspaceId: String): WorkspaceMemberCandidatesView {
        val candidates = workspaceMemberCandidatesUseCase.list(
            WorkspaceMemberCandidatesUseCase.Command(
                actorUserId = actorUserId(),
                workspaceId = WorkspaceId(parseCanonicalUuid(workspaceId, "workspace id")),
            ),
        )
        return WorkspaceMemberCandidatesView.from(candidates)
    }

    @MutationMapping
    suspend fun createOrganization(
        @Argument displayName: String,
    ): OrganizationBootstrapView {
        val actorUserId = actorUserId()
        val response = createOrganizationUseCase.create(
            CreateOrganizationUseCase.Command(
                actorUserId = actorUserId,
                initialOwnerUserId = actorUserId,
                displayName = displayName,
            ),
        )
        return OrganizationBootstrapView(
            organization = OrganizationView(
                id = response.organization.id.toString(),
                displayName = response.organization.displayName,
                plan = response.organization.plan.name,
                createdAt = response.organization.createdAt.toString(),
            ),
            defaultWorkspace = WorkspaceView.from(response.defaultWorkspace),
            membership = OrganizationMembershipResultView(
                organizationId = response.membership.organizationId.toString(),
                userId = response.membership.userId.toString(),
                role = response.membership.role.name,
            ),
        )
    }

    @SchemaMapping(typeName = "Workspace", field = "memberCount")
    suspend fun workspaceMemberCount(workspace: WorkspaceView): Int = workspaceQuery.countMembers(WorkspaceId(Uuid.parse(workspace.id)))

    @SchemaMapping(typeName = "AccessibleWorkspace", field = "memberCount")
    suspend fun accessibleWorkspaceMemberCount(workspace: AccessibleWorkspaceView): Int =
        workspaceQuery.countMembers(WorkspaceId(Uuid.parse(workspace.id)))

    @MutationMapping
    suspend fun updateOrganizationDisplayName(@Argument organizationId: String, @Argument displayName: String): OrganizationView {
        val organization = updateOrganizationDisplayNameUseCase.update(UpdateOrganizationDisplayNameUseCase.Command(
            actorUserId(), OrganizationId(parseCanonicalUuid(organizationId, "organization id")), displayName,
        ))
        return OrganizationView(
            id = organization.id.toString(),
            displayName = organization.displayName,
            plan = organization.plan.name,
            createdAt = organization.createdAt.toString(),
        )
    }

    @MutationMapping
    suspend fun createWorkspace(
        @Argument organizationId: String,
        @Argument displayName: String,
        @Argument initialOwnerUserId: String,
    ): WorkspaceView {
        val actorUserId = actorUserId()
        val workspace = createWorkspaceUseCase.create(
            CreateWorkspaceUseCase.Command(
                actorUserId = actorUserId,
                initialOwnerUserId = UserId(parseCanonicalUuid(initialOwnerUserId, "initial owner user id")),
                organizationId = OrganizationId(parseCanonicalUuid(organizationId, "organization id")),
                displayName = displayName,
            ),
        )
        return WorkspaceView.from(workspace)
    }
}

data class OrganizationBootstrapView(
    val organization: OrganizationView,
    val defaultWorkspace: WorkspaceView,
    val membership: OrganizationMembershipResultView,
)

data class WorkspaceView(
    val id: String,
    val organizationId: String,
    val name: String,
    val createdAt: String,
    val publicPages: Boolean,
    val reportingTimeZone: String,
    val sample: WorkspaceSampleView?,
) {
    companion object {
        fun from(workspace: Workspace): WorkspaceView = WorkspaceView(
            id = workspace.id.toString(),
            organizationId = workspace.organizationId.toString(),
            name = workspace.displayName,
            createdAt = workspace.createdAt.toString(),
            publicPages = workspace.publicPages,
            reportingTimeZone = workspace.reportingTimeZone.id,
            sample = workspace.sample?.let(WorkspaceSampleView::from),
        )
    }
}

data class AccessibleWorkspaceView(
    val id: String,
    val organizationId: String,
    val name: String,
    val createdAt: String,
    val publicPages: Boolean,
    val reportingTimeZone: String,
    val role: String,
    val sample: WorkspaceSampleView?,
) {
    companion object {
        fun from(accessible: AccessibleWorkspace): AccessibleWorkspaceView = AccessibleWorkspaceView(
            id = accessible.workspace.id.toString(),
            organizationId = accessible.workspace.organizationId.toString(),
            name = accessible.workspace.displayName,
            createdAt = accessible.workspace.createdAt.toString(),
            publicPages = accessible.workspace.publicPages,
            reportingTimeZone = accessible.workspace.reportingTimeZone.id,
            role = accessible.role.name,
            sample = accessible.workspace.sample?.let(WorkspaceSampleView::from),
        )
    }
}

data class OrganizationMemberView(
    val user: UserView,
    val role: String,
    val joinedAt: String,
    val workspaces: List<OrganizationMemberWorkspaceView>,
) {
    companion object {
        fun from(member: OrganizationMember): OrganizationMemberView = OrganizationMemberView(
            user = UserView(
                id = member.user.userId.toString(),
                email = member.user.primaryEmail,
                displayName = member.user.displayName,
            ),
            role = member.role.name,
            joinedAt = member.joinedAt.toString(),
            workspaces = member.workspaces.map { OrganizationMemberWorkspaceView(it.id.toString(), it.name, it.role.name, it.memberCount, it.ownerCount, it.sample?.let(WorkspaceSampleView::from)) },
        )
    }
}

data class WorkspaceMemberView(
    val principalId: String,
    val displayName: String,
    val user: UserView?,
    val role: String,
) {
    companion object {
        fun from(member: WorkspaceMember): WorkspaceMemberView = WorkspaceMemberView(
            principalId = member.user.userId.toString(),
            displayName = member.user.displayName,
            user = UserView(
                id = member.user.userId.toString(),
                email = member.user.primaryEmail,
                displayName = member.user.displayName,
            ),
            role = member.role.name,
        )
    }
}

data class WorkspaceMemberCandidatesView(
    val users: List<OrganizationMemberView>,
) {
    companion object {
        fun from(candidates: WorkspaceMemberCandidates): WorkspaceMemberCandidatesView =
            WorkspaceMemberCandidatesView(
                users = candidates.users.map { OrganizationMemberView.from(it) },
            )
    }
}

data class OrganizationMemberWorkspaceView(val id: String, val name: String, val role: String, val memberCount: Int, val ownerCount: Int, val sample: WorkspaceSampleView?)

data class WorkspaceSampleView(val dataset: String, val state: String, val initiator: String) {
    companion object {
        fun from(marker: cc.monomer.identity.api.WorkspaceSampleMarker) =
            WorkspaceSampleView(marker.dataset, marker.state.name, marker.initiator.toString())
    }
}

data class OrganizationResourceCountsView(val members: Int, val workspaces: Int, val pendingInvitations: Int) {
    companion object {
        fun from(counts: OrganizationResourceCounts): OrganizationResourceCountsView =
            OrganizationResourceCountsView(counts.members, counts.workspaces, counts.pendingInvitations)
    }
}

data class OrganizationUsageView(val limits: OrganizationResourceCountsView?, val usage: OrganizationResourceCountsView) {
    companion object {
        fun from(usage: OrganizationUsage): OrganizationUsageView = OrganizationUsageView(
            limits = usage.limits?.let(OrganizationResourceCountsView::from),
            usage = OrganizationResourceCountsView.from(usage.usage),
        )
    }
}
