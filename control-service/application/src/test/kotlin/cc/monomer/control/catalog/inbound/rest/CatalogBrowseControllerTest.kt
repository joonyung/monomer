package cc.monomer.control.catalog.inbound.rest

import cc.monomer.catalog.application.CatalogBrowseUseCase
import cc.monomer.catalog.application.CatalogNotFoundException
import cc.monomer.catalog.application.CatalogSummary
import cc.monomer.catalog.application.SchemaListPage
import cc.monomer.catalog.application.SchemaSummary
import cc.monomer.catalog.application.RelationDetail
import cc.monomer.catalog.application.RelationListPage
import cc.monomer.catalog.application.RelationSummary
import cc.monomer.control.human.auth.ConnectorCatalogProblemHandler
import cc.monomer.control.human.auth.ConnectorCatalogRequestAccess
import cc.monomer.control.human.auth.ConnectorCatalogAccess
import cc.monomer.control.human.auth.ConnectorCatalogRequestProblem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.uuid.Uuid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient

class CatalogBrowseControllerTest {
    private val useCase: CatalogBrowseUseCase = mockk()
    private val requestAccess: ConnectorCatalogRequestAccess = mockk()
    private val connectorId = "019e0000-0000-7000-8000-0000000000b1"
    private val workspaceId = "019e0000-0000-7000-8000-0000000000a1"
    private val client = WebTestClient
        .bindToController(CatalogBrowseController(useCase, requestAccess))
        .controllerAdvice(CatalogProblemHandler(), ConnectorCatalogProblemHandler())
        .build()

    @BeforeEach
    fun authorize() {
        coEvery { requestAccess.requireConnector(any(), workspaceId, connectorId) } returns
            ConnectorCatalogAccess(workspaceId)
    }

