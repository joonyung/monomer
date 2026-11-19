@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package cc.monomer.control.publicapi

import cc.monomer.agent.api.*
import cc.monomer.agent.application.AgentWorkspaceContractDecoder
import cc.monomer.identity.api.*
import io.mockk.*
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.ServerRequest

class AgentOrganizationPublicApiRouterTest {
    private val organizationId = OrganizationId(Uuid.parse("01942b04-6e00-7000-8000-000000000001"))
    private val userId = PrincipalId(Uuid.parse("01942b04-6e00-7000-8000-000000000002"))
    private val oauth = mockk<AgentOAuthPort>()
    private val owner = mockk<AgentManagementOwnerPort>()
    private val workspace = mockk<AgentWorkspaceHandler>()
    private val authenticator = mockk<AgentWorkspaceCallerAuthenticationPort>()
    private val router = AgentWorkspacePublicApiRouter(
        AgentWorkspaceHttpPorts(authenticator, workspace, AgentWorkspaceContractDecoder()),
        "https://api.example.test/.well-known/oauth-protected-resource/v2/workspaces",
        ControlPublicApiProperties(URI("https://app.example.test")),
        object : AgentWorkspaceTransportCredentialResolver {
            override suspend fun resolve(request: ServerRequest) = if (request.headers().firstHeader("Authorization") == "Bearer rest-token")
                AgentWorkspaceTransportCredentialResolution.Resolved(PresentedCredential("rest-token")) else AgentWorkspaceTransportCredentialResolution.Missing
        },
        PublicControlContractValidator(),
        oauth,
    )
    private val client = WebTestClient.bindToRouterFunction(router.routes().and(router.organizationRoutes(oauth, owner))).webFilter<WebTestClient.RouterFunctionSpec>(cc.monomer.control.observability.RequestCorrelationTestSupport.filter()).build()

