package cc.monomer.pages.domain

import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PagesDirectoryTest {
    private val workspaceId = Uuid.parse("019e501f-0000-7000-8000-000000000011")
    private val actor = PageActor.create(PageActorKind.USER, "user-1")
    private val now = Instant.parse("2026-07-10T00:00:00Z")

    @Test
    fun `dashboard navigation rename keeps immutable reference and requires title`() {
        val target = Uuid.generateV7()
        val metadata = (PageNavigationMetadata.create("Independent name", null) as PageNavigationMetadataResult.Valid).metadata
        val page = (Page.createDashboard(workspaceId, target, metadata, actor, null, now) as PageCreationResult.Created).page
        val renamed = page.updateNavigationMetadata(
            (PageNavigationMetadata.create("Renamed", null) as PageNavigationMetadataResult.Valid).metadata, actor, null, now,
        ) as PageUpdateResult.Updated
        assertThat(renamed.page.id).isEqualTo(page.id)
        assertThat(renamed.page.dashboardId).isEqualTo(target)
        assertThat(Page.createDashboard(workspaceId, target,
            (PageNavigationMetadata.create(null, null) as PageNavigationMetadataResult.Valid).metadata, actor, null, now))
            .isEqualTo(PageCreationResult.Invalid(PageMetadataRejection.TITLE_REQUIRED))
        val child = folder("Child")
        val invalid = directory(listOf(page, child), listOf(PagePlacement(page.id, listOf(PagePlacement(child.id, emptyList())))))
        assertThat(invalid.validate()).isInstanceOf(PagesDirectoryValidationResult.Invalid::class.java)
    }

    @Test
    fun `directory change types expose current Page lifecycle changes`() {
        assertThat(PageDirectoryChangeType.entries)
            .containsExactly(
                PageDirectoryChangeType.FOLDER_CREATED,
                PageDirectoryChangeType.DOCUMENT_PAGE_CREATED,
                PageDirectoryChangeType.DASHBOARD_PAGE_CREATED,
                PageDirectoryChangeType.NAVIGATION_METADATA_UPDATED,
                PageDirectoryChangeType.PAGE_DELETED,
                PageDirectoryChangeType.PLACEMENTS_PATCHED,
            )
    }

    @Test
    fun `non-empty folder deletion is rejected while an empty folder can be deleted`() {
        val folder = folder("Folder")
        val first = document("first")
        val second = document("second")
        val after = document("after")
        val directory = directory(
            pages = listOf(folder, first, second, after),
            placements = listOf(
                PagePlacement(folder.id, listOf(PagePlacement(first.id, emptyList()), PagePlacement(second.id, emptyList()))),
                PagePlacement(after.id, emptyList()),
            ),
        )

        val result = directory.deletePage(folder.id, actor, now.plusSeconds(1))

        assertThat(result).isEqualTo(PagesDirectoryChangeResult.Invalid(PagesDirectoryRejection.FOLDER_NOT_EMPTY))

        val emptyDirectory = directory(
            pages = listOf(folder, after),
            placements = listOf(PagePlacement(folder.id, emptyList()), PagePlacement(after.id, emptyList())),
        )
        val deleted = emptyDirectory.deletePage(folder.id, actor, now.plusSeconds(1))
        val changed = deleted as PagesDirectoryChangeResult.Changed
        assertThat(changed.directory.pages.map { it.id }).containsExactly(after.id)
        assertThat(changed.directory.placements.map { it.pageId }).containsExactly(after.id)
    }

    @Test
    fun `placement patch moves a page atomically and preserves exact-one placement`() {
        val folder = folder("Folder")
        val document = document("document")
        val directory = directory(
            pages = listOf(folder, document),
            placements = listOf(PagePlacement(folder.id, emptyList()), PagePlacement(document.id, emptyList())),
        )
        val patch = parsePatch(
            """[
              {"op":"test","path":"/1/pageId","value":"${document.id}"},
              {"op":"remove","path":"/1"},
              {"op":"add","path":"/0/children/-","value":{"pageId":"${document.id}","children":[]}}
            ]""",
        )

        val result = directory.patchPlacements(patch, actor, now.plusSeconds(1))

        val patched = result as PagesDirectoryPatchResult.Patched
        assertThat(patched.appliedOperationCount).isEqualTo(3)
        assertThat(patched.directory.placements.single().children.single().pageId).isEqualTo(document.id)
        assertThat(patched.directory.validate()).isEqualTo(PagesDirectoryValidationResult.Valid)
    }

    @Test
    fun `failed multi-operation patch leaves the directory unchanged`() {
        val folder = folder("Folder")
        val document = document("document")
        val directory = directory(
            pages = listOf(folder, document),
            placements = listOf(PagePlacement(folder.id, emptyList()), PagePlacement(document.id, emptyList())),
        )
        val patch = parsePatch(
            """[
              {"op":"remove","path":"/1"},
              {"op":"test","path":"/0/pageId","value":"019e501f-0000-7000-8000-000000000099"}
            ]""",
        )

        val result = directory.patchPlacements(patch, actor, now.plusSeconds(1))

        assertThat(result).isInstanceOf(PagesDirectoryPatchResult.PatchInvalid::class.java)
        assertThat(directory.revision).isEqualTo(PagesDirectoryRevision(0))
        assertThat(directory.placements.map { it.pageId }).containsExactly(folder.id, document.id)
    }

    @Test
    fun `only folders may own children and repeated ancestor IDs are cycles`() {
        val parentDocument = document("parent")
        val childDocument = document("child")
        val nonFolder = directory(
            pages = listOf(parentDocument, childDocument),
            placements = listOf(PagePlacement(parentDocument.id, listOf(PagePlacement(childDocument.id, emptyList())))),
        )
        assertThat(nonFolder.validate()).isEqualTo(
            PagesDirectoryValidationResult.Invalid(PagesDirectoryRejection.NON_FOLDER_HAS_CHILDREN),
        )

        val first = folder("first")
        val second = folder("second")
        val cycle = directory(
            pages = listOf(first, second),
            placements = listOf(
                PagePlacement(first.id, listOf(PagePlacement(second.id, listOf(PagePlacement(first.id, emptyList()))))),
            ),
        )
        assertThat(cycle.validate()).isEqualTo(
            PagesDirectoryValidationResult.Invalid(PagesDirectoryRejection.CYCLE_DETECTED),
        )
    }

    @Test
    fun `every live page must have exactly one known placement`() {
        val first = folder("first")
        val second = folder("second")
        val missing = directory(
            pages = listOf(first, second),
            placements = listOf(PagePlacement(first.id, emptyList())),
        )
        assertThat(missing.validate()).isEqualTo(
            PagesDirectoryValidationResult.Invalid(PagesDirectoryRejection.PAGE_PLACEMENT_MISMATCH),
        )

        val duplicate = directory(
            pages = listOf(first),
            placements = listOf(PagePlacement(first.id, emptyList()), PagePlacement(first.id, emptyList())),
        )
        assertThat(duplicate.validate()).isEqualTo(
            PagesDirectoryValidationResult.Invalid(PagesDirectoryRejection.DUPLICATE_PLACEMENT),
        )

        val unknown = directory(
            pages = listOf(first),
            placements = listOf(PagePlacement(PageId(Uuid.generateV7()), emptyList())),
        )
        assertThat(unknown.validate()).isEqualTo(
            PagesDirectoryValidationResult.Invalid(PagesDirectoryRejection.UNKNOWN_PAGE_PLACEMENT),
        )
    }

    @Test
    fun `only agent work names its agent app`() {
        val agent = PageActor.create(PageActorKind.AGENT, actor.id)
        val metadata = (PageNavigationMetadata.create("Revenue notes", null) as PageNavigationMetadataResult.Valid).metadata
        val created = (Page.createFolder(workspaceId, metadata, agent, "https://claude.ai/oauth/mcp-oauth-client-metadata", now) as PageCreationResult.Created).page
        assertThat(created.createdByAgentClientId).isEqualTo("https://claude.ai/oauth/mcp-oauth-client-metadata")
        assertThat(created.updatedByAgentClientId).isEqualTo("https://claude.ai/oauth/mcp-oauth-client-metadata")
        val updated = (created.updateNavigationMetadata(metadata, actor, null, now) as PageUpdateResult.Updated).page
        assertThat(updated.updatedByAgentClientId).isNull()
        assertThat(updated.createdByAgentClientId).isEqualTo("https://claude.ai/oauth/mcp-oauth-client-metadata")
        assertThatThrownBy { Page.createFolder(workspaceId, metadata, actor, "https://claude.ai/oauth/mcp-oauth-client-metadata", now) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `restore rejects invalid metadata`() {
        assertThatThrownBy {
            Page.restore(
                PageId.generate(),
                workspaceId,
                PageKind.DOCUMENT,
                null,
                PageNavigationMetadata.restore(null, null),
                now,
                actor,
                null,
                now,
                actor,
                null,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `titles are trimmed and exact duplicate sibling titles are rejected across page kinds`() {
        val folder = folder("  Shared title  ")
        assertThat(folder.navigationMetadata.title).isEqualTo("Shared title")
        val document = document("shared")
            .let { page ->
                val metadata = (PageNavigationMetadata.create("Shared title", null) as PageNavigationMetadataResult.Valid).metadata
                (Page.createDocument(workspaceId, metadata, actor, null, now) as PageCreationResult.Created).page
            }
        val duplicate = directory(
            pages = listOf(folder, document),
            placements = listOf(PagePlacement(folder.id, emptyList()), PagePlacement(document.id, emptyList())),
        )
        assertThat(duplicate.validate()).isEqualTo(
            PagesDirectoryValidationResult.Invalid(PagesDirectoryRejection.DUPLICATE_SIBLING_TITLE),
        )
    }

    @Test
    fun `navigation description accepts one thousand characters and rejects the next`() {
        val maximum = PageNavigationMetadata.create(
            title = "Folder",
            description = "x".repeat(PageNavigationMetadata.MAX_DESCRIPTION_LENGTH),
        )
        val overLimit = PageNavigationMetadata.create(
            title = "Folder",
            description = "x".repeat(PageNavigationMetadata.MAX_DESCRIPTION_LENGTH + 1),
        )

        assertThat(maximum).isInstanceOf(PageNavigationMetadataResult.Valid::class.java)
        assertThat(overLimit).isEqualTo(
            PageNavigationMetadataResult.Invalid(PageMetadataRejection.DESCRIPTION_TOO_LONG),
        )
    }

    @Test
    fun `depth six and 500 nodes fit while the next node is rejected`() {
        val depthPages = (1..7).map { folder("depth-$it") }
        fun nested(index: Int, lastInclusive: Int): PagePlacement = PagePlacement(
            pageId = depthPages[index].id,
            children = if (index == lastInclusive) emptyList() else listOf(nested(index + 1, lastInclusive)),
        )
        val depthSix = directory(depthPages.take(6), listOf(nested(0, 5)))
        assertThat(depthSix.validate()).isEqualTo(PagesDirectoryValidationResult.Valid)
        val depthSeven = directory(depthPages, listOf(nested(0, 6)))
        assertThat(depthSeven.validate()).isEqualTo(
            PagesDirectoryValidationResult.Invalid(PagesDirectoryRejection.DEPTH_LIMIT_EXCEEDED),
        )

        val fiveHundredPages = (1..PagesDirectory.MAX_PAGE_COUNT).map { folder("folder-$it") }
        val fiveHundredPlacements = fiveHundredPages.map { PagePlacement(it.id, emptyList()) }
        val maximum = directory(fiveHundredPages, fiveHundredPlacements)
        assertThat(maximum.validate()).isEqualTo(PagesDirectoryValidationResult.Valid)
        assertThat(PagesDirectory.serializedPlacementsSize(fiveHundredPlacements))
            .isLessThanOrEqualTo(PagesDirectory.MAX_SERIALIZED_PLACEMENTS_BYTES)

        val extra = folder("extra")
        val overLimit = directory(fiveHundredPages + extra, fiveHundredPlacements + PagePlacement(extra.id, emptyList()))
        assertThat(overLimit.validate()).isEqualTo(
            PagesDirectoryValidationResult.Invalid(PagesDirectoryRejection.PAGE_LIMIT_EXCEEDED),
        )
    }

    private fun folder(title: String): Page {
        val metadata = (PageNavigationMetadata.create(title, null) as PageNavigationMetadataResult.Valid).metadata
        return (Page.createFolder(workspaceId, metadata, actor, null, now) as PageCreationResult.Created).page
    }

    private fun document(seed: String): Page {
        val metadata = (PageNavigationMetadata.create("Document $seed", null) as PageNavigationMetadataResult.Valid).metadata
        return (Page.createDocument(
            workspaceId,
            metadata,
            actor,
            null,
            now,
        ) as PageCreationResult.Created).page
    }

    private fun directory(pages: List<Page>, placements: List<PagePlacement>): PagesDirectory = PagesDirectory(
        workspaceId = workspaceId,
        pages = pages,
        placements = placements,
        revision = PagesDirectoryRevision(0),
        updatedAt = now,
        updatedBy = actor,
    )

    private fun parsePatch(raw: String): List<JsonObject> = Json.parseToJsonElement(raw).jsonArray.map { it.jsonObject }
}
