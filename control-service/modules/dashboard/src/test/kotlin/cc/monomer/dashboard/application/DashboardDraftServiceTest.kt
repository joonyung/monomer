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
import cc.monomer.dashboard.domain.DashboardDefinition
import cc.monomer.dashboard.domain.DashboardDraft
import cc.monomer.dashboard.domain.DashboardListItem
import cc.monomer.dashboard.domain.QueryRun
import cc.monomer.dashboard.domain.QueryRunState
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.api.PresentedCredential
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import cc.monomer.execution.api.ArrowResultManifest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private val DRAFT_REPLAY_NOW = Instant.parse("2026-01-01T00:00:00Z")

class DashboardDraftServiceTest {
    private val json = Json { explicitNulls = true }

    @Test
    fun `deleted Dashboard rejects create Draft and Apply receipt replays`() = runTest {
        val fixture = Fixture()
        val creator = DashboardService(testSearchProjection { }, ImmediateTransaction, fixture.dashboardRepository, fixture.receiptRepository)
        val createCommand = DashboardUseCase.CreateCommand(fixture.workspaceId, fixture.dashboard.definition.document, "operator", "operator", "create", DRAFT_REPLAY_NOW, null, null)
        val created = assertIs<DashboardResult.Found>(creator.create(createCommand)).dashboard
        val service = fixture.service()
        val start = DashboardDraftUseCase.StartCommand(fixture.workspaceId, created.id, PresentedCredential("test-credential"), null, emptyMap(), "operator", "start", DRAFT_REPLAY_NOW)
        val started = assertIs<DraftResult.Found>(service.start(start))
        val apply = DashboardDraftUseCase.ApplyCommand(fixture.workspaceId, created.id, PresentedCredential("test-credential"), null, started.draft.authoringRevision, "operator", "apply", DRAFT_REPLAY_NOW.plusSeconds(1), null, null)
        assertIs<DraftApplyResult.Applied>(service.apply(apply))
        fixture.dashboardRepository.remove()
        assertEquals(DashboardResult.IdempotencyConflict, creator.create(createCommand))
        assertEquals("IDEMPOTENCY_CONFLICT", assertIs<DraftResult.Rejected>(service.start(start)).code)
        assertEquals("IDEMPOTENCY_CONFLICT", assertIs<DraftApplyResult.Rejected>(service.apply(apply)).code)
    }

    @Test
    fun `Draft start rechecks deletion after Workspace admission before inserting children`() = runTest {
        val fixture = Fixture()
        val admission = object : DashboardExecutionAdmissionPort by FakeAdmission {
            override fun lockWorkspace(workspaceId: WorkspaceId) { fixture.dashboardRepository.remove() }
        }
        val service = fixture.service(admission = admission)
        val result = service.start(DashboardDraftUseCase.StartCommand(fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null, emptyMap(), "operator", "start", DRAFT_REPLAY_NOW))
        assertEquals(DraftResult.NotFound, result)
        assertEquals(null, fixture.draftRepository.findDraft(fixture.workspaceId, fixture.dashboard.id))
        assertEquals(null, fixture.receiptRepository.find(fixture.workspaceId, "operator", "start"))
    }

    @Test
    fun `semantic usage reads current query references without validating unrelated layout`() {
        val fixture = Fixture()
        val document = JsonObject(fixture.dashboard.definition.document + ("layout" to Json.parseToJsonElement(
            """{"sections":[{"id":"legacy","heightScale":1,"root":{"type":"WIDGET","widgetId":"orders"}}]}""")))
        kotlin.test.assertFailsWith<InvalidDashboardDefinitionException> { DashboardDefinitionDecoder.decode(document) }
        val references = dashboardSemanticReferences(fixture.dashboard.id, document)
        assertEquals("Initial definition", references.title)
        assertEquals(mapOf("orders" to setOf("order_total")), references.queryMetricNames)
    }

    @Test
    fun `semantic references use current named queries and ignore Draft SQL and other workspaces`() = runTest {
        val fixture = Fixture()
        val service = DashboardService(testSearchProjection { }, ImmediateTransaction,
            fixture.dashboardRepository, fixture.receiptRepository)
        val started = assertIs<DraftResult.Found>(fixture.service().start(DashboardDraftUseCase.StartCommand(
            fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null, emptyMap(), "operator", "usage-start", DRAFT_REPLAY_NOW)))
        assertIs<DraftResult.Found>(fixture.service().replaceDocument(DashboardDraftUseCase.ReplaceDocumentCommand(
            fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null, started.draft.authoringRevision,
            sqlDashboardDefinition(), emptyMap(), mapOf("a" to JsonPrimitive("a"), "b" to JsonPrimitive("b")), "operator",
            cc.monomer.identity.api.CredentialSurface.WEB, null, "usage-replace", DRAFT_REPLAY_NOW.plusSeconds(1))))
        val current = service.readCurrentSemanticReferences(fixture.workspaceId).single()
        assertEquals("Initial definition", current.title)
        assertEquals(mapOf("orders" to setOf("order_total")), current.queryMetricNames)
        assertEquals(emptyList(), service.readCurrentSemanticReferences(WorkspaceId.generate()))
        fixture.dashboardRepository.applyCurrent(fixture.workspaceId, fixture.dashboard.id,
            DashboardDefinitionDecoder.decode(sqlDashboardDefinition()), DRAFT_REPLAY_NOW.plusSeconds(2), null, null, null)
        assertEquals(emptyMap(), service.readCurrentSemanticReferences(fixture.workspaceId).single().queryMetricNames)
    }

    @Test
    fun `pre cutover create retries are rejected while normalized request remains unchanged`() = runTest {
        val fixture = Fixture()
        val current = fixture.dashboard.definition.document
        val oldQueries = current.getValue("queries").jsonObject.mapValues { (_, value) -> JsonObject(value.jsonObject - "type") }
        val old = JsonObject(current + ("queries" to JsonObject(oldQueries)))
        fixture.receiptRepository.insert(CommandReceipt(
            fixture.workspaceId, "operator", "old-create", "CREATE_DASHBOARD", fixture.dashboard.id.toString(),
            JsonObject(mapOf("definition" to old)), fixture.dashboard.id, null, emptyList(),
            CommandReceiptResponse.DashboardCreated(fixture.dashboard), DRAFT_REPLAY_NOW,
        ))
        val service = DashboardService(
            testSearchProjection { fixture.projected += it }, ImmediateTransaction,
            fixture.dashboardRepository, fixture.receiptRepository,
        )
        val command = DashboardUseCase.CreateCommand(fixture.workspaceId, old, "operator", "operator", "old-create", DRAFT_REPLAY_NOW, null, null)
        kotlin.test.assertFailsWith<InvalidDashboardDefinitionException> { service.create(command) }
        assertEquals(DashboardResult.IdempotencyConflict, service.create(command.copy(document = current)))
        assertEquals(old, fixture.receiptRepository.find(fixture.workspaceId, "operator", "old-create")!!.normalizedRequest.getValue("definition"))
    }

