@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.adapter.outbound.postgres

import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardSessionId
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.dashboard.application.DashboardDefinitionDecoder
import cc.monomer.dashboard.domain.Dashboard
import cc.monomer.dashboard.domain.DashboardDraft
import cc.monomer.dashboard.domain.DashboardDraftReadyResult
import cc.monomer.dashboard.domain.DashboardSession
import cc.monomer.execution.api.ArrowResultManifest
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.sql.ResultSet
import kotlin.uuid.Uuid

internal val dashboardRowJson = Json { explicitNulls = true }

internal fun readDashboard(row: ResultSet): Dashboard = Dashboard(
    id = DashboardId(row.getUuid("dashboard_id")),
    workspaceId = WorkspaceId(row.getUuid("workspace_id")),
    definition = DashboardDefinitionDecoder.decode(row.getString("definition")),
    createdAt = row.getInstant("created_at"),
    updatedAt = row.getInstant("updated_at"),
    updatedBy = row.getString("updated_by"),
    credentialSurface = row.getString("credential_surface")?.let(cc.monomer.identity.api.CredentialSurface::valueOf),
    agentClientId = row.getString("agent_client_id")?.let { cc.monomer.identity.api.AgentClientId(it) },
)

internal fun readSession(row: ResultSet): DashboardSession {
    val definition = DashboardDefinitionDecoder.decode(
        dashboardRowJson.parseToJsonElement(row.getString("definition_snapshot")).jsonObject,
    )
    return DashboardSession(
        id = DashboardSessionId(row.getUuid("session_id")),
        workspaceId = WorkspaceId(row.getUuid("workspace_id")),
        dashboardId = DashboardId(row.getUuid("dashboard_id")),
        definition = definition,
        parameterBindings = dashboardRowJson.parseToJsonElement(row.getString("parameter_bindings")).jsonObject,
        createdAt = row.getInstant("created_at"),
        updatedAt = row.getInstant("updated_at"),
    )
}

internal fun readDraft(row: ResultSet): DashboardDraft {
    val definition = DashboardDefinitionDecoder.decode(dashboardRowJson.parseToJsonElement(row.getString("definition")).jsonObject)
    return DashboardDraft(
        workspaceId = WorkspaceId(row.getUuid("workspace_id")),
        dashboardId = DashboardId(row.getUuid("dashboard_id")),
        definition = definition,
        parameterBindings = dashboardRowJson.parseToJsonElement(row.getString("parameter_bindings")).jsonObject,
        variables = dashboardRowJson.parseToJsonElement(row.getString("variables")).jsonObject.toMap(),
        authoringRevision = row.getLong("authoring_revision"),
        targetQueryRuns = decodeIdMap(row.getString("target_query_runs"), ::QueryRunId),
        readyResults = decodeDraftReadyResults(row.getString("ready_results")),
        createdAt = row.getInstant("created_at"),
        updatedAt = row.getInstant("updated_at"),
        lastEditedAt = row.getTimestamp("last_edited_at")?.toInstant(),
        lastEditedBy = row.getString("last_edited_by"),
        lastEditSurface = row.getString("last_edit_surface")?.let(cc.monomer.identity.api.CredentialSurface::valueOf),
        lastEditAgentClientId = row.getString("last_edit_agent_client_id")?.let { cc.monomer.identity.api.AgentClientId(it) },
    )
}

internal fun encodeObject(values: Map<String, JsonElement>): String =
    buildJsonObject { values.forEach { (key, value) -> put(key, value) } }.toString()

internal fun idMapJson(values: Map<String, QueryRunId>): JsonObject =
    buildJsonObject { values.forEach { (key, value) -> put(key, value.toString()) } }

internal fun encodeIdMap(values: Map<String, QueryRunId>): String =
    idMapJson(values).toString()

internal fun decodeIdMap(raw: String, factory: (Uuid) -> QueryRunId): Map<String, QueryRunId> =
    dashboardRowJson.parseToJsonElement(raw).jsonObject.mapValues { (_, value) -> factory(Uuid.parse(value.jsonPrimitive.content)) }

internal fun encodeDraftReadyResults(values: Map<String, DashboardDraftReadyResult>): String =
    draftReadyResultsJson(values).toString()

internal fun draftReadyResultsJson(values: Map<String, DashboardDraftReadyResult>): JsonObject = buildJsonObject {
    values.forEach { (widgetId, ready) ->
        putJsonObject(widgetId) {
            put("authoringRevision", ready.authoringRevision)
            put("parameterBindings", buildJsonObject { ready.parameterBindings.forEach { (key, value) -> put(key, value) } })
            put("variables", buildJsonObject { ready.variables.forEach { (key, value) -> put(key, value) } })
            put("queryRuns", idMapJson(ready.queryRuns))
        }
    }
}

internal fun decodeDraftReadyResults(raw: String): Map<String, DashboardDraftReadyResult> =
    dashboardRowJson.parseToJsonElement(raw).jsonObject.mapValues { (_, value) ->
        val node = value.jsonObject
        DashboardDraftReadyResult(
            authoringRevision = node.getValue("authoringRevision").jsonPrimitive.long,
            parameterBindings = node.getValue("parameterBindings").jsonObject,
            variables = node.getValue("variables").jsonObject.toMap(),
            queryRuns = decodeIdMap(node.getValue("queryRuns").toString(), ::QueryRunId),
        )
    }

internal fun encodeManifest(manifest: ArrowResultManifest): String =
    encodeManifestObject(manifest).toString()

internal fun encodeManifestObject(manifest: ArrowResultManifest): JsonObject = buildJsonObject {
    put("objectKey", manifest.objectKey)
    put("byteSize", manifest.byteSize.toString())
    put("objectEtag", manifest.objectEtag)
    put("rowCount", manifest.rowCount.toString())
    put("columnCount", manifest.columnCount)
    put("mediaType", manifest.mediaType)
}

internal fun decodeManifest(raw: String): ArrowResultManifest = decodeManifest(dashboardRowJson.parseToJsonElement(raw).jsonObject)

internal fun decodeManifest(node: JsonObject): ArrowResultManifest = ArrowResultManifest(
    objectKey = node.getValue("objectKey").jsonPrimitive.content,
    byteSize = node.getValue("byteSize").jsonPrimitive.content.toULong(),
    objectEtag = node.getValue("objectEtag").jsonPrimitive.content,
    rowCount = node.getValue("rowCount").jsonPrimitive.content.toULong(),
    columnCount = node.getValue("columnCount").jsonPrimitive.int,
    mediaType = node.getValue("mediaType").jsonPrimitive.content,
)
