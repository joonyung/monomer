package cc.monomer.agent.api

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonObject

class AgentWorkspaceResponseRelationsTest {
    @Test
    fun `download presence must match explicit result read request`() {
        val path = path("workspace/explore/runs/$RUN_ID/result")
        val expiry = java.time.Instant.parse("2026-09-07T00:05:00Z")
        val link = AgentExploreResultDownloadLink("https://objects.example.test/result.arrow", "result.arrow", "application/vnd.apache.arrow.file", "1", expiry)
        val body = AgentExploreResultResourceBody(emptyList(), emptyList(), "0", 0, false, expiry, null, java.time.ZoneId.of("UTC"))
        val preview = AgentResourceEnvelope.ExploreResult(path, body, emptyList())
        val download = preview.copy(body = body.copy(download = link))
        assertTrue(AgentWorkspaceResponseRelations.matches(AgentReadRequest(path, null), preview))
        assertFalse(AgentWorkspaceResponseRelations.matches(AgentReadRequest(path, null), download))
        assertFalse(AgentWorkspaceResponseRelations.matches(AgentReadRequest(path, AgentResultDownloadFormat.ARROW), preview))
        assertTrue(AgentWorkspaceResponseRelations.matches(AgentReadRequest(path, AgentResultDownloadFormat.ARROW), download))
    }

    @Test
    fun `session resolution must match exact session action input status and run paths`() {
        val session = path("workspace/dashboards/$DASHBOARD_ID/sessions/$RUN_ID")
        val request = AgentInvokeRequest(session, "resolve", AgentInvokeInput.DashboardSessionResolve(emptyMap()))
        val response = AgentActionResult.DashboardSessionResolution(
            session, "resolve", AgentActionStatus.ACCEPTED,
            AgentActionResultReference(AgentResourceKind.DASHBOARD_SESSION, session),
            emptyMap(), mapOf("orders" to path("${session.value}/query-runs/$RUN_ID")),
        )
        assertTrue(AgentWorkspaceResponseRelations.matches(request, response))
        assertTrue(AgentWorkspaceResponseRelations.matches(request, response.copy(queryRuns = emptyMap())))
        assertFalse(AgentWorkspaceResponseRelations.matches(request, response.copy(status = AgentActionStatus.COMPLETED)))
        assertFalse(AgentWorkspaceResponseRelations.matches(request, response.copy(action = "refresh")))
        assertFalse(AgentWorkspaceResponseRelations.matches(request, response.copy(queryRuns = mapOf("orders" to path("${session.value}/query-runs")))))
        assertFalse(AgentWorkspaceResponseRelations.matches(request, response.copy(queryRuns = mapOf("orders" to path("workspace/dashboards/$DASHBOARD_ID/sessions/$DASHBOARD_ID/query-runs/$RUN_ID")))))
        assertFalse(AgentWorkspaceResponseRelations.matches(request, response.copy(queryRuns = mapOf("orders" to path("${session.value}/query-runs/$RUN_ID/result")))))
        assertFalse(AgentWorkspaceResponseRelations.matches(request, AgentActionResult.Invoke(session, "resolve", AgentActionStatus.ACCEPTED, response.result)))
        assertFalse(AgentWorkspaceResponseRelations.matches(request.copy(input = AgentInvokeInput.DashboardQueryRefresh(setOf("orders"))), response))
        assertTrue(AgentWorkspaceResponseRelations.matchesInvocation(session, "resolve", "DASHBOARD_SESSION_RESOLVE"))
        assertTrue(AgentWorkspaceResponseRelations.matchesInvocation(session, "refresh", "DASHBOARD_SESSION_REFRESH"))
        assertFalse(AgentWorkspaceResponseRelations.matchesInvocation(session, "refresh", "DASHBOARD_QUERY_REFRESH"))
        assertFalse(AgentWorkspaceResponseRelations.matchesInvocation(session, "resolve", "DASHBOARD_SESSION_REFRESH"))
    }