    @Test
    fun `SQL draft reuse observes appearance order types and row limit but ignores binding map order`() = runTest {
        val fixture = Fixture()
        val initial = DashboardDefinitionDecoder.decode(sqlDashboardDefinition())
        fixture.dashboardRepository.applyCurrent(fixture.workspaceId, fixture.dashboard.id, initial, DRAFT_REPLAY_NOW, null, null, null)
        val service = fixture.service()
        val started = assertIs<DraftResult.Found>(service.start(DashboardDraftUseCase.StartCommand(
            fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null, emptyMap(), "operator", "start", DRAFT_REPLAY_NOW,
        )))
        val initialId = started.draft.targetQueryRuns.getValue("orders")
        val first = fixture.queryRunRepository.find(initialId)!!
        fixture.queryRunRepository.update(first.updatedAt, first.succeed(ArrowResultManifest("artifacts/sql", 1uL, "etag", 1uL, 2, "application/vnd.apache.arrow.file"), DRAFT_REPLAY_NOW.plusSeconds(1)))
        var revision = 0L
        fun replace(document: JsonObject, values: Map<String, JsonElement>, key: String) = DashboardDraftUseCase.ReplaceDocumentCommand(
            fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null,
            revision, document, emptyMap(), values, "operator", cc.monomer.identity.api.CredentialSurface.WEB, null, key, DRAFT_REPLAY_NOW.plusSeconds(2),
        )
        val values = mapOf("a" to JsonPrimitive("first"), "b" to JsonPrimitive("second"))
        val reordered = assertIs<DraftResult.Found>(service.replaceDocument(replace(sqlDashboardDefinition(bindings = """{"b":{"kind":"VARIABLE","name":"b"},"a":{"kind":"VARIABLE","name":"a"}}"""), values, "reorder")))
        assertEquals(initialId, reordered.draft.targetQueryRuns.getValue("orders"))
        revision = reordered.draft.authoringRevision
        val swapped = assertIs<DraftResult.Found>(service.replaceDocument(replace(sqlDashboardDefinition(sql = "SELECT {{b}} AS value, {{a}} AS other"), values, "swap")))
        kotlin.test.assertNotEquals(initialId, swapped.draft.targetQueryRuns.getValue("orders"))
        revision = swapped.draft.authoringRevision
        val limited = assertIs<DraftResult.Found>(service.replaceDocument(replace(sqlDashboardDefinition(sql = "SELECT {{b}} AS value, {{a}} AS other", rowLimit = 10), values, "limit")))
        kotlin.test.assertNotEquals(swapped.draft.targetQueryRuns, limited.draft.targetQueryRuns)
        revision = limited.draft.authoringRevision
        val textOne = assertIs<DraftResult.Found>(service.replaceDocument(replace(sqlDashboardDefinition(sql = "SELECT {{b}} AS value, {{a}} AS other", rowLimit = 10), mapOf("a" to JsonPrimitive("1"), "b" to JsonPrimitive("second")), "text-one")))
        revision = textOne.draft.authoringRevision
        val numbered = assertIs<DraftResult.Found>(service.replaceDocument(replace(sqlDashboardDefinition(sql = "SELECT {{b}} AS value, {{a}} AS other", rowLimit = 10, variables = """{"a":{"type":"NUMBER","nullable":false,"initial":"1"},"b":{"type":"TEXT","nullable":false,"initial":"second"}}"""), mapOf("a" to JsonPrimitive("1"), "b" to JsonPrimitive("second")), "number-one")))
        kotlin.test.assertNotEquals(textOne.draft.targetQueryRuns, numbered.draft.targetQueryRuns)
    }

    @Test
    fun `SQL Draft passes names and never reuses different quoted slot placements`() = runTest {
        val fixture = Fixture()
        val firstDocument = sqlDashboardDefinition(sql = "SELECT '{{a}}', '?'", bindings = """{"a":{"kind":"VARIABLE","name":"a"}}""")
        val initial = DashboardDefinitionDecoder.decode(firstDocument)
        fixture.dashboardRepository.applyCurrent(fixture.workspaceId, fixture.dashboard.id, initial, DRAFT_REPLAY_NOW, null, null, null)
        val commands = mutableListOf<DashboardExecutionAdmissionPort.AdmissionCommand>()
        val planner = object : DashboardQueryPlanningPort by FakeSemanticQuery {
            override suspend fun resolve(command: DashboardQueryPlanningPort.ResolveQueryCommand): DashboardQueryResolution {
                val template = (command.query as DashboardNamedQuery.Sql).template
                return (FakeSemanticQuery.resolve(command) as DashboardQueryResolution.Resolved).copy(sql = template.namedSql(), parameterNames = template.namedParameterNames())
            }
        }
        val admission = object : DashboardExecutionAdmissionPort by FakeAdmission {
            override fun admit(command: DashboardExecutionAdmissionPort.AdmissionCommand): DashboardExecutionAdmissionPort.AdmissionOutcome {
                commands += command
                return DashboardExecutionAdmissionPort.AdmissionOutcome.Admitted
            }
        }
        val service = fixture.service(planner, admission)
        val started = assertIs<DraftResult.Found>(service.start(DashboardDraftUseCase.StartCommand(
            fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null, emptyMap(), "operator", "named-start", DRAFT_REPLAY_NOW,
        )))
        assertEquals(listOf("monomer_p1"), commands.single().parameterNames)
        assertEquals("SELECT ' :monomer_p1 ', '?'", started.queryRuns.single().renderedSql)
        val changed = assertIs<DraftResult.Found>(service.replaceDocument(DashboardDraftUseCase.ReplaceDocumentCommand(
            fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null, started.draft.authoringRevision,
            sqlDashboardDefinition(sql = "SELECT '?', '{{a}}'", bindings = """{"a":{"kind":"VARIABLE","name":"a"}}"""),
            emptyMap(), started.draft.variables, "operator", cc.monomer.identity.api.CredentialSurface.WEB, null, "named-replace", DRAFT_REPLAY_NOW.plusSeconds(1),
        )))
        kotlin.test.assertNotEquals(started.draft.targetQueryRuns, changed.draft.targetQueryRuns)
        assertEquals(listOf("monomer_p1"), commands.last().parameterNames)
        assertEquals("SELECT '?', ' :monomer_p1 '", commands.last().sql)
        assertEquals(2, commands.size)
    }

    @Test
    fun `SQL draft precision rejection leaves the accepted draft and targets unchanged`() = runTest {
        val fixture = Fixture()
        val initial = DashboardDefinitionDecoder.decode(sqlDashboardDefinition(sql = "SELECT {{a}} AS value", bindings = """{"a":{"kind":"VARIABLE","name":"a"}}""", variables = """{"a":{"type":"TIMESTAMP","nullable":false,"initial":"2026-10-06T00:00:00Z"}}""", columns = """["value"]"""))
        fixture.dashboardRepository.applyCurrent(fixture.workspaceId, fixture.dashboard.id, initial, DRAFT_REPLAY_NOW, null, null, null)
        val service = fixture.service()
        val started = assertIs<DraftResult.Found>(service.start(DashboardDraftUseCase.StartCommand(
            fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null, emptyMap(), "operator", "start", DRAFT_REPLAY_NOW,
        )))
        val rejected = assertIs<DraftResult.Rejected>(service.setVariables(DashboardDraftUseCase.SetVariablesCommand(
            fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null,
            mapOf("a" to JsonPrimitive("2026-10-06T00:00:00.123456789Z")), 0,
            "operator", "invalid", DRAFT_REPLAY_NOW.plusSeconds(1),
        )))
        assertEquals("INVALID_QUERY_BINDING", rejected.code)
        assertEquals(started.draft, assertIs<DraftResult.Found>(service.get(fixture.workspaceId, fixture.dashboard.id)).draft)
    }

