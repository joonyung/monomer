package cc.monomer.control.pages.inbound.rest.pages

import cc.monomer.pages.application.content.PageContentAttachmentOutcome
import cc.monomer.pages.application.content.PageContentViewOutcome
import cc.monomer.pages.application.content.PageContentViewerUseCase
import cc.monomer.pages.domain.PageId
import cc.monomer.pages.domain.content.PageContentFormat
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import kotlin.math.min
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactor.asFlux
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux

@RestController
@RequestMapping("/v2/workspaces/{workspaceId}/pages/{pageId}/content")
class PageContentViewerController(
    private val viewerUseCase: PageContentViewerUseCase,
    private val requestAccess: PagesRequestAccess,
    private val principalDisplayNames: cc.monomer.identity.api.PrincipalDisplayNames,
    private val agentApps: cc.monomer.identity.api.AgentAppDisplays,
) {
    @GetMapping
    suspend fun view(
        @PathVariable workspaceId: String,
        @PathVariable pageId: String,
        @RequestParam(required = false) versionNo: Int?,
        exchange: ServerWebExchange,
    ): ResponseEntity<PageContentDocumentResponse> {
        val access = requestAccess.require(exchange, workspaceId)
        val query = query(access.workspaceId, pageId, versionNo)
        return when (val outcome = viewerUseCase.view(query)) {
            is PageContentViewOutcome.Found -> ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .body(
                    PageContentDocumentResponse(
                        title = outcome.document.title,
                        version = outcome.document.version.toResponse(pageVersionFinalizers(principalDisplayNames, agentApps, listOf(outcome.document.version))),
                        attachmentUrl = attachmentUrl(
                            access.workspaceId,
                            query.pageId,
                            outcome.document.version.versionNumber.value,
                        ),
                    ),
                )
            PageContentViewOutcome.NotFound -> throw notFound()
        }
    }

    @GetMapping("/attachment")
    suspend fun attachment(
        @PathVariable workspaceId: String,
        @PathVariable pageId: String,
        @RequestParam(required = false) versionNo: Int?,
        exchange: ServerWebExchange,
    ): ResponseEntity<Flux<DataBuffer>> {
        val access = requestAccess.require(exchange, workspaceId)
        val query = query(access.workspaceId, pageId, versionNo)
        return when (val outcome = viewerUseCase.attachment(query)) {
            is PageContentAttachmentOutcome.Found -> {
                val attachment = outcome.attachment
                val content = attachment.content
                    .map { buffer -> DefaultDataBufferFactory.sharedInstance.wrap(buffer) as DataBuffer }
                    .asFlux(org.springframework.core.PropagationContextElement())
                ResponseEntity.ok()
                    .contentType(attachment.version.format.attachmentMediaType())
                    .contentLength(attachment.contentLength)
                    .headers { headers ->
                        headers.contentDisposition = ContentDisposition.attachment()
                            .filename(
                                attachment.title.attachmentFilename(attachment.version.format),
                                StandardCharsets.UTF_8,
                            )
                            .build()
                        headers.cacheControl = CacheControl.noStore().headerValue
                        headers.set("X-Content-Type-Options", "nosniff")
                        headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; sandbox")
                        headers.set("Referrer-Policy", "no-referrer")
                    }
                    .body(content)
            }
            PageContentAttachmentOutcome.NotFound -> throw notFound()
            PageContentAttachmentOutcome.SourceUnavailable ->
                throw unavailable("PAGE_CONTENT_SOURCE_UNAVAILABLE")
        }
    }

    private fun query(
        workspaceId: Uuid,
        rawPageId: String,
        versionNumber: Int?,
    ): PageContentViewerUseCase.Query {
        if (versionNumber != null && versionNumber <= 0) {
            throw PageContentRestProblem(
                HttpStatus.BAD_REQUEST,
                "INVALID_VERSION_NO",
                null,
                emptyMap(),
                "versionNo must be positive.",
            )
        }
        val parsedPageId = runCatching { Uuid.parse(rawPageId) }
            .getOrElse { throw invalidPageId() }
        if (parsedPageId.toString() != rawPageId) throw invalidPageId()
        return PageContentViewerUseCase.Query(
            workspaceId,
            PageId(parsedPageId),
            versionNumber,
        )
    }

    private fun attachmentUrl(workspaceId: Uuid, pageId: PageId, versionNumber: Int): String =
        "/v2/workspaces/$workspaceId/pages/$pageId/content/attachment?versionNo=$versionNumber"

    private fun String.attachmentFilename(format: PageContentFormat): String {
        val normalized = Normalizer.normalize(this, Normalizer.Form.NFKC)
            .replace(FILENAME_UNSAFE_CHARACTER, "-")
            .replace(FILENAME_WHITESPACE, " ")
            .trim(' ', '.')
            .ifEmpty { "document" }
        val codePointCount = normalized.codePointCount(0, normalized.length)
        val maximumEnd = normalized.offsetByCodePoints(0, min(codePointCount, MAXIMUM_FILENAME_CODE_POINTS))
        val bounded = normalized.substring(0, maximumEnd).trimEnd(' ', '.').ifEmpty { "document" }
        val extension = format.extension()
        return if (bounded.lowercase(Locale.ROOT).endsWith(".$extension")) bounded else "$bounded.$extension"
    }

    private fun PageContentFormat.extension(): String = when (this) {
        PageContentFormat.MARKDOWN -> "md"
        PageContentFormat.NOTEBOOK -> "ipynb"
        PageContentFormat.HTML -> "html"
    }

    private fun PageContentFormat.attachmentMediaType(): MediaType = when (this) {
        PageContentFormat.MARKDOWN -> MediaType.parseMediaType("text/markdown;charset=UTF-8")
        PageContentFormat.NOTEBOOK -> MediaType.parseMediaType("application/x-ipynb+json")
        PageContentFormat.HTML -> MediaType.parseMediaType("text/html;charset=UTF-8")
    }

    private fun invalidPageId(): PageContentRestProblem = PageContentRestProblem(
        HttpStatus.BAD_REQUEST,
        "INVALID_PAGE_ID",
        null,
        emptyMap(),
        "Identifier must be a canonical UUID.",
    )

    private fun notFound(): PageContentRestProblem = PageContentRestProblem(
        HttpStatus.NOT_FOUND,
        "PAGE_CONTENT_NOT_FOUND",
        null,
        emptyMap(),
        "Page content was not found.",
    )

    private fun unavailable(code: String): PageContentRestProblem = PageContentRestProblem(
        HttpStatus.SERVICE_UNAVAILABLE,
        code,
        null,
        emptyMap(),
        "Page content is temporarily unavailable.",
    )

    private companion object {
        const val MAXIMUM_FILENAME_CODE_POINTS = 120
        val FILENAME_UNSAFE_CHARACTER = Regex("[\\u0000-\\u001f\\u007f/\\\\:*?\"<>|]")
        val FILENAME_WHITESPACE = Regex("\\s+")
    }
}
