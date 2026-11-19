@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardSessionId
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.dashboard.application.DashboardResultReadPort
import cc.monomer.dashboard.application.DashboardResult
import cc.monomer.dashboard.application.DashboardDraftUseCase
import cc.monomer.dashboard.application.DraftApplyResult
import cc.monomer.dashboard.application.DraftResult
import cc.monomer.dashboard.application.DashboardSessionUseCase
import cc.monomer.dashboard.application.DashboardSessionPage
import cc.monomer.dashboard.application.DashboardSessionPageResult
import cc.monomer.dashboard.application.DashboardSessionSummary
import cc.monomer.dashboard.application.DashboardQueryRunPage
import cc.monomer.dashboard.application.DashboardQueryRunPageResult
import cc.monomer.dashboard.application.DashboardUseCase
import cc.monomer.dashboard.application.SessionResolutionResult
import cc.monomer.dashboard.application.SessionResult
import cc.monomer.dashboard.application.DashboardDefinitionDecoder
import cc.monomer.dashboard.domain.DashboardSession
import cc.monomer.dashboard.domain.QueryRun
import cc.monomer.execution.api.ArrowResultManifest
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.ServerWebExchange
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class DashboardControllerBoundaryTest {
    private val dashboardUseCase = mockk<DashboardUseCase>()
    private val draftUseCase = mockk<DashboardDraftUseCase>()
    private val sessionUseCase = mockk<DashboardSessionUseCase>()
    private val resultReadPort = mockk<DashboardResultReadPort>()
    private val authorizer = mockk<DashboardRequestAuthorizer>()
    private val pages = mockk<cc.monomer.pages.api.PagesCurrentReadPort>()
    private val displayNames = mockk<cc.monomer.identity.api.PrincipalDisplayNames>().also { names ->
        coEvery { names.read(any()) } answers { firstArg<Set<Uuid>>().associateWith { "Sam Kim" } }
    }
    private val clock = Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC)
    private val notifications = DashboardNotificationTestFixture()
    private val eventHub = DashboardSessionEventHub(
        clock,
        notifications.notifications,
    )
    private val draftEventHub = DashboardDraftEventHub(clock, notifications.notifications)
    private val lifecycle = mockk<cc.monomer.dashboard.application.DashboardLifecycleService>()
    private val controller = DashboardController(
        dashboardUseCase,
        authorizer,
        clock,
        ObjectMapper(),
        pages,
        displayNames,
        cc.monomer.control.agentapp.FixedAgentAppDisplays,
        kotlinx.coroutines.Dispatchers.Unconfined,
        lifecycle,
    )
    private val draftController = DashboardDraftController(
        draftUseCase,
        resultReadPort,
        authorizer,
        draftEventHub,
        clock,
        ObjectMapper(),
        displayNames,
        cc.monomer.control.agentapp.FixedAgentAppDisplays,
        kotlinx.coroutines.Dispatchers.Unconfined,
    )
    private val sessionController = DashboardSessionController(
        sessionUseCase,
        resultReadPort,
        authorizer,
        eventHub,
        clock,
        ObjectMapper(),
        displayNames,
        cc.monomer.control.agentapp.FixedAgentAppDisplays,
        kotlinx.coroutines.Dispatchers.Unconfined,
    )
    private val client = WebTestClient.bindToController(
        controller,
        draftController,
        sessionController,
    ).controllerAdvice(DashboardProblemHandler()).build()
    private val workspace = WorkspaceId(Uuid.parse("019e0000-0000-7000-8000-0000000000a1"))
    private val dashboardId = DashboardId(Uuid.parse("019e0000-0000-7000-8000-0000000000a2"))
    private val sessionId = DashboardSessionId(Uuid.parse("019e0000-0000-7000-8000-0000000000a3"))
    private val actorId = "019e0000-0000-7000-8000-0000000000a4"

    @Test
    fun `delete is inputless returns 204 and preserves owner retry and missing outcomes`() {
        authorizeWorkspace()
        val command = io.mockk.slot<cc.monomer.dashboard.api.DeleteDashboardCommand>()
        coEvery { lifecycle.delete(capture(command)) } returns cc.monomer.dashboard.api.DashboardOwnerOutcome.Success(Unit)
        client.delete().uri("/v2/workspaces/$workspace/dashboards/$dashboardId").exchange().expectStatus().isNoContent.expectBody().isEmpty
        assertEquals(actorId, command.captured.metadata.actorId)
        assertEquals(cc.monomer.identity.api.CredentialSurface.WEB, command.captured.metadata.credentialSurface)
        coEvery { lifecycle.delete(any()) } returns cc.monomer.dashboard.api.DashboardOwnerOutcome.NotFound
        client.delete().uri("/v2/workspaces/$workspace/dashboards/$dashboardId").exchange().expectStatus().isNotFound
        coEvery { lifecycle.delete(any()) } returns cc.monomer.dashboard.api.DashboardOwnerOutcome.Rejected("DASHBOARD_DELETE_RETRY_REQUIRED", "Retry deleting the dashboard.")
        client.delete().uri("/v2/workspaces/$workspace/dashboards/$dashboardId").exchange().expectStatus().isEqualTo(503)
            .expectBody().jsonPath("$.code").isEqualTo("DASHBOARD_DELETE_RETRY_REQUIRED").jsonPath("$.retryable").isEqualTo(true)
    }

    @Test
    fun `history projects nullable attribution and refuses cross dashboard cursors before owner read`() {
        authorizeWorkspace()
        val changeId = Uuid.generateV7()
        val version = cc.monomer.dashboard.api.DashboardOwnerDefinitionChange(changeId, workspace, dashboardId, "Deleted dashboard",
            cc.monomer.dashboard.api.DashboardDefinitionChangeAction.DELETED, Json.parseToJsonElement(DASHBOARD_DOCUMENT).jsonObject,
            null, null, null, clock.instant(), true)
        coEvery { lifecycle.listHistory(any()) } returns cc.monomer.dashboard.api.DashboardOwnerOutcome.Success(
            cc.monomer.dashboard.api.DashboardOwnerHistoryPage(dashboardId, "Deleted dashboard", true, listOf(version), null))
        client.get().uri("/v2/workspaces/$workspace/dashboards/$dashboardId/history").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.deleted").isEqualTo(true).jsonPath("$.changes[0].action").isEqualTo("DELETED")
            .jsonPath("$.changes[0].actor").isEmpty.jsonPath("$.changes[0].credentialSurface").isEmpty.jsonPath("$.changes[0].restorable").isEqualTo(true)
        val otherCursor = encodeDashboardHistoryCursor(cc.monomer.dashboard.api.DashboardHistoryCursor(workspace, DashboardId(Uuid.generateV7()), clock.instant(), changeId))
        client.get().uri("/v2/workspaces/$workspace/dashboards/$dashboardId/history?cursor=$otherCursor").exchange().expectStatus().isBadRequest
        client.get().uri("/v2/workspaces/$workspace/dashboards/$dashboardId/history?limit=101").exchange().expectStatus().isBadRequest
        coVerify(exactly = 1) { lifecycle.listHistory(any()) }
        coEvery { lifecycle.readHistory(any()) } returns cc.monomer.dashboard.api.DashboardOwnerOutcome.NotFound
        client.get().uri("/v2/workspaces/$workspace/dashboards/$dashboardId/history/$changeId").exchange().expectStatus().isNotFound
    }

    @Test
    fun `restore needs stable key and returns dedicated owner rejections`() {
        authorizeWorkspace()
        val changeId = Uuid.generateV7()
        val command = io.mockk.slot<cc.monomer.dashboard.api.RestoreDashboardCommand>()
        val live = "This dashboard isn't deleted, so there's nothing to restore. Open it to edit it instead."
        coEvery { lifecycle.restore(capture(command)) } returns cc.monomer.dashboard.api.DashboardOwnerOutcome.Rejected("DASHBOARD_NOT_RESTORABLE", live)
        client.post().uri("/v2/workspaces/$workspace/dashboards/$dashboardId/history/$changeId/restore").exchange().expectStatus().isBadRequest
        client.post().uri("/v2/workspaces/$workspace/dashboards/$dashboardId/history/$changeId/restore")
            .header("Idempotency-Key", "restore-key").exchange().expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("DASHBOARD_NOT_RESTORABLE").jsonPath("$.detail").isEqualTo(live)
            .jsonPath("$.retryable").isEqualTo(false)
        assertEquals("restore-key", command.captured.metadata.idempotencyKey)
        assertEquals(changeId, command.captured.changeId)
        coEvery { lifecycle.restore(any()) } returns cc.monomer.dashboard.api.DashboardOwnerOutcome.Rejected("INVALID_DASHBOARD_DEFINITION", "layout.sections must not be empty")
        client.post().uri("/v2/workspaces/$workspace/dashboards/$dashboardId/history/$changeId/restore")
            .header("Idempotency-Key", "restore-key").exchange().expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("INVALID_DASHBOARD_DEFINITION").jsonPath("$.detail").isEqualTo("layout.sections must not be empty")
    }

    @Test
    fun `ordinary HTTP dashboard list reads use the configured JDBC dispatcher`() = runTest {
        authorizeWorkspace()
        val caller = Thread.currentThread()
        var readThread: Thread? = null
        coEvery { dashboardUseCase.list(workspace) } coAnswers {
            readThread = Thread.currentThread()
            emptyList()
        }
        coEvery { pages.tree(workspace.value) } returns cc.monomer.pages.api.CurrentPagesTree(1, emptyList(), emptyList())
        java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            DashboardController(dashboardUseCase, authorizer, clock, ObjectMapper(), pages, displayNames,
                cc.monomer.control.agentapp.FixedAgentAppDisplays, dispatcher, mockk())
                .list(workspace.toString(), mockk())
        }
        kotlin.test.assertNotSame(caller, readThread)
    }

    @Test
    fun `dashboard list exposes stored list facts and counts only placed DASHBOARD pages per Dashboard`() {
        authorizeWorkspace()
        val placedTwice = DashboardId(Uuid.parse("019e0000-0000-7000-8000-0000000000b1"))
        val unplaced = DashboardId(Uuid.parse("019e0000-0000-7000-8000-0000000000b2"))
        val updatedAt = Instant.parse("2026-08-21T00:00:00Z")
        val resultsAt = Instant.parse("2026-08-21T12:00:00Z")
        coEvery { dashboardUseCase.list(workspace) } returns listOf(
            cc.monomer.dashboard.domain.DashboardListEntry(
                placedTwice, workspace, "Revenue", "Daily revenue", updatedAt, actorId, cc.monomer.identity.api.CredentialSurface.AGENT, cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE, resultsAt,
                listOf(cc.monomer.dashboard.domain.DashboardListSchedule("daily", "0 9 * * *", "Asia/Seoul")), true,
                resultsAt, actorId, cc.monomer.identity.api.CredentialSurface.AGENT, cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE,
            ),
            cc.monomer.dashboard.domain.DashboardListEntry(unplaced, workspace, "Orders", null, updatedAt, null, null, null, null, emptyList(), false, null, null, null, null),
        )
        fun page(id: String, kind: String, dashboardId: DashboardId?) = cc.monomer.pages.api.CurrentPage(
            Uuid.parse(id), kind, null, dashboardId?.value, "Page $id", "Page $id", null,
        )
        val folder = page("019e0000-0000-7000-8000-0000000000c1", "FOLDER", null)
        val nested = page("019e0000-0000-7000-8000-0000000000c2", "DASHBOARD", placedTwice)
        val root = page("019e0000-0000-7000-8000-0000000000c3", "DASHBOARD", placedTwice)
        val detached = page("019e0000-0000-7000-8000-0000000000c4", "DASHBOARD", unplaced)
        coEvery { pages.tree(workspace.value) } returns cc.monomer.pages.api.CurrentPagesTree(
            7,
            listOf(folder, nested, root, detached),
            listOf(
                cc.monomer.pages.api.CurrentPagePlacement(folder.pageId, listOf(cc.monomer.pages.api.CurrentPagePlacement(nested.pageId, emptyList()))),
                cc.monomer.pages.api.CurrentPagePlacement(root.pageId, emptyList()),
            ),
        )
        client.get().uri("/v2/workspaces/$workspace/dashboards").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.items.length()").isEqualTo(2)
            .jsonPath("$.items[0].dashboardId").isEqualTo(placedTwice.toString())
            .jsonPath("$.items[0].latestResultsAt").isEqualTo("2026-08-21T12:00:00Z")
            .jsonPath("$.items[0].schedules[0].key").isEqualTo("daily")
            .jsonPath("$.items[0].schedules[0].cron").isEqualTo("0 9 * * *")
            .jsonPath("$.items[0].schedules[0].timeZone").isEqualTo("Asia/Seoul")
            .jsonPath("$.items[0].draftHasChanges").isEqualTo(true)
            .jsonPath("$.items[0].updatedBy.id").isEqualTo(actorId)
            .jsonPath("$.items[0].updatedBy.displayName").isEqualTo("Sam Kim")
            .jsonPath("$.items[0].credentialSurface").isEqualTo("AGENT")
            .jsonPath("$.items[0].draftLastEditedBy.displayName").isEqualTo("Sam Kim")
            .jsonPath("$.items[0].draftLastEditedAt").isEqualTo("2026-08-21T12:00:00Z")
            .jsonPath("$.items[0].draftLastEditSurface").isEqualTo("AGENT")
            .jsonPath("$.items[0].pagePlacementCount").isEqualTo(2)
            .jsonPath("$.items[1].dashboardId").isEqualTo(unplaced.toString())
            .jsonPath("$.items[1].latestResultsAt").isEmpty
            .jsonPath("$.items[1].schedules.length()").isEqualTo(0)
            .jsonPath("$.items[1].draftHasChanges").isEqualTo(false)
            .jsonPath("$.items[1].updatedBy").isEmpty
            .jsonPath("$.items[1].draftLastEditedBy").isEmpty
            .jsonPath("$.items[1].pagePlacementCount").isEqualTo(0)
    }

    @Test
    fun `library reads editor names in batches the name lookup accepts`() {
        authorizeWorkspace()
        val updatedAt = Instant.parse("2026-08-21T00:00:00Z")
        val editors = List(201) { Uuid.generateV7().toString() }
        coEvery { dashboardUseCase.list(workspace) } returns editors.map { editor ->
            cc.monomer.dashboard.domain.DashboardListEntry(
                DashboardId(Uuid.generateV7()), workspace, "Dashboard", null, updatedAt, editor, cc.monomer.identity.api.CredentialSurface.WEB, null,
                null, emptyList(), false, null, null, null, null,
            )
        }
        coEvery { pages.tree(workspace.value) } returns cc.monomer.pages.api.CurrentPagesTree(1, emptyList(), emptyList())
        client.get().uri("/v2/workspaces/$workspace/dashboards").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.items.length()").isEqualTo(201)
            .jsonPath("$.items[200].updatedBy.displayName").isEqualTo("Sam Kim")
        io.mockk.coVerify(exactly = 2) { displayNames.read(match { it.size <= 200 }) }
    }

    @Test
    fun `discard returns the reset Draft with its last editor and uses the caller's surface`() {
        coEvery { authorizer.requireWorkspace(any(), workspace.toString()) } returns
            AuthorizedDashboardRequest(workspace, cc.monomer.identity.api.OrganizationId.generate(), actorId, PresentedCredential("test-credential"), cc.monomer.identity.api.CredentialSurface.WEB, null)
        val definition = DashboardDefinitionDecoder.decode(Json.parseToJsonElement(DASHBOARD_DOCUMENT).jsonObject)
        val draft = cc.monomer.dashboard.domain.DashboardDraft.start(
            workspace, dashboardId, definition, mapOf("region" to JsonPrimitive("north")), clock.instant(),
        ).copy(authoringRevision = 4, lastEditedAt = clock.instant(), lastEditedBy = actorId, lastEditSurface = cc.monomer.identity.api.CredentialSurface.WEB)
        val command = io.mockk.slot<cc.monomer.dashboard.application.DashboardDraftUseCase.DiscardCommand>()
        coEvery { draftUseCase.discard(capture(command)) } returns DraftResult.Found(draft, emptyList())
        client.post().uri("/v2/workspaces/$workspace/dashboards/$dashboardId/draft:discard")
            .header("Idempotency-Key", "discard-1").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"expectedRevision":3}""")
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.authoringRevision").isEqualTo(4)
            .jsonPath("$.lastEditedBy.id").isEqualTo(actorId)
            .jsonPath("$.lastEditedBy.displayName").isEqualTo("Sam Kim")
            .jsonPath("$.lastEditedAt").isEqualTo("2026-08-22T00:00:00Z")
            .jsonPath("$.lastEditSurface").isEqualTo("WEB")
        kotlin.test.assertEquals(3L, command.captured.expectedRevision)
        kotlin.test.assertEquals("discard-1", command.captured.idempotencyKey)
        kotlin.test.assertEquals(cc.monomer.identity.api.CredentialSurface.WEB, command.captured.credentialSurface)

        coEvery { draftUseCase.discard(any()) } returns DraftResult.Conflict(5)
        client.post().uri("/v2/workspaces/$workspace/dashboards/$dashboardId/draft:discard")
            .header("Idempotency-Key", "discard-2").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"expectedRevision":3}""")
            .exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("DRAFT_REVISION_CONFLICT")
        coEvery { draftUseCase.discard(any()) } returns DraftResult.Rejected("DRAFT_DISCARD_INPUT_MISSING", "Restore input region in the Draft, then discard again.")
        client.post().uri("/v2/workspaces/$workspace/dashboards/$dashboardId/draft:discard")
            .header("Idempotency-Key", "discard-3").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"expectedRevision":3}""")
            .exchange().expectStatus().isEqualTo(422).expectBody().jsonPath("$.code").isEqualTo("DRAFT_DISCARD_INPUT_MISSING")
        client.post().uri("/v2/workspaces/$workspace/dashboards/$dashboardId/draft:discard")
            .header("Idempotency-Key", "discard-4").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{}""")
            .exchange().expectStatus().isBadRequest
    }

    @Test
    fun `Draft recent runs name the person who started each run`() {
        authorizeWorkspace()
        val definition = DashboardDefinitionDecoder.decode(Json.parseToJsonElement(DASHBOARD_DOCUMENT).jsonObject)
        val draft = cc.monomer.dashboard.domain.DashboardDraft.start(
            workspace, dashboardId, definition, mapOf("region" to JsonPrimitive("north")), clock.instant(),
        )
        val run = QueryRun.start(
            id = QueryRunId(Uuid.parse("019e0000-0000-7000-8000-0000000000a6")), workspaceId = workspace, dashboardId = dashboardId,
            viewerSessionId = null, query = session().definition.query("orders"), resolvedQuery = cc.monomer.dashboard.domain.DashboardResolvedQuery.Semantic((session().definition.query("orders") as cc.monomer.dashboard.domain.DashboardNamedQuery.Semantic).query),
            renderedSql = "select 1", outputColumnNames = listOf("metric.revenue"), startedAt = clock.instant(), actorId = actorId,
            credentialSurface = cc.monomer.identity.api.CredentialSurface.AGENT, agentClientId = cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE,
            outputColumnDisplays = null,
            reportingTimeZone = null,
        )
        coEvery { draftUseCase.get(workspace, dashboardId) } returns
            DraftResult.Found(draft, listOf(run, run.copy(id = QueryRunId.generate(), actorId = null, credentialSurface = null, agentClientId = null)))
        client.get().uri("/v2/workspaces/$workspace/dashboards/$dashboardId/draft")
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.recentQueryRuns[0].startedBy.id").isEqualTo(actorId)
            .jsonPath("$.recentQueryRuns[0].startedBy.displayName").isEqualTo("Sam Kim")
            .jsonPath("$.recentQueryRuns[0].agentApp.name").isEqualTo("Claude")
            .jsonPath("$.recentQueryRuns[1].startedBy").doesNotExist()
    }

    @Test
    fun `shared Variable success returns a Draft and malformed maps never reach the owner`() {
        authorizeWorkspace()
        val definition = DashboardDefinitionDecoder.decode(Json.parseToJsonElement(DASHBOARD_DOCUMENT).jsonObject)
        val draft = cc.monomer.dashboard.domain.DashboardDraft.start(
            workspace, dashboardId, definition, mapOf("region" to JsonPrimitive("north")), clock.instant(),
        ).copy(authoringRevision = 1, variables = mapOf("showAverage" to JsonPrimitive(false)))
        coEvery { draftUseCase.setVariables(any()) } returns DraftResult.Found(draft, emptyList())
        client.post().uri("/v2/workspaces/$workspace/dashboards/$dashboardId/draft/variables:set")
            .header("Idempotency-Key", "variables-success").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"expectedRevision":0,"variables":{"showAverage":false}}""")
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.authoringRevision").isEqualTo(1)
            .jsonPath("$.variables.showAverage").isEqualTo(false)
        for (body in listOf("""{"expectedRevision":1}""", """{"expectedRevision":1,"variables":null}""", """{"expectedRevision":1,"variables":[]}""")) {
            client.post().uri("/v2/workspaces/$workspace/dashboards/$dashboardId/draft/variables:set")
                .header("Idempotency-Key", "variables-malformed").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).exchange().expectStatus().isBadRequest
        }
        coVerify(exactly = 1) { draftUseCase.setVariables(any()) }
    }

    @Test
    fun `shared Variable write without Explorer admission never reaches the owner`() {
        authorizeWorkspace()
        coEvery { authorizer.requireWorkspace(any(), workspace.toString()) } throws org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)
        client.post().uri("/v2/workspaces/$workspace/dashboards/$dashboardId/draft/variables:set")
            .header("Idempotency-Key", "variables-denied").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"expectedRevision":0,"variables":{"showAverage":false}}""")
            .exchange().expectStatus().isForbidden
        coVerify(exactly = 0) { draftUseCase.setVariables(any()) }
    }

    @Test
    fun `shared Draft Variable batch requires Explorer admission and forwards one atomic revision command`() {
        authorizeWorkspace()
        coEvery { draftUseCase.setVariables(any()) } returns DraftResult.Conflict(12)
        client.post()
            .uri("/v2/workspaces/$workspace/dashboards/$dashboardId/draft/variables:set")
            .header("Idempotency-Key", "variables-batch")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"expectedRevision":11,"variables":{"channel":"App","region":"North"}}""")
            .exchange().expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.current.revision").isEqualTo("12")
        coVerify(exactly = 1) { authorizer.requireWorkspace(any(), workspace.toString()) }
        coVerify(exactly = 1) { draftUseCase.setVariables(match {
            it.expectedRevision == 11L && it.actorId == actorId && it.idempotencyKey == "variables-batch" &&
                it.variables == mapOf("channel" to JsonPrimitive("App"), "region" to JsonPrimitive("North"))
        }) }
    }

    @Test
    fun `non v7 dashboard path identity is rejected as bad request`() {
        authorizeWorkspace()

        client.get()
            .uri("/v2/workspaces/$workspace/dashboards/00000000-0000-4000-8000-000000000001")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.type").isEqualTo("https://monomer.cc/problems/invalid-request")
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.requestId").isNotEmpty
            .jsonPath("$.traceId").isNotEmpty
            .jsonPath("$.retryable").isEqualTo(false)
    }

    @Test
    fun `current Dashboard read requires one Explorer admission`() {
        authorizeWorkspace()
        coEvery { dashboardUseCase.get(workspace, dashboardId) } returns DashboardResult.NotFound

        client.get()
            .uri("/v2/workspaces/$workspace/dashboards/$dashboardId")
            .exchange()
            .expectStatus().isNotFound

        coVerify(exactly = 1) { authorizer.requireWorkspace(any(), workspace.toString()) }
    }

    @Test
    fun `Dashboard Draft read requires one Explorer admission`() {
        authorizeWorkspace()
        coEvery { draftUseCase.get(workspace, dashboardId) } returns DraftResult.NotFound

        client.get()
            .uri("/v2/workspaces/$workspace/dashboards/$dashboardId/draft")
            .exchange()
            .expectStatus().isNotFound

        coVerify(exactly = 1) { authorizer.requireWorkspace(any(), workspace.toString()) }
    }

    @Test
    fun `Dashboard Draft conflict returns schema valid current revision`() {
        authorizeWorkspace()
        coEvery { draftUseCase.apply(any()) } returns DraftApplyResult.Conflict(12)

        client.post()
            .uri("/v2/workspaces/$workspace/dashboards/$dashboardId/draft:apply")
            .header("Idempotency-Key", "apply-conflict")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"expectedRevision\":11}")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.type").isEqualTo("https://monomer.cc/problems/draft-revision-conflict")
            .jsonPath("$.code").isEqualTo("DRAFT_REVISION_CONFLICT")
            .jsonPath("$.current.revision").isEqualTo("12")
            .jsonPath("$.traceId").isNotEmpty
            .jsonPath("$.retryable").isEqualTo(false)
    }

    @Test
    fun `session open requires an object parameter binding and rejects explicit null`() {
        authorizeWorkspace()

        client.post()
            .uri("/v2/workspaces/$workspace/dashboards/$dashboardId/sessions")
            .header("Idempotency-Key", "open-missing")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isBadRequest

        client.post()
            .uri("/v2/workspaces/$workspace/dashboards/$dashboardId/sessions")
            .header("Idempotency-Key", "open-null")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"parameterBindings\":null}")
            .exchange()
            .expectStatus().isBadRequest

        coVerify(exactly = 0) { sessionUseCase.open(any()) }
    }

    @Test
    fun `invalid dashboard definitions map to stable unprocessable definition errors`() {
        authorizeWorkspace()
        val decoderFailure = assertFailsWith<cc.monomer.dashboard.application.InvalidDashboardDefinitionException> {
            DashboardDefinitionDecoder.decode(kotlinx.serialization.json.buildJsonObject { put("unknown", JsonPrimitive(true)) })
        }
        coEvery { dashboardUseCase.create(any()) } throws decoderFailure

        client.post()
            .uri("/v2/workspaces/$workspace/dashboards")
            .header("Idempotency-Key", "invalid-create")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isEqualTo(422)
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_DASHBOARD_DEFINITION")
            .jsonPath("$.detail").isEqualTo(decoderFailure.message!!)
    }

    @Test
    fun `other create failures are not reported as definition errors`() {
        authorizeWorkspace()
        coEvery { dashboardUseCase.create(any()) } throws IllegalArgumentException("receipt store rejected the row")

        client.post()
            .uri("/v2/workspaces/$workspace/dashboards")
            .header("Idempotency-Key", "failed-create")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().is5xxServerError
    }

    @Test
    fun `Human session open forwards and returns out of control typed values unchanged`() {
        authorizeWorkspace()
        val bindings = mapOf("country" to JsonPrimitive("CN"), "amount" to JsonPrimitive("150"))
        val document = Json.parseToJsonElement(DASHBOARD_DOCUMENT).jsonObject
        val declarations = Json.parseToJsonElement("""{
            "country":{"type":"TEXT","nullable":false,"control":{"options":[{"value":"KR"},{"value":"JP"}]}},
            "amount":{"type":"NUMBER","nullable":false,"control":{"min":"0","max":"100"}}
        }""")
        val definition = DashboardDefinitionDecoder.decode(kotlinx.serialization.json.JsonObject(document + ("parameters" to declarations)))
        val opened = DashboardSession.open(sessionId, workspace, dashboardId, definition, bindings, clock.instant())
        coEvery { sessionUseCase.open(any()) } returns SessionResult.Found(opened, emptyList())
        coEvery { sessionUseCase.get(workspace, sessionId) } returns SessionResult.Found(opened, emptyList())

        client.post().uri("/v2/workspaces/$workspace/dashboards/$dashboardId/sessions")
            .header("Idempotency-Key", "ui-only-open").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"parameterBindings":{"country":"CN","amount":"150"}}""")
            .exchange().expectStatus().isAccepted.expectBody()
            .jsonPath("$.parameterBindings.country").isEqualTo("CN")
            .jsonPath("$.parameterBindings.amount").isEqualTo("150")
        client.get().uri("/v2/workspaces/$workspace/dashboard-sessions/$sessionId")
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.parameterBindings.country").isEqualTo("CN")
            .jsonPath("$.parameterBindings.amount").isEqualTo("150")
        coVerify(exactly = 1) {
            sessionUseCase.open(match { it.parameterBindings == bindings && it.workspaceId == workspace && it.expectedOAuthResource == null })
        }
    }

    @Test
    fun `open returns accepted frozen definition snapshot`() {
        authorizeWorkspace()
        val session = session(parameterBindings = mapOf("region" to JsonPrimitive("north")))
        coEvery { sessionUseCase.open(any()) } returns SessionResult.Found(session, emptyList())

        client.post()
            .uri("/v2/workspaces/$workspace/dashboards/$dashboardId/sessions")
            .header("Idempotency-Key", "open-frozen")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"parameterBindings\":{\"region\":\"north\"}}")
            .exchange()
            .expectStatus().isAccepted
            .expectBody()
            .jsonPath("$.dashboardSessionId").isEqualTo(session.id.toString())
            .jsonPath("$.dashboardId").isEqualTo(dashboardId.toString())
            .jsonPath("$.definition.meta.title").isEqualTo("Frozen dashboard")
            .jsonPath("$.definition.schemaVersion").isEqualTo(1)
            .jsonPath("$.parameterBindings.region").isEqualTo("north")

        coVerify(exactly = 1) {
            sessionUseCase.open(match { command ->
                command.workspaceId == workspace &&
                    command.dashboardId == dashboardId &&
                    command.parameterBindings == mapOf("region" to JsonPrimitive("north")) &&
                    command.actorId == actorId &&
                    command.idempotencyKey == "open-frozen"
            })
        }
    }

    @Test
    fun `session list returns summaries and uses the default page size`() {
        authorizeWorkspace()
        coEvery { sessionUseCase.list(workspace, dashboardId, null, 20) } returns
            DashboardSessionPageResult.Found(
                DashboardSessionPage(
                    items = listOf(
                        DashboardSessionSummary(
                            id = sessionId,
                            dashboardId = dashboardId,
                            parameterBindings = mapOf("region" to JsonPrimitive("north")),
                            runningQueryRuns = emptyList(),
                            lastQueryRunAt = clock.instant().minusSeconds(30),
                            createdAt = clock.instant(),
                            updatedAt = clock.instant(),
                        ),
                        DashboardSessionSummary(
                            id = DashboardSessionId.generate(),
                            dashboardId = dashboardId,
                            parameterBindings = emptyMap(),
                            runningQueryRuns = emptyList(),
                            lastQueryRunAt = null,
                            createdAt = clock.instant(),
                            updatedAt = clock.instant(),
                        ),
                    ),
                    nextCursor = null,
                ),
            )

        client.get()
            .uri("/v2/workspaces/$workspace/dashboards/$dashboardId/sessions")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.items[0].dashboardSessionId").isEqualTo(sessionId.toString())
            .jsonPath("$.items[0].parameterBindings.region").isEqualTo("north")
            .jsonPath("$.items[0].runningQueryRuns").isEmpty
            .jsonPath("$.items[0].lastQueryRunAt").isEqualTo(clock.instant().minusSeconds(30).toString())
            .jsonPath("$.items[1]").value<Map<String, Any?>> { item ->
                assertEquals(true, item.containsKey("lastQueryRunAt"))
                assertEquals(null, item["lastQueryRunAt"])
            }
            .jsonPath("$.nextCursor").isEqualTo(null)

        coVerify(exactly = 1) { sessionUseCase.list(workspace, dashboardId, null, 20) }
    }

    @Test
    fun `session list rejects invalid page size and cursor at the HTTP boundary`() {
        authorizeWorkspace()

        client.get()
            .uri { builder ->
                builder.path("/v2/workspaces/$workspace/dashboards/$dashboardId/sessions")
                    .queryParam("pageSize", "101")
                    .build()
            }
            .exchange()
            .expectStatus().isBadRequest

        client.get()
            .uri { builder ->
                builder.path("/v2/workspaces/$workspace/dashboards/$dashboardId/sessions")
                    .queryParam("cursor", "not-a-cursor")
                    .build()
            }
            .exchange()
            .expectStatus().isEqualTo(422)

        val invalidTimestampCursor = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$dashboardId|not-an-instant|$sessionId".encodeToByteArray())
        client.get()
            .uri { builder ->
                builder.path("/v2/workspaces/$workspace/dashboards/$dashboardId/sessions")
                    .queryParam("cursor", invalidTimestampCursor)
                    .build()
            }
            .exchange()
            .expectStatus().isEqualTo(422)

        val nonV7Cursor = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$dashboardId|2026-08-25T00:00:00Z|00000000-0000-4000-8000-000000000001".encodeToByteArray())
        client.get()
            .uri { builder ->
                builder.path("/v2/workspaces/$workspace/dashboards/$dashboardId/sessions")
                    .queryParam("cursor", nonV7Cursor)
                    .build()
            }
            .exchange()
            .expectStatus().isEqualTo(422)

        coVerify(exactly = 0) { sessionUseCase.list(workspace, dashboardId, any(), any()) }
    }

    @Test
    fun `session cursor round trips and another Dashboard is rejected before owner read`() {
        authorizeWorkspace()
        val cursor = cc.monomer.dashboard.application.DashboardSessionPageCursor(dashboardId, clock.instant(), sessionId)
        val encoded = encodeSessionCursor(cursor)
        assertEquals(cursor, decodeSessionCursor(encoded, dashboardId))
        val otherDashboard = DashboardId.generate()
        client.get().uri("/v2/workspaces/$workspace/dashboards/$otherDashboard/sessions?cursor=$encoded")
            .exchange().expectStatus().isEqualTo(422)
        coVerify(exactly = 0) { sessionUseCase.list(workspace, otherDashboard, any(), any()) }
    }

    @Test
    fun `session query run history uses the existing query run response`() {
        authorizeWorkspace()
        val run = QueryRun.start(
            id = QueryRunId(Uuid.parse("019e0000-0000-7000-8000-0000000000a5")),
            workspaceId = workspace,
            dashboardId = dashboardId,
            viewerSessionId = sessionId,
            query = session().definition.query("orders"),
            resolvedQuery = cc.monomer.dashboard.domain.DashboardResolvedQuery.Semantic((session().definition.query("orders") as cc.monomer.dashboard.domain.DashboardNamedQuery.Semantic).query),
            renderedSql = "select 1",
            outputColumnNames = listOf("dimension.orders.status", "metric.revenue"),
            startedAt = clock.instant(),
            actorId = actorId,
            credentialSurface = cc.monomer.identity.api.CredentialSurface.AGENT,
            agentClientId = cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"),
            outputColumnDisplays = null,
            reportingTimeZone = null,
        )
        coEvery { sessionUseCase.listQueryRuns(workspace, sessionId, null, 20) } returns
            DashboardQueryRunPageResult.Found(DashboardQueryRunPage(listOf(run, run.copy(id = QueryRunId.generate(), actorId = null, credentialSurface = null, agentClientId = null)), null))

        client.get()
            .uri("/v2/workspaces/$workspace/dashboard-sessions/$sessionId/query-runs")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.items[0].queryRunId").isEqualTo(run.id.toString())
            .jsonPath("$.items[0].queryName").isEqualTo("orders")
            .jsonPath("$.items[0].state").isEqualTo("RUNNING")
            .jsonPath("$.items[0].credentialSurface").isEqualTo("AGENT")
            .jsonPath("$.items[1].credentialSurface").doesNotExist()
            .jsonPath("$.items[0].agentApp.name").isEqualTo("Claude")
            .jsonPath("$.items[0].agentApp.verifiedHost").isEqualTo("claude.ai")
            .jsonPath("$.items[1].agentApp").doesNotExist()
            .jsonPath("$.items[0].startedBy.id").isEqualTo(actorId)
            .jsonPath("$.items[0].startedBy.displayName").isEqualTo("Sam Kim")
            .jsonPath("$.items[1].startedBy").doesNotExist()
            .jsonPath("$.nextCursor").isEqualTo(null)

        coVerify(exactly = 1) { sessionUseCase.listQueryRuns(workspace, sessionId, null, 20) }
    }

    @Test
    fun `session failure metadata never emits warehouse error bytes`() {
        authorizeWorkspace()
        val run = QueryRun.start(
            id = QueryRunId(Uuid.parse("019e0000-0000-7000-8000-0000000000a5")),
            workspaceId = workspace,
            dashboardId = dashboardId,
            viewerSessionId = sessionId,
            query = session().definition.query("orders"),
            resolvedQuery = cc.monomer.dashboard.domain.DashboardResolvedQuery.Semantic((session().definition.query("orders") as cc.monomer.dashboard.domain.DashboardNamedQuery.Semantic).query),
            renderedSql = "select 1",
            outputColumnNames = listOf("dimension.orders.status", "metric.revenue"),
            startedAt = clock.instant(),
            actorId = null,
            credentialSurface = null,
            agentClientId = null,
            outputColumnDisplays = null,
            reportingTimeZone = null,
        ).fail("EXECUTION_UNAVAILABLE", "secret warehouse row contents", clock.instant().plusSeconds(30))
        coEvery { sessionUseCase.listQueryRuns(workspace, sessionId, null, 20) } returns
            DashboardQueryRunPageResult.Found(DashboardQueryRunPage(listOf(run), null))

        client.get()
            .uri("/v2/workspaces/$workspace/dashboard-sessions/$sessionId/query-runs")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.items[0].queryRunId").isEqualTo(run.id.toString())
            .jsonPath("$.items[0].queryName").isEqualTo("orders")
            .jsonPath("$.items[0].state").isEqualTo("FAILED")
            .jsonPath("$.items[0].failureMessage").isEqualTo("The query could not start before its dispatch deadline.")
            .jsonPath("$.items[0].updatedAt").isEqualTo(clock.instant().plusSeconds(30).toString())
            .jsonPath("$.nextCursor").isEqualTo(null)

        coVerify(exactly = 1) { sessionUseCase.listQueryRuns(workspace, sessionId, null, 20) }
    }

    @Test
    fun `complete variables reach owner resolution and return selected runs`() {
        authorizeWorkspace()
        coEvery { sessionUseCase.get(workspace, sessionId) } returns SessionResult.Found(session(), emptyList())
        coEvery { sessionUseCase.resolve(any()) } returns SessionResolutionResult.Resolved(mapOf("showAverage" to JsonPrimitive(true)), emptyMap())
        client.post().uri("/v2/workspaces/$workspace/dashboard-sessions/$sessionId:resolve")
            .header("Idempotency-Key", "resolve").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"variables":{"showAverage":true}}""").exchange().expectStatus().isOk
            .expectBody().jsonPath("$.variables.showAverage").isEqualTo(true).jsonPath("$.queryRuns").isEmpty
        coVerify(exactly = 1) { sessionUseCase.resolve(match {
            it.sessionId == sessionId && it.dashboardId == dashboardId && it.variables == mapOf("showAverage" to JsonPrimitive(true)) && it.idempotencyKey == "resolve"
        }) }
    }

    @Test
    fun `Session open definition conflict returns 409 with the existing conflict code`() {
        authorizeWorkspace()
        coEvery { sessionUseCase.open(any()) } returns SessionResult.Rejected("SESSION_CONFLICT", null)
        client.post().uri("/v2/workspaces/$workspace/dashboards/$dashboardId/sessions")
            .header("Idempotency-Key", "definition-conflict").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"parameterBindings":{}}""").exchange().expectStatus().isEqualTo(409)
            .expectHeader().doesNotExist("Retry-After")
            .expectBody().jsonPath("$.code").isEqualTo("SESSION_CONFLICT").jsonPath("$.retryable").isEqualTo(true)
    }

    @Test
    fun `member Session commands recheck current caller admission before owner replay`() {
        authorizeWorkspace()
        coEvery { sessionUseCase.open(any()) } returns SessionResult.Found(session(), emptyList())
        val uri = "/v2/workspaces/$workspace/dashboards/$dashboardId/sessions"
        client.post().uri(uri).header("Idempotency-Key", "replay").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"parameterBindings":{"region":"north"}}""").exchange().expectStatus().isAccepted
        for (status in listOf(org.springframework.http.HttpStatus.UNAUTHORIZED, org.springframework.http.HttpStatus.NOT_FOUND, org.springframework.http.HttpStatus.FORBIDDEN)) {
            coEvery { authorizer.requireWorkspace(any(), workspace.toString()) } throws org.springframework.web.server.ResponseStatusException(status)
            for ((path, body) in listOf(uri to """{"parameterBindings":{}}""",
                "/v2/workspaces/$workspace/dashboard-sessions/$sessionId:resolve" to """{"variables":{}}""",
                "/v2/workspaces/$workspace/dashboard-sessions/$sessionId:refresh" to """{"variables":{},"queryNames":["orders"]}""")) {
                client.post().uri(path).header("Idempotency-Key", "replay").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body).exchange().expectStatus().isEqualTo(status.value())
            }
        }
        coVerify(exactly = 1) { sessionUseCase.open(any()) }
        coVerify(exactly = 0) { sessionUseCase.resolve(any()) }
        coVerify(exactly = 0) { sessionUseCase.refresh(any()) }
    }

    @Test
    fun `Session replays with a conflicting key return 409 on open resolve and refresh`() {
        authorizeWorkspace()
        coEvery { sessionUseCase.open(any()) } returns SessionResult.Rejected("IDEMPOTENCY_CONFLICT", null)
        coEvery { sessionUseCase.get(workspace, sessionId) } returns SessionResult.Found(session(), emptyList())
        coEvery { sessionUseCase.resolve(any()) } returns SessionResolutionResult.Rejected("IDEMPOTENCY_CONFLICT", null)
        coEvery { sessionUseCase.refresh(any()) } returns SessionResolutionResult.Rejected("IDEMPOTENCY_CONFLICT", null)
        val requests = listOf(
            "/v2/workspaces/$workspace/dashboards/$dashboardId/sessions" to """{"parameterBindings":{}}""",
            "/v2/workspaces/$workspace/dashboard-sessions/$sessionId:resolve" to """{"variables":{}}""",
            "/v2/workspaces/$workspace/dashboard-sessions/$sessionId:refresh" to """{"variables":{},"queryNames":["orders"]}""",
        )
        for ((uri, body) in requests) {
            client.post().uri(uri).header("Idempotency-Key", "replayed").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("IDEMPOTENCY_CONFLICT")
        }
    }

    @Test
    fun `resolution rejects malformed and obsolete mutation fields before owner call`() {
        authorizeWorkspace()
        for (body in listOf("{}", """{"variables":null}""", """{"variables":[]}""", """{"variables":{},"expectedRevision":0}""", """{"values":{}}""")) {
            client.post().uri("/v2/workspaces/$workspace/dashboard-sessions/$sessionId:resolve")
                .header("Idempotency-Key", "invalid").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isBadRequest
        }
        coVerify(exactly = 0) { sessionUseCase.resolve(any()) }
    }

    @Test
    fun `resolution preserves explicit null and complete submitted names`() {
        authorizeWorkspace()
        coEvery { sessionUseCase.get(workspace, sessionId) } returns SessionResult.Found(session(), emptyList())
        coEvery { sessionUseCase.resolve(any()) } returns SessionResolutionResult.Rejected("INVALID_VARIABLE_VALUE", null)
        client.post().uri("/v2/workspaces/$workspace/dashboard-sessions/$sessionId:resolve")
            .header("Idempotency-Key", "nullable").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"variables":{"showAverage":true,"selectedRegion":null}}""")
            .exchange().expectStatus().isEqualTo(422)
        coVerify(exactly = 1) { sessionUseCase.resolve(match { it.variables == mapOf("showAverage" to JsonPrimitive(true), "selectedRegion" to kotlinx.serialization.json.JsonNull) }) }
    }

    @Test
    fun `refresh requires a non empty query name array and preserves use case validation`() {
        authorizeWorkspace()

        client.post()
            .uri("/v2/workspaces/$workspace/dashboard-sessions/$sessionId:refresh")
            .header("Idempotency-Key", "refresh-empty")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"queryNames\":[]}")
            .exchange()
            .expectStatus().isBadRequest

        coEvery { sessionUseCase.get(workspace, sessionId) } returns SessionResult.Found(session(), emptyList())
        coEvery { sessionUseCase.refresh(any()) } returns SessionResolutionResult.Rejected("UNKNOWN_QUERY", "unknown query")
        client.post()
            .uri("/v2/workspaces/$workspace/dashboard-sessions/$sessionId:refresh")
            .header("Idempotency-Key", "refresh-unknown")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"variables\":{},\"queryNames\":[\"missing\"]}")
            .exchange()
            .expectStatus().isEqualTo(422)
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.type").isEqualTo("https://monomer.cc/problems/unknown-query")
            .jsonPath("$.code").isEqualTo("UNKNOWN_QUERY")
            .jsonPath("$.traceId").isNotEmpty
            .jsonPath("$.retryable").isEqualTo(false)

        coVerify(exactly = 1) {
            sessionUseCase.refresh(match { command -> command.queryNames == setOf("missing") })
        }
    }

    @Test
    fun `exact Draft run read requires Explorer without starting a Draft`() = runTest {
        authorizeWorkspace()
        val runId = QueryRunId(Uuid.parse("019e0000-0000-7000-8000-0000000000a5"))
        val run = QueryRun.start(
            id = runId, workspaceId = workspace, dashboardId = dashboardId, viewerSessionId = null,
            query = session().definition.query("orders"), resolvedQuery = cc.monomer.dashboard.domain.DashboardResolvedQuery.Semantic((session().definition.query("orders") as cc.monomer.dashboard.domain.DashboardNamedQuery.Semantic).query),
            renderedSql = "select 1",
            outputColumnNames = listOf("metric.revenue"), startedAt = clock.instant(), actorId = actorId,
            credentialSurface = cc.monomer.identity.api.CredentialSurface.AGENT,
            agentClientId = cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"),
            outputColumnDisplays = null,
            reportingTimeZone = null,
        )
        coEvery { draftUseCase.get(workspace, dashboardId) } returns cc.monomer.dashboard.application.DraftResult.Found(mockk(), emptyList())
        coEvery { draftUseCase.getQueryRun(workspace, dashboardId, runId) } returns cc.monomer.dashboard.application.QueryRunLookupResult.Found(run)
        client.get().uri("/v2/workspaces/$workspace/dashboards/$dashboardId/draft/query-runs/$runId").exchange()
            .expectStatus().isOk.expectBody().jsonPath("$.queryRunId").isEqualTo(runId.toString())
            .jsonPath("$.credentialSurface").isEqualTo("AGENT")
            .jsonPath("$.startedBy.id").isEqualTo(actorId)
            .jsonPath("$.startedBy.displayName").isEqualTo("Sam Kim")
        coVerify(exactly = 0) { draftUseCase.start(any()) }
        coEvery { authorizer.requireWorkspace(any(), any()) } throws org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)
        client.get().uri("/v2/workspaces/$workspace/dashboards/$dashboardId/draft/query-runs/$runId").exchange().expectStatus().isForbidden
    }

    @Test
    fun `missing Draft deep link does not recreate or read its former run`() = runTest {
        authorizeWorkspace()
        val runId = QueryRunId(Uuid.parse("019e0000-0000-7000-8000-0000000000a5"))
        coEvery { draftUseCase.get(workspace, dashboardId) } returns cc.monomer.dashboard.application.DraftResult.NotFound
        client.get().uri("/v2/workspaces/$workspace/dashboards/$dashboardId/draft/query-runs/$runId").exchange().expectStatus().isNotFound
        coVerify(exactly = 0) { draftUseCase.getQueryRun(workspace, dashboardId, runId) }
        coVerify(exactly = 0) { draftUseCase.start(any()) }
    }

    @Test
    fun `result read accepts a previous successful run referenced by ready result`() = runTest {
        authorizeWorkspace()
        val runId = QueryRunId(Uuid.parse("019e0000-0000-7000-8000-0000000000a5"))
        val manifest = ArrowResultManifest(
            objectKey = "artifacts/dashboard/$runId/result.arrow",
            byteSize = 128u,
            objectEtag = "etag-1",
            rowCount = 1u,
            columnCount = 2,
            mediaType = "application/vnd.apache.arrow.file",
        )
        val run = QueryRun.start(
            id = runId,
            workspaceId = workspace,
            dashboardId = dashboardId,
            viewerSessionId = sessionId,
            query = session().definition.query("orders"),
            resolvedQuery = cc.monomer.dashboard.domain.DashboardResolvedQuery.Semantic((session().definition.query("orders") as cc.monomer.dashboard.domain.DashboardNamedQuery.Semantic).query),
            renderedSql = "select 1",
            outputColumnNames = listOf("dimension.orders.status", "metric.revenue"),
            startedAt = clock.instant(),
            actorId = null,
            credentialSurface = null,
            agentClientId = null,
            outputColumnDisplays = null,
            reportingTimeZone = java.time.ZoneId.of("Asia/Seoul"),
        ).succeed(manifest, clock.instant())
        val snapshot = session()
        coEvery { sessionUseCase.get(workspace, sessionId) } returns SessionResult.Found(snapshot, listOf(run))
        coEvery { sessionUseCase.getQueryRun(workspace, dashboardId, sessionId, runId) } returns
            cc.monomer.dashboard.application.QueryRunLookupResult.Found(run)
        coEvery { resultReadPort.read(manifest) } returns DashboardResultReadPort.DashboardResultPayload(
            columns = listOf(
                DashboardResultReadPort.Column("__monomer_result_0", "TEXT", false, display = null),
                DashboardResultReadPort.Column("__monomer_result_1", "DECIMAL", false, display = null),
            ),
            rows = listOf(listOf(JsonPrimitive("completed"), JsonPrimitive("12.30"))),
        )

        client.get()
            .uri("/v2/workspaces/$workspace/dashboard-sessions/$sessionId/query-runs/$runId/result")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.queryRunId").isEqualTo(runId.toString())
            .jsonPath("$.reportingTimeZone").isEqualTo("Asia/Seoul")
            .jsonPath("$.columns[0].name").isEqualTo("dimension.orders.status")
            .jsonPath("$.columns[0].logicalType").isEqualTo("TEXT")
            .jsonPath("$.columns[1].name").isEqualTo("metric.revenue")
            .jsonPath("$.rows[0][1]").isEqualTo("12.30")
        val dashboardResultSchema = com.fasterxml.jackson.databind.ObjectMapper().readTree(
            java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("control.service.root")).parent.resolve("contracts/control-service.openapi.json")),
        ).path("components").path("schemas").path("DashboardResult")
        kotlin.test.assertTrue(dashboardResultSchema.path("required").any { it.asText() == "reportingTimeZone" })

        coVerify(exactly = 1) { resultReadPort.read(manifest) }
    }

    @Test
    fun `result read rejects an artifact whose column count differs from the frozen semantic query`() = runTest {
        authorizeWorkspace()
        val runId = QueryRunId(Uuid.parse("019e0000-0000-7000-8000-0000000000a5"))
        val manifest = ArrowResultManifest(
            objectKey = "artifacts/dashboard/$runId/result.arrow",
            byteSize = 128u,
            objectEtag = "etag-1",
            rowCount = 1u,
            columnCount = 1,
            mediaType = "application/vnd.apache.arrow.file",
        )
        val run = QueryRun.start(
            id = runId,
            workspaceId = workspace,
            dashboardId = dashboardId,
            viewerSessionId = sessionId,
            query = session().definition.query("orders"),
            resolvedQuery = cc.monomer.dashboard.domain.DashboardResolvedQuery.Semantic((session().definition.query("orders") as cc.monomer.dashboard.domain.DashboardNamedQuery.Semantic).query),
            renderedSql = "select 1",
            outputColumnNames = listOf("dimension.orders.status", "metric.revenue"),
            startedAt = clock.instant(),
            actorId = null,
            credentialSurface = null,
            agentClientId = null,
            outputColumnDisplays = null,
            reportingTimeZone = null,
        ).succeed(manifest, clock.instant())
        val snapshot = session()
        coEvery { sessionUseCase.get(workspace, sessionId) } returns SessionResult.Found(snapshot, listOf(run))
        coEvery { sessionUseCase.getQueryRun(workspace, dashboardId, sessionId, runId) } returns
            cc.monomer.dashboard.application.QueryRunLookupResult.Found(run)
        coEvery { resultReadPort.read(manifest) } returns DashboardResultReadPort.DashboardResultPayload(
            columns = listOf(DashboardResultReadPort.Column("__monomer_result_0", "TEXT", false, display = null)),
            rows = listOf(listOf(JsonPrimitive("completed"))),
        )

        client.get()
            .uri("/v2/workspaces/$workspace/dashboard-sessions/$sessionId/query-runs/$runId/result")
            .exchange()
            .expectStatus().isEqualTo(503)

        coVerify(exactly = 1) { resultReadPort.read(manifest) }
    }

    @Test
    fun `events send connection and coalesced invalidation hints without run identity`() = runTest {
        authorizeWorkspace()
        val session = session()
        coEvery { sessionUseCase.get(workspace, sessionId) } returns SessionResult.Found(session, emptyList())

        val eventsDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            clientEvents().take(3).toList()
        }
        testScheduler.advanceUntilIdle()
        notifications.sessions.emit(sessionId to "QUERY_RUN_SETTLED")
        val events = eventsDeferred.await()

        assertThat(events.map(ServerSentEvent<Any>::event)).containsExactly("connected", "invalidate", "invalidate")
        assertThat(events[0].id()).isNull()
        assertThat(events[1].id()).isNull()
        assertThat(events[0].data()).isEqualTo(mapOf("sessionId" to sessionId.toString()))
        @Suppress("UNCHECKED_CAST")
        val invalidation = events[2].data() as Map<String, Any>
        assertThat(invalidation).containsEntry("sessionId", sessionId.toString())
        assertThat(invalidation).containsEntry("reason", "QUERY_RUN_SETTLED")
        assertThat(invalidation).doesNotContainKey("queryRunId")
    }

    @Test
    fun `session subscribe closes admission to subscription gap with snapshot invalidation`() = runTest {
        authorizeWorkspace()
        coEvery { sessionUseCase.get(workspace, sessionId) } returns SessionResult.Found(session(), emptyList())
        val stream = clientEvents()
        notifications.sessions.emit(sessionId to "QUERY_RUN_SETTLED")
        val events = stream.take(2).toList()
        assertThat(events.map { it.event() }).containsExactly("connected", "invalidate")
        assertEquals("RECONNECTED", (events.last().data() as Map<*, *>)["reason"])
        coVerify(exactly = 2) { sessionUseCase.get(workspace, sessionId) }
    }

    @Test
    fun `session reconnect rechecks revoked membership before invalidation`() = runTest {
        authorizeWorkspace()
        coEvery { sessionUseCase.get(workspace, sessionId) } returns SessionResult.Found(session(), emptyList())
        val events = async(start = CoroutineStart.UNDISPATCHED) { clientEvents().toList() }
        testScheduler.advanceUntilIdle()
        coEvery { authorizer.requireWorkspace(any(), workspace.toString()) } throws
            org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)
        notifications.sessions.emit(null)
        assertThat(events.await().map { it.event() }).containsExactly("connected", "invalidate")
    }

    @Test
    fun `session events reject an identity outside the admitted workspace`() = runTest {
        authorizeWorkspace()
        coEvery { sessionUseCase.get(workspace, sessionId) } returns SessionResult.NotFound
        val failure = kotlin.test.assertFailsWith<org.springframework.web.server.ResponseStatusException> { clientEvents() }
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, failure.statusCode)
    }

    @Test
    fun `draft reconnect rechecks Explorer admission before invalidation`() = runTest {
        authorizeWorkspace()
        coEvery { draftUseCase.get(workspace, dashboardId) } returns DraftResult.Found(mockk(), emptyList())
        val events = async(start = CoroutineStart.UNDISPATCHED) {
            draftController.draftEvents(workspace.toString(), dashboardId.toString(), mockk()).toList()
        }
        testScheduler.advanceUntilIdle()
        coEvery { authorizer.requireWorkspace(any(), workspace.toString()) } throws
            org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)
        notifications.drafts.emit(null)
        assertThat(events.await().map { it.event() }).containsExactly("connected", "invalidate")
    }

    @Test
    fun `draft events reject an identity outside the admitted workspace`() = runTest {
        authorizeWorkspace()
        coEvery { draftUseCase.get(workspace, dashboardId) } returns DraftResult.NotFound
        val failure = kotlin.test.assertFailsWith<org.springframework.web.server.ResponseStatusException> {
            draftController.draftEvents(workspace.toString(), dashboardId.toString(), mockk())
        }
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, failure.statusCode)
    }

    private suspend fun clientEvents(): kotlinx.coroutines.flow.Flow<ServerSentEvent<Any>> =
        sessionController.events(workspace.toString(), sessionId.toString(), mockk<ServerWebExchange>())

    private fun authorizeWorkspace() {
        coEvery { authorizer.requireWorkspace(any(), workspace.toString()) } returns
            AuthorizedDashboardRequest(workspace, cc.monomer.identity.api.OrganizationId.generate(), actorId, PresentedCredential("test-credential"), cc.monomer.identity.api.CredentialSurface.WEB, null)
    }

    private fun session(parameterBindings: Map<String, JsonElement> = mapOf("region" to JsonPrimitive("north"))): DashboardSession {
        val definition = DashboardDefinitionDecoder.decode(Json.parseToJsonElement(DASHBOARD_DOCUMENT).jsonObject)
        return DashboardSession.open(
            id = sessionId,
            workspaceId = workspace,
            dashboardId = dashboardId,
            definition = definition,
            parameterBindings = parameterBindings,
            openedAt = clock.instant(),
        )
    }

    private companion object {
        const val DASHBOARD_DOCUMENT = """
        {
          "schemaVersion": 1,
          "meta": {"title": "Frozen dashboard", "description": null},
          "parameters": {"region": {"type": "TEXT", "nullable": false}},
          "variables": {"showAverage": {"type": "BOOLEAN", "nullable": false, "initial": true}},
          "queries": {
            "orders": {
              "type":"SEMANTIC","connectorId": "019e0000-0000-7000-8000-0000000000b1",
              "query": {
                "schemaVersion": 4,
                "metrics": ["revenue"],
                "groupBys": [{"kind":"DIMENSION","semanticModelName":"orders","name":"status"}],
                "metricTimeRange": null,
                "filters": [],
                "orderBy": [],
                "rowLimit": 100
              }
            }
          },
          "widgets": {
            "ordersTable": {
              "type": "TABLE",
              "queryName": "orders",
              "columns": [{"field": "dimension.orders.status"}, {"field": "metric.revenue"}]
            }
          },
          "layout": {
            "sections": [{
              "id": "main",
              "height": { "mode": "SCALED", "scale": 1.0 },
              "root": {"type": "WIDGET", "id": "ordersItem", "widgetId": "ordersTable"}
            }]
          }
        }
        """
    }
}
