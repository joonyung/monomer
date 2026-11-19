@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

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
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.WorkspaceId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.assertIs
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private val TEST_NOW = Instant.parse("2026-01-01T00:00:00Z")

class DashboardSessionServiceTest {
    private val json = Json { explicitNulls = true }
    private val now = TEST_NOW

    @Test
    fun `display edits do not change reuse equality and reused run retains its captured display`() = runTest {
        val fixture = fixture()
        val oldDisplay = cc.monomer.semantic.api.MetricDisplay.Percent(2)
        val semantic = FakeSemantic().apply {
            outputColumnDisplays = listOf(cc.monomer.semantic.api.SemanticOutputColumnDisplay(cc.monomer.semantic.api.SemanticResultColumnKey("metric.value"), oldDisplay))
        }
        val admission = FakeAdmission()
        val service = fixture.service(semantic = semantic, admission = admission)
        val opened = service.open(fixture.openCommand("display-open")) as SessionResult.Found
        assertTrue(opened.queryRuns.all { it.outputColumnDisplays?.single()?.display == oldDisplay })
        semantic.outputColumnDisplays = listOf(cc.monomer.semantic.api.SemanticOutputColumnDisplay(cc.monomer.semantic.api.SemanticResultColumnKey("metric.value"), cc.monomer.semantic.api.MetricDisplay.Number(0, null)))
        val resolved = service.resolve(resolveCommand(opened.session, opened.session.initialVariables, "display-resolve")) as SessionResolutionResult.Resolved
        assertEquals(opened.queryRuns.associate { it.queryName to it.id }, resolved.queryRuns)
        assertEquals(2, admission.commands.size)
        opened.queryRuns.forEach { original ->
            val lookup = service.getQueryRun(fixture.workspaceId, fixture.dashboard.id, opened.session.id, original.id) as QueryRunLookupResult.Found
            assertEquals(oldDisplay, lookup.queryRun.outputColumnDisplays?.single()?.display)
        }
    }

    @Test
    fun `a Reporting time zone change with the same SQL reuses the run and keeps its recorded zone`() = runTest {
        val fixture = fixture()
        val seoul = java.time.ZoneId.of("Asia/Seoul")
        val semantic = FakeSemantic().apply { reportingTimeZone = seoul }
        val admission = FakeAdmission()
        val service = fixture.service(semantic = semantic, admission = admission)
        val opened = service.open(fixture.openCommand("zone-open")) as SessionResult.Found
        assertEquals(listOf(seoul, seoul), opened.queryRuns.map { it.reportingTimeZone })
        semantic.reportingTimeZone = java.time.ZoneId.of("UTC")
        val resolved = service.resolve(resolveCommand(opened.session, opened.session.initialVariables, "zone-resolve")) as SessionResolutionResult.Resolved
        assertEquals(opened.queryRuns.associate { it.queryName to it.id }, resolved.queryRuns)
        assertEquals(2, admission.commands.size)
        opened.queryRuns.forEach { original ->
            val lookup = service.getQueryRun(fixture.workspaceId, fixture.dashboard.id, opened.session.id, original.id) as QueryRunLookupResult.Found
            assertEquals(seoul, lookup.queryRun.reportingTimeZone)
        }
    }

    @Test
    fun `deleted Dashboard rejects member receipt replays while public resolve is not found`() = runTest {
        val fixture = fixture()
        val service = fixture.service()
        val open = fixture.openCommand("open")
        val opened = service.open(open) as SessionResult.Found
        val resolve = resolveCommand(opened.session, opened.session.initialVariables, "resolve")
        val public = publicCommand(opened.session, "public-resolve")
        assertIs<SessionResolutionResult.Resolved>(service.resolve(resolve))
        assertIs<SessionResolutionResult.Resolved>(service.resolvePublic(public))
        fixture.dashboardRepository.remove()
        fixture.sessions.removeAll()
        assertEquals("IDEMPOTENCY_CONFLICT", assertIs<SessionResult.Rejected>(service.open(open)).code)
        assertEquals("IDEMPOTENCY_CONFLICT", assertIs<SessionResolutionResult.Rejected>(service.resolve(resolve)).code)
        assertEquals(SessionResolutionResult.NotFound, service.resolvePublic(public))
    }

    @Test
    fun `Session open rechecks deletion after admission and creates no Session or receipt`() = runTest {
        val fixture = fixture()
        val admission = object : DashboardExecutionAdmissionPort by FakeAdmission() {
            override fun lockWorkspace(workspaceId: WorkspaceId) { fixture.dashboardRepository.remove() }
        }
        val result = fixture.service(admission = admission).open(fixture.openCommand("open"))
        assertEquals(SessionResult.NotFound, result)
        assertEquals(0, fixture.sessions.count())
        assertEquals(null, fixture.receipts.find(fixture.workspaceId, "operator", "open"))
    }

    @Test
    fun `Session open rejects an Apply between planning and transaction without writes`() = runTest {
        for (definitionChanges in listOf(false, true)) {
            val fixture = fixture()
            val semantic = FakeSemantic()
            val admission = FakeAdmission()
            val racedAdmission = object : DashboardExecutionAdmissionPort by admission {
                override fun lockWorkspace(workspaceId: WorkspaceId) {
                    val definition = if (definitionChanges) fixture(table = true).dashboard.definition else fixture.dashboard.definition
                    fixture.dashboardRepository.applyCurrent(workspaceId, fixture.dashboard.id, definition, now.plusSeconds(1), null, null, null)
                }
            }
            val result = fixture.service(semantic = semantic, admission = racedAdmission).open(fixture.openCommand("raced-open"))
            assertEquals("SESSION_CONFLICT", assertIs<SessionResult.Rejected>(result).code)
            assertEquals(2, semantic.commands.size)
            assertTrue(admission.commands.isEmpty())
            assertTrue(fixture.writeOrder.isEmpty())
            assertEquals(null, fixture.receipts.find(fixture.workspaceId, "operator", "raced-open"))
        }
    }

    @Test
    fun `member open resolve and refresh receipts return fixed snapshots without planning or admission`() = runTest {
        val fixture = fixture()
        val semantic = FakeSemantic()
        val admission = FakeAdmission()
        val service = fixture.service(semantic = semantic, admission = admission)
        val open = fixture.openCommand("replay-open")
        val opened = assertIs<SessionResult.Found>(service.open(open))
        val resolve = resolveCommand(opened.session, opened.session.initialVariables, "replay-resolve")
        val resolved = service.resolve(resolve)
        val refresh = refreshCommand(opened.session, opened.session.initialVariables, "replay-refresh")
        val refreshed = service.refresh(refresh)
        val planned = semantic.commands.size
        val admitted = admission.commands.size
        semantic.invalidAt = planned + 1
        fixture.dashboardRepository.applyCurrent(fixture.workspaceId, fixture.dashboard.id, fixture().dashboard.definition, now.plusSeconds(1), null, null, null)
        assertEquals(opened, service.open(open))
        assertEquals(resolved, service.resolve(resolve))
        assertEquals(refreshed, service.refresh(refresh))
        assertEquals("IDEMPOTENCY_CONFLICT", assertIs<SessionResult.Rejected>(service.open(open.copy(parameterBindings = mapOf("unknown" to JsonNull)))).code)
        assertEquals("IDEMPOTENCY_CONFLICT", assertIs<SessionResolutionResult.Rejected>(service.resolve(resolve.copy(variables = emptyMap()))).code)
        assertEquals("IDEMPOTENCY_CONFLICT", assertIs<SessionResolutionResult.Rejected>(service.refresh(refresh.copy(queryNames = setOf("unknown")))).code)
        assertEquals(planned, semantic.commands.size)
        assertEquals(admitted, admission.commands.size)
    }

    @Test
    fun `Session page cursor binds the Dashboard and supports stable pagination`() = runTest {
        val fixture = fixture()
        val service = fixture.service()
        repeat(3) { service.open(fixture.openCommand("page-$it")) }
        val first = assertIs<DashboardSessionPageResult.Found>(service.list(fixture.workspaceId, fixture.dashboard.id, null, 1)).page
        val cursor = requireNotNull(first.nextCursor)
        assertEquals(fixture.dashboard.id, cursor.dashboardId)
        val second = assertIs<DashboardSessionPageResult.Found>(service.list(fixture.workspaceId, fixture.dashboard.id, cursor, 1)).page
        assertNotEquals(first.items.single().id, second.items.single().id)
        assertFailsWith<IllegalArgumentException> {
            service.list(fixture.workspaceId, fixture.dashboard.id, cursor.copy(dashboardId = DashboardId.generate()), 1)
        }
    }