    @Test
    fun `Organization target rejects uppercase and compact UUID forms before owner execution`() {
        coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_REST) } returns OAuthOutcome.Success(OAuthAgentIdentity(userId, "client-test"))
        val canonical = organizationId.value.toString()
        for (invalid in listOf(canonical.uppercase(), canonical.replace("-", ""))) {
            post("/v2/agent/organizations/$invalid/read", """{"path":"organization"}""").expectStatus().isBadRequest
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_PATH_PARAMETER")
        }
        coVerify { owner wasNot Called; authenticator wasNot Called; workspace wasNot Called }
    }

    @Test
    fun `Organization owner IllegalArgumentException is a sanitized internal failure`() {
        coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_REST) } returns OAuthOutcome.Success(OAuthAgentIdentity(userId, "client-test"))
        coEvery { owner.read(any<AgentOrganizationCaller>(), any()) } throws IllegalArgumentException("sensitive-owner-detail")
        post("/v2/agent/organizations/${organizationId.value}/read", """{"path":"organization"}""")
            .expectStatus().isEqualTo(500).expectHeader().doesNotExist("Retry-After").expectBody()
            .jsonPath("$.code").isEqualTo("INTERNAL_ERROR").jsonPath("$.retryable").isEqualTo(false)
            .consumeWith { kotlin.test.assertFalse(String(it.responseBody!!).contains("sensitive-owner-detail")) }
    }

    @Test
    fun `Organization read authenticates REST grant without Workspace and rejects cross-scope bodies`() {
        coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_REST) } returns OAuthOutcome.Success(OAuthAgentIdentity(userId, "client-test"))
        coEvery { owner.read(any<AgentOrganizationCaller>(), any()) } coAnswers {
            assertEquals(organizationId, firstArg<AgentOrganizationCaller>().organizationId)
            AgentResourceOutcome.Found(organization(organizationId.value))
        }
        post("/v2/agent/organizations/${organizationId.value}/read", """{"path":"organization"}""").expectStatus().isOk
            .expectHeader().valueEquals("Cache-Control", "no-store").expectBody().jsonPath("$.body.id").isEqualTo(organizationId.value.toString())
            .jsonPath("$.kind").isEqualTo("ORGANIZATION").jsonPath("$.body.accountKind").doesNotExist()
            .jsonPath("$.body.caller.status").doesNotExist().jsonPath("$.body.caller.source").doesNotExist()
        for (body in listOf("""{"path":"organization","workspaceId":"${Uuid.generateV7()}"}""", """{"path":"organization","organizationId":"${Uuid.generateV7()}"}""", """{"path":"workspace"}""", """{"path":"organization","path":"organization"}""")) {
            post("/v2/agent/organizations/${organizationId.value}/read", body).expectStatus().isBadRequest
        }
        post("/v2/workspaces/${Uuid.generateV7()}/agent/read", """{"path":"organization"}""").expectStatus().isBadRequest
        coVerify(exactly = 1) { owner.read(any<AgentOrganizationCaller>(), any()) }
        coVerify { authenticator wasNot Called; workspace wasNot Called }
    }

    @Test
    fun `retired tenant routes paths and invitation input are rejected before owner admission`() {
        post("/v2/agent/accounts/${organizationId.value}/read", """{"path":"account"}""").expectStatus().isNotFound
        coVerify { oauth wasNot Called }
        coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_REST) } returns OAuthOutcome.Success(OAuthAgentIdentity(userId, "client-test"))
        post("/v2/agent/list", """{"path":"accounts"}""").expectStatus().isBadRequest
        post("/v2/agent/organizations/${organizationId.value}/read", """{"path":"account"}""").expectStatus().isBadRequest
        post("/v2/agent/organizations/${organizationId.value}/create", """{"path":"organization/invitations","input":{"kind":"ACCOUNT_INVITATION_CREATE","email":"member@example.test"}}""")
            .expectStatus().isBadRequest
        coVerify { owner wasNot Called; authenticator wasNot Called; workspace wasNot Called }
    }

    @Test
    fun `Organization member listing and read omit retired membership fields`() {
        coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_REST) } returns OAuthOutcome.Success(OAuthAgentIdentity(userId, "client-test"))
        val memberPath = AgentResourcePath.parse("organization/members/${userId.value}")
        coEvery { owner.list(any<AgentOrganizationCaller>(), any()) } returns AgentListingOutcome.Found(AgentResourceListingResponse(
            listOf(AgentResourceListingItem.OrganizationMember(memberPath, userId.value, "Member", AgentOrganizationRole.MEMBER)), null, emptyList()))
        coEvery { owner.read(any<AgentOrganizationCaller>(), any()) } returns AgentResourceOutcome.Found(AgentResourceEnvelope.OrganizationMember(
            memberPath, AgentOrganizationMemberBody(userId.value, "Member", "member@example.test", AgentOrganizationRole.MEMBER), emptyList()))
        post("/v2/agent/organizations/${organizationId.value}/list", """{"path":"organization/members","limit":20}""").expectStatus().isOk.expectBody()
            .jsonPath("$.items[0].role").isEqualTo("MEMBER").jsonPath("$.items[0].status").doesNotExist().jsonPath("$.items[0].source").doesNotExist()
        post("/v2/agent/organizations/${organizationId.value}/read", """{"path":"${memberPath.value}"}""").expectStatus().isOk.expectBody()
            .jsonPath("$.body.role").isEqualTo("MEMBER").jsonPath("$.body.status").doesNotExist().jsonPath("$.body.source").doesNotExist()
    }

    @Test
    fun `Organization member status inputs and paths fail before owner admission`() {
        coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_REST) } returns OAuthOutcome.Success(OAuthAgentIdentity(userId, "client-test"))
        post("/v2/agent/organizations/${organizationId.value}/invoke", """{"path":"organization/members/${userId.value}","action":"set-status","input":{"kind":"ORGANIZATION_MEMBER_STATUS","status":"ACTIVE"}}""").expectStatus().isBadRequest
        post("/v2/agent/organizations/${organizationId.value}/read", """{"path":"organization/members/${userId.value}/status"}""").expectStatus().isBadRequest
        coVerify { owner wasNot Called; authenticator wasNot Called; workspace wasNot Called }
    }

    @Test
    fun `Organization responses fail closed on wrong Organization and wrong audience`() {
        coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_REST) } returns OAuthOutcome.Success(OAuthAgentIdentity(userId, "client-test"))
        coEvery { owner.read(any<AgentOrganizationCaller>(), any()) } returns AgentResourceOutcome.Found(organization(Uuid.generateV7()))
        post("/v2/agent/organizations/${organizationId.value}/read", """{"path":"organization"}""").expectStatus().isEqualTo(503)
        coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_REST) } returns OAuthOutcome.Failure(OAuthError.INVALID_GRANT)
        post("/v2/agent/organizations/${organizationId.value}/read", """{"path":"organization"}""").expectStatus().isUnauthorized.expectHeader().exists("WWW-Authenticate")
    }

    @Test
    fun `invitation rate limit returns exact retry header and delivery failure is not automatically retryable`() {
        coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_REST) } returns OAuthOutcome.Success(OAuthAgentIdentity(userId, "client-test"))
        val path = "organization/invitations/${Uuid.generateV7()}"
        coEvery { owner.invoke(any<AgentOrganizationCaller>(), any()) } returns AgentActionOutcome.Rejected(
            AgentWorkspaceProblem("RATE_LIMITED", "Too Many Requests", "Wait before resending.", AgentWorkspaceProblemStatus.TOO_MANY_REQUESTS, false, currentRevision = null, violations = emptyList(), hints = emptyList(), nextActions = emptyList(), retryAfterSeconds = 61, roleRequirement = null, partitionFilters = emptyList()))
        post("/v2/agent/organizations/${organizationId.value}/invoke", """{"path":"$path","action":"resend"}""").expectStatus().isEqualTo(429).expectHeader().valueEquals("Retry-After", "61")
        coEvery { owner.invoke(any<AgentOrganizationCaller>(), any()) } returns AgentActionOutcome.Rejected(
            AgentWorkspaceProblem("DELIVERY_FAILED", "Delivery unavailable", "Invitation saved; inspect it before explicitly resending.", AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE, false, currentRevision = null, violations = emptyList(), hints = emptyList(), nextActions = emptyList(), retryAfterSeconds = null, roleRequirement = null, partitionFilters = emptyList()))
        post("/v2/agent/organizations/${organizationId.value}/invoke", """{"path":"$path","action":"resend"}""").expectStatus().isEqualTo(503).expectHeader().doesNotExist("Retry-After").expectBody().jsonPath("$.retryable").isEqualTo(false)
    }

    @Test
    fun `discovery is explicit closed shape and uses the REST audience`() {
        coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_REST) } returns OAuthOutcome.Success(OAuthAgentIdentity(userId, "client-test"))
        coEvery { oauth.listOrganizations(any(), ExpectedOAuthResource.AGENT_REST) } returns OAuthOutcome.Success(listOf(OAuthOrganization(organizationId, "Team", "ADMIN")))
        coEvery { oauth.listWorkspaces(any(), ExpectedOAuthResource.AGENT_REST) } returns OAuthOutcome.Success(emptyList())
        post("/v2/agent/list", """{"path":"organizations"}""").expectStatus().isOk.expectHeader().valueEquals("Cache-Control", "no-store")
            .expectBody().jsonPath("$.organizations[0].organizationId").isEqualTo(organizationId.value.toString())
        post("/v2/agent/list", """{"path":"workspaces"}""").expectStatus().isOk.expectBody().jsonPath("$.workspaces.length()").isEqualTo(0)
        for (body in listOf("", "{}", "null", """{"path":"organizations","organizationId":"$organizationId"}""", """{"path":"organizations","path":"workspaces"}""", """{"path":"organizations"} {}""", """{"path":"workspace"}""")) {
            post("/v2/agent/list", body).expectStatus().isBadRequest
        }
        coVerify { owner wasNot Called; authenticator wasNot Called; workspace wasNot Called }
    }

    @Test
    fun `successful Organization mutations serialize without a Workspace identifier`() {
        coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_REST) } returns OAuthOutcome.Success(OAuthAgentIdentity(userId, "client-test"))
        val collection = AgentResourcePath.parse("organization/invitations")
        val invitation = AgentResourcePath.parse("organization/invitations/${Uuid.generateV7()}")
        coEvery { owner.create(any<AgentOrganizationCaller>(), any()) } returns AgentActionOutcome.Completed(AgentActionResult.Create(collection, AgentActionResultReference(AgentResourceKind.ORGANIZATION_INVITATION, invitation)))
        post("/v2/agent/organizations/${organizationId.value}/create", """{"path":"organization/invitations","input":{"kind":"ORGANIZATION_INVITATION_CREATE","email":"member@example.test"}}""").expectStatus().isOk.expectBody().jsonPath("$.result.path").isEqualTo(invitation.value)
        coEvery { owner.invoke(any<AgentOrganizationCaller>(), any()) } returns AgentActionOutcome.Completed(AgentActionResult.Invoke(invitation, "cancel", AgentActionStatus.COMPLETED, AgentActionResultReference(AgentResourceKind.COLLECTION, collection)))
        post("/v2/agent/organizations/${organizationId.value}/invoke", """{"path":"${invitation.value}","action":"cancel"}""").expectStatus().isOk.expectBody().jsonPath("$.result.path").isEqualTo(collection.value)
    }

    private fun organization(id: Uuid) = AgentResourceEnvelope.Organization(AgentResourcePath.parse("organization"), AgentOrganizationBody(id, "Team", AgentOrganizationSelf(userId.value, "Admin", AgentOrganizationRole.ADMIN)), emptyList())
    private fun post(path: String, body: String) = client.post().uri(path).header("Authorization", "Bearer rest-token").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
}
