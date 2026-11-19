@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import io.mockk.mockk
import io.mockk.every
import kotlin.uuid.toJavaUuid
import kotlinx.serialization.json.jsonPrimitive
import cc.monomer.control.EphemeralPostgresAuthoritySupport
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardDraftRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardSessionRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresQueryRunRepository
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardSessionId
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.dashboard.application.DashboardDefinitionDecoder
import cc.monomer.dashboard.application.DashboardDraftStatus
import cc.monomer.dashboard.application.DashboardDraftTargetQueryRun
import cc.monomer.dashboard.domain.Dashboard
import cc.monomer.dashboard.domain.DashboardDefinition
import cc.monomer.dashboard.domain.DashboardDraft
import cc.monomer.dashboard.domain.DashboardDraftReadyResult
import cc.monomer.dashboard.domain.DashboardNamedQuery
import cc.monomer.dashboard.domain.DashboardResolvedQuery
import cc.monomer.dashboard.domain.DashboardSession
import cc.monomer.dashboard.domain.QueryRun
import cc.monomer.dashboard.domain.QueryRunState
import cc.monomer.execution.api.ArrowResultManifest
import cc.monomer.identity.api.WorkspaceId
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DashboardDraftStatusPostgresIntegrationTest {
    private val dashboards by lazy { PostgresDashboardRepository(postgres.dataSource) }
    private val drafts by lazy { PostgresDashboardDraftRepository(postgres.dataSource) }
    private val sessions by lazy { PostgresDashboardSessionRepository(postgres.dataSource) }
    private val queryRuns by lazy { PostgresQueryRunRepository(postgres.dataSource) }
    private val transaction by lazy {
        SpringDashboardTransactionPort(
            TransactionTemplate(DataSourceTransactionManager(postgres.dataSource)).apply {
                isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
                timeout = 5
            },
            blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
    }

    @Test
    fun `Apply persists only the replayed Dashboard response JSON and replays it`() = runBlocking {
        val workspace = WorkspaceId.generate()
        val dashboard = DashboardId.generate()
        val definition = definition()
        val receipts = cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardCommandReceiptRepository(postgres.dataSource)
        transaction.inTransaction {
            check(dashboards.insert(Dashboard(dashboard, workspace, definition, NOW, NOW, null, null, null)))
            check(drafts.insert(DashboardDraft.start(workspace, dashboard, definition, emptyMap(), NOW)))
        }
        val admission = mockk<cc.monomer.dashboard.application.DashboardExecutionAdmissionPort> {
            every { lockWorkspace(workspace) } returns Unit
        }
        val service = cc.monomer.dashboard.application.DashboardDraftService(mockk(relaxed = true), transaction,
            dashboards, drafts, queryRuns, receipts, mockk(), admission, mockk())
        val command = cc.monomer.dashboard.application.DashboardDraftUseCase.ApplyCommand(workspace, dashboard,
            cc.monomer.identity.api.PresentedCredential("fixture-token"), null, 0, kotlin.uuid.Uuid.generateV7().toString(), "apply-receipt", NOW.plusSeconds(1), null, null)
        val applied = service.apply(command)
        kotlin.test.assertIs<cc.monomer.dashboard.application.DraftApplyResult.Applied>(applied)
        assertEquals(applied, service.apply(command))
        postgres.dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT response_snapshot FROM dashboard.command_receipts WHERE workspace_id = ? AND idempotency_key = ?").use { statement ->
                statement.setObject(1, workspace.value.toJavaUuid())
                statement.setString(2, "apply-receipt")
                statement.executeQuery().use { rows ->
                    kotlin.test.assertTrue(rows.next())
                    val response = Json.parseToJsonElement(rows.getString(1)).jsonObject
                    assertEquals(setOf("kind", "dashboard"), response.keys)
                    assertEquals("DRAFT_APPLIED", response.getValue("kind").jsonPrimitive.content)
                }
            }
        }
    }

    @Test
    fun `status reads the Draft revision target run states and ready results without the definition`() = runBlocking {
        val workspaceId = WorkspaceId.generate()
        val dashboardId = DashboardId.generate()
        val definition = definition()
        val target = run(workspaceId, dashboardId, null, definition, NOW.plusSeconds(1))
        val readyRun = run(workspaceId, dashboardId, null, definition, NOW.plusSeconds(2)).succeed(MANIFEST, NOW.plusSeconds(3))
        val ready = DashboardDraftReadyResult(0, emptyMap(), emptyMap(), mapOf("orders" to readyRun.id))
        val session = DashboardSession.open(DashboardSessionId.generate(), workspaceId, dashboardId, definition, emptyMap(), NOW)
        transaction.inTransaction {
            check(dashboards.insert(Dashboard(dashboardId, workspaceId, definition, NOW, NOW, null, null, null)))
            check(sessions.insert(session))
            listOf(target, readyRun, run(workspaceId, dashboardId, session.id, definition, NOW.plusSeconds(4))).forEach { check(queryRuns.insert(it)) }
        }
        assertEquals(null, transaction.inTransaction { drafts.findDraftStatus(workspaceId, dashboardId) })
        transaction.inTransaction {
            check(drafts.insert(DashboardDraft.start(workspaceId, dashboardId, definition, emptyMap(), NOW)
                .withTargets(mapOf("orders" to target.id), NOW).withReadyResults(mapOf("table" to ready), NOW)))
        }

        val running = transaction.inTransaction { drafts.findDraftStatus(workspaceId, dashboardId) }
        val draft = checkNotNull(transaction.inTransaction { drafts.findDraft(workspaceId, dashboardId) })
        assertEquals(DashboardDraftStatus(draft.authoringRevision, mapOf("orders" to DashboardDraftTargetQueryRun(target.id, QueryRunState.RUNNING)), draft.readyResults), running)
        assertEquals(mapOf("table" to ready), draft.readyResults)

        transaction.inTransaction { check(queryRuns.update(target.updatedAt, target.fail("QUERY_FAILED", null, NOW.plusSeconds(5)))) }
        assertEquals(QueryRunState.FAILED, transaction.inTransaction { drafts.findDraftStatus(workspaceId, dashboardId) }?.targetQueryRuns?.getValue("orders")?.state)
        assertEquals(null, transaction.inTransaction { drafts.findDraftStatus(WorkspaceId.generate(), dashboardId) })

        val missing = QueryRunId.generate()
        transaction.inTransaction { check(drafts.update(draft.updatedAt, draft.withTargets(mapOf("orders" to missing), NOW.plusSeconds(6)))) }
        assertFailsWith<IllegalStateException> { drafts.findDraftStatus(workspaceId, dashboardId) }
        Unit
    }

    private fun run(
        workspaceId: WorkspaceId,
        dashboardId: DashboardId,
        sessionId: DashboardSessionId?,
        definition: DashboardDefinition,
        at: Instant,
    ): QueryRun = QueryRun.start(
        QueryRunId.generate(), workspaceId, dashboardId, sessionId, definition.query("orders"),
        DashboardResolvedQuery.Semantic((definition.query("orders") as DashboardNamedQuery.Semantic).query), "SELECT 1", listOf("metric.orders"), at, null, null, null,
        outputColumnDisplays = null,
        reportingTimeZone = null,
    )

    private fun definition(): DashboardDefinition = DashboardDefinitionDecoder.decode(Json.parseToJsonElement("""
        {"schemaVersion":1,"meta":{"title":"Status"},"parameters":{},"variables":{},
         "queries":{"orders":{"type":"SEMANTIC","connectorId":"0195f3b2-5c84-7a44-8cc4-3e9bf4fd10de","query":{"schemaVersion":4,"metrics":["orders"],"groupBys":[],"filters":[],"orderBy":[],"metricTimeRange":null,"rowLimit":100}}},
         "widgets":{"table":{"type":"TABLE","title":"Orders","queryName":"orders","columns":[{"field":"metric.orders"}]}},
         "layout":{"sections":[{"id":"main","height":{"mode":"SCALED","scale":1.0},"root":{"type":"WIDGET","id":"item","widgetId":"table"}}]}}
    """).jsonObject)

    companion object {
        private val NOW = Instant.parse("2026-10-10T00:00:00Z")
        private val MANIFEST = ArrowResultManifest("results/fixture.arrow", 128u, "fixture-etag", 1u, 1, "application/vnd.apache.arrow.file")
        @JvmField @RegisterExtension val postgres = EphemeralPostgresAuthoritySupport
        @JvmStatic @BeforeAll fun schemas() { postgres.verifyEphemeralIdentity(); postgres.applyAuthoritySchemasTwice() }
    }
}
