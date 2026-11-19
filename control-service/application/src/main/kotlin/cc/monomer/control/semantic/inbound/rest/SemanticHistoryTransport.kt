@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.semantic.inbound.rest

import cc.monomer.control.agentapp.AgentAppResponse
import cc.monomer.control.agentapp.responses
import cc.monomer.identity.api.AgentAppDisplays
import cc.monomer.identity.api.PrincipalDisplayNames
import cc.monomer.semantic.api.SemanticDefinitionHistory
import cc.monomer.semantic.api.SemanticDefinitionSnapshot
import cc.monomer.semantic.api.SemanticHistoryBoundary
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.Base64
import kotlin.uuid.Uuid
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

data class SemanticDefinitionHistoryResponseDto<D>(
    val definitionId: String,
    val name: String,
    val deleted: Boolean,
    val changes: List<SemanticDefinitionVersionResponseDto<D>>,
    val nextCursor: String?,
)

data class SemanticDefinitionVersionResponseDto<D>(
    val changeId: String,
    val action: String,
    val definition: D,
    val occurredAt: String,
    val actor: SemanticActorDto?,
    val credentialSurface: String?,
    val agentApp: AgentAppResponse?,
    val restorable: Boolean,
)

internal suspend fun <D> SemanticDefinitionHistory.toHttp(
    displayNames: PrincipalDisplayNames,
    agentApps: AgentAppDisplays,
    scope: String,
    definition: (SemanticDefinitionSnapshot) -> D,
): SemanticDefinitionHistoryResponseDto<D> {
    val names = displayNames.read(changes.mapNotNull { it.actorId?.value }.toSet())
    val apps = agentApps.responses(changes.map { it.agentClientId })
    return SemanticDefinitionHistoryResponseDto(
        definitionId.toString(), name, deleted,
        changes.map { version -> SemanticDefinitionVersionResponseDto(
            version.changeId.toString(), version.action.name, definition(version.definition), version.occurredAt.toString(),
            version.actorId?.let { SemanticActorDto(it.toString(), names[it.value]) },
            version.credentialSurface?.name, version.agentClientId?.let(apps::get),
            version.restorable,
        ) },
        next?.let { encodeHistoryCursor(it, scope) },
    )
}

internal fun requireHistoryLimit(limit: Int) {
    if (limit !in 1..100) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "History limit must be between 1 and 100.")
}

internal fun encodeHistoryCursor(boundary: SemanticHistoryBoundary, scope: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(historyCursorMapper.writeValueAsBytes(
        linkedMapOf("scope" to scope, "occurredAt" to boundary.occurredAt.toString(), "changeId" to boundary.changeId.toString()),
    ))

internal fun decodeHistoryCursor(cursor: String?, scope: String): SemanticHistoryBoundary? {
    if (cursor == null) return null
    return try {
        require(cursor.length <= 2048)
        val node = historyCursorMapper.readTree(Base64.getUrlDecoder().decode(cursor))
        require(node.isObject && node.size() == 3 && node.path("scope").textValue() == scope)
        val boundary = SemanticHistoryBoundary(Instant.parse(node.path("occurredAt").textValue()), Uuid.parse(node.path("changeId").textValue()))
        require(encodeHistoryCursor(boundary, scope) == cursor)
        boundary
    } catch (_: Exception) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The history cursor is invalid for this definition.")
    }
}

private val historyCursorMapper = jacksonObjectMapper()
