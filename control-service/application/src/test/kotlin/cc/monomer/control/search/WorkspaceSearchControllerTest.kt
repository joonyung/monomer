@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.search

import cc.monomer.catalog.api.RelationKind
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorProductType
import cc.monomer.control.human.auth.ConnectorCatalogAccess
import cc.monomer.control.human.auth.ConnectorCatalogProblemHandler
import cc.monomer.control.human.auth.ConnectorCatalogRequestAccess
import cc.monomer.control.human.auth.ConnectorCatalogRequestProblem
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.SemanticMetricType
import cc.monomer.semantic.api.SemanticModelId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.uuid.Uuid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient

class WorkspaceSearchControllerTest {
    private val resourceSearch: WorkspaceResourceSearchUseCase = mockk()
    private val requestAccess: ConnectorCatalogRequestAccess = mockk()
    private val workspaceId = "019e0000-0000-7000-8000-0000000000a1"
    private val client = WebTestClient
        .bindToController(WorkspaceSearchController(resourceSearch, requestAccess))
        .controllerAdvice(WorkspaceSearchProblemHandler(), ConnectorCatalogProblemHandler())
        .build()

    @BeforeEach
    fun authorize() {
        coEvery { requestAccess.requireWorkspace(any(), workspaceId) } returns ConnectorCatalogAccess(workspaceId)
    }

    @Test
    fun `search returns current dashboards, metrics, models, and tables by kind`() {
        val dashboardId = DashboardId(Uuid.generateV7())
        val metricId = MetricId.generate()
        val semanticModelId = SemanticModelId.generate()
        val connectorId = ConnectorId.generate()
        coEvery { resourceSearch.search(WorkspaceId.parse(workspaceId), "reve", 5) } returns WorkspaceResourceSearchOutcome.Found(
            WorkspaceSearchResults(
                dashboards = listOf(WorkspaceSearchDashboard(dashboardId, "Revenue overview")),
                metrics = listOf(WorkspaceSearchMetric(metricId, "revenue", "Revenue", SemanticMetricType.SIMPLE)),
                semanticModels = listOf(WorkspaceSearchSemanticModel(semanticModelId, "revenue_lines", null)),
                tables = listOf(
                    CatalogTableSearchHit(
                        connectorId, "Production Postgres", ConnectorProductType.POSTGRESQL, null, "billing", "revenue_lines",
                        RelationKind.TABLE, emptyList(),
                    ),
                ),
            ),
        )

        client.get().uri("/v2/workspaces/$workspaceId/search?query=reve&limit=5")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.dashboards[0].dashboardId").isEqualTo(dashboardId.toString())
            .jsonPath("$.dashboards[0].title").isEqualTo("Revenue overview")
            .jsonPath("$.metrics[0].metricId").isEqualTo(metricId.toString())
            .jsonPath("$.metrics[0].label").isEqualTo("Revenue")
            .jsonPath("$.metrics[0].type").isEqualTo("SIMPLE")
            .jsonPath("$.semanticModels[0].semanticModelId").isEqualTo(semanticModelId.toString())
            .jsonPath("$.semanticModels[0].label").isEmpty()
            .jsonPath("$.tables[0].connectorId").isEqualTo(connectorId.toString())
            .jsonPath("$.tables[0].schemaName").isEqualTo("billing")
            .jsonPath("$.tables[0].relationKind").isEqualTo("TABLE")
    }

    @Test
    fun `a viewer is denied before the search runs`() {
        coEvery { requestAccess.requireWorkspace(any(), workspaceId) } throws
            ConnectorCatalogRequestProblem(HttpStatus.FORBIDDEN, "ROLE_REQUIRED", "The required Workspace role is unavailable.")

        client.get().uri("/v2/workspaces/$workspaceId/search?query=reve&limit=5")
            .exchange()
            .expectStatus().isForbidden
            .expectHeader().contentType("application/problem+json")
            .expectBody()
            .jsonPath("$.code").isEqualTo("ROLE_REQUIRED")
            .jsonPath("$.status").isEqualTo(403)
            .jsonPath("$.instance").isEqualTo("/v2/workspaces/$workspaceId/search")
            .jsonPath("$.retryable").isEqualTo(false)

        coVerify(exactly = 0) { resourceSearch.search(WorkspaceId.parse(workspaceId), any(), any()) }
    }

    @Test
    fun `search rejects an out-of-range limit and a query without letters or numbers`() {
        coEvery { resourceSearch.search(WorkspaceId.parse(workspaceId), "!!", 5) } returns WorkspaceResourceSearchOutcome.InvalidQuery

        client.get().uri("/v2/workspaces/$workspaceId/search?query=reve&limit=11")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.detail").isEqualTo("invalid search limit")
        client.get().uri("/v2/workspaces/$workspaceId/search?query=!!&limit=5")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().contentType("application/problem+json")
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.detail").isEqualTo("Search using letters or numbers.")
            .jsonPath("$.instance").isEqualTo("/v2/workspaces/$workspaceId/search")
            .jsonPath("$.retryable").isEqualTo(false)

        coVerify(exactly = 0) { resourceSearch.search(WorkspaceId.parse(workspaceId), "reve", any()) }
    }
}
