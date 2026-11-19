package cc.monomer.identity.application.audit

import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.PrincipalId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.AuditLogEntry
import java.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Constructs [AuditLogEntry] rows. Centralized so every mutation UseCase produces
 * consistently-shaped events without duplicating UUID v7 / clock plumbing.
 */
@OptIn(ExperimentalUuidApi::class)
class AuditEventFactory(private val clock: Clock) {

    fun build(
        actorId: PrincipalId,
        action: String,
        workspaceId: WorkspaceId?,
        organizationId: OrganizationId?,
        resourceType: String?,
        resourceId: Uuid?,
        details: String,
    ): AuditLogEntry = AuditLogEntry(
        id = Uuid.generateV7(),
        workspaceId = workspaceId,
        organizationId = organizationId,
        actorId = actorId,
        action = action,
        resourceType = resourceType,
        resourceId = resourceId,
        requestId = null,
        actorKind = null,
        actorRef = null,
        details = details,
        occurredAt = clock.instant(),
    )
}
