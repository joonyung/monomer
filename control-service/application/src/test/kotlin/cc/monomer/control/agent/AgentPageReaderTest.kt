@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package cc.monomer.control.agent

import cc.monomer.agent.api.*
import cc.monomer.control.*
import cc.monomer.control.pagereader.PublicPagesAccess
import cc.monomer.control.publicapi.AgentWorkspaceJson
import cc.monomer.control.publicapi.PublicControlContractValidator
import cc.monomer.dashboard.api.*
import cc.monomer.dashboard.application.DashboardResultReadPort
import cc.monomer.identity.api.*
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.pages.api.*
import cc.monomer.semantic.api.MetricDisplay
import cc.monomer.semantic.api.MetricDisplayCurrency
import cc.monomer.semantic.api.SemanticOutputColumnDisplay
import cc.monomer.semantic.api.SemanticResultColumnKey
import cc.monomer.semantic.api.SemanticResultColumns
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import kotlin.test.*
import kotlin.uuid.Uuid
import tools.jackson.databind.json.JsonMapper

class AgentPageReaderTest {
    private val workspace = WorkspaceId.generate()
    private val actor = CanonicalPrincipal(PrincipalId.generate(), PrincipalKind.USER)
    private val organization = OrganizationId.generate()
    private val page = Uuid.generateV7()
    private val dashboardId = DashboardId(Uuid.generateV7())
    private val sessionId = DashboardSessionId(Uuid.generateV7())
    private val runId = QueryRunId(Uuid.generateV7())
    private val credential = PresentedCredential("fixture")
    private val admission = mockk<ControlAdmissionFacade>()
    private val pages = mockk<PagesCurrentReadPort>()
    private val dashboards = mockk<DashboardOwnerPort>()
    private val results = mockk<DashboardResultReadPort>()
    private val public = mockk<WorkspacePublicPagesQuery>()
    private var insideSnapshot = false
    private val access = PublicPagesAccess(public, admission, object : ConsistentSnapshotTransactionRunner {
        override suspend fun <T> run(action: suspend () -> T): T {
            check(!insideSnapshot)
            insideSnapshot = true
            return try { action() } finally { insideSnapshot = false }
        }
    })
    private val service = cc.monomer.control.pages.publicpages.PageReaderService(access, pages, dashboards, results, mockk(), public, Clock.systemUTC())
    private val reader = AgentPageReader(service)
    private val now = Instant.parse("2026-09-29T00:00:00Z")
    private val session = DashboardOwnerSession(sessionId, workspace, dashboardId,
        Json.parseToJsonElement("""{"meta":{"title":"Published"},"parameters":{},"variables":{},"widgets":{},"layout":{},"queries":{"revenue":{"type":"SQL","sql":"secret sql","connectorId":"secret connector"}}}""") as JsonObject,
        emptyMap(), emptyMap(), emptyList(), now, now)
    private val caller = mockk<AgentWorkspaceCaller> {
        every { workspaceId } returns workspace
        every { actorPrincipalId } returns actor.id.value.toString()
        every { presentedCredential } returns credential
        every { expectedOAuthResource } returns ExpectedOAuthResource.AGENT_REST
        every { requestId } returns AgentRequestId("srv_reader")
        every { role } returns WorkspaceRole.VIEWER
    }
    init {
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Admitted(IdentityMembershipEvidence(actor, organization, workspace, AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), WorkspaceRole.VIEWER))
        coEvery { admission.authorizeCredential(any()) } returns ControlAdmissionDecision.Admitted(IdentityCredentialEvidence(actor, CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata")))
        coEvery { public.pageReaderName(workspace) } returns "Workspace"
        coEvery { pages.tree(workspace.value) } returns tree()
        coEvery { dashboards.read(workspace, dashboardId) } returns DashboardOwnerOutcome.Success(DashboardOwnerDashboard(dashboardId, workspace, session.definition, now, now, null, null, null))
        coEvery { dashboards.readSession(workspace, dashboardId, sessionId) } returns DashboardOwnerOutcome.Success(session)
    }
    @Test fun `resolve hides lost access and marks execution backpressure retryable`() = runTest {
        val request = AgentInvokeRequest(path("workspace/pages/$page/sessions/$sessionId"), "resolve", AgentInvokeInput.DashboardSessionResolve(emptyMap()))
        for ((ownerCode, status, publicCode) in listOf(
            Triple("DASHBOARD_QUERY_UNAUTHORIZED", AgentWorkspaceProblemStatus.NOT_FOUND, "NOT_FOUND"),
            Triple("EXECUTION_BACKPRESSURED", AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE, "EXECUTION_BACKPRESSURED"),
        )) {
            coEvery { dashboards.resolvePublicSession(any()) } returns DashboardOwnerOutcome.Rejected(ownerCode, "private diagnostic")
            val problem = assertIs<AgentActionOutcome.Rejected>(reader.resolve(caller, request)).problem
            assertEquals(status, problem.status)
            assertEquals(publicCode, problem.code)
            assertEquals(ownerCode == "EXECUTION_BACKPRESSURED", problem.retryable)
            assertFalse(problem.toString().contains("private diagnostic"))
        }
    }

    @Test fun `resolve preserves owner input details and retryable concurrency codes`() = runTest {
        val request = AgentInvokeRequest(path("workspace/pages/$page/sessions/$sessionId"), "resolve", AgentInvokeInput.DashboardSessionResolve(emptyMap()))
        for (code in listOf("INVALID_VARIABLE_VALUE", "INVALID_SESSION_PARAMETERS", "SESSION_CONFLICT", "QUERY_RUN_CONFLICT", "DRAFT_CONFLICT", "EXECUTION_ADMISSION_CONFLICT", "QUERY_RUN_CANCEL_CONFLICT")) {
            val detail = if (code.startsWith("INVALID")) "variables.region must be a string" else "Concurrent Dashboard operation"
            coEvery { dashboards.resolvePublicSession(any()) } returns DashboardOwnerOutcome.Rejected(code, detail)
            val problem = assertIs<AgentActionOutcome.Rejected>(reader.resolve(caller, request)).problem
            assertEquals(code, problem.code)
            assertEquals(if (code.startsWith("INVALID")) 400 else 409, problem.status.value)
            assertEquals(detail, problem.detail)
            assertEquals(!code.startsWith("INVALID"), problem.retryable)
        }
    }

    @Test fun `Viewer Session uses public display and advertises only existing Session operations`() = runTest {
        val path = path("workspace/pages/$page/sessions/$sessionId")
        val resource = assertIs<AgentResourceOutcome.Found>(reader.read(caller, AgentReadRequest(path, null), null)).resource
        val body = assertIs<AgentPageSessionBody>(resource.body)
        assertEquals(setOf("parameters", "variables", "widgets", "layout", "queryNames", "queryTypes"), body.display.keys)
        assertFalse(body.toString().contains("secret sql"))
        assertFalse(body.toString().contains("secret connector"))
        assertEquals(setOf("resolve"), resource.availableOperations.filterIsInstance<AgentAvailableOperation.Invoke>().map { it.action }.toSet())
        PublicControlContractValidator().agentResource(AgentWorkspaceJson.agentResourceJson(AgentRequestId("srv_reader"), resource))
        coVerify(exactly = 0) { public.isPublic(workspace) }
    }
    @Test fun `unlink and lost direct membership hide existing sessions even when Workspace is public`() = runTest {
        val request = AgentReadRequest(path("workspace/pages/$page/sessions/$sessionId"), null)
        coEvery { pages.tree(workspace.value) } returns tree().copy(placements = emptyList())
        assertEquals(AgentWorkspaceProblemStatus.NOT_FOUND, assertIs<AgentResourceOutcome.Rejected>(reader.read(caller, request, null)).problem.status)
        coEvery { pages.tree(workspace.value) } returns tree()
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY, ControlAdmissionDenial.Identity(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE))
        assertEquals(AgentWorkspaceProblemStatus.NOT_FOUND, assertIs<AgentResourceOutcome.Rejected>(reader.read(caller, request, null)).problem.status)
        coVerify(exactly = 0) { public.isPublic(workspace) }
        coVerify(exactly = 0) { dashboards.readSession(workspace, dashboardId, sessionId) }
    }
    @Test fun `resolve retains OAuth audience credential and real actor without opening a Session`() = runTest {
        val command = slot<ResolvePublicDashboardSessionCommand>()
        coEvery { dashboards.resolvePublicSession(capture(command)) } returns DashboardOwnerOutcome.Success(DashboardOwnerSessionResolution(emptyMap(), mapOf("revenue" to runId)))
        val request = AgentInvokeRequest(path("workspace/pages/$page/sessions/$sessionId"), "resolve", AgentInvokeInput.DashboardSessionResolve(emptyMap()))
        val result = assertIs<AgentActionOutcome.Completed>(reader.resolve(caller, request)).action
        assertEquals(credential, command.captured.credential)
        assertEquals(ExpectedOAuthResource.AGENT_REST, command.captured.expectedOAuthResource)
        assertEquals(actor.id.value.toString(), command.captured.actorId)
        assertTrue(AgentWorkspaceResponseRelations.matches(request, result))
        coVerify(exactly = 0) { dashboards.openSession(any()) }
        PublicControlContractValidator().agentAction(AgentWorkspaceJson.agentActionJson(AgentRequestId("srv_reader"), result, workspace, java.net.URI("http://localhost:3100")))
    }
    @Test fun `Run status hides internal failure and checks exact current Session`() = runTest {
        val run = DashboardOwnerQueryRun(runId, dashboardId, sessionId, "revenue", "SEMANTIC", cc.monomer.connector.api.ConnectorId.generate(), "FAILED", false, "WAREHOUSE_ERROR", "secret table and credentials", emptyList(), now, now, null, null, null, outputColumnDisplays = null, reportingTimeZone = null)
        coEvery { dashboards.readSessionQueryRun(any()) } returns DashboardOwnerOutcome.Success(run)
        val request = AgentReadRequest(path("workspace/pages/$page/sessions/$sessionId/query-runs/$runId"), null)
        val resource = assertIs<AgentResourceOutcome.Found>(reader.read(caller, request, null)).resource
        val body = assertIs<AgentPageRunBody>(resource.body)
        assertEquals("QUERY_FAILED", body.failureCode)
        assertFalse(body.toString().contains("secret"))
        PublicControlContractValidator().agentResource(AgentWorkspaceJson.agentResourceJson(AgentRequestId("srv_reader"), resource))
        coEvery { dashboards.readSessionQueryRun(any()) } returns DashboardOwnerOutcome.Success(run.copy(viewerSessionId = DashboardSessionId(Uuid.generateV7())))
        assertEquals(AgentWorkspaceProblemStatus.NOT_FOUND, assertIs<AgentResourceOutcome.Rejected>(reader.read(caller, request, null)).problem.status)
    }
    @Test fun `Document read uses only latest display content and denies download`() = runTest {
        coEvery { pages.tree(workspace.value) } returns CurrentPagesTree(1, listOf(CurrentPage(page, "DOCUMENT", "MARKDOWN", null, "Guide", "Guide", null)), listOf(CurrentPagePlacement(page, emptyList())))
        coEvery { pages.latestContentVersion(workspace.value, page) } coAnswers { assertTrue(insideSnapshot); 3 }
        coEvery { pages.content(workspace.value, page, 3) } coAnswers {
            assertFalse(insideSnapshot)
            CurrentPageContentOutcome.Found(CurrentPageContent("Guide", "MARKDOWN", "# Latest guide"))
        }
        val request = AgentReadRequest(path("workspace/pages/$page/content"), null)
        val resource = assertIs<AgentResourceOutcome.Found>(reader.read(caller, request, null)).resource
        assertEquals("# Latest guide", assertIs<AgentPageContentBody>(resource.body).source)
        PublicControlContractValidator().agentResource(AgentWorkspaceJson.agentResourceJson(AgentRequestId("srv_reader"), resource))
    }
    @Test fun `registry routes every Page reader operation to the installed Pages owner`() = runTest {
        val owner = mockk<AgentPagesOwnerPort>()
        coEvery { owner.read(caller, any()) } returns AgentResourceOutcome.Forbidden
        coEvery { owner.list(caller, any()) } returns AgentListingOutcome.Forbidden
        coEvery { owner.invoke(caller, any()) } returns AgentActionOutcome.Forbidden
        val registry = cc.monomer.agent.application.AgentResourceRouteRegistry(mockk(), mockk(), mockk(), mockk(), mockk(), owner, listOf(path("workspace/pages")), mockk(), null)
        val sessionPath = "workspace/pages/$page/sessions/$sessionId"
        for (value in listOf("workspace/pages/$page/content", sessionPath, "$sessionPath/query-runs/$runId", "$sessionPath/query-runs/$runId/result")) {
            val request = AgentReadRequest(path(value), null)
            assertEquals(AgentResourceOutcome.Forbidden, registry.read(caller, request))
            coVerify(exactly = 1) { owner.read(caller, request) }
        }
        for (value in listOf("workspace/pages/$page/sessions", "$sessionPath/query-runs")) {
            val request = AgentListRequest(path(value), null, 20)
            assertEquals(AgentListingOutcome.Forbidden, registry.list(caller, request))
            coVerify(exactly = 1) { owner.list(caller, request) }
        }
        val resolve = AgentInvokeRequest(path(sessionPath), "resolve", AgentInvokeInput.DashboardSessionResolve(emptyMap()))
        assertEquals(AgentActionOutcome.Forbidden, registry.invoke(caller, resolve))
        coVerify(exactly = 1) { owner.invoke(caller, resolve) }
    }
    @Test fun `Web and Agent Viewer share placed Page projection without internal timestamps`() = runTest {
        val hidden = Uuid.generateV7()
        coEvery { pages.tree(workspace.value) } returns CurrentPagesTree(8,
            listOf(CurrentPage(page, "DOCUMENT", "MARKDOWN", null, "Guide", "Guide", null),
                CurrentPage(hidden, "DOCUMENT", "HTML", null, "Unplaced", "Unplaced", null)),
            listOf(CurrentPagePlacement(page, emptyList())))
        val webProjection = service.tree(cc.monomer.control.pages.publicpages.PageReaderCaller(workspace, credential, null, null, null))
        assertEquals(listOf(page), webProjection.pages.map { it.pageId })
        val resource = assertIs<AgentResourceOutcome.Found>(reader.read(caller, AgentReadRequest(path("workspace/pages/tree"), null), null)).resource
        val body = assertIs<AgentPagesTreeResourceBody>(resource.body)
        assertEquals(webProjection.pages.single().displayTitle, body.pages.single().body.displayTitle)
        assertEquals(AgentPageContentFormat.MARKDOWN, body.pages.single().body.documentFormat)
        val json = AgentWorkspaceJson.agentResourceJson(AgentRequestId("srv_reader"), resource)
        PublicControlContractValidator().agentResource(json)
        assertFalse(JsonMapper.builder().build().writeValueAsString(json).contains("createdAt"))
        assertFalse(JsonMapper.builder().build().writeValueAsString(json).contains(hidden.toString()))
        coVerify(exactly = 2) { admission.authorizeWorkspaceMembership(any()) }
        coVerify(exactly = 0) { admission.authorizeCredential(any()) }
    }

