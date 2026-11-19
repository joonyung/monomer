@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.application

import cc.monomer.dashboard.domain.DashboardResolvedQuery
import cc.monomer.dashboard.domain.DashboardNamedQuery

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import cc.monomer.connector.api.ConnectorId
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardSessionId
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.dashboard.domain.Dashboard
import cc.monomer.dashboard.domain.DashboardDraft
import cc.monomer.dashboard.domain.DashboardDefinition
import cc.monomer.dashboard.domain.DashboardListItem
import cc.monomer.dashboard.domain.DashboardSession
import cc.monomer.dashboard.domain.QueryRun
import cc.monomer.dashboard.domain.QueryRunState
import cc.monomer.execution.api.ArrowResultManifest
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val TEST_NOW = Instant.parse("2026-09-06T00:00:00Z")

class DashboardD10ExampleTest {
    @Test
    fun `D10 example declares five fixed grains and only city consumes selected country`() {
        val definition = exampleDefinition()
        val expectedGroups = mapOf(
            "salesByCountry" to """[{"kind":"DIMENSION","semanticModelName":"orders","name":"country"}]""",
            "salesByCity" to """[{"kind":"DIMENSION","semanticModelName":"orders","name":"city"}]""",
            "salesByChannel" to """[{"kind":"DIMENSION","semanticModelName":"orders","name":"channel"}]""",
            "salesByDay" to """[{"kind":"METRIC_TIME","granularity":"DAY"}]""",
            "salesByMonth" to """[{"kind":"METRIC_TIME","granularity":"MONTH"}]""",
        )
        assertEquals(expectedGroups.keys, definition.queries.keys)
        assertEquals(expectedGroups.keys, definition.widgets.getValue("salesChart").queryNames)
        for ((name, query) in definition.queries) {
            assertEquals(Json.parseToJsonElement(expectedGroups.getValue(name)), (query as DashboardNamedQuery.Semantic).query.getValue("groupBys"), name)
            assertEquals(
                Json.parseToJsonElement("""["sales","average_order","customers"]"""),
                (query as DashboardNamedQuery.Semantic).query.getValue("metrics"),
                name,
            )
            val expectedFilters = if (name == "salesByCity") {
                """[{"kind":"TEXT","dimension":{"semanticModelName":"orders","name":"country"},"operator":"EQUALS","value":{"kind":"VARIABLE","name":"selectedCountry"}}]"""
            } else "[]"
            assertEquals(Json.parseToJsonElement(expectedFilters), (query as DashboardNamedQuery.Semantic).query.getValue("filters"), name)
        }
    }

    @Test
    fun `D10 independent complete inputs share five fixed queries and only country changes city execution`() = runTest {
        val workspaceId = WorkspaceId.generate()
        val fixture = Fixture(workspaceId, Dashboard(DashboardId.generate(), workspaceId, exampleDefinition(), TEST_NOW, TEST_NOW,
    updatedBy = null,
    credentialSurface = null,
    agentClientId = null,
))
        val admission = FakeAdmission()
        val semantic = FakeSemantic()
        val service = fixture.service(admission, semantic)
        val opened = assertIs<SessionResult.Found>(service.open(fixture.openCommand("open")))
        val session = opened.session
        val initialTargets = opened.queryRuns.associate { it.queryName to it.id }
        assertEquals(5, admission.commands.size)
        val usValues = session.initialVariables + mapOf("selectedCountry" to JsonPrimitive("US"), "view" to JsonPrimitive("city"))
        val us = assertIs<SessionResolutionResult.Resolved>(service.resolve(resolveCommand(session, usValues, 1)))
        assertNotEquals(initialTargets.getValue("salesByCity"), us.queryRuns.getValue("salesByCity"))
        assertEquals(initialTargets.filterKeys { it != "salesByCity" }, us.queryRuns.filterKeys { it != "salesByCity" })
        assertResolvedCountry(fixture.queryRuns.find(us.queryRuns.getValue("salesByCity"))!!, "US")
        val presentation = usValues + mapOf("analysisAxis" to JsonPrimitive("time"), "timeUnit" to JsonPrimitive("month"))
        assertEquals(us.queryRuns, assertIs<SessionResolutionResult.Resolved>(service.resolve(resolveCommand(session, presentation, 2))).queryRuns)
        assertEquals(initialTargets, assertIs<SessionResolutionResult.Resolved>(service.resolve(resolveCommand(session, session.initialVariables, 3))).queryRuns)
        assertEquals(6, admission.commands.size)
        assertEquals(20, semantic.commands.size)
        val settlement = DashboardQueryRunSettlementService(fixture.queryRuns, fixture.drafts)
        (initialTargets.values + us.queryRuns.values).distinct().forEach { settlement.settleInOwnerTransaction(success(workspaceId, it, 10)) }
        assertEquals(us.queryRuns, assertIs<SessionResolutionResult.Resolved>(service.resolve(resolveCommand(session, presentation, 11))).queryRuns)
        assertEquals(6, admission.commands.size)
        val snapshot = assertIs<SessionResult.Found>(service.get(workspaceId, session.id))
        assertEquals(session, snapshot.session)
        assertTrue(snapshot.queryRuns.isEmpty())
    }

