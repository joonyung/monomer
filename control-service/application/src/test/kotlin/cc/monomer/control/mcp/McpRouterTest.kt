@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.mcp

import cc.monomer.agent.api.*
import cc.monomer.agent.application.AgentWorkspaceCallerAuthenticator
import cc.monomer.agent.application.AgentWorkspaceContractDecoder
import cc.monomer.control.publicapi.AgentWorkspaceHttpPorts
import cc.monomer.identity.api.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.mockk.*
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

class McpRouterTest {
    private val oauth = mockk<AgentOAuthPort>()
    private val management = mockk<AgentManagementOwnerPort>()
    private val docs = mockk<MonomerDocsReader> {
        every { agentApiReferenceUrl } returns "https://monomer.cc/en/docs/reference/agent-api/"
        excludeRecords { agentApiReferenceUrl }
    }
    private val handler = mockk<AgentWorkspaceHandler>()
    private val admission = mockk<AgentWorkspaceAdmissionPort>()
    private val workspaceId = WorkspaceId(Uuid.parse("01942b04-6e00-7000-8000-000000000001"))
    private val organizationId = OrganizationId(Uuid.parse("01942b04-6e00-7000-8000-000000000002"))
    private val userId = PrincipalId(Uuid.parse("01942b04-6e00-7000-8000-000000000003"))
    private val client = WebTestClient.bindToRouterFunction(McpRouter(
        AgentWorkspaceHttpPorts(AgentWorkspaceCallerAuthenticator(admission), handler, AgentWorkspaceContractDecoder()),
        oauth, McpTransportProperties("https://api.example.com/.well-known/oauth-protected-resource/mcp", setOf("https://app.example.com")), docs, managementOwnerPort = management, publicApiProperties = cc.monomer.control.publicapi.ControlPublicApiProperties(java.net.URI("https://app.example.com")),
        dashboardViewHtml = VIEW_HTML, clock = java.time.Clock.fixed(SHOWN_AT, java.time.ZoneOffset.UTC),
    ).routes()).webFilter<org.springframework.test.web.reactive.server.WebTestClient.RouterFunctionSpec>(cc.monomer.control.observability.RequestCorrelationTestSupport.filter()).configureClient().codecs { it.defaultCodecs().maxInMemorySize(16 * 1024 * 1024) }.build()

    @AfterTest
    fun releaseStubbedAnswers() = clearAllMocks()