    @Test
    fun `Draft status runs must belong to the same Draft`() {
        val status = path("workspace/dashboards/$DASHBOARD_ID/draft/status")
        val run = path("workspace/dashboards/$DASHBOARD_ID/draft/query-runs/$RUN_ID")
        val ready = AgentDashboardReadyResult(AgentRevision(1), emptyMap(), emptyMap(), mapOf("orders" to run))
        val body = AgentDashboardDraftStatusBody(AgentRevision(2), mapOf("orders" to AgentDashboardDraftTargetQueryRun(run, AgentRunState.SUCCEEDED)), mapOf("chart" to ready))
        val request = AgentReadRequest(status, null)
        assertTrue(AgentWorkspaceResponseRelations.matches(request, AgentResourceEnvelope.DashboardDraftStatus(status, body)))
        for (foreign in listOf(
            path("workspace/dashboards/$RUN_ID/draft/query-runs/$RUN_ID"),
            path("workspace/dashboards/$DASHBOARD_ID/sessions/$RUN_ID/query-runs/$RUN_ID"),
            path("workspace/dashboards/$DASHBOARD_ID/draft/query-runs/$RUN_ID/result"),
        )) {
            assertFalse(AgentWorkspaceResponseRelations.matches(request, AgentResourceEnvelope.DashboardDraftStatus(status,
                body.copy(targetQueryRuns = mapOf("orders" to AgentDashboardDraftTargetQueryRun(foreign, AgentRunState.RUNNING))))))
            assertFalse(AgentWorkspaceResponseRelations.matches(request, AgentResourceEnvelope.DashboardDraftStatus(status,
                body.copy(readyResults = mapOf("chart" to ready.copy(queryRuns = mapOf("orders" to foreign)))))))
        }
        assertFalse(AgentWorkspaceResponseRelations.matches(AgentReadRequest(path("workspace/dashboards/$DASHBOARD_ID/draft"), null),
            AgentResourceEnvelope.DashboardDraftStatus(status, body)))
    }

    @Test
    fun `bodyless owner resource may expose only follow-up operations`() {
        val path = AgentResourcePath.parse(
            "workspace/connectors/01942b04-6e00-7000-8000-000000000010/catalog",
        )
        val request = AgentReadRequest(path, null)
        val resource = AgentResourceEnvelope.CatalogRoot(path = path, availableOperations = listOf(
                AgentAvailableOperation.List(path = AgentResourcePath.parse(
                        "workspace/connectors/01942b04-6e00-7000-8000-000000000010/catalog/catalogs",
                    )),
            ))

        assertTrue(AgentWorkspaceResponseRelations.matches(request, resource))
    }
    @Test
    fun `available commands reject unsupported input and action combinations`() {
        val draft = path("workspace/dashboards/$DASHBOARD_ID/draft")
        assertFailsWith<IllegalArgumentException> {
            AgentAvailableOperation.Create(path("workspace/metrics"), "DASHBOARD_DEFINITION")
        }
        assertFailsWith<IllegalArgumentException> {
            AgentAvailableOperation.Invoke(draft, "start", "DASHBOARD_DRAFT_APPLY")
        }
        assertFailsWith<IllegalArgumentException> {
            AgentAvailableOperation.Invoke(path("workspace/explore/runs/$RUN_ID"), "cancel", "SQL_EXPLORE_RUN")
        }
        assertTrue(AgentAvailableOperation.Edit(draft).operation == AgentWorkspaceOperation.EDIT)
    }

    @Test
    fun `create requires the requested owner kind and a concrete collection child`() {
        val collection = path("workspace/dashboards")
        val request = AgentCreateRequest(collection, AgentCreateInput.Dashboard(JsonObject(emptyMap())))
        fun result(kind: AgentResourceKind, resourcePath: AgentResourcePath) =
            AgentActionResult.Create(collection, AgentActionResultReference(kind, resourcePath))
        assertTrue(AgentWorkspaceResponseRelations.matches(request, result(AgentResourceKind.DASHBOARD, path("${collection.value}/$DASHBOARD_ID"))))
        assertFalse(AgentWorkspaceResponseRelations.matches(request, result(AgentResourceKind.METRIC, path("${collection.value}/$DASHBOARD_ID"))))
        assertFalse(AgentWorkspaceResponseRelations.matches(request, result(AgentResourceKind.DASHBOARD, collection)))
        assertFalse(AgentWorkspaceResponseRelations.matches(request, result(AgentResourceKind.DASHBOARD, path("${collection.value}/$DASHBOARD_ID/draft"))))
    }

    @Test
    fun `draft apply result must identify the same current dashboard`() {
        val draft = path("workspace/dashboards/$DASHBOARD_ID/draft")
        val request = AgentInvokeRequest(draft, "apply", AgentInvokeInput.DashboardDraftApply(1))
        fun result(kind: AgentResourceKind, resourcePath: AgentResourcePath) =
            AgentActionResult.Invoke(draft, "apply", AgentActionStatus.COMPLETED, AgentActionResultReference(kind, resourcePath))
        assertTrue(AgentWorkspaceResponseRelations.matches(request, result(AgentResourceKind.DASHBOARD, path("workspace/dashboards/$DASHBOARD_ID"))))
        assertFalse(AgentWorkspaceResponseRelations.matches(request, result(AgentResourceKind.DASHBOARD_DRAFT, draft)))
        assertFalse(AgentWorkspaceResponseRelations.matches(request, result(AgentResourceKind.DASHBOARD, path("workspace/dashboards/$RUN_ID"))))
    }