    private fun exampleDefinition(): DashboardDefinition {
        val productRoot = Path.of(requireNotNull(System.getProperty("control.service.root"))).parent
        return DashboardDefinitionDecoder.decode(Files.readString(
            productRoot.resolve("testkit/dashboard-d10/dashboard.json"),
        ))
    }

    private fun resolveCommand(session: DashboardSession, variables: Map<String, JsonElement>, second: Long) = DashboardSessionUseCase.ResolveCommand(
        session.workspaceId, PresentedCredential("test-credential"), null, session.id, session.dashboardId,
        variables, "operator", "d10-resolve-$second", TEST_NOW.plusSeconds(second),
    )

    private fun assertResolvedCountry(run: QueryRun, country: String) {
        assertEquals(Json.parseToJsonElement(
            """[{"kind":"TEXT","dimension":{"semanticModelName":"orders","name":"country"},"operator":"EQUALS","values":["$country"]}]""",
        ), (run.resolvedQuery as DashboardResolvedQuery.Semantic).query.getValue("filters"))
    }

    private fun success(workspaceId: WorkspaceId, queryRunId: QueryRunId, second: Long) =
        DashboardQueryRunSettlementUseCase.Command(
            workspaceId = workspaceId,
            queryRunId = queryRunId,
            state = DashboardQueryRunSettlementUseCase.State.SUCCEEDED,
            result = ArrowResultManifest("artifacts/$queryRunId", 1uL, "etag", 1uL, 1, "application/vnd.apache.arrow.file"),
            failureCode = null,
            failureMessage = null,
            settledAt = TEST_NOW.plusSeconds(second),
        )

    private class Fixture(val workspaceId: WorkspaceId, val dashboard: Dashboard) {
        val sessions = InMemorySessionRepository()
        val queryRuns = InMemoryQueryRunRepository()
        val drafts = UnusedDraftRepository

        fun service(admission: FakeAdmission, semantic: FakeSemantic) = DashboardSessionService(
            transactionPort = ImmediateTransaction,
            dashboardRepository = InMemoryDashboardRepository(dashboard),
            sessionRepository = sessions,
            queryRunRepository = queryRuns,
            commandReceiptRepository = InMemoryReceiptRepository(),
            queryPlanningPort = semantic,
            executionAdmissionPort = admission,
        )

        fun openCommand(key: String) = DashboardSessionUseCase.OpenSessionCommand(
            workspaceId = workspaceId,
            credential = PresentedCredential("test-credential"),
            expectedOAuthResource = null,
            dashboardId = dashboard.id,
            parameterBindings = emptyMap(),
            actorId = "operator",
            idempotencyKey = key,
            openedAt = TEST_NOW,
        )
    }

    private class InMemoryDashboardRepository(private val dashboard: Dashboard) : DashboardRepository {
        override fun readCurrentSemanticReferences(workspaceId: WorkspaceId): List<cc.monomer.dashboard.api.DashboardCurrentSemanticReferences> = listOfNotNull(dashboard.takeIf { it.workspaceId == workspaceId }?.let { dashboardSemanticReferences(it.id, it.definition.document) })
        override fun findForUpdate(workspaceId: WorkspaceId, dashboardId: DashboardId): Dashboard? = find(workspaceId, dashboardId)
        override fun find(workspaceId: WorkspaceId, dashboardId: DashboardId): Dashboard? =
            dashboard.takeIf { it.workspaceId == workspaceId && it.id == dashboardId }
        override fun list(workspaceId: WorkspaceId): List<cc.monomer.dashboard.domain.DashboardListEntry> = error("Not used by D10 fixture")
        override fun findPage(workspaceId: WorkspaceId, cursor: DashboardPageCursor?, limit: Int): List<DashboardListItem> =
            error("Not used by D10 fixture")
        override fun insert(dashboard: Dashboard): Boolean = error("Not used by D10 fixture")
        override fun applyCurrent(workspaceId: WorkspaceId, dashboardId: DashboardId, definition: DashboardDefinition, updatedAt: Instant,
        updatedBy: String?,
        credentialSurface: cc.monomer.identity.api.CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?,): Boolean =
            error("Not used by D10 fixture")
    }

