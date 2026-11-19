@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.agent

import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.agent.api.AgentRoleRequirement
import cc.monomer.agent.api.AgentWorkspaceAdmissionIntent
import cc.monomer.agent.api.AgentWorkspaceProblemStatus
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.identity.api.AgentOAuthPort
import cc.monomer.identity.api.AuthorizationRevision
import cc.monomer.identity.api.CanonicalPrincipal
import cc.monomer.identity.api.CredentialKind
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.IdentityMembershipEvidence
import cc.monomer.identity.api.OAuthOutcome
import cc.monomer.identity.api.OAuthWorkspace
import cc.monomer.identity.api.OrganizationId
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.PrincipalDisplayNames
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.PrincipalKind
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.application.permission.WorkspaceAccessService
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.port.WorkspaceAccessProjection
import cc.monomer.identity.domain.port.WorkspaceAccessQueryResult
import cc.monomer.identity.domain.port.WorkspaceOwnerSummary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class AgentWorkspaceRoleProblemsTest {
    private val workspace = WorkspaceId.generate()
    private val organization = OrganizationId.generate()
    private val actor = PrincipalId.generate()
    private val credential = PresentedCredential("agent-token")
    private val admission = mockk<ControlAdmissionFacade>()
    private val access = mockk<WorkspaceAccessService>()
    private val oauth = mockk<AgentOAuthPort>()
    private val names = mockk<PrincipalDisplayNames>()
    private val problems = AgentWorkspaceRoleProblems(admission, access, oauth, names)
    private val metricEdit = AgentWorkspaceAdmissionIntent.MetricEdit(AgentResourcePath.parse("workspace/metrics/revenue"))

    private fun member(role: WorkspaceRole) {
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Admitted(IdentityMembershipEvidence(
            CanonicalPrincipal(actor, PrincipalKind.USER), organization, workspace, AuthorizationRevision(1),
            CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), role,
        ))
    }

    @Test
    fun `a member lacking a role learns the required role, their role, and the Owners they already see`() = runTest {
        member(WorkspaceRole.EXPLORER)
        coEvery { access.read(UserId(actor.value), cc.monomer.identity.domain.id.WorkspaceId(workspace.value)) } returns
            WorkspaceAccessQueryResult.Authorized(mockk(), WorkspaceAccessProjection(WorkspaceRole.EXPLORER, listOf(
                WorkspaceOwnerSummary(UserId(PrincipalId.generate().value), "Mina Park"),
            )))
        coEvery { oauth.listWorkspaces(credential, ExpectedOAuthResource.AGENT_MCP) } returns OAuthOutcome.Success(listOf(
            OAuthWorkspace(workspace, organization, "Northstar", "Revenue Analytics", null),
        ))
        coEvery { names.read(setOf(actor.value)) } returns mapOf(actor.value to "Sam Kim")

        val problem = problems.explain(workspace, credential, ExpectedOAuthResource.AGENT_MCP, metricEdit)!!

        assertEquals("ROLE_REQUIRED", problem.code)
        assertEquals("Editor role required", problem.title)
        assertEquals(AgentWorkspaceProblemStatus.FORBIDDEN, problem.status)
        assertEquals("Changing a metric in Revenue Analytics needs the Editor role. Sam Kim is an Explorer.", problem.detail)
        assertEquals(AgentRoleRequirement("EDITOR", "EXPLORER", listOf("Mina Park")), problem.roleRequirement)
        assertEquals(listOf("Ask Mina Park to give Sam Kim the Editor role, then try again."), problem.hints)
    }

    @Test
    fun `no explanation leaves a non-member, a sufficient role, or a failed lookup without names`() = runTest {
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Denied(mockk(), mockk())
        assertNull(problems.explain(workspace, credential, ExpectedOAuthResource.AGENT_REST, metricEdit))
        coVerify(exactly = 0) { access.read(any(), any()) }
        coVerify(exactly = 0) { names.read(any()) }

        member(WorkspaceRole.EDITOR)
        assertNull(problems.explain(workspace, credential, ExpectedOAuthResource.AGENT_REST, metricEdit))

        member(WorkspaceRole.VIEWER)
        coEvery { access.read(any(), any()) } throws IllegalStateException("identity unavailable")
        assertNull(problems.explain(workspace, credential, ExpectedOAuthResource.AGENT_REST, metricEdit))
    }
}
