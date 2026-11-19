@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package cc.monomer.control.agent

import cc.monomer.agent.api.*
import cc.monomer.authority.api.*
import cc.monomer.control.*
import cc.monomer.identity.api.*
import cc.monomer.identity.application.AdminAuthorization
import cc.monomer.identity.application.organization.*
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.application.grant.*
import cc.monomer.identity.application.me.MeUseCase
import cc.monomer.identity.application.workspace.*
import cc.monomer.identity.domain.model.*
import cc.monomer.identity.domain.port.*
import io.mockk.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import cc.monomer.identity.domain.id.OrganizationId as OwnerOrganizationId
import cc.monomer.identity.domain.id.UserId as OwnerUserId
import cc.monomer.identity.domain.id.WorkspaceId as OwnerWorkspaceId
import cc.monomer.identity.api.OrganizationId
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.PrincipalKind
import cc.monomer.identity.api.AuthorizationRevision
import cc.monomer.identity.api.WorkspaceId

class AgentManagementOwnerAdapterTest {
    private val now = Instant.parse("2026-09-12T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val organization = OrganizationId.generate()
    private val workspace = WorkspaceId.generate()
    private val actor = PrincipalId.generate()
    private val ownerOrganization = OwnerOrganizationId(organization.value)
    private val ownerActor = OwnerUserId(actor.value)
    private val ownerWorkspace = OwnerWorkspaceId(workspace.value)
    private val credential = PresentedCredential("test-credential")
    private val oauth = mockk<AgentOAuthPort>()
    private val admission = mockk<ControlAdmissionFacade>()
    private val me = mockk<MeUseCase>()
    private val candidatesPort = mockk<WorkspaceMemberCandidatesQueryPort>()
    private val membersPort = mockk<WorkspaceMembersQueryPort>()
    private val assignmentsPort = mockk<WorkspaceAuthorizationAssignmentsQueryPort>()
    private val assignments = mockk<AuthorizationAssignmentRepository>()
    private val memberRepository = mockk<WorkspaceMemberRepository>()
    private val auditRepository = mockk<AuditLogRepository>(relaxed = true)
    private val invitationRepository = mockk<OrganizationInvitationRepository>()
    private val organizations = mockk<OrganizationRepository>()
    private val delivery = mockk<InvitationDelivery>()
    private val transactions = object : TransactionRunner {
        override suspend fun <T> run(action: () -> T) = action()
        override suspend fun <T> readOnly(action: () -> T) = action()
    }
    private val snapshotTransactions = object : ConsistentSnapshotTransactionRunner {
        override suspend fun <T> run(action: suspend () -> T) = action()
    }
    private val auditFactory = AuditEventFactory(clock)
    private val mutationRunner = OwnerWorkspaceMutationRunner(transactions, assignments)
    private val invitationService = OrganizationInvitationService(transactions, invitationRepository, organizations, memberRepository, AdminAuthorization(organizations), auditRepository, auditFactory, delivery, clock)
    private val managedWorkspaces = mockk<ManagedWorkspacesUseCase>()
    private val reportingTimeZones = mockk<WorkspaceReportingTimeZoneQuery>()
    private val adapter = AgentManagementOwnerAdapter(oauth, admission, me, managedWorkspaces, OrganizationMembersService(mockk()), invitationService,
        WorkspaceMembersService(membersPort), WorkspaceMemberCandidatesService(candidatesPort), WorkspaceAuthorizationAssignmentsService(assignmentsPort),
        AddWorkspaceMemberService(mutationRunner, assignments, memberRepository, auditRepository, auditFactory, clock),
        RemoveWorkspaceMemberService(mutationRunner, memberRepository, auditRepository, auditFactory),
        SetWorkspaceMemberRoleService(mutationRunner, assignments, auditRepository, auditFactory, memberRepository, clock),
        reportingTimeZones, mockk(), clock)
    private val caller = mockk<AgentWorkspaceCaller>()

    init {
        coEvery { oauth.validateCredential(credential, ExpectedOAuthResource.AGENT_REST) } returns OAuthOutcome.Success(OAuthAgentIdentity(actor, "https://client.example"))
        every { caller.workspaceId } returns workspace
        every { caller.actorPrincipalId } returns actor.value.toString()
        every { caller.presentedCredential } returns credential
        every { caller.expectedOAuthResource } returns ExpectedOAuthResource.AGENT_REST
        every { caller.requestId } returns AgentRequestId("request-management")
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Admitted(IdentityMembershipEvidence(
            CanonicalPrincipal(actor, PrincipalKind.USER), organization, workspace, AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), cc.monomer.identity.api.WorkspaceRole.OWNER))
    }