    private class InMemorySessionRepository : DashboardSessionRepository {
        private val values = linkedMapOf<DashboardSessionId, DashboardSession>()
        override fun find(workspaceId: WorkspaceId, sessionId: DashboardSessionId): DashboardSession? =
            values[sessionId]?.takeIf { it.workspaceId == workspaceId }
        override fun find(workspaceId: WorkspaceId, dashboardId: DashboardId, sessionId: DashboardSessionId): DashboardSession? =
            find(workspaceId, sessionId)?.takeIf { it.dashboardId == dashboardId }
        override fun findPage(workspaceId: WorkspaceId, dashboardId: DashboardId, cursor: DashboardSessionPageCursor?, limit: Int): List<DashboardSessionSummary> =
            error("Not used by D10 fixture")
        override fun insert(session: DashboardSession): Boolean = values.putIfAbsent(session.id, session) == null
        override fun findInitial(workspaceId: WorkspaceId, dashboardId: DashboardId, sessionId: DashboardSessionId, deadline: Instant): DashboardSession? = find(workspaceId, dashboardId, sessionId)
        override fun findForUpdate(workspaceId: WorkspaceId, dashboardId: DashboardId, sessionId: DashboardSessionId): DashboardSession? = find(workspaceId, dashboardId, sessionId)
    }

    private class InMemoryQueryRunRepository : QueryRunRepository {
        private val values = linkedMapOf<QueryRunId, QueryRun>()
        fun count(): Int = values.size
        override fun find(queryRunId: QueryRunId): QueryRun? = values[queryRunId]
        override fun find(workspaceId: WorkspaceId, dashboardId: DashboardId, viewerSessionId: DashboardSessionId?, queryRunId: QueryRunId): QueryRun? =
            error("Not used by D10 fixture")
        override fun findEquivalent(
            workspaceId: WorkspaceId,
            dashboardId: DashboardId,
            viewerSessionId: DashboardSessionId?,
            queryName: String,
            connectorId: ConnectorId,
            resolvedQuery: DashboardResolvedQuery,
            renderedSql: String,
            outputColumnNames: List<String>,
            allowedStates: Set<QueryRunState>,
        ): QueryRun? = values.values
            .filter {
                it.workspaceId == workspaceId &&
                    it.dashboardId == dashboardId &&
                    it.viewerSessionId == viewerSessionId &&
                    it.queryName == queryName &&
                    it.connectorId == connectorId &&
                    it.state in allowedStates &&
                    it.resolvedQuery == resolvedQuery &&
                    it.renderedSql == renderedSql && it.outputColumnNames == outputColumnNames
            }
            .maxWithOrNull(compareBy<QueryRun> { it.state == QueryRunState.SUCCEEDED }.thenBy { it.createdAt }.thenBy { it.id.toString() })
        override fun findEquivalentRuns(
            workspaceId: WorkspaceId,
            dashboardId: DashboardId,
            viewerSessionId: DashboardSessionId?,
            lookups: List<EquivalentQueryRunLookup>,
            allowedStates: Set<QueryRunState>,
            deadline: Instant,
        ): List<QueryRun?> = error("Not used by D10 fixture")
        override fun findRunningSessionRuns(workspaceId: WorkspaceId, dashboardId: DashboardId, sessionId: DashboardSessionId): List<QueryRun> =
            values.values.filter { it.workspaceId == workspaceId && it.dashboardId == dashboardId && it.viewerSessionId == sessionId && it.state == QueryRunState.RUNNING }
        override fun findRecentDraftRuns(workspaceId: WorkspaceId, dashboardId: DashboardId, limit: Int): List<QueryRun> =
            error("Not used by D10 fixture")
        override fun findDraftPage(workspaceId: WorkspaceId, dashboardId: DashboardId, cursor: DashboardQueryRunPageCursor?, limit: Int): List<QueryRun> =
            error("Not used by D10 fixture")
        override fun findSessionPage(workspaceId: WorkspaceId, dashboardId: DashboardId, sessionId: DashboardSessionId, cursor: DashboardQueryRunPageCursor?, limit: Int): List<QueryRun> =
            error("Not used by D10 fixture")
        override fun insert(queryRun: QueryRun): Boolean = values.putIfAbsent(queryRun.id, queryRun) == null
        override fun update(expectedUpdatedAt: Instant, queryRun: QueryRun): Boolean {
            val current = values[queryRun.id] ?: return false
            if (current.updatedAt != expectedUpdatedAt) return false
            values[queryRun.id] = queryRun
            return true
        }
    }