    @Test
    fun `widget type and height replacement is atomic for every definition mutation`() = runTest {
        for (documentReplacement in listOf(false, true)) {
            val fixture = Fixture()
            val chart = fixture.dashboard.definition.document
            val content = JsonObject(chart + mapOf(
                "queries" to JsonObject(emptyMap()),
                "widgets" to json.parseToJsonElement("""{"chart":{"type":"TEXT","content":"Introduction"}}"""),
                "layout" to json.parseToJsonElement("""{"sections":[{"id":"main","height":{"mode":"CONTENT"},"root":{"type":"WIDGET","id":"chartItem","widgetId":"chart"}}]}"""),
            ))
            fixture.dashboardRepository.applyCurrent(fixture.workspaceId, fixture.dashboard.id, DashboardDefinitionDecoder.decode(content), DRAFT_REPLAY_NOW, null, null, null)
            val service = fixture.service()
            val started = assertIs<DraftResult.Found>(service.start(DashboardDraftUseCase.StartCommand(
                fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null,
                emptyMap(), "operator", "start", DRAFT_REPLAY_NOW,
            )))
            suspend fun replace(document: JsonObject, key: String): DraftResult =
                if (documentReplacement) service.replaceDocument(DashboardDraftUseCase.ReplaceDocumentCommand(
                    fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null,
                    0, document, emptyMap(), emptyMap(), "operator", cc.monomer.identity.api.CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"),
                    key, DRAFT_REPLAY_NOW.plusSeconds(1),
                )) else service.replace(replaceCommand(fixture, "Height", 0, key, DRAFT_REPLAY_NOW.plusSeconds(1)).copy(definition = document))
            val invalid = JsonObject(chart + ("layout" to content.getValue("layout")))
            assertIs<DraftResult.Rejected>(replace(invalid, "invalid"))
            assertEquals(started.draft, assertIs<DraftResult.Found>(service.get(fixture.workspaceId, fixture.dashboard.id)).draft)
            val accepted = assertIs<DraftResult.Found>(replace(chart, "valid"))
            assertEquals(1, accepted.draft.authoringRevision)
            assertEquals(chart, accepted.draft.definition.document)
            assertEquals(accepted.draft, assertIs<DraftResult.Found>(service.get(fixture.workspaceId, fixture.dashboard.id)).draft)
        }
    }

    @Test
    fun `text to chart replacement atomically updates column weight and section height`() = runTest {
        for (documentReplacement in listOf(false, true)) {
            val fixture = Fixture()
            val chartDocument = fixture.dashboard.definition.document
            val initial = JsonObject(chartDocument + mapOf(
                "queries" to JsonObject(emptyMap()),
                "widgets" to json.parseToJsonElement("""{"intro":{"type":"TEXT","content":"Introduction"},"chart":{"type":"TEXT","content":"Description"}}"""),
                "layout" to json.parseToJsonElement("""{"sections":[{"id":"main","height":{"mode":"CONTENT"},"root":{"type":"STACK","id":"column","direction":"COLUMN","children":[{"node":{"type":"WIDGET","id":"introNode","widgetId":"intro"}},{"node":{"type":"WIDGET","id":"chartItem","widgetId":"chart"}}]}}]}"""),
            ))
            fixture.dashboardRepository.applyCurrent(fixture.workspaceId, fixture.dashboard.id, DashboardDefinitionDecoder.decode(initial), DRAFT_REPLAY_NOW, null, null, null)
            val service = fixture.service()
            val started = assertIs<DraftResult.Found>(service.start(DashboardDraftUseCase.StartCommand(
                fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null,
                emptyMap(), "operator", "start-layout", DRAFT_REPLAY_NOW,
            )))
            val invalid = JsonObject(initial + mapOf(
                "queries" to chartDocument.getValue("queries"),
                "widgets" to JsonObject(initial.getValue("widgets").jsonObject + chartDocument.getValue("widgets").jsonObject),
            ))
            suspend fun replace(document: JsonObject, key: String): DraftResult =
                if (documentReplacement) service.replaceDocument(DashboardDraftUseCase.ReplaceDocumentCommand(
                    fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null,
                    0, document, emptyMap(), emptyMap(), "operator", cc.monomer.identity.api.CredentialSurface.WEB, null,
                    key, DRAFT_REPLAY_NOW.plusSeconds(1),
                )) else service.replace(replaceCommand(fixture, "Height", 0, key, DRAFT_REPLAY_NOW.plusSeconds(1)).copy(definition = document))
            assertIs<DraftResult.Rejected>(replace(invalid, "widget-only"))
            assertEquals(started.draft, assertIs<DraftResult.Found>(service.get(fixture.workspaceId, fixture.dashboard.id)).draft)
            val valid = JsonObject(invalid + ("layout" to json.parseToJsonElement("""{"sections":[{"id":"main","height":{"mode":"SCALED","scale":1},"root":{"type":"STACK","id":"column","direction":"COLUMN","children":[{"node":{"type":"WIDGET","id":"introNode","widgetId":"intro"}},{"weight":1,"node":{"type":"WIDGET","id":"chartItem","widgetId":"chart"}}]}}]}""")))
            val replaced = assertIs<DraftResult.Found>(replace(valid, "widget-and-layout"))
            assertEquals(1, replaced.draft.authoringRevision)
            assertEquals(valid, replaced.draft.definition.document)
            val applied = assertIs<DraftApplyResult.Applied>(service.apply(DashboardDraftUseCase.ApplyCommand(
                fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null,
                replaced.draft.authoringRevision, "operator", "apply-layout", DRAFT_REPLAY_NOW.plusSeconds(2), null, null,
            )))
            assertEquals(valid, applied.dashboard.definition.document)
        }
    }

