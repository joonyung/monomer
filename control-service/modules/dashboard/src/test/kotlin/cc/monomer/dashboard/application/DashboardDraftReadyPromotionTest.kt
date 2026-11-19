@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.application

import cc.monomer.dashboard.domain.DashboardResolvedQuery
import cc.monomer.dashboard.domain.DashboardNamedQuery

import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.dashboard.domain.DashboardDefinition
import cc.monomer.dashboard.domain.DashboardDraft
import cc.monomer.dashboard.domain.DashboardDraftReadyResult
import cc.monomer.dashboard.domain.DraftMutationResult
import cc.monomer.dashboard.domain.QueryRun
import cc.monomer.dashboard.domain.QueryRunState
import cc.monomer.execution.api.ArrowResultManifest
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private val READY_TEST_NOW = Instant.parse("2026-01-01T00:00:00Z")

class DashboardDraftReadyPromotionTest {
    private val json = Json { explicitNulls = true }
    private val workspaceId = WorkspaceId.generate()
    private val dashboardId = DashboardId.generate()
    private val firstRunId = QueryRunId.generate()
    private val secondRunId = QueryRunId.generate()

    @Test
    fun `changing a widget query-name set clears its ready result`() {
        val previousDefinition = definition(listOf("firstQuery"))
        val candidateDefinition = definition(listOf("secondQuery"))
        val previous = draft(previousDefinition, mapOf("firstQuery" to firstRunId))
        val candidate = draft(candidateDefinition, mapOf("secondQuery" to secondRunId))

        assertEquals(emptyMap(), retainDraftReadyResults(previous, candidate))
    }

    @Test
    fun `the exact same widget query-name set retains its ready result`() {
        val previousDefinition = definition(listOf("firstQuery"))
        val candidateDefinition = definition(listOf("firstQuery"))
        val previous = draft(previousDefinition, mapOf("firstQuery" to firstRunId))
        val candidate = draft(candidateDefinition, mapOf("firstQuery" to firstRunId))

        assertEquals(previous.readyResults, retainDraftReadyResults(previous, candidate))
    }

    @Test
    fun `adding a variable clears a ready result even when query bindings stay unchanged`() {
        val previousDefinition = definition(listOf("firstQuery"), listOf("region"))
        val candidateDefinition = definition(listOf("firstQuery"), listOf("region", "timezone"))
        val previous = draft(previousDefinition, mapOf("firstQuery" to firstRunId))
        val candidate = assertIs<DraftMutationResult.Applied>(
            previous.replaceDefinition(0, candidateDefinition, emptyMap(), READY_TEST_NOW.plusSeconds(1)),
        ).draft

        assertEquals(emptyMap(), candidate.readyResults)
        assertEquals(emptyMap(), retainDraftReadyResults(previous, candidate))
    }

    @Test
    fun `removing a variable clears a ready result even when query bindings stay unchanged`() {
        val previousDefinition = definition(listOf("firstQuery"), listOf("region", "timezone"))
        val candidateDefinition = definition(listOf("firstQuery"), listOf("region"))
        val previous = draft(previousDefinition, mapOf("firstQuery" to firstRunId))
        val candidate = assertIs<DraftMutationResult.Applied>(
            previous.replaceDefinition(0, candidateDefinition, emptyMap(), READY_TEST_NOW.plusSeconds(1)),
        ).draft

        assertEquals(emptyMap(), candidate.readyResults)
        assertEquals(emptyMap(), retainDraftReadyResults(previous, candidate))
    }

    @Test
    fun `ready promotion returns the exact current successful target IDs`() {
        val definition = definition(listOf("firstQuery"))
        val currentRun = queryRun(definition, "firstQuery", firstRunId, QueryRunState.SUCCEEDED)
        val lateOldRun = queryRun(definition, "firstQuery", secondRunId, QueryRunState.SUCCEEDED)

        assertEquals(
            mapOf("firstQuery" to firstRunId),
            resolveSuccessfulTargetQueryRuns(
                queryNames = setOf("firstQuery"),
                targetQueryRuns = mapOf("firstQuery" to firstRunId),
                queryRunsById = mapOf(currentRun.id to currentRun, lateOldRun.id to lateOldRun),
            ),
        )
    }

    @Test
    fun `a late old successful target is not promoted over a current running target`() {
        val definition = definition(listOf("firstQuery"))
        val currentRun = queryRun(definition, "firstQuery", firstRunId, QueryRunState.RUNNING)
        val lateOldRun = queryRun(definition, "firstQuery", secondRunId, QueryRunState.SUCCEEDED)

        assertNull(
            resolveSuccessfulTargetQueryRuns(
                queryNames = setOf("firstQuery"),
                targetQueryRuns = mapOf("firstQuery" to firstRunId),
                queryRunsById = mapOf(currentRun.id to currentRun, lateOldRun.id to lateOldRun),
            ),
        )
    }