    @Test fun `result file is read outside the snapshot that verifies its current Session manifest`() = runTest {
        val columnKeys = listOf("dimension.orders.country", "metric.revenue", "metric.margin", "metric.orders")
        val displays = listOf(null, MetricDisplay.Currency(2, MetricDisplayCurrency.USD), MetricDisplay.Percent(1), null)
        val run = DashboardOwnerQueryRun(runId, dashboardId, sessionId, "revenue", "SEMANTIC", cc.monomer.connector.api.ConnectorId.generate(), "SUCCEEDED", true, null, null, columnKeys, now, now, null, null, null,
            outputColumnDisplays = columnKeys.zip(displays) { key, display -> SemanticOutputColumnDisplay(SemanticResultColumnKey(key), display) }, reportingTimeZone = java.time.ZoneId.of("Asia/Seoul"))
        val manifest = cc.monomer.execution.api.ArrowResultManifest("private/result", 100u, "etag", 1u, 1, "application/vnd.apache.arrow.file")
        val artifactColumns = SemanticResultColumns.fromKeys(columnKeys.map(::SemanticResultColumnKey))
        val rows = listOf(listOf(JsonPrimitive("US"), JsonPrimitive("1234.50"), JsonPrimitive("0.123"), JsonNull))
        coEvery { dashboards.readSessionResult(any()) } coAnswers {
            assertTrue(insideSnapshot)
            DashboardOwnerResultLookupOutcome.Success(DashboardOwnerResultLookup(run, manifest))
        }
        coEvery { results.read(manifest) } coAnswers {
            assertFalse(insideSnapshot)
            DashboardResultReadPort.DashboardResultPayload(artifactColumns.zip(listOf("TEXT", "DECIMAL", "DECIMAL", "INTEGER")) { column, type -> DashboardResultReadPort.Column(column.sqlName, type, type != "TEXT", display = null) }, rows)
        }
        val request = AgentReadRequest(path("workspace/pages/$page/sessions/$sessionId/query-runs/$runId/result"), null)
        val resource = assertIs<AgentResourceOutcome.Found>(reader.read(caller, request, null)).resource
        assertTrue(AgentWorkspaceResponseRelations.matches(request, resource))
        val body = assertIs<AgentDashboardResultResourceBody>(resource.body)
        assertEquals(columnKeys, body.columns.map { it.name })
        assertEquals(listOf(false, true, true, true), body.columns.map { it.nullable })
        assertEquals(listOf(null, AgentMetricDisplay.Currency(2, AgentMetricDisplayCurrency.USD), AgentMetricDisplay.Percent(1), null), body.columns.map { it.display })
        assertEquals(rows, body.rows)
        assertEquals(java.time.ZoneId.of("Asia/Seoul"), body.reportingTimeZone)
        val json = AgentWorkspaceJson.agentResourceJson(AgentRequestId("srv_reader"), resource)
        val validator = PublicControlContractValidator()
        validator.agentResource(json)
        @Suppress("UNCHECKED_CAST")
        assertEquals("Asia/Seoul", (json["body"] as Map<String, Any?>)["reportingTimeZone"])
        assertEquals(emptyList<Any>(), json["availableOperations"])
        assertFailsWith<IllegalStateException> { validator.agentResource(json - "availableOperations") }
        coVerify(exactly = 1) { results.read(manifest) }
    }
    @Test fun `storage failures remain retryable and document errors do not use result codes`() = runTest {
        coEvery { pages.tree(workspace.value) } returns CurrentPagesTree(1,
            listOf(CurrentPage(page, "DOCUMENT", "MARKDOWN", null, "Guide", "Guide", null)), listOf(CurrentPagePlacement(page, emptyList())))
        coEvery { pages.latestContentVersion(workspace.value, page) } returns 1
        coEvery { pages.content(workspace.value, page, 1) } returns CurrentPageContentOutcome.Unavailable
        val request = AgentReadRequest(path("workspace/pages/$page/content"), null)
        val problem = assertIs<AgentResourceOutcome.Rejected>(reader.read(caller, request, null)).problem
        assertEquals("OWNER_UNAVAILABLE", problem.code)
        assertEquals(AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE, problem.status)
        assertTrue(problem.retryable)
        coEvery { pages.tree(workspace.value) } throws IllegalStateException("storage is unavailable")
        val treeProblem = assertIs<AgentResourceOutcome.Rejected>(reader.read(caller, AgentReadRequest(path("workspace/pages/tree"), null), null)).problem
        assertEquals("OWNER_UNAVAILABLE", treeProblem.code)
        assertTrue(treeProblem.retryable)
    }

