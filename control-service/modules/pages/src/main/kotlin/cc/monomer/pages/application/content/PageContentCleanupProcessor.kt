package cc.monomer.pages.application.content

import cc.monomer.pages.domain.content.PageContentCleanupClaimResult
import cc.monomer.pages.domain.content.PageContentCleanupObjectKind
import cc.monomer.pages.domain.content.PageContentCleanupRepository
import cc.monomer.pages.domain.content.PageContentObjectStorage
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class PageContentCleanupProcessor(
    private val repository: PageContentCleanupRepository,
    private val objectStorage: PageContentObjectStorage,
) {
    suspend fun processBatch(workerId: String): PageContentCleanupBatchResult {
        val expiredUploads = repository.terminalizeExpiredUploads(BATCH_SIZE)
        val claims = repository.claimDueObjectCleanup(workerId, LEASE_DURATION_MILLIS, BATCH_SIZE)
        var completed = 0
        var scheduledForRetry = 0
        claims.forEach { claim ->
            try {
                when (claim.objectKind) {
                    PageContentCleanupObjectKind.TEMPORARY -> {
                        objectStorage.abortMultipartUpload(
                            PageContentObjectStorage.MultipartUpload(
                                claim.objectRef,
                                checkNotNull(claim.multipartUploadId),
                            ),
                        )
                        objectStorage.delete(claim.objectRef)
                    }
                    PageContentCleanupObjectKind.FINAL_SOURCE -> objectStorage.delete(claim.objectRef)
                }
                when (repository.completeObjectCleanup(claim)) {
                    PageContentCleanupClaimResult.APPLIED -> completed += 1
                    PageContentCleanupClaimResult.STALE -> Unit
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                val retryScheduled = repository.retryObjectCleanup(claim, RETRY_BACKOFF_MILLIS) ==
                    PageContentCleanupClaimResult.APPLIED
                if (retryScheduled) scheduledForRetry += 1
                logger.atWarn()
                    .addKeyValue("event", "pages_content_cleanup_object_failed")
                    .addKeyValue("object_kind", claim.objectKind.name)
                    .addKeyValue("retry_scheduled", retryScheduled)
                    .setCause(exception)
                    .log("pages_content_cleanup_object_failed")
            }
        }
        return PageContentCleanupBatchResult(
            expiredUploads = expiredUploads,
            claimedObjects = claims.size,
            completedObjects = completed,
            scheduledForRetry = scheduledForRetry,
        )
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(PageContentCleanupProcessor::class.java)
        const val BATCH_SIZE: Int = 100
        const val LEASE_DURATION_MILLIS: Long = 60_000
        const val RETRY_BACKOFF_MILLIS: Long = 30_000
    }
}

data class PageContentCleanupBatchResult(
    val expiredUploads: Int,
    val claimedObjects: Int,
    val completedObjects: Int,
    val scheduledForRetry: Int,
)
