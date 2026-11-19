package cc.monomer.identity.domain.model

import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.PrincipalId
import cc.monomer.identity.domain.id.WorkspaceId
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Append-only audit event. Every mutation in this service writes one row in the same TX
 * as the domain change.
 *
 * Either [workspaceId] or [organizationId] (or both) is set. Workspace-scoped events carry both;
 * organization-level events carry only [organizationId].
 */
data class AuditLogEntry(
    val id: Uuid,
    val workspaceId: WorkspaceId?,
    val organizationId: OrganizationId?,
    val actorId: PrincipalId,
    val action: String,
    val resourceType: String?,
    val resourceId: Uuid?,
    val requestId: String?,
    val actorKind: AuditActorKind?,
    val actorRef: String?,
    val details: String,
    val occurredAt: Instant,
)

enum class AuditActorKind {
    USER,
    AGENT,
}