    @Test fun `forged tree cursor UUID is a cursor failure`() = runTest {
        val collection = path("workspace/pages")
        for (suffix in listOf("1|invalid-uuid", "invalid-revision|$page")) {
            val cursor = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "$workspace|$collection|$suffix".toByteArray(Charsets.UTF_8))
            val problem = assertIs<AgentListingOutcome.Rejected>(reader.list(caller, AgentListRequest(collection, cursor, 20), null)).problem
            assertEquals("CURSOR_INVALID", problem.code)
            assertEquals(AgentWorkspaceProblemStatus.BAD_REQUEST, problem.status)
            assertFalse(problem.retryable)
        }
    }

    @Test fun `Page Session cursor round trips and rejects a changed linked Dashboard`() = runTest {
        val commands = mutableListOf<ListDashboardSessionsCommand>()
        coEvery { dashboards.listSessions(capture(commands)) } returns DashboardOwnerOutcome.Success(
            DashboardOwnerSessionPage(emptyList(), DashboardOwnerSessionPageCursor(dashboardId, now, sessionId)),
        )
        val collection = path("workspace/pages/$page/sessions")
        val first = assertIs<AgentListingOutcome.Found>(reader.list(caller, AgentListRequest(collection, null, 1), null)).listing
        assertIs<AgentListingOutcome.Found>(reader.list(caller, AgentListRequest(collection, first.nextCursor, 1), null))
        assertEquals(DashboardOwnerSessionPageCursor(dashboardId, now, sessionId), commands.last().cursor)
        val other = DashboardId.generate()
        coEvery { pages.tree(workspace.value) } returns tree().copy(pages = listOf(CurrentPage(page, "DASHBOARD", null, other.value, "Published", "Published", null)))
        coEvery { dashboards.read(workspace, other) } returns DashboardOwnerOutcome.Success(DashboardOwnerDashboard(other, workspace, session.definition, now, now, null, null, null))
        val rejected = assertIs<AgentListingOutcome.Rejected>(reader.list(caller, AgentListRequest(collection, first.nextCursor, 1), null)).problem
        assertEquals("CURSOR_INVALID", rejected.code)
        assertEquals(AgentWorkspaceProblemStatus.BAD_REQUEST, rejected.status)
        assertEquals(2, commands.size)
    }

    @Test fun `forged session cursor is invalid rather than a result or SQL failure`() = runTest {
        val collection = path("workspace/pages/$page/sessions")
        val cursor = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            "$workspace|$collection|not-an-instant|$sessionId".toByteArray(Charsets.UTF_8))
        val problem = assertIs<AgentListingOutcome.Rejected>(reader.list(caller, AgentListRequest(collection, cursor, 20), null)).problem
        assertEquals("CURSOR_INVALID", problem.code)
        assertEquals(AgentWorkspaceProblemStatus.BAD_REQUEST, problem.status)
        assertFalse(problem.retryable)
        coVerify(exactly = 0) { dashboards.listSessions(any()) }
    }

    private fun tree() = CurrentPagesTree(1, listOf(CurrentPage(page, "DASHBOARD", null, dashboardId.value, "Published", "Published", null)), listOf(CurrentPagePlacement(page, emptyList())))
    private fun path(value: String) = AgentResourcePath.parse(value)
}
