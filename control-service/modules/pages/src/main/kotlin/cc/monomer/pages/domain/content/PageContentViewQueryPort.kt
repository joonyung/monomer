package cc.monomer.pages.domain.content

import cc.monomer.pages.domain.PageId
import kotlin.uuid.Uuid

data class DocumentPageVersionSource(
    val title: String,
    val version: PageVersion,
) {
    init {
        require(title.isNotBlank()) { "Document Page title must not be blank" }
    }
}

interface PageContentViewQueryPort {
    suspend fun findDocumentVersionSource(
        workspaceId: Uuid,
        pageId: PageId,
        versionNumber: PageVersionNumber?,
    ): DocumentPageVersionSource?
}