    @Test
    fun `variable batch is atomic and stale editors cannot overwrite accepted values`() = runTest {
        val fixture = Fixture()
        val base = controlUiOnlyDefinition(controlUiOnlyCases.first()).document
        val definition = DashboardDefinitionDecoder.decode(JsonObject(base + ("variables" to JsonObject(
            base.getValue("variables").jsonObject + ("other" to json.parseToJsonElement("""{"type":"TEXT","nullable":false,"initial":"initial"}""")),
        ))))
        fixture.dashboardRepository.applyCurrent(fixture.workspaceId, fixture.dashboard.id, definition, DRAFT_REPLAY_NOW, null, null, null)
        val service = fixture.service()
        val started = assertIs<DraftResult.Found>(service.start(DashboardDraftUseCase.StartCommand(
            fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null,
            mapOf("p" to JsonPrimitive("CN")), "editor-a", "start-batch", DRAFT_REPLAY_NOW,
        )))
        val command = DashboardDraftUseCase.SetVariablesCommand(
            fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null,
            mapOf("v" to JsonPrimitive("US"), "other" to JsonPrimitive("changed")), 0,
            "editor-a", "variables-batch", DRAFT_REPLAY_NOW.plusSeconds(1),
        )
        for ((index, invalid) in listOf(
            emptyMap(), mapOf("missing" to JsonPrimitive("bad")),
            mapOf("v" to JsonPrimitive("US"), "other" to JsonPrimitive(123)),
        ).withIndex()) {
            assertIs<DraftResult.Rejected>(service.setVariables(command.copy(variables = invalid, idempotencyKey = "invalid-$index")))
            assertEquals(started.draft, assertIs<DraftResult.Found>(service.get(fixture.workspaceId, fixture.dashboard.id)).draft)
        }
        val changed = assertIs<DraftResult.Found>(service.setVariables(command))
        assertEquals(1, changed.draft.authoringRevision)
        assertEquals(command.variables, changed.draft.variables)
        assertEquals(started.draft.definition, changed.draft.definition)
        assertEquals(started.draft.parameterBindings, changed.draft.parameterBindings)
        assertEquals(null, changed.draft.lastEditedAt)
        assertEquals(started.draft.targetQueryRuns["parameterQuery"], changed.draft.targetQueryRuns["parameterQuery"])
        kotlin.test.assertNotEquals(started.draft.targetQueryRuns["variableQuery"], changed.draft.targetQueryRuns["variableQuery"])
        assertEquals(1, assertIs<DraftResult.Conflict>(service.setVariables(command.copy(
            actorId = "editor-b", idempotencyKey = "stale-editor", variables = mapOf("v" to JsonPrimitive("JP")),
        ))).currentRevision)
        assertEquals(changed.draft, assertIs<DraftResult.Found>(service.get(fixture.workspaceId, fixture.dashboard.id)).draft)
        assertEquals(changed.draft.targetQueryRuns, assertIs<DraftResult.Found>(service.setVariables(command)).draft.targetQueryRuns)
        val partial = assertIs<DraftResult.Found>(service.setVariables(command.copy(
            expectedRevision = 1, variables = mapOf("other" to JsonPrimitive("partial")), idempotencyKey = "partial",
        )))
        assertEquals(JsonPrimitive("US"), partial.draft.variables["v"])
        assertEquals(JsonPrimitive("partial"), partial.draft.variables["other"])
        assertEquals(changed.draft.targetQueryRuns, partial.draft.targetQueryRuns)
    }

    @Test
    fun `definition and document replacement remove query targets while reconciling retained queries`() = runTest {
        for (documentReplacement in listOf(false, true)) {
            for (changeRetainedQuery in listOf(false, true)) {
                val fixture = Fixture()
                val original = fixture.dashboard.definition.document
                val query = original.getValue("queries").jsonObject.getValue("orders")
                val chart = original.getValue("widgets").jsonObject.getValue("chart").jsonObject
                val initial = DashboardDefinitionDecoder.decode(JsonObject(original + mapOf(
                    "queries" to buildJsonObject { put("orders", query); put("removed", query) },
                    "widgets" to buildJsonObject { put("chart", JsonObject(chart + ("queryNames" to json.parseToJsonElement("""["orders","removed"]""")))) },
                )))
                fixture.dashboardRepository.applyCurrent(fixture.workspaceId, fixture.dashboard.id, initial, DRAFT_REPLAY_NOW, null, null, null)
                val service = fixture.service()
                val started = assertIs<DraftResult.Found>(service.start(DashboardDraftUseCase.StartCommand(
                    fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null,
                    emptyMap(), "operator", "start", DRAFT_REPLAY_NOW,
                )))
                val next = if (changeRetainedQuery) JsonObject(original + ("queries" to buildJsonObject {
                    put("orders", JsonObject(query.jsonObject + ("query" to JsonObject(query.jsonObject.getValue("query").jsonObject + ("rowLimit" to JsonPrimitive(10))))))
                })) else original
                val result = if (documentReplacement) service.replaceDocument(DashboardDraftUseCase.ReplaceDocumentCommand(
                    fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null,
                    0, next, emptyMap(), emptyMap(), "operator", cc.monomer.identity.api.CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"),
                    "replace", DRAFT_REPLAY_NOW.plusSeconds(1),
                )) else service.replace(replaceCommand(fixture, "Initial definition", 0, "replace", DRAFT_REPLAY_NOW.plusSeconds(1)).copy(definition = next))
                val replaced = assertIs<DraftResult.Found>(result)
                assertEquals(setOf("orders"), replaced.draft.targetQueryRuns.keys)
                assertEquals(1, replaced.draft.authoringRevision)
                assertEquals(replaced.draft, assertIs<DraftResult.Found>(service.get(fixture.workspaceId, fixture.dashboard.id)).draft)
                val retainedRun = started.draft.targetQueryRuns.getValue("orders")
                if (changeRetainedQuery) kotlin.test.assertNotEquals(retainedRun, replaced.draft.targetQueryRuns.getValue("orders"))
                else assertEquals(retainedRun, replaced.draft.targetQueryRuns.getValue("orders"))
                val removedRun = started.draft.targetQueryRuns.getValue("removed")
                kotlin.test.assertNotNull(fixture.queryRunRepository.find(removedRun))
                assertEquals(if (changeRetainedQuery) setOf(removedRun, retainedRun) else setOf(removedRun), fixture.cancelled.map { it.queryRunId }.toSet())
                val settlement = DashboardQueryRunSettlementService(fixture.queryRunRepository, fixture.draftRepository)
                assertEquals(DashboardQueryRunSettlementUseCase.Outcome.Applied, settlement.settleInOwnerTransaction(
                    DashboardQueryRunSettlementUseCase.Command(
                        fixture.workspaceId, removedRun, DashboardQueryRunSettlementUseCase.State.SUCCEEDED,
                        cc.monomer.execution.api.ArrowResultManifest("artifacts/$removedRun", 1uL, "etag", 1uL, 1, "application/vnd.apache.arrow.file"),
                        null, null, DRAFT_REPLAY_NOW.plusSeconds(2),
                    ),
                ))
                val afterLateResult = assertIs<DraftResult.Found>(service.get(fixture.workspaceId, fixture.dashboard.id)).draft
                assertEquals(replaced.draft.targetQueryRuns, afterLateResult.targetQueryRuns)
                assertEquals(emptyMap(), afterLateResult.readyResults)
            }
        }
    }

    @Test
    fun `create search failure propagates before a receipt is recorded`() = runTest {
        val fixture = Fixture()
        val service = DashboardService(
            searchProjection = testSearchProjection { error("search unavailable") },
            transactionPort = ImmediateTransaction,
            repository = fixture.dashboardRepository,
            commandReceiptRepository = fixture.receiptRepository,
        )
        kotlin.test.assertFailsWith<IllegalStateException> {
            service.create(DashboardUseCase.CreateCommand(
                fixture.workspaceId, definition("Current only").document, "operator", "operator", "failed-create", DRAFT_REPLAY_NOW,
                credentialSurface = null,
                agentClientId = null,
            ))
        }
        assertEquals(null, fixture.receiptRepository.find(fixture.workspaceId, "operator", "failed-create"))
    }