    @Test
    fun `cancel preserves the exact run and supports accepted or already terminal completion`() {
        val run = path("workspace/explore/runs/$RUN_ID")
        val request = AgentInvokeRequest(run, "cancel", AgentInvokeInput.None)
        AgentActionStatus.entries.forEach { status ->
            assertTrue(AgentWorkspaceResponseRelations.matches(request,
                AgentActionResult.Invoke(run, "cancel", status, AgentActionResultReference(AgentResourceKind.EXPLORE_RUN, run))))
        }
        assertFalse(AgentWorkspaceResponseRelations.matches(request,
            AgentActionResult.Invoke(run, "cancel", AgentActionStatus.ACCEPTED,
                AgentActionResultReference(AgentResourceKind.DASHBOARD_QUERY_RUN, run))))
    }

    @Test
    fun `listing rejects a prefix collision or a grandchild`() {
        val collection = path("workspace/metrics")
        val request = AgentListRequest(collection, null, 10)
        fun listing(resourcePath: AgentResourcePath) = AgentResourceListingResponse(
            listOf(AgentResourceListingItem.Metric(resourcePath, "revenue", emptyList())), null, emptyList(),
        )
        assertTrue(AgentWorkspaceResponseRelations.matches(request, listing(path("workspace/metrics/revenue"))))
        assertFalse(AgentWorkspaceResponseRelations.matches(request, listing(path("workspace/metrics-other/revenue"))))
        assertFalse(AgentWorkspaceResponseRelations.matches(request, listing(path("workspace/metrics/revenue/child"))))
    }

    @Test
    fun `catalog action results preserve names that equal structural path words`() {
        val catalogRoot = "workspace/connectors/$DASHBOARD_ID/catalog"
        listOf("options", "option-refreshes", "selection", "schemas", "relations", "ingestions", ".", "..", "한글😀~1~0%2F|+?#", "a\u2028b", "a\u2029b").forEach { name ->
            val ingestion = path("$catalogRoot/catalogs/$name/ingestions/$RUN_ID")
            val cancel = AgentInvokeRequest(ingestion, "cancel", AgentInvokeInput.None)
            assertTrue(AgentWorkspaceResponseRelations.matches(cancel,
                AgentActionResult.Invoke(ingestion, "cancel", AgentActionStatus.ACCEPTED,
                    AgentActionResultReference(AgentResourceKind.CATALOG_INGESTION, ingestion))))
            assertFalse(AgentWorkspaceResponseRelations.matches(cancel,
                AgentActionResult.Invoke(ingestion, "cancel", AgentActionStatus.ACCEPTED,
                    AgentActionResultReference(AgentResourceKind.CATALOG_SELECTION_OPTION_REFRESH, ingestion))))

            listOf(
                "$catalogRoot/catalog-selection" to "catalogs",
                "$catalogRoot/selection" to "schemas/$name/relations",
                "$catalogRoot/catalogs/$name/selection" to "schemas",
                "$catalogRoot/catalogs/$name/selection" to "schemas/$name/relations",
            ).forEach { (selection, child) ->
                val options = path("$selection/options/$child")
                val refresh = path("$selection/option-refreshes/$child/$RUN_ID")
                val request = AgentInvokeRequest(options, "refresh", AgentInvokeInput.None)
                assertTrue(AgentWorkspaceResponseRelations.matches(request,
                    AgentActionResult.Invoke(options, "refresh", AgentActionStatus.ACCEPTED,
                        AgentActionResultReference(AgentResourceKind.CATALOG_SELECTION_OPTION_REFRESH, refresh))))
                assertFalse(AgentWorkspaceResponseRelations.matches(request,
                    AgentActionResult.Invoke(options, "refresh", AgentActionStatus.ACCEPTED,
                        AgentActionResultReference(AgentResourceKind.CATALOG_INGESTION, refresh))))
                val cancelRefresh = AgentInvokeRequest(refresh, "cancel", AgentInvokeInput.None)
                assertTrue(AgentWorkspaceResponseRelations.matches(cancelRefresh,
                    AgentActionResult.Invoke(refresh, "cancel", AgentActionStatus.ACCEPTED,
                        AgentActionResultReference(AgentResourceKind.CATALOG_SELECTION_OPTION_REFRESH, refresh))))
                assertFalse(AgentWorkspaceResponseRelations.matches(cancelRefresh,
                    AgentActionResult.Invoke(refresh, "cancel", AgentActionStatus.ACCEPTED,
                        AgentActionResultReference(AgentResourceKind.CATALOG_INGESTION, refresh))))
            }
        }
    }

    private fun path(value: String) = AgentResourcePath.parse(value)

    private companion object {
        const val DASHBOARD_ID = "01942b04-6e00-7000-8000-000000000010"
        const val RUN_ID = "01942b04-6e00-7000-8000-000000000011"
    }

}
