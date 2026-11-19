@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.dashboard.api.DashboardId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.CredentialSurface
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.ResponseStatusException
import kotlin.uuid.Uuid

class DashboardScheduleControllerTest {
    private val workspace = WorkspaceId(Uuid.parse("01942b04-6e00-7000-8000-000000000001"))
    private val dashboard = DashboardId(Uuid.parse("01942b04-6e00-7000-8000-000000000010"))
    private val authorizer = mockk<DashboardRequestAuthorizer>()
    private val observation = mockk<DashboardScheduleObservation>()
    private val controller = DashboardScheduleController(authorizer, tools.jackson.databind.ObjectMapper(), observation)
    private val dashboardController = DashboardController(mockk(), authorizer, java.time.Clock.systemUTC(), tools.jackson.databind.ObjectMapper(),
        mockk(), mockk(), cc.monomer.control.agentapp.FixedAgentAppDisplays, kotlinx.coroutines.Dispatchers.Unconfined, mockk())
    private val draftController = DashboardDraftController(mockk(), mockk(), authorizer, mockk(), java.time.Clock.systemUTC(),
        tools.jackson.databind.ObjectMapper(), mockk(), cc.monomer.control.agentapp.FixedAgentAppDisplays, kotlinx.coroutines.Dispatchers.Unconfined)
    private val client = WebTestClient.bindToController(controller, dashboardController, draftController)
        .controllerAdvice(DashboardProblemHandler()).build()
    private val base = "/v2/workspaces/$workspace/dashboards/$dashboard"

    init {
        coEvery { authorizer.requireWorkspace(any(), workspace.toString()) } returns AuthorizedDashboardRequest(workspace, cc.monomer.identity.api.OrganizationId.generate(), "actor", PresentedCredential("test-token"), CredentialSurface.WEB, null)
    }

    @Test
    fun `Human schedule routes and Draft preview require current Explorer admission`() {
        coEvery { observation.schedules(workspace, dashboard, false) } returns JsonObject(emptyMap())
        client.get().uri("$base/schedules").exchange().expectStatus().isOk
        coEvery { authorizer.requireWorkspace(any(), workspace.toString()) } throws ResponseStatusException(HttpStatus.FORBIDDEN)
        client.get().uri("$base/draft/schedules").exchange().expectStatus().isForbidden
        coVerify(exactly = 0) { observation.schedules(workspace, dashboard, true) }
        coEvery { authorizer.requireWorkspace(any(), workspace.toString()) } throws ResponseStatusException(HttpStatus.NOT_FOUND)
        for (path in listOf("schedules", "draft/schedules", "occurrences", "occurrences/daily/2026-09-20T00:00:00Z")) {
            client.get().uri("$base/$path").exchange().expectStatus().isNotFound
        }
        coVerify(exactly = 1) { observation.schedules(workspace, dashboard, false) }
    }

    @Test
    fun `Human create and Draft replace reject duplicate JSON keys before owner invocation`() {
        val duplicate = """{"schedules":{"daily":{},"daily":{}}}"""
        client.post().uri("/v2/workspaces/$workspace/dashboards").header("Idempotency-Key", "duplicate-create")
            .header("Content-Type", "application/json").bodyValue(duplicate).exchange().expectStatus().isBadRequest
        client.put().uri("$base/draft").header("Idempotency-Key", "duplicate-draft")
            .header("Content-Type", "application/json").bodyValue("""{"expectedRevision":1,"definition":$duplicate,"parameterBindings":{}}""")
            .exchange().expectStatus().isBadRequest
    }
}
