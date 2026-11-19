package cc.monomer.control.pages.sharing

import cc.monomer.control.pagereader.*

import cc.monomer.control.observability.RequestLogContext
import cc.monomer.control.pages.publicpages.PageReaderService
import cc.monomer.control.pages.publicpages.PageReaderCaller
import cc.monomer.control.publicapi.ControlPublicApiProperties
import cc.monomer.identity.api.WorkspaceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import kotlin.uuid.Uuid

@RestController
class PublicPageHtmlController(
    private val shell: PublicPageAppShell,
    private val reader: PageReaderService,
    private val properties: ControlPublicApiProperties,
) {
    @RequestMapping(
        "/v2/public/workspaces/{workspaceId}/pages/{pageId}/html",
        method = [RequestMethod.GET, RequestMethod.HEAD],
        produces = [MediaType.TEXT_HTML_VALUE],
    )
    suspend fun html(
        @PathVariable workspaceId: String,
        @PathVariable pageId: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<String> {
        val workspace = canonicalUuid(workspaceId) ?: return response(404, UNAVAILABLE, exchange)
        val page = canonicalUuid(pageId) ?: return response(404, UNAVAILABLE, exchange)
        return try {
            val template = shell.read()
            val ownerWorkspace = try { WorkspaceId(workspace) } catch (_: IllegalArgumentException) { null }
            val metadata = if (ownerWorkspace == null) null else try {
                val current = reader.page(PageReaderCaller(ownerWorkspace, null, null, null, null), page.toString()).page
                PublicPageMetadata(current.displayTitle, current.description)
            } catch (missing: PageReaderProblem) {
                if (missing.failure != PageReaderFailure.NotFound) throw missing
                null
            }
            val html = if (metadata == null) template else PublicPageHtml.render(
                template, properties.webOrigin, workspaceId, pageId, metadata,
            )
            response(200, html, exchange)
        } catch (timeout: TimeoutCancellationException) {
            RequestLogContext.failure(timeout)
            response(503, UNAVAILABLE, exchange)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            RequestLogContext.failure(failure)
            response(503, UNAVAILABLE, exchange)
        }
    }

    private fun response(status: Int, body: String, exchange: ServerWebExchange): ResponseEntity<String> =
        ResponseEntity.status(status)
            .contentType(MediaType("text", "html", Charsets.UTF_8))
            .contentLength(body.toByteArray(Charsets.UTF_8).size.toLong())
            .header("Cache-Control", "no-store")
            .headers { if (status == 503) it.set("Retry-After", "2") }
            .header("X-Content-Type-Options", "nosniff")
            .header("X-Robots-Tag", "noindex, nofollow")
            .header("Content-Security-Policy", APP_CSP)
            .body(if (exchange.request.method.name() == "HEAD") "" else body)

    private fun canonicalUuid(raw: String): Uuid? = try {
        Uuid.parse(raw).takeIf { it.toString() == raw }
    } catch (_: IllegalArgumentException) { null }

    private companion object {
        const val UNAVAILABLE = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Monomer</title></head><body><p>Monomer is temporarily unavailable.</p></body></html>"
        const val APP_CSP = "default-src 'self'; script-src 'self'; worker-src 'self'; connect-src 'self' http: https: ws: wss:; img-src 'self' data: blob:; font-src 'self' data:; style-src 'self' 'unsafe-inline'; object-src 'none'; frame-src 'none'; base-uri 'self'"
    }
}
