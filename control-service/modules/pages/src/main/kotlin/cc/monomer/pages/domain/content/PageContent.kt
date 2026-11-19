package cc.monomer.pages.domain.content

import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageActorKind
import cc.monomer.pages.domain.PageId
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class PageContentUploadRef(val value: Uuid) {
    companion object {
        fun generate(): PageContentUploadRef = PageContentUploadRef(Uuid.generateV7())
    }

    override fun toString(): String = value.toString()
}

@JvmInline
@Serializable
value class PageContentHash(val value: String) {
    init {
        require(value.matches(LOWERCASE_SHA_256)) { "content hash must be a lowercase SHA-256 value" }
    }

    override fun toString(): String = value

    private companion object {
        val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
    }
}

@JvmInline
value class PageVersionNumber(val value: Int) {
    init {
        require(value > 0) { "version number must be positive" }
    }

    fun next(): PageVersionNumber = PageVersionNumber(value + 1)
}

enum class PageContentFormat {
    MARKDOWN,
    NOTEBOOK,
    HTML,
}

enum class PageContentUploadStatus {
    INITIATED,
    COMPLETING,
    COMPLETED,
    FAILED,
    CONSUMED,
    ABORTED,
}

enum class PageContentConsumptionRejection {
    UPLOAD_NOT_FOUND,
    UPLOAD_EXPIRED,
    UPLOAD_NOT_COMPLETED,
    UPLOAD_ALREADY_CONSUMED,
    UPLOAD_ABORTED,
}

