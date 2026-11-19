package cc.monomer.pages.application

import cc.monomer.pages.api.CurrentPageContentOutcome
import cc.monomer.pages.application.content.PageContentAttachment
import cc.monomer.pages.application.content.PageContentAttachmentOutcome
import cc.monomer.pages.application.content.PageContentViewerUseCase
import cc.monomer.pages.domain.content.PageContentFormat
import cc.monomer.pages.domain.content.PageVersionMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

class PagesCurrentReaderTest {
    @Test
    fun `current content reads the exact selected source with matching title and format`() = runTest {
        val viewer = mockk<PageContentViewerUseCase>()
        val metadata = mockk<PageVersionMetadata>()
        every { metadata.format } returns PageContentFormat.MARKDOWN
        coEvery { viewer.attachment(any()) } returns PageContentAttachmentOutcome.Found(
            PageContentAttachment("Current title", metadata, 7,
                flowOf(ByteBuffer.wrap("# Title".toByteArray(Charsets.UTF_8)))),
        )
        val workspace = Uuid.generateV7()
        val page = Uuid.generateV7()
        val result = assertIs<CurrentPageContentOutcome.Found>(PagesCurrentReader(mockk(), viewer).content(workspace, page, 3))
        assertEquals("Current title", result.content.title)
        assertEquals("MARKDOWN", result.content.format)
        assertEquals("# Title", result.content.source)
        coVerify(exactly = 1) { viewer.attachment(match { it.workspaceId == workspace && it.pageId.value == page && it.versionNumber == 3 }) }
        coVerify(exactly = 0) { viewer.view(any()) }
    }
}
