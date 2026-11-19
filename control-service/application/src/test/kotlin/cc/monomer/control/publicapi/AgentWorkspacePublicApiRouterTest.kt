@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.publicapi

import kotlinx.serialization.json.jsonObject
import cc.monomer.agent.api.AgentActionOutcome
import cc.monomer.agent.api.AgentActionResult
import cc.monomer.agent.api.AgentActionResultReference
import cc.monomer.agent.api.AgentActionStatus
import cc.monomer.agent.api.AgentCreateInput
import cc.monomer.agent.api.AgentCreateRequest
import cc.monomer.agent.api.AgentEditRequest
import cc.monomer.agent.api.AgentInvokeRequest
import cc.monomer.agent.api.AgentListRequest
import cc.monomer.agent.api.AgentListingOutcome
import cc.monomer.agent.api.AgentReadRequest
import cc.monomer.agent.api.AgentResourceEnvelope
import cc.monomer.agent.api.AgentResourceKind
import cc.monomer.agent.api.AgentResourceListingResponse
import cc.monomer.agent.api.AgentResourceOutcome
import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.agent.api.AgentSearchOutcome
import cc.monomer.agent.api.AgentSearchResponse
import cc.monomer.agent.api.AgentSearchQueryResult
import cc.monomer.agent.api.AgentResourceListingItem
import cc.monomer.agent.api.AgentSearchRequest
import cc.monomer.agent.api.AgentSemanticModelResourceBody
import cc.monomer.agent.api.AgentWorkspaceAdmissionDecision
import cc.monomer.agent.api.AgentWorkspaceAdmissionDenial
import cc.monomer.agent.api.AgentWorkspaceAdmissionPort
import cc.monomer.agent.api.AgentWorkspaceAdmissionRequest
import cc.monomer.agent.api.AgentWorkspaceCaller
import cc.monomer.agent.api.AgentWorkspaceHandler
import cc.monomer.agent.api.AgentWorkspaceRequestDecoderPort
import cc.monomer.agent.api.AgentWorkspaceDecodedRequest
import cc.monomer.agent.api.AgentWorkspaceProblem
import cc.monomer.agent.api.AgentWorkspaceProblemStatus
import cc.monomer.agent.application.AgentWorkspaceCallerAuthenticator
import cc.monomer.agent.application.AgentWorkspaceContractDecoder
import cc.monomer.identity.api.OrganizationId
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.AuthorizationRevision
import cc.monomer.identity.api.CanonicalPrincipal
import cc.monomer.identity.api.CredentialKind
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.PrincipalKind
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.catalog.domain.CatalogListChildKind
import cc.monomer.catalog.domain.CatalogListOption
import cc.monomer.catalog.domain.CatalogListOptionPage
import cc.monomer.catalog.domain.CatalogListRefreshTarget
import cc.monomer.connector.api.ConnectorId
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.agent.AgentCatalogOwnerAdapter
import cc.monomer.control.agent.AgentWorkspaceApplicationComposition
import cc.monomer.catalog.application.CatalogBrowseUseCase
import cc.monomer.catalog.application.ingest.CatalogIngestApplicationPort
import cc.monomer.catalog.application.ingest.CatalogListOptionPageView
import cc.monomer.catalog.application.ingest.CatalogListRefreshApplicationPort
import cc.monomer.catalog.application.ingest.CatalogListRefreshParent
import cc.monomer.control.connector.application.ConnectorNotFoundException
import cc.monomer.control.connector.application.ConnectorReadUseCase
import cc.monomer.control.connector.application.ConnectorView
import cc.monomer.identity.api.AuthorizedExecutionContext
import cc.monomer.identity.api.IdentityRoleEvidence
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient

class AgentWorkspacePublicApiRouterTest {
    @Test
    fun `missing and expired credentials reject every Workspace body before decoding`() {
        for (fixture in listOf(AgentHttpFixture(credentialMissing = true), AgentHttpFixture(credentialInvalid = true))) {
            for (operation in listOf("list", "search", "read", "create", "edit", "invoke")) {
                for (body in listOf("{", "{\"unknown\":true}", "x".repeat(1024 * 1024 + 1))) {
                    fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/$operation")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(body).exchange()
                        .expectStatus().isUnauthorized.expectBody().jsonPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED")
                }
            }
            assertEquals(0, fixture.decodeCalls)
            assertEquals(0, fixture.admissionCalls)
            assertEquals(0, fixture.ownerCalls)
        }
    }

