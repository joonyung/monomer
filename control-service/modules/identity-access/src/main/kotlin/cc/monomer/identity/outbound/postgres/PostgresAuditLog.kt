package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.domain.model.AuditLogEntry
import cc.monomer.identity.domain.port.AuditLogRepository
import java.sql.Timestamp
import kotlin.uuid.toJavaUuid
import org.postgresql.util.PGobject
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PostgresAuditLogRepository(
    @param:Qualifier("postgresJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) : AuditLogRepository {

    override fun insert(entry: AuditLogEntry) {
        val detailsJson = PGobject().apply {
            type = "jsonb"
            value = entry.details
        }
        jdbc.update(
            """
            INSERT INTO identity_runtime.audit_log
                (id, workspace_id, organization_id, actor_id, action, resource_type, resource_id, request_id, actor_kind, actor_ref, details, occurred_at)
            VALUES
                (:id, :workspaceId, :organizationId, :actorId, :action, :resourceType, :resourceId, :requestId, :actorKind, :actorRef, :details, :occurredAt)
            """.trimIndent(),
            mapOf(
                "id" to entry.id.toJavaUuid(),
                "workspaceId" to entry.workspaceId?.value?.toJavaUuid(),
                "organizationId" to entry.organizationId?.value?.toJavaUuid(),
                "actorId" to entry.actorId.value.toJavaUuid(),
                "action" to entry.action,
                "resourceType" to entry.resourceType,
                "resourceId" to entry.resourceId?.toJavaUuid(),
                "requestId" to entry.requestId,
                "actorKind" to entry.actorKind?.name,
                "actorRef" to entry.actorRef,
                "details" to detailsJson,
                "occurredAt" to Timestamp.from(entry.occurredAt),
            ),
        )
    }
}
