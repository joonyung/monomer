package cc.monomer.pages.domain.content

import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageId
import java.time.Instant
import kotlin.uuid.Uuid

interface PageContentQueryPort {
    suspend fun findUpload(uploadRef: PageContentUploadRef): PageContentUpload?

    /**
     * Returns the latest immutable source format for the requested Document Pages in one query.
     * Missing entries mean that the Page has no finalized version yet.
     */
    suspend fun findLatestDocumentFormats(
        workspaceId: Uuid,
        pageIds: Set<PageId>,
    ): Map<PageId, PageContentFormat>

    suspend fun listVersions(
        workspaceId: Uuid,
        pageId: PageId,
        beforeVersionNumber: PageVersionNumber?,
        limit: Int,
    ): List<PageVersion>

    suspend fun findVersion(
        workspaceId: Uuid,
        pageId: PageId,
        versionNumber: PageVersionNumber,
    ): PageVersion?
}

enum class PageContentCleanupObjectKind {
    TEMPORARY,
    FINAL_SOURCE,
}

data class ClaimedPageContentObjectCleanup(
    val workspaceId: Uuid,
    val objectRef: String,
    val objectKind: PageContentCleanupObjectKind,
    val multipartUploadId: String?,
    val claimToken: Uuid,
    val leaseExpiresAt: Instant,
) {
    init {
        require(objectRef.isNotBlank()) { "cleanup object ref must not be blank" }
        require((objectKind == PageContentCleanupObjectKind.TEMPORARY) == !multipartUploadId.isNullOrBlank()) {
            "only temporary cleanup claims require a multipart upload id"
        }
    }
}

enum class PageContentCleanupClaimResult {
    APPLIED,
    STALE,
}

data class PageContentObjectCleanupCandidate(
    val workspaceId: Uuid,
    val objectRef: String,
    val objectKind: PageContentCleanupObjectKind,
    val multipartUploadId: String?,
    val notBefore: Instant,
    val createdAt: Instant,
) {
    init {
        require(objectRef.isNotBlank()) { "cleanup object ref must not be blank" }
        require((objectKind == PageContentCleanupObjectKind.TEMPORARY) == !multipartUploadId.isNullOrBlank()) {
            "only temporary cleanup candidates require a multipart upload id"
        }
        require(!notBefore.isBefore(createdAt)) { "cleanup grace cannot precede creation" }
    }
}

interface PageContentCleanupRepository {
    suspend fun terminalizeExpiredUploads(limit: Int): Int

    suspend fun claimDueObjectCleanup(
        workerId: String,
        leaseDurationMillis: Long,
        limit: Int,
    ): List<ClaimedPageContentObjectCleanup>

    suspend fun completeObjectCleanup(
        claim: ClaimedPageContentObjectCleanup,
    ): PageContentCleanupClaimResult

    suspend fun retryObjectCleanup(
        claim: ClaimedPageContentObjectCleanup,
        retryBackoffMillis: Long,
    ): PageContentCleanupClaimResult
}

interface PageContentRepository {
    fun insertUploadWithinTx(upload: PageContentUpload)

    fun findUploadForUpdateWithinTx(uploadRef: PageContentUploadRef): PageContentUpload?

    fun updateUploadWithinTx(current: PageContentUpload, next: PageContentUpload)

    fun prepareDocumentPageDeletionWithinTx(
        workspaceId: Uuid,
        pageId: PageId,
        notBefore: Instant,
    )

    fun lockDocumentPageWithinTx(workspaceId: Uuid, pageId: PageId): Boolean

    fun findLatestVersionNumberWithinTx(pageId: PageId): PageVersionNumber?

    fun lockCommandKeyWithinTx(
        workspaceId: Uuid,
        actor: PageActor,
        requestId: String,
        createdAt: Instant,
    )

    fun findCommandResultWithinTx(
        workspaceId: Uuid,
        actor: PageActor,
        requestId: String,
    ): PageContentCommandResult?

    fun insertVersionAndConsumeUploadWithinTx(
        upload: PageContentUpload,
        version: PageVersion,
        consumedAt: Instant,
    )

    fun insertCommandResultWithinTx(result: PageContentCommandResult)

    fun enqueueObjectCleanupWithinTx(candidate: PageContentObjectCleanupCandidate)
}
