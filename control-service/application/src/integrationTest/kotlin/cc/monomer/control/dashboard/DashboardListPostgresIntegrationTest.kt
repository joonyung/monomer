@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.dashboard.domain.DashboardNamedQuery
import cc.monomer.dashboard.domain.DashboardResolvedQuery

import cc.monomer.control.EphemeralPostgresAuthoritySupport
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardDraftRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardSessionRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresQueryRunRepository
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardSessionId
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.dashboard.application.DashboardDefinitionDecoder
import cc.monomer.dashboard.domain.Dashboard
import cc.monomer.dashboard.domain.DashboardDefinition
import cc.monomer.dashboard.domain.DashboardDraft
import cc.monomer.dashboard.domain.DashboardListEntry
import cc.monomer.dashboard.domain.DashboardListSchedule
import cc.monomer.dashboard.domain.DashboardSession
import cc.monomer.dashboard.domain.QueryRun
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

class DashboardListPostgresIntegrationTest {
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
    fun `list derives latest Session results schedules and Draft change state from stored facts`() = runBlocking {
        val workspaceId = WorkspaceId.generate()
        val otherWorkspaceId = WorkspaceId.generate()
        val plain = DashboardId.generate()
        val withResults = DashboardId.generate()
        val scheduled = DashboardId.generate()
        val draftEqual = DashboardId.generate()
        val draftChanged = DashboardId.generate()
        val plainDefinition = definition("Plain", schedules = null)
        val scheduledDefinition = definition(
            "Scheduled",
            schedules = """{"weekly":{"cron":"0 9 * * 1","timeZone":"Asia/Seoul","parameters":{}},"daily":{"cron":"0 6 * * *","timeZone":"UTC","parameters":{}}}""",
        )
        transaction.inTransaction {
            insert(plain, workspaceId, plainDefinition, NOW)
            insert(withResults, workspaceId, definition("Results", schedules = null), NOW.plusSeconds(1))
            insert(scheduled, workspaceId, scheduledDefinition, NOW.plusSeconds(2))
            insert(draftEqual, workspaceId, definition("Draft equal", schedules = null), NOW.plusSeconds(3))
            insert(draftChanged, workspaceId, definition("Draft changed", schedules = null), NOW.plusSeconds(4))
            insert(DashboardId.generate(), otherWorkspaceId, plainDefinition, NOW)

            check(drafts.insert(DashboardDraft.start(workspaceId, draftEqual, definition("Draft equal", schedules = null), emptyMap(), NOW)))
            check(drafts.insert(DashboardDraft.start(workspaceId, draftChanged, definition("Draft changed (edited)", schedules = null), emptyMap(), NOW)))

            val resultsDefinition = definition("Results", schedules = null)
            val session = DashboardSession.open(DashboardSessionId.generate(), workspaceId, withResults, resultsDefinition, emptyMap(), NOW)
            check(sessions.insert(session))
            val earlier = run(workspaceId, withResults, session.id, resultsDefinition, NOW.plusSeconds(10)).succeed(MANIFEST, NOW.plusSeconds(20))
            val latest = run(workspaceId, withResults, session.id, resultsDefinition, NOW.plusSeconds(30)).succeed(MANIFEST, NOW.plusSeconds(40))
            val laterFailure = run(workspaceId, withResults, session.id, resultsDefinition, NOW.plusSeconds(50)).fail("QUERY_FAILED", null, NOW.plusSeconds(60))
            val running = run(workspaceId, withResults, session.id, resultsDefinition, NOW.plusSeconds(70))
            val laterDraftRun = run(workspaceId, withResults, null, resultsDefinition, NOW.plusSeconds(80)).succeed(MANIFEST, NOW.plusSeconds(90))
            listOf(earlier, latest, laterFailure, running, laterDraftRun).forEach { check(queryRuns.insert(it)) }
        }

        val entries = transaction.inTransaction { dashboards.list(workspaceId) }.associateBy(DashboardListEntry::id)

        assertEquals(setOf(plain, withResults, scheduled, draftEqual, draftChanged), entries.keys)
        assertEquals(listOf(draftChanged, draftEqual, scheduled, withResults, plain), transaction.inTransaction { dashboards.list(workspaceId) }.map { it.id })
        with(entries.getValue(plain)) {
            assertEquals(null, latestResultsAt)
            assertEquals(emptyList(), schedules)
            assertEquals(false, draftHasChanges)
        }
        assertEquals(NOW.plusSeconds(40), entries.getValue(withResults).latestResultsAt)
        assertEquals(
            listOf(DashboardListSchedule("daily", "0 6 * * *", "UTC"), DashboardListSchedule("weekly", "0 9 * * 1", "Asia/Seoul")),
            entries.getValue(scheduled).schedules,
        )
        assertEquals(false, entries.getValue(draftEqual).draftHasChanges)
        assertEquals(true, entries.getValue(draftChanged).draftHasChanges)
    }

    private fun insert(id: DashboardId, workspaceId: WorkspaceId, definition: DashboardDefinition, at: Instant) {
        check(dashboards.insert(Dashboard(id, workspaceId, definition, at, at, null, null, null)))
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

    private fun definition(title: String, schedules: String?): DashboardDefinition {
        val scheduleField = schedules?.let { ""","schedules":$it""" }.orEmpty()
        return DashboardDefinitionDecoder.decode(Json.parseToJsonElement("""
            {"schemaVersion":1,"meta":{"title":"$title"},"parameters":{},"variables":{},
             "queries":{"orders":{"type":"SEMANTIC","connectorId":"0195f3b2-5c84-7a44-8cc4-3e9bf4fd10de","query":{"schemaVersion":4,"metrics":["orders"],"groupBys":[],"filters":[],"orderBy":[],"metricTimeRange":null,"rowLimit":100}}},
             "widgets":{"table":{"type":"TABLE","title":"Orders","queryName":"orders","columns":[{"field":"metric.orders"}]}},
             "layout":{"sections":[{"id":"main","height":{"mode":"SCALED","scale":1.0},"root":{"type":"WIDGET","id":"item","widgetId":"table"}}]}$scheduleField}
        """).jsonObject)
    }

    companion object {
        private val NOW = Instant.parse("2026-09-06T00:00:00Z")
        private val MANIFEST = ArrowResultManifest("results/fixture.arrow", 128u, "fixture-etag", 1u, 1, "application/vnd.apache.arrow.file")
        @JvmField @RegisterExtension val postgres = EphemeralPostgresAuthoritySupport
        @JvmStatic @BeforeAll fun schemas() { postgres.verifyEphemeralIdentity(); postgres.applyAuthoritySchemasTwice() }
    }
}
