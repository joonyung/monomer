@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package cc.monomer.agent.application

import cc.monomer.agent.api.*
import cc.monomer.identity.api.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.uuid.Uuid

class AgentWorkspaceAdmissionBoundaryTest {
    private val id = "01942b04-6e00-7000-8000-000000000010"
    private val session = "workspace/pages/$id/sessions/$id"
    @Test fun `REST and MCP enforce the same cumulative role matrix`() = runTest {
        val reads = mapOf(
            "workspace" to WorkspaceRole.VIEWER,
            "workspace/access" to WorkspaceRole.VIEWER,
            "workspace/lineage/relations/$id" to WorkspaceRole.EXPLORER,
            "workspace/lineage/semantic-models/$id" to WorkspaceRole.EXPLORER,
            "workspace/lineage/metrics/$id" to WorkspaceRole.EXPLORER,
            "workspace/pages/tree" to WorkspaceRole.VIEWER,
            "workspace/pages/$id" to WorkspaceRole.VIEWER,
            "workspace/pages/$id/content" to WorkspaceRole.VIEWER,
            session to WorkspaceRole.VIEWER,
            "$session/query-runs/$id" to WorkspaceRole.VIEWER,
            "$session/query-runs/$id/result" to WorkspaceRole.VIEWER,
            "workspace/dashboards/$id" to WorkspaceRole.EXPLORER,
            "workspace/dashboards/$id/sessions/$id" to WorkspaceRole.EXPLORER,
            "workspace/explore/runs/$id" to WorkspaceRole.EXPLORER,
            "workspace/pages/$id/versions/1" to WorkspaceRole.EXPLORER,
            "workspace/semantic-models/orders" to WorkspaceRole.EXPLORER,
            "workspace/authorization" to WorkspaceRole.OWNER,
        )
        for (audience in AgentWorkspaceAdmissionAudience.entries) for (role in WorkspaceRole.entries) for ((path, required) in reads) {
            val workspace = WorkspaceId(Uuid.generateV7())
            val port = object : AgentWorkspaceAdmissionPort {
                override suspend fun decide(request: AgentWorkspaceAdmissionRequest): AgentWorkspaceAdmissionDecision {
                    assertEquals(required, request.intent.requiredRole, path)
                    assertEquals(audience.expectedOAuthResource, request.expectedOAuthResource)
                    return AgentWorkspaceAdmissionDecision.Authorized(evidence(request, role))
                }
            }
            val result = AgentWorkspaceCallerAuthenticator(port).authenticate(workspace, PresentedCredential("fixture"), audience,
                AgentReadRequest(AgentResourcePath.parse(path), null), AgentRequestId("srv_test"))
            if (role.includes(required)) assertIs<AgentWorkspaceCallerAuthenticationOutcome.Authenticated>(result, "$role $path")
            else assertEquals(AgentWorkspaceCallerAuthenticationOutcome.EvidenceMismatch, result, "$role $path")
        }
    }
    @Test fun `REST and MCP admit Viewer Page searches while keeping other searches Explorer only`() = runTest {
        val requests = listOf(
            AgentSearchRequest(AgentResourcePath.parse("workspace/pages"), listOf("Reports"), null, 20) to WorkspaceRole.VIEWER,
            AgentSearchRequest(AgentResourcePath.parse("workspace"), listOf("Reports"), setOf(AgentResourceKind.PAGE), 20) to WorkspaceRole.VIEWER,
            AgentSearchRequest(AgentResourcePath.parse("workspace"), listOf("Reports"), null, 20) to WorkspaceRole.EXPLORER,
            AgentSearchRequest(AgentResourcePath.parse("workspace"), listOf("Reports"), setOf(AgentResourceKind.PAGE, AgentResourceKind.METRIC), 20) to WorkspaceRole.EXPLORER,
            AgentSearchRequest(AgentResourcePath.parse("workspace/connectors/$id"), listOf("Reports"), setOf(AgentResourceKind.PAGE), 20) to WorkspaceRole.EXPLORER,
        )
        for (audience in AgentWorkspaceAdmissionAudience.entries) for (role in WorkspaceRole.entries) for ((input, required) in requests) {
            val workspace = WorkspaceId(Uuid.generateV7())
            val port = object : AgentWorkspaceAdmissionPort {
                override suspend fun decide(request: AgentWorkspaceAdmissionRequest): AgentWorkspaceAdmissionDecision {
                    assertEquals(required, request.intent.requiredRole)
                    assertEquals(input.kinds, (request.intent as AgentWorkspaceAdmissionIntent.Search).kinds)
                    return AgentWorkspaceAdmissionDecision.Authorized(evidence(request, role))
                }
            }
            val result = AgentWorkspaceCallerAuthenticator(port).authenticate(workspace, PresentedCredential("fixture"), audience,
                input, AgentRequestId("srv_test"))
            if (role.includes(required)) assertIs<AgentWorkspaceCallerAuthenticationOutcome.Authenticated>(result)
            else assertEquals(AgentWorkspaceCallerAuthenticationOutcome.EvidenceMismatch, result)
        }
    }
    @Test fun `Viewer resolve only operates on existing Page sessions and no other mutation`() {
        val resolve = AgentInvokeRequest(AgentResourcePath.parse(session), "resolve", AgentInvokeInput.DashboardSessionResolve(emptyMap()))
        assertEquals(WorkspaceRole.VIEWER, assertNotNull(AgentWorkspaceAdmissionIntents.invoke(resolve)).requiredRole)
        assertNull(AgentWorkspaceAdmissionIntents.invoke(resolve.copy(action = "refresh", input = AgentInvokeInput.DashboardSessionRefresh(emptyMap(), setOf("query")))))
        assertNull(AgentWorkspaceAdmissionIntents.invoke(resolve.copy(path = AgentResourcePath.parse("workspace/pages/$id/sessions"), action = "open", input = AgentInvokeInput.DashboardSessionOpen(emptyMap()))))
        assertEquals(WorkspaceRole.EDITOR, AgentWorkspaceAdmissionIntent.CatalogEdit(AgentResourcePath.parse("workspace/connectors/$id/catalog/selection")).requiredRole)
        assertEquals(WorkspaceRole.EXPLORER, AgentWorkspaceAdmissionIntent.CatalogInvoke(AgentResourcePath.parse("workspace/connectors/$id/catalog"), "ingest").requiredRole)
        assertEquals(WorkspaceRole.OWNER, AgentWorkspaceAdmissionIntent.ConnectorCreate(AgentResourcePath.parse("workspace/connectors")).requiredRole)
        assertEquals(WorkspaceRole.EDITOR, AgentWorkspaceAdmissionIntent.MetricEdit(AgentResourcePath.parse("workspace/metrics/revenue")).requiredRole)
    }
    @Test fun `undeclared resources do not issue admission`() = runTest {
        val port = object : AgentWorkspaceAdmissionPort { override suspend fun decide(request: AgentWorkspaceAdmissionRequest): AgentWorkspaceAdmissionDecision = error("Admission must not run") }
        for (path in listOf("workspace/data-access/relations/$id", "workspace/pages/$id/attachments", "$session/events", "$session/query-runs/$id/cancel", "workspace/dashboards/not-a-uuid")) {
            assertEquals(AgentWorkspaceCallerAuthenticationOutcome.InvalidRequest, AgentWorkspaceCallerAuthenticator(port).authenticate(WorkspaceId(Uuid.generateV7()), PresentedCredential("fixture"), AgentWorkspaceAdmissionAudience.REST, AgentReadRequest(AgentResourcePath.parse(path), null), AgentRequestId("srv_test")))
        }
    }
    private fun evidence(request: AgentWorkspaceAdmissionRequest, role: WorkspaceRole) = AgentWorkspaceRoleEvidence(OrganizationPlan.FREE,
        CanonicalPrincipal(PrincipalId(Uuid.generateV7()), PrincipalKind.USER), OrganizationId(Uuid.generateV7()), request.workspaceId,
        AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), role, request.intent)
}
