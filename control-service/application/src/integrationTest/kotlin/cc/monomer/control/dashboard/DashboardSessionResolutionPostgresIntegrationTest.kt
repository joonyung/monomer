@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.dashboard.domain.DashboardNamedQuery
import cc.monomer.dashboard.domain.DashboardResolvedQuery

import io.mockk.*
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.pagereader.PublicPagesAccess
import cc.monomer.identity.api.*
import cc.monomer.identity.application.IdentityAdmissionService
import cc.monomer.identity.application.agent.AgentOAuthTokenHasher
import cc.monomer.identity.outbound.postgres.PostgresCanonicalIdentityAdmissionSnapshot
import cc.monomer.identity.outbound.postgres.SpringTransactionRunner
import cc.monomer.identity.domain.model.DeveloperAccessPolicy
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.pages.api.*
import cc.monomer.semantic.application.SemanticQueryUseCase
import cc.monomer.semantic.api.*
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.Timestamp


import cc.monomer.connector.api.ConnectorExecutionAccessInOwnerTransactionPort
import cc.monomer.connector.api.ConnectorExecutionAccessResult
import cc.monomer.connector.api.ConnectorExecutionBinding
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorRuntimeBindingProjectionPort
import cc.monomer.connector.api.ContentDigest
import cc.monomer.connector.api.CredentialEnvelopeReference
import cc.monomer.connector.api.CredentialVersionId
import cc.monomer.control.EphemeralPostgresAuthoritySupport
import cc.monomer.control.testControlRuntimeProperties
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.agent.AgentDashboardOwnerAdapter
import cc.monomer.control.pages.publicpages.PageReaderCaller
import cc.monomer.control.pages.publicpages.PageReaderService
import cc.monomer.control.pagereader.PageReaderFailure
import cc.monomer.control.pagereader.PageReaderProblem
import cc.monomer.control.semantic.inbound.rest.MetricDisplayDto
import cc.monomer.agent.api.*
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardCommandReceiptRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardLifecycleRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardSessionRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresQueryRunRepository
import cc.monomer.dashboard.api.*
import cc.monomer.dashboard.application.DashboardLifecycleService
import cc.monomer.dashboard.application.DashboardResultReadPort
import cc.monomer.dashboard.application.DashboardService
import cc.monomer.dashboard.application.DashboardDefinitionDecoder
import cc.monomer.dashboard.application.DashboardResult
import cc.monomer.dashboard.application.DashboardQueryPlanningPort
import cc.monomer.dashboard.application.DashboardSessionService
import cc.monomer.dashboard.application.DashboardSessionUseCase
import cc.monomer.dashboard.application.DashboardTransactionPort
import cc.monomer.dashboard.application.DashboardUseCase
import cc.monomer.dashboard.application.QueryRunLookupResult
import cc.monomer.dashboard.application.DashboardQueryResolution
import cc.monomer.dashboard.application.SessionResolutionResult
import cc.monomer.dashboard.application.SessionResult
import cc.monomer.dashboard.domain.Dashboard
import cc.monomer.dashboard.domain.DashboardSession
import cc.monomer.dashboard.domain.QueryRun
import cc.monomer.dashboard.domain.QueryRunState
import cc.monomer.execution.adapter.inbound.grpc.ExecutableRequestAssignmentCodec
import cc.monomer.execution.adapter.inbound.grpc.ProtoRawSqlRunSpecFactory
import cc.monomer.execution.adapter.outbound.postgres.PostgresExecutionRuntimeStore
import cc.monomer.execution.adapter.outbound.postgres.PostgresRunDispatchPersistence
import cc.monomer.execution.adapter.outbound.postgres.SpringExecutionRuntimeTransactionRunner
import cc.monomer.execution.api.ArrowResultManifest
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorRuntimeBindingSpec
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.CredentialVersionReference
import cc.monomer.execution.api.ManagedCredentialDispatchReference
import cc.monomer.execution.api.Sha256Digest
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunUseCase
import cc.monomer.execution.api.PhysicalOutcome
import cc.monomer.execution.api.PhysicalOutcomeKind
import cc.monomer.execution.application.ExecutionRunAdmissionService
import cc.monomer.execution.application.ExecutionRunLimits
import cc.monomer.execution.application.ExecutionRunService
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.concurrent.CompletableFuture
import javax.sql.DataSource
import org.springframework.jdbc.datasource.DelegatingDataSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

class DashboardSessionResolutionPostgresIntegrationTest {
    @Test
    fun `Apply committed during member open planning rejects stale snapshot without owner writes`(): Unit = runBlocking {
        val fixture = fixture(queryCount = 1, quota = 2)
        val workspace = fixture.session.workspaceId
        val dashboard = fixture.session.dashboardId
        fixture.semantic.onResolve = {
            fixture.transaction.inTransaction {
                check(fixture.dashboards.applyCurrent(workspace, dashboard, fixture.session.definition, NOW.plusSeconds(1), null, null, null))
            }
        }
        val command = DashboardSessionUseCase.OpenSessionCommand(workspace, PresentedCredential("fixture-token"), null,
            dashboard, emptyMap(), "fixture", "open-apply-race", NOW)
        val result = assertIs<SessionResult.Rejected>(fixture.service.open(command))
        assertEquals("SESSION_CONFLICT", result.code)
        assertEquals(1, count("dashboard.sessions", workspace))
        assertEquals(0, count("dashboard.query_runs", workspace))
        assertEquals(0, count("execution_run.runs", workspace))
        assertEquals(0, dispatchCount(workspace))
        assertEquals(0, count("dashboard.command_receipts", workspace))
        fixture.semantic.onResolve = null
        assertIs<SessionResult.Found>(fixture.service.open(command.copy(idempotencyKey = "fresh-open")))
    }

