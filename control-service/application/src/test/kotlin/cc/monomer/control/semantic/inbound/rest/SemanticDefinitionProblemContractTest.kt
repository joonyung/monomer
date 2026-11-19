@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.semantic.inbound.rest

import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.AuthorizedMutationContext
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.SemanticAuthorityUseCase
import cc.monomer.semantic.api.SemanticCheckDraft
import cc.monomer.semantic.api.SemanticCheckPreview
import cc.monomer.semantic.api.SemanticCheckProblem
import cc.monomer.semantic.api.SemanticCheckProblemCode
import cc.monomer.semantic.api.SemanticCheckResult
import cc.monomer.semantic.api.SemanticCurrentReadUseCase
import cc.monomer.semantic.api.SemanticDefinitionCheckUseCase
import cc.monomer.semantic.api.SemanticDefinitionReference
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticGroupBy
import cc.monomer.semantic.api.SemanticInvalidReason
import cc.monomer.semantic.api.SemanticInvalidReasonCode
import cc.monomer.semantic.api.SemanticMutationResult
import cc.monomer.semantic.api.SemanticSqlDialect
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.api.SemanticTimeSpineUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper

class SemanticDefinitionProblemContractTest {
    private val authority = mockk<SemanticAuthorityUseCase>()
    private val currentRead = mockk<SemanticCurrentReadUseCase>()
    private val timeSpines = mockk<SemanticTimeSpineUseCase>()
    private val snapshotActive = java.util.concurrent.atomic.AtomicBoolean()
    private val checks = mockk<SemanticDefinitionCheckUseCase>()
    private val authorizer = mockk<SemanticWorkspaceRequestAuthorizer>()
    private val authorization = mockk<AuthorizedMutationContext>(relaxed = true) { io.mockk.every { agentClientId } returns null }
    private val client = WebTestClient.bindToController(
        SemanticModelController(authority, currentRead, authorizer, mockk(), cc.monomer.control.agentapp.FixedAgentAppDisplays),
        MetricController(authority, currentRead, authorizer, mockk(), cc.monomer.control.agentapp.FixedAgentAppDisplays),
        SemanticTimeSpineController(timeSpines, currentRead, authorizer),
        SemanticCheckController(
            checks,
            authorizer,
            object : cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner {
                override suspend fun <T> run(action: suspend () -> T): T {
                    assertTrue(snapshotActive.compareAndSet(false, true))
                    return try { action() } finally { snapshotActive.set(false) }
                }
            },
        ),
    ).controllerAdvice(SemanticDefinitionProblemHandler()).build()

    @BeforeEach
    fun admit() {
        coEvery { checks.readInputs(any()) } answers {
            assertTrue(snapshotActive.get())
            cc.monomer.semantic.api.SemanticCheckInputs(firstArg(), emptyList(), emptyList(), emptyMap(), emptyMap(), emptyList(), java.time.ZoneId.of("UTC"))
        }

        coEvery { authorizer.authorizeExplorer(any(), WORKSPACE) } returns Unit
        coEvery { authorizer.authorizeMutation(any(), WORKSPACE, any()) } returns authorization
    }

    @Test
    fun `a name conflict is a declared problem with the name field`() {
        coEvery { authority.putMetric(any()) } returns SemanticMutationResult.Conflict

        write("/semantic/metrics", METRIC_WRITE)
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.code").isEqualTo("SEMANTIC_NAME_CONFLICT")
            .jsonPath("$.type").isEqualTo("https://monomer.cc/problems/semantic-name-conflict")
            .jsonPath("$.status").isEqualTo(409)
            .jsonPath("$.detail").isNotEmpty
            .jsonPath("$.violations[0].field").isEqualTo("name")
            .jsonPath("$.violations[0].code").isEqualTo("SEMANTIC_NAME_CONFLICT")
            .jsonPath("$.requestId").isNotEmpty
            .jsonPath("$.retryable").isEqualTo(false)
    }

    @Test
    fun `an idempotency conflict is distinct from a name conflict`() {
        coEvery { authority.putModel(any()) } returns SemanticMutationResult.IdempotencyConflict

        write("/semantic/semantic-models", MODEL_WRITE)
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.code").isEqualTo("IDEMPOTENCY_CONFLICT")
            .jsonPath("$.violations").doesNotExist()
    }