    @Test
    fun `SQL session admits ordered binds and records type independently of current definition`() = runTest {
        val original = fixture()
        val definition = DashboardDefinitionDecoder.decode(sqlDashboardDefinition(sql = "SELECT {{a}} AS value, {{a}} AS other", bindings = """{"a":{"kind":"VARIABLE","name":"a"}}"""))
        val fixture = Fixture(original.workspaceId, original.dashboard.copy(definition = definition))
        val semantic = FakeSemantic()
        val admission = FakeAdmission()
        val service = fixture.service(semantic = semantic, admission = admission)
        val opened = service.open(fixture.openCommand("sql-open")) as SessionResult.Found
        val run = opened.queryRuns.single()
        assertEquals(cc.monomer.dashboard.domain.DashboardQueryType.SQL, run.queryType)
        assertEquals(listOf("value", "other"), admission.commands.single().expectedColumnNames)
        assertTrue(admission.commands.single().validateRawSqlBeforeRun)
        val injection = "x' OR '1'='1"
        val changed = service.resolve(resolveCommand(opened.session, mapOf("a" to JsonPrimitive(injection), "b" to JsonPrimitive("second")), "sql-resolve")) as SessionResolutionResult.Resolved
        assertEquals("SELECT ? AS value, ? AS other", admission.commands.last().sql)
        assertEquals(listOf(cc.monomer.dashboard.domain.DashboardBindValue.Text(injection), cc.monomer.dashboard.domain.DashboardBindValue.Text(injection)), admission.commands.last().bindings)
        fixture.dashboardRepository.applyCurrent(fixture.workspaceId, fixture.dashboard.id, original.dashboard.definition, now.plusSeconds(1), null, null, null)
        val historical = service.getQueryRun(fixture.workspaceId, fixture.dashboard.id, opened.session.id, run.id) as QueryRunLookupResult.Found
        assertEquals(cc.monomer.dashboard.domain.DashboardQueryType.SQL, historical.queryRun.queryType)
        assertEquals(changed.queryRuns, (service.resolve(resolveCommand(opened.session, mapOf("a" to JsonPrimitive(injection), "b" to JsonPrimitive("second")), "sql-repeat")) as SessionResolutionResult.Resolved).queryRuns)
        assertEquals(2, admission.commands.size)
    }

    @Test
    fun `SQL Session passes named plan bindings into admission and reuses identical rendered SQL`() = runTest {
        val original = fixture()
        val definition = DashboardDefinitionDecoder.decode(sqlDashboardDefinition(sql = "SELECT {{a}} AS value, {{a}} AS other", bindings = """{"a":{"kind":"VARIABLE","name":"a"}}"""))
        val fixture = Fixture(original.workspaceId, original.dashboard.copy(definition = definition))
        val semantic = FakeSemantic().apply { namedSql = true }
        val admission = FakeAdmission()
        val service = fixture.service(semantic = semantic, admission = admission)
        val opened = service.open(fixture.openCommand("named-open")) as SessionResult.Found
        assertEquals("SELECT  :monomer_p1  AS value,  :monomer_p2  AS other", opened.queryRuns.single().renderedSql)
        assertEquals(listOf("monomer_p1", "monomer_p2"), admission.commands.single().parameterNames)
        val next = service.resolve(resolveCommand(opened.session, mapOf("a" to JsonPrimitive("changed"), "b" to JsonPrimitive("second")), "named-change")) as SessionResolutionResult.Resolved
        assertEquals(listOf("monomer_p1", "monomer_p2"), admission.commands.last().parameterNames)
        assertEquals(2, admission.commands.size)
        val repeated = service.resolve(resolveCommand(opened.session, mapOf("a" to JsonPrimitive("changed"), "b" to JsonPrimitive("second")), "named-repeat")) as SessionResolutionResult.Resolved
        assertEquals(next.queryRuns, repeated.queryRuns)
        assertEquals(2, admission.commands.size)
    }

    @Test
    fun `invalid SQL timestamp bindings reject the entire session resolution before writes`() = runTest {
        val original = fixture()
        val definition = DashboardDefinitionDecoder.decode(sqlDashboardDefinition(sql = "SELECT {{a}} AS value", bindings = """{"a":{"kind":"VARIABLE","name":"a"}}""", variables = """{"a":{"type":"TIMESTAMP","nullable":false,"initial":"2026-10-06T00:00:00Z"}}""", columns = """["value"]"""))
        val fixture = Fixture(original.workspaceId, original.dashboard.copy(definition = definition))
        val admission = FakeAdmission()
        val service = fixture.service(admission = admission)
        val opened = service.open(fixture.openCommand("sql-open")) as SessionResult.Found
        val rejected = service.resolve(resolveCommand(opened.session, mapOf("a" to JsonPrimitive("2026-10-06T00:00:00.123456789Z")), "sql-invalid")) as SessionResolutionResult.Rejected
        assertEquals("INVALID_QUERY_BINDING", rejected.code)
        assertEquals(1, admission.commands.size)
        assertEquals(1, fixture.queryRuns.count())
    }

    @Test
    fun `public receipt replays before planning and separates the member namespace`() = runTest {
        val fixture = rangeEndpointFixture(startOnly = false)
        val semantic = FakeSemantic()
        val admission = FakeAdmission()
        val service = fixture.service(semantic = semantic, admission = admission)
        val opened = service.open(fixture.openCommand("public-open")) as SessionResult.Found
        val command = publicCommand(opened.session, "same-key")
        val first = service.resolvePublic(command) as SessionResolutionResult.Resolved
        val planned = semantic.commands.size
        semantic.invalidAt = planned + 1
        assertEquals(first, service.resolvePublic(command))
        assertEquals(planned, semantic.commands.size)
        val conflicting = command.copy(variables = mapOf("period" to Json.parseToJsonElement("""{"start":"2026-02-01","end":"2026-02-28"}""")))
        assertEquals("IDEMPOTENCY_CONFLICT", (service.resolvePublic(conflicting) as SessionResolutionResult.Rejected).code)
        assertEquals(planned, semantic.commands.size)
        semantic.invalidAt = null
        val member = service.resolve(resolveCommand(opened.session, opened.session.initialVariables, "same-key").copy(actorId = "null"))
        assertEquals(first, member)
        assertEquals(planned + 2, semantic.commands.size)
        assertEquals(2, admission.commands.size)
        assertTrue(semantic.commands.drop(2).take(2).all { it.credential == null && it.expectedOAuthResource == null })
    }

    @Test
    fun `public final receipt recheck observes request accepted during planning`() = runTest {
        val fixture = rangeEndpointFixture(startOnly = false)
        val semantic = FakeSemantic()
        val admission = FakeAdmission()
        val service = fixture.service(semantic = semantic, admission = admission)
        val opened = service.open(fixture.openCommand("race-open")) as SessionResult.Found
        val command = publicCommand(opened.session, "race-key")
        var competing: SessionResolutionResult? = null
        semantic.beforeResolve = { competing = service.resolvePublic(command) }
        assertEquals(service.resolvePublic(command), competing)
        assertEquals(2, admission.commands.size)
        assertEquals(2, fixture.queryRuns.count())
    }

    @Test
    fun `initial query verification overlaps planning without writes or admissions`() = runTest {
        val baseline = rangeEndpointFixture(startOnly = false)
        val document = baseline.dashboard.definition.document
        val queries = document.getValue("queries").jsonObject
        val expandedQueries = JsonObject(queries + (0 until 5).associate { "extra$it" to queries.getValue("stableQuery") })
        val widgets = document.getValue("widgets").jsonObject
        val chart = widgets.getValue("chart").jsonObject
        val expanded = JsonObject(document + mapOf(
            "queries" to expandedQueries,
            "widgets" to JsonObject(widgets + ("chart" to JsonObject(chart + ("queryNames" to kotlinx.serialization.json.JsonArray(expandedQueries.keys.map(::JsonPrimitive)))))),
        ))
        val fixture = Fixture(baseline.workspaceId, baseline.dashboard.copy(definition = DashboardDefinitionDecoder.decode(expanded)))
        val semantic = FakeSemantic()
        val admission = FakeAdmission()
        val service = fixture.service(semantic = semantic, admission = admission)
        val opened = service.open(fixture.openCommand("parallel-open")) as SessionResult.Found
        val writes = fixture.writeOrder.toList()
        val admissions = admission.commands.size
        var active = 0
        var peak = 0
        semantic.inspect = {
            active++
            peak = maxOf(peak, active)
            try { kotlinx.coroutines.delay(100) } finally { active-- }
        }
        val start = testScheduler.currentTime
        val result = service.readInitialResults(cc.monomer.dashboard.api.ReadInitialDashboardSessionResultsCommand(
            fixture.workspaceId, opened.session.dashboardId, opened.session.id, 64, Instant.now().plusSeconds(10),
        )) as InitialSessionResultsResult.Found
        assertEquals(7, result.queriesChecked)
        assertEquals(4, peak)
        assertEquals(200, testScheduler.currentTime - start)
        assertEquals(writes, fixture.writeOrder)
        assertEquals(admissions, admission.commands.size)
    }

