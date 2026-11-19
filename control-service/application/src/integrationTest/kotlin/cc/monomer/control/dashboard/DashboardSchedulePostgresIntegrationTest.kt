@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.dashboard.domain.DashboardNamedQuery

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
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardCommandReceiptRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardScheduleRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardSessionRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresQueryRunRepository
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.application.DashboardDefinitionDecoder
import cc.monomer.dashboard.application.DashboardQueryPlanningPort
import cc.monomer.dashboard.application.DashboardSessionService
import cc.monomer.dashboard.application.DashboardTransactionPort
import cc.monomer.dashboard.application.DashboardQueryResolution
import cc.monomer.dashboard.domain.Dashboard
import cc.monomer.execution.adapter.inbound.grpc.ExecutableRequestAssignmentCodec
import cc.monomer.execution.adapter.inbound.grpc.ProtoRawSqlRunSpecFactory
import cc.monomer.execution.adapter.outbound.postgres.PostgresExecutionRuntimeStore
import cc.monomer.execution.adapter.outbound.postgres.PostgresRunDispatchPersistence
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorRuntimeBindingSpec
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.CredentialVersionReference
import cc.monomer.execution.api.ManagedCredentialDispatchReference
import cc.monomer.execution.api.Sha256Digest
import cc.monomer.execution.application.ExecutionRunAdmissionService
import cc.monomer.execution.application.ExecutionRunLimits
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import java.time.Duration
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

import cc.monomer.dashboard.application.DashboardScheduleService
import kotlinx.serialization.json.jsonPrimitive

class DashboardSchedulePostgresIntegrationTest {
    private val fixtureWorkspaceIds = mutableListOf<WorkspaceId>()

