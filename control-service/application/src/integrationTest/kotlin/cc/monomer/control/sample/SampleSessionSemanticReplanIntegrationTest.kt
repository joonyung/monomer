@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.sample

import cc.monomer.catalog.api.*
import cc.monomer.connector.api.*
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.dashboard.DashboardQueryPlanningAdapter
import cc.monomer.dashboard.api.*
import cc.monomer.dashboard.application.*
import cc.monomer.dashboard.domain.*
import cc.monomer.execution.api.ArrowResultManifest
import cc.monomer.identity.api.*
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.semantic.adapter.outbound.metricflow.MetricFlowSemanticRenderer
import cc.monomer.semantic.api.*
import cc.monomer.semantic.application.*
import cc.monomer.semantic.domain.*
import io.mockk.*
import java.io.DataInputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import tools.jackson.databind.ObjectMapper

class SampleSessionSemanticReplanIntegrationTest {
    @Test
    fun `a stored metric filter edit replans v3 Sessions and restoring it reuses their successful runs`() = verifyDataset("acme-cloud@3", "")

    @Test
    fun `the preserved v2 Sessions also replan after a metric filter edit`() = verifyDataset("acme-cloud@2", "versions/2")

    private fun verifyDataset(dataset: String, versionDirectory: String) = runBlocking {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("testkit/data/duckdb/acme-cloud/generate.sql")) }
        val dataRoot = root.resolve("testkit/data/duckdb/acme-cloud")
        val dataFixture = dataRoot.resolve(versionDirectory)
        val driver = root.resolve("execution-service/application/build/install/application/lib/duckdb_jdbc-1.5.4.0.jar")
        assertTrue(Files.isRegularFile(driver), "Run execution-service ./gradlew check before this sample owner integration gate.")
        val temporary = Files.createTempDirectory("sample-session-semantic-replan")
        try {
            val database = temporary.resolve("acme_cloud.duckdb")
            javaFixture(driver, dataRoot.resolve("GenerateDataset.java"), listOf(dataFixture.resolve("generate.sql").toString(), database.toString()), temporary.resolve("generate.out"), temporary.resolve("generate.err"))
            val workspace = WorkspaceId.generate()
            val connector = ConnectorId.generate()
            val now = Instant.now()
            val definitions = SampleDefinitionBundle().read(dataset, connector, LocalDate.now())
            val execution = DuckDbExecution(driver, dataRoot.resolve("ExecuteRenderedMetrics.java"), database, temporary)
            val schema = execution.execute("schema", "SELECT table_schema, table_name, column_name, data_type, ordinal_position, is_nullable FROM information_schema.columns WHERE table_schema = 'acme' ORDER BY table_name, ordinal_position")
            val catalog = catalog(workspace, connector, schema, now)
            val actor = CanonicalPrincipal(PrincipalId.generate(), PrincipalKind.USER)
            val metrics = definitions.metrics.associate { definition -> MetricId.generate().let { it to CurrentMetric(it, workspace, definition, now, now) } }.toMutableMap()
            val models = definitions.models.map { CurrentSemanticModel(SemanticModelId.generate(), workspace, it, now, now) }
            val semanticRepository = mockk<SemanticRepository>(relaxed = true) {
                every { findMetrics(workspace) } answers { metrics.values.toList() }
                every { findModels(workspace) } returns models
                every { findMutationReceipt(workspace, any()) } returns null
                every { updateMetric(any(), actor.id, CredentialSurface.WEB, null) } answers { firstArg<CurrentMetric>().let { metrics[it.id] = it } }
            }
            metrics.keys.forEach { id -> every { semanticRepository.findMetric(id) } answers { metrics[id] } }
            metrics.values.map { it.definition.name }.forEach { name ->
                every { semanticRepository.findMetricByName(workspace, name) } answers { metrics.values.singleOrNull { it.definition.name == name } }
            }
            val spineRepository = mockk<SemanticTimeSpineRepository> {
                every { findTimeSpineConfigurations(workspace) } returns listOf(CurrentTimeSpineConfiguration(workspace, connector, definitions.timeSpine))
            }
            val connectorRead = mockk<ConnectorWorkspaceReadUseCase> {
                coEvery { findByWorkspaceAndId(workspace, connector) } returns CurrentConnectorRead(connector, workspace, ConnectorProductType.DUCKDB, "Acme Cloud", ConnectorSettingsProperties.Duckdb(dataset), now, now)
            }
            val catalogRead = mockk<CatalogCurrentObjectsReadPort> { every { readCurrent(workspace, connector) } returns catalog }
            val zone = mockk<WorkspaceReportingTimeZoneQuery> { coEvery { reportingTimeZone(workspace) } returns ZoneId.of("UTC") }
            val semantic = SemanticQueryService(semanticRepository, spineRepository, SemanticTransaction, MetricFlowSemanticRenderer.production(), connectorRead, catalogRead, zone)
            val admission = mockk<ControlAdmissionFacade> {
                coEvery { authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(mockk {
                    every { this@mockk.actor } returns actor
                    every { credentialKind } returns CredentialKind.SESSION
                    every { credentialSurface } returns CredentialSurface.WEB
                    every { agentClientId } returns null
                })
            }
            val planner = DashboardQueryPlanningAdapter(mockk(), ObjectMapper(), semantic, admission, SnapshotTransaction, mockk(), Dispatchers.Unconfined, mockk(), mockk(), connectorRead)
            val authorization = AuthorizedMutationContext(OrganizationPlan.FREE, actor, OrganizationId.generate(), workspace, AuthorizationRevision(1), CredentialKind.SESSION, CredentialSurface.WEB, null, WorkspaceRole.OWNER, MutationIntent.METRIC_UPDATE, now)
            val authority = SemanticAuthorityService(semanticRepository, SemanticTransaction, Clock.fixed(now.plusSeconds(120), ZoneOffset.UTC), mockk(relaxed = true))
            val active = metrics.values.single { it.definition.name.value == "active_accounts" }
            val original = assertIs<SemanticMetricDefinition.Cumulative>(active.definition)
            val predicate = assertIs<SemanticMetricFilter.Predicate>(original.filters.single())
            assertEquals("active_users", predicate.dimension.name.value)
            val edited = original.copy(filters = listOf(predicate.copy(dimension = predicate.dimension.copy(name = SemanticElementName("core_actions")))))

            // Keep each seeded named query and its input declarations exactly; presentation is irrelevant to owner planning.
            val fixtures = definitions.dashboards.filterValues { definition ->
                definition.getValue("queries").jsonObject.values.any { value -> value.jsonObject["query"]?.jsonObject?.get("metrics")?.jsonArray?.any { it.jsonPrimitive.content == "active_accounts" } == true }
            }.map { (name, definition) ->
                val query = definition.getValue("queries").jsonObject.entries.first { (_, value) ->
                    value.jsonObject["query"]?.jsonObject?.get("metrics")?.jsonArray?.any { it.jsonPrimitive.content == "active_accounts" } == true
                }
                name to SessionFixture(workspace, dashboardProjection(definition, query.key, query.value), planner, now)
            }.toMap()
            assertEquals(if (dataset == "acme-cloud@3") setOf("board-metrics", "product-engagement") else setOf("revenue-overview", "product-usage"), fixtures.keys)
            val opened = fixtures.mapValues { (name, fixture) ->
                val result = assertIs<SessionResult.Found>(fixture.service.open(fixture.open("$name-first-open")))
                assertEquals(1, result.queryRuns.size)
                val run = result.queryRuns.single()
                fixture.succeed(run, execution.execute("$name-before", assertNotNull(run.renderedSql)), now.plusSeconds(1))
                result.session
            }
            val previous = fixtures.mapValues { (_, fixture) -> fixture.runs.values.single() }
            val previousValues = fixtures.mapValues { (_, fixture) -> fixture.results.values.single().weeklyActiveAccounts(fixture.runs.values.single()) }
            val result = assertIs<SemanticMutationResult.Committed<MetricView>>(authority.putMetric(PutMetricCommand(authorization, "change-active-filter", active.id, edited)))
            assertEquals(edited, result.value.definition)

            fixtures.forEach { (name, fixture) ->
                val session = opened.getValue(name)
                val restored = assertIs<SessionResult.Found>(fixture.service.get(workspace, fixture.dashboard.id, session.id)).session
                assertEquals(session, restored)
                val resolution = assertIs<SessionResolutionResult.Resolved>(fixture.service.resolve(fixture.resolve(restored, "$name-bare-reopen", now.plusSeconds(120))))
                val changed = fixture.runs.getValue(resolution.queryRuns.values.single())
                assertEquals(session.id, changed.viewerSessionId)
                val before = previous.getValue(name)
                assertEquals(before.resolvedQuery, changed.resolvedQuery, "The saved Dashboard query intent remains unchanged")
                assertNotEquals(before.id, changed.id)
                assertNotEquals(before.renderedSql, changed.renderedSql)
                assertEquals(before.outputColumnNames, changed.outputColumnNames)
                val changedSql = assertNotNull(changed.renderedSql)
                assertTrue(changedSql.contains("core_actions"))
                val changedResult = execution.execute("$name-after", changedSql)
                val succeeded = fixture.succeed(changed, changedResult, now.plusSeconds(121))
                assertEquals(now.plusSeconds(121), succeeded.updatedAt)
                assertTrue(succeeded.updatedAt > before.updatedAt)
                val after = changedResult.weeklyActiveAccounts(changed)
                val prior = previousValues.getValue(name)
                assertEquals(prior.keys, after.keys)
                assertTrue(prior.isNotEmpty())
                prior.forEach { (week, value) ->
                    val drop = 1.0 - after.getValue(week).toDouble() / value.toDouble()
                    assertTrue(drop in 0.25..0.35, "$name week=$week drop=$drop before=$value after=${after.getValue(week)}")
                }
                assertEquals(1, fixture.sessions.size)
                assertEquals(2, fixture.admitted.size)
                println("sample_session_replan dashboard=$name session=${session.id} before_run=${before.id} after_run=${changed.id} weeks=${prior.size} result_at=${succeeded.updatedAt}")
            }
            assertIs<SemanticMutationResult.Committed<MetricView>>(authority.putMetric(PutMetricCommand(authorization, "restore-active-filter", active.id, original)))
            fixtures.forEach { (name, fixture) ->
                val session = opened.getValue(name)
                val restored = assertIs<SessionResolutionResult.Resolved>(fixture.service.resolve(fixture.resolve(session, "$name-restored-filter", now.plusSeconds(240))))
                assertEquals(previous.getValue(name).id, restored.queryRuns.values.single())
                assertEquals(session.id, fixture.runs.getValue(restored.queryRuns.values.single()).viewerSessionId)
                assertEquals(QueryRunState.SUCCEEDED, fixture.runs.getValue(restored.queryRuns.values.single()).state)
                assertEquals(2, fixture.admitted.size, "Restoring the definition reuses the original successful run without another execution")
                assertEquals(2, fixture.runs.size)
            }
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    private fun dashboardProjection(definition: JsonObject, name: String, query: JsonElement): DashboardDefinition = DashboardDefinitionDecoder.decode(JsonObject(definition + mapOf(
        "queries" to JsonObject(mapOf(name to query)),
        "widgets" to buildJsonObject {
            put("active", buildJsonObject {
                put("type", "TABLE"); put("queryName", name)
                put("columns", buildJsonArray { add(buildJsonObject { put("field", "metric.active_accounts") }) })
            })
        },
        "layout" to Json.parseToJsonElement("""{"sections":[{"id":"main","height":{"mode":"SCALED","scale":1.0},"root":{"type":"WIDGET","id":"active","widgetId":"active"}}]}"""),
    )))

    private class SessionFixture(private val workspace: WorkspaceId, definition: DashboardDefinition, planner: DashboardQueryPlanningPort, private val now: Instant) {
        val dashboard = Dashboard(DashboardId.generate(), workspace, definition, now, now, null, null, null)
        val sessions = mutableMapOf<DashboardSessionId, DashboardSession>()
        val runs = mutableMapOf<QueryRunId, QueryRun>()
        val results = mutableMapOf<QueryRunId, Result>()
        val admitted = mutableListOf<DashboardExecutionAdmissionPort.AdmissionCommand>()
        private val receipts = mutableMapOf<String, CommandReceipt>()
        private val dashboards = mockk<DashboardRepository> {
            every { find(workspace, dashboard.id) } returns dashboard
            every { findForUpdate(workspace, dashboard.id) } returns dashboard
        }
        private val sessionRepository = object : DashboardSessionRepository {
            override fun insert(session: DashboardSession): Boolean = sessions.putIfAbsent(session.id, session) == null
            override fun find(workspaceId: WorkspaceId, sessionId: DashboardSessionId): DashboardSession? = sessions[sessionId]?.takeIf { it.workspaceId == workspaceId }
            override fun find(workspaceId: WorkspaceId, dashboardId: DashboardId, sessionId: DashboardSessionId): DashboardSession? = find(workspaceId, sessionId)?.takeIf { it.dashboardId == dashboardId }
            override fun findForUpdate(workspaceId: WorkspaceId, dashboardId: DashboardId, sessionId: DashboardSessionId): DashboardSession? = find(workspaceId, dashboardId, sessionId)
            override fun findInitial(workspaceId: WorkspaceId, dashboardId: DashboardId, sessionId: DashboardSessionId, deadline: Instant): DashboardSession? = error("Unused initial-result reader")
            override fun findPage(workspaceId: WorkspaceId, dashboardId: DashboardId, cursor: DashboardSessionPageCursor?, limit: Int): List<DashboardSessionSummary> = error("Unused History list")
        }
        private val queryRuns = object : QueryRunRepository {
            override fun insert(queryRun: QueryRun): Boolean = runs.putIfAbsent(queryRun.id, queryRun) == null
            override fun update(expectedUpdatedAt: Instant, queryRun: QueryRun): Boolean {
                if (runs[queryRun.id]?.updatedAt != expectedUpdatedAt) return false
                runs[queryRun.id] = queryRun
                return true
            }
            override fun find(queryRunId: QueryRunId): QueryRun? = runs[queryRunId]
            override fun find(workspaceId: WorkspaceId, dashboardId: DashboardId, viewerSessionId: DashboardSessionId?, queryRunId: QueryRunId): QueryRun? = runs[queryRunId]?.takeIf { it.workspaceId == workspaceId && it.dashboardId == dashboardId && it.viewerSessionId == viewerSessionId }
            override fun findRunningSessionRuns(workspaceId: WorkspaceId, dashboardId: DashboardId, sessionId: DashboardSessionId): List<QueryRun> = runs.values.filter { it.workspaceId == workspaceId && it.dashboardId == dashboardId && it.viewerSessionId == sessionId && it.state == QueryRunState.RUNNING }
            override fun findEquivalent(workspaceId: WorkspaceId, dashboardId: DashboardId, viewerSessionId: DashboardSessionId?, queryName: String, connectorId: ConnectorId, resolvedQuery: DashboardResolvedQuery, renderedSql: String, outputColumnNames: List<String>, allowedStates: Set<QueryRunState>): QueryRun? = runs.values.filter { it.workspaceId == workspaceId && it.dashboardId == dashboardId && it.viewerSessionId == viewerSessionId && it.queryName == queryName && it.connectorId == connectorId && it.resolvedQuery == resolvedQuery && it.renderedSql == renderedSql && it.outputColumnNames == outputColumnNames && it.state in allowedStates }
                .maxWithOrNull(compareBy<QueryRun> { it.state == QueryRunState.SUCCEEDED }.thenBy { it.createdAt }.thenBy { it.id.toString() })
            override fun findEquivalentRuns(workspaceId: WorkspaceId, dashboardId: DashboardId, viewerSessionId: DashboardSessionId?, lookups: List<EquivalentQueryRunLookup>, allowedStates: Set<QueryRunState>, deadline: Instant): List<QueryRun?> = error("Unused initial-result reader")
            override fun findRecentDraftRuns(workspaceId: WorkspaceId, dashboardId: DashboardId, limit: Int): List<QueryRun> = error("Unused Draft reader")
            override fun findDraftPage(workspaceId: WorkspaceId, dashboardId: DashboardId, cursor: DashboardQueryRunPageCursor?, limit: Int): List<QueryRun> = error("Unused Draft reader")
            override fun findSessionPage(workspaceId: WorkspaceId, dashboardId: DashboardId, sessionId: DashboardSessionId, cursor: DashboardQueryRunPageCursor?, limit: Int): List<QueryRun> = error("Unused History reader")
        }
        private val receiptRepository = object : DashboardCommandReceiptRepository {
            override fun lock(workspaceId: WorkspaceId, actorId: String?, idempotencyKey: String) = Unit
            override fun find(workspaceId: WorkspaceId, actorId: String?, idempotencyKey: String): CommandReceipt? = receipts[idempotencyKey]?.takeIf { it.workspaceId == workspaceId && it.actorId == actorId }
            override fun insert(receipt: CommandReceipt): Boolean = receipts.putIfAbsent(receipt.idempotencyKey, receipt) == null
        }
        private val executionAdmission = object : DashboardExecutionAdmissionPort {
            override fun lockWorkspace(workspaceId: WorkspaceId) = Unit
            override fun hasUnreleasedOccupancy(workspaceId: WorkspaceId, queryRunIds: Set<QueryRunId>): Boolean = false
            override fun admit(command: DashboardExecutionAdmissionPort.AdmissionCommand): DashboardExecutionAdmissionPort.AdmissionOutcome {
                admitted += command
                return DashboardExecutionAdmissionPort.AdmissionOutcome.Admitted
            }
        }
        val service = DashboardSessionService(DashboardTransaction, dashboards, sessionRepository, queryRuns, receiptRepository, planner, executionAdmission)
        fun open(key: String) = DashboardSessionUseCase.OpenSessionCommand(workspace, PresentedCredential("local-test-only"), null, dashboard.id, emptyMap(), "sample-test", key, now)
        fun resolve(session: DashboardSession, key: String, at: Instant) = DashboardSessionUseCase.ResolveCommand(session.workspaceId, PresentedCredential("local-test-only"), null, session.id, session.dashboardId, session.initialVariables, "sample-test", key, at)
        fun succeed(run: QueryRun, result: Result, at: Instant): QueryRun {
            val succeeded = run.succeed(ArrowResultManifest("sample-test/${run.id}", 1uL, "test-only", result.rows.size.toULong(), result.columns.size, "application/vnd.apache.arrow.file"), at)
            runs[run.id] = succeeded
            results[run.id] = result
            return succeeded
        }
    }

    private fun catalog(workspace: WorkspaceId, connector: ConnectorId, schema: Result, at: Instant): CatalogCurrentObjectSet {
        var sequence = 1
        fun item(kind: CatalogObjectKind, parent: CatalogObjectId?, address: CatalogPhysicalAddress, type: String?, ordinal: Int?): CatalogCurrentObject {
            val value = sequence++
            val digest = CatalogDigest("sha256:${value.toString().padStart(64, '0')}")
            return CatalogCurrentObject(
                id = CatalogObjectId(Uuid.parse("0197d83f-2c9e-8a3b-8c1d-${value.toString().padStart(12, '0')}")),
                identityDigest = digest, parentId = parent, kind = kind,
                relationKind = if (kind == CatalogObjectKind.RELATION) RelationKind.TABLE else null,
                address = address, qualifiedName = listOfNotNull(address.schemaName, address.objectName, address.memberName).joinToString("."),
                nativeType = type, contentDigest = digest, definition = null, ordinal = ordinal,
                nullable = if (kind == CatalogObjectKind.COLUMN) true else null,
                defaultExpression = null, relationshipKind = null, memberOrdinal = null, constraintName = null,
                sourceColumn = null, referencedCatalog = null, referencedSchema = null, referencedRelation = null,
                referencedColumn = null, observedAt = at, partitionKeys = null, clusteringKeys = null, requiresPartitionFilter = null,
            )
        }
        val schemaItem = item(CatalogObjectKind.SCHEMA, null, CatalogPhysicalAddress(null, "acme", null, null), null, null)
        val items = mutableListOf(schemaItem)
        schema.rows.groupBy { requireNotNull(it[1]) }.forEach { (table, columns) ->
            val relation = item(CatalogObjectKind.RELATION, schemaItem.id, CatalogPhysicalAddress(null, "acme", table, null), null, null)
            items += relation
            columns.forEach { column -> items += item(CatalogObjectKind.COLUMN, relation.id, relation.address.copy(memberName = requireNotNull(column[2])), requireNotNull(column[3]), requireNotNull(column[4]).toInt()) }
        }
        return CatalogCurrentObjectSet(workspace, connector, items)
    }

    private class DuckDbExecution(private val driver: Path, private val source: Path, private val database: Path, private val temporary: Path) {
        fun execute(name: String, sql: String): Result {
            val directory = temporary.resolve(name)
            Files.createDirectories(directory)
            Files.writeString(directory.resolve("$name.sql"), sql)
            val output = directory.resolve("result.bin")
            javaFixture(driver, source, listOf(database.toString(), directory.toString()), output, directory.resolve("execute.err"))
            return DataInputStream(Files.newInputStream(output)).use { input ->
                assertEquals(1, input.readInt())
                assertEquals(name, input.readUTF())
                val columns = List(input.readInt()) { input.readUTF() }
                val rows = List(input.readInt()) { List(columns.size) { if (input.readBoolean()) input.readUTF() else null } }
                assertEquals(-1, input.read())
                Result(columns, rows)
            }
        }
    }

    private data class Result(val columns: List<String>, val rows: List<List<String?>>) {
        fun weeklyActiveAccounts(run: QueryRun): Map<String, BigDecimal> {
            val time = run.outputColumnNames.indexOfFirst { it.startsWith("metric_time.") }
            val active = run.outputColumnNames.indexOf("metric.active_accounts")
            assertTrue(time >= 0 && active >= 0, run.outputColumnNames.toString())
            return rows.groupBy { requireNotNull(it[time]) }.mapValues { (_, values) -> values.sumOf { requireNotNull(it[active]).toBigDecimal() } }
        }
    }

    private object DashboardTransaction : DashboardTransactionPort { override suspend fun <T> inTransaction(block: () -> T): T = block() }
    private object SnapshotTransaction : ConsistentSnapshotTransactionRunner { override suspend fun <T> run(action: suspend () -> T): T = action() }
    private object SemanticTransaction : SemanticTransactionPort {
        override suspend fun <T> inSnapshotTransaction(deadline: Instant, block: suspend () -> T): T = block()
        override suspend fun <T> inSnapshotTransaction(block: () -> T): T = block()
        override suspend fun <T> inMutationTransaction(block: () -> T): T = block()
    }

    companion object {
        private fun javaFixture(driver: Path, source: Path, arguments: List<String>, output: Path, error: Path) {
            val process = ProcessBuilder(listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "--enable-native-access=ALL-UNNAMED", "-cp", driver.toString(), source.toString()) + arguments)
                .redirectOutput(output.toFile()).redirectError(error.toFile()).start()
            try {
                assertTrue(process.waitFor(60, TimeUnit.SECONDS), "DuckDB fixture process exceeded its deadline")
                assertEquals(0, process.exitValue(), Files.readString(error))
            } finally {
                if (process.isAlive) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS), "DuckDB process cleanup failed") }
            }
        }
    }
}
