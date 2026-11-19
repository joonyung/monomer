package cc.monomer.pages.application.content

import cc.monomer.pages.application.PagesDirectoryEventBus
import cc.monomer.pages.application.PagesDirectoryWakeup
import cc.monomer.pages.domain.audit.AuditActorContext
import cc.monomer.pages.domain.audit.PagesAuditRepository
import cc.monomer.pages.domain.audit.PagesAuditEvent
import cc.monomer.pages.domain.audit.PageContentAuditDetails
import cc.monomer.pages.domain.audit.PagesAuditAction
import cc.monomer.pages.domain.audit.PagesAuditDetails
import cc.monomer.pages.domain.common.TransactionRunner
import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageId
import cc.monomer.pages.domain.content.PageContentCommandResult
import cc.monomer.pages.domain.content.PageContentConsumptionRejection
import cc.monomer.pages.domain.content.PageContentQueryPort
import cc.monomer.pages.domain.content.PageContentRepository
import cc.monomer.pages.domain.content.PageContentUpload
import cc.monomer.pages.domain.content.PageContentUploadRef
import cc.monomer.pages.domain.content.PageContentUploadStatus
import cc.monomer.pages.domain.content.PageVersion
import cc.monomer.pages.domain.content.PageVersionMetadata
import cc.monomer.pages.domain.content.PageVersionNumber
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface PageContentUseCase {
    suspend fun replace(command: ReplaceCommand): PageContentMutationOutcome
    suspend fun listVersions(
        workspaceId: Uuid,
        pageId: PageId,
        beforeVersionNumber: Int?,
        limit: Int,
    ): PageContentVersionPage
    suspend fun getVersion(
        workspaceId: Uuid,
        pageId: PageId,
        versionNumber: Int,
    ): PageVersionMetadata?

    data class ReplaceCommand(
        val workspaceId: Uuid,
        val pageId: PageId,
        val uploadRef: PageContentUploadRef,
        val requestId: String,
        val actor: PageActor,
        val agentClientId: String?,
        val auditActorContext: AuditActorContext,
        val requestedAt: Instant,
    )
}

data class PageContentVersionPage(
    val items: List<PageVersionMetadata>,
    val nextBeforeVersionNumber: Int?,
)

sealed interface PageContentMutationOutcome {
    data class Applied(val version: PageVersionMetadata) : PageContentMutationOutcome
    data class Replayed(val version: PageVersionMetadata) : PageContentMutationOutcome
    data class RequestIdReused(val existingRequestHash: String) : PageContentMutationOutcome
    data class UploadRejected(val error: PageContentConsumptionRejection) : PageContentMutationOutcome
    data object PageNotFound : PageContentMutationOutcome
}

sealed interface CompletedUploadConsumption {
    data class Ready(val upload: PageContentUpload) : CompletedUploadConsumption
    data class Rejected(val error: PageContentConsumptionRejection) : CompletedUploadConsumption
}

fun PageContentUpload?.forConsumption(
    workspaceId: Uuid,
    actor: PageActor,
    requestedAt: Instant,
): CompletedUploadConsumption = when {
    this == null || this.workspaceId != workspaceId || this.actor != actor ->
        CompletedUploadConsumption.Rejected(PageContentConsumptionRejection.UPLOAD_NOT_FOUND)
    status == PageContentUploadStatus.ABORTED ->
        CompletedUploadConsumption.Rejected(PageContentConsumptionRejection.UPLOAD_ABORTED)
    status == PageContentUploadStatus.CONSUMED ->
        CompletedUploadConsumption.Rejected(PageContentConsumptionRejection.UPLOAD_ALREADY_CONSUMED)
    !requestedAt.isBefore(expiresAt) ->
        CompletedUploadConsumption.Rejected(PageContentConsumptionRejection.UPLOAD_EXPIRED)
    status != PageContentUploadStatus.COMPLETED ->
        CompletedUploadConsumption.Rejected(PageContentConsumptionRejection.UPLOAD_NOT_COMPLETED)
    else -> CompletedUploadConsumption.Ready(this)
}

