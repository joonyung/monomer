package cc.monomer.pages.application.content

import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageActorKind
import cc.monomer.pages.domain.PageId
import cc.monomer.pages.domain.content.DocumentPageVersionSource
import cc.monomer.pages.domain.content.PageContentFormat
import cc.monomer.pages.domain.content.PageContentHash
import cc.monomer.pages.domain.content.PageContentObjectStorage
import cc.monomer.pages.domain.content.PageContentViewQueryPort
import cc.monomer.pages.domain.content.PageVersion
import cc.monomer.pages.domain.content.PageVersionNumber
import io.mockk.coEvery
import io.mockk.mockk
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PageContentViewerUseCaseImplTest {
    private val workspaceId = Uuid.parse("019e7000-0000-7000-8000-000000000001")
    private val pageId = PageId(Uuid.parse("019e7000-0000-7000-8000-000000000002"))
    private val actor = PageActor.create(PageActorKind.USER, "user-1")
    private val viewQueryPort = mockk<PageContentViewQueryPort>()
    private val objectStorage = mockk<PageContentObjectStorage>()
    private val useCase = PageContentViewerUseCaseImpl(viewQueryPort, objectStorage)

    @Test
    fun `view returns only version metadata and attachment remains the source read`() = runTest {
        val bytes = "<h1>Report</h1>".toByteArray()
        val version = version(PageContentFormat.HTML, bytes)
        coEvery { viewQueryPort.findDocumentVersionSource(workspaceId, pageId, null) } returns
            DocumentPageVersionSource("HTML report", version)
        coEvery { objectStorage.head(version.sourceObjectRef) } returns
            PageContentObjectStorage.ObjectMetadata(bytes.size.toLong(), version.contentHash)
        coEvery { objectStorage.downloadBounded(version.sourceObjectRef, bytes.size.toLong()) } returns
            PageContentObjectStorage.ObjectDownload(
                contentLength = bytes.size.toLong(),
                contentType = "text/html",
                content = flowOf(ByteBuffer.wrap(bytes)),
                discard = { error("a matching download must be streamed") },
            )

        val view = useCase.view(PageContentViewerUseCase.Query(workspaceId, pageId, null))
        val attachment = useCase.attachment(PageContentViewerUseCase.Query(workspaceId, pageId, null))

        assertThat(view).isEqualTo(
            PageContentViewOutcome.Found(
                PageContentDocumentView("HTML report", version.metadata()),
            ),
        )
        val found = (attachment as PageContentAttachmentOutcome.Found).attachment
        assertThat(found.version).isEqualTo(version.metadata())
        assertThat(found.content.toList().single().remaining()).isEqualTo(bytes.size)
    }

    @Test
    fun `a source shorter than its version is unavailable and its unread download released`() = runTest {
        val bytes = "# Report".toByteArray()
        val version = version(PageContentFormat.MARKDOWN, bytes)
        var discarded = 0
        coEvery { viewQueryPort.findDocumentVersionSource(workspaceId, pageId, null) } returns
            DocumentPageVersionSource("Report", version)
        coEvery { objectStorage.head(version.sourceObjectRef) } returns
            PageContentObjectStorage.ObjectMetadata(bytes.size.toLong(), version.contentHash)
        coEvery { objectStorage.downloadBounded(version.sourceObjectRef, bytes.size.toLong()) } returns
            PageContentObjectStorage.ObjectDownload(
                contentLength = bytes.size - 1L,
                contentType = "text/markdown",
                content = flowOf(ByteBuffer.wrap(bytes, 0, bytes.size - 1)),
                discard = { discarded += 1 },
            )

        val attachment = useCase.attachment(PageContentViewerUseCase.Query(workspaceId, pageId, null))

        assertThat(attachment).isEqualTo(PageContentAttachmentOutcome.SourceUnavailable)
        assertThat(discarded).isEqualTo(1)
    }

    @Test
    fun `view does not expose a conversion state when a version exists`() = runTest {
        val bytes = "# Report".toByteArray()
        val version = version(PageContentFormat.MARKDOWN, bytes)
        coEvery { viewQueryPort.findDocumentVersionSource(workspaceId, pageId, PageVersionNumber(1)) } returns
            DocumentPageVersionSource("Report", version)

        val outcome = useCase.view(PageContentViewerUseCase.Query(workspaceId, pageId, 1))

        assertThat(outcome).isInstanceOf(PageContentViewOutcome.Found::class.java)
    }

    private fun version(format: PageContentFormat, bytes: ByteArray): PageVersion = PageVersion(
        pageId = pageId,
        versionNumber = PageVersionNumber(1),
        format = format,
        sourceObjectRef = "workspace/$workspaceId/source/upload-1",
        contentHash = PageContentHash(sha256(bytes)),
        contentSizeBytes = bytes.size.toLong(),
        finalizedBy = actor,
        finalizedByAgentClientId = null,
        finalizedAt = Instant.parse("2026-08-21T00:00:00Z"),
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
