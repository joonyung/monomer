package cc.monomer.pages.domain.audit

import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageActorKind
import java.time.Instant
import kotlin.uuid.Uuid

enum class AuditActorKind {
    USER,
    AGENT,
}

enum class PagesAuditAction(val value: String, val resourceType: String) {
    FOLDER_CREATE("pages.folder.create", "page"),
    DASHBOARD_CREATE("pages.dashboard.create", "page"),
    DOCUMENT_CREATE("pages.document.create", "page"),
    CONTENT_VERSION_FINALIZED("pages.content.version.finalize", "page"),
    NAVIGATION_UPDATE("pages.navigation.update", "page"),
    PAGE_DELETE("pages.page.delete", "page"),
    PLACEMENTS_PATCH("pages.placements.patch", "pages_directory"),
}

data class AuditActorContext(
    val organizationId: Uuid,
    val principalId: Uuid,
    val actorRef: String,
) {
    init {
        require(actorRef.isNotBlank()) { "actorRef must not be blank" }
        require(actorRef.length <= 200) { "actorRef must not exceed 200 characters" }
    }
}

data class PagesAuditDetails(
    val pageKind: String?,
    val appliedOperationCount: Int?,
    val content: PageContentAuditDetails?,
    val cause: String?,
    val dashboardId: Uuid?,
) {
    init {
        require((cause == null) == (dashboardId == null))
        require(cause == null || (cause == "DASHBOARD_DELETED" && pageKind == "DASHBOARD"))
        require(pageKind == null || pageKind in setOf("FOLDER", "DOCUMENT", "DASHBOARD")) {
            "pageKind must be a supported Page kind"
        }
        require(appliedOperationCount == null || appliedOperationCount in 1..100) {
            "appliedOperationCount must be between 1 and 100"
        }
    }
}

data class PageContentAuditDetails(
    val pageId: Uuid,
    val versionNo: Int,
    val format: String,
    val contentHash: String,
    val parentPageId: Uuid?,
) {
    init {
        require(versionNo > 0) { "versionNo must be positive" }
        require(format in setOf("MARKDOWN", "NOTEBOOK", "HTML")) { "format is invalid" }
        require(contentHash.matches(Regex("[0-9a-f]{64}"))) { "contentHash must be a lowercase SHA-256 value" }
    }
}

data class PagesAuditEvent(
    val eventId: Uuid,
    val workspaceId: Uuid,
    val organizationId: Uuid,
    val actorPrincipalId: Uuid,
    val actorKind: AuditActorKind,
    val actorRef: String,
    val action: PagesAuditAction,
    val resourceId: Uuid,
    val requestId: String,
    val occurredAt: Instant,
    val details: PagesAuditDetails,
) {
    init {
        require(actorRef.isNotBlank()) { "actorRef must not be blank" }
        require(actorRef.length <= 200) { "actorRef must not exceed 200 characters" }
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(requestId.length <= 200) { "requestId must not exceed 200 characters" }
    }

    companion object {
        fun create(
            workspaceId: Uuid,
            actor: PageActor,
            actorContext: AuditActorContext,
            action: PagesAuditAction,
            resourceId: Uuid,
            requestId: String,
            occurredAt: Instant,
            details: PagesAuditDetails,
        ): PagesAuditEvent = PagesAuditEvent(
            eventId = Uuid.generateV7(),
            workspaceId = workspaceId,
            organizationId = actorContext.organizationId,
            actorPrincipalId = actorContext.principalId,
            actorKind = when (actor.kind) {
                PageActorKind.USER -> AuditActorKind.USER
                PageActorKind.AGENT -> AuditActorKind.AGENT
                PageActorKind.SYSTEM -> error("SYSTEM actors cannot enter the Pages audit records")
            },
            actorRef = actorContext.actorRef,
            action = action,
            resourceId = resourceId,
            requestId = requestId,
            occurredAt = occurredAt,
            details = details,
        )
    }
}

interface PagesAuditRepository {
    fun appendWithinTx(event: PagesAuditEvent)
}
