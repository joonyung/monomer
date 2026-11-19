@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package cc.monomer.agent.application

import cc.monomer.agent.api.*
import cc.monomer.identity.api.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.uuid.Uuid

class AgentManagementDiscoveryTest {
    @Test fun `workspace root reuses owner filtered discovery and paginates current readable lists`() = runTest {
        val workspaceId = WorkspaceId(Uuid.generateV7())
        val organizationId = OrganizationId(Uuid.generateV7())
        val admission = object : AgentWorkspaceAdmissionPort {
            override suspend fun decide(request: AgentWorkspaceAdmissionRequest) = AgentWorkspaceAdmissionDecision.Authorized(AgentWorkspaceRoleEvidence(OrganizationPlan.FREE,CanonicalPrincipal(PrincipalId(Uuid.generateV7()),PrincipalKind.USER),organizationId,workspaceId,AuthorizationRevision(1),CredentialKind.AGENT_OAUTH,CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"),WorkspaceRole.OWNER,request.intent))
        }
        val caller = assertIs<AgentWorkspaceCallerAuthenticationOutcome.Authenticated>(AgentWorkspaceCallerAuthenticator(admission).authenticate(workspaceId,PresentedCredential("fixture"),AgentWorkspaceAdmissionAudience.REST,AgentReadRequest(AgentResourcePath.parse("workspace"),null),AgentRequestId("srv_discovery"))).caller
        var owner = false
        val management = proxy<AgentManagementOwnerPort> { name ->
            check(name == "read")
            val operations = mutableListOf<AgentAvailableOperation>(AgentAvailableOperation.List(AgentResourcePath.parse("workspace/members")),AgentAvailableOperation.List(AgentResourcePath.parse("workspace/lineage/relations")))
            if(owner) operations += AgentAvailableOperation.Read(AgentResourcePath.parse("workspace/authorization"))
            AgentResourceOutcome.Found(AgentResourceEnvelope.WorkspaceAccess(AgentResourcePath.parse("workspace/access"),AgentWorkspaceAccessBody(organizationId.value,WorkspaceRole.OWNER,AgentRevision(1),java.time.ZoneId.of("UTC")),operations))
        }
        val registry = AgentResourceRouteRegistry(unused(),unused(),unused(),unused(),unused(),null,listOf(AgentResourcePath.parse("workspace/metrics")),unused(),management)
        val root = AgentResourcePath.parse("workspace")
        val read = assertIs<AgentResourceOutcome.Found>(registry.read(caller,AgentReadRequest(root,null))).resource
        assertTrue(read.availableOperations.any { it.path.value == "workspace/access" })
        assertFalse(read.availableOperations.any { it.path.value == "workspace/authorization" })
        owner=true
        assertTrue(assertIs<AgentResourceOutcome.Found>(registry.read(caller,AgentReadRequest(root,null))).resource.availableOperations.any { it.path.value == "workspace/authorization" })
        val first = assertIs<AgentListingOutcome.Found>(registry.list(caller,AgentListRequest(root,null,1))).listing
        val next = assertIs<AgentListingOutcome.Found>(registry.list(caller,AgentListRequest(root,first.nextCursor,100))).listing
        assertEquals(listOf("workspace/lineage/metrics","workspace/lineage/relations","workspace/lineage/semantic-models","workspace/members","workspace/metrics"),(first.items+next.items).map { it.path.value })
    }
    @Test fun `root uses current access role and propagates membership revocation`() = runTest {
        val workspace = WorkspaceId(Uuid.generateV7())
        val organization = OrganizationId(Uuid.generateV7())
        val admission = object : AgentWorkspaceAdmissionPort {
            override suspend fun decide(request: AgentWorkspaceAdmissionRequest) = AgentWorkspaceAdmissionDecision.Authorized(
                AgentWorkspaceRoleEvidence(OrganizationPlan.FREE, CanonicalPrincipal(PrincipalId(Uuid.generateV7()), PrincipalKind.USER),
                    organization, workspace, AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), WorkspaceRole.OWNER, request.intent))
        }
        val root = AgentResourcePath.parse("workspace")
        val caller = assertIs<AgentWorkspaceCallerAuthenticationOutcome.Authenticated>(AgentWorkspaceCallerAuthenticator(admission)
            .authenticate(workspace, PresentedCredential("fixture"), AgentWorkspaceAdmissionAudience.REST, AgentReadRequest(root, null), AgentRequestId("srv_role"))).caller
        var current: AgentResourceOutcome = AgentResourceOutcome.Found(AgentResourceEnvelope.WorkspaceAccess(
            AgentResourcePath.parse("workspace/access"), AgentWorkspaceAccessBody(organization.value, WorkspaceRole.VIEWER, AgentRevision(2), java.time.ZoneId.of("UTC")), emptyList()))
        val management = proxy<AgentManagementOwnerPort> { current }
        val registry = AgentResourceRouteRegistry(unused(), unused(), unused(), unused(), unused(), unused(),
            listOf(AgentResourcePath.parse("workspace/metrics")), unused(), management)
        val result = assertIs<AgentResourceEnvelope.Workspace>(assertIs<AgentResourceOutcome.Found>(registry.read(caller, AgentReadRequest(root, null))).resource)
        assertEquals(WorkspaceRole.VIEWER, result.body.role)
        assertEquals(setOf("workspace/pages", "workspace/pages/tree", "workspace/access"), result.availableOperations.map { it.path.value }.toSet())
        for (role in listOf(WorkspaceRole.EXPLORER, WorkspaceRole.EDITOR)) {
            current = AgentResourceOutcome.Found(AgentResourceEnvelope.WorkspaceAccess(
                AgentResourcePath.parse("workspace/access"), AgentWorkspaceAccessBody(organization.value, role, AgentRevision(3), java.time.ZoneId.of("UTC")), emptyList()))
            val explorer = assertIs<AgentResourceEnvelope.Workspace>(assertIs<AgentResourceOutcome.Found>(registry.read(caller, AgentReadRequest(root, null))).resource)
            assertEquals(role, explorer.body.role)
            assertEquals(setOf("workspace/metrics", "workspace/access", "workspace/lineage/relations", "workspace/lineage/semantic-models", "workspace/lineage/metrics"),
                explorer.availableOperations.map { it.path.value }.toSet())
        }
        current = AgentResourceOutcome.NotFound
        assertEquals(AgentResourceOutcome.NotFound, registry.read(caller, AgentReadRequest(root, null)))
        assertEquals(AgentListingOutcome.NotFound, registry.list(caller, AgentListRequest(root, null, 100)))
    }
    private inline fun <reified T> unused(): T = proxy { error("Unrelated owner called") }
    private inline fun <reified T> proxy(crossinline call: (String)->Any): T = Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)) { _,method,_->call(method.name) } as T
}
