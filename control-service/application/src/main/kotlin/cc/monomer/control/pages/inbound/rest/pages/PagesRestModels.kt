package cc.monomer.control.pages.inbound.rest.pages

import cc.monomer.pages.application.PagesDirectoryProjection
import cc.monomer.pages.domain.Page
import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageDirectoryChange
import cc.monomer.pages.domain.PageDirectoryChangeType
import cc.monomer.pages.domain.PageId
import cc.monomer.pages.domain.PagePlacement
import cc.monomer.pages.domain.content.PageContentFormat

data class PagesTreeResponse(
    val workspaceId: String,
    val revision: Long,
    val pages: List<PageResponse>,
    val placements: List<PagePlacementResponse>,
    val updatedAt: String,
    val updatedBy: PageActorResponse,
)

data class PageResponse(
    val pageId: String,
    val kind: String,
    val documentFormat: String?,
    val dashboardId: String?,
    val title: String?,
    val displayTitle: String,
    val description: String?,
    val createdAt: String,
    val updatedAt: String,
)

data class PagePlacementResponse(
    val pageId: String,
    val children: List<PagePlacementResponse>,
)

data class PageActorResponse(
    val kind: String,
    val id: String,
)

data class PageDetailResponse(
    val page: PageResponse,
    val path: List<String>,
    val children: List<String>,
    val revision: Long,
)

data class PageDirectoryChangeResponse(
    val type: String,
    val pageId: String?,
    val appliedOperationCount: Int?,
)

data class PageMutationResponse(
    val result: String,
    val snapshot: PagesTreeResponse,
    val change: PageDirectoryChangeResponse?,
)

data class CreatePageRequest(
    val kind: String,
    val title: String?,
    val description: String?,
    val uploadRef: String?,
    val dashboardId: String?,
    val parentPageId: String?,
    val index: Int?,
    val baseRevision: Long,
    val requestId: String,
)

data class PatchPagesTreeRequest(
    val baseRevision: Long,
    val requestId: String,
    val patch: List<Map<String, Any?>>,
)

data class UpdatePageRequest(
    val title: String?,
    val description: String?,
    val baseRevision: Long,
    val requestId: String,
)

data class DeletePageRequest(
    val baseRevision: Long,
    val requestId: String,
)

internal fun PagesDirectoryProjection.toResponse(): PagesTreeResponse = PagesTreeResponse(
    workspaceId = snapshot.workspaceId.toString(),
    revision = snapshot.revision.value,
    pages = snapshot.pages.map { page -> page.toResponse(displayTitle(page.id), documentFormats[page.id]) },
    placements = snapshot.placements.map(PagePlacement::toResponse),
    updatedAt = snapshot.updatedAt.toString(),
    updatedBy = snapshot.updatedBy.toResponse(),
)

internal fun PagesDirectoryProjection.pageDetail(pageId: PageId): PageDetailResponse? {
    val page = snapshot.pages.firstOrNull { candidate -> candidate.id == pageId } ?: return null
    val placement = snapshot.placements.findPlacement(pageId) ?: return null
    return PageDetailResponse(
        page = page.toResponse(displayTitle(pageId), documentFormats[pageId]),
        path = snapshot.placements.findPath(pageId, emptyList())?.map(PageId::toString) ?: return null,
        children = placement.children.map { child -> child.pageId.toString() },
        revision = snapshot.revision.value,
    )
}

internal fun Page.toResponse(displayTitle: String, documentFormat: PageContentFormat?): PageResponse = PageResponse(
    pageId = id.toString(),
    kind = kind.name,
    documentFormat = documentFormat?.name,
    dashboardId = dashboardId?.toString(),
    title = navigationMetadata.title,
    displayTitle = displayTitle,
    description = navigationMetadata.description,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

private fun PagePlacement.toResponse(): PagePlacementResponse = PagePlacementResponse(
    pageId = pageId.toString(),
    children = children.map(PagePlacement::toResponse),
)

private fun PageActor.toResponse(): PageActorResponse = PageActorResponse(kind.name, id)

internal fun PageDirectoryChange.toResponse(): PageDirectoryChangeResponse = when (this) {
    is PageDirectoryChange.FolderCreated -> PageDirectoryChangeResponse(
        type = type.wireValue(),
        pageId = pageId.toString(),
        appliedOperationCount = null,
    )
    is PageDirectoryChange.DocumentPageCreated -> PageDirectoryChangeResponse(
        type = type.wireValue(),
        pageId = pageId.toString(),
        appliedOperationCount = null,
    )
    is PageDirectoryChange.DashboardPageCreated -> PageDirectoryChangeResponse(
        type = type.wireValue(),
        pageId = pageId.toString(),
        appliedOperationCount = null,
    )
    is PageDirectoryChange.NavigationMetadataUpdated -> PageDirectoryChangeResponse(
        type = type.wireValue(),
        pageId = pageId.toString(),
        appliedOperationCount = null,
    )
    is PageDirectoryChange.PageDeleted -> PageDirectoryChangeResponse(
        type = type.wireValue(),
        pageId = pageId.toString(),
        appliedOperationCount = null,
    )
    is PageDirectoryChange.PlacementsPatched -> PageDirectoryChangeResponse(
        type = type.wireValue(),
        pageId = null,
        appliedOperationCount = appliedOperationCount,
    )
}

internal fun PageDirectoryChangeType.wireValue(): String = when (this) {
    PageDirectoryChangeType.FOLDER_CREATED -> "folder-created"
    PageDirectoryChangeType.DASHBOARD_PAGE_CREATED -> "dashboard-page-created"
    PageDirectoryChangeType.DOCUMENT_PAGE_CREATED -> "document-page-created"
    PageDirectoryChangeType.NAVIGATION_METADATA_UPDATED -> "navigation-metadata-updated"
    PageDirectoryChangeType.PAGE_DELETED -> "page-deleted"
    PageDirectoryChangeType.PLACEMENTS_PATCHED -> "placements-patched"
}

private fun List<PagePlacement>.findPlacement(pageId: PageId): PagePlacement? {
    for (placement in this) {
        if (placement.pageId == pageId) return placement
        placement.children.findPlacement(pageId)?.let { return it }
    }
    return null
}

private fun List<PagePlacement>.findPath(pageId: PageId, prefix: List<PageId>): List<PageId>? {
    for (placement in this) {
        val path = prefix + placement.pageId
        if (placement.pageId == pageId) return path
        placement.children.findPath(pageId, path)?.let { return it }
    }
    return null
}
