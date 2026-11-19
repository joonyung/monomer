package cc.monomer.pages.domain

import cc.monomer.pages.domain.common.JsonPatchEngine
import cc.monomer.pages.domain.common.JsonPatchError
import cc.monomer.pages.domain.common.JsonPatchResult
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@JvmInline
value class PagesDirectoryRevision(val value: Long) {
    init {
        require(value >= 0) { "revision must be non-negative" }
    }

    fun next(): PagesDirectoryRevision = PagesDirectoryRevision(value + 1)
}

@Serializable
data class PagePlacement(
    val pageId: PageId,
    val children: List<PagePlacement>,
)

data class PagesDirectory(
    val workspaceId: Uuid,
    val pages: List<Page>,
    val placements: List<PagePlacement>,
    val revision: PagesDirectoryRevision,
    val updatedAt: Instant,
    val updatedBy: PageActor,
) {
    fun addPage(
        page: Page,
        parentPageId: PageId?,
        index: Int?,
        actor: PageActor,
        changedAt: Instant,
    ): PagesDirectoryChangeResult {
        if (page.workspaceId != workspaceId) {
            return PagesDirectoryChangeResult.Invalid(PagesDirectoryRejection.PAGE_WORKSPACE_MISMATCH)
        }
        if (pages.any { it.id == page.id }) {
            return PagesDirectoryChangeResult.Invalid(PagesDirectoryRejection.DUPLICATE_PAGE_ID)
        }
        val placement = PagePlacement(pageId = page.id, children = emptyList())
        val nextPlacements = when (val inserted = placements.insertPlacement(parentPageId, index, placement, pages)) {
            is PlacementEditResult.Success -> inserted.placements
            is PlacementEditResult.Invalid -> return PagesDirectoryChangeResult.Invalid(inserted.reason)
        }
        return changed(
            nextPages = pages + page,
            nextPlacements = nextPlacements,
            actor = actor,
            changedAt = changedAt,
        )
    }

    fun updatePage(
        page: Page,
        actor: PageActor,
        changedAt: Instant,
    ): PagesDirectoryChangeResult {
        if (page.workspaceId != workspaceId) {
            return PagesDirectoryChangeResult.Invalid(PagesDirectoryRejection.PAGE_WORKSPACE_MISMATCH)
        }
        if (pages.none { it.id == page.id }) {
            return PagesDirectoryChangeResult.Invalid(PagesDirectoryRejection.PAGE_NOT_FOUND)
        }
        return changed(
            nextPages = pages.map { existing -> if (existing.id == page.id) page else existing },
            nextPlacements = placements,
            actor = actor,
            changedAt = changedAt,
        )
    }

    fun deletePage(
        pageId: PageId,
        actor: PageActor,
        changedAt: Instant,
    ): PagesDirectoryChangeResult {
        if (pages.none { it.id == pageId }) {
            return PagesDirectoryChangeResult.Invalid(PagesDirectoryRejection.PAGE_NOT_FOUND)
        }
        val targetPlacement = placements.findPlacement(pageId)
            ?: return PagesDirectoryChangeResult.Invalid(PagesDirectoryRejection.PAGE_NOT_FOUND)
        if (targetPlacement.children.isNotEmpty()) {
            return PagesDirectoryChangeResult.Invalid(PagesDirectoryRejection.FOLDER_NOT_EMPTY)
        }
        val nextPlacements = placements.delete(pageId)
            ?: return PagesDirectoryChangeResult.Invalid(PagesDirectoryRejection.PAGE_NOT_FOUND)
        return changed(
            nextPages = pages.filterNot { it.id == pageId },
            nextPlacements = nextPlacements,
            actor = actor,
            changedAt = changedAt,
        )
    }

    fun deleteDashboardPages(
        dashboardId: Uuid,
        actor: PageActor,
        changedAt: Instant,
    ): PagesDirectoryChangeResult {
        val pageIds = pages.filter { it.kind == PageKind.DASHBOARD && it.dashboardId == dashboardId }.map(Page::id).toSet()
        var nextPlacements = placements
        pageIds.forEach { pageId ->
            nextPlacements = nextPlacements.delete(pageId) ?: nextPlacements
        }
        return changed(pages.filterNot { it.id in pageIds }, nextPlacements, actor, changedAt)
    }

    fun patchPlacements(
        patch: List<JsonObject>,
        actor: PageActor,
        changedAt: Instant,
    ): PagesDirectoryPatchResult {
        val document = json.encodeToJsonElement(ListSerializer(PagePlacement.serializer()), placements)
        return when (val patched = JsonPatchEngine.apply(document, patch)) {
            is JsonPatchResult.Failure -> PagesDirectoryPatchResult.PatchInvalid(patched.error)
            is JsonPatchResult.Success -> {
                val nextPlacements = try {
                    json.decodeFromJsonElement(ListSerializer(PagePlacement.serializer()), patched.document)
                } catch (exception: SerializationException) {
                    return PagesDirectoryPatchResult.PatchInvalid(
                        JsonPatchError(
                            code = "INVALID_PLACEMENT_DOCUMENT",
                            message = "Patched placement document has an invalid shape.",
                            path = null,
                            operationIndex = null,
                        ),
                    )
                }
                when (val result = changed(pages, nextPlacements, actor, changedAt)) {
                    is PagesDirectoryChangeResult.Changed -> PagesDirectoryPatchResult.Patched(result.directory, patched.applied)
                    is PagesDirectoryChangeResult.Invalid -> PagesDirectoryPatchResult.DirectoryInvalid(result.reason)
                }
            }
        }
    }

    fun validate(): PagesDirectoryValidationResult = validateDirectory(this)

    private fun changed(
        nextPages: List<Page>,
        nextPlacements: List<PagePlacement>,
        actor: PageActor,
        changedAt: Instant,
    ): PagesDirectoryChangeResult {
        val next = copy(
            pages = nextPages,
            placements = nextPlacements,
            revision = revision.next(),
            updatedAt = changedAt,
            updatedBy = actor,
        )
        return when (val validation = next.validate()) {
            PagesDirectoryValidationResult.Valid -> PagesDirectoryChangeResult.Changed(next)
            is PagesDirectoryValidationResult.Invalid -> PagesDirectoryChangeResult.Invalid(validation.reason)
        }
    }

    companion object {
        const val MAX_PAGE_COUNT: Int = 500
        const val MAX_DEPTH: Int = 6
        const val MAX_SERIALIZED_PLACEMENTS_BYTES: Int = 256 * 1024

        private val json = Json { encodeDefaults = true }

        fun empty(workspaceId: Uuid): PagesDirectory =
            PagesDirectory(
                workspaceId = workspaceId,
                pages = emptyList(),
                placements = emptyList(),
                revision = PagesDirectoryRevision(0),
                updatedAt = Instant.EPOCH,
                updatedBy = PageActor.create(PageActorKind.SYSTEM, "initial"),
            )

        fun serializedPlacementsSize(placements: List<PagePlacement>): Int =
            json.encodeToString(ListSerializer(PagePlacement.serializer()), placements).toByteArray(Charsets.UTF_8).size

        private fun validateDirectory(directory: PagesDirectory): PagesDirectoryValidationResult {
            if (directory.pages.size > MAX_PAGE_COUNT) {
                return PagesDirectoryValidationResult.Invalid(PagesDirectoryRejection.PAGE_LIMIT_EXCEEDED)
            }
            if (directory.pages.map { it.id }.toSet().size != directory.pages.size) {
                return PagesDirectoryValidationResult.Invalid(PagesDirectoryRejection.DUPLICATE_PAGE_ID)
            }
            if (directory.pages.any { it.workspaceId != directory.workspaceId }) {
                return PagesDirectoryValidationResult.Invalid(PagesDirectoryRejection.PAGE_WORKSPACE_MISMATCH)
            }
            if (serializedPlacementsSize(directory.placements) > MAX_SERIALIZED_PLACEMENTS_BYTES) {
                return PagesDirectoryValidationResult.Invalid(PagesDirectoryRejection.PLACEMENTS_PAYLOAD_TOO_LARGE)
            }

            val pagesById = directory.pages.associateBy { it.id }
            val seen = linkedSetOf<PageId>()
            fun visit(placement: PagePlacement, depth: Int, ancestors: Set<PageId>): PagesDirectoryRejection? {
                if (placement.pageId in ancestors) return PagesDirectoryRejection.CYCLE_DETECTED
                if (!seen.add(placement.pageId)) return PagesDirectoryRejection.DUPLICATE_PLACEMENT
                val page = pagesById[placement.pageId] ?: return PagesDirectoryRejection.UNKNOWN_PAGE_PLACEMENT
                if (depth > MAX_DEPTH) return PagesDirectoryRejection.DEPTH_LIMIT_EXCEEDED
                if (placement.children.isNotEmpty() && page.kind != PageKind.FOLDER) {
                    return PagesDirectoryRejection.NON_FOLDER_HAS_CHILDREN
                }
                if (placement.children.map { it.pageId }.toSet().size != placement.children.size) {
                    return PagesDirectoryRejection.DUPLICATE_PLACEMENT
                }
                val siblingTitles = mutableSetOf<String>()
                for (child in placement.children) {
                    val childPage = pagesById[child.pageId] ?: continue
                    val title = childPage.navigationMetadata.title ?: continue
                    if (!siblingTitles.add(title)) return PagesDirectoryRejection.DUPLICATE_SIBLING_TITLE
                }
                val nextAncestors = ancestors + placement.pageId
                for (child in placement.children) {
                    val rejection = visit(child, depth + 1, nextAncestors)
                    if (rejection != null) return rejection
                }
                return null
            }
            if (directory.placements.map { it.pageId }.toSet().size != directory.placements.size) {
                return PagesDirectoryValidationResult.Invalid(PagesDirectoryRejection.DUPLICATE_PLACEMENT)
            }
            val rootTitles = mutableSetOf<String>()
            for (placement in directory.placements) {
                val page = pagesById[placement.pageId] ?: continue
                val title = page.navigationMetadata.title ?: continue
                if (!rootTitles.add(title)) return PagesDirectoryValidationResult.Invalid(
                    PagesDirectoryRejection.DUPLICATE_SIBLING_TITLE,
                )
            }
            for (root in directory.placements) {
                val rejection = visit(root, 1, emptySet())
                if (rejection != null) return PagesDirectoryValidationResult.Invalid(rejection)
            }
            if (seen != pagesById.keys) {
                return PagesDirectoryValidationResult.Invalid(PagesDirectoryRejection.PAGE_PLACEMENT_MISMATCH)
            }
            return PagesDirectoryValidationResult.Valid
        }
    }
}

