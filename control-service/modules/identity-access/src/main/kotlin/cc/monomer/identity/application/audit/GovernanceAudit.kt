package cc.monomer.identity.application.audit

import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.model.AuditActorKind
import cc.monomer.identity.domain.model.AuditLogEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

data class GovernanceAudit(
    val requestId: String,
    val surface: String,
    val clientId: String,
    val organizationId: OrganizationId,
) {
    init {
        require(requestId.isNotBlank())
        require(surface == "AGENT_REST" || surface == "AGENT_MCP")
        require(clientId.isNotBlank())
    }
}

fun AuditLogEntry.withGovernanceAudit(audit: GovernanceAudit?): AuditLogEntry {
    if (audit == null) return this
    require(organizationId == null || organizationId == audit.organizationId)
    return copy(
        organizationId = audit.organizationId,
        requestId = audit.requestId,
        actorKind = AuditActorKind.AGENT,
        actorRef = audit.clientId,
        details = JsonObject(Json.parseToJsonElement(details).jsonObject +
            ("surface" to JsonPrimitive(audit.surface))).toString(),
    )
}
