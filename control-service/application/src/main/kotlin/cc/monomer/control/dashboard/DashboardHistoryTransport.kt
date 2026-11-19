@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.dashboard.api.*
import cc.monomer.control.agentapp.AgentAppResponse
import cc.monomer.control.agentapp.responses
import cc.monomer.identity.api.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.Base64
import kotlin.uuid.Uuid
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

data class DashboardDefinitionVersionResponse(
    val changeId: String,
    val action: String,
    val definition: JsonNode,
    val occurredAt: String,
    val actor: DashboardActorResponse?,
    val credentialSurface: String?,
    val agentApp: AgentAppResponse?,
    val restorable: Boolean,
)

data class DashboardHistoryResponse(
    val definitionId: String,
    val name: String,
    val deleted: Boolean,
    val changes: List<DashboardDefinitionVersionResponse>,
    val nextCursor: String?,
)

internal suspend fun DashboardOwnerDefinitionChange.historyResponse(names: PrincipalDisplayNames, apps: AgentAppDisplays, mapper: ObjectMapper): DashboardDefinitionVersionResponse {
    val actors = names.read(listOfNotNull(actorId?.let(Uuid::parse)).toSet())
    val agentApps = apps.responses(listOf(agentClientId))
    return DashboardDefinitionVersionResponse(changeId.toString(), action.name, mapper.readTree(definition.toString()), occurredAt.toString(),
        actorId?.let { DashboardActorResponse(it, actors[Uuid.parse(it)]) }, credentialSurface?.name, agentClientId?.let(agentApps::get), restorable)
}

internal fun encodeDashboardHistoryCursor(cursor: DashboardHistoryCursor): String = Base64.getUrlEncoder().withoutPadding().encodeToString(historyMapper.writeValueAsBytes(
    linkedMapOf("workspaceId" to cursor.workspaceId.toString(), "dashboardId" to cursor.dashboardId.toString(), "occurredAt" to cursor.occurredAt.toString(), "changeId" to cursor.changeId.toString()),
))

internal fun decodeDashboardHistoryCursor(raw: String?, workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardHistoryCursor? {
    if (raw == null) return null
    return try {
        require(raw.length <= 2048)
        val node = historyMapper.readTree(Base64.getUrlDecoder().decode(raw))
        require(node.isObject && node.size() == 4 && node.path("workspaceId").textValue() == workspaceId.toString() && node.path("dashboardId").textValue() == dashboardId.toString())
        val cursor = DashboardHistoryCursor(workspaceId, dashboardId, Instant.parse(node.path("occurredAt").textValue()), Uuid.parse(node.path("changeId").textValue()))
        require(encodeDashboardHistoryCursor(cursor) == raw)
        cursor
    } catch (_: Exception) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The history cursor is invalid for this dashboard.")
    }
}

private val historyMapper = jacksonObjectMapper()
