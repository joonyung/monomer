package cc.monomer.pages.api

import kotlin.uuid.Uuid

interface PagesCurrentReadPort {
    suspend fun tree(workspaceId: Uuid): CurrentPagesTree
    suspend fun latestContentVersion(workspaceId: Uuid, pageId: Uuid): Int?
    suspend fun content(workspaceId: Uuid, pageId: Uuid, versionNumber: Int): CurrentPageContentOutcome
}

data class CurrentPagesTree(val revision: Long, val pages: List<CurrentPage>, val placements: List<CurrentPagePlacement>)
data class CurrentPage(
    val pageId: Uuid,
    val kind: String,
    val documentFormat: String?,
    val dashboardId: Uuid?,
    val title: String?,
    val displayTitle: String,
    val description: String?,
)
data class CurrentPagePlacement(val pageId: Uuid, val children: List<CurrentPagePlacement>)
data class CurrentPageContent(val title: String, val format: String, val source: String)
sealed interface CurrentPageContentOutcome {
    data class Found(val content: CurrentPageContent) : CurrentPageContentOutcome
    data object NotFound : CurrentPageContentOutcome
    data object Unavailable : CurrentPageContentOutcome
}
