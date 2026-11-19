package cc.monomer.control.connector.inbound.rest

import cc.monomer.control.connector.application.ConnectorNotFoundException
import cc.monomer.control.connector.application.ConnectorReadUseCase
import cc.monomer.control.connector.application.ConnectorView
import cc.monomer.control.human.auth.ConnectorCatalogProblemHandler
import cc.monomer.control.human.auth.ConnectorCatalogAccess
import cc.monomer.control.human.auth.ConnectorCatalogRequestAccess
import cc.monomer.control.human.auth.ConnectorCatalogRequestProblem
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient

class ConnectorControllerTest {
    private val useCase: ConnectorReadUseCase = mockk()
    private val requestAccess: ConnectorCatalogRequestAccess = mockk()
    private val workspaceId = "019e0000-0000-7000-8000-0000000000a1"
    private val otherWorkspaceId = "019e0000-0000-7000-8000-0000000000a2"
    private val publicConnectorFields = setOf(
        "id",
        "workspaceId",
        "name",
        "type",
        "properties",
        "sample",
        "createdAtEpochMs",
        "updatedAtEpochMs",
    )
    private val access = ConnectorCatalogAccess(workspaceId)
    private val client = WebTestClient.bindToController(ConnectorController(useCase, requestAccess))
        .controllerAdvice(ConnectorCatalogProblemHandler())
        .build()

    @BeforeEach
    fun authorize() {
        coEvery { requestAccess.requireWorkspace(any(), any()) } returns access
    }

    @Test
    fun `list preserves workspace-scoped connector collection`() {
        coEvery { useCase.listByWorkspace(workspaceId) } returns listOf(view("a", "alpha"), view("b", "beta"))
        coEvery { useCase.listByWorkspace(otherWorkspaceId) } returns listOf(view("other", "other", otherWorkspaceId))

        val workspaceAResponse = client.get().uri("/v2/workspaces/$workspaceId/connectors")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .returnResult()
            .responseBody
        val workspaceBResponse = client.get().uri("/v2/workspaces/$otherWorkspaceId/connectors")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .returnResult()
            .responseBody

        val workspaceABody = requireNotNull(workspaceAResponse).decodeToString()
        val workspaceBBody = requireNotNull(workspaceBResponse).decodeToString()
        val workspaceAConnectors = ObjectMapper().readTree(workspaceABody).path("connectors")
        val workspaceBConnectors = ObjectMapper().readTree(workspaceBBody).path("connectors")
        assertEquals(listOf("a", "b"), workspaceAConnectors.map { it.path("id").asText() })
        assertEquals(listOf("other"), workspaceBConnectors.map { it.path("id").asText() })
        assertEquals(publicConnectorFields, workspaceAConnectors[0].fieldNames().asSequence().toSet())
        assertEquals(workspaceId, workspaceAConnectors[0].path("workspaceId").asText())
        assertEquals(otherWorkspaceId, workspaceBConnectors[0].path("workspaceId").asText())
        assertFalse(workspaceABody.contains("secretSet"))
        assertFalse(workspaceABody.contains("credentialVersionId"))
        assertFalse(workspaceABody.contains("dw_query"))
        assertFalse(workspaceABody.contains("sentinel-secret"))

        coVerify(exactly = 1) { requestAccess.requireWorkspace(any(), workspaceId) }
        coVerify(exactly = 1) { requestAccess.requireWorkspace(any(), otherWorkspaceId) }
        coVerify(exactly = 1) { useCase.listByWorkspace(workspaceId) }
        coVerify(exactly = 1) { useCase.listByWorkspace(otherWorkspaceId) }
    }

    @Test
    fun `get admits workspace before resolving the connector`() {
        coEvery { useCase.get(workspaceId, "conn-1") } returns view("conn-1", "analytics-postgres")

        val response = client.get().uri("/v2/workspaces/$workspaceId/connectors/conn-1")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .returnResult()
            .responseBody

        val responseBody = requireNotNull(response).decodeToString()
        val connector = ObjectMapper().readTree(responseBody)
        assertEquals(publicConnectorFields, connector.fieldNames().asSequence().toSet())
        assertEquals(setOf("host", "port", "database"), connector.path("properties").fieldNames().asSequence().toSet())
        assertEquals(workspaceId, connector.path("workspaceId").asText())
        assertFalse(responseBody.contains("dw_query"))
        assertFalse(responseBody.contains("sentinel-secret"))
        assertFalse(responseBody.contains("credentialVersionId"))

        coVerify(exactly = 1) { requestAccess.requireWorkspace(any(), workspaceId) }
        coVerify(exactly = 1) { useCase.get(workspaceId, "conn-1") }
    }

    @Test
    fun `workspace path never reveals another workspace connector`() {
        coEvery { useCase.get(workspaceId, "conn-1") } throws ConnectorNotFoundException("connector not found")
        coEvery { useCase.get(otherWorkspaceId, "conn-1") } throws ConnectorNotFoundException("connector not found")

        client.get().uri("/v2/workspaces/$workspaceId/connectors/conn-1")
            .exchange()
            .expectStatus().isNotFound
        client.get().uri("/v2/workspaces/$otherWorkspaceId/connectors/conn-1")
            .exchange()
            .expectStatus().isNotFound

        coVerify(exactly = 1) { requestAccess.requireWorkspace(any(), workspaceId) }
        coVerify(exactly = 1) { requestAccess.requireWorkspace(any(), otherWorkspaceId) }
        coVerify(exactly = 1) { useCase.get(workspaceId, "conn-1") }
        coVerify(exactly = 1) { useCase.get(otherWorkspaceId, "conn-1") }
    }

