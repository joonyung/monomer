package cc.monomer.pages.domain

import cc.monomer.pages.domain.common.JsonPatchError
import cc.monomer.pages.domain.content.PageContentConsumptionRejection
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@JvmInline
value class PageDirectoryRequestId(val value: String) {
    init {
        require(value.isNotBlank()) { "requestId must not be blank" }
        require(value.length <= 200) { "requestId must not exceed 200 characters" }
    }
}

enum class PageDirectoryChangeType {
    FOLDER_CREATED,
    DOCUMENT_PAGE_CREATED,
    DASHBOARD_PAGE_CREATED,
    NAVIGATION_METADATA_UPDATED,
    PAGE_DELETED,
    PLACEMENTS_PATCHED,
}

sealed interface PageDirectoryChange {
    val type: PageDirectoryChangeType

    data class FolderCreated(val pageId: PageId) : PageDirectoryChange {
        override val type: PageDirectoryChangeType = PageDirectoryChangeType.FOLDER_CREATED
    }

    data class DashboardPageCreated(val pageId: PageId) : PageDirectoryChange {
        override val type: PageDirectoryChangeType = PageDirectoryChangeType.DASHBOARD_PAGE_CREATED
    }

    data class DocumentPageCreated(val pageId: PageId) : PageDirectoryChange {
        override val type: PageDirectoryChangeType = PageDirectoryChangeType.DOCUMENT_PAGE_CREATED
    }

    data class NavigationMetadataUpdated(val pageId: PageId) : PageDirectoryChange {
        override val type: PageDirectoryChangeType = PageDirectoryChangeType.NAVIGATION_METADATA_UPDATED
    }

    data class PageDeleted(val pageId: PageId) : PageDirectoryChange {
        override val type: PageDirectoryChangeType = PageDirectoryChangeType.PAGE_DELETED
    }

    data class PlacementsPatched(val appliedOperationCount: Int) : PageDirectoryChange {
        override val type: PageDirectoryChangeType = PageDirectoryChangeType.PLACEMENTS_PATCHED
    }
}

data class PagesDirectorySnapshot(
    val workspaceId: Uuid,
    val pages: List<Page>,
    val placements: List<PagePlacement>,
    val revision: PagesDirectoryRevision,
    val updatedAt: Instant,
    val updatedBy: PageActor,
) {
    companion object {
        fun from(directory: PagesDirectory): PagesDirectorySnapshot = PagesDirectorySnapshot(
            workspaceId = directory.workspaceId,
            pages = directory.pages,
            placements = directory.placements,
            revision = directory.revision,
            updatedAt = directory.updatedAt,
            updatedBy = directory.updatedBy,
        )
    }
}

data class PageDirectoryMutationRecord(
    val requestId: PageDirectoryRequestId,
    val requestHash: String,
    val requestBody: JsonElement,
    val actor: PageActor,
    val change: PageDirectoryChange,
    val createdAt: Instant,
) {
    init {
        if (change is PageDirectoryChange.DocumentPageCreated) {
            require(requestBody is JsonObject) { "Document Page request body must be an object" }
            require(requestBody.keys.none { it.isSensitiveContentCapabilityKey() }) {
                "Document Page mutations cannot persist upload or object capabilities"
            }
        }
    }
}

private fun String.isSensitiveContentCapabilityKey(): Boolean = lowercase() in setOf(
    "uploadref",
    "temporaryobjectref",
    "sourceobjectref",
    "objectref",
)

data class PageDirectoryRequestRecord(
    val requestId: PageDirectoryRequestId,
    val requestHash: String,
    val requestBody: JsonElement,
    val actor: PageActor,
    val createdAt: Instant,
)

sealed interface PageDirectoryRequestResult {
    val requestHash: String

    data class Applied(
        override val requestHash: String,
        val change: PageDirectoryChange,
        val snapshot: PagesDirectorySnapshot,
    ) : PageDirectoryRequestResult

    sealed interface NonAdvancing : PageDirectoryRequestResult {
        val snapshot: PagesDirectorySnapshot
    }

    data class RevisionConflict(
        override val requestHash: String,
        override val snapshot: PagesDirectorySnapshot,
    ) : NonAdvancing

    data class Rejected(
        override val requestHash: String,
        val reason: PageDirectoryRequestRejection,
        override val snapshot: PagesDirectorySnapshot,
    ) : NonAdvancing

}

sealed interface PageDirectoryRequestRejection {
    data class DirectoryInvalid(val reason: PagesDirectoryRejection) : PageDirectoryRequestRejection
    data class MetadataInvalid(val reason: PageMetadataRejection) : PageDirectoryRequestRejection
    data class PatchInvalid(val error: JsonPatchError) : PageDirectoryRequestRejection
    data class ContentUploadInvalid(val reason: PageContentConsumptionRejection) : PageDirectoryRequestRejection
}

interface PagesDirectoryQueryPort {
    suspend fun findSnapshot(workspaceId: Uuid): PagesDirectorySnapshot?
}

interface PagesDirectoryRepository {
    fun requireAmbientTransaction()

    fun saveDirectoryWithinTx(current: PagesDirectory, next: PagesDirectory)

    fun findOrCreateForUpdateWithinTx(workspaceId: Uuid): PagesDirectory

    fun findRequestResultWithinTx(
        workspaceId: Uuid,
        actor: PageActor,
        requestId: PageDirectoryRequestId,
    ): PageDirectoryRequestResult?

    fun saveMutationWithinTx(
        current: PagesDirectory,
        next: PagesDirectory,
        record: PageDirectoryMutationRecord,
    ): PageDirectoryRequestResult.Applied

    fun saveNonAdvancingResultWithinTx(
        current: PagesDirectory,
        record: PageDirectoryRequestRecord,
        result: PageDirectoryRequestResult.NonAdvancing,
    ): PageDirectoryRequestResult.NonAdvancing
}
