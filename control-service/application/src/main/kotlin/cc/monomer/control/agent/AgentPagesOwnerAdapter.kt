@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.agent

import cc.monomer.agent.api.*
import cc.monomer.control.WorkspaceRoleAdmissionRequest
import cc.monomer.control.WorkspaceMembershipAdmissionRequest
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardOwnerOutcome
import cc.monomer.dashboard.api.DashboardOwnerPort
import cc.monomer.identity.api.AgentClientId
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.IdentityRoleEvidence
import cc.monomer.pages.application.PagesDirectoryMutationOutcome
import cc.monomer.pages.application.PagesDirectoryProjection
import cc.monomer.pages.application.PagesDirectoryUseCase
import cc.monomer.pages.application.content.*
import cc.monomer.pages.domain.*
import cc.monomer.pages.domain.audit.AuditActorContext
import cc.monomer.pages.domain.content.*
import java.time.Clock
import java.util.Base64
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AgentPagesOwnerAdapter(
    private val directory: PagesDirectoryUseCase,
    private val content: PageContentUseCase,
    private val uploads: PageContentUploadUseCase,
    private val dashboard: DashboardOwnerPort,
    private val admission: ControlAdmissionFacade,
    private val clock: Clock,
    private val reader: AgentPageReader,
    private val provenanceNames: AgentProvenanceNames,
) : AgentPagesOwnerPort {
    override suspend fun list(caller: AgentWorkspaceCaller, request: AgentListRequest): AgentListingOutcome =
        if (AgentPagesPaths.sessions.matches(request.path.value) || AgentPagesPaths.runs.matches(request.path.value)) reader.list(caller, request, null)
        else reader.snapshot { listInSnapshot(caller, request) } ?: reader.list(caller, request, null)

    private suspend fun listInSnapshot(caller: AgentWorkspaceCaller, request: AgentListRequest): AgentListingOutcome? {
        if (request.path.value != PAGES && !AgentPagesPaths.versions.matches(request.path.value)) return AgentListingOutcome.NotFound
        val membership = when (val decision = membershipAdmission(caller)) {
            is ControlAdmissionDecision.Admitted -> decision.context.takeIf { it.actor.id.value.toString() == caller.actorPrincipalId }
                ?: return AgentListingOutcome.Forbidden
            is ControlAdmissionDecision.Denied -> return decision.toAgentListingDenial()
        }
        if (membership.role == WorkspaceRole.VIEWER) return if (request.path.value == PAGES) null else AgentListingOutcome.Forbidden
        val projection = directory.getProjection(caller.workspaceId.value)
        if (request.path.value == PAGES) {
            val after = try {
                request.cursor?.let { cursor ->
                    val segments = decodeCursor(cursor).split(':')
                    require(segments.size == 5 && segments[0] == "1" && segments[1] == "pages" && segments[2] == caller.workspaceId.toString())
                    val revision = segments[3].toLongOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException()
                    val pageId = Uuid.parse(segments[4])
                    require(segments[4] == pageId.toString() && pageCursor(caller, revision, pageId) == cursor)
                    if (revision != projection.snapshot.revision.value) return AgentListingOutcome.Rejected(problem("CURSOR_STALE", AgentWorkspaceProblemStatus.CONFLICT, projection.snapshot.revision.value))
                    pageId
                }
            } catch (_: IllegalArgumentException) {
                return AgentListingOutcome.Rejected(AgentOwnerProblems.invalidCursor)
            }
            val remaining = projection.snapshot.pages.sortedBy { it.id.toString() }.filter { after == null || it.id.toString() > after.toString() }
            val page = remaining.take(request.limit)
            val provenance = pageProvenance(page)
            return AgentListingOutcome.Found(AgentResourceListingResponse(
                items = page.map { AgentResourceListingItem.Page(pagePath(it.id), pageBody(it, projection, provenance)) },
                nextCursor = if (remaining.size > page.size) pageCursor(caller, projection.snapshot.revision.value, page.last().id.value) else null,
                availableOperations = buildList {
                    add(AgentAvailableOperation.Read(treePath()))
                    listOf("FOLDER_PAGE", "DOCUMENT_PAGE", "DASHBOARD_PAGE").forEach { add(AgentAvailableOperation.Create(request.path, it)) }
                    add(AgentAvailableOperation.Create(AgentResourcePath.parse(UPLOADS), "PAGE_CONTENT_UPLOAD"))
                },
            ))
        }
        val pageId = PageId(Uuid.parse(request.path.value.split('/')[2]))
        if (projection.snapshot.pages.none { it.id == pageId && it.kind == PageKind.DOCUMENT }) return AgentListingOutcome.NotFound
        val before = try {
            request.cursor?.let { cursor ->
                val prefix = "1:versions:${caller.workspaceId}:$pageId:"
                val raw = decodeCursor(cursor)
                require(raw.startsWith(prefix))
                val number = raw.removePrefix(prefix).toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException()
                require(versionCursor(caller, pageId, number) == cursor)
                number
            }
        } catch (_: IllegalArgumentException) {
            return AgentListingOutcome.Rejected(AgentOwnerProblems.invalidCursor)
        }
        val versions = content.listVersions(caller.workspaceId.value, pageId, before, request.limit)
        val provenance = versionProvenance(versions.items)
        return AgentListingOutcome.Found(AgentResourceListingResponse(
            items = versions.items.map { AgentResourceListingItem.PageVersion(versionPath(it), it.body(provenance)) },
            nextCursor = versions.nextBeforeVersionNumber?.let { versionCursor(caller, pageId, it) },
            availableOperations = listOf(AgentAvailableOperation.Create(request.path, "PAGE_VERSION")),
        ))
    }

    override suspend fun read(caller: AgentWorkspaceCaller, request: AgentReadRequest): AgentResourceOutcome =
        if (AgentPagesPaths.content.matches(request.path.value) || AgentPagesPaths.session.matches(request.path.value) || AgentPagesPaths.run.matches(request.path.value) || AgentPagesPaths.result.matches(request.path.value)) reader.read(caller, request, null)
        else reader.snapshot { readInSnapshot(caller, request) } ?: reader.read(caller, request, null)

    private suspend fun readInSnapshot(caller: AgentWorkspaceCaller, request: AgentReadRequest): AgentResourceOutcome? {
        if (request.download != null || (request.path.value != TREE && !AgentPagesPaths.page.matches(request.path.value) && !AgentPagesPaths.version.matches(request.path.value))) return AgentResourceOutcome.NotFound
        val membership = when (val decision = membershipAdmission(caller)) {
            is ControlAdmissionDecision.Admitted -> decision.context.takeIf { it.actor.id.value.toString() == caller.actorPrincipalId }
                ?: return AgentResourceOutcome.Forbidden
            is ControlAdmissionDecision.Denied -> return decision.toAgentResourceDenial()
        }
        if (membership.role == WorkspaceRole.VIEWER) return if (AgentPagesPaths.version.matches(request.path.value)) AgentResourceOutcome.Forbidden else null
        val projection = directory.getProjection(caller.workspaceId.value)
        if (request.path.value == TREE) return AgentResourceOutcome.Found(AgentResourceEnvelope.PagesTree(
            path = request.path,
            revision = AgentRevision(projection.snapshot.revision.value),
            body = AgentPagesTreeResourceBody(
                pageProvenance(projection.snapshot.pages).let { provenance ->
                    projection.snapshot.pages.map { AgentResourceListingItem.Page(pagePath(it.id), pageBody(it, projection, provenance)) }
                },
                projection.snapshot.placements.map(::placement),
            ),
            availableOperations = listOf(AgentAvailableOperation.Edit(request.path)),
        ))
        val pageId = PageId(Uuid.parse(request.path.value.split('/')[2]))
        val page = projection.snapshot.pages.find { it.id == pageId } ?: return AgentResourceOutcome.NotFound
        if (AgentPagesPaths.version.matches(request.path.value)) {
            if (page.kind != PageKind.DOCUMENT) return AgentResourceOutcome.NotFound
            val version = content.getVersion(caller.workspaceId.value, pageId, request.path.value.substringAfterLast('/').toInt()) ?: return AgentResourceOutcome.NotFound
            return AgentResourceOutcome.Found(AgentResourceEnvelope.PageVersion(request.path, version.body(versionProvenance(listOf(version))), emptyList()))
        }
        return AgentResourceOutcome.Found(AgentResourceEnvelope.Page(
            request.path, AgentRevision(projection.snapshot.revision.value), pageBody(page, projection, pageProvenance(listOf(page))),
            buildList {
                add(AgentAvailableOperation.Read(treePath()))
                if (page.kind == PageKind.DOCUMENT) {
                    add(AgentAvailableOperation.Read(AgentResourcePath.parse("${request.path.value}/content")))
                    add(AgentAvailableOperation.List(AgentResourcePath.parse("${request.path.value}/versions")))
                    add(AgentAvailableOperation.Create(AgentResourcePath.parse("${request.path.value}/versions"), "PAGE_VERSION"))
                }
                if (page.kind == PageKind.DASHBOARD) {
                    add(AgentAvailableOperation.List(AgentResourcePath.parse("${request.path.value}/sessions")))
                    add(AgentAvailableOperation.Read(AgentResourcePath.parse("workspace/dashboards/${page.dashboardId}")))
                }
                add(AgentAvailableOperation.Edit(request.path))
                add(AgentAvailableOperation.Invoke(request.path, "delete", "PAGE_DELETE"))
            },
        ))
    }

    override suspend fun create(caller: AgentWorkspaceCaller, request: AgentCreateRequest): AgentActionOutcome {
        if (!validCreate(request)) return AgentActionOutcome.NotFound
        val evidence = when (val decision = editAdmission(caller)) {
            is ControlAdmissionDecision.Admitted -> decision.context.takeIf { it.actor.id.value.toString() == caller.actorPrincipalId }
                ?: return AgentActionOutcome.Forbidden
            is ControlAdmissionDecision.Denied -> return decision.toAgentActionDenial()
        }
        val actor = actor(evidence)
        val agentClientId = evidence.agentClientId?.value
        val audit = audit(evidence)
        val workspace = caller.workspaceId.value
        val now = clock.instant()
        return when (val input = request.input) {
            is AgentCreateInput.FolderPage -> directory.createFolder(PagesDirectoryUseCase.CreateFolderCommand(
                workspace, input.title, input.description, input.parentPageId?.let(::PageId), input.index,
                PagesDirectoryRevision(input.baseRevision), PageDirectoryRequestId(input.commandId), actor, agentClientId, audit, now,
            )).action(request.path, AgentWorkspaceOperation.CREATE)
            is AgentCreateInput.DocumentPage -> directory.createDocumentPage(PagesDirectoryUseCase.CreateDocumentPageCommand(
                workspace, input.title, input.description, input.parentPageId?.let(::PageId), input.index, PageContentUploadRef(input.uploadRef),
                PagesDirectoryRevision(input.baseRevision), PageDirectoryRequestId(input.commandId), actor, agentClientId, audit, now,
            )).action(request.path, AgentWorkspaceOperation.CREATE)
            is AgentCreateInput.DashboardPage -> {
                when (dashboard.read(caller.workspaceId, DashboardId(input.dashboardId))) {
                    is DashboardOwnerOutcome.Success -> Unit
                    DashboardOwnerOutcome.NotFound -> return AgentActionOutcome.NotFound
                    is DashboardOwnerOutcome.Conflict -> return rejected("DASHBOARD_CONFLICT", AgentWorkspaceProblemStatus.CONFLICT)
                    is DashboardOwnerOutcome.Rejected -> return rejected("DASHBOARD_REJECTED", AgentWorkspaceProblemStatus.BAD_REQUEST)
                }
                directory.createDashboardPage(PagesDirectoryUseCase.CreateDashboardPageCommand(
                    workspace, input.dashboardId, input.title, input.description, input.parentPageId?.let(::PageId), input.index,
                    PagesDirectoryRevision(input.baseRevision), PageDirectoryRequestId(input.commandId), actor, agentClientId, audit, now,
                )).action(request.path, AgentWorkspaceOperation.CREATE)
            }
            is AgentCreateInput.PageVersion -> {
                val pageId = PageId(Uuid.parse(request.path.value.split('/')[2]))
                when (val outcome = content.replace(PageContentUseCase.ReplaceCommand(workspace, pageId, PageContentUploadRef(input.uploadRef), input.commandId, actor, agentClientId, audit, now))) {
                    is PageContentMutationOutcome.Applied -> versionCreated(request.path, outcome.version)
                    is PageContentMutationOutcome.Replayed -> versionCreated(request.path, outcome.version)
                    PageContentMutationOutcome.PageNotFound -> AgentActionOutcome.NotFound
                    is PageContentMutationOutcome.RequestIdReused -> rejected("COMMAND_ID_REUSED", AgentWorkspaceProblemStatus.CONFLICT)
                    is PageContentMutationOutcome.UploadRejected -> uploadRejected(outcome.error)
                }
            }
            is AgentCreateInput.PageContentUpload -> when (val outcome = uploads.createSession(PageContentUploadUseCase.CreateSessionCommand(
                workspace, actor, PageContentFormat.valueOf(input.format.name), input.byteSize, PageContentHash(input.sha256), now,
            ))) {
                is PageContentUploadCreateOutcome.Created -> AgentActionOutcome.Completed(AgentActionResult.PageUploadPrepared(
                    request.path, uploadReference(outcome.upload), outcome.upload.metadata(), PageContentUploadUseCaseImpl.MULTIPART_PART_BYTES,
                    outcome.parts.map { AgentPageUploadPart(it.partNumber, it.contentLength, it.uploadUrl, mapOf("Content-Length" to it.contentLength.toString())) },
                ))
                is PageContentUploadCreateOutcome.Rejected -> rejected(outcome.error.code.name, uploadStatus(outcome.error.code))
            }
            else -> AgentActionOutcome.NotFound
        }
    }

    override suspend fun edit(caller: AgentWorkspaceCaller, request: AgentEditRequest): AgentActionOutcome {
        if (!AgentPagesPaths.editable(request.path.value)) return AgentActionOutcome.NotFound
        val revision = request.revision ?: return rejected("REVISION_REQUIRED", AgentWorkspaceProblemStatus.BAD_REQUEST)
        val commandId = request.commandId ?: return rejected("COMMAND_ID_REQUIRED", AgentWorkspaceProblemStatus.BAD_REQUEST)
        val evidence = when (val decision = editAdmission(caller)) {
            is ControlAdmissionDecision.Admitted -> decision.context.takeIf { it.actor.id.value.toString() == caller.actorPrincipalId }
                ?: return AgentActionOutcome.Forbidden
            is ControlAdmissionDecision.Denied -> return decision.toAgentActionDenial()
        }
        val outcome = if (request.path.value == TREE) {
            if (request.patch.any { it.path != "/placements" && !it.path.startsWith("/placements/") }) return rejected("PATCH_INVALID", AgentWorkspaceProblemStatus.BAD_REQUEST)
            directory.patchPlacements(PagesDirectoryUseCase.PatchPlacementsCommand(
                caller.workspaceId.value,
                request.patch.map { operation -> buildJsonObject {
                    put("op", operation.op.name.lowercase())
                    put("path", operation.path.removePrefix("/placements"))
                    operation.value?.let { put("value", it) }
                } },
                PagesDirectoryRevision(revision.value), PageDirectoryRequestId(commandId), actor(evidence), audit(evidence), clock.instant(),
            ))
        } else {
            if (request.patch.size != 2 || request.patch.any { it.op != AgentJsonPatchOp.REPLACE } || request.patch.map { it.path }.toSet() != setOf("/title", "/description")) return rejected("PATCH_INVALID", AgentWorkspaceProblemStatus.BAD_REQUEST)
            val title = request.patch.single { it.path == "/title" }.value as? JsonPrimitive
            val description = request.patch.single { it.path == "/description" }.value as? JsonPrimitive
            if (title == null || !title.isString || description == null || (description != JsonNull && !description.isString)) return rejected("PATCH_INVALID", AgentWorkspaceProblemStatus.BAD_REQUEST)
            directory.updateNavigationMetadata(PagesDirectoryUseCase.UpdateNavigationMetadataCommand(
                caller.workspaceId.value, PageId(Uuid.parse(request.path.value.substringAfterLast('/'))), title.content,
                description.takeUnless { it == JsonNull }?.content, PagesDirectoryRevision(revision.value), PageDirectoryRequestId(commandId), actor(evidence),
                evidence.agentClientId?.value, audit(evidence), clock.instant(),
            ))
        }
        return outcome.action(request.path, AgentWorkspaceOperation.EDIT)
    }

    override suspend fun invoke(caller: AgentWorkspaceCaller, request: AgentInvokeRequest): AgentActionOutcome {
        if (AgentPagesPaths.session.matches(request.path.value) && request.action == "resolve") return reader.resolve(caller, request)
        if (!AgentPagesPaths.invokable(request.path.value, request.action)) return AgentActionOutcome.NotFound
        if ((request.action == "delete" && request.input !is AgentInvokeInput.PageDelete) || (request.action == "complete" && request.input !is AgentInvokeInput.PageContentUploadComplete) || (request.action == "abort" && request.input != AgentInvokeInput.None)) return AgentActionOutcome.NotFound
        val evidence = when (val decision = editAdmission(caller)) {
            is ControlAdmissionDecision.Admitted -> decision.context.takeIf { it.actor.id.value.toString() == caller.actorPrincipalId }
                ?: return AgentActionOutcome.Forbidden
            is ControlAdmissionDecision.Denied -> return decision.toAgentActionDenial()
        }
        val workspace = caller.workspaceId.value
        val actor = actor(evidence)
        val now = clock.instant()
        if (request.action == "delete") {
            val input = request.input as AgentInvokeInput.PageDelete
            return directory.deletePage(PagesDirectoryUseCase.DeletePageCommand(workspace, PageId(Uuid.parse(request.path.value.substringAfterLast('/'))), PagesDirectoryRevision(input.baseRevision), PageDirectoryRequestId(input.commandId), actor, audit(evidence), now)).action(request.path, AgentWorkspaceOperation.INVOKE)
        }
        val uploadRef = PageContentUploadRef(Uuid.parse(request.path.value.substringAfterLast('/')))
        if (request.action == "complete") return when (val outcome = uploads.complete(PageContentUploadUseCase.CompleteCommand(
            workspace, actor, uploadRef, (request.input as AgentInvokeInput.PageContentUploadComplete).parts.map { PageContentUploadUseCase.CompletedPartCommand(it.partNumber, it.eTag) }, now,
        ))) {
            is PageContentUploadCompleteOutcome.Completed -> uploadCompleted(request.path, outcome.upload)
            is PageContentUploadCompleteOutcome.Replayed -> uploadCompleted(request.path, outcome.upload)
            PageContentUploadCompleteOutcome.NotFound -> AgentActionOutcome.NotFound
            PageContentUploadCompleteOutcome.InProgress -> rejected("UPLOAD_IN_PROGRESS", AgentWorkspaceProblemStatus.CONFLICT)
            PageContentUploadCompleteOutcome.RequestReused -> rejected("UPLOAD_COMPLETION_REUSED", AgentWorkspaceProblemStatus.CONFLICT)
            is PageContentUploadCompleteOutcome.Rejected -> rejected(outcome.error.code.name, uploadStatus(outcome.error.code))
        }
        return when (uploads.abort(PageContentUploadUseCase.AbortCommand(workspace, actor, uploadRef, now))) {
            PageContentUploadAbortOutcome.Aborted, PageContentUploadAbortOutcome.Replayed -> AgentActionOutcome.Completed(AgentActionResult.Invoke(request.path, "abort", AgentActionStatus.COMPLETED, treeReference()))
            PageContentUploadAbortOutcome.NotFound -> AgentActionOutcome.NotFound
            PageContentUploadAbortOutcome.InProgress -> rejected("UPLOAD_IN_PROGRESS", AgentWorkspaceProblemStatus.CONFLICT)
            PageContentUploadAbortOutcome.AlreadyConsumed -> rejected("UPLOAD_ALREADY_CONSUMED", AgentWorkspaceProblemStatus.CONFLICT)
        }
    }

    private suspend fun membershipAdmission(caller: AgentWorkspaceCaller) =
        admission.authorizeWorkspaceMembership(
            WorkspaceMembershipAdmissionRequest(caller.workspaceId, caller.presentedCredential, caller.expectedOAuthResource),
        )

    private suspend fun editAdmission(caller: AgentWorkspaceCaller) = admission.authorizeWorkspaceRole(
        WorkspaceRoleAdmissionRequest(caller.workspaceId, caller.presentedCredential, caller.expectedOAuthResource, WorkspaceRole.EXPLORER),
    )

    /** The person acting through this agent connection; AGENT keeps their writes marked "via agent" and filterable. */
    private fun actor(evidence: IdentityRoleEvidence) = PageActor.create(
        if (evidence.credentialSurface == cc.monomer.identity.api.CredentialSurface.AGENT) PageActorKind.AGENT else PageActorKind.USER,
        evidence.actor.id.value.toString(),
    )
    private fun audit(evidence: IdentityRoleEvidence) = AuditActorContext(evidence.organizationId.value, evidence.actor.id.value, evidence.actor.id.value.toString())
    private fun validCreate(request: AgentCreateRequest): Boolean = when (request.input) {
        is AgentCreateInput.FolderPage, is AgentCreateInput.DocumentPage, is AgentCreateInput.DashboardPage -> request.path.value == PAGES
        is AgentCreateInput.PageVersion -> AgentPagesPaths.versions.matches(request.path.value)
        is AgentCreateInput.PageContentUpload -> request.path.value == UPLOADS
        else -> false
    }

    private suspend fun pageBody(page: Page, projection: PagesDirectoryProjection, provenance: AgentProvenance): AgentPageResourceBody {
        val latest = if (page.kind == PageKind.DOCUMENT) content.listVersions(page.workspaceId, page.id, null, 1).items.firstOrNull() else null
        return AgentPageResourceBody(AgentPageKind.valueOf(page.kind.name), page.navigationMetadata.title, page.navigationMetadata.description,
            projection.displayTitle(page.id), page.dashboardId?.let { AgentActionResultReference(AgentResourceKind.DASHBOARD, AgentResourcePath.parse("workspace/dashboards/$it")) },
            latest?.let { AgentActionResultReference(AgentResourceKind.PAGE_VERSION, versionPath(it)) }, page.createdAt, page.updatedAt, latest?.format?.name?.let(AgentPageContentFormat::valueOf),
            provenance.pageActor(page.createdBy, page.createdByAgentClientId), provenance.pageActor(page.updatedBy, page.updatedByAgentClientId))
    }

    private suspend fun pageProvenance(pages: List<Page>): AgentProvenance = provenanceNames.read(
        pages.flatMap { listOf(it.createdBy, it.updatedBy) }.personIds(),
        pages.flatMap { listOf(it.createdByAgentClientId, it.updatedByAgentClientId) }.map { it?.let(::AgentClientId) },
    )

    private suspend fun versionProvenance(versions: List<PageVersionMetadata>): AgentProvenance = provenanceNames.read(
        versions.map { it.finalizedBy }.personIds(),
        versions.map { version -> version.finalizedByAgentClientId?.let(::AgentClientId) },
    )

    private fun List<PageActor>.personIds(): List<String> = filter { it.kind != PageActorKind.SYSTEM }.map { it.id }
    private fun AgentProvenance.pageActor(actor: PageActor, agentClientId: String?) = pageActor(AgentPageActorKind.valueOf(actor.kind.name), actor.id, agentClientId)

    private fun placement(value: PagePlacement): AgentPagePlacement = AgentPagePlacement(value.pageId.value, value.children.map(::placement))
    private fun PageVersionMetadata.body(provenance: AgentProvenance) = AgentPageVersionResourceBody(
        AgentPageContentFormat.valueOf(format.name), contentSizeBytes, contentHash.value, finalizedAt, provenance.pageActor(finalizedBy, finalizedByAgentClientId),
    )
    private fun PageContentUploadView.metadata() = AgentPageUploadMetadata(uploadRef.value, AgentPageContentFormat.valueOf(format.name), byteSize, sha256.value, expiresAt)
    private fun pagePath(id: PageId) = AgentResourcePath.parse("$PAGES/$id")
    private fun versionPath(version: PageVersionMetadata) = AgentResourcePath.parse("$PAGES/${version.pageId}/versions/${version.versionNumber.value}")
    private fun treePath() = AgentResourcePath.parse(TREE)
    private fun treeReference() = AgentActionResultReference(AgentResourceKind.PAGES_TREE, treePath())
    private fun uploadReference(upload: PageContentUploadView) = AgentActionResultReference(AgentResourceKind.PAGE_CONTENT_UPLOAD, AgentResourcePath.parse("$UPLOADS/${upload.uploadRef}"))
    private fun versionCreated(path: AgentResourcePath, version: PageVersionMetadata) = AgentActionOutcome.Completed(AgentActionResult.Create(path, AgentActionResultReference(AgentResourceKind.PAGE_VERSION, versionPath(version))))
    private fun uploadCompleted(path: AgentResourcePath, upload: PageContentUploadView) = AgentActionOutcome.Completed(AgentActionResult.PageUploadCompleted(path, uploadReference(upload), upload.metadata()))

    private fun PagesDirectoryMutationOutcome.action(path: AgentResourcePath, operation: AgentWorkspaceOperation): AgentActionOutcome = when (this) {
        PagesDirectoryMutationOutcome.NotFound -> AgentActionOutcome.NotFound
        is PagesDirectoryMutationOutcome.Applied -> changed(path, operation, change)
        is PagesDirectoryMutationOutcome.Replayed -> changed(path, operation, change)
        is PagesDirectoryMutationOutcome.RevisionConflict -> rejected("REVISION_CONFLICT", AgentWorkspaceProblemStatus.CONFLICT, snapshot.revision.value)
        is PagesDirectoryMutationOutcome.RequestIdReused -> rejected("COMMAND_ID_REUSED", AgentWorkspaceProblemStatus.CONFLICT, snapshot.revision.value)
        is PagesDirectoryMutationOutcome.Rejected -> when (val rejection = reason) {
            is PageDirectoryRequestRejection.MetadataInvalid -> rejected(rejection.reason.name, AgentWorkspaceProblemStatus.BAD_REQUEST, snapshot.revision.value)
            is PageDirectoryRequestRejection.PatchInvalid -> rejected(rejection.error.code, AgentWorkspaceProblemStatus.BAD_REQUEST, snapshot.revision.value)
            is PageDirectoryRequestRejection.ContentUploadInvalid -> uploadRejected(rejection.reason)
            is PageDirectoryRequestRejection.DirectoryInvalid -> if (rejection.reason in setOf(PagesDirectoryRejection.PAGE_NOT_FOUND, PagesDirectoryRejection.PARENT_NOT_FOUND)) AgentActionOutcome.NotFound else rejected(rejection.reason.name, AgentWorkspaceProblemStatus.BAD_REQUEST, snapshot.revision.value)
        }
    }

    private fun changed(path: AgentResourcePath, operation: AgentWorkspaceOperation, change: PageDirectoryChange): AgentActionOutcome {
        val reference = when (change) {
            is PageDirectoryChange.FolderCreated -> AgentActionResultReference(AgentResourceKind.PAGE, pagePath(change.pageId))
            is PageDirectoryChange.DocumentPageCreated -> AgentActionResultReference(AgentResourceKind.PAGE, pagePath(change.pageId))
            is PageDirectoryChange.DashboardPageCreated -> AgentActionResultReference(AgentResourceKind.PAGE, pagePath(change.pageId))
            is PageDirectoryChange.NavigationMetadataUpdated -> AgentActionResultReference(AgentResourceKind.PAGE, pagePath(change.pageId))
            is PageDirectoryChange.PageDeleted, is PageDirectoryChange.PlacementsPatched -> treeReference()
        }
        return AgentActionOutcome.Completed(when (operation) {
            AgentWorkspaceOperation.CREATE -> AgentActionResult.Create(path, reference)
            AgentWorkspaceOperation.EDIT -> AgentActionResult.Edit(path, reference)
            AgentWorkspaceOperation.INVOKE -> AgentActionResult.Invoke(path, "delete", AgentActionStatus.COMPLETED, reference)
            else -> error("Pages mutation operation is invalid")
        })
    }

    private fun uploadRejected(error: PageContentConsumptionRejection): AgentActionOutcome = if (error == PageContentConsumptionRejection.UPLOAD_NOT_FOUND) AgentActionOutcome.NotFound else rejected(error.name, AgentWorkspaceProblemStatus.CONFLICT)
    private fun uploadStatus(code: PageContentUploadErrorCode): AgentWorkspaceProblemStatus = when (code) {
        PageContentUploadErrorCode.UPLOAD_EXPIRED, PageContentUploadErrorCode.UPLOAD_ABORTED -> AgentWorkspaceProblemStatus.CONFLICT
        else -> AgentWorkspaceProblemStatus.BAD_REQUEST
    }
    private fun rejected(code: String, status: AgentWorkspaceProblemStatus, revision: Long? = null) = AgentActionOutcome.Rejected(problem(code, status, revision))
    private fun problem(code: String, status: AgentWorkspaceProblemStatus, revision: Long? = null) = AgentWorkspaceProblem(
        code, "Pages operation rejected", "Check the Pages input and current directory. Re-read the tree before changing a conflicted command; reuse commandId only for the same request.",
        status, false, revision?.let(::AgentRevision), emptyList(), emptyList(), if (revision != null) listOf(AgentAvailableOperation.Read(treePath())) else emptyList(),
        retryAfterSeconds = null,
        roleRequirement = null,
        partitionFilters = emptyList(),
    )
    private fun encodeCursor(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decodeCursor(value: String) = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    private fun pageCursor(caller: AgentWorkspaceCaller, revision: Long, lastPageId: Uuid) = encodeCursor("1:pages:${caller.workspaceId}:$revision:$lastPageId")
    private fun versionCursor(caller: AgentWorkspaceCaller, pageId: PageId, before: Int) = encodeCursor("1:versions:${caller.workspaceId}:$pageId:$before")
    private companion object {
        const val PAGES = "workspace/pages"
        const val TREE = "workspace/pages/tree"
        const val UPLOADS = "workspace/page-content-uploads"
    }
}
