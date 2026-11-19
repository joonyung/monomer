package cc.monomer.control.pages.inbound.rest.pages

import cc.monomer.pages.application.content.PageContentMutationOutcome
import cc.monomer.pages.application.content.PageContentUploadAbortOutcome
import cc.monomer.pages.application.content.PageContentUploadCompleteOutcome
import cc.monomer.pages.application.content.PageContentUploadCreateOutcome
import cc.monomer.pages.application.content.PageContentUploadError
import cc.monomer.pages.application.content.PageContentUploadErrorCode
import cc.monomer.pages.application.content.PageContentUploadUseCase
import cc.monomer.pages.application.content.PageContentUploadUseCaseImpl
import cc.monomer.pages.application.content.PageContentUseCase
import cc.monomer.pages.domain.PageId
import cc.monomer.pages.domain.content.PageContentConsumptionRejection
import cc.monomer.pages.domain.content.PageContentFormat
import cc.monomer.pages.domain.content.PageContentHash
import cc.monomer.pages.domain.content.PageContentUploadRef
import java.net.URI
import java.time.Instant
import kotlin.uuid.Uuid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

@RestController
@RequestMapping("/v2/workspaces/{workspaceId}")
class PageContentController(
    private val uploadUseCase: PageContentUploadUseCase,
    private val contentUseCase: PageContentUseCase,
    private val requestAccess: PagesRequestAccess,
    private val principalDisplayNames: cc.monomer.identity.api.PrincipalDisplayNames,
    private val agentApps: cc.monomer.identity.api.AgentAppDisplays,
) {
    @PostMapping("/page-content-uploads")
    suspend fun createUpload(
        @PathVariable workspaceId: String,
        @RequestBody request: CreatePageContentUploadRequest,
        exchange: ServerWebExchange,
    ): ResponseEntity<PageContentUploadSessionResponse> {
        val access = requestAccess.require(exchange, workspaceId)
        return when (val outcome = uploadUseCase.createSession(
            PageContentUploadUseCase.CreateSessionCommand(
                workspaceId = access.workspaceId,
                actor = access.actor,
                format = format(request.format),
                byteSize = request.byteSize,
                sha256 = hash(request.sha256),
                requestedAt = Instant.now(),
            ),
        )) {
            is PageContentUploadCreateOutcome.Created -> ResponseEntity
                .created(URI.create("/v2/workspaces/${access.workspaceId}/page-content-uploads/${outcome.upload.uploadRef}"))
                .body(
                    PageContentUploadSessionResponse(
                        uploadRef = outcome.upload.uploadRef.toString(),
                        format = outcome.upload.format.name,
                        byteSize = outcome.upload.byteSize,
                        sha256 = outcome.upload.sha256.value,
                        expiresAt = outcome.upload.expiresAt.toString(),
                        partSizeBytes = PageContentUploadUseCaseImpl.MULTIPART_PART_BYTES,
                        parts = outcome.parts.map { part -> part.toResponse() },
                    ),
                )
            is PageContentUploadCreateOutcome.Rejected -> throw outcome.error.toProblem(null)
        }
    }

    @PostMapping("/page-content-uploads/{uploadRef}/complete")
    suspend fun completeUpload(
        @PathVariable workspaceId: String,
        @PathVariable uploadRef: String,
        @RequestBody request: CompletePageContentUploadRequest,
        exchange: ServerWebExchange,
    ): ResponseEntity<PageContentUploadCompletionResponse> {
        val access = requestAccess.require(exchange, workspaceId)
        val outcome = uploadUseCase.complete(
            PageContentUploadUseCase.CompleteCommand(
                workspaceId = access.workspaceId,
                actor = access.actor,
                uploadRef = uploadRef(uploadRef),
                parts = request.parts.map { part ->
                    PageContentUploadUseCase.CompletedPartCommand(part.partNumber, part.eTag)
                },
                requestedAt = Instant.now(),
            ),
        )
        return when (outcome) {
            is PageContentUploadCompleteOutcome.Completed -> ResponseEntity.ok(
                PageContentUploadCompletionResponse("COMPLETED", outcome.upload.toResponse()),
            )
            is PageContentUploadCompleteOutcome.Replayed -> ResponseEntity.ok(
                PageContentUploadCompletionResponse("REPLAYED", outcome.upload.toResponse()),
            )
            is PageContentUploadCompleteOutcome.Rejected -> throw outcome.error.toProblem(null)
            PageContentUploadCompleteOutcome.NotFound -> throw problem(
                HttpStatus.NOT_FOUND,
                "UPLOAD_NOT_FOUND",
                null,
                "Page content upload was not found.",
            )
            PageContentUploadCompleteOutcome.InProgress -> throw problem(
                HttpStatus.CONFLICT,
                "UPLOAD_COMPLETION_IN_PROGRESS",
                null,
                "Page content upload completion is already in progress.",
            )
            PageContentUploadCompleteOutcome.RequestReused -> throw problem(
                HttpStatus.CONFLICT,
                "UPLOAD_COMPLETION_REQUEST_REUSED",
                null,
                "Page content upload completion parts differ from the first request.",
            )
        }
    }

    @DeleteMapping("/page-content-uploads/{uploadRef}")
    suspend fun abortUpload(
        @PathVariable workspaceId: String,
        @PathVariable uploadRef: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<Void> {
        val access = requestAccess.require(exchange, workspaceId)
        return when (uploadUseCase.abort(
            PageContentUploadUseCase.AbortCommand(
                access.workspaceId,
                access.actor,
                uploadRef(uploadRef),
                Instant.now(),
            ),
        )) {
            PageContentUploadAbortOutcome.Aborted,
            PageContentUploadAbortOutcome.Replayed,
            -> ResponseEntity.noContent().build()
            PageContentUploadAbortOutcome.NotFound -> throw problem(
                HttpStatus.NOT_FOUND,
                "UPLOAD_NOT_FOUND",
                null,
                "Page content upload was not found.",
            )
            PageContentUploadAbortOutcome.InProgress -> throw problem(
                HttpStatus.CONFLICT,
                "UPLOAD_COMPLETION_IN_PROGRESS",
                null,
                "Page content upload completion is in progress and cannot be aborted.",
            )
            PageContentUploadAbortOutcome.AlreadyConsumed -> throw problem(
                HttpStatus.CONFLICT,
                "UPLOAD_ALREADY_CONSUMED",
                null,
                "Consumed Page content cannot be aborted.",
            )
        }
    }

    @PostMapping("/pages/{pageId}/versions")
    suspend fun replaceContent(
        @PathVariable workspaceId: String,
        @PathVariable pageId: String,
        @RequestBody request: ReplacePageContentRequest,
        exchange: ServerWebExchange,
    ): ResponseEntity<PageContentVersionMutationResponse> {
        val access = requestAccess.require(exchange, workspaceId)
        val parsedPageId = pageId(pageId)
        val validatedRequestId = requestId(request.requestId)
        return when (val outcome = contentUseCase.replace(
            PageContentUseCase.ReplaceCommand(
                workspaceId = access.workspaceId,
                pageId = parsedPageId,
                uploadRef = uploadRef(request.uploadRef),
                requestId = validatedRequestId,
                actor = access.actor,
                agentClientId = null,
                auditActorContext = access.auditActorContext,
                requestedAt = Instant.now(),
            ),
        )) {
            is PageContentMutationOutcome.Applied -> ResponseEntity
                .created(
                    URI.create(
                        "/v2/workspaces/${access.workspaceId}/pages/$pageId/versions/${outcome.version.versionNumber.value}",
                    ),
                )
                .body(PageContentVersionMutationResponse("APPLIED", outcome.version.toResponse(pageVersionFinalizers(principalDisplayNames, agentApps, listOf(outcome.version)))))
            is PageContentMutationOutcome.Replayed -> ResponseEntity.ok(
                PageContentVersionMutationResponse("REPLAYED", outcome.version.toResponse(pageVersionFinalizers(principalDisplayNames, agentApps, listOf(outcome.version)))),
            )
            is PageContentMutationOutcome.RequestIdReused -> throw PageContentRestProblem(
                HttpStatus.CONFLICT,
                "REQUEST_ID_REUSED",
                validatedRequestId,
                mapOf("existingRequestHash" to outcome.existingRequestHash),
                "requestId was already used with a different body.",
            )
            is PageContentMutationOutcome.UploadRejected -> throw outcome.error.toProblem(validatedRequestId)
            PageContentMutationOutcome.PageNotFound -> throw problem(
                HttpStatus.NOT_FOUND,
                "PAGE_NOT_FOUND",
                validatedRequestId,
                "Document Page was not found.",
            )
        }
    }

    @GetMapping("/pages/{pageId}/versions")
    suspend fun listVersions(
        @PathVariable workspaceId: String,
        @PathVariable pageId: String,
        @RequestParam(required = false) beforeVersionNo: Int?,
        @RequestParam limit: Int,
        exchange: ServerWebExchange,
    ): PageContentVersionPageResponse {
        val access = requestAccess.require(exchange, workspaceId)
        if (beforeVersionNo != null && beforeVersionNo <= 0) {
            throw invalid("INVALID_BEFORE_VERSION_NO", "beforeVersionNo must be positive.")
        }
        if (limit !in 1..100) throw invalid("INVALID_LIMIT", "limit must be between 1 and 100.")
        val parsedPageId = pageId(pageId)
        val page = contentUseCase.listVersions(access.workspaceId, parsedPageId, beforeVersionNo, limit)
        if (page.items.isEmpty() && contentUseCase.getVersion(access.workspaceId, parsedPageId, 1) == null) {
            throw problem(HttpStatus.NOT_FOUND, "PAGE_NOT_FOUND", null, "Document Page was not found.")
        }
        return PageContentVersionPageResponse(
            items = pageVersionFinalizers(principalDisplayNames, agentApps, page.items).let { finalizers -> page.items.map { version -> version.toResponse(finalizers) } },
            nextBeforeVersionNo = page.nextBeforeVersionNumber,
        )
    }

    @GetMapping("/pages/{pageId}/versions/{versionNo}")
    suspend fun version(
        @PathVariable workspaceId: String,
        @PathVariable pageId: String,
        @PathVariable versionNo: Int,
        exchange: ServerWebExchange,
    ): PageContentVersionResponse {
        val access = requestAccess.require(exchange, workspaceId)
        if (versionNo <= 0) throw invalid("INVALID_VERSION_NO", "versionNo must be positive.")
        return contentUseCase.getVersion(access.workspaceId, pageId(pageId), versionNo)
            ?.let { version -> version.toResponse(pageVersionFinalizers(principalDisplayNames, agentApps, listOf(version))) }
            ?: throw problem(HttpStatus.NOT_FOUND, "PAGE_VERSION_NOT_FOUND", null, "Page version was not found.")
    }

    private fun PageContentUploadError.toProblem(requestId: String?): PageContentRestProblem {
        val status = when (code) {
            PageContentUploadErrorCode.SOURCE_SIZE_INVALID -> HttpStatus.PAYLOAD_TOO_LARGE
            PageContentUploadErrorCode.UPLOAD_EXPIRED -> HttpStatus.GONE
            PageContentUploadErrorCode.INVALID_COMPLETED_PARTS -> HttpStatus.BAD_REQUEST
            PageContentUploadErrorCode.UPLOAD_FAILED,
            PageContentUploadErrorCode.UPLOAD_ABORTED,
            PageContentUploadErrorCode.SOURCE_OBJECT_COLLISION,
            -> HttpStatus.CONFLICT
            PageContentUploadErrorCode.SOURCE_OBJECT_MISSING,
            PageContentUploadErrorCode.SOURCE_SIZE_MISMATCH,
            PageContentUploadErrorCode.SOURCE_HASH_MISMATCH,
            PageContentUploadErrorCode.SOURCE_FORMAT_MISMATCH,
            PageContentUploadErrorCode.SOURCE_UTF8_INVALID,
            -> HttpStatus.UNPROCESSABLE_ENTITY
        }
        return PageContentRestProblem(
            status,
            code.name,
            requestId,
            maximumBytes?.let { mapOf("maximumBytes" to it) } ?: emptyMap(),
            "Page content upload was rejected.",
        )
    }

    private fun PageContentConsumptionRejection.toProblem(requestId: String): PageContentRestProblem = when (this) {
        PageContentConsumptionRejection.UPLOAD_NOT_FOUND -> problem(
            HttpStatus.NOT_FOUND,
            name,
            requestId,
            "Page content upload was not found.",
        )
        PageContentConsumptionRejection.UPLOAD_EXPIRED -> problem(
            HttpStatus.GONE,
            name,
            requestId,
            "Page content upload expired.",
        )
        PageContentConsumptionRejection.UPLOAD_NOT_COMPLETED,
        PageContentConsumptionRejection.UPLOAD_ALREADY_CONSUMED,
        PageContentConsumptionRejection.UPLOAD_ABORTED,
        -> problem(HttpStatus.CONFLICT, name, requestId, "Page content upload cannot be consumed.")
    }

    private fun format(raw: String): PageContentFormat = runCatching { PageContentFormat.valueOf(raw) }
        .getOrElse { throw invalid("INVALID_CONTENT_FORMAT", "format is invalid.") }

    private fun hash(raw: String): PageContentHash = runCatching { PageContentHash(raw) }
        .getOrElse { throw invalid("INVALID_SHA256", "sha256 must be a lowercase SHA-256 value.") }

    private fun pageId(raw: String): PageId = PageId(canonicalUuid(raw, "INVALID_PAGE_ID"))

    private fun uploadRef(raw: String): PageContentUploadRef =
        PageContentUploadRef(canonicalUuid(raw, "INVALID_UPLOAD_REF"))

    private fun requestId(raw: String): String {
        if (raw.isBlank() || raw.length > 200) throw invalid("INVALID_REQUEST_ID", "requestId is invalid.")
        return raw
    }

    private fun canonicalUuid(raw: String, code: String): Uuid {
        val parsed = runCatching { Uuid.parse(raw) }
            .getOrElse { throw invalid(code, "Identifier must be a canonical UUID.") }
        if (parsed.toString() != raw) throw invalid(code, "Identifier must be a canonical UUID.")
        return parsed
    }

    private fun invalid(code: String, message: String): PageContentRestProblem =
        PageContentRestProblem(HttpStatus.BAD_REQUEST, code, null, emptyMap(), message)

    private fun problem(
        status: HttpStatus,
        code: String,
        requestId: String?,
        message: String,
    ): PageContentRestProblem = PageContentRestProblem(status, code, requestId, emptyMap(), message)
}
