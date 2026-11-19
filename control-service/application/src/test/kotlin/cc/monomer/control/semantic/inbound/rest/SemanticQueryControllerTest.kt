@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.semantic.inbound.rest

import io.mockk.coVerifyOrder
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.SemanticDefinitionIssue
import cc.monomer.semantic.api.SemanticCategoricalFilterKind
import cc.monomer.semantic.api.SemanticDimensionFilterType
import cc.monomer.semantic.api.SemanticElementReference
import cc.monomer.semantic.api.SemanticDefinitionReference
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticExcludedModel
import cc.monomer.semantic.api.SemanticGroupBy
import cc.monomer.semantic.api.SemanticGroupByOptionsResult
import cc.monomer.semantic.api.SemanticInvalidReason
import cc.monomer.semantic.api.SemanticInvalidReasonCode
import cc.monomer.semantic.api.SemanticRenderResult
import cc.monomer.semantic.api.SemanticSqlDialect
import cc.monomer.semantic.api.SemanticSqlRender
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.application.SemanticQueryUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper

class SemanticQueryControllerTest {
    @Test
    fun `SQL preview names a missing top-level Metric in a 404 violation`() {
        coEvery { authorizer.authorizeExplorer(any(), WORKSPACE_ID) } returns Unit
        coEvery { queryUseCase.renderFromInputs(any()) } returns SemanticRenderResult.MetricNotFound(METRIC_NAME)
        val body = client.post()
            .uri("/v2/workspaces/$WORKSPACE_ID/semantic/query/render-sql")
            .header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(RENDER_REQUEST)
            .exchange()
            .expectStatus().isNotFound
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody(String::class.java).returnResult().responseBody
        val problem = com.fasterxml.jackson.databind.ObjectMapper().readTree(body)
        val message = "Metric `revenue` was not found. It may have been deleted."
        assertEquals("NOT_FOUND", problem.path("code").asText())
        assertEquals(message, problem.path("detail").asText())
        assertEquals(1, problem.path("violations").size())
        val violation = problem.path("violations")[0]
        assertEquals("workspace/metrics/revenue", violation.path("field").asText())
        assertEquals("METRIC_NOT_FOUND", violation.path("code").asText())
        assertEquals(message, violation.path("message").asText())
        assertEquals(emptyList(), problemDetailsViolations(problem))
        coVerify(exactly = 1) { queryUseCase.renderFromInputs(match { it.request.query.metrics == listOf(METRIC_NAME) }) }
    }

    private val snapshotActive = java.util.concurrent.atomic.AtomicBoolean()
    private val queryUseCase = mockk<SemanticQueryUseCase>()
    private val authorizer = mockk<SemanticWorkspaceRequestAuthorizer>()
    init {
        val current = cc.monomer.semantic.application.SemanticCurrentQueryInputs(false, null, null, emptyMap(), emptyMap(), emptyList(), null)
        coEvery { queryUseCase.readRenderInputs(any()) } answers {
            assertTrue(snapshotActive.get())
            cc.monomer.semantic.application.SemanticRenderInputs(firstArg(), current)
        }
        coEvery { queryUseCase.readGroupByInputs(any()) } answers {
            assertTrue(snapshotActive.get())
            cc.monomer.semantic.application.SemanticGroupByInputs(firstArg(), current)
        }
    }

    private val client = WebTestClient.bindToController(
        SemanticQueryController(
            queryUseCase,
            authorizer,
            object : cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner {
                override suspend fun <T> run(action: suspend () -> T): T {
                    assertTrue(snapshotActive.compareAndSet(false, true))
                    return try { action() } finally { snapshotActive.set(false) }
                }
            },
        ),
    ).controllerAdvice(SemanticQueryProblemHandler()).build()

    @Test
    fun `SQL preview closes the identity snapshot before computing`() {
        coEvery { authorizer.authorizeExplorer(any(), WORKSPACE_ID) } answers { assertTrue(snapshotActive.get()) }
        coEvery { queryUseCase.renderFromInputs(any()) } answers {
            assertFalse(snapshotActive.get())
            SemanticRenderResult.MetricNotFound(METRIC_NAME)
        }
        client.post().uri("/v2/workspaces/$WORKSPACE_ID/semantic/query/render-sql")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(RENDER_REQUEST).exchange().expectStatus().isNotFound
        coVerifyOrder {
            authorizer.authorizeExplorer(any(), WORKSPACE_ID)
            queryUseCase.readRenderInputs(any())
            queryUseCase.renderFromInputs(any())
        }
        coVerify(exactly = 0) { queryUseCase.render(any()) }
    }