    @Test
    fun `stored display and Reporting time zone survive receipt and reuse while restored dashboards capture current values`() = runBlocking {
        val fixture = fixture(queryCount = 1, quota = 5, isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED)
        val workspace = fixture.session.workspaceId
        val dashboard = fixture.session.dashboardId
        val keys = listOf("metric.metric1", "metric.revenue", "metric.margin")
        val captured = listOf<MetricDisplay>(
            MetricDisplay.Number(3, MetricDisplayUnit.MILLISECOND),
            MetricDisplay.Currency(2, MetricDisplayCurrency.USD),
            MetricDisplay.Percent(1),
        )
        fun metadata(displays: List<MetricDisplay>) = keys.zip(displays) { key, display ->
            SemanticOutputColumnDisplay(SemanticResultColumnKey(key), display)
        }
        val seoul = java.time.ZoneId.of("Asia/Seoul")
        fixture.semantic.columns = keys
        fixture.semantic.outputColumnDisplays = metadata(captured)
        fixture.semantic.reportingTimeZone = seoul
        val command = DashboardSessionUseCase.OpenSessionCommand(workspace, PresentedCredential("fixture-token"), null,
            dashboard, emptyMap(), "fixture", "display-open", NOW)
        val opened = assertIs<SessionResult.Found>(fixture.service.open(command))
        val original = opened.queryRuns.single()
        assertEquals(metadata(captured), original.outputColumnDisplays)
        assertEquals(seoul, original.reportingTimeZone)
        assertEquals(original, fixture.queryRuns.find(original.id))
        postgres.dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT output_column_displays, reporting_time_zone FROM dashboard.query_runs WHERE query_run_id = ?").use { statement ->
                statement.setObject(1, original.id.value.toJavaUuid())
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(MetricDisplayJsonCodec.encodeColumns(metadata(captured)), Json.parseToJsonElement(rows.getString(1)))
                    assertEquals("Asia/Seoul", rows.getString(2))
                }
            }
        }
        val current = listOf<MetricDisplay>(MetricDisplay.Number(0, null), MetricDisplay.Currency(4, MetricDisplayCurrency.EUR), MetricDisplay.Percent(6))
        fixture.semantic.outputColumnDisplays = metadata(current)
        fixture.semantic.reportingTimeZone = java.time.ZoneId.of("UTC")
        assertEquals(opened, fixture.service.open(command.copy(openedAt = NOW.plusSeconds(10))))
        val resolution = fixture.resolve("display-reuse").copy(sessionId = opened.session.id, variables = opened.session.initialVariables)
        assertEquals(original.id, assertIs<SessionResolutionResult.Resolved>(fixture.service.resolve(resolution)).queryRuns.getValue("q1"))
        val succeeded = fixture.succeed(original, NOW.plusSeconds(1))
        assertEquals(original.id, assertIs<SessionResolutionResult.Resolved>(fixture.service.resolve(resolution.copy(idempotencyKey = "display-success-reuse"))).queryRuns.getValue("q1"))
        val query = opened.session.definition.query("q1") as DashboardNamedQuery.Semantic
        val lookup = cc.monomer.dashboard.application.EquivalentQueryRunLookup("q1", query.connectorId,
            DashboardResolvedQuery.Semantic(query.query), fixture.semantic.sql, keys)
        val storedReads = fixture.transaction.inTransaction {
            listOf(
                fixture.queryRuns.find(workspace, dashboard, opened.session.id, original.id),
                fixture.queryRuns.findSessionPage(workspace, dashboard, opened.session.id, null, 20).single(),
                fixture.queryRuns.findEquivalent(workspace, dashboard, opened.session.id, lookup.queryName, lookup.connectorId,
                    lookup.resolvedQuery, lookup.renderedSql, lookup.outputColumnNames, setOf(QueryRunState.SUCCEEDED)),
                fixture.queryRuns.findEquivalentRuns(workspace, dashboard, opened.session.id, listOf(lookup),
                    setOf(QueryRunState.SUCCEEDED), Instant.now().plusSeconds(5)).single(),
            )
        }
        assertEquals(List(storedReads.size) { succeeded }, storedReads)
        assertEquals(1, count("execution_run.runs", workspace))
        assertEquals(1, dispatchCount(workspace))

        val search = mockk<DashboardSearchProjection>(relaxed = true)
        val cleanup = DashboardDependencyCleanupAdapter(fixture.execution, fixture.execution, fixture.execution,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val lifecycle = DashboardLifecycleService(fixture.transaction, fixture.dashboards, fixture.lifecycleRepository, fixture.receipts,
            fixture.executionAdmission, cleanup, search)
        val dashboardService = DashboardService(search, fixture.transaction, fixture.dashboards, fixture.receipts)
        val owner = DashboardOwnerPortAdapter(dashboardService, mockk(), fixture.service, Dispatchers.IO, lifecycle)
        val artifact = DashboardResultReadPort.DashboardResultPayload(
            keys.indices.map { index -> DashboardResultReadPort.Column("__monomer_result_$index", "DECIMAL", false, null) },
            listOf(listOf(JsonPrimitive("12.345000"), JsonPrimitive("12345678901234567890.120000"), JsonPrimitive("0.042000"))),
        )
        val results = mockk<DashboardResultReadPort> { coEvery { read(any()) } returns artifact }
        val clock = Clock.fixed(NOW, ZoneOffset.UTC)
        val authorizer = mockk<DashboardRequestAuthorizer> {
            coEvery { requireWorkspace(any(), workspace.toString()) } returns AuthorizedDashboardRequest(workspace,
                OrganizationId.generate(), command.actorId, PresentedCredential("fixture-token"), CredentialSurface.WEB, null)
        }
        val controller = DashboardSessionController(fixture.service, results, authorizer, mockk(), clock,
            ObjectMapper(), mockk(), mockk(), Dispatchers.IO)
        val admission = mockk<ControlAdmissionFacade> {
            coEvery { authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(mockk())
        }
        val agent = AgentDashboardOwnerAdapter(owner, mockk(), results, admission, clock, mockk()) { _, _ -> }
        val agentCaller = mockk<AgentWorkspaceCaller> {
            every { workspaceId } returns workspace
            every { presentedCredential } returns PresentedCredential("fixture-token")
            every { expectedOAuthResource } returns ExpectedOAuthResource.AGENT_REST
        }
        val page = Uuid.generateV7()
        val pages = mockk<PagesCurrentReadPort> {
            coEvery { tree(workspace.value) } returns CurrentPagesTree(1,
                listOf(CurrentPage(page, "DASHBOARD", null, dashboard.value, null, "Display fixture", null)),
                listOf(CurrentPagePlacement(page, emptyList())))
        }
        val publicPages = mockk<WorkspacePublicPagesQuery> { coEvery { isPublic(workspace) } returns true }
        val snapshots = object : ConsistentSnapshotTransactionRunner {
            override suspend fun <T> run(action: suspend () -> T): T = action()
        }
        val reader = PageReaderService(PublicPagesAccess(publicPages, admission, snapshots), pages, owner, results, mockk(), publicPages, clock)
        val publicCaller = PageReaderCaller(workspace, null, null, null, null)
        fun agentRequest(session: DashboardSession, run: QueryRun) = AgentReadRequest(AgentResourcePath.parse(
            "workspace/dashboards/$dashboard/sessions/${session.id}/query-runs/${run.id}/result"), null)
        suspend fun assertResults(session: DashboardSession, run: QueryRun, displays: List<MetricDisplay>, zone: java.time.ZoneId) {
            val rest = controller.result(workspace.toString(), session.id.toString(), run.id.toString(), mockk())
            assertEquals(zone.id, rest.reportingTimeZone)
            assertEquals(keys, rest.columns.map { it.name })
            assertEquals(displays.map(MetricDisplayDto::fromDomain), rest.columns.map { it.display })
            assertEquals(artifact.rows.single().map { (it as JsonPrimitive).content }, rest.rows.single().map { it.stringValue() })
            val resource = assertIs<AgentResourceEnvelope.DashboardQueryResult>(assertIs<AgentResourceOutcome.Found>(
                agent.read(agentCaller, agentRequest(session, run))).resource)
            assertEquals(keys, resource.body.columns.map { it.name })
            val wire = Json.parseToJsonElement(ObjectMapper().writeValueAsString(
                cc.monomer.control.publicapi.AgentWorkspaceJson.agentResourceJson(AgentRequestId("srv_display"), resource),
            )).jsonObject
            assertEquals(displays.map { MetricDisplayJsonCodec.encode(it) }, wire.getValue("body").jsonObject
                .getValue("columns").jsonArray.map { it.jsonObject.getValue("display") })
            assertEquals(JsonPrimitive(zone.id), wire.getValue("body").jsonObject.getValue("reportingTimeZone"))
            assertEquals(artifact.rows, resource.body.rows)
            val publicRead = reader.result(publicCaller, page.toString(), session.id.toString(), run.id.toString())
            assertEquals(zone, publicRead.reportingTimeZone)
            val publicResult = publicRead.payload
            assertEquals(keys, publicResult.columns.map { it.name })
            assertEquals(displays, publicResult.columns.map { it.display })
            assertEquals(artifact.rows, publicResult.rows)
        }
        assertResults(opened.session, succeeded, captured, seoul)
        val actor = Uuid.generateV7().toString()
        assertIs<DashboardOwnerOutcome.Success<Unit>>(lifecycle.delete(DeleteDashboardCommand(workspace, dashboard,
            DashboardDeletionMetadata(actor, OrganizationId.generate(), PresentedCredential("fixture-token"), null,
                NOW.plusSeconds(2), CredentialSurface.WEB, null))))
        assertEquals(DashboardOwnerResultLookupOutcome.NotFound, owner.readSessionResult(ReadDashboardSessionResultCommand(workspace, dashboard, opened.session.id, original.id)))
        assertEquals(HttpStatus.NOT_FOUND, assertFailsWith<ResponseStatusException> {
            controller.result(workspace.toString(), opened.session.id.toString(), original.id.toString(), mockk())
        }.statusCode)
        assertEquals(AgentResourceOutcome.NotFound, agent.read(agentCaller, agentRequest(opened.session, succeeded)))
        assertEquals(PageReaderFailure.NotFound, assertFailsWith<PageReaderProblem> {
            reader.result(publicCaller, page.toString(), opened.session.id.toString(), original.id.toString())
        }.failure)
        assertEquals("IDEMPOTENCY_CONFLICT", assertIs<SessionResult.Rejected>(fixture.service.open(command)).code)
        val replay = assertFailsWith<DashboardRestProblem> {
            controller.openSession(workspace.toString(), dashboard.toString(), command.idempotencyKey,
                ObjectMapper().readTree("""{"parameterBindings":{}}"""), mockk())
        }
        assertEquals(HttpStatus.CONFLICT, replay.status)
        assertEquals("IDEMPOTENCY_CONFLICT", replay.code)
        val deleted = assertIs<DashboardOwnerOutcome.Success<DashboardOwnerHistoryPage>>(lifecycle.listHistory(
            ListDashboardHistoryCommand(workspace, dashboard, null, 20))).value.changes.first()
        assertTrue(deleted.restorable)
        assertIs<DashboardOwnerOutcome.Success<Dashboard>>(lifecycle.restore(RestoreDashboardCommand(workspace, dashboard, deleted.changeId,
            DashboardOwnerCommandMetadata(actor, PresentedCredential("fixture-token"), null, "display-restore", NOW.plusSeconds(3), CredentialSurface.WEB, null))))
        val restored = assertIs<SessionResult.Found>(fixture.service.open(command.copy(idempotencyKey = "display-open-restored", openedAt = NOW.plusSeconds(4))))
        val fresh = restored.queryRuns.single()
        assertNotEquals(original.id, fresh.id)
        assertEquals(metadata(current), fixture.queryRuns.find(fresh.id)?.outputColumnDisplays)
        assertEquals(java.time.ZoneId.of("UTC"), fixture.queryRuns.find(fresh.id)?.reportingTimeZone)
        assertEquals(DashboardOwnerResultLookupOutcome.NotFound, owner.readSessionResult(ReadDashboardSessionResultCommand(workspace, dashboard, opened.session.id, original.id)))
        assertResults(restored.session, fixture.succeed(fresh, NOW.plusSeconds(5)), current, java.time.ZoneId.of("UTC"))
        coVerify(exactly = 6) { results.read(any()) }
    }

    @Test
    fun `persisted membership loss after Page admission rejects owner planning before rendering`() = runBlocking {
        val fixture = fixture(queryCount = 2, quota = 10)
        val workspace = fixture.session.workspaceId
        val organization = Uuid.generateV7()
        val principal = Uuid.generateV7()
        val owner = Uuid.generateV7()
        val page = Uuid.generateV7()
        val grant = Uuid.generateV7()
        val token = "page-reader-sequence-${Uuid.generateV7()}"
        val credential = PresentedCredential(token)
        val jdbc = NamedParameterJdbcTemplate(postgres.dataSource)
        val args = mapOf("organization" to organization.toJavaUuid(), "workspace" to workspace.value.toJavaUuid(),
            "principal" to principal.toJavaUuid(), "owner" to owner.toJavaUuid(), "grant" to grant.toJavaUuid(),
            "tokenId" to Uuid.generateV7().toJavaUuid(), "hash" to AgentOAuthTokenHasher().hash(token),
            "now" to Timestamp.from(NOW), "expires" to Timestamp.from(NOW.plusSeconds(600)))
        val manager = DataSourceTransactionManager(postgres.dataSource)
        val transactions = TransactionTemplate(manager)
        transactions.execute {
            jdbc.update("INSERT INTO identity_runtime.organizations(id, display_name, created_at) VALUES (:organization, 'Reader sequence', :now)", args)
            jdbc.update("INSERT INTO identity_runtime.principals(id, kind, display_name, created_at) VALUES (:owner, 'user', 'Owner', :now), (:principal, 'user', 'Reader', :now)", args)
            jdbc.update("INSERT INTO identity_runtime.users(id, primary_email, created_at) VALUES (:owner, :ownerEmail, :now), (:principal, :email, :now)", args + mapOf("ownerEmail" to "$owner@example.test", "email" to "$principal@example.test"))
            jdbc.update("INSERT INTO identity_runtime.workspaces(id, organization_id, display_name, authorization_revision, created_at) VALUES (:workspace, :organization, 'Reader sequence', 1, :now)", args)
            jdbc.update("INSERT INTO identity_runtime.organization_members(organization_id, user_id, role, joined_at) VALUES (:organization, :owner, 'ADMIN', :now), (:organization, :principal, 'MEMBER', :now)", args)
            jdbc.update("INSERT INTO identity_runtime.workspace_members(workspace_id, user_id, invited_at, role) VALUES (:workspace, :owner, :now, 'OWNER'), (:workspace, :principal, :now, 'EXPLORER')", args)
            jdbc.update("INSERT INTO identity_runtime.agent_oauth_grants(id, owner_user_id, oauth_client_id, resource_audience, scopes, issued_at) VALUES (:grant, :principal, 'reader-sequence', 'https://monomer.test/agent', ARRAY['agent.workspace']::TEXT[], :now)", args)
            jdbc.update("INSERT INTO identity_runtime.agent_oauth_access_tokens(id, grant_id, token_hash, issued_at, expires_at) VALUES (:tokenId, :grant, :hash, :now, :expires)", args)
        }
        val readTransactions = TransactionTemplate(manager).apply { isReadOnly = true; isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ }
        val identity = IdentityAdmissionService(PostgresCanonicalIdentityAdmissionSnapshot(
            jdbc, SpringTransactionRunner(transactions, readTransactions, Dispatchers.Unconfined),
            mockk { every { verifyCurrentCredential(any()) } returns null }, AgentOAuthTokenHasher(), Clock.fixed(NOW, ZoneOffset.UTC),
            mapOf(ExpectedOAuthResource.AGENT_REST to "https://monomer.test/agent", ExpectedOAuthResource.AGENT_MCP to "https://monomer.test/mcp"),
            DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.OPEN, emptySet()),
        ))
        val admission = ControlAdmissionFacade(identity, Clock.fixed(NOW, ZoneOffset.UTC))
        val snapshots = object : ConsistentSnapshotTransactionRunner {
            override suspend fun <T> run(action: suspend () -> T): T = action()
        }
        val publicPages = mockk<WorkspacePublicPagesQuery> { coEvery { isPublic(workspace) } returns true }
        val access = PublicPagesAccess(publicPages, admission, snapshots)
        val pageSource = mockk<PagesCurrentReadPort> {
            coEvery { tree(workspace.value) } returns CurrentPagesTree(1,
                listOf(CurrentPage(page, "DASHBOARD", null, fixture.session.dashboardId.value, "Published", "Published", null)),
                listOf(CurrentPagePlacement(page, emptyList())))
        }
        val events = mutableListOf<String>()
        val semantic = mockk<SemanticQueryUseCase> {
            coEvery { render(any()) } answers {
                events += "render"
                SemanticRenderResult.Rendered(mockk {
                    every { workspaceId } returns workspace
                    every { sql } returns "SELECT 1"
                    every { reportingTimeZone } returns java.time.ZoneId.of("UTC")
                    every { outputColumns } returns listOf(mockk {
                        every { name } returns SemanticResultColumnKey("metric.value")
                        every { display } returns null
                    })
                })
            }
        }
        val adapter = DashboardQueryPlanningAdapter(postgres.dataSource, tools.jackson.databind.ObjectMapper(), semantic,
            admission, snapshots, readTransactions, Dispatchers.Unconfined, access, pageSource, io.mockk.mockk())
        val service = DashboardSessionService(fixture.transaction, fixture.dashboards, fixture.sessions, fixture.queryRuns,
            fixture.receipts, adapter, fixture.executionAdmission)
        assertEquals(principal, access.requireWorkspace(workspace, credential, ExpectedOAuthResource.AGENT_REST)!!.actor.id.value)
        events += "request-admitted"
        jdbc.update("UPDATE identity_runtime.workspace_members SET role = 'VIEWER' WHERE workspace_id = :workspace AND user_id = :principal", args)
        val command = cc.monomer.dashboard.api.ResolvePublicDashboardSessionCommand(workspace, fixture.session.dashboardId,
            fixture.session.id, fixture.session.initialVariables, Uuid.generateV7().toString(), NOW,
            credential, ExpectedOAuthResource.AGENT_REST, principal.toString(), page)
        val viewerResolution = assertIs<SessionResolutionResult.Resolved>(service.resolvePublic(command))
        assertEquals(2, viewerResolution.queryRuns.size)
        coVerify(exactly = 1) { pageSource.tree(workspace.value) }
        viewerResolution.queryRuns.values.forEach { id ->
            val run = requireNotNull(fixture.queryRuns.find(id))
            assertEquals(principal.toString(), run.actorId)
            assertEquals(CredentialSurface.AGENT, run.credentialSurface)
        }
        events.clear()
        events += "viewer-admitted"
        jdbc.update("DELETE FROM identity_runtime.workspace_members WHERE workspace_id = :workspace AND user_id = :principal", args)
        events += "membership-removed"
        val denied = assertIs<SessionResolutionResult.Rejected>(service.resolvePublic(command.copy(idempotencyKey = Uuid.generateV7().toString())))
        events += "owner-rejected"
        assertEquals("DASHBOARD_QUERY_UNAUTHORIZED", denied.code)
        assertEquals(listOf("viewer-admitted", "membership-removed", "owner-rejected"), events)
        coVerify(exactly = 2) { semantic.render(any()) }
        coVerify(exactly = 1) { pageSource.tree(workspace.value) }
        coVerify(exactly = 0) { publicPages.isPublic(workspace) }
        assertEquals(2, count("execution_run.runs", workspace))
    }

    @Test
    fun `session summaries project latest run creation within the exact session scope`() = runBlocking {
        val fixture = fixture(queryCount = 2, quota = 4)
        val emptySession = fixture.session.copy(id = DashboardSessionId.generate())
        fixture.transaction.inTransaction { check(fixture.sessions.insert(emptySession)) }
        val first = fixture.seedSuccessful(NOW.plusSeconds(1), NOW.plusSeconds(100))
        val latest = fixture.seedSuccessful(NOW.plusSeconds(2), NOW.plusSeconds(3))
        val otherFixture = fixture(queryCount = 1, quota = 2)
        otherFixture.seedSuccessful(NOW.plusSeconds(50), NOW.plusSeconds(60))
        val summaries = fixture.transaction.inTransaction {
            fixture.sessions.findPage(fixture.session.workspaceId, fixture.session.dashboardId, null, 20)
        }
        assertEquals(2, summaries.size)
        val executed = summaries.single { it.id == fixture.session.id }
        assertEquals(latest.createdAt, executed.lastQueryRunAt)
        assertNotEquals(first.updatedAt, executed.lastQueryRunAt)
        assertEquals(null, summaries.single { it.id == emptySession.id }.lastQueryRunAt)
        assertEquals(fixture.session.createdAt, executed.createdAt)
        assertEquals(fixture.session.updatedAt, executed.updatedAt)
        assertTrue(executed.runningQueryRuns.isEmpty())
        val firstResolution = assertIs<SessionResolutionResult.Resolved>(
            fixture.service.refresh(fixture.refresh("summary-running").copy(requestedAt = NOW.plusSeconds(4))))
        val otherResolution = assertIs<SessionResolutionResult.Resolved>(
            fixture.service.refresh(fixture.refresh("other-summary-running").copy(sessionId = emptySession.id, requestedAt = NOW.plusSeconds(5))))
        val running = fixture.transaction.inTransaction {
            fixture.sessions.findPage(fixture.session.workspaceId, fixture.session.dashboardId, null, 20)
        }.single { it.id == fixture.session.id }
        assertEquals(NOW.plusSeconds(4), running.lastQueryRunAt)
        assertEquals(firstResolution.queryRuns, running.runningQueryRuns.associate { it.queryName to it.queryRunId })
        assertEquals(firstResolution.queryRuns.values.sortedByDescending { it.value }, running.runningQueryRuns.map { it.queryRunId })
        val otherRunning = fixture.transaction.inTransaction {
            fixture.sessions.findPage(fixture.session.workspaceId, fixture.session.dashboardId, null, 20)
        }.single { it.id == emptySession.id }
        assertEquals(otherResolution.queryRuns, otherRunning.runningQueryRuns.associate { it.queryName to it.queryRunId })
        assertEquals(NOW.plusSeconds(5), otherRunning.lastQueryRunAt)
        assertTrue(fixture.transaction.inTransaction {
            fixture.sessions.findPage(otherFixture.session.workspaceId, fixture.session.dashboardId, null, 20)
        }.isEmpty())
    }

    @Test
    fun `simultaneous equal misses reserve one execution and dispatch intent within workspace quota`() = runBlocking {
        val fixture = fixture(queryCount = 1, quota = 1)
        fixture.semantic.barrier = CyclicBarrier(2)
        val results = listOf("one", "two").map { key -> async(Dispatchers.IO) {
            assertIs<SessionResolutionResult.Resolved>(fixture.service.resolve(fixture.resolve(key)))
        } }.awaitAll()
        assertEquals(results[0].queryRuns, results[1].queryRuns)
        assertEquals(1, count("dashboard.query_runs", fixture.session.workspaceId))
        assertEquals(1, count("execution_run.runs", fixture.session.workspaceId))
        assertEquals(1, dispatchCount(fixture.session.workspaceId))
        assertEquals(2, count("dashboard.command_receipts", fixture.session.workspaceId))
        fixture.semantic.barrier = null
        val snapshot = assertIs<SessionResult.Found>(fixture.service.get(fixture.session.workspaceId, fixture.session.id))
        assertEquals(fixture.session, snapshot.session)
        assertEquals(results[0].queryRuns.values.toSet(), snapshot.queryRuns.map { it.id }.toSet())
    }

    @Test
    fun `simultaneous refresh bypasses success once then shares running while ordinary resolve prefers success`() = runBlocking {
        val fixture = fixture(queryCount = 1, quota = 1)
        val successful = fixture.seedSuccessful(createdAt = NOW.minusSeconds(10), settledAt = NOW.minusSeconds(1))
        fixture.semantic.barrier = CyclicBarrier(2)
        val results = listOf("refresh-one", "refresh-two").map { key -> async(Dispatchers.IO) {
            assertIs<SessionResolutionResult.Resolved>(fixture.service.refresh(fixture.refresh(key)))
        } }.awaitAll()
        assertEquals(results[0].queryRuns, results[1].queryRuns)
        assertNotEquals(successful.id, results[0].queryRuns.getValue("q1"))
        assertEquals(1, count("execution_run.runs", fixture.session.workspaceId))
        assertEquals(1, dispatchCount(fixture.session.workspaceId))
        fixture.semantic.barrier = null
        val ordinary = assertIs<SessionResolutionResult.Resolved>(fixture.service.resolve(fixture.resolve("ordinary")))
        assertEquals(successful.id, ordinary.queryRuns.getValue("q1"))
        assertEquals(1, count("execution_run.runs", fixture.session.workspaceId))
    }

    @Test
    fun `success selection follows creation order despite reverse settlement and exact SQL output metadata changes miss`() = runBlocking {
        val fixture = fixture(queryCount = 1, quota = 5)
        val older = fixture.seedSuccessful(createdAt = NOW.minusSeconds(20), settledAt = NOW.minusSeconds(1))
        val newer = fixture.seedSuccessful(createdAt = NOW.minusSeconds(10), settledAt = NOW.minusSeconds(5))
        assertEquals(newer.id, assertIs<SessionResolutionResult.Resolved>(fixture.service.resolve(fixture.resolve("latest"))).queryRuns.getValue("q1"))
        fixture.semantic.sql = "SELECT 2"
        val changedSql = assertIs<SessionResolutionResult.Resolved>(fixture.service.resolve(fixture.resolve("sql")))
        assertNotEquals(newer.id, changedSql.queryRuns.getValue("q1"))
        fixture.semantic.columns = listOf("other")
        val changedOutput = assertIs<SessionResolutionResult.Resolved>(fixture.service.resolve(fixture.resolve("output")))
        assertNotEquals(changedSql.queryRuns, changedOutput.queryRuns)
        assertEquals(2, count("execution_run.runs", fixture.session.workspaceId))
        assertEquals(2, dispatchCount(fixture.session.workspaceId))
        assertEquals(QueryRunState.SUCCEEDED, fixture.queryRuns.find(older.id)!!.state)
    }

    @Test
    fun `second query quota rejection rolls back first execution dispatch run and receipt`() = runBlocking {
        val fixture = fixture(queryCount = 2, quota = 1)
        val result = assertIs<SessionResolutionResult.Rejected>(fixture.service.resolve(fixture.resolve("rejected")))
        assertEquals("EXECUTION_BACKPRESSURED", result.code)
        assertEquals(0, count("dashboard.query_runs", fixture.session.workspaceId))
        assertEquals(0, count("execution_run.runs", fixture.session.workspaceId))
        assertEquals(0, dispatchCount(fixture.session.workspaceId))
        assertEquals(0, count("dashboard.command_receipts", fixture.session.workspaceId))
        assertEquals(fixture.session, fixture.sessions.find(fixture.session.workspaceId, fixture.session.id))
    }

    @Test
    fun `invalid later query is rejected before admission and wrong workspace dashboard session never returns shared runs`() = runBlocking {
        val fixture = fixture(queryCount = 2, quota = 10)
        fixture.semantic.invalidMetric = "metric2"
        assertEquals("INVALID_SEMANTIC_QUERY", assertIs<SessionResolutionResult.Rejected>(fixture.service.resolve(fixture.resolve("invalid"))).code)
        assertEquals(0, count("execution_run.runs", fixture.session.workspaceId))
        fixture.semantic.invalidMetric = null
        val selected = assertIs<SessionResolutionResult.Resolved>(fixture.service.resolve(fixture.resolve("accepted")))
        val command = fixture.resolve("scope")
        assertEquals(SessionResolutionResult.NotFound, fixture.service.resolve(command.copy(workspaceId = WorkspaceId.generate())))
        assertEquals(SessionResolutionResult.NotFound, fixture.service.resolve(command.copy(dashboardId = DashboardId.generate())))
        assertEquals(SessionResolutionResult.NotFound, fixture.service.resolve(command.copy(sessionId = DashboardSessionId.generate())))
        assertEquals(QueryRunLookupResult.NotFound, fixture.service.getQueryRun(fixture.session.workspaceId, fixture.session.dashboardId, DashboardSessionId.generate(), selected.queryRuns.getValue("q1")))
        fixture.semantic.invalidMetric = "metric2"
        assertEquals(selected, fixture.service.resolve(fixture.resolve("accepted")))
        assertEquals(2, count("execution_run.runs", fixture.session.workspaceId))
        assertEquals(1, count("dashboard.command_receipts", fixture.session.workspaceId))
    }

    @Test
    fun `two persisted sessions in the same dashboard admit distinct executions for identical inputs`() = runBlocking {
        val fixture = fixture(queryCount = 1, quota = 2)
        val secondSession = fixture.session.copy(id = DashboardSessionId.generate())
        fixture.transaction.inTransaction { check(fixture.sessions.insert(secondSession)) }
        val first = assertIs<SessionResolutionResult.Resolved>(fixture.service.resolve(fixture.resolve("first-session")))
        val second = assertIs<SessionResolutionResult.Resolved>(fixture.service.resolve(fixture.resolve("second-session").copy(sessionId = secondSession.id)))
        assertNotEquals(first.queryRuns, second.queryRuns)
        assertEquals(2, count("execution_run.runs", fixture.session.workspaceId))
        assertEquals(2, dispatchCount(fixture.session.workspaceId))
        assertEquals(QueryRunLookupResult.NotFound, fixture.service.getQueryRun(fixture.session.workspaceId, fixture.session.dashboardId, secondSession.id, first.queryRuns.getValue("q1")))
        assertEquals(first.queryRuns, assertIs<SessionResolutionResult.Resolved>(fixture.service.resolve(fixture.resolve("first-again"))).queryRuns)
    }

    @Test
    fun `limited reader can join another callers shared run without data access planning`() = runBlocking {
        val fixture = fixture(queryCount = 1, quota = 1)
        val producer = assertIs<SessionResolutionResult.Resolved>(fixture.service.resolve(fixture.resolve("producer")))
        val consumer = fixture.resolve("consumer").copy(actorId = "consumer", credential = PresentedCredential("limited-reader"))
        assertEquals(producer.queryRuns, assertIs<SessionResolutionResult.Resolved>(fixture.service.resolve(consumer)).queryRuns)
        assertEquals(1, count("execution_run.runs", fixture.session.workspaceId))
        assertEquals(2, count("dashboard.command_receipts", fixture.session.workspaceId))
    }

    @Test
    fun `settlement after equivalent lookup snapshot does not admit another run`() = runBlocking {
        val fixture = fixture(queryCount = 1, quota = 2)
        val initial = assertIs<SessionResolutionResult.Resolved>(fixture.service.resolve(fixture.resolve("initial")))
        val running = requireNotNull(fixture.queryRuns.find(initial.queryRuns.getValue("q1")))
        fixture.lookupObservation.statements = 0
        fixture.lookupObservation.afterSnapshot = {
            CompletableFuture.runAsync {
                val settlementRepository = PostgresQueryRunRepository(EphemeralPostgresAuthoritySupport.dataSource)
                val succeeded = running.succeed(ArrowResultManifest("artifacts/${running.id}", 1uL, "etag", 1uL, 1,
                    "application/vnd.apache.arrow.file"), NOW.plusSeconds(1))
                check(settlementRepository.update(running.updatedAt, succeeded))
            }.get(5, TimeUnit.SECONDS)
        }
        val resolved = assertIs<SessionResolutionResult.Resolved>(fixture.service.resolve(fixture.resolve("settled-during-lookup")))
        assertEquals(QueryRunState.SUCCEEDED, fixture.queryRuns.find(running.id)?.state)
        assertEquals(initial.queryRuns, resolved.queryRuns)
        assertEquals(1, fixture.lookupObservation.statements)
        assertEquals(1, count("dashboard.query_runs", fixture.session.workspaceId))
        assertEquals(1, count("execution_run.runs", fixture.session.workspaceId))
        assertEquals(1, dispatchCount(fixture.session.workspaceId))
    }

    @Test
    fun `open receipt replays nullable Run payload without query permission checks`() = runBlocking {
        val fixture = fixture(queryCount = 1, quota = 1)
        val command = DashboardSessionUseCase.OpenSessionCommand(
            fixture.session.workspaceId, PresentedCredential("fixture-token"), null, fixture.session.dashboardId,
            emptyMap(), "fixture", "open-receipt", NOW,
        )
        val opened = assertIs<SessionResult.Found>(fixture.service.open(command))
        assertEquals(opened, fixture.service.open(command))
        fixture.semantic.invalidMetric = "metric1"
        assertEquals(opened, fixture.service.open(command))
        assertEquals(1, count("execution_run.runs", fixture.session.workspaceId))
        assertEquals(1, count("dashboard.query_runs", fixture.session.workspaceId))
    }

    @Test
    fun `historical D12 cutover preserves receipt JSON and unrelated stored rows`() = runBlocking {
        val fixture = fixture(queryCount = 1, quota = 1)
        assertIs<SessionResolutionResult.Resolved>(fixture.service.resolve(fixture.resolve("unrelated-resolution")))
        val dashboardService = DashboardApplicationComposition().dashboardUseCase(object : cc.monomer.dashboard.api.DashboardSearchProjection { override fun upsert(fields: cc.monomer.dashboard.api.DashboardSearchFields) = Unit; override fun delete(workspaceId: WorkspaceId, dashboardId: DashboardId) = Unit }, fixture.transaction, fixture.dashboards, fixture.receipts)
        val actor = Uuid.generateV7().toString()
        val command = DashboardUseCase.CreateCommand(
            fixture.session.workspaceId, fixture.session.definition.document, actor, actor, "d12-create-receipt", NOW,
            credentialSurface = null,
            agentClientId = null,
        )
        val original = assertIs<DashboardResult.Found>(dashboardService.create(command))
        val historicalSchema = historicalD12Schema(command.workspaceId)
        val originalRows = storedRows(historicalSchema)

        postgres.dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE $historicalSchema.dashboards
                SET definition = jsonb_set(definition, '{layout,sections,0,root}', ?::jsonb, false)
                WHERE dashboard_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, """{"type":"ITEM","id":"item","item":{"kind":"WIDGET","id":"table"}}""")
                statement.setObject(2, original.dashboard.id.value.toJavaUuid())
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                UPDATE $historicalSchema.command_receipts AS receipt
                SET normalized_request = jsonb_set(receipt.normalized_request, '{definition}', stored.definition, false),
                    response_snapshot = jsonb_set(receipt.response_snapshot, '{dashboard,definition}', stored.definition, false)
                FROM $historicalSchema.dashboards AS stored
                WHERE receipt.dashboard_id = stored.dashboard_id AND stored.dashboard_id = ?
                    AND receipt.command_kind = 'CREATE_DASHBOARD'
                RETURNING normalized_request #>> '{definition,layout,sections,0,root,type}',
                    response_snapshot #>> '{dashboard,definition,layout,sections,0,root,type}'
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, original.dashboard.id.value.toJavaUuid())
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals("ITEM", rows.getString(1))
                    assertEquals("ITEM", rows.getString(2))
                    assertTrue(!rows.next())
                }
            }
        }
        assertNotEquals(originalRows, storedRows(historicalSchema))

        applyD12Cutover(historicalSchema)

        assertEquals(originalRows, storedRows(historicalSchema))
        applyD12Cutover(historicalSchema)
        assertEquals(originalRows, storedRows(historicalSchema))

        postgres.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("UPDATE $historicalSchema.dashboards SET definition = jsonb_set(definition, '{layout,sections,0,root,type}', '\"INVALID\"'::jsonb, false)")
            }
        }
        val invalidRows = storedRows(historicalSchema)
        assertFailsWith<java.sql.SQLException> { applyD12Cutover(historicalSchema) }
        assertEquals(invalidRows, storedRows(historicalSchema))
    }

    @Test
    fun `current definition create receipt replays without changing unrelated stored rows`() = runBlocking {
        val fixture = fixture(queryCount = 1, quota = 1)
        assertIs<SessionResolutionResult.Resolved>(fixture.service.resolve(fixture.resolve("unrelated-resolution")))
        val dashboardService = DashboardApplicationComposition().dashboardUseCase(object : cc.monomer.dashboard.api.DashboardSearchProjection { override fun upsert(fields: cc.monomer.dashboard.api.DashboardSearchFields) = Unit; override fun delete(workspaceId: WorkspaceId, dashboardId: DashboardId) = Unit }, fixture.transaction, fixture.dashboards, fixture.receipts)
        val actor = Uuid.generateV7().toString()
        val command = DashboardUseCase.CreateCommand(
            fixture.session.workspaceId, fixture.session.definition.document, actor, actor, "current-create-receipt", NOW,
            credentialSurface = null,
            agentClientId = null,
        )
        val original = assertIs<DashboardResult.Found>(dashboardService.create(command))
        val originalRows = storedRows("dashboard")
        assertEquals(original, dashboardService.get(command.workspaceId, original.dashboard.id))
        val replayed = assertIs<DashboardResult.Found>(dashboardService.create(command.copy(createdAt = NOW.plusSeconds(60))))
        assertEquals(original, replayed)
        assertEquals(command.document, replayed.dashboard.definition.document)
        assertEquals(originalRows, storedRows("dashboard"))
        assertEquals(2, count("dashboard.dashboards", command.workspaceId))
        assertEquals(2, count("dashboard.command_receipts", command.workspaceId))
        assertEquals(1, count("dashboard.sessions", command.workspaceId))
        assertEquals(1, count("dashboard.query_runs", command.workspaceId))
    }

    @Test
    fun `anonymous equal concurrent keys share runs and receipt replay does not plan`() = runBlocking {
        val fixture = fixture(queryCount = 1, quota = 1)
        val key = Uuid.random().toString()
        fun command(requestKey: String) = cc.monomer.dashboard.api.ResolvePublicDashboardSessionCommand(
            fixture.session.workspaceId, fixture.session.dashboardId, fixture.session.id,
            fixture.session.initialVariables, requestKey, NOW, credential = null, expectedOAuthResource = null, actorId = null, pageId = Uuid.generateV7(),
        )
        fixture.semantic.barrier = CyclicBarrier(2)
        val outcomes = (1..2).map { async(Dispatchers.IO) {
            assertIs<SessionResolutionResult.Resolved>(fixture.service.resolvePublic(command(key)))
        } }.awaitAll()
        fixture.semantic.barrier = null
        assertEquals(outcomes[0].queryRuns, outcomes[1].queryRuns)
        assertEquals(1, count("dashboard.command_receipts", fixture.session.workspaceId))
        assertEquals(1, count("execution_run.runs", fixture.session.workspaceId))
        val run = requireNotNull(fixture.queryRuns.find(outcomes[0].queryRuns.getValue("q1")))
        assertEquals(null, run.actorId)
        assertEquals(null, run.credentialSurface)
        fixture.semantic.invalidMetric = "metric1"
        assertEquals(outcomes[0], fixture.service.resolvePublic(command(key)))
        val conflict = fixture.service.resolvePublic(command(key).copy(variables = mapOf("presentation" to JsonPrimitive("table"))))
        assertEquals("IDEMPOTENCY_CONFLICT", assertIs<SessionResolutionResult.Rejected>(conflict).code)
        fixture.semantic.invalidMetric = null
        assertEquals(outcomes[0].queryRuns, assertIs<SessionResolutionResult.Resolved>(fixture.service.resolve(fixture.resolve(key))).queryRuns)
        assertEquals(2, count("dashboard.command_receipts", fixture.session.workspaceId))
        assertEquals(outcomes[0].queryRuns, assertIs<SessionResolutionResult.Resolved>(fixture.service.resolvePublic(command(Uuid.random().toString()))).queryRuns)
        assertEquals(3, count("dashboard.command_receipts", fixture.session.workspaceId))
        assertEquals(1, count("execution_run.runs", fixture.session.workspaceId))
    }

    @Test
    fun `initial read blocked by DDL lock times out and releases its connection`() = runBlocking {
        val fixture = fixture(queryCount = 1, quota = 1)
        postgres.dataSource.connection.use { blocker ->
            blocker.autoCommit = false
            blocker.createStatement().use { it.execute("LOCK TABLE dashboard.sessions IN ACCESS EXCLUSIVE MODE") }
            val started = System.nanoTime()
            kotlin.test.assertFailsWith<cc.monomer.dashboard.application.DashboardReadDeadlineExceeded> {
                fixture.sessions.findInitial(fixture.session.workspaceId, fixture.session.dashboardId, fixture.session.id, Instant.now().plusMillis(150))
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500)
            blocker.rollback()
        }
        assertEquals(fixture.session, fixture.sessions.findInitial(fixture.session.workspaceId, fixture.session.dashboardId, fixture.session.id, Instant.now().plusSeconds(1)))
        postgres.dataSource.connection.use { connection -> connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND state='active' AND query LIKE '%dashboard.sessions%'").use { rows ->
                check(rows.next()); assertEquals(0, rows.getInt(1))
            }
        } }
    }

    @Test
    fun `SQL ordered typed binds and row limit round trip and constrain PostgreSQL reuse`() = runBlocking {
        val fixture = fixture(queryCount = 1, quota = 1)
        val original = fixture.session.definition
        val connectorId = original.query("q1").connectorId
        val definition = DashboardDefinitionDecoder.decode(JsonObject(original.document + mapOf(
            "variables" to Json.parseToJsonElement("""{
                "number":{"type":"NUMBER","nullable":false,"initial":"1.25"},
                "optionalDate":{"type":"DATE","nullable":true,"initial":null},
                "label":{"type":"TEXT","nullable":false,"initial":"x' OR '1'='1"}
            }"""),
            "queries" to Json.parseToJsonElement("""{"q1":{
                "type":"SQL","connectorId":"$connectorId",
                "sql":"SELECT {{number}} AS amount, {{optionalDate}} AS date, {{label}} AS label, {{number}} AS repeated",
                "bindings":{"label":{"kind":"VARIABLE","name":"label"},"number":{"kind":"VARIABLE","name":"number"},"optionalDate":{"kind":"VARIABLE","name":"optionalDate"}},
                "columns":["amount","date","label","repeated"],"rowLimit":50
            }}"""),
        )))
        val session = fixture.session.copy(id = DashboardSessionId.generate(), definition = definition)
        val query = definition.query("q1") as DashboardNamedQuery.Sql
        val binds = listOf(
            cc.monomer.dashboard.domain.DashboardBindValue.Number("1.25"),
            cc.monomer.dashboard.domain.DashboardBindValue.Null(cc.monomer.dashboard.domain.DashboardValueType.DATE),
            cc.monomer.dashboard.domain.DashboardBindValue.Text("x' OR '1'='1"),
            cc.monomer.dashboard.domain.DashboardBindValue.Number("1.25"),
        )
        val resolved = DashboardResolvedQuery.Sql(binds, query.rowLimit)
        val started = QueryRun.start(QueryRunId.generate(), session.workspaceId, session.dashboardId, session.id, query,
            resolved, query.template.executableSql, query.columns, NOW, null, null, null, outputColumnDisplays = null, reportingTimeZone = null)
        val run = started.succeed(ArrowResultManifest("artifacts/${started.id}", 1uL, "etag", 1uL, 4,
            "application/vnd.apache.arrow.file"), NOW.plusSeconds(1))
        fixture.transaction.inTransaction {
            check(fixture.dashboards.applyCurrent(session.workspaceId, session.dashboardId, definition, NOW, null, null, null))
            check(fixture.sessions.insert(session))
            check(fixture.queryRuns.insert(run))
        }
        val read = requireNotNull(fixture.queryRuns.find(run.id))
        assertEquals(cc.monomer.dashboard.domain.DashboardQueryType.SQL, read.queryType)
        assertEquals(run, read)
        assertEquals(resolved, read.resolvedQuery)
        assertEquals("SELECT ? AS amount, ? AS date, ? AS label, ? AS repeated", read.renderedSql)
        val expectedJson = cc.monomer.dashboard.domain.DashboardResolvedQueryCodec.encode(resolved)
        postgres.dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT query_type, resolved_query FROM dashboard.query_runs WHERE query_run_id = ?").use { statement ->
                statement.setObject(1, java.util.UUID.fromString(run.id.toString()))
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    assertEquals("SQL", rows.getString("query_type"))
                    assertEquals(expectedJson, Json.parseToJsonElement(rows.getString("resolved_query")))
                }
            }
        }
        val differingConditions = listOf<DashboardResolvedQuery>(
            resolved,
            resolved.copy(bindings = listOf(binds[2], binds[1], binds[0], binds[3])),
            resolved.copy(bindings = listOf(cc.monomer.dashboard.domain.DashboardBindValue.Text("1.25")) + binds.drop(1)),
            resolved.copy(bindings = listOf(binds[0], cc.monomer.dashboard.domain.DashboardBindValue.Null(cc.monomer.dashboard.domain.DashboardValueType.TEXT), binds[2], binds[3])),
            resolved.copy(rowLimit = 51),
        )
        val lookups = differingConditions.map { condition ->
            cc.monomer.dashboard.application.EquivalentQueryRunLookup(query.name, connectorId, condition, query.template.executableSql, query.columns)
        }
        val expected = listOf(run.id, null, null, null, null)
        val singles = lookups.map { lookup -> fixture.queryRuns.findEquivalent(session.workspaceId, session.dashboardId, session.id,
            lookup.queryName, lookup.connectorId, lookup.resolvedQuery, lookup.renderedSql, lookup.outputColumnNames, setOf(QueryRunState.SUCCEEDED)) }
        val batched = fixture.queryRuns.findEquivalentRuns(session.workspaceId, session.dashboardId, session.id, lookups,
            setOf(QueryRunState.SUCCEEDED), Instant.now().plusSeconds(5))
        assertEquals(expected, singles.map { it?.id })
        assertEquals(singles, batched)
    }

    @Test
    fun `batched equivalent lookup answers every lookup exactly as the single lookup does`() = runBlocking {
        val fixture = fixture(queryCount = 3, quota = 1)
        val session = fixture.session
        val otherSession = session.copy(id = DashboardSessionId.generate())
        fixture.transaction.inTransaction { check(fixture.sessions.insert(otherSession)) }
        val q1 = session.definition.query("q1") as DashboardNamedQuery.Semantic
        val q2 = session.definition.query("q2") as DashboardNamedQuery.Semantic
        val q3 = session.definition.query("q3") as DashboardNamedQuery.Semantic
        val escapedQuery = JsonObject(q2.query + ("label" to JsonPrimitive("quote\" comma, brace} backslash\\ {\"x\":[1,2]} NULL 한글")))
        val escapedColumns = listOf("metric.metric2", "comma,column", "quote\"column")
        suspend fun seed(
            viewer: DashboardSession, query: cc.monomer.dashboard.domain.DashboardNamedQuery, resolvedQuery: JsonObject,
            sql: String, columns: List<String>, createdAt: Instant, succeeded: Boolean,
        ): QueryRun {
            val started = QueryRun.start(QueryRunId.generate(), viewer.workspaceId, viewer.dashboardId, viewer.id, query, DashboardResolvedQuery.Semantic(resolvedQuery),
                sql, columns, createdAt, actorId = null, credentialSurface = null, agentClientId = null, outputColumnDisplays = null, reportingTimeZone = null)
            val stored = if (!succeeded) started else started.succeed(ArrowResultManifest("artifacts/${started.id}", 1uL, "etag", 1uL, 1,
                "application/vnd.apache.arrow.file"), createdAt.plusMillis(1))
            fixture.transaction.inTransaction { check(fixture.queryRuns.insert(stored)) }
            return stored
        }
        seed(session, q1, q1.query, "SELECT 1", listOf("metric.metric1"), NOW.minusSeconds(30), succeeded = true)
        val q1Newest = seed(session, q1, q1.query, "SELECT 1", listOf("metric.metric1"), NOW.minusSeconds(20), succeeded = true)
        seed(session, q1, q1.query, "SELECT 1", listOf("metric.metric1"), NOW.minusSeconds(10), succeeded = false)
        seed(otherSession, q1, q1.query, "SELECT 1", listOf("metric.metric1"), NOW.minusSeconds(5), succeeded = true)
        val q2Tied = listOf(
            seed(session, q2, escapedQuery, "SELECT 'a,b'", escapedColumns, NOW.minusSeconds(15), succeeded = true),
            seed(session, q2, escapedQuery, "SELECT 'a,b'", escapedColumns, NOW.minusSeconds(15), succeeded = true),
        )
        seed(session, q2, escapedQuery, "SELECT 'a,b' ", escapedColumns, NOW.minusSeconds(1), succeeded = true)
        seed(session, q2, escapedQuery, "SELECT 'a,b'", escapedColumns.reversed(), NOW.minusSeconds(1), succeeded = true)
        seed(session, q2, q2.query, "SELECT 'a,b'", escapedColumns, NOW.minusSeconds(1), succeeded = true)
        val q3Running = seed(session, q3, q3.query, "SELECT 3", listOf("metric.metric3"), NOW.minusSeconds(25), succeeded = false)
        val lookups = listOf(
            cc.monomer.dashboard.application.EquivalentQueryRunLookup("q1", q1.connectorId, DashboardResolvedQuery.Semantic(q1.query), "SELECT 1", listOf("metric.metric1")),
            cc.monomer.dashboard.application.EquivalentQueryRunLookup("q2", q2.connectorId, DashboardResolvedQuery.Semantic(escapedQuery), "SELECT 'a,b'", escapedColumns),
            cc.monomer.dashboard.application.EquivalentQueryRunLookup("q3", q3.connectorId, DashboardResolvedQuery.Semantic(q3.query), "SELECT 3", listOf("metric.metric3")),
            cc.monomer.dashboard.application.EquivalentQueryRunLookup("q3", q3.connectorId, DashboardResolvedQuery.Semantic(q3.query), "SELECT 4", listOf("metric.metric3")),
            cc.monomer.dashboard.application.EquivalentQueryRunLookup("q1", q1.connectorId, DashboardResolvedQuery.Semantic(q1.query), "SELECT 1", listOf("metric.metric1")),
        )
        val q2Latest = q2Tied.maxBy { it.id.value.toString() }
        val expectedByStates = mapOf(
            setOf(QueryRunState.SUCCEEDED) to listOf(q1Newest.id, q2Latest.id, null, null, q1Newest.id),
            setOf(QueryRunState.SUCCEEDED, QueryRunState.RUNNING) to listOf(q1Newest.id, q2Latest.id, q3Running.id, null, q1Newest.id),
        )
        for ((states, expectedIds) in expectedByStates) {
            val single = lookups.map { lookup ->
                fixture.queryRuns.findEquivalent(session.workspaceId, session.dashboardId, session.id, lookup.queryName, lookup.connectorId,
                    lookup.resolvedQuery, lookup.renderedSql, lookup.outputColumnNames, states)
            }
            val batched = fixture.queryRuns.findEquivalentRuns(session.workspaceId, session.dashboardId, session.id, lookups, states, Instant.now().plusSeconds(5))
            assertEquals(expectedIds, single.map { it?.id })
            assertEquals(single, batched)
        }
        assertEquals(List(lookups.size) { null }, fixture.queryRuns.findEquivalentRuns(
            session.workspaceId, DashboardId.generate(), session.id, lookups, setOf(QueryRunState.SUCCEEDED), Instant.now().plusSeconds(5)))
    }

    @Test
    fun `batched equivalent lookup blocked by DDL lock times out and releases its connection`() = runBlocking {
        val fixture = fixture(queryCount = 1, quota = 1)
        val query = fixture.session.definition.query("q1") as DashboardNamedQuery.Semantic
        val lookups = listOf(cc.monomer.dashboard.application.EquivalentQueryRunLookup("q1", query.connectorId, DashboardResolvedQuery.Semantic(query.query), "SELECT 1", listOf("metric.metric1")))
        postgres.dataSource.connection.use { blocker ->
            blocker.autoCommit = false
            blocker.createStatement().use { it.execute("LOCK TABLE dashboard.query_runs IN ACCESS EXCLUSIVE MODE") }
            val started = System.nanoTime()
            assertFailsWith<cc.monomer.dashboard.application.DashboardReadDeadlineExceeded> {
                fixture.queryRuns.findEquivalentRuns(fixture.session.workspaceId, fixture.session.dashboardId, fixture.session.id,
                    lookups, setOf(QueryRunState.SUCCEEDED), Instant.now().plusMillis(150))
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500)
            blocker.rollback()
        }
        assertEquals(listOf<QueryRun?>(null), fixture.queryRuns.findEquivalentRuns(fixture.session.workspaceId, fixture.session.dashboardId,
            fixture.session.id, lookups, setOf(QueryRunState.SUCCEEDED), Instant.now().plusSeconds(1)))
        postgres.dataSource.connection.use { connection -> connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND state='active' AND query LIKE '%lookup_position%'").use { rows ->
                check(rows.next()); assertEquals(0, rows.getInt(1))
            }
        } }
    }

    private fun historicalD12Schema(workspaceId: WorkspaceId): String {
        val schema = "d12_" + Uuid.random().toString().replace("-", "")
        fun definition(expression: String) =
            "jsonb_set($expression, '{layout,sections,0}', ($expression #> '{layout,sections,0}') - 'height' || jsonb_build_object('heightScale', $expression #> '{layout,sections,0,height,scale}'), false)"
        postgres.verifyEphemeralIdentity()
        postgres.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
                for (table in listOf("dashboards", "drafts", "sessions", "query_runs", "command_receipts")) {
                    statement.execute("CREATE TABLE $schema.$table (LIKE dashboard.$table INCLUDING ALL)")
                    statement.executeUpdate("INSERT INTO $schema.$table SELECT * FROM dashboard.$table WHERE workspace_id = '$workspaceId'::uuid")
                }
                // D12 predates section.height; only this isolated historical fixture uses heightScale.
                for ((table, column) in listOf("dashboards" to "definition", "drafts" to "definition", "sessions" to "definition_snapshot")) {
                    statement.executeUpdate("UPDATE $schema.$table SET $column = ${definition(column)}")
                }
                statement.executeUpdate("UPDATE $schema.command_receipts SET normalized_request = jsonb_set(normalized_request, '{definition}', ${definition("normalized_request->'definition'")}, false), response_snapshot = jsonb_set(response_snapshot, '{dashboard,definition}', ${definition("response_snapshot #> '{dashboard,definition}'")}, false) WHERE command_kind = 'CREATE_DASHBOARD'")
            }
        }
        return schema
    }

    private fun applyD12Cutover(dashboardSchema: String) {
        postgres.verifyEphemeralIdentity()
        val source = Path.of(System.getProperty("control.service.root"))
            .resolve("testkit/dashboard-d12-layout-cutover.sql").toFile().readText()
        val localIdentity = "current_database() <> 'monomer' OR current_user <> 'monomer'"
        check(source.contains(localIdentity))
        // Retain the cutover logic and guards; route only the disposable identity and fixture schema.
        val sql = source.replace(
            localIdentity,
            "current_database() <> 'monomer_authority_gate_ephemeral' OR current_user <> 'authority_gate'",
        ).replace("dashboard.", "$dashboardSchema.").lineSequence().mapNotNull { line ->
            when {
                line == "\\gset" -> ";"
                line.startsWith("\\") -> null
                else -> line
            }
        }.joinToString("\n")
        postgres.dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
    }

    private fun storedRows(dashboardSchema: String): Map<String, JsonElement> = postgres.dataSource.connection.use { connection ->
        listOf(
            "$dashboardSchema.dashboards", "$dashboardSchema.drafts", "$dashboardSchema.sessions", "$dashboardSchema.command_receipts",
            "$dashboardSchema.query_runs", "execution_run.runs", "execution_run.dispatch_intents",
        ).associateWith { table ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT COALESCE(jsonb_agg(to_jsonb(stored) ORDER BY to_jsonb(stored)::text), '[]'::jsonb) FROM $table AS stored",
                ).use { rows ->
                    check(rows.next())
                    Json.parseToJsonElement(rows.getString(1))
                }
            }
        }
    }

    private suspend fun fixture(queryCount: Int, quota: Int, isolationLevel: Int = TransactionDefinition.ISOLATION_REPEATABLE_READ): Fixture {
        val lookupObservation = LookupObservation()
        val dataSource = ObservedLookupDataSource(EphemeralPostgresAuthoritySupport.dataSource, lookupObservation)
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val dashboardId = DashboardId.generate()
        val queries = (1..queryCount).joinToString(",") { index ->
            """"q$index":{"type":"SEMANTIC","connectorId":"$connectorId","query":{"schemaVersion":4,"metrics":["metric$index"],"groupBys":[],"filters":[],"orderBy":[],"metricTimeRange":null,"rowLimit":100}}"""
        }
        val queryNames = (1..queryCount).joinToString(",") { "\"q$it\"" }
        val definition = DashboardDefinitionDecoder.decode(Json.parseToJsonElement("""
            {"schemaVersion":1,"meta":{"title":"D11"},"parameters":{},"variables":{"presentation":{"type":"TEXT","nullable":false,"initial":"chart"}},
             "queries":{$queries},"widgets":{"table":{"type":"CHART","queryNames":[$queryNames],"module":{"inputs":{"parameters":[],"variables":[]},"source":"export default defineChart({ render() { return {}; } });"}}},
             "layout":{"sections":[{"id":"main","height": { "mode": "SCALED", "scale": 1.0 },"root":{"type":"WIDGET","id":"item","widgetId":"table"}}]}}
        """).jsonObject)
        val dashboards = PostgresDashboardRepository(dataSource)
        val sessions = PostgresDashboardSessionRepository(dataSource)
        val queryRuns = PostgresQueryRunRepository(dataSource)
        val receipts = PostgresDashboardCommandReceiptRepository(dataSource)
        val lifecycleRepository = PostgresDashboardLifecycleRepository(dataSource)
        val sharedTransactionOperations = TransactionTemplate(DataSourceTransactionManager(dataSource)).apply {
            this.isolationLevel = isolationLevel
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
            timeout = 5
        }
        val transaction = DashboardApplicationComposition().dashboardTransactionPort(cc.monomer.control.jdbc.JdbcVirtualThreadDispatcher(dataSource), sharedTransactionOperations)
        assertEquals(isolationLevel, sharedTransactionOperations.isolationLevel)
        assertEquals(5, sharedTransactionOperations.timeout)
        val session = DashboardSession.open(DashboardSessionId.generate(), workspaceId, dashboardId, definition, emptyMap(), NOW)
        transaction.inTransaction {
            check(dashboards.insert(Dashboard(dashboardId, workspaceId, definition, NOW, NOW,
    updatedBy = null,
    credentialSurface = null,
    agentClientId = null,
)))
            check(sessions.insert(session))
        }
        val runtimeRunner = SpringExecutionRuntimeTransactionRunner(sharedTransactionOperations, Dispatchers.Unconfined)
        val executionStore = PostgresExecutionRuntimeStore(dataSource)
        val dispatch = PostgresRunDispatchPersistence(dataSource, sharedTransactionOperations)
        val assignmentCodec = ExecutableRequestAssignmentCodec()
        val admission = ExecutionRunAdmissionService(
            executionStore, dispatch, assignmentCodec, ExecutionRunLimits(quota),
        )
        val credentialVersion = Uuid.generateV7()
        val connector = ConnectorExecutionBinding(connectorId, workspaceId, "POSTGRESQL", "fixture", mapOf("host" to "fixture-warehouse"),
            CredentialEnvelopeReference(CredentialVersionId(credentialVersion), "fixture-key", ContentDigest("sha256:${"a".repeat(64)}")), NOW)
        val connectorProjection = ConnectorRuntimeBindingSpec(ConnectorReference(connectorId.value), ConnectorType.POSTGRESQL,
            mapOf("host" to ConnectorRuntimePropertyValue.Text("fixture-warehouse")),
            ManagedCredentialDispatchReference(CredentialVersionReference(credentialVersion), "fixture-key", Sha256Digest("a".repeat(64))))
        val adapter = DashboardExecutionAdmissionAdapter(
            object : ConnectorExecutionAccessInOwnerTransactionPort {
                override fun readCurrentForExecutionInOwnerTransaction(workspaceId: WorkspaceId, connectorId: ConnectorId): ConnectorExecutionAccessResult =
                    if (workspaceId == connector.workspaceId && connectorId == connector.connectorId) ConnectorExecutionAccessResult.Granted(connector)
                    else ConnectorExecutionAccessResult.NotFoundOrOutsideWorkspace
            }, ConnectorRuntimeBindingProjectionPort { connectorProjection }, ProtoRawSqlRunSpecFactory(), admission,
            Clock.fixed(NOW, ZoneOffset.UTC), testControlRuntimeProperties(),
        )
        val semantic = SemanticFixture()
        return Fixture(session, dashboards, sessions, queryRuns, receipts, lifecycleRepository, transaction, semantic, lookupObservation, adapter,
            DashboardSessionService(transaction, dashboards, sessions, queryRuns, receipts, semantic, adapter),
            ExecutionRunService(executionStore, runtimeRunner, runtimeRunner, dispatch, assignmentCodec))
    }

    private class LookupObservation {
        var statements = 0
        var afterSnapshot: (() -> Unit)? = null
    }

    private class ObservedLookupDataSource(delegate: DataSource, private val observation: LookupObservation) : DelegatingDataSource(delegate) {
        override fun getConnection(): Connection = observe(super.getConnection())
        override fun getConnection(username: String, password: String): Connection = observe(super.getConnection(username, password))
        private fun observe(connection: Connection): Connection = proxy(Connection::class.java, connection) { method, arguments, result ->
            if (method == "prepareStatement" && (arguments?.firstOrNull() as? String)?.contains("AND rendered_sql = ?") == true) {
                proxy(PreparedStatement::class.java, result as PreparedStatement) { statementMethod, _, statementResult ->
                    if (statementMethod == "executeQuery") {
                        observation.statements += 1
                        val afterSnapshot = observation.afterSnapshot
                        observation.afterSnapshot = null
                        afterSnapshot?.invoke()
                    }
                    statementResult
                }
            } else result
        }
        private fun <T> proxy(type: Class<T>, delegate: T, after: (String, Array<out Any?>?, Any?) -> Any?): T =
            type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments ->
                val result = try {
                    method.invoke(delegate, *(arguments ?: emptyArray()))
                } catch (error: InvocationTargetException) {
                    throw error.targetException
                }
                after(method.name, arguments, result)
            })
    }

    private class SemanticFixture : DashboardQueryPlanningPort {
        override suspend fun admitPageReader(
            workspaceId: cc.monomer.identity.api.WorkspaceId, dashboardId: cc.monomer.dashboard.api.DashboardId, pageId: kotlin.uuid.Uuid,
            credential: cc.monomer.identity.api.PresentedCredential?, expectedOAuthResource: cc.monomer.identity.api.ExpectedOAuthResource?,
        ): cc.monomer.dashboard.api.DashboardOwnerOutcome<cc.monomer.identity.api.IdentityCredentialEvidence?> = cc.monomer.dashboard.api.DashboardOwnerOutcome.Success(null)

        override suspend fun resolveBatch(commands: List<DashboardQueryPlanningPort.ResolveQueryCommand>): List<DashboardQueryResolution> = commands.map { resolve(it) }
        var barrier: CyclicBarrier? = null
        var sql = "SELECT 1"
        var columns = listOf("metric.metric1")
        var outputColumnDisplays: List<SemanticOutputColumnDisplay>? = null
        var reportingTimeZone: java.time.ZoneId = java.time.ZoneId.of("UTC")
        var invalidMetric: String? = null
        var onResolve: (suspend () -> Unit)? = null
        val unauthorizedCaller = PresentedCredential("unauthorized-fixture-caller")
        override suspend fun resolve(command: DashboardQueryPlanningPort.ResolveQueryCommand): DashboardQueryResolution {
            onResolve?.invoke()
            if (command.credential == unauthorizedCaller) return DashboardQueryResolution.Rejected("DASHBOARD_QUERY_UNAUTHORIZED", null)
            barrier?.let { withContext(Dispatchers.IO) { it.await(4, TimeUnit.SECONDS) } }
            if (invalidMetric?.let { (command.resolvedQuery as cc.monomer.dashboard.domain.DashboardResolvedQuery.Semantic).query.getValue("metrics").toString().contains(it) } == true) return DashboardQueryResolution.Rejected("INVALID_SEMANTIC_QUERY", null)
            return DashboardQueryResolution.Resolved(command.resolvedQuery, sql, columns, actorId = null,
    credentialSurface = null,
    agentClientId = null,
    parameterNames = emptyList(),
    outputColumnDisplays = outputColumnDisplays,
    reportingTimeZone = if (command.query is DashboardNamedQuery.Semantic) reportingTimeZone else null,
)
        }
    }

    private data class Fixture(
        val session: DashboardSession,
        val dashboards: PostgresDashboardRepository,
        val sessions: PostgresDashboardSessionRepository,
        val queryRuns: PostgresQueryRunRepository,
        val receipts: PostgresDashboardCommandReceiptRepository,
        val lifecycleRepository: PostgresDashboardLifecycleRepository,
        val transaction: DashboardTransactionPort,
        val semantic: SemanticFixture,
        val lookupObservation: LookupObservation,
        val executionAdmission: cc.monomer.dashboard.application.DashboardExecutionAdmissionPort,
        val service: DashboardSessionService,
        val execution: ExecutionRunService,
    ) {
        fun resolve(key: String) = DashboardSessionUseCase.ResolveCommand(session.workspaceId, PresentedCredential("fixture-token"), null,
            session.id, session.dashboardId, session.initialVariables, "fixture", key, NOW)
        fun refresh(key: String) = DashboardSessionUseCase.RefreshCommand(session.workspaceId, PresentedCredential("fixture-token"), null,
            session.dashboardId, session.id, session.definition.queries.keys, session.initialVariables, "fixture", key, NOW)
        suspend fun succeed(run: QueryRun, at: Instant): QueryRun {
            val executionId = ExecutionRunId(run.id.value)
            val key = requireNotNull(execution.get(executionId)).runtimeBindings.resultSink.objectKey!!
            val manifest = ArrowResultManifest(key, 1uL, "etag", 1uL, run.outputColumnNames.size, "application/vnd.apache.arrow.file")
            assertIs<ExecutionRunUseCase.TerminalOutcome.Accepted>(execution.recordPhysicalOutcome(
                ExecutionRunUseCase.RecordPhysicalOutcomeCommand(executionId,
                    PhysicalOutcome("$executionId:terminal", PhysicalOutcomeKind.SUCCEEDED, null, null, manifest), at)))
            val succeeded = run.succeed(manifest, at)
            transaction.inTransaction { check(queryRuns.update(run.updatedAt, succeeded)) }
            return succeeded
        }
        suspend fun seedSuccessful(createdAt: Instant, settledAt: Instant): QueryRun {
            val query = session.definition.query("q1") as DashboardNamedQuery.Semantic
            val started = QueryRun.start(QueryRunId.generate(), session.workspaceId, session.dashboardId, session.id, query, DashboardResolvedQuery.Semantic(query.query),
                semantic.sql, semantic.columns, createdAt,
                actorId = null,
                credentialSurface = null,
                agentClientId = null,
                outputColumnDisplays = null,
                reportingTimeZone = null,
            )
            val succeeded = started.succeed(ArrowResultManifest("artifacts/${started.id}", 1uL, "etag", 1uL, 1, "application/vnd.apache.arrow.file"), settledAt)
            transaction.inTransaction { check(queryRuns.insert(succeeded)) }
            return succeeded
        }
    }

    private fun count(table: String, workspaceId: WorkspaceId): Int = EphemeralPostgresAuthoritySupport.count(table, "workspace_id", workspaceId.value.toJavaUuid())
    private fun dispatchCount(workspaceId: WorkspaceId): Int = EphemeralPostgresAuthoritySupport.dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM execution_run.dispatch_intents d JOIN execution_run.runs r ON r.run_id = d.run_id WHERE r.workspace_id = ?").use { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
        }
    }

    companion object {
        private val NOW = Instant.parse("2026-09-06T00:00:00Z")
        @JvmField @RegisterExtension val postgres = EphemeralPostgresAuthoritySupport
        @JvmStatic @BeforeAll fun schemas() {
            postgres.verifyEphemeralIdentity()
            postgres.applyAuthoritySchemasTwice()
        }
    }
}
