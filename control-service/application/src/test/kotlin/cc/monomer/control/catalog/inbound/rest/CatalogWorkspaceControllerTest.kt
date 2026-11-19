@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.catalog.inbound.rest

import cc.monomer.catalog.api.CatalogConnectorLastRefresh
import cc.monomer.catalog.api.CatalogConnectorSummary
import cc.monomer.catalog.api.CatalogIngestJobId
import cc.monomer.catalog.api.CatalogRefreshState
import cc.monomer.catalog.api.RelationKind
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorProductType
import cc.monomer.catalog.application.CatalogConnectorSummaryUseCase
import cc.monomer.control.human.auth.ConnectorCatalogAccess
import cc.monomer.control.human.auth.ConnectorCatalogProblemHandler
import cc.monomer.control.human.auth.ConnectorCatalogRequestAccess
import cc.monomer.control.human.auth.ConnectorCatalogRequestProblem
import cc.monomer.control.search.CatalogTableSearchHit
import cc.monomer.control.search.CatalogTableSearchOutcome
import cc.monomer.control.search.CatalogTableSearchUseCase
import cc.monomer.identity.api.WorkspaceId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient

class CatalogWorkspaceControllerTest {
    private val summaries: CatalogConnectorSummaryUseCase = mockk()
    private val tableSearch: CatalogTableSearchUseCase = mockk()
    private val requestAccess: ConnectorCatalogRequestAccess = mockk()
    private val workspaceId = "019e0000-0000-7000-8000-0000000000a1"
    private val connectorId = ConnectorId.generate()
    private val client = WebTestClient
        .bindToController(CatalogWorkspaceController(summaries, tableSearch, requestAccess))
        .controllerAdvice(CatalogProblemHandler(), ConnectorCatalogProblemHandler())
        .build()

    @BeforeEach
    fun authorize() {
        coEvery { requestAccess.requireWorkspace(any(), workspaceId) } returns ConnectorCatalogAccess(workspaceId)
    }

    @Test
    fun `connector summaries expose selection table counts and the latest refresh`() {
        val jobId = CatalogIngestJobId.generate()
        coEvery { summaries.connectorSummaries(WorkspaceId.parse(workspaceId)) } returns listOf(
            CatalogConnectorSummary(
                connectorId = connectorId,
                selectedCatalogCount = 2,
                selectedSchemaCount = 3,
                tableCount = 38,
                lastRefresh = CatalogConnectorLastRefresh(
                    jobId = jobId,
                    state = CatalogRefreshState.COMPLETED_WITH_ERRORS,
                    catalogName = "FINANCE",
                    requestedAt = Instant.parse("2026-10-01T18:39:00Z"),
                    finishedAt = Instant.parse("2026-10-01T18:40:00Z"),
                    errorCount = 1,
                    entryFailureCode = "AUTHENTICATION_FAILED",
                ),
                lastSuccessfulRefreshAt = Instant.parse("2026-09-30T08:00:00Z"),
            ),
        )

        client.get().uri("/v2/workspaces/$workspaceId/catalog/connector-summaries")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.connectors[0].connectorId").isEqualTo(connectorId.toString())
            .jsonPath("$.connectors[0].selectedCatalogCount").isEqualTo(2)
            .jsonPath("$.connectors[0].selectedSchemaCount").isEqualTo(3)
            .jsonPath("$.connectors[0].tableCount").isEqualTo(38)
            .jsonPath("$.connectors[0].lastRefresh.jobId").isEqualTo(jobId.toString())
            .jsonPath("$.connectors[0].lastRefresh.state").isEqualTo("COMPLETED_WITH_ERRORS")
            .jsonPath("$.connectors[0].lastRefresh.catalogName").isEqualTo("FINANCE")
            .jsonPath("$.connectors[0].lastRefresh.errorCount").isEqualTo(1)
            .jsonPath("$.connectors[0].lastRefresh.entryFailureCode").isEqualTo("AUTHENTICATION_FAILED")
            .jsonPath("$.connectors[0].lastSuccessfulRefreshAt").exists()
    }

    @Test
    fun `admission denials return problems before either catalog read runs`() {
        for (problem in listOf(
            ConnectorCatalogRequestProblem(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required."),
            ConnectorCatalogRequestProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace resource was not found."),
            ConnectorCatalogRequestProblem(HttpStatus.FORBIDDEN, "ROLE_REQUIRED", "The required Workspace role is unavailable."),
        )) {
            coEvery { requestAccess.requireWorkspace(any(), workspaceId) } throws problem

            for (path in listOf(
                "/v2/workspaces/$workspaceId/catalog/connector-summaries",
                "/v2/workspaces/$workspaceId/catalog/table-search?query=orders&limit=20",
            )) {
                client.get().uri(path)
                    .exchange()
                    .expectStatus().isEqualTo(problem.status)
                    .expectHeader().contentType("application/problem+json")
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(problem.code)
                    .jsonPath("$.status").isEqualTo(problem.status.value())
                    .jsonPath("$.detail").isEqualTo(problem.detail)
                    .jsonPath("$.instance").isEqualTo(path.substringBefore('?'))
                    .jsonPath("$.requestId").exists()
                    .jsonPath("$.traceId").exists()
                    .jsonPath("$.retryable").isEqualTo(false)
            }
        }

        coVerify(exactly = 0) { summaries.connectorSummaries(WorkspaceId.parse(workspaceId)) }
        coVerify(exactly = 0) { tableSearch.search(WorkspaceId.parse(workspaceId), any(), any()) }
    }

    @Test
    fun `table search returns the address and matched columns of current tables`() {
        coEvery { tableSearch.search(WorkspaceId.parse(workspaceId), "invo", 20) } returns CatalogTableSearchOutcome.Found(
            listOf(
                CatalogTableSearchHit(
                    connectorId = connectorId,
                    connectorName = "Production Postgres",
                    connectorType = ConnectorProductType.POSTGRESQL,
                    catalogName = null,
                    schemaName = "billing",
                    tableName = "invoices",
                    relationKind = RelationKind.TABLE,
                    matchedColumns = listOf("invoice_id"),
                ),
            ),
        )

        client.get().uri("/v2/workspaces/$workspaceId/catalog/table-search?query=invo&limit=20")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.tables[0].connectorId").isEqualTo(connectorId.toString())
            .jsonPath("$.tables[0].connectorName").isEqualTo("Production Postgres")
            .jsonPath("$.tables[0].connectorType").isEqualTo("POSTGRESQL")
            .jsonPath("$.tables[0].schemaName").isEqualTo("billing")
            .jsonPath("$.tables[0].tableName").isEqualTo("invoices")
            .jsonPath("$.tables[0].relationKind").isEqualTo("TABLE")
            .jsonPath("$.tables[0].matchedColumns[0]").isEqualTo("invoice_id")
    }

    @Test
    fun `table search rejects an out-of-range limit and a query without letters or numbers`() {
        coEvery { tableSearch.search(WorkspaceId.parse(workspaceId), "!!", 20) } returns CatalogTableSearchOutcome.InvalidQuery

        client.get().uri("/v2/workspaces/$workspaceId/catalog/table-search?query=orders&limit=51")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.detail").isEqualTo("invalid table search limit")
        client.get().uri("/v2/workspaces/$workspaceId/catalog/table-search?query=!!&limit=20")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().contentType("application/problem+json")
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.detail").isEqualTo("Search for a table or column name using letters or numbers.")
            .jsonPath("$.retryable").isEqualTo(false)

        coVerify(exactly = 0) { tableSearch.search(WorkspaceId.parse(workspaceId), "orders", any()) }
    }
}
