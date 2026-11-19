@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.catalog.inbound.rest

import cc.monomer.catalog.api.CatalogIngestJobId
import cc.monomer.catalog.api.CatalogCommandResult
import cc.monomer.catalog.api.CatalogConflictReason
import cc.monomer.catalog.api.CatalogIngestEntry
import cc.monomer.catalog.api.CatalogIngestStarted
import cc.monomer.catalog.api.CatalogRelationSelection
import cc.monomer.catalog.api.CatalogSelectedSchema
import cc.monomer.catalog.api.CatalogSelection
import cc.monomer.catalog.api.CatalogSelectionView
import cc.monomer.catalog.api.ConnectorCatalogSelection
import cc.monomer.catalog.api.ConnectorCatalogSelectionView
import cc.monomer.catalog.api.SaveCatalogSelectionCommand
import cc.monomer.catalog.api.SaveConnectorCatalogSelectionCommand
import cc.monomer.catalog.domain.CatalogIngestErrorPage
import cc.monomer.catalog.domain.CatalogIngestJob
import cc.monomer.catalog.domain.CatalogIngestJobPage
import cc.monomer.catalog.domain.CatalogIngestJobSummary
import cc.monomer.catalog.domain.CatalogIngestTask
import cc.monomer.catalog.domain.CatalogIngestTaskTarget
import cc.monomer.catalog.domain.CatalogListOptionPage
import cc.monomer.catalog.domain.CatalogListRefreshJob
import cc.monomer.catalog.domain.CatalogListRefreshTarget
import cc.monomer.connector.api.ConnectorId
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionDenial
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.ControlAdmissionStage
import cc.monomer.control.human.auth.ConnectorCatalogProblemHandler
import cc.monomer.catalog.application.ingest.CatalogActiveIngestResult
import cc.monomer.catalog.application.ingest.CatalogIngestApplicationPort
import cc.monomer.catalog.application.ingest.CatalogListRefreshApplicationPort
import cc.monomer.catalog.application.ingest.CatalogListOptionPageView
import cc.monomer.catalog.application.ingest.CatalogListRefreshJobView
import cc.monomer.identity.api.AuthorizedExecutionContext
import cc.monomer.identity.api.AuthorizedMutationContext
import cc.monomer.identity.api.ExecutionIntent
import cc.monomer.identity.api.IdentityAdmissionDenial
import cc.monomer.identity.api.MutationIntent
import cc.monomer.identity.api.PrincipalDisplayNames
import cc.monomer.identity.api.WorkspaceId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

class CatalogAuthorityControllerContractTest {
    private val workspace = WorkspaceId(Uuid.generateV7())
    private val connector = ConnectorId.generate()
    private val catalogIngest = mockk<CatalogIngestApplicationPort>(relaxed = true)
    private val catalogListRefresh = mockk<CatalogListRefreshApplicationPort>(relaxed = true)
    private val admission = mockk<ControlAdmissionFacade>()
    private val displayNames = mockk<PrincipalDisplayNames>()
    private val client = WebTestClient
        .bindToController(CatalogAuthorityController(catalogIngest, catalogListRefresh, admission, displayNames, cc.monomer.control.agentapp.FixedAgentAppDisplays))
        .controllerAdvice(CatalogProblemHandler(), ConnectorCatalogProblemHandler())
        .build()

    @org.junit.jupiter.api.BeforeEach
    fun unknownRequesterNames() {
        coEvery { displayNames.read(any()) } returns emptyMap()
    }

    @Test
    fun `headerless canonical ingest returns the connector-scoped job location`() {
        val authorization = mockk<AuthorizedExecutionContext>(relaxed = true)
        io.mockk.every { authorization.agentClientId } returns null
        coEvery {
            admission.authorizeExecution(match { it.intent == ExecutionIntent.CATALOG_INGEST })
        } returns ControlAdmissionDecision.Admitted(authorization)
        coEvery {
            catalogIngest.startIngest(any())
        } returns CatalogCommandResult.Accepted(
            CatalogIngestStarted(
                jobId = cc.monomer.catalog.api.CatalogIngestJobId.generate(),
                workspaceId = workspace,
                connectorId = connector,
                entry = CatalogIngestEntry.Connector(connector),
            ),
        )

        client.post()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/ingests")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"entryKind\":\"CONNECTOR\",\"catalogName\":null}")
            .exchange()
            .expectStatus().isAccepted
            .expectHeader().valueMatches(HttpHeaders.LOCATION, ".*/catalog/connectors/$connector/ingests/.*")

