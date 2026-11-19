package cc.monomer.control.sample

import cc.monomer.control.auth.inbound.oauth.JwtAuthentication
import cc.monomer.identity.api.*
import cc.monomer.identity.domain.model.UserCredentialProvenance
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono

class SampleWorkspaceControllerTest {
    private val provisioning = mockk<SampleWorkspaceProvisioning>()
    private val actor = PrincipalId.generate()
    private val organization = OrganizationId.generate()
    private val sample = SampleWorkspace(WorkspaceId.generate(), organization, "Acme Cloud (sample)", Instant.parse("2026-10-12T12:00:00Z"),
        WorkspaceSampleMarker("acme-cloud@2", WorkspaceSampleState.PROVISIONING, actor))
    private val path = "/v2/organizations/$organization/sample-workspace"
    private fun client(authenticated: Boolean = true): WebTestClient {
        val authentication = JwtAuthentication(UserCredentialProvenance.BrowserSession(UserId(actor.value), SessionId.generate()), PresentedCredential("test-session"))
        return WebTestClient.bindToController(SampleWorkspaceController(provisioning)).controllerAdvice(SampleWorkspaceProblemHandler())
            .webFilter<WebTestClient.ControllerSpec>(org.springframework.web.server.WebFilter { exchange, chain -> chain.filter(if (authenticated) exchange.mutate().principal(Mono.just(authentication)).build() else exchange) }).build()
    }

    @Test
    fun `post immediate response and get use exact canonical sample contract`() {
        coEvery { provisioning.start(actor, organization) } returns sample
        coEvery { provisioning.read(actor, organization) } returns sample
        val client = client()
        val response = client.post().uri(path).exchange().expectStatus().isAccepted.expectHeader().valueEquals("Cache-Control", "no-store")
            .expectBody().jsonPath("$.workspaceId").isEqualTo(sample.workspaceId.toString())
            .jsonPath("$.sample.state").isEqualTo("PROVISIONING").jsonPath("$.sample.initiator").isEqualTo(actor.toString()).returnResult().responseBody!!
        client.get().uri(path).exchange().expectStatus().isOk.expectBody().jsonPath("$.sample.dataset").isEqualTo("acme-cloud@2")
        val mapper = ObjectMapper()
        val contract = mapper.readTree(javaClass.classLoader.getResourceAsStream("contracts/control-service.openapi.json"))
        val schema = mapper.createObjectNode().apply {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            put("\$ref", "#/components/schemas/SampleWorkspaceResponse")
            set<com.fasterxml.jackson.databind.JsonNode>("components", contract.required("components"))
        }
        kotlin.test.assertTrue(com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
            .getSchema(schema).validate(mapper.readTree(response)).isEmpty())
    }

    @Test
    fun `ready post opens same workspace and absent get returns not found`() {
        coEvery { provisioning.start(actor, organization) } returns sample.copy(sample = sample.sample.copy(state = WorkspaceSampleState.READY))
        client().post().uri(path).exchange().expectStatus().isOk.expectBody().jsonPath("$.sample.state").isEqualTo("READY")
        coEvery { provisioning.read(actor, organization) } returns null
        client().get().uri(path).exchange().expectStatus().isNotFound.expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND")
    }

    @Test
    fun `authentication precedes id parsing and admin quota failures preserve existing semantics`() {
        client(false).post().uri("/v2/organizations/not-an-id/sample-workspace").exchange().expectStatus().isUnauthorized
            .expectBody().jsonPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED")
        coVerify(exactly = 0) { provisioning.start(actor, organization) }
        client().get().uri("/v2/organizations/${organization.toString().uppercase()}/sample-workspace").exchange().expectStatus().isBadRequest
        coEvery { provisioning.start(actor, organization) } throws SampleWorkspaceAdminRequiredException()
        client().post().uri(path).exchange().expectStatus().isForbidden.expectBody().jsonPath("$.code").isEqualTo("ROLE_REQUIRED")
        coEvery { provisioning.start(actor, organization) } throws SampleWorkspaceLimitExceededException()
        client().post().uri(path).exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("WORKSPACE_LIMIT_REACHED")
        coEvery { provisioning.read(actor, organization) } throws SampleWorkspaceAdminRequiredException()
        client().get().uri(path).exchange().expectStatus().isForbidden
    }
}
