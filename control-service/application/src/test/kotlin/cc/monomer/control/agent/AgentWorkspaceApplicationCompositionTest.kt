@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.agent

import cc.monomer.control.WorkspaceRoleAdmissionRequest
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionDenial
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.ControlAdmissionStage
import cc.monomer.control.publicapi.ControlPublicApiProperties
import cc.monomer.identity.api.OrganizationId
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.AuthorizationRevision
import cc.monomer.identity.api.CanonicalPrincipal
import cc.monomer.identity.api.CredentialKind
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.IdentityAdmissionDenial
import cc.monomer.identity.api.IdentityRoleEvidence
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.PrincipalKind
import io.mockk.coEvery
import io.mockk.mockk
import java.net.URI
import kotlin.uuid.Uuid
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse

class AgentWorkspaceApplicationCompositionTest {
    @Test
    fun `registered routes authenticate and fail closed when owner adapters are unavailable`() {
        val capabilityRequests = mutableListOf<WorkspaceRoleAdmissionRequest>()
        val admissionFacade: ControlAdmissionFacade = mockk()
        val actor = CanonicalPrincipal(PrincipalId(Uuid.generateV7()), PrincipalKind.USER)
        coEvery { admissionFacade.authorizeWorkspaceRole(any()) } answers {
            val request = firstArg<WorkspaceRoleAdmissionRequest>()
            capabilityRequests += request
            if (request.credential.value != "valid-agent-token") {
                ControlAdmissionDecision.Denied(
                    ControlAdmissionStage.IDENTITY,
                    ControlAdmissionDenial.Identity(IdentityAdmissionDenial.CREDENTIAL_UNAVAILABLE),
                )
            } else {
                ControlAdmissionDecision.Admitted(
                    IdentityRoleEvidence(
                        actor = actor,
                        organizationId = OrganizationId.generate(),
                        organizationPlan = cc.monomer.identity.api.OrganizationPlan.FREE,
                        workspaceId = request.workspaceId,
                        authorizationRevision = AuthorizationRevision(1),
                        credentialKind = CredentialKind.AGENT_OAUTH,
                        credentialSurface = CredentialSurface.AGENT,
                        agentClientId = cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"),
                        role = WorkspaceRole.OWNER,

                    ),
                )
            }
        }

        val context = AnnotationConfigApplicationContext()
        context.register(AgentWorkspaceApplicationComposition::class.java)
        context.beanFactory.registerSingleton("agentOAuthProperties", cc.monomer.control.auth.inbound.agentoauth.AgentOAuthProperties("http://127.0.0.1:11077", "http://127.0.0.1:11077/mcp"))
        context.beanFactory.registerSingleton("controlPublicApiProperties", ControlPublicApiProperties(URI("http://localhost:3000")))
        context.beanFactory.registerSingleton("controlAdmissionFacade", admissionFacade)
        context.addBeanFactoryPostProcessor { factory -> factory.registerSingleton("agentManagementOwnerPort", mockk<cc.monomer.agent.api.AgentManagementOwnerPort> {
            coEvery { read(any<cc.monomer.agent.api.AgentWorkspaceCaller>(), any()) } returns cc.monomer.agent.api.AgentResourceOutcome.Unavailable
        }) }
        context.beanFactory.registerSingleton("oauth", mockk<cc.monomer.identity.api.AgentOAuthPort> {
            coEvery { validateCredential(cc.monomer.identity.api.PresentedCredential("valid-agent-token"), cc.monomer.identity.api.ExpectedOAuthResource.AGENT_REST) } returns
                cc.monomer.identity.api.OAuthOutcome.Success(cc.monomer.identity.api.OAuthAgentIdentity(actor.id, "https://claude.ai/oauth/mcp-oauth-client-metadata"))
            coEvery { validateCredential(cc.monomer.identity.api.PresentedCredential("invalid-agent-token"), cc.monomer.identity.api.ExpectedOAuthResource.AGENT_REST) } returns
                cc.monomer.identity.api.OAuthOutcome.Failure(cc.monomer.identity.api.OAuthError.INVALID_GRANT)
        })
        context.beanFactory.registerSingleton("workspaceAccessService", mockk<cc.monomer.identity.application.permission.WorkspaceAccessService>())
        context.beanFactory.registerSingleton("principalDisplayNames", mockk<cc.monomer.identity.api.PrincipalDisplayNames>())
        context.beanFactory.registerSingleton("agentAppDisplays", mockk<cc.monomer.identity.api.AgentAppDisplays>())
        context.refresh()
        try {
            @Suppress("UNCHECKED_CAST")
            val routes = context.getBean("agentWorkspacePublicApiRoutes") as RouterFunction<ServerResponse>
            val client = WebTestClient.bindToRouterFunction(routes).build()
            val workspaceId = "018f1c9c-8000-7000-8000-000000000001"

            client.post()
                .uri("/v2/workspaces/$workspaceId/agent/workspace/read")
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isNotFound

            client.post()
                .uri("/v2/workspaces/$workspaceId/agent/read")
                .header("Content-Type", "application/json")
                .bodyValue("{\"path\":\"workspace\"}")
                .exchange()
                .expectStatus().isUnauthorized

            client.post()
                .uri("/v2/workspaces/$workspaceId/agent/read")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer invalid-agent-token")
                .bodyValue("{\"path\":\"workspace\"}")
                .exchange()
                .expectStatus().isUnauthorized

            listOf("list", "read").forEach { operation ->
                client.post()
                    .uri("/v2/workspaces/$workspaceId/agent/$operation")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer valid-agent-token")
                    .bodyValue(if (operation == "list") "{\"path\":\"workspace\",\"limit\":100}" else "{\"path\":\"workspace\"}")
                    .exchange().expectStatus().isEqualTo(503).expectBody()
                    .jsonPath("$.code").isEqualTo("OWNER_UNAVAILABLE")
            }

            supportedRequests.forEach { request ->
                client.post()
                    .uri("/v2/workspaces/$workspaceId/agent/${request.operation}")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer valid-agent-token")
                    .bodyValue(request.body)
                    .exchange()
                    .expectStatus().isEqualTo(request.expectedStatus)
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(request.expectedCode)
            }

            assertThat(capabilityRequests).noneMatch { it.credential.value == "invalid-agent-token" }
            assertThat(capabilityRequests.map(WorkspaceRoleAdmissionRequest::requiredRole))
                .contains(WorkspaceRole.EXPLORER)
        } finally {
            context.close()
        }
    }

