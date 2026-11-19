package cc.monomer.control.pages.inbound.rest.pages

import cc.monomer.control.dashboard.DashboardRequestAuthorizer
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardOwnerPort
import cc.monomer.dashboard.api.DashboardOwnerOutcome
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.pages.application.PagesDirectoryMutationOutcome
import cc.monomer.pages.application.PagesDirectoryUseCase
import cc.monomer.pages.domain.PageDirectoryChange
import cc.monomer.pages.domain.PageDirectoryRequestId
import cc.monomer.pages.domain.PageDirectoryRequestRejection
import cc.monomer.pages.domain.PageId
import cc.monomer.pages.domain.PageKind
import cc.monomer.pages.domain.PagesDirectoryRejection
import cc.monomer.pages.domain.PagesDirectoryRevision
import cc.monomer.pages.domain.content.PageContentUploadRef
import java.net.URI
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

@RestController
@RequestMapping("/v2/workspaces/{workspaceId}/pages")
class PagesController(
    private val useCase: PagesDirectoryUseCase,
    private val requestAccess: PagesRequestAccess,
    private val dashboardOwner: DashboardOwnerPort,
    private val dashboardAuthorizer: DashboardRequestAuthorizer,
) {
    @GetMapping("/tree")
    suspend fun tree(
        @PathVariable workspaceId: String,
        exchange: ServerWebExchange,
    ): PagesTreeResponse {
        val access = requestAccess.require(exchange, workspaceId)
        return useCase.getProjection(access.workspaceId).toResponse()
    }

    @PatchMapping("/tree")
    suspend fun patchTree(
        @PathVariable workspaceId: String,
        @RequestBody request: PatchPagesTreeRequest,
        exchange: ServerWebExchange,
    ): ResponseEntity<Any> {
        val access = requestAccess.require(exchange, workspaceId)
        val patch = request.patch.toValidatedPatch()
        val outcome = useCase.patchPlacements(
            PagesDirectoryUseCase.PatchPlacementsCommand(
                workspaceId = access.workspaceId,
                patch = patch,
                baseRevision = revision(request.baseRevision),
                requestId = requestId(request.requestId),
                actor = access.actor,
                auditActorContext = access.auditActorContext,
                requestedAt = Instant.now(),
            ),
        )
        return outcome.toHttpResponse(request.requestId, creation = false, access)
    }

    @PostMapping
    suspend fun createPage(
        @PathVariable workspaceId: String,
        @RequestBody request: CreatePageRequest,
        exchange: ServerWebExchange,
    ): ResponseEntity<Any> {
        val access = requestAccess.require(exchange, workspaceId)
        validateIndex(request.index)
        val parentPageId = request.parentPageId?.let(::parsePageId)
        val baseRevision = revision(request.baseRevision)
        val requestId = requestId(request.requestId)
        val requestedAt = Instant.now()
        val kind = pageKind(request.kind)
        if (kind != PageKind.DASHBOARD && request.dashboardId != null) {
            invalid("INVALID_PAGE_PAYLOAD", "Only Dashboard Pages can reference a dashboard.")
        }
        val outcome = when (kind) {
            PageKind.FOLDER -> {
                if (request.uploadRef != null) {
                    invalid("INVALID_PAGE_PAYLOAD", "Folder Page cannot reference content.")
                }
                useCase.createFolder(
                    PagesDirectoryUseCase.CreateFolderCommand(
                        workspaceId = access.workspaceId,
                        title = request.title ?: invalid("TITLE_REQUIRED", "Folder title is required."),
                        description = request.description,
                        parentPageId = parentPageId,
                        index = request.index,
                        baseRevision = baseRevision,
                        requestId = requestId,
                        actor = access.actor,
                        agentClientId = null,
                        auditActorContext = access.auditActorContext,
                        requestedAt = requestedAt,
                    ),
                )
            }
            PageKind.DASHBOARD -> {
                if (request.uploadRef != null) {
                    invalid("INVALID_PAGE_PAYLOAD", "Dashboard Page cannot reference content.")
                }
                val dashboardId = request.dashboardId?.let(::parseDashboardId)
                    ?: invalid("DASHBOARD_ID_REQUIRED", "Dashboard Page requires dashboardId.")
                val title = request.title ?: invalid("TITLE_REQUIRED", "Dashboard Page title is required.")
                dashboardAuthorizer.requireWorkspace(exchange, workspaceId)
                when (dashboardOwner.read(WorkspaceId(access.workspaceId), dashboardId)) {
                    is DashboardOwnerOutcome.Success -> Unit
                    DashboardOwnerOutcome.NotFound -> notFound("DASHBOARD_NOT_FOUND", "Dashboard was not found.")
                    is DashboardOwnerOutcome.Conflict -> throw PagesRestProblem(
                        HttpStatus.CONFLICT, "DASHBOARD_CONFLICT", null, request.requestId, emptyMap(),
                        "Dashboard cannot be linked.",
                    )
                    is DashboardOwnerOutcome.Rejected -> throw PagesRestProblem(
                        HttpStatus.UNPROCESSABLE_ENTITY, "DASHBOARD_REJECTED", null, request.requestId, emptyMap(),
                        "Dashboard cannot be linked.",
                    )
                }
                useCase.createDashboardPage(
                    PagesDirectoryUseCase.CreateDashboardPageCommand(
                        workspaceId = access.workspaceId,
                        dashboardId = dashboardId.value,
                        title = title,
                        description = request.description,
                        parentPageId = parentPageId,
                        index = request.index,
                        baseRevision = baseRevision,
                        requestId = requestId,
                        actor = access.actor,
                        agentClientId = null,
                        auditActorContext = access.auditActorContext,
                        requestedAt = requestedAt,
                    ),
                )
            }
            PageKind.DOCUMENT -> {
                useCase.createDocumentPage(
                    PagesDirectoryUseCase.CreateDocumentPageCommand(
                        workspaceId = access.workspaceId,
                        title = request.title ?: invalid("TITLE_REQUIRED", "Document title is required."),
                        description = request.description,
                        parentPageId = parentPageId,
                        index = request.index,
                        uploadRef = request.uploadRef?.let(::parseUploadRef)
                            ?: invalid("UPLOAD_REF_REQUIRED", "Document Page requires uploadRef."),
                        baseRevision = baseRevision,
                        requestId = requestId,
                        actor = access.actor,
                        agentClientId = null,
                        auditActorContext = access.auditActorContext,
                        requestedAt = requestedAt,
                    ),
                )
            }
        }
        return outcome.toHttpResponse(request.requestId, creation = true, access)
    }

    @GetMapping("/{pageId}")
    suspend fun page(
        @PathVariable workspaceId: String,
        @PathVariable pageId: String,
        exchange: ServerWebExchange,
    ): PageDetailResponse {
        val access = requestAccess.require(exchange, workspaceId)
        return useCase.getProjection(access.workspaceId).pageDetail(parsePageId(pageId))
            ?: notFound("PAGE_NOT_FOUND", "Page was not found.")
    }

    @PatchMapping("/{pageId}")
    suspend fun updatePage(
        @PathVariable workspaceId: String,
        @PathVariable pageId: String,
        @RequestBody request: UpdatePageRequest,
        exchange: ServerWebExchange,
    ): ResponseEntity<Any> {
        val access = requestAccess.require(exchange, workspaceId)
        val parsedPageId = parsePageId(pageId)
        val outcome = useCase.updateNavigationMetadata(
            PagesDirectoryUseCase.UpdateNavigationMetadataCommand(
                workspaceId = access.workspaceId,
                pageId = parsedPageId,
                title = request.title,
                description = request.description,
                baseRevision = revision(request.baseRevision),
                requestId = requestId(request.requestId),
                actor = access.actor,
                agentClientId = null,
                auditActorContext = access.auditActorContext,
                requestedAt = Instant.now(),
            ),
        )
        return outcome.toHttpResponse(request.requestId, creation = false, access)
    }

    @DeleteMapping("/{pageId}")
    suspend fun deletePage(
        @PathVariable workspaceId: String,
        @PathVariable pageId: String,
        @RequestBody request: DeletePageRequest,
        exchange: ServerWebExchange,
    ): ResponseEntity<Any> {
        val access = requestAccess.require(exchange, workspaceId)
        val parsedPageId = parsePageId(pageId)
        val outcome = useCase.deletePage(
            PagesDirectoryUseCase.DeletePageCommand(
                workspaceId = access.workspaceId,
                pageId = parsedPageId,
                baseRevision = revision(request.baseRevision),
                requestId = requestId(request.requestId),
                actor = access.actor,
                auditActorContext = access.auditActorContext,
                requestedAt = Instant.now(),
            ),
        )
        return outcome.toHttpResponse(request.requestId, creation = false, access)
    }

    private suspend fun PagesDirectoryMutationOutcome.toHttpResponse(
        requestId: String,
        creation: Boolean,
        access: PagesAccess,
    ): ResponseEntity<Any> = when (this) {
        PagesDirectoryMutationOutcome.NotFound -> notFound("DASHBOARD_NOT_FOUND", "Dashboard was not found.")
        is PagesDirectoryMutationOutcome.Applied -> {
            val projection = useCase.projectSnapshot(snapshot)
            val response = PageMutationResponse(
                "APPLIED",
                projection.toResponse(),
                change.toResponse(),
            )
            if (creation) {
                ResponseEntity.created(URI.create("/v2/workspaces/${access.workspaceId}/pages/${change.createdPageId()}")).body(response)
            } else {
                ResponseEntity.ok(response)
            }
        }
        is PagesDirectoryMutationOutcome.Replayed -> {
            val projection = useCase.projectSnapshot(snapshot)
            ResponseEntity.ok(
                PageMutationResponse("REPLAYED", projection.toResponse(), change.toResponse()),
            )
        }
        is PagesDirectoryMutationOutcome.RevisionConflict -> throw PagesRestProblem(
            HttpStatus.CONFLICT,
            "REVISION_CONFLICT",
            useCase.projectSnapshot(snapshot).toResponse(),
            requestId,
            emptyMap(),
            "PagesDirectory revision is stale.",
        )
        is PagesDirectoryMutationOutcome.RequestIdReused -> throw PagesRestProblem(
            HttpStatus.CONFLICT,
            "REQUEST_ID_REUSED",
            useCase.projectSnapshot(snapshot).toResponse(),
            requestId,
            mapOf("existingRequestHash" to existingRequestHash),
            "requestId was already used with a different body.",
        )
        is PagesDirectoryMutationOutcome.Rejected -> throw reason.toProblem(
            useCase.projectSnapshot(snapshot).toResponse(),
            requestId,
        )
    }

    private fun PageDirectoryRequestRejection.toProblem(
        current: PagesTreeResponse,
        requestId: String,
    ): PagesRestProblem = when (this) {
        is PageDirectoryRequestRejection.MetadataInvalid -> PagesRestProblem(
            HttpStatus.BAD_REQUEST,
            reason.name,
            current,
            requestId,
            emptyMap(),
            "Page navigation metadata is invalid.",
        )
        is PageDirectoryRequestRejection.PatchInvalid -> PagesRestProblem(
            HttpStatus.BAD_REQUEST,
            error.code,
            current,
            requestId,
            mapOf("path" to error.path, "operationIndex" to error.operationIndex),
            error.message,
        )
        is PageDirectoryRequestRejection.ContentUploadInvalid -> {
            val status = when (reason) {
                cc.monomer.pages.domain.content.PageContentConsumptionRejection.UPLOAD_NOT_FOUND ->
                    HttpStatus.NOT_FOUND
                cc.monomer.pages.domain.content.PageContentConsumptionRejection.UPLOAD_EXPIRED ->
                    HttpStatus.GONE
                cc.monomer.pages.domain.content.PageContentConsumptionRejection.UPLOAD_NOT_COMPLETED,
                cc.monomer.pages.domain.content.PageContentConsumptionRejection.UPLOAD_ALREADY_CONSUMED,
                cc.monomer.pages.domain.content.PageContentConsumptionRejection.UPLOAD_ABORTED,
                -> HttpStatus.CONFLICT
            }
            PagesRestProblem(
                status,
                reason.name,
                current,
                requestId,
                emptyMap(),
                "Page content upload cannot be consumed.",
            )
        }
        is PageDirectoryRequestRejection.DirectoryInvalid -> {
            val status = when (reason) {
                PagesDirectoryRejection.PAGE_NOT_FOUND,
                PagesDirectoryRejection.PARENT_NOT_FOUND,
                -> HttpStatus.NOT_FOUND
                PagesDirectoryRejection.PLACEMENTS_PAYLOAD_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE
                else -> HttpStatus.UNPROCESSABLE_ENTITY
            }
            PagesRestProblem(
                status,
                reason.name,
                current,
                requestId,
                emptyMap(),
                "PagesDirectory mutation is invalid.",
            )
        }
    }

    private fun List<Map<String, Any?>>.toValidatedPatch(): List<JsonObject> {
        if (isEmpty()) invalid("PATCH_REQUIRED", "At least one patch operation is required.")
        if (size > MAX_PATCH_OPERATION_COUNT) {
            throw PagesRestProblem(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "PATCH_OPERATION_LIMIT_EXCEEDED",
                null,
                null,
                mapOf("maximum" to MAX_PATCH_OPERATION_COUNT),
                "Patch operation limit was exceeded.",
            )
        }
        forEachIndexed { index, operation ->
            val path = operation["path"]
            if (path !is String) invalid("INVALID_PATCH_PATH", "Patch operation $index requires a string path.")
            if (path.length > MAX_JSON_POINTER_LENGTH) {
                invalid("PATCH_PATH_TOO_LONG", "Patch operation $index path is too long.")
            }
            if (operation.maxJsonDepth(1) > MAX_PATCH_JSON_DEPTH) {
                invalid("PATCH_DEPTH_LIMIT_EXCEEDED", "Patch operation $index is nested too deeply.")
            }
        }
        val patch = map { operation -> JsonObject(operation.toJsonElementMap()) }
        if (JsonArray(patch).toString().toByteArray(Charsets.UTF_8).size > MAX_PATCH_BODY_BYTES) {
            throw PagesRestProblem(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "PATCH_BODY_TOO_LARGE",
                null,
                null,
                mapOf("maximumBytes" to MAX_PATCH_BODY_BYTES),
                "Patch body is too large.",
            )
        }
        return patch
    }

    private fun Map<String, Any?>.maxJsonDepth(depth: Int): Int =
        values.maxOfOrNull { value -> value.maxJsonDepth(depth + 1) } ?: depth

    private fun Any?.maxJsonDepth(depth: Int): Int = when (this) {
        is Map<*, *> -> values.maxOfOrNull { value -> value.maxJsonDepth(depth + 1) } ?: depth
        is List<*> -> maxOfOrNull { value -> value.maxJsonDepth(depth + 1) } ?: depth
        else -> depth
    }

    private fun Map<String, Any?>.toJsonElementMap(): Map<String, JsonElement> =
        entries.associate { (key, value) -> key to value.toJsonElement() }

    private fun Any?.toJsonElement(): JsonElement =
        when (this) {
            null -> JsonNull
            is String -> JsonPrimitive(this)
            is Boolean -> JsonPrimitive(this)
            is Number -> JsonPrimitive(this)
            is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.toJsonElement() })
            is List<*> -> JsonArray(map { value -> value.toJsonElement() })
            else -> JsonPrimitive(toString())
        }

    private fun pageKind(raw: String): PageKind = runCatching { PageKind.valueOf(raw) }
        .getOrElse { invalid("INVALID_PAGE_KIND", "Page kind is invalid.") }

    private fun parsePageId(raw: String): PageId = PageId(canonicalUuid(raw, "INVALID_PAGE_ID"))

    private fun parseDashboardId(raw: String): DashboardId = try {
        DashboardId(canonicalUuid(raw, "INVALID_DASHBOARD_ID"))
    } catch (_: IllegalArgumentException) {
        invalid("INVALID_DASHBOARD_ID", "dashboardId must be a canonical UUIDv7.")
    }

    private fun parseUploadRef(raw: String): PageContentUploadRef =
        PageContentUploadRef(canonicalUuid(raw, "INVALID_UPLOAD_REF"))

    private fun revision(raw: Long): PagesDirectoryRevision = runCatching { PagesDirectoryRevision(raw) }
        .getOrElse { invalid("INVALID_BASE_REVISION", "baseRevision must be non-negative.") }

    private fun requestId(raw: String): PageDirectoryRequestId = runCatching { PageDirectoryRequestId(raw) }
        .getOrElse { invalid("INVALID_REQUEST_ID", "requestId must contain between 1 and 200 characters.") }

    private fun canonicalUuid(raw: String, code: String): Uuid {
        val parsed = runCatching { Uuid.parse(raw) }.getOrElse { invalid(code, "Identifier must be a canonical UUID.") }
        if (parsed.toString() != raw) invalid(code, "Identifier must be a canonical UUID.")
        return parsed
    }

    private fun validateIndex(index: Int?) {
        if (index != null && index !in 0..500) invalid("INVALID_INSERT_INDEX", "index must be between 0 and 500.")
    }

    private fun PageDirectoryChange.createdPageId(): PageId = when (this) {
        is PageDirectoryChange.FolderCreated -> pageId
        is PageDirectoryChange.DashboardPageCreated -> pageId
        is PageDirectoryChange.DocumentPageCreated -> pageId
        else -> error("Creation outcome does not identify a created Page")
    }

    private companion object {
        const val MAX_PATCH_OPERATION_COUNT: Int = 100
        const val MAX_JSON_POINTER_LENGTH: Int = 1_024
        const val MAX_PATCH_JSON_DEPTH: Int = 16
        const val MAX_PATCH_BODY_BYTES: Int = 512 * 1_024

        fun invalid(code: String, message: String): Nothing = throw PagesRestProblem(
            HttpStatus.BAD_REQUEST,
            code,
            null,
            null,
            emptyMap(),
            message,
        )

        fun notFound(code: String, message: String): Nothing = throw PagesRestProblem(
            HttpStatus.NOT_FOUND,
            code,
            null,
            null,
            emptyMap(),
            message,
        )
    }
}
