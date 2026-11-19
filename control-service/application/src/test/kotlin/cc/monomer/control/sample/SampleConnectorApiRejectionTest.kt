@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.sample

import cc.monomer.agent.api.*
import cc.monomer.agent.application.AgentResourceRouteRegistry
import cc.monomer.agent.application.AgentWorkspaceCallerAuthenticator
import cc.monomer.agent.application.AgentWorkspaceContractDecoder
import cc.monomer.connector.api.*
import cc.monomer.connector.application.ConnectorAuthorityService
import cc.monomer.connector.application.ConnectorMutationKeyLockPort
import cc.monomer.connector.application.ConnectorRepository
import cc.monomer.connector.application.ConnectorTransactionPort
import cc.monomer.connector.application.ManagedCredentialWritePort
import cc.monomer.connector.domain.Connector
import cc.monomer.connector.domain.ConnectorType
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.agent.AgentConnectorOwnerAdapter
import cc.monomer.control.auth.inbound.BearerAuthorization
import cc.monomer.control.auth.inbound.BearerCredentials
import cc.monomer.control.connector.inbound.rest.ConnectorMutationController
import cc.monomer.control.connector.inbound.rest.ConnectorMutationProblemHandler
import cc.monomer.control.publicapi.*
import cc.monomer.identity.api.*
import io.mockk.*
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.*
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.ServerRequest

class SampleConnectorApiRejectionTest {
    @Test
    fun `Human creation rejects DuckDB and external warehouses in a sample Workspace before persistence`() {
        val fixture = Fixture(CredentialSurface.WEB)
        for (input in listOf(DUCKDB_CREATE, EXTERNAL_CREATE)) {
            fixture.human.post().uri("/v2/workspaces/${fixture.workspace}/connectors")
                .header(HttpHeaders.AUTHORIZATION, "Bearer fixture-human-token")
                .header("Idempotency-Key", "sample-rejected-create")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(input).exchange()
                .expectStatus().isEqualTo(422).expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
                .jsonPath("$.retryable").isEqualTo(false)
        }
        assertEquals(listOf(MutationIntent.CONNECTOR_CREATE, MutationIntent.CONNECTOR_CREATE), fixture.admittedIntents)
        verify { fixture.repository wasNot Called }
        fixture.verifyNoWrites()
    }

