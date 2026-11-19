@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.semantic.inbound.rest

import cc.monomer.control.agent.FixedPrincipalDisplayNames
import cc.monomer.control.agentapp.FixedAgentAppDisplays
import cc.monomer.control.explore.ExploreRunAccessService
import cc.monomer.control.explore.ExploreSemanticLaunchService
import cc.monomer.control.explore.ExploreSqlLaunchService
import cc.monomer.control.explore.inbound.rest.ExploreProblemHandler
import cc.monomer.control.explore.inbound.rest.ExploreRunController
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.semantic.api.SemanticCheckInputs
import cc.monomer.semantic.api.SemanticCheckProblem
import cc.monomer.semantic.api.SemanticCheckProblemCode
import cc.monomer.semantic.api.SemanticCheckResult
import cc.monomer.semantic.api.SemanticDefinitionCheckUseCase
import cc.monomer.semantic.api.SemanticInvalidReasonCode
import cc.monomer.semantic.application.SemanticCurrentQueryInputs
import cc.monomer.semantic.application.SemanticGroupByInputs
import cc.monomer.semantic.application.SemanticMetricFlowEngineFaultException
import cc.monomer.semantic.application.SemanticMetricFlowInvalidQueryException
import cc.monomer.semantic.application.SemanticMetricFlowUnsupportedException
import cc.monomer.semantic.application.SemanticQueryUseCase
import cc.monomer.semantic.application.SemanticRenderInputs
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

class SemanticMetricFlowProblemHandlerTest {
    private val workspace = "019e0000-0000-7000-8000-0000000000a1"
    private val connector = "019e0000-0000-7000-8000-0000000000c1"
    private val queries = mockk<SemanticQueryUseCase>()
    private val checks = mockk<SemanticDefinitionCheckUseCase>()
    private val semanticLaunch = mockk<ExploreSemanticLaunchService>()
    private val authorizer = mockk<SemanticWorkspaceRequestAuthorizer>()
    private val snapshotActive = AtomicBoolean()
    private val authorized = AtomicBoolean()
    private val snapshots = object : ConsistentSnapshotTransactionRunner {
        override suspend fun <T> run(action: suspend () -> T): T {
            assertTrue(snapshotActive.compareAndSet(false, true))
            authorized.set(false)
            return try { action() } finally { snapshotActive.set(false) }
        }
    }
    private val client = WebTestClient.bindToController(
        SemanticQueryController(queries, authorizer, snapshots),
        SemanticCheckController(checks, authorizer, snapshots),
        ExploreRunController(
            mockk<ExploreSqlLaunchService>(), semanticLaunch, mockk<ExploreRunAccessService>(),
            ObjectMapper().registerKotlinModule(), FixedAgentAppDisplays, FixedPrincipalDisplayNames,
        ),
    ).controllerAdvice(
        SemanticQueryProblemHandler(), SemanticDefinitionProblemHandler(),
        ExploreProblemHandler("https://control.example.test"), SemanticMetricFlowProblemHandler(),
    ).build()

    init {
        coEvery { authorizer.authorizeExplorer(any(), WorkspaceId.parse(workspace)) } answers {
            assertTrue(snapshotActive.get())
            authorized.set(true)
        }
        val current = SemanticCurrentQueryInputs(false, null, null, emptyMap(), emptyMap(), emptyList(), null)
        coEvery { queries.readRenderInputs(any()) } answers {
            assertAuthorizedRead()
            SemanticRenderInputs(firstArg(), current)
        }
        coEvery { queries.readGroupByInputs(any()) } answers {
            assertAuthorizedRead()
            SemanticGroupByInputs(firstArg(), current)
        }
        coEvery { checks.readInputs(any()) } answers {
            assertAuthorizedRead()
            SemanticCheckInputs(firstArg(), emptyList(), emptyList(), emptyMap(), emptyMap(), emptyList(), java.time.ZoneId.of("UTC"))
        }
    }

    @Test
    fun `invalid engine input returns human 422 on render group by check and Explore routes`() {
        assertFailure(
            SemanticMetricFlowInvalidQueryException(
                SemanticInvalidReasonCode.QUERY_GROUP_BY_NOT_FOUND,
                IllegalArgumentException("private query diagnostic"),
            ),
            422, "INVALID_SEMANTIC_QUERY",
        )
    }

    @Test
    fun `unsupported engine input returns human 422 on render group by check and Explore routes`() {
        assertFailure(SemanticMetricFlowUnsupportedException(IllegalStateException("private SQL diagnostic")), 422, "UNSUPPORTED_SEMANTIC_QUERY")
    }

    @Test
    fun `template engine faults return human 500 on all routes without diagnostics or retry policy`() {
        assertFailure(SemanticMetricFlowEngineFaultException(IllegalStateException("private template diagnostic")), 500, "INTERNAL_ERROR")
    }

