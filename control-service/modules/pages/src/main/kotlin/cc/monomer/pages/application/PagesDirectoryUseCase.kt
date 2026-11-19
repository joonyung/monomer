package cc.monomer.pages.application

import cc.monomer.pages.domain.audit.AuditActorContext
import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageDirectoryChange
import cc.monomer.pages.domain.PageDirectoryRequestRejection
import cc.monomer.pages.domain.PageDirectoryRequestId
import cc.monomer.pages.domain.PageId
import cc.monomer.pages.domain.PagesDirectoryRevision
import cc.monomer.pages.domain.PagesDirectorySnapshot
import cc.monomer.pages.domain.content.PageContentUploadRef
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

interface PagesDirectoryMutationCommand {
    val workspaceId: Uuid
    val baseRevision: PagesDirectoryRevision
    val requestId: PageDirectoryRequestId
    val actor: PageActor
    val auditActorContext: AuditActorContext
    val requestedAt: Instant
}

fun interface PagesDashboardReferencePort {
    fun existsInCallerTransaction(workspaceId: Uuid, dashboardId: Uuid): Boolean
}

interface PagesDirectoryUseCase {
    suspend fun getProjection(workspaceId: Uuid): PagesDirectoryProjection
    suspend fun projectSnapshot(snapshot: PagesDirectorySnapshot): PagesDirectoryProjection
    fun subscribeEvents(workspaceId: Uuid): Flow<PagesDirectoryWakeup>
    suspend fun createDashboardPage(command: CreateDashboardPageCommand): PagesDirectoryMutationOutcome
    suspend fun createFolder(command: CreateFolderCommand): PagesDirectoryMutationOutcome
    suspend fun createDocumentPage(command: CreateDocumentPageCommand): PagesDirectoryMutationOutcome
    suspend fun updateNavigationMetadata(command: UpdateNavigationMetadataCommand): PagesDirectoryMutationOutcome
    suspend fun deletePage(command: DeletePageCommand): PagesDirectoryMutationOutcome
    fun deleteDashboardPagesInCallerTransaction(command: DeleteDashboardPagesCommand)

    data class DeleteDashboardPagesCommand(
        val workspaceId: Uuid,
        val dashboardId: Uuid,
        val actor: PageActor,
        val auditActorContext: AuditActorContext,
        val requestedAt: Instant,
    )
    suspend fun patchPlacements(command: PatchPlacementsCommand): PagesDirectoryMutationOutcome

    data class CreateFolderCommand(
        override val workspaceId: Uuid,
        val title: String,
        val description: String?,
        val parentPageId: PageId?,
        val index: Int?,
        override val baseRevision: PagesDirectoryRevision,
        override val requestId: PageDirectoryRequestId,
        override val actor: PageActor,
        val agentClientId: String?,
        override val auditActorContext: AuditActorContext,
        override val requestedAt: Instant,
    ) : PagesDirectoryMutationCommand

    data class CreateDashboardPageCommand(
        override val workspaceId: Uuid,
        val dashboardId: Uuid,
        val title: String,
        val description: String?,
        val parentPageId: PageId?,
        val index: Int?,
        override val baseRevision: PagesDirectoryRevision,
        override val requestId: PageDirectoryRequestId,
        override val actor: PageActor,
        val agentClientId: String?,
        override val auditActorContext: AuditActorContext,
        override val requestedAt: Instant,
    ) : PagesDirectoryMutationCommand

    data class CreateDocumentPageCommand(
        override val workspaceId: Uuid,
        val title: String,
        val description: String?,
        val parentPageId: PageId?,
        val index: Int?,
        val uploadRef: PageContentUploadRef,
        override val baseRevision: PagesDirectoryRevision,
        override val requestId: PageDirectoryRequestId,
        override val actor: PageActor,
        val agentClientId: String?,
        override val auditActorContext: AuditActorContext,
        override val requestedAt: Instant,
    ) : PagesDirectoryMutationCommand

    data class UpdateNavigationMetadataCommand(
        override val workspaceId: Uuid,
        val pageId: PageId,
        val title: String?,
        val description: String?,
        override val baseRevision: PagesDirectoryRevision,
        override val requestId: PageDirectoryRequestId,
        override val actor: PageActor,
        val agentClientId: String?,
        override val auditActorContext: AuditActorContext,
        override val requestedAt: Instant,
    ) : PagesDirectoryMutationCommand

    data class DeletePageCommand(
        override val workspaceId: Uuid,
        val pageId: PageId,
        override val baseRevision: PagesDirectoryRevision,
        override val requestId: PageDirectoryRequestId,
        override val actor: PageActor,
        override val auditActorContext: AuditActorContext,
        override val requestedAt: Instant,
    ) : PagesDirectoryMutationCommand

    data class PatchPlacementsCommand(
        override val workspaceId: Uuid,
        val patch: List<JsonObject>,
        override val baseRevision: PagesDirectoryRevision,
        override val requestId: PageDirectoryRequestId,
        override val actor: PageActor,
        override val auditActorContext: AuditActorContext,
        override val requestedAt: Instant,
    ) : PagesDirectoryMutationCommand
}

sealed interface PagesDirectoryMutationOutcome {
    data object NotFound : PagesDirectoryMutationOutcome

    data class Applied(
        val change: PageDirectoryChange,
        val snapshot: PagesDirectorySnapshot,
    ) : PagesDirectoryMutationOutcome

    data class Replayed(
        val change: PageDirectoryChange,
        val snapshot: PagesDirectorySnapshot,
    ) : PagesDirectoryMutationOutcome

    data class RevisionConflict(val snapshot: PagesDirectorySnapshot) : PagesDirectoryMutationOutcome

    data class RequestIdReused(
        val existingRequestHash: String,
        val snapshot: PagesDirectorySnapshot,
    ) : PagesDirectoryMutationOutcome

    data class Rejected(
        val reason: PageDirectoryRequestRejection,
        val snapshot: PagesDirectorySnapshot,
    ) : PagesDirectoryMutationOutcome

}
