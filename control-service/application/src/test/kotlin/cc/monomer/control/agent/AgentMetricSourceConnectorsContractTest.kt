@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.agent

import cc.monomer.agent.api.*
import cc.monomer.agent.application.AgentWorkspaceCallerAuthenticator
import cc.monomer.agent.application.AgentWorkspaceContractDecoder
import cc.monomer.connector.api.*
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.mcp.*
import cc.monomer.control.publicapi.*
import cc.monomer.control.semantic.inbound.rest.MetricDefinitionDto
import cc.monomer.control.semantic.inbound.rest.MetricResponseDto
import cc.monomer.control.semantic.inbound.rest.SemanticModelResponseDto
import cc.monomer.control.semantic.inbound.rest.SemanticModelDefinitionDto
import cc.monomer.identity.api.*
import cc.monomer.semantic.api.*
import cc.monomer.semantic.application.*
import cc.monomer.semantic.domain.CurrentMetric
import cc.monomer.semantic.domain.CurrentSemanticModel
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.mockk.*
import java.net.URI
import java.time.Instant
import kotlin.test.*
import kotlin.uuid.Uuid
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

class AgentMetricSourceConnectorsContractTest {
    private val mapper = ObjectMapper()

    @AfterTest
    fun clearFixtures() = clearAllMocks()