data class PageContentUpload(
    val uploadRef: PageContentUploadRef,
    val workspaceId: Uuid,
    val actor: PageActor,
    val format: PageContentFormat,
    val expectedHash: PageContentHash,
    val expectedSizeBytes: Long,
    val temporaryObjectRef: String,
    val multipartUploadId: String,
    val expiresAt: Instant,
    val status: PageContentUploadStatus,
    val completionRequestHash: String?,
    val completionStartedAt: Instant?,
    val completionLeaseExpiresAt: Instant?,
    val completionClaimToken: Uuid?,
    val sourceObjectRef: String?,
    val actualHash: PageContentHash?,
    val actualSizeBytes: Long?,
    val completedAt: Instant?,
    val consumedAt: Instant?,
    val consumedByPageId: PageId?,
    val abortedAt: Instant?,
    val failureCode: String?,
    val createdAt: Instant,
) {
    init {
        require(actor.kind != PageActorKind.SYSTEM) { "SYSTEM cannot own a Page content upload" }
        require(expectedSizeBytes > 0) { "expected source size must be positive" }
        require(temporaryObjectRef.isNotBlank()) { "temporary object ref must not be blank" }
        require(multipartUploadId.isNotBlank()) { "multipart upload id must not be blank" }
        require(expiresAt.isAfter(createdAt)) { "upload expiry must be after creation" }
        require(completionRequestHash == null || completionRequestHash.matches(LOWERCASE_SHA_256)) {
            "completion request hash must be a lowercase SHA-256 value"
        }
        require(failureCode == null || failureCode.matches(SAFE_FAILURE_CODE)) { "failure code is invalid" }
        when (status) {
            PageContentUploadStatus.INITIATED -> require(
                completionRequestHash == null && completionStartedAt == null && completionLeaseExpiresAt == null &&
                    completionClaimToken == null && sourceObjectRef == null && actualHash == null &&
                    actualSizeBytes == null && completedAt == null && consumedAt == null &&
                    consumedByPageId == null && abortedAt == null && failureCode == null,
            ) { "initiated upload contains terminal state" }
            PageContentUploadStatus.COMPLETING -> require(
                completionRequestHash != null && completionStartedAt != null && completionLeaseExpiresAt != null &&
                    completionClaimToken != null && completionLeaseExpiresAt.isAfter(completionStartedAt) &&
                    sourceObjectRef == null && actualHash == null && actualSizeBytes == null && completedAt == null &&
                    consumedAt == null && consumedByPageId == null && abortedAt == null && failureCode == null,
            ) { "completing upload state is invalid" }
            PageContentUploadStatus.COMPLETED -> require(
                completionRequestHash != null && completionStartedAt != null && completionLeaseExpiresAt == null &&
                    completionClaimToken == null && !sourceObjectRef.isNullOrBlank() && actualHash != null &&
                    actualSizeBytes != null && completedAt != null && consumedAt == null &&
                    consumedByPageId == null && abortedAt == null && failureCode == null,
            ) { "completed upload state is invalid" }
            PageContentUploadStatus.FAILED -> require(
                completionRequestHash != null && completionStartedAt != null && completionLeaseExpiresAt == null &&
                    completionClaimToken == null && sourceObjectRef == null && actualHash == null &&
                    actualSizeBytes == null && completedAt == null && consumedAt == null &&
                    consumedByPageId == null && abortedAt == null && failureCode != null,
            ) { "failed upload state is invalid" }
            PageContentUploadStatus.CONSUMED -> require(
                completionRequestHash != null && completionStartedAt != null && completionLeaseExpiresAt == null &&
                    completionClaimToken == null && !sourceObjectRef.isNullOrBlank() && actualHash != null &&
                    actualSizeBytes != null && completedAt != null && consumedAt != null &&
                    consumedByPageId != null && abortedAt == null && failureCode == null,
            ) { "consumed upload state is invalid" }
            PageContentUploadStatus.ABORTED -> require(
                completionLeaseExpiresAt == null && completionClaimToken == null && consumedAt == null &&
                    consumedByPageId == null && abortedAt != null && !abortedAt.isBefore(createdAt) &&
                    (
                        (
                            sourceObjectRef == null && actualHash == null && actualSizeBytes == null &&
                                completedAt == null &&
                                (
                                    (completionRequestHash == null && completionStartedAt == null && failureCode == null) ||
                                        (completionRequestHash != null && completionStartedAt != null && failureCode != null)
                                    )
                            ) ||
                            (
                                !sourceObjectRef.isNullOrBlank() && actualHash != null && actualSizeBytes != null &&
                                    completedAt != null && completionRequestHash != null &&
                                    completionStartedAt != null && failureCode == null
                                )
                        ),
            ) { "aborted upload state is invalid" }
        }
    }

    fun startCompletion(
        requestHash: String,
        requestedAt: Instant,
        leaseExpiresAt: Instant,
        claimToken: Uuid,
    ): PageContentUploadCompletionStart = when {
        !requestHash.matches(LOWERCASE_SHA_256) -> error("completion request hash must be a lowercase SHA-256 value")
        status == PageContentUploadStatus.ABORTED -> PageContentUploadCompletionStart.Failed("UPLOAD_ABORTED")
        completionRequestHash != null && completionRequestHash != requestHash ->
            PageContentUploadCompletionStart.RequestReused
        status == PageContentUploadStatus.COMPLETED || status == PageContentUploadStatus.CONSUMED ->
            PageContentUploadCompletionStart.Replayed(this)
        !requestedAt.isBefore(expiresAt) -> PageContentUploadCompletionStart.Expired
        status == PageContentUploadStatus.INITIATED -> PageContentUploadCompletionStart.Started(
            copyWithCompletionClaim(requestHash, requestedAt, leaseExpiresAt, claimToken),
            recovered = false,
            claimToken = claimToken,
        )
        status == PageContentUploadStatus.COMPLETING &&
            !requestedAt.isBefore(completionLeaseExpiresAt ?: error("completion lease is missing")) ->
            PageContentUploadCompletionStart.Started(
                copyWithCompletionClaim(requestHash, requestedAt, leaseExpiresAt, claimToken),
                recovered = true,
                claimToken = claimToken,
            )
        status == PageContentUploadStatus.COMPLETING -> PageContentUploadCompletionStart.InProgress
        else -> PageContentUploadCompletionStart.Failed(failureCode ?: "UPLOAD_FAILED")
    }

    private fun copyWithCompletionClaim(
        requestHash: String,
        requestedAt: Instant,
        leaseExpiresAt: Instant,
        claimToken: Uuid,
    ): PageContentUpload {
        require(leaseExpiresAt.isAfter(requestedAt)) { "completion lease expiry must be after its start" }
        return copy(
            status = PageContentUploadStatus.COMPLETING,
            completionRequestHash = requestHash,
            completionStartedAt = requestedAt,
            completionLeaseExpiresAt = leaseExpiresAt,
            completionClaimToken = claimToken,
        )
    }

    fun complete(
        sourceObjectRef: String,
        actualHash: PageContentHash,
        actualSizeBytes: Long,
        completedAt: Instant,
        claimToken: Uuid,
    ): PageContentUpload {
        require(status == PageContentUploadStatus.COMPLETING) { "only a completing upload can complete" }
        require(completionClaimToken == claimToken) { "completion claim is no longer owned" }
        require(sourceObjectRef.isNotBlank()) { "source object ref must not be blank" }
        require(actualHash == expectedHash) { "actual content hash must match expected hash" }
        require(actualSizeBytes == expectedSizeBytes) { "actual source size must match expected size" }
        return copy(
            status = PageContentUploadStatus.COMPLETED,
            sourceObjectRef = sourceObjectRef,
            actualHash = actualHash,
            actualSizeBytes = actualSizeBytes,
            completedAt = completedAt,
            completionLeaseExpiresAt = null,
            completionClaimToken = null,
        )
    }

    fun fail(failureCode: String, claimToken: Uuid): PageContentUpload {
        require(status == PageContentUploadStatus.COMPLETING) { "only a completing upload can fail" }
        require(completionClaimToken == claimToken) { "completion claim is no longer owned" }
        require(failureCode.matches(SAFE_FAILURE_CODE)) { "failure code is invalid" }
        return copy(
            status = PageContentUploadStatus.FAILED,
            completionLeaseExpiresAt = null,
            completionClaimToken = null,
            failureCode = failureCode,
        )
    }

    fun consume(pageId: PageId, consumedAt: Instant): PageContentUpload {
        require(status == PageContentUploadStatus.COMPLETED) { "only a completed upload can be consumed" }
        return copy(
            status = PageContentUploadStatus.CONSUMED,
            consumedAt = consumedAt,
            consumedByPageId = pageId,
        )
    }

    fun abort(abortedAt: Instant): PageContentUploadAbortTransition {
        require(!abortedAt.isBefore(createdAt)) { "upload abort cannot precede creation" }
        return when (status) {
            PageContentUploadStatus.ABORTED -> PageContentUploadAbortTransition.Replayed(this)
            PageContentUploadStatus.COMPLETING -> if (
                abortedAt.isBefore(completionLeaseExpiresAt ?: error("completion lease is missing"))
            ) {
                PageContentUploadAbortTransition.InProgress
            } else {
                PageContentUploadAbortTransition.Aborted(abortCompleting(abortedAt, "UPLOAD_ABORTED"))
            }
            PageContentUploadStatus.CONSUMED -> PageContentUploadAbortTransition.AlreadyConsumed
            PageContentUploadStatus.INITIATED,
            PageContentUploadStatus.COMPLETED,
            PageContentUploadStatus.FAILED,
            -> PageContentUploadAbortTransition.Aborted(
                copy(
                    status = PageContentUploadStatus.ABORTED,
                    completionLeaseExpiresAt = null,
                    completionClaimToken = null,
                    abortedAt = abortedAt,
                ),
            )
        }
    }

    fun expire(expiredAt: Instant): PageContentUploadExpirationTransition {
        require(!expiredAt.isBefore(expiresAt)) { "upload cannot expire before its expiry" }
        return when (status) {
            PageContentUploadStatus.ABORTED -> PageContentUploadExpirationTransition.Replayed(this)
            PageContentUploadStatus.CONSUMED -> PageContentUploadExpirationTransition.Consumed
            PageContentUploadStatus.COMPLETING -> if (
                expiredAt.isBefore(completionLeaseExpiresAt ?: error("completion lease is missing"))
            ) {
                PageContentUploadExpirationTransition.InProgress
            } else {
                PageContentUploadExpirationTransition.Expired(abortCompleting(expiredAt, "UPLOAD_EXPIRED"))
            }
            PageContentUploadStatus.INITIATED,
            PageContentUploadStatus.COMPLETED,
            PageContentUploadStatus.FAILED,
            -> PageContentUploadExpirationTransition.Expired(
                copy(
                    status = PageContentUploadStatus.ABORTED,
                    completionLeaseExpiresAt = null,
                    completionClaimToken = null,
                    abortedAt = expiredAt,
                ),
            )
        }
    }

    private fun abortCompleting(abortedAt: Instant, failureCode: String): PageContentUpload = copy(
        status = PageContentUploadStatus.ABORTED,
        completionLeaseExpiresAt = null,
        completionClaimToken = null,
        abortedAt = abortedAt,
        failureCode = failureCode,
    )

    companion object {
        private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
        private val SAFE_FAILURE_CODE = Regex("[A-Z0-9_.-]{1,100}")

        fun initiate(
            uploadRef: PageContentUploadRef,
            workspaceId: Uuid,
            actor: PageActor,
            format: PageContentFormat,
            expectedHash: PageContentHash,
            expectedSizeBytes: Long,
            temporaryObjectRef: String,
            multipartUploadId: String,
            expiresAt: Instant,
            createdAt: Instant,
        ): PageContentUpload = PageContentUpload(
            uploadRef = uploadRef,
            workspaceId = workspaceId,
            actor = actor,
            format = format,
            expectedHash = expectedHash,
            expectedSizeBytes = expectedSizeBytes,
            temporaryObjectRef = temporaryObjectRef,
            multipartUploadId = multipartUploadId,
            expiresAt = expiresAt,
            status = PageContentUploadStatus.INITIATED,
            completionRequestHash = null,
            completionStartedAt = null,
            completionLeaseExpiresAt = null,
            completionClaimToken = null,
            sourceObjectRef = null,
            actualHash = null,
            actualSizeBytes = null,
            completedAt = null,
            consumedAt = null,
            consumedByPageId = null,
            abortedAt = null,
            failureCode = null,
            createdAt = createdAt,
        )
    }
}

