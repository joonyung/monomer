package cc.monomer.pages.adapter.outbound.postgres.audit

import com.fasterxml.jackson.databind.ObjectMapper
import cc.monomer.pages.domain.audit.PagesAuditRepository
import cc.monomer.pages.domain.audit.PagesAuditEvent
import kotlin.uuid.toJavaUuid
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager

class PostgresPagesAuditRepository(
    @param:Qualifier("pagesJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) : PagesAuditRepository {

    override fun appendWithinTx(event: PagesAuditEvent) {
        val dataSource = checkNotNull(jdbc.jdbcTemplate.dataSource)
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Pages audit append requires an active owner transaction"
        }
        check(TransactionSynchronizationManager.hasResource(dataSource)) {
            "Pages audit append requires the transaction bound to the exact DataSource"
        }
        val inserted = jdbc.update(
            """INSERT INTO pages_relocation.audit_events
               (event_id, aggregate_type, aggregate_id, event_type, payload, created_at)
               VALUES (:eventId, :aggregateType, :aggregateId, :eventType, CAST(:payload AS JSONB), :createdAt)""",
            mapOf(
                "eventId" to event.eventId.toJavaUuid(),
                "aggregateType" to event.action.resourceType,
                "aggregateId" to event.resourceId.toJavaUuid(),
                "eventType" to AUDIT_REQUESTED_EVENT_TYPE,
                "payload" to objectMapper.writeValueAsString(event.toPayload()),
                "createdAt" to event.occurredAt.toEpochMilli(),
            ),
        )
        check(inserted == 1) { "Pages audit insert failed" }
    }

    private fun PagesAuditEvent.toPayload(): PagesAuditEventPayload = PagesAuditEventPayload(
        eventId = eventId.toString(),
        workspaceId = workspaceId.toString(),
        organizationId = organizationId.toString(),
        actorPrincipalId = actorPrincipalId.toString(),
        actorKind = actorKind.name,
        actorRef = actorRef,
        action = action.value,
        resourceType = action.resourceType,
        resourceId = resourceId.toString(),
        requestId = requestId,
        occurredAtEpochMs = occurredAt.toEpochMilli(),
        details = AuditDetailsPayload(
            pageKind = details.pageKind,
            appliedOperationCount = details.appliedOperationCount,
            pageId = details.content?.pageId?.toString(),
            versionNo = details.content?.versionNo,
            format = details.content?.format,
            contentHash = details.content?.contentHash,
            parentPageId = details.content?.parentPageId?.toString(),
            cause = details.cause,
            dashboardId = details.dashboardId?.toString(),
        ),
    )

    private data class PagesAuditEventPayload(
        val eventId: String,
        val workspaceId: String,
        val organizationId: String,
        val actorPrincipalId: String,
        val actorKind: String,
        val actorRef: String,
        val action: String,
        val resourceType: String,
        val resourceId: String,
        val requestId: String,
        val occurredAtEpochMs: Long,
        val details: AuditDetailsPayload,
    )

    private data class AuditDetailsPayload(
        val pageKind: String?,
        val appliedOperationCount: Int?,
        val pageId: String?,
        val versionNo: Int?,
        val format: String?,
        val contentHash: String?,
        val parentPageId: String?,
        val cause: String?,
        val dashboardId: String?,
    )

    private companion object {
        const val AUDIT_REQUESTED_EVENT_TYPE = "AUDIT_REQUESTED"
    }
}