    @Test
    fun `draft current lifecycle preserves typed values outside UI controls and control edits do not rewrite values`() = runTest {
        for (case in controlUiOnlyCases) {
            val fixture = Fixture()
            val definition = controlUiOnlyDefinition(case)
            fixture.dashboardRepository.applyCurrent(fixture.workspaceId, fixture.dashboard.id, definition, DRAFT_REPLAY_NOW, null, null, null)
            val value = Json.parseToJsonElement(case.value)
            val bindings = mapOf("p" to value)
            val service = fixture.service()
            val start = DashboardDraftUseCase.StartCommand(
                fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null,
                bindings, "operator", "ui-start", DRAFT_REPLAY_NOW,
            )
            val started = assertIs<DraftResult.Found>(service.start(start))
            assertEquals(bindings, started.draft.parameterBindings)
            assertEquals(mapOf("v" to value), started.draft.variables)
            started.queryRuns.forEach { assertControlUiOnlyQueryValue((it.resolvedQuery as DashboardResolvedQuery.Semantic).query, value, case.type) }

            val changedControl = if (case.type == "TEXT" || case.type == "TEXT_SET") {
                """{"options":[{"value":"JP"},{"value":"KR"}]}"""
            } else if (case.type == "NUMBER" || case.type == "NUMBER_SET") {
                """{"min":"10","max":"20"}"""
            } else if (case.type == "DATE_RANGE") {
                """{"min":"2026-07-01","max":"2026-07-31"}"""
            } else {
                """{"min":"2026-07-01T00:00:00Z","max":"2026-07-31T00:00:00Z"}"""
            }
            val nextDefinition = controlUiOnlyDefinition(case.copy(control = changedControl))
            val replaced = assertIs<DraftResult.Found>(service.replace(DashboardDraftUseCase.ReplaceCommand(
                fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null,
                0, nextDefinition.document, bindings, "operator", cc.monomer.identity.api.CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), "ui-replace", DRAFT_REPLAY_NOW.plusSeconds(1),
            )))
            assertEquals(DRAFT_REPLAY_NOW.plusSeconds(1), replaced.draft.lastEditedAt)
            assertEquals("operator", replaced.draft.lastEditedBy)
            assertEquals(cc.monomer.identity.api.CredentialSurface.AGENT, replaced.draft.lastEditSurface)
            assertEquals(cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), replaced.draft.lastEditAgentClientId)
            assertEquals(bindings, replaced.draft.parameterBindings)
            assertEquals(started.draft.variables, replaced.draft.variables)
            assertEquals(started.draft.targetQueryRuns, replaced.draft.targetQueryRuns)