    private object UnusedDraftRepository : DashboardDraftRepository {
        override fun findDraft(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardDraft? = error("Not used by D10 fixture")
        override fun findDraftForUpdate(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardDraft? = error("Not used by D10 fixture")
        override fun findDraftStatus(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardDraftStatus? = error("Not used by D10 fixture")
        override fun insert(draft: DashboardDraft): Boolean = error("Not used by D10 fixture")
        override fun update(expectedUpdatedAt: Instant, draft: DashboardDraft): Boolean = error("Not used by D10 fixture")
    }

    private class InMemoryReceiptRepository : DashboardCommandReceiptRepository {
        private val values = linkedMapOf<Triple<WorkspaceId, String?, String>, CommandReceipt>()
        override fun lock(workspaceId: WorkspaceId, actorId: String?, idempotencyKey: String) = Unit
        override fun find(workspaceId: WorkspaceId, actorId: String?, idempotencyKey: String): CommandReceipt? = values[key(workspaceId, actorId, idempotencyKey)]
        override fun insert(receipt: CommandReceipt): Boolean = values.putIfAbsent(key(receipt.workspaceId, receipt.actorId, receipt.idempotencyKey), receipt) == null
        private fun key(workspaceId: WorkspaceId, actorId: String?, idempotencyKey: String) = Triple(workspaceId, actorId, idempotencyKey)
    }

    private class FakeSemantic : DashboardQueryPlanningPort {
        override suspend fun admitPageReader(
            workspaceId: cc.monomer.identity.api.WorkspaceId, dashboardId: cc.monomer.dashboard.api.DashboardId, pageId: kotlin.uuid.Uuid,
            credential: cc.monomer.identity.api.PresentedCredential?, expectedOAuthResource: cc.monomer.identity.api.ExpectedOAuthResource?,
        ): cc.monomer.dashboard.api.DashboardOwnerOutcome<cc.monomer.identity.api.IdentityCredentialEvidence?> = error("Page reader admission is not configured in this fixture")

        override suspend fun resolveBatch(commands: List<DashboardQueryPlanningPort.ResolveQueryCommand>): List<DashboardQueryResolution> = kotlinx.coroutines.coroutineScope {
            val results = mutableListOf<DashboardQueryResolution>()
            for (batch in commands.chunked(4)) results += batch.map { command -> async { resolve(command) } }.awaitAll()
            results
        }

        val commands = mutableListOf<DashboardQueryPlanningPort.ResolveQueryCommand>()
        override suspend fun resolve(command: DashboardQueryPlanningPort.ResolveQueryCommand): DashboardQueryResolution {
            commands += command
            return DashboardQueryResolution.Resolved(
                resolvedQuery = command.resolvedQuery,
                sql = "select 1",
                outputColumns = listOf("value"),
                actorId = null,
                credentialSurface = null,
                agentClientId = null,
                parameterNames = List(command.resolvedQuery.bindings.size) { "" },
                outputColumnDisplays = null,
                reportingTimeZone = if (command.query is cc.monomer.dashboard.domain.DashboardNamedQuery.Semantic) java.time.ZoneId.of("UTC") else null,
            )
        }
    }

    private class FakeAdmission : DashboardExecutionAdmissionPort {
        val commands = mutableListOf<DashboardExecutionAdmissionPort.AdmissionCommand>()
        override fun lockWorkspace(workspaceId: cc.monomer.identity.api.WorkspaceId) = Unit
        override fun hasUnreleasedOccupancy(workspaceId: cc.monomer.identity.api.WorkspaceId, queryRunIds: Set<cc.monomer.dashboard.api.QueryRunId>): Boolean = false
        override fun admit(command: DashboardExecutionAdmissionPort.AdmissionCommand): DashboardExecutionAdmissionPort.AdmissionOutcome {
            commands += command
            return DashboardExecutionAdmissionPort.AdmissionOutcome.Admitted
        }
    }

    private object UnusedCancellation : DashboardExecutionCancellationPort {
        override fun requestCancel(command: DashboardExecutionCancellationPort.CancelCommand): DashboardExecutionCancellationPort.CancellationOutcome =
            error("Not used by D10 fixture")
    }

    private object ImmediateTransaction : DashboardTransactionPort {
        override suspend fun <T> inTransaction(block: () -> T): T = block()
    }
}