    @Test
    fun `owner invalid reasons become field violations`() {
        coEvery { authority.putModel(any()) } returns SemanticMutationResult.Invalid(
            listOf(SemanticInvalidReason(SemanticInvalidReasonCode.MODEL_NAME_IMMUTABLE, "name")),
        )

        write("/semantic/semantic-models", MODEL_WRITE)
            .expectStatus().value { kotlin.test.assertEquals(422, it) }
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.code").isEqualTo("SEMANTIC_DEFINITION_INVALID")
            .jsonPath("$.violations[0].field").isEqualTo("name")
            .jsonPath("$.violations[0].code").isEqualTo("MODEL_NAME_IMMUTABLE")
            .jsonPath("$.violations[0].message").isEqualTo("The name can't be changed after it is created.")
    }

    @Test
    fun `definition decoding failures carry a safe detail before the owner is called`() {
        write("/semantic/semantic-models", MODEL_WRITE.replace("\"primary\"", "\"unique\""))
            .expectStatus().isBadRequest
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.code").isEqualTo("SEMANTIC_DEFINITION_INVALID")
            .jsonPath("$.detail").isEqualTo("Semantic model must define exactly one primary entity")
            .jsonPath("$.violations[0].field").isEqualTo("definition")
        coVerify(exactly = 0) { authority.putModel(any()) }
    }

    @Test
    fun `time spine and malformed bodies return problem details`() {
        client.put()
            .uri("/v2/workspaces/$WORKSPACE_ID/semantic/time-spines/$CONNECTOR_ID")
            .header(HttpHeaders.AUTHORIZATION, BEARER)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"schemaVersion":1,"timeSpines":[{"relation":{"schema":"analytics","relation":"calendar"},"primaryColumn":{"name":"day","granularity":"fortnight"}}]}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.code").isEqualTo("SEMANTIC_DEFINITION_INVALID")
            .jsonPath("$.detail").isEqualTo("Unsupported time spine granularity: fortnight")