    @Test
    fun `admission denials return problems before browse owner`() {
        val path = "/v2/workspaces/$workspaceId/catalog/connectors/$connectorId/catalogs"
        for (problem in listOf(
            ConnectorCatalogRequestProblem(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required."),
            ConnectorCatalogRequestProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace resource was not found."),
            ConnectorCatalogRequestProblem(HttpStatus.FORBIDDEN, "ROLE_REQUIRED", "The required Workspace role is unavailable."),
        )) {
            coEvery { requestAccess.requireConnector(any(), workspaceId, connectorId) } throws problem

            client.get().uri(path)
                .exchange()
                .expectStatus().isEqualTo(problem.status)
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.code").isEqualTo(problem.code)
                .jsonPath("$.status").isEqualTo(problem.status.value())
                .jsonPath("$.detail").isEqualTo(problem.detail)
                .jsonPath("$.instance").isEqualTo(path)
                .jsonPath("$.requestId").exists()
                .jsonPath("$.traceId").exists()
                .jsonPath("$.retryable").isEqualTo(false)
        }

        coVerify(exactly = 0) { useCase.listCatalogs(any(), any()) }
        coVerify(exactly = 3) { requestAccess.requireConnector(any(), workspaceId, connectorId) }
    }

    @Test
    fun `non-positive page or page size is a catalog bad request before the browse owner runs`() {
        client.get().uri("/v2/workspaces/$workspaceId/catalog/connectors/$connectorId/schemas?page=0")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.detail").isEqualTo("page must be at least 1")
            .jsonPath("$.retryable").isEqualTo(false)
        client.get().uri("/v2/workspaces/$workspaceId/catalog/connectors/$connectorId/schemas/orders/relations?pageSize=0")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.detail").isEqualTo("pageSize must be between 1 and 200")

        coVerify(exactly = 0) { useCase.listSchemas(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { useCase.listRelations(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `oversized browse pages are rejected before the owner runs`() {
        listOf("schemas", "schemas/orders/relations").forEach { path ->
            client.get().uri("/v2/workspaces/$workspaceId/catalog/connectors/$connectorId/$path?pageSize=201")
                .exchange()
                .expectStatus().isBadRequest
                .expectHeader().contentType("application/problem+json")
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST")
        }
        coVerify(exactly = 0) { useCase.listSchemas(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { useCase.listRelations(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `catalogs preserve response fields and connector authorization`() {
        coEvery { useCase.listCatalogs(workspaceId, connectorId) } returns listOf(
            CatalogSummary("main", 1_700_000_000_000),
            CatalogSummary("warehouse", null),
        )

        client.get().uri("/v2/workspaces/$workspaceId/catalog/connectors/$connectorId/catalogs")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.catalogs[0].name").isEqualTo("main")
            .jsonPath("$.catalogs[0].lastModifiedAtEpochMs").doesNotExist()
            .jsonPath("$.catalogs[0].comment").doesNotExist()
            .jsonPath("$.catalogs[0].lastRefreshedAt").isEqualTo(1_700_000_000_000)
            .jsonPath("$.catalogs[1].lastRefreshedAt").doesNotExist()

        coVerify { requestAccess.requireConnector(any(), workspaceId, connectorId) }
    }

    @Test
    fun `schemas preserve pagination and filtering parameters`() {
        coEvery { useCase.listSchemas(workspaceId, connectorId, "main", 2, 25, "ord") } returns SchemaListPage(
            schemas = listOf(SchemaSummary("main", "orders", 1_700_000_000_000)),
            totalCount = 42,
            page = 2,
            pageSize = 25,
        )

        client.get().uri(
            "/v2/workspaces/$workspaceId/catalog/connectors/$connectorId/schemas" +
                "?catalog=main&page=2&pageSize=25&query=ord",
        )
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.schemas[0].catalogName").isEqualTo("main")
            .jsonPath("$.schemas[0].name").isEqualTo("orders")
            .jsonPath("$.totalCount").isEqualTo(42)
            .jsonPath("$.page").isEqualTo(2)
            .jsonPath("$.pageSize").isEqualTo(25)
    }

    @Test
    fun `relations preserve page fields and relation summary`() {
        coEvery { useCase.listRelations(workspaceId, connectorId, null, "public", null, null, null) } returns RelationListPage(
            relations = listOf(RelationSummary(null, "public", "users", "TABLE", 5, 1_700_000_000_000)),
            totalCount = 1,
            page = 1,
            pageSize = 50,
        )

        client.get().uri(
            "/v2/workspaces/$workspaceId/catalog/connectors/$connectorId/schemas/public/relations",
        )
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.relations[0].name").isEqualTo("users")
            .jsonPath("$.relations[0].columnCount").isEqualTo(5)
            .jsonPath("$.relations[0].lastRefreshedAt").isEqualTo(1_700_000_000_000)
            .jsonPath("$.pageSize").isEqualTo(50)
    }

    @Test
    fun `relation detail preserves catalog lineage resource reference`() {
        val resourceId = Uuid.parse("019e501f-0000-7000-8000-000000000021")
        coEvery { useCase.getRelation(workspaceId, connectorId, "main", "public", "users") } returns RelationDetail(
            resourceId = resourceId,
            catalogName = "main",
            schemaName = "public",
            name = "users",
            kind = "TABLE",
            viewDefinition = null,
            columns = emptyList(),
            partitionKeys = emptyList(),
            clusteringKeys = emptyList(), requiresPartitionFilter = null,
            primaryKey = null,
            foreignKeys = emptyList(),
        )

        client.get().uri(
            "/v2/workspaces/$workspaceId/catalog/connectors/$connectorId/relations/users" +
                "?catalog=main&schema=public",
        )
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.lineageResourceRef.kind").isEqualTo("catalog_table")
            .jsonPath("$.lineageResourceRef.id").isEqualTo(resourceId.toString())
            .jsonPath("$.name").isEqualTo("users")
    }

    @Test
    fun `missing relation remains not found`() {
        coEvery { useCase.getRelation(workspaceId, connectorId, null, "public", "missing") } throws
            CatalogNotFoundException("relation missing")

        client.get().uri(
            "/v2/workspaces/$workspaceId/catalog/connectors/$connectorId/relations/missing?schema=public",
        )
            .exchange()
            .expectStatus().isNotFound
    }
}
