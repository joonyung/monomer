@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.mcp

import cc.monomer.agent.api.*
import cc.monomer.agent.application.AgentWorkspaceCallerAuthenticator
import cc.monomer.agent.application.AgentWorkspaceContractDecoder
import cc.monomer.control.publicapi.AgentWorkspaceHttpPorts
import cc.monomer.identity.api.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.server.adapter.WebHttpHandlerBuilder
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import reactor.netty.http.server.HttpServer

@EnabledIfEnvironmentVariable(named = "MONOMER_MCP_INTEROP_PYTHON", matches = ".+")
class McpClientInteropIntegrationTest {
    @Test
    fun `official MCP SDK uses authenticated sessions and rejects unauthenticated messages over TCP`() {
        val workspaceId = WorkspaceId(Uuid.parse("01942b04-6e00-7000-8000-000000000001"))
        val organizationId = OrganizationId(Uuid.parse("01942b04-6e00-7000-8000-000000000002"))
        val userId = PrincipalId(Uuid.parse("01942b04-6e00-7000-8000-000000000003"))
        val credential = PresentedCredential("mcp-interop-fixture")
        val oauth = mockk<AgentOAuthPort>()
        coEvery { oauth.validateCredential(credential, ExpectedOAuthResource.AGENT_MCP) } returns OAuthOutcome.Success(OAuthAgentIdentity(userId, clientId = "https://client.example/metadata.json"))
        coEvery { oauth.listWorkspaces(credential, ExpectedOAuthResource.AGENT_MCP) } returns OAuthOutcome.Success(listOf(OAuthWorkspace(workspaceId, organizationId, "Team", "Fixture Workspace", null)))
        coEvery { oauth.listOrganizations(credential, ExpectedOAuthResource.AGENT_MCP) } returns OAuthOutcome.Success(listOf(OAuthOrganization(organizationId, "Team", "MEMBER")))
        val admission = mockk<AgentWorkspaceAdmissionPort>()
        coEvery { admission.decide(any()) } answers {
            val request = firstArg<AgentWorkspaceAdmissionRequest>()
            assertEquals(credential, request.credential)
            assertEquals(ExpectedOAuthResource.AGENT_MCP, request.expectedOAuthResource)
            if (request.workspaceId != workspaceId) AgentWorkspaceAdmissionDecision.Denied(AgentWorkspaceAdmissionDenial.FORBIDDEN, null)
            else AgentWorkspaceAdmissionDecision.Authorized(AgentWorkspaceRoleEvidence(OrganizationPlan.FREE, CanonicalPrincipal(userId, PrincipalKind.USER), organizationId, workspaceId, AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://client.example/metadata.json"), WorkspaceRole.OWNER, request.intent))
        }
        val handler = mockk<AgentWorkspaceHandler>()
        coEvery { handler.list(any(), any()) } answers {
            assertEquals(ExpectedOAuthResource.AGENT_MCP, firstArg<AgentWorkspaceCaller>().expectedOAuthResource)
            AgentListingOutcome.Found(AgentResourceListingResponse(emptyList(), null, emptyList()))
        }
        coEvery { handler.search(any(), any()) } answers {
            assertEquals(ExpectedOAuthResource.AGENT_MCP, firstArg<AgentWorkspaceCaller>().expectedOAuthResource)
            val request = secondArg<AgentSearchRequest>()
            assertEquals(20, request.limitPerQuery)
            AgentSearchOutcome.Found(AgentSearchResponse(request.queries.map { AgentSearchQueryResult(it, emptyList()) }))
        }
        val metricCollection = AgentAvailableOperation.List(AgentResourcePath.parse("workspace/metrics"))
        coEvery { handler.read(any(), any()) } returns AgentResourceOutcome.Found(AgentResourceEnvelope.Workspace(
            AgentResourcePath.parse("workspace"), AgentWorkspaceRootBody(WorkspaceRole.OWNER, listOf(metricCollection)), listOf(metricCollection),
        ))
        coEvery { handler.create(any(), any()) } returns AgentActionOutcome.Unavailable
        val catalogSelection = AgentResourcePath.parse("workspace/connectors/01942b04-6e00-7000-8000-000000000010/catalog/catalogs/monomer-public-data/selection")
        val page = AgentResourcePath.parse("workspace/pages/01942b04-6e00-7000-8000-000000000011")
        coEvery { handler.edit(any(), any()) } answers {
            assertEquals(ExpectedOAuthResource.AGENT_MCP, firstArg<AgentWorkspaceCaller>().expectedOAuthResource)
            val request = secondArg<AgentEditRequest>()
            val kind = when (request.path) {
                catalogSelection -> {
                    assertEquals(null, request.revision)
                    assertEquals(null, request.commandId)
                    assertEquals(1, request.patch.size)
                    assertEquals(AgentJsonPatchOp.REPLACE, request.patch.single().op)
                    assertEquals("", request.patch.single().path)
                    assertEquals(kotlinx.serialization.json.Json.parseToJsonElement("""{"selectedSchemas":[{"schemaName":"github_public","relations":{"kind":"SELECTED_RELATIONS","relationNames":["packages"]}}]}"""), request.patch.single().value)
                    AgentResourceKind.CATALOG_SELECTION
                }
                page -> {
                    assertEquals(AgentRevision(3), request.revision)
                    assertEquals("interop-page-edit", request.commandId)
                    assertEquals(listOf("/title", "/description"), request.patch.map { it.path })
                    assertTrue(request.patch.all { it.op == AgentJsonPatchOp.REPLACE })
                    assertEquals(kotlinx.serialization.json.JsonPrimitive("Updated report"), request.patch[0].value)
                    assertEquals(kotlinx.serialization.json.JsonNull, request.patch[1].value)
                    AgentResourceKind.PAGE
                }
                else -> null
            }
            if (kind == null) AgentActionOutcome.Unavailable
            else AgentActionOutcome.Completed(AgentActionResult.Edit(request.path, AgentActionResultReference(kind, request.path)))
        }
        coEvery { handler.invoke(any(), any()) } answers {
            val request = secondArg<AgentInvokeRequest>()
            if (request.action != "check") AgentActionOutcome.Unavailable
            else AgentActionOutcome.Completed(AgentActionResult.SemanticCheck(
                request.path, AgentActionResultReference(AgentResourceKind.COLLECTION, request.path),
                kotlinx.serialization.json.Json.parseToJsonElement("""{"ok":false,"runsOn":[],"preview":null,"problems":[],"partitionFilters":[]}"""),
            ))
        }
        val docsRoot = sequenceOf(Path.of("../public-site/dist"), Path.of("../../public-site/dist"))
            .first { Files.isRegularFile(it.resolve("en/llms.txt")) }.toAbsolutePath()
        val docsServer = HttpServer.create().host("127.0.0.1").port(0).handle { request, response ->
            assertTrue(!request.requestHeaders().contains("Authorization"))
            assertTrue(!request.requestHeaders().contains("Cookie"))
            val path = request.uri()
            if (!MonomerDocsReader.validPath(path)) response.status(400).send()
            else {
                val file = docsRoot.resolve(path.removePrefix("/"))
                if (!Files.isRegularFile(file)) response.status(404).send()
                else response.header("Content-Type", "text/markdown; charset=utf-8").sendFile(file)
            }
        }.bindNow(Duration.ofSeconds(10))
        val router = McpRouter(AgentWorkspaceHttpPorts(AgentWorkspaceCallerAuthenticator(admission), handler, AgentWorkspaceContractDecoder()), oauth,
            McpTransportProperties("http://127.0.0.1/.well-known/oauth-protected-resource/mcp", emptySet()), MonomerDocsReader("http://127.0.0.1:${docsServer.port()}"), managementOwnerPort = io.mockk.mockk(), publicApiProperties = cc.monomer.control.publicapi.ControlPublicApiProperties(java.net.URI("http://127.0.0.1:3100")), dashboardViewHtml = "<!doctype html><title>Fixture view</title>", clock = java.time.Clock.systemUTC())
        val httpHandler = WebHttpHandlerBuilder.webHandler(RouterFunctions.toWebHandler(router.routes()))
            .filter(org.springframework.web.server.WebFilter { exchange, chain ->
                val fixtureRequestId = "srv_${java.util.UUID.randomUUID()}"
                val correlatedRequest = object : ServerHttpRequestDecorator(exchange.request) {
                    override fun getId(): String = fixtureRequestId
                }
                chain.filter(exchange.mutate().request(correlatedRequest).build())
            }).build()
        val server = HttpServer.create().host("127.0.0.1").port(0)
            .handle(ReactorHttpHandlerAdapter(httpHandler)).bindNow(Duration.ofSeconds(10))
        val log = Files.createTempFile("monomer-mcp-sdk-interop-", ".log")
        try {
            val script = sequenceOf(Path.of("../testkit/agent-oauth-client/verify_mcp_protocol.py"), Path.of("../../testkit/agent-oauth-client/verify_mcp_protocol.py"))
                .first { Files.isRegularFile(it) }.toAbsolutePath()
            val codex = System.getenv("MONOMER_MCP_PROBE_CODEX")
            val command = mutableListOf(System.getenv("MONOMER_MCP_INTEROP_PYTHON"), script.toString(), "http://127.0.0.1:${server.port()}/mcp", docsRoot.toString())
            if (!codex.isNullOrBlank()) command += listOf("--codex", codex)
            val process = ProcessBuilder(command)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start()
            if (!process.waitFor(if (codex.isNullOrBlank()) 60 else 300, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("MCP verification subprocess exceeded its bounded timeout")
            }
            val output = Files.readString(log)
            assertEquals(0, process.exitValue(), output)
            assertTrue(output.contains("\"result\": \"PASS\""), output)
            println(output)
            coVerify(exactly = if (codex.isNullOrBlank()) 8 else 14) { admission.decide(any()) }
            coVerify(exactly = 2) { handler.list(any(), any()) }
            coVerify(exactly = 1) { handler.search(any(), any()) }
            coVerify(exactly = 1) { handler.read(any(), any()) }
            coVerify(exactly = 1) { oauth.listWorkspaces(credential, ExpectedOAuthResource.AGENT_MCP) }
            coVerify(exactly = 1) { handler.invoke(any(), match { it.action == "check" }) }
            coVerify(exactly = 1) { handler.edit(any(), match { it.path == catalogSelection }) }
            coVerify(exactly = 1) { handler.edit(any(), match { it.path == page }) }
            if (!codex.isNullOrBlank()) {
                assertTrue(output.contains("\"codexProbe\": \"PASS\""), output)
                coVerify(exactly = 3) { handler.create(any(), any()) }
                coVerify(exactly = 3) { handler.edit(any(), any()) }
                coVerify(exactly = 2) { handler.invoke(any(), match { it.action == "run" }) }
            }
        } finally {
            server.disposeNow(Duration.ofSeconds(10))
            docsServer.disposeNow(Duration.ofSeconds(10))
            Files.deleteIfExists(log)
        }
    }
}
