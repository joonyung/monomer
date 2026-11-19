@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.explore.inbound.rest

import cc.monomer.connector.api.ConnectorId
import cc.monomer.control.explore.ExploreRunAccessService
import cc.monomer.control.explore.ExploreRunCancelOutcome
import cc.monomer.control.explore.ExploreRunArtifactReadOutcome
import cc.monomer.control.explore.ExploreRunListOutcome
import cc.monomer.control.explore.ExploreRunReadOutcome
import cc.monomer.control.explore.ExploreRunRecord
import cc.monomer.control.explore.ExploreRunResultRead
import cc.monomer.control.explore.ExploreSemanticLaunchOutcome
import cc.monomer.control.explore.ExploreSemanticLaunchService
import cc.monomer.control.explore.ExploreSqlLaunchOutcome
import cc.monomer.control.explore.ExploreSqlLaunchService
import cc.monomer.control.explore.LaunchSqlExploreRunCommand
import cc.monomer.control.explore.LaunchSemanticExploreRunCommand
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.ResultColumn
import cc.monomer.execution.api.ResultLogicalType
import cc.monomer.execution.api.ResultPreviewCell
import cc.monomer.execution.api.ResultPreviewStopReason
import cc.monomer.explore.api.ExploreConnectorBinding
import cc.monomer.explore.api.ExploreDigest
import cc.monomer.explore.api.ExploreResolvedResult
import cc.monomer.explore.api.ExploreResultArtifact
import cc.monomer.explore.api.ExploreRunId
import cc.monomer.explore.api.ExploreRunFailureCode
import cc.monomer.explore.api.ExploreRunState
import cc.monomer.explore.api.ExploreRunView
import cc.monomer.explore.api.ExploreRunPage
import cc.monomer.control.agent.FixedPrincipalDisplayNames
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticQuerySpec
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.ResponseStatusException

class ExploreRunControllerTest {
    private val sqlLaunchService = mockk<ExploreSqlLaunchService>()
    private val semanticLaunchService = mockk<ExploreSemanticLaunchService>()
    private val runAccessService = mockk<ExploreRunAccessService>()
    private val workspaceId = "019e0000-0000-7000-8000-0000000000a1"
    private val connectorId = "019e0000-0000-7000-8000-0000000000c1"
    private val runId = "019e0000-0000-7000-8000-0000000000e1"
    private val objectMapper = ObjectMapper().registerKotlinModule()
    init {
        coEvery { runAccessService.readRecordedSql(any()) } returns "SELECT 1"
        coEvery { runAccessService.readRecordedHistory(any()) } answers {
            firstArg<List<ExploreRunView>>().map { run -> ExploreRunRecord(run, "SELECT 1") }
        }
    }

    private val client = WebTestClient.bindToController(
        ExploreRunController(sqlLaunchService, semanticLaunchService, runAccessService, objectMapper, cc.monomer.control.agentapp.FixedAgentAppDisplays, FixedPrincipalDisplayNames),
    ).controllerAdvice(ExploreProblemHandler("https://control.example.test"))
        .build()

    @Test
    fun `SQL start returns the common Explore run location`() {
        val command = slot<LaunchSqlExploreRunCommand>()
        coEvery { sqlLaunchService.start(capture(command)) } returns
            ExploreSqlLaunchOutcome.Started(runView(), "SELECT 1")

        client.post().uri("/v2/workspaces/$workspaceId/explore/sql-runs")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .header("Idempotency-Key", "sql-1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"connectorId":"$connectorId","sql":"SELECT 1"}""")
            .exchange()
            .expectStatus().isAccepted
            .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "2")
            .expectHeader().valueEquals(
                HttpHeaders.LOCATION,
                "/v2/workspaces/$workspaceId/explore/runs/$runId",
            )
            .expectBody()
            .jsonPath("$.kind").isEqualTo("SQL")
            .jsonPath("$.runId").isEqualTo(runId)
            .jsonPath("$.sql").isEqualTo("SELECT 1")

