package cc.monomer.pages.domain.content

import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageActorKind
import cc.monomer.pages.domain.PageId
import java.time.Instant
import kotlin.uuid.Uuid
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PageContentTest {
    private val workspaceId = Uuid.parse("019e6000-0000-7000-8000-000000000301")
    private val pageId = PageId(Uuid.parse("019e6000-0000-7000-8000-000000000302"))
    private val actor = PageActor.create(PageActorKind.USER, "user-1")
    private val createdAt = Instant.parse("2026-08-21T00:00:00Z")

    @Test
    fun `PageVersion preserves the completed upload source object without a separate manifest`() {
        val content = "# Revenue".toByteArray()
        val hash = PageContentHash("a".repeat(64))
        val upload = PageContentUpload.initiate(
            uploadRef = PageContentUploadRef(Uuid.parse("019e6000-0000-7000-8000-000000000303")),
            workspaceId = workspaceId,
            actor = actor,
            format = PageContentFormat.MARKDOWN,
            expectedHash = hash,
            expectedSizeBytes = content.size.toLong(),
            temporaryObjectRef = "workspace/$workspaceId/uploads/source",
            multipartUploadId = "multipart-1",
            expiresAt = createdAt.plusSeconds(900),
            createdAt = createdAt,
        )
        val claim = Uuid.parse("019e6000-0000-7000-8000-000000000304")
        val completing = (upload.startCompletion(
            requestHash = "b".repeat(64),
            requestedAt = createdAt.plusSeconds(1),
            leaseExpiresAt = createdAt.plusSeconds(61),
            claimToken = claim,
        ) as PageContentUploadCompletionStart.Started).upload
        val completed = completing.complete(
            sourceObjectRef = "workspace/$workspaceId/source/${upload.uploadRef}",
            actualHash = hash,
            actualSizeBytes = content.size.toLong(),
            completedAt = createdAt.plusSeconds(2),
            claimToken = claim,
        )

        val version = PageVersion.fromUpload(
            pageId = pageId,
            versionNumber = PageVersionNumber(1),
            upload = completed,
            finalizedBy = actor,
            finalizedByAgentClientId = null,
            finalizedAt = createdAt.plusSeconds(3),
        )

        assertThat(version.sourceObjectRef).isEqualTo("workspace/$workspaceId/source/${upload.uploadRef}")
        assertThat(version.format).isEqualTo(PageContentFormat.MARKDOWN)
        assertThat(PageContentFormat.entries).containsExactly(
            PageContentFormat.MARKDOWN,
            PageContentFormat.NOTEBOOK,
            PageContentFormat.HTML,
        )
    }
}