    @Test
    fun `group-by discovery closes the identity snapshot before computing`() {
        coEvery { authorizer.authorizeExplorer(any(), WORKSPACE_ID) } answers { assertTrue(snapshotActive.get()) }
        coEvery { queryUseCase.listGroupBysFromInputs(any()) } answers {
            assertFalse(snapshotActive.get())
            SemanticGroupByOptionsResult.Listed(emptyList(), emptyList(), emptyList())
        }
        client.post().uri("/v2/workspaces/$WORKSPACE_ID/semantic/query/group-bys")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("""{"connectorId":"$CONNECTOR_ID","metrics":["$METRIC_NAME"]}""")
            .exchange().expectStatus().isOk
        coVerifyOrder {
            authorizer.authorizeExplorer(any(), WORKSPACE_ID)
            queryUseCase.readGroupByInputs(any())
            queryUseCase.listGroupBysFromInputs(any())
        }
        coVerify(exactly = 0) { queryUseCase.listGroupBys(any()) }
    }

    @Test
    fun `group-by options require Explorer for selected Connector`() {
        coEvery { authorizer.authorizeExplorer(any(), WORKSPACE_ID) } returns Unit
        coEvery { queryUseCase.listGroupBysFromInputs(any()) } returns SemanticGroupByOptionsResult.Listed(
            listOf(
                SemanticGroupBy.Dimension(
                    semanticModelName = SemanticElementName("orders"),
                    name = SemanticElementName("ordered_at"),
                    granularity = SemanticTimeGranularity.MONTH,
                ),
                SemanticGroupBy.MetricTime(SemanticTimeGranularity.MONTH),
            ),
            emptyList(),
            emptyList(),
        )

        client.post()
            .uri("/v2/workspaces/$WORKSPACE_ID/semantic/query/group-bys")
            .header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"connectorId":"$CONNECTOR_ID","metrics":["$METRIC_NAME"]}""",
            )
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.groupBys[0].kind").isEqualTo("DIMENSION")
            .jsonPath("$.groupBys[0].semanticModelName").isEqualTo("orders")
            .jsonPath("$.groupBys[0].name").isEqualTo("ordered_at")
            .jsonPath("$.groupBys[0].granularity").isEqualTo("MONTH")
            .jsonPath("$.groupBys[1].kind").isEqualTo("METRIC_TIME")
            .jsonPath("$.groupBys[1].granularity").isEqualTo("MONTH")
            .jsonPath("$.excludedSemanticModels.length()").isEqualTo(0)
            .jsonPath("$.dimensionFilterTypes.length()").isEqualTo(0)

        coVerifyOrder {
            authorizer.authorizeExplorer(any(), WORKSPACE_ID)
            queryUseCase.listGroupBysFromInputs(any())
        }
        coVerify(exactly = 1) {
            queryUseCase.listGroupBysFromInputs(
                match { request ->
                    request.request.workspaceId == WORKSPACE_ID &&
                        request.request.connectorId.value == CONNECTOR_ID &&
                        request.request.metrics == listOf(METRIC_NAME)
                },
            )
        }
    }

    @Test
    fun `group-by options reject an empty or repeated metric list as an invalid request`() {
        for (metrics in listOf("[]", """["$METRIC_NAME","$METRIC_NAME"]""")) {
            client.post()
                .uri("/v2/workspaces/$WORKSPACE_ID/semantic/query/group-bys")
                .header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"connectorId":"$CONNECTOR_ID","metrics":$metrics}""")
                .exchange()
                .expectStatus().isBadRequest
                .expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
        }
        coVerify(exactly = 0) { queryUseCase.listGroupBysFromInputs(any()) }
    }

    @Test
    fun `SQL preview requires Explorer before rendering`() {
        coEvery { authorizer.authorizeExplorer(any(), WORKSPACE_ID) } returns Unit
        coEvery { queryUseCase.renderFromInputs(any()) } returns SemanticRenderResult.Rendered(
            SemanticSqlRender(
                workspaceId = WORKSPACE_ID,
                dialect = SemanticSqlDialect.POSTGRESQL,
                sql = "SELECT revenue FROM analytics.orders",
                reportingTimeZone = java.time.ZoneId.of("UTC"),
                outputColumns = emptyList(),
                partitionCheck = cc.monomer.semantic.api.SemanticPartitionCheck(false, emptyList()),
            ),
        )

        client.post()
            .uri("/v2/workspaces/$WORKSPACE_ID/semantic/query/render-sql")
            .header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """
                {
                  "connectorId":"$CONNECTOR_ID",
                  "query":{
                    "schemaVersion":4,
                    "metrics":["$METRIC_NAME"],
                    "groupBys":[],
                    "metricTimeRange":null,
                    "filters":[],
                    "orderBy":[],
                    "rowLimit":500
                  }
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.dialect").isEqualTo("POSTGRESQL")
            .jsonPath("$.sql").isEqualTo("SELECT revenue FROM analytics.orders")

        coVerifyOrder {
            authorizer.authorizeExplorer(any(), WORKSPACE_ID)
            queryUseCase.renderFromInputs(any())
        }
        coVerify(exactly = 1) {
            queryUseCase.renderFromInputs(
                match { request ->
                    request.request.workspaceId == WORKSPACE_ID &&
                        request.request.connectorId.value == CONNECTOR_ID &&
                        request.request.query.metrics == listOf(METRIC_NAME) &&
                        request.request.query.rowLimit == 500
                },
            )
        }
    }

    @Test
    fun `semantic run transport decodes the owning query schema without lifecycle identity`() {
        val queryNode = ObjectMapper().readTree(
            """
            {
              "schemaVersion": 4,
              "metrics": ["$METRIC_NAME"],
              "groupBys": [{"kind":"DIMENSION","semanticModelName":"orders","name":"status","granularity":null}],
              "metricTimeRange": null,
              "filters": [],
              "orderBy": [],
              "rowLimit": 500
            }
            """.trimIndent(),
        )

        val payload = SemanticQueryHttpRequest(
            connectorId = CONNECTOR_ID.toString(),
            query = queryNode,
        ).toPayload()

        assertEquals(CONNECTOR_ID, payload.connectorId.value)
        assertEquals(listOf(METRIC_NAME), payload.query.metrics)
        assertEquals(
            listOf(SemanticGroupBy.Dimension(SemanticElementName("orders"), SemanticElementName("status"), null)),
            payload.query.groupBys,
        )
        assertEquals(500, payload.query.rowLimit)
    }

    @Test
    fun `OpenAPI declares workspace-scoped Semantic SQL rendering`() {
        val openApi = ObjectMapper().readTree(
            Files.readString(
                Path.of(System.getProperty("control.service.root"))
                    .parent
                    .resolve("contracts/control-service.openapi.json"),
            ),
        )
        val operation = openApi.path("paths")
            .path("/v2/workspaces/{workspaceId}/semantic/query/render-sql")
            .path("post")

        assertEquals("renderSemanticSql", operation.path("operationId").stringValue())
        assertEquals(
            setOf(200, 400, 401, 403, 404, 409, 422, 500),
            operation.path("responses").properties().asSequence().map { property -> property.key.toInt() }.toSet(),
        )
        assertEquals(
            "#/components/requestBodies/SemanticSqlRenderRequest",
            operation.path("requestBody").path("\$ref").stringValue(),
        )
    }

    @Test
    fun `group-by discovery exposes catalog comparison types without changing dimension selectors`() {
        coEvery { authorizer.authorizeExplorer(any(), WORKSPACE_ID) } returns Unit
        coEvery { queryUseCase.listGroupBysFromInputs(any()) } returns SemanticGroupByOptionsResult.Listed(
            listOf(SemanticGroupBy.Dimension(SemanticElementName("orders"), SemanticElementName("state"), null)),
            emptyList(),
            listOf(SemanticDimensionFilterType(SemanticElementReference(SemanticElementName("orders"), SemanticElementName("state")), SemanticCategoricalFilterKind.TEXT)),
        )
        client.post().uri("/v2/workspaces/$WORKSPACE_ID/semantic/query/group-bys")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("""{"connectorId":"$CONNECTOR_ID","metrics":["$METRIC_NAME"]}""")
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.groupBys[0].kind").isEqualTo("DIMENSION")
            .jsonPath("$.groupBys[0].name").isEqualTo("state")
            .jsonPath("$.dimensionFilterTypes[0].dimension.semanticModelName").isEqualTo("orders")
            .jsonPath("$.dimensionFilterTypes[0].dimension.name").isEqualTo("state")
            .jsonPath("$.dimensionFilterTypes[0].kind").isEqualTo("TEXT")
    }

    @Test
    fun `catalog comparison mismatch returns the existing 422 contract and filter kind violation`() {
        coEvery { authorizer.authorizeExplorer(any(), WORKSPACE_ID) } returns Unit
        coEvery { queryUseCase.renderFromInputs(any()) } returns SemanticRenderResult.Invalid(
            listOf(SemanticInvalidReason(SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE, "filters[0].kind")),
            emptyList(),
        )
        client.post().uri("/v2/workspaces/$WORKSPACE_ID/semantic/query/render-sql")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(RENDER_REQUEST)
            .exchange().expectStatus().isEqualTo(422).expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_SEMANTIC_QUERY")
            .jsonPath("$.violations[0].field").isEqualTo("query.filters[0].kind")
            .jsonPath("$.violations[0].code").isEqualTo("QUERY_FILTER_UNRENDERABLE")
            .jsonPath("$.violations[0].message").isEqualTo("Filter comparison type must match the column's Catalog type; time dimensions use DATE at day or coarser grain and TIMESTAMP at finer grain.")
    }

    @Test
    fun `comparison mismatch violations preserve saved definition issues`() {
        val rejected = semanticQueryRejected(
            listOf(SemanticInvalidReason(SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE, "filters[2].kind")),
            listOf(SemanticDefinitionIssue(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID, SemanticDefinitionReference.SemanticModel(SemanticElementName("shipments")), SHIPMENTS_MESSAGE)),
            "query",
        )
        assertEquals(listOf("workspace/semantic-models/shipments", "query.filters[2].kind"), rejected.violations.map { it.field })
        assertEquals(SHIPMENTS_MESSAGE, rejected.violations.first().message)
    }

    @Test
    fun `semantic validation failure returns canonical problem details`() {
        coEvery { authorizer.authorizeExplorer(any(), WORKSPACE_ID) } returns Unit
        coEvery { queryUseCase.renderFromInputs(any()) } returns SemanticRenderResult.Invalid(
            listOf(SemanticInvalidReason(SemanticInvalidReasonCode.QUERY_METRIC_NOT_FOUND, null)),
            emptyList(),
        )

        client.post()
            .uri("/v2/workspaces/$WORKSPACE_ID/semantic/query/render-sql")
            .header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """
                {
                  "connectorId":"$CONNECTOR_ID",
                  "query":{
                    "schemaVersion":4,
                    "metrics":["$METRIC_NAME"],
                    "groupBys":[],
                    "metricTimeRange":null,
                    "filters":[],
                    "orderBy":[],
                    "rowLimit":500
                  }
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isEqualTo(422)
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.type").isEqualTo("https://monomer.cc/problems/invalid-semantic-query")
            .jsonPath("$.title").isEqualTo("Unprocessable Content")
            .jsonPath("$.status").isEqualTo(422)
            .jsonPath("$.detail").isEqualTo("QUERY_METRIC_NOT_FOUND")
            .jsonPath("$.code").isEqualTo("INVALID_SEMANTIC_QUERY")
            .jsonPath("$.requestId").isNotEmpty
            .jsonPath("$.traceId").isNotEmpty
            .jsonPath("$.retryable").isEqualTo(false)
            .jsonPath("$.violations").doesNotExist()
    }

    @Test
    fun `group-bys name the candidate models the engine left out`() {
        coEvery { authorizer.authorizeExplorer(any(), WORKSPACE_ID) } returns Unit
        coEvery { queryUseCase.listGroupBysFromInputs(any()) } returns SemanticGroupByOptionsResult.Listed(
            emptyList(),
            listOf(SemanticExcludedModel(SemanticElementName("shipments"), SHIPMENTS_MESSAGE)),
            emptyList(),
        )

        client.post()
            .uri("/v2/workspaces/$WORKSPACE_ID/semantic/query/group-bys")
            .header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"connectorId":"$CONNECTOR_ID","metrics":["$METRIC_NAME"]}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.groupBys.length()").isEqualTo(0)
            .jsonPath("$.excludedSemanticModels.length()").isEqualTo(1)
            .jsonPath("$.excludedSemanticModels[0].semanticModelName").isEqualTo("shipments")
            .jsonPath("$.excludedSemanticModels[0].message").isEqualTo(SHIPMENTS_MESSAGE)
    }

    @Test
    fun `SQL preview problems name each broken saved definition by its resource path`() {
        coEvery { authorizer.authorizeExplorer(any(), WORKSPACE_ID) } returns Unit
        coEvery { queryUseCase.renderFromInputs(any()) } returns SemanticRenderResult.Invalid(
            listOf(
                SemanticInvalidReason(SemanticInvalidReasonCode.QUERY_GROUP_BY_NOT_FOUND, "groupBys[0]"),
                SemanticInvalidReason(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID, null),
            ),
            listOf(
                SemanticDefinitionIssue(
                    SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID,
                    SemanticDefinitionReference.SemanticModel(SemanticElementName("shipments")),
                    SHIPMENTS_MESSAGE,
                ),
                SemanticDefinitionIssue(
                    SemanticInvalidReasonCode.METRIC_DEPENDENCY_NOT_FOUND,
                    SemanticDefinitionReference.Metric(SemanticElementName("total")),
                    "Metric `total` uses metric `base`, which doesn't exist.",
                ),
                SemanticDefinitionIssue(
                    SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID,
                    null,
                    "Metric inputs form a cycle or are more than 100 levels deep.",
                ),
            ),
        )

        val body = client.post()
            .uri("/v2/workspaces/$WORKSPACE_ID/semantic/query/render-sql")
            .header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(RENDER_REQUEST)
            .exchange()
            .expectStatus().isEqualTo(422)
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
        val problem = com.fasterxml.jackson.databind.ObjectMapper().readTree(body)

        assertEquals("QUERY_GROUP_BY_NOT_FOUND,METRICFLOW_MANIFEST_INVALID", problem.path("detail").asText())
        assertEquals("INVALID_SEMANTIC_QUERY", problem.path("code").asText())
        assertEquals(
            listOf(
                Triple("workspace/semantic-models/shipments", "METRICFLOW_MANIFEST_INVALID", SHIPMENTS_MESSAGE),
                Triple("workspace/metrics/total", "METRIC_DEPENDENCY_NOT_FOUND", "Metric `total` uses metric `base`, which doesn't exist."),
                Triple("query", "METRICFLOW_MANIFEST_INVALID", "Metric inputs form a cycle or are more than 100 levels deep."),
            ),
            problem.path("violations").map { violation ->
                Triple(violation.path("field").asText(), violation.path("code").asText(), violation.path("message").asText())
            },
        )
        assertEquals(emptyList(), problemDetailsViolations(problem))
    }

    @Test
    fun `group-by problems point an unattributed definition issue at the metrics`() {
        coEvery { authorizer.authorizeExplorer(any(), WORKSPACE_ID) } returns Unit
        coEvery { queryUseCase.listGroupBysFromInputs(any()) } returns SemanticGroupByOptionsResult.Invalid(
            listOf(SemanticInvalidReason(SemanticInvalidReasonCode.CATALOG_RELATION_NOT_FOUND, null)),
            listOf(
                SemanticDefinitionIssue(
                    SemanticInvalidReasonCode.CATALOG_RELATION_NOT_FOUND,
                    null,
                    "This connector has no tables loaded in Catalog yet.",
                ),
            ),
        )

        client.post()
            .uri("/v2/workspaces/$WORKSPACE_ID/semantic/query/group-bys")
            .header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"connectorId":"$CONNECTOR_ID","metrics":["$METRIC_NAME"]}""")
            .exchange()
            .expectStatus().isEqualTo(422)
            .expectBody()
            .jsonPath("$.detail").isEqualTo("CATALOG_RELATION_NOT_FOUND")
            .jsonPath("$.violations.length()").isEqualTo(1)
            .jsonPath("$.violations[0].field").isEqualTo("metrics")
            .jsonPath("$.violations[0].code").isEqualTo("CATALOG_RELATION_NOT_FOUND")
            .jsonPath("$.violations[0].message").isEqualTo("This connector has no tables loaded in Catalog yet.")
    }

    @Test
    fun `OpenAPI declares excluded models and definition violations for Semantic queries`() {
        val openApi = ObjectMapper().readTree(
            Files.readString(
                Path.of(System.getProperty("control.service.root"))
                    .parent
                    .resolve("contracts/control-service.openapi.json"),
            ),
        )
        val view = openApi.path("components").path("schemas").path("SemanticGroupByOptionsView")
        assertEquals(
            setOf("groupBys", "excludedSemanticModels", "dimensionFilterTypes"),
            view.path("required").asSequence().map { it.stringValue() }.toSet(),
        )
        val filterType = view.path("properties").path("dimensionFilterTypes").path("items")
        assertEquals(setOf("dimension", "kind"), filterType.path("required").asSequence().map { it.stringValue() }.toSet())
        assertEquals(setOf("TEXT", "NUMBER", "BOOLEAN"), filterType.path("properties").path("kind").path("enum").asSequence().map { it.stringValue() }.toSet())
        val excluded = openApi.path("components").path("schemas").path("SemanticExcludedModelView")
        assertEquals(
            setOf("semanticModelName", "message"),
            excluded.path("required").asSequence().map { it.stringValue() }.toSet(),
        )
        for (path in listOf(
            "/v2/workspaces/{workspaceId}/semantic/query/render-sql",
            "/v2/workspaces/{workspaceId}/semantic/query/group-bys",
            "/v2/workspaces/{workspaceId}/explore/semantic-runs",
        )) {
            val response = openApi.path("paths").path(path).path("post").path("responses").path("422")
            kotlin.test.assertFalse(response.isMissingNode, path)
            var resolved = response
            while (!resolved.path("\$ref").isMissingNode) {
                val reference = resolved.path("\$ref").stringValue()
                kotlin.test.assertTrue(reference.startsWith("#/components/responses/"), reference)
                resolved = openApi.path("components").path("responses").path(reference.substringAfterLast('/'))
                kotlin.test.assertFalse(resolved.isMissingNode, reference)
            }
            assertEquals(
                "#/components/schemas/ProblemDetails",
                resolved.path("content").path("application/problem+json").path("schema").path("\$ref").stringValue(),
            )
        }
    }

    private fun problemDetailsViolations(problem: com.fasterxml.jackson.databind.JsonNode): List<String> {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val schema = mapper.readTree(
            Files.readString(
                Path.of(System.getProperty("control.service.root")).parent.resolve("contracts/problem-details.schema.json"),
            ),
        )
        val violation = com.networknt.schema.JsonSchemaFactory
            .getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
            .getSchema(schema.path("\$defs").path("Violation"))
        return problem.path("violations").flatMap { item -> violation.validate(item).map { it.message } }
    }

    @Test
    fun `semantic request without bearer credential returns challenge problem`() {
        coEvery { authorizer.authorizeExplorer(any(), WORKSPACE_ID) } throws
            ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bearer credential is required.")

        client.post()
            .uri("/v2/workspaces/$WORKSPACE_ID/semantic/query/render-sql")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """
                {
                  "connectorId":"$CONNECTOR_ID",
                  "query":{
                    "schemaVersion":4,
                    "metrics":["$METRIC_NAME"],
                    "groupBys":[],
                    "metricTimeRange":null,
                    "filters":[],
                    "orderBy":[],
                    "rowLimit":500
                  }
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isUnauthorized
            .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED")
            .jsonPath("$.retryable").isEqualTo(false)
    }

    @Test
    fun `malformed semantic request body returns canonical problem details`() {
        client.post()
            .uri("/v2/workspaces/$WORKSPACE_ID/semantic/query/render-sql")
            .header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.type").isEqualTo("https://monomer.cc/problems/invalid-request")
            .jsonPath("$.status").isEqualTo(400)
            .jsonPath("$.detail").isEqualTo("Request body is invalid.")
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.traceId").isNotEmpty
            .jsonPath("$.retryable").isEqualTo(false)
    }

    @Test
    fun `missing semantic request body returns canonical problem details`() {
        client.post()
            .uri("/v2/workspaces/$WORKSPACE_ID/semantic/query/render-sql")
            .header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
            .contentType(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.type").isEqualTo("https://monomer.cc/problems/invalid-request")
            .jsonPath("$.status").isEqualTo(400)
            .jsonPath("$.detail").isEqualTo("Request body is invalid.")
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.traceId").isNotEmpty
            .jsonPath("$.retryable").isEqualTo(false)
    }

    @Test
    fun `invalid date and timestamp filters and range endpoints return useful 400 problems`() {
        for ((kind, raw, message) in listOf(
            Triple("DATE", "2026-02-30", "Use a valid ISO 8601 date."),
            Triple("TIMESTAMP", "2026-10-06T12:00:00", "Use a valid ISO 8601 timestamp with an offset."),
            Triple("TIMESTAMP", "not-a-timestamp", "Use a valid ISO 8601 timestamp with an offset."),
        )) {
            assertInvalidQuery(filterQuery(kind, "EQUALS", listOf(raw)), message)
            for (endpoint in listOf("start", "end")) {
                val valid = if (kind == "DATE") "2026-10-06" else "2026-10-06T00:00:00Z"
                val range = """{"start":{"kind":"$kind","value":"${if (endpoint == "start") raw else valid}"},"end":{"kind":"$kind","value":"${if (endpoint == "end") raw else valid}"}}"""
                assertInvalidQuery(filterQuery(kind, "IS_NULL", emptyList()).replace("\"metricTimeRange\":null", "\"metricTimeRange\":$range"), message)
            }
        }
        coVerify(exactly = 0) { queryUseCase.renderFromInputs(any()) }
    }

    @Test
    fun `timestamp filter precision is rejected before SQL preview and names the invalid value`() {
        val message = "Timestamp filter values must have at most 6 fractional second digits."
        for (fraction in listOf("1234567", "12345678", "123456789", "1234567890", "1234560", "0000000")) {
            for (offset in listOf("Z", "+09:00", "-05:00")) {
                val raw = "2026-10-01T00:00:00.$fraction$offset"
                client.post().uri("/v2/workspaces/$WORKSPACE_ID/semantic/query/render-sql")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""{"connectorId":"$CONNECTOR_ID","query":${filterQuery("TIMESTAMP", "IN", listOf("2026-10-01T00:00:00Z", raw))}}""")
                    .exchange().expectStatus().isBadRequest
                    .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST")
                    .jsonPath("$.detail").isEqualTo(message)
                    .jsonPath("$.violations.length()").isEqualTo(1)
                    .jsonPath("$.violations[0].field").isEqualTo("query.filters[0].values[1]")
                    .jsonPath("$.violations[0].code").isEqualTo("SCHEMA_MISMATCH")
                    .jsonPath("$.violations[0].message").isEqualTo(message)
            }
        }
        coVerify(exactly = 0) { queryUseCase.renderFromInputs(any()) }
    }

    @Test
    fun `timestamp filter zero through six digits keep their exact UTC instant`() {
        for (fraction in listOf("", ".1", ".12", ".123", ".1234", ".12345", ".123456", ".000000")) {
            for (offset in listOf("Z", "+09:00", "-05:00")) {
                val raw = "2026-10-01T00:00:00$fraction$offset"
                val filter = kotlin.test.assertIs<cc.monomer.semantic.api.SemanticQueryFilter.Timestamp>(
                    decodeQuery(filterQuery("TIMESTAMP", "EQUALS", listOf(raw))).query.filters.single(),
                )
                assertEquals(listOf(java.time.Instant.parse(raw)), filter.values)
            }
        }
    }

    @Test
    fun `text filter values obey the published character and NUL limits`() {
        for (value in listOf("", "a".repeat(257), "😀".repeat(257))) {
            assertInvalidQuery(filterQuery("TEXT", "EQUALS", listOf(value)), "Text filter values must contain 1 to 256 characters.")
        }
        assertInvalidQuery(filterQuery("TEXT", "EQUALS", listOf("a\u0000b")), "Text filter values must not contain NUL.")
        for (value in listOf("a", " ", "a".repeat(256), "😀".repeat(256), "{{ literal }}")) {
            val filter = kotlin.test.assertIs<cc.monomer.semantic.api.SemanticQueryFilter.Text>(decodeQuery(filterQuery("TEXT", "EQUALS", listOf(value))).query.filters.single())
            assertEquals(listOf(value), filter.values)
        }
    }

    @Test
    fun `number filters accept only the schema canonical decimal spelling and bounds`() {
        val message = "Number filter values must be canonical decimal strings with at most 20 integer digits and 18 fractional digits."
        for (value in listOf("01", "+1", "1e2", "1.0", "-0", "0.0", " 1", "1.", "123456789012345678901", "0.1234567890123456789")) {
            assertInvalidQuery(filterQuery("NUMBER", "EQUALS", listOf(value)), message)
        }
        for (value in listOf("0", "-1", "0.1", "-0.1", "12345678901234567890.123456789012345678")) {
            val filter = kotlin.test.assertIs<cc.monomer.semantic.api.SemanticQueryFilter.Number>(decodeQuery(filterQuery("NUMBER", "EQUALS", listOf(value))).query.filters.single())
            assertEquals(value, filter.values.single().toPlainString())
        }
    }

    @Test
    fun `filter cardinality and duplicate values report clear invalid requests`() {
        assertInvalidQuery(filterQuery("TEXT", "IS_NULL", listOf("a")), "This filter operator does not accept values.")
        for (values in listOf(emptyList(), listOf("a", "b"))) {
            assertInvalidQuery(filterQuery("TEXT", "EQUALS", values), "This filter operator requires exactly one value.")
        }
        for (values in listOf(emptyList(), (1..101).map(Int::toString))) {
            assertInvalidQuery(filterQuery("TEXT", "IN", values), "This filter operator requires 1 to 100 values.")
        }
        for ((kind, values) in listOf(
            "TEXT" to listOf("a", "a"), "NUMBER" to listOf("1", "1"), "DATE" to listOf("2026-10-06", "2026-10-06"),
            "TIMESTAMP" to listOf("2026-10-06T00:00:00Z", "2026-10-06T09:00:00+09:00"),
        )) {
            assertInvalidQuery(filterQuery(kind, "IN", values), "Filter values must be unique.")
        }
        assertEquals(100, kotlin.test.assertIs<cc.monomer.semantic.api.SemanticQueryFilter.Text>(decodeQuery(filterQuery("TEXT", "IN", (1..100).map(Int::toString))).query.filters.single()).values.size)
    }

    @Test
    fun `semantic filters retain the 32 filter limit`() {
        val mapper = ObjectMapper()
        fun queryWithFilters(count: Int): String {
            val query = mapper.readTree(filterQuery("TEXT", "EQUALS", listOf("a"))) as tools.jackson.databind.node.ObjectNode
            query.putArray("filters").apply {
                (1..count).forEach { index ->
                    add(mapper.readTree(filterQuery("TEXT", "EQUALS", listOf(index.toString()))).path("filters")[0])
                }
            }
            return query.toString()
        }
        assertEquals(32, decodeQuery(queryWithFilters(32)).query.filters.size)
        assertInvalidQuery(queryWithFilters(33), "semantic query may contain at most 32 filters")
    }

    private fun decodeQuery(json: String): SemanticQueryHttpPayload =
        SemanticQueryHttpRequest(CONNECTOR_ID.toString(), ObjectMapper().readTree(json)).toPayload()

    private fun assertInvalidQuery(json: String, message: String) {
        coEvery { authorizer.authorizeExplorer(any(), WORKSPACE_ID) } returns Unit
        client.post()
            .uri("/v2/workspaces/$WORKSPACE_ID/semantic/query/render-sql")
            .header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"connectorId":"$CONNECTOR_ID","query":$json}""")
            .exchange().expectStatus().isBadRequest
            .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.detail").isEqualTo(message)
    }

    private fun filterQuery(kind: String, operator: String, values: List<String>): String {
        val valueJson = ObjectMapper().writeValueAsString(values)
        return """{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[{"kind":"$kind","dimension":{"semanticModelName":"orders","name":"status"},"operator":"$operator","values":$valueJson}],"orderBy":[],"rowLimit":10}"""
    }

    private companion object {
        val WORKSPACE_ID = WorkspaceId.parse("019e0000-0000-7000-8000-0000000000a1")
        val CONNECTOR_ID = Uuid.parse("019e0000-0000-7000-8000-0000000000c1")
        val METRIC_NAME = SemanticElementName("revenue")
        const val SHIPMENTS_MESSAGE =
            "In semantic model `shipments`, type conflict for dimension `ordered_at` - already in model as type `TIME` but got `CATEGORICAL`"
        val RENDER_REQUEST = """
            {
              "connectorId":"$CONNECTOR_ID",
              "query":{
                "schemaVersion":4,
                "metrics":["$METRIC_NAME"],
                "groupBys":[],
                "metricTimeRange":null,
                "filters":[],
                "orderBy":[],
                "rowLimit":500
              }
            }
        """.trimIndent()
    }
}