    @Test
    fun `REST and MCP expose the same source candidates on paged lists and definition envelopes`() {
        val fixture = Fixture(WorkspaceRole.EXPLORER)
        for ((operation, body) in listOf(
            "list" to """{"path":"workspace/metrics","limit":1}""",
            "read" to """{"path":"workspace/metrics/revenue"}""",
            "read" to """{"path":"workspace/metrics/unavailable"}""",
        )) {
            val rest = fixture.rest(operation, body)
            val mcp = fixture.mcp(operation, body).path("result").path("structuredContent")
            assertEquals(withoutRequestId(rest), withoutRequestId(mcp))
            val metadata = if (operation == "list") rest.path("items").first() else rest
            val expected = if (metadata.path("path").asText().endsWith("/unavailable")) mapper.readTree("[]") else
                mapper.readTree("""[{"connectorId":"${fixture.localConnector}","displayName":"Analytics"}]""")
            assertEquals(expected, metadata.path("sourceConnectors"))
            assertFalse(metadata.path("body").has("sourceConnectors"))
            if (operation == "read") {
                val facts = metadata.at("/body/sourcePartitionInfo")
                if (metadata.path("path").asText().endsWith("/unavailable")) assertEquals(0, facts.size())
                else {
                    assertEquals(1, facts.size())
                    assertEquals(fixture.localConnector.toString(), facts.single().path("connectorId").asText())
                    assertTrue(facts.single().path("requiresPartitionFilter").isNull)
                    assertEquals(0, facts.single().path("partitionKeys").size())
                }
            }
            assertFalse(rest.toString().contains(fixture.foreignConnector.toString()))
            assertFalse(rest.toString().contains(fixture.deletedConnector.toString()))
            val output = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                mapper.valueToTree<JsonNode>(McpToolCatalog(MonomerDocsReader("https://monomer.cc").agentApiReferenceUrl).tools.single { it["name"] == "monomer_$operation" }["outputSchema"]),
            )
            assertTrue(output.validate(mcp).isEmpty(), output.validate(mcp).toString())
            val missing = mcp.deepCopy<ObjectNode>()
            (if (operation == "list") missing.path("items").first() as ObjectNode else missing).remove("sourceConnectors")
            assertTrue(output.validate(missing).isEmpty(), "MCP advertises only the minimal envelope")
            val ownerValidator = PublicControlContractValidator()
            val responseType = object : TypeReference<Map<String, Any?>>() {}
            val validateOwner: (JsonNode) -> Unit = { response ->
                val value = mapper.convertValue(response, responseType)
                if (operation == "list") ownerValidator.agentListing(value) else ownerValidator.agentResource(value)
            }
            validateOwner(mcp)
            assertFailsWith<IllegalStateException>("sourceConnectors remains required by the owning response schema") {
                validateOwner(missing)
            }
            if (operation == "list") {
                val cursor = rest.path("nextCursor").asText()
                assertTrue(cursor.isNotEmpty())
                val nextBody = """{"path":"workspace/metrics","limit":1,"cursor":"$cursor"}"""
                val nextRest = fixture.rest(operation, nextBody)
                val nextMcp = fixture.mcp(operation, nextBody).path("result").path("structuredContent")
                assertEquals(withoutRequestId(nextRest), withoutRequestId(nextMcp))
                assertEquals("workspace/metrics/unavailable", nextRest.at("/items/0/path").asText())
                assertEquals(mapper.readTree("[]"), nextRest.at("/items/0/sourceConnectors"))
            }
        }
        coVerify { fixture.connectorRead.listByWorkspace(fixture.workspaceId) }
        verify(exactly = 0) { fixture.repository.findMetrics(fixture.otherWorkspace) }
    }

    @Test
    fun `Web response projections retain only current workspace partition hints`() = kotlinx.coroutines.runBlocking {
        val fixture = Fixture(WorkspaceRole.EXPLORER)
        val metric = assertNotNull(fixture.currentRead.findMetricByName(fixture.workspaceId, SemanticElementName("revenue")))
        val model = assertNotNull(fixture.currentRead.findModelByName(fixture.workspaceId, SemanticElementName("orders")))
        for (response in listOf(MetricResponseDto.fromDomain(metric), SemanticModelResponseDto.fromDomain(model))) {
            val json = mapper.valueToTree<JsonNode>(response)
            val facts = json.path("sourcePartitionInfo")
            assertEquals(1, facts.size())
            assertEquals(fixture.localConnector.toString(), facts.single().path("connectorId").asText())
            assertTrue(facts.single().path("requiresPartitionFilter").isNull)
            assertEquals(0, facts.single().path("partitionKeys").size())
        }
        assertEquals(setOf(fixture.localConnector, fixture.foreignConnector, fixture.deletedConnector),
            model.definition.sourceRelations.map { it.connectorId }.toSet())
    }

    @Test
    fun `Viewer and nonmember metric requests are rejected before source metadata is read`() {
        for (role in listOf(WorkspaceRole.VIEWER, null)) {
            val fixture = Fixture(role)
            for ((operation, body) in listOf(
                "list" to """{"path":"workspace/metrics","limit":20}""",
                "read" to """{"path":"workspace/metrics/revenue"}""",
            )) {
                val status = if (role == null) 404 else 403
                val rest = fixture.rest(operation, body, status)
                val mcp = fixture.mcp(operation, body)
                assertTrue(mcp.path("result").path("isError").asBoolean())
                val problem = mapper.readTree(mcp.at("/result/content/0/text").asText())
                assertEquals(rest.path("code"), problem.path("code"))
                assertEquals(status, problem.path("status").asInt())
                assertFalse(rest.has("sourceConnectors"))
                assertFalse(problem.has("sourceConnectors"))
                assertFalse(rest.toString().contains("Analytics"))
                assertFalse(problem.toString().contains(fixture.localConnector.toString()))
            }
            coVerify { fixture.connectorRead wasNot Called }
            verify { fixture.repository wasNot Called }
        }
    }

    private fun withoutRequestId(node: JsonNode): JsonNode = node.deepCopy<ObjectNode>().apply { remove("requestId") }

    private class Fixture(private val role: WorkspaceRole?) {
        val workspaceId = WorkspaceId.generate()
        val otherWorkspace = WorkspaceId.generate()
        val localConnector = ConnectorId.generate()
        val foreignConnector = ConnectorId.generate()
        val deletedConnector = ConnectorId.generate()
        val repository = mockk<SemanticRepository>()
        val connectorRead = mockk<ConnectorWorkspaceReadUseCase>()
        val currentRead: SemanticCurrentReadService
        private val mapper = jacksonObjectMapper()
        private val time = Instant.parse("2026-10-08T00:00:00Z")
        private val userId = PrincipalId.generate()
        private val organizationId = OrganizationId.generate()
        private val oauth = mockk<AgentOAuthPort>()
        private val admission = object : AgentWorkspaceAdmissionPort {
            override suspend fun decide(request: AgentWorkspaceAdmissionRequest): AgentWorkspaceAdmissionDecision {
                assertEquals(WorkspaceRole.EXPLORER, request.intent.requiredRole)
                if (role == null || request.workspaceId != workspaceId) {
                    return AgentWorkspaceAdmissionDecision.Denied(AgentWorkspaceAdmissionDenial.NOT_FOUND, null)
                }
                if (!role.includes(request.intent.requiredRole)) {
                    return AgentWorkspaceAdmissionDecision.Denied(AgentWorkspaceAdmissionDenial.FORBIDDEN, null)
                }
                return AgentWorkspaceAdmissionDecision.Authorized(AgentWorkspaceRoleEvidence(
                    OrganizationPlan.FREE, CanonicalPrincipal(userId, PrincipalKind.USER), organizationId, workspaceId,
                    AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT,
                    AgentClientId("https://client.example/metadata.json"), role, request.intent,
                ))
            }
        }
        private val ports: AgentWorkspaceHttpPorts
        private val restClient: WebTestClient
        private val mcpClient: WebTestClient

        init {
            val metrics = listOf(metric("revenue", "orders"), metric("unavailable", "orphan"))
            val models = listOf(model("orders", listOf(localConnector, foreignConnector, deletedConnector)),
                model("orphan", listOf(foreignConnector, deletedConnector)))
            every { repository.findMetrics(workspaceId) } returns metrics
            every { repository.findModels(workspaceId) } returns models
            models.forEach { model ->
                every { repository.findModelByName(workspaceId, model.definition.name) } returns model
            }
            metrics.forEach { metric ->
                every { repository.findMetricByName(workspaceId, metric.definition.name) } returns metric
                every { repository.readMetricChange(workspaceId, metric.id) } returns null
            }
            every { repository.listMetrics(workspaceId, any(), any()) } answers {
                val after = secondArg<MetricListBoundary?>()
                metrics.filter { after == null || it.definition.name.value > after.name.value }.take(thirdArg<Int>())
            }
            coEvery { connectorRead.listByWorkspace(workspaceId) } returns listOf(
                connector(localConnector, workspaceId, "Analytics"), connector(foreignConnector, otherWorkspace, "Hidden warehouse"),
            )
            coEvery { connectorRead.findByWorkspaceAndIds(workspaceId, any()) } returns listOf(
                connector(localConnector, workspaceId, "Analytics"), connector(foreignConnector, otherWorkspace, "Hidden warehouse"),
            )
            currentRead = SemanticCurrentReadService(repository, mockk(), ImmediateTransactions, connectorRead,
                io.mockk.mockk(relaxed = true),
            )
            val owner = AgentSemanticOwnerAdapter(mockk(), currentRead, mockk(), AgentSemanticEditAvailability { false },
                mockk<ControlAdmissionFacade>(), fixedAgentProvenanceNames())
            val handler = object : AgentWorkspaceHandler {
                override suspend fun list(caller: AgentWorkspaceCaller, request: AgentListRequest) = owner.list(caller, request)
                override suspend fun read(caller: AgentWorkspaceCaller, request: AgentReadRequest) = owner.read(caller, request)
                override suspend fun search(caller: AgentWorkspaceCaller, request: AgentSearchRequest): AgentSearchOutcome = error("unused")
                override suspend fun create(caller: AgentWorkspaceCaller, request: AgentCreateRequest): AgentActionOutcome = error("unused")
                override suspend fun edit(caller: AgentWorkspaceCaller, request: AgentEditRequest): AgentActionOutcome = error("unused")
                override suspend fun invoke(caller: AgentWorkspaceCaller, request: AgentInvokeRequest): AgentActionOutcome = error("unused")
            }
            ports = AgentWorkspaceHttpPorts(AgentWorkspaceCallerAuthenticator(admission), handler, AgentWorkspaceContractDecoder())
            val properties = ControlPublicApiProperties(URI("https://app.example.com"))
            restClient = WebTestClient.bindToRouterFunction(AgentWorkspacePublicApiRouter(ports,
                "https://api.example.com/.well-known/oauth-protected-resource/v2/workspaces", properties,
                object : AgentWorkspaceTransportCredentialResolver {
                    override suspend fun resolve(request: org.springframework.web.reactive.function.server.ServerRequest) =
                        AgentWorkspaceTransportCredentialResolution.Resolved(PresentedCredential("agent-token"))
                }, PublicControlContractValidator(), oauth).routes()).webFilter<org.springframework.test.web.reactive.server.WebTestClient.RouterFunctionSpec>(cc.monomer.control.observability.RequestCorrelationTestSupport.filter()).build()
            coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_REST) } returns
                OAuthOutcome.Success(OAuthAgentIdentity(userId, "https://client.example/metadata.json"))
            coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_MCP) } returns
                OAuthOutcome.Success(OAuthAgentIdentity(userId, "https://client.example/metadata.json"))
            mcpClient = WebTestClient.bindToRouterFunction(McpRouter(ports, oauth,
                McpTransportProperties("https://api.example.com/.well-known/oauth-protected-resource/mcp", setOf("https://app.example.com")),
                MonomerDocsReader("https://monomer.cc"), mockk(), properties, dashboardViewHtml = "<!doctype html><title>Fixture view</title>", clock = java.time.Clock.systemUTC()).routes()).webFilter<org.springframework.test.web.reactive.server.WebTestClient.RouterFunctionSpec>(cc.monomer.control.observability.RequestCorrelationTestSupport.filter()).build()
        }

        fun rest(operation: String, body: String, status: Int = 200): JsonNode = mapper.readTree(restClient.post()
            .uri("/v2/workspaces/$workspaceId/agent/$operation").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .exchange().expectStatus().isEqualTo(status).expectBody().returnResult().responseBody!!)

        fun mcp(operation: String, body: String): JsonNode {
            val arguments = mapper.readTree(body) as ObjectNode
            arguments.put("workspaceId", workspaceId.toString())
            val request = mapper.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "tools/call",
                "params" to mapOf("name" to "monomer_$operation", "arguments" to arguments)))
            return mapper.readTree(mcpClient.post().uri("/mcp").header("Authorization", "Bearer agent-token")
                .header("MCP-Protocol-Version", "2025-11-25").contentType(MediaType.APPLICATION_JSON).bodyValue(request)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .exchange().expectStatus().isOk.expectBody().returnResult().responseBody!!)
        }

        private fun metric(name: String, modelName: String) = CurrentMetric(MetricId.generate(), workspaceId,
            mapper.readValue("""{"schemaVersion":3,"name":"$name","type":"simple","semanticModelName":"$modelName","measure":"amount"}""",
                MetricDefinitionDto::class.java).toDomain(), time, time)

        private fun model(name: String, connectorIds: List<ConnectorId>) = CurrentSemanticModel(SemanticModelId.generate(), workspaceId,
            mapper.readValue(mapper.writeValueAsString(mapOf("schemaVersion" to 3, "name" to name,
                "sourceRelations" to connectorIds.map { mapOf("connectorId" to it.toString(), "schema" to "analytics", "relation" to "orders") },
                "entities" to listOf(mapOf("name" to "order_id", "type" to "primary", "expression" to "order_id")),
                "dimensions" to emptyList<Any>(), "measures" to emptyList<Any>())),
                SemanticModelDefinitionDto::class.java).toDomain(), time, time)

        private fun connector(id: ConnectorId, workspace: WorkspaceId, name: String) = CurrentConnectorRead(id, workspace,
            ConnectorProductType.POSTGRESQL, name, ConnectorSettingsProperties.Postgresql("db.example.test", 5432, "analytics", null, null), time, time)
    }

    private object ImmediateTransactions : SemanticTransactionPort {
        override suspend fun <T> inSnapshotTransaction(deadline: Instant, block: suspend () -> T): T = block()
        override suspend fun <T> inSnapshotTransaction(block: () -> T): T = block()
        override suspend fun <T> inMutationTransaction(block: () -> T): T = block()
    }
}