    @AfterEach
    fun clearOwnedSchedulePollingInputs() {
        postgres.verifyEphemeralIdentity()
        // Schedule polling spans all workspaces; prior fixtures can remain or become due between tests.
        postgres.dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM dashboard.schedule_cursors WHERE workspace_id = ?").use { statement ->
                fixtureWorkspaceIds.forEach { workspaceId ->
                    statement.setObject(1, workspaceId.value.toJavaUuid())
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun `two schedulers accept one immutable occurrence and restart observes committed cursor`() = runBlocking {
        val fixture = fixture(2, 10)
        val due = fixture.due(Instant.now().minusSeconds(60))
        fixture.semantic.barrier = CyclicBarrier(2)
        val second = fixture.runner()
        listOf(fixture.scheduler, second).map { runner -> async(Dispatchers.IO) { runner.tick() } }.awaitAll()
        assertEquals(1, fixture.count("dashboard.schedule_occurrences"))
        assertEquals(1, fixture.count("dashboard.sessions"))
        assertEquals(2, fixture.count("dashboard.query_runs"))
        assertEquals(2, fixture.count("execution_run.runs"))
        val occurrence = fixture.schedules.readOccurrence(fixture.workspaceId, fixture.dashboardId, "daily", due)!!
        assertEquals("ACCEPTED", occurrence.outcome)
        assertEquals(2, occurrence.initialQueryRuns.size)
        assertEquals(due.toString(), occurrence.parameters!!.getValue("at").jsonPrimitive.content)
        val session = fixture.sessions.find(fixture.workspaceId, occurrence.sessionId!!)!!
        assertEquals(occurrence.parameters, session.parameterBindings)
        assertEquals(occurrence.parameters!!.getValue("at"), session.initialVariables.getValue("at"))
        assertEquals(occurrence.definition, session.definition.document)
        fixture.semantic.barrier = null
        fixture.runner().tick()
        assertEquals(1, fixture.count("dashboard.sessions"))
        assertEquals(occurrence, fixture.schedules.readOccurrence(fixture.workspaceId, fixture.dashboardId, "daily", due))
    }

    @Test
    fun `second query admission rejection rolls back all owner and execution rows then records failure`() = runBlocking {
        val fixture = fixture(2, 1)
        val due = fixture.due(Instant.now().minusSeconds(30))
        val result = fixture.scheduler.tick()
        assertEquals(1, result.processed)
        assertEquals(0, fixture.count("dashboard.sessions"))
        assertEquals(0, fixture.count("dashboard.query_runs"))
        assertEquals(0, fixture.count("execution_run.runs"))
        val occurrence = fixture.schedules.readOccurrence(fixture.workspaceId, fixture.dashboardId, "daily", due)!!
        assertEquals("ADMISSION_FAILED", occurrence.outcome)
        assertEquals("EXECUTION_ADMISSION_REJECTED", occurrence.reason)
        assertTrue(occurrence.initialQueryRuns.isEmpty())
        assertEquals(null, occurrence.sessionId)
    }

    @Test
    fun `late and running initial occurrence skip without extra query planning`() = runBlocking {
        val fixture = fixture(1, 10)
        val old = fixture.due(Instant.now().minusSeconds(901))
        fixture.scheduler.tick()
        assertEquals("LATE", fixture.schedules.readOccurrence(fixture.workspaceId, fixture.dashboardId, "daily", old)!!.reason)
        assertEquals(0, fixture.semantic.calls)
        val first = fixture.due(Instant.now().minusSeconds(60))
        fixture.scheduler.tick()
        val accepted = fixture.schedules.readOccurrence(fixture.workspaceId, fixture.dashboardId, "daily", first)!!
        assertEquals("ACCEPTED", accepted.outcome)
        val next = fixture.due(Instant.now().minusSeconds(10))
        fixture.scheduler.tick()
        assertEquals("OVERLAP", fixture.schedules.readOccurrence(fixture.workspaceId, fixture.dashboardId, "daily", next)!!.reason)
        assertEquals(1, fixture.count("dashboard.sessions"))
        assertEquals(accepted, fixture.schedules.readOccurrence(fixture.workspaceId, fixture.dashboardId, "daily", first))
    }

    @Test
    fun `same settings preserve cursor changed settings reset and removing then readding preserves history`() = runBlocking {
        val fixture = fixture(1, 10)
        val due = fixture.due(Instant.now().minusSeconds(901))
        fixture.transaction.inTransaction {
            fixture.dashboards.applyCurrent(fixture.workspaceId, fixture.dashboardId, fixture.definition, NOW, null, null, null)
        }
        assertEquals(due, fixture.schedules.listSchedules(fixture.workspaceId, fixture.dashboardId).single().nextScheduledAt)
        fixture.scheduler.tick()
        val occurrence = fixture.schedules.readOccurrence(fixture.workspaceId, fixture.dashboardId, "daily", due)!!
        val removed = DashboardDefinitionDecoder.decode(JsonObject(fixture.definition.document - "schedules"))
        fixture.transaction.inTransaction { fixture.dashboards.applyCurrent(fixture.workspaceId, fixture.dashboardId, removed, NOW, null, null, null) }
        assertTrue(fixture.schedules.listSchedules(fixture.workspaceId, fixture.dashboardId).isEmpty())
        fixture.transaction.inTransaction { fixture.dashboards.applyCurrent(fixture.workspaceId, fixture.dashboardId, fixture.definition, NOW, null, null, null) }
        assertTrue(fixture.schedules.listSchedules(fixture.workspaceId, fixture.dashboardId).single().nextScheduledAt > Instant.now())
        assertEquals(occurrence, fixture.schedules.readOccurrence(fixture.workspaceId, fixture.dashboardId, "daily", due))
    }

    @Test
    fun `Apply during planning invalidates prepared definition and creates no stale session`() = runBlocking {
        val fixture = fixture(1, 10)
        val due = fixture.due(Instant.now().minusSeconds(20))
        fixture.semantic.onResolve = {
            fixture.transaction.inTransaction {
                val changed = DashboardDefinitionDecoder.decode(JsonObject(fixture.definition.document + ("meta" to JsonObject(mapOf("title" to JsonPrimitive("Changed"))))))
                fixture.dashboards.applyCurrent(fixture.workspaceId, fixture.dashboardId, changed, NOW, null, null, null)
            }
        }
        fixture.scheduler.tick()
        assertEquals(0, fixture.count("dashboard.schedule_occurrences"))
        assertEquals(0, fixture.count("dashboard.sessions"))
        assertEquals(due, fixture.schedules.listSchedules(fixture.workspaceId, fixture.dashboardId).single().nextScheduledAt)
        fixture.semantic.onResolve = null
        fixture.scheduler.tick()
        assertEquals("Changed", fixture.schedules.readOccurrence(fixture.workspaceId, fixture.dashboardId, "daily", due)!!.definition.getValue("meta").jsonObject.getValue("title").jsonPrimitive.content)
    }

    @Test
    fun `commit response loss never creates admission failure or repeats accepted runs`() = runBlocking {
        val fixture = fixture(1, 10)
        val due = fixture.due(Instant.now().minusSeconds(20))
        var lost = false
        val lostCommit = object : DashboardTransactionPort {
            override suspend fun <T> inTransaction(block: () -> T): T {
                val result = fixture.transaction.inTransaction(block)
                if (!lost && fixture.count("dashboard.schedule_occurrences") == 1) { lost = true; throw java.sql.SQLException("simulated lost commit response") }
                return result
            }
        }
        val runner = DashboardScheduleService(lostCommit, fixture.schedules, fixture.sessions, fixture.queryRuns, fixture.adapter, fixture.service)
        assertEquals(1, runner.tick().failed)
        fixture.runner().tick()
        assertEquals(1, fixture.count("dashboard.schedule_occurrences"))
        assertEquals(1, fixture.count("dashboard.sessions"))
        assertEquals("ACCEPTED", fixture.schedules.readOccurrence(fixture.workspaceId, fixture.dashboardId, "daily", due)!!.outcome)
    }

    @Test
    fun `terminal initial run with unreleased execution occupancy still blocks overlap`() = runBlocking {
        val fixture = fixture(1, 10)
        val first = fixture.due(Instant.now().minusSeconds(60))
        fixture.scheduler.tick()
        val occurrence = fixture.schedules.readOccurrence(fixture.workspaceId, fixture.dashboardId, "daily", first)!!
        val runId = occurrence.initialQueryRuns.values.single()
        fixture.transaction.inTransaction {
            val run = fixture.queryRuns.find(runId)!!
            check(fixture.queryRuns.update(run.updatedAt, run.cancel(run.updatedAt.plusSeconds(1))))
        }
        postgres.dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE execution_run.runs SET dispatch_claimed_at = created_at, execution_occupancy_acquired_at = clock_timestamp() WHERE run_id = ?").use {
                it.setObject(1, runId.value.toJavaUuid()); check(it.executeUpdate() == 1)
            }
        }
        val next = fixture.due(Instant.now().minusSeconds(10))
        fixture.scheduler.tick()
        assertEquals("OVERLAP", fixture.schedules.readOccurrence(fixture.workspaceId, fixture.dashboardId, "daily", next)!!.reason)
        assertEquals(1, fixture.count("dashboard.sessions"))
    }