enum class PagesDirectoryRejection {
    PAGE_NOT_FOUND,
    PARENT_NOT_FOUND,
    PARENT_NOT_FOLDER,
    INVALID_INSERT_INDEX,
    PAGE_WORKSPACE_MISMATCH,
    DUPLICATE_PAGE_ID,
    DUPLICATE_PLACEMENT,
    UNKNOWN_PAGE_PLACEMENT,
    PAGE_PLACEMENT_MISMATCH,
    CYCLE_DETECTED,
    NON_FOLDER_HAS_CHILDREN,
    PAGE_LIMIT_EXCEEDED,
    DEPTH_LIMIT_EXCEEDED,
    PLACEMENTS_PAYLOAD_TOO_LARGE,
    DUPLICATE_SIBLING_TITLE,
    FOLDER_NOT_EMPTY,
}

sealed interface PagesDirectoryValidationResult {
    data object Valid : PagesDirectoryValidationResult
    data class Invalid(val reason: PagesDirectoryRejection) : PagesDirectoryValidationResult
}

sealed interface PagesDirectoryChangeResult {
    data class Changed(val directory: PagesDirectory) : PagesDirectoryChangeResult
    data class Invalid(val reason: PagesDirectoryRejection) : PagesDirectoryChangeResult
}

sealed interface PagesDirectoryPatchResult {
    data class Patched(val directory: PagesDirectory, val appliedOperationCount: Int) : PagesDirectoryPatchResult
    data class PatchInvalid(val error: JsonPatchError) : PagesDirectoryPatchResult
    data class DirectoryInvalid(val reason: PagesDirectoryRejection) : PagesDirectoryPatchResult
}

