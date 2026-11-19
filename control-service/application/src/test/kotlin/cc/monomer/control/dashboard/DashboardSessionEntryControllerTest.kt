package cc.monomer.control.dashboard

import cc.monomer.dashboard.api.*
import cc.monomer.identity.api.WorkspaceId
import io.mockk.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.json.JsonMapper

class DashboardSessionEntryControllerTest {
    private val workspace = WorkspaceId.generate()
    private val dashboard = DashboardId.generate()
    private val entries = mockk<DashboardSessionEntryReader>()
    private val authorizer = mockk<DashboardRequestAuthorizer>()
    private val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"))
    private val access = AuthorizedDashboardRequest(workspace, cc.monomer.identity.api.OrganizationId.generate(), "actor",
        cc.monomer.identity.api.PresentedCredential("credential"), cc.monomer.identity.api.CredentialSurface.WEB, null)
    private val controller = DashboardSessionEntryController(entries, authorizer, JsonMapper.builder().build(),
        cc.monomer.control.agentapp.FixedAgentAppDisplays, cc.monomer.control.agent.FixedPrincipalDisplayNames)
    private val lookups = (0 until 7).map {
        val id = QueryRunId.generate()
        DashboardOwnerResultLookup(DashboardOwnerQueryRun(
            id, dashboard, DashboardSessionId.generate(), "query$it", "SEMANTIC", cc.monomer.connector.api.ConnectorId.generate(),
            "SUCCEEDED", true, null, null, emptyList(), java.time.Instant.EPOCH, java.time.Instant.EPOCH, null, null, null,
            outputColumnDisplays = null,
            reportingTimeZone = null,
        ), mockk())
    }

    init { coEvery { authorizer.requireWorkspace(exchange, workspace.toString()) } returns access }

    @Test fun `Explorer batch refreshes role once and includes historical results`() = runTest {
        coEvery { entries.select(workspace, dashboard, null, any(), any()) } coAnswers {
            arg<suspend () -> Unit>(3)()
            arg<suspend (List<DashboardOwnerResultLookup>) -> Unit>(4)(lookups)
            arg<suspend () -> Unit>(3)()
            DashboardSessionEntry(null, emptyMap())
        }
        controller.entry(workspace.toString(), dashboard.toString(), null, exchange)
        coVerify(exactly = 4) { authorizer.requireWorkspace(exchange, workspace.toString()) }
    }

    @Test fun `initial query runs name the person who started them`() = runTest {
        val mina = cc.monomer.control.agent.FixedPrincipalDisplayNames.MINA.toString()
        val started = lookups[0].queryRun.copy(actorId = mina, credentialSurface = cc.monomer.identity.api.CredentialSurface.AGENT,
            agentClientId = cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE)
        val scheduled = lookups[1].queryRun
        coEvery { entries.select(workspace, dashboard, null, any(), any()) } returns
            DashboardSessionEntry(null, mapOf("orders" to started, "revenue" to scheduled))
        @Suppress("UNCHECKED_CAST")
        val runs = controller.entry(workspace.toString(), dashboard.toString(), null, exchange).body!!["initialQueryRuns"] as Map<String, QueryRunResponse>
        assertEquals(DashboardActorResponse(mina, "Mina Park"), runs.getValue("orders").startedBy)
        assertEquals("Claude", runs.getValue("orders").agentApp?.name)
        assertEquals(null, runs.getValue("revenue").startedBy)
    }

    @Test fun `member batch never suppresses membership denial`() = runTest {
        coEvery { entries.select(workspace, dashboard, null, any(), any()) } coAnswers {
            coEvery { authorizer.requireWorkspace(exchange, workspace.toString()) } throws ResponseStatusException(HttpStatus.FORBIDDEN)
            arg<suspend (List<DashboardOwnerResultLookup>) -> Unit>(4)(lookups)
            error("Membership denial must propagate")
        }
        assertEquals(HttpStatus.FORBIDDEN, assertFailsWith<ResponseStatusException> {
            controller.entry(workspace.toString(), dashboard.toString(), null, exchange)
        }.statusCode)
    }

    @Test fun `Explorer batch propagates authorization service failure`() = runTest {
        coEvery { authorizer.requireWorkspace(exchange, workspace.toString()) } throws ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
        coEvery { entries.select(workspace, dashboard, null, any(), any()) } coAnswers {
            arg<suspend (List<DashboardOwnerResultLookup>) -> Unit>(4)(lookups)
            error("Graph failure must propagate")
        }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertFailsWith<ResponseStatusException> {
            controller.entry(workspace.toString(), dashboard.toString(), null, exchange)
        }.statusCode)
    }

    @Test fun `canonical identifiers that are not UUIDv7 are client errors with the Dashboard problem shape`() {
        val nonV7 = "01942b04-6e00-4000-8000-000000000010"
        coEvery { authorizer.requireWorkspace(any(), workspace.toString()) } returns access
        val client = org.springframework.test.web.reactive.server.WebTestClient.bindToController(controller)
            .controllerAdvice(DashboardProblemHandler()).build()
        client.get().uri("/v2/workspaces/$workspace/dashboards/$nonV7/sessions/entry").exchange()
            .expectStatus().isBadRequest.expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST").jsonPath("$.detail").isEqualTo("dashboardId must be a UUIDv7")
            .jsonPath("$.retryable").isEqualTo(false)
        client.get().uri("/v2/workspaces/$workspace/dashboards/$dashboard/sessions/entry?sessionId=$nonV7").exchange()
            .expectStatus().isBadRequest.expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST").jsonPath("$.detail").isEqualTo("sessionId must be a UUIDv7")
        client.get().uri("/v2/workspaces/$workspace/dashboards/${dashboard.toString().uppercase()}/sessions/entry").exchange()
            .expectStatus().isBadRequest.expectBody().jsonPath("$.detail").isEqualTo("Identifier must be a canonical UUID.")
        confirmVerified(entries)
    }
}
