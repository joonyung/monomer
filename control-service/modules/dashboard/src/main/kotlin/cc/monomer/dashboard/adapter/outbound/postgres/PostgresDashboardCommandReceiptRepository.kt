@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.adapter.outbound.postgres

import cc.monomer.connector.api.ConnectorId
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardSessionId
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.dashboard.application.CommandReceipt
import cc.monomer.dashboard.application.CommandReceiptResponse
import cc.monomer.dashboard.application.DashboardCommandReceiptRepository
import cc.monomer.dashboard.application.DashboardDefinitionDecoder
import cc.monomer.dashboard.domain.Dashboard
import cc.monomer.dashboard.domain.DashboardDraft
import cc.monomer.dashboard.domain.DashboardQueryType
import cc.monomer.dashboard.domain.DashboardResolvedQueryCodec
import cc.monomer.dashboard.domain.DashboardSession
import cc.monomer.dashboard.domain.QueryRun
import cc.monomer.dashboard.domain.QueryRunState
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.time.Instant
import javax.sql.DataSource
import kotlin.uuid.Uuid

class PostgresDashboardCommandReceiptRepository(
    dataSource: DataSource,
) : DashboardCommandReceiptRepository {
    private val jdbc = PostgresDashboardJdbc(dataSource)

    override fun lock(workspaceId: WorkspaceId, actorId: String?, idempotencyKey: String) {
        jdbc.withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT pg_catalog.pg_advisory_xact_lock(
                    pg_catalog.hashtextextended(jsonb_build_array(?::text, ?::text, ?::text)::text, 0)
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, workspaceId.toString())
                statement.setString(2, actorId)
                statement.setString(3, idempotencyKey)
                statement.executeQuery().use { resultSet -> check(resultSet.next()) }
            }
        }
    }

    override fun find(workspaceId: WorkspaceId, actorId: String?, idempotencyKey: String): CommandReceipt? =
        jdbc.queryOne(
            """
            SELECT workspace_id, actor_id, idempotency_key, command_kind, subject_id,
                   normalized_request, dashboard_id, session_id, query_run_ids, response_snapshot, created_at
            FROM dashboard.command_receipts
            WHERE workspace_id = ? AND actor_id IS NOT DISTINCT FROM ? AND idempotency_key = ?
            """.trimIndent(),
            { statement ->
                statement.setUuid(1, workspaceId.value)
                statement.setString(2, actorId)
                statement.setString(3, idempotencyKey)
            },
        ) { row ->
            CommandReceipt(
                workspaceId = WorkspaceId(row.getUuid("workspace_id")),
                actorId = row.getString("actor_id"),
                idempotencyKey = row.getString("idempotency_key"),
                commandKind = row.getString("command_kind"),
                subjectId = row.getString("subject_id"),
                normalizedRequest = dashboardRowJson.parseToJsonElement(row.getString("normalized_request")).jsonObject,
                dashboardId = row.getNullableUuid("dashboard_id")?.let(::DashboardId),
                sessionId = row.getNullableUuid("session_id")?.let(::DashboardSessionId),
                queryRunIds = decodeIdMap(row.getString("query_run_ids"), ::QueryRunId).values.toList(),
                response = decodeReceiptResponse(row.getString("response_snapshot")),
                createdAt = row.getInstant("created_at"),
            )
        }

    override fun insert(receipt: CommandReceipt): Boolean =
        jdbc.update(
            """
            INSERT INTO dashboard.command_receipts (
                workspace_id, actor_id, idempotency_key, command_kind, subject_id,
                normalized_request, dashboard_id, session_id, query_run_ids, response_snapshot, created_at
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?::jsonb, ?)
            ON CONFLICT (workspace_id, actor_id, idempotency_key) DO NOTHING
            """.trimIndent(),
        ) { statement ->
            statement.setUuid(1, receipt.workspaceId.value)
            statement.setString(2, receipt.actorId)
            statement.setString(3, receipt.idempotencyKey)
            statement.setString(4, receipt.commandKind)
            statement.setString(5, receipt.subjectId)
            statement.setString(6, receipt.normalizedRequest.toString())
            statement.setNullableUuid(7, receipt.dashboardId?.value)
            statement.setNullableUuid(8, receipt.sessionId?.value)
            statement.setString(9, encodeIdMap(receipt.queryRunIds.mapIndexed { index, id -> index.toString() to id }.toMap()))
            statement.setString(10, encodeReceiptResponse(receipt.response))
            statement.setInstant(11, receipt.createdAt)
        } == 1

    private fun encodeReceiptResponse(response: CommandReceiptResponse): String =
        when (response) {
            is CommandReceiptResponse.DashboardCreated -> buildJsonObject {
                put("kind", "DASHBOARD_CREATED")
                put("dashboard", encodeDashboard(response.dashboard))
            }
            is CommandReceiptResponse.Draft -> buildJsonObject {
                put("kind", "DRAFT")
                put("draft", encodeDraft(response.draft))
                put("queryRuns", JsonArray(response.queryRuns.map(::encodeQueryRun)))
            }
            is CommandReceiptResponse.DraftApplied -> buildJsonObject {
                put("kind", "DRAFT_APPLIED")
                put("dashboard", encodeDashboard(response.dashboard))
            }
            is CommandReceiptResponse.SessionResolution -> buildJsonObject {
                put("kind", "SESSION_RESOLUTION")
                put("variables", JsonObject(response.variables))
                put("queryRuns", idMapJson(response.queryRuns))
            }
            is CommandReceiptResponse.Session -> buildJsonObject {
                put("kind", "SESSION")
                put("session", encodeSession(response.session))
                put("queryRuns", JsonArray(response.queryRuns.map(::encodeQueryRun)))
            }
        }.toString()

    private fun decodeReceiptResponse(raw: String): CommandReceiptResponse {
        val node = dashboardRowJson.parseToJsonElement(raw).jsonObject
        return when (node.getValue("kind").jsonPrimitive.content) {
            "DASHBOARD_CREATED" -> CommandReceiptResponse.DashboardCreated(
                decodeDashboard(node.getValue("dashboard").jsonObject),
            )
            "DRAFT" -> CommandReceiptResponse.Draft(
                draft = decodeDraft(node.getValue("draft").jsonObject),
                queryRuns = node.getValue("queryRuns").jsonArray.map { decodeQueryRun(it.jsonObject) },
            )
            "DRAFT_APPLIED" -> CommandReceiptResponse.DraftApplied(
                decodeDashboard(node.getValue("dashboard").jsonObject),
            )
            "SESSION_RESOLUTION" -> CommandReceiptResponse.SessionResolution(
                node.getValue("variables").jsonObject, decodeIdMap(node.getValue("queryRuns").toString(), ::QueryRunId),
            )
            "SESSION" -> CommandReceiptResponse.Session(
                session = decodeSession(node.getValue("session").jsonObject),
                queryRuns = node.getValue("queryRuns").jsonArray.map { decodeQueryRun(it.jsonObject) },
            )
            else -> error("unknown dashboard command receipt response kind")
        }
    }

    private fun encodeDashboard(dashboard: Dashboard): JsonObject = buildJsonObject {
        put("id", dashboard.id.toString())
        put("workspaceId", dashboard.workspaceId.toString())
        put("definition", dashboard.definition.document)
        put("createdAt", dashboard.createdAt.toString())
        put("updatedAt", dashboard.updatedAt.toString())
        put("updatedBy", dashboard.updatedBy?.let(::JsonPrimitive) ?: JsonNull)
        put("credentialSurface", dashboard.credentialSurface?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("agentClientId", dashboard.agentClientId?.value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun decodeDashboard(node: JsonObject): Dashboard = Dashboard(
        id = DashboardId(Uuid.parse(node.getValue("id").jsonPrimitive.content)),
        workspaceId = WorkspaceId(Uuid.parse(node.getValue("workspaceId").jsonPrimitive.content)),
        definition = DashboardDefinitionDecoder.decode(node.getValue("definition").jsonObject),
        createdAt = Instant.parse(node.getValue("createdAt").jsonPrimitive.content),
        updatedAt = Instant.parse(node.getValue("updatedAt").jsonPrimitive.content),
        updatedBy = (node["updatedBy"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content,
        credentialSurface = (node["credentialSurface"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.let(cc.monomer.identity.api.CredentialSurface::valueOf),
        agentClientId = (node["agentClientId"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.let { cc.monomer.identity.api.AgentClientId(it) },
    )

    private fun encodeDraft(draft: DashboardDraft): JsonObject = buildJsonObject {
        put("workspaceId", draft.workspaceId.toString())
        put("dashboardId", draft.dashboardId.toString())
        put("definition", draft.definition.document)
        put("parameterBindings", dashboardRowJson.parseToJsonElement(encodeObject(draft.parameterBindings)))
        put("variables", dashboardRowJson.parseToJsonElement(encodeObject(draft.variables)))
        put("authoringRevision", draft.authoringRevision)
        put("targetQueryRuns", idMapJson(draft.targetQueryRuns))
        put("readyResults", draftReadyResultsJson(draft.readyResults))
        put("createdAt", draft.createdAt.toString())
        put("updatedAt", draft.updatedAt.toString())
        put("lastEditedAt", draft.lastEditedAt?.toString())
        put("lastEditedBy", draft.lastEditedBy)
        put("lastEditSurface", draft.lastEditSurface?.name)
        put("lastEditAgentClientId", draft.lastEditAgentClientId?.value)
    }

    private fun decodeDraft(node: JsonObject): DashboardDraft = DashboardDraft(
        workspaceId = WorkspaceId(Uuid.parse(node.getValue("workspaceId").jsonPrimitive.content)),
        dashboardId = DashboardId(Uuid.parse(node.getValue("dashboardId").jsonPrimitive.content)),
        definition = DashboardDefinitionDecoder.decode(node.getValue("definition").jsonObject),
        parameterBindings = node.getValue("parameterBindings").jsonObject,
        variables = node.getValue("variables").jsonObject.toMap(),
        authoringRevision = node.getValue("authoringRevision").jsonPrimitive.long,
        targetQueryRuns = decodeIdMap(node.getValue("targetQueryRuns").toString(), ::QueryRunId),
        readyResults = decodeDraftReadyResults(node.getValue("readyResults").toString()),
        createdAt = Instant.parse(node.getValue("createdAt").jsonPrimitive.content),
        updatedAt = Instant.parse(node.getValue("updatedAt").jsonPrimitive.content),
        lastEditedAt = node["lastEditedAt"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse),
        lastEditedBy = node["lastEditedBy"]?.jsonPrimitive?.contentOrNull,
        lastEditSurface = node["lastEditSurface"]?.jsonPrimitive?.contentOrNull?.let(cc.monomer.identity.api.CredentialSurface::valueOf),
        lastEditAgentClientId = node["lastEditAgentClientId"]?.jsonPrimitive?.contentOrNull?.let { cc.monomer.identity.api.AgentClientId(it) },
    )

    private fun encodeSession(session: DashboardSession): JsonObject = buildJsonObject {
        put("id", session.id.toString())
        put("workspaceId", session.workspaceId.toString())
        put("dashboardId", session.dashboardId.toString())
        put("definition", session.definition.document)
        put("parameterBindings", dashboardRowJson.parseToJsonElement(encodeObject(session.parameterBindings)))
        put("createdAt", session.createdAt.toString())
        put("updatedAt", session.updatedAt.toString())
    }

    private fun decodeSession(node: JsonObject): DashboardSession = DashboardSession(
        id = DashboardSessionId(Uuid.parse(node.getValue("id").jsonPrimitive.content)),
        workspaceId = WorkspaceId(Uuid.parse(node.getValue("workspaceId").jsonPrimitive.content)),
        dashboardId = DashboardId(Uuid.parse(node.getValue("dashboardId").jsonPrimitive.content)),
        definition = DashboardDefinitionDecoder.decode(node.getValue("definition").jsonObject),
        parameterBindings = node.getValue("parameterBindings").jsonObject,
        createdAt = Instant.parse(node.getValue("createdAt").jsonPrimitive.content),
        updatedAt = Instant.parse(node.getValue("updatedAt").jsonPrimitive.content),
    )

    private fun encodeQueryRun(queryRun: QueryRun): JsonObject = buildJsonObject {
        put("id", queryRun.id.toString())
        put("workspaceId", queryRun.workspaceId.toString())
        put("dashboardId", queryRun.dashboardId.toString())
        put("viewerSessionId", queryRun.viewerSessionId?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("queryName", queryRun.queryName)
        put("queryType", queryRun.queryType.name)
        put("connectorId", queryRun.connectorId.toString())
        put("resolvedQuery", DashboardResolvedQueryCodec.encode(queryRun.resolvedQuery))
        put("renderedSql", queryRun.renderedSql?.let(::JsonPrimitive) ?: JsonNull)

        put("outputColumnNames", JsonArray(queryRun.outputColumnNames.map(::JsonPrimitive)))
        put("outputColumnDisplays", queryRun.outputColumnDisplays?.let(cc.monomer.semantic.api.MetricDisplayJsonCodec::encodeColumns) ?: JsonNull)
        put("reportingTimeZone", queryRun.reportingTimeZone?.id?.let(::JsonPrimitive) ?: JsonNull)
        put("state", queryRun.state.name)
        put("result", queryRun.result?.let(::encodeManifestObject) ?: JsonNull)
        put("failureCode", queryRun.failureCode?.let(::JsonPrimitive) ?: JsonNull)
        put("failureMessage", queryRun.failureMessage?.let(::JsonPrimitive) ?: JsonNull)
        put("createdAt", queryRun.createdAt.toString())
        put("updatedAt", queryRun.updatedAt.toString())
        put("actorId", queryRun.actorId?.let(::JsonPrimitive) ?: JsonNull)
        put("credentialSurface", queryRun.credentialSurface?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("agentClientId", queryRun.agentClientId?.value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun decodeQueryRun(node: JsonObject): QueryRun = QueryRun(
        id = QueryRunId(Uuid.parse(node.getValue("id").jsonPrimitive.content)),
        workspaceId = WorkspaceId(Uuid.parse(node.getValue("workspaceId").jsonPrimitive.content)),
        dashboardId = DashboardId(Uuid.parse(node.getValue("dashboardId").jsonPrimitive.content)),
        viewerSessionId = (node["viewerSessionId"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.let { DashboardSessionId(Uuid.parse(it)) },
        queryName = node.getValue("queryName").jsonPrimitive.content,
        queryType = DashboardQueryType.valueOf(node.getValue("queryType").jsonPrimitive.content),
        connectorId = ConnectorId(Uuid.parse(node.getValue("connectorId").jsonPrimitive.content)),
        resolvedQuery = DashboardResolvedQueryCodec.decode(DashboardQueryType.valueOf(node.getValue("queryType").jsonPrimitive.content), node.getValue("resolvedQuery").jsonObject),
        renderedSql = (node["renderedSql"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content,

        outputColumnNames = node.getValue("outputColumnNames").jsonArray.map { it.jsonPrimitive.content },
        state = QueryRunState.valueOf(node.getValue("state").jsonPrimitive.content),
        result = (node["result"] as? JsonObject)?.let(::decodeManifest),
        failureCode = (node["failureCode"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content,
        failureMessage = (node["failureMessage"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content,
        createdAt = Instant.parse(node.getValue("createdAt").jsonPrimitive.content),
        updatedAt = Instant.parse(node.getValue("updatedAt").jsonPrimitive.content),
        actorId = (node["actorId"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content,
        credentialSurface = (node["credentialSurface"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.let(cc.monomer.identity.api.CredentialSurface::valueOf),
        agentClientId = (node["agentClientId"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.let { cc.monomer.identity.api.AgentClientId(it) },
        outputColumnDisplays = node["outputColumnDisplays"]?.takeUnless { it == JsonNull }?.let(cc.monomer.semantic.api.MetricDisplayJsonCodec::decodeColumns),
        reportingTimeZone = (node["reportingTimeZone"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.let(java.time.ZoneId::of),
    )
}
