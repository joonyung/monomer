package cc.monomer.pages.application

import cc.monomer.pages.domain.Page
import cc.monomer.pages.domain.PageId
import cc.monomer.pages.domain.PageKind
import cc.monomer.pages.domain.PagesDirectorySnapshot
import cc.monomer.pages.domain.content.PageContentFormat

data class PagesDirectoryProjection(
    val snapshot: PagesDirectorySnapshot,
    val displayTitles: Map<PageId, String>,
    val documentFormats: Map<PageId, PageContentFormat>,
) {
    init {
        require(displayTitles.keys == snapshot.pages.map(Page::id).toSet()) {
            "Every Page must have exactly one display title"
        }
        require(documentFormats.keys.all { pageId ->
            snapshot.pages.any { page -> page.id == pageId && page.kind == PageKind.DOCUMENT }
        }) {
            "Document formats may only be projected for Document Pages"
        }
    }

    fun displayTitle(pageId: PageId): String = displayTitles.getValue(pageId)
}