    @Test
    fun `invalid MCP credential rejects oversized body before JSON parsing`() {
        coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_MCP) } returns OAuthOutcome.Failure(OAuthError.INVALID_GRANT)
        for (token in listOf(null, "invalid-token")) assertUnauthorized(post("x".repeat(1024 * 1024 + 1), token))
        coVerify { admission wasNot Called; handler wasNot Called; docs wasNot Called; management wasNot Called }
    }

    @Test
    fun `MCP restore eligibility errors preserve both owner reasons without schema guidance`() {
        authenticated()
        admitExplorer()
        val historyPath = "workspace/dashboards/${workspaceId.value}/history/${workspaceId.value}"
        for (detail in listOf(
            "This dashboard isn't deleted, so there's nothing to restore. Open it to edit it instead.",
            "This version matches the dashboard as it was deleted. Restore the deleted version instead.",
        )) {
            coEvery { handler.invoke(any(), any()) } returns AgentActionOutcome.Rejected(
                cc.monomer.control.publicapi.AgentTransportProblems.ownerUnavailable.copy(
                    code = "DASHBOARD_NOT_RESTORABLE", title = "Dashboard action rejected", detail = detail,
                    status = AgentWorkspaceProblemStatus.CONFLICT, retryable = false,
                ),
            )
            val response = post(toolCall("monomer_invoke", """{"workspaceId":"${workspaceId.value}","path":"$historyPath","action":"restore"}"""), "mcp-token")
                .expectStatus().isOk.expectHeader().doesNotExist("Retry-After").expectBody()
                .jsonPath("$.result.isError").isEqualTo(true).returnResult()
            val mapper = ObjectMapper()
            val text = mapper.readTree(response.responseBody).at("/result/content/0/text").asText()
            val problem = mapper.readTree(text)
            assertEquals(409, problem.path("status").asInt())
            assertEquals("DASHBOARD_NOT_RESTORABLE", problem.path("code").asText())
            assertEquals(detail, problem.path("detail").asText())
            assertEquals(false, problem.path("retryable").asBoolean())
            assertTrue(!text.contains("monomer_describe"))
        }
        coVerify(exactly = 2) { handler.invoke(any(), match { it.path.value == historyPath && it.action == "restore" && it.input == AgentInvokeInput.None }) }
    }

    @Test
    fun `MCP mutation owner problems preserve recovery content without schema guidance`() {
        authenticated()
        admitExplorer()
        val mapper = ObjectMapper()
        for (problem in listOf(
            cc.monomer.control.publicapi.AgentTransportProblems.resourceNotFound,
            cc.monomer.control.publicapi.AgentTransportProblems.forbidden,
            cc.monomer.control.publicapi.AgentTransportProblems.ownerUnavailable,
            cc.monomer.control.publicapi.AgentTransportProblems.requestInvalid(listOf(
                AgentProblemViolation("input", "INVALID_FIELD", "Read the target resource before trying again.", null, null),
            )),
        ).map { it.copy(hints = listOf("Read the target resource before trying again."),
            nextActions = listOf(AgentAvailableOperation.Read(AgentResourcePath.parse("workspace")))) }) {
            coEvery { handler.create(any(), any()) } returns AgentActionOutcome.Rejected(problem)
            coEvery { handler.edit(any(), any()) } returns AgentActionOutcome.Rejected(problem)
            coEvery { handler.invoke(any(), any()) } returns AgentActionOutcome.Rejected(problem)
            for ((tool, arguments) in mutationArguments()) {
                val response = post(toolCall(tool, arguments), "mcp-token").expectStatus().isOk.expectBody()
                    .jsonPath("$.result.isError").isEqualTo(true).returnResult()
                val text = mapper.readTree(response.responseBody).at("/result/content/0/text").asText()
                val actual = mapper.readTree(text)
                assertEquals(problem.status.value, actual.path("status").asInt())
                assertEquals(problem.code, actual.path("code").asText())
                assertEquals(problem.detail, actual.path("detail").asText())
                assertEquals(problem.retryable, actual.path("retryable").asBoolean())
                if (problem.violations.isNotEmpty()) assertEquals("input", actual.at("/violations/0/field").asText())
                assertEquals(mapper.valueToTree<JsonNode>(problem.hints), actual.path("hints"))
                assertEquals("READ", actual.at("/nextActions/0/operation").asText())
                assertEquals("workspace", actual.at("/nextActions/0/path").asText())
                assertTrue(!text.contains("monomer_describe"))
            }
        }
        coVerify(exactly = 4) { handler.create(any(), any()) }
        coVerify(exactly = 4) { handler.edit(any(), any()) }
        coVerify(exactly = 4) { handler.invoke(any(), any()) }
    }

    @Test
    fun `MCP mutation admission denials omit schema guidance`() {
        authenticated()
        for ((denial, status, code) in listOf(
            Triple(AgentWorkspaceAdmissionDenial.NOT_FOUND, 404, "NOT_FOUND"),
            Triple(AgentWorkspaceAdmissionDenial.FORBIDDEN, 403, "ROLE_REQUIRED"),
            Triple(AgentWorkspaceAdmissionDenial.UNAVAILABLE, 503, "OWNER_UNAVAILABLE"),
        )) {
            coEvery { admission.decide(any()) } returns AgentWorkspaceAdmissionDecision.Denied(denial, null)
            for ((tool, arguments) in mutationArguments()) {
                val response = post(toolCall(tool, arguments), "mcp-token").expectStatus().isOk.expectBody()
                    .jsonPath("$.result.isError").isEqualTo(true).returnResult()
                val mapper = ObjectMapper()
                val text = mapper.readTree(response.responseBody).at("/result/content/0/text").asText()
                val problem = mapper.readTree(text)
                assertEquals(status, problem.path("status").asInt())
                assertEquals(code, problem.path("code").asText())
                assertTrue(!text.contains("monomer_describe"))
            }
        }
        coVerify { handler wasNot Called }
    }

    @Test
    fun `MCP mutation schema and unknown operation errors retain describe guidance`() {
        authenticated()
        val mapper = ObjectMapper()
        val cases = mutationArguments().map { (tool, arguments) ->
            tool to mapper.readTree(arguments).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("extra", true).toString()
        } + listOf(
            "monomer_create" to """{"workspaceId":"${workspaceId.value}","path":"workspace/dashboards","input":{"kind":"UNKNOWN"}}""",
            "monomer_invoke" to """{"workspaceId":"${workspaceId.value}","path":"workspace/dashboards/${workspaceId.value}","action":"unknown"}""",
        )
        for ((tool, arguments) in cases) {
            val response = post(toolCall(tool, arguments), "mcp-token").expectStatus().isOk.expectBody()
                .jsonPath("$.result.isError").isEqualTo(true).returnResult()
            val text = mapper.readTree(response.responseBody).at("/result/content/0/text").asText()
            assertEquals("Use monomer_describe with the availableOperations or nextActions entry to confirm this operation's input schema.", text.substringBefore('\n'))
            val problem = mapper.readTree(problemJson(text))
            assertEquals(400, problem.path("status").asInt())
            assertEquals("AGENT_REQUEST_INVALID", problem.path("code").asText())
            assertEquals(false, problem.path("retryable").asBoolean())
            assertTrue(problem.path("violations").size() > 0)
        }
        coVerify { admission wasNot Called; handler wasNot Called }
    }

    @Test
    fun `MCP direct create handler unexpected exception requires read before resending`() {
        authenticated()
        admitExplorer()
        coEvery { handler.create(any(), any()) } throws java.io.IOException("sensitive-owner-detail")
        val result = post(toolCall("monomer_create", """{"workspaceId":"${workspaceId.value}","path":"workspace/dashboards","input":{"kind":"DASHBOARD_DEFINITION","definition":{"schemaVersion":1,"meta":{"title":"Orders"},"parameters":{},"variables":{},"queries":{},"widgets":{},"layout":{"sections":[]}}}}"""), "mcp-token")
            .expectStatus().isOk.expectHeader().doesNotExist("Retry-After").expectBody().returnResult()
        val problem = ObjectMapper().readTree(problemJson(ObjectMapper().readTree(result.responseBody).at("/result/content/0/text").asText()))
        assertEquals("OWNER_UNAVAILABLE", problem.path("code").asText())
        assertEquals(false, problem.path("retryable").asBoolean())
        assertTrue(problem.path("detail").asText().contains("Read or list the target resource"))
        assertTrue(!String(result.responseBody!!).contains("sensitive-owner-detail"))
    }

    @Test
    fun `MCP create response contract failure requires reading before resending without retry after`() {
        authenticated()
        admitExplorer()
        coEvery { handler.create(any(), any()) } returns AgentActionOutcome.Completed(AgentActionResult.Create(
            AgentResourcePath.parse("workspace/dashboards"), AgentActionResultReference(AgentResourceKind.DASHBOARD,
                AgentResourcePath.parse("workspace/dashboards/invalid"))))
        val result = post(toolCall("monomer_create", """{"workspaceId":"${workspaceId.value}","path":"workspace/dashboards","input":{"kind":"DASHBOARD_DEFINITION","definition":{"schemaVersion":1,"meta":{"title":"Orders"},"parameters":{},"variables":{},"queries":{},"widgets":{},"layout":{"sections":[]}}}}"""), "mcp-token")
            .expectStatus().isOk.expectHeader().doesNotExist("Retry-After").expectBody().returnResult()
        val problem = ObjectMapper().readTree(problemJson(ObjectMapper().readTree(result.responseBody).at("/result/content/0/text").asText()))
        assertEquals("AGENT_OWNER_RESPONSE_INVALID", problem.path("code").asText())
        assertEquals(false, problem.path("retryable").asBoolean())
        assertTrue(problem.path("detail").asText().contains("Read or list the target resource"))
    }

    @Test
    fun `MCP request decoder IllegalArgumentException remains a sanitized request problem`() {
        authenticated()
        val decoder = mockk<AgentWorkspaceRequestDecoderPort>()
        every { decoder.read(any()) } throws IllegalArgumentException("sensitive-decoder-detail")
        val localClient = WebTestClient.bindToRouterFunction(McpRouter(
            AgentWorkspaceHttpPorts(AgentWorkspaceCallerAuthenticator(admission), handler, decoder), oauth,
            McpTransportProperties("https://api.example.com/.well-known/oauth-protected-resource/mcp", emptySet()),
            docs, management, cc.monomer.control.publicapi.ControlPublicApiProperties(java.net.URI("https://app.example.com")),
            VIEW_HTML, java.time.Clock.fixed(SHOWN_AT, java.time.ZoneOffset.UTC),
        ).routes()).webFilter<WebTestClient.RouterFunctionSpec>(cc.monomer.control.observability.RequestCorrelationTestSupport.filter()).build()
        val result = localClient.post().uri("/mcp").contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM).headers { it.setBearerAuth("mcp-token") }
            .bodyValue(toolCall("monomer_read", """{"workspaceId":"${workspaceId.value}","path":"workspace"}"""))
            .exchange().expectStatus().isOk.expectBody().returnResult()
        val problem = ObjectMapper().readTree(ObjectMapper().readTree(result.responseBody).at("/result/content/0/text").asText())
        assertEquals("AGENT_REQUEST_INVALID", problem.path("code").asText())
        assertEquals(400, problem.path("status").asInt())
        assertTrue(!String(result.responseBody!!).contains("sensitive-decoder-detail"))
        coVerify { admission wasNot Called; handler wasNot Called }
    }

    @Test
    fun `MCP owner IllegalArgumentException returns sanitized internal error without retry after`() {
        authenticated()
        coEvery { admission.decide(any()) } answers {
            AgentWorkspaceAdmissionDecision.Authorized(AgentWorkspaceRoleEvidence(OrganizationPlan.FREE,
                CanonicalPrincipal(userId, PrincipalKind.USER), organizationId, workspaceId, AuthorizationRevision(1),
                CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, AgentClientId("https://client.example/metadata.json"),
                WorkspaceRole.EXPLORER, firstArg<AgentWorkspaceAdmissionRequest>().intent))
        }
        coEvery { handler.read(any(), any()) } throws IllegalArgumentException("sensitive-owner-detail")
        val response = post(toolCall("monomer_read", """{"workspaceId":"${workspaceId.value}","path":"workspace"}"""), "mcp-token")
            .expectStatus().isOk.expectHeader().doesNotExist("Retry-After").expectBody().returnResult()
        val problem = ObjectMapper().readTree(ObjectMapper().readTree(response.responseBody).at("/result/content/0/text").asText())
        assertEquals("INTERNAL_ERROR", problem.path("code").asText())
        assertEquals(500, problem.path("status").asInt())
        assertEquals(false, problem.path("retryable").asBoolean())
        assertTrue(!String(response.responseBody!!).contains("sensitive-owner-detail"))
    }

    @Test
    fun `MCP preserves Semantic input rejections as non retryable 400 Problems`() {
        authenticated()
        admitExplorer()
        for ((code, detail) in listOf(
            "SEMANTIC_QUERY_INVALID" to "The Semantic query is invalid.",
            "SEMANTIC_QUERY_UNSUPPORTED" to "The Semantic query uses a capability MetricFlow does not support.",
        )) {
            val rejection = cc.monomer.control.publicapi.AgentTransportProblems.ownerUnavailable.copy(
                code = code,
                title = "Semantic query rejected",
                detail = detail,
                status = AgentWorkspaceProblemStatus.BAD_REQUEST,
                retryable = false,
                retryAfterSeconds = null,
            )
            coEvery { handler.invoke(any(), any()) } returns AgentActionOutcome.Rejected(rejection)
            val response = post(toolCall("monomer_invoke", """{"workspaceId":"${workspaceId.value}","path":"workspace/explore/runs","action":"run",
                "input":{"kind":"SEMANTIC_EXPLORE_RUN","connectorId":"01942b04-6e00-7000-8000-000000000011",
                "query":{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":100}}}"""), "mcp-token")
                .expectStatus().isOk.expectHeader().doesNotExist("Retry-After")
                .expectBody().jsonPath("$.result.isError").isEqualTo(true)
                .jsonPath("$.error").doesNotExist().returnResult()
            val problem = ObjectMapper().readTree(problemJson(ObjectMapper().readTree(response.responseBody).at("/result/content/0/text").asText()))
            assertEquals(400, problem.path("status").asInt())
            assertEquals(code, problem.path("code").asText())
            assertEquals(false, problem.path("retryable").asBoolean())
            assertEquals(detail, problem.path("detail").asText())
        }
        coVerify(exactly = 2) { handler.invoke(any(), any()) }
    }

    @Test
    fun `Semantic engine faults return sanitized 500 Problems without retry after on MCP invoke`() {
        authenticated()
        admitExplorer()
        for (diagnostic in listOf("private SQL template diagnostic", "private MetricFlow internal diagnostic")) {
            coEvery { handler.invoke(any(), any()) } throws
                cc.monomer.semantic.application.SemanticMetricFlowEngineFaultException(IllegalStateException(diagnostic))
            val response = post(toolCall("monomer_invoke", """{"workspaceId":"${workspaceId.value}","path":"workspace/explore/runs","action":"run",
                "input":{"kind":"SEMANTIC_EXPLORE_RUN","connectorId":"01942b04-6e00-7000-8000-000000000011",
                "query":{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":100}}}"""), "mcp-token")
                .expectStatus().isOk.expectHeader().doesNotExist("Retry-After")
                .expectBody().jsonPath("$.result.isError").isEqualTo(true)
                .jsonPath("$.error").doesNotExist().returnResult()
            val problem = ObjectMapper().readTree(ObjectMapper().readTree(response.responseBody).at("/result/content/0/text").asText())
            assertEquals(500, problem.path("status").asInt())
            assertEquals("INTERNAL_ERROR", problem.path("code").asText())
            assertEquals(false, problem.path("retryable").asBoolean())
            assertEquals("The Agent request could not be completed. Read the target resource before resending a create or start request.", problem.path("detail").asText())
            assertTrue(!String(response.responseBody!!).contains(diagnostic))
        }
        coVerify(exactly = 2) { handler.invoke(any(), any()) }
    }

    @Test
    fun `MCP target IDs accept only lowercase canonical UUID text before admission`() {
        authenticated()
        for ((field, canonical, path) in listOf(Triple("workspaceId", workspaceId.value.toString(), "workspace"),
            Triple("organizationId", organizationId.value.toString(), "organization"))) {
            for (invalid in listOf(canonical.uppercase(), canonical.replace("-", ""))) {
                val result = post(toolCall("monomer_read", """{"$field":"$invalid","path":"$path"}"""), "mcp-token")
                    .expectStatus().isOk.expectBody().returnResult()
                val problem = ObjectMapper().readTree(ObjectMapper().readTree(result.responseBody).at("/result/content/0/text").asText())
                assertEquals("AGENT_REQUEST_INVALID", problem.path("code").asText())
                assertEquals(400, problem.path("status").asInt())
            }
        }
        coVerify { admission wasNot Called; handler wasNot Called; management wasNot Called }
    }

    @Test
    fun `MCP tool Problem and response header share the ingress request ID`() {
        authenticated()
        val result = post(toolCall("monomer_docs", """{"path":42}"""), "mcp-token")
            .expectStatus().isOk.expectBody().returnResult()
        val mapper = ObjectMapper()
        val envelope = mapper.readTree(result.responseBody)
        val problem = mapper.readTree(envelope.at("/result/content/0/text").asText())
        val id = result.responseHeaders.getFirst("X-Request-Id")!!
        assertEquals(id, problem.path("requestId").asText())
        assertEquals(id, problem.path("traceId").asText())
        assertTrue(id.startsWith("srv_"))
    }

    @Test
    fun `MCP Page search admits Viewer and returns the REST Page listing shape`() {
        authenticated()
        coEvery { admission.decide(any()) } answers {
            val request = firstArg<AgentWorkspaceAdmissionRequest>()
            assertEquals(WorkspaceRole.VIEWER, request.intent.requiredRole)
            AgentWorkspaceAdmissionDecision.Authorized(AgentWorkspaceRoleEvidence(OrganizationPlan.FREE,
                CanonicalPrincipal(userId, PrincipalKind.USER), organizationId, workspaceId, AuthorizationRevision(1),
                CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), WorkspaceRole.VIEWER, request.intent))
        }
        val pagePath = AgentResourcePath.parse("workspace/pages/01942b04-6e00-7000-8000-000000000010")
        val page = AgentResourceListingItem.Page(pagePath, AgentPageResourceBody(AgentPageKind.FOLDER,
            "Reports", null, "Reports", null, null, null, null, null, null, null))
        coEvery { handler.search(any(), any()) } returns AgentSearchOutcome.Found(AgentSearchResponse(listOf(AgentSearchQueryResult("Reports", listOf(page)))))
        val response = post(toolCall("monomer_search", """{"workspaceId":"${workspaceId.value}","path":"workspace/pages","queries":["Reports"],"kinds":["PAGE"],"limitPerQuery":20}"""), "mcp-token")
            .expectStatus().isOk.expectBody().returnResult().responseBody!!
        val mapper = ObjectMapper()
        val structured = mapper.readTree(response).at("/result/structuredContent")
        assertEquals("PAGE", structured.at("/results/0/items/0/kind").asText())
        assertEquals(pagePath.value, structured.at("/results/0/items/0/path").asText())
        assertEquals("Reports", structured.at("/results/0/items/0/body/displayTitle").asText())
        assertTrue(!structured.at("/results/0/items/0/body").has("createdBy"))
        val output = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(mapper.valueToTree<JsonNode>(McpToolCatalog(MonomerDocsReader("https://monomer.cc").agentApiReferenceUrl).tools.single { it["name"] == "monomer_search" }["outputSchema"]))
        assertTrue(output.validate(structured).isEmpty())
        coVerify(exactly = 1) { handler.search(match { it.role == WorkspaceRole.VIEWER }, match { it.kinds == setOf(AgentResourceKind.PAGE) }) }
    }

    @Test
    fun `dashboard action URLs match REST destinations and use configured origin`() {
        authenticated()
        coEvery { admission.decide(any()) } answers {
            val request = firstArg<AgentWorkspaceAdmissionRequest>()
            AgentWorkspaceAdmissionDecision.Authorized(AgentWorkspaceRoleEvidence(
                OrganizationPlan.FREE, CanonicalPrincipal(userId, PrincipalKind.USER), organizationId, workspaceId,
                AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"),
                WorkspaceRole.EXPLORER, request.intent))
        }
        val dashboardPath = "workspace/dashboards/01942b04-6e00-7000-8000-000000000010"
        coEvery { handler.create(any(), any()) } returns AgentActionOutcome.Completed(AgentActionResult.Create(
            AgentResourcePath.parse("workspace/dashboards"), AgentActionResultReference(AgentResourceKind.DASHBOARD, AgentResourcePath.parse(dashboardPath))))
        coEvery { handler.edit(any(), any()) } returns AgentActionOutcome.Completed(AgentActionResult.Edit(
            AgentResourcePath.parse("$dashboardPath/draft"), AgentActionResultReference(AgentResourceKind.DASHBOARD_DRAFT, AgentResourcePath.parse("$dashboardPath/draft"))))
        coEvery { handler.invoke(any(), any()) } returns AgentActionOutcome.Completed(AgentActionResult.Invoke(
            AgentResourcePath.parse("$dashboardPath/draft"), "apply", AgentActionStatus.COMPLETED, AgentActionResultReference(AgentResourceKind.DASHBOARD, AgentResourcePath.parse(dashboardPath))))
        val requests = listOf(
            Triple("create", """{"workspaceId":"${workspaceId.value}","path":"workspace/dashboards","input":{"kind":"DASHBOARD_DEFINITION","definition":{"schemaVersion":1,"meta":{"title":"Orders"},"parameters":{},"variables":{},"queries":{},"widgets":{},"layout":{"sections":[]}}}}""", dashboardPath),
            Triple("edit", """{"workspaceId":"${workspaceId.value}","path":"$dashboardPath/draft","revision":"1","patch":[{"op":"replace","path":"/definition/meta/title","value":"Revenue"}]}""", "$dashboardPath/draft"),
            Triple("invoke", """{"workspaceId":"${workspaceId.value}","path":"$dashboardPath/draft","action":"apply","input":{"kind":"DASHBOARD_DRAFT_APPLY","expectedRevision":"1"}}""", dashboardPath),
        )
        for ((operation, body, resultPath) in requests) {
            val expected = "https://app.example.com/workspaces/${workspaceId.value}/dashboards/${dashboardPath.substringAfterLast('/')}" + if (operation == "invoke") "" else "/builder"
            post(toolCall("monomer_$operation", body), "mcp-token").expectStatus().isOk.expectBody()
                .jsonPath("$.result.isError").isEqualTo(false)
                .jsonPath("$.result.structuredContent.webUrl").isEqualTo(expected)
                .jsonPath("$.result.structuredContent.result.path").isEqualTo(resultPath)
        }
        coEvery { admission.decide(any()) } returns AgentWorkspaceAdmissionDecision.Denied(AgentWorkspaceAdmissionDenial.FORBIDDEN, null)
        post(toolCall("monomer_edit", requests[1].second), "mcp-token").expectStatus().isOk.expectBody()
            .jsonPath("$.result.isError").isEqualTo(true).jsonPath("$.result.structuredContent.webUrl").doesNotExist()
    }

    @Test
    fun `monomer_read carries the same provenance as REST and satisfies its output schema`() {
        authenticated()
        coEvery { admission.decide(any()) } answers {
            val request = firstArg<AgentWorkspaceAdmissionRequest>()
            AgentWorkspaceAdmissionDecision.Authorized(AgentWorkspaceRoleEvidence(OrganizationPlan.FREE, CanonicalPrincipal(userId, PrincipalKind.USER), organizationId, workspaceId, AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), WorkspaceRole.EXPLORER, request.intent))
        }
        val time = java.time.Instant.parse("2026-10-03T00:00:00Z")
        val mina = AgentActor(userId.toString(), "Mina Park")
        val claude = AgentApp("https://claude.ai/oauth/mcp-oauth-client-metadata", "Claude", "claude.ai")
        val dashboardPath = "workspace/dashboards/01942b04-6e00-7000-8000-000000000010"
        val definition = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"schemaVersion":1,"meta":{"title":"Orders"},"parameters":{},"variables":{},"queries":{},"widgets":{},"layout":{"sections":[]}}""",
        ) as kotlinx.serialization.json.JsonObject
        val resources = listOf(
            AgentResourceEnvelope.Dashboard(AgentResourcePath.parse(dashboardPath),
                AgentDashboardResourceBody(definition, time, time, mina, CredentialSurface.WEB, null), emptyList()),
            AgentResourceEnvelope.DashboardDraft(AgentResourcePath.parse("$dashboardPath/draft"), AgentRevision(2),
                AgentDashboardDraftResourceBody(definition, emptyMap(), emptyMap(), emptyMap(), emptyMap(), time, time, mina, time, CredentialSurface.AGENT, claude), emptyList()),
            AgentResourceEnvelope.PageVersion(AgentResourcePath.parse("workspace/pages/01942b04-6e00-7000-8000-000000000011/versions/1"),
                AgentPageVersionResourceBody(AgentPageContentFormat.MARKDOWN, 12, "a".repeat(64), time, AgentPageActor(AgentPageActorKind.AGENT, userId.toString(), "Mina Park", claude)), emptyList()),
        )
        val mapper = ObjectMapper()
        val output = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(mapper.valueToTree<JsonNode>(McpToolCatalog(MonomerDocsReader("https://monomer.cc").agentApiReferenceUrl).tools.single { it["name"] == "monomer_read" }["outputSchema"]))
        for (resource in resources) {
            coEvery { handler.read(any(), any()) } returns AgentResourceOutcome.Found(resource)
            val response = post(toolCall("monomer_read", """{"workspaceId":"${workspaceId.value}","path":"${resource.path.value}"}"""), "mcp-token")
                .expectStatus().isOk.expectBody().returnResult().responseBody!!
            val structured = mapper.readTree(response).at("/result/structuredContent")
            val rest = mapper.readTree(mapper.writeValueAsString(cc.monomer.control.publicapi.AgentWorkspaceJson.agentResourceJson(AgentRequestId("srv_rest"), resource)))
            assertEquals(rest.path("body"), structured.path("body"), resource.kind.name)
            assertTrue(output.validate(structured).isEmpty(), "${resource.kind}: ${output.validate(structured)}")
        }
    }

    @Test
    fun `explicit Arrow read returns lossless metadata and resource link only for requested download`() {
        authenticated()
        coEvery { admission.decide(any()) } answers {
            val request = firstArg<AgentWorkspaceAdmissionRequest>()
            AgentWorkspaceAdmissionDecision.Authorized(AgentWorkspaceRoleEvidence(OrganizationPlan.FREE, CanonicalPrincipal(userId, PrincipalKind.USER), organizationId, workspaceId, AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), WorkspaceRole.OWNER, request.intent))
        }
        val path = "workspace/explore/runs/01942b04-6e00-7000-8000-000000000021/result"
        val link = AgentExploreResultDownloadLink(
            "https://objects.example.test/result.arrow?signature=fixture", "result.arrow",
            "application/vnd.apache.arrow.file", "18446744073709551615", java.time.Instant.parse("2026-09-07T00:05:00Z"),
        )
        var advertiseDownload = true
        coEvery { handler.read(any(), any()) } answers {
            assertEquals(ExpectedOAuthResource.AGENT_MCP, firstArg<AgentWorkspaceCaller>().expectedOAuthResource)
            AgentResourceOutcome.Found(AgentResourceEnvelope.ExploreResult(
                AgentResourcePath.parse(path), AgentExploreResultResourceBody(
                    emptyList(), emptyList(), "0", 0, false, java.time.Instant.parse("2026-09-07T01:00:00Z"),
                    if (advertiseDownload) link else null,
                    reportingTimeZone = null,
                ), emptyList(),
            ))
        }
        val explicit = toolCall("monomer_read", """{"workspaceId":"${workspaceId.value}","path":"$path","download":"arrow"}""")
        post(explicit, "mcp-token").expectStatus().isOk.expectHeader().valueEquals("Cache-Control", "no-store")
            .expectBody().jsonPath("$.result.isError").isEqualTo(false)
            .jsonPath("$.result.structuredContent.body.download.byteSize").isEqualTo("18446744073709551615")
            .jsonPath("$.result.content[1].type").isEqualTo("resource_link")
            .jsonPath("$.result.content[1].uri").isEqualTo(link.url)
            .jsonPath("$.result.content[1].mimeType").isEqualTo(link.mediaType)
        val normal = toolCall("monomer_read", """{"workspaceId":"${workspaceId.value}","path":"$path"}""")
        post(normal, "mcp-token").expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(true)
            .jsonPath("$.result.structuredContent").doesNotExist()
        advertiseDownload = false
        post(normal, "mcp-token").expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(false)
            .jsonPath("$.result.structuredContent.body.download").doesNotExist().jsonPath("$.result.content[1]").doesNotExist()
        post(explicit, "mcp-token").expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(true)
    }

    @Test
    fun `authenticated initialize negotiates JSON stateless transport and returns static catalog`() {
        authenticated()
        post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2099-01-01","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""", "mcp-token")
            .expectStatus().isOk.expectHeader().doesNotExist("MCP-Session-Id").expectBody()
            .jsonPath("$.result.protocolVersion").isEqualTo("2025-11-25").jsonPath("$.result.capabilities.tools.listChanged").isEqualTo(false)
        post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", "mcp-token").expectStatus().isAccepted.expectBody().isEmpty
        post("""{"jsonrpc":"2.0","id":"ping","method":"ping"}""", "mcp-token").expectStatus().isOk.expectBody().jsonPath("$.result").isMap
        post("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", "mcp-token").expectStatus().isOk.expectBody()
            .jsonPath("$.result.tools.length()").isEqualTo(10).jsonPath("$.result.tools[0].inputSchema.oneOf[2].properties.workspaceId.format").isEqualTo("uuid")
        coVerify(exactly = 4) { oauth.validateCredential(PresentedCredential("mcp-token"), ExpectedOAuthResource.AGENT_MCP) }
        coVerify { admission wasNot Called; handler wasNot Called }
    }

    @Test
    fun `authenticated initialize advertises a bundled PNG and server metadata`() {
        authenticated()
        for (version in listOf("2025-11-25", "2025-06-18", "2025-03-26")) {
            val response = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$version","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""", "mcp-token", version = version)
                .expectStatus().isOk.expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().returnResult().responseBody!!
            val result = ObjectMapper().readTree(response).path("result")
            assertEquals(version, result.path("protocolVersion").asText())
            val info = result.path("serverInfo")
            assertEquals(setOf("name", "version", "description", "websiteUrl", "icons"), info.fieldNames().asSequence().toSet())
            assertEquals("Monomer", info.path("name").asText())
            assertEquals("1.0.0", info.path("version").asText())
            assertEquals("Explore data, build semantic models, and manage dashboards in your Monomer workspaces.", info.path("description").asText())
            assertEquals("https://monomer.cc", info.path("websiteUrl").asText())
            assertEquals(1, info.path("icons").size())
            val icon = info.path("icons").single()
            assertEquals(setOf("src", "mimeType", "sizes"), icon.fieldNames().asSequence().toSet())
            assertEquals("image/png", icon.path("mimeType").asText())
            assertEquals(listOf("512x512"), icon.path("sizes").map { it.asText() })
            val uri = icon.path("src").asText()
            assertTrue(uri.startsWith("data:image/png;base64,"))
            val bytes = Base64.getDecoder().decode(uri.removePrefix("data:image/png;base64,"))
            assertContentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), bytes.take(8).toByteArray())
            val image = ImageIO.read(bytes.inputStream())
            assertEquals(512, image.width)
            assertEquals(512, image.height)
            assertContentEquals(checkNotNull(javaClass.getResourceAsStream("/brand/monomer-favicon.png")).use { it.readBytes() }, bytes)
        }
        coVerify(exactly = 3) { oauth.validateCredential(PresentedCredential("mcp-token"), ExpectedOAuthResource.AGENT_MCP) }
        coVerify { admission wasNot Called; handler wasNot Called; docs wasNot Called; management wasNot Called }
    }

    @Test
    fun `authenticated origin protocol media and RPC shape are validated before product use`() {
        authenticated()
        for (method in listOf(HttpMethod.GET, HttpMethod.DELETE)) {
            client.method(method).uri("/mcp").headers { it.setBearerAuth("mcp-token") }.exchange()
                .expectStatus().isEqualTo(405).expectHeader().valueEquals("Allow", "POST")
            client.method(method).uri("/mcp").header("Origin", "https://evil.example").headers { it.setBearerAuth("mcp-token") }.exchange()
                .expectStatus().isForbidden
        }
        client.post().uri("/mcp").header("Origin", "null").headers { it.setBearerAuth("mcp-token") }.exchange().expectStatus().isForbidden
        client.post().uri("/mcp").headers { it.setBearerAuth("mcp-token") }.contentType(MediaType.TEXT_PLAIN).exchange().expectStatus().isEqualTo(415)
        client.post().uri("/mcp").headers { it.setBearerAuth("mcp-token") }.contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isEqualTo(406)
        post("{}", "mcp-token", version = "invalid").expectStatus().isBadRequest
        post("[]", "mcp-token").expectStatus().isBadRequest.expectBody().jsonPath("$.error.code").isEqualTo(-32600)
        post("{", "mcp-token").expectStatus().isBadRequest.expectBody().jsonPath("$.error.code").isEqualTo(-32700)
        post("""{"jsonrpc":"2.0","id":null,"method":"ping"}""", "mcp-token").expectStatus().isBadRequest
        post("""{"jsonrpc":"2.0","id":1,"method":"ping","id":2}""", "mcp-token").expectStatus().isBadRequest
        post("""{"jsonrpc":"2.0","id":1,"method":"unknown"}""", "mcp-token").expectStatus().isOk.expectBody().jsonPath("$.error.code").isEqualTo(-32601)
        coVerify { admission wasNot Called; handler wasNot Called }
    }

    @Test
    fun `all MCP methods and notifications require valid MCP credentials before RPC handling`() {
        val messages = listOf(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""",
            """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
            """{"jsonrpc":"2.0","id":3,"method":"ping"}""",
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            toolCall("monomer_list", """{"path":"workspaces"}"""),
        )
        coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_MCP) } returns OAuthOutcome.Failure(OAuthError.INVALID_GRANT)
        for (message in messages) {
            for (token in listOf(null, "invalid-token", "rest-token")) assertUnauthorized(post(message, token))
        }
        coVerify(exactly = 5) { oauth.validateCredential(PresentedCredential("invalid-token"), ExpectedOAuthResource.AGENT_MCP) }
        coVerify(exactly = 5) { oauth.validateCredential(PresentedCredential("rest-token"), ExpectedOAuthResource.AGENT_MCP) }
        coVerify { admission wasNot Called; handler wasNot Called; docs wasNot Called; management wasNot Called }
    }

    @Test
    fun `GET and DELETE require MCP credentials before origin and method handling`() {
        coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_MCP) } returns OAuthOutcome.Failure(OAuthError.INVALID_GRANT)
        for (method in listOf(HttpMethod.GET, HttpMethod.DELETE)) {
            for (token in listOf(null, "invalid-token")) {
                assertUnauthorized(client.method(method).uri("/mcp").header("Origin", "https://evil.example")
                    .headers { if (token != null) it.setBearerAuth(token) }.exchange())
            }
        }
        coVerify(exactly = 2) { oauth.validateCredential(PresentedCredential("invalid-token"), ExpectedOAuthResource.AGENT_MCP) }
        coVerify { admission wasNot Called; handler wasNot Called; docs wasNot Called; management wasNot Called }
    }

    @Test
    fun `authentication precedes invalid transport headers and malformed JSON`() {
        coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_MCP) } returns OAuthOutcome.Failure(OAuthError.INVALID_GRANT)
        for (token in listOf(null, "invalid-token")) {
            assertUnauthorized(client.post().uri("/mcp").header("Origin", "null").header("MCP-Protocol-Version", "invalid")
                .contentType(MediaType.TEXT_PLAIN).accept(MediaType.TEXT_PLAIN)
                .headers { if (token != null) it.setBearerAuth(token) }.bodyValue("{").exchange())
            assertUnauthorized(post("{", token))
            assertUnauthorized(post("[]", token))
            assertUnauthorized(post("""{"jsonrpc":"2.0","method":"notifications/unknown"}""", token))
        }
        coVerify { admission wasNot Called; handler wasNot Called; docs wasNot Called; management wasNot Called }
    }

    @Test
    fun `malformed and duplicate Authorization headers never reach Identity`() {
        for (headers in listOf(listOf("Basic credential"), listOf("Bearer "), listOf("Bearer invalid token"),
            listOf("Bearer mcp-token", "Bearer mcp-token"))) {
            assertUnauthorized(client.post().uri("/mcp").header("Authorization", *headers.toTypedArray()).bodyValue("{").exchange())
        }
        coVerify { oauth wasNot Called; admission wasNot Called; handler wasNot Called }
    }

    @Test
    fun `initialized clients still authenticate every subsequent request`() {
        coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_MCP) } returnsMany listOf(
            OAuthOutcome.Success(OAuthAgentIdentity(userId, clientId = "https://client.example/metadata.json")),
            OAuthOutcome.Failure(OAuthError.INVALID_GRANT),
        )
        post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""", "mcp-token")
            .expectStatus().isOk
        assertUnauthorized(post("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", "mcp-token"))
        coVerify(exactly = 2) { oauth.validateCredential(PresentedCredential("mcp-token"), ExpectedOAuthResource.AGENT_MCP) }
        coVerify { admission wasNot Called; handler wasNot Called }
    }

    @Test
    fun `workspace discovery uses authenticated Identity grant and explicit typed fields`() {
        authenticated()
        val sampleWorkspaceId = WorkspaceId.generate()
        coEvery { oauth.listWorkspaces(any(), ExpectedOAuthResource.AGENT_MCP) } returns OAuthOutcome.Success(listOf(
            OAuthWorkspace(workspaceId, organizationId, "Team", "Data", null),
            OAuthWorkspace(sampleWorkspaceId, organizationId, "Team", "Acme Cloud (sample)", OAuthWorkspaceSample("acme-cloud@3")),
        ))
        val body = post("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"monomer_list","arguments":{"path":"workspaces"}}}""", "mcp-token")
            .expectStatus().isOk.expectBody().jsonPath("$.result.structuredContent.workspaces[0].workspaceId").isEqualTo(workspaceId.value.toString())
            .jsonPath("$.result.structuredContent.workspaces[0].displayName").isEqualTo("Data")
            .jsonPath("$.result.structuredContent.workspaces[0].organizationId").isEqualTo(organizationId.value.toString())
            .jsonPath("$.result.structuredContent.workspaces[1].workspaceId").isEqualTo(sampleWorkspaceId.value.toString())
            .jsonPath("$.result.structuredContent.workspaces[1].sample.dataset").isEqualTo("acme-cloud@3")
            .returnResult().responseBody!!
        val workspaces = ObjectMapper().readTree(body).at("/result/structuredContent/workspaces")
        assertTrue(workspaces[0].has("sample") && workspaces[0]["sample"].isNull)
        assertEquals(setOf("dataset"), workspaces[1]["sample"].fieldNames().asSequence().toSet())
        assertEquals(setOf("workspaceId", "organizationId", "organizationDisplayName", "displayName", "sample"), workspaces[1].fieldNames().asSequence().toSet())
        coVerify(exactly = 1) { oauth.listWorkspaces(PresentedCredential("mcp-token"), ExpectedOAuthResource.AGENT_MCP) }
        coVerify { admission wasNot Called; handler wasNot Called }
    }

    @Test
    fun `real decoder and authenticator pass MCP audience to in-process handler`() {
        authenticated()
        coEvery { admission.decide(any()) } answers {
            val request = firstArg<AgentWorkspaceAdmissionRequest>()
            assertEquals(ExpectedOAuthResource.AGENT_MCP, request.expectedOAuthResource)
            AgentWorkspaceAdmissionDecision.Authorized(AgentWorkspaceRoleEvidence(OrganizationPlan.FREE, CanonicalPrincipal(userId, PrincipalKind.USER), organizationId, workspaceId, AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), WorkspaceRole.OWNER, request.intent))
        }
        coEvery { handler.list(any(), any()) } answers {
            assertEquals(ExpectedOAuthResource.AGENT_MCP, firstArg<AgentWorkspaceCaller>().expectedOAuthResource)
            AgentListingOutcome.Found(AgentResourceListingResponse(emptyList(), null, emptyList()))
        }
        coEvery { oauth.listWorkspaces(any(), ExpectedOAuthResource.AGENT_MCP) } returns OAuthOutcome.Success(listOf(OAuthWorkspace(workspaceId, organizationId, "Team", "Data", null)))
        val discovery = post(toolCall("monomer_list", """{"path":"workspaces"}"""), "mcp-token")
            .expectStatus().isOk.expectBody().returnResult().responseBody!!
        val selectedWorkspaceId = ObjectMapper().readTree(discovery).at("/result/structuredContent/workspaces/0/workspaceId").asText()
        post(toolCall("monomer_list", """{"workspaceId":"$selectedWorkspaceId","path":"workspace","limit":20}"""), "mcp-token")
            .expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(false).jsonPath("$.result.structuredContent.items").isArray
        post(toolCall("monomer_list", """{"workspaceId":"${workspaceId.value}","path":"workspace","unclaimed":true}"""), "mcp-token")
            .expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(true)
        coVerify(exactly = 1) { handler.list(any(), any()) }
    }

    @Test
    fun `Workspace list accepts integral JSON numbers and passes pagination through admission`() {
        authenticated()
        coEvery { admission.decide(any()) } answers {
            val request = firstArg<AgentWorkspaceAdmissionRequest>()
            assertEquals(workspaceId, request.workspaceId)
            assertEquals(ExpectedOAuthResource.AGENT_MCP, request.expectedOAuthResource)
            AgentWorkspaceAdmissionDecision.Authorized(AgentWorkspaceRoleEvidence(
                OrganizationPlan.FREE, CanonicalPrincipal(userId, PrincipalKind.USER), organizationId, workspaceId,
                AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT,
                AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), WorkspaceRole.EXPLORER, request.intent,
            ))
        }
        coEvery { handler.list(any(), any()) } answers {
            val caller = firstArg<AgentWorkspaceCaller>()
            assertEquals(workspaceId, caller.workspaceId)
            assertEquals(ExpectedOAuthResource.AGENT_MCP, caller.expectedOAuthResource)
            assertEquals(AgentListRequest(AgentResourcePath.parse("workspace/metrics"), "next-page", 20), secondArg())
            AgentListingOutcome.Found(AgentResourceListingResponse(emptyList(), null, emptyList()))
        }
        for (limit in listOf("20", "20.0", "2e1")) {
            post(toolCall("monomer_list", """{"workspaceId":"${workspaceId.value}","path":"workspace/metrics","limit":$limit,"cursor":"next-page"}"""), "mcp-token")
                .expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(false)
                .jsonPath("$.result.structuredContent.items").isArray
        }
        coVerify(exactly = 3) { admission.decide(any()) }
        coVerify(exactly = 3) { handler.list(any(), any()) }
        coVerify { management wasNot Called }
    }

    @Test
    fun `Organization list accepts integral JSON numbers and preserves authenticated scope`() {
        authenticated()
        coEvery { management.list(any<AgentOrganizationCaller>(), any()) } answers {
            val caller = firstArg<AgentOrganizationCaller>()
            assertEquals(organizationId, caller.organizationId)
            assertEquals(userId.value.toString(), caller.actorPrincipalId)
            assertEquals(ExpectedOAuthResource.AGENT_MCP, caller.expectedOAuthResource)
            assertEquals(AgentListRequest(AgentResourcePath.parse("organization/members"), "next-page", 20), secondArg())
            AgentListingOutcome.Found(AgentResourceListingResponse(emptyList(), null, emptyList()))
        }
        for (limit in listOf("20", "20.0", "2e1")) {
            post(toolCall("monomer_list", """{"organizationId":"${organizationId.value}","path":"organization/members","limit":$limit,"cursor":"next-page"}"""), "mcp-token")
                .expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(false)
                .jsonPath("$.result.structuredContent.items").isArray
        }
        coVerify(exactly = 3) { management.list(any<AgentOrganizationCaller>(), any()) }
        coVerify(exactly = 6) { oauth.validateCredential(PresentedCredential("mcp-token"), ExpectedOAuthResource.AGENT_MCP) }
        coVerify { admission wasNot Called; handler wasNot Called }
    }

    @Test
    fun `resource list rejects fractional string and out of range limits before owner dispatch`() {
        authenticated()
        val mapper = ObjectMapper()
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(mapper.valueToTree<JsonNode>(McpToolCatalog(MonomerDocsReader("https://monomer.cc").agentApiReferenceUrl).tools.single { it["name"] == "monomer_list" }["inputSchema"]))
        for (target in listOf(
            """"workspaceId":"${workspaceId.value}","path":"workspace/metrics"""",
            """"organizationId":"${organizationId.value}","path":"organization/members"""",
        )) {
            for (limit in listOf("20.5", "0.5", "\"20\"", "null", "0", "101", "2147483648", "1e100")) {
                val arguments = """{$target,"limit":$limit}"""
                assertTrue(schema.validate(mapper.readTree(arguments)).isNotEmpty(), arguments)
                val problem = validationProblem("monomer_list", arguments)
                assertTrue(problem.path("violations").any { it.path("field").asText() == "limit" }, "$arguments: $problem")
            }
        }
        coVerify { admission wasNot Called; handler wasNot Called; management wasNot Called }
    }

    @Test
    fun `integral numeric list inputs retain credential and workspace admission gates`() {
        authenticated()
        coEvery { admission.decide(any()) } returns AgentWorkspaceAdmissionDecision.Denied(AgentWorkspaceAdmissionDenial.FORBIDDEN, null)
        val workspaceCall = toolCall("monomer_list", """{"workspaceId":"${workspaceId.value}","path":"workspace/metrics","limit":20.0}""")
        post(workspaceCall, "mcp-token").expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(true)
        coVerify(exactly = 1) { admission.decide(any()) }
        coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_MCP) } returns OAuthOutcome.Failure(OAuthError.INVALID_GRANT)
        for (call in listOf(
            workspaceCall,
            toolCall("monomer_list", """{"organizationId":"${organizationId.value}","path":"organization/members","limit":2e1}"""),
        )) post(call, "mcp-token").expectStatus().isUnauthorized
        coVerify(exactly = 1) { admission.decide(any()) }
        coVerify { handler wasNot Called; management wasNot Called }
    }

    @Test
    fun `Organization and Workspace discovery accept only path without pagination`() {
        authenticated()
        coEvery { oauth.listOrganizations(any(), ExpectedOAuthResource.AGENT_MCP) } returns OAuthOutcome.Success(listOf(OAuthOrganization(organizationId, "Team", "MEMBER")))
        coEvery { oauth.listWorkspaces(any(), ExpectedOAuthResource.AGENT_MCP) } returns OAuthOutcome.Success(listOf(OAuthWorkspace(workspaceId, organizationId, "Team", "Data", null)))
        val mapper = ObjectMapper()
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(mapper.valueToTree<JsonNode>(McpToolCatalog(MonomerDocsReader("https://monomer.cc").agentApiReferenceUrl).tools.single { it["name"] == "monomer_list" }["inputSchema"]))
        for (path in listOf("organizations", "workspaces")) {
            val arguments = """{"path":"$path"}"""
            assertTrue(schema.validate(mapper.readTree(arguments)).isEmpty(), arguments)
            post(toolCall("monomer_list", arguments), "mcp-token").expectStatus().isOk.expectBody()
                .jsonPath("$.result.isError").isEqualTo(false).jsonPath("$.result.structuredContent.$path.length()").isEqualTo(1)
            val withLimit = """{"path":"$path","limit":20}"""
            assertTrue(schema.validate(mapper.readTree(withLimit)).isNotEmpty(), withLimit)
            validationProblem("monomer_list", withLimit)
        }
        coVerify(exactly = 1) { oauth.listOrganizations(PresentedCredential("mcp-token"), ExpectedOAuthResource.AGENT_MCP) }
        coVerify(exactly = 1) { oauth.listWorkspaces(PresentedCredential("mcp-token"), ExpectedOAuthResource.AGENT_MCP) }
        coVerify { admission wasNot Called; handler wasNot Called; management wasNot Called }
    }

    @Test
    fun `membership loss is safe tool error and admission credential loss is HTTP401`() {
        authenticated()
        val call = toolCall("monomer_list", """{"workspaceId":"${workspaceId.value}","path":"workspace","limit":20}""")
        coEvery { admission.decide(any()) } returns AgentWorkspaceAdmissionDecision.Denied(AgentWorkspaceAdmissionDenial.FORBIDDEN, null)
        post(call, "mcp-token").expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(true)
        coEvery { admission.decide(any()) } returns AgentWorkspaceAdmissionDecision.Denied(AgentWorkspaceAdmissionDenial.UNAUTHENTICATED, null)
        post(call, "mcp-token").expectStatus().isUnauthorized
        coVerify { handler wasNot Called }
    }

    @Test
    fun `a role denial tells the agent which role to ask for and whom to ask`() {
        authenticated()
        val call = toolCall("monomer_list", """{"workspaceId":"${workspaceId.value}","path":"workspace","limit":20}""")
        val problem = AgentWorkspaceProblem(
            "ROLE_REQUIRED", "Editor role required", "Changing a metric in Revenue Analytics needs the Editor role. Sam Kim is an Explorer.",
            AgentWorkspaceProblemStatus.FORBIDDEN, false, null, emptyList(), listOf("Ask Mina Park to give Sam Kim the Editor role, then try again."),
            emptyList(), null, cc.monomer.agent.api.AgentRoleRequirement("EDITOR", "EXPLORER", listOf("Mina Park")),
            partitionFilters = emptyList(),
        )
        coEvery { admission.decide(any()) } returns AgentWorkspaceAdmissionDecision.Denied(AgentWorkspaceAdmissionDenial.FORBIDDEN, problem)
        post(call, "mcp-token").expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(true)
            .consumeWith { response ->
                val text = com.fasterxml.jackson.databind.ObjectMapper().readTree(response.responseBody).path("result").path("content")[0].path("text").asText()
                val body = com.fasterxml.jackson.databind.ObjectMapper().readTree(text)
                assertEquals("ROLE_REQUIRED", body.path("code").asText())
                assertEquals(403, body.path("status").asInt())
                assertEquals("EDITOR", body.path("requiredRole").asText())
                assertEquals("EXPLORER", body.path("currentRole").asText())
                assertEquals(listOf("Mina Park"), body.path("askOwners").map { it.asText() })
                assertEquals(problem.detail, body.path("detail").asText())
            }
        coEvery { admission.decide(any()) } returns AgentWorkspaceAdmissionDecision.Denied(AgentWorkspaceAdmissionDenial.NOT_FOUND, null)
        post(call, "mcp-token").expectStatus().isOk.expectBody()
            .consumeWith { response -> assertTrue(!String(response.responseBody!!).contains("Mina Park")) }
        coVerify { handler wasNot Called }
    }

    @Test
    fun `SQL start rejection preserves owner causes across Agent REST MCP and discovery`() {
        authenticated()
        coEvery { admission.decide(any()) } answers {
            val request = firstArg<AgentWorkspaceAdmissionRequest>()
            AgentWorkspaceAdmissionDecision.Authorized(AgentWorkspaceRoleEvidence(
                OrganizationPlan.FREE, CanonicalPrincipal(userId, PrincipalKind.USER), organizationId, workspaceId,
                AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT,
                cc.monomer.identity.api.AgentClientId("https://client.example/metadata.json"), WorkspaceRole.EXPLORER, request.intent))
        }
        val launch = mockk<cc.monomer.control.explore.ExploreSqlLaunchService>()
        val adapter = cc.monomer.control.agent.AgentExploreOwnerAdapter(
            mockk(), launch, mockk(), java.time.Clock.systemUTC(), ObjectMapper(), mockk(),
        )
        coEvery { handler.invoke(any(), any()) } coAnswers {
            adapter.invoke(firstArg<AgentWorkspaceCaller>(), secondArg<AgentInvokeRequest>())
        }
        val restClient = WebTestClient.bindToRouterFunction(cc.monomer.control.publicapi.AgentWorkspacePublicApiRouter(
            AgentWorkspaceHttpPorts(AgentWorkspaceCallerAuthenticator(admission), handler, AgentWorkspaceContractDecoder()),
            "https://api.example.com/.well-known/oauth-protected-resource/v2/workspaces",
            cc.monomer.control.publicapi.ControlPublicApiProperties(java.net.URI("https://app.example.com")),
            object : cc.monomer.control.publicapi.AgentWorkspaceTransportCredentialResolver {
                override suspend fun resolve(request: org.springframework.web.reactive.function.server.ServerRequest) =
                    cc.monomer.control.publicapi.AgentWorkspaceTransportCredentialResolution.Resolved(PresentedCredential("rest-token"))
            },

            cc.monomer.control.publicapi.PublicControlContractValidator(),
            oauth,
        ).routes()).webFilter<org.springframework.test.web.reactive.server.WebTestClient.RouterFunctionSpec>(cc.monomer.control.observability.RequestCorrelationTestSupport.filter()).build()
        val mapper = ObjectMapper()
        val body = """{"path":"workspace/explore/runs","action":"run","input":{"kind":"SQL_EXPLORE_RUN","connectorId":"01942b04-6e00-7000-8000-000000000004","sql":"SELECT 1"}}"""
        val cases = listOf(
            Triple("CONNECTION_FAILED", "CONNECTION_FAILED", true),
            Triple("CREDENTIAL_UNAVAILABLE", "CREDENTIAL_UNAVAILABLE", true),
            Triple("AUTHENTICATION_FAILED", "AUTHENTICATION_FAILED", false),
            Triple("RESOURCE_EXHAUSTED", "RESOURCE_EXHAUSTED", true),
            Triple("RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", true),
            Triple("RAW_SQL_VALIDATION_FAILED", "RAW_SQL_VALIDATION_FAILED", true),
            Triple("EXECUTION_UNAVAILABLE", "OWNER_UNAVAILABLE", true),
        )
        for ((failureCode, problemCode, retryable) in cases) {
            coEvery { launch.start(any()) } returns cc.monomer.control.explore.ExploreSqlLaunchOutcome.ExecutionUnavailable(failureCode)
            val restResult = restClient.post().uri("/v2/workspaces/${workspaceId.value}/agent/invoke")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
                .expectStatus().isEqualTo(503).expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().returnResult()
            val mcpResult = post(toolCall("monomer_invoke", """{"workspaceId":"${workspaceId.value}",${body.drop(1)}"""), "mcp-token")
                .expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(true)
                .jsonPath("$.result.structuredContent").doesNotExist().returnResult()
            val rest = mapper.readTree(restResult.responseBody)
            val mcp = mapper.readTree(problemJson(mapper.readTree(mcpResult.responseBody).at("/result/content/0/text").asText()))
            for (problem in listOf(rest, mcp)) {
                assertEquals(problemCode, problem.path("code").asText())
                assertEquals(503, problem.path("status").asInt())
                assertEquals(retryable, problem.path("retryable").asBoolean())
                cc.monomer.control.publicapi.PublicControlContractValidator().problem(mapper.convertValue(problem, object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {}))
            }
            assertEquals(rest.path("detail"), mcp.path("detail"))
            assertEquals(rest.path("title"), mcp.path("title"))
            assertEquals(restResult.responseHeaders.getFirst("Retry-After"), mcpResult.responseHeaders.getFirst("Retry-After"))
            assertEquals(if (retryable) "2" else null, restResult.responseHeaders.getFirst("Retry-After"))
        }
        val described = McpToolCatalog(MonomerDocsReader("https://monomer.cc").agentApiReferenceUrl).describe(mapper.readTree("""{"operations":[{"operation":"INVOKE","path":"workspace/explore/runs","action":"run","inputKind":"SQL_EXPLORE_RUN"}]}"""))
        val description = described.path("operations").toString()
        for (code in cases.map { it.second }) assertTrue(description.contains(code), code)
        assertTrue(description.contains("HTTP 503"))
        assertTrue(description.contains("AUTHENTICATION_FAILED has retryable=false"))
    }

    @Test
    fun `admission and owner failures carry the same Problem Details as REST`() {
        authenticated()
        val restClient = WebTestClient.bindToRouterFunction(cc.monomer.control.publicapi.AgentWorkspacePublicApiRouter(
            AgentWorkspaceHttpPorts(AgentWorkspaceCallerAuthenticator(admission), handler, AgentWorkspaceContractDecoder()),
            "https://api.example.com/.well-known/oauth-protected-resource/v2/workspaces",
            cc.monomer.control.publicapi.ControlPublicApiProperties(java.net.URI("https://app.example.com")),
            object : cc.monomer.control.publicapi.AgentWorkspaceTransportCredentialResolver {
                override suspend fun resolve(request: org.springframework.web.reactive.function.server.ServerRequest) =
                    cc.monomer.control.publicapi.AgentWorkspaceTransportCredentialResolution.Resolved(PresentedCredential("rest-token"))
            },

            cc.monomer.control.publicapi.PublicControlContractValidator(),
            oauth,
        ).routes()).webFilter<org.springframework.test.web.reactive.server.WebTestClient.RouterFunctionSpec>(cc.monomer.control.observability.RequestCorrelationTestSupport.filter()).build()
        val mapper = ObjectMapper()
        fun mcpFailure(): Pair<JsonNode, String?> {
            val result = post(toolCall("monomer_list", """{"workspaceId":"${workspaceId.value}","path":"workspace","limit":20}"""), "mcp-token")
                .expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(true).jsonPath("$.error").doesNotExist().returnResult()
            return mapper.readTree(mapper.readTree(result.responseBody).at("/result/content/0/text").asText()) to result.responseHeaders.getFirst("Retry-After")
        }
        fun restFailure(): Pair<JsonNode, String?> {
            val result = restClient.post().uri("/v2/workspaces/${workspaceId.value}/agent/list").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"path":"workspace","limit":20}""").exchange().expectBody().returnResult()
            return mapper.readTree(result.responseBody) to result.responseHeaders.getFirst("Retry-After")
        }
        fun withoutRequestReferences(problem: JsonNode) = problem.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply { remove(listOf("requestId", "traceId", "instance")) }
        fun assertSameAsRest(): JsonNode {
            val (mcp, mcpRetryAfter) = mcpFailure()
            val (rest, restRetryAfter) = restFailure()
            assertEquals(withoutRequestReferences(rest), withoutRequestReferences(mcp))
            assertEquals(restRetryAfter, mcpRetryAfter)
            assertEquals("/mcp", mcp.path("instance").asText())
            assertTrue(mcp.path("requestId").asText().startsWith("srv_"))
            assertEquals(mcp.path("requestId"), mcp.path("traceId"))
            return mcp
        }
        fun expected(code: String, title: String, status: Int, detail: String, retryable: Boolean) = mapper.createObjectNode()
            .put("type", "https://monomer.cc/problems/${code.lowercase().replace('_', '-')}").put("title", title).put("detail", detail)
            .put("status", status).put("code", code).put("retryable", retryable)
        val notFound = expected("NOT_FOUND", "Not Found", 404, "The requested Agent resource was not found.", false)
        val forbidden = expected("ROLE_REQUIRED", "Role required", 403, "The caller does not have the role required to access this resource.", false)
        val unavailable = expected("OWNER_UNAVAILABLE", "Service Unavailable", 503, "The Agent workspace owner is temporarily unavailable.", true)

        for ((denial, problem) in listOf(AgentWorkspaceAdmissionDenial.NOT_FOUND to notFound, AgentWorkspaceAdmissionDenial.FORBIDDEN to forbidden, AgentWorkspaceAdmissionDenial.UNAVAILABLE to unavailable)) {
            coEvery { admission.decide(any()) } returns AgentWorkspaceAdmissionDecision.Denied(denial, null)
            assertEquals(problem, withoutRequestReferences(assertSameAsRest()))
        }
        assertEquals("2", mcpFailure().second)
        coEvery { admission.decide(any()) } returns AgentWorkspaceAdmissionDecision.Denied(AgentWorkspaceAdmissionDenial.NOT_FOUND, null)
        assertEquals(null, mcpFailure().second)

        coEvery { admission.decide(any()) } answers {
            AgentWorkspaceAdmissionDecision.Authorized(AgentWorkspaceRoleEvidence(OrganizationPlan.FREE, CanonicalPrincipal(userId, PrincipalKind.USER), organizationId, workspaceId, AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), WorkspaceRole.OWNER, firstArg<AgentWorkspaceAdmissionRequest>().intent))
        }
        for ((outcome, problem) in listOf(AgentListingOutcome.NotFound to notFound, AgentListingOutcome.Forbidden to forbidden, AgentListingOutcome.Unavailable to unavailable)) {
            coEvery { handler.list(any(), any()) } returns outcome
            assertEquals(problem, withoutRequestReferences(assertSameAsRest()))
        }
    }

    @Test
    fun `Identity validation failures return transport unavailable before RPC handling`() {
        coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_MCP) } throws IllegalStateException("sensitive-infrastructure-detail")
        for (method in listOf(HttpMethod.POST, HttpMethod.GET, HttpMethod.DELETE)) {
            client.method(method).uri("/mcp").headers { it.setBearerAuth("mcp-token") }.exchange()
                .expectStatus().isEqualTo(503).expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().valueEquals("Retry-After", "2").expectHeader().doesNotExist("WWW-Authenticate").expectBody().isEmpty
        }
        coVerify { admission wasNot Called; handler wasNot Called; docs wasNot Called; management wasNot Called }
    }

    @Test
    fun `authenticated Identity availability failures preserve safe tool errors`() {
        authenticated()
        coEvery { oauth.listWorkspaces(any(), ExpectedOAuthResource.AGENT_MCP) } throws IllegalStateException("sensitive-infrastructure-detail")
        post(toolCall("monomer_list", """{"path":"workspaces"}"""), "mcp-token")
            .expectStatus().isOk.expectHeader().valueEquals("Retry-After", "2").expectBody().jsonPath("$.result.isError").isEqualTo(true)
            .consumeWith { assertTrue(!String(it.responseBody!!).contains("sensitive-infrastructure-detail")) }
            .jsonPath("$.result.content[0].text").value<String> { text ->
                val problem = ObjectMapper().readTree(text)
                assertEquals("OWNER_UNAVAILABLE", problem.path("code").asText())
                assertEquals(503, problem.path("status").asInt())
                assertEquals(true, problem.path("retryable").asBoolean())
                assertEquals("/mcp", problem.path("instance").asText())
            }
    }

    @Test
    fun `list schemas and runtime reject mixed discovery and missing Workspace selection`() {
        authenticated()
        val mapper = ObjectMapper()
        val tool = McpToolCatalog(MonomerDocsReader("https://monomer.cc").agentApiReferenceUrl).tools.single { it["name"] == "monomer_list" }
        val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        val inputDocument = mapper.valueToTree<JsonNode>(tool["inputSchema"])
        val outputDocument = mapper.valueToTree<JsonNode>(tool["outputSchema"])
        assertEquals("object", inputDocument.path("type").asText())
        assertEquals(3, inputDocument.path("oneOf").size())
        assertEquals("object", outputDocument.path("type").asText())
        assertEquals(3, outputDocument.path("oneOf").size())
        val input = factory.getSchema(inputDocument)
        for (valid in listOf(
            """{"path":"workspaces"}""",
            """{"workspaceId":"${workspaceId.value}","path":"workspace","limit":20}""",
            """{"workspaceId":"${workspaceId.value}","path":"workspace/metrics","limit":20.0}""",
            """{"workspaceId":"${workspaceId.value}","path":"workspace/metrics","limit":2e1}""",
            """{"organizationId":"${organizationId.value}","path":"organization/members","limit":20.0}""",
            """{"organizationId":"${organizationId.value}","path":"organization/members","limit":2e1}""",
        )) assertTrue(input.validate(mapper.readTree(valid)).isEmpty(), valid)
        for (invalid in listOf(
            """{}""",
            """{"path":"workspace","limit":20}""",
            """{"workspaceId":"${workspaceId.value}","path":"workspace"}""",
            """{"path":"workspaces","workspaceId":"${workspaceId.value}"}""",
            """{"path":"workspaces","workspaceId":"${workspaceId.value}","limit":20}""",
            """{"path":"workspaces","limit":20}""",
            """{"path":"workspaces","cursor":"next"}""",
            """{"path":"workspaces","unknown":true}""",
        )) {
            assertTrue(input.validate(mapper.readTree(invalid)).isNotEmpty(), invalid)
            post(toolCall("monomer_list", invalid), "mcp-token").expectStatus().isOk.expectBody()
                .jsonPath("$.result.isError").isEqualTo(true)
        }
        post(toolCall("monomer_list_workspaces", "{}"), "mcp-token").expectStatus().isOk.expectBody()
            .jsonPath("$.error.message").isEqualTo("Unknown tool")
        coVerify(exactly = 0) { oauth.listWorkspaces(any(), any()) }
        coVerify { admission wasNot Called; handler wasNot Called }
        val output = factory.getSchema(outputDocument)
        for (valid in listOf(
            """{"workspaces":[]}""",
            """{"workspaces":[{"workspaceId":"${workspaceId.value}","organizationId":"${organizationId.value}","organizationDisplayName":"Team","displayName":"Data","sample":null}]}""",
            """{"requestId":"srv_test","items":[],"availableOperations":[]}""",
            """{"workspaces":[{"displayName":"Data"}]}""",
            """{"workspaces":[],"items":[]}""",
        )) assertTrue(output.validate(mapper.readTree(valid)).isEmpty(), valid)
        for (invalid in listOf("""{}""", """{"workspaces":42}""", """{"items":[]}""")) {
            assertTrue(output.validate(mapper.readTree(invalid)).isNotEmpty(), invalid)
        }
    }

    @Test
    fun `discovery rechecks credential and returns an empty organization list unchanged`() {
        authenticated()
        val call = toolCall("monomer_list", """{"path":"workspaces"}""")
        coEvery { oauth.listWorkspaces(any(), ExpectedOAuthResource.AGENT_MCP) } returns OAuthOutcome.Failure(OAuthError.INVALID_GRANT)
        post(call, "mcp-token").expectStatus().isUnauthorized
        coEvery { oauth.listWorkspaces(any(), ExpectedOAuthResource.AGENT_MCP) } returns OAuthOutcome.Success(emptyList())
        post(call, "mcp-token").expectStatus().isOk.expectBody()
            .jsonPath("$.result.structuredContent.workspaces").isEmpty
        coVerify { admission wasNot Called; handler wasNot Called }
    }

    @Test
    fun `initialize supplies shared target selection and discovery guidance`() {
        authenticated()
        val response = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""", "mcp-token")
            .expectStatus().isOk.expectBody().returnResult().responseBody!!
        val instructions = ObjectMapper().readTree(response).at("/result/instructions").asText()
        for (guidance in listOf("workspaceId for workspace paths", "organizationId for organization paths", "exactly one target ID", "monomer_docs and monomer_describe need no target ID", "availableOperations", "monomer_describe", "result.path",
            "Show a Dashboard Draft with monomer_show as soon as the Draft exists and before editing it", "right after the Draft start returns",
            "Call it once per Draft", "call monomer_show after each resolve or refresh")) {
            assertTrue(instructions.contains(guidance), guidance)
        }
        coVerify { admission wasNot Called; handler wasNot Called; management wasNot Called }
    }

    @Test
    fun `schemas are self contained and write annotations are conservative`() {
        val tools = McpToolCatalog(MonomerDocsReader("https://monomer.cc").agentApiReferenceUrl).tools
        val mapper = ObjectMapper()
        tools.forEach { tool ->
          listOf("inputSchema", "outputSchema").forEach { schemaKey ->
            val document = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(tool[schemaKey])
            fun checkReferences(node: com.fasterxml.jackson.databind.JsonNode) {
                if (node.isObject) {
                    if (node.has("\$ref")) {
                        val reference = node.path("\$ref").asText()
                        assertTrue(reference.startsWith("#/"))
                        assertTrue(!document.at(reference.removePrefix("#")).isMissingNode, reference)
                    }
                    node.elements().forEachRemaining(::checkReferences)
                } else if (node.isArray) node.forEach(::checkReferences)
            }
            checkReferences(document)
          }
        }
        val bytes = mapper.writeValueAsBytes(mapOf("jsonrpc" to "2.0", "id" to 1, "result" to mapOf("tools" to tools))).size
        println("MCP tools/list serialized bytes: $bytes")
        assertTrue(bytes < 19_000, "MCP tools/list total cap with canonical EDIT and SEARCH argument declarations: $bytes bytes")
        tools.forEach { tool ->
            val toolBytes = mapper.writeValueAsBytes(tool).size
            println("MCP ${tool["name"]} serialized bytes: $toolBytes")
            val cap = if (tool["name"] == "monomer_search") 4_100 else 4_000
            assertTrue(toolBytes < cap, "Per-tool cap for ${tool["name"]}: $toolBytes bytes")
        }
        val annotations = tools.single { it["name"] == "monomer_invoke" }["annotations"] as Map<*, *>
        assertEquals(false, annotations["readOnlyHint"])
        assertEquals(false, annotations["idempotentHint"])
        assertEquals(true, annotations["destructiveHint"])
    }

    @Test
    fun `docs reads Markdown without Workspace admission and preserves MCP authentication`() {
        val call = toolCall("monomer_docs", "{}")
        post(call).expectStatus().isUnauthorized
        coVerify { docs wasNot Called }
        authenticated()
        coEvery { docs.read("/en/llms.txt") } returns MonomerDocsResult.Read("# Monomer\nChoose a task.")
        post(call, "mcp-token").expectStatus().isOk.expectBody()
            .jsonPath("$.result.content[0].text").isEqualTo("# Monomer\nChoose a task.")
            .jsonPath("$.result.isError").isEqualTo(false)
            .jsonPath("$.result.structuredContent").doesNotExist()
        for (arguments in listOf("{\"path\":null}", "{\"path\":\"https://evil.test/\"}", "{\"workspaceId\":\"$workspaceId\"}", "{\"section\":\"intro\"}")) {
            post(toolCall("monomer_docs", arguments), "mcp-token").expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(true)
        }
        coVerify(exactly = 1) { docs.read(any()) }
        coVerify { admission wasNot Called; handler wasNot Called }
    }

    @Test
    fun `docs failures are safe actionable tool errors`() {
        authenticated()
        for ((outcome, message) in listOf(
            MonomerDocsResult.NotFound to "Document not found",
            MonomerDocsResult.TooLarge to "Document exceeds",
            MonomerDocsResult.Unavailable to "Documentation is temporarily unavailable",
        )) {
            coEvery { docs.read(any()) } returns outcome
            post(toolCall("monomer_docs", "{\"path\":\"/en/analyze/llms.txt\"}"), "mcp-token")
                .expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(true)
                .jsonPath("$.result.content[0].text").value<String> { assertTrue(it.startsWith(message)) }
        }
        coVerify { admission wasNot Called; handler wasNot Called }
    }

    @Test
    fun `all tool validation branches expose safe repair details and request references`() {
        authenticated()
        val workspace = "\"workspaceId\":\"${workspaceId.value}\""
        val connectorId = "01942b04-6e00-7000-8000-000000000011"
        val cases = listOf(
            Triple("monomer_list", """{$workspace,"path":"workspace"}""", "limit"),
            Triple("monomer_list", """{"path":"workspaces","secret-key":"secret-value"}""", "arguments"),
            Triple("monomer_read", """{"workspaceId":"secret-value","path":"workspace"}""", "workspaceId"),
            Triple("monomer_read", """{$workspace,"path":42}""", "path"),
            Triple("monomer_search", """{$workspace,"path":"workspace","queries":["secret-value"],"limitPerQuery":50}""", "limitPerQuery"),
            Triple("monomer_create", """{$workspace,"path":"workspace/connectors","input":{"connectorType":"secret-value"}}""", "input.kind"),
            Triple("monomer_edit", """{$workspace,"path":"workspace/connectors/$connectorId/catalog/selection","patch":{"secret-key":"secret-value"}}""", "patch"),
            Triple("monomer_invoke", """{$workspace,"path":"workspace/explore/runs","action":"run","input":{"kind":"SQL_EXPLORE_RUN","connectorId":"$connectorId","query":"secret-value"}}""", "input.sql"),
            Triple("monomer_invoke", """{$workspace,"path":"workspace/explore/runs","action":"run","input":{"kind":"SQL_EXPLORE_RUN","connectorId":"$connectorId","sql":"   "}}""", "input.sql"),
            Triple("monomer_invoke", """{$workspace,"path":"workspace/connectors/$connectorId","action":"rotate-credentials","input":{"kind":"CONNECTOR_CREDENTIAL_ROTATE","credential":{"kind":"USERNAME_PASSWORD","username":"secret-user","password":"   "}}}""", "input.credential.password"),
            Triple("monomer_create", """{$workspace,"path":"workspace/metrics","input":{"kind":"METRIC_DEFINITION","definition":{"schemaVersion":3,"name":"revenue","type":"simple","semanticModelName":"orders","measure":"revenue","label":"   "}}}""", "input.definition.label"),
            Triple("monomer_docs", """{"path":"secret-value"}""", "path"),
            Triple("monomer_docs", """{"path":null}""", "path"),
            Triple("monomer_docs", """{"secret-key":"secret-value"}""", "arguments"),
        )
        for ((name, arguments, field) in cases) {
            val problem = validationProblem(name, arguments)
            assertTrue(problem.path("violations").any { it.path("field").asText() == field }, "$name: $problem")
            assertTrue(!problem.toString().contains("secret-"), "$name: $problem")
            if (name == "monomer_search") assertTrue(problem.path("violations").any { it.path("message").asText().contains("at most 20") })
        }
        coVerify { admission wasNot Called; handler wasNot Called; docs wasNot Called }
    }

    @Test
    fun `MCP run requests retain zero through six fractional digits after decoding`() {
        authenticated()
        coEvery { admission.decide(any()) } answers {
            val request = firstArg<AgentWorkspaceAdmissionRequest>()
            AgentWorkspaceAdmissionDecision.Authorized(AgentWorkspaceRoleEvidence(
                OrganizationPlan.FREE, CanonicalPrincipal(userId, PrincipalKind.USER), organizationId, workspaceId,
                AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT,
                AgentClientId("https://client.example/metadata.json"), WorkspaceRole.EXPLORER, request.intent,
            ))
        }
        val observed = mutableListOf<AgentInvokeRequest>()
        coEvery { handler.invoke(any(), capture(observed)) } answers {
            val request = secondArg<AgentInvokeRequest>()
            AgentActionOutcome.Completed(AgentActionResult.Invoke(
                request.path, request.action, AgentActionStatus.ACCEPTED,
                AgentActionResultReference(AgentResourceKind.EXPLORE_RUN,
                    AgentResourcePath.parse("workspace/explore/runs/01942b04-6e00-7000-8000-000000000021")),
            ))
        }
        for (fraction in listOf("", ".1", ".12", ".123", ".1234", ".12345", ".123456", ".000000")) {
            val raw = "2026-10-01T00:00:00$fraction+09:00"
            post(toolCall("monomer_invoke", """{"workspaceId":"${workspaceId.value}","path":"workspace/explore/runs","action":"run",
                "input":{"kind":"SEMANTIC_EXPLORE_RUN","connectorId":"01942b04-6e00-7000-8000-000000000011",
                "query":{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,
                "filters":[{"kind":"TIMESTAMP","dimension":{"semanticModelName":"orders","name":"ordered_at"},
                "operator":"GREATER_THAN_OR_EQUAL","values":["$raw"]}],"orderBy":[],"rowLimit":100}}}"""), "mcp-token")
                .expectStatus().isOk.expectBody()
                .jsonPath("$.result.isError").isEqualTo(false)
                .jsonPath("$.result.structuredContent.status").isEqualTo("ACCEPTED")
            val query = ((observed.last().input as AgentInvokeInput.ExploreRun).request as AgentExploreRunRequest.Semantic).query
            assertEquals(listOf(java.time.Instant.parse(raw)), (query.filters.single() as AgentSemanticFilter.Timestamp).values)
        }
    }

    @Test
    fun `timestamp filter precision returns the same 400 problem before MCP run admission`() {
        authenticated()
        for (fraction in listOf("1234567", "12345678", "123456789", "1234567890", "1234560", "0000000")) {
            val problem = validationProblem("monomer_invoke", """{"workspaceId":"${workspaceId.value}","path":"workspace/explore/runs","action":"run",
                "input":{"kind":"SEMANTIC_EXPLORE_RUN","connectorId":"01942b04-6e00-7000-8000-000000000011",
                "query":{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,
                "filters":[{"kind":"TIMESTAMP","dimension":{"semanticModelName":"orders","name":"ordered_at"},
                "operator":"GREATER_THAN_OR_EQUAL","values":["2026-10-01T00:00:00.$fraction+09:00"]}],"orderBy":[],"rowLimit":100}}}""")
            val violation = problem.path("violations").single()
            assertEquals("input.query.filters[0].values[0]", violation.path("field").asText())
            assertEquals("SCHEMA_MISMATCH", violation.path("code").asText())
            assertEquals("Timestamp filter values must have at most 6 fractional second digits.", violation.path("message").asText())
        }
        coVerify { admission wasNot Called; handler wasNot Called }
    }

    @Test
    fun `collection reads explain how to list the same target before owner admission`() {
        authenticated()
        for ((target, targetId, paths) in listOf(
            Triple("workspaceId", workspaceId.value.toString(), listOf("workspace/metrics", "workspace/semantic-models", "workspace/connectors")),
            Triple("organizationId", organizationId.value.toString(), listOf("organization/members", "organization/invitations", "organization/workspaces")),
        )) {
            for (path in paths) {
                val problem = validationProblem("monomer_read", """{"$target":"$targetId","path":"$path"}""")
                val violation = problem.path("violations").single()
                assertEquals("path", violation.path("field").asText())
                val message = violation.path("message").asText()
                assertTrue(message.contains("monomer_list"), message)
                assertTrue(message.contains("Keep the same path and $target target unchanged"), message)
                assertTrue(message.contains("integer from 1 to 100"), message)
                assertTrue(message.contains("for example, 20"), message)
                assertTrue(!message.contains(path) && !message.contains(targetId), message)
            }
        }
        coVerify { admission wasNot Called; handler wasNot Called; management wasNot Called }
    }

    @Test
    fun `unknown read paths and malformed collection requests do not claim list support`() {
        authenticated()
        for (arguments in listOf(
            """{"workspaceId":"${workspaceId.value}","path":"workspace/unknown"}""",
            """{"organizationId":"${organizationId.value}","path":"organization/unknown"}""",
            """{"workspaceId":"${workspaceId.value}","path":"workspace/metrics","unclaimed":true}""",
            """{"organizationId":"${organizationId.value}","path":"organization/members","unclaimed":true}""",
        )) {
            val problem = validationProblem("monomer_read", arguments)
            assertTrue(!problem.path("violations").toString().contains("monomer_list"), problem.toString())
        }
        coVerify { admission wasNot Called; handler wasNot Called; management wasNot Called }
    }

    @Test
    fun `missing resource list limit returns its repair hint through MCP`() {
        authenticated()
        for (arguments in listOf(
            """{"workspaceId":"${workspaceId.value}","path":"workspace/dashboards"}""",
            """{"organizationId":"${organizationId.value}","path":"organization/members"}""",
        )) {
            val problem = validationProblem("monomer_list", arguments)
            val violation = problem.path("violations").single()
            assertEquals("limit", violation.path("field").asText())
            assertEquals("REQUIRED_FIELD_MISSING", violation.path("code").asText())
            assertTrue(violation.path("message").asText().contains("integer from 1 to 100"))
            assertTrue(violation.path("message").asText().contains("for example 20"))
        }
        coVerify { admission wasNot Called; handler wasNot Called }
    }

    @Test
    fun `malformed call envelopes and unknown tools remain protocol errors`() {
        authenticated()
        for (call in listOf(
            toolCall("monomer_read", "[]"),
            toolCall("monomer_read", "null"),
            toolCall("unknown", "{}"),
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":42}}""",
        )) {
            post(call, "mcp-token").expectStatus().isOk.expectBody()
                .jsonPath("$.error.code").isEqualTo(-32602).jsonPath("$.result").doesNotExist()
        }
        coVerify { admission wasNot Called; handler wasNot Called; docs wasNot Called }
    }

    private fun validationProblem(name: String, arguments: String): JsonNode {
        val response = post(toolCall(name, arguments), "mcp-token").expectStatus().isOk
            .expectHeader().valueEquals("Cache-Control", "no-store").expectBody()
            .jsonPath("$.result.isError").isEqualTo(true).jsonPath("$.error").doesNotExist()
            .returnResult().responseBody!!
        val mapper = ObjectMapper()
        val problem = mapper.readTree(problemJson(mapper.readTree(response).at("/result/content/0/text").asText()))
        assertEquals("AGENT_REQUEST_INVALID", problem.path("code").asText())
        assertEquals(400, problem.path("status").asInt())
        assertEquals("/mcp", problem.path("instance").asText())
        assertTrue(problem.path("requestId").asText().startsWith("srv_"))
        assertEquals(problem.path("requestId"), problem.path("traceId"))
        assertEquals(false, problem.path("retryable").asBoolean())
        return problem
    }

    @Test
    fun `Organization management uses grant identity without workspace admission and rejects mixed scope`() {
        authenticated()
        coEvery { management.read(any<AgentOrganizationCaller>(), any()) } coAnswers {
            val caller = firstArg<AgentOrganizationCaller>()
            assertEquals(organizationId, caller.organizationId)
            assertEquals(ExpectedOAuthResource.AGENT_MCP, caller.expectedOAuthResource)
            AgentResourceOutcome.Found(AgentResourceEnvelope.Organization(AgentResourcePath.parse("organization"),
                AgentOrganizationBody(organizationId.value, "Team", AgentOrganizationSelf(userId.value, "Admin", AgentOrganizationRole.ADMIN)), emptyList()))
        }
        post(toolCall("monomer_read", """{"path":"organization","organizationId":"${organizationId.value}"}"""), "mcp-token").expectStatus().isOk.expectBody()
            .jsonPath("$.result.isError").isEqualTo(false)
            .jsonPath("$.result.structuredContent.body.id").isEqualTo(organizationId.value.toString())
            .jsonPath("$.result.structuredContent.body.caller.status").doesNotExist()
            .jsonPath("$.result.structuredContent.body.caller.source").doesNotExist()
        post(toolCall("monomer_read", """{"path":"organization","workspaceId":"${workspaceId.value}"}"""), "mcp-token").expectStatus().isOk.expectBody()
            .jsonPath("$.result.isError").isEqualTo(true)
        post(toolCall("monomer_read", """{"path":"organization"}"""), "mcp-token").expectStatus().isOk.expectBody()
            .jsonPath("$.result.isError").isEqualTo(true)
        coVerify(exactly = 1) { management.read(any<AgentOrganizationCaller>(), any()) }
        coVerify { admission wasNot Called; handler wasNot Called }
    }

    @Test
    fun `Organization tool schemas exclude workspaceId while Workspace operations require it`() {
        val mapper = ObjectMapper()
        val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        val examples = mapOf(
            "read" to """{"path":"organization","organizationId":"${organizationId.value}"}""",
            "list" to """{"organizationId":"${organizationId.value}","path":"organization/invitations","limit":10}""",
            "create" to """{"organizationId":"${organizationId.value}","path":"organization/invitations","input":{"kind":"ORGANIZATION_INVITATION_CREATE","email":"member@example.test"}}""",
            "invoke" to """{"organizationId":"${organizationId.value}","path":"organization/invitations/01942b04-6e00-7000-8000-000000000021","action":"resend"}""",
        )
        for ((operation, example) in examples) {
            val tool = McpToolCatalog(MonomerDocsReader("https://monomer.cc").agentApiReferenceUrl).tools.single { it["name"] == "monomer_$operation" }
            val schema = factory.getSchema(mapper.valueToTree<JsonNode>(tool["inputSchema"]))
            val body = mapper.readTree(example)
            assertTrue(schema.validate(body).isEmpty(), "$operation: ${schema.validate(body)}")
            (body as com.fasterxml.jackson.databind.node.ObjectNode).put("workspaceId", workspaceId.value.toString())
            assertTrue(schema.validate(body).isNotEmpty(), operation)
        }
    }

    @Test
    fun `Organization membership status inputs and paths fail before owner admission`() {
        authenticated()
        val calls = listOf(
            toolCall("monomer_invoke", """{"organizationId":"${organizationId.value}","path":"organization/members/${userId.value}","action":"set-status","input":{"kind":"ORGANIZATION_MEMBER_STATUS","status":"ACTIVE"}}"""),
            toolCall("monomer_read", """{"organizationId":"${organizationId.value}","path":"organization/members/${userId.value}/status"}"""),
        )
        for (call in calls) {
            post(call, "mcp-token").expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(true)
                .jsonPath("$.result.content[0].text").value<String> { assertEquals("AGENT_REQUEST_INVALID", ObjectMapper().readTree(problemJson(it)).path("code").asText()) }
        }
        coVerify { management wasNot Called; admission wasNot Called; handler wasNot Called }
    }

    @Test
    fun `describe needs MCP authentication but no target or owner admission and errors locate entries`() {
        val arguments = """{"operations":[{"operation":"CREATE","path":"workspace/metrics","inputKind":"METRIC_DEFINITION"},{"operation":"INVOKE","path":"workspace/metrics/revenue","action":"delete"}]}"""
        post(toolCall("monomer_describe", arguments)).expectStatus().isUnauthorized
        authenticated()
        val result = post(toolCall("monomer_describe", arguments), "mcp-token").expectStatus().isOk.expectBody()
            .jsonPath("$.result.isError").isEqualTo(false).jsonPath("$.result.structuredContent").doesNotExist().returnResult()
        val mapper = ObjectMapper()
        val response = mapper.readTree(mapper.readTree(result.responseBody).at("/result/content/0/text").asText())
        assertEquals("monomer_create", response.at("/operations/0/tool").asText())
        assertEquals("no input", response.at("/operations/1/input").asText())
        val problem = validationProblem("monomer_describe", """{"operations":[{"operation":"READ","path":"workspace"},{"operation":"EDIT","path":"workspace/metrics"}]}""")
        assertEquals("operations[1]", problem.path("violations").single().path("field").asText())
        coVerify { admission wasNot Called; handler wasNot Called; management wasNot Called; docs wasNot Called }
    }

    @Test
    fun `MCP describe returns Dashboard and Page Session operation and input descriptions`() {
        authenticated()
        val id = workspaceId.value
        val dashboard = "workspace/dashboards/$id"
        val session = "$dashboard/sessions/$id"
        val arguments = """{"operations":[
            {"operation":"INVOKE","path":"$dashboard/sessions","action":"open","inputKind":"DASHBOARD_SESSION_OPEN"},
            {"operation":"INVOKE","path":"$session","action":"resolve","inputKind":"DASHBOARD_SESSION_RESOLVE"},
            {"operation":"INVOKE","path":"$session","action":"refresh","inputKind":"DASHBOARD_SESSION_REFRESH"},
            {"operation":"INVOKE","path":"workspace/pages/$id/sessions/$id","action":"resolve","inputKind":"DASHBOARD_SESSION_RESOLVE"}
        ]}"""
        val result = post(toolCall("monomer_describe", arguments), "mcp-token").expectStatus().isOk.expectBody()
            .jsonPath("$.result.isError").isEqualTo(false).returnResult()
        val mapper = ObjectMapper()
        val response = mapper.readTree(mapper.readTree(result.responseBody).at("/result/content/0/text").asText())
        val expected = listOf("starts a QueryRun for every named query", "Unlike refresh", "REFRESH_QUERY_RUN_UNAVAILABLE", "PAGE_SESSION result.path")
        response.path("operations").forEachIndexed { index, operation ->
            assertEquals("monomer_invoke", operation.path("tool").asText())
            val schema = operation.path("argumentsSchema")
            assertTrue(schema.path("description").asText().contains(expected[index]))
            val field = if (index == 0) "parameterBindings" else "variables"
            assertTrue(schema.at("/properties/input/properties/$field/description").asText().contains("empty map is valid only"))
        }
        assertEquals(4, response.path("operations").size())
        coVerify { admission wasNot Called; handler wasNot Called; management wasNot Called; docs wasNot Called }
    }

    @Test
    fun `check and invoke reject each others valid contract operations before admission`() {
        authenticated()
        val check = """{"workspaceId":"${workspaceId.value}","path":"workspace/metrics","action":"check","input":{"kind":"METRIC_CHECK","definition":{"schemaVersion":3,"name":"revenue","type":"simple","semanticModelName":"orders","measure":"revenue"}}}"""
        val invoke = """{"workspaceId":"${workspaceId.value}","path":"workspace/metrics/revenue","action":"delete"}"""
        for ((tool, arguments, correctTool) in listOf(Triple("monomer_invoke", check, "monomer_check"), Triple("monomer_check", invoke, "monomer_invoke"))) {
            val result = post(toolCall(tool, arguments), "mcp-token").expectStatus().isOk.expectBody()
                .jsonPath("$.result.isError").isEqualTo(true).jsonPath("$.result.structuredContent").doesNotExist().returnResult()
            val mapper = ObjectMapper()
            val text = mapper.readTree(result.responseBody).at("/result/content/0/text").asText()
            val expectedMessage = "Use $correctTool for this available operation. monomer_check accepts only contract check operations; monomer_invoke does not accept check."
            assertEquals(expectedMessage, text.substringBefore('\n'))
            val problem = mapper.readTree(problemJson(text))
            assertEquals("AGENT_REQUEST_INVALID", problem.path("code").asText())
            assertEquals(400, problem.path("status").asInt())
            assertEquals(false, problem.path("retryable").asBoolean())
            assertEquals("/mcp", problem.path("instance").asText())
            assertEquals(problem.path("requestId"), problem.path("traceId"))
            assertEquals(mapper.readTree("""[{"field":"action","code":"INVALID_FIELD","message":"$expectedMessage"}]"""), problem.path("violations"))
        }
        coVerify { admission wasNot Called; handler wasNot Called; management wasNot Called }
    }

    @Test
    fun `check routes through the same decoder admission handler and owner response validation`() {
        authenticated()
        coEvery { admission.decide(any()) } answers {
            val request = firstArg<AgentWorkspaceAdmissionRequest>()
            AgentWorkspaceAdmissionDecision.Authorized(AgentWorkspaceRoleEvidence(OrganizationPlan.FREE,
                CanonicalPrincipal(userId, PrincipalKind.USER), organizationId, workspaceId, AuthorizationRevision(1),
                CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, AgentClientId("https://client.example/metadata.json"), WorkspaceRole.EXPLORER, request.intent))
        }
        coEvery { handler.invoke(any(), any()) } returns AgentActionOutcome.Rejected(cc.monomer.control.publicapi.AgentTransportProblems.ownerUnavailable)
        val check = """{"workspaceId":"${workspaceId.value}","path":"workspace/metrics/revenue","action":"check","input":{"kind":"METRIC_CHECK","definition":{"schemaVersion":3,"name":"revenue","type":"simple","semanticModelName":"orders","measure":"revenue"}}}"""
        val result = post(toolCall("monomer_check", check), "mcp-token").expectStatus().isOk.expectBody().returnResult()
        val text = ObjectMapper().readTree(result.responseBody).at("/result/content/0/text").asText()
        assertTrue(text.startsWith("{"))
        assertEquals("OWNER_UNAVAILABLE", ObjectMapper().readTree(text).path("code").asText())
        val metricPath = AgentResourcePath.parse("workspace/metrics/revenue")
        val reference = AgentActionResultReference(AgentResourceKind.METRIC, metricPath)
        coEvery { handler.invoke(any(), any()) } returns AgentActionOutcome.Completed(AgentActionResult.SemanticCheck(metricPath, reference,
            kotlinx.serialization.json.Json.parseToJsonElement("""{"ok":true,"runsOn":[],"preview":null,"problems":[],"partitionFilters":[]}""")))
        post(toolCall("monomer_check", check), "mcp-token").expectStatus().isOk.expectBody()
            .jsonPath("$.result.isError").isEqualTo(false).jsonPath("$.result.structuredContent.check.ok").isEqualTo(true)
        coEvery { handler.invoke(any(), any()) } returns AgentActionOutcome.Completed(AgentActionResult.SemanticCheck(metricPath, reference,
            kotlinx.serialization.json.Json.parseToJsonElement("{}")))
        val invalid = post(toolCall("monomer_check", check), "mcp-token").expectStatus().isOk.expectBody()
            .jsonPath("$.result.isError").isEqualTo(true).jsonPath("$.result.structuredContent").doesNotExist().returnResult()
        val invalidProblem = ObjectMapper().readTree(ObjectMapper().readTree(invalid.responseBody).at("/result/content/0/text").asText())
        assertEquals(cc.monomer.control.publicapi.AgentTransportProblems.ownerResponseInvalid.code, invalidProblem.path("code").asText())
        coVerify(exactly = 3) { handler.invoke(any(), match { it.action == "check" && it.input is AgentInvokeInput.MetricCheck }) }
    }

    @Test
    fun `owner Problem nextActions remain verbatim describe inputs without schema guidance`() {
        authenticated()
        coEvery { admission.decide(any()) } answers {
            val request = firstArg<AgentWorkspaceAdmissionRequest>()
            AgentWorkspaceAdmissionDecision.Authorized(AgentWorkspaceRoleEvidence(OrganizationPlan.FREE, CanonicalPrincipal(userId, PrincipalKind.USER),
                organizationId, workspaceId, AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT,
                AgentClientId("https://client.example/metadata.json"), WorkspaceRole.OWNER, request.intent))
        }
        val path = AgentResourcePath.parse("workspace/connectors/${workspaceId.value}/catalog/selection/options/schemas")
        val next = AgentAvailableOperation.Invoke(path, "refresh", null)
        coEvery { handler.invoke(any(), any()) } returns AgentActionOutcome.Rejected(AgentWorkspaceProblem("CATALOG_SELECTION_OPTIONS_NOT_FETCHED", "Catalog options not fetched",
            "Refresh this option collection before listing its children.", AgentWorkspaceProblemStatus.CONFLICT, false, null, emptyList(), emptyList(), listOf(next),
            retryAfterSeconds = null,
            roleRequirement = null,
            partitionFilters = emptyList()))
        val result = post(toolCall("monomer_invoke", """{"workspaceId":"${workspaceId.value}","path":"${path.value}","action":"refresh"}"""), "mcp-token").expectStatus().isOk.expectBody().returnResult()
        val mapper = ObjectMapper()
        val text = mapper.readTree(result.responseBody).at("/result/content/0/text").asText()
        val problem = mapper.readTree(text)
        assertEquals("CATALOG_SELECTION_OPTIONS_NOT_FETCHED", problem.path("code").asText())
        val described = post(toolCall("monomer_describe", mapper.createObjectNode().set<com.fasterxml.jackson.databind.node.ObjectNode>("operations", problem.path("nextActions")).toString()), "mcp-token").expectStatus().isOk.expectBody().returnResult()
        val response = mapper.readTree(mapper.readTree(described.responseBody).at("/result/content/0/text").asText())
        assertEquals("monomer_invoke", response.at("/operations/0/tool").asText())
        assertEquals("no input", response.at("/operations/0/input").asText())
    }

    @Test
    fun `initialize advertises resources and the dashboard view resource lists and reads behind MCP gates`() {
        authenticated()
        val mapper = ObjectMapper()
        post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""", "mcp-token")
            .expectStatus().isOk.expectBody().consumeWith {
                assertEquals(mapper.readTree("""{"tools":{"listChanged":false},"resources":{}}"""), mapper.readTree(it.responseBody).at("/result/capabilities"))
            }
        post("""{"jsonrpc":"2.0","id":2,"method":"resources/list"}""", "mcp-token").expectStatus().isOk.expectBody().consumeWith {
            assertEquals(mapper.readTree("""{"resources":[{"uri":"ui://monomer/dashboard-view/v1","name":"dashboard-view","title":"Monomer dashboard view",
                "description":"Shows a Monomer Dashboard Draft or Session inside the chat.","mimeType":"text/html;profile=mcp-app"}]}"""), mapper.readTree(it.responseBody).path("result"))
        }
        post("""{"jsonrpc":"2.0","id":3,"method":"resources/templates/list","params":{}}""", "mcp-token").expectStatus().isOk.expectBody()
            .consumeWith { assertEquals(mapper.readTree("""{"resourceTemplates":[]}"""), mapper.readTree(it.responseBody).path("result")) }
        for (method in listOf("resources/list", "resources/templates/list")) {
            post("""{"jsonrpc":"2.0","id":4,"method":"$method","params":{"cursor":"next"}}""", "mcp-token").expectStatus().isOk.expectBody()
                .jsonPath("$.error.code").isEqualTo(-32602).jsonPath("$.result").doesNotExist()
        }
        fun read(params: String): JsonNode = mapper.readTree(post("""{"jsonrpc":"2.0","id":5,"method":"resources/read","params":$params}""", "mcp-token")
            .expectStatus().isOk.expectHeader().valueEquals("Cache-Control", "no-store").expectBody().returnResult().responseBody)
        val current = read("""{"uri":"ui://monomer/dashboard-view/v1","_meta":{}}""").at("/result/contents")
        assertEquals(1, current.size())
        assertEquals(setOf("uri", "mimeType", "text", "_meta"), current[0].fieldNames().asSequence().toSet())
        assertEquals("ui://monomer/dashboard-view/v1", current[0].path("uri").asText())
        assertEquals("text/html;profile=mcp-app", current[0].path("mimeType").asText())
        assertEquals(VIEW_HTML, current[0].path("text").asText())
        assertEquals(mapper.readTree("""{"ui":{"prefersBorder":true}}"""), current[0].path("_meta"))
        for (version in listOf("v2", "v10")) {
            val outdated = read("""{"uri":"ui://monomer/dashboard-view/$version"}""").at("/result/contents/0")
            assertEquals("ui://monomer/dashboard-view/$version", outdated.path("uri").asText())
            assertEquals("text/html;profile=mcp-app", outdated.path("mimeType").asText())
            val html = outdated.path("text").asText()
            assertTrue(html.contains("<title>This view is out of date</title>") && html.contains("Reconnect the Monomer connector, or open the dashboard in Monomer."), html)
            assertTrue(!html.contains("<script", ignoreCase = true), html)
            assertEquals(mapper.readTree("""{"ui":{"prefersBorder":true}}"""), outdated.path("_meta"))
        }
        for (params in listOf("""{"uri":"ui://monomer/dashboard-view/v0"}""", """{"uri":"ui://monomer/dashboard-view/latest"}""",
            """{"uri":"ui://monomer/other/v1"}""", """{"uri":"https://app.example.com/view.html"}""", """{"uri":42}""", """{}""")) {
            val error = read(params)
            assertEquals(mapper.readTree("""{"code":-32002,"message":"Resource not found"}"""), error.path("error"), params)
            assertTrue(error.path("result").isMissingNode, params)
        }
        assertEquals(-32602, read("""{"uri":"ui://monomer/dashboard-view/v1","_meta":"invalid"}""").at("/error/code").asInt())
        assertEquals(-32002, mapper.readTree(post("""{"jsonrpc":"2.0","id":6,"method":"resources/read"}""", "mcp-token").expectStatus().isOk
            .expectBody().returnResult().responseBody).at("/error/code").asInt())
        assertUnauthorized(post("""{"jsonrpc":"2.0","id":7,"method":"resources/read","params":{"uri":"ui://monomer/dashboard-view/v1"}}"""))
        client.post().uri("/mcp").header("Origin", "https://evil.example").headers { it.setBearerAuth("mcp-token") }
            .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
            .bodyValue("""{"jsonrpc":"2.0","id":8,"method":"resources/list"}""").exchange().expectStatus().isForbidden
        coVerify { admission wasNot Called; handler wasNot Called; docs wasNot Called; management wasNot Called }
    }

    @Test
    fun `monomer_show shows a Draft by its read revision with a Builder link and view contract`() {
        authenticated()
        admitExplorer()
        val mapper = ObjectMapper()
        val draftPath = "$SHOW_DASHBOARD_PATH/draft"
        val read = slot<AgentReadRequest>()
        val readCaller = slot<AgentWorkspaceCaller>()
        coEvery { handler.read(capture(readCaller), capture(read)) } returns AgentResourceOutcome.Found(AgentResourceEnvelope.DashboardDraft(
            AgentResourcePath.parse(draftPath), AgentRevision(7),
            AgentDashboardDraftResourceBody(showDefinition("""{}""", """{}"""), emptyMap(), emptyMap(), emptyMap(), emptyMap(), SHOWN_AT, SHOWN_AT, null, null, null, null),
            emptyList(),
        ))
        val response = mapper.readTree(post(toolCall("monomer_show", """{"workspaceId":"${workspaceId.value}","path":"$draftPath"}"""), "mcp-token")
            .expectStatus().isOk.expectBody().returnResult().responseBody)
        val result = response.path("result")
        assertEquals(setOf("structuredContent", "content", "isError", "_meta"), result.fieldNames().asSequence().toSet())
        assertEquals(false, result.path("isError").asBoolean())
        assertEquals(mapper.readTree("""{"cc.monomer/viewContract":1}"""), result.path("_meta"))
        val structured = result.path("structuredContent")
        val builder = "https://app.example.com/workspaces/${workspaceId.value}/dashboards/$SHOW_DASHBOARD_ID/builder"
        assertTrue(structured.path("requestId").asText().startsWith("srv_"))
        assertEquals(mapper.readTree("""{"requestId":${mapper.writeValueAsString(structured.path("requestId").asText())},"viewContract":1,
            "workspaceId":"${workspaceId.value}","dashboardId":"$SHOW_DASHBOARD_ID","title":"Revenue \"live\"",
            "target":{"kind":"DRAFT","path":"$draftPath","revision":"7"},"webUrl":"$builder","shownAt":${SHOWN_AT.toEpochMilli()}}"""), structured)
        assertEquals(listOf("""Showing "Revenue "live"" (Draft) in the chat. If no view appears, open it in Monomer: $builder"""), result.path("content").map {
            assertEquals("text", it.path("type").asText())
            it.path("text").asText()
        })
        val output = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(mapper.valueToTree<JsonNode>(McpToolCatalog(MonomerDocsReader("https://monomer.cc").agentApiReferenceUrl).tools.single { it["name"] == "monomer_show" }["outputSchema"]))
        assertTrue(output.validate(structured).isEmpty())
        assertEquals(AgentReadRequest(AgentResourcePath.parse(draftPath), null), read.captured)
        assertEquals(ExpectedOAuthResource.AGENT_MCP, readCaller.captured.expectedOAuthResource)
        assertEquals(workspaceId, readCaller.captured.workspaceId)

        val selected = validationProblem("monomer_show", """{"workspaceId":"${workspaceId.value}","path":"$draftPath","selection":{"variables":{},"queryRuns":{}}}""")
        val violation = selected.path("violations").single()
        assertEquals("selection", violation.path("field").asText())
        assertTrue(violation.path("message").asText().startsWith("Omit selection for a Draft."))
        coVerify(exactly = 1) { handler.read(any(), any()) }
    }

    @Test
    fun `monomer_show passes a valid Session selection through unchanged with a Viewer link`() {
        authenticated()
        admitExplorer()
        val mapper = ObjectMapper()
        val sessionPath = "$SHOW_DASHBOARD_PATH/sessions/$SHOW_SESSION_ID"
        coEvery { handler.read(any(), any()) } answers {
            val path = secondArg<AgentReadRequest>().path
            AgentResourceOutcome.Found(AgentResourceEnvelope.DashboardSession(path,
                AgentDashboardSessionResourceBody(showDefinition("""{"revenue":{},"orders":{}}""", """{"region":{},"city":{}}"""), emptyMap(), emptyMap(), emptyList()), emptyList()))
        }
        val selection = """{"variables":{"region":"EMEA","city":null},"queryRuns":{"revenue":"$sessionPath/query-runs/$RUN_ONE","orders":"$sessionPath/query-runs/$RUN_TWO"}}"""
        val result = mapper.readTree(post(toolCall("monomer_show", """{"workspaceId":"${workspaceId.value}","path":"$sessionPath","selection":$selection}"""), "mcp-token")
            .expectStatus().isOk.expectBody().returnResult().responseBody).path("result")
        assertEquals(false, result.path("isError").asBoolean())
        assertEquals(mapper.readTree("""{"cc.monomer/viewContract":1}"""), result.path("_meta"))
        val viewer = "https://app.example.com/workspaces/${workspaceId.value}/dashboards/$SHOW_DASHBOARD_ID?session=$SHOW_SESSION_ID"
        val structured = result.path("structuredContent")
        assertEquals(mapper.readTree("""{"kind":"SESSION","path":"$sessionPath","variables":{"region":"EMEA","city":null},
            "queryRuns":{"revenue":"$sessionPath/query-runs/$RUN_ONE","orders":"$sessionPath/query-runs/$RUN_TWO"}}"""), structured.path("target"))
        assertEquals(viewer, structured.path("webUrl").asText())
        assertEquals(SHOWN_AT.toEpochMilli(), structured.path("shownAt").asLong())
        assertEquals("""Showing "Revenue "live"" (Session) in the chat. If no view appears, open it in Monomer: $viewer""", result.at("/content/0/text").asText())
        val partial = """{"variables":{"region":"EMEA"},"queryRuns":{"revenue":"$sessionPath/query-runs/$RUN_ONE","orders":"$sessionPath/query-runs/$RUN_TWO"}}"""
        post(toolCall("monomer_show", """{"workspaceId":"${workspaceId.value}","path":"$sessionPath","selection":$partial}"""), "mcp-token")
            .expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(false).jsonPath("$.result.structuredContent.target.variables.city").doesNotExist()
        coEvery { handler.read(any(), any()) } answers {
            AgentResourceOutcome.Found(AgentResourceEnvelope.DashboardSession(secondArg<AgentReadRequest>().path,
                AgentDashboardSessionResourceBody(showDefinition("""{}""", """{"none":{},"text":{},"flag":{},"regions":{},"window":{}}"""), emptyMap(), emptyMap(), emptyList()), emptyList()))
        }
        val values = """{"none":null,"text":"EMEA","flag":true,"regions":["EMEA","APAC"],"window":{"start":"2026-01-01","end":"2026-01-31"}}"""
        val shown = mapper.readTree(post(toolCall("monomer_show", """{"workspaceId":"${workspaceId.value}","path":"$sessionPath","selection":{"variables":$values,"queryRuns":{}}}"""), "mcp-token")
            .expectStatus().isOk.expectBody().returnResult().responseBody).path("result")
        assertEquals(false, shown.path("isError").asBoolean())
        assertEquals(mapper.readTree(values), shown.at("/structuredContent/target/variables"))
        coEvery { handler.read(any(), any()) } answers {
            AgentResourceOutcome.Found(AgentResourceEnvelope.DashboardSession(secondArg<AgentReadRequest>().path,
                AgentDashboardSessionResourceBody(showDefinition("""{}""", """{}"""), emptyMap(), emptyMap(), emptyList()), emptyList()))
        }
        post(toolCall("monomer_show", """{"workspaceId":"${workspaceId.value}","path":"$sessionPath","selection":{"variables":{},"queryRuns":{}}}"""), "mcp-token")
            .expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(false).jsonPath("$.result.structuredContent.target.queryRuns").isEmpty
    }

    @Test
    fun `monomer_show rejects every invalid argument and Session selection with repair fields`() {
        authenticated()
        admitExplorer()
        val sessionPath = "$SHOW_DASHBOARD_PATH/sessions/$SHOW_SESSION_ID"
        coEvery { handler.read(any(), any()) } answers {
            AgentResourceOutcome.Found(AgentResourceEnvelope.DashboardSession(secondArg<AgentReadRequest>().path,
                AgentDashboardSessionResourceBody(showDefinition("""{"revenue":{},"orders":{}}""", """{"region":{}}"""), emptyMap(), emptyMap(), emptyList()), emptyList()))
        }
        val ws = workspaceId.value
        val revenue = "$sessionPath/query-runs/$RUN_ONE"
        val orders = "$sessionPath/query-runs/$RUN_TWO"
        fun selection(queryRuns: String, variables: String = "{}") = """{"workspaceId":"$ws","path":"$sessionPath","selection":{"variables":$variables,"queryRuns":$queryRuns}}"""
        val beforeRead = listOf(
            """{"workspaceId":"$ws","path":"$sessionPath","selection":{},"extra":true}""" to listOf("arguments" to "SCHEMA_MISMATCH"),
            """{"organizationId":"$ws","path":"$sessionPath"}""" to listOf("arguments" to "SCHEMA_MISMATCH"),
            """{"path":"$sessionPath"}""" to listOf("workspaceId" to "INVALID_FIELD"),
            """{"workspaceId":"not-a-uuid","path":"$sessionPath"}""" to listOf("workspaceId" to "INVALID_FIELD"),
            """{"workspaceId":"$ws","path":"$SHOW_DASHBOARD_PATH"}""" to listOf("path" to "INVALID_FIELD"),
            """{"workspaceId":"$ws","path":"$sessionPath/query-runs/$RUN_ONE"}""" to listOf("path" to "INVALID_FIELD"),
            """{"workspaceId":"$ws","path":42}""" to listOf("path" to "INVALID_FIELD"),
            """{"workspaceId":"$ws","path":"$sessionPath"}""" to listOf("selection" to "REQUIRED_FIELD_MISSING"),
            """{"workspaceId":"$ws","path":"$sessionPath","selection":[]}""" to listOf("selection" to "SCHEMA_MISMATCH"),
            """{"workspaceId":"$ws","path":"$sessionPath","selection":{"queryRuns":{}}}""" to listOf("selection" to "SCHEMA_MISMATCH"),
            """{"workspaceId":"$ws","path":"$sessionPath","selection":{"variables":{},"queryRuns":{},"extra":1}}""" to listOf("selection" to "SCHEMA_MISMATCH"),
            selection("""{"revenue":42}""") to listOf("selection" to "SCHEMA_MISMATCH"),
            """{"workspaceId":"$ws","path":"$sessionPath","selection":{"variables":[],"queryRuns":{}}}""" to listOf("selection" to "SCHEMA_MISMATCH"),
        )
        for ((arguments, expected) in beforeRead) {
            val violations = validationProblem("monomer_show", arguments).path("violations").map { it.path("field").asText() to it.path("code").asText() }
            assertEquals(expected, violations, arguments)
        }
        coVerify { handler wasNot Called }
        val otherSession = "$SHOW_DASHBOARD_PATH/sessions/01942b04-6e00-7000-8000-000000000099"
        val otherDashboard = "workspace/dashboards/01942b04-6e00-7000-8000-000000000098/sessions/$SHOW_SESSION_ID"
        val afterRead = listOf(
            selection("""{"revenue":"$revenue"}""") to listOf("selection.queryRuns.orders" to "REQUIRED_FIELD_MISSING"),
            selection("""{"revenue":"$revenue","orders":"$orders","extra":"$orders"}""") to listOf("selection.queryRuns.extra" to "INVALID_FIELD"),
            selection("""{"revenue":"$revenue","orders":"$orders","bad name":"$orders"}""") to listOf("selection.queryRuns" to "INVALID_FIELD"),
            selection("""{"revenue":"$otherSession/query-runs/$RUN_ONE","orders":"$orders"}""") to listOf("selection.queryRuns.revenue" to "INVALID_FIELD"),
            selection("""{"revenue":"$otherDashboard/query-runs/$RUN_ONE","orders":"$orders"}""") to listOf("selection.queryRuns.revenue" to "INVALID_FIELD"),
            selection("""{"revenue":"$SHOW_DASHBOARD_PATH/draft/query-runs/$RUN_ONE","orders":"$orders"}""") to listOf("selection.queryRuns.revenue" to "INVALID_FIELD"),
            selection("""{"revenue":"$revenue/result","orders":"$orders"}""") to listOf("selection.queryRuns.revenue" to "INVALID_FIELD"),
            selection("""{"revenue":"$revenue","orders":"$orders"}""", """{"region":"EMEA","city":"Seoul"}""") to listOf("selection.variables.city" to "INVALID_FIELD"),
            selection("""{"orders":"x"}""", """{"city":"Seoul"}""") to listOf("selection.queryRuns.revenue" to "REQUIRED_FIELD_MISSING",
                "selection.queryRuns.orders" to "INVALID_FIELD", "selection.variables.city" to "INVALID_FIELD"),
        ) + listOf("10", "1.5", """{"from":"2026-01-01","to":"2026-01-31"}""", """{"start":"2026-01-01"}""", """{"start":"2026-01-01","end":"2026-01-31","extra":true}""",
            "[]", """["EMEA","EMEA"]""", "[1]", """{"start":1,"end":2}""").map { value ->
            selection("""{"revenue":"$revenue","orders":"$orders"}""", """{"region":$value}""") to listOf("selection.variables.region" to "SCHEMA_MISMATCH")
        }
        for ((arguments, expected) in afterRead) {
            val problem = validationProblem("monomer_show", arguments)
            assertEquals(expected, problem.path("violations").map { it.path("field").asText() to it.path("code").asText() }, arguments)
            if (expected == listOf("selection.variables.region" to "SCHEMA_MISMATCH")) {
                assertEquals("Copy the variables from the resolve or refresh result unchanged.", problem.at("/violations/0/message").asText())
            }
        }
        coEvery { handler.read(any(), any()) } answers {
            AgentResourceOutcome.Found(AgentResourceEnvelope.DashboardSession(secondArg<AgentReadRequest>().path,
                AgentDashboardSessionResourceBody(showDefinition("""{}""", """{}"""), emptyMap(), emptyMap(), emptyList()), emptyList()))
        }
        assertEquals(listOf("selection.queryRuns.revenue" to "INVALID_FIELD"), validationProblem("monomer_show", selection("""{"revenue":"$revenue"}"""))
            .path("violations").map { it.path("field").asText() to it.path("code").asText() })
    }

    @Test
    fun `monomer_show maps admission and owner failures to the same Problems as monomer_read`() {
        authenticated()
        val mapper = ObjectMapper()
        val draftPath = "$SHOW_DASHBOARD_PATH/draft"
        fun problem(tool: String): JsonNode {
            val arguments = """{"workspaceId":"${workspaceId.value}","path":"$draftPath"}"""
            val result = mapper.readTree(post(toolCall(tool, arguments), "mcp-token").expectStatus().isOk.expectBody().returnResult().responseBody).path("result")
            assertEquals(true, result.path("isError").asBoolean(), tool)
            assertTrue(result.path("_meta").isMissingNode && result.path("structuredContent").isMissingNode, tool)
            return (mapper.readTree(problemJson(result.at("/content/0/text").asText())) as com.fasterxml.jackson.databind.node.ObjectNode).apply { remove(listOf("requestId", "traceId")) }
        }
        for (denial in listOf(AgentWorkspaceAdmissionDenial.NOT_FOUND, AgentWorkspaceAdmissionDenial.FORBIDDEN, AgentWorkspaceAdmissionDenial.UNAVAILABLE)) {
            coEvery { admission.decide(any()) } returns AgentWorkspaceAdmissionDecision.Denied(denial, null)
            assertEquals(problem("monomer_read"), problem("monomer_show"), denial.name)
        }
        admitExplorer()
        val rejected = AgentWorkspaceProblem("OWNER_UNAVAILABLE", "Dashboard owner unavailable", "The Dashboard owner is not available in the current Control composition.", AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE, true, currentRevision = null, violations = emptyList(), hints = emptyList(), nextActions = emptyList(), retryAfterSeconds = null, roleRequirement = null, partitionFilters = emptyList())
        for (outcome in listOf(AgentResourceOutcome.NotFound, AgentResourceOutcome.Forbidden, AgentResourceOutcome.Unavailable, AgentResourceOutcome.Rejected(rejected))) {
            coEvery { handler.read(any(), any()) } returns outcome
            val shown = problem("monomer_show")
            assertEquals(problem("monomer_read"), shown, outcome.toString())
            assertTrue(shown.path("code").asText() in setOf("NOT_FOUND", "ROLE_REQUIRED", "OWNER_UNAVAILABLE"))
        }
        coEvery { admission.decide(any()) } returns AgentWorkspaceAdmissionDecision.Denied(AgentWorkspaceAdmissionDenial.UNAUTHENTICATED, null)
        assertUnauthorized(post(toolCall("monomer_show", """{"workspaceId":"${workspaceId.value}","path":"$draftPath"}"""), "mcp-token"))
    }

    @Test
    fun `successful monomer_read results carry the view contract while other results do not`() {
        authenticated()
        admitExplorer()
        val draftPath = "$SHOW_DASHBOARD_PATH/draft/status"
        coEvery { handler.read(any(), any()) } returns AgentResourceOutcome.Found(AgentResourceEnvelope.DashboardDraftStatus(
            AgentResourcePath.parse(draftPath), AgentDashboardDraftStatusBody(AgentRevision(3), mapOf("revenue" to AgentDashboardDraftTargetQueryRun(
                AgentResourcePath.parse("$SHOW_DASHBOARD_PATH/draft/query-runs/$RUN_ONE"), AgentRunState.RUNNING)), emptyMap()),
        ))
        post(toolCall("monomer_read", """{"workspaceId":"${workspaceId.value}","path":"$draftPath"}"""), "mcp-token").expectStatus().isOk.expectBody()
            .jsonPath("$.result.isError").isEqualTo(false)
            .jsonPath("$.result._meta['cc.monomer/viewContract']").isEqualTo(1)
            .jsonPath("$.result.structuredContent.kind").isEqualTo("DASHBOARD_DRAFT_STATUS")
            .jsonPath("$.result.structuredContent.revision").doesNotExist()
            .jsonPath("$.result.structuredContent.availableOperations").doesNotExist()
            .jsonPath("$.result.structuredContent.body.draftRevision").isEqualTo("3")
            .jsonPath("$.result.structuredContent.body.targetQueryRuns.revenue.state").isEqualTo("RUNNING")
        coEvery { handler.read(any(), any()) } returns AgentResourceOutcome.NotFound
        post(toolCall("monomer_read", """{"workspaceId":"${workspaceId.value}","path":"$draftPath"}"""), "mcp-token").expectStatus().isOk.expectBody()
            .jsonPath("$.result.isError").isEqualTo(true).jsonPath("$.result._meta").doesNotExist()
        coEvery { handler.list(any(), any()) } returns AgentListingOutcome.Found(AgentResourceListingResponse(emptyList(), null, emptyList()))
        post(toolCall("monomer_list", """{"workspaceId":"${workspaceId.value}","path":"workspace/dashboards","limit":20}"""), "mcp-token").expectStatus().isOk.expectBody()
            .jsonPath("$.result.isError").isEqualTo(false).jsonPath("$.result._meta").doesNotExist()
        post(toolCall("monomer_read", """{"workspaceId":"${workspaceId.value}","path":"workspace/dashboards"}"""), "mcp-token").expectStatus().isOk.expectBody()
            .jsonPath("$.result.isError").isEqualTo(true).jsonPath("$.result._meta").doesNotExist()
    }

    private fun mutationArguments(): List<Pair<String, String>> {
        val dashboardPath = "workspace/dashboards/${workspaceId.value}"
        return listOf(
            "monomer_create" to """{"workspaceId":"${workspaceId.value}","path":"workspace/dashboards","input":{"kind":"DASHBOARD_DEFINITION","definition":{"schemaVersion":1,"meta":{"title":"Orders"},"parameters":{},"variables":{},"queries":{},"widgets":{},"layout":{"sections":[]}}}}""",
            "monomer_edit" to """{"workspaceId":"${workspaceId.value}","path":"$dashboardPath/draft","revision":"1","patch":[{"op":"replace","path":"/definition/meta/title","value":"Revenue"}]}""",
            "monomer_invoke" to """{"workspaceId":"${workspaceId.value}","path":"$dashboardPath","action":"delete"}""",
        )
    }

    private fun admitExplorer() {
        coEvery { admission.decide(any()) } answers {
            AgentWorkspaceAdmissionDecision.Authorized(AgentWorkspaceRoleEvidence(OrganizationPlan.FREE, CanonicalPrincipal(userId, PrincipalKind.USER), organizationId, workspaceId,
                AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"),
                WorkspaceRole.EXPLORER, firstArg<AgentWorkspaceAdmissionRequest>().intent))
        }
    }

    private fun showDefinition(queries: String, variables: String): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement("""{"schemaVersion":1,"meta":{"title":"Revenue \"live\""},"parameters":{},"variables":$variables,"queries":$queries,"widgets":{},"layout":{"sections":[]}}""")
            as kotlinx.serialization.json.JsonObject

    private companion object {
        const val VIEW_HTML = "<!doctype html><title>Fixture dashboard view</title>"
        val SHOWN_AT: java.time.Instant = java.time.Instant.parse("2026-10-10T01:02:03.456Z")
        const val SHOW_DASHBOARD_ID = "01942b04-6e00-7000-8000-000000000010"
        const val SHOW_DASHBOARD_PATH = "workspace/dashboards/$SHOW_DASHBOARD_ID"
        const val SHOW_SESSION_ID = "01942b04-6e00-7000-8000-000000000012"
        const val RUN_ONE = "01942b04-6e00-7000-8000-000000000021"
        const val RUN_TWO = "01942b04-6e00-7000-8000-000000000022"
    }

    private fun problemJson(text: String) = text.substringAfter('\n', text)

    private fun assertUnauthorized(response: WebTestClient.ResponseSpec) {
        response.expectStatus().isUnauthorized.expectHeader().valueEquals("WWW-Authenticate",
            "Bearer resource_metadata=\"https://api.example.com/.well-known/oauth-protected-resource/mcp\", scope=\"agent.workspace\"")
            .expectHeader().valueEquals("Cache-Control", "no-store").expectBody().isEmpty
    }

    private fun authenticated() {
        coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_MCP) } returns OAuthOutcome.Success(OAuthAgentIdentity(userId, clientId = "https://client.example/metadata.json"))
        coEvery { oauth.validateCredential(any(), ExpectedOAuthResource.AGENT_REST) } returns OAuthOutcome.Success(OAuthAgentIdentity(userId, clientId = "https://client.example/metadata.json"))
    }
    private fun toolCall(name: String, arguments: String) = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}"""
    private fun post(body: String, token: String? = null, version: String = "2025-11-25"): WebTestClient.ResponseSpec = client.post().uri("/mcp")
        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
        .header("MCP-Protocol-Version", version).headers { if (token != null) it.setBearerAuth(token) }.bodyValue(body).exchange()
}