    @Test
    fun `table auxiliary query participates in draft ready promotion and retention`() {
        fun tableDefinition(queryNames: List<String>): DashboardDefinition {
            val base = definition(queryNames)
            val auxiliary = queryNames.drop(1).joinToString(",") { "\"$it\"" }
            val widget = json.parseToJsonElement("""{
                "type":"TABLE","queryName":"${queryNames.first()}","columns":[{"field":"metric.order_total"}],
                "module":{"inputs":{"queries":[$auxiliary],"parameters":[],"variables":["region"]},
                "source":"export default defineTable({ render() { return {}; } });"}
            }""")
            return DashboardDefinitionDecoder.decode(JsonObject(base.document + ("widgets" to JsonObject(mapOf("chart" to widget)))))
        }
        val previousDefinition = tableDefinition(listOf("firstQuery"))
        val nextDefinition = tableDefinition(listOf("firstQuery", "secondQuery"))
        val targets = mapOf("firstQuery" to firstRunId, "secondQuery" to secondRunId)
        assertEquals(emptyMap(), retainDraftReadyResults(
            draft(previousDefinition, mapOf("firstQuery" to firstRunId)),
            draft(nextDefinition, targets),
        ))
        val primary = queryRun(nextDefinition, "firstQuery", firstRunId, QueryRunState.SUCCEEDED)
        val auxiliary = queryRun(nextDefinition, "secondQuery", secondRunId, QueryRunState.RUNNING)
        assertNull(resolveSuccessfulTargetQueryRuns(nextDefinition.widgets.getValue("chart").queryNames, targets, mapOf(primary.id to primary, auxiliary.id to auxiliary)))
        val succeeded = queryRun(nextDefinition, "secondQuery", secondRunId, QueryRunState.SUCCEEDED)
        assertEquals(targets, resolveSuccessfulTargetQueryRuns(nextDefinition.widgets.getValue("chart").queryNames, targets, mapOf(primary.id to primary, succeeded.id to succeeded)))
    }

    private fun draft(
        definition: DashboardDefinition,
        targetQueryRuns: Map<String, QueryRunId>,
    ): DashboardDraft {
        val base = DashboardDraft.start(
            workspaceId = workspaceId,
            dashboardId = dashboardId,
            definition = definition,
            parameterBindings = emptyMap(),
            startedAt = READY_TEST_NOW,
        )
        return base
            .withTargets(targetQueryRuns, READY_TEST_NOW)
            .withReadyResults(
                mapOf(
                    "chart" to DashboardDraftReadyResult(
                        authoringRevision = 0,
                        parameterBindings = emptyMap(),
                        variables = definition.variables.mapValues { (_, declaration) -> declaration.initial },
                        queryRuns = targetQueryRuns,
                    ),
                ),
                READY_TEST_NOW,
            )
    }

    private fun queryRun(
        definition: DashboardDefinition,
        queryName: String,
        id: QueryRunId,
        state: QueryRunState,
    ): QueryRun {
        val started = QueryRun.start(
            id = id,
            workspaceId = workspaceId,
            dashboardId = dashboardId,
            viewerSessionId = null,
            query = definition.query(queryName),
            resolvedQuery = DashboardResolvedQuery.Semantic((definition.query(queryName) as DashboardNamedQuery.Semantic).query),
            renderedSql = "select 1",
            outputColumnNames = listOf("value"),
            startedAt = READY_TEST_NOW,
            actorId = null,
            credentialSurface = null,
            agentClientId = null,
            outputColumnDisplays = null,
            reportingTimeZone = java.time.ZoneId.of("UTC"),
        )
        return when (state) {
            QueryRunState.RUNNING -> started
            QueryRunState.SUCCEEDED -> started.succeed(
                ArrowResultManifest("artifacts/$id", 1uL, "etag", 1uL, 1, "application/vnd.apache.arrow.file"),
                READY_TEST_NOW.plusSeconds(1),
            )
            QueryRunState.FAILED -> started.fail("FAILED", null, READY_TEST_NOW.plusSeconds(1))
            QueryRunState.CANCELLED -> started.cancel(READY_TEST_NOW.plusSeconds(1))
        }
    }

    private fun definition(widgetQueryNames: List<String>, variableNames: List<String> = listOf("region")): DashboardDefinition {
        val queryNamesJson = widgetQueryNames.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
        val variableDefinitionsJson = variableNames.joinToString(",\n") { variableName ->
            val initial = if (variableName == "region") "US" else "UTC"
            """                    "$variableName": { "type": "TEXT", "nullable": false, "initial": "$initial" }"""
        }
        val widgetVariablesJson = variableNames.joinToString(",") { "\"$it\"" }
        val queryDefinitionsJson = widgetQueryNames.joinToString(",\n") { queryName ->
            val metricName = when (queryName) {
                "firstQuery" -> "order_total"
                "secondQuery" -> "order_count"
                else -> error("unexpected ready-test query name: $queryName")
            }
            """                    "$queryName": { "type": "SEMANTIC", "connectorId": "0195f3b2-5c84-7a44-8cc4-3e9bf4fd10de", "query": {
                      "schemaVersion": 4, "metrics": ["$metricName"], "groupBys": [], "metricTimeRange": null,
                      "filters": [], "orderBy": [], "rowLimit": 100
                    } }"""
        }
        return DashboardDefinitionDecoder.decode(
            json.parseToJsonElement(
                """
                {
                  "schemaVersion": 1,
                  "meta": { "title": "Ready test" },
                  "parameters": {},
                  "variables": {
                    $variableDefinitionsJson
                  },
                  "queries": {
                    $queryDefinitionsJson
                  },
                  "widgets": { "chart": { "type": "CHART", "queryNames": $queryNamesJson, "module": {
                    "inputs": { "parameters": [], "variables": [$widgetVariablesJson] },
                    "source": "export default defineChart({ render() { return {}; } });"
                  } } },
                  "layout": { "sections": [{ "id": "main", "height": { "mode": "SCALED", "scale": 1.0 }, "root": {
                    "type": "WIDGET", "id": "chartItem", "widgetId": "chart"
                  } }] }
                }
                """.trimIndent(),
            ).jsonObject,
        )
    }
}