    @Test
    fun `Human rename settings rotation and deletion dispatch to owner and leave sample unchanged`() {
        val fixture = Fixture(CredentialSurface.WEB)
        val path = "/v2/workspaces/${fixture.workspace}/connectors/${fixture.connector.id}"
        for (input in listOf(
            """{"displayName":"Renamed","properties":{"sampleDataset":"acme-cloud@2"}}""",
            """{"displayName":"Acme Cloud (sample)","properties":{"sampleDataset":"unsupported@1"}}""",
        )) {
            fixture.human.put().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer fixture-human-token")
                .header("Idempotency-Key", "sample-rejected-edit").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input).exchange().expectStatus().isEqualTo(422).expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
                .jsonPath("$.detail").isEqualTo("Sample connectors are managed by Monomer")
                .jsonPath("$.retryable").isEqualTo(false)
        }
        fixture.human.put().uri("$path/credentials").header(HttpHeaders.AUTHORIZATION, "Bearer fixture-human-token")
            .header("Idempotency-Key", "sample-rejected-rotate").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(EXTERNAL_CREDENTIAL).exchange().expectStatus().isEqualTo(422).expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST").jsonPath("$.retryable").isEqualTo(false)
        fixture.human.delete().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer fixture-human-token")
            .exchange().expectStatus().isEqualTo(422).expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST").jsonPath("$.retryable").isEqualTo(false)
        assertEquals(listOf(MutationIntent.CONNECTOR_UPDATE, MutationIntent.CONNECTOR_UPDATE,
            MutationIntent.CONNECTOR_UPDATE, MutationIntent.CONNECTOR_DELETE), fixture.admittedIntents)
        verify(exactly = 4) { fixture.repository.findConnector(fixture.connector.id) }
        fixture.verifyNoWrites()
    }

    @Test
    fun `Agent strict create contract rejects DuckDB and owner rejects external create in sample Workspace`() {
        val fixture = Fixture(CredentialSurface.AGENT)
        fixture.agentRequest("create", agentCreate(DUCKDB_CREATE))
            .expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("AGENT_REQUEST_INVALID")
        assertEquals(emptyList(), fixture.admittedIntents)
        fixture.agentRequest("create", agentCreate(EXTERNAL_CREATE))
            .expectStatus().isBadRequest.expectBody()
            .jsonPath("$.code").isEqualTo("CONNECTOR_INPUT_INVALID")
            .jsonPath("$.detail").isEqualTo("Real warehouse connectors cannot be added to a sample workspace")
            .jsonPath("$.retryable").isEqualTo(false)
        assertEquals(listOf(MutationIntent.CONNECTOR_CREATE), fixture.admittedIntents)
        verify { fixture.repository wasNot Called }
        fixture.verifyNoWrites()
    }

    @Test
    fun `Agent rename settings rotation and deletion dispatch to real owner as nonretryable input errors`() {
        val fixture = Fixture(CredentialSurface.AGENT)
        val path = "workspace/connectors/${fixture.connector.id}"
        for ((property, value) in listOf("/displayName" to "Renamed", "/properties/sampleDataset" to "unsupported@1")) {
            fixture.agentRequest("edit", """{"path":"$path","patch":[{"op":"replace","path":"$property","value":"$value"}]}""")
                .expectStatus().isBadRequest.expectBody()
                .jsonPath("$.code").isEqualTo("CONNECTOR_INPUT_INVALID")
                .jsonPath("$.detail").isEqualTo("Sample connectors are managed by Monomer")
                .jsonPath("$.retryable").isEqualTo(false)
        }
        fixture.agentRequest("invoke", """{"path":"$path","action":"rotate-credentials","input":{"kind":"CONNECTOR_CREDENTIAL_ROTATE","credential":$EXTERNAL_CREDENTIAL}}""")
            .expectStatus().isBadRequest.expectBody()
            .jsonPath("$.code").isEqualTo("CONNECTOR_INPUT_INVALID").jsonPath("$.retryable").isEqualTo(false)
        fixture.agentRequest("invoke", """{"path":"$path","action":"delete"}""")
            .expectStatus().isBadRequest.expectBody()
            .jsonPath("$.code").isEqualTo("CONNECTOR_INPUT_INVALID")
            .jsonPath("$.detail").isEqualTo("Sample connectors are managed by Monomer and cannot be changed.")
            .jsonPath("$.retryable").isEqualTo(false)
        assertEquals(listOf(MutationIntent.CONNECTOR_UPDATE, MutationIntent.CONNECTOR_UPDATE,
            MutationIntent.CONNECTOR_UPDATE, MutationIntent.CONNECTOR_DELETE), fixture.admittedIntents)
        verify(exactly = 4) { fixture.repository.findConnector(fixture.connector.id) }
        fixture.verifyNoWrites()
    }

    @Test
    fun `Agent sample list and read advertise only applicable operations including an empty provisioning Workspace`() {
        val fixture = Fixture(CredentialSurface.AGENT)
        fixture.connectorPresent = false
        for (state in WorkspaceSampleState.entries) {
            fixture.sampleState = state
            fixture.agentRequest("list", """{"path":"workspace/connectors","limit":5}""")
                .expectStatus().isOk.expectBody().jsonPath("$.items.length()").isEqualTo(0)
                .jsonPath("$.availableOperations").doesNotExist()
        }
        fixture.connectorPresent = true
        fixture.agentRequest("list", """{"path":"workspace/connectors","limit":5}""")
            .expectStatus().isOk.expectBody().jsonPath("$.items[0].connectorType").isEqualTo("DUCKDB")
            .jsonPath("$.availableOperations").doesNotExist()
        val path = "workspace/connectors/${fixture.connector.id}"
        fixture.agentRequest("read", """{"path":"$path"}""")
            .expectStatus().isOk.expectBody().jsonPath("$.body.connectorType").isEqualTo("DUCKDB")
            .jsonPath("$.body.sample.dataset").isEqualTo("acme-cloud@2")
            .jsonPath("$.availableOperations.length()").isEqualTo(1)
            .jsonPath("$.availableOperations[0].operation").isEqualTo("READ")
            .jsonPath("$.availableOperations[0].path").isEqualTo("$path/catalog")
        assertEquals(emptyList(), fixture.admittedIntents)
        fixture.verifyNoWrites()
    }

    private class Fixture(private val surface: CredentialSurface) {
        val workspace = WorkspaceId.generate()
        private val organization = OrganizationId.generate()
        private val actor = CanonicalPrincipal(PrincipalId.generate(), PrincipalKind.USER)
        private val now = Instant.parse("2026-10-12T12:00:00Z")
        val connector = Connector(ConnectorId.generate(), workspace, ConnectorType("duckdb"), "Acme Cloud (sample)",
            ConnectorSettingsProperties.Duckdb("acme-cloud@2"),
            CredentialEnvelopeReference(CredentialVersionId.generate(), "fixture-key-1", ContentDigest("sha256:" + "1".repeat(64))),
            1, now, now, null)
        val repository = mockk<ConnectorRepository>()
        private val credentials = mockk<ManagedCredentialWritePort>()
        private val search = mockk<ConnectorSearchProjectionPort>()
        private val dependents = mockk<ConnectorDependentStateRemovalPort>()
        private val identity = mockk<IdentityAdmissionPort>()
        private val clock = Clock.fixed(now, ZoneOffset.UTC)
        private val admission = ControlAdmissionFacade(identity, clock)
        val admittedIntents = mutableListOf<MutationIntent>()
        var sampleState = WorkspaceSampleState.READY
        var connectorPresent = true
        private val workspaceSamples = WorkspaceSampleQuery { id ->
            check(id == workspace)
            WorkspaceSampleMarker("acme-cloud@2", sampleState, actor.id)
        }
        private val authority = ConnectorAuthorityService(repository, object : ConnectorTransactionPort {
            override suspend fun <T> inTransaction(block: () -> T): T = block()
        }, mockk<ConnectorMutationKeyLockPort>(relaxed = true), credentials, clock, search, dependents, workspaceSamples)
        private val clientId = AgentClientId("https://example.test/sample-verification-agent")

        init {
            every { repository.findConnector(connector.id) } returns connector
            coEvery { identity.decide(any<IdentityRoleAdmissionRequest>()) } coAnswers {
                val request = firstArg<IdentityRoleAdmissionRequest>()
                check(request.workspaceId == workspace)
                check(request.credential.value == if (surface == CredentialSurface.WEB) "fixture-human-token" else "fixture-agent-token")
                check(request.expectedOAuthResource == if (surface == CredentialSurface.AGENT) ExpectedOAuthResource.AGENT_REST else null)
                IdentityRoleAdmissionDecision.Authorized(IdentityRoleEvidence(OrganizationPlan.FREE, actor, organization, workspace,
                    AuthorizationRevision(1), if (surface == CredentialSurface.WEB) CredentialKind.SESSION else CredentialKind.AGENT_OAUTH,
                    surface, if (surface == CredentialSurface.AGENT) clientId else null, WorkspaceRole.OWNER))
            }
        }

        private val observedAuthority = object : ConnectorAuthorityUseCase {
            override suspend fun create(command: CreateConnectorCommand): ConnectorMutationResult {
                admittedIntents.add(command.authorization.intent)
                return authority.create(command)
            }
            override suspend fun revise(command: ReviseConnectorCommand): ConnectorMutationResult {
                admittedIntents.add(command.authorization.intent)
                return authority.revise(command)
            }
            override suspend fun rotateCredential(command: RotateConnectorCredentialCommand): ConnectorMutationResult {
                admittedIntents.add(command.authorization.intent)
                return authority.rotateCredential(command)
            }
            override suspend fun delete(command: DeleteConnectorCommand): ConnectorDeletionResult {
                admittedIntents.add(command.authorization.intent)
                return authority.delete(command)
            }
        }

        val human = WebTestClient.bindToController(ConnectorMutationController(observedAuthority, admission))
            .controllerAdvice(ConnectorMutationProblemHandler())
            .webFilter<WebTestClient.ControllerSpec>(cc.monomer.control.observability.RequestCorrelationTestSupport.filter()).build()

        private val currentRead = mockk<ConnectorWorkspaceReadUseCase>().also { read ->
            fun current() = CurrentConnectorRead(connector.id, workspace, ConnectorProductType.DUCKDB, connector.displayName, connector.properties, now, now)
            coEvery { read.listByWorkspace(workspace) } coAnswers { if (connectorPresent) listOf(current()) else emptyList() }
            coEvery { read.findByWorkspaceAndId(workspace, connector.id) } coAnswers { if (connectorPresent) current() else null }
        }
        private val connectorOwner = AgentConnectorOwnerAdapter(observedAuthority, currentRead, admission, workspaceSamples)
        private val roleAdmission = mockk<AgentWorkspaceAdmissionPort>().also { port ->
            coEvery { port.decide(any()) } coAnswers {
                val request = firstArg<AgentWorkspaceAdmissionRequest>()
                check(request.workspaceId == workspace)
                check(request.credential.value == "fixture-agent-token")
                AgentWorkspaceAdmissionDecision.Authorized(AgentWorkspaceRoleEvidence(OrganizationPlan.FREE, actor, organization, workspace,
                    AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, clientId, WorkspaceRole.OWNER, request.intent))
            }
        }
        private val handler = AgentResourceRouteRegistry(mockk(), connectorOwner, mockk(), mockk(), mockk(), null,
            listOf(AgentResourcePath.parse("workspace/connectors")), mockk(), null)
        private val oauth = mockk<AgentOAuthPort>().also { port ->
            coEvery { port.validateCredential(PresentedCredential("fixture-agent-token"), ExpectedOAuthResource.AGENT_REST) } returns
                OAuthOutcome.Success(OAuthAgentIdentity(actor.id, clientId.value))
        }
        private val agent = WebTestClient.bindToRouterFunction(AgentWorkspacePublicApiRouter(
            AgentWorkspaceHttpPorts(AgentWorkspaceCallerAuthenticator(roleAdmission), handler, AgentWorkspaceContractDecoder()),
            "http://localhost/.well-known/oauth-protected-resource/v2/workspaces", ControlPublicApiProperties(URI("http://localhost")),
            object : AgentWorkspaceTransportCredentialResolver {
                override suspend fun resolve(request: ServerRequest): AgentWorkspaceTransportCredentialResolution =
                    when (val bearer = BearerCredentials.fromSingleAuthorizationHeader(request.headers().asHttpHeaders())) {
                        is BearerAuthorization.Presented -> AgentWorkspaceTransportCredentialResolution.Resolved(bearer.credential)
                        else -> AgentWorkspaceTransportCredentialResolution.Missing
                    }
            }, PublicControlContractValidator(), oauth).routes())
            .webFilter<WebTestClient.RouterFunctionSpec>(cc.monomer.control.observability.RequestCorrelationTestSupport.filter()).build()

        fun agentRequest(operation: String, input: String): WebTestClient.ResponseSpec = agent.post()
            .uri("/v2/workspaces/$workspace/agent/$operation").header(HttpHeaders.AUTHORIZATION, "Bearer fixture-agent-token")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(input).exchange()

        fun verifyNoWrites() {
            verify(exactly = 0) { repository.insertConnector(any()) }
            verify(exactly = 0) { repository.updateConnector(any(), any()) }
            verify(exactly = 0) { repository.markDeleted(any(), any()) }
            verify(exactly = 0) { repository.insertMutationReceipt(any()) }
            verify { credentials wasNot Called; search wasNot Called; dependents wasNot Called }
        }
    }

    private companion object {
        const val EXTERNAL_CREDENTIAL = """{"kind":"USERNAME_PASSWORD","username":"fixture-reader","password":"fixture-only-secret"}"""
        const val EXTERNAL_CREATE = """{"connectorType":"POSTGRESQL","displayName":"Warehouse","properties":{"host":"example.test","port":5432,"database":"warehouse"},"credential":$EXTERNAL_CREDENTIAL}"""
        const val DUCKDB_CREATE = """{"connectorType":"DUCKDB","displayName":"My sample","properties":{"sampleDataset":"acme-cloud@2"},"credential":{"kind":"SAMPLE_DATASET_ACCESS","dataset":"acme-cloud@2"}}"""
        fun agentCreate(input: String): String = buildJsonObject {
            put("path", "workspace/connectors")
            put("input", JsonObject(Json.parseToJsonElement(input).jsonObject + ("kind" to JsonPrimitive("CONNECTOR_CREATE"))))
        }.toString()
    }
}
