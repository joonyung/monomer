@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.dashboard.domain.DashboardNamedQuery
import cc.monomer.dashboard.domain.DashboardResolvedQuery

import cc.monomer.control.ControlProductPostgresProperties
import cc.monomer.control.ControlProductTransactionProperties
import cc.monomer.control.EphemeralPostgresAuthoritySupport
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardDraftRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardSessionRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresQueryRunRepository
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardSessionId
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.dashboard.application.*
import cc.monomer.dashboard.domain.*
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.web.server.ServerWebExchange
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DashboardSsePostgresIntegrationTest {
    @Test
    fun `remote settlement and draft change invalidate B SSE and B reads committed owner snapshots`() = runBlocking<Unit> {
        val fixture = fixture()
        adapter().use { a ->
            adapter().use { b ->
                a.start()
                b.start()
                val sessionA = DashboardSessionEventHub(Clock.systemUTC(), a)
                val draftA = DashboardDraftEventHub(Clock.systemUTC(), a)
                val sessionController = fixture.sessionController(b)
                val draftController = fixture.draftController(b)
                val sessionReady = Channel<Unit>(1)
                val draftReady = Channel<Unit>(1)
                val sessionObserved = async(start = CoroutineStart.UNDISPATCHED) {
                    sessionController.events(fixture.workspace.toString(), fixture.session.id.toString(), mockk<ServerWebExchange>())
                        .onEach { if ((it.data() as? Map<*, *>)?.get("reason") == "RECONNECTED") sessionReady.send(Unit) }
                        .first { (it.data() as? Map<*, *>)?.get("reason") == "QUERY_RUN_SETTLED" }
                    fixture.readRun().state
                }
                val draftObserved = async(start = CoroutineStart.UNDISPATCHED) {
                    draftController.draftEvents(fixture.workspace.toString(), fixture.dashboardId.toString(), mockk<ServerWebExchange>())
                        .onEach { if ((it.data() as? Map<*, *>)?.get("reason") == "RECONNECTED") draftReady.send(Unit) }
                        .first { (it.data() as? Map<*, *>)?.get("reason") == "DRAFT_CHANGED" }
                    fixture.readDraft().authoringRevision
                }
                withTimeout(5_000) { sessionReady.receive(); draftReady.receive() }
                postgres.transactionOperations.executeWithoutResult {
                    assertTrue(fixture.queryRunRepository.update(fixture.run.updatedAt, fixture.run.fail("QUERY_FAILED", null, NOW.plusSeconds(1))))
                    assertTrue(fixture.draftRepository.update(fixture.draft.updatedAt, fixture.draft.copy(authoringRevision = 1, updatedAt = NOW.plusSeconds(1))))
                    publishSessionSettlementForTest(sessionA, fixture.session.id, "QUERY_RUN_SETTLED")
                    publishDraftSettlementForTest(draftA, fixture.dashboardId, "DRAFT_CHANGED")
                }
                withTimeout(5_000) {
                    assertEquals(QueryRunState.FAILED, sessionObserved.await())
                    assertEquals(1, draftObserved.await())
                }
            }
        }
    }

    @Test
    fun `already opened draft SSE converges after listener restarts across a missed change`() = runBlocking<Unit> {
        val fixture = fixture()
        adapter().use { a ->
            adapter().use { b ->
                a.start()
                b.start()
                val snapshots = Channel<Long>(Channel.UNLIMITED)
                val observed = async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.draftController(b).draftEvents(fixture.workspace.toString(), fixture.dashboardId.toString(), mockk())
                        .onEach { if (it.event() == "invalidate") snapshots.send(fixture.readDraft().authoringRevision) }
                        .first { (it.data() as? Map<*, *>)?.get("reason") == "DRAFT_APPLIED" }
                }
                withTimeout(5_000) { assertEquals(0, snapshots.receive()) }
                b.close()
                postgres.transactionOperations.executeWithoutResult {
                    assertTrue(fixture.draftRepository.update(fixture.draft.updatedAt, fixture.draft.copy(authoringRevision = 2, updatedAt = NOW.plusSeconds(2))))
                    DashboardDraftEventHub(Clock.systemUTC(), a).emit(fixture.dashboardId, "DRAFT_CHANGED")
                }
                b.start()
                withTimeout(5_000) { assertEquals(2, snapshots.receive()) }
                DashboardDraftEventHub(Clock.systemUTC(), a).emit(fixture.dashboardId, "DRAFT_APPLIED")
                withTimeout(5_000) { observed.await() }
            }
        }
    }

    private fun fixture(): Fixture {
        val workspace = WorkspaceId.generate()
        val dashboardId = DashboardId.generate()
        val definition = DashboardDefinitionDecoder.decode(Json.parseToJsonElement("""
            {"schemaVersion":1,"meta":{"title":"Notification fixture"},"parameters":{},"variables":{},
             "queries":{"orders":{"type":"SEMANTIC","connectorId":"019e0000-0000-7000-8000-0000000000b1","query":{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"filters":[],"orderBy":[],"metricTimeRange":null,"rowLimit":100}}},
             "widgets":{"table":{"type":"CHART","queryNames":["orders"],"module":{"inputs":{"parameters":[],"variables":[]},"source":"export default defineChart({ render() { return {}; } });"}}},"layout":{"sections":[{"id":"main","height": { "mode": "SCALED", "scale": 1.0 },"root":{"type":"WIDGET","id":"item","widgetId":"table"}}]}}
        """).jsonObject)
        val session = DashboardSession.open(DashboardSessionId.generate(), workspace, dashboardId, definition, emptyMap(), NOW)
        val draft = DashboardDraft.start(workspace, dashboardId, definition, emptyMap(), NOW)
        val run = QueryRun.start(QueryRunId.generate(), workspace, dashboardId, session.id, definition.query("orders"),
            DashboardResolvedQuery.Semantic((definition.query("orders") as DashboardNamedQuery.Semantic).query), "select 1", listOf("revenue"), NOW, null, null, null, outputColumnDisplays = null, reportingTimeZone = null)
        val sessionRepository = PostgresDashboardSessionRepository(postgres.dataSource)
        val draftRepository = PostgresDashboardDraftRepository(postgres.dataSource)
        val queryRunRepository = PostgresQueryRunRepository(postgres.dataSource)
        postgres.transactionOperations.executeWithoutResult {
            assertTrue(PostgresDashboardRepository(postgres.dataSource).insert(Dashboard(dashboardId, workspace, definition, NOW, NOW, null, null, null)))
            assertTrue(sessionRepository.insert(session))
            assertTrue(draftRepository.insert(draft))
            assertTrue(queryRunRepository.insert(run))
        }
        return Fixture(workspace, dashboardId, session, draft, run, sessionRepository, draftRepository, queryRunRepository)
    }

    private data class Fixture(
        val workspace: WorkspaceId,
        val dashboardId: DashboardId,
        val session: DashboardSession,
        val draft: DashboardDraft,
        val run: QueryRun,
        val sessionRepository: PostgresDashboardSessionRepository,
        val draftRepository: PostgresDashboardDraftRepository,
        val queryRunRepository: PostgresQueryRunRepository,
    ) {
        fun readRun(): QueryRun = assertNotNull(postgres.transactionOperations.execute { queryRunRepository.find(run.id) })
        fun readDraft(): DashboardDraft = assertNotNull(postgres.transactionOperations.execute { draftRepository.findDraft(workspace, dashboardId) })

        fun sessionController(notifications: PostgresDashboardNotifications): DashboardSessionController {
            val sessions = mockk<DashboardSessionUseCase>()
            coEvery { sessions.get(workspace, session.id) } answers {
                SessionResult.Found(assertNotNull(postgres.transactionOperations.execute { sessionRepository.find(workspace, session.id) }), listOf(readRun()))
            }
            return DashboardSessionController(sessions, mockk(), authorizer(), DashboardSessionEventHub(Clock.systemUTC(), notifications),
                Clock.systemUTC(), ObjectMapper(), mockk(), cc.monomer.control.agentapp.FixedAgentAppDisplays, kotlinx.coroutines.Dispatchers.IO)
        }

        fun draftController(notifications: PostgresDashboardNotifications): DashboardDraftController {
            val drafts = mockk<DashboardDraftUseCase>()
            coEvery { drafts.get(workspace, dashboardId) } answers { DraftResult.Found(readDraft(), emptyList()) }
            return DashboardDraftController(drafts, mockk(), authorizer(), DashboardDraftEventHub(Clock.systemUTC(), notifications),
                Clock.systemUTC(), ObjectMapper(), mockk(), cc.monomer.control.agentapp.FixedAgentAppDisplays, kotlinx.coroutines.Dispatchers.IO)
        }

        private fun authorizer(): DashboardRequestAuthorizer = mockk<DashboardRequestAuthorizer>().also { authorizer ->
            coEvery { authorizer.requireWorkspace(any(), workspace.toString()) } returns
                AuthorizedDashboardRequest(workspace, cc.monomer.identity.api.OrganizationId.generate(), "fixture", PresentedCredential("test-credential"), CredentialSurface.WEB, null)
        }
    }

    private fun adapter(): PostgresDashboardNotifications {
        val source = postgres.newDataSource() as DriverManagerDataSource
        return PostgresDashboardNotifications(
            ControlProductPostgresProperties(requireNotNull(source.url), requireNotNull(source.username), requireNotNull(source.password),
                2, Duration.ofSeconds(1), Duration.ofMillis(250)),
            ControlProductTransactionProperties(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(100)),
            Duration.ofMillis(20),
            publicationDataSource = postgres.dataSource,
        )
    }

    companion object {
        private val NOW = Instant.parse("2026-09-11T00:00:00Z")
        @JvmField @RegisterExtension val postgres = EphemeralPostgresAuthoritySupport
        @JvmStatic @BeforeAll fun schemas() {
            postgres.verifyEphemeralIdentity()
            postgres.applyAuthoritySchemasTwice()
        }
    }
}