        assertEquals("SELECT 1", command.captured.sql)
        assertEquals("sql-1", command.captured.idempotencyKey)
        coVerify(exactly = 0) { runAccessService.readRecordedSql(any()) }
    }

    @Test
    fun `timestamp precision is rejected before creating a Semantic run`() {
        val message = "Timestamp filter values must have at most 6 fractional second digits."
        for (fraction in listOf("1234567", "12345678", "123456789", "1234567890", "1234560", "0000000")) {
            val query = """{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,
                "filters":[{"kind":"TIMESTAMP","dimension":{"semanticModelName":"orders","name":"ordered_at"},
                "operator":"GREATER_THAN_OR_EQUAL","values":["2026-10-01T00:00:00.$fraction+09:00"]}],"orderBy":[],"rowLimit":100}"""
            client.post().uri("/v2/workspaces/$workspaceId/explore/semantic-runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .header("Idempotency-Key", "timestamp-precision-$fraction")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"connectorId":"$connectorId","query":$query}""")
                .exchange().expectStatus().isBadRequest
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST")
                .jsonPath("$.detail").isEqualTo(message)
                .jsonPath("$.violations[0].field").isEqualTo("query.filters[0].values[0]")
                .jsonPath("$.violations[0].code").isEqualTo("SCHEMA_MISMATCH")
                .jsonPath("$.violations[0].message").isEqualTo(message)
        }
        coVerify(exactly = 0) { semanticLaunchService.start(any()) }
    }

    @Test
    fun `timestamp zero through six fractional digits still start with an exact query`() {
        val command = slot<LaunchSemanticExploreRunCommand>()
        coEvery { semanticLaunchService.start(capture(command)) } returns
            ExploreSemanticLaunchOutcome.Started(semanticRunView(ExploreRunState.RUNNING), "SELECT rendered")
        for (fraction in listOf("", ".1", ".12", ".123", ".1234", ".12345", ".123456", ".000000")) {
            val raw = "2026-10-01T00:00:00$fraction+09:00"
            val query = """{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,
                "filters":[{"kind":"TIMESTAMP","dimension":{"semanticModelName":"orders","name":"ordered_at"},
                "operator":"GREATER_THAN_OR_EQUAL","values":["$raw"]}],"orderBy":[],"rowLimit":100}"""
            client.post().uri("/v2/workspaces/$workspaceId/explore/semantic-runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .header("Idempotency-Key", "timestamp-accepted-$fraction")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"connectorId":"$connectorId","query":$query}""")
                .exchange().expectStatus().isAccepted
            assertEquals(listOf(Instant.parse(raw)), kotlin.test.assertIs<cc.monomer.semantic.api.SemanticQueryFilter.Timestamp>(command.captured.query.filters.single()).values)
        }
    }

    @Test
    fun `Semantic start fingerprints the canonical query transport`() {
        val command = slot<LaunchSemanticExploreRunCommand>()
        coEvery { semanticLaunchService.start(capture(command)) } returns
            ExploreSemanticLaunchOutcome.Conflict
        val metricName = "revenue"

        client.post().uri("/v2/workspaces/$workspaceId/explore/semantic-runs")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .header("Idempotency-Key", "semantic-1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """
                {
                  "connectorId":"$connectorId",
                  "query":{
                    "rowLimit":100,
                    "orderBy":[],
                    "filters":[],
                    "metricTimeRange":null,
                    "groupBys":[],
                    "metrics":["$metricName"],
                    "schemaVersion":4
                  }
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isEqualTo(409)

        assertEquals(
            "sha256:8c536aa938629c040a920f7a02b39a675e07188db208c669d1139299cabf692f",
            command.captured.requestDigest.value,
        )
    }

    @Test
    fun `recent list returns current actor runs without result previews`() {
        coEvery { runAccessService.listRuns(any()) } returns
            ExploreRunListOutcome.Observed(ExploreRunPage(listOf(runView(), runView(credentialSurface = CredentialSurface.AGENT), runView(credentialSurface = null)), null))

        client.get().uri("/v2/workspaces/$workspaceId/explore/runs")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.runs.length()").isEqualTo(3)
            .jsonPath("$.runs[0].runId").isEqualTo(runId)
            .jsonPath("$.runs[0].result").doesNotExist()
            .jsonPath("$.runs[0].credentialSurface").isEqualTo("WEB")
            .jsonPath("$.runs[1].credentialSurface").isEqualTo("AGENT")
            .jsonPath("$.runs[2].credentialSurface").doesNotExist()
            .jsonPath("$.runs[0].agentApp").doesNotExist()
            .jsonPath("$.runs[1].agentApp.name").isEqualTo("Claude")
            .jsonPath("$.runs[1].agentApp.verifiedHost").isEqualTo("claude.ai")
            .jsonPath("$.runs[1].agentApp.clientId").isEqualTo("https://claude.ai/oauth/mcp-oauth-client-metadata")
            .jsonPath("$.runs[0].sql").isEqualTo("SELECT 1")
            .jsonPath("$.runs[0].startedBy.id").isEqualTo(FixedPrincipalDisplayNames.MINA.toString())
            .jsonPath("$.runs[0].startedBy.displayName").isEqualTo("Mina Park")
            .jsonPath("$.runs[1].startedBy.displayName").isEqualTo("Mina Park")

        coVerify(exactly = 1) { runAccessService.listRuns(any()) }
        coVerify(exactly = 1) { runAccessService.readRecordedHistory(match { it.size == 3 }) }
        coVerify(exactly = 0) { runAccessService.readRecordedSql(any()) }
    }

    @Test
    fun `recent list keeps runs with recorded SQL when another run's history is unavailable`() {
        val kept = runView()
        coEvery { runAccessService.listRuns(any()) } returns
            ExploreRunListOutcome.Observed(ExploreRunPage(listOf(kept, runView(credentialSurface = null)), null))
        coEvery { runAccessService.readRecordedHistory(any()) } returns listOf(ExploreRunRecord(kept, "SELECT 2"))

        client.get().uri("/v2/workspaces/$workspaceId/explore/runs")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.runs.length()").isEqualTo(1)
            .jsonPath("$.runs[0].sql").isEqualTo("SELECT 2")
    }

    @Test
    fun `result download streams the original Arrow artifact as an attachment`() {
        val bytes = "arrow".toByteArray()
        coEvery { runAccessService.readArtifact(any()) } returns
            ExploreRunArtifactReadOutcome.Visible(
                ExploreResultArtifact(
                    contentLength = bytes.size.toLong(),
                    mediaType = "application/vnd.apache.arrow.file",
                    content = flowOf(ByteBuffer.wrap(bytes)),
                ),
            )

        client.get().uri("/v2/workspaces/$workspaceId/explore/runs/$runId/result")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType("application/vnd.apache.arrow.file")
            .expectHeader().valueEquals(HttpHeaders.CONTENT_LENGTH, bytes.size.toString())
            .expectHeader().valueMatches(HttpHeaders.CONTENT_DISPOSITION, "attachment;.*explore-$runId\\.arrow.*")
            .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
            .expectHeader().valueEquals("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; sandbox")
            .expectBody(ByteArray::class.java).isEqualTo(bytes)

        coVerify(exactly = 1) { runAccessService.readArtifact(any()) }
    }

    @Test
    fun `CSV streams without a false source length and fixes external audience`() {
        val bytes = "\uFEFFname\r\nvalue\r\n".toByteArray()
        coEvery { runAccessService.readCsv(any()) } returns
            cc.monomer.control.explore.ExploreRunCsvReadOutcome.Visible(
                cc.monomer.explore.api.ExploreResultCsv(flowOf(ByteBuffer.wrap(bytes))),
            )
        client.get().uri("/v2/workspaces/$workspaceId/agent/explore/runs/$runId/result?format=csv")
            .header(HttpHeaders.AUTHORIZATION, "Bearer opaque-agent-token")
            .exchange().expectStatus().isOk
            .expectHeader().contentType("text/csv;charset=utf-8")
            .expectHeader().doesNotExist(HttpHeaders.CONTENT_LENGTH)
            .expectHeader().valueMatches(HttpHeaders.CONTENT_DISPOSITION, "attachment;.*explore-$runId\\.csv.*")
            .expectBody(ByteArray::class.java).isEqualTo(bytes)
        coVerify { runAccessService.readCsv(match {
            it.expectedOAuthResource == cc.monomer.identity.api.ExpectedOAuthResource.AGENT_REST &&
                it.credential.value == "opaque-agent-token"
        }) }
        coVerify(exactly = 0) { runAccessService.readArtifact(any()) }
    }

    @Test
    fun `unknown download format is rejected before reading artifact`() {
        client.get().uri("/v2/workspaces/$workspaceId/explore/runs/$runId/result?format=xlsx")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange().expectStatus().isBadRequest
        coVerify(exactly = 0) { runAccessService.readArtifact(any()) }
        coVerify(exactly = 0) { runAccessService.readCsv(any()) }
    }

    @Test
    fun `external download requires authentication and advertises REST resource metadata`() {
        client.get().uri("/v2/workspaces/$workspaceId/agent/explore/runs/$runId/result?format=csv")
            .exchange().expectStatus().isUnauthorized
            .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE,
                "Bearer resource_metadata=\"https://control.example.test/.well-known/oauth-protected-resource/v2/workspaces\", scope=\"agent.workspace\"")
        coVerify(exactly = 0) { runAccessService.readCsv(any()) }
    }

    @Test
    fun `common read returns a visible SQL result`() {
        coEvery { runAccessService.read(any()) } returns
            ExploreRunReadOutcome.Observed(
                run = runView(state = ExploreRunState.SUCCEEDED, resultAvailable = true),
                result = ExploreRunResultRead.Available(visibleResult()),
            )

        client.get().uri("/v2/workspaces/$workspaceId/explore/runs/$runId")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.state").isEqualTo("SUCCEEDED")
            .jsonPath("$.result.columns[0].name").isEqualTo("value")
            .jsonPath("$.result.rows[0][0]").isEqualTo("1")
            .jsonPath("$.result.columns[1].logicalType").isEqualTo("DATETIME")
            .jsonPath("$.result.rows[0][1]").isEqualTo("2024-02-29T12:34:56.123456")
            .jsonPath("$.result.rows[0][2]").isEmpty()
    }

    @Test
    fun `common read exposes safe terminal failure detail`() {
        val diagnostic = "BigQuery: Cannot query table 'qa.orders' without a filter over column 'order_date' for partition elimination"
        coEvery { runAccessService.read(any()) } returns
            ExploreRunReadOutcome.Observed(
                run = runView(
                    state = ExploreRunState.FAILED,
                    failureCode = ExploreRunFailureCode.EXECUTION_FAILED,
                    failureDetail = diagnostic,
                ),
                result = ExploreRunResultRead.None,
            )

        client.get().uri("/v2/workspaces/$workspaceId/explore/runs/$runId")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.state").isEqualTo("FAILED")
            .jsonPath("$.failureCode").isEqualTo("EXECUTION_FAILED")
            .jsonPath("$.failureDetail").isEqualTo(diagnostic)
            .jsonPath("$.result").doesNotExist()
    }

    @Test
    fun `accepted SQL warehouse failures expose their classified code and safe detail`() {
        listOf(
            ExploreRunFailureCode.PERMISSION_DENIED to "permission denied for schema private_data",
            ExploreRunFailureCode.CONNECTION_FAILED to "Connection to 127.0.0.1:15432 refused.",
            ExploreRunFailureCode.AUTHENTICATION_FAILED to "password authentication failed for user reader",
        ).forEach { (failureCode, failureDetail) ->
            coEvery { runAccessService.read(any()) } returns ExploreRunReadOutcome.Observed(
                runView(state = ExploreRunState.FAILED, failureCode = failureCode, failureDetail = failureDetail),
                ExploreRunResultRead.None,
            )
            client.get().uri("/v2/workspaces/$workspaceId/explore/runs/$runId")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .exchange().expectStatus().isOk.expectBody()
                .jsonPath("$.failureCode").isEqualTo(failureCode.name)
                .jsonPath("$.failureDetail").isEqualTo(failureDetail)
        }
    }

    @Test
    fun `common read returns the original Semantic query intent and its recorded SQL`() {
        val metricName = "revenue"
        val renderedSql = "SELECT SUM(amount) AS revenue FROM billing.invoices"
        coEvery { runAccessService.readRecordedSql(any()) } returns renderedSql
        coEvery { runAccessService.read(any()) } returns
            ExploreRunReadOutcome.Observed(
                run = ExploreRunView(
                    id = ExploreRunId(Uuid.parse(runId)),
                    kind = ExploreRunView.Kind.Semantic(query = SemanticQuerySpec(
                        listOf(SemanticElementName(metricName)),
                        emptyList(),
                        null,
                        emptyList(),
                        emptyList(),
                        100,
                    ), reportingTimeZone = java.time.ZoneId.of("Asia/Seoul")),
                    workspaceId = WorkspaceId.parse(workspaceId),
                    connectorBinding = ExploreConnectorBinding(
                        WorkspaceId.parse(workspaceId),
                        ConnectorId(Uuid.parse(connectorId)),
                        ConnectorType.POSTGRESQL,
                    ),
                    requestDigest = ExploreDigest("sha256:${"1".repeat(64)}"),
                    state = ExploreRunState.RUNNING,
                    revision = 0,
                    resultAvailable = false,
                    failureCode = null,
                    failureDetail = null,
                    createdAt = Instant.parse("2026-08-20T00:00:00Z"),
                    updatedAt = Instant.parse("2026-08-20T00:00:00Z"),
                    resultMetadata = null,
                    ownerPrincipalId = PrincipalId(FixedPrincipalDisplayNames.MINA),
                    credentialSurface = CredentialSurface.AGENT,
                    agentClientId = cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"),
                ),
                result = ExploreRunResultRead.None,
            )

        val response = client.get().uri("/v2/workspaces/$workspaceId/explore/runs/$runId")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .returnResult()
        val body = ObjectMapper().readTree(requireNotNull(response.responseBody))
        assertEquals("SEMANTIC", body.path("kind").asText())
        assertEquals(renderedSql, body.path("sql").asText())
        assertEquals(4, body.path("query").path("schemaVersion").asInt(), body.toString())
        assertEquals(metricName, body.path("query").path("metrics").get(0).asText())
        assertEquals(100, body.path("query").path("rowLimit").asInt())
        assertEquals("Asia/Seoul", body.path("reportingTimeZone").asText())
        assertEquals("AGENT", body.path("credentialSurface").asText())
        assertEquals("Claude", body.path("agentApp").path("name").asText())
        assertEquals("Mina Park", body.path("startedBy").path("displayName").asText())
    }

    @Test
    fun `run views name the person who started the run`() {
        val teammate = PrincipalId.generate()
        coEvery { runAccessService.read(any()) } returns
            ExploreRunReadOutcome.Observed(runView(credentialSurface = CredentialSurface.AGENT), ExploreRunResultRead.None)
        client.get().uri("/v2/workspaces/$workspaceId/explore/runs/$runId")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.startedBy.id").isEqualTo(FixedPrincipalDisplayNames.MINA.toString())
            .jsonPath("$.startedBy.displayName").isEqualTo("Mina Park")
            .jsonPath("$.agentApp.name").isEqualTo("Claude")

        coEvery { runAccessService.read(any()) } returns
            ExploreRunReadOutcome.Observed(runView(ownerPrincipalId = teammate), ExploreRunResultRead.None)
        val unnamed = client.get().uri("/v2/workspaces/$workspaceId/explore/runs/$runId")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .returnResult()
        val startedBy = ObjectMapper().readTree(requireNotNull(unnamed.responseBody)).path("startedBy")
        assertEquals(teammate.toString(), startedBy.path("id").asText())
        assertEquals(true, startedBy.has("displayName") && startedBy.path("displayName").isNull, startedBy.toString())

        coEvery { sqlLaunchService.start(any()) } returns ExploreSqlLaunchOutcome.Started(runView(), "SELECT 1")
        client.post().uri("/v2/workspaces/$workspaceId/explore/sql-runs")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .header("Idempotency-Key", "sql-started-by")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"connectorId":"$connectorId","sql":"SELECT 1"}""")
            .exchange()
            .expectStatus().isAccepted
            .expectBody()
            .jsonPath("$.startedBy.displayName").isEqualTo("Mina Park")
    }

    @Test
    fun `SQL run views carry a null Reporting time zone and OpenAPI requires the nullable field`() {
        coEvery { runAccessService.read(any()) } returns ExploreRunReadOutcome.Observed(runView(), ExploreRunResultRead.None)
        val response = client.get().uri("/v2/workspaces/$workspaceId/explore/runs/$runId")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .returnResult()
        val body = ObjectMapper().readTree(requireNotNull(response.responseBody))
        assertEquals(true, body.has("reportingTimeZone") && body.path("reportingTimeZone").isNull, body.toString())
        val openApi = ObjectMapper().readTree(
            Files.readString(Path.of(System.getProperty("control.service.root")).parent.resolve("contracts/control-service.openapi.json")),
        )
        for (name in listOf("ExploreRunView", "ExploreRunDetailView")) {
            val schema = openApi.path("components").path("schemas").path(name)
            assertEquals(true, schema.path("required").any { it.asText() == "reportingTimeZone" }, name)
            assertEquals(
                listOf("string", "null"),
                schema.path("properties").path("reportingTimeZone").path("oneOf").map { it.path("type").asText() },
                name,
            )
        }
    }

    @Test
    fun `OpenAPI requires the person on every run view`() {
        val openApi = ObjectMapper().readTree(
            Files.readString(
                Path.of(System.getProperty("control.service.root"))
                    .parent
                    .resolve("contracts/control-service.openapi.json"),
            ),
        )
        for (name in listOf("ExploreRunView", "ExploreRunDetailView")) {
            val schema = openApi.path("components").path("schemas").path(name)
            assertEquals(true, schema.path("required").any { it.asText() == "startedBy" }, name)
            assertEquals("#/components/schemas/RecordedActor", schema.path("properties").path("startedBy").path("\$ref").asText(), name)
        }
    }

    @Test
    fun `run view fails explicitly when recorded SQL history is unavailable`() {
        coEvery { runAccessService.read(any()) } returns ExploreRunReadOutcome.Observed(runView(), ExploreRunResultRead.None)
        coEvery { runAccessService.readRecordedSql(any()) } returns null

        client.get().uri("/v2/workspaces/$workspaceId/explore/runs/$runId")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")
            .jsonPath("$.retryable").isEqualTo(true)
    }

    @Test
    fun `OpenAPI run views accept every Explore run failure code`() {
        val openApi = ObjectMapper().readTree(
            Files.readString(
                Path.of(System.getProperty("control.service.root"))
                    .parent
                    .resolve("contracts/control-service.openapi.json"),
            ),
        )
        for (name in listOf("ExploreRunView", "ExploreRunDetailView")) {
            val codeBranch = openApi.path("components").path("schemas").path(name)
                .path("properties").path("failureCode").path("oneOf")
                .single { it.path("type").asText() == "string" }
            val enumerated = codeBranch.path("enum").map { it.asText() }.toSet()
            ExploreRunFailureCode.entries.forEach { code ->
                assertTrue(code.name.length >= codeBranch.path("minLength").asInt(), "$name ${code.name}")
                assertTrue(enumerated.isEmpty() || code.name in enumerated, "$name ${code.name}")
            }
        }
    }

    @Test
    fun `OpenAPI requires the recorded SQL on every run view`() {
        val openApi = ObjectMapper().readTree(
            Files.readString(
                Path.of(System.getProperty("control.service.root"))
                    .parent
                    .resolve("contracts/control-service.openapi.json"),
            ),
        )
        val schema = openApi.path("components").path("schemas").path("ExploreRunView")

        assertEquals(true, schema.path("required").any { it.asText() == "sql" })
        assertEquals("string", schema.path("properties").path("sql").path("type").asText())
        assertEquals(1, schema.path("properties").path("sql").path("minLength").asInt())
    }

    @Test
    fun `Semantic start names a missing top-level Metric in a 404 violation`() {
        coEvery { semanticLaunchService.start(any()) } returns ExploreSemanticLaunchOutcome.MetricNotFound(SemanticElementName("revenue"))
        client.post().uri("/v2/workspaces/$workspaceId/explore/semantic-runs")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .header("Idempotency-Key", "missing-metric")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"connectorId":"$connectorId","query":{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":100}}""")
            .exchange()
            .expectStatus().isNotFound
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.code").isEqualTo("NOT_FOUND")
            .jsonPath("$.detail").isEqualTo("Metric `revenue` was not found. It may have been deleted.")
            .jsonPath("$.violations.length()").isEqualTo(1)
            .jsonPath("$.violations[0].field").isEqualTo("workspace/metrics/revenue")
            .jsonPath("$.violations[0].code").isEqualTo("METRIC_NOT_FOUND")
            .jsonPath("$.violations[0].message").isEqualTo("Metric `revenue` was not found. It may have been deleted.")
        coVerify(exactly = 0) { runAccessService.readRecordedSql(any()) }
    }

    @Test
    fun `Semantic start answers with the SQL its admission rendered without reading history`() {
        coEvery { semanticLaunchService.start(any()) } returns
            ExploreSemanticLaunchOutcome.Started(semanticRunView(ExploreRunState.RUNNING), "SELECT rendered")

        client.post().uri("/v2/workspaces/$workspaceId/explore/semantic-runs")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .header("Idempotency-Key", "semantic-2")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"connectorId":"$connectorId","query":{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":100}}""",
            )
            .exchange()
            .expectStatus().isAccepted
            .expectBody()
            .jsonPath("$.sql").isEqualTo("SELECT rendered")
            .jsonPath("$.result").doesNotExist()
            .jsonPath("$.resultState").doesNotExist()

        coVerify(exactly = 0) { runAccessService.readRecordedSql(any()) }
    }

    @Test
    fun `invalid Semantic start keeps the reason detail and names broken definitions`() {
        coEvery { semanticLaunchService.start(any()) } returns ExploreSemanticLaunchOutcome.Invalid(
            listOf(cc.monomer.semantic.api.SemanticInvalidReason(cc.monomer.semantic.api.SemanticInvalidReasonCode.CATALOG_RELATION_NOT_FOUND, null)),
            listOf(
                cc.monomer.semantic.api.SemanticDefinitionIssue(
                    cc.monomer.semantic.api.SemanticInvalidReasonCode.CATALOG_RELATION_NOT_FOUND,
                    cc.monomer.semantic.api.SemanticDefinitionReference.SemanticModel(cc.monomer.semantic.api.SemanticElementName("orders")),
                    "Semantic model `orders` reads analytics.orders, which isn't loaded in this connector's Catalog.",
                ),
            ),
        )

        client.post().uri("/v2/workspaces/$workspaceId/explore/semantic-runs")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .header("Idempotency-Key", "semantic-invalid")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"connectorId":"$connectorId","query":{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":100}}""",
            )
            .exchange()
            .expectStatus().isEqualTo(422)
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_SEMANTIC_QUERY")
            .jsonPath("$.detail").isEqualTo("CATALOG_RELATION_NOT_FOUND")
            .jsonPath("$.violations.length()").isEqualTo(1)
            .jsonPath("$.violations[0].field").isEqualTo("workspace/semantic-models/orders")
            .jsonPath("$.violations[0].code").isEqualTo("CATALOG_RELATION_NOT_FOUND")
            .jsonPath("$.violations[0].message")
            .isEqualTo("Semantic model `orders` reads analytics.orders, which isn't loaded in this connector's Catalog.")
    }

    @Test
    fun `Catalog comparison type mismatch rejects Human start with a filter kind violation`() {
        coEvery { semanticLaunchService.start(any()) } returns ExploreSemanticLaunchOutcome.Invalid(
            listOf(cc.monomer.semantic.api.SemanticInvalidReason(
                cc.monomer.semantic.api.SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE, "filters[0].kind",
            )),
            emptyList(),
        )
        client.post().uri("/v2/workspaces/$workspaceId/explore/semantic-runs")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .header("Idempotency-Key", "semantic-filter-type")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"connectorId":"$connectorId","query":{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[{"kind":"TEXT","dimension":{"semanticModelName":"orders","name":"amount"},"operator":"EQUALS","values":["1"]}],"orderBy":[],"rowLimit":100}}""")
            .exchange().expectStatus().isEqualTo(422)
            .expectHeader().doesNotExist("Retry-After")
            .expectBody().jsonPath("$.code").isEqualTo("INVALID_SEMANTIC_QUERY")
            .jsonPath("$.retryable").isEqualTo(false)
            .jsonPath("$.violations.length()").isEqualTo(1)
            .jsonPath("$.violations[0].field").isEqualTo("query.filters[0].kind")
            .jsonPath("$.violations[0].code").isEqualTo("QUERY_FILTER_UNRENDERABLE")
            .jsonPath("$.violations[0].message").isEqualTo("Filter comparison type must match the column's Catalog type; time dimensions use DATE at day or coarser grain and TIMESTAMP at finer grain.")
        coVerify(exactly = 1) { semanticLaunchService.start(any()) }
        coVerify(exactly = 0) { sqlLaunchService.start(any()) }
    }

    @Test
    fun `exact read keeps an expired or unpreviewable run readable with an explicit result state`() {
        for ((resultRead, state) in listOf(
            ExploreRunResultRead.Expired to "EXPIRED",
            ExploreRunResultRead.PreviewUnavailable to "PREVIEW_UNAVAILABLE",
            ExploreRunResultRead.None to "NONE",
        )) {
            coEvery { runAccessService.read(any()) } returns
                ExploreRunReadOutcome.Observed(semanticRunView(ExploreRunState.SUCCEEDED), resultRead)

            val response = client.get().uri("/v2/workspaces/$workspaceId/explore/runs/$runId")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .exchange()
                .expectStatus().isOk
                .expectBody()
                .returnResult()
            val body = ObjectMapper().readTree(requireNotNull(response.responseBody))
            assertEquals(state, body.path("resultState").asText())
            assertEquals(true, body.has("result") && body.path("result").isNull, body.toString())
            assertEquals("SELECT 1", body.path("sql").asText())
            assertEquals("revenue", body.path("query").path("metrics").get(0).asText())
        }
        coEvery { runAccessService.read(any()) } returns ExploreRunReadOutcome.Observed(
            runView(state = ExploreRunState.SUCCEEDED, resultAvailable = true),
            ExploreRunResultRead.Available(visibleResult()),
        )
        client.get().uri("/v2/workspaces/$workspaceId/explore/runs/$runId")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.resultState").isEqualTo("AVAILABLE")
            .jsonPath("$.result.rows[0][0]").isEqualTo("1")
    }

    @Test
    fun `OpenAPI gives the exact read its own view with a required result state`() {
        val openApi = ObjectMapper().readTree(
            Files.readString(
                Path.of(System.getProperty("control.service.root"))
                    .parent
                    .resolve("contracts/control-service.openapi.json"),
            ),
        )
        val schemas = openApi.path("components").path("schemas")
        assertEquals(false, schemas.path("ExploreRunView").path("properties").has("result"))
        val detail = schemas.path("ExploreRunDetailView")
        assertEquals(true, detail.path("required").any { it.asText() == "resultState" })
        assertEquals(
            listOf("AVAILABLE", "EXPIRED", "PREVIEW_UNAVAILABLE", "NONE"),
            detail.path("properties").path("resultState").path("enum").map { it.asText() },
        )
        val read = openApi.path("paths").path("/v2/workspaces/{workspaceId}/explore/runs/{runId}").path("get").path("responses")
        assertEquals("#/components/responses/ExploreRunDetailResponse", read.path("200").path("\$ref").asText())
        assertEquals(false, read.has("410"))
        assertEquals(false, read.has("409"))
    }

    @Test
    fun `Semantic start and cancel answer unavailable history and execution with a declared 503`() {
        coEvery { semanticLaunchService.start(any()) } returns ExploreSemanticLaunchOutcome.ExecutionUnavailable("CONNECTION_FAILED")
        client.post().uri("/v2/workspaces/$workspaceId/explore/semantic-runs")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .header("Idempotency-Key", "semantic-unavailable")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"connectorId":"$connectorId","query":{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":10}}""")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "2")
            .expectBody()
            .jsonPath("$.code").isEqualTo("CONNECTION_FAILED")
        coEvery { runAccessService.cancel(any()) } returns ExploreRunCancelOutcome.Accepted(runView())
        coEvery { runAccessService.readRecordedSql(any()) } returns null
        client.post().uri("/v2/workspaces/$workspaceId/explore/runs/$runId:cancel")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "2")
            .expectBody()
            .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")

        val paths = ObjectMapper().readTree(
            Files.readString(
                Path.of(System.getProperty("control.service.root")).parent.resolve("contracts/control-service.openapi.json"),
            ),
        ).path("paths")
        for ((path, method) in listOf(
            "/v2/workspaces/{workspaceId}/explore/semantic-runs" to "post",
            "/v2/workspaces/{workspaceId}/explore/runs/{runId}:cancel" to "post",
        )) {
            assertEquals(
                "#/components/responses/ServiceUnavailable",
                paths.path(path).path(method).path("responses").path("503").path("\$ref").asText(),
                path,
            )
        }
    }

    @Test
    fun `common cancel delegates membership and owner enforcement to access service`() {
        coEvery { runAccessService.cancel(any()) } returns
            ExploreRunCancelOutcome.Accepted(runView())

        client.post().uri("/v2/workspaces/$workspaceId/explore/runs/$runId:cancel")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isAccepted
            .expectHeader().valueEquals(
                HttpHeaders.LOCATION,
                "/v2/workspaces/$workspaceId/explore/runs/$runId",
            )

        coVerify(exactly = 1) { runAccessService.cancel(any()) }
    }

    @Test
    fun `start without bearer credential is rejected before launch`() {
        client.post().uri("/v2/workspaces/$workspaceId/explore/sql-runs")
            .header("Idempotency-Key", "sql-1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"connectorId":"$connectorId","sql":"SELECT 1"}""")
            .exchange()
            .expectStatus().isUnauthorized
            .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED")
            .jsonPath("$.retryable").isEqualTo(false)

        coVerify(exactly = 0) { sqlLaunchService.start(any()) }
    }

    @Test
    fun `invalid SQL request returns canonical problem details`() {
        client.post().uri("/v2/workspaces/$workspaceId/explore/sql-runs")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .header("Idempotency-Key", "sql-invalid")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"connectorId":"$connectorId","sql":"  "}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.type").isEqualTo("https://monomer.cc/problems/invalid-request")
            .jsonPath("$.title").isEqualTo("Bad Request")
            .jsonPath("$.status").isEqualTo(400)
            .jsonPath("$.detail").isEqualTo("sql is required")
            .jsonPath("$.instance").isEqualTo("/v2/workspaces/$workspaceId/explore/sql-runs")
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.traceId").isNotEmpty
            .jsonPath("$.retryable").isEqualTo(false)
    }

    @Test
    fun `malformed SQL request body returns canonical problem details`() {
        client.post().uri("/v2/workspaces/$workspaceId/explore/sql-runs")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .header("Idempotency-Key", "sql-malformed")
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
    fun `missing SQL request body returns canonical problem details`() {
        client.post().uri("/v2/workspaces/$workspaceId/explore/sql-runs")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .header("Idempotency-Key", "sql-missing")
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
    fun `forbidden read returns canonical problem details`() {
        coEvery { runAccessService.read(any()) } returns ExploreRunReadOutcome.AccessDenied

        client.get().uri("/v2/workspaces/$workspaceId/explore/runs/$runId")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isForbidden
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.status").isEqualTo(403)
            .jsonPath("$.code").isEqualTo("FORBIDDEN")
            .jsonPath("$.detail").isEqualTo("Explore result is not authorized.")
            .jsonPath("$.requestId").isNotEmpty
            .jsonPath("$.retryable").isEqualTo(false)
    }

    @Test
    fun `temporary SQL admission failure marks problem retryable`() {
        coEvery { sqlLaunchService.start(any()) } returns ExploreSqlLaunchOutcome.ExecutionUnavailable("EXECUTION_UNAVAILABLE")

        client.post().uri("/v2/workspaces/$workspaceId/explore/sql-runs")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .header("Idempotency-Key", "sql-unavailable")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"connectorId":"$connectorId","sql":"SELECT 1"}""")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "2")
            .expectBody()
            .jsonPath("$.status").isEqualTo(503)
            .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")
            .jsonPath("$.detail").isEqualTo("Explore SQL execution is temporarily unavailable.")
            .jsonPath("$.retryable").isEqualTo(true)
    }

    @Test
    fun `SQL failures report status retry policy and safe rejection diagnostics`() {
        val failures = listOf(
            ExploreSqlLaunchOutcome.SqlValidationFailed("RAW_SQL_VALIDATION_REJECTED", "SQL has no output columns."),
            ExploreSqlLaunchOutcome.SqlValidationFailed("PERMISSION_DENIED", "The Connector's warehouse account is not allowed to run this SQL."),
            ExploreSqlLaunchOutcome.SqlValidationFailed("READ_ONLY_VIOLATION", "The warehouse transaction is read-only."),
            ExploreSqlLaunchOutcome.ExecutionUnavailable("EXECUTION_UNAVAILABLE"),
            ExploreSqlLaunchOutcome.ExecutionUnavailable("RESOURCE_EXHAUSTED"),
            ExploreSqlLaunchOutcome.ExecutionUnavailable("CREDENTIAL_UNAVAILABLE"),
            ExploreSqlLaunchOutcome.ExecutionUnavailable("CONNECTION_FAILED"),
            ExploreSqlLaunchOutcome.ExecutionUnavailable("AUTHENTICATION_FAILED"),
            ExploreSqlLaunchOutcome.ExecutionUnavailable("EXECUTION_BACKPRESSURED"),
            ExploreSqlLaunchOutcome.ExecutionUnavailable("RAW_SQL_VALIDATION_DEADLINE_EXCEEDED"),
            ExploreSqlLaunchOutcome.ExecutionUnavailable("RAW_SQL_VALIDATION_FAILED"),
        )
        failures.forEach { outcome ->
            coEvery { sqlLaunchService.start(any()) } returns outcome
            val rejected = outcome as? ExploreSqlLaunchOutcome.SqlValidationFailed
            val unavailable = outcome as? ExploreSqlLaunchOutcome.ExecutionUnavailable
            client.post().uri("/v2/workspaces/$workspaceId/explore/sql-runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .header("Idempotency-Key", "sql-failure")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"connectorId":"$connectorId","sql":"SELECT 1"}""")
                .exchange()
                .expectStatus().isEqualTo(if (unavailable == null) 409 else 503)
                .expectHeader().let { headers ->
                    if (unavailable?.retryable == true) headers.valueEquals(HttpHeaders.RETRY_AFTER, "2")
                    else headers.doesNotExist(HttpHeaders.RETRY_AFTER)
                }
                .expectBody()
                .jsonPath("$.code").isEqualTo(rejected?.code ?: if (unavailable?.failureCode == "EXECUTION_UNAVAILABLE") "SERVICE_UNAVAILABLE" else unavailable?.failureCode)
                .jsonPath("$.retryable").isEqualTo(unavailable?.retryable ?: false)
                .jsonPath("$.detail").isEqualTo(unavailable?.message ?: rejected?.message)
        }
    }

    @Test
    fun `problem detail is bounded by the canonical schema`() {
        val response = ExploreProblemHandler("https://control.example.test").handle(
            ResponseStatusException(HttpStatus.BAD_REQUEST, "x".repeat(5_000)),
            MockServerWebExchange.from(MockServerHttpRequest.get("/explore/test").build()),
        )

        assertEquals(4_096, response.body?.detail?.length)
    }

    @Test
    fun `OpenAPI declares nullable bounded terminal failure detail`() {
        val openApi = ObjectMapper().readTree(
            Files.readString(
                Path.of(System.getProperty("control.service.root"))
                    .parent
                    .resolve("contracts/control-service.openapi.json"),
            ),
        )
        val schema = openApi.path("components").path("schemas").path("ExploreRunView")

        assertEquals(true, schema.path("required").any { it.asText() == "failureDetail" })
        val detail = schema.path("properties").path("failureDetail")
        assertEquals(1, detail.path("oneOf").get(0).path("minLength").asInt())
        assertEquals(4096, detail.path("oneOf").get(0).path("maxLength").asInt())
        assertEquals("null", detail.path("oneOf").get(1).path("type").asText())
    }

    private fun runView(
        state: ExploreRunState = ExploreRunState.RUNNING,
        resultAvailable: Boolean = false,
        failureCode: ExploreRunFailureCode? = null,
        failureDetail: String? = null,
        credentialSurface: CredentialSurface? = CredentialSurface.WEB,
        agentClientId: cc.monomer.identity.api.AgentClientId? = if (credentialSurface == CredentialSurface.AGENT) cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE else null,
        ownerPrincipalId: PrincipalId = PrincipalId(FixedPrincipalDisplayNames.MINA),
    ): ExploreRunView =
        ExploreRunView(
            id = ExploreRunId(Uuid.parse(runId)),
            kind = ExploreRunView.Kind.Sql,
            workspaceId = WorkspaceId.parse(workspaceId),
            connectorBinding = ExploreConnectorBinding(
                workspaceId = WorkspaceId.parse(workspaceId),
                connectorId = ConnectorId(Uuid.parse(connectorId)),
                connectorType = ConnectorType.POSTGRESQL,
            ),
            requestDigest = ExploreDigest("sha256:${"1".repeat(64)}"),
            state = state,
            revision = 0,
            resultAvailable = resultAvailable,
            failureCode = failureCode,
            failureDetail = failureDetail,
            createdAt = Instant.parse("2026-08-20T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-20T00:00:00Z"),
            resultMetadata = null,
            ownerPrincipalId = ownerPrincipalId,
            credentialSurface = credentialSurface,
            agentClientId = agentClientId,
        )

    private fun semanticRunView(state: ExploreRunState): ExploreRunView =
        ExploreRunView(
            id = ExploreRunId(Uuid.parse(runId)),
            kind = ExploreRunView.Kind.Semantic(query = SemanticQuerySpec(listOf(SemanticElementName("revenue")), emptyList(), null, emptyList(), emptyList(), 100), reportingTimeZone = null),
            workspaceId = WorkspaceId.parse(workspaceId),
            connectorBinding = ExploreConnectorBinding(
                WorkspaceId.parse(workspaceId),
                ConnectorId(Uuid.parse(connectorId)),
                ConnectorType.POSTGRESQL,
            ),
            requestDigest = ExploreDigest("sha256:${"1".repeat(64)}"),
            state = state,
            revision = 0,
            resultAvailable = state == ExploreRunState.SUCCEEDED,
            failureCode = null,
            failureDetail = null,
            createdAt = Instant.parse("2026-08-20T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-20T00:00:00Z"),
            resultMetadata = null,
            ownerPrincipalId = PrincipalId(FixedPrincipalDisplayNames.MINA),
            credentialSurface = CredentialSurface.WEB,
            agentClientId = null,
        )

    private fun visibleResult(): ExploreResolvedResult =
        ExploreResolvedResult(
            columns = listOf(
                ResultColumn(1, "value", ResultLogicalType.INTEGER, false),
                ResultColumn(2, "local_time", ResultLogicalType.DATETIME, true),
                ResultColumn(3, "null_time", ResultLogicalType.DATETIME, true),
            ),
            rows = listOf(listOf(ResultPreviewCell.Number("1"), ResultPreviewCell.LocalDateTimeValue("2024-02-29T12:34:56.123456"), ResultPreviewCell.Null)),
            returnedRowCount = 1,
            stopReason = ResultPreviewStopReason.END_OF_RESULT,
            columnDisplays = emptyList(),
        )
}