    @Test
    fun `cancelled initial read cancels every active plan without admitting work`() = runTest {
        val fixture = rangeEndpointFixture(startOnly = false)
        val semantic = FakeSemantic()
        val admission = FakeAdmission()
        val service = fixture.service(semantic = semantic, admission = admission)
        val opened = service.open(fixture.openCommand("cancel-open")) as SessionResult.Found
        val writes = fixture.writeOrder.toList()
        val admissions = admission.commands.size
        var cancelled = 0
        semantic.inspect = {
            try { kotlinx.coroutines.awaitCancellation() } finally { cancelled++ }
        }
        kotlin.test.assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            kotlinx.coroutines.withTimeout(100) {
                service.readInitialResults(cc.monomer.dashboard.api.ReadInitialDashboardSessionResultsCommand(
                    fixture.workspaceId, opened.session.dashboardId, opened.session.id, 64, Instant.now().plusSeconds(10),
                ))
            }
        }
        assertEquals(2, cancelled)
        assertEquals(writes, fixture.writeOrder)
        assertEquals(admissions, admission.commands.size)
    }

    @Test
    fun `initial candidates read only latest equivalent successes and consume supplied budget`() = runTest {
        val fixture = rangeEndpointFixture(startOnly = false)
        val semantic = FakeSemantic()
        val admission = FakeAdmission()
        val service = fixture.service(semantic = semantic, admission = admission)
        val opened = service.open(fixture.openCommand("initial-open")) as SessionResult.Found
        val settlement = DashboardQueryRunSettlementService(fixture.queryRuns, fixture.drafts)
        opened.queryRuns.forEach { settlement.settleInOwnerTransaction(success(fixture.workspaceId, it.id)) }
        val refresh = service.refresh(refreshCommand(opened.session, opened.session.initialVariables, "newest").copy(queryNames = opened.session.definition.queries.keys)) as SessionResolutionResult.Resolved
        refresh.queryRuns.values.forEach { settlement.settleInOwnerTransaction(success(fixture.workspaceId, it).copy(settledAt = now.plusSeconds(12))) }
        val beforeWrites = fixture.writeOrder.toList()
        val beforeAdmissions = admission.commands.size
        val deadline = Instant.now().plusSeconds(10)
        val command = cc.monomer.dashboard.api.ReadInitialDashboardSessionResultsCommand(fixture.workspaceId, opened.session.dashboardId, opened.session.id, 64, deadline)
        val initial = service.readInitialResults(command) as InitialSessionResultsResult.Found
        assertEquals(1, fixture.queryRuns.equivalentRunReads)
        assertEquals(opened.session.definition.queries.keys, initial.requiredQueryNames)
        assertEquals(2, initial.queriesChecked)
        assertTrue(!initial.budgetExhausted)
        assertEquals(refresh.queryRuns, initial.queryRuns.mapValues { it.value.id })
        assertEquals(beforeWrites, fixture.writeOrder)
        assertTrue(semantic.commands.takeLast(2).all { it.deadline == deadline && it.credential == null })
        val bounded = service.readInitialResults(command.copy(remainingQueryCount = 1)) as InitialSessionResultsResult.Found
        assertEquals(1, bounded.queriesChecked)
        assertEquals(1, bounded.queryRuns.size)
        assertTrue(bounded.budgetExhausted)
        semantic.sql = "select changed_semantic_definition"
        val changed = service.readInitialResults(command) as InitialSessionResultsResult.Found
        assertTrue(changed.queryRuns.isEmpty())
        assertEquals(3, fixture.queryRuns.equivalentRunReads)
        semantic.sql = "select 1"
        fixture.queryRuns.equivalentRunReadTimesOut = true
        val timedOut = service.readInitialResults(command) as InitialSessionResultsResult.Found
        assertTrue(timedOut.queryRuns.isEmpty())
        assertTrue(timedOut.budgetExhausted)
        assertEquals(2, timedOut.queriesChecked)
        assertEquals(beforeWrites, fixture.writeOrder)
        assertEquals(beforeAdmissions, admission.commands.size)
    }

    @Test
    fun `historical successful Run remains readable after current Semantic definitions change or disappear`() = runTest {
        val fixture = rangeEndpointFixture(startOnly = false)
        val semantic = FakeSemantic()
        val service = fixture.service(semantic = semantic)
        val opened = service.open(fixture.openCommand("historical-open")) as SessionResult.Found
        val run = opened.queryRuns.first()
        DashboardQueryRunSettlementService(fixture.queryRuns, fixture.drafts).settleInOwnerTransaction(success(fixture.workspaceId, run.id))
        val plansBeforeRead = semantic.commands.size
        semantic.sql = "select changed_definition"
        semantic.invalidAt = plansBeforeRead + 1
        val historical = service.getQueryRun(fixture.workspaceId, opened.session.dashboardId, opened.session.id, run.id) as QueryRunLookupResult.Found
        assertEquals(QueryRunState.SUCCEEDED, historical.queryRun.state)
        assertEquals(run.renderedSql, historical.queryRun.renderedSql)
        assertEquals(plansBeforeRead, semantic.commands.size)
    }

    @Test
    fun `authenticated Page resolve preserves credential and run actor with actor scoped receipts`() = runTest {
        val fixture = rangeEndpointFixture(startOnly = false)
        val semantic = FakeSemantic()
        val service = fixture.service(semantic = semantic)
        val opened = service.open(fixture.openCommand("reader-open")) as SessionResult.Found
        val credential = PresentedCredential("authenticated-page-reader")
        semantic.sql = "select new_input"
        semantic.actorId = "page-reader"
        semantic.credentialSurface = cc.monomer.identity.api.CredentialSurface.AGENT
        val resolved = service.resolvePublic(publicCommand(opened.session, "reader-key").copy(
            credential = credential, expectedOAuthResource = cc.monomer.identity.api.ExpectedOAuthResource.AGENT_REST, actorId = "page-reader",
        )) as SessionResolutionResult.Resolved
        resolved.queryRuns.values.forEach {
            val run = fixture.queryRuns.find(it)!!
            assertEquals("page-reader", run.actorId)
            assertEquals(cc.monomer.identity.api.CredentialSurface.AGENT, run.credentialSurface)
        }
        assertTrue(semantic.commands.takeLast(2).all { it.credential == credential && it.expectedOAuthResource == cc.monomer.identity.api.ExpectedOAuthResource.AGENT_REST })
        assertEquals("RESOLVE_SESSION", fixture.receipts.find(fixture.workspaceId, "page-reader", "reader-key")!!.commandKind)
        assertEquals(null, fixture.receipts.find(fixture.workspaceId, null, "reader-key"))
    }

    @Test
    fun `only public resolve receipt admits a null actor`() = runTest {
        val fixture = rangeEndpointFixture(startOnly = false)
        val service = fixture.service()
        val opened = service.open(fixture.openCommand("receipt-open")) as SessionResult.Found
        service.resolvePublic(publicCommand(opened.session, "receipt-key"))
        val receipt = fixture.receipts.find(fixture.workspaceId, null, "receipt-key")!!
        kotlin.test.assertFailsWith<IllegalArgumentException> { receipt.copy(commandKind = "RESOLVE_SESSION") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { receipt.copy(actorId = "member") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { receipt.copy(actorId = " ", commandKind = "RESOLVE_SESSION") }
    }

    private fun publicCommand(session: DashboardSession, key: String) = cc.monomer.dashboard.api.ResolvePublicDashboardSessionCommand(
        session.workspaceId, session.dashboardId, session.id, session.initialVariables, key, now.plusSeconds(10), null, null, null, kotlin.uuid.Uuid.generateV7(),
    )

    @Test
    fun `range INPUT resolves both DATE filters from complete variables without changing immutable session`() = runTest {
        val fixture = rangeEndpointFixture(startOnly = false)
        val semantic = FakeSemantic()
        val admission = FakeAdmission()
        val service = fixture.service(semantic = semantic, admission = admission)
        assertEquals("period", fixture.dashboard.definition.widgets.getValue("periodInput").variableName)
        val opened = service.open(fixture.openCommand("range-open")) as SessionResult.Found
        val initialRuns = opened.queryRuns.associate { it.queryName to it.id }
        val initialRun = fixture.queryRuns.find(initialRuns.getValue("rangeQuery"))!!
        assertDateEndpointFilters((initialRun.resolvedQuery as DashboardResolvedQuery.Semantic).query, "2026-01-01", "2026-01-31")
        assertEquals(2, semantic.commands.size)
        assertEquals(2, admission.commands.size)

        val updatedPeriod = Json.parseToJsonElement("""{"start":"2026-02-01","end":"2026-02-28"}""")
        val command = resolveCommand(opened.session, mapOf("period" to updatedPeriod), "range-update")
        val partialRange = Json.parseToJsonElement("""{"start":"2026-02-01"}""")
        kotlin.test.assertIs<SessionResolutionResult.Rejected>(service.resolve(command.copy(variables = mapOf("period" to partialRange))))
        assertEquals(opened.session, fixture.sessions.find(fixture.workspaceId, opened.session.id))
        assertEquals(2, semantic.commands.size)
        assertEquals(2, admission.commands.size)

        val changed = service.resolve(command) as SessionResolutionResult.Resolved
        assertEquals(mapOf("period" to updatedPeriod), changed.variables)
        assertNotEquals(initialRun.id, changed.queryRuns.getValue("rangeQuery"))
        assertEquals(initialRuns.getValue("stableQuery"), changed.queryRuns.getValue("stableQuery"))
        assertEquals(4, semantic.commands.size)
        assertEquals(3, admission.commands.size)
        val changedRun = fixture.queryRuns.find(changed.queryRuns.getValue("rangeQuery"))!!
        assertDateEndpointFilters((changedRun.resolvedQuery as DashboardResolvedQuery.Semantic).query, "2026-02-01", "2026-02-28")
        assertEquals(changedRun.resolvedQuery, semantic.commands[2].resolvedQuery)
        assertEquals(opened.session, fixture.sessions.find(fixture.workspaceId, opened.session.id))

        assertEquals(changed, service.resolve(command))
        assertEquals(changed, service.resolve(command.copy(idempotencyKey = "range-no-op", requestedAt = now.plusSeconds(12))))
        val initialAgain = service.resolve(resolveCommand(opened.session, opened.session.initialVariables, "range-initial-again")) as SessionResolutionResult.Resolved
        assertEquals(initialRuns, initialAgain.queryRuns)
        assertEquals(opened.session, fixture.sessions.find(fixture.workspaceId, opened.session.id))
        assertEquals(8, semantic.commands.size)
        assertEquals(3, admission.commands.size)
        assertEquals(3, fixture.queryRuns.count())
    }

    @Test
    fun `changing only range end reuses successful start-only query with current graph and no admission`() = runTest {
        val fixture = rangeEndpointFixture(startOnly = true)
        val semantic = FakeSemantic()
        val admission = FakeAdmission()
        val service = fixture.service(semantic = semantic, admission = admission)
        val opened = service.open(fixture.openCommand("start-only-open")) as SessionResult.Found
        val initialRuns = opened.queryRuns.associate { it.queryName to it.id }
        val initialRunId = initialRuns.getValue("rangeQuery")
        val settlement = DashboardQueryRunSettlementService(fixture.queryRuns, fixture.drafts)
        assertEquals(DashboardQueryRunSettlementUseCase.Outcome.Applied, settlement.settleInOwnerTransaction(success(fixture.workspaceId, initialRunId)))
        val succeededRun = fixture.queryRuns.find(initialRunId)!!
        assertEquals(QueryRunState.SUCCEEDED, succeededRun.state)
        assertDateEndpointFilters((succeededRun.resolvedQuery as DashboardResolvedQuery.Semantic).query, "2026-01-01")

        val updatedPeriod = Json.parseToJsonElement("""{"start":"2026-01-01","end":"2026-02-28"}""")
        val changed = service.resolve(
            resolveCommand(opened.session, mapOf("period" to updatedPeriod), "end-only-update"),
        ) as SessionResolutionResult.Resolved

        assertEquals(updatedPeriod, changed.variables.getValue("period"))
        assertEquals(opened.session, fixture.sessions.find(fixture.workspaceId, opened.session.id))
        assertEquals(initialRuns, changed.queryRuns)
        assertEquals(succeededRun, fixture.queryRuns.find(initialRunId))
        assertEquals(4, semantic.commands.size)
        assertEquals(succeededRun.resolvedQuery, semantic.commands[2].resolvedQuery)
        assertEquals(2, admission.commands.size)
        assertEquals(2, fixture.queryRuns.count())
    }

    @Test
    fun `session open and restored get preserve values outside controls in resolved queries`() = runTest {
        for (case in controlUiOnlyCases) {
            val baseline = fixture()
            val definition = controlUiOnlyDefinition(case)
            val fixture = Fixture(baseline.workspaceId, baseline.dashboard.copy(definition = definition))
            val service = fixture.service()
            val value = Json.parseToJsonElement(case.value)
            val opened = kotlin.test.assertIs<SessionResult.Found>(
                service.open(fixture.openCommand("ui-only-${case.type}").copy(parameterBindings = mapOf("p" to value))),
            )
            assertEquals(value, opened.session.parameterBindings.getValue("p"), case.type)
            assertEquals(value, opened.session.initialVariables.getValue("v"), case.type)
            for (runId in opened.queryRuns.map { it.id }) {
                assertControlUiOnlyQueryValue((fixture.queryRuns.find(runId)!!.resolvedQuery as DashboardResolvedQuery.Semantic).query, value, case.type)
            }
            val restored = kotlin.test.assertIs<SessionResult.Found>(
                fixture.service().get(fixture.workspaceId, opened.session.id),
            )
            assertEquals(opened.session, restored.session)
        }
    }

    @Test
    fun `session open still rejects missing unknown null and malformed parameter bindings`() = runTest {
        val baseline = fixture()
        val fixture = Fixture(baseline.workspaceId, baseline.dashboard.copy(definition = controlUiOnlyDefinition(controlUiOnlyCases[1])))
        val invalidBindings = listOf(
            emptyMap(), mapOf("unknown" to JsonPrimitive("150")),
            mapOf("p" to JsonPrimitive("150"), "unknown" to JsonPrimitive("150")),
            mapOf("p" to kotlinx.serialization.json.JsonNull), mapOf("p" to JsonPrimitive(150)),
        )
        for ((index, bindings) in invalidBindings.withIndex()) {
            kotlin.test.assertIs<SessionResult.Rejected>(
                fixture.service().open(fixture.openCommand("invalid-$index").copy(parameterBindings = bindings)),
            )
        }
        assertEquals(0, fixture.sessions.count())
        assertEquals(0, fixture.queryRuns.count())
    }

    @Test
    fun `independent complete variables share equal running plans without changing immutable session`() = runTest {
        val fixture = fixture()
        val admission = FakeAdmission()
        val semantic = FakeSemantic()
        val service = fixture.service(admission = admission, semantic = semantic)
        val opened = service.open(fixture.openCommand("open")) as SessionResult.Found
        val initial = opened.queryRuns.associate { it.queryName to it.id }
        val variables = opened.session.initialVariables
        val same = service.resolve(resolveCommand(opened.session, variables, "same")) as SessionResolutionResult.Resolved
        assertEquals(initial, same.queryRuns)
        val eu = service.resolve(resolveCommand(opened.session, variables + ("region" to JsonPrimitive("EU")), "eu")) as SessionResolutionResult.Resolved
        assertNotEquals(initial.getValue("regionQuery"), eu.queryRuns.getValue("regionQuery"))
        assertEquals(initial.getValue("stableQuery"), eu.queryRuns.getValue("stableQuery"))
        val usAgain = service.resolve(resolveCommand(opened.session, variables, "us-again")) as SessionResolutionResult.Resolved
        assertEquals(initial, usAgain.queryRuns)
        assertEquals(3, admission.commands.size)
        assertEquals(8, semantic.commands.size)
        val snapshot = service.get(fixture.workspaceId, opened.session.id) as SessionResult.Found
        assertEquals(opened.session, snapshot.session)
        assertEquals(3, snapshot.queryRuns.size)
        assertTrue(snapshot.queryRuns.all { it.state == QueryRunState.RUNNING })
    }

    @Test
    fun `success precedes running and refresh joins running while leaving other success reusable`() = runTest {
        val fixture = fixture()
        val admission = FakeAdmission()
        val service = fixture.service(admission = admission)
        val opened = service.open(fixture.openCommand("open")) as SessionResult.Found
        val settlement = DashboardQueryRunSettlementService(fixture.queryRuns, fixture.drafts)
        opened.queryRuns.forEach { settlement.settleInOwnerTransaction(success(fixture.workspaceId, it.id)) }
        val initial = opened.queryRuns.associate { it.queryName to it.id }
        val variables = opened.session.initialVariables
        val refresh = service.refresh(refreshCommand(opened.session, variables, "refresh")) as SessionResolutionResult.Resolved
        assertNotEquals(initial.getValue("regionQuery"), refresh.queryRuns.getValue("regionQuery"))
        assertEquals(initial.getValue("stableQuery"), refresh.queryRuns.getValue("stableQuery"))
        val joined = service.refresh(refreshCommand(opened.session, variables, "refresh-2")) as SessionResolutionResult.Resolved
        assertEquals(refresh.queryRuns, joined.queryRuns)
        val ordinary = service.resolve(resolveCommand(opened.session, variables, "ordinary")) as SessionResolutionResult.Resolved
        assertEquals(initial, ordinary.queryRuns)
        assertEquals(3, admission.commands.size)
        assertEquals(1, (service.get(fixture.workspaceId, opened.session.id) as SessionResult.Found).queryRuns.size)
    }

    @Test
    fun `refresh retries only selected queries and preserves equivalent unrelated terminal runs`() = runTest {
        for (terminalState in listOf(QueryRunState.FAILED, QueryRunState.CANCELLED)) {
            val fixture = fixture()
            val admission = FakeAdmission()
            val service = fixture.service(admission = admission)
            val opened = service.open(fixture.openCommand("open")) as SessionResult.Found
            opened.queryRuns.forEach { run ->
                val terminal = if (terminalState == QueryRunState.FAILED) run.fail("TEST_FAILURE", null, now.plusSeconds(1))
                    else run.cancel(now.plusSeconds(1))
                assertTrue(fixture.queryRuns.update(run.updatedAt, terminal))
            }
            val initial = opened.queryRuns.associate { it.queryName to it.id }
            val command = refreshCommand(opened.session, opened.session.initialVariables, "retry")
            val refreshed = service.refresh(command) as SessionResolutionResult.Resolved

            assertEquals(initial.keys, refreshed.queryRuns.keys)
            assertEquals(opened.session.initialVariables, refreshed.variables)
            assertNotEquals(initial.getValue("regionQuery"), refreshed.queryRuns.getValue("regionQuery"))
            assertEquals(initial.getValue("stableQuery"), refreshed.queryRuns.getValue("stableQuery"))
            assertEquals(terminalState, fixture.queryRuns.find(refreshed.queryRuns.getValue("stableQuery"))!!.state)
            assertEquals(3, admission.commands.size)
            assertEquals(refreshed, service.refresh(command))
            assertEquals(3, admission.commands.size)
        }
    }

    @Test
    fun `refresh prefers unrelated success or running over a newer equivalent terminal run`() = runTest {
        for (reusableState in listOf(QueryRunState.SUCCEEDED, QueryRunState.RUNNING)) {
            val fixture = fixture()
            val admission = FakeAdmission()
            val service = fixture.service(admission = admission)
            val opened = service.open(fixture.openCommand("open")) as SessionResult.Found
            val initial = opened.queryRuns.associate { it.queryName to it.id }
            if (reusableState == QueryRunState.SUCCEEDED) {
                DashboardQueryRunSettlementService(fixture.queryRuns, fixture.drafts)
                    .settleInOwnerTransaction(success(fixture.workspaceId, initial.getValue("stableQuery")))
            }
            val reusable = fixture.queryRuns.find(initial.getValue("stableQuery"))!!
            val newer = reusable.copy(id = QueryRunId.generate(), createdAt = now.plusSeconds(20), updatedAt = now.plusSeconds(20))
            fixture.queryRuns.insert(newer.copy(state = QueryRunState.RUNNING, result = null).fail("TEST_FAILURE", null, now.plusSeconds(21)))

            val refreshed = service.refresh(refreshCommand(opened.session, opened.session.initialVariables, "retry")) as SessionResolutionResult.Resolved

            assertEquals(initial, refreshed.queryRuns)
            assertEquals(2, admission.commands.size)
        }
    }

    @Test
    fun `refresh rejects missing unrelated equivalent before admitting selected queries or receipt`() = runTest {
        for (changedCondition in listOf("sql", "columns", "variables", "missing-sql")) {
            val fixture = fixture()
            val admission = FakeAdmission()
            val semantic = FakeSemantic()
            val service = fixture.service(admission = admission, semantic = semantic)
            val opened = service.open(fixture.openCommand("open")) as SessionResult.Found
            val initial = opened.queryRuns.associate { it.queryName to it.id }
            opened.queryRuns.forEach { run -> fixture.queryRuns.update(run.updatedAt, run.fail("TEST_FAILURE", null, now.plusSeconds(1))) }
            var command = refreshCommand(opened.session, opened.session.initialVariables, "retry")
            when (changedCondition) {
                "sql" -> semantic.sql = "select changed_definition"
                "columns" -> semantic.columns = listOf("changed")
                "variables" -> command = command.copy(queryNames = setOf("stableQuery"), variables = command.variables + ("region" to JsonPrimitive("EU")))
                "missing-sql" -> {
                    val unrelated = fixture.queryRuns.find(initial.getValue("stableQuery"))!!
                    fixture.queryRuns.update(unrelated.updatedAt, unrelated.copy(renderedSql = null))
                }
            }
            val writes = fixture.writeOrder.toList()

            val rejected = kotlin.test.assertIs<SessionResolutionResult.Rejected>(service.refresh(command))

            assertEquals("REFRESH_QUERY_RUN_UNAVAILABLE", rejected.code, changedCondition)
            assertEquals(2, admission.commands.size, changedCondition)
            assertEquals(2, fixture.queryRuns.count(), changedCondition)
            assertEquals(writes, fixture.writeOrder, changedCondition)
            assertEquals(null, fixture.receipts.find(fixture.workspaceId, "operator", "retry"), changedCondition)
            service.resolve(resolveCommand(opened.session, command.variables, "resolve")) as SessionResolutionResult.Resolved
            assertEquals(4, admission.commands.size, changedCondition)
        }
    }

    @Test
    fun `current SQL output columns and graph are required for reuse`() = runTest {
        val fixture = fixture()
        val admission = FakeAdmission()
        val semantic = FakeSemantic()
        val service = fixture.service(admission = admission, semantic = semantic)
        val opened = service.open(fixture.openCommand("open")) as SessionResult.Found
        semantic.sql = "select 2"
        service.resolve(resolveCommand(opened.session, opened.session.initialVariables, "sql")) as SessionResolutionResult.Resolved
        assertEquals(4, admission.commands.size)
        semantic.columns = listOf("changed")
        service.resolve(resolveCommand(opened.session, opened.session.initialVariables, "columns")) as SessionResolutionResult.Resolved
        assertEquals(6, admission.commands.size)
        val unknownSql = fixture.queryRuns.find(opened.queryRuns.first().id)!!
        fixture.queryRuns.update(unknownSql.updatedAt, unknownSql.copy(renderedSql = null))
        semantic.sql = "select 1"
        semantic.columns = listOf("value")
        service.resolve(resolveCommand(opened.session, opened.session.initialVariables, "unknown-sql")) as SessionResolutionResult.Resolved
        assertEquals(7, admission.commands.size)
    }

    @Test
    fun `limited reader admits every valid query for open resolve and refresh without data access planning`() = runTest {
        val fixture = fixture()
        val admission = FakeAdmission()
        val semantic = FakeSemantic()
        val service = fixture.service(admission = admission, semantic = semantic)
        val limitedReader = PresentedCredential("limited-reader")

        val opened = service.open(fixture.openCommand("limited-open").copy(credential = limitedReader)) as SessionResult.Found
        val openedRuns = opened.queryRuns.associate { it.queryName to it.id }
        assertEquals(setOf("regionQuery", "stableQuery"), openedRuns.keys)
        assertEquals(2, admission.commands.size)
        assertEquals(2, semantic.commands.size)
        assertTrue(semantic.commands.all { it.credential == limitedReader })

        val changedVariables = opened.session.initialVariables + ("region" to JsonPrimitive("EU"))
        val resolved = service.resolve(
            resolveCommand(opened.session, changedVariables, "limited-resolve").copy(credential = limitedReader),
        ) as SessionResolutionResult.Resolved
        assertNotEquals(openedRuns.getValue("regionQuery"), resolved.queryRuns.getValue("regionQuery"))
        assertEquals(openedRuns.getValue("stableQuery"), resolved.queryRuns.getValue("stableQuery"))
        assertEquals(3, admission.commands.size)

        val settlement = DashboardQueryRunSettlementService(fixture.queryRuns, fixture.drafts)
        assertEquals(
            DashboardQueryRunSettlementUseCase.Outcome.Applied,
            settlement.settleInOwnerTransaction(
                success(fixture.workspaceId, resolved.queryRuns.getValue("regionQuery")).copy(settledAt = now.plusSeconds(12)),
            ),
        )
        val refreshed = service.refresh(
            refreshCommand(opened.session, changedVariables, "limited-refresh").copy(
                credential = limitedReader,
                requestedAt = now.plusSeconds(13),
            ),
        ) as SessionResolutionResult.Resolved
        assertNotEquals(resolved.queryRuns.getValue("regionQuery"), refreshed.queryRuns.getValue("regionQuery"))
        assertEquals(resolved.queryRuns.getValue("stableQuery"), refreshed.queryRuns.getValue("stableQuery"))
        assertEquals(4, admission.commands.size)
        assertEquals(6, semantic.commands.size)
        assertTrue(semantic.commands.all { it.credential == limitedReader })
    }

    @Test
    fun `new requests validate all plans before admission and receipts skip changed plans`() = runTest {
        val fixture = fixture()
        val admission = FakeAdmission()
        val semantic = FakeSemantic()
        val service = fixture.service(admission = admission, semantic = semantic)
        val opened = service.open(fixture.openCommand("open")) as SessionResult.Found
        val command = resolveCommand(opened.session, opened.session.initialVariables + ("region" to JsonPrimitive("EU")), "agent-resolve")
            .copy(expectedOAuthResource = ExpectedOAuthResource.AGENT_REST)
        semantic.invalidAt = semantic.commands.size + 2
        kotlin.test.assertIs<SessionResolutionResult.Rejected>(service.resolve(command))
        assertEquals(2, admission.commands.size)
        semantic.invalidAt = null
        val accepted = service.resolve(command)
        assertEquals(3, admission.commands.size)
        assertEquals(accepted, service.resolve(command))
        val planned = semantic.commands.size
        semantic.invalidAt = planned + 1
        assertEquals(accepted, service.resolve(command))
        assertEquals(planned, semantic.commands.size)
        assertEquals(3, admission.commands.size)
        assertTrue(semantic.commands.drop(2).all { it.expectedOAuthResource == ExpectedOAuthResource.AGENT_REST })
    }

    @Test
    fun `complete variable validation and session scope reject before planning`() = runTest {
        val fixture = fixture()
        val semantic = FakeSemantic()
        val service = fixture.service(semantic = semantic)
        val opened = service.open(fixture.openCommand("open")) as SessionResult.Found
        val invalid = listOf(emptyMap(), opened.session.initialVariables + ("unknown" to JsonPrimitive("x")), opened.session.initialVariables + ("region" to JsonNull))
        invalid.forEachIndexed { index, values ->
            kotlin.test.assertIs<SessionResolutionResult.Rejected>(service.resolve(resolveCommand(opened.session, values, "invalid-$index")))
        }
        val valid = resolveCommand(opened.session, opened.session.initialVariables, "scope")
        assertEquals(SessionResolutionResult.NotFound, service.resolve(valid.copy(dashboardId = DashboardId.generate())))
        assertEquals(SessionResolutionResult.NotFound, service.resolve(valid.copy(workspaceId = WorkspaceId.generate())))
        assertEquals(2, semantic.commands.size)
        kotlin.test.assertIs<SessionResolutionResult.Rejected>(service.refresh(refreshCommand(opened.session, opened.session.initialVariables, "unknown").copy(queryNames = setOf("unknown"))))
        assertEquals(2, semantic.commands.size)
    }

    @Test
    fun `failed and cancelled runs are not retried by GET and explicit resolve can replace them`() = runTest {
        for (terminalState in listOf(QueryRunState.FAILED, QueryRunState.CANCELLED)) {
            val fixture = fixture()
            val admission = FakeAdmission()
            val service = fixture.service(admission = admission)
            val opened = service.open(fixture.openCommand("open")) as SessionResult.Found
            val run = opened.queryRuns.first()
            val terminal = if (terminalState == QueryRunState.FAILED) run.fail("TEST_FAILURE", null, now.plusSeconds(1))
                else run.cancel(now.plusSeconds(1))
            fixture.queryRuns.update(run.updatedAt, terminal)
            repeat(3) { service.get(fixture.workspaceId, opened.session.id) }
            assertEquals(2, admission.commands.size)
            val resolved = service.resolve(resolveCommand(opened.session, opened.session.initialVariables, "retry")) as SessionResolutionResult.Resolved
            assertNotEquals(run.id, resolved.queryRuns.getValue(run.queryName))
            assertEquals(3, admission.commands.size)
        }
    }

    @Test
    fun `new sessions never reuse another session runs and history lookups remain scoped`() = runTest {
        val fixture = fixture()
        val admission = FakeAdmission()
        val service = fixture.service(admission = admission)
        val first = service.open(fixture.openCommand("first")) as SessionResult.Found
        val second = service.open(fixture.openCommand("second")) as SessionResult.Found
        assertEquals(4, admission.commands.size)
        assertEquals(QueryRunLookupResult.NotFound, service.getQueryRun(fixture.workspaceId, fixture.dashboard.id, second.session.id, first.queryRuns.first().id))
        kotlin.test.assertIs<QueryRunLookupResult.Found>(service.getQueryRun(fixture.workspaceId, fixture.dashboard.id, first.session.id, first.queryRuns.first().id))
    }

    private fun resolveCommand(session: DashboardSession, variables: Map<String, JsonElement>, key: String) = DashboardSessionUseCase.ResolveCommand(
        session.workspaceId, PresentedCredential("test-credential"), null, session.id, session.dashboardId,
        variables, "operator", key, now.plusSeconds(11),
    )

    private fun refreshCommand(session: DashboardSession, variables: Map<String, JsonElement>, key: String) = DashboardSessionUseCase.RefreshCommand(
        session.workspaceId, PresentedCredential("test-credential"), null, session.dashboardId, session.id, setOf("regionQuery"),
        variables, "operator", key, now.plusSeconds(11),
    )

    private fun success(workspaceId: WorkspaceId, queryRunId: QueryRunId): DashboardQueryRunSettlementUseCase.Command =
        DashboardQueryRunSettlementUseCase.Command(
            workspaceId = workspaceId,
            queryRunId = queryRunId,
            state = DashboardQueryRunSettlementUseCase.State.SUCCEEDED,
            result = ArrowResultManifest("artifacts/$queryRunId", 1uL, "etag", 1uL, 1, "application/vnd.apache.arrow.file"),
            failureCode = null,
            failureMessage = null,
            settledAt = now.plusSeconds(10),
        )

    private fun assertDateEndpointFilters(query: JsonObject, vararg endpoints: String) {
        assertEquals(JsonNull, query.getValue("metricTimeRange"))
        val expectedFilters = endpoints.mapIndexed { index, endpoint ->
            val operator = if (index == 0) "GREATER_THAN_OR_EQUAL" else "LESS_THAN_OR_EQUAL"
            Json.parseToJsonElement("""{"kind":"DATE","dimension":{"semanticModelName":"orders","name":"order_date"},"operator":"$operator","values":["$endpoint"]}""")
        }
        assertEquals(expectedFilters, query.getValue("filters").jsonArray.toList())
    }


    private fun rangeEndpointFixture(startOnly: Boolean): Fixture {
        val baseline = fixture()
        val endFilter = if (startOnly) "" else """,
            {"kind":"DATE","dimension":{"semanticModelName":"orders","name":"order_date"},"operator":"LESS_THAN_OR_EQUAL","value":{"kind":"VARIABLE","name":"period","endpoint":"end"}}
        """.trimIndent()
        val stableQuery = baseline.dashboard.definition.document.getValue("queries").jsonObject.getValue("stableQuery")
        val definition = DashboardDefinitionDecoder.decode(Json.parseToJsonElement("""
            {
              "schemaVersion":1,"meta":{"title":"Orders in selected period"},"parameters":{},
              "variables":{"period":{"type":"DATE_RANGE","nullable":false,"initial":{"start":"2026-01-01","end":"2026-01-31"}}},
              "queries":{
                "rangeQuery":{"type": "SEMANTIC", "connectorId":"0195f3b2-5c84-7a44-8cc4-3e9bf4fd10de","query":{
                  "schemaVersion":4,"metrics":["order_total"],"groupBys":[],"metricTimeRange":null,
                  "filters":[{"kind":"DATE","dimension":{"semanticModelName":"orders","name":"order_date"},"operator":"GREATER_THAN_OR_EQUAL","value":{"kind":"VARIABLE","name":"period","endpoint":"start"}}$endFilter],
                  "orderBy":[],"rowLimit":100
                }},
                "stableQuery":$stableQuery
              },
              "widgets":{
                "chart":{"type":"CHART","queryNames":["rangeQuery","stableQuery"],"module":{
                  "inputs":{"parameters":[],"variables":[]},"source":"export default defineChart({ render() { return {}; } });"
                }},
                "periodInput":{"type":"INPUT","variableName":"period"}
              },
              "layout":{"sections":[
                {"id":"main","height": { "mode": "SCALED", "scale": 1.0 },"root":{"type":"WIDGET","id":"chartItem","widgetId":"chart"}},
                {"id":"controls","height": { "mode": "CONTENT" },"root":{"type":"WIDGET","id":"periodItem","widgetId":"periodInput"}}
              ]}
            }
        """).jsonObject)
        return Fixture(baseline.workspaceId, baseline.dashboard.copy(definition = definition))
    }

    private fun fixture(table: Boolean = false): Fixture {
        val workspaceId = WorkspaceId.generate()
        val dashboardId = DashboardId.generate()
        val definition = DashboardDefinitionDecoder.decode(
            json.parseToJsonElement(
                """
                {
                  "schemaVersion": 1,
                  "meta": { "title": "Orders" },
                  "parameters": {},
                  "variables": { "region": { "type": "TEXT", "nullable": false, "initial": "US" }, "segment": { "type": "TEXT", "nullable": true, "initial": "retail" }, "limit": { "type": "NUMBER", "nullable": false, "initial": "10" } },
                  "queries": {
                    "regionQuery": { "type": "SEMANTIC", "connectorId": "0195f3b2-5c84-7a44-8cc4-3e9bf4fd10de", "query": {
                      "schemaVersion": 4, "metrics": ["order_total"], "groupBys": [], "metricTimeRange": null,
                      "filters": [{ "kind": "TEXT", "dimension": { "semanticModelName": "orders", "name": "region" }, "operator": "EQUALS", "value": { "kind": "VARIABLE", "name": "region" } }, { "kind": "TEXT", "dimension": { "semanticModelName": "orders", "name": "segment" }, "operator": "EQUALS", "value": { "kind": "VARIABLE", "name": "segment" }, "emptyBehavior": "OMIT" }],
                      "orderBy": [], "rowLimit": 100
                    } },
                    "stableQuery": { "type": "SEMANTIC", "connectorId": "0195f3b2-5c84-7a44-8cc4-3e9bf4fd10de", "query": {
                      "schemaVersion": 4, "metrics": ["order_count"], "groupBys": [], "metricTimeRange": null,
                      "filters": [], "orderBy": [], "rowLimit": 100
                    } }
                  },
                  "widgets": { "chart": ${if (table) """{
                    "type": "TABLE", "queryName": "stableQuery", "columns": [{"field":"metric.order_count"}],
                    "module": {"inputs":{"queries":["regionQuery"],"parameters":[],"variables":["region","limit"]},
                    "source":"export default defineTable({ render() { return {}; } });"}
                  }""" else """{ "type": "CHART", "queryNames": ["regionQuery", "stableQuery"], "module": {
                    "inputs": { "parameters": [], "variables": ["region"] },
                    "source": "export default defineChart({ render() { return {}; } });"
                  } }"""} },
                  "layout": { "sections": [{ "id": "main", "height": { "mode": "SCALED", "scale": 1.0 }, "root": {
                    "type": "WIDGET", "id": "chartItem", "widgetId": "chart"
                  } }] }
                }
                """.trimIndent(),
            ).jsonObject,
        )
        val dashboard = Dashboard(dashboardId, workspaceId, definition, now, now,
    updatedBy = null,
    credentialSurface = null,
    agentClientId = null,
)
        return Fixture(workspaceId, dashboard)
    }

    private class Fixture(
        val workspaceId: WorkspaceId,
        val dashboard: Dashboard,
        val dashboardRepository: InMemoryDashboardRepository = InMemoryDashboardRepository(dashboard),
        val writeOrder: MutableList<String> = mutableListOf(),
        val sessions: InMemorySessionRepository = InMemorySessionRepository(writeOrder),
        val queryRuns: InMemoryQueryRunRepository = InMemoryQueryRunRepository(writeOrder),
        val drafts: InMemoryDraftRepository = InMemoryDraftRepository(),
        val receipts: InMemoryReceiptRepository = InMemoryReceiptRepository(),
    ) {
        fun service(
            admission: DashboardExecutionAdmissionPort = FakeAdmission(),
            cancellation: FakeCancellation = FakeCancellation(),
            semantic: FakeSemantic = FakeSemantic(),
        ): DashboardSessionService = DashboardSessionService(
            transactionPort = ImmediateTransaction,
            dashboardRepository = dashboardRepository,
            sessionRepository = sessions,
            queryRunRepository = queryRuns,
            commandReceiptRepository = receipts,
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

    private class InMemoryDashboardRepository(private var dashboard: Dashboard) : DashboardRepository {
        private var removed = false
        fun remove() { removed = true }
        override fun readCurrentSemanticReferences(workspaceId: WorkspaceId): List<cc.monomer.dashboard.api.DashboardCurrentSemanticReferences> = listOfNotNull(dashboard.takeIf { it.workspaceId == workspaceId }?.let { dashboardSemanticReferences(it.id, it.definition.document) })
        override fun findForUpdate(workspaceId: WorkspaceId, dashboardId: DashboardId): Dashboard? = find(workspaceId, dashboardId)
        override fun find(workspaceId: WorkspaceId, dashboardId: DashboardId): Dashboard? =
            dashboard.takeIf { !removed && it.workspaceId == workspaceId && it.id == dashboardId }
        override fun list(workspaceId: WorkspaceId): List<cc.monomer.dashboard.domain.DashboardListEntry> = emptyList()
        override fun findPage(workspaceId: WorkspaceId, cursor: DashboardPageCursor?, limit: Int): List<DashboardListItem> = emptyList()
        override fun insert(dashboard: Dashboard): Boolean { this.dashboard = dashboard; return true }
        override fun applyCurrent(workspaceId: WorkspaceId, dashboardId: DashboardId, definition: DashboardDefinition, updatedAt: Instant,
        updatedBy: String?,
        credentialSurface: cc.monomer.identity.api.CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?,): Boolean {
            if (this.dashboard.workspaceId != workspaceId || this.dashboard.id != dashboardId) return false
            this.dashboard = this.dashboard.copy(definition = definition, updatedAt = updatedAt)
            return true
        }
    }

    private class InMemorySessionRepository(
        private val writeOrder: MutableList<String>,
    ) : DashboardSessionRepository {
        private val values = linkedMapOf<DashboardSessionId, DashboardSession>()
        fun count(): Int = values.size
        fun removeAll() { values.clear() }
        override fun find(workspaceId: WorkspaceId, sessionId: DashboardSessionId): DashboardSession? =
            values[sessionId]?.takeIf { it.workspaceId == workspaceId }
        override fun find(
            workspaceId: WorkspaceId,
            dashboardId: DashboardId,
            sessionId: DashboardSessionId,
        ): DashboardSession? = values[sessionId]?.takeIf {
            it.workspaceId == workspaceId && it.dashboardId == dashboardId
        }
        override fun findPage(
            workspaceId: WorkspaceId,
            dashboardId: DashboardId,
            cursor: DashboardSessionPageCursor?,
            limit: Int,
        ): List<DashboardSessionSummary> = values.values
            .filter { it.workspaceId == workspaceId && it.dashboardId == dashboardId }
            .sortedWith(compareByDescending<DashboardSession> { it.createdAt }.thenByDescending { it.id.toString() })
            .filter { cursor == null || it.createdAt < cursor.createdAt || (it.createdAt == cursor.createdAt && it.id.toString() < cursor.sessionId.toString()) }
            .take(limit)
            .map {
                DashboardSessionSummary(
                    id = it.id,
                    dashboardId = it.dashboardId,
                    parameterBindings = it.parameterBindings,
                    runningQueryRuns = emptyList(),
                    lastQueryRunAt = null,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            }
        override fun insert(session: DashboardSession): Boolean {
            writeOrder += "session"
            return values.putIfAbsent(session.id, session) == null
        }
        override fun findInitial(workspaceId: WorkspaceId, dashboardId: DashboardId, sessionId: DashboardSessionId, deadline: Instant): DashboardSession? = find(workspaceId, dashboardId, sessionId)
        override fun findForUpdate(workspaceId: WorkspaceId, dashboardId: DashboardId, sessionId: DashboardSessionId): DashboardSession? = find(workspaceId, dashboardId, sessionId)
    }

    private class InMemoryQueryRunRepository(
        private val writeOrder: MutableList<String>,
    ) : QueryRunRepository {
        private val values = linkedMapOf<QueryRunId, QueryRun>()
        fun count(): Int = values.size
        override fun find(queryRunId: QueryRunId): QueryRun? = values[queryRunId]
        override fun find(
            workspaceId: WorkspaceId,
            dashboardId: DashboardId,
            viewerSessionId: DashboardSessionId?,
            queryRunId: QueryRunId,
        ): QueryRun? = values[queryRunId]?.takeIf {
            it.workspaceId == workspaceId && it.dashboardId == dashboardId && it.viewerSessionId == viewerSessionId
        }
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
        var equivalentRunReads = 0
        var equivalentRunReadTimesOut = false
        override fun findEquivalentRuns(
            workspaceId: WorkspaceId,
            dashboardId: DashboardId,
            viewerSessionId: DashboardSessionId?,
            lookups: List<EquivalentQueryRunLookup>,
            allowedStates: Set<QueryRunState>,
            deadline: Instant,
        ): List<QueryRun?> {
            equivalentRunReads++
            if (equivalentRunReadTimesOut) throw DashboardReadDeadlineExceeded()
            return lookups.map { lookup ->
                findEquivalent(workspaceId, dashboardId, viewerSessionId, lookup.queryName, lookup.connectorId,
                    lookup.resolvedQuery, lookup.renderedSql, lookup.outputColumnNames, allowedStates)
            }
        }
        override fun findRunningSessionRuns(workspaceId: WorkspaceId, dashboardId: DashboardId, sessionId: DashboardSessionId): List<QueryRun> =
            values.values.filter { it.workspaceId == workspaceId && it.dashboardId == dashboardId && it.viewerSessionId == sessionId && it.state == QueryRunState.RUNNING }
        override fun findRecentDraftRuns(workspaceId: WorkspaceId, dashboardId: DashboardId, limit: Int): List<QueryRun> =
            values.values.filter { it.workspaceId == workspaceId && it.dashboardId == dashboardId && it.viewerSessionId == null }
                .sortedByDescending { it.createdAt }.take(limit)
        override fun findDraftPage(
            workspaceId: WorkspaceId,
            dashboardId: DashboardId,
            cursor: DashboardQueryRunPageCursor?,
            limit: Int,
        ): List<QueryRun> = values.values
            .filter { it.workspaceId == workspaceId && it.dashboardId == dashboardId && it.viewerSessionId == null }
            .sortedWith(compareByDescending<QueryRun> { it.createdAt }.thenByDescending { it.id.toString() })
            .filter { cursor == null || it.createdAt < cursor.createdAt || (it.createdAt == cursor.createdAt && it.id.toString() < cursor.queryRunId.toString()) }
            .take(limit)
        override fun findSessionPage(
            workspaceId: WorkspaceId,
            dashboardId: DashboardId,
            sessionId: DashboardSessionId,
            cursor: DashboardQueryRunPageCursor?,
            limit: Int,
        ): List<QueryRun> = values.values
            .filter { it.workspaceId == workspaceId && it.dashboardId == dashboardId && it.viewerSessionId == sessionId }
            .sortedWith(compareByDescending<QueryRun> { it.createdAt }.thenByDescending { it.id.toString() })
            .filter { cursor == null || it.createdAt < cursor.createdAt || (it.createdAt == cursor.createdAt && it.id.toString() < cursor.queryRunId.toString()) }
            .take(limit)
        override fun insert(queryRun: QueryRun): Boolean {
            writeOrder += "query-run"
            return values.putIfAbsent(queryRun.id, queryRun) == null
        }
        override fun update(expectedUpdatedAt: Instant, queryRun: QueryRun): Boolean {
            val current = values[queryRun.id] ?: return false
            if (current.updatedAt != expectedUpdatedAt) return false
            values[queryRun.id] = queryRun
            return true
        }
    }

    private class InMemoryDraftRepository : DashboardDraftRepository {
        private val values = linkedMapOf<Pair<WorkspaceId, DashboardId>, DashboardDraft>()
        override fun findDraft(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardDraft? = values[workspaceId to dashboardId]
        override fun findDraftForUpdate(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardDraft? = findDraft(workspaceId, dashboardId)
        override fun findDraftStatus(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardDraftStatus? = error("Not used by Session fixtures")
        override fun insert(draft: DashboardDraft): Boolean = values.putIfAbsent(draft.workspaceId to draft.dashboardId, draft) == null
        override fun update(expectedUpdatedAt: Instant, draft: DashboardDraft): Boolean {
            val current = findDraft(draft.workspaceId, draft.dashboardId) ?: return false
            if (current.updatedAt != expectedUpdatedAt) return false
            values[draft.workspaceId to draft.dashboardId] = draft
            return true
        }
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
        ): cc.monomer.dashboard.api.DashboardOwnerOutcome<cc.monomer.identity.api.IdentityCredentialEvidence?> = cc.monomer.dashboard.api.DashboardOwnerOutcome.Success(null)

        override suspend fun resolveBatch(commands: List<DashboardQueryPlanningPort.ResolveQueryCommand>): List<DashboardQueryResolution> = kotlinx.coroutines.coroutineScope {
            val results = mutableListOf<DashboardQueryResolution>()
            for (batch in commands.chunked(4)) results += batch.map { command -> async { resolve(command) } }.awaitAll()
            results
        }

        val commands = mutableListOf<DashboardQueryPlanningPort.ResolveQueryCommand>()
        var beforeResolve: (suspend () -> Unit)? = null
        var inspect: (suspend () -> Unit)? = null
        var namedSql = false
        var sql = "select 1"
        var columns = listOf("value")
        var outputColumnDisplays: List<cc.monomer.semantic.api.SemanticOutputColumnDisplay>? = null
        var reportingTimeZone: java.time.ZoneId = java.time.ZoneId.of("UTC")
        var invalidAt: Int? = null
        var actorId: String? = null
        var credentialSurface: cc.monomer.identity.api.CredentialSurface? = null
        var agentClientId: cc.monomer.identity.api.AgentClientId? = null

        override suspend fun resolve(command: DashboardQueryPlanningPort.ResolveQueryCommand): DashboardQueryResolution {
            val callback = beforeResolve
            beforeResolve = null
            callback?.invoke()
            inspect?.invoke()
            commands += command
            if (commands.size == invalidAt) return DashboardQueryResolution.Rejected("INVALID_SEMANTIC_QUERY", null)
            return DashboardQueryResolution.Resolved(
                resolvedQuery = command.resolvedQuery,
                sql = (command.query as? DashboardNamedQuery.Sql)?.template?.let { if (namedSql) it.namedSql() else it.executableSql } ?: sql,
                outputColumns = (command.query as? DashboardNamedQuery.Sql)?.columns ?: columns,
                actorId = actorId,
                credentialSurface = credentialSurface,
                agentClientId = agentClientId,
                parameterNames = (command.query as? DashboardNamedQuery.Sql)?.template?.takeIf { namedSql }?.namedParameterNames()
                    ?: List(command.resolvedQuery.bindings.size) { "" },
                outputColumnDisplays = outputColumnDisplays,
                reportingTimeZone = if (command.query is DashboardNamedQuery.Semantic) reportingTimeZone else null,
            )
        }
    }

    private class FakeAdmission(
        var outcome: DashboardExecutionAdmissionPort.AdmissionOutcome = DashboardExecutionAdmissionPort.AdmissionOutcome.Admitted,
    ) : DashboardExecutionAdmissionPort {
        val commands = mutableListOf<DashboardExecutionAdmissionPort.AdmissionCommand>()
        override fun lockWorkspace(workspaceId: cc.monomer.identity.api.WorkspaceId) = Unit
        override fun hasUnreleasedOccupancy(workspaceId: cc.monomer.identity.api.WorkspaceId, queryRunIds: Set<cc.monomer.dashboard.api.QueryRunId>): Boolean = false
        override fun admit(command: DashboardExecutionAdmissionPort.AdmissionCommand): DashboardExecutionAdmissionPort.AdmissionOutcome {
            commands += command
            return outcome
        }
    }

    private class FakeCancellation : DashboardExecutionCancellationPort {
        val commands = mutableListOf<DashboardExecutionCancellationPort.CancelCommand>()
        override fun requestCancel(command: DashboardExecutionCancellationPort.CancelCommand): DashboardExecutionCancellationPort.CancellationOutcome {
            commands += command
            return DashboardExecutionCancellationPort.CancellationOutcome.Requested
        }
    }

    private object ImmediateTransaction : DashboardTransactionPort {
        override suspend fun <T> inTransaction(block: () -> T): T = block()
    }

}
