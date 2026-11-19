package cc.monomer.pages.application

import cc.monomer.pages.api.*
import cc.monomer.pages.application.content.PageContentAttachmentOutcome
import cc.monomer.pages.application.content.PageContentViewOutcome
import cc.monomer.pages.application.content.PageContentViewerUseCase
import cc.monomer.pages.domain.PageId
import cc.monomer.pages.domain.PagePlacement
import java.io.ByteArrayOutputStream
import kotlin.uuid.Uuid

class PagesCurrentReader(
    private val directory: PagesDirectoryUseCase,
    private val viewer: PageContentViewerUseCase,
) : PagesCurrentReadPort {
    override suspend fun tree(workspaceId: Uuid): CurrentPagesTree {
        val projection = directory.getProjection(workspaceId)
        return CurrentPagesTree(
            projection.snapshot.revision.value,
            projection.snapshot.pages.map { page ->
                CurrentPage(page.id.value, page.kind.name, projection.documentFormats[page.id]?.name,
                    page.dashboardId, page.navigationMetadata.title, projection.displayTitle(page.id),
                    page.navigationMetadata.description)
            },
            projection.snapshot.placements.map(::placement),
        )
    }

    override suspend fun latestContentVersion(workspaceId: Uuid, pageId: Uuid): Int? =
        when (val outcome = viewer.view(PageContentViewerUseCase.Query(workspaceId, PageId(pageId), null))) {
            is PageContentViewOutcome.Found -> outcome.document.version.versionNumber.value
            PageContentViewOutcome.NotFound -> null
        }

    override suspend fun content(workspaceId: Uuid, pageId: Uuid, versionNumber: Int): CurrentPageContentOutcome =
        when (val outcome = viewer.attachment(PageContentViewerUseCase.Query(workspaceId, PageId(pageId), versionNumber))) {
            PageContentAttachmentOutcome.NotFound -> CurrentPageContentOutcome.NotFound
            PageContentAttachmentOutcome.SourceUnavailable -> CurrentPageContentOutcome.Unavailable
            is PageContentAttachmentOutcome.Found -> {
                val output = ByteArrayOutputStream()
                outcome.attachment.content.collect { buffer ->
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    output.write(bytes)
                }
                CurrentPageContentOutcome.Found(CurrentPageContent(
                    outcome.attachment.title, outcome.attachment.version.format.name,
                    output.toString(Charsets.UTF_8),
                ))
            }
        }

    private fun placement(value: PagePlacement): CurrentPagePlacement =
        CurrentPagePlacement(value.pageId.value, value.children.map(::placement))
}