sealed interface PageContentUploadAbortTransition {
    data class Aborted(val upload: PageContentUpload) : PageContentUploadAbortTransition
    data class Replayed(val upload: PageContentUpload) : PageContentUploadAbortTransition
    data object InProgress : PageContentUploadAbortTransition
    data object AlreadyConsumed : PageContentUploadAbortTransition
}

sealed interface PageContentUploadExpirationTransition {
    data class Expired(val upload: PageContentUpload) : PageContentUploadExpirationTransition
    data class Replayed(val upload: PageContentUpload) : PageContentUploadExpirationTransition
    data object InProgress : PageContentUploadExpirationTransition
    data object Consumed : PageContentUploadExpirationTransition
}

sealed interface PageContentUploadCompletionStart {
    data class Started(
        val upload: PageContentUpload,
        val recovered: Boolean,
        val claimToken: Uuid,
    ) : PageContentUploadCompletionStart
    data class Replayed(val upload: PageContentUpload) : PageContentUploadCompletionStart
    data class Failed(val failureCode: String) : PageContentUploadCompletionStart
    data object InProgress : PageContentUploadCompletionStart
    data object Expired : PageContentUploadCompletionStart
    data object RequestReused : PageContentUploadCompletionStart
}

data class PageVersion(
    val pageId: PageId,
    val versionNumber: PageVersionNumber,
    val format: PageContentFormat,
    val sourceObjectRef: String,
    val contentHash: PageContentHash,
    val contentSizeBytes: Long,
    val finalizedBy: PageActor,
    val finalizedByAgentClientId: String?,
    val finalizedAt: Instant,
) {
    init {
        require(sourceObjectRef.isNotBlank()) { "source object ref must not be blank" }
        cc.monomer.pages.domain.requirePageAgentClient(finalizedBy, finalizedByAgentClientId)
        require(contentSizeBytes > 0) { "content size must be positive" }
    }

    companion object {
        fun fromUpload(
            pageId: PageId,
            versionNumber: PageVersionNumber,
            upload: PageContentUpload,
            finalizedBy: PageActor,
            finalizedByAgentClientId: String?,
            finalizedAt: Instant,
        ): PageVersion {
            require(upload.status == PageContentUploadStatus.COMPLETED) { "PageVersion requires a completed upload" }
            return PageVersion(
                pageId = pageId,
                versionNumber = versionNumber,
                format = upload.format,
                sourceObjectRef = upload.sourceObjectRef ?: error("completed upload has no source object ref"),
                contentHash = upload.actualHash ?: error("completed upload has no actual hash"),
                contentSizeBytes = upload.actualSizeBytes ?: error("completed upload has no actual size"),
                finalizedBy = finalizedBy,
                finalizedByAgentClientId = finalizedByAgentClientId,
                finalizedAt = finalizedAt,
            )
        }
    }

    fun metadata(): PageVersionMetadata = PageVersionMetadata(
        pageId = pageId,
        versionNumber = versionNumber,
        format = format,
        contentHash = contentHash,
        contentSizeBytes = contentSizeBytes,
        finalizedBy = finalizedBy,
        finalizedByAgentClientId = finalizedByAgentClientId,
        finalizedAt = finalizedAt,
    )
}

data class PageVersionMetadata(
    val pageId: PageId,
    val versionNumber: PageVersionNumber,
    val format: PageContentFormat,
    val contentHash: PageContentHash,
    val contentSizeBytes: Long,
    val finalizedBy: PageActor,
    val finalizedByAgentClientId: String?,
    val finalizedAt: Instant,
) {
    init {
        require(contentSizeBytes > 0) { "content size must be positive" }
        cc.monomer.pages.domain.requirePageAgentClient(finalizedBy, finalizedByAgentClientId)
    }
}

data class PageContentCommandResult(
    val workspaceId: Uuid,
    val actor: PageActor,
    val requestId: String,
    val requestHash: String,
    val requestBody: kotlinx.serialization.json.JsonObject,
    val version: PageVersionMetadata,
    val createdAt: Instant,
) {
    init {
        require(requestId.isNotBlank() && requestId.length <= 200) { "request id is invalid" }
        require(requestHash.matches(Regex("[0-9a-f]{64}"))) { "request hash must be a lowercase SHA-256 value" }
        require(requestBody.keys.none { key ->
            key.lowercase() in setOf("uploadref", "temporaryobjectref", "sourceobjectref", "objectref")
        }) { "content command results cannot persist upload or object capabilities" }
    }
}
