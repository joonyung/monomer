@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.agent

import cc.monomer.agent.api.AgentActionResult
import cc.monomer.agent.api.AgentActionOutcome
import cc.monomer.agent.api.AgentActionStatus
import cc.monomer.agent.api.AgentCreateInput
import cc.monomer.agent.api.AgentCreateRequest
import cc.monomer.agent.api.AgentEditRequest
import cc.monomer.agent.api.AgentInvokeInput
import cc.monomer.agent.api.AgentInvokeRequest
import cc.monomer.agent.api.AgentJsonPatchOp
import cc.monomer.agent.api.AgentJsonPatchOperation
import cc.monomer.agent.api.AgentListRequest
import cc.monomer.agent.api.AgentListingOutcome
import cc.monomer.agent.api.AgentReadRequest
import cc.monomer.agent.api.AgentResourceEnvelope
import cc.monomer.agent.api.AgentResourceKind
import cc.monomer.agent.api.AgentResourceOutcome
import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.agent.api.AgentWorkspaceOperation
import cc.monomer.agent.api.AgentWorkspaceCaller
import cc.monomer.connector.api.ConnectorId
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.dashboard.api.ApplyDraftCommand
import cc.monomer.dashboard.api.CancelDashboardDraftRunCommand
import cc.monomer.dashboard.api.CreateDashboardCommand
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardOwnerCommandMetadata
import cc.monomer.dashboard.api.DashboardOwnerDashboard
import cc.monomer.dashboard.api.DashboardOwnerDraft
import cc.monomer.dashboard.api.DashboardOwnerOutcome
import cc.monomer.dashboard.api.DashboardOwnerPort
import cc.monomer.dashboard.api.DashboardOwnerQueryRun
import cc.monomer.dashboard.api.DashboardOwnerResultLookup
import cc.monomer.dashboard.api.DashboardOwnerResultLookupOutcome
import cc.monomer.dashboard.api.DashboardOwnerDashboardListItem
import cc.monomer.dashboard.api.DashboardOwnerDashboardPage
import cc.monomer.dashboard.api.DashboardOwnerDashboardPageCursor
import cc.monomer.dashboard.api.DashboardOwnerSession
import cc.monomer.dashboard.api.DashboardSessionId
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.dashboard.api.ReadDashboardDraftRunCommand
import cc.monomer.dashboard.api.ReadDashboardDraftResultCommand
import cc.monomer.dashboard.api.ReadDashboardSessionResultCommand
import cc.monomer.dashboard.api.RefreshDraftCommand
import cc.monomer.dashboard.api.StartDraftCommand
import cc.monomer.control.dashboard.DashboardOwnerPortAdapter
import cc.monomer.dashboard.application.DashboardDefinitionDecoder
import cc.monomer.dashboard.application.DashboardService
import cc.monomer.dashboard.application.DashboardDraftService
import cc.monomer.dashboard.application.DashboardDraftRepository
import cc.monomer.dashboard.application.DashboardCommandReceiptRepository
import cc.monomer.dashboard.application.QueryRunRepository
import cc.monomer.dashboard.domain.DashboardDraft
import cc.monomer.dashboard.application.DashboardResultReadPort
import cc.monomer.execution.api.ArrowResultManifest
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.mockk.every
import io.mockk.coEvery
import io.mockk.confirmVerified
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot

class AgentDashboardOwnerAdapterTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC)
    private val workspaceId = WorkspaceId(Uuid.parse("01942b04-6e00-7000-8000-000000000001"))
    private val dashboardId = DashboardId(Uuid.parse("01942b04-6e00-7000-8000-000000000010"))
    private val queryRunId = QueryRunId(Uuid.parse("01942b04-6e00-7000-8000-000000000011"))
    private val sessionId = DashboardSessionId(Uuid.parse("01942b04-6e00-7000-8000-000000000012"))

    @Test
    fun `delete and restore use owner operations and exact result paths`() = kotlinx.coroutines.test.runTest {
        val owner = mockk<DashboardOwnerPort>()
        val deleted = slot<cc.monomer.dashboard.api.DeleteDashboardCommand>()
        val restored = slot<cc.monomer.dashboard.api.RestoreDashboardCommand>()
        coEvery { owner.delete(capture(deleted)) } returns DashboardOwnerOutcome.Success(Unit)
        coEvery { owner.restore(capture(restored)) } returns DashboardOwnerOutcome.Success(dashboard())
        val evidence = mockk<cc.monomer.identity.api.IdentityRoleEvidence>()
        val actor = Uuid.generateV7()
        val principal = mockk<cc.monomer.identity.api.CanonicalPrincipal>()
        every { principal.id } returns cc.monomer.identity.api.PrincipalId(actor)
        every { evidence.actor } returns principal
        every { evidence.organizationId } returns cc.monomer.identity.api.OrganizationId.generate()
        every { evidence.agentClientId } returns cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE
        val admission = mockk<ControlAdmissionFacade>()
        coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(evidence)
        val adapter = adapter(owner, admission) { _, _ -> }
        val deletedAction = assertIs<AgentActionResult.Invoke>(assertIs<AgentActionOutcome.Completed>(adapter.invoke(caller(),
            AgentInvokeRequest(path("workspace/dashboards/$dashboardId"), "delete", AgentInvokeInput.None))).action)
        assertEquals(AgentActionStatus.COMPLETED, deletedAction.status)
        assertEquals(AgentResourceKind.COLLECTION, deletedAction.result.kind)
        assertEquals("workspace/dashboards", deletedAction.result.path.value)
        val validator = cc.monomer.control.publicapi.PublicControlContractValidator()
        validator.agentAction(cc.monomer.control.publicapi.AgentWorkspaceJson.agentActionJson(
            cc.monomer.agent.api.AgentRequestId("srv_test"), deletedAction, workspaceId, java.net.URI("http://localhost:3100")))
        assertEquals(actor.toString(), deleted.captured.metadata.actorId)
        assertEquals(cc.monomer.identity.api.CredentialSurface.AGENT, deleted.captured.metadata.credentialSurface)
        val change = Uuid.generateV7()
        val restoreAction = assertIs<AgentActionResult.Invoke>(assertIs<AgentActionOutcome.Completed>(adapter.invoke(caller(),
            AgentInvokeRequest(path("workspace/dashboards/$dashboardId/history/$change"), "restore", AgentInvokeInput.None))).action)
        assertEquals(AgentResourceKind.DASHBOARD, restoreAction.result.kind)
        assertEquals("workspace/dashboards/$dashboardId", restoreAction.result.path.value)
        assertEquals("srv_test", restored.captured.metadata.idempotencyKey)
        assertEquals(change, restored.captured.changeId)
        validator.agentAction(cc.monomer.control.publicapi.AgentWorkspaceJson.agentActionJson(
            cc.monomer.agent.api.AgentRequestId("srv_test"), restoreAction, workspaceId, java.net.URI("http://localhost:3100")))
        coEvery { owner.delete(any()) } returns DashboardOwnerOutcome.NotFound
        assertIs<AgentActionOutcome.NotFound>(adapter.invoke(caller(), AgentInvokeRequest(path("workspace/dashboards/$dashboardId"), "delete", AgentInvokeInput.None)))
        coEvery { owner.delete(any()) } returns DashboardOwnerOutcome.Rejected("DASHBOARD_DELETE_RETRY_REQUIRED", "Retry")
        val retry = assertIs<AgentActionOutcome.Rejected>(adapter.invoke(caller(), AgentInvokeRequest(path("workspace/dashboards/$dashboardId"), "delete", AgentInvokeInput.None)))
        assertEquals(cc.monomer.agent.api.AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE, retry.problem.status)
        assertEquals("A Dashboard query run changed while the Dashboard was being deleted. Retry the delete.", retry.problem.detail)
    }

    @Test
    fun `restore rejections keep the owner code and English detail`() = kotlinx.coroutines.test.runTest {
        val owner = mockk<DashboardOwnerPort>()
        val adapter = adapter(owner) { _, _ -> }
        val request = AgentInvokeRequest(path("workspace/dashboards/$dashboardId/history/${Uuid.generateV7()}"), "restore", AgentInvokeInput.None)
        val live = "This dashboard isn't deleted, so there's nothing to restore. Open it to edit it instead."
        coEvery { owner.restore(any()) } returns DashboardOwnerOutcome.Rejected("DASHBOARD_NOT_RESTORABLE", live)
        val notRestorable = assertIs<AgentActionOutcome.Rejected>(adapter.invoke(caller(), request)).problem
        assertEquals("DASHBOARD_NOT_RESTORABLE", notRestorable.code)
        assertEquals(live, notRestorable.detail)
        assertEquals(cc.monomer.agent.api.AgentWorkspaceProblemStatus.CONFLICT, notRestorable.status)
        assertEquals(null, notRestorable.currentRevision)
        coEvery { owner.restore(any()) } returns DashboardOwnerOutcome.Rejected("INVALID_DASHBOARD_DEFINITION", "layout.sections must not be empty")
        val invalid = assertIs<AgentActionOutcome.Rejected>(adapter.invoke(caller(), request)).problem
        assertEquals("INVALID_DASHBOARD_DEFINITION", invalid.code)
        assertEquals("layout.sections must not be empty", invalid.detail)
        assertEquals(cc.monomer.agent.api.AgentWorkspaceProblemStatus.BAD_REQUEST, invalid.status)
        assertEquals(false, invalid.retryable)
    }

    @Test
    fun `history lists immutable versions and exposes restore only when owner permits`() = kotlinx.coroutines.test.runTest {
        val owner = mockk<DashboardOwnerPort>()
        val changeId = Uuid.generateV7()
        val version = cc.monomer.dashboard.api.DashboardOwnerDefinitionChange(changeId, workspaceId, dashboardId, "Orders",
            cc.monomer.dashboard.api.DashboardDefinitionChangeAction.DELETED, authoredDefinition("""{"mode":"CONTENT"}"""),
            null, null, null, clock.instant(), true)
        coEvery { owner.listHistory(any()) } returns DashboardOwnerOutcome.Success(
            cc.monomer.dashboard.api.DashboardOwnerHistoryPage(dashboardId, "Orders", true, listOf(version), null))
        coEvery { owner.readHistory(any()) } returns DashboardOwnerOutcome.Success(version)
        val adapter = adapter(owner)
        val historyPath = path("workspace/dashboards/$dashboardId/history")
        val listing = assertIs<AgentListingOutcome.Found>(adapter.list(caller(), AgentListRequest(historyPath, null, 20))).listing
        val item = assertIs<cc.monomer.agent.api.AgentResourceListingItem.DashboardDefinitionVersion>(listing.items.single())
        assertEquals("DELETED", item.action)
        assertEquals(null, item.actor)
        assertEquals(true, item.restorable)
        val validator = cc.monomer.control.publicapi.PublicControlContractValidator()
        validator.agentListing(cc.monomer.control.publicapi.AgentWorkspaceJson.agentListingJson(cc.monomer.agent.api.AgentRequestId("srv_test"), listing))
        assertTrue(cc.monomer.agent.api.AgentWorkspaceResponseRelations.matches(AgentListRequest(historyPath, null, 20), listing))
        val request = AgentReadRequest(item.path, null)
        val resource = assertIs<AgentResourceEnvelope.DashboardDefinitionVersion>(assertIs<AgentResourceOutcome.Found>(adapter.read(caller(), request)).resource)
        assertEquals(listOf("restore"), resource.availableOperations.filterIsInstance<cc.monomer.agent.api.AgentAvailableOperation.Invoke>().map { it.action })
        assertTrue(cc.monomer.agent.api.AgentWorkspaceResponseRelations.matches(request, resource))
        validator.agentResource(cc.monomer.control.publicapi.AgentWorkspaceJson.agentResourceJson(cc.monomer.agent.api.AgentRequestId("srv_test"), resource))
        coEvery { owner.readHistory(any()) } returns DashboardOwnerOutcome.Success(version.copy(restorable = false))
        assertEquals(emptyList(), assertIs<AgentResourceOutcome.Found>(adapter.read(caller(), request)).resource.availableOperations)
    }

    @Test
    fun `create and draft edit retain decoder paths and repair hints through real owner services`() = kotlinx.coroutines.test.runTest {
        val valid = authoredDefinition("""{"mode":"CONTENT"}""")
        val draftRepository = mockk<DashboardDraftRepository>()
        val receipts = mockk<DashboardCommandReceiptRepository>()
        val runs = mockk<QueryRunRepository>()
        every { draftRepository.findDraft(workspaceId, dashboardId) } returns DashboardDraft.start(
            workspaceId, dashboardId, DashboardDefinitionDecoder.decode(valid), emptyMap(), clock.instant(),
        )
        every { receipts.find(workspaceId, "actor-1", "srv_test") } returns null
        every { runs.findRecentDraftRuns(workspaceId, dashboardId, any()) } returns emptyList()
        val owner = DashboardOwnerPortAdapter(
            DashboardService(mockk(), mockk(), mockk(), receipts),
            DashboardDraftService(mockk(), mockk(), mockk(), draftRepository, runs, receipts, mockk(), mockk(), mockk()),
            mockk(), kotlinx.coroutines.Dispatchers.Unconfined,
            lifecycle = mockk(),
        )
        val adapter = adapter(owner) { _, _ -> error("invalid definition must not publish a mutation") }
        val cases = listOf(
            authoredDefinition("""{"mode":"AUTO"}""") to
                "layout.sections[0].height.mode must be CONTENT or SCALED",
            authoredDefinition("""{"mode":"SCALED","scale":1}""") to
                "layout.sections[0].height: SCALED needs a CHART, TABLE, or a SPACER directly inside a COLUMN; use CONTENT for TEXT and INPUT",
            authoredDefinition("""{"mode":"CONTENT","extra":true}""") to
                "layout.sections[0].height fields are invalid; unknown=[extra] missing=[]",
            authoredDefinition("""{"mode":"SCALED","scale":1}""", """{"type":"INPUT","variableName":"label"}""") to
                "layout.sections[0].height: SCALED needs a CHART, TABLE, or a SPACER directly inside a COLUMN; use CONTENT for TEXT and INPUT",
        )
        for ((definition, detail) in cases) {
            val created = assertIs<AgentActionOutcome.Rejected>(adapter.create(
                caller(), AgentCreateRequest(path("workspace/dashboards"), AgentCreateInput.Dashboard(definition)),
            ))
            val edited = assertIs<AgentActionOutcome.Rejected>(adapter.edit(
                caller(), AgentEditRequest(
                    path("workspace/dashboards/$dashboardId/draft"), cc.monomer.agent.api.AgentRevision(0),
                    listOf(AgentJsonPatchOperation(AgentJsonPatchOp.REPLACE, "/definition", definition)), null,
                    null,
                ),
            ))
            for (rejected in listOf(created, edited)) {
                assertEquals("INVALID_DASHBOARD_DEFINITION", rejected.problem.code)
                assertEquals(detail, rejected.problem.detail)
            }
        }
        io.mockk.verify(exactly = 0) { receipts.insert(any()) }
    }

    private fun authoredDefinition(height: String, widget: String = """{"type":"TEXT","content":"Intro"}"""): JsonObject =
        Json.parseToJsonElement("""{
          "schemaVersion":1,"meta":{"title":"Height"},"parameters":{},
          "variables":{"label":{"type":"TEXT","nullable":false,"initial":"initial"}},
          "queries":{},"widgets":{"widget":$widget},
          "layout":{"sections":[{"id":"main","height":$height,"root":{"type":"WIDGET","id":"item","widgetId":"widget"}}]}
        }""").jsonObject

    @Test
    fun `owner rejection never exposes raw warehouse failure detail`() = kotlinx.coroutines.test.runTest {
        val owner = mockk<DashboardOwnerPort>()
        val rejection = DashboardOwnerOutcome.Rejected("QUERY_PLANNING_FAILED", "private warehouse SQL and credentials")
        coEvery { owner.create(any()) } returns rejection
        coEvery { owner.readDraft(workspaceId, dashboardId) } returns DashboardOwnerOutcome.Success(draft())
        coEvery { owner.replaceDraftDocument(any()) } returns rejection
        val adapter = adapter(owner) { _, _ -> error("rejection must not publish a mutation") }
        val created = assertIs<AgentActionOutcome.Rejected>(adapter.create(
            caller(), AgentCreateRequest(path("workspace/dashboards"), AgentCreateInput.Dashboard(definition(false, false))),
        ))
        val edited = assertIs<AgentActionOutcome.Rejected>(adapter.edit(
            caller(), AgentEditRequest(
                path("workspace/dashboards/$dashboardId/draft"), cc.monomer.agent.api.AgentRevision(1),
                listOf(AgentJsonPatchOperation(AgentJsonPatchOp.REPLACE, "/definition/meta/title", JsonPrimitive("New title"))), null,
                null,
            ),
        ))
        for (rejected in listOf(created, edited)) {
            assertEquals("QUERY_PLANNING_FAILED", rejected.problem.code)
            assertEquals("The Dashboard operation was rejected.", rejected.problem.detail)
        }
    }

    @Test
    fun `owner rejection details name the owner code without echoing owner messages`() = kotlinx.coroutines.test.runTest {
        val owner = mockk<DashboardOwnerPort>()
        coEvery { owner.readDraft(workspaceId, dashboardId) } returns DashboardOwnerOutcome.Success(draft())
        val adapter = adapter(owner) { _, _ -> error("rejection must not publish a mutation") }
        val edit = AgentEditRequest(
            path("workspace/dashboards/$dashboardId/draft"), cc.monomer.agent.api.AgentRevision(1),
            listOf(AgentJsonPatchOperation(AgentJsonPatchOp.REPLACE, "/definition/meta/title", JsonPrimitive("New title"))), null,
            null,
        )
        val cases = listOf(
            Triple("IDEMPOTENCY_CONFLICT", "This request conflicts with an earlier Dashboard command recorded under the same request ID.",
                cc.monomer.agent.api.AgentWorkspaceProblemStatus.CONFLICT),
            Triple("IDEMPOTENCY_RECEIPT_INVALID", "The earlier Dashboard command recorded under this request ID cannot be replayed.",
                cc.monomer.agent.api.AgentWorkspaceProblemStatus.BAD_REQUEST),
            Triple("INVALID_DRAFT_DOCUMENT", "The Draft parameterBindings and variables must give a valid value for every declared parameter and Variable.",
                cc.monomer.agent.api.AgentWorkspaceProblemStatus.BAD_REQUEST),
            Triple("DRAFT_CONFLICT", "Another request changed the Draft at the same time. Read the Draft and retry.",
                cc.monomer.agent.api.AgentWorkspaceProblemStatus.CONFLICT),
            Triple("CONNECTOR_NOT_FOUND", "The connector was not found. It may have been deleted; choose another connector.",
                cc.monomer.agent.api.AgentWorkspaceProblemStatus.BAD_REQUEST),
        )
        for ((code, detail, status) in cases) {
            coEvery { owner.replaceDraftDocument(any()) } returns DashboardOwnerOutcome.Rejected(code, "draft variables must contain secret_value")
            val problem = assertIs<AgentActionOutcome.Rejected>(adapter.edit(caller(), edit)).problem
            assertEquals(code, problem.code)
            assertEquals(detail, problem.detail)
            assertEquals(status, problem.status)
        }
        coEvery { owner.readDashboardDraftResult(any()) } returns cc.monomer.dashboard.api.DashboardOwnerResultLookupOutcome.Rejected(
            "DASHBOARD_RESULT_UNAVAILABLE", "The Dashboard QueryRun has no result.")
        val unavailable = assertIs<AgentResourceOutcome.Rejected>(adapter.read(caller(),
            AgentReadRequest(path("workspace/dashboards/$dashboardId/draft/query-runs/$queryRunId/result"), null))).problem
        assertEquals("The Dashboard result is unavailable.", unavailable.detail)
    }

    @Test
    fun `concurrency rejections and Workspace run cap preserve retry policies`() = kotlinx.coroutines.test.runTest {
        val owner = mockk<DashboardOwnerPort>()
        val adapter = adapter(owner) { _, _ -> }
        val request = AgentCreateRequest(path("workspace/dashboards"), AgentCreateInput.Dashboard(definition(false, false)))
        for (code in listOf("SESSION_CONFLICT", "QUERY_RUN_CONFLICT", "DRAFT_CONFLICT", "EXECUTION_ADMISSION_CONFLICT", "QUERY_RUN_CANCEL_CONFLICT", "EXECUTION_BACKPRESSURED")) {
            coEvery { owner.create(any()) } returns DashboardOwnerOutcome.Rejected(code, "private diagnostic")
            val problem = assertIs<AgentActionOutcome.Rejected>(adapter.create(caller(), request)).problem
            assertEquals(code, problem.code)
            assertEquals(if (code == "EXECUTION_BACKPRESSURED") 503 else 409, problem.status.value)
            assertTrue(problem.retryable)
            assertTrue(!problem.detail.contains("private diagnostic"))
        }
    }

    @Test
    fun `secondary admission preserves expired credential and lost membership outcomes`() = kotlinx.coroutines.test.runTest {
        val owner = mockk<DashboardOwnerPort>()
        val admission = mockk<ControlAdmissionFacade>()
        val adapter = adapter(owner, admission) { _, _ -> }
        val create = AgentCreateRequest(path("workspace/dashboards"), AgentCreateInput.Dashboard(definition(false, false)))
        for (reason in listOf(cc.monomer.identity.api.IdentityAdmissionDenial.CREDENTIAL_EXPIRED, cc.monomer.identity.api.IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE)) {
            coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Denied(
                cc.monomer.control.ControlAdmissionStage.IDENTITY, cc.monomer.control.ControlAdmissionDenial.Identity(reason))
            val outcome = adapter.create(caller(), create)
            if (reason == cc.monomer.identity.api.IdentityAdmissionDenial.CREDENTIAL_EXPIRED) {
                assertEquals(401, assertIs<AgentActionOutcome.Rejected>(outcome).problem.status.value)
            } else assertIs<AgentActionOutcome.NotFound>(outcome)
        }
        coVerify(exactly = 0) { owner.create(any()) }
    }

    @Test
    fun `input and Semantic rejection details retain safe owner repair facts`() = kotlinx.coroutines.test.runTest {
        val owner = mockk<DashboardOwnerPort>()
        val adapter = adapter(owner) { _, _ -> }
        val request = AgentCreateRequest(path("workspace/dashboards"), AgentCreateInput.Dashboard(definition(false, false)))
        for ((code, detail) in listOf(
            "INVALID_DRAFT_VALUE" to "variables.region must be a string",
            "INVALID_VARIABLE_VALUE" to "variables.region is required",
            "INVALID_DRAFT_PARAMETERS" to "parameterBindings.start is required",
            "INVALID_SESSION_PARAMETERS" to "parameterBindings.start must be a date",
            "INVALID_SEMANTIC_QUERY" to "CATALOG_RELATION_NOT_FOUND",
            "UNSUPPORTED_SEMANTIC_QUERY" to "TIME_SPINE",
        )) {
            coEvery { owner.create(any()) } returns DashboardOwnerOutcome.Rejected(code, detail)
            val problem = assertIs<AgentActionOutcome.Rejected>(adapter.create(caller(), request)).problem
            assertEquals(code, problem.code)
            assertEquals(detail, problem.detail)
            assertEquals(400, problem.status.value)
            assertEquals(false, problem.retryable)
        }
    }

    @Test
    fun `unconfirmed Dashboard create requires reading before resending and owner arguments propagate`() = kotlinx.coroutines.test.runTest {
        val owner = mockk<DashboardOwnerPort>()
        val adapter = adapter(owner) { _, _ -> }
        val request = AgentCreateRequest(path("workspace/dashboards"), AgentCreateInput.Dashboard(definition(false, false)))
        coEvery { owner.create(any()) } throws IllegalStateException("private diagnostic")
        val problem = assertIs<AgentActionOutcome.Rejected>(adapter.create(caller(), request)).problem
        assertEquals(AgentOwnerProblems.dashboardCreateUnconfirmed, problem)
        assertEquals(false, problem.retryable)
        coEvery { owner.create(any()) } throws java.io.IOException("private checked diagnostic")
        val checkedProblem = assertIs<AgentActionOutcome.Rejected>(adapter.create(caller(), request)).problem
        assertEquals(503, checkedProblem.status.value)
        assertEquals("OWNER_UNAVAILABLE", checkedProblem.code)
        assertEquals(false, checkedProblem.retryable)
        assertEquals(AgentOwnerProblems.dashboardCreateUnconfirmed.detail, checkedProblem.detail)
        assertTrue(!checkedProblem.toString().contains("private checked diagnostic"))
        coEvery { owner.create(any()) } throws IllegalArgumentException("private diagnostic")
        kotlin.test.assertFailsWith<IllegalArgumentException> { adapter.create(caller(), request) }
    }

    @Test
    fun `Agent session open and read preserve out of control typed values through owner boundary`() = kotlinx.coroutines.test.runTest {
        val owner = mockk<DashboardOwnerPort>()
        val bindings = mapOf("country" to JsonPrimitive("CN"), "amount" to JsonPrimitive("150"))
        val definition = Json.parseToJsonElement("""{
            "parameters":{
                "country":{"type":"TEXT","nullable":false,"control":{"options":[{"value":"KR"},{"value":"JP"}]}},
                "amount":{"type":"NUMBER","nullable":false,"control":{"min":"0","max":"100"}}
            },"queries":{},"widgets":{}
        }""").jsonObject
        val snapshot = session().copy(definition = definition, parameterBindings = bindings)
        val command = slot<cc.monomer.dashboard.api.OpenDashboardSessionCommand>()
        coEvery { owner.openSession(capture(command)) } returns DashboardOwnerOutcome.Success(snapshot)
        coEvery { owner.readSession(workspaceId, dashboardId, sessionId) } returns DashboardOwnerOutcome.Success(snapshot)
        val adapter = adapter(owner)

        val opened = assertIs<AgentActionOutcome.Completed>(adapter.invoke(
            caller(), AgentInvokeRequest(path("workspace/dashboards/$dashboardId/sessions"), "open", AgentInvokeInput.DashboardSessionOpen(bindings)),
        ))
        assertEquals(AgentActionStatus.ACCEPTED, opened.action.status)
        assertEquals(bindings, command.captured.parameterBindings)
        assertEquals(ExpectedOAuthResource.AGENT_REST, command.captured.metadata.expectedOAuthResource)
        val restored = assertIs<AgentResourceEnvelope.DashboardSession>(assertIs<AgentResourceOutcome.Found>(
            adapter.read(caller(), AgentReadRequest(path("workspace/dashboards/$dashboardId/sessions/$sessionId"), null)),
        ).resource)
        assertEquals(bindings, restored.body.parameterBindings)
        assertEquals(snapshot.initialVariables, restored.body.initialVariables)
        coVerify(exactly = 1) { owner.openSession(any()) }
    }

    @Test
    fun `session listing preserves nullable last query run creation time from owner`() = kotlinx.coroutines.test.runTest {
        val owner = mockk<DashboardOwnerPort>()
        for (lastQueryRunAt in listOf(null, clock.instant().minusSeconds(30))) {
            coEvery { owner.listSessions(any()) } returns DashboardOwnerOutcome.Success(
                cc.monomer.dashboard.api.DashboardOwnerSessionPage(
                    listOf(cc.monomer.dashboard.api.DashboardOwnerSessionSummary(
                        sessionId = sessionId,
                        dashboardId = dashboardId,
                        parameterBindings = emptyMap(),
                        runningQueryRuns = emptyList(),
                        createdAt = clock.instant(),
                        updatedAt = clock.instant(),
                        lastQueryRunAt = lastQueryRunAt,
                    )),
                    null,
                ),
            )
            val listed = assertIs<AgentListingOutcome.Found>(adapter(owner).list(
                caller(), AgentListRequest(path("workspace/dashboards/$dashboardId/sessions"), null, 20),
            )).listing
            assertEquals(lastQueryRunAt, assertIs<cc.monomer.agent.api.AgentResourceListingItem.DashboardSession>(listed.items.single()).lastQueryRunAt)
        }
    }

    @Test
    fun `Session open definition conflict keeps Agent 409 and its owner code`() = kotlinx.coroutines.test.runTest {
        val owner = mockk<DashboardOwnerPort>()
        coEvery { owner.openSession(any()) } returns DashboardOwnerOutcome.Rejected("SESSION_CONFLICT", null)
        val problem = assertIs<AgentActionOutcome.Rejected>(adapter(owner).invoke(caller(),
            AgentInvokeRequest(path("workspace/dashboards/$dashboardId/sessions"), "open", AgentInvokeInput.DashboardSessionOpen(emptyMap())))).problem
        assertEquals("SESSION_CONFLICT", problem.code)
        assertEquals(cc.monomer.agent.api.AgentWorkspaceProblemStatus.CONFLICT, problem.status)
        assertEquals(true, problem.retryable)
        assertEquals(null, problem.retryAfterSeconds)
    }

    @Test
    fun `Session list cursor rejects another Dashboard with Agent input status`() = kotlinx.coroutines.test.runTest {
        val owner = mockk<DashboardOwnerPort>()
        coEvery { owner.listSessions(any()) } returns DashboardOwnerOutcome.Success(
            cc.monomer.dashboard.api.DashboardOwnerSessionPage(emptyList(),
                cc.monomer.dashboard.api.DashboardOwnerSessionPageCursor(dashboardId, clock.instant(), sessionId)),
        )
        val adapter = adapter(owner)
        val first = assertIs<AgentListingOutcome.Found>(adapter.list(caller(),
            AgentListRequest(path("workspace/dashboards/$dashboardId/sessions"), null, 1))).listing
        val rejected = assertIs<AgentListingOutcome.Rejected>(adapter.list(caller(),
            AgentListRequest(path("workspace/dashboards/${DashboardId.generate()}/sessions"), first.nextCursor, 1)))
        assertEquals("CURSOR_INVALID", rejected.problem.code)
        assertEquals(cc.monomer.agent.api.AgentWorkspaceProblemStatus.BAD_REQUEST, rejected.problem.status)
        coVerify(exactly = 1) { owner.listSessions(any()) }
    }

    @Test
    fun `dashboard listing emits a path-bound keyset cursor`() = kotlinx.coroutines.test.runTest {
        val owner = mockk<DashboardOwnerPort>()
        coEvery { owner.listDashboards(workspaceId, null, 1) } returns DashboardOwnerOutcome.Success(
            DashboardOwnerDashboardPage(
                listOf(DashboardOwnerDashboardListItem(dashboardId, workspaceId, "Orders", "Daily orders", clock.instant())),
                DashboardOwnerDashboardPageCursor(clock.instant(), dashboardId),
            ),
        )
        val listed = assertIs<AgentListingOutcome.Found>(
            adapter(owner).list(caller(), AgentListRequest(path("workspace/dashboards"), null, 1)),
        ).listing

        assertEquals("Orders", assertIs<cc.monomer.agent.api.AgentResourceListingItem.Dashboard>(listed.items.single()).title)
        val crossCollection = adapter(owner).list(
            caller(),
            AgentListRequest(path("workspace/dashboards/${dashboardId.value}/sessions"), listed.nextCursor, 1),
        )
        assertEquals("CURSOR_INVALID", assertIs<AgentListingOutcome.Rejected>(crossCollection).problem.code)
    }

    @Test
    fun `session resolve and refresh return transient complete target maps through the owner boundary`() = kotlinx.coroutines.test.runTest {
        val owner = mockk<DashboardOwnerPort>()
        val variables = session().initialVariables
        val resolve = slot<cc.monomer.dashboard.api.ResolveDashboardSessionCommand>()
        val refresh = slot<cc.monomer.dashboard.api.RefreshDashboardSessionCommand>()
        val resolution = cc.monomer.dashboard.api.DashboardOwnerSessionResolution(variables, mapOf("orders" to queryRunId))
        coEvery { owner.resolveSession(capture(resolve)) } returns DashboardOwnerOutcome.Success(resolution)
        coEvery { owner.refreshSession(capture(refresh)) } returns DashboardOwnerOutcome.Success(resolution)
        val sessionPath = path("workspace/dashboards/${dashboardId.value}/sessions/${sessionId.value}")
        val adapter = adapter(owner)
        listOf(
            AgentInvokeRequest(sessionPath, "resolve", AgentInvokeInput.DashboardSessionResolve(variables)),
            AgentInvokeRequest(sessionPath, "refresh", AgentInvokeInput.DashboardSessionRefresh(variables, setOf("orders"))),
        ).forEach { request ->
            val result = assertIs<AgentActionResult.DashboardSessionResolution>(
                assertIs<AgentActionOutcome.Completed>(adapter.invoke(caller(), request)).action,
            )
            assertEquals(variables, result.variables)
            assertEquals(path("${sessionPath.value}/query-runs/${queryRunId.value}"), result.queryRuns.getValue("orders"))
            assertEquals(AgentActionStatus.ACCEPTED, result.status)
            assertEquals(sessionPath, result.result.path)
        }
        assertEquals(variables, resolve.captured.variables)
        assertEquals(variables, refresh.captured.variables)
        assertEquals(setOf("orders"), refresh.captured.queryNames)
        assertEquals(ExpectedOAuthResource.AGENT_REST, resolve.captured.metadata.expectedOAuthResource)
        coVerify(exactly = 1) { owner.resolveSession(any()) }
        coVerify(exactly = 1) { owner.refreshSession(any()) }
        coVerify(exactly = 0) { owner.readSession(workspaceId, dashboardId, sessionId) }
    }

    @Test
    fun `dashboard read maps the owner snapshot to the canonical resource envelope`() = kotlinx.coroutines.test.runTest {
        val owner = RecordingDashboardOwner(
            dashboard = DashboardOwnerOutcome.Success(dashboard()),
            draft = DashboardOwnerOutcome.NotFound,
        )
        val adapter = adapter(owner)

        val outcome = adapter.read(caller(), AgentReadRequest(path("workspace/dashboards/01942b04-6e00-7000-8000-000000000010"), null))

        val resource = assertIs<AgentResourceOutcome.Found>(outcome).resource
        assertEquals(AgentResourceKind.DASHBOARD, resource.kind)
        assertEquals("workspace/dashboards/01942b04-6e00-7000-8000-000000000010", resource.path.value)
    }

    @Test
    fun `dashboard and Draft reads carry who changed them, the surface, and the agent app as the Web shows`() = kotlinx.coroutines.test.runTest {
        val mina = FixedPrincipalDisplayNames.MINA.toString()
        val claude = cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE
        val unknownApp = cc.monomer.identity.api.AgentClientId("https://retired.example/client")
        val web = cc.monomer.identity.api.CredentialSurface.WEB
        val agent = cc.monomer.identity.api.CredentialSurface.AGENT
        data class Row(val actor: String?, val surface: cc.monomer.identity.api.CredentialSurface?, val client: cc.monomer.identity.api.AgentClientId?)
        val rows = listOf(
            Row(mina, web, null),
            Row(mina, agent, claude),
            Row(mina, agent, null),
            Row("01900000-0000-7000-8000-0000000000aa", agent, unknownApp),
            Row(null, null, null),
        )
        for (row in rows) {
            val owner = mockk<DashboardOwnerPort>()
            coEvery { owner.read(workspaceId, dashboardId) } returns DashboardOwnerOutcome.Success(dashboard(row.actor, row.surface, row.client))
            coEvery { owner.readDraft(workspaceId, dashboardId) } returns DashboardOwnerOutcome.Success(draft(row.actor, row.surface, row.client))
            val adapter = adapter(owner) { _, _ -> error("read must not publish a mutation") }
            val current = assertIs<cc.monomer.agent.api.AgentDashboardResourceBody>(
                assertIs<AgentResourceOutcome.Found>(adapter.read(caller(), AgentReadRequest(path("workspace/dashboards/${dashboardId.value}"), null))).resource.body,
            )
            val draft = assertIs<cc.monomer.agent.api.AgentDashboardDraftResourceBody>(
                assertIs<AgentResourceOutcome.Found>(adapter.read(caller(), AgentReadRequest(path("workspace/dashboards/${dashboardId.value}/draft"), null))).resource.body,
            )
            val expectedActor = row.actor?.let { cc.monomer.agent.api.AgentActor(it, if (it == mina) "Mina Park" else null) }
            val expectedApp = if (row.client == claude) cc.monomer.agent.api.AgentApp(claude.value, "Claude", "claude.ai") else null
            assertEquals(expectedActor, current.updatedBy, "$row")
            assertEquals(row.surface, current.credentialSurface, "$row")
            assertEquals(expectedApp, current.agentApp, "$row")
            assertEquals(expectedActor, draft.lastEditedBy, "$row")
            assertEquals(row.actor?.let { clock.instant() }, draft.lastEditedAt, "$row")
            assertEquals(row.surface, draft.lastEditSurface, "$row")
            assertEquals(expectedApp, draft.lastEditAgentApp, "$row")
        }
    }

    @Test
    fun `Draft query run read carries the surface and agent app the Web shows`() = kotlinx.coroutines.test.runTest {
        val claude = cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE
        val queryRunId = QueryRunId(Uuid.parse("01942b04-6e00-7000-8000-000000000030"))
        for ((surface, client) in listOf(
            cc.monomer.identity.api.CredentialSurface.WEB to null,
            cc.monomer.identity.api.CredentialSurface.AGENT to claude,
            cc.monomer.identity.api.CredentialSurface.AGENT to null,
            null to null,
        )) {
            val owner = mockk<DashboardOwnerPort>()
            coEvery { owner.readDashboardDraftRun(ReadDashboardDraftRunCommand(workspaceId, dashboardId, queryRunId)) } returns DashboardOwnerOutcome.Success(
                DashboardOwnerQueryRun(
                    queryRunId, dashboardId, null, "orders", "SEMANTIC", ConnectorId(Uuid.parse("01942b04-6e00-7000-8000-000000000020")), "RUNNING", false,
                    null, null, emptyList(), clock.instant(), clock.instant(), FixedPrincipalDisplayNames.MINA.toString(), surface, client,
                    outputColumnDisplays = null,
                    reportingTimeZone = null,
                ),
            )
            val body = assertIs<cc.monomer.agent.api.AgentDashboardQueryRunBody>(
                assertIs<AgentResourceOutcome.Found>(
                    adapter(owner) { _, _ -> error("read must not publish a mutation") }
                        .read(caller(), AgentReadRequest(path("workspace/dashboards/${dashboardId.value}/draft/query-runs/${queryRunId.value}"), null)),
                ).resource.body,
            )
            assertEquals(surface, body.credentialSurface)
            assertEquals(if (client == claude) cc.monomer.agent.api.AgentApp(claude.value, "Claude", "claude.ai") else null, body.agentApp)
        }
    }

    @Test
    fun `current dashboard discovers existing Draft only for Explorer without starting it`() = kotlinx.coroutines.test.runTest {
        for (canEdit in listOf(true)) {
            for (draftExists in listOf(false, true)) {
                val owner = mockk<DashboardOwnerPort>()
                coEvery { owner.read(workspaceId, dashboardId) } returns DashboardOwnerOutcome.Success(dashboard())
                coEvery { owner.readDraft(workspaceId, dashboardId) } returns if (draftExists) {
                    DashboardOwnerOutcome.Success(draft())
                } else {
                    DashboardOwnerOutcome.NotFound
                }
                val admission = mockk<ControlAdmissionFacade>()
                coEvery { admission.authorizeWorkspaceRole(any()) } returns if (canEdit) {
                    ControlAdmissionDecision.Admitted(mockk())
                } else {
                    ControlAdmissionDecision.Denied(cc.monomer.control.ControlAdmissionStage.IDENTITY, cc.monomer.control.ControlAdmissionDenial.Identity(cc.monomer.identity.api.IdentityAdmissionDenial.PERMISSION_UNAVAILABLE))
                }
                val adapter = adapter(owner, admission) { _, _ -> error("read must not publish a mutation") }
                val dashboardPath = "workspace/dashboards/${dashboardId.value}"
                val resource = assertIs<AgentResourceOutcome.Found>(
                    adapter.read(caller(), AgentReadRequest(path(dashboardPath), null)),
                ).resource
                val operations = resource.availableOperations.map { Triple(it.operation, it.path.value, (it as? cc.monomer.agent.api.AgentAvailableOperation.Invoke)?.action) }.toSet()
                val expected = buildSet {
                    if (canEdit && draftExists) add(Triple(AgentWorkspaceOperation.READ, "$dashboardPath/draft", null))
                    if (canEdit) add(Triple(AgentWorkspaceOperation.INVOKE, "$dashboardPath/draft", "start"))
                    add(Triple(AgentWorkspaceOperation.READ, "$dashboardPath/schedules", null))
                    add(Triple(AgentWorkspaceOperation.LIST, "$dashboardPath/occurrences", null))
                    add(Triple(AgentWorkspaceOperation.LIST, "$dashboardPath/history", null))
                    add(Triple(AgentWorkspaceOperation.INVOKE, dashboardPath, "delete"))
                    add(Triple(AgentWorkspaceOperation.LIST, "$dashboardPath/sessions", null))
                    add(Triple(AgentWorkspaceOperation.INVOKE, "$dashboardPath/sessions", "open"))
                }
                assertEquals(expected, operations, "canEdit=$canEdit draftExists=$draftExists")
                coVerify(exactly = 1) { owner.read(workspaceId, dashboardId) }
                coVerify(exactly = if (canEdit) 1 else 0) { owner.readDraft(workspaceId, dashboardId) }
                confirmVerified(owner)
            }
        }
    }

    @Test
    fun `Viewer cannot list direct Dashboards`() = kotlinx.coroutines.test.runTest {
        val owner = mockk<DashboardOwnerPort>()
        coEvery { owner.listDashboards(workspaceId, null, 20) } returns DashboardOwnerOutcome.Success(
            DashboardOwnerDashboardPage(emptyList(), null),
        )
        val admission = mockk<ControlAdmissionFacade>()
        coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Denied(cc.monomer.control.ControlAdmissionStage.IDENTITY, cc.monomer.control.ControlAdmissionDenial.Identity(cc.monomer.identity.api.IdentityAdmissionDenial.PERMISSION_UNAVAILABLE))
        assertIs<AgentListingOutcome.Forbidden>(
            adapter(owner, admission) { _, _ -> }.list(caller(), AgentListRequest(path("workspace/dashboards"), null, 20)),
        )
        coVerify(exactly = 0) { owner.listDashboards(workspaceId, any(), any()) }
    }

    @Test
    fun `Draft read advertises refresh for any defined query without executing it`() = kotlinx.coroutines.test.runTest {
        for ((hasQueries, referencedByWidget) in listOf(false to false, true to true)) {
            val owner = mockk<DashboardOwnerPort>()
            val snapshot = draft().copy(definition = definition(hasQueries, referencedByWidget))
            coEvery { owner.readDraft(workspaceId, dashboardId) } returns DashboardOwnerOutcome.Success(snapshot)
            val draftPath = path("workspace/dashboards/${dashboardId.value}/draft")
            val resource = assertIs<AgentResourceOutcome.Found>(
                adapter(owner) { _, _ -> error("read must not publish a mutation") }.read(caller(), AgentReadRequest(draftPath, null)),
            ).resource

            assertEquals(
                buildSet {
                    add(AgentWorkspaceOperation.READ to null)
                    add(AgentWorkspaceOperation.EDIT to null)
                    add(AgentWorkspaceOperation.INVOKE to "apply")
                    add(AgentWorkspaceOperation.INVOKE to "discard")
                    if (hasQueries) add(AgentWorkspaceOperation.INVOKE to "refresh")
                    add(AgentWorkspaceOperation.LIST to null)
                },
                resource.availableOperations.map { it.operation to (it as? cc.monomer.agent.api.AgentAvailableOperation.Invoke)?.action }.toSet(),
                "hasQueries=$hasQueries referencedByWidget=$referencedByWidget",
            )
            assertEquals(
                if (hasQueries) "DASHBOARD_QUERY_REFRESH" else null,
                resource.availableOperations.filterIsInstance<cc.monomer.agent.api.AgentAvailableOperation.Invoke>().singleOrNull { it.action == "refresh" }?.inputKind,
            )
            coVerify(exactly = 1) { owner.readDraft(workspaceId, dashboardId) }
            confirmVerified(owner)
        }
    }

    @Test
    fun `Draft status is one owner snapshot whose revision and ready results equal the Draft read`() = kotlinx.coroutines.test.runTest {
        val owner = mockk<DashboardOwnerPort>()
        val targetRun = QueryRunId(Uuid.parse("01942b04-6e00-7000-8000-000000000013"))
        val ready = cc.monomer.dashboard.api.DashboardOwnerReadyResult(3, mapOf("region" to JsonPrimitive("US")), mapOf("city" to JsonNull), mapOf("orders" to queryRunId))
        coEvery { owner.readDraft(workspaceId, dashboardId) } returns DashboardOwnerOutcome.Success(
            draft().copy(definition = definition(hasQueries = true, referencedByWidget = true), authoringRevision = 4,
                targetQueryRuns = mapOf("orders" to targetRun), readyResults = mapOf("chart" to ready)),
        )
        val adapter = adapter(owner) { _, _ -> error("read must not publish a mutation") }
        val draftPath = path("workspace/dashboards/${dashboardId.value}/draft")
        val statusPath = path("workspace/dashboards/${dashboardId.value}/draft/status")
        val draftResource = assertIs<AgentResourceEnvelope.DashboardDraft>(
            assertIs<AgentResourceOutcome.Found>(adapter.read(caller(), AgentReadRequest(draftPath, null))).resource,
        )
        assertTrue(cc.monomer.agent.api.AgentAvailableOperation.Read(statusPath) in draftResource.availableOperations)
        val validator = cc.monomer.control.publicapi.PublicControlContractValidator()
        for (state in listOf("RUNNING", "SUCCEEDED", "FAILED", "CANCELLED")) {
            coEvery { owner.readDraftStatus(workspaceId, dashboardId) } returns DashboardOwnerOutcome.Success(cc.monomer.dashboard.api.DashboardOwnerDraftStatus(
                dashboardId, 4, mapOf("orders" to cc.monomer.dashboard.api.DashboardOwnerDraftTargetQueryRun(targetRun, state)), mapOf("chart" to ready),
            ))
            val request = AgentReadRequest(statusPath, null)
            val status = assertIs<AgentResourceEnvelope.DashboardDraftStatus>(assertIs<AgentResourceOutcome.Found>(adapter.read(caller(), request)).resource)
            assertEquals(draftResource.revision, status.body.draftRevision)
            assertEquals(draftResource.body.readyResults, status.body.readyResults)
            assertEquals(draftResource.body.targetQueryRuns, status.body.targetQueryRuns.mapValues { it.value.path })
            assertEquals(cc.monomer.agent.api.AgentRunState.valueOf(state), status.body.targetQueryRuns.getValue("orders").state)
            assertEquals(null, status.revision)
            assertEquals(emptyList(), status.availableOperations)
            assertTrue(cc.monomer.agent.api.AgentWorkspaceResponseRelations.matches(request, status))
            val json = validator.agentResource(cc.monomer.control.publicapi.AgentWorkspaceJson.agentResourceJson(cc.monomer.agent.api.AgentRequestId("srv_test"), status))
            assertEquals(setOf("requestId", "kind", "path", "body"), json.keys)
            assertEquals(Json.parseToJsonElement("""{"draftRevision":"4","targetQueryRuns":{"orders":{"path":"${draftPath.value}/query-runs/$targetRun","state":"$state"}},
                "readyResults":{"chart":{"authoringRevision":"3","parameterBindings":{"region":"US"},"variables":{"city":null},"queryRuns":{"orders":"${draftPath.value}/query-runs/$queryRunId"}}}}"""),
                Json.parseToJsonElement(com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(json["body"])))
        }
        coVerify(exactly = 4) { owner.readDraftStatus(workspaceId, dashboardId) }
        coVerify(exactly = 1) { owner.readDraft(workspaceId, dashboardId) }
        confirmVerified(owner)

        coEvery { owner.readDraftStatus(workspaceId, dashboardId) } returns DashboardOwnerOutcome.NotFound
        assertIs<AgentResourceOutcome.NotFound>(adapter.read(caller(), AgentReadRequest(statusPath, null)))
        val denied = mockk<ControlAdmissionFacade>()
        coEvery { denied.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Denied(cc.monomer.control.ControlAdmissionStage.IDENTITY, cc.monomer.control.ControlAdmissionDenial.Identity(cc.monomer.identity.api.IdentityAdmissionDenial.PERMISSION_UNAVAILABLE))
        val forbiddenOwner = mockk<DashboardOwnerPort>()
        for (readPath in listOf(draftPath, statusPath)) {
            assertIs<AgentResourceOutcome.Forbidden>(adapter(forbiddenOwner, denied) { _, _ -> }.read(caller(), AgentReadRequest(readPath, null)))
        }
        confirmVerified(forbiddenOwner)
    }

    @Test
    fun `Session read always advertises resolve and refresh only for defined queries`() = kotlinx.coroutines.test.runTest {
        for ((hasQueries, referencedByWidget) in listOf(false to false, true to true)) {
            for (hasVariables in listOf(false, true)) {
                val owner = mockk<DashboardOwnerPort>()
                val snapshot = session().copy(
                    definition = definition(hasQueries, referencedByWidget),
                    initialVariables = if (hasVariables) session().initialVariables else emptyMap(),
                )
                coEvery { owner.readSession(workspaceId, dashboardId, sessionId) } returns DashboardOwnerOutcome.Success(snapshot)
                val sessionPath = path("workspace/dashboards/${dashboardId.value}/sessions/${sessionId.value}")
                val resource = assertIs<AgentResourceOutcome.Found>(
                    adapter(owner) { _, _ -> error("read must not publish a mutation") }.read(caller(), AgentReadRequest(sessionPath, null)),
                ).resource

                assertEquals(
                    buildSet {
                        add(AgentWorkspaceOperation.INVOKE to "resolve")
                        if (hasQueries) add(AgentWorkspaceOperation.INVOKE to "refresh")
                        add(AgentWorkspaceOperation.LIST to null)
                    },
                    resource.availableOperations.map { it.operation to (it as? cc.monomer.agent.api.AgentAvailableOperation.Invoke)?.action }.toSet(),
                    "hasQueries=$hasQueries referencedByWidget=$referencedByWidget hasVariables=$hasVariables",
                )
                coVerify(exactly = 1) { owner.readSession(workspaceId, dashboardId, sessionId) }
                confirmVerified(owner)
            }
        }
    }

    @Test
    fun `dashboard create uses the new create operation and server request identity`() = kotlinx.coroutines.test.runTest {
        val owner = RecordingDashboardOwner(dashboard = DashboardOwnerOutcome.Success(dashboard()))
        val adapter = adapter(owner)
        val request = AgentCreateRequest(
            path("workspace/dashboards"),
            AgentCreateInput.Dashboard(Json.parseToJsonElement("{\"meta\":{\"title\":\"Orders\"}}") as JsonObject),
        )

        val outcome = adapter.create(caller(), request)

        val action = assertIs<AgentActionOutcome.Completed>(outcome).action
        assertEquals(AgentActionStatus.COMPLETED, action.status)
        assertEquals("CREATE", action.operation.name)
        assertEquals("workspace/dashboards", action.path.value)
        assertEquals("srv_test", owner.createdMetadata?.idempotencyKey)
    }

    @Test
    fun `dashboard draft edit applies an ordered patch and reports the owner path`() = kotlinx.coroutines.test.runTest {
        val owner = RecordingDashboardOwner(draft = DashboardOwnerOutcome.Success(draft()))
        val hub = mockk<cc.monomer.control.dashboard.DashboardDraftEventHub>(relaxed = true)
        fun <T : Any> provider(value: T): org.springframework.beans.factory.ObjectProvider<T> = mockk {
            every { ifAvailable } returns value
        }
        val adapter = AgentWorkspaceApplicationComposition().agentDashboardOwnerPort(
            provider<DashboardOwnerPort>(owner), provider(mockk<cc.monomer.control.dashboard.DashboardScheduleObservation>()), provider(Clock.systemUTC()), provider(hub),
            provider(mockk<DashboardResultReadPort>()), admittedAdmission(), fixedAgentProvenanceNames(),
        )
        assertEquals(false, org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
        val request = AgentEditRequest(
            path("workspace/dashboards/01942b04-6e00-7000-8000-000000000010/draft"),
            revision = cc.monomer.agent.api.AgentRevision(1),
            patch = listOf(
                AgentJsonPatchOperation(AgentJsonPatchOp.REPLACE, "/definition/meta/title", Json.parseToJsonElement("\"Revenue\"")),
            ),
            commandId = null,
            aggregateMetrics = null,
        )

        val outcome = adapter.edit(caller(), request)

        assertIs<AgentActionOutcome.Completed>(outcome)
        assertEquals(1, owner.replaceCount)
        io.mockk.verify(exactly = 1) { hub.emit(dashboardId, "DRAFT_CHANGED") }
        assertEquals("Revenue", owner.lastReplace?.definition?.get("meta")?.jsonObject?.get("title")?.jsonPrimitive?.content)
    }

    @Test
    fun `discard resets through the owner as the agent and reports the Draft like other Draft mutations`() = kotlinx.coroutines.test.runTest {
        val owner = mockk<DashboardOwnerPort>()
        val command = slot<cc.monomer.dashboard.api.DiscardDraftCommand>()
        coEvery { owner.discardDraft(capture(command)) } returns DashboardOwnerOutcome.Success(draft())
        val published = mutableListOf<Pair<DashboardId, String>>()
        val draftPath = path("workspace/dashboards/${dashboardId.value}/draft")
        val outcome = assertIs<AgentActionOutcome.Completed>(adapter(owner, admittedAdmission()) { id, reason -> published += id to reason }.invoke(
            caller(), AgentInvokeRequest(draftPath, "discard", AgentInvokeInput.DashboardDraftDiscard(4)),
        ))
        val action = assertIs<AgentActionResult.Invoke>(outcome.action)
        assertEquals(AgentActionStatus.COMPLETED, action.status)
        assertEquals(AgentResourceKind.DASHBOARD_DRAFT, action.result.kind)
        assertEquals(draftPath, action.result.path)
        assertEquals(4L, command.captured.expectedRevision)
        assertEquals(cc.monomer.identity.api.CredentialSurface.AGENT, command.captured.metadata.credentialSurface)
        assertEquals("actor-1", command.captured.metadata.actorId)
        assertEquals(listOf(dashboardId to "DRAFT_DISCARDED"), published)

        coEvery { owner.discardDraft(any()) } returns DashboardOwnerOutcome.Conflict(5)
        val conflict = assertIs<AgentActionOutcome.Rejected>(adapter(owner).invoke(
            caller(), AgentInvokeRequest(draftPath, "discard", AgentInvokeInput.DashboardDraftDiscard(4)),
        ))
        assertEquals("REVISION_CONFLICT", conflict.problem.code)
        assertEquals(5L, conflict.problem.currentRevision?.value)

        coEvery { owner.discardDraft(any()) } returns DashboardOwnerOutcome.Rejected("DRAFT_DISCARD_INPUT_MISSING", "Restore input region in the Draft, then discard again.")
        val missing = assertIs<AgentActionOutcome.Rejected>(adapter(owner).invoke(
            caller(), AgentInvokeRequest(draftPath, "discard", AgentInvokeInput.DashboardDraftDiscard(4)),
        ))
        assertEquals("Restore input region in the Draft, then discard again.", missing.problem.detail)

        val denied = mockk<ControlAdmissionFacade>()
        coEvery { denied.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Denied(cc.monomer.control.ControlAdmissionStage.IDENTITY, cc.monomer.control.ControlAdmissionDenial.Identity(cc.monomer.identity.api.IdentityAdmissionDenial.PERMISSION_UNAVAILABLE))
        assertIs<AgentActionOutcome.Forbidden>(adapter(owner, denied) { _, _ -> }.invoke(
            caller(), AgentInvokeRequest(draftPath, "discard", AgentInvokeInput.DashboardDraftDiscard(4)),
        ))
    }

    private fun adapter(owner: DashboardOwnerPort): AgentDashboardOwnerAdapter =
        adapter(owner, admittedAdmission()) { _, _ -> }

    @Test
    fun `draft and session result reads expose canonical columns and preserve exact cells`() = kotlinx.coroutines.test.runTest {
        for (viewerSessionId in listOf(null, sessionId)) {
            val fixture = ResultReadFixture(viewerSessionId)
            val payload = resultPayload()
            coEvery { fixture.resultReadPort.read(fixture.lookup.resultManifest) } returns payload

            val resource = assertIs<AgentResourceEnvelope.DashboardQueryResult>(
                assertIs<AgentResourceOutcome.Found>(
                    fixture.adapter.read(caller(), AgentReadRequest(fixture.resultPath, null)),
                ).resource,
            )

            assertEquals(fixture.resultPath, resource.path)
            assertEquals(listOf("dimension.orders.status", "metric.status"), resource.body.columns.map { it.name })
            assertEquals(listOf("TEXT", "DECIMAL"), resource.body.columns.map { it.logicalType })
            assertEquals(listOf(false, true), resource.body.columns.map { it.nullable })
            assertEquals(listOf(false, true), cc.monomer.control.publicapi.PublicControlContractValidator().agentResource(cc.monomer.control.publicapi.AgentWorkspaceJson.agentResourceJson(
                cc.monomer.agent.api.AgentRequestId("srv_test"), resource)).let { json -> ((json["body"] as Map<*, *>)["columns"] as List<*>).map { (it as Map<*, *>)["nullable"] } })
            assertEquals(payload.rows, resource.body.rows)
            assertEquals(JsonPrimitive("12345678901234567890.12345678901234567"), resource.body.rows[0][1])
            assertEquals(JsonNull, resource.body.rows[1][1])
            assertEquals("3", resource.body.totalRowCount)
            assertEquals(2, resource.body.returnedRowCount)
            assertEquals(true, resource.body.truncated)
            coVerify(exactly = 1) { fixture.resultReadPort.read(fixture.lookup.resultManifest) }
            coVerify(exactly = 1) { fixture.admission.authorizeWorkspaceRole(any()) }
        }
    }

    @Test
    fun `draft and session result reads reject mismatched artifact names with result unavailable 503`() = kotlinx.coroutines.test.runTest {
        for (viewerSessionId in listOf(null, sessionId)) {
            val fixture = ResultReadFixture(viewerSessionId)
            val payload = resultPayload()
            coEvery { fixture.resultReadPort.read(fixture.lookup.resultManifest) } returns payload.copy(
                columns = payload.columns.reversed(),
            )

            val problem = assertIs<AgentResourceOutcome.Rejected>(
                fixture.adapter.read(caller(), AgentReadRequest(fixture.resultPath, null)),
            ).problem

            assertEquals("RESULT_UNAVAILABLE", problem.code)
            assertEquals(503, problem.status.value)
            coVerify(exactly = 1) { fixture.resultReadPort.read(fixture.lookup.resultManifest) }
            coVerify(exactly = 1) { fixture.admission.authorizeWorkspaceRole(any()) }
        }
    }

    @Test
    fun `draft and session result reads denied by the current role never read the artifact`() = kotlinx.coroutines.test.runTest {
        for (viewerSessionId in listOf(null, sessionId)) {
            val fixture = ResultReadFixture(viewerSessionId)
            coEvery { fixture.admission.authorizeWorkspaceRole(any()) } returns
                ControlAdmissionDecision.Denied(cc.monomer.control.ControlAdmissionStage.IDENTITY, cc.monomer.control.ControlAdmissionDenial.Identity(cc.monomer.identity.api.IdentityAdmissionDenial.PERMISSION_UNAVAILABLE))

            assertIs<AgentResourceOutcome.Forbidden>(
                fixture.adapter.read(caller(), AgentReadRequest(fixture.resultPath, null)),
            )

            coVerify(exactly = 1) { fixture.admission.authorizeWorkspaceRole(any()) }
            coVerify(exactly = 0) { fixture.resultReadPort.read(any()) }
        }
    }

    private fun resultPayload() = DashboardResultReadPort.DashboardResultPayload(
        columns = listOf(
            DashboardResultReadPort.Column("__monomer_result_0", "TEXT", false, display = null),
            DashboardResultReadPort.Column("__monomer_result_1", "DECIMAL", true, display = null),
        ),
        rows = listOf(
            listOf(JsonPrimitive("completed"), JsonPrimitive("12345678901234567890.12345678901234567")),
            listOf(JsonNull, JsonNull),
        ),
    )

    private inner class ResultReadFixture(viewerSessionId: DashboardSessionId?) {
        val owner = mockk<DashboardOwnerPort>()
        val resultReadPort = mockk<DashboardResultReadPort>()
        val admission = mockk<ControlAdmissionFacade>()
        val lookup = DashboardOwnerResultLookup(
            queryRun = DashboardOwnerQueryRun(
                queryType = "SEMANTIC",
                queryRunId = queryRunId,
                dashboardId = dashboardId,
                viewerSessionId = viewerSessionId,
                queryName = "orders",
                connectorId = ConnectorId(Uuid.parse("01942b04-6e00-7000-8000-000000000020")),
                state = "SUCCEEDED",
                resultAvailable = true,
                failureCode = null,
                failureMessage = null,
                outputColumnNames = listOf("dimension.orders.status", "metric.status"),
                createdAt = clock.instant(),
                updatedAt = clock.instant(),
                actorId = null,
                credentialSurface = null,
                agentClientId = null,
                outputColumnDisplays = null,
                reportingTimeZone = null,
            ),
            resultManifest = ArrowResultManifest(
                objectKey = "artifacts/dashboard/${queryRunId.value}/result.arrow",
                byteSize = 128u,
                objectEtag = "etag-1",
                rowCount = 3u,
                columnCount = 2,
                mediaType = "application/vnd.apache.arrow.file",
            ),
        )
        val resultPath = path(
            if (viewerSessionId == null) {
                "workspace/dashboards/${dashboardId.value}/draft/query-runs/${queryRunId.value}/result"
            } else {
                "workspace/dashboards/${dashboardId.value}/sessions/${viewerSessionId.value}/query-runs/${queryRunId.value}/result"
            },
        )
        val adapter = AgentDashboardOwnerAdapter(
            owner,
            mockk(),
            resultReadPort,
            admission,
            clock,
            fixedAgentProvenanceNames(),
        ) { _, _ -> error("result read must not publish a mutation") }

        init {
            if (viewerSessionId == null) {
                coEvery {
                    owner.readDashboardDraftResult(ReadDashboardDraftResultCommand(workspaceId, dashboardId, queryRunId))
                } returns DashboardOwnerResultLookupOutcome.Success(lookup)
            } else {
                coEvery {
                    owner.readSessionResult(ReadDashboardSessionResultCommand(workspaceId, dashboardId, viewerSessionId, queryRunId))
                } returns DashboardOwnerResultLookupOutcome.Success(lookup)
            }
            coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(mockk())
        }
    }

    private fun adapter(
        owner: DashboardOwnerPort,
        admission: ControlAdmissionFacade = admittedAdmission(),
        callback: (DashboardId, String) -> Unit,
    ): AgentDashboardOwnerAdapter = AgentDashboardOwnerAdapter(
        owner,
        mockk(),
        mockk(),
        admission,
        clock,
        fixedAgentProvenanceNames(),
        callback,
    )

    private fun admittedAdmission(): ControlAdmissionFacade = mockk<ControlAdmissionFacade>().also { admission ->
        coEvery { admission.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Admitted(mockk())
    }

    private fun caller(): AgentWorkspaceCaller = mockk<AgentWorkspaceCaller>().also { caller ->
        every { caller.workspaceId } returns workspaceId
        every { caller.actorPrincipalId } returns "actor-1"
        every { caller.presentedCredential } returns PresentedCredential("agent-token")
        every { caller.expectedOAuthResource } returns ExpectedOAuthResource.AGENT_REST
        every { caller.requestId } returns cc.monomer.agent.api.AgentRequestId("srv_test")
        every { caller.agentClientId } returns cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE
    }

    private fun path(value: String): AgentResourcePath = AgentResourcePath.parse(value)

    private fun definition(hasQueries: Boolean, referencedByWidget: Boolean): JsonObject = Json.parseToJsonElement(
        """{
            "queries": ${if (hasQueries) """{"orders":{"connectorId":"01942b04-6e00-7000-8000-000000000020","query":{}}}""" else "{}"},
            "widgets": ${if (referencedByWidget) """{"chart":{"type":"CHART","queryNames":["orders"]}}""" else "{}"}
        }""",
    ).jsonObject

    private fun dashboard(
        updatedBy: String? = null,
        surface: cc.monomer.identity.api.CredentialSurface? = null,
        agentClientId: cc.monomer.identity.api.AgentClientId? = null,
    ): DashboardOwnerDashboard = DashboardOwnerDashboard(
        dashboardId = dashboardId,
        workspaceId = workspaceId,
        definition = Json.parseToJsonElement("{\"meta\":{\"title\":\"Orders\"}}") as JsonObject,
        createdAt = clock.instant(),
        updatedAt = clock.instant(),
        updatedBy = updatedBy,
        credentialSurface = surface,
        agentClientId = agentClientId,
    )

    private fun draft(
        lastEditedBy: String? = null,
        lastEditSurface: cc.monomer.identity.api.CredentialSurface? = null,
        lastEditAgentClientId: cc.monomer.identity.api.AgentClientId? = null,
    ): DashboardOwnerDraft = DashboardOwnerDraft(
        dashboardId = dashboardId,
        workspaceId = workspaceId,
        definition = Json.parseToJsonElement("{\"meta\":{\"title\":\"Orders\"}}") as JsonObject,
        parameterBindings = emptyMap(),
        variables = emptyMap(),
        authoringRevision = 1,
        targetQueryRuns = emptyMap(),
        readyResults = emptyMap(),
        recentQueryRuns = emptyList(),
        createdAt = clock.instant(),
        updatedAt = clock.instant(),
        lastEditedBy = lastEditedBy,
        lastEditedAt = lastEditedBy?.let { clock.instant() },
        lastEditSurface = lastEditSurface,
        lastEditAgentClientId = lastEditAgentClientId,
    )

    private fun session(): DashboardOwnerSession = DashboardOwnerSession(
        sessionId = sessionId,
        workspaceId = workspaceId,
        dashboardId = dashboardId,
        definition = JsonObject(emptyMap()),
        parameterBindings = emptyMap(),
        initialVariables = mapOf(
            "window_days" to Json.parseToJsonElement("\"30\""),
            "region" to Json.parseToJsonElement("\"US\""),
        ),
        runningQueryRuns = emptyList(),
        createdAt = clock.instant(),
        updatedAt = clock.instant(),
    )

    private class RecordingDashboardOwner(
        private val dashboard: DashboardOwnerOutcome<DashboardOwnerDashboard>? = null,
        private val draft: DashboardOwnerOutcome<DashboardOwnerDraft>? = null,
    ) : DashboardOwnerPort {
        override suspend fun delete(command: cc.monomer.dashboard.api.DeleteDashboardCommand): DashboardOwnerOutcome<Unit> = error("unused")
        override suspend fun listHistory(command: cc.monomer.dashboard.api.ListDashboardHistoryCommand): DashboardOwnerOutcome<cc.monomer.dashboard.api.DashboardOwnerHistoryPage> = error("unused")
        override suspend fun readHistory(command: cc.monomer.dashboard.api.ReadDashboardHistoryCommand): DashboardOwnerOutcome<cc.monomer.dashboard.api.DashboardOwnerDefinitionChange> = error("unused")
        override suspend fun restore(command: cc.monomer.dashboard.api.RestoreDashboardCommand): DashboardOwnerOutcome<DashboardOwnerDashboard> = error("unused")
        override suspend fun readCurrentSemanticReferences(workspaceId: WorkspaceId): List<cc.monomer.dashboard.api.DashboardCurrentSemanticReferences> = error("Not used by Agent Dashboard fixture")
        override suspend fun resolvePublicSession(command: cc.monomer.dashboard.api.ResolvePublicDashboardSessionCommand): DashboardOwnerOutcome<cc.monomer.dashboard.api.DashboardOwnerSessionResolution> = error("Public route is not an Agent surface")
        override suspend fun readInitialSessionResults(command: cc.monomer.dashboard.api.ReadInitialDashboardSessionResultsCommand): DashboardOwnerOutcome<cc.monomer.dashboard.api.DashboardOwnerInitialSessionResults> = error("Entry route is not an Agent surface")
        var replaceCount = 0
        var lastReplace: cc.monomer.dashboard.api.ReplaceDraftDocumentCommand? = null
        var createdMetadata: DashboardOwnerCommandMetadata? = null

        override suspend fun create(command: CreateDashboardCommand): DashboardOwnerOutcome<DashboardOwnerDashboard> {
            createdMetadata = command.metadata
            return checkNotNull(dashboard)
        }

        override suspend fun read(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardOwnerOutcome<DashboardOwnerDashboard> =
            checkNotNull(dashboard)

        override suspend fun listDashboards(
            workspaceId: WorkspaceId,
            cursor: cc.monomer.dashboard.api.DashboardOwnerDashboardPageCursor?,
            pageSize: Int,
        ): DashboardOwnerOutcome<cc.monomer.dashboard.api.DashboardOwnerDashboardPage> = error("unused")

        override suspend fun startDraft(command: StartDraftCommand): DashboardOwnerOutcome<DashboardOwnerDraft> = error("unused")

        override suspend fun readDraft(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardOwnerOutcome<DashboardOwnerDraft> =
            checkNotNull(draft)

        override suspend fun readDraftStatus(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardOwnerOutcome<cc.monomer.dashboard.api.DashboardOwnerDraftStatus> = error("unused")

        override suspend fun listDraftQueryRuns(command: cc.monomer.dashboard.api.ListDraftQueryRunsCommand): DashboardOwnerOutcome<cc.monomer.dashboard.api.DashboardOwnerQueryRunPage> = error("unused")

        override suspend fun replaceDraftDocument(command: cc.monomer.dashboard.api.ReplaceDraftDocumentCommand): DashboardOwnerOutcome<DashboardOwnerDraft> {
            replaceCount += 1
            lastReplace = command
            return checkNotNull(draft)
        }

        override suspend fun applyDraft(command: ApplyDraftCommand): DashboardOwnerOutcome<DashboardOwnerDashboard> = error("unused")
        override suspend fun discardDraft(command: cc.monomer.dashboard.api.DiscardDraftCommand): DashboardOwnerOutcome<DashboardOwnerDraft> = error("unused")
        override suspend fun refreshDraft(command: RefreshDraftCommand): DashboardOwnerOutcome<DashboardOwnerDraft> = error("unused")
        override suspend fun readDashboardDraftRun(command: ReadDashboardDraftRunCommand): DashboardOwnerOutcome<DashboardOwnerQueryRun> = error("unused")
        override suspend fun readDashboardDraftResult(command: cc.monomer.dashboard.api.ReadDashboardDraftResultCommand): cc.monomer.dashboard.api.DashboardOwnerResultLookupOutcome = error("unused")
        override suspend fun cancelDashboardDraftRun(command: CancelDashboardDraftRunCommand): DashboardOwnerOutcome<DashboardOwnerDraft> = error("unused")
        override suspend fun openSession(command: cc.monomer.dashboard.api.OpenDashboardSessionCommand): DashboardOwnerOutcome<cc.monomer.dashboard.api.DashboardOwnerSession> = error("unused")
        override suspend fun readSession(workspaceId: WorkspaceId, dashboardId: DashboardId, sessionId: cc.monomer.dashboard.api.DashboardSessionId): DashboardOwnerOutcome<cc.monomer.dashboard.api.DashboardOwnerSession> = error("unused")
        override suspend fun listSessions(command: cc.monomer.dashboard.api.ListDashboardSessionsCommand): DashboardOwnerOutcome<cc.monomer.dashboard.api.DashboardOwnerSessionPage> = error("unused")
        override suspend fun resolveSession(command: cc.monomer.dashboard.api.ResolveDashboardSessionCommand): DashboardOwnerOutcome<cc.monomer.dashboard.api.DashboardOwnerSessionResolution> = error("unused")
        override suspend fun refreshSession(command: cc.monomer.dashboard.api.RefreshDashboardSessionCommand): DashboardOwnerOutcome<cc.monomer.dashboard.api.DashboardOwnerSessionResolution> = error("unused")
        override suspend fun listSessionQueryRuns(command: cc.monomer.dashboard.api.ListDashboardSessionQueryRunsCommand): DashboardOwnerOutcome<cc.monomer.dashboard.api.DashboardOwnerQueryRunPage> = error("unused")
        override suspend fun readSessionQueryRun(command: cc.monomer.dashboard.api.ReadDashboardSessionQueryRunCommand): DashboardOwnerOutcome<DashboardOwnerQueryRun> = error("unused")
        override suspend fun readSessionResult(command: cc.monomer.dashboard.api.ReadDashboardSessionResultCommand): cc.monomer.dashboard.api.DashboardOwnerResultLookupOutcome = error("unused")
    }
}