    @Test
    fun `access reports every current member role and only Owner management operations`() = runTest {
        coEvery { reportingTimeZones.reportingTimeZone(workspace) } returns java.time.ZoneId.of("Asia/Seoul")
        for (role in cc.monomer.identity.api.WorkspaceRole.entries) {
            coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Admitted(IdentityMembershipEvidence(
                CanonicalPrincipal(actor, PrincipalKind.USER), organization, workspace, AuthorizationRevision(2), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), role))
            val result = assertIs<AgentResourceEnvelope.WorkspaceAccess>(assertIs<AgentResourceOutcome.Found>(
                adapter.read(caller, AgentReadRequest(path("workspace/access"), null))).resource)
            assertEquals(role, result.body.role)
            assertEquals(AgentRevision(2), result.body.authorizationRevision)
            assertEquals(java.time.ZoneId.of("Asia/Seoul"), result.body.reportingTimeZone)
            assertEquals(if (role == cc.monomer.identity.api.WorkspaceRole.OWNER)
                setOf("workspace/members", "workspace/member-candidates", "workspace/authorization") else emptySet(),
                result.availableOperations.map { it.path.value }.toSet())
        }
        coVerify(exactly = 4) { admission.authorizeWorkspaceMembership(any()) }
        coVerify(exactly = 0) { membersPort.readWorkspaceMembers(any(), any()) }
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Denied(mockk(), mockk())
        assertEquals(AgentWorkspaceProblemStatus.NOT_FOUND,
            assertIs<AgentResourceOutcome.Rejected>(adapter.read(caller, AgentReadRequest(path("workspace/access"), null))).problem.status)
    }

    @Test
    fun `member listing carries each direct role while candidate listing stays role free`() = runTest {
        val viewer = OwnerUserId.generate()
        coEvery { membersPort.readWorkspaceMembers(ownerActor, ownerWorkspace) } returns WorkspaceMembersQueryResult.Authorized(snapshot(), listOf(
            WorkspaceMember(User(ownerActor, "owner@example.com", "Owner"), cc.monomer.identity.api.WorkspaceRole.OWNER),
            WorkspaceMember(User(viewer, "viewer@example.com", "Viewer"), cc.monomer.identity.api.WorkspaceRole.VIEWER)))
        val request = AgentListRequest(path("workspace/members"), null, 20)
        val listing = assertIs<AgentListingOutcome.Found>(adapter.list(caller, request)).listing
        assertTrue(AgentWorkspaceResponseRelations.matches(request, listing))
        assertEquals(mapOf(ownerActor.value to cc.monomer.identity.api.WorkspaceRole.OWNER, viewer.value to cc.monomer.identity.api.WorkspaceRole.VIEWER),
            listing.items.map { assertIs<AgentResourceListingItem.WorkspaceMember>(it) }.associate { it.userId to it.role })

        val candidate = OwnerUserId.generate()
        candidates(candidate)
        val candidateListing = assertIs<AgentListingOutcome.Found>(adapter.list(caller, AgentListRequest(path("workspace/member-candidates"), null, 20))).listing
        assertEquals(listOf(AgentResourceListingItem.WorkspaceMemberCandidate(path("workspace/member-candidates/$candidate"), candidate.value, "Member")), candidateListing.items)
    }

    @Test
    fun `owner authorization is rechecked in real mutation service after discovery`() = runTest {
        candidates(ownerActor)
        every { assignments.actorIsOwnerAfterLock(ownerActor, ownerWorkspace) } returns false
        val result = assertIs<AgentActionOutcome.Rejected>(adapter.invoke(caller, add(ownerActor)))
        assertEquals("ROLE_REQUIRED", result.problem.code)
        verify(exactly = 0) { memberRepository.insert(any()) }
    }

    @Test
    fun `add with a different role returns conflict and same role remains a no-op`() = runTest {
        candidates(ownerActor)
        every { assignments.actorIsOwnerAfterLock(ownerActor, ownerWorkspace) } returns true
        every { assignments.isEligibleMemberRoleTarget(ownerWorkspace, ownerActor, false) } returns true
        every { memberRepository.insert(any()) } returns WorkspaceMemberInsertion.AlreadyMember(
            WorkspaceMembership(ownerWorkspace, ownerActor, ownerActor, now, cc.monomer.identity.api.WorkspaceRole.VIEWER),
        )
        val conflict = assertIs<AgentActionOutcome.Rejected>(adapter.invoke(caller, add(ownerActor))).problem
        assertEquals(AgentWorkspaceProblemStatus.CONFLICT, conflict.status)
        assertTrue(conflict.detail.contains("set-role"))
        assertEquals(listOf(AgentAvailableOperation.Invoke(path("workspace/members/$ownerActor"), "set-role", "WORKSPACE_MEMBER_ROLE")), conflict.nextActions)
        every { memberRepository.insert(any()) } returns WorkspaceMemberInsertion.AlreadyMember(
            WorkspaceMembership(ownerWorkspace, ownerActor, ownerActor, now, cc.monomer.identity.api.WorkspaceRole.EXPLORER),
        )
        assertIs<AgentActionOutcome.Completed>(adapter.invoke(caller, add(ownerActor)))
        verify(exactly = 0) { auditRepository.insert(any()) }
    }

    @Test
    fun `self member removal returns success without readback and subsequent admission is lost`() = runTest {
        candidates(ownerActor)
        every { assignments.actorIsOrganizationAdminAfterLock(ownerActor, ownerWorkspace) } returns false
        every { assignments.actorIsOwnerAfterLock(ownerActor, ownerWorkspace) } returns true
        every { memberRepository.delete(ownerWorkspace, ownerActor) } returns WorkspaceMemberRemoval.Removed
        val result = assertIs<AgentActionOutcome.Completed>(adapter.invoke(caller, remove(ownerActor)))
        assertEquals("workspace/members", (result.action as AgentActionResult.Invoke).result.path.value)
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Denied(mockk(), mockk())
        assertEquals(AgentWorkspaceProblemStatus.NOT_FOUND, assertIs<AgentListingOutcome.Rejected>(adapter.list(caller, AgentListRequest(path("workspace/members"), null, 20))).problem.status)
        verify(exactly = 1) { memberRepository.delete(ownerWorkspace, ownerActor) }
        coVerify(exactly = 0) { membersPort.readWorkspaceMembers(any(), any()) }
    }

    @Test
    fun `removing an organization member who is not in the workspace reports not found`() = runTest {
        val target = OwnerUserId.generate()
        candidates(target)
        every { assignments.actorIsOrganizationAdminAfterLock(ownerActor, ownerWorkspace) } returns false
        every { assignments.actorIsOwnerAfterLock(ownerActor, ownerWorkspace) } returns true
        every { memberRepository.delete(ownerWorkspace, target) } returns WorkspaceMemberRemoval.NotFound
        assertEquals(AgentActionOutcome.NotFound, adapter.invoke(caller, remove(target)))
        verify(exactly = 1) { memberRepository.delete(ownerWorkspace, target) }
    }

    @Test
    fun `last owner rejection is preserved without reporting a successful removal`() = runTest {
        candidates(ownerActor)
        every { assignments.actorIsOrganizationAdminAfterLock(ownerActor, ownerWorkspace) } returns false
        every { assignments.actorIsOwnerAfterLock(ownerActor, ownerWorkspace) } returns true
        every { memberRepository.delete(ownerWorkspace, ownerActor) } throws LastWorkspaceOwnerException()
        assertEquals("LAST_WORKSPACE_OWNER", assertIs<AgentActionOutcome.Rejected>(adapter.invoke(caller, remove(ownerActor))).problem.code)
        verify(exactly = 0) { auditRepository.insert(any()) }
    }

    @Test
    fun `member add passes verified request client surface and organization into owner audit`() = runTest {
        candidates(ownerActor)
        every { assignments.actorIsOwnerAfterLock(ownerActor, ownerWorkspace) } returns true
        every { assignments.isEligibleMemberRoleTarget(ownerWorkspace, ownerActor, false) } returns true
        every { memberRepository.insert(any()) } returns WorkspaceMemberInsertion.Inserted
        val outcome = assertIs<AgentActionOutcome.Completed>(adapter.invoke(caller, add(ownerActor)))
        assertTrue(AgentWorkspaceResponseRelations.matches(add(ownerActor), outcome.action))
        verify { auditRepository.insert(match { it.requestId == "request-management" && it.actorRef == "https://client.example" && it.details.contains("AGENT_REST") && it.organizationId == ownerOrganization }) }
    }

    @Test
    fun `unavailable member role target preserves conflict mapping and emits no audit`() = runTest {
        every { assignments.actorIsOrganizationAdminAfterLock(ownerActor, ownerWorkspace) } returns false
        every { assignments.actorIsOwnerAfterLock(ownerActor, ownerWorkspace) } returns true
        every { assignments.isEligibleMemberRoleTarget(ownerWorkspace, ownerActor, true) } returns false
        val request = AgentInvokeRequest(path("workspace/members/$ownerActor"), "set-role", AgentInvokeInput.WorkspaceMemberRole(cc.monomer.identity.api.WorkspaceRole.EDITOR))
        val problem = assertIs<AgentActionOutcome.Rejected>(adapter.invoke(caller, request)).problem
        assertEquals("ASSIGNMENT_TARGET_UNAVAILABLE", problem.code)
        assertEquals(AgentWorkspaceProblemStatus.CONFLICT, problem.status)
        assertEquals("Select a direct Workspace user in this Organization.", problem.detail)
        assertFalse(problem.retryable)
        verify(exactly = 0) { auditRepository.insert(any()); memberRepository.insert(any()); assignments.setMemberRole(any(), any(), any()) }
    }

    @Test
    fun `member add of a user outside the organization maps to the unavailable target conflict without writes`() = runTest {
        candidates(ownerActor)
        every { assignments.actorIsOwnerAfterLock(ownerActor, ownerWorkspace) } returns true
        every { assignments.isEligibleMemberRoleTarget(ownerWorkspace, ownerActor, false) } returns false
        val problem = assertIs<AgentActionOutcome.Rejected>(adapter.invoke(caller, add(ownerActor))).problem
        assertEquals("ASSIGNMENT_TARGET_UNAVAILABLE", problem.code)
        assertEquals(AgentWorkspaceProblemStatus.CONFLICT, problem.status)
        assertEquals("Select a member of this Organization.", problem.detail)
        assertFalse(problem.retryable)
        verify(exactly = 0) { auditRepository.insert(any()); memberRepository.insert(any()) }
    }

    @Test
    fun `wrong audience rejects before reading or mutating owner data`() = runTest {
        coEvery { oauth.validateCredential(credential, ExpectedOAuthResource.AGENT_REST) } returns OAuthOutcome.Failure(OAuthError.INVALID_TARGET)
        assertEquals(AgentWorkspaceProblemStatus.UNAUTHORIZED, assertIs<AgentActionOutcome.Rejected>(adapter.invoke(caller, add(ownerActor))).problem.status)
        coVerify(exactly = 0) { candidatesPort.readWorkspaceMemberCandidates(any(), any()) }
    }

    @Test
    fun `organization root works for administrator without any workspace admission`() = runTest {
        val caller = organizationCaller()
        coEvery { me.me(MeUseCase.Command(ownerActor)) } returns MeUseCase.Response(User(ownerActor, "admin@example.com", "Admin"), listOf(
            MeUseCase.OrganizationMembershipView(Organization(ownerOrganization, "Team", null, OrganizationPlan.FREE, now), OrganizationRole.ADMIN)))
        val body = assertIs<AgentResourceEnvelope.Organization>(assertIs<AgentResourceOutcome.Found>(adapter.read(caller, AgentReadRequest(path("organization"), null))).resource)
        assertTrue(body.availableOperations.any { it is AgentAvailableOperation.Create && it.path.value == "organization/invitations" })
        coEvery { me.me(MeUseCase.Command(ownerActor)) } returns MeUseCase.Response(User(ownerActor, "admin@example.com", "Admin"), listOf(
            MeUseCase.OrganizationMembershipView(Organization(ownerOrganization, "Team", null, OrganizationPlan.FREE, now), OrganizationRole.MEMBER)))
        val memberBody = assertIs<AgentResourceEnvelope.Organization>(assertIs<AgentResourceOutcome.Found>(adapter.read(caller, AgentReadRequest(path("organization"), null))).resource)
        assertTrue(memberBody.availableOperations.isEmpty())
        coVerify(exactly = 2) { me.me(MeUseCase.Command(ownerActor)) }
        coVerify(exactly = 0) { admission.authorizeWorkspaceMembership(any()) }
    }

    @Test
    fun `an Organization Member denied an Admin action learns the role to ask for without seeing the Admin roster`() = runTest {
        val caller = organizationCaller()
        coEvery { managedWorkspaces.list(ManagedWorkspacesUseCase.Command(ownerActor, ownerOrganization)) } throws
            cc.monomer.identity.application.OrganizationAdminRequiredException("Admin required")
        coEvery { me.me(MeUseCase.Command(ownerActor)) } returns MeUseCase.Response(User(ownerActor, "sam@example.com", "Sam Kim"), listOf(
            MeUseCase.OrganizationMembershipView(Organization(ownerOrganization, "Northstar", null, OrganizationPlan.FREE, now), OrganizationRole.MEMBER)))
        val member = assertIs<AgentListingOutcome.Rejected>(adapter.list(caller, AgentListRequest(path("organization/workspaces"), null, 20))).problem
        assertEquals("ROLE_REQUIRED", member.code)
        assertEquals(AgentWorkspaceProblemStatus.FORBIDDEN, member.status)
        assertEquals(AgentRoleRequirement("ADMIN", "MEMBER", emptyList()), member.roleRequirement)
        assertEquals("This Organization action in Northstar needs the Admin role. Sam Kim is a Member.", member.detail)
        assertEquals(listOf("Ask an Organization Admin to give Sam Kim the Admin role, then try again."), member.hints)

        coEvery { me.me(MeUseCase.Command(ownerActor)) } returns MeUseCase.Response(User(ownerActor, "sam@example.com", "Sam Kim"), listOf(
            MeUseCase.OrganizationMembershipView(Organization(ownerOrganization, "Northstar", null, OrganizationPlan.FREE, now), OrganizationRole.ADMIN)))
        val admin = assertIs<AgentListingOutcome.Rejected>(adapter.list(caller, AgentListRequest(path("organization/workspaces"), null, 20))).problem
        assertEquals("ROLE_REQUIRED", admin.code)
        assertNull(admin.roleRequirement)

        coEvery { me.me(MeUseCase.Command(ownerActor)) } returns MeUseCase.Response(User(ownerActor, "sam@example.com", "Sam Kim"), listOf(
            MeUseCase.OrganizationMembershipView(Organization(ownerOrganization, "Northstar", null, OrganizationPlan.FREE, now), OrganizationRole.MEMBER)))
        coEvery { managedWorkspaces.list(ManagedWorkspacesUseCase.Command(ownerActor, ownerOrganization)) } throws
            cc.monomer.identity.application.AccessDeniedException("user must be an Organization Admin or Workspace Owner")
        val ownerSufficient = assertIs<AgentListingOutcome.Rejected>(adapter.list(caller, AgentListRequest(path("organization/workspaces"), null, 20))).problem
        assertEquals("ROLE_REQUIRED", ownerSufficient.code)
        assertNull(ownerSufficient.roleRequirement)
    }

    @Test
    fun `Organization membership removal blocks the next request in the same OAuth session`() = runTest {
        val caller = organizationCaller()
        coEvery { me.me(MeUseCase.Command(ownerActor)) } returns MeUseCase.Response(User(ownerActor, "admin@example.com", "Admin"), listOf(
            MeUseCase.OrganizationMembershipView(Organization(ownerOrganization, "Team", null, OrganizationPlan.FREE, now), OrganizationRole.ADMIN)))
        assertIs<AgentResourceOutcome.Found>(adapter.read(caller, AgentReadRequest(path("organization"), null)))
        coEvery { me.me(MeUseCase.Command(ownerActor)) } returns MeUseCase.Response(User(ownerActor, "admin@example.com", "Admin"), emptyList())
        assertEquals(AgentWorkspaceProblemStatus.NOT_FOUND,
            assertIs<AgentResourceOutcome.Rejected>(adapter.read(caller, AgentReadRequest(path("organization"), null))).problem.status)
        coVerify(exactly = 2) { me.me(MeUseCase.Command(ownerActor)) }
        coVerify(exactly = 0) { admission.authorizeWorkspaceMembership(any()) }
    }

    @Test
    fun `delivery failure persists real invitation and returns safe exact read without token`() = runTest {
        val caller = organizationCaller()
        coEvery { me.me(MeUseCase.Command(ownerActor)) } returns MeUseCase.Response(User(ownerActor, "admin@example.com", "Admin"), listOf(
            MeUseCase.OrganizationMembershipView(Organization(ownerOrganization, "Team", null, OrganizationPlan.FREE, now), OrganizationRole.ADMIN)))
        every { organizations.lockAndFindMembership(ownerOrganization, ownerActor) } returns OrganizationMembership(ownerOrganization, ownerActor, OrganizationRole.ADMIN, now)
        every { invitationRepository.exists(ownerOrganization) } returns true
        every { invitationRepository.membershipExistsByEmail(ownerOrganization, any()) } returns false
        every { invitationRepository.findByEmail(ownerOrganization, any()) } returns null
        every { invitationRepository.requirePendingCapacity(ownerOrganization, null, now) } just Runs
        every { invitationRepository.lockOrganization(ownerOrganization) } returns true
        var saved: OrganizationMembershipInvitation? = null
        every { invitationRepository.insert(any()) } answers { saved = firstArg() }
        every { invitationRepository.find(ownerOrganization, any()) } answers { saved?.let { InvitationPreview(it, "Team", null, emptyList()) } }
        every { invitationRepository.list(ownerOrganization) } answers { listOfNotNull(saved?.let { InvitationPreview(it, "Team", null, emptyList()) }) }
        coEvery { delivery.send(any(), any(), any(), any(), any()) } returns false
        val problem = assertIs<AgentActionOutcome.Rejected>(adapter.create(caller, AgentCreateRequest(path("organization/invitations"), AgentCreateInput.OrganizationInvitation("colleague@example.com", emptyList())))).problem
        assertEquals("DELIVERY_FAILED", problem.code)
        assertFalse(problem.retryable)
        assertEquals("Invitation saved; email was not sent.", problem.detail)
        val read = problem.nextActions.single() as AgentAvailableOperation.Read
        val body = assertIs<AgentResourceEnvelope.OrganizationInvitation>(assertIs<AgentResourceOutcome.Found>(adapter.read(caller, AgentReadRequest(read.path, null))).resource).body
        assertEquals(saved!!.id, body.id)
        assertNull(body.lastSentAt)
        assertFalse(body.toString().contains(saved!!.tokenHash))
        coVerify(exactly = 1) { delivery.send(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { admission.authorizeWorkspaceMembership(any()) }
    }

    @Test
    fun `organization admin removes a workspace member through the shared Admin or Owner rule`() = runTest {
        val caller = organizationCaller()
        val target = OwnerUserId.generate()
        coEvery { me.me(MeUseCase.Command(ownerActor)) } returns MeUseCase.Response(User(ownerActor, "admin@example.com", "Admin"), listOf(
            MeUseCase.OrganizationMembershipView(Organization(ownerOrganization, "Team", null, OrganizationPlan.FREE, now), OrganizationRole.ADMIN)))
        coEvery { managedWorkspaces.list(ManagedWorkspacesUseCase.Command(ownerActor, ownerOrganization)) } returns listOf(
            Workspace(ownerWorkspace, ownerOrganization, "Workspace", now, false, ReportingTimeZone.UTC, null))
        every { assignments.actorIsOrganizationAdminAfterLock(ownerActor, ownerWorkspace) } returns true
        every { memberRepository.delete(ownerWorkspace, target) } returns WorkspaceMemberRemoval.Removed
        val request = AgentInvokeRequest(path("organization/workspaces/$workspace/members/$target"), "remove-member", AgentInvokeInput.None)
        val outcome = assertIs<AgentActionOutcome.Completed>(adapter.invoke(caller, request))
        assertTrue(AgentWorkspaceResponseRelations.matches(request, outcome.action))
        assertEquals("organization/workspaces/$workspace/members", (outcome.action as AgentActionResult.Invoke).result.path.value)
        verify { auditRepository.insert(match { it.action == "workspace.member.remove" && it.actorRef == "https://client.example" }) }
        verify(exactly = 0) { assignments.actorIsOwnerAfterLock(any(), any()) }

        every { memberRepository.delete(ownerWorkspace, target) } throws LastWorkspaceOwnerException()
        assertEquals("LAST_WORKSPACE_OWNER", assertIs<AgentActionOutcome.Rejected>(adapter.invoke(caller, request)).problem.code)

        every { assignments.actorIsOrganizationAdminAfterLock(ownerActor, ownerWorkspace) } returns false
        every { assignments.actorIsOwnerAfterLock(ownerActor, ownerWorkspace) } returns false
        assertEquals("ROLE_REQUIRED", assertIs<AgentActionOutcome.Rejected>(adapter.invoke(caller, request)).problem.code)

        coEvery { managedWorkspaces.list(ManagedWorkspacesUseCase.Command(ownerActor, ownerOrganization)) } returns emptyList()
        assertEquals(AgentWorkspaceProblemStatus.NOT_FOUND, assertIs<AgentActionOutcome.Rejected>(adapter.invoke(caller, request)).problem.status)
        verify(exactly = 2) { memberRepository.delete(ownerWorkspace, target) }
    }

    private suspend fun organizationCaller() = (AgentOrganizationCaller.authenticate(organization, oauth, credential, AgentWorkspaceAdmissionAudience.REST, AgentRequestId("request-management")) as OAuthOutcome.Success).value
    private fun candidates(target: OwnerUserId) {
        coEvery { candidatesPort.readWorkspaceMemberCandidates(ownerActor, ownerWorkspace) } returns WorkspaceMemberCandidatesQueryResult.Authorized(snapshot(), listOf(OrganizationMember(User(target, "member@example.com", "Member"), OrganizationRole.MEMBER, now, emptyList())))
    }
    private fun snapshot() = WorkspaceActorAuthorizationSnapshot(ownerActor, ownerWorkspace, ownerOrganization, cc.monomer.identity.domain.model.AuthorizationRevision(1), OrganizationRole.MEMBER,
        cc.monomer.identity.api.WorkspaceRole.OWNER)
    private fun add(user: OwnerUserId) = AgentInvokeRequest(path("workspace/members"), "add-member", AgentInvokeInput.WorkspaceMemberAdd(user.value, cc.monomer.identity.api.WorkspaceRole.EXPLORER))
    private fun remove(user: OwnerUserId) = AgentInvokeRequest(path("workspace/members/$user"), "remove-member", AgentInvokeInput.None)
    private fun path(value: String) = AgentResourcePath.parse(value)
}