    @Test
    fun `database lock timeout leaves cursor untouched for next tick`() = runBlocking {
        val fixture = fixture(1, 10)
        val due = fixture.due(Instant.now().minusSeconds(20))
        postgres.dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("SELECT dashboard_id FROM dashboard.dashboards WHERE dashboard_id = ? FOR UPDATE").use {
                it.setObject(1, fixture.dashboardId.value.toJavaUuid()); it.executeQuery().close()
            }
            val started = System.nanoTime()
            assertEquals(1, fixture.scheduler.tick().failed)
            assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(5))
            assertEquals(0, fixture.count("dashboard.schedule_occurrences"))
            assertEquals(due, fixture.schedules.listSchedules(fixture.workspaceId, fixture.dashboardId).single().nextScheduledAt)
            connection.rollback()
        }
        fixture.scheduler.tick()
        assertEquals("ACCEPTED", fixture.schedules.readOccurrence(fixture.workspaceId, fixture.dashboardId, "daily", due)!!.outcome)
    }

    @Test
    fun `polling is bounded and rotates across more than one batch of workspaces`() = runBlocking {
        val fixtures = List(33) { fixture(1, 10) }
        fixtures.forEach { it.due(Instant.now().minusSeconds(901)) }
        val runner = fixtures.first().runner()
        val first = runner.tick()
        assertEquals(32, first.candidates)
        assertEquals(32, first.processed)
        assertTrue(first.truncated)
        assertTrue(first.oldestCandidateDelayMillis >= 901_000)
        assertEquals(32, fixtures.sumOf { it.count("dashboard.schedule_occurrences") })
        runner.tick()
        assertTrue(fixtures.all { it.count("dashboard.schedule_occurrences") >= 1 })
        assertTrue(fixtures.all { it.semantic.calls == 0 })
    }

    @Test
    fun `readding key after clock regression starts strictly beyond last processed instant`() = runBlocking {
        val fixture = fixture(1, 10)
        val future = Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        val candidate = cc.monomer.dashboard.application.DashboardScheduleCandidate(fixture.workspaceId, fixture.dashboardId, "daily", future,
            fixture.dashboards.find(fixture.workspaceId, fixture.dashboardId)!!)
        fixture.transaction.inTransaction {
            fixture.schedules.insertOccurrence(candidate, cc.monomer.dashboard.api.DashboardScheduleOccurrenceView("daily", future, future,
                "SKIPPED", "LATE", fixture.definition.document, null, null, emptyMap()))
            val removed = DashboardDefinitionDecoder.decode(JsonObject(fixture.definition.document - "schedules"))
            fixture.dashboards.applyCurrent(fixture.workspaceId, fixture.dashboardId, removed, NOW, null, null, null)
            fixture.dashboards.applyCurrent(fixture.workspaceId, fixture.dashboardId, fixture.definition, NOW, null, null, null)
        }
        assertEquals(fixture.definition.schedules.getValue("daily").nextAfter(future), fixture.schedules.listSchedules(fixture.workspaceId, fixture.dashboardId).single().nextScheduledAt)
    }

    @Test
    fun `scheduled semantic planning applies a real database statement timeout in ambient snapshot`() = runBlocking {
        val fixture = fixture(1, 10)
        val dataSource = postgres.dataSource
        val semantic = io.mockk.mockk<cc.monomer.semantic.application.SemanticQueryUseCase>()
        io.mockk.coEvery { semantic.render(any()) } coAnswers {
            val connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource)
            try {
                connection.prepareStatement("SELECT pg_sleep(6)").use { it.execute() }
            } finally {
                org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection, dataSource)
            }
            error("the database statement must time out")
        }
        val snapshot = cc.monomer.identity.outbound.postgres.SpringConsistentSnapshotTransactionRunner(
            TransactionTemplate(DataSourceTransactionManager(dataSource)).apply {
                isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
                timeout = 10
            }, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        val adapter = DashboardQueryPlanningAdapter(dataSource, tools.jackson.databind.ObjectMapper(), semantic, io.mockk.mockk(), snapshot,
            TransactionTemplate(DataSourceTransactionManager(dataSource)).apply {
                isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
                timeout = 5
            }, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined, pageAccess = io.mockk.mockk(), pages = io.mockk.mockk(), connectorRead = io.mockk.mockk())
        val query = fixture.definition.queries.values.single()
        val started = System.nanoTime()
        val failure = kotlin.test.assertFailsWith<java.lang.reflect.UndeclaredThrowableException> {
            adapter.resolve(semanticPlanningCommand(fixture.workspaceId, query.connectorId, (query as DashboardNamedQuery.Semantic).query, null, null, null, cc.monomer.dashboard.api.DashboardId.generate(), null))
        }
        assertEquals("57014", generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<java.sql.SQLException>().first().sqlState)
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(7))
        repeat(3) {
            val deadlineStarted = System.nanoTime()
            kotlin.test.assertFails {
                adapter.resolve(semanticPlanningCommand(
                    fixture.workspaceId, query.connectorId, (query as DashboardNamedQuery.Semantic).query, null, null, Instant.now().plusMillis(150), cc.monomer.dashboard.api.DashboardId.generate(), null,
                ))
            }
            assertTrue(Duration.ofNanos(System.nanoTime() - deadlineStarted) < Duration.ofSeconds(2))
        }
        dataSource.connection.use { connection -> connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND state='active' AND query LIKE '%pg_sleep%'").use { rows ->
                check(rows.next()); assertEquals(0, rows.getInt(1))
            }
        } }

    }

    private suspend fun fixture(queryCount: Int, quota: Int): Fixture {
        val dataSource = postgres.dataSource
        val workspaceId = WorkspaceId.generate()
        fixtureWorkspaceIds += workspaceId
        val connectorId = ConnectorId.generate()
        val dashboardId = DashboardId.generate()
        val queries = (1..queryCount).joinToString(",") { index ->
            """"q$index":{"type":"SEMANTIC","connectorId":"$connectorId","query":{"schemaVersion":4,"metrics":["metric$index"],"groupBys":[],"filters":[],"orderBy":[],"metricTimeRange":null,"rowLimit":100}}"""
        }
        val queryNames = (1..queryCount).joinToString(",") { "\"q$it\"" }
        val definition = DashboardDefinitionDecoder.decode(Json.parseToJsonElement("""
            {"schemaVersion":1,"meta":{"title":"Schedule"},"parameters":{"at":{"type":"TIMESTAMP","nullable":false}},"variables":{"at":{"type":"TIMESTAMP","nullable":false,"initial":{"kind":"PARAMETER","name":"at"}}},
             "schedules":{"daily":{"cron":"0 0 * * *","timeZone":"UTC","parameters":{"at":{"type":"SCHEDULED_TIME"}}}},
             "queries":{$queries},"widgets":{"table":{"type":"CHART","queryNames":[$queryNames],"module":{"inputs":{"parameters":[],"variables":[]},"source":"export default defineChart({ render() { return {}; } });"}}},
             "layout":{"sections":[{"id":"main","height": { "mode": "SCALED", "scale": 1.0 },"root":{"type":"WIDGET","id":"item","widgetId":"table"}}]}}
        """).jsonObject)
        val dashboards = PostgresDashboardRepository(dataSource)
        val schedules = PostgresDashboardScheduleRepository(dataSource)
        val sessions = PostgresDashboardSessionRepository(dataSource)
        val queryRuns = PostgresQueryRunRepository(dataSource)
        val operations = TransactionTemplate(DataSourceTransactionManager(dataSource)).apply {
            isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
            timeout = 5
        }
        val transaction = SpringDashboardTransactionPort(operations, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        transaction.inTransaction { check(dashboards.insert(Dashboard(dashboardId, workspaceId, definition, NOW, NOW, null, null, null))) }
        val admission = ExecutionRunAdmissionService(PostgresExecutionRuntimeStore(dataSource),
            PostgresRunDispatchPersistence(dataSource, operations),
            ExecutableRequestAssignmentCodec(), ExecutionRunLimits(quota))
        val credentialVersion = Uuid.generateV7()
        val connector = ConnectorExecutionBinding(connectorId, workspaceId, "POSTGRESQL", "fixture", mapOf("host" to "fixture-warehouse"),
            CredentialEnvelopeReference(CredentialVersionId(credentialVersion), "fixture-key", ContentDigest("sha256:${"a".repeat(64)}")), NOW)
        val projection = ConnectorRuntimeBindingSpec(ConnectorReference(connectorId.value), ConnectorType.POSTGRESQL,
            mapOf("host" to ConnectorRuntimePropertyValue.Text("fixture-warehouse")),
            ManagedCredentialDispatchReference(CredentialVersionReference(credentialVersion), "fixture-key", Sha256Digest("a".repeat(64))))
        val adapter = DashboardExecutionAdmissionAdapter(object : ConnectorExecutionAccessInOwnerTransactionPort {
            override fun readCurrentForExecutionInOwnerTransaction(workspaceId: WorkspaceId, connectorId: ConnectorId): ConnectorExecutionAccessResult =
                if (workspaceId == connector.workspaceId && connectorId == connector.connectorId) ConnectorExecutionAccessResult.Granted(connector) else ConnectorExecutionAccessResult.NotFoundOrOutsideWorkspace
        }, ConnectorRuntimeBindingProjectionPort { projection }, ProtoRawSqlRunSpecFactory(), admission, Clock.systemUTC(), testControlRuntimeProperties())
        val semantic = SemanticFixture()
        val service = DashboardSessionService(transaction, dashboards, sessions, queryRuns, PostgresDashboardCommandReceiptRepository(dataSource), semantic, adapter)
        return Fixture(workspaceId, dashboardId, definition, dashboards, schedules, sessions, queryRuns, transaction, semantic, service, adapter)
    }

    private class SemanticFixture : DashboardQueryPlanningPort {
        override suspend fun admitPageReader(
            workspaceId: cc.monomer.identity.api.WorkspaceId, dashboardId: cc.monomer.dashboard.api.DashboardId, pageId: kotlin.uuid.Uuid,
            credential: cc.monomer.identity.api.PresentedCredential?, expectedOAuthResource: cc.monomer.identity.api.ExpectedOAuthResource?,
        ): cc.monomer.dashboard.api.DashboardOwnerOutcome<cc.monomer.identity.api.IdentityCredentialEvidence?> = error("Page reader admission is not configured in this fixture")

        override suspend fun resolveBatch(commands: List<DashboardQueryPlanningPort.ResolveQueryCommand>): List<DashboardQueryResolution> = commands.map { resolve(it) }
        var barrier: CyclicBarrier? = null
        var onResolve: (suspend () -> Unit)? = null
        var calls = 0
        override suspend fun resolve(command: DashboardQueryPlanningPort.ResolveQueryCommand): DashboardQueryResolution {
            assertEquals(null, command.credential)
            calls++
            barrier?.let { withContext(Dispatchers.IO) { it.await(4, TimeUnit.SECONDS) } }
            onResolve?.invoke()
            return DashboardQueryResolution.Resolved(command.resolvedQuery, "SELECT 1", listOf("metric.metric1"), null, null, null, emptyList(), outputColumnDisplays = null, reportingTimeZone = null)
        }
    }

    private data class Fixture(
        val workspaceId: WorkspaceId,
        val dashboardId: DashboardId,
        val definition: cc.monomer.dashboard.domain.DashboardDefinition,
        val dashboards: PostgresDashboardRepository,
        val schedules: PostgresDashboardScheduleRepository,
        val sessions: PostgresDashboardSessionRepository,
        val queryRuns: PostgresQueryRunRepository,
        val transaction: DashboardTransactionPort,
        val semantic: SemanticFixture,
        val service: DashboardSessionService,
        val adapter: DashboardExecutionAdmissionAdapter,
    ) {
        val scheduler = runner()
        fun runner() = DashboardScheduleService(transaction, schedules, sessions, queryRuns, adapter, service)
        fun count(table: String) = EphemeralPostgresAuthoritySupport.count(table, "workspace_id", workspaceId.value.toJavaUuid())
        fun due(at: Instant): Instant {
            val instant = at.truncatedTo(java.time.temporal.ChronoUnit.MICROS)
            postgres.dataSource.connection.use { connection ->
                connection.prepareStatement("UPDATE dashboard.schedule_cursors SET next_scheduled_at = ? WHERE workspace_id = ? AND dashboard_id = ?").use {
                    it.setTimestamp(1, java.sql.Timestamp.from(instant)); it.setObject(2, workspaceId.value.toJavaUuid()); it.setObject(3, dashboardId.value.toJavaUuid()); it.executeUpdate()
                }
            }
            return instant
        }
    }

    companion object {
        private val NOW = Instant.parse("2026-09-06T00:00:00Z")
        @JvmField @RegisterExtension val postgres = EphemeralPostgresAuthoritySupport
        @JvmStatic @BeforeAll fun schemas() { postgres.verifyEphemeralIdentity(); postgres.applyAuthoritySchemasTwice() }
    }
}