class PageContentUseCaseImpl(
    private val repository: PageContentRepository,
    private val queryPort: PageContentQueryPort,
    private val transactionRunner: TransactionRunner,
    private val auditRepository: PagesAuditRepository,
    private val directoryEventBus: PagesDirectoryEventBus,
) : PageContentUseCase {
    private val json = Json { encodeDefaults = true }

    override suspend fun replace(command: PageContentUseCase.ReplaceCommand): PageContentMutationOutcome {
        require(command.requestId.isNotBlank() && command.requestId.length <= 200) { "requestId is invalid" }
        val persistedAt = command.requestedAt.truncatedTo(ChronoUnit.MILLIS)
        val rawBody = buildJsonObject {
            put("operation", "replace-document-content")
            put("pageId", command.pageId.toString())
            put("uploadRef", command.uploadRef.toString())
        }
        val requestHash = rawBody.sha256()
        return transactionRunner.run {
            repository.lockCommandKeyWithinTx(
                command.workspaceId,
                command.actor,
                command.requestId,
                persistedAt,
            )
            repository.findCommandResultWithinTx(
                command.workspaceId,
                command.actor,
                command.requestId,
            )?.let { previousResult ->
                return@run previousResult.toOutcome(requestHash)
            }
            if (!repository.lockDocumentPageWithinTx(command.workspaceId, command.pageId)) {
                return@run PageContentMutationOutcome.PageNotFound
            }
            val upload = when (val consumption = repository.findUploadForUpdateWithinTx(command.uploadRef)
                .forConsumption(command.workspaceId, command.actor, persistedAt)) {
                is CompletedUploadConsumption.Ready -> consumption.upload
                is CompletedUploadConsumption.Rejected ->
                    return@run PageContentMutationOutcome.UploadRejected(consumption.error)
            }
            val latestVersion = repository.findLatestVersionNumberWithinTx(command.pageId)
                ?: error("Document Page has no first version")
            val version = PageVersion.fromUpload(
                pageId = command.pageId,
                versionNumber = latestVersion.next(),
                upload = upload,
                finalizedBy = command.actor,
                finalizedByAgentClientId = command.agentClientId,
                finalizedAt = persistedAt,
            )
            repository.insertVersionAndConsumeUploadWithinTx(
                upload,
                version,
                persistedAt,
            )
            repository.insertCommandResultWithinTx(
                PageContentCommandResult(
                    workspaceId = command.workspaceId,
                    actor = command.actor,
                    requestId = command.requestId,
                    requestHash = requestHash,
                    requestBody = version.safeCommandBody("replace-document-content"),
                    version = version.metadata(),
                    createdAt = persistedAt,
                ),
            )
            appendFinalizeAudit(command, version)
            directoryEventBus.publish(PagesDirectoryWakeup(command.workspaceId))
            PageContentMutationOutcome.Applied(version.metadata())
        }
    }

    override suspend fun listVersions(
        workspaceId: Uuid,
        pageId: PageId,
        beforeVersionNumber: Int?,
        limit: Int,
    ): PageContentVersionPage {
        require(limit in 1..MAXIMUM_VERSION_PAGE_SIZE) { "version page limit must be between 1 and 100" }
        val before = beforeVersionNumber?.let(::PageVersionNumber)
        val versions = queryPort.listVersions(workspaceId, pageId, before, limit + 1)
        val items = versions.take(limit)
        return PageContentVersionPage(
            items = items.map(PageVersion::metadata),
            nextBeforeVersionNumber = items.lastOrNull()?.versionNumber?.value?.takeIf { versions.size > limit },
        )
    }

    override suspend fun getVersion(
        workspaceId: Uuid,
        pageId: PageId,
        versionNumber: Int,
    ): PageVersionMetadata? {
        if (versionNumber <= 0) return null
        return queryPort.findVersion(
            workspaceId,
            pageId,
            PageVersionNumber(versionNumber),
        )?.metadata()
    }

    private fun appendFinalizeAudit(command: PageContentUseCase.ReplaceCommand, version: PageVersion) {
        check(command.actor.id == command.auditActorContext.principalId.toString()) {
            "Page content audit principal must match the mutation actor"
        }
        auditRepository.appendWithinTx(
            PagesAuditEvent.create(
                workspaceId = command.workspaceId,
                actor = command.actor,
                actorContext = command.auditActorContext,
                action = PagesAuditAction.CONTENT_VERSION_FINALIZED,
                resourceId = command.pageId.value,
                requestId = command.requestId,
                occurredAt = version.finalizedAt,
                details = PagesAuditDetails(
                    pageKind = null,
                    cause = null,
                    dashboardId = null,
                    appliedOperationCount = null,
                    content = PageContentAuditDetails(
                        pageId = command.pageId.value,
                        versionNo = version.versionNumber.value,
                        format = version.format.name,
                        contentHash = version.contentHash.value,
                        parentPageId = null,
                    ),
                ),
            ),
        )
    }

    private fun PageContentCommandResult.toOutcome(requestHash: String): PageContentMutationOutcome =
        if (this.requestHash == requestHash) {
            PageContentMutationOutcome.Replayed(version)
        } else {
            PageContentMutationOutcome.RequestIdReused(this.requestHash)
        }

    private fun PageVersion.safeCommandBody(operation: String): JsonObject = buildJsonObject {
        put("operation", operation)
        put("pageId", pageId.toString())
        put("versionNo", versionNumber.value)
        put("format", format.name)
        put("contentHash", contentHash.value)
        put("contentSizeBytes", contentSizeBytes)
    }

    private fun JsonElement.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(JsonElement.serializer(), this).toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val MAXIMUM_VERSION_PAGE_SIZE: Int = 100
    }
}