    private data class SupportedRequest(
        val operation: String,
        val body: String,
        val expectedStatus: Int,
        val expectedCode: String,
    )

    private companion object {
        val supportedRequests = listOf(
            SupportedRequest("search", "{\"path\":\"workspace\",\"queries\":[\"orders\"],\"limitPerQuery\":1}", 503, "OWNER_UNAVAILABLE"),
            SupportedRequest("create", "{\"path\":\"workspace/dashboards\",\"input\":{\"kind\":\"DASHBOARD_DEFINITION\",\"definition\":{\"schemaVersion\":1,\"meta\":{\"title\":\"Orders\"},\"parameters\":{},\"variables\":{},\"queries\":{},\"widgets\":{},\"layout\":{\"sections\":[]}}}}", 503, "OWNER_UNAVAILABLE"),
            SupportedRequest("edit", "{\"path\":\"workspace/dashboards/01942b04-6e00-7000-8000-000000000010/draft\",\"revision\":\"1\",\"patch\":[{\"op\":\"replace\",\"path\":\"/definition\",\"value\":{}}]}", 503, "OWNER_UNAVAILABLE"),
            SupportedRequest("invoke", "{\"path\":\"workspace/dashboards/01942b04-6e00-7000-8000-000000000010/draft/query-runs/01942b04-6e00-7000-8000-000000000011\",\"action\":\"cancel\"}", 503, "OWNER_UNAVAILABLE"),
        )
    }
}