    @Test
    fun `workspace denial prevents connector reads`() {
        coEvery { requestAccess.requireWorkspace(any(), workspaceId) } throws
            ConnectorCatalogRequestProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace resource was not found.")

        client.get().uri("/v2/workspaces/$workspaceId/connectors")
            .exchange()
            .expectStatus().isNotFound
            .expectHeader().contentType("application/problem+json")
            .expectBody()
            .jsonPath("$.code").isEqualTo("NOT_FOUND")
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.detail").isEqualTo("Workspace resource was not found.")
            .jsonPath("$.instance").isEqualTo("/v2/workspaces/$workspaceId/connectors")
            .jsonPath("$.requestId").exists()
            .jsonPath("$.traceId").exists()
            .jsonPath("$.retryable").isEqualTo(false)

        client.get().uri("/v2/workspaces/$workspaceId/connectors/conn-1")
            .exchange()
            .expectStatus().isNotFound
            .expectHeader().contentType("application/problem+json")
            .expectBody()
            .jsonPath("$.code").isEqualTo("NOT_FOUND")

        coVerify(exactly = 0) { useCase.listByWorkspace(any()) }
        coVerify(exactly = 0) { useCase.get(any(), any()) }
    }

    @Test
    fun `removed mutation and test rails are not routed`() {
        client.get().uri("/v2/connectors/conn-1")
            .exchange()
            .expectStatus().isNotFound

        client.patch().uri("/v2/connectors/conn-1")
            .exchange()
            .expectStatus().isNotFound

        client.delete().uri("/v2/connectors/conn-1")
            .exchange()
            .expectStatus().isNotFound

        client.post().uri("/v2/connectors/conn-1/test")
            .exchange()
            .expectStatus().isNotFound

        client.patch().uri("/v2/workspaces/$workspaceId/connectors/conn-1")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)

        client.delete().uri("/v2/workspaces/$workspaceId/connectors/conn-1")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)

        client.post().uri("/v2/workspaces/$workspaceId/connectors/test")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)

        client.post().uri("/v2/workspaces/$workspaceId/connectors/conn-1/test")
            .exchange()
            .expectStatus().isNotFound

        coVerify(exactly = 0) { requestAccess.requireWorkspace(any(), any()) }
        coVerify(exactly = 0) { useCase.get(any(), any()) }
        coVerify(exactly = 0) { useCase.listByWorkspace(any()) }
    }

    @Test
    fun `OpenAPI declares workspace-scoped connector reads and authority mutations`() {
        val openApi =
            ObjectMapper().readTree(
                Files.readString(
                    Path.of(System.getProperty("control.service.root"))
                        .parent
                        .resolve("contracts/control-service.openapi.json"),
                ),
            )
        val paths = openApi.path("paths")
        val collectionPath = paths.path("/v2/workspaces/{workspaceId}/connectors")
        val connectorPath = paths.path("/v2/workspaces/{workspaceId}/connectors/{connectorId}")

        assertEquals(setOf("get", "post"), collectionPath.fieldNames().asSequence().toSet())
        assertEquals(setOf("get", "put", "delete"), connectorPath.fieldNames().asSequence().toSet())
        assertEquals(
            setOf(204, 400, 401, 403, 404, 409),
            connectorPath.path("delete").path("responses").fieldNames().asSequence().map(String::toInt).toSet(),
        )
        assertTrue(connectorPath.path("delete").path("security").first().has("bearerAuth"))
        assertEquals(
            setOf(200, 400, 401, 403),
            collectionPath.path("get").path("responses").fieldNames().asSequence().map(String::toInt).toSet(),
        )
        assertEquals(
            setOf(200, 400, 401, 403, 404),
            connectorPath.path("get").path("responses").fieldNames().asSequence().map(String::toInt).toSet(),
        )
        assertTrue(collectionPath.path("get").path("security").first().has("bearerAuth"))
        assertTrue(collectionPath.path("post").path("security").first().has("bearerAuth"))
        assertTrue(connectorPath.path("get").path("security").first().has("bearerAuth"))
        assertTrue(connectorPath.path("put").path("security").first().has("bearerAuth"))
        assertFalse(paths.has("/v2/connectors/{connectorId}"))
        assertFalse(paths.has("/v2/connectors/{connectorId}/test"))
        assertFalse(paths.has("/v2/workspaces/{workspaceId}/connectors/test"))
        assertFalse(paths.has("/v2/workspaces/{workspaceId}/connectors/{connectorId}/test"))

        val schemas = openApi.path("components").path("schemas")
        listOf("ConnectorTestDraftRequest", "ConnectorSecret", "ConnectionStatusResponse")
            .forEach { schemaName -> assertFalse(schemas.has(schemaName), schemaName) }
        assertTrue(schemas.has("CreateConnectorRequest"))
        assertTrue(schemas.has("ReviseConnectorRequest"))
        assertFalse(schemas.has("ConnectorMutationRequest"))
        assertTrue(schemas.has("ConnectorMutationResponse"))
        assertFalse(schemas.has("ConnectorAuthMethod"))
        assertEquals(
            listOf("POSTGRESQL", "BIGQUERY", "SNOWFLAKE", "DATABRICKS", "REDSHIFT", "TRINO", "DUCKDB"),
            schemas.path("ConnectorType").path("enum").map { it.asText() },
        )
    }

    private fun view(id: String, name: String, scopedWorkspaceId: String = workspaceId): ConnectorView = ConnectorView(
        id = id,
        workspaceId = scopedWorkspaceId,
        name = name,
        type = "POSTGRESQL",
        properties = mapOf("host" to "warehouse.internal", "port" to 5432, "database" to "analytics"),
        createdAtEpochMs = 100,
        updatedAtEpochMs = 200,
        sample = null,
    )

}
