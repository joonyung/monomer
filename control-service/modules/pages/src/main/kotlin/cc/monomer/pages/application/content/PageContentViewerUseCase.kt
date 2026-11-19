package cc.monomer.pages.application.content

import cc.monomer.pages.domain.PageId
import cc.monomer.pages.domain.content.DocumentPageVersionSource
import cc.monomer.pages.domain.content.PageContentObjectStorage
import cc.monomer.pages.domain.content.PageContentViewQueryPort
import cc.monomer.pages.domain.content.PageVersionMetadata
import cc.monomer.pages.domain.content.PageVersionNumber
import java.nio.ByteBuffer
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface PageContentViewerUseCase {
    suspend fun view(query: Query): PageContentViewOutcome

    suspend fun attachment(query: Query): PageContentAttachmentOutcome

    data class Query(
        val workspaceId: Uuid,
        val pageId: PageId,
        val versionNumber: Int?,
    )
}

data class PageContentDocumentView(
    val title: String,
    val version: PageVersionMetadata,
) {
    init {
        require(title.isNotBlank()) { "Document Page title must not be blank" }
    }
}

sealed interface PageContentViewOutcome {
    data class Found(val document: PageContentDocumentView) : PageContentViewOutcome
    data object NotFound : PageContentViewOutcome
}

data class PageContentAttachment(
    val title: String,
    val version: PageVersionMetadata,
    val contentLength: Long,
    val content: Flow<ByteBuffer>,
)

sealed interface PageContentAttachmentOutcome {
    data class Found(val attachment: PageContentAttachment) : PageContentAttachmentOutcome
    data object NotFound : PageContentAttachmentOutcome
    data object SourceUnavailable : PageContentAttachmentOutcome
}

class PageContentViewerUseCaseImpl(
    private val viewQueryPort: PageContentViewQueryPort,
    private val objectStorage: PageContentObjectStorage,
) : PageContentViewerUseCase {
    override suspend fun view(query: PageContentViewerUseCase.Query): PageContentViewOutcome {
        val source = findSource(query) ?: return PageContentViewOutcome.NotFound
        return PageContentViewOutcome.Found(
            PageContentDocumentView(
                title = source.title,
                version = source.version.metadata(),
            ),
        )
    }

    override suspend fun attachment(query: PageContentViewerUseCase.Query): PageContentAttachmentOutcome {
        val source = findSource(query) ?: return PageContentAttachmentOutcome.NotFound
        val metadata = objectStorage.head(source.version.sourceObjectRef)
            ?: return PageContentAttachmentOutcome.SourceUnavailable
        if (metadata.contentLength != source.version.contentSizeBytes ||
            metadata.contentHash != source.version.contentHash
        ) {
            return PageContentAttachmentOutcome.SourceUnavailable
        }
        val download = objectStorage.downloadBounded(
            source.version.sourceObjectRef,
            source.version.contentSizeBytes,
        ) ?: return PageContentAttachmentOutcome.SourceUnavailable
        if (download.contentLength != source.version.contentSizeBytes) {
            download.discard()
            return PageContentAttachmentOutcome.SourceUnavailable
        }
        return PageContentAttachmentOutcome.Found(
            PageContentAttachment(
                title = source.title,
                version = source.version.metadata(),
                contentLength = download.contentLength,
                content = download.content.requireExactLength(download.contentLength),
            ),
        )
    }

    private suspend fun findSource(query: PageContentViewerUseCase.Query): DocumentPageVersionSource? {
        if (query.versionNumber != null && query.versionNumber <= 0) return null
        return viewQueryPort.findDocumentVersionSource(
            workspaceId = query.workspaceId,
            pageId = query.pageId,
            versionNumber = query.versionNumber?.let(::PageVersionNumber),
        )
    }

    private fun Flow<ByteBuffer>.requireExactLength(expectedBytes: Long): Flow<ByteBuffer> = flow {
        var emittedBytes = 0L
        collect { buffer ->
            val copy = buffer.asReadOnlyBuffer()
            emittedBytes += copy.remaining()
            check(emittedBytes <= expectedBytes) { "Page content attachment exceeded its declared size" }
            emit(copy)
        }
        check(emittedBytes == expectedBytes) { "Page content attachment ended before its declared size" }
    }
}