            val changedValue = when (value) {
                is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(value.reversed())
                is JsonObject -> JsonObject(value + ("end" to value.getValue("start")))
                else -> JsonPrimitive(if (case.type == "NUMBER") "175" else "US")
            }
            val parameterSet = assertIs<DraftResult.Found>(service.setParameter(DashboardDraftUseCase.SetValueCommand(
                fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null,
                "p", changedValue, 1, "operator", "ui-parameter", DRAFT_REPLAY_NOW.plusSeconds(2),
            )))
            assertEquals(mapOf("p" to changedValue), parameterSet.draft.parameterBindings)
            assertControlUiOnlyQueryValue(
                (fixture.queryRunRepository.find(parameterSet.draft.targetQueryRuns.getValue("parameterQuery"))!!.resolvedQuery as DashboardResolvedQuery.Semantic).query,
                changedValue, case.type,
            )
            val documentReplaced = assertIs<DraftResult.Found>(service.replaceDocument(DashboardDraftUseCase.ReplaceDocumentCommand(
                fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null,
                parameterSet.draft.authoringRevision, nextDefinition.document, bindings, mapOf("v" to changedValue),
                "operator", cc.monomer.identity.api.CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), "ui-document", DRAFT_REPLAY_NOW.plusSeconds(3),
            )))
            assertEquals(replaced.draft.lastEditedAt, documentReplaced.draft.lastEditedAt)
            assertEquals(bindings, documentReplaced.draft.parameterBindings)
            assertEquals(mapOf("v" to changedValue), documentReplaced.draft.variables)
            assertControlUiOnlyQueryValue(
                (fixture.queryRunRepository.find(documentReplaced.draft.targetQueryRuns.getValue("variableQuery"))!!.resolvedQuery as DashboardResolvedQuery.Semantic).query,
                changedValue, case.type,
            )
            val applied = assertIs<DraftApplyResult.Applied>(service.apply(DashboardDraftUseCase.ApplyCommand(
                fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null,
                documentReplaced.draft.authoringRevision, "operator", "ui-apply", DRAFT_REPLAY_NOW.plusSeconds(4),
                credentialSurface = null,
                agentClientId = null,
            )))
            assertEquals(nextDefinition, applied.dashboard.definition)
            assertEquals(documentReplaced.draft, assertIs<DraftResult.Found>(service.get(fixture.workspaceId, fixture.dashboard.id)).draft)
        }
    }

    @Test
    fun `start creates missing Draft with bindings and returns existing Draft unchanged for new bindings`() = runTest {
        val fixture = Fixture()
        val definitionWithParameter = DashboardDefinitionDecoder.decode(
            JsonObject(fixture.dashboard.definition.document + (
                "parameters" to json.parseToJsonElement("""{"region":{"type":"TEXT","nullable":false}}""")
            )),
        )
        fixture.dashboardRepository.applyCurrent(
            fixture.workspaceId, fixture.dashboard.id, definitionWithParameter, DRAFT_REPLAY_NOW, null, null, null,
        )
        val service = fixture.service()
        val command = DashboardDraftUseCase.StartCommand(
            workspaceId = fixture.workspaceId,
            dashboardId = fixture.dashboard.id,
            credential = PresentedCredential("test-credential"),
            expectedOAuthResource = null,
            parameterBindings = mapOf("region" to JsonPrimitive("US")),
            actorId = "operator",
            idempotencyKey = "start-initial",
            requestedAt = DRAFT_REPLAY_NOW,
        )
        val started = assertIs<DraftResult.Found>(service.start(command))
        assertEquals(command.parameterBindings, started.draft.parameterBindings)
        val runsBeforeReentry = fixture.queryRunRepository.findRecentDraftRuns(fixture.workspaceId, fixture.dashboard.id, 100)

        for ((index, bindings) in listOf(mapOf("region" to JsonPrimitive("KR")), emptyMap()).withIndex()) {
            val existing = assertIs<DraftResult.Found>(
                service.start(command.copy(
                    parameterBindings = bindings,
                    idempotencyKey = "start-existing-$index",
                    requestedAt = DRAFT_REPLAY_NOW.plusSeconds(index + 1L),
                )),
            )
            assertEquals(started, existing)
            assertEquals(started, service.get(fixture.workspaceId, fixture.dashboard.id))
        }
        assertEquals(
            runsBeforeReentry,
            fixture.queryRunRepository.findRecentDraftRuns(fixture.workspaceId, fixture.dashboard.id, 100),
        )
    }

    @Test
    fun `status reports the Draft revision and target run states without a Draft read`() = runTest {
        val fixture = Fixture()
        val service = fixture.service()
        assertEquals(null, service.getStatus(fixture.workspaceId, fixture.dashboard.id))
        val started = assertIs<DraftResult.Found>(service.start(
            DashboardDraftUseCase.StartCommand(
                workspaceId = fixture.workspaceId,
                dashboardId = fixture.dashboard.id,
                credential = PresentedCredential("test-credential"),
                expectedOAuthResource = null,
                parameterBindings = emptyMap(),
                actorId = "operator",
                idempotencyKey = "start",
                requestedAt = DRAFT_REPLAY_NOW,
            ),
        ))
        assertEquals(
            DashboardDraftStatus(
                started.draft.authoringRevision,
                mapOf("orders" to DashboardDraftTargetQueryRun(started.queryRuns.single().id, QueryRunState.RUNNING)),
                started.draft.readyResults,
            ),
            service.getStatus(fixture.workspaceId, fixture.dashboard.id),
        )
    }

    @Test
    fun `replaying an earlier draft mutation returns its original revision and run IDs`() = runTest {
        val fixture = Fixture()
        val service = fixture.service()
        service.start(
            DashboardDraftUseCase.StartCommand(
                workspaceId = fixture.workspaceId,
                dashboardId = fixture.dashboard.id,
                credential = PresentedCredential("test-credential"),
                expectedOAuthResource = null,
                parameterBindings = emptyMap(),
                actorId = "operator",
                idempotencyKey = "start",
                requestedAt = DRAFT_REPLAY_NOW,
            ),
        )
        val mutationA = replaceCommand(fixture, "Revision one", 0, "mutation-a", DRAFT_REPLAY_NOW.plusSeconds(1))
        val first = assertIs<DraftResult.Found>(service.replace(mutationA))
        val mutationB = replaceCommand(fixture, "Revision two", 1, "mutation-b", DRAFT_REPLAY_NOW.plusSeconds(2))
        val second = assertIs<DraftResult.Found>(service.replace(mutationB))

        assertEquals(1, first.draft.authoringRevision)
        assertEquals(2, second.draft.authoringRevision)
        assertEquals(first.queryRuns.map(QueryRun::id), second.queryRuns.map(QueryRun::id))

        val replay = assertIs<DraftResult.Found>(service.replace(mutationA))
        assertEquals(first.draft, replay.draft)
        assertEquals(first.queryRuns, replay.queryRuns)
        assertEquals(first.queryRuns.map(QueryRun::id), replay.queryRuns.map(QueryRun::id))
    }

    @Test
    fun `replaying apply returns the original current definition after a later current change`() = runTest {
        val fixture = Fixture()
        val service = fixture.service()
        service.start(
            DashboardDraftUseCase.StartCommand(
                workspaceId = fixture.workspaceId,
                dashboardId = fixture.dashboard.id,
                credential = PresentedCredential("test-credential"),
                expectedOAuthResource = null,
                parameterBindings = emptyMap(),
                actorId = "operator",
                idempotencyKey = "start",
                requestedAt = DRAFT_REPLAY_NOW,
            ),
        )
        service.replace(replaceCommand(fixture, "Applied definition", 0, "mutation-a", DRAFT_REPLAY_NOW.plusSeconds(1)))

        val applyCommand = DashboardDraftUseCase.ApplyCommand(
            workspaceId = fixture.workspaceId,
            dashboardId = fixture.dashboard.id,
            credential = PresentedCredential("test-credential"),
            expectedOAuthResource = null,
            expectedRevision = 1,
            actorId = "operator",
            idempotencyKey = "apply-a",
            requestedAt = DRAFT_REPLAY_NOW.plusSeconds(2),
            credentialSurface = null,
            agentClientId = null,
        )
        val first = assertIs<DraftApplyResult.Applied>(service.apply(applyCommand))
        fixture.dashboardRepository.applyCurrent(
            fixture.workspaceId,
            fixture.dashboard.id,
            definition("Later current definition"),
            DRAFT_REPLAY_NOW.plusSeconds(3), null, null, null,
        )

        val replay = assertIs<DraftApplyResult.Applied>(service.apply(applyCommand))
        assertEquals(first.dashboard, replay.dashboard)
        assertEquals("Applied definition", replay.dashboard.definition.meta.title)
        assertEquals("Later current definition", fixture.dashboardRepository.current().definition.meta.title)
        assertEquals(listOf("Applied definition"), fixture.projected.single().primaryNames)
        assertEquals(listOf("order_total"), fixture.projected.single().structuralNames)
    }

    @Test
    fun `discard resets the Draft to the applied definition, records the discarder, and replays like other Draft commands`() = runTest {
        val fixture = Fixture()
        val service = fixture.service()
        val started = assertIs<DraftResult.Found>(service.start(DashboardDraftUseCase.StartCommand(
            fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null,
            emptyMap(), "operator", "start", DRAFT_REPLAY_NOW,
        )))
        assertIs<DraftResult.Found>(service.replace(replaceCommand(fixture, "Agent edit", 0, "agent-edit", DRAFT_REPLAY_NOW.plusSeconds(1))))
        val discard = DashboardDraftUseCase.DiscardCommand(
            workspaceId = fixture.workspaceId,
            dashboardId = fixture.dashboard.id,
            credential = PresentedCredential("test-credential"),
            expectedOAuthResource = null,
            expectedRevision = 1,
            actorId = "reviewer",
            credentialSurface = cc.monomer.identity.api.CredentialSurface.WEB,
            agentClientId = null,
            idempotencyKey = "discard-a",
            requestedAt = DRAFT_REPLAY_NOW.plusSeconds(2),
        )
        assertEquals(1, assertIs<DraftResult.Conflict>(service.discard(discard.copy(expectedRevision = 0, idempotencyKey = "stale"))).currentRevision)

        val discarded = assertIs<DraftResult.Found>(service.discard(discard))
        assertEquals(2, discarded.draft.authoringRevision)
        assertEquals(fixture.dashboardRepository.current().definition, discarded.draft.definition)
        assertEquals("Initial definition", discarded.draft.definition.meta.title)
        assertEquals("reviewer", discarded.draft.lastEditedBy)
        assertEquals(DRAFT_REPLAY_NOW.plusSeconds(2), discarded.draft.lastEditedAt)
        assertEquals(cc.monomer.identity.api.CredentialSurface.WEB, discarded.draft.lastEditSurface)
        assertEquals(null, discarded.draft.lastEditAgentClientId)
        assertEquals(started.draft.targetQueryRuns.keys, discarded.draft.targetQueryRuns.keys)
        assertEquals("Initial definition", fixture.dashboardRepository.current().definition.meta.title)
        val receipt = fixture.receiptRepository.find(fixture.workspaceId, "reviewer", "discard-a")!!
        assertEquals("DISCARD_DRAFT", receipt.commandKind)

        assertEquals(discarded.draft, assertIs<DraftResult.Found>(service.discard(discard)).draft)
        assertEquals("IDEMPOTENCY_CONFLICT", assertIs<DraftResult.Rejected>(service.discard(discard.copy(expectedRevision = 2))).code)
        val applyWithDiscardKey = DashboardDraftUseCase.ApplyCommand(
            fixture.workspaceId, fixture.dashboard.id, PresentedCredential("test-credential"), null, 1, "reviewer", "discard-a",
            DRAFT_REPLAY_NOW.plusSeconds(3), null, null,
        )
        assertEquals("IDEMPOTENCY_CONFLICT", assertIs<DraftApplyResult.Rejected>(service.apply(applyWithDiscardKey)).code)

        val unchanged = assertIs<DraftResult.Found>(service.discard(discard.copy(expectedRevision = 2, idempotencyKey = "discard-b", actorId = "someone-else")))
        assertEquals(2, unchanged.draft.authoringRevision)
        assertEquals("reviewer", unchanged.draft.lastEditedBy)
    }

    @Test
    fun `cancel replay found after the command lock returns its original draft snapshot`() = runTest {
        val fixture = Fixture()
        val service = fixture.service()
        val started = assertIs<DraftResult.Found>(
            service.start(
                DashboardDraftUseCase.StartCommand(
                    workspaceId = fixture.workspaceId,
                    dashboardId = fixture.dashboard.id,
                    credential = PresentedCredential("test-credential"),
                    expectedOAuthResource = null,
                    parameterBindings = emptyMap(),
                    actorId = "operator",
                    idempotencyKey = "start",
                    requestedAt = DRAFT_REPLAY_NOW,
                ),
            ),
        )
        val cancel = DashboardDraftUseCase.CancelCommand(
            workspaceId = fixture.workspaceId,
            dashboardId = fixture.dashboard.id,
            credential = PresentedCredential("test-credential"),
            expectedOAuthResource = null,
            queryRunId = started.queryRuns.single().id,
            actorId = "operator",
            idempotencyKey = "cancel",
            requestedAt = DRAFT_REPLAY_NOW.plusSeconds(1),
        )
        val first = assertIs<DraftResult.Found>(service.cancel(cancel))
        val changed = assertIs<DraftResult.Found>(
            service.replace(replaceCommand(fixture, "Changed after cancel", 0, "replace", DRAFT_REPLAY_NOW.plusSeconds(2))),
        )
        assertEquals(1, changed.draft.authoringRevision)

        fixture.receiptRepository.hideNextFind = true
        val replay = assertIs<DraftResult.Found>(service.cancel(cancel))

        assertEquals(first.draft, replay.draft)
        assertEquals(first.queryRuns, replay.queryRuns)
        assertEquals(0, replay.draft.authoringRevision)
    }

    private fun replaceCommand(
        fixture: Fixture,
        title: String,
        expectedRevision: Long,
        idempotencyKey: String,
        requestedAt: Instant,
    ) = DashboardDraftUseCase.ReplaceCommand(
        workspaceId = fixture.workspaceId,
        dashboardId = fixture.dashboard.id,
        credential = PresentedCredential("test-credential"),
        expectedOAuthResource = null,
        expectedRevision = expectedRevision,
        definition = definition(title).document,
        parameterBindings = emptyMap(),
        actorId = "operator",
        credentialSurface = cc.monomer.identity.api.CredentialSurface.WEB,
        agentClientId = null,
        idempotencyKey = idempotencyKey,
        requestedAt = requestedAt,
    )

    private fun definition(title: String): DashboardDefinition = DashboardDefinitionDecoder.decode(
        json.parseToJsonElement(
            """
            {
              "schemaVersion": 1,
              "meta": { "title": "$title" },
              "parameters": {},
              "variables": {},
              "queries": {
                "orders": { "type": "SEMANTIC", "connectorId": "0195f3b2-5c84-7a44-8cc4-3e9bf4fd10de", "query": {
                  "schemaVersion": 4, "metrics": ["order_total"], "groupBys": [], "metricTimeRange": null,
                  "filters": [], "orderBy": [], "rowLimit": 100
                } }
              },
              "widgets": { "chart": { "type": "CHART", "queryNames": ["orders"], "module": {
                "inputs": { "parameters": [], "variables": [] },
                "source": "export default defineChart({ render() { return {}; } });"
              } } },
              "layout": { "sections": [{ "id": "main", "height": { "mode": "SCALED", "scale": 1.0 }, "root": {
                "type": "WIDGET", "id": "chartItem", "widgetId": "chart"
              } }] }
            }
            """.trimIndent(),
        ).jsonObject,
    )

    private inner class Fixture {
        val workspaceId = WorkspaceId.generate()
        val dashboard = Dashboard(
            id = DashboardId.generate(),
            workspaceId = workspaceId,
            definition = definition("Initial definition"),
            createdAt = DRAFT_REPLAY_NOW,
            updatedAt = DRAFT_REPLAY_NOW,
            updatedBy = null,
            credentialSurface = null,
            agentClientId = null,
        )
        val dashboardRepository = InMemoryDashboardRepository(dashboard)
        val queryRunRepository = InMemoryQueryRunRepository()
        val draftRepository = InMemoryDraftRepository(queryRunRepository)
        val receiptRepository = InMemoryReceiptRepository()
        val projected = mutableListOf<cc.monomer.dashboard.api.DashboardSearchFields>()
        val cancelled = mutableListOf<DashboardExecutionCancellationPort.CancelCommand>()

        fun service(
            planner: DashboardQueryPlanningPort = FakeSemanticQuery,
            admission: DashboardExecutionAdmissionPort = FakeAdmission,
        ) = DashboardDraftService(
            searchProjection = testSearchProjection { projected += it },
            transactionPort = ImmediateTransaction,
            dashboardRepository = dashboardRepository,
            draftRepository = draftRepository,
            queryRunRepository = queryRunRepository,
            commandReceiptRepository = receiptRepository,
            queryPlanningPort = planner,
            executionAdmissionPort = admission,
            executionCancellationPort = object : DashboardExecutionCancellationPort {
                override fun requestCancel(command: DashboardExecutionCancellationPort.CancelCommand): DashboardExecutionCancellationPort.CancellationOutcome {
                    cancelled += command
                    return DashboardExecutionCancellationPort.CancellationOutcome.Requested
                }
            },
        )
    }

    private class InMemoryDashboardRepository(private var value: Dashboard) : DashboardRepository {
        private var removed = false
        fun remove() { removed = true }
        override fun readCurrentSemanticReferences(workspaceId: WorkspaceId): List<cc.monomer.dashboard.api.DashboardCurrentSemanticReferences> = listOfNotNull(value.takeIf { it.workspaceId == workspaceId }?.let { dashboardSemanticReferences(it.id, it.definition.document) })
        override fun findForUpdate(workspaceId: WorkspaceId, dashboardId: DashboardId): Dashboard? = find(workspaceId, dashboardId)
        fun current(): Dashboard = value
        override fun find(workspaceId: WorkspaceId, dashboardId: DashboardId): Dashboard? =
            value.takeIf { !removed && it.workspaceId == workspaceId && it.id == dashboardId }
        override fun list(workspaceId: WorkspaceId): List<cc.monomer.dashboard.domain.DashboardListEntry> = emptyList()
        override fun findPage(workspaceId: WorkspaceId, cursor: DashboardPageCursor?, limit: Int): List<DashboardListItem> = emptyList()
        override fun insert(dashboard: Dashboard): Boolean {
            removed = false
            value = dashboard
            return true
        }
        override fun applyCurrent(
            workspaceId: WorkspaceId,
            dashboardId: DashboardId,
            definition: DashboardDefinition,
            updatedAt: Instant,
        updatedBy: String?,
        credentialSurface: cc.monomer.identity.api.CredentialSurface?,
        agentClientId: cc.monomer.identity.api.AgentClientId?,
        ): Boolean {
            if (find(workspaceId, dashboardId) == null) return false
            value = value.copy(definition = definition, updatedAt = updatedAt)
            return true
        }
    }

    private class InMemoryDraftRepository(private val queryRuns: InMemoryQueryRunRepository) : DashboardDraftRepository {
        private var value: DashboardDraft? = null
        override fun findDraft(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardDraft? = value
        override fun findDraftForUpdate(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardDraft? = value
        override fun findDraftStatus(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardDraftStatus? = value?.let { draft ->
            DashboardDraftStatus(
                draft.authoringRevision,
                draft.targetQueryRuns.mapValues { (_, queryRunId) -> DashboardDraftTargetQueryRun(queryRunId, checkNotNull(queryRuns.find(queryRunId)).state) },
                draft.readyResults,
            )
        }
        override fun insert(draft: DashboardDraft): Boolean {
            if (value != null) return false
            value = draft
            return true
        }
        override fun update(expectedUpdatedAt: Instant, draft: DashboardDraft): Boolean {
            if (value?.updatedAt != expectedUpdatedAt) return false
            value = draft
            return true
        }
    }

    private class InMemoryQueryRunRepository : QueryRunRepository {
        private val values = linkedMapOf<QueryRunId, QueryRun>()
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
        ): QueryRun? = values.values.firstOrNull {
            it.workspaceId == workspaceId &&
                it.dashboardId == dashboardId &&
                it.viewerSessionId == viewerSessionId &&
                it.queryName == queryName &&
                it.connectorId == connectorId &&
                it.resolvedQuery == resolvedQuery &&
                it.renderedSql == renderedSql && it.outputColumnNames == outputColumnNames &&
                it.state in allowedStates
        }
        override fun findEquivalentRuns(
            workspaceId: WorkspaceId,
            dashboardId: DashboardId,
            viewerSessionId: DashboardSessionId?,
            lookups: List<EquivalentQueryRunLookup>,
            allowedStates: Set<QueryRunState>,
            deadline: Instant,
        ): List<QueryRun?> = error("Draft planning never reads Session initial results")
        override fun findRunningSessionRuns(workspaceId: WorkspaceId, dashboardId: DashboardId, sessionId: DashboardSessionId): List<QueryRun> =
            values.values.filter { it.workspaceId == workspaceId && it.dashboardId == dashboardId && it.viewerSessionId == sessionId && it.state == QueryRunState.RUNNING }
        override fun findRecentDraftRuns(workspaceId: WorkspaceId, dashboardId: DashboardId, limit: Int): List<QueryRun> =
            values.values.sortedByDescending(QueryRun::createdAt).take(limit)
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
        override fun insert(queryRun: QueryRun): Boolean = values.putIfAbsent(queryRun.id, queryRun) == null
        override fun update(expectedUpdatedAt: Instant, queryRun: QueryRun): Boolean {
            if (values[queryRun.id]?.updatedAt != expectedUpdatedAt) return false
            values[queryRun.id] = queryRun
            return true
        }
    }

    private class InMemoryReceiptRepository : DashboardCommandReceiptRepository {
        private val values = linkedMapOf<Triple<WorkspaceId, String?, String>, CommandReceipt>()
        var hideNextFind: Boolean = false
        override fun lock(workspaceId: WorkspaceId, actorId: String?, idempotencyKey: String) = Unit
        override fun find(workspaceId: WorkspaceId, actorId: String?, idempotencyKey: String): CommandReceipt? {
            if (hideNextFind) {
                hideNextFind = false
                return null
            }
            return values[key(workspaceId, actorId, idempotencyKey)]
        }
        override fun insert(receipt: CommandReceipt): Boolean =
            values.putIfAbsent(key(receipt.workspaceId, receipt.actorId, receipt.idempotencyKey), receipt) == null
        private fun key(workspaceId: WorkspaceId, actorId: String?, idempotencyKey: String) = Triple(workspaceId, actorId, idempotencyKey)
    }

    private object FakeSemanticQuery : DashboardQueryPlanningPort {
        override suspend fun admitPageReader(
            workspaceId: cc.monomer.identity.api.WorkspaceId, dashboardId: cc.monomer.dashboard.api.DashboardId, pageId: kotlin.uuid.Uuid,
            credential: cc.monomer.identity.api.PresentedCredential?, expectedOAuthResource: cc.monomer.identity.api.ExpectedOAuthResource?,
        ): cc.monomer.dashboard.api.DashboardOwnerOutcome<cc.monomer.identity.api.IdentityCredentialEvidence?> = error("Reader admission is not used by Draft")

        override suspend fun resolveBatch(commands: List<DashboardQueryPlanningPort.ResolveQueryCommand>): List<DashboardQueryResolution> = kotlinx.coroutines.coroutineScope {
            val results = mutableListOf<DashboardQueryResolution>()
            for (batch in commands.chunked(4)) results += batch.map { command -> async { resolve(command) } }.awaitAll()
            results
        }

        override suspend fun resolve(command: DashboardQueryPlanningPort.ResolveQueryCommand): DashboardQueryResolution =
            DashboardQueryResolution.Resolved(
                resolvedQuery = command.resolvedQuery,
                sql = (command.query as? DashboardNamedQuery.Sql)?.template?.executableSql ?: "select 1",
                outputColumns = (command.query as? DashboardNamedQuery.Sql)?.columns ?: listOf("value"),
                actorId = null,
                credentialSurface = null,
                agentClientId = null,
                parameterNames = List(command.resolvedQuery.bindings.size) { "" },
                outputColumnDisplays = null,
                reportingTimeZone = if (command.query is DashboardNamedQuery.Semantic) java.time.ZoneId.of("UTC") else null,
            )
    }

    private object FakeAdmission : DashboardExecutionAdmissionPort {
        override fun lockWorkspace(workspaceId: cc.monomer.identity.api.WorkspaceId) = Unit
        override fun hasUnreleasedOccupancy(workspaceId: cc.monomer.identity.api.WorkspaceId, queryRunIds: Set<cc.monomer.dashboard.api.QueryRunId>): Boolean = false
        override fun admit(command: DashboardExecutionAdmissionPort.AdmissionCommand): DashboardExecutionAdmissionPort.AdmissionOutcome =
            DashboardExecutionAdmissionPort.AdmissionOutcome.Admitted
    }

    private object ImmediateTransaction : DashboardTransactionPort {
        override suspend fun <T> inTransaction(block: () -> T): T = block()
    }
}
