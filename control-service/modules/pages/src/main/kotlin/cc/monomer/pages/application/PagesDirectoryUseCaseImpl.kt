package cc.monomer.pages.application

import cc.monomer.pages.domain.audit.AuditActorContext
import cc.monomer.pages.domain.audit.PagesAuditRepository
import cc.monomer.pages.domain.audit.PagesAuditEvent
import cc.monomer.pages.domain.audit.PageContentAuditDetails
import cc.monomer.pages.domain.audit.PagesAuditAction
import cc.monomer.pages.domain.audit.PagesAuditDetails
import cc.monomer.pages.domain.common.TransactionRunner
import cc.monomer.pages.domain.Page
import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageActorKind
import cc.monomer.pages.domain.PageCreationResult
import cc.monomer.pages.domain.PageKind
import cc.monomer.pages.domain.PageDirectoryChange
import cc.monomer.pages.domain.PageDirectoryMutationRecord
import cc.monomer.pages.domain.PageDirectoryRequestId
import cc.monomer.pages.domain.PageDirectoryRequestRejection
import cc.monomer.pages.domain.PageDirectoryRequestRecord
import cc.monomer.pages.domain.PageDirectoryRequestResult
import cc.monomer.pages.domain.PageMetadataRejection
import cc.monomer.pages.domain.PageNavigationMetadata
import cc.monomer.pages.domain.PageNavigationMetadataResult
import cc.monomer.pages.domain.PageUpdateResult
import cc.monomer.pages.domain.PagesDirectory
import cc.monomer.pages.domain.PagesDirectoryChangeResult
import cc.monomer.pages.domain.PagesDirectoryPatchResult
import cc.monomer.pages.domain.PagesDirectoryQueryPort
import cc.monomer.pages.domain.PagesDirectoryRejection
import cc.monomer.pages.domain.PagesDirectoryRepository
import cc.monomer.pages.domain.PagesDirectorySnapshot
import cc.monomer.pages.application.content.CompletedUploadConsumption
import cc.monomer.pages.application.content.forConsumption
import cc.monomer.pages.domain.content.PageContentRepository
import cc.monomer.pages.domain.content.PageContentQueryPort
import cc.monomer.pages.domain.content.PageVersion
import cc.monomer.pages.domain.content.PageVersionNumber
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PagesDirectoryUseCaseImpl(
    private val queryPort: PagesDirectoryQueryPort,
    private val repository: PagesDirectoryRepository,
    private val transactionRunner: TransactionRunner,
    private val eventBus: PagesDirectoryEventBus,
    private val auditRepository: PagesAuditRepository,
    private val contentRepository: PageContentRepository,
    private val contentQueryPort: PageContentQueryPort,
    private val dashboardReferencePort: PagesDashboardReferencePort,
) : PagesDirectoryUseCase {
    private val json = Json { encodeDefaults = true }

    suspend fun getSnapshot(workspaceId: Uuid): PagesDirectorySnapshot =
        queryPort.findSnapshot(workspaceId)
            ?: PagesDirectorySnapshot.from(PagesDirectory.empty(workspaceId))

    override suspend fun getProjection(workspaceId: Uuid): PagesDirectoryProjection = projectSnapshot(getSnapshot(workspaceId))

    override suspend fun projectSnapshot(snapshot: PagesDirectorySnapshot): PagesDirectoryProjection {
        val displayTitles = snapshot.pages.associate { page ->
            page.id to (page.navigationMetadata.title ?: error("Page has no display title"))
        }
        val documentPageIds = snapshot.pages.asSequence()
            .filter { page -> page.kind == PageKind.DOCUMENT }
            .map(Page::id)
            .toSet()
        val documentFormats = contentQueryPort.findLatestDocumentFormats(snapshot.workspaceId, documentPageIds)
        return PagesDirectoryProjection(snapshot, displayTitles, documentFormats)
    }

    override fun subscribeEvents(workspaceId: Uuid): Flow<PagesDirectoryWakeup> = eventBus.subscribe(workspaceId)

    override suspend fun createFolder(
        command: PagesDirectoryUseCase.CreateFolderCommand,
    ): PagesDirectoryMutationOutcome {
        val body = buildJsonObject {
            put("operation", "create-folder")
            put("baseRevision", command.baseRevision.value)
            put("title", command.title)
            putNullable("description", command.description)
            putNullable("parentPageId", command.parentPageId?.toString())
            putNullable("index", command.index)
        }
        return transactionRunner.run {
            val guard = guard(command, body)
            if (guard !is MutationGuard.Proceed) return@run guard.toOutcome()
            val metadata = when (val result = PageNavigationMetadata.create(command.title, command.description)) {
                is PageNavigationMetadataResult.Valid -> result.metadata
                is PageNavigationMetadataResult.Invalid -> return@run rejected(guard.current, command, body, result.reason)
            }
            val page = when (val created = Page.createFolder(
                command.workspaceId,
                metadata,
                command.actor,
                command.agentClientId,
                command.persistedAt(),
            )) {
                is PageCreationResult.Created -> created.page
                is PageCreationResult.Invalid -> return@run rejected(guard.current, command, body, created.reason)
            }
            val next = when (val changed = guard.current.addPage(page, command.parentPageId, command.index, command.actor, command.persistedAt())) {
                is PagesDirectoryChangeResult.Changed -> changed.directory
                is PagesDirectoryChangeResult.Invalid -> return@run rejected(guard.current, command, body, changed.reason)
            }
            save(
                current = guard.current,
                next = next,
                command = command,
                requestBody = body,
                change = PageDirectoryChange.FolderCreated(page.id),
            )
        }
    }

    override suspend fun createDashboardPage(
        command: PagesDirectoryUseCase.CreateDashboardPageCommand,
    ): PagesDirectoryMutationOutcome {
        val body = buildJsonObject {
            put("operation", "create-dashboard-page")
            put("baseRevision", command.baseRevision.value)
            put("dashboardId", command.dashboardId.toString())
            put("title", command.title)
            putNullable("description", command.description)
            putNullable("parentPageId", command.parentPageId?.toString())
            putNullable("index", command.index)
        }
        return transactionRunner.run {
            if (!dashboardReferencePort.existsInCallerTransaction(command.workspaceId, command.dashboardId)) {
                return@run PagesDirectoryMutationOutcome.NotFound
            }
            val guard = guard(command, body)
            if (guard !is MutationGuard.Proceed) return@run guard.toOutcome()
            val metadata = when (val result = PageNavigationMetadata.create(command.title, command.description)) {
                is PageNavigationMetadataResult.Valid -> result.metadata
                is PageNavigationMetadataResult.Invalid -> return@run rejected(guard.current, command, body, result.reason)
            }
            val page = when (val created = Page.createDashboard(
                command.workspaceId,
                command.dashboardId,
                metadata,
                command.actor,
                command.agentClientId,
                command.persistedAt(),
            )) {
                is PageCreationResult.Created -> created.page
                is PageCreationResult.Invalid -> return@run rejected(guard.current, command, body, created.reason)
            }
            val next = when (val changed = guard.current.addPage(page, command.parentPageId, command.index, command.actor, command.persistedAt())) {
                is PagesDirectoryChangeResult.Changed -> changed.directory
                is PagesDirectoryChangeResult.Invalid -> return@run rejected(guard.current, command, body, changed.reason)
            }
            save(
                current = guard.current,
                next = next,
                command = command,
                requestBody = body,
                change = PageDirectoryChange.DashboardPageCreated(page.id),
            )
        }
    }

    override suspend fun createDocumentPage(
        command: PagesDirectoryUseCase.CreateDocumentPageCommand,
    ): PagesDirectoryMutationOutcome {
        val rawBody = buildJsonObject {
            put("operation", "create-document-page")
            put("baseRevision", command.baseRevision.value)
            put("title", command.title)
            putNullable("description", command.description)
            putNullable("parentPageId", command.parentPageId?.toString())
            putNullable("index", command.index)
            put("uploadRef", command.uploadRef.toString())
        }
        val redactedBody = buildJsonObject {
            put("operation", "create-document-page")
            put("baseRevision", command.baseRevision.value)
            put("title", command.title)
            putNullable("description", command.description)
            putNullable("parentPageId", command.parentPageId?.toString())
            putNullable("index", command.index)
        }
        return transactionRunner.run {
            val current = repository.findOrCreateForUpdateWithinTx(command.workspaceId)
            val requestHash = rawBody.sha256()
            val replay = replayOrReuse(current, command.actor, command.requestId, requestHash)
            if (replay != null) return@run replay
            if (current.revision != command.baseRevision) {
                return@run saveNonAdvancing(current, command, redactedBody, requestHash) { hash, snapshot ->
                    PageDirectoryRequestResult.RevisionConflict(hash, snapshot)
                }
            }
            val metadata = when (val result = PageNavigationMetadata.create(command.title, command.description)) {
                is PageNavigationMetadataResult.Valid -> result.metadata
                is PageNavigationMetadataResult.Invalid -> return@run saveNonAdvancing(
                    current,
                    command,
                    redactedBody,
                    requestHash,
                ) { hash, snapshot ->
                    PageDirectoryRequestResult.Rejected(
                        hash,
                        PageDirectoryRequestRejection.MetadataInvalid(result.reason),
                        snapshot,
                    )
                }
            }
            val page = when (val created = Page.createDocument(
                command.workspaceId,
                metadata,
                command.actor,
                command.agentClientId,
                command.persistedAt(),
            )) {
                is PageCreationResult.Created -> created.page
                is PageCreationResult.Invalid -> error("validated Document metadata was rejected: ${created.reason}")
            }
            val next = when (val changed = current.addPage(
                page,
                command.parentPageId,
                command.index,
                command.actor,
                command.persistedAt(),
            )) {
                is PagesDirectoryChangeResult.Changed -> changed.directory
                is PagesDirectoryChangeResult.Invalid -> return@run saveNonAdvancing(
                    current,
                    command,
                    redactedBody,
                    requestHash,
                ) { hash, snapshot ->
                    PageDirectoryRequestResult.Rejected(
                        hash,
                        PageDirectoryRequestRejection.DirectoryInvalid(changed.reason),
                        snapshot,
                    )
                }
            }
            val upload = when (val consumption = contentRepository.findUploadForUpdateWithinTx(command.uploadRef)
                .forConsumption(command.workspaceId, command.actor, command.persistedAt())) {
                is CompletedUploadConsumption.Ready -> consumption.upload
                is CompletedUploadConsumption.Rejected -> return@run saveNonAdvancing(
                    current,
                    command,
                    redactedBody,
                    requestHash,
                ) { hash, snapshot ->
                    PageDirectoryRequestResult.Rejected(
                        hash,
                        PageDirectoryRequestRejection.ContentUploadInvalid(consumption.error),
                        snapshot,
                    )
                }
            }
            val version = PageVersion.fromUpload(
                pageId = page.id,
                versionNumber = PageVersionNumber(1),
                upload = upload,
                finalizedBy = command.actor,
                finalizedByAgentClientId = command.agentClientId,
                finalizedAt = command.persistedAt(),
            )
            val safeBody = buildJsonObject {
                redactedBody.forEach { (key, value) -> put(key, value) }
                put("format", version.format.name)
                put("contentHash", version.contentHash.value)
                put("contentSizeBytes", version.contentSizeBytes)
            }
            val change = PageDirectoryChange.DocumentPageCreated(page.id)
            val applied = repository.saveMutationWithinTx(
                current = current,
                next = next,
                record = PageDirectoryMutationRecord(
                    requestId = command.requestId,
                    requestHash = requestHash,
                    requestBody = safeBody,
                    actor = command.actor,
                    change = change,
                    createdAt = command.persistedAt(),
                ),
            )
            contentRepository.insertVersionAndConsumeUploadWithinTx(
                upload,
                version,
                command.persistedAt(),
            )
            appendDocumentCreateAudit(command, version, command.parentPageId, command.auditActorContext)
            eventBus.publish(PagesDirectoryWakeup(applied.snapshot.workspaceId))
            PagesDirectoryMutationOutcome.Applied(change, applied.snapshot)
        }
    }

    private fun appendDocumentCreateAudit(
        command: PagesDirectoryUseCase.CreateDocumentPageCommand,
        version: PageVersion,
        parentPageId: cc.monomer.pages.domain.PageId?,
        actorContext: AuditActorContext,
    ) {
        check(command.actor.id == actorContext.principalId.toString()) {
            "Pages audit principal must match the mutation actor"
        }
        auditRepository.appendWithinTx(
            PagesAuditEvent.create(
                workspaceId = command.workspaceId,
                actor = command.actor,
                actorContext = actorContext,
                action = PagesAuditAction.DOCUMENT_CREATE,
                resourceId = version.pageId.value,
                requestId = command.requestId.value,
                occurredAt = version.finalizedAt,
                details = PagesAuditDetails(
                    pageKind = null,
                    appliedOperationCount = null,
                    cause = null,
                    dashboardId = null,
                    content = PageContentAuditDetails(
                        pageId = version.pageId.value,
                        versionNo = 1,
                        format = version.format.name,
                        contentHash = version.contentHash.value,
                        parentPageId = parentPageId?.value,
                    ),
                ),
            ),
        )
    }

    override suspend fun updateNavigationMetadata(
        command: PagesDirectoryUseCase.UpdateNavigationMetadataCommand,
    ): PagesDirectoryMutationOutcome {
        val body = buildJsonObject {
            put("operation", "update-navigation-metadata")
            put("baseRevision", command.baseRevision.value)
            put("pageId", command.pageId.toString())
            putNullable("title", command.title)
            putNullable("description", command.description)
        }
        return transactionRunner.run {
            val guard = guard(command, body)
            if (guard !is MutationGuard.Proceed) return@run guard.toOutcome()
            val metadata = when (val result = PageNavigationMetadata.create(command.title, command.description)) {
                is PageNavigationMetadataResult.Valid -> result.metadata
                is PageNavigationMetadataResult.Invalid -> return@run rejected(guard.current, command, body, result.reason)
            }
            val page = guard.current.pages.firstOrNull { it.id == command.pageId }
                ?: return@run rejected(guard.current, command, body, PagesDirectoryRejection.PAGE_NOT_FOUND)
            val updated = when (val result = page.updateNavigationMetadata(
                metadata,
                command.actor,
                command.agentClientId,
                command.persistedAt(),
            )) {
                is PageUpdateResult.Updated -> result.page
                is PageUpdateResult.Invalid -> return@run rejected(guard.current, command, body, result.reason)
            }
            val next = when (val changed = guard.current.updatePage(updated, command.actor, command.persistedAt())) {
                is PagesDirectoryChangeResult.Changed -> changed.directory
                is PagesDirectoryChangeResult.Invalid -> return@run rejected(guard.current, command, body, changed.reason)
            }
            save(
                current = guard.current,
                next = next,
                command = command,
                requestBody = body,
                change = PageDirectoryChange.NavigationMetadataUpdated(command.pageId),
            )
        }
    }

    override suspend fun deletePage(
        command: PagesDirectoryUseCase.DeletePageCommand,
    ): PagesDirectoryMutationOutcome {
        val body = buildJsonObject {
            put("operation", "delete-page")
            put("baseRevision", command.baseRevision.value)
            put("pageId", command.pageId.toString())
        }
        return transactionRunner.run {
            val guard = guard(command, body)
            if (guard !is MutationGuard.Proceed) return@run guard.toOutcome()
            val deletingPage = guard.current.pages.firstOrNull { page -> page.id == command.pageId }
            if (deletingPage?.kind == PageKind.DOCUMENT) {
                contentRepository.prepareDocumentPageDeletionWithinTx(
                    command.workspaceId,
                    command.pageId,
                    command.persistedAt(),
                )
            }
            val next = when (val changed = guard.current.deletePage(command.pageId, command.actor, command.persistedAt())) {
                is PagesDirectoryChangeResult.Changed -> changed.directory
                is PagesDirectoryChangeResult.Invalid -> return@run rejected(guard.current, command, body, changed.reason)
            }
            save(
                current = guard.current,
                next = next,
                command = command,
                requestBody = body,
                change = PageDirectoryChange.PageDeleted(command.pageId),
            )
        }
    }

    override fun deleteDashboardPagesInCallerTransaction(
        command: PagesDirectoryUseCase.DeleteDashboardPagesCommand,
    ) {
        repository.requireAmbientTransaction()
        check(command.actor.kind != PageActorKind.SYSTEM) { "Dashboard Page deletion requires a user or agent actor" }
        val current = repository.findOrCreateForUpdateWithinTx(command.workspaceId)
        val deletingPages = current.pages.filter { it.kind == PageKind.DASHBOARD && it.dashboardId == command.dashboardId }
        if (deletingPages.isEmpty()) return
        val occurredAt = command.requestedAt.truncatedTo(ChronoUnit.MILLIS)
        val next = when (val changed = current.deleteDashboardPages(command.dashboardId, command.actor, occurredAt)) {
            is PagesDirectoryChangeResult.Changed -> changed.directory
            is PagesDirectoryChangeResult.Invalid -> error("Dashboard Page deletion violated directory invariants: ${changed.reason}")
        }
        repository.saveDirectoryWithinTx(current, next)
        deletingPages.forEach { page ->
            auditRepository.appendWithinTx(PagesAuditEvent.create(
                workspaceId = command.workspaceId,
                actor = command.actor,
                actorContext = command.auditActorContext,
                action = PagesAuditAction.PAGE_DELETE,
                resourceId = page.id.value,
                requestId = "dashboard-delete:${command.dashboardId}",
                occurredAt = occurredAt,
                details = PagesAuditDetails(PageKind.DASHBOARD.name, null, null, "DASHBOARD_DELETED", command.dashboardId),
            ))
        }
        eventBus.publish(PagesDirectoryWakeup(command.workspaceId))
    }

    override suspend fun patchPlacements(
        command: PagesDirectoryUseCase.PatchPlacementsCommand,
    ): PagesDirectoryMutationOutcome {
        val body = buildJsonObject {
            put("operation", "patch-placements")
            put("baseRevision", command.baseRevision.value)
            put("patch", JsonArray(command.patch))
        }
        return transactionRunner.run {
            val guard = guard(command, body)
            if (guard !is MutationGuard.Proceed) return@run guard.toOutcome()
            when (val patched = guard.current.patchPlacements(command.patch, command.actor, command.persistedAt())) {
                is PagesDirectoryPatchResult.PatchInvalid ->
                    rejected(guard.current, command, body, PageDirectoryRequestRejection.PatchInvalid(patched.error))
                is PagesDirectoryPatchResult.DirectoryInvalid -> rejected(guard.current, command, body, patched.reason)
                is PagesDirectoryPatchResult.Patched -> save(
                    current = guard.current,
                    next = patched.directory,
                    command = command,
                    requestBody = body,
                    change = PageDirectoryChange.PlacementsPatched(patched.appliedOperationCount),
                )
            }
        }
    }

    private fun guard(command: PagesDirectoryMutationCommand, body: JsonElement): MutationGuard {
        val current = repository.findOrCreateForUpdateWithinTx(command.workspaceId)
        val requestHash = body.sha256()
        val replay = replayOrReuse(current, command.actor, command.requestId, requestHash)
        if (replay != null) return MutationGuard.Terminal(replay)
        if (current.revision != command.baseRevision) {
            return MutationGuard.Terminal(revisionConflict(current, command, body))
        }
        return MutationGuard.Proceed(current)
    }

    private fun replayOrReuse(
        current: PagesDirectory,
        actor: PageActor,
        requestId: PageDirectoryRequestId,
        requestHash: String,
    ): PagesDirectoryMutationOutcome? {
        val result = repository.findRequestResultWithinTx(current.workspaceId, actor, requestId) ?: return null
        return if (result.requestHash == requestHash) {
            result.toReplayOutcome()
        } else {
            PagesDirectoryMutationOutcome.RequestIdReused(result.requestHash, PagesDirectorySnapshot.from(current))
        }
    }

    private fun PageDirectoryRequestResult.toReplayOutcome(): PagesDirectoryMutationOutcome = when (this) {
        is PageDirectoryRequestResult.Applied -> PagesDirectoryMutationOutcome.Replayed(
            change,
            snapshot,
        )
        is PageDirectoryRequestResult.RevisionConflict -> PagesDirectoryMutationOutcome.RevisionConflict(snapshot)
        is PageDirectoryRequestResult.Rejected -> PagesDirectoryMutationOutcome.Rejected(reason, snapshot)
    }

    private fun save(
        current: PagesDirectory,
        next: PagesDirectory,
        command: PagesDirectoryMutationCommand,
        requestBody: JsonElement,
        change: PageDirectoryChange,
    ): PagesDirectoryMutationOutcome {
        val applied = repository.saveMutationWithinTx(
            current = current,
            next = next,
            record = PageDirectoryMutationRecord(
                requestId = command.requestId,
                requestHash = requestBody.sha256(),
                requestBody = requestBody,
                actor = command.actor,
                change = change,
                createdAt = command.persistedAt(),
            ),
        )
        appendAuditEvent(applied, command, current, command.auditActorContext)
        eventBus.publish(PagesDirectoryWakeup(applied.snapshot.workspaceId))
        return PagesDirectoryMutationOutcome.Applied(change, applied.snapshot)
    }

    private fun appendAuditEvent(
        applied: cc.monomer.pages.domain.PageDirectoryRequestResult.Applied,
        command: PagesDirectoryMutationCommand,
        previousDirectory: PagesDirectory,
        actorContext: AuditActorContext,
    ) {
        check(command.actor.id == actorContext.principalId.toString()) {
            "Pages audit principal must match the mutation actor"
        }
        val shape = applied.toAuditShape(previousDirectory)
        auditRepository.appendWithinTx(
            PagesAuditEvent.create(
                workspaceId = applied.snapshot.workspaceId,
                actor = command.actor,
                actorContext = actorContext,
                action = shape.action,
                resourceId = shape.resourceId,
                requestId = command.requestId.value,
                occurredAt = command.persistedAt(),
                details = shape.details,
            ),
        )
    }

    private fun cc.monomer.pages.domain.PageDirectoryRequestResult.Applied.toAuditShape(
        previousDirectory: PagesDirectory,
    ): PagesAuditShape = when (val value = change) {
        is PageDirectoryChange.FolderCreated -> PagesAuditShape(
            PagesAuditAction.FOLDER_CREATE,
            value.pageId.value,
            PagesAuditDetails(PageKind.FOLDER.name, null, null, null, null),
        )
        is PageDirectoryChange.DashboardPageCreated -> PagesAuditShape(
            PagesAuditAction.DASHBOARD_CREATE,
            value.pageId.value,
            PagesAuditDetails(PageKind.DASHBOARD.name, null, null, null, null),
        )
        is PageDirectoryChange.DocumentPageCreated ->
            error("Document Page creation uses the content-aware audit shape")
        is PageDirectoryChange.NavigationMetadataUpdated -> {
            val page = snapshot.pages.single { candidate -> candidate.id == value.pageId }
            PagesAuditShape(
                PagesAuditAction.NAVIGATION_UPDATE,
                value.pageId.value,
                PagesAuditDetails(page.kind.name, null, null, null, null),
            )
        }
        is PageDirectoryChange.PageDeleted -> {
            val page = previousDirectory.pages.single { candidate -> candidate.id == value.pageId }
            PagesAuditShape(
                PagesAuditAction.PAGE_DELETE,
                value.pageId.value,
                PagesAuditDetails(page.kind.name, null, null, null, null),
            )
        }
        is PageDirectoryChange.PlacementsPatched -> PagesAuditShape(
            PagesAuditAction.PLACEMENTS_PATCH,
            snapshot.workspaceId,
            PagesAuditDetails(null, value.appliedOperationCount, null, null, null),
        )
    }

    private data class PagesAuditShape(
        val action: PagesAuditAction,
        val resourceId: Uuid,
        val details: PagesAuditDetails,
    )

    private fun saveNonAdvancing(
        current: PagesDirectory,
        command: PagesDirectoryMutationCommand,
        requestBody: JsonElement,
        requestHash: String,
        resultFactory: (String, PagesDirectorySnapshot) -> PageDirectoryRequestResult.NonAdvancing,
    ): PagesDirectoryMutationOutcome {
        val result = resultFactory(requestHash, PagesDirectorySnapshot.from(current))
        val saved = repository.saveNonAdvancingResultWithinTx(
            current = current,
            record = PageDirectoryRequestRecord(
                requestId = command.requestId,
                requestHash = requestHash,
                requestBody = requestBody,
                actor = command.actor,
                createdAt = command.persistedAt(),
            ),
            result = result,
        )
        return saved.toReplayOutcome()
    }

    private fun revisionConflict(
        current: PagesDirectory,
        command: PagesDirectoryMutationCommand,
        requestBody: JsonElement,
    ): PagesDirectoryMutationOutcome = saveNonAdvancing(
        current,
        command,
        requestBody,
        requestBody.sha256(),
    ) { requestHash, snapshot ->
        PageDirectoryRequestResult.RevisionConflict(requestHash, snapshot)
    }

    private fun rejected(
        current: PagesDirectory,
        command: PagesDirectoryMutationCommand,
        requestBody: JsonElement,
        reason: PageMetadataRejection,
    ): PagesDirectoryMutationOutcome = rejected(
        current,
        command,
        requestBody,
        PageDirectoryRequestRejection.MetadataInvalid(reason),
    )

    private fun rejected(
        current: PagesDirectory,
        command: PagesDirectoryMutationCommand,
        requestBody: JsonElement,
        reason: PagesDirectoryRejection,
    ): PagesDirectoryMutationOutcome = rejected(
        current,
        command,
        requestBody,
        PageDirectoryRequestRejection.DirectoryInvalid(reason),
    )

    private fun rejected(
        current: PagesDirectory,
        command: PagesDirectoryMutationCommand,
        requestBody: JsonElement,
        reason: PageDirectoryRequestRejection,
    ): PagesDirectoryMutationOutcome = saveNonAdvancing(
        current,
        command,
        requestBody,
        requestBody.sha256(),
    ) { requestHash, snapshot ->
        PageDirectoryRequestResult.Rejected(requestHash, reason, snapshot)
    }

    private fun JsonElement.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(JsonElement.serializer(), canonicalize()).toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun JsonElement.canonicalize(): JsonElement = when (this) {
        is JsonObject -> JsonObject(
            entries
                .sortedBy { it.key }
                .associate { (key, value) -> key to value.canonicalize() },
        )
        is JsonArray -> JsonArray(map { element -> element.canonicalize() })
        else -> this
    }

    private fun PagesDirectoryMutationCommand.persistedAt(): Instant = requestedAt.toPersistencePrecision()

    private fun Instant.toPersistencePrecision(): Instant = truncatedTo(ChronoUnit.MILLIS)

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) {
        if (value == null) put(key, JsonNull) else put(key, JsonPrimitive(value))
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Int?) {
        if (value == null) put(key, JsonNull) else put(key, JsonPrimitive(value))
    }

    private sealed interface MutationGuard {
        data class Proceed(val current: PagesDirectory) : MutationGuard
        data class Terminal(val outcome: PagesDirectoryMutationOutcome) : MutationGuard

        fun toOutcome(): PagesDirectoryMutationOutcome = when (this) {
            is Proceed -> error("proceed guard is not terminal")
            is Terminal -> outcome
        }
    }

}
