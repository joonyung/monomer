@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package cc.monomer.control.agent

import cc.monomer.agent.api.*
import cc.monomer.control.WorkspaceMembershipAdmissionRequest
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.lineage.*
import cc.monomer.identity.api.AgentOAuthPort
import cc.monomer.identity.api.OAuthAgentIdentity
import cc.monomer.identity.api.OAuthOutcome
import cc.monomer.identity.api.WorkspaceReportingTimeZoneQuery
import cc.monomer.identity.application.AccessDeniedException
import cc.monomer.identity.application.ScopedResourceNotFoundException
import cc.monomer.identity.application.organization.OrganizationInvitationService
import cc.monomer.identity.application.organization.OrganizationMembersUseCase
import cc.monomer.identity.application.audit.GovernanceAudit
import cc.monomer.identity.application.grant.*
import cc.monomer.identity.application.me.MeUseCase
import cc.monomer.identity.application.workspace.*
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.*
import cc.monomer.identity.domain.port.FreeOrganizationLimitException
import cc.monomer.identity.domain.port.LastWorkspaceOwnerException
import java.time.Clock
import kotlin.uuid.Uuid

class AgentManagementOwnerAdapter(
    private val oauth: AgentOAuthPort,
    private val admission: ControlAdmissionFacade,
    private val me: MeUseCase,
    private val managedWorkspaces: ManagedWorkspacesUseCase,
    private val organizationMembers: OrganizationMembersUseCase,
    private val invitations: OrganizationInvitationService,
    private val members: WorkspaceMembersUseCase,
    private val candidates: WorkspaceMemberCandidatesUseCase,
    private val authorization: WorkspaceAuthorizationAssignmentsUseCase,
    private val addMember: AddWorkspaceMemberUseCase,
    private val removeMember: RemoveWorkspaceMemberUseCase,
    private val setMemberRole: SetWorkspaceMemberRoleUseCase,
    private val reportingTimeZones: WorkspaceReportingTimeZoneQuery,
    private val lineage: LineageGraphQueryService,
    private val clock: Clock,
) : AgentManagementOwnerPort {
    override suspend fun list(caller: AgentOrganizationCaller, request: AgentListRequest): AgentListingOutcome = listing(caller) {
        if (!AgentManagementPaths.organizationCollection.matches(request.path.value)) return@listing AgentListingOutcome.NotFound
        verify(caller)
        val actor = UserId(Uuid.parse(caller.actorPrincipalId))
        val organization = OrganizationId(caller.organizationId.value)
        val path = request.path.value
        val items: List<AgentResourceListingItem> = when (path) {
            "organization/workspaces" -> managedWorkspaces.list(ManagedWorkspacesUseCase.Command(actor, organization)).map {
                AgentResourceListingItem.Collection(path("organization/workspaces/${it.id}/members"), it.displayName)
            }
            "organization/members" -> organizationMembers.list(OrganizationMembersUseCase.Command(actor, organization)).map { it.listing() }
            "organization/invitations" -> invitations.list(actor, organization).map { preview -> preview.managementBody(clock.instant()).let {
                AgentResourceListingItem.OrganizationInvitation(path("organization/invitations/${it.id}"), it.id, it.email, it.status) } }
            else -> {
                val workspace = managedWorkspace(actor, organization, path)
                members.list(WorkspaceMembersUseCase.Command(actor, workspace)).map {
                    AgentResourceListingItem.OrganizationWorkspaceMember(path("$path/${it.user.userId}"), it.managementBody())
                }
            }
        }
        val operations = if (path == "organization/invitations") listOf(AgentAvailableOperation.Create(request.path, "ORGANIZATION_INVITATION_CREATE")) else emptyList()
        paginate("${caller.organizationId}:$path", request, items, operations)
    }

    override suspend fun read(caller: AgentOrganizationCaller, request: AgentReadRequest): AgentResourceOutcome = resource(caller) {
        if (!AgentManagementPaths.organization(request.path.value) || !AgentManagementPaths.readable(request.path.value)) return@resource AgentResourceOutcome.NotFound
        val (_, response) = verify(caller)
        val actor = UserId(Uuid.parse(caller.actorPrincipalId))
        val organization = OrganizationId(caller.organizationId.value)
        val currentPath = request.path.value
        val envelope = when {
            currentPath == "organization" -> {
                val membership = response.organizationMemberships.singleOrNull { it.organization.id == organization } ?: missing()
                AgentResourceEnvelope.Organization(request.path, AgentOrganizationBody(organization.value, membership.organization.displayName,
                    AgentOrganizationSelf(actor.value, response.user.displayName, AgentOrganizationRole.valueOf(membership.role.name))),
                    buildList {
                        if (membership.role == OrganizationRole.ADMIN) {
                            add(AgentAvailableOperation.List(path("organization/workspaces")))
                            add(AgentAvailableOperation.List(path("organization/members")))
                            add(AgentAvailableOperation.List(path("organization/invitations")))
                            add(AgentAvailableOperation.Create(path("organization/invitations"), "ORGANIZATION_INVITATION_CREATE"))
                        }
                    })
            }
            AgentManagementPaths.organizationWorkspaceMember.matches(currentPath) -> {
                val workspace = managedWorkspace(actor, organization, currentPath)
                val member = members.list(WorkspaceMembersUseCase.Command(actor, workspace)).singleOrNull { it.user.userId.value == id(currentPath) } ?: missing()
                AgentResourceEnvelope.WorkspaceMember(request.path, member.managementBody(), listOf(AgentAvailableOperation.Invoke(request.path, "set-role", "WORKSPACE_MEMBER_ROLE"), AgentAvailableOperation.Invoke(request.path, "remove-member", null)))
            }
            AgentManagementPaths.organizationMember.matches(currentPath) -> {
                val member = organizationMembers.list(OrganizationMembersUseCase.Command(actor, organization)).singleOrNull { it.user.userId.value == id(currentPath) } ?: missing()
                AgentResourceEnvelope.OrganizationMember(request.path, member.managementBody(), listOf(AgentAvailableOperation.List(path("organization/members"))))
            }
            else -> {
                val preview = invitations.list(actor, organization).singleOrNull { it.invitation.id == id(currentPath) } ?: missing()
                AgentResourceEnvelope.OrganizationInvitation(request.path, preview.managementBody(clock.instant()), listOf(
                    AgentAvailableOperation.Invoke(request.path, "resend", null), AgentAvailableOperation.Invoke(request.path, "cancel", null)))
            }
        }
        AgentResourceOutcome.Found(envelope)
    }

    override suspend fun create(caller: AgentOrganizationCaller, request: AgentCreateRequest): AgentActionOutcome = action(caller) {
        if (request.path.value != "organization/invitations" || request.input !is AgentCreateInput.OrganizationInvitation) return@action AgentActionOutcome.NotFound
        val (identity, _) = verify(caller)
        val input = request.input as AgentCreateInput.OrganizationInvitation
        invitationResult(invitations.create(UserId(identity.userId.value), OrganizationId(caller.organizationId.value), input.email,
            input.workspaceAccess.map { InvitationWorkspaceGrant(WorkspaceId(it.workspaceId), it.role) }, audit(caller, identity)), request.path, null)
    }

    override suspend fun invoke(caller: AgentOrganizationCaller, request: AgentInvokeRequest): AgentActionOutcome = action(caller) {
        if (!AgentManagementPaths.organization(request.path.value) || !AgentManagementPaths.invocation(request)) return@action AgentActionOutcome.NotFound
        val (identity, _) = verify(caller)
        val actor = UserId(identity.userId.value)
        val organization = OrganizationId(caller.organizationId.value)
        if (AgentManagementPaths.organizationWorkspaceMember.matches(request.path.value)) {
            val workspace = managedWorkspace(actor, organization, request.path.value)
            if (request.action == "remove-member") {
                val removed = removeMember.remove(RemoveWorkspaceMemberUseCase.Command(actor, workspace, UserId(id(request.path.value)), audit(caller, identity)))
                if (!removed) return@action AgentActionOutcome.NotFound
                return@action completed(request, AgentResourceKind.COLLECTION, request.path.value.substringBeforeLast('/'))
            }
            setMemberRole.set(SetWorkspaceMemberRoleUseCase.Command(actor, workspace, UserId(id(request.path.value)), (request.input as AgentInvokeInput.WorkspaceMemberRole).role, audit(caller, identity)))
            return@action completed(request, AgentResourceKind.WORKSPACE_MEMBER, request.path.value)
        }
        val result = when (request.action) {
            "resend" -> invitations.resend(actor, organization, id(request.path.value), audit(caller, identity))
            "cancel" -> invitations.cancel(actor, organization, id(request.path.value), audit(caller, identity))
            else -> return@action AgentActionOutcome.NotFound
        }
        invitationResult(result, request.path, request.action)
    }

    override suspend fun list(caller: AgentWorkspaceCaller, request: AgentListRequest): AgentListingOutcome = listing(null) {
        if (!AgentManagementPaths.workspaceCollection.matches(request.path.value)) return@listing AgentListingOutcome.NotFound
        val (identity, organization, membership) = verify(caller)
        val actor = UserId(identity.userId.value)
        val workspace = WorkspaceId(caller.workspaceId.value)
        val scope = "${organization}:${caller.workspaceId}:${request.path.value}"
        if (request.path.value.startsWith("workspace/lineage/")) {
            val kind = resourceKind(request.path.value)
            val cursor = request.cursor?.let { decodeCursor { AgentManagementCursor.decodeLineage(scope, kind, it) } }
            return@listing when (val result = lineage.listResources(caller.workspaceId, kind, "", cursor, request.limit, caller.presentedCredential, caller.expectedOAuthResource)) {
                is LineageResourceInventoryResult.Found -> AgentListingOutcome.Found(AgentResourceListingResponse(result.page.items.map {
                    AgentResourceListingItem.LineageResource(path("${request.path.value}/${it.ref.id}"), it.ref.managementRef(), it.label, it.address) },
                    result.page.nextCursor?.let { AgentManagementCursor.encodeLineage(scope, it) }, emptyList()))
                LineageResourceInventoryResult.InvalidCursor -> throw ManagementRequestInvalidException()
                LineageResourceInventoryResult.Unavailable -> AgentListingOutcome.Unavailable
            }
        }
        val items = if (request.path.value == "workspace/members") members.list(WorkspaceMembersUseCase.Command(actor, workspace)).map {
            AgentResourceListingItem.WorkspaceMember(path("workspace/members/${it.user.userId}"), it.user.userId.value, it.user.displayName, it.role)
        } else candidates.list(WorkspaceMemberCandidatesUseCase.Command(actor, workspace)).users.map {
            AgentResourceListingItem.WorkspaceMemberCandidate(path("workspace/member-candidates/${it.user.userId}"), it.user.userId.value, it.user.displayName)
        }
        val operations = if (membership.role == cc.monomer.identity.api.WorkspaceRole.OWNER) listOf(AgentAvailableOperation.Invoke(path("workspace/members"), "add-member", "WORKSPACE_MEMBER_ADD")) else emptyList()
        paginate(scope, request, items, operations)
    }

    override suspend fun read(caller: AgentWorkspaceCaller, request: AgentReadRequest): AgentResourceOutcome = resource(null) {
        if (!AgentManagementPaths.workspace(request.path.value) || !AgentManagementPaths.readable(request.path.value)) return@resource AgentResourceOutcome.NotFound
        val (identity, organization, membership) = verify(caller)
        val actor = UserId(identity.userId.value)
        val workspace = WorkspaceId(caller.workspaceId.value)
        val currentPath = request.path.value
        val envelope = when {
            currentPath == "workspace/access" -> {
                AgentResourceEnvelope.WorkspaceAccess(request.path,
                    AgentWorkspaceAccessBody(membership.organizationId.value, membership.role, AgentRevision(membership.authorizationRevision.value),
                        reportingTimeZones.reportingTimeZone(caller.workspaceId)),
                    if (membership.role == cc.monomer.identity.api.WorkspaceRole.OWNER) listOf(
                        AgentAvailableOperation.List(path("workspace/members")),
                        AgentAvailableOperation.List(path("workspace/member-candidates")),
                        AgentAvailableOperation.Read(path("workspace/authorization")),
                    ) else emptyList())
            }
            currentPath == "workspace/authorization" -> AgentResourceEnvelope.WorkspaceAuthorization(request.path,
                authorization.read(WorkspaceAuthorizationAssignmentsUseCase.Command(actor, workspace)).managementBody(), emptyList())
            AgentManagementPaths.workspaceMember.matches(currentPath) -> {
                val member = members.list(WorkspaceMembersUseCase.Command(actor, workspace)).singleOrNull { it.user.userId.value == id(currentPath) } ?: missing()
                AgentResourceEnvelope.WorkspaceMember(request.path, member.managementBody(), if (membership.role == cc.monomer.identity.api.WorkspaceRole.OWNER) listOf(AgentAvailableOperation.Invoke(request.path, "remove-member", null), AgentAvailableOperation.Invoke(request.path, "set-role", "WORKSPACE_MEMBER_ROLE")) else emptyList())
            }
            AgentManagementPaths.memberCandidate.matches(currentPath) -> {
                val candidate = candidates.list(WorkspaceMemberCandidatesUseCase.Command(actor, workspace)).users.singleOrNull { it.user.userId.value == id(currentPath) } ?: missing()
                AgentResourceEnvelope.WorkspaceMember(request.path, candidate.user.managementCandidate(), listOf(AgentAvailableOperation.Invoke(path("workspace/members"), "add-member", "WORKSPACE_MEMBER_ADD")))
            }
            else -> return@resource when (val result = lineage.read(caller.workspaceId, cc.monomer.authority.api.ResourceRef(resourceKind(currentPath), id(currentPath)), caller.presentedCredential, caller.expectedOAuthResource)) {
                is LineageGraphQueryResult.Found -> AgentResourceOutcome.Found(AgentResourceEnvelope.LineageResource(request.path, result.graph.managementBody(), emptyList()))
                is LineageGraphQueryResult.InvalidLineage -> if (result.reason in setOf(cc.monomer.authority.api.CurrentResourceFocusConeDenial.RESOURCE_MISSING, cc.monomer.authority.api.CurrentResourceFocusConeDenial.CROSS_WORKSPACE_RESOURCE)) AgentResourceOutcome.NotFound else AgentResourceOutcome.Unavailable
                LineageGraphQueryResult.Unavailable -> AgentResourceOutcome.Unavailable
            }
        }
        AgentResourceOutcome.Found(envelope)
    }

    override suspend fun invoke(caller: AgentWorkspaceCaller, request: AgentInvokeRequest): AgentActionOutcome = action(null) {
        if (!AgentManagementPaths.workspace(request.path.value) || !AgentManagementPaths.invocation(request)) return@action AgentActionOutcome.NotFound
        val (identity, organization, membership) = verify(caller)
        val actor = UserId(identity.userId.value)
        val workspace = WorkspaceId(caller.workspaceId.value)
        val audit = GovernanceAudit(caller.requestId.value, caller.expectedOAuthResource.name, identity.clientId, organization)
        val input = request.input
        if (request.action == "add-member" || request.action == "remove-member") {
            val target = if (input is AgentInvokeInput.WorkspaceMemberAdd) input.userId else id(request.path.value)
            if (candidates.list(WorkspaceMemberCandidatesUseCase.Command(actor, workspace)).users.none { it.user.userId.value == target }) missing()
            if (request.action == "add-member") try {
                addMember.add(AddWorkspaceMemberUseCase.Command(actor, workspace, UserId(target), audit, (input as AgentInvokeInput.WorkspaceMemberAdd).role))
            } catch (conflict: WorkspaceMemberRoleConflictException) {
                return@action AgentActionOutcome.Rejected(conflict.problem().copy(nextActions = listOf(AgentAvailableOperation.Invoke(path("workspace/members/$target"), "set-role", "WORKSPACE_MEMBER_ROLE"))))
            } catch (_: InvalidWorkspaceRoleTargetException) {
                return@action AgentActionOutcome.Rejected(managementProblem(
                    "ASSIGNMENT_TARGET_UNAVAILABLE", "Assignment target unavailable", "Select a member of this Organization.", AgentWorkspaceProblemStatus.CONFLICT,
                ))
            }
            else if (!removeMember.remove(RemoveWorkspaceMemberUseCase.Command(actor, workspace, UserId(target), audit))) return@action AgentActionOutcome.NotFound
            return@action if (request.action == "add-member") completed(request, AgentResourceKind.WORKSPACE_MEMBER, "workspace/members/$target") else completed(request, AgentResourceKind.COLLECTION, "workspace/members")
        }
        when (input) {
            is AgentInvokeInput.WorkspaceMemberRole -> {
                setMemberRole.set(SetWorkspaceMemberRoleUseCase.Command(actor, workspace, UserId(id(request.path.value)), input.role, audit))
                return@action completed(request, AgentResourceKind.WORKSPACE_MEMBER, request.path.value)
            }
            else -> return@action AgentActionOutcome.NotFound
        }
    }

    private suspend fun verify(caller: AgentOrganizationCaller): Pair<OAuthAgentIdentity, MeUseCase.Response> {
        val identity = (oauth.validateCredential(caller.presentedCredential, caller.expectedOAuthResource) as? OAuthOutcome.Success)?.value ?: unauthenticated()
        if (identity.userId.value.toString() != caller.actorPrincipalId || identity.clientId != caller.clientId) unauthenticated()
        val response = me.me(MeUseCase.Command(UserId(identity.userId.value)))
        if (response.organizationMemberships.none {
            it.organization.id.value == caller.organizationId.value
        }) missing()
        return identity to response
    }
    private suspend fun verify(caller: AgentWorkspaceCaller): Triple<OAuthAgentIdentity, OrganizationId, cc.monomer.identity.api.IdentityMembershipEvidence> {
        val identity = (oauth.validateCredential(caller.presentedCredential, caller.expectedOAuthResource) as? OAuthOutcome.Success)?.value ?: unauthenticated()
        if (identity.userId.value.toString() != caller.actorPrincipalId) unauthenticated()
        val member = admission.authorizeWorkspaceMembership(WorkspaceMembershipAdmissionRequest(caller.workspaceId, caller.presentedCredential, caller.expectedOAuthResource))
        if (member !is ControlAdmissionDecision.Admitted || member.context.actor.id != identity.userId) missing()
        return Triple(identity, OrganizationId(member.context.organizationId.value), member.context)
    }
    private fun audit(caller: AgentOrganizationCaller, identity: OAuthAgentIdentity) = GovernanceAudit(caller.requestId.value, caller.expectedOAuthResource.name, identity.clientId, OrganizationId(caller.organizationId.value))
    private suspend fun managedWorkspace(actor: UserId, organization: OrganizationId, path: String): WorkspaceId =
        managedWorkspaces.list(ManagedWorkspacesUseCase.Command(actor, organization)).singleOrNull { it.id.value == Uuid.parse(path.split('/')[2]) }?.id ?: missing()
    private fun OrganizationMember.listing() = AgentResourceListingItem.OrganizationMember(path("organization/members/${user.userId}"), user.userId.value, user.displayName, AgentOrganizationRole.valueOf(role.name))
    private fun paginate(scope: String, request: AgentListRequest, items: List<AgentResourceListingItem>, operations: List<AgentAvailableOperation>): AgentListingOutcome {
        val after = request.cursor?.let { decodeCursor { AgentManagementCursor.decode(scope, it).also { require(it.size == 1); Uuid.parse(it.single()) }.single() } }
        fun key(item: AgentResourceListingItem) = if (request.path.value == "organization/workspaces") item.path.value.split('/')[2] else item.path.value.substringAfterLast('/')
        val sorted = items.sortedBy(::key).filter { after == null || key(it) > after }
        val page = sorted.take(request.limit)
        return AgentListingOutcome.Found(AgentResourceListingResponse(page, if (sorted.size > request.limit) AgentManagementCursor.encode(scope, listOf(key(page.last()))) else null, operations))
    }
    private inline fun <T> decodeCursor(decode: () -> T): T = try {
        decode()
    } catch (_: IllegalArgumentException) {
        throw ManagementRequestInvalidException()
    }
    private fun invitationResult(result: OrganizationInvitationService.Result, requestedPath: AgentResourcePath, action: String?): AgentActionOutcome {
        val resultPath = result.invitation?.let { path("organization/invitations/${it.invitation.id}") }
        return when (result.outcome) {
            OrganizationInvitationService.Outcome.SENT -> {
                val reference = AgentActionResultReference(AgentResourceKind.ORGANIZATION_INVITATION, checkNotNull(resultPath))
                AgentActionOutcome.Completed(if (action == null) AgentActionResult.Create(requestedPath, reference) else AgentActionResult.Invoke(requestedPath, action, AgentActionStatus.COMPLETED, reference))
            }
            OrganizationInvitationService.Outcome.CANCELLED -> AgentActionOutcome.Completed(AgentActionResult.Invoke(requestedPath, checkNotNull(action), AgentActionStatus.COMPLETED, AgentActionResultReference(AgentResourceKind.COLLECTION, path("organization/invitations"))))
            OrganizationInvitationService.Outcome.UNAVAILABLE -> AgentActionOutcome.NotFound
            else -> {
                val status = when (result.outcome) {
                    OrganizationInvitationService.Outcome.DELIVERY_FAILED -> AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE
                    OrganizationInvitationService.Outcome.RATE_LIMITED -> AgentWorkspaceProblemStatus.TOO_MANY_REQUESTS
                    OrganizationInvitationService.Outcome.INVALID_EMAIL, OrganizationInvitationService.Outcome.INVALID_WORKSPACE_ACCESS -> AgentWorkspaceProblemStatus.BAD_REQUEST
                    else -> AgentWorkspaceProblemStatus.CONFLICT
                }
                val detail = when (result.outcome) {
                    OrganizationInvitationService.Outcome.DELIVERY_FAILED -> "Invitation saved; email was not sent."
                    OrganizationInvitationService.Outcome.INVALID_WORKSPACE_ACCESS -> "Each Workspace must belong to this Organization and appear once, with at most ${OrganizationInvitationService.MAX_WORKSPACE_ACCESS} Workspaces."
                    else -> "Check the current invitations and organization members before retrying this action."
                }
                AgentActionOutcome.Rejected(AgentWorkspaceProblem(
                    code = result.outcome.name,
                    title = "Invitation action rejected",
                    detail = detail,
                    status = status,
                    retryable = false,
                    currentRevision = null,
                    violations = emptyList(),
                    hints = result.retryAfter?.let { listOf("Retry after $it seconds.") } ?: emptyList(),
                    nextActions = if (resultPath != null) listOf(AgentAvailableOperation.Read(resultPath)) else listOf(AgentAvailableOperation.List(path("organization/invitations"))),
                    retryAfterSeconds = result.retryAfter?.toLong(),
                    roleRequirement = null,
                    partitionFilters = emptyList(),
                ))
            }
        }
    }
    private fun completed(request: AgentInvokeRequest, kind: AgentResourceKind, resultPath: String) = AgentActionOutcome.Completed(AgentActionResult.Invoke(request.path, request.action, AgentActionStatus.COMPLETED, AgentActionResultReference(kind, path(resultPath))))
    private fun resourceKind(path: String) = when (path.split('/')[2]) {
        "relations" -> cc.monomer.authority.api.ResourceKind.RELATION
        "semantic-models" -> cc.monomer.authority.api.ResourceKind.SEMANTIC_MODEL
        "metrics" -> cc.monomer.authority.api.ResourceKind.METRIC
        else -> throw IllegalArgumentException()
    }
    private fun path(value: String) = AgentResourcePath.parse(value)
    private fun id(path: String) = Uuid.parse(path.substringAfterLast('/'))
    private fun missing(): Nothing = throw ScopedResourceNotFoundException("Management resource unavailable")
    private fun unauthenticated(): Nothing = throw ManagementAuthenticationException()
    private suspend fun listing(organization: AgentOrganizationCaller?, block: suspend () -> AgentListingOutcome): AgentListingOutcome = try { block() } catch (failure: Exception) { AgentListingOutcome.Rejected(failure.problem(organization)) }
    private suspend fun resource(organization: AgentOrganizationCaller?, block: suspend () -> AgentResourceOutcome): AgentResourceOutcome = try { block() } catch (failure: Exception) { AgentResourceOutcome.Rejected(failure.problem(organization)) }
    private suspend fun action(organization: AgentOrganizationCaller?, block: suspend () -> AgentActionOutcome): AgentActionOutcome = try { block() } catch (failure: Exception) { AgentActionOutcome.Rejected(failure.problem(organization)) }

    /**
     * An Organization Member denied an Admin-only action learns the role to ask for. Every other denial, including
     * Admin-or-Owner checks and any Admin's denial, keeps the generic answer so it adds no new signal.
     */
    private suspend fun Exception.problem(organization: AgentOrganizationCaller?): AgentWorkspaceProblem {
        if (this !is cc.monomer.identity.application.OrganizationAdminRequiredException || organization == null) return problem()
        val response = try {
            me.me(MeUseCase.Command(UserId(Uuid.parse(organization.actorPrincipalId))))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return problem()
        }
        val membership = response.organizationMemberships.singleOrNull { it.organization.id.value == organization.organizationId.value }
            ?: return problem()
        if (membership.role.name != "MEMBER") return problem()
        return organizationAdminRequiredProblem(response.user.displayName, membership.organization.displayName)
    }

    private fun Exception.problem(): AgentWorkspaceProblem = when (this) {
        is ManagementAuthenticationException -> managementProblem("AUTHENTICATION_REQUIRED", "Authentication required", "Reconnect using the current Organization and requested Agent surface.", AgentWorkspaceProblemStatus.UNAUTHORIZED)
        is AccessDeniedException -> managementProblem("ROLE_REQUIRED", "Management authority required", "This action requires the current Organization Admin or Workspace Owner authority.", AgentWorkspaceProblemStatus.FORBIDDEN)
        is ScopedResourceNotFoundException -> managementProblem("NOT_FOUND", "Resource unavailable", "Select a current resource in the authorized scope.", AgentWorkspaceProblemStatus.NOT_FOUND)
        is cc.monomer.identity.application.workspace.WorkspaceMemberRoleConflictException -> managementProblem("WORKSPACE_MEMBER_ROLE_CONFLICT", "Member role conflict", "This member already has a different role. Read the member and use set-role to change it.", AgentWorkspaceProblemStatus.CONFLICT)
        is LastWorkspaceOwnerException -> managementProblem("LAST_WORKSPACE_OWNER", "Last Owner must remain", "Another personal Workspace Owner is required before removing or demoting this member.", AgentWorkspaceProblemStatus.CONFLICT)
        is InvalidWorkspaceRoleTargetException -> managementProblem("ASSIGNMENT_TARGET_UNAVAILABLE", "Assignment target unavailable", "Select a direct Workspace user in this Organization.", AgentWorkspaceProblemStatus.CONFLICT)
        is FreeOrganizationLimitException -> managementProblem("ORGANIZATION_LIMIT_REACHED", "Organization limit reached", "Check the current Organization membership and invitation limits.", AgentWorkspaceProblemStatus.CONFLICT)
        is ManagementRequestInvalidException -> managementProblem("AGENT_REQUEST_INVALID", "Invalid management input", "Check the input and use a cursor from this collection and scope.", AgentWorkspaceProblemStatus.BAD_REQUEST)
        else -> throw this
    }

    private fun managementProblem(code: String, title: String, detail: String, status: AgentWorkspaceProblemStatus) = AgentWorkspaceProblem(
        code = code,
        title = title,
        detail = detail,
        status = status,
        retryable = false,
        currentRevision = null,
        violations = emptyList(),
        hints = emptyList(),
        nextActions = emptyList(),
        retryAfterSeconds = null,
        roleRequirement = null,
        partitionFilters = emptyList(),
    )
}

private class ManagementAuthenticationException : RuntimeException()
private class ManagementRequestInvalidException : RuntimeException()