    @Test
    fun `internal engine faults return human 500 on all routes without diagnostics or retry policy`() {
        assertFailure(SemanticMetricFlowEngineFaultException(IllegalStateException("private internal diagnostic")), 500, "INTERNAL_ERROR")
    }

    @Test
    fun `ordinary Check findings remain a human 200 data response`() {
        coEvery { checks.checkFromInputs(any()) } answers {
            assertAuthorizedComputation()
            SemanticCheckResult.Checked(
                emptyList(), null,
                listOf(SemanticCheckProblem(SemanticCheckProblemCode.METRIC_MODEL_NOT_FOUND, "semanticModelName", null, null, null)),
                emptyList(),
            )
        }
        client.post().uri("/v2/workspaces/$workspace/semantic/check")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(checkBody()).exchange()
            .expectStatus().isOk.expectHeader().doesNotExist(HttpHeaders.RETRY_AFTER)
            .expectBody().jsonPath("$.ok").isEqualTo(false)
            .jsonPath("$.problems[0].code").isEqualTo("METRIC_MODEL_NOT_FOUND")
            .jsonPath("$.problems[0].field").isEqualTo("semanticModelName")
        coVerify(exactly = 1) { checks.readInputs(any()) }
        coVerify(exactly = 1) { checks.checkFromInputs(any()) }
        coVerify(exactly = 0) { checks.check(any()) }
    }

    private fun assertFailure(failure: RuntimeException, status: Int, code: String) {
        coEvery { queries.renderFromInputs(any()) } answers { assertAuthorizedComputation(); throw failure }
        coEvery { queries.listGroupBysFromInputs(any()) } answers { assertAuthorizedComputation(); throw failure }
        coEvery { checks.checkFromInputs(any()) } answers { assertAuthorizedComputation(); throw failure }
        coEvery { semanticLaunch.start(any()) } throws failure
        val requests = listOf(
            "/semantic/query/render-sql" to renderBody(),
            "/semantic/query/group-bys" to """{"connectorId":"$connector","metrics":["revenue"]}""",
            "/semantic/check" to checkBody(),
            "/explore/semantic-runs" to renderBody(),
        )
        val mapper = ObjectMapper()
        for ((route, body) in requests) {
            val response = client.post().uri("/v2/workspaces/$workspace$route")
                .header(HttpHeaders.AUTHORIZATION, "Bearer synthetic-test-token")
                .header("Idempotency-Key", "metricflow-problem")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
                .expectStatus().value { assertEquals(status, it) }
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().doesNotExist(HttpHeaders.RETRY_AFTER)
                .expectBody(String::class.java).returnResult()
            val responseBody = assertNotNull(response.responseBody)
            val problem = mapper.readTree(responseBody)
            assertEquals(status, problem.path("status").asInt(), route)
            assertEquals(code, problem.path("code").asText(), route)
            if (code == "INVALID_SEMANTIC_QUERY") {
                assertEquals(SemanticInvalidReasonCode.QUERY_GROUP_BY_NOT_FOUND.name, problem.path("detail").asText(), route)
            }
            assertFalse(problem.path("retryable").asBoolean(), route)
            assertTrue(problem.path("requestId").asText().isNotBlank(), route)
            assertEquals(problem.path("requestId").asText(), problem.path("traceId").asText(), route)
            assertFalse(responseBody.contains("private"), route)
            assertFalse(responseBody.contains("IllegalStateException"), route)
            assertFalse(responseBody.contains("IllegalArgumentException"), route)
            assertFalse(responseBody.contains("stackTrace"), route)
        }
        coVerify(exactly = 3) { authorizer.authorizeExplorer(any(), WorkspaceId.parse(workspace)) }
        coVerify(exactly = 1) { queries.readRenderInputs(any()) }
        coVerify(exactly = 1) { queries.readGroupByInputs(any()) }
        coVerify(exactly = 1) { checks.readInputs(any()) }
        coVerify(exactly = 1) { semanticLaunch.start(any()) }
        coVerify(exactly = 0) { queries.render(any()) }
        coVerify(exactly = 0) { queries.listGroupBys(any()) }
        coVerify(exactly = 0) { checks.check(any()) }
    }

    private fun assertAuthorizedRead() {
        assertTrue(authorized.get())
        assertTrue(snapshotActive.get())
    }

    private fun assertAuthorizedComputation() {
        assertTrue(authorized.get())
        assertFalse(snapshotActive.get())
    }

    private fun renderBody() = """{"connectorId":"$connector","query":{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":100}}"""
    private fun checkBody() = """{"kind":"METRIC","connectorId":"$connector","definition":{"schemaVersion":3,"name":"revenue","type":"simple","semanticModelName":"orders","measure":"revenue"}}"""
}