        coVerify(exactly = 1) { catalogIngest.startIngest(any()) }
    }

    @Test
    fun `catalog ingest returns catalog body and retryable tracking headers`() {
        val authorization = mockk<AuthorizedExecutionContext>(relaxed = true)
        io.mockk.every { authorization.agentClientId } returns null
        val entry = CatalogIngestEntry.Catalog(connector, "analytics")
        val started = CatalogIngestStarted(
            jobId = cc.monomer.catalog.api.CatalogIngestJobId.generate(),
            workspaceId = workspace,
            connectorId = connector,
            entry = entry,
        )
        coEvery {
            admission.authorizeExecution(match { it.intent == ExecutionIntent.CATALOG_INGEST })
        } returns ControlAdmissionDecision.Admitted(authorization)
        coEvery { catalogIngest.startIngest(any()) } returns CatalogCommandResult.Accepted(started)

        client.post()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/ingests")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"entryKind\":\"CATALOG\",\"catalogName\":\"analytics\"}")
            .exchange()
            .expectStatus().isAccepted
            .expectHeader().valueMatches(HttpHeaders.LOCATION, ".*/catalog/connectors/$connector/ingests/${started.jobId}")
            .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "2")
            .expectBody()
            .jsonPath("$.jobId").isEqualTo(started.jobId.toString())
            .jsonPath("$.workspaceId").isEqualTo(workspace.toString())
            .jsonPath("$.connectorId").isEqualTo(connector.toString())
            .jsonPath("$.entryKind").isEqualTo("CATALOG")
            .jsonPath("$.catalogName").isEqualTo("analytics")

        coVerify(exactly = 1) {
            catalogIngest.startIngest(match { it.connectorId == connector && it.entry == entry })
        }
    }

    @Test
    fun `ingest membership denial returns not found without starting the owner`() {
        coEvery {
            admission.authorizeExecution(match { it.intent == ExecutionIntent.CATALOG_INGEST })
        } returns
            ControlAdmissionDecision.Denied(
                ControlAdmissionStage.IDENTITY,
                ControlAdmissionDenial.Identity(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE),
            )

        client.post()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/ingests")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"entryKind\":\"CONNECTOR\",\"catalogName\":null}")
            .exchange()
            .expectStatus().isNotFound
            .expectHeader().contentType("application/problem+json")
            .expectBody()
            .jsonPath("$.status").isEqualTo(404)
            .jsonPath("$.code").isEqualTo("NOT_FOUND")
            .jsonPath("$.instance").isEqualTo("/v2/workspaces/$workspace/catalog/connectors/$connector/ingests")
            .jsonPath("$.retryable").isEqualTo(false)

        coVerify(exactly = 0) { catalogIngest.startIngest(any()) }
    }

    @Test
    fun `selection mutation role denial returns role required without entering the owner`() {
        coEvery {
            admission.authorizeMutation(match { it.intent == MutationIntent.CATALOG_SELECTION_UPDATE })
        } returns ControlAdmissionDecision.Denied(
            ControlAdmissionStage.IDENTITY,
            ControlAdmissionDenial.Identity(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE),
        )

        client.put()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/selection")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"selectedSchemas\":[]}")
            .exchange()
            .expectStatus().isForbidden
            .expectHeader().contentType("application/problem+json")
            .expectBody()
            .jsonPath("$.status").isEqualTo(403)
            .jsonPath("$.code").isEqualTo("ROLE_REQUIRED")
            .jsonPath("$.instance").isEqualTo("/v2/workspaces/$workspace/catalog/connectors/$connector/selection")
            .jsonPath("$.retryable").isEqualTo(false)

        coVerify(exactly = 0) { catalogIngest.saveSelection(any()) }
    }

    @Test
    fun `catalog ingestion admission denial protects refresh and cancellation owners`() {
        coEvery {
            admission.authorizeExecution(match { it.intent == ExecutionIntent.CATALOG_INGEST })
        } returns ControlAdmissionDecision.Denied(
            ControlAdmissionStage.REQUEST,
            ControlAdmissionDenial.INVALID_EXECUTION_TARGET,
        )
        val refreshJobId = cc.monomer.catalog.api.CatalogListRefreshJobId.generate()
        val ingestJobId = cc.monomer.catalog.api.CatalogIngestJobId.generate()

        client.post()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/selection/list-refresh-jobs")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"parentKind\":\"SCHEMA\",\"catalogName\":null,\"schemaName\":\"public\"}")
            .exchange()
            .expectStatus().isForbidden
            .expectHeader().contentType("application/problem+json")
            .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN")

        client.post()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/selection/list-refresh-jobs/$refreshJobId/cancel")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isForbidden
            .expectHeader().contentType("application/problem+json")
            .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN")

        client.post()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/ingests/$ingestJobId:cancel")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isForbidden
            .expectHeader().contentType("application/problem+json")
            .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN")

        coVerify(exactly = 3) {
            admission.authorizeExecution(
                match { it.intent == ExecutionIntent.CATALOG_INGEST },
            )
        }
        coVerify(exactly = 0) { catalogListRefresh.startListRefresh(any()) }
        coVerify(exactly = 0) { catalogListRefresh.cancelListRefresh(any()) }
        coVerify(exactly = 0) { catalogIngest.cancelIngest(any()) }
    }

    @Test
    fun `catalog authority read admission preserves authentication membership and role problems`() {
        val path = "/v2/workspaces/$workspace/catalog/connectors/$connector/catalog-selection"
        for ((reason, status, code) in listOf(
            Triple(IdentityAdmissionDenial.CREDENTIAL_EXPIRED, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED"),
            Triple(IdentityAdmissionDenial.WORKSPACE_UNAVAILABLE, HttpStatus.NOT_FOUND, "NOT_FOUND"),
            Triple(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE, HttpStatus.FORBIDDEN, "ROLE_REQUIRED"),
        )) {
            coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Denied(
                ControlAdmissionStage.IDENTITY,
                ControlAdmissionDenial.Identity(reason),
            )

            val response = client.get().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .exchange()
                .expectStatus().isEqualTo(status)
                .expectHeader().contentType("application/problem+json")
            if (status == HttpStatus.UNAUTHORIZED) {
                response.expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            }
            response.expectBody()
                .jsonPath("$.status").isEqualTo(status.value())
                .jsonPath("$.code").isEqualTo(code)
                .jsonPath("$.instance").isEqualTo(path)
                .jsonPath("$.requestId").exists()
                .jsonPath("$.traceId").exists()
                .jsonPath("$.retryable").isEqualTo(false)
        }

        coVerify(exactly = 0) { catalogIngest.currentConnectorCatalogSelection(workspace, connector) }
    }

    @Test
    fun `catalog owner invalid authorization remains a forbidden problem`() {
        coEvery { admission.authorizeExecution(any()) } returns
            ControlAdmissionDecision.Admitted(mockk<AuthorizedExecutionContext>(relaxed = true))
        coEvery { catalogIngest.startIngest(any()) } returns CatalogCommandResult.InvalidAuthorization

        client.post()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/ingests")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"entryKind\":\"CONNECTOR\",\"catalogName\":null}")
            .exchange()
            .expectStatus().isForbidden
            .expectHeader().contentType("application/problem+json")
            .expectBody()
            .jsonPath("$.status").isEqualTo(403)
            .jsonPath("$.code").isEqualTo("FORBIDDEN")
    }

    @Test
    fun `job reads require fresh connector-scoped authorization`() {
        val requestedJobId = cc.monomer.catalog.api.CatalogIngestJobId.generate()
        client.get()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/ingests/$requestedJobId")
            .exchange()
            .expectStatus().isUnauthorized
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            .expectBody()
            .jsonPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED")
            .jsonPath("$.requestId").isNotEmpty

        coVerify(exactly = 0) { catalogIngest.findJob(workspace, requestedJobId) }
    }

    @Test
    fun `entry without an active ingest answers no content without a body`() {
        admitMembership()
        val entry = CatalogIngestEntry.Connector(connector)
        coEvery { catalogIngest.findActive(workspace, connector, entry) } returns
            CatalogActiveIngestResult.NoActiveIngest

        client.get()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/ingests/active")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isNoContent
            .expectHeader().doesNotExist(HttpHeaders.CONTENT_TYPE)
            .expectBody().isEmpty

        coVerify(exactly = 1) { catalogIngest.findActive(workspace, connector, entry) }
    }

    @Test
    fun `unknown active ingest entry is a declared problem response`() {
        admitMembership()
        val entry = CatalogIngestEntry.Catalog(connector, "archive")
        coEvery { catalogIngest.findActive(workspace, connector, entry) } returns
            CatalogActiveIngestResult.EntryNotFound

        client.get()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/ingests/active?catalogName=archive")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isNotFound
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.code").isEqualTo("NOT_FOUND")
            .jsonPath("$.retryable").isEqualTo(false)

        coVerify(exactly = 1) { catalogIngest.findActive(workspace, connector, entry) }
    }

    @Test
    fun `active and detail ingest reads map bounded tasks and aggregates`() {
        admitMembership()
        val requestedAt = Instant.parse("2026-08-21T01:02:03Z")
        val entry = CatalogIngestEntry.Catalog(connector, "analytics")
        val job = CatalogIngestJob.request(
            id = cc.monomer.catalog.api.CatalogIngestJobId.generate(),
            workspaceId = workspace,
            connectorId = connector,
            selection = CatalogSelection(
                setOf(
                    CatalogSelectedSchema(
                        schemaName = "marketing",
                        relations = CatalogRelationSelection.AllRelations,
                    ),
                ),
            ),
            entry = entry,
            requestedBy = cc.monomer.identity.api.PrincipalId.generate(),
            requestedAt = requestedAt,
            credentialSurface = null,
            agentClientId = null,
        ).claim("runner-a", requestedAt)
        val task = CatalogIngestTask.start(
            job = job,
            parentTaskId = null,
            target = CatalogIngestTaskTarget.Relation(
                catalogName = "analytics",
                schemaName = "marketing",
                name = "campaigns",
                kind = cc.monomer.catalog.api.RelationKind.TABLE,
            ),
            at = requestedAt,
        )
        val view = cc.monomer.catalog.application.ingest.CatalogIngestJobView(
            job = job,
            refreshedRelationCount = 23,
            runningTaskCount = 7,
            errorCount = 2,
            tasks = listOf(task),
            loadingTaskIds = setOf(task.id),
        )
        coEvery { catalogIngest.findActive(workspace, connector, entry) } returns
            CatalogActiveIngestResult.Active(view)
        coEvery { catalogIngest.findJob(workspace, job.id) } returns view

        client.get()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/ingests/active?catalogName=analytics")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectBody()
            .jsonPath("$.jobId").isEqualTo(job.id.toString())
            .jsonPath("$.entryKind").isEqualTo("CATALOG")
            .jsonPath("$.catalogName").isEqualTo("analytics")
            .jsonPath("$.state").isEqualTo("RUNNING")
            .jsonPath("$.refreshedRelationCount").isEqualTo(23)
            .jsonPath("$.runningTaskCount").isEqualTo(7)
            .jsonPath("$.errorCount").isEqualTo(2)
            .jsonPath("$.tasks[0].targetKind").isEqualTo("RELATION")
            .jsonPath("$.tasks[0].catalogName").isEqualTo("analytics")
            .jsonPath("$.tasks[0].schemaName").isEqualTo("marketing")
            .jsonPath("$.tasks[0].relationName").isEqualTo("campaigns")
            .jsonPath("$.tasks[0].relationKind").isEqualTo("TABLE")
            .jsonPath("$.tasks[0].state").isEqualTo("RUNNING")
            .jsonPath("$.tasks[0].loading").isEqualTo(true)
            .jsonPath("$.requestedBy.principalId").isEqualTo(job.requestedBy.toString())
            .jsonPath("$.requestedBy.displayName").doesNotExist()

        client.get()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/ingests/${job.id}")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.jobId").isEqualTo(job.id.toString())
            .jsonPath("$.tasks[0].state").isEqualTo("RUNNING")

        coVerify(exactly = 1) { catalogIngest.findActive(workspace, connector, entry) }
        coVerify(exactly = 1) { catalogIngest.findJob(workspace, job.id) }
    }

    @Test
    fun `entry refresh history exposes bounded progress aggregates`() {
        admitMembership()
        val requester = cc.monomer.identity.api.PrincipalId.generate()
        val requestedAt = Instant.parse("2026-08-21T01:02:03Z")
        val job = CatalogIngestJob.request(
            id = cc.monomer.catalog.api.CatalogIngestJobId.generate(),
            workspaceId = workspace,
            connectorId = connector,
            selection = CatalogSelection(
                setOf(
                    CatalogSelectedSchema(
                        schemaName = "marketing",
                        relations = CatalogRelationSelection.AllRelations,
                    ),
                ),
            ),
            entry = CatalogIngestEntry.Catalog(connector, "analytics"),
            requestedBy = requester,
            requestedAt = requestedAt,
            credentialSurface = cc.monomer.identity.api.CredentialSurface.AGENT,
            agentClientId = cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"),
        )
        coEvery { displayNames.read(setOf(requester.value)) } returns mapOf(requester.value to "Sam Kim")
        coEvery {
            catalogIngest.findJobs(workspace, connector, CatalogIngestEntry.Catalog(connector, "analytics"), 1, 10)
        } returns CatalogIngestJobPage(
            jobs = listOf(CatalogIngestJobSummary(job, 23, 7, 2)),
            totalCount = 14,
            page = 1,
            pageSize = 10,
        )

        client.get()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/ingests?catalogName=analytics&page=1&pageSize=10")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.totalCount").isEqualTo(14)
            .jsonPath("$.jobs[0].catalogName").isEqualTo("analytics")
            .jsonPath("$.jobs[0].refreshedRelationCount").isEqualTo(23)
            .jsonPath("$.jobs[0].runningTaskCount").isEqualTo(7)
            .jsonPath("$.jobs[0].errorCount").isEqualTo(2)
            .jsonPath("$.jobs[0].requestedBy.principalId").isEqualTo(requester.toString())
            .jsonPath("$.jobs[0].requestedBy.displayName").isEqualTo("Sam Kim")
            .jsonPath("$.jobs[0].requestedBy.credentialSurface").isEqualTo("AGENT")
            .jsonPath("$.jobs[0].tasks").doesNotExist()
    }

    @Test
    fun `refresh errors expose only safe task details`() {
        admitMembership()
        val requestedAt = Instant.parse("2026-08-21T01:02:03Z")
        val job = CatalogIngestJob.request(
            id = cc.monomer.catalog.api.CatalogIngestJobId.generate(),
            workspaceId = workspace,
            connectorId = connector,
            selection = CatalogSelection(
                setOf(
                    CatalogSelectedSchema(
                        schemaName = "public",
                        relations = CatalogRelationSelection.AllRelations,
                    ),
                ),
            ),
            entry = CatalogIngestEntry.Connector(connector),
            requestedBy = cc.monomer.identity.api.PrincipalId.generate(),
            requestedAt = requestedAt,
            credentialSurface = null,
            agentClientId = null,
        ).claim("runner-a", requestedAt)
        val error = CatalogIngestTask.start(
            job = job,
            parentTaskId = null,
            target = CatalogIngestTaskTarget.Relation(null, "public", "orders", cc.monomer.catalog.api.RelationKind.TABLE),
            at = requestedAt,
        ).fail(
            code = "WAREHOUSE_QUERY_FAILED",
            message = "Metadata query timed out.",
            at = requestedAt.plusSeconds(3),
            runId = null,
        )
        coEvery { catalogIngest.findErrors(workspace, connector, job.id, 1, 10) } returns
            CatalogIngestErrorPage(listOf(error), 1, 1, 10)

        client.get()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/ingests/${job.id}/errors")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.totalCount").isEqualTo(1)
            .jsonPath("$.errors[0].schemaName").isEqualTo("public")
            .jsonPath("$.errors[0].relationName").isEqualTo("orders")
            .jsonPath("$.errors[0].safeMessage").isEqualTo("Metadata query timed out.")
            .jsonPath("$.errors[0].activeRunId").doesNotExist()
            .jsonPath("$.errors[0].failureRunId").doesNotExist()
    }

    @Test
    fun `selection read exposes explicit relation coverage without a revision concept`() {
        admitMembership()
        coEvery { catalogIngest.currentSelection(workspace, connector, CatalogIngestEntry.Connector(connector)) } returns CatalogSelectionView(
            workspaceId = workspace,
            connectorId = connector,
            entry = CatalogIngestEntry.Connector(connector),
            selection = CatalogSelection(
                selectedSchemas = setOf(
                    CatalogSelectedSchema(
                        schemaName = "analytics",
                        relations = CatalogRelationSelection.SelectedRelations(setOf("orders")),
                    ),
                ),
            ),
            committedAt = Instant.parse("2026-08-19T00:00:00Z"),
        )

        client.get()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/selection")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.selectedSchemas[0].schemaName").isEqualTo("analytics")
            .jsonPath("$.selectedSchemas[0].relations.kind").isEqualTo("SELECTED_RELATIONS")
            .jsonPath("$.selectedSchemas[0].relations.relationNames[0]").isEqualTo("orders")
            .jsonPath("$.selectionRevisionId").doesNotExist()
    }

    @Test
    fun `catalog selection route owns catalog identity and uses the direct selection document`() {
        val catalog = "analytics"
        val authorization = mockk<AuthorizedMutationContext>(relaxed = true)
        io.mockk.every { authorization.agentClientId } returns null
        val entry = CatalogIngestEntry.Catalog(connector, catalog)
        coEvery {
            admission.authorizeMutation(match { it.intent == MutationIntent.CATALOG_SELECTION_UPDATE })
        } returns ControlAdmissionDecision.Admitted(authorization)
        coEvery {
            catalogIngest.saveSelection(match { command: SaveCatalogSelectionCommand -> command.entry == entry })
        } returns CatalogCommandResult.Accepted(
            CatalogSelectionView(
                workspaceId = workspace,
                connectorId = connector,
                entry = entry,
                selection = CatalogSelection(
                    selectedSchemas = setOf(
                        CatalogSelectedSchema(
                            schemaName = "marketing",
                            relations = CatalogRelationSelection.SelectedRelations(setOf("campaigns")),
                        ),
                    ),
                ),
                committedAt = Instant.parse("2026-08-19T00:00:00Z"),
            ),
        )

        client.put()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/catalogs/$catalog/selection")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"selectedSchemas":[{"schemaName":"marketing","relations":{"kind":"SELECTED_RELATIONS","relationNames":["campaigns"]}}]}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.selectedSchemas[0].schemaName").isEqualTo("marketing")
            .jsonPath("$.selectedSchemas[0].catalogName").doesNotExist()
            .jsonPath("$.selectedSchemas[0].relations.kind").isEqualTo("SELECTED_RELATIONS")
            .jsonPath("$.selection").doesNotExist()
            .jsonPath("$.workspaceId").doesNotExist()
            .jsonPath("$.connectorId").doesNotExist()

        coVerify(exactly = 1) {
            catalogIngest.saveSelection(match { command: SaveCatalogSelectionCommand ->
                command.entry == entry && command.selection.selectedSchemas.single().schemaName == "marketing"
            })
        }
    }

    @Test
    fun `selection rejects duplicate schema names before set normalization`() {
        val authorization = mockk<AuthorizedMutationContext>(relaxed = true)
        io.mockk.every { authorization.agentClientId } returns null
        coEvery { admission.authorizeMutation(any()) } returns ControlAdmissionDecision.Admitted(authorization)

        client.put()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/selection")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"selectedSchemas":[{"schemaName":"analytics","relations":{"kind":"ALL_RELATIONS"}},{"schemaName":"analytics","relations":{"kind":"ALL_RELATIONS"}}]}""",
            )
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")

        coVerify(exactly = 0) { catalogIngest.saveSelection(any()) }
    }

    @Test
    fun `selection rejects duplicate relation names before set normalization`() {
        val authorization = mockk<AuthorizedMutationContext>(relaxed = true)
        io.mockk.every { authorization.agentClientId } returns null
        coEvery { admission.authorizeMutation(any()) } returns ControlAdmissionDecision.Admitted(authorization)

        client.put()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/selection")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"selectedSchemas":[{"schemaName":"analytics","relations":{"kind":"SELECTED_RELATIONS","relationNames":["orders","orders"]}}]}""",
            )
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")

        coVerify(exactly = 0) { catalogIngest.saveSelection(any()) }
    }

    @Test
    fun `selection rejects ALL_RELATIONS with relation names before owner`() {
        val authorization = mockk<AuthorizedMutationContext>(relaxed = true)
        io.mockk.every { authorization.agentClientId } returns null
        coEvery { admission.authorizeMutation(any()) } returns ControlAdmissionDecision.Admitted(authorization)

        client.put()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/selection")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"selectedSchemas":[{"schemaName":"analytics","relations":{"kind":"ALL_RELATIONS","relationNames":["orders"]}}]}""",
            )
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")

        coVerify(exactly = 0) { catalogIngest.saveSelection(any()) }
    }

    @Test
    fun `selection rejects SELECTED_RELATIONS with null relation names before owner`() {
        val authorization = mockk<AuthorizedMutationContext>(relaxed = true)
        io.mockk.every { authorization.agentClientId } returns null
        coEvery { admission.authorizeMutation(any()) } returns ControlAdmissionDecision.Admitted(authorization)

        client.put()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/selection")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"selectedSchemas":[{"schemaName":"analytics","relations":{"kind":"SELECTED_RELATIONS","relationNames":null}}]}""",
            )
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")

        coVerify(exactly = 0) { catalogIngest.saveSelection(any()) }
    }

    @Test
    fun `connector Catalog selection exposes the complete selected name set`() {
        admitMembership()
        coEvery { catalogIngest.currentConnectorCatalogSelection(workspace, connector) } returns
            ConnectorCatalogSelectionView(
                workspaceId = workspace,
                connectorId = connector,
                selection = ConnectorCatalogSelection(setOf("warehouse", "finance")),
                committedAt = Instant.parse("2026-09-03T00:00:00Z"),
            )

        client.get()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/catalog-selection")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.selectedCatalogNames[0]").isEqualTo("finance")
            .jsonPath("$.selectedCatalogNames[1]").isEqualTo("warehouse")
            .jsonPath("$.committedAt").isEqualTo("2026-09-03T00:00:00Z")
            .jsonPath("$.selection").doesNotExist()
            .jsonPath("$.workspaceId").doesNotExist()
            .jsonPath("$.connectorId").doesNotExist()

        coVerify(exactly = 1) { catalogIngest.currentConnectorCatalogSelection(workspace, connector) }
    }

    @Test
    fun `connector Catalog selection accepts an empty complete set`() {
        val authorization = mockk<AuthorizedMutationContext>(relaxed = true)
        io.mockk.every { authorization.agentClientId } returns null
        coEvery { admission.authorizeMutation(any()) } returns ControlAdmissionDecision.Admitted(authorization)
        coEvery {
            catalogIngest.saveConnectorCatalogSelection(
                match { command: SaveConnectorCatalogSelectionCommand ->
                    command.connectorId == connector && command.selection.selectedCatalogNames.isEmpty()
                },
            )
        } returns CatalogCommandResult.Accepted(
            ConnectorCatalogSelectionView(
                workspaceId = workspace,
                connectorId = connector,
                selection = ConnectorCatalogSelection(emptySet()),
                committedAt = Instant.parse("2026-09-03T00:00:00Z"),
            ),
        )

        client.put()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/catalog-selection")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"selectedCatalogNames":[]}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.selectedCatalogNames").isEmpty
    }

    @Test
    fun `connector Catalog selection reports active ingestion conflict with a safe next step`() {
        val authorization = mockk<AuthorizedMutationContext>(relaxed = true)
        io.mockk.every { authorization.agentClientId } returns null
        coEvery { admission.authorizeMutation(any()) } returns ControlAdmissionDecision.Admitted(authorization)
        coEvery { catalogIngest.saveConnectorCatalogSelection(any()) } returns
            CatalogCommandResult.ConflictWithReason(CatalogConflictReason.ACTIVE_INGESTION)

        client.put()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/catalog-selection")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"selectedCatalogNames":[]}""")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.code").isEqualTo("CATALOG_SELECTION_ACTIVE_INGESTION")
            .jsonPath("$.detail").isEqualTo(
                "Finish or cancel the active Catalog ingestion before changing Catalog selection.",
            )
            .jsonPath("$.type").isEqualTo(
                "https://monomer.cc/problems/catalog-selection-active-ingestion",
            )
    }

    @Test
    fun `connector Catalog selection reports stale candidate as a distinct safe conflict`() {
        val authorization = mockk<AuthorizedMutationContext>(relaxed = true)
        io.mockk.every { authorization.agentClientId } returns null
        coEvery { admission.authorizeMutation(any()) } returns ControlAdmissionDecision.Admitted(authorization)
        coEvery { catalogIngest.saveConnectorCatalogSelection(any()) } returns
            CatalogCommandResult.ConflictWithReason(CatalogConflictReason.INVALID_CANDIDATE)

        client.put()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/catalog-selection")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"selectedCatalogNames":["stale"]}""")
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.code").isEqualTo("CATALOG_SELECTION_INVALID_CANDIDATE")
            .jsonPath("$.detail").isEqualTo(
                "The Catalog selection is stale or invalid. Refresh the Catalog options and try again.",
            )
    }

    @Test
    fun `connector Catalog selection rejects duplicate names before set normalization`() {
        val authorization = mockk<AuthorizedMutationContext>(relaxed = true)
        io.mockk.every { authorization.agentClientId } returns null
        coEvery { admission.authorizeMutation(any()) } returns ControlAdmissionDecision.Admitted(authorization)

        client.put()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/catalog-selection")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"selectedCatalogNames":["warehouse","warehouse"]}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")

        coVerify(exactly = 0) { catalogIngest.saveConnectorCatalogSelection(any()) }
    }

    @Test
    fun `catalog selection read is scoped by the catalog path`() {
        admitMembership()
        val entry = CatalogIngestEntry.Catalog(connector, "analytics")
        coEvery { catalogIngest.currentSelection(workspace, connector, entry) } returns CatalogSelectionView(
            workspaceId = workspace,
            connectorId = connector,
            entry = entry,
            selection = CatalogSelection(emptySet()),
            committedAt = null,
        )

        client.get()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/catalogs/analytics/selection")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.selectedSchemas").isArray
            .jsonPath("$.selectedSchemas").isEmpty
            .jsonPath("$.committedAt").doesNotExist()

        coVerify(exactly = 1) { catalogIngest.currentSelection(workspace, connector, entry) }
    }

    @Test
    fun `selection list refresh returns a safe durable job view`() {
        val authorization = mockk<AuthorizedExecutionContext>(relaxed = true)
        io.mockk.every { authorization.agentClientId } returns null
        val job = CatalogListRefreshJob.request(
            id = cc.monomer.catalog.api.CatalogListRefreshJobId.generate(),
            workspaceId = workspace,
            connectorId = connector,
            target = CatalogListRefreshTarget.Schema(null, "public"),
            requestedBy = cc.monomer.identity.api.PrincipalId.generate(),
            requestedAt = Instant.parse("2026-08-20T00:00:00Z"),
            credentialSurface = null,
            agentClientId = null,
        )
        coEvery {
            admission.authorizeExecution(match { it.intent == ExecutionIntent.CATALOG_INGEST })
        } returns ControlAdmissionDecision.Admitted(authorization)
        coEvery { catalogListRefresh.startListRefresh(any()) } returns
            CatalogCommandResult.Accepted(CatalogListRefreshJobView(job))

        client.post()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/selection/list-refresh-jobs")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"parentKind\":\"SCHEMA\",\"catalogName\":null,\"schemaName\":\"public\"}")
            .exchange()
            .expectStatus().isAccepted
            .expectBody()
            .jsonPath("$.state").isEqualTo("RUNNING")
            .jsonPath("$.childKind").isEqualTo("RELATION")
            .jsonPath("$.runnerInstanceId").doesNotExist()
            .jsonPath("$.activeRunId").doesNotExist()
    }

    @Test
    fun `selection options exposes cached page and latest safe job`() {
        admitMembership()
        coEvery {
            catalogListRefresh.findListOptions(workspace, connector, any(), 1, 50, any())
        } returns
            CatalogListOptionPageView(
                CatalogListOptionPage(
                    target = CatalogListRefreshTarget.Schema(null, "public"),
                    fetchedAt = Instant.parse("2026-08-20T00:00:00Z"),
                    options = emptyList(),
                    totalCount = 0,
                    page = 1,
                    pageSize = 50,
                    latestJob = null,
                ),
            )

        client.get()
            .uri("/v2/workspaces/$workspace/catalog/connectors/$connector/selection/options?parentKind=SCHEMA&schemaName=public")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.childKind").isEqualTo("RELATION")
            .jsonPath("$.fetchedAt").isEqualTo("2026-08-20T00:00:00Z")
            .jsonPath("$.runnerInstanceId").doesNotExist()
    }

    @Test
    fun `human catalog list pages reject zero and sizes outside the public bound before owner reads`() {
        admitMembership()
        val jobId = CatalogIngestJobId.generate()
        val paths = listOf(
            "selection/options?parentKind=SCHEMA&schemaName=public&",
            "ingests?",
            "ingests/${jobId.value}/errors?",
        )
        for (path in paths) {
            for (query in listOf("page=0", "pageSize=0", "pageSize=201")) {
                client.get().uri("/v2/workspaces/$workspace/catalog/connectors/$connector/$path$query")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                    .exchange()
                    .expectStatus().isBadRequest
                    .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            }
        }
        coVerify(exactly = 0) { catalogListRefresh.findListOptions(workspace, connector, any(), any(), any(), any()) }
        coVerify(exactly = 0) { catalogIngest.findJobs(workspace, connector, any(), any(), any()) }
        coVerify(exactly = 0) { catalogIngest.findErrors(workspace, connector, jobId, any(), any()) }
    }

    @Test
    fun `human catalog lists accept the maximum page size and default to page one`() {
        admitMembership()
        val jobId = CatalogIngestJobId.generate()
        coEvery { catalogListRefresh.findListOptions(workspace, connector, any(), 1, 200, any()) } returns
            CatalogListOptionPageView(CatalogListOptionPage(CatalogListRefreshTarget.Schema(null, "public"), null, emptyList(), 0, 1, 200, null))
        coEvery { catalogIngest.findJobs(workspace, connector, any(), 1, 200) } returns
            CatalogIngestJobPage(emptyList(), 0, 1, 200)
        coEvery { catalogIngest.findErrors(workspace, connector, jobId, 1, 200) } returns
            CatalogIngestErrorPage(emptyList(), 0, 1, 200)
        for (path in listOf("selection/options?parentKind=SCHEMA&schemaName=public&", "ingests?", "ingests/${jobId.value}/errors?")) {
            client.get().uri("/v2/workspaces/$workspace/catalog/connectors/$connector/${path}pageSize=200")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .exchange()
                .expectStatus().isOk
                .expectBody()
                .jsonPath("$.page").isEqualTo(1)
                .jsonPath("$.pageSize").isEqualTo(200)
        }
    }

    private fun admitMembership() {
        coEvery {
            admission.authorizeWorkspaceRole(any())
        } returns ControlAdmissionDecision.Admitted(mockk(relaxed = true))
    }
}