    @Test
    fun `request decoder IllegalArgumentException remains a sanitized 400`() {
        val fixture = AgentHttpFixture(decoderFailure = true)
        fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/read")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{\"path\":\"workspace\"}").exchange()
            .expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("AGENT_REQUEST_INVALID")
            .consumeWith { kotlin.test.assertFalse(String(it.responseBody!!).contains("sensitive-decoder-detail")) }
        assertEquals(1, fixture.credentialValidationCalls)
        assertEquals(0, fixture.admissionCalls)
    }

    @Test
    fun `direct create handler unexpected exception cannot advertise an automatic resend`() {
        val handler = mockk<AgentWorkspaceHandler>()
        coEvery { handler.create(any(), any()) } throws java.io.IOException("sensitive-owner-detail")
        val fixture = AgentHttpFixture(ownerHandler = handler)
        fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/create")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue("""{"path":"workspace/dashboards","input":{"kind":"DASHBOARD_DEFINITION","definition":{"schemaVersion":1,"meta":{"title":"Orders"},"parameters":{},"variables":{},"queries":{},"widgets":{},"layout":{"sections":[]}}}}""")
            .exchange().expectStatus().isEqualTo(503).expectHeader().doesNotExist("Retry-After").expectBody()
            .jsonPath("$.code").isEqualTo("OWNER_UNAVAILABLE").jsonPath("$.retryable").isEqualTo(false)
            .jsonPath("$.detail").value<String> { kotlin.test.assertTrue(it.contains("Read or list the target resource")) }
            .consumeWith { kotlin.test.assertFalse(String(it.responseBody!!).contains("sensitive-owner-detail")) }
    }

    @Test
    fun `non-idempotent create response contract failures require read before resending`() {
        val handler = mockk<AgentWorkspaceHandler>()
        val fixture = AgentHttpFixture(ownerHandler = handler)
        val invalidResult = AgentActionOutcome.Completed(AgentActionResult.Create(
            AgentResourcePath.parse("workspace/dashboards"), AgentActionResultReference(AgentResourceKind.DASHBOARD,
                AgentResourcePath.parse("workspace/dashboards/invalid"))))
        val invalidProblem = AgentActionOutcome.Rejected(AgentTransportProblems.ownerUnavailable.copy(detail = "x".repeat(4097)))
        for (outcome in listOf(invalidResult, invalidProblem)) {
            coEvery { handler.create(any(), any()) } returns outcome
            fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/create")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("""{"path":"workspace/dashboards","input":{"kind":"DASHBOARD_DEFINITION","definition":{"schemaVersion":1,"meta":{"title":"Orders"},"parameters":{},"variables":{},"queries":{},"widgets":{},"layout":{"sections":[]}}}}""")
                .exchange().expectStatus().isEqualTo(503).expectHeader().doesNotExist("Retry-After").expectBody()
                .jsonPath("$.code").isEqualTo("AGENT_OWNER_RESPONSE_INVALID").jsonPath("$.retryable").isEqualTo(false)
                .jsonPath("$.detail").value<String> { kotlin.test.assertTrue(it.contains("Read or list the target resource")) }
        }
    }

    @Test
    fun `Explore start response contract failure requires listing runs before resending`() {
        val fixture = AgentHttpFixture(invokeOwner = { _, request -> AgentActionOutcome.Completed(AgentActionResult.Invoke(
            request.path, "run", AgentActionStatus.ACCEPTED, AgentActionResultReference(AgentResourceKind.EXPLORE_RUN,
                AgentResourcePath.parse("workspace/explore/runs/invalid")))) })
        fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/invoke")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue("""{"path":"workspace/explore/runs","action":"run","input":{"kind":"SQL_EXPLORE_RUN","connectorId":"01942b04-6e00-7000-8000-000000000004","sql":"SELECT 1"}}""")
            .exchange().expectStatus().isEqualTo(503).expectHeader().doesNotExist("Retry-After").expectBody()
            .jsonPath("$.code").isEqualTo("AGENT_OWNER_RESPONSE_INVALID").jsonPath("$.retryable").isEqualTo(false)
            .jsonPath("$.detail").value<String> { kotlin.test.assertTrue(it.contains("List Explore runs")) }
    }

    @Test
    fun `invalid projected owner schema and unexpected owner execution have distinct codes`() {
        val path = "workspace/dashboards/01942b04-6e00-7000-8000-000000000010"
        val handler = mockk<AgentWorkspaceHandler>()
        val now = Instant.parse("2026-10-12T00:00:00Z")
        coEvery { handler.read(any(), any()) } returns AgentResourceOutcome.Found(AgentResourceEnvelope.Dashboard(
            AgentResourcePath.parse(path), cc.monomer.agent.api.AgentDashboardResourceBody(
                kotlinx.serialization.json.Json.parseToJsonElement("{}").jsonObject, now, now, null, CredentialSurface.WEB, null),
            emptyList()))
        val fixture = AgentHttpFixture(ownerHandler = handler)
        fun read() = fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/read")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("""{"path":"$path"}""").exchange()
        read().expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("AGENT_OWNER_RESPONSE_INVALID")
        coEvery { handler.read(any(), any()) } returns AgentResourceOutcome.Rejected(AgentTransportProblems.ownerUnavailable.copy(detail = "x".repeat(4097)))
        read().expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("AGENT_OWNER_RESPONSE_INVALID")
        coEvery { handler.read(any(), any()) } throws IllegalStateException("sensitive-owner-detail")
        read().expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("OWNER_UNAVAILABLE")
            .consumeWith { kotlin.test.assertFalse(String(it.responseBody!!).contains("sensitive-owner-detail")) }
    }

    @Test
    fun `owner IllegalArgumentException is a logged sanitized 500 without retry after`() {
        val fixture = AgentHttpFixture(invokeOwner = { _, _ -> throw IllegalArgumentException("sensitive-owner-detail") })
        fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/invoke")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue("""{"path":"workspace/explore/runs","action":"run","input":{"kind":"SQL_EXPLORE_RUN","connectorId":"01942b04-6e00-7000-8000-000000000004","sql":"SELECT 1"}}""")
            .exchange().expectStatus().isEqualTo(500).expectHeader().doesNotExist("Retry-After")
            .expectBody().jsonPath("$.code").isEqualTo("INTERNAL_ERROR").jsonPath("$.retryable").isEqualTo(false)
            .consumeWith { kotlin.test.assertFalse(String(it.responseBody!!).contains("sensitive-owner-detail")) }
    }

    @Test
    fun `Agent REST preserves Semantic input rejections as non retryable 400 Problems`() {
        for ((code, detail) in listOf(
            "SEMANTIC_QUERY_INVALID" to "The Semantic query is invalid.",
            "SEMANTIC_QUERY_UNSUPPORTED" to "The Semantic query uses a capability MetricFlow does not support.",
        )) {
            val problem = AgentTransportProblems.ownerUnavailable.copy(
                code = code,
                title = "Semantic query rejected",
                detail = detail,
                status = AgentWorkspaceProblemStatus.BAD_REQUEST,
                retryable = false,
                retryAfterSeconds = null,
            )
            val fixture = AgentHttpFixture(invokeOwner = { _, _ -> AgentActionOutcome.Rejected(problem) })
            fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/invoke")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("""{"path":"workspace/explore/runs","action":"run","input":{"kind":"SEMANTIC_EXPLORE_RUN",
                    "connectorId":"01942b04-6e00-7000-8000-000000000011","query":{"schemaVersion":4,"metrics":["revenue"],
                    "groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":100}}}""")
                .exchange().expectStatus().isBadRequest.expectHeader().doesNotExist("Retry-After")
                .expectBody().jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.code").isEqualTo(code)
                .jsonPath("$.retryable").isEqualTo(false)
                .jsonPath("$.detail").isEqualTo(detail)
            assertEquals(1, fixture.ownerCallsFor("invoke"))
        }
    }

    @Test
    fun `Semantic engine faults return sanitized 500 without retry after on Agent REST invoke`() {
        for (diagnostic in listOf("private SQL template diagnostic", "private MetricFlow internal diagnostic")) {
            val fixture = AgentHttpFixture(invokeOwner = { _, _ ->
                throw cc.monomer.semantic.application.SemanticMetricFlowEngineFaultException(IllegalStateException(diagnostic))
            })
            fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/invoke")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("""{"path":"workspace/explore/runs","action":"run","input":{"kind":"SEMANTIC_EXPLORE_RUN",
                    "connectorId":"01942b04-6e00-7000-8000-000000000011","query":{"schemaVersion":4,"metrics":["revenue"],
                    "groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":100}}}""")
                .exchange().expectStatus().isEqualTo(500)
                .expectHeader().doesNotExist("Retry-After")
                .expectHeader().contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.status").isEqualTo(500)
                .jsonPath("$.code").isEqualTo("INTERNAL_ERROR")
                .jsonPath("$.retryable").isEqualTo(false)
                .jsonPath("$.detail").isEqualTo("The Agent request could not be completed. Read the target resource before resending a create or start request.")
                .consumeWith { kotlin.test.assertFalse(String(it.responseBody!!).contains(diagnostic)) }
            assertEquals(1, fixture.ownerCallsFor("invoke"))
        }
    }

    @Test
    fun `REST Workspace target accepts only lowercase canonical UUID text`() {
        val fixture = AgentHttpFixture()
        val canonical = fixture.workspaceId.value.toString()
        for (invalid in listOf(canonical.uppercase(), canonical.replace("-", ""))) {
            fixture.client.post().uri("/v2/workspaces/$invalid/agent/read")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{\"path\":\"workspace\"}").exchange()
                .expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("INVALID_PATH_PARAMETER")
        }
        assertEquals(0, fixture.decodeCalls)
        assertEquals(0, fixture.admissionCalls)
    }

    @Test
    fun `REST Problem and response header share the ingress request ID`() {
        val fixture = AgentHttpFixture(credentialMissing = true)
        val result = fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/list")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("""{"path":"workspace/connectors","limit":20}""")
            .exchange().expectStatus().isUnauthorized.expectBody().returnResult()
        val problem = com.fasterxml.jackson.databind.ObjectMapper().readTree(result.responseBody)
        val id = result.responseHeaders.getFirst("X-Request-Id")!!
        assertEquals(id, problem.path("requestId").asText())
        assertEquals(id, problem.path("traceId").asText())
        assertEquals(true, id.startsWith("srv_"))
    }

    @Test
    fun `atomic model owner conflicts serialize indexed aggregate fields on public create and edit routes`() {
        val authority = mockk<cc.monomer.semantic.api.SemanticAuthorityUseCase>()
        coEvery { authority.putModel(any()) } returns cc.monomer.semantic.api.SemanticMutationResult.AggregateMetricNameConflict("aggregateMetrics[1].name")
        val admission = mockk<ControlAdmissionFacade>()
        coEvery { admission.authorizeMutation(any()) } answers {
            ControlAdmissionDecision.Admitted(cc.monomer.identity.api.AuthorizedMutationContext(
                cc.monomer.identity.api.OrganizationPlan.FREE, CanonicalPrincipal(PrincipalId.generate(), PrincipalKind.USER), OrganizationId.generate(), AgentHttpFixture.WORKSPACE_ID,
                AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://example.test/agent"), WorkspaceRole.EDITOR,
                firstArg<cc.monomer.control.MutationAdmissionRequest>().intent, Instant.now(),
            ))
        }
        val definition = """{"schemaVersion":3,"name":"orders","sourceRelations":[{"connectorId":"01942b04-6e00-7000-8000-000000000010","schema":"analytics","relation":"orders"}],"entities":[{"name":"order_id","type":"primary","expression":"order_id"}],"dimensions":[],"measures":[{"name":"amount","aggregation":"sum","expression":"amount"}]}"""
        val read = mockk<cc.monomer.semantic.api.SemanticCurrentReadUseCase>()
        coEvery { read.findModelByName(AgentHttpFixture.WORKSPACE_ID, cc.monomer.semantic.api.SemanticElementName("orders")) } returns cc.monomer.semantic.api.SemanticModelView(cc.monomer.semantic.api.SemanticModelId.generate(), AgentHttpFixture.WORKSPACE_ID,
            com.fasterxml.jackson.databind.ObjectMapper().registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build()).readValue(definition, cc.monomer.control.semantic.inbound.rest.SemanticModelDefinitionDto::class.java).toDomain(), Instant.now(), Instant.now(),
                emptyList(),
            )
        val adapter = cc.monomer.control.agent.AgentSemanticOwnerAdapter(authority, read, mockk(), cc.monomer.control.agent.AgentSemanticEditAvailability { true }, admission, cc.monomer.control.agent.fixedAgentProvenanceNames())
        val handler = mockk<AgentWorkspaceHandler>()
        coEvery { handler.create(any(), any()) } coAnswers { adapter.create(firstArg(), secondArg()) }
        coEvery { handler.edit(any(), any()) } coAnswers { adapter.edit(firstArg(), secondArg()) }
        val fixture = AgentHttpFixture(ownerHandler = handler)
        val aggregates = """[{"measure":"amount","name":"first"},{"measure":"amount","name":"second"}]"""
        for ((operation, body, field) in listOf(
            Triple("create", """{"path":"workspace/semantic-models","input":{"kind":"SEMANTIC_MODEL_DEFINITION","definition":$definition,"aggregateMetrics":$aggregates}}""", "input.aggregateMetrics[1].name"),
            Triple("edit", """{"path":"workspace/semantic-models/orders","patch":[{"op":"add","path":"/label","value":"Orders"}],"aggregateMetrics":$aggregates}""", "aggregateMetrics[1].name"),
        )) {
            fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/$operation").header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue(body).exchange()
                .expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("SEMANTIC_NAME_CONFLICT")
                .jsonPath("$.violations[0].field").isEqualTo(field)
        }
        coVerify(exactly = 2) { authority.putModel(any()) }
        coVerify(exactly = 0) { authority.putMetric(any()) }
    }

    @Test
    fun `schedule public reads route through observation owner without execution and enforce admission`() {
        val dashboardId = cc.monomer.dashboard.api.DashboardId(kotlin.uuid.Uuid.parse("01942b04-6e00-7000-8000-000000000010"))
        val owner = mockk<cc.monomer.dashboard.api.DashboardOwnerPort>()
        val schedules = mockk<cc.monomer.dashboard.api.DashboardScheduleOwnerPort>()
        val definition = kotlinx.serialization.json.Json.parseToJsonElement("""{"schemaVersion":1,"meta":{"title":"Scheduled report"},"parameters":{},"variables":{},"queries":{},"widgets":{},"layout":{"sections":[]}}""").jsonObject
        val now = Instant.parse("2026-09-20T00:00:00Z")
        coEvery { owner.read(AgentHttpFixture.WORKSPACE_ID, dashboardId) } answers {
            cc.monomer.dashboard.api.DashboardOwnerOutcome.Success(cc.monomer.dashboard.api.DashboardOwnerDashboard(dashboardId, AgentHttpFixture.WORKSPACE_ID, definition, now, now, null, null, null))
        }
        coEvery { schedules.preview(definition, any()) } returns emptyList()
        coEvery { schedules.listSchedules(AgentHttpFixture.WORKSPACE_ID, dashboardId) } returns listOf(cc.monomer.dashboard.api.DashboardScheduleStatus("daily", now))
        val occurrence = cc.monomer.dashboard.api.DashboardScheduleOccurrenceView("daily", now, now, "SKIPPED", "LATE", definition, null, null, emptyMap())
        coEvery { schedules.listOccurrences(AgentHttpFixture.WORKSPACE_ID, dashboardId, null, 20) } returns cc.monomer.dashboard.api.DashboardScheduleOccurrencePage(listOf(occurrence), null)
        coEvery { schedules.readOccurrence(AgentHttpFixture.WORKSPACE_ID, dashboardId, "daily", now) } returns occurrence
        val observation = cc.monomer.control.dashboard.DashboardScheduleObservation(schedules, owner, java.time.Clock.fixed(now, java.time.ZoneOffset.UTC))
        val dashboardAdmission = mockk<ControlAdmissionFacade>()
        coEvery { dashboardAdmission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(mockk<IdentityRoleEvidence>())
        val adapter = cc.monomer.control.agent.AgentDashboardOwnerAdapter(owner, observation, mockk(), dashboardAdmission, java.time.Clock.systemUTC(), cc.monomer.control.agent.fixedAgentProvenanceNames()) { _, _ -> error("observation cannot mutate") }
        val handler = cc.monomer.agent.application.AgentResourceRouteRegistry(mockk(), mockk(), mockk(), mockk(), adapter, null, emptyList(), mockk(), null)
        val fixture = AgentHttpFixture(ownerHandler = handler)
        val dashboard = "workspace/dashboards/$dashboardId"
        fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue("""{"path":"$dashboard/schedules"}""")
            .exchange().expectStatus().isOk.expectBody().jsonPath("$.kind").isEqualTo("DASHBOARD_SCHEDULES")
            .jsonPath("$.body.schedules[0].nextScheduledAt").isEqualTo(now.toString())
        fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/list")
            .header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue("""{"path":"$dashboard/occurrences","limit":20}""")
            .exchange().expectStatus().isOk.expectBody().jsonPath("$.items[0].path").isEqualTo("$dashboard/occurrences/daily/$now")
            .jsonPath("$.items[0].body.scheduleKey").isEqualTo("daily")
            .jsonPath("$.items[0].body.definition").doesNotExist()
            .jsonPath("$.items[0].body.initialQueryRuns").doesNotExist()
        fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue("""{"path":"$dashboard/occurrences/daily/$now"}""")
            .exchange().expectStatus().isOk.expectBody().jsonPath("$.body.outcome").isEqualTo("SKIPPED")
            .jsonPath("$.body.definition.meta.title").isEqualTo("Scheduled report")
        for (suffix in listOf("schedules", "draft/schedules", "occurrences/daily/$now")) {
            val denied = AgentHttpFixture(ownerHandler = handler, admissionDenial = AgentWorkspaceAdmissionDenial.FORBIDDEN)
            denied.client.post().uri("/v2/workspaces/${denied.workspaceId}/agent/read")
                .header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue("""{"path":"$dashboard/$suffix"}""")
                .exchange().expectStatus().isForbidden
        }
        fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/invoke")
            .header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue("""{"path":"$dashboard/schedules","action":"run","input":{}}""")
            .exchange().expectStatus().isBadRequest
        coVerify(exactly = 1) { schedules.preview(any(), any()) }
        coVerify(exactly = 1) { schedules.listSchedules(AgentHttpFixture.WORKSPACE_ID, dashboardId) }
        coVerify(exactly = 1) { schedules.listOccurrences(AgentHttpFixture.WORKSPACE_ID, dashboardId, null, 20) }
        coVerify(exactly = 1) { schedules.readOccurrence(AgentHttpFixture.WORKSPACE_ID, dashboardId, "daily", now) }
        confirmVerified(schedules)
    }

    @Test
    fun `Draft status public read routes to the Dashboard owner and keeps Draft read admission`() {
        val dashboardId = cc.monomer.dashboard.api.DashboardId(kotlin.uuid.Uuid.parse("01942b04-6e00-7000-8000-000000000010"))
        val runId = cc.monomer.dashboard.api.QueryRunId(kotlin.uuid.Uuid.parse("01942b04-6e00-7000-8000-000000000011"))
        val owner = mockk<cc.monomer.dashboard.api.DashboardOwnerPort>()
        coEvery { owner.readDraftStatus(AgentHttpFixture.WORKSPACE_ID, dashboardId) } returns cc.monomer.dashboard.api.DashboardOwnerOutcome.Success(
            cc.monomer.dashboard.api.DashboardOwnerDraftStatus(dashboardId, 5,
                mapOf("orders" to cc.monomer.dashboard.api.DashboardOwnerDraftTargetQueryRun(runId, "SUCCEEDED")),
                mapOf("chart" to cc.monomer.dashboard.api.DashboardOwnerReadyResult(5, emptyMap(), emptyMap(), mapOf("orders" to runId)))),
        )
        val dashboardAdmission = mockk<ControlAdmissionFacade>()
        coEvery { dashboardAdmission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(mockk<IdentityRoleEvidence>())
        val adapter = cc.monomer.control.agent.AgentDashboardOwnerAdapter(owner, mockk(), mockk(), dashboardAdmission, java.time.Clock.systemUTC(), cc.monomer.control.agent.fixedAgentProvenanceNames()) { _, _ -> error("status cannot mutate") }
        val handler = cc.monomer.agent.application.AgentResourceRouteRegistry(mockk(), mockk(), mockk(), mockk(), adapter, null, emptyList(), mockk(), null)
        val fixture = AgentHttpFixture(ownerHandler = handler)
        val draft = "workspace/dashboards/$dashboardId/draft"
        fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue("""{"path":"$draft/status"}""")
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.kind").isEqualTo("DASHBOARD_DRAFT_STATUS").jsonPath("$.path").isEqualTo("$draft/status")
            .jsonPath("$.revision").doesNotExist().jsonPath("$.availableOperations").doesNotExist()
            .jsonPath("$.body.draftRevision").isEqualTo("5")
            .jsonPath("$.body.targetQueryRuns.orders.path").isEqualTo("$draft/query-runs/$runId")
            .jsonPath("$.body.targetQueryRuns.orders.state").isEqualTo("SUCCEEDED")
            .jsonPath("$.body.readyResults.chart.queryRuns.orders").isEqualTo("$draft/query-runs/$runId")
            .jsonPath("$.body.definition").doesNotExist()
        val denied = AgentHttpFixture(ownerHandler = handler, admissionDenial = AgentWorkspaceAdmissionDenial.FORBIDDEN)
        denied.client.post().uri("/v2/workspaces/${denied.workspaceId}/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue("""{"path":"$draft/status"}""")
            .exchange().expectStatus().isForbidden
        fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/list")
            .header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue("""{"path":"$draft/status","limit":20}""")
            .exchange().expectStatus().isBadRequest
        coVerify(exactly = 1) { owner.readDraftStatus(AgentHttpFixture.WORKSPACE_ID, dashboardId) }
        confirmVerified(owner)
    }

    @Test
    fun `dashboard action browser URLs use configured origin and preserve resource paths`() {
        val dashboardPath = "workspace/dashboards/01942b04-6e00-7000-8000-000000000010"
        for (origin in listOf("http://127.0.0.1:3500", "https://app.dev.monomer.cc", "https://app.monomer.cc")) {
            val fixture = AgentHttpFixture(webOrigin = URI(origin), invokeOwner = { _, request ->
                AgentActionOutcome.Completed(AgentActionResult.Invoke(request.path, request.action, AgentActionStatus.COMPLETED,
                    AgentActionResultReference(AgentResourceKind.DASHBOARD, AgentResourcePath.parse(dashboardPath))))
            })
            val requests = listOf(
                Triple("create", """{"path":"workspace/dashboards","input":{"kind":"DASHBOARD_DEFINITION","definition":{"schemaVersion":1,"meta":{"title":"Orders"},"parameters":{},"variables":{},"queries":{},"widgets":{},"layout":{"sections":[]}}}}""", dashboardPath),
                Triple("edit", """{"path":"$dashboardPath/draft","revision":"1","patch":[{"op":"replace","path":"/definition/meta/title","value":"Revenue"}]}""", "$dashboardPath/draft"),
                Triple("invoke", """{"path":"$dashboardPath/draft","action":"apply","input":{"kind":"DASHBOARD_DRAFT_APPLY","expectedRevision":"1"}}""", dashboardPath),
            )
            for ((operation, body, resultPath) in requests) {
                val expected = "$origin/workspaces/${fixture.workspaceId}/dashboards/${dashboardPath.substringAfterLast('/')}" + if (operation == "invoke") "" else "/builder"
                fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/$operation")
                    .header(HttpHeaders.CONTENT_TYPE, "application/json").header(HttpHeaders.HOST, "attacker.invalid")
                    .header("Forwarded", "host=attacker.invalid;proto=https").bodyValue(body).exchange()
                    .expectStatus().isOk.expectBody().jsonPath("$.webUrl").isEqualTo(expected)
                    .jsonPath("$.result.path").isEqualTo(resultPath)
            }
        }
    }

    @Test
    fun `REST result read projects explicit Arrow reference and rejects download on other paths`() {
        val handler = mockk<AgentWorkspaceHandler>()
        val path = "workspace/explore/runs/01942b04-6e00-7000-8000-000000000021/result"
        val link = cc.monomer.agent.api.AgentExploreResultDownloadLink(
            "https://objects.example.test/result.arrow?signature=fixture", "result.arrow",
            "application/vnd.apache.arrow.file", "18446744073709551615", Instant.parse("2026-09-07T00:05:00Z"),
        )
        coEvery { handler.read(any(), any()) } answers {
            assertEquals(cc.monomer.identity.api.ExpectedOAuthResource.AGENT_REST, firstArg<AgentWorkspaceCaller>().expectedOAuthResource)
            AgentResourceOutcome.Found(AgentResourceEnvelope.ExploreResult(
                AgentResourcePath.parse(path), cc.monomer.agent.api.AgentExploreResultResourceBody(
                    emptyList(), emptyList(), "0", 0, false, Instant.parse("2026-09-07T01:00:00Z"),
                    if (secondArg<AgentReadRequest>().download != null) link else null,
                    reportingTimeZone = null,
                ), emptyList(),
            ))
        }
        val fixture = AgentHttpFixture(ownerHandler = handler)
        fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue("""{"path":"$path","download":"arrow"}""")
            .exchange().expectStatus().isOk.expectHeader().valueEquals("Cache-Control", "no-store")
            .expectBody().jsonPath("$.body.download.url").isEqualTo(link.url)
            .jsonPath("$.body.download.byteSize").isEqualTo("18446744073709551615")
        fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue("""{"path":"$path"}""")
            .exchange().expectStatus().isOk.expectBody().jsonPath("$.body.download").doesNotExist()
            .jsonPath("$.body.reportingTimeZone").isEqualTo(null)
        fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue("""{"path":"workspace","download":"arrow"}""")
            .exchange().expectStatus().isBadRequest
        coVerify(exactly = 2) { handler.read(any(), any()) }
    }

    @Test
    fun `Page result read returns required empty operations while Dashboard result wire shape stays unchanged`() {
        val handler = mockk<AgentWorkspaceHandler>()
        val pageResultPath = AgentResourcePath.parse("workspace/pages/01942b04-6e00-7000-8000-000000000021/sessions/01942b04-6e00-7000-8000-000000000022/query-runs/01942b04-6e00-7000-8000-000000000023/result")
        val dashboardResultPath = AgentResourcePath.parse("workspace/dashboards/01942b04-6e00-7000-8000-000000000024/sessions/01942b04-6e00-7000-8000-000000000022/query-runs/01942b04-6e00-7000-8000-000000000023/result")
        val body = cc.monomer.agent.api.AgentDashboardResultResourceBody(
            "SEMANTIC",
            listOf(
                cc.monomer.agent.api.AgentDashboardResultColumn("dimension.orders.country", "TEXT", true, null),
                cc.monomer.agent.api.AgentDashboardResultColumn("metric.revenue", "DECIMAL", false, cc.monomer.agent.api.AgentMetricDisplay.Currency(2, cc.monomer.agent.api.AgentMetricDisplayCurrency.USD)),
                cc.monomer.agent.api.AgentDashboardResultColumn("metric.margin", "DECIMAL", true, cc.monomer.agent.api.AgentMetricDisplay.Percent(1)),
                cc.monomer.agent.api.AgentDashboardResultColumn("metric.orders", "INTEGER", false, null),
            ),
            listOf(listOf(kotlinx.serialization.json.JsonPrimitive("US"), kotlinx.serialization.json.JsonPrimitive("1234.50"), kotlinx.serialization.json.JsonPrimitive("0.123"), kotlinx.serialization.json.JsonNull)),
            "1", 1, false,
            reportingTimeZone = java.time.ZoneId.of("Asia/Seoul"),
        )
        val pageResource = AgentResourceEnvelope.DashboardQueryResult(pageResultPath, body, emptyList())
        coEvery { handler.read(any(), AgentReadRequest(pageResultPath, null)) } returns AgentResourceOutcome.Found(pageResource)
        coEvery { handler.read(any(), AgentReadRequest(dashboardResultPath, null)) } returns AgentResourceOutcome.Found(pageResource.copy(path = dashboardResultPath))
        val fixture = AgentHttpFixture(ownerHandler = handler)

        fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue("""{"path":"$pageResultPath"}""")
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.kind").isEqualTo("DASHBOARD_QUERY_RESULT")
            .jsonPath("$.path").isEqualTo(pageResultPath.value)
            .jsonPath("$.availableOperations").isArray
            .jsonPath("$.availableOperations.length()").isEqualTo(0)
            .jsonPath("$.body.columns[*].name").isEqualTo(body.columns.map { it.name })
            .jsonPath("$.body.columns[*].nullable").isEqualTo(listOf(true, false, true, false))
            .jsonPath("$.body.columns[0].display").isEqualTo(null)
            .jsonPath("$.body.columns[1].display").isEqualTo(mapOf("style" to "CURRENCY", "decimals" to 2, "currency" to "USD"))
            .jsonPath("$.body.columns[2].display").isEqualTo(mapOf("style" to "PERCENT", "decimals" to 1))
            .jsonPath("$.body.columns[3].display").isEqualTo(null)
            .jsonPath("$.body.rows[0][1]").isEqualTo("1234.50")
            .jsonPath("$.body.rows[0][2]").isEqualTo("0.123")
            .jsonPath("$.body.rows[0][3]").isEqualTo(null)
            .jsonPath("$.body.reportingTimeZone").isEqualTo("Asia/Seoul")
        fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue("""{"path":"$dashboardResultPath"}""")
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.path").isEqualTo(dashboardResultPath.value)
            .jsonPath("$.availableOperations").doesNotExist()

        val otherSessionPath = AgentResourcePath.parse(pageResultPath.value.replace("000000000022", "000000000025"))
        coEvery { handler.read(any(), AgentReadRequest(pageResultPath, null)) } returns AgentResourceOutcome.Found(pageResource.copy(path = otherSessionPath))
        fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue("""{"path":"$pageResultPath"}""")
            .exchange().expectStatus().isEqualTo(503).expectBody()
            .jsonPath("$.code").isEqualTo("AGENT_OWNER_RESPONSE_INVALID")
    }

    @Test
    fun `public Connector routes use installed owner and return only typed safe projections`() {
        val authority = mockk<cc.monomer.connector.api.ConnectorAuthorityUseCase>()
        val currentRead = mockk<cc.monomer.connector.api.ConnectorWorkspaceReadUseCase>()
        val admission = mockk<ControlAdmissionFacade>()
        val id = cc.monomer.connector.api.ConnectorId(Uuid.parse("01942b04-6e00-7000-8000-000000000010"))
        val path = "workspace/connectors/$id"
        val properties = cc.monomer.connector.api.ConnectorSettingsCodec.decode(cc.monomer.connector.api.ConnectorProductType.POSTGRESQL,
            kotlinx.serialization.json.Json.parseToJsonElement("""{"host":"db.example.com","port":5432,"database":"warehouse"}""") as kotlinx.serialization.json.JsonObject)
        val current = cc.monomer.connector.api.CurrentConnectorRead(id, AgentHttpFixture.WORKSPACE_ID,
            cc.monomer.connector.api.ConnectorProductType.POSTGRESQL, "Warehouse", properties, Instant.EPOCH, Instant.EPOCH)
        val view = cc.monomer.connector.api.ConnectorMutationView(id, current.workspaceId, "POSTGRESQL", current.displayName, properties,
            cc.monomer.connector.api.CredentialVersionId(Uuid.parse("01942b04-6e00-7000-8000-000000000011")), Instant.EPOCH, Instant.EPOCH)
        coEvery { currentRead.listByWorkspace(current.workspaceId) } returns listOf(current)
        coEvery { currentRead.findByWorkspaceAndId(current.workspaceId, id) } returns current
        coEvery { admission.authorizeMutation(any()) } returns ControlAdmissionDecision.Admitted(mockk())
        coEvery { authority.create(any()) } returns cc.monomer.connector.api.ConnectorMutationResult.Committed(view)
        coEvery { authority.revise(any()) } returns cc.monomer.connector.api.ConnectorMutationResult.Committed(view)
        coEvery { authority.rotateCredential(any()) } returns cc.monomer.connector.api.ConnectorMutationResult.Committed(view)
        val connector = cc.monomer.control.agent.AgentConnectorOwnerAdapter(authority, currentRead, admission, cc.monomer.identity.api.WorkspaceSampleQuery { null })
        val handler = AgentWorkspaceApplicationComposition().agentWorkspaceHandler(mockk(), connector, mockk(), mockk(), mockk(), mockk(), mockk { every { ifAvailable } returns mockk() }, managementOwnerPort = io.mockk.mockk { io.mockk.coEvery { read(any<cc.monomer.agent.api.AgentWorkspaceCaller>(), any()) } returns cc.monomer.agent.api.AgentResourceOutcome.Unavailable })
        val fixture = AgentHttpFixture(ownerHandler = handler)
        fun post(operation: String, body: String) = fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/$operation")
            .header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue(body).exchange()
        post("list", """{"path":"workspace/connectors","limit":5}""").expectStatus().isOk.expectBody()
            .jsonPath("$.items[0].kind").isEqualTo("CONNECTOR")
            .jsonPath("$.items[0].path").isEqualTo(path)
            .jsonPath("$.items[0].displayName").isEqualTo("Warehouse")
            .jsonPath("$.items[0].connectorId").doesNotExist()
            .jsonPath("$.availableOperations[0].inputKind").isEqualTo("CONNECTOR_CREATE")
        post("read", """{"path":"$path"}""").expectStatus().isOk.expectBody()
            .jsonPath("$.body.connectorType").isEqualTo("POSTGRESQL")
            .jsonPath("$.availableOperations.length()").isEqualTo(4)
            .jsonPath("$.body.properties.port").isEqualTo(5432)
            .jsonPath("$.body.connectorId").doesNotExist()
            .jsonPath("$.body.workspaceId").doesNotExist()
            .jsonPath("$.body.secretSet").doesNotExist()
            .jsonPath("$.body.credential").doesNotExist()
            .jsonPath("$.body.createdAt").doesNotExist()
        val create = """{"path":"workspace/connectors","input":{"kind":"CONNECTOR_CREATE","connectorType":"POSTGRESQL","displayName":"Warehouse","properties":{"host":"db.example.com","port":5432,"database":"warehouse"},"credential":{"kind":"USERNAME_PASSWORD","username":"reader","password":"fixture-only"}}}"""
        val edit = """{"path":"$path","patch":[{"op":"replace","path":"/displayName","value":"Renamed"}]}"""
        val rotate = """{"path":"$path","action":"rotate-credentials","input":{"kind":"CONNECTOR_CREDENTIAL_ROTATE","credential":{"kind":"USERNAME_PASSWORD","username":"reader","password":"fixture-only"}}}"""
        listOf("create" to create, "edit" to edit, "invoke" to rotate).forEach { (operation, body) ->
            post(operation, body).expectStatus().isOk.expectBody().jsonPath("$.status").isEqualTo("COMPLETED")
                .jsonPath("$.result.path").isEqualTo(path).jsonPath("$.credential").doesNotExist()
        }
        post("invoke", rotate.replace("rotate-credentials", "test")).expectStatus().isBadRequest
        post("create", create.replace("5432", "\"5432\"")).expectStatus().isBadRequest
        coEvery { admission.authorizeMutation(any()) } returns ControlAdmissionDecision.Denied(cc.monomer.control.ControlAdmissionStage.IDENTITY,
                    cc.monomer.control.ControlAdmissionDenial.Identity(cc.monomer.identity.api.IdentityAdmissionDenial.PERMISSION_UNAVAILABLE))
        post("edit", edit).expectStatus().isForbidden
        val denied = AgentHttpFixture(ownerHandler = handler, admissionDenial = AgentWorkspaceAdmissionDenial.FORBIDDEN)
        denied.client.post().uri("/v2/workspaces/${denied.workspaceId}/agent/create").header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue(create).exchange().expectStatus().isForbidden
        val unauthenticated = AgentHttpFixture(ownerHandler = handler, credentialMissing = true)
        unauthenticated.client.post().uri("/v2/workspaces/${unauthenticated.workspaceId}/agent/read").header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("""{"path":"$path"}""").exchange().expectStatus().isUnauthorized
        io.mockk.coVerify(exactly = 1) { authority.create(any()) }
        io.mockk.coVerify(exactly = 1) { authority.revise(any()) }
        io.mockk.coVerify(exactly = 1) { authority.rotateCredential(any()) }
    }


    @Test
    fun `a role denial carries the required role and visible Owners while a hidden Workspace stays a bare 404`() {
        val requirement = cc.monomer.agent.api.AgentRoleRequirement("EDITOR", "EXPLORER", listOf("Mina Park"))
        val problem = AgentWorkspaceProblem(
            "ROLE_REQUIRED", "Editor role required", "Changing a metric in Revenue Analytics needs the Editor role. Sam Kim is an Explorer.",
            AgentWorkspaceProblemStatus.FORBIDDEN, false, null, emptyList(), listOf("Ask Mina Park to give Sam Kim the Editor role, then try again."),
            emptyList(), null, requirement,
            partitionFilters = emptyList(),
        )
        val denied = AgentHttpFixture(admissionDenial = AgentWorkspaceAdmissionDenial.FORBIDDEN, admissionProblem = problem)
        denied.client.post().uri("/v2/workspaces/${denied.workspaceId}/agent/read").header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("""{"path":"workspace"}""").exchange().expectStatus().isForbidden.expectBody()
            .jsonPath("$.code").isEqualTo("ROLE_REQUIRED")
            .jsonPath("$.type").isEqualTo("https://monomer.cc/problems/role-required")
            .jsonPath("$.title").isEqualTo("Editor role required")
            .jsonPath("$.detail").isEqualTo(problem.detail)
            .jsonPath("$.requiredRole").isEqualTo("EDITOR")
            .jsonPath("$.currentRole").isEqualTo("EXPLORER")
            .jsonPath("$.askOwners[0]").isEqualTo("Mina Park")
            .jsonPath("$.hints[0]").isEqualTo(problem.hints.single())
        val bare = AgentHttpFixture(admissionDenial = AgentWorkspaceAdmissionDenial.FORBIDDEN)
        bare.client.post().uri("/v2/workspaces/${bare.workspaceId}/agent/read").header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("""{"path":"workspace"}""").exchange().expectStatus().isForbidden.expectBody()
            .jsonPath("$.code").isEqualTo("ROLE_REQUIRED").jsonPath("$.requiredRole").doesNotExist()
        val hidden = AgentHttpFixture(admissionDenial = AgentWorkspaceAdmissionDenial.NOT_FOUND)
        hidden.client.post().uri("/v2/workspaces/${hidden.workspaceId}/agent/read").header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("""{"path":"workspace"}""").exchange().expectStatus().isNotFound.expectBody()
            .jsonPath("$.code").isEqualTo("NOT_FOUND").jsonPath("$.askOwners").doesNotExist()
    }

    @Test
    fun `public session resolution serializes transient maps and rejects cross-session owner results`() {
        val session = AgentResourcePath.parse("workspace/dashboards/01942b04-6e00-7000-8000-000000000010/sessions/01942b04-6e00-7000-8000-000000000012")
        val run = AgentResourcePath.parse("${session.value}/query-runs/01942b04-6e00-7000-8000-000000000011")
        var selectedRun = run
        val fixture = AgentHttpFixture(invokeOwner = { _, request ->
            AgentActionOutcome.Completed(AgentActionResult.DashboardSessionResolution(
                request.path, request.action, AgentActionStatus.ACCEPTED,
                AgentActionResultReference(AgentResourceKind.DASHBOARD_SESSION, request.path),
                mapOf("country" to kotlinx.serialization.json.JsonNull), mapOf("orders" to selectedRun),
            ))
        })
        fun invoke() = fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/invoke")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("""{"path":"${session.value}","action":"resolve","input":{"kind":"DASHBOARD_SESSION_RESOLVE","variables":{"country":null}}}""").exchange()
        invoke().expectStatus().isAccepted.expectBody()
            .jsonPath("$.variables.country").isEmpty
            .jsonPath("$.queryRuns.orders").isEqualTo(run.value)
            .jsonPath("$.result.path").isEqualTo(session.value)
        selectedRun = AgentResourcePath.parse(run.value.replace("000000000012", "000000000013"))
        invoke().expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("AGENT_OWNER_RESPONSE_INVALID")
    }

    @Test
    fun `Agent run requests retain zero through six fractional digits after decoding`() {
        val observed = mutableListOf<cc.monomer.agent.api.AgentSemanticQuery>()
        val fixture = AgentHttpFixture(invokeOwner = { _, request ->
            observed += (request.input as cc.monomer.agent.api.AgentInvokeInput.ExploreRun)
                .request.let { it as cc.monomer.agent.api.AgentExploreRunRequest.Semantic }.query
            AgentActionOutcome.Completed(AgentActionResult.Invoke(
                request.path, request.action, AgentActionStatus.ACCEPTED,
                AgentActionResultReference(AgentResourceKind.EXPLORE_RUN,
                    AgentResourcePath.parse("workspace/explore/runs/01942b04-6e00-7000-8000-000000000021")),
            ))
        })
        for (fraction in listOf("", ".1", ".12", ".123", ".1234", ".12345", ".123456", ".000000")) {
            val raw = "2026-10-01T00:00:00$fraction+09:00"
            fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/invoke")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue("""{"path":"workspace/explore/runs","action":"run","input":{"kind":"SEMANTIC_EXPLORE_RUN",
                    "connectorId":"01942b04-6e00-7000-8000-000000000011","query":{"schemaVersion":4,"metrics":["revenue"],
                    "groupBys":[],"metricTimeRange":null,"filters":[{"kind":"TIMESTAMP","dimension":{"semanticModelName":"orders","name":"ordered_at"},
                    "operator":"GREATER_THAN_OR_EQUAL","values":["$raw"]}],"orderBy":[],"rowLimit":100}}}""")
                .exchange().expectStatus().isAccepted
                .expectBody().jsonPath("$.status").isEqualTo("ACCEPTED")
            assertEquals(listOf(Instant.parse(raw)), kotlin.test.assertIs<cc.monomer.agent.api.AgentSemanticFilter.Timestamp>(observed.last().filters.single()).values)
        }
        assertEquals(8, fixture.totalOwnerCalls)
    }

    @Test
    fun `timestamp filter precision fails before Agent run owner admission`() {
        val fixture = AgentHttpFixture()
        for (fraction in listOf("1234567", "12345678", "123456789", "1234567890", "1234560", "0000000")) {
            fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/invoke")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue("""{"path":"workspace/explore/runs","action":"run","input":{"kind":"SEMANTIC_EXPLORE_RUN",
                    "connectorId":"01942b04-6e00-7000-8000-000000000011","query":{"schemaVersion":4,"metrics":["revenue"],
                    "groupBys":[],"metricTimeRange":null,"filters":[{"kind":"TIMESTAMP","dimension":{"semanticModelName":"orders","name":"ordered_at"},
                    "operator":"GREATER_THAN_OR_EQUAL","values":["2026-10-01T00:00:00.$fraction+09:00"]}],"orderBy":[],"rowLimit":100}}}""")
                .exchange().expectStatus().isBadRequest
                .expectHeader().contentType("application/problem+json")
                .expectBody().jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.code").isEqualTo("AGENT_REQUEST_INVALID")
                .jsonPath("$.violations.length()").isEqualTo(1)
                .jsonPath("$.violations[0].field").isEqualTo("input.query.filters[0].values[0]")
                .jsonPath("$.violations[0].code").isEqualTo("SCHEMA_MISMATCH")
                .jsonPath("$.violations[0].message").isEqualTo("Timestamp filter values must have at most 6 fractional second digits.")
        }
        assertEquals(0, fixture.totalOwnerCalls)
    }

    @Test
    fun `owner failures after decoding return the same Problem Details as MCP`() {
        val session = AgentResourcePath.parse("workspace/dashboards/01942b04-6e00-7000-8000-000000000010/sessions/01942b04-6e00-7000-8000-000000000011")
        val fixture = AgentHttpFixture(invokeOwner = { _, _ -> throw IllegalStateException("sensitive-owner-detail") })
        fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/invoke")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("""{"path":"${session.value}","action":"resolve","input":{"kind":"DASHBOARD_SESSION_RESOLVE","variables":{"country":null}}}""")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.code").isEqualTo("OWNER_UNAVAILABLE")
            .consumeWith { kotlin.test.assertFalse(String(it.responseBody!!).contains("sensitive-owner-detail")) }
    }

    @Test
    fun `long Catalog names survive public routing response validation and conflict recovery`() {
        listOf("/".repeat(1024), "~".repeat(1024), "😀".repeat(256), "a\u2028b", "a\u2029b", "한글 /~%2F|\\\"+?#", ".", "..").forEach { name ->
            val options = CatalogListFixture(fetched = true, catalogAndSchema = name to name, optionName = name)
            options.list().expectStatus().isOk.expectBody()
                .jsonPath("$.items[0].name").isEqualTo(name)
                .jsonPath("$.items[0].path").isEqualTo("${options.path}/${AgentResourcePath.encodeNameSegment(name)}")
            options.verifyReadOnly(1)
            val missing = CatalogListFixture(catalogAndSchema = name to name)
            missing.list().expectStatus().isEqualTo(409).expectBody()
                .jsonPath("$.code").isEqualTo("CATALOG_SELECTION_OPTIONS_NOT_FETCHED")
                .jsonPath("$.nextActions[0].path").isEqualTo(missing.path)
            missing.verifyReadOnly(1)
        }
    }

    @Test
    fun `public ingress rejects malformed UTF8 and JSON surrogate names`() {
        val fixture = AgentHttpFixture()
        val prefix = """{"path":"workspace/connectors/01942b04-6e00-7000-8000-000000000010/catalog/schemas/"""
        val malformedUtf8 = prefix.toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray()
        val malformedSurrogate = (prefix + "\\uD800\"}").toByteArray()
        listOf(malformedUtf8, malformedSurrogate).forEach { bytes ->
            fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/read")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue(bytes).exchange().expectStatus().isBadRequest
        }
    }

    @Test
    fun `the six Agent routes serialize through the public facade`() {
        val fixture = AgentHttpFixture()
        val requests = listOf(
            "list" to "{\"path\":\"workspace\",\"limit\":1}",
            "search" to "{\"path\":\"workspace\",\"queries\":[\"orders\"],\"limitPerQuery\":1}",
            "read" to "{\"path\":\"workspace\"}",
            "create" to "{\"path\":\"workspace/dashboards\",\"input\":{\"kind\":\"DASHBOARD_DEFINITION\",\"definition\":{\"schemaVersion\":1,\"meta\":{\"title\":\"Orders\"},\"parameters\":{},\"variables\":{},\"queries\":{},\"widgets\":{},\"layout\":{\"sections\":[]}}}}",
            "edit" to "{\"path\":\"workspace/dashboards/01942b04-6e00-7000-8000-000000000010/draft\",\"revision\":\"1\",\"patch\":[{\"op\":\"replace\",\"path\":\"/definition\",\"value\":{}}]}",
            "invoke" to "{\"path\":\"workspace/dashboards/01942b04-6e00-7000-8000-000000000010/draft/query-runs/01942b04-6e00-7000-8000-000000000011\",\"action\":\"cancel\"}",
        )

        requests.forEach { (operation, body) ->
            val response = fixture.client.post()
                .uri("/v2/workspaces/${fixture.workspaceId}/agent/$operation")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(if (operation == "invoke") 202 else 200)
                .expectHeader().contentType("application/json")
            if (operation == "invoke") response.expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "2")
            response
                .expectBody()
                .jsonPath("$.requestId").value<String> { value -> assertEquals(true, value.startsWith("srv_")) }

            assertEquals(1, fixture.ownerCallsFor(operation))
        }
    }

    @Test
    fun `exact Explore histories serialize long SQL and original semantic query without list inputs`() {
        val connectorId = Uuid.parse("01942b04-6e00-7000-8000-000000000010")
        val path = AgentResourcePath.parse("workspace/explore/runs/$connectorId")
        val time = Instant.parse("2026-09-06T00:00:00Z")
        val sql = "select '서울🙂' as city\n-- " + "x".repeat(1_048_576)
        val queryJson = """{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,
            "filters":[{"kind":"NUMBER","dimension":{"semanticModelName":"orders","name":"amount"},
                "operator":"EQUALS","values":["12345678901234567890.123456789012345678"]}],"orderBy":[],"rowLimit":100}"""
        val query = ((AgentWorkspaceContractDecoder().invoke("""{
            "path":"workspace/explore/runs","action":"run","input":{"kind":"SEMANTIC_EXPLORE_RUN",
                "connectorId":"$connectorId","query":$queryJson}}
        """).value.input as cc.monomer.agent.api.AgentInvokeInput.ExploreRun).request as cc.monomer.agent.api.AgentExploreRunRequest.Semantic).query
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        for (state in listOf("RUNNING", "SUCCEEDED", "FAILED", "CANCELLED")) {
            val failureCode = when (state) { "FAILED" -> "EXECUTION_FAILED"; "CANCELLED" -> "CANCELLED_BY_ACTOR"; else -> null }
            val failureDetail = failureCode?.let { "The run did not succeed." }
            val bodies = listOf(
                cc.monomer.agent.api.AgentExploreRunResourceBody.Sql(connectorId, state, failureCode, failureDetail, time, time, sql,
                    cc.monomer.identity.api.CredentialSurface.AGENT, cc.monomer.agent.api.AgentApp("https://claude.ai/oauth/mcp-oauth-client-metadata", "Claude", "claude.ai")),
                cc.monomer.agent.api.AgentExploreRunResourceBody.Semantic(connectorId, state, failureCode, failureDetail, time, time, sql, query,
                    cc.monomer.identity.api.CredentialSurface.WEB, null),
            )
            bodies.forEach { body ->
                val handler = mockk<AgentWorkspaceHandler>()
                coEvery { handler.read(any(), any()) } returns AgentResourceOutcome.Found(
                    AgentResourceEnvelope.ExploreRun(path, body, emptyList()),
                )
                val fixture = AgentHttpFixture(ownerHandler = handler)
                val response = fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/read")
                    .header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue("""{"path":"${path.value}"}""")
                    .exchange().expectStatus().isOk.expectBody().returnResult().responseBody!!
                val actual = mapper.readTree(response).path("body")
                assertEquals(sql, actual.path("sql").textValue())
                assertEquals(body.runKind, actual.path("runKind").textValue())
                assertEquals(state, actual.path("state").textValue())
                assertEquals(false, actual.has("parameters"))
                assertEquals(body is cc.monomer.agent.api.AgentExploreRunResourceBody.Semantic, actual.has("query"))
                if (body is cc.monomer.agent.api.AgentExploreRunResourceBody.Semantic) assertEquals(mapper.readTree(queryJson), actual.path("query"))
                assertEquals(failureCode != null, actual.has("failureCode"))
                assertEquals(failureDetail != null, actual.has("failureDetail"))
                assertEquals(body.credentialSurface?.name, actual.path("credentialSurface").textValue())
                if (body.agentApp == null) {
                    assertEquals(false, actual.has("agentApp"))
                } else {
                    assertEquals(mapper.readTree("""{"clientId":"https://claude.ai/oauth/mcp-oauth-client-metadata","name":"Claude","verifiedHost":"claude.ai"}"""), actual.path("agentApp"))
                }
            }
        }
    }

    @Test
    fun `legacy workspace route is not published`() {
        val fixture = AgentHttpFixture()

        fixture.client.post()
            .uri("/v2/workspaces/${fixture.workspaceId}/agent/workspace/write")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("{}")
            .exchange()
            .expectStatus().isNotFound

        assertEquals(0, fixture.totalOwnerCalls)
    }

    @Test
    fun `strict JSON and content type failures happen before admission`() {
        val fixture = AgentHttpFixture()

        fixture.client.post()
            .uri("/v2/workspaces/${fixture.workspaceId}/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("{\"path\":\"workspace\",\"path\":\"workspace\"}")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("AGENT_REQUEST_INVALID")

        fixture.client.post()
            .uri("/v2/workspaces/${fixture.workspaceId}/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "text/plain")
            .bodyValue("{\"path\":\"workspace\"}")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("AGENT_REQUEST_CONTENT_TYPE_INVALID")

        assertEquals(0, fixture.admissionCalls)
        assertEquals(0, fixture.totalOwnerCalls)
    }

    @Test
    fun `oversized paths and pointers return the Agent request problem before admission`() {
        val fixture = AgentHttpFixture()
        val connector = "workspace/connectors/01942b04-6e00-7000-8000-000000000010"
        listOf(
            "list" to """{"path":"workspace${"/a".repeat(100_000)}","limit":20}""",
            "read" to """{"path":"workspace/semantic-models/a${"_a".repeat(100_000)}"}""",
            "read" to """{"path":"$connector/catalog/catalogs/${"~0".repeat(100_000)}"}""",
            "invoke" to """{"path":"$connector/catalog/catalogs/${"a".repeat(200_000)}","action":"ingest"}""",
            "edit" to """{"path":"workspace/semantic-models/orders","aggregateMetrics":[],"patch":[{"op":"remove","path":"/entities${"/a".repeat(100_000)}"}]}""",
        ).forEach { (operation, body) ->
            fixture.client.post()
                .uri("/v2/workspaces/${fixture.workspaceId}/agent/$operation")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest
                .expectBody()
                .jsonPath("$.code").isEqualTo("AGENT_REQUEST_INVALID")
                .jsonPath("$.violations[0].code").isEqualTo("SCHEMA_MISMATCH")
        }

        assertEquals(0, fixture.admissionCalls)
        assertEquals(0, fixture.totalOwnerCalls)
    }

    @Test
    fun `missing Agent credential returns problem details with a server request id`() {
        val fixture = AgentHttpFixture(credentialMissing = true)

        fixture.client.post()
            .uri("/v2/workspaces/${fixture.workspaceId}/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("{\"path\":\"workspace\"}")
            .exchange()
            .expectStatus().isUnauthorized
            .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer resource_metadata=\"http://127.0.0.1:11077/.well-known/oauth-protected-resource/v2/workspaces\", scope=\"agent.workspace\"")
            .expectBody()
            .jsonPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED")
            .jsonPath("$.requestId").value<String> { value -> assertEquals(true, value.startsWith("srv_")) }

        assertEquals(0, fixture.ownerCalls)
    }

    @Test
    fun `admission outcomes preserve forbidden unavailable invalid request and evidence mismatch semantics`() {
        fun response(
            body: String,
            denial: AgentWorkspaceAdmissionDenial? = null,
            evidenceWorkspaceMismatch: Boolean = false,
        ) = AgentHttpFixture(
            admissionDenial = denial,
            evidenceWorkspaceMismatch = evidenceWorkspaceMismatch,
        ).client.post()
            .uri("/v2/workspaces/${AgentHttpFixture.WORKSPACE_ID}/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue(body)
            .exchange()

        response("{\"path\":\"workspace\"}", AgentWorkspaceAdmissionDenial.FORBIDDEN)
            .expectStatus().isForbidden
            .expectBody().jsonPath("$.code").isEqualTo("ROLE_REQUIRED")
        response("{\"path\":\"workspace\"}", AgentWorkspaceAdmissionDenial.UNAVAILABLE)
            .expectStatus().isEqualTo(503)
            .expectBody().jsonPath("$.code").isEqualTo("OWNER_UNAVAILABLE")
        response("{\"path\":\"workspace/unknown\"}")
            .expectStatus().isBadRequest
            .expectBody().jsonPath("$.code").isEqualTo("AGENT_REQUEST_INVALID")
        response("{\"path\":\"workspace\"}", evidenceWorkspaceMismatch = true)
            .expectStatus().isForbidden
            .expectBody().jsonPath("$.code").isEqualTo("AUTHORIZATION_EVIDENCE_MISMATCH")
    }

    @Test
    fun `owner response relation violations fail closed`() {
        val fixture = AgentHttpFixture(mismatchedRead = true)

        fixture.client.post()
            .uri("/v2/workspaces/${fixture.workspaceId}/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("{\"path\":\"workspace\"}")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.code").isEqualTo("AGENT_OWNER_RESPONSE_INVALID")
    }

    @Test
    fun `read preserves a declared owner conflict`() {
        val fixture = AgentHttpFixture(
            readProblem = AgentWorkspaceProblem(
                code = "EXPLORE_RESULT_UNAVAILABLE",
                title = "Explore operation rejected",
                detail = "Explore result preview is not available.",
                status = AgentWorkspaceProblemStatus.CONFLICT,
                retryable = false,
                currentRevision = null,
                violations = emptyList(),
                hints = emptyList(),
                nextActions = emptyList(),
                retryAfterSeconds = null,
                roleRequirement = null,
                partitionFilters = emptyList(),
            ),
        )

        fixture.client.post()
            .uri("/v2/workspaces/${fixture.workspaceId}/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("{\"path\":\"workspace\"}")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("EXPLORE_RESULT_UNAVAILABLE")
            .jsonPath("$.retryable").isEqualTo(false)
    }

    @Test
    fun `Semantic read serializes the definition itself and its last save beside it`() {
        val fixture = AgentHttpFixture(semanticRead = true)

        fixture.client.post()
            .uri("/v2/workspaces/${fixture.workspaceId}/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("{\"path\":\"workspace/semantic-models/orders\"}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.body.schemaVersion").isEqualTo(3)
            .jsonPath("$.body.name").isEqualTo("orders")
            .jsonPath("$.body.definition").doesNotExist()
            .jsonPath("$.body.semanticModelId").doesNotExist()
            .jsonPath("$.body.workspaceId").doesNotExist()
            .jsonPath("$.body.updatedBy").doesNotExist()
            .jsonPath("$.updatedAt").isEqualTo("2026-10-03T00:00:00Z")
            .jsonPath("$.updatedBy.id").isEqualTo("01900000-0000-7000-8000-00000000a11c")
            .jsonPath("$.updatedBy.displayName").isEqualTo("Mina Park")
            .jsonPath("$.credentialSurface").isEqualTo("AGENT")
            .jsonPath("$.agentApp.name").isEqualTo("Claude")
            .jsonPath("$.agentApp.verifiedHost").isEqualTo("claude.ai")
    }

    @Test
    fun `cross Workspace owner resources fail closed before serialization`() {
        val fixture = AgentHttpFixture(semanticRead = true, semanticWorkspaceMismatch = true)

        fixture.client.post()
            .uri("/v2/workspaces/${fixture.workspaceId}/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("{\"path\":\"workspace/semantic-models/orders\"}")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.code").isEqualTo("AGENT_OWNER_RESPONSE_INVALID")
    }

    @Test
    fun `Catalog relation read serializes the public compound address without technical ids`() {
        val fixture = AgentHttpFixture(catalogRead = true)

        fixture.client.post()
            .uri("/v2/workspaces/${fixture.workspaceId}/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("""{"path":"workspace/connectors/01942b04-6e00-7000-8000-000000000010/catalog/catalogs/warehouse/schemas/analytics/relations/orders"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.kind").isEqualTo("RELATION")
            .jsonPath("$.body.connectorId").isEqualTo("01942b04-6e00-7000-8000-000000000010")
            .jsonPath("$.body.catalogName").isEqualTo("warehouse")
            .jsonPath("$.body.comment").doesNotExist()
            .jsonPath("$.body.viewDialect").doesNotExist()
            .jsonPath("$.body.columns[0].name").isEqualTo("order_id")
            .jsonPath("$.body.columns[0].comment").doesNotExist()
            .jsonPath("$.body.columns[0].defaultValue").isEqualTo("nextval('orders_order_id_seq')")
            .jsonPath("$.body.resourceId").doesNotExist()
    }

    @Test
    fun `public request violations preserve enum required and patch repair coordinates safely`() {
        val fixture = AgentHttpFixture()
        fun post(operation: String, body: String) = fixture.client.post()
            .uri("/v2/workspaces/${fixture.workspaceId}/agent/$operation")
            .header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue(body).exchange()
        post("create", """{"path":"workspace/metrics","input":{"kind":"METRIC_DEFINITION","definition":{"schemaVersion":3,"name":"revenue","type":"simple","semanticModelName":"orders","timeGranularity":"day","filters":[]}}}""")
            .expectStatus().isBadRequest.expectBody()
            .jsonPath("$.violations[0].field").isEqualTo("input.definition.measure")
            .jsonPath("$.violations[0].code").isEqualTo("REQUIRED_FIELD_MISSING")
        post("search", """{"path":"workspace","queries":["orders"],"kinds":["secret-enum-value"],"limitPerQuery":1}""")
            .expectStatus().isBadRequest.expectBody()
            .jsonPath("$.violations[0].field").isEqualTo("kinds[0]")
            .jsonPath("$.violations[0].code").isEqualTo("INVALID_ENUM_VALUE")
        post("edit", """{"path":"workspace/semantic-models/orders", "aggregateMetrics": [],"patch":[{"op":"replace","path":"/label","value":"ok"},{"op":"secret-enum-value","path":"/measures/0/aggregation","value":"credential-sentinel"}]}""")
            .expectStatus().isBadRequest.expectBody()
            .jsonPath("$.violations[0].field").isEqualTo("patch[1].op")
            .jsonPath("$.violations[0].operationIndex").isEqualTo(1)
            .jsonPath("$.violations[0].path").isEqualTo("/measures/0/aggregation")
        post("edit", """{"path":"workspace/semantic-models/orders", "aggregateMetrics": [],"patch":[{"op":"secret-enum-value","path":"/credential-sentinel","value":"SELECT private_column FROM hidden_table"}]}""")
            .expectStatus().isBadRequest.expectBody(String::class.java).value { body ->
                listOf("secret-enum-value", "credential-sentinel", "private_column", "hidden_table").forEach {
                    kotlin.test.assertFalse(body.orEmpty().contains(it))
                }
            }
        post("edit", """{"path":"workspace/metrics/orders","patch":[{"op":"invalid","path":"/123456789012345678901234567890"}]}""")
            .expectStatus().isBadRequest.expectBody()
            .jsonPath("$.violations[0].operationIndex").isEqualTo(0)
            .jsonPath("$.violations[0].path").doesNotExist()
        assertEquals(0, fixture.admissionCalls)
        assertEquals(0, fixture.totalOwnerCalls)
    }

    @Test
    fun `semantic owner causes reach public violations without invented reference locations`() {
        val launch = io.mockk.mockk<cc.monomer.control.explore.ExploreSemanticLaunchService>()
        val adapter = cc.monomer.control.agent.AgentExploreOwnerAdapter(
            io.mockk.mockk(), io.mockk.mockk(), launch, java.time.Clock.systemUTC(), com.fasterxml.jackson.databind.ObjectMapper(),
            cc.monomer.control.agent.fixedAgentProvenanceNames(),
        )
        io.mockk.coEvery { launch.start(any()) } returns cc.monomer.control.explore.ExploreSemanticLaunchOutcome.Invalid(
            listOf(
                cc.monomer.semantic.api.SemanticInvalidReason(cc.monomer.semantic.api.SemanticInvalidReasonCode.METRIC_MODEL_NOT_FOUND, null),
                cc.monomer.semantic.api.SemanticInvalidReason(cc.monomer.semantic.api.SemanticInvalidReasonCode.QUERY_GROUP_BY_NOT_FOUND, "groupBys[0]"),
            ),
            listOf(
                cc.monomer.semantic.api.SemanticDefinitionIssue(
                    cc.monomer.semantic.api.SemanticInvalidReasonCode.METRIC_MODEL_NOT_FOUND,
                    cc.monomer.semantic.api.SemanticDefinitionReference.Metric(cc.monomer.semantic.api.SemanticElementName("revenue")),
                    "Metric `revenue` uses semantic model `orders`, which doesn't exist.",
                ),
                cc.monomer.semantic.api.SemanticDefinitionIssue(
                    cc.monomer.semantic.api.SemanticInvalidReasonCode.CATALOG_RELATION_NOT_FOUND,
                    null,
                    "This connector has no tables loaded in Catalog yet.",
                ),
            ),
        )
        val fixture = AgentHttpFixture(invokeOwner = adapter::invoke)
        fun invoke(target: AgentHttpFixture) = target.client.post()
            .uri("/v2/workspaces/${target.workspaceId}/agent/invoke")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("""{"path":"workspace/explore/runs","action":"run","input":{"kind":"SEMANTIC_EXPLORE_RUN","connectorId":"01942b04-6e00-7000-8000-000000000003","query":{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":100}}}""")
            .exchange()
        invoke(fixture).expectStatus().isBadRequest.expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_SEMANTIC_QUERY")
            .jsonPath("$.violations[0].code").isEqualTo("METRIC_MODEL_NOT_FOUND")
            .jsonPath("$.violations[0].field").isEqualTo("input.query")
            .jsonPath("$.violations[0].path").doesNotExist()
            .jsonPath("$.violations[1].field").isEqualTo("input.query.groupBys[0]")
            .jsonPath("$.violations[2].field").isEqualTo("workspace/metrics/revenue")
            .jsonPath("$.violations[2].code").isEqualTo("METRIC_MODEL_NOT_FOUND")
            .jsonPath("$.violations[2].message").isEqualTo("Metric `revenue` uses semantic model `orders`, which doesn't exist.")
            .jsonPath("$.violations[3].field").isEqualTo("input.query")
            .jsonPath("$.violations[3].code").isEqualTo("CATALOG_RELATION_NOT_FOUND")
            .jsonPath("$.violations.length()").isEqualTo(4)
        io.mockk.coEvery { launch.start(any()) } returns cc.monomer.control.explore.ExploreSemanticLaunchOutcome.NotFound
        invoke(fixture).expectStatus().isNotFound.expectBody().jsonPath("$.violations").doesNotExist()
        io.mockk.coEvery { launch.start(any()) } throws IllegalStateException("private SQL SELECT password FROM credentials; agent-token")
        invoke(fixture).expectStatus().isEqualTo(503).expectBody(String::class.java).value { body ->
            kotlin.test.assertFalse(body.orEmpty().contains("password"))
            kotlin.test.assertFalse(body.orEmpty().contains("agent-token"))
        }
        val denied = AgentHttpFixture(admissionDenial = AgentWorkspaceAdmissionDenial.FORBIDDEN, invokeOwner = adapter::invoke)
        invoke(denied).expectStatus().isForbidden.expectBody().jsonPath("$.violations").doesNotExist()
        assertEquals(0, denied.totalOwnerCalls)
    }

    @Test
    fun `Catalog filter kind mismatch keeps Agent 400 and the precise repair field`() {
        val launch = io.mockk.mockk<cc.monomer.control.explore.ExploreSemanticLaunchService>()
        val adapter = cc.monomer.control.agent.AgentExploreOwnerAdapter(
            io.mockk.mockk(), io.mockk.mockk(), launch, java.time.Clock.systemUTC(), com.fasterxml.jackson.databind.ObjectMapper(),
            cc.monomer.control.agent.fixedAgentProvenanceNames(),
        )
        io.mockk.coEvery { launch.start(any()) } returns cc.monomer.control.explore.ExploreSemanticLaunchOutcome.Invalid(
            listOf(cc.monomer.semantic.api.SemanticInvalidReason(
                cc.monomer.semantic.api.SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE, "filters[0].kind",
            )),
            emptyList(),
        )
        val fixture = AgentHttpFixture(invokeOwner = adapter::invoke)
        val body = """{"path":"workspace/explore/runs","action":"run","input":{"kind":"SEMANTIC_EXPLORE_RUN","connectorId":"01942b04-6e00-7000-8000-000000000003","query":{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[{"kind":"TEXT","dimension":{"semanticModelName":"orders","name":"amount"},"operator":"EQUALS","values":["1"]}],"orderBy":[],"rowLimit":100}}}"""
        fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/invoke")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(body).exchange()
            .expectStatus().isBadRequest.expectHeader().doesNotExist("Retry-After")
            .expectBody().jsonPath("$.code").isEqualTo("INVALID_SEMANTIC_QUERY")
            .jsonPath("$.retryable").isEqualTo(false)
            .jsonPath("$.violations[0].field").isEqualTo("input.query.filters[0].kind")
            .jsonPath("$.violations[0].code").isEqualTo("QUERY_FILTER_UNRENDERABLE")
            .jsonPath("$.violations[0].message").isEqualTo("Filter comparison type must match the column's Catalog type; time dimensions use DATE at day or coarser grain and TIMESTAMP at finer grain.")
            .jsonPath("$.violations[0].path").doesNotExist()
        assertEquals(1, fixture.admissionCalls)
        val denied = AgentHttpFixture(admissionDenial = AgentWorkspaceAdmissionDenial.FORBIDDEN, invokeOwner = adapter::invoke)
        denied.client.post().uri("/v2/workspaces/${denied.workspaceId}/agent/invoke")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(body).exchange()
            .expectStatus().isForbidden.expectBody().jsonPath("$.violations").doesNotExist()
        assertEquals(0, denied.totalOwnerCalls)
    }

    @Test
    fun `Catalog option LIST preserves unfetched conflict and capability scoped refresh recovery`() {
        listOf(true, false).flatMap { canRefresh ->
            listOf(false, true).map { catalogCandidates -> canRefresh to catalogCandidates }
        }.forEach { (canRefresh, catalogCandidates) ->
            val catalog = CatalogListFixture(canRefresh = canRefresh, catalogCandidates = catalogCandidates)
            val response = catalog.list().expectStatus().isEqualTo(409)
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.code").isEqualTo("CATALOG_SELECTION_OPTIONS_NOT_FETCHED")
                .jsonPath("$.detail").isEqualTo("Refresh this option collection before listing its children.")
                .jsonPath("$.retryable").isEqualTo(false)
                .jsonPath("$.requestId").value<String> { assertEquals(true, it.startsWith("srv_")) }
            if (canRefresh) {
                response.jsonPath("$.nextActions.length()").isEqualTo(1)
                    .jsonPath("$.nextActions[0].operation").isEqualTo("INVOKE")
                    .jsonPath("$.nextActions[0].path").isEqualTo(catalog.path)
                    .jsonPath("$.nextActions[0].action").isEqualTo("refresh")
            } else {
                response.jsonPath("$.nextActions").doesNotExist()
            }
            catalog.verifyReadOnly(listCalls = 1)
        }
    }

    @Test
    fun `Catalog option LIST returns fetched owner items through the public listing union`() {
        val catalog = CatalogListFixture(fetched = true)
        catalog.list().expectStatus().isOk.expectBody()
            .jsonPath("$.items.length()").isEqualTo(1)
            .jsonPath("$.items[0].kind").isEqualTo("CATALOG_SELECTION_OPTION")
            .jsonPath("$.items[0].name").isEqualTo("public")
            .jsonPath("$.items[0].path").isEqualTo("${catalog.path}/public")
            .jsonPath("$.items[0].available").isEqualTo(true)
            .jsonPath("$.body").doesNotExist()
        catalog.verifyReadOnly(listCalls = 1)
    }

    @Test
    fun `Catalog option LIST keeps absent owner and actual owner failure unavailable`() {
        listOf(true, false).forEach { missingOwner ->
            val catalog = CatalogListFixture(missingOwner = missingOwner)
            if (!missingOwner) {
                coEvery { catalog.refresh.findListOptionsAfter(AgentHttpFixture.WORKSPACE_ID, catalog.connectorId, CatalogListRefreshParent.Connector, null, 21) } throws
                    IllegalStateException("private SQL SELECT password FROM credentials; agent-token")
            }
            catalog.list().expectStatus().isEqualTo(503).expectBody()
                .jsonPath("$.code").isEqualTo("OWNER_UNAVAILABLE")
                .jsonPath("$.nextActions").doesNotExist()
                .jsonPath("$.detail").value<String> {
                    kotlin.test.assertFalse(it.contains("password"))
                    kotlin.test.assertFalse(it.contains("agent-token"))
                }
            catalog.verifyReadOnly(listCalls = if (missingOwner) 0 else 1)
        }
    }

    @Test
    fun `Catalog option LIST retains credential membership and connector existence boundaries`() {
        val unauthenticated = CatalogListFixture(credentialMissing = true)
        unauthenticated.list().expectStatus().isUnauthorized
        coVerify(exactly = 0) { unauthenticated.admission.authorizeWorkspaceRole(any()) }
        unauthenticated.verifyReadOnly(listCalls = 0)

        val denied = CatalogListFixture()
        coEvery { denied.admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Denied(cc.monomer.control.ControlAdmissionStage.IDENTITY,
                    cc.monomer.control.ControlAdmissionDenial.Identity(cc.monomer.identity.api.IdentityAdmissionDenial.PERMISSION_UNAVAILABLE))
        denied.list().expectStatus().isForbidden
        verify { denied.connectorRead wasNot Called }
        denied.verifyReadOnly(listCalls = 0)

        val missing = CatalogListFixture()
        coEvery { missing.connectorRead.get(any(), any()) } throws ConnectorNotFoundException("private connector name")
        missing.list().expectStatus().isNotFound
        missing.verifyReadOnly(listCalls = 0)
    }

    @Test
    fun `public Agent routes preserve owner conflicts exactly where OpenAPI declares them`() {
        val openApi = com.fasterxml.jackson.databind.ObjectMapper().readTree(
            java.nio.file.Path.of(System.getProperty("control.service.root")).parent
                .resolve("contracts/control-service.openapi.json").toFile(),
        )
        val requests = mapOf(
            "list" to """{"path":"workspace","limit":1}""",
            "search" to """{"path":"workspace","queries":["orders"],"limitPerQuery":1}""",
            "read" to """{"path":"workspace"}""",
            "create" to """{"path":"workspace/dashboards","input":{"kind":"DASHBOARD_DEFINITION","definition":{"schemaVersion":1,"meta":{"title":"Orders"},"parameters":{},"variables":{},"queries":{},"widgets":{},"layout":{"sections":[]}}}}""",
            "edit" to """{"path":"workspace/dashboards/01942b04-6e00-7000-8000-000000000010/draft","revision":"1","patch":[{"op":"replace","path":"/definition","value":{}}]}""",
            "invoke" to """{"path":"workspace/dashboards/01942b04-6e00-7000-8000-000000000010/draft/query-runs/01942b04-6e00-7000-8000-000000000011","action":"cancel"}""",
        )
        requests.forEach { (operation, body) ->
            val responses = openApi.path("paths").path("/v2/workspaces/{workspaceId}/agent/$operation")
                .path("post").path("responses")
            listOf(AgentWorkspaceProblemStatus.CONFLICT).forEach { status ->
                val problem = AgentWorkspaceProblem("OWNER_TEST_REJECTION", "Owner rejected", "Safe owner detail.", status, false, currentRevision = null, violations = emptyList(), hints = emptyList(), nextActions = emptyList(), retryAfterSeconds = null, roleRequirement = null, partitionFilters = emptyList())
                val owner = mockk<AgentWorkspaceHandler>()
                coEvery { owner.list(any(), any()) } returns AgentListingOutcome.Rejected(problem)
                coEvery { owner.search(any(), any()) } returns AgentSearchOutcome.Rejected(problem)
                coEvery { owner.read(any(), any()) } returns AgentResourceOutcome.Rejected(problem)
                coEvery { owner.create(any(), any()) } returns AgentActionOutcome.Rejected(problem)
                coEvery { owner.edit(any(), any()) } returns AgentActionOutcome.Rejected(problem)
                coEvery { owner.invoke(any(), any()) } returns AgentActionOutcome.Rejected(problem)
                val fixture = AgentHttpFixture(ownerHandler = owner)
                val declared = responses.has(status.value.toString())
                fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/$operation")
                    .header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue(body).exchange()
                    .expectStatus().isEqualTo(if (declared) status.value else 503).expectBody()
                    .jsonPath("$.code").isEqualTo(if (declared) "OWNER_TEST_REJECTION" else "AGENT_OWNER_RESPONSE_INVALID")
            }
        }
    }

    @Test
    fun `search rejects malformed batches and legacy fields before owner execution`() {
        val fixture = AgentHttpFixture()
        val invalid = listOf(
            "\"queries\":[]", "\"queries\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]",
            "\"queries\":[\" \"]", "\"queries\":[\"${"a".repeat(1025)}\"]",
            "\"queries\":[\"a\"],\"kinds\":[]", "\"queries\":[\"a\"],\"kinds\":[\"EXPLORE_RUN\"]",
            "\"queries\":[\"a\"],\"cursor\":\"next\"", "\"query\":\"a\"",
            "\"queries\":[\"a\"],\"limit\":1",
        ).map { "{\"path\":\"workspace\",$it,\"limitPerQuery\":1}" } + listOf(0, 21).map {
            """{"path":"workspace","queries":["a"],"limitPerQuery":$it}"""
        }
        invalid.forEach { body ->
            fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/search")
                .header(HttpHeaders.CONTENT_TYPE, "application/json").bodyValue(body).exchange()
                .expectStatus().isBadRequest
        }
        assertEquals(0, fixture.totalOwnerCalls)
    }

    @Test
    fun `search preserves query order duplicates and existing listing item shapes`() {
        val owner = mockk<AgentWorkspaceHandler>()
        val connectorPath = AgentResourcePath.parse("workspace/connectors/01942b04-6e00-7000-8000-000000000010")
        val item = AgentResourceListingItem.Connector(connectorPath, "POSTGRESQL", "Orders warehouse")
        coEvery { owner.search(any(), any()) } answers {
            val request = secondArg<AgentSearchRequest>()
            AgentSearchOutcome.Found(AgentSearchResponse(request.queries.mapIndexed { index, query ->
                AgentSearchQueryResult(query, if (index == 1) emptyList() else listOf(item))
            }))
        }
        val fixture = AgentHttpFixture(ownerHandler = owner)
        fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/search")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("""{"path":"workspace","queries":["orders","missing","orders"],"limitPerQuery":20}""").exchange()
            .expectStatus().isOk.expectBody()
            .jsonPath("$.results.length()").isEqualTo(3)
            .jsonPath("$.results[0].query").isEqualTo("orders")
            .jsonPath("$.results[1].query").isEqualTo("missing")
            .jsonPath("$.results[1].items.length()").isEqualTo(0)
            .jsonPath("$.results[2].query").isEqualTo("orders")
            .jsonPath("$.results[2].items[0].path").isEqualTo(connectorPath.value)
            .jsonPath("$.results[0].items[0].kind").isEqualTo("CONNECTOR")
            .jsonPath("$.results[0].items[0].connectorType").isEqualTo("POSTGRESQL")
            .jsonPath("$.results[0].items[0].displayName").isEqualTo("Orders warehouse")
            .jsonPath("$.nextCursor").doesNotExist()
        coVerify(exactly = 1) { owner.search(any(), match { it.queries == listOf("orders", "missing", "orders") && it.kinds == null && it.limitPerQuery == 20 }) }
    }

    @Test
    fun `REST search accepts PAGE kind and returns the existing Page listing contract`() {
        val owner = mockk<AgentWorkspaceHandler>()
        val pagePath = AgentResourcePath.parse("workspace/pages/01942b04-6e00-7000-8000-000000000010")
        val page = AgentResourceListingItem.Page(pagePath, cc.monomer.agent.api.AgentPageResourceBody(
            cc.monomer.agent.api.AgentPageKind.FOLDER, "Reports", null, "Reports", null, null, null, null, null, null, null,
        ))
        coEvery { owner.search(any(), any()) } returns AgentSearchOutcome.Found(AgentSearchResponse(listOf(AgentSearchQueryResult("Reports", listOf(page)))))
        val fixture = AgentHttpFixture(ownerHandler = owner)
        fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/search")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("""{"path":"workspace/pages","queries":["Reports"],"kinds":["PAGE"],"limitPerQuery":20}""").exchange()
            .expectStatus().isOk.expectBody()
            .jsonPath("$.results[0].items[0].kind").isEqualTo("PAGE")
            .jsonPath("$.results[0].items[0].path").isEqualTo(pagePath.value)
            .jsonPath("$.results[0].items[0].body.displayTitle").isEqualTo("Reports")
            .jsonPath("$.results[0].items[0].body.createdBy").doesNotExist()
        coVerify(exactly = 1) { owner.search(any(), match { it.path.value == "workspace/pages" && it.kinds == setOf(AgentResourceKind.PAGE) }) }
    }

    @Test
    fun `search failure returns a problem instead of empty successful results`() {
        val owner = mockk<AgentWorkspaceHandler>()
        coEvery { owner.search(any(), any()) } returns AgentSearchOutcome.Rejected(
            AgentWorkspaceProblem("SEARCH_UNAVAILABLE", "Search unavailable", "Current metadata could not be read.",
                AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE, false,
                currentRevision = null,
                violations = emptyList(),
                hints = emptyList(),
                nextActions = emptyList(),
                retryAfterSeconds = null,
                roleRequirement = null,
                partitionFilters = emptyList()),
        )
        val fixture = AgentHttpFixture(ownerHandler = owner)
        fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/search")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("""{"path":"workspace","queries":["orders","sales"],"limitPerQuery":1}""").exchange()
            .expectStatus().isEqualTo(503).expectBody()
            .jsonPath("$.code").isEqualTo("SEARCH_UNAVAILABLE")
            .jsonPath("$.results").doesNotExist()
            .jsonPath("$.retryable").isEqualTo(false)
    }

    @Test
    fun `search accepts five queries of maximum unicode character length`() {
        val fixture = AgentHttpFixture()
        val queries = List(5) { "\"${"𐐀".repeat(1024)}\"" }.joinToString(",")
        fixture.client.post().uri("/v2/workspaces/${fixture.workspaceId}/agent/search")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("""{"path":"workspace","queries":[$queries],"limitPerQuery":1}""").exchange()
            .expectStatus().isOk
        assertEquals(1, fixture.ownerCallsFor("search"))
    }

    private class CatalogListFixture(
        canRefresh: Boolean = true,
        fetched: Boolean = false,
        missingOwner: Boolean = false,
        credentialMissing: Boolean = false,
        catalogCandidates: Boolean = false,
        private val catalogAndSchema: Pair<String, String>? = null,
        optionName: String = "public",
    ) {
        val connectorId = ConnectorId(Uuid.parse("01942b04-6e00-7000-8000-000000000010"))
        val path = "workspace/connectors/${connectorId.value}/catalog/" +
            if (catalogAndSchema != null) "catalogs/${AgentResourcePath.encodeNameSegment(catalogAndSchema.first)}/selection/options/schemas/${AgentResourcePath.encodeNameSegment(catalogAndSchema.second)}/relations"
            else if (catalogCandidates) "catalog-selection/options/catalogs" else "selection/options/schemas"
        private val parent = catalogAndSchema?.let { CatalogListRefreshParent.Schema(it.first, it.second) } ?: CatalogListRefreshParent.Connector
        val refresh = mockk<CatalogListRefreshApplicationPort>()
        val admission = mockk<ControlAdmissionFacade>()
        val connectorRead = mockk<ConnectorReadUseCase>()
        private val browse = mockk<CatalogBrowseUseCase>()
        private val ingest = mockk<CatalogIngestApplicationPort>()
        private val http: AgentHttpFixture

        init {
            if (catalogAndSchema != null) {
                coEvery { ingest.currentConnectorCatalogSelection(AgentHttpFixture.WORKSPACE_ID, connectorId) } returns
                    cc.monomer.catalog.api.ConnectorCatalogSelectionView(AgentHttpFixture.WORKSPACE_ID, connectorId,
                        cc.monomer.catalog.api.ConnectorCatalogSelection(setOf(catalogAndSchema.first)), null)
            }
            coEvery { admission.authorizeWorkspaceRole(any()) } returns
                ControlAdmissionDecision.Admitted(mockk<IdentityRoleEvidence>())
            coEvery { admission.authorizeExecution(any()) } returns if (canRefresh) {
                ControlAdmissionDecision.Admitted(mockk<AuthorizedExecutionContext>())
            } else {
                ControlAdmissionDecision.Denied(cc.monomer.control.ControlAdmissionStage.IDENTITY,
                    cc.monomer.control.ControlAdmissionDenial.Identity(cc.monomer.identity.api.IdentityAdmissionDenial.PERMISSION_UNAVAILABLE))
            }
            coEvery { connectorRead.get(AgentHttpFixture.WORKSPACE_ID.toString(), connectorId.toString()) } returns ConnectorView(
                connectorId.toString(), AgentHttpFixture.WORKSPACE_ID.toString(), "warehouse", if (catalogCandidates || catalogAndSchema != null) "SNOWFLAKE" else "POSTGRESQL",
                emptyMap(), 0, 0, null,
            )
            coEvery { refresh.findListOptionsAfter(AgentHttpFixture.WORKSPACE_ID, connectorId, parent, null, 21) } returns CatalogListOptionPageView(
                CatalogListOptionPage(
                    catalogAndSchema?.let { CatalogListRefreshTarget.Schema(it.first, it.second) } ?: CatalogListRefreshTarget.Connector(if (catalogCandidates) CatalogListChildKind.CATALOG else CatalogListChildKind.SCHEMA),
                    if (fetched) Instant.parse("2026-09-06T00:00:00Z") else null,
                    if (fetched) listOf(CatalogListOption(optionName, if (catalogAndSchema != null) cc.monomer.catalog.api.RelationKind.TABLE else null, true)) else emptyList(),
                    if (fetched) 1 else 0, 1, 21, null,
                ),
            )
            val composition = AgentWorkspaceApplicationComposition()
            val owner = if (missingOwner) {
                val absentBrowse = mockk<org.springframework.beans.factory.ObjectProvider<CatalogBrowseUseCase>>()
                every { absentBrowse.ifAvailable } returns null
                composition.agentCatalogOwnerPort(absentBrowse, mockk(), mockk(), mockk(), admission, cc.monomer.control.agent.fixedAgentProvenanceNames())
            } else {
                AgentCatalogOwnerAdapter(browse, ingest, refresh, connectorRead, admission, cc.monomer.control.agent.fixedAgentProvenanceNames())
            }
            http = AgentHttpFixture(
                credentialMissing = credentialMissing,
                ownerHandler = composition.agentWorkspaceHandler(mockk(), mockk(), owner, mockk(), mockk(), mockk(), mockk { every { ifAvailable } returns mockk() }, managementOwnerPort = io.mockk.mockk { io.mockk.coEvery { read(any<cc.monomer.agent.api.AgentWorkspaceCaller>(), any()) } returns cc.monomer.agent.api.AgentResourceOutcome.Unavailable }),
            )
        }

        fun list() = http.client.post().uri("/v2/workspaces/${http.workspaceId}/agent/list")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("""{"path":${kotlinx.serialization.json.JsonPrimitive(path)},"limit":20}""")
            .exchange()

        fun verifyReadOnly(listCalls: Int) {
            coVerify(exactly = listCalls) {
                refresh.findListOptionsAfter(AgentHttpFixture.WORKSPACE_ID, connectorId, parent, null, 21)
            }
            confirmVerified(refresh)
            verify { browse wasNot Called }
            if (catalogAndSchema == null) verify { ingest wasNot Called }
            else {
                coVerify(exactly = listCalls) { ingest.currentConnectorCatalogSelection(AgentHttpFixture.WORKSPACE_ID, connectorId) }
                confirmVerified(ingest)
            }
            coVerify(exactly = 0) { admission.authorizeMutation(any()) }
        }
    }

    private class AgentHttpFixture(
        private val webOrigin: URI = URI("http://localhost:3000"),
        private val credentialMissing: Boolean = false,
        private val credentialInvalid: Boolean = false,
        private val decoderFailure: Boolean = false,
        private val mismatchedRead: Boolean = false,
        private val semanticRead: Boolean = false,
        private val catalogRead: Boolean = false,
        private val semanticWorkspaceMismatch: Boolean = false,
        private val readProblem: AgentWorkspaceProblem? = null,
        private val admissionDenial: AgentWorkspaceAdmissionDenial? = null,
        private val admissionProblem: AgentWorkspaceProblem? = null,
        private val evidenceWorkspaceMismatch: Boolean = false,
        private val ownerHandler: AgentWorkspaceHandler? = null,
        private val invokeOwner: (suspend (AgentWorkspaceCaller, AgentInvokeRequest) -> AgentActionOutcome)? = null,
    ) {
        val workspaceId = WORKSPACE_ID
        var admissionCalls = 0
        var ownerCalls = 0
        var decodeCalls = 0
        var credentialValidationCalls = 0
        private val callsByOperation = mutableMapOf<String, Int>()
        private val decoder = AgentWorkspaceContractDecoder()
        private val rootResource = AgentResourceEnvelope.Workspace(path = AgentResourcePath.parse("workspace"), body = cc.monomer.agent.api.AgentWorkspaceRootBody(cc.monomer.identity.api.WorkspaceRole.EXPLORER, emptyList()), availableOperations = emptyList())
        private val action = AgentActionResult.Create(path = AgentResourcePath.parse("workspace/dashboards"), result = AgentActionResultReference(
                kind = AgentResourceKind.DASHBOARD,
                path = AgentResourcePath.parse("workspace/dashboards/01942b04-6e00-7000-8000-000000000010"),
            ))
        private val semanticResource by lazy {
            val definition = (decoder.create(
                """
                {
                  "path":"workspace/semantic-models",
                  "input":{
                    "kind":"SEMANTIC_MODEL_DEFINITION", "aggregateMetrics": [],
                    "definition":{
                      "schemaVersion":3,
                      "name":"orders",
                      "sourceRelations":[{
                        "connectorId":"01942b04-6e00-7000-8000-000000000010",
                        "schema":"analytics",
                        "relation":"orders"
                      }],
                      "entities":[{"name":"order_id","type":"primary","expression":"order_id"}],
                      "dimensions":[],
                      "measures":[]
                    }
                  }
                }
                """.trimIndent(),
            ).value.input as AgentCreateInput.SemanticModel).definition
            AgentResourceEnvelope.SemanticModel(path = AgentResourcePath.parse("workspace/semantic-models/orders"), body = AgentSemanticModelResourceBody(
                    semanticModelId = Uuid.parse("01942b04-6e00-7000-8000-000000000020"),
                    workspaceId = if (semanticWorkspaceMismatch) WorkspaceId.generate() else workspaceId,
                    name = "orders",
                    definition = definition,
                    sourcePartitionInfo = emptyList(),
                ), availableOperations = emptyList(), updatedAt = Instant.parse("2026-10-03T00:00:00Z"),
                updatedBy = cc.monomer.agent.api.AgentActor("01900000-0000-7000-8000-00000000a11c", "Mina Park"),
                credentialSurface = cc.monomer.identity.api.CredentialSurface.AGENT,
                agentApp = cc.monomer.agent.api.AgentApp("https://claude.ai/oauth/mcp-oauth-client-metadata", "Claude", "claude.ai"))
        }
        private val catalogResource = AgentResourceEnvelope.Relation(path = AgentResourcePath.parse(
                "workspace/connectors/01942b04-6e00-7000-8000-000000000010/catalog/catalogs/warehouse/schemas/analytics/relations/orders",
            ), body = cc.monomer.agent.api.AgentCatalogResourceBody(
                connectorId = Uuid.parse("01942b04-6e00-7000-8000-000000000010"),
                catalogName = "warehouse",
                schemaName = "analytics",
                relationName = "orders",
                relationKind = "TABLE",
                viewDefinition = null,
                columns = listOf(
                    cc.monomer.agent.api.AgentCatalogColumn(
                        "order_id",
                        "BIGINT",
                        1,
                        false,
                        "nextval('orders_order_id_seq')",
                    ),
                ),
                primaryKey = cc.monomer.agent.api.AgentCatalogPrimaryKey("orders_pkey", listOf("order_id")),
                foreignKeys = emptyList(),
                partitionKeys = emptyList(), clusteringKeys = emptyList(), requiresPartitionFilter = null,
            ), availableOperations = emptyList())

        val client: WebTestClient = WebTestClient.bindToRouterFunction(
            AgentWorkspacePublicApiRouter(
                oauthResourceMetadataUrl = "http://127.0.0.1:11077/.well-known/oauth-protected-resource/v2/workspaces",
                agentPorts = AgentWorkspaceHttpPorts(
                    callerAuthenticator = AgentWorkspaceCallerAuthenticator(AdmissionPort()),
                    handler = ownerHandler ?: Handler(),
                    requestDecoder = Decoder(),
                ),
                properties = ControlPublicApiProperties(webOrigin),
                credentialResolver = object : AgentWorkspaceTransportCredentialResolver {
                    override suspend fun resolve(request: org.springframework.web.reactive.function.server.ServerRequest) =
                        if (credentialMissing) {
                            AgentWorkspaceTransportCredentialResolution.Missing
                        } else {
                            AgentWorkspaceTransportCredentialResolution.Resolved(PresentedCredential("agent-token"))
                        }
                },
                contractValidator = PublicControlContractValidator(),
                oauth = mockk<cc.monomer.identity.api.AgentOAuthPort>().also { port ->
                    coEvery { port.validateCredential(any(), cc.monomer.identity.api.ExpectedOAuthResource.AGENT_REST) } answers {
                        credentialValidationCalls += 1
                        if (credentialInvalid) cc.monomer.identity.api.OAuthOutcome.Failure(cc.monomer.identity.api.OAuthError.INVALID_GRANT)
                        else cc.monomer.identity.api.OAuthOutcome.Success(cc.monomer.identity.api.OAuthAgentIdentity(
                            PrincipalId.generate(), "https://example.test/agent"))
                    }
                },
            ).routes(),
        ).webFilter<org.springframework.test.web.reactive.server.WebTestClient.RouterFunctionSpec>(cc.monomer.control.observability.RequestCorrelationTestSupport.filter()).configureClient().codecs { it.defaultCodecs().maxInMemorySize(4 * 1024 * 1024) }.build()

        fun ownerCallsFor(operation: String): Int = callsByOperation[operation] ?: 0
        val totalOwnerCalls: Int get() = ownerCalls

        private fun count(operation: String) {
            ownerCalls += 1
            callsByOperation[operation] = (callsByOperation[operation] ?: 0) + 1
        }

        private inner class Decoder : AgentWorkspaceRequestDecoderPort {
            override fun list(body: String): AgentListRequest {
                decodeCalls += 1
                if (decoderFailure) throw IllegalArgumentException("sensitive-decoder-detail")
                return decoder.list(body)
            }
            override fun search(body: String): AgentSearchRequest {
                decodeCalls += 1
                if (decoderFailure) throw IllegalArgumentException("sensitive-decoder-detail")
                return decoder.search(body)
            }
            override fun read(body: String): AgentReadRequest {
                decodeCalls += 1
                if (decoderFailure) throw IllegalArgumentException("sensitive-decoder-detail")
                return decoder.read(body)
            }
            override fun create(body: String): AgentWorkspaceDecodedRequest<AgentCreateRequest> {
                decodeCalls += 1
                if (decoderFailure) throw IllegalArgumentException("sensitive-decoder-detail")
                return decoder.create(body)
            }
            override fun edit(body: String): AgentWorkspaceDecodedRequest<AgentEditRequest> {
                decodeCalls += 1
                if (decoderFailure) throw IllegalArgumentException("sensitive-decoder-detail")
                return decoder.edit(body)
            }
            override fun invoke(body: String): AgentWorkspaceDecodedRequest<AgentInvokeRequest> {
                decodeCalls += 1
                if (decoderFailure) throw IllegalArgumentException("sensitive-decoder-detail")
                return decoder.invoke(body)
            }
        }

        private inner class Handler : AgentWorkspaceHandler {
            override suspend fun list(caller: AgentWorkspaceCaller, request: AgentListRequest): AgentListingOutcome {
                count("list")
                return AgentListingOutcome.Found(AgentResourceListingResponse(emptyList(), null, emptyList()))
            }

            override suspend fun search(caller: AgentWorkspaceCaller, request: AgentSearchRequest): AgentSearchOutcome {
                count("search")
                return AgentSearchOutcome.Found(AgentSearchResponse(request.queries.map { AgentSearchQueryResult(it, emptyList()) }))
            }

            override suspend fun read(caller: AgentWorkspaceCaller, request: AgentReadRequest): AgentResourceOutcome {
                count("read")
                return if (readProblem != null) {
                    AgentResourceOutcome.Rejected(readProblem)
                } else if (catalogRead) {
                    AgentResourceOutcome.Found(catalogResource)
                } else if (semanticRead) {
                    AgentResourceOutcome.Found(semanticResource)
                } else if (mismatchedRead) {
                    AgentResourceOutcome.Found(rootResource.copy(path = AgentResourcePath.parse("workspace/other")))
                } else {
                    AgentResourceOutcome.Found(rootResource)
                }
            }

            override suspend fun create(caller: AgentWorkspaceCaller, request: AgentCreateRequest): AgentActionOutcome {
                count("create")
                return AgentActionOutcome.Completed(action.copy(path = request.path))
            }

            override suspend fun edit(caller: AgentWorkspaceCaller, request: AgentEditRequest): AgentActionOutcome {
                count("edit")
                return AgentActionOutcome.Completed(AgentActionResult.Edit(request.path, AgentActionResultReference(AgentResourceKind.DASHBOARD_DRAFT, request.path)))
            }

            override suspend fun invoke(caller: AgentWorkspaceCaller, request: AgentInvokeRequest): AgentActionOutcome {
                count("invoke")
                invokeOwner?.let { return it(caller, request) }
                return AgentActionOutcome.Completed(
                    AgentActionResult.Invoke(
                        path = request.path,
                        action = request.action,
                        status = AgentActionStatus.ACCEPTED,
                        result = AgentActionResultReference(AgentResourceKind.DASHBOARD_QUERY_RUN, request.path),
                    ),
                )
            }
        }

        private inner class AdmissionPort : AgentWorkspaceAdmissionPort {
            override suspend fun decide(request: AgentWorkspaceAdmissionRequest): AgentWorkspaceAdmissionDecision {
                admissionCalls += 1
                admissionDenial?.let { denial -> return AgentWorkspaceAdmissionDecision.Denied(denial, admissionProblem) }
                val principalId = PrincipalId(Uuid.generateV7())
                val evidenceWorkspaceId = if (evidenceWorkspaceMismatch) WorkspaceId.generate() else request.workspaceId
                val evidence = cc.monomer.agent.api.AgentWorkspaceRoleEvidence(
                    actor = CanonicalPrincipal(principalId, PrincipalKind.USER),
                    organizationId = OrganizationId(Uuid.generateV7()),
                    organizationPlan = cc.monomer.identity.api.OrganizationPlan.FREE,
                    workspaceId = evidenceWorkspaceId,
                    authorizationRevision = AuthorizationRevision(1),
                    credentialKind = CredentialKind.AGENT_OAUTH,
                    credentialSurface = CredentialSurface.AGENT,
                    agentClientId = cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"),
                    role = WorkspaceRole.OWNER,
                    intent = request.intent,
                )
                return AgentWorkspaceAdmissionDecision.Authorized(evidence)
            }


        }

        companion object {
            val WORKSPACE_ID = WorkspaceId(Uuid.parse("01942b04-6e00-7000-8000-000000000001"))
        }
    }
}