private sealed interface PlacementEditResult {
    data class Success(val placements: List<PagePlacement>) : PlacementEditResult
    data class Invalid(val reason: PagesDirectoryRejection) : PlacementEditResult
}

private fun List<PagePlacement>.insertPlacement(
    parentPageId: PageId?,
    index: Int?,
    placement: PagePlacement,
    pages: List<Page>,
): PlacementEditResult {
    if (parentPageId == null) {
        val insertionIndex = index ?: size
        if (insertionIndex !in 0..size) return PlacementEditResult.Invalid(PagesDirectoryRejection.INVALID_INSERT_INDEX)
        return PlacementEditResult.Success(toMutableList().apply { add(insertionIndex, placement) })
    }
    val parent = pages.firstOrNull { it.id == parentPageId }
        ?: return PlacementEditResult.Invalid(PagesDirectoryRejection.PARENT_NOT_FOUND)
    if (parent.kind != PageKind.FOLDER) return PlacementEditResult.Invalid(PagesDirectoryRejection.PARENT_NOT_FOLDER)

    var found = false
    var rejection: PagesDirectoryRejection? = null
    fun recurse(nodes: List<PagePlacement>): List<PagePlacement> = nodes.map { node ->
        if (node.pageId == parentPageId) {
            found = true
            val insertionIndex = index ?: node.children.size
            if (insertionIndex !in 0..node.children.size) {
                rejection = PagesDirectoryRejection.INVALID_INSERT_INDEX
                node
            } else {
                node.copy(children = node.children.toMutableList().apply { add(insertionIndex, placement) })
            }
        } else {
            node.copy(children = recurse(node.children))
        }
    }
    val next = recurse(this)
    if (rejection != null) return PlacementEditResult.Invalid(rejection)
    if (!found) return PlacementEditResult.Invalid(PagesDirectoryRejection.PARENT_NOT_FOUND)
    return PlacementEditResult.Success(next)
}

private fun List<PagePlacement>.delete(pageId: PageId): List<PagePlacement>? {
    var found = false
    fun recurse(nodes: List<PagePlacement>): List<PagePlacement> {
        val result = mutableListOf<PagePlacement>()
        for (node in nodes) {
            if (node.pageId == pageId) {
                found = true
            } else {
                result += node.copy(children = recurse(node.children))
            }
        }
        return result
    }
    val next = recurse(this)
    return if (found) next else null
}

private fun List<PagePlacement>.findPlacement(pageId: PageId): PagePlacement? {
    for (placement in this) {
        if (placement.pageId == pageId) return placement
        placement.children.findPlacement(pageId)?.let { return it }
    }
    return null
}