        write("/semantic/metrics", "{")
            .expectStatus().isBadRequest
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.violations[0].field").isEqualTo("body")
    }

    @Test
    fun `invalid time spine identifiers identify their row and never reach the owner write`() {
        val invalidFields = listOf(
            Triple("relation.catalog", "\"catalog\":\"warehouse\"", "\"catalog\":\" warehouse\""),
            Triple("relation.schema", "\"schema\":\"analytics\"", "\"schema\":\" analytics\""),
            Triple("relation.relation", "\"relation\":\"calendar\"", "\"relation\":\"calendar \""),
            Triple("primaryColumn.name", "\"name\":\"day\"", "\"name\":\"day\\u0000\""),
            Triple("primaryColumn.granularity", "\"granularity\":\"week\"", "\"granularity\":\"fortnight\""),
        )
        val first = """{"relation":{"schema":"analytics","relation":"calendar"},"primaryColumn":{"name":"day","granularity":"day"}}"""
        val second = """{"relation":{"catalog":"warehouse","schema":"analytics","relation":"calendar"},"primaryColumn":{"name":"day","granularity":"week"}}"""
        for ((field, valid, invalid) in invalidFields) {
            client.put()
                .uri("/v2/workspaces/$WORKSPACE_ID/semantic/time-spines/$CONNECTOR_ID")
                .header(HttpHeaders.AUTHORIZATION, BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"schemaVersion":1,"timeSpines":[$first,${second.replace(valid, invalid)}]}""")
                .exchange()
                .expectStatus().isBadRequest
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SEMANTIC_DEFINITION_INVALID")
                .jsonPath("$.violations[0].field").isEqualTo("timeSpines[1].$field")
                .jsonPath("$.violations[0].message").isNotEmpty
                .jsonPath("$.retryable").isEqualTo(false)
        }
        coVerify(exactly = 0) { timeSpines.putTimeSpineConfiguration(any()) }
    }

    @Test
    fun `a time spine column with a time zone is an unprocessable definition at its row`() {
        coEvery { timeSpines.putTimeSpineConfiguration(any()) } returns cc.monomer.semantic.api.SemanticTimeSpineMutationResult.Invalid(
            listOf(cc.monomer.semantic.api.SemanticInvalidReason(
                cc.monomer.semantic.api.SemanticInvalidReasonCode.TIME_SPINE_COLUMN_TYPE_INVALID, "timeSpines[0].primaryColumn.name",
            )),
        )
        client.put()
            .uri("/v2/workspaces/$WORKSPACE_ID/semantic/time-spines/$CONNECTOR_ID")
            .header(HttpHeaders.AUTHORIZATION, BEARER)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"schemaVersion":1,"timeSpines":[{"relation":{"schema":"analytics","relation":"calendar"},"primaryColumn":{"name":"day","granularity":"day"}}]}""")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.code").isEqualTo("SEMANTIC_DEFINITION_INVALID")
            .jsonPath("$.violations[0].field").isEqualTo("timeSpines[0].primaryColumn.name")
            .jsonPath("$.violations[0].code").isEqualTo("TIME_SPINE_COLUMN_TYPE_INVALID")
            .jsonPath("$.violations[0].message").isEqualTo("Use a date column or a timestamp column without a time zone.")
    }

    @Test
    fun `time spine definition failures keep a definition violation`() {
        client.put()
            .uri("/v2/workspaces/$WORKSPACE_ID/semantic/time-spines/$CONNECTOR_ID")
            .header(HttpHeaders.AUTHORIZATION, BEARER)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"schemaVersion":1,"timeSpines":[]}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.violations[0].field").isEqualTo("definition")
        coVerify(exactly = 0) { timeSpines.putTimeSpineConfiguration(any()) }
    }

    @Test
    fun `time spine operational failures keep a retryable service problem without a field repair`() {
        coEvery { timeSpines.putTimeSpineConfiguration(any()) } throws
            ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
        client.put()
            .uri("/v2/workspaces/$WORKSPACE_ID/semantic/time-spines/$CONNECTOR_ID")
            .header(HttpHeaders.AUTHORIZATION, BEARER)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"schemaVersion":1,"timeSpines":[{"relation":{"schema":"analytics","relation":"calendar"},"primaryColumn":{"name":"day","granularity":"day"}}]}""")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")
            .jsonPath("$.retryable").isEqualTo(true)
            .jsonPath("$.violations").doesNotExist()
        coVerify(exactly = 1) { timeSpines.putTimeSpineConfiguration(any()) }
    }

    @Test
    fun `authorization denial is a forbidden problem`() {
        coEvery { authorizer.authorizeMutation(any(), WORKSPACE, any()) } throws
            ResponseStatusException(HttpStatus.FORBIDDEN, "Semantic authorization was rejected")

        write("/semantic/metrics", METRIC_WRITE)
            .expectStatus().isForbidden
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.code").isEqualTo("FORBIDDEN")
        coVerify(exactly = 0) { authority.putMetric(any()) }
    }

    @Test
    fun `definition check closes the identity snapshot before computing`() {
        coEvery { authorizer.authorizeExplorer(any(), WORKSPACE) } answers { assertTrue(snapshotActive.get()) }
        coEvery { checks.checkFromInputs(any()) } answers {
            assertFalse(snapshotActive.get())
            SemanticCheckResult.Checked(emptyList(), null, emptyList(), emptyList())
        }
        check("""{"kind":"METRIC","connectorId":"$CONNECTOR_ID",${METRIC_WRITE.removePrefix("{")}""")
            .expectStatus().isOk
        coVerifyOrder {
            authorizer.authorizeExplorer(any(), WORKSPACE)
            checks.readInputs(any())
            checks.checkFromInputs(any())
        }
        coVerify(exactly = 0) { checks.check(any()) }
    }

    @Test
    fun `check requires Explorer and returns the resolution as data`() {
        coEvery { checks.checkFromInputs(any()) } returns SemanticCheckResult.Checked(
            runsOn = listOf(CONNECTOR_ID),
            preview = SemanticCheckPreview(
                CONNECTOR_ID,
                SemanticSqlDialect.POSTGRESQL,
                "SELECT 1",
                listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.DAY)),
            ),
            problems = emptyList(),
            partitionFilters = emptyList(),
        )

        check("""{"kind":"METRIC","connectorId":"$CONNECTOR_ID",${METRIC_WRITE.removePrefix("{")}""")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.ok").isEqualTo(true)
            .jsonPath("$.runsOn[0]").isEqualTo(CONNECTOR_ID.value.toString())
            .jsonPath("$.preview.dialect").isEqualTo("POSTGRESQL")
            .jsonPath("$.preview.sql").isEqualTo("SELECT 1")
            .jsonPath("$.preview.groupBys[0].kind").isEqualTo("METRIC_TIME")
            .jsonPath("$.problems.length()").isEqualTo(0)

        coVerifyOrder {
            authorizer.authorizeExplorer(any(), WORKSPACE)
            checks.checkFromInputs(match { request ->
                val draft = request.request.draft as SemanticCheckDraft.Metric
                request.request.workspaceId == WORKSPACE && draft.connectorId == CONNECTOR_ID && draft.metricId == null
            })
        }
    }

    @Test
    fun `identifiers outside the id contract are bad requests before the owner is called`() {
        val versionFour = "123e4567-e89b-42d3-a456-426614174000"
        check("""{"kind":"METRIC","connectorId":"$versionFour",${METRIC_WRITE.removePrefix("{")}""")
            .expectStatus().isBadRequest
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
        for (path in listOf(
            "/v2/workspaces/$WORKSPACE_ID/semantic/time-spines/$versionFour",
            "/v2/workspaces/00000000-0000-0000-0000-000000000000/semantic/time-spines/$CONNECTOR_ID",
        )) {
            client.put()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"schemaVersion":1,"timeSpines":[{"relation":{"schema":"analytics","relation":"calendar"},"primaryColumn":{"name":"day","granularity":"DAY"}}]}""")
                .exchange()
                .expectStatus().isBadRequest
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
        }
        coVerify(exactly = 0) { checks.checkFromInputs(any()) }
    }

    @Test
    fun `check problems carry code field and English message`() {
        coEvery { checks.checkFromInputs(any()) } returns SemanticCheckResult.Checked(
            runsOn = emptyList(),
            preview = null,
            problems = listOf(
                SemanticCheckProblem(SemanticCheckProblemCode.METRIC_MODEL_NOT_FOUND, "semanticModelName", null, null, null),
                SemanticCheckProblem(SemanticCheckProblemCode.TIME_SPINE_UNAVAILABLE, null, CONNECTOR_ID, null, null),
                SemanticCheckProblem(SemanticCheckProblemCode.METRICFLOW_MANIFEST_INVALID, null, null, null, "Measure invoice_cnt is not defined."),
                SemanticCheckProblem(SemanticCheckProblemCode.METRICFLOW_MANIFEST_INVALID, null, null, SemanticDefinitionReference.SemanticModel(SemanticElementName("orders_copy")), "In semantic model `orders_copy`, type conflict for dimension `region`."),
            ),
            partitionFilters = emptyList(),
        )

        check("""{"kind":"SEMANTIC_MODEL",${MODEL_WRITE.replace("\"aggregateMetrics\": [], ", "").removePrefix("{")}""")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.ok").isEqualTo(false)
            .jsonPath("$.preview").isEmpty
            .jsonPath("$.problems[0].code").isEqualTo("METRIC_MODEL_NOT_FOUND")
            .jsonPath("$.problems[0].field").isEqualTo("semanticModelName")
            .jsonPath("$.problems[0].message").isEqualTo("This semantic model doesn't exist.")
            .jsonPath("$.problems[1].connectorId").isEqualTo(CONNECTOR_ID.value.toString())
            .jsonPath("$.problems[2].message").isEqualTo("Measure invoice_cnt is not defined.")
            .jsonPath("$.problems[2].definition").isEmpty
            .jsonPath("$.problems[3].definition.kind").isEqualTo("SEMANTIC_MODEL")
            .jsonPath("$.problems[3].definition.name").isEqualTo("orders_copy")
    }

    @Test
    fun `check denial and unknown resources are problem details`() {
        coEvery { authorizer.authorizeExplorer(any(), WORKSPACE) } throws
            ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer credential")
        check("""{"kind":"SEMANTIC_MODEL",${MODEL_WRITE.replace("\"aggregateMetrics\": [], ", "").removePrefix("{")}""")
            .expectStatus().isUnauthorized
            .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
        coVerify(exactly = 0) { checks.checkFromInputs(any()) }

        coEvery { authorizer.authorizeExplorer(any(), WORKSPACE) } returns Unit
        coEvery { checks.checkFromInputs(any()) } returns SemanticCheckResult.NotFoundOrOutsideWorkspace
        check("""{"kind":"METRIC","metricId":"019f4a78-afd2-7172-b806-0d9a98a59b97",${METRIC_WRITE.removePrefix("{")}""")
            .expectStatus().isNotFound
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.code").isEqualTo("NOT_FOUND")
    }

    @Test
    fun `OpenAPI declares the check operation and problem responses for Semantic writes`() {
        val openApi = ObjectMapper().readTree(
            Files.readString(
                Path.of(System.getProperty("control.service.root")).parent.resolve("contracts/control-service.openapi.json"),
            ),
        )
        val check = openApi.path("paths").path("/v2/workspaces/{workspaceId}/semantic/check").path("post")
        assertEquals("checkSemanticDefinition", check.path("operationId").stringValue())
        assertEquals(
            setOf(200, 400, 401, 403, 404, 422, 500),
            check.path("responses").properties().asSequence().map { it.key.toInt() }.toSet(),
        )
        listOf(
            "/v2/workspaces/{workspaceId}/semantic/metrics" to "post",
            "/v2/workspaces/{workspaceId}/semantic/metrics/{metricId}" to "put",
            "/v2/workspaces/{workspaceId}/semantic/semantic-models" to "post",
            "/v2/workspaces/{workspaceId}/semantic/semantic-models/{semanticModelId}" to "put",
            "/v2/workspaces/{workspaceId}/semantic/time-spines/{connectorId}" to "put",
        ).forEach { (path, method) ->
            val responses = openApi.path("paths").path(path).path(method).path("responses")
            assertTrue(responses.has("400") && responses.has("403"), "$method $path")
        }
    }

    private fun write(path: String, body: String): WebTestClient.ResponseSpec =
        client.post()
            .uri("/v2/workspaces/$WORKSPACE_ID$path")
            .header(HttpHeaders.AUTHORIZATION, BEARER)
            .header("Idempotency-Key", "semantic-problem-test")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()

    private fun check(body: String): WebTestClient.ResponseSpec =
        client.post()
            .uri("/v2/workspaces/$WORKSPACE_ID/semantic/check")
            .header(HttpHeaders.AUTHORIZATION, BEARER)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()

    private companion object {
        const val WORKSPACE_ID = "019f4a78-afd2-7172-b806-0d9a98a59b94"
        val WORKSPACE = WorkspaceId(Uuid.parse(WORKSPACE_ID))
        val CONNECTOR_ID = ConnectorId(Uuid.parse("019f4a78-afd2-7172-b806-0d9a98a59b95"))
        const val BEARER = "Bearer semantic-test-token"
        val MODEL_WRITE = """
            {"aggregateMetrics": [], "definition":{"schemaVersion":3,"name":"orders",
            "sourceRelations":[{"connectorId":"019f4a78-afd2-7172-b806-0d9a98a59b95","schema":"analytics","relation":"orders"}],
            "entities":[{"name":"order_id","type":"primary","expression":"order_id"}],
            "dimensions":[{"isPartition":false,"name":"ordered_at","type":"time","granularity":"hour","expression":"ordered_at"}],
            "measures":[{"name":"revenue","aggregation":"sum","expression":"amount"}]}}
        """.trimIndent().replace("\n", "")
        val METRIC_WRITE = """
            {"definition":{"schemaVersion":3,"name":"revenue","type":"simple","semanticModelName":"orders","measure":"revenue"}}
        """.trimIndent()
    }
}
