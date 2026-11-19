package cc.monomer.pages.application.content

import cc.monomer.pages.application.content.rendering.StrictUtf8Decoder
import cc.monomer.pages.application.content.rendering.Utf8DecodingResult
import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.common.TransactionRunner
import cc.monomer.pages.domain.content.PageContentCleanupObjectKind
import cc.monomer.pages.domain.content.PageContentFormat
import cc.monomer.pages.domain.content.PageContentHash
import cc.monomer.pages.domain.content.PageContentObjectCleanupCandidate
import cc.monomer.pages.domain.content.PageContentObjectStorage
import cc.monomer.pages.domain.content.PageContentQueryPort
import cc.monomer.pages.domain.content.PageContentRepository
import cc.monomer.pages.domain.content.PageContentUpload
import cc.monomer.pages.domain.content.PageContentUploadAbortTransition
import cc.monomer.pages.domain.content.PageContentUploadCompletionStart
import cc.monomer.pages.domain.content.PageContentUploadExpirationTransition
import cc.monomer.pages.domain.content.PageContentUploadRef
import cc.monomer.pages.domain.content.PageContentUploadStatus
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

interface PageContentUploadUseCase {
    suspend fun createSession(command: CreateSessionCommand): PageContentUploadCreateOutcome
    suspend fun complete(command: CompleteCommand): PageContentUploadCompleteOutcome
    suspend fun abort(command: AbortCommand): PageContentUploadAbortOutcome

    data class CreateSessionCommand(
        val workspaceId: Uuid,
        val actor: PageActor,
        val format: PageContentFormat,
        val byteSize: Long,
        val sha256: PageContentHash,
        val requestedAt: Instant,
    )

    data class CompleteCommand(
        val workspaceId: Uuid,
        val actor: PageActor,
        val uploadRef: PageContentUploadRef,
        val parts: List<CompletedPartCommand>,
        val requestedAt: Instant,
    )

    data class CompletedPartCommand(
        val partNumber: Int,
        val eTag: String,
    )

    data class AbortCommand(
        val workspaceId: Uuid,
        val actor: PageActor,
        val uploadRef: PageContentUploadRef,
        val requestedAt: Instant,
    )
}

data class PageContentUploadPart(
    val partNumber: Int,
    val contentLength: Long,
    val uploadUrl: String,
)

data class PageContentUploadView(
    val uploadRef: PageContentUploadRef,
    val format: PageContentFormat,
    val byteSize: Long,
    val sha256: PageContentHash,
    val expiresAt: Instant,
)

sealed interface PageContentUploadCreateOutcome {
    data class Created(
        val upload: PageContentUploadView,
        val parts: List<PageContentUploadPart>,
    ) : PageContentUploadCreateOutcome

    data class Rejected(val error: PageContentUploadError) : PageContentUploadCreateOutcome
}

sealed interface PageContentUploadCompleteOutcome {
    data class Completed(val upload: PageContentUploadView) : PageContentUploadCompleteOutcome
    data class Replayed(val upload: PageContentUploadView) : PageContentUploadCompleteOutcome
    data class Rejected(val error: PageContentUploadError) : PageContentUploadCompleteOutcome
    data object NotFound : PageContentUploadCompleteOutcome
    data object InProgress : PageContentUploadCompleteOutcome
    data object RequestReused : PageContentUploadCompleteOutcome
}

sealed interface PageContentUploadAbortOutcome {
    data object Aborted : PageContentUploadAbortOutcome
    data object Replayed : PageContentUploadAbortOutcome
    data object NotFound : PageContentUploadAbortOutcome
    data object InProgress : PageContentUploadAbortOutcome
    data object AlreadyConsumed : PageContentUploadAbortOutcome
}

data class PageContentUploadError(
    val code: PageContentUploadErrorCode,
    val maximumBytes: Long?,
)

enum class PageContentUploadErrorCode {
    SOURCE_SIZE_INVALID,
    UPLOAD_EXPIRED,
    UPLOAD_FAILED,
    UPLOAD_ABORTED,
    INVALID_COMPLETED_PARTS,
    SOURCE_OBJECT_MISSING,
    SOURCE_SIZE_MISMATCH,
    SOURCE_HASH_MISMATCH,
    SOURCE_FORMAT_MISMATCH,
    SOURCE_UTF8_INVALID,
    SOURCE_OBJECT_COLLISION,
}

class PageContentUploadUseCaseImpl(
    private val repository: PageContentRepository,
    private val queryPort: PageContentQueryPort,
    private val transactionRunner: TransactionRunner,
    private val objectStorage: PageContentObjectStorage,
) : PageContentUploadUseCase {
    private val strictUtf8Decoder = StrictUtf8Decoder()
    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun createSession(
        command: PageContentUploadUseCase.CreateSessionCommand,
    ): PageContentUploadCreateOutcome {
        if (command.byteSize !in 1..MAXIMUM_SOURCE_BYTES) {
            return PageContentUploadCreateOutcome.Rejected(
                PageContentUploadError(PageContentUploadErrorCode.SOURCE_SIZE_INVALID, MAXIMUM_SOURCE_BYTES),
            )
        }
        val partCount = ceil(command.byteSize.toDouble() / MULTIPART_PART_BYTES.toDouble()).toInt()
        check(partCount in 1..MAXIMUM_PART_COUNT) { "source size produced an invalid part count" }
        val uploadRef = PageContentUploadRef.generate()
        val temporaryObjectRef = "workspace/${command.workspaceId}/uploads/$uploadRef/source"
        val multipart = objectStorage.createMultipartUpload(
            temporaryObjectRef,
            command.format.contentType(),
        )
        val upload = PageContentUpload.initiate(
            uploadRef = uploadRef,
            workspaceId = command.workspaceId,
            actor = command.actor,
            format = command.format,
            expectedHash = command.sha256,
            expectedSizeBytes = command.byteSize,
            temporaryObjectRef = temporaryObjectRef,
            multipartUploadId = multipart.uploadId,
            expiresAt = command.requestedAt.plus(UPLOAD_CAPABILITY_LIFETIME),
            createdAt = command.requestedAt,
        )
        return try {
            val signatureDuration = Duration.between(command.requestedAt, upload.expiresAt)
            val parts = (1..partCount).map { partNumber ->
                val contentLength = expectedPartLength(command.byteSize, partNumber, partCount)
                PageContentUploadPart(
                    partNumber = partNumber,
                    contentLength = contentLength,
                    uploadUrl = objectStorage.presignUploadPart(
                        multipart,
                        partNumber,
                        contentLength,
                        signatureDuration,
                    ).toString(),
                )
            }
            transactionRunner.run {
                repository.insertUploadWithinTx(upload)
                repository.enqueueObjectCleanupWithinTx(upload.temporaryCleanup(upload.expiresAt))
            }
            PageContentUploadCreateOutcome.Created(upload.toView(), parts)
        } catch (exception: Throwable) {
            try {
                objectStorage.abortMultipartUpload(multipart)
            } catch (abortFailure: Throwable) {
                exception.addSuppressed(abortFailure)
            }
            throw exception
        }
    }

    override suspend fun complete(
        command: PageContentUploadUseCase.CompleteCommand,
    ): PageContentUploadCompleteOutcome {
        val visibleUpload = queryPort.findUpload(command.uploadRef)
            ?.takeIf { it.workspaceId == command.workspaceId && it.actor == command.actor }
            ?: return PageContentUploadCompleteOutcome.NotFound
        val sortedParts = validateCompletedParts(visibleUpload, command.parts)
            ?: return PageContentUploadCompleteOutcome.Rejected(
                PageContentUploadError(PageContentUploadErrorCode.INVALID_COMPLETED_PARTS, null),
            )
        val requestHash = completionRequestHash(command.uploadRef, sortedParts)
        val start = transactionRunner.run {
            val current = repository.findUploadForUpdateWithinTx(command.uploadRef)
                ?.takeIf { it.workspaceId == command.workspaceId && it.actor == command.actor }
                ?: return@run CompletionStart.NotFound
            val leaseExpiresAt = minOf(
                command.requestedAt.plus(COMPLETION_LEASE_DURATION),
                current.expiresAt,
            )
            val claimToken = Uuid.generateV7()
            when (val result = current.startCompletion(requestHash, command.requestedAt, leaseExpiresAt, claimToken)) {
                is PageContentUploadCompletionStart.Started -> {
                    repository.updateUploadWithinTx(current, result.upload)
                    CompletionStart.Started(result.upload, result.recovered, result.claimToken)
                }
                is PageContentUploadCompletionStart.Replayed -> CompletionStart.Replayed(result.upload)
                is PageContentUploadCompletionStart.Failed -> CompletionStart.Failed(result.failureCode)
                PageContentUploadCompletionStart.InProgress -> CompletionStart.InProgress
                PageContentUploadCompletionStart.Expired -> {
                    val expired = when (val transition = current.expire(command.requestedAt)) {
                        is PageContentUploadExpirationTransition.Expired -> transition.upload
                        else -> error("expired upload did not terminalize")
                    }
                    repository.updateUploadWithinTx(current, expired)
                    repository.enqueueObjectCleanupWithinTx(expired.temporaryCleanup(command.requestedAt))
                    repository.enqueueObjectCleanupWithinTx(
                        expired.finalSourceCleanup(
                            sourceObjectRef(command.workspaceId, command.uploadRef),
                            command.requestedAt,
                        ),
                    )
                    CompletionStart.Expired(expired)
                }
                PageContentUploadCompletionStart.RequestReused -> CompletionStart.RequestReused
            }
        }
        when (start) {
            CompletionStart.NotFound -> return PageContentUploadCompleteOutcome.NotFound
            is CompletionStart.Replayed -> return PageContentUploadCompleteOutcome.Replayed(start.upload.toView())
            is CompletionStart.Failed -> return PageContentUploadCompleteOutcome.Rejected(
                PageContentUploadError(
                    if (start.failureCode == PageContentUploadErrorCode.UPLOAD_ABORTED.name) {
                        PageContentUploadErrorCode.UPLOAD_ABORTED
                    } else {
                        PageContentUploadErrorCode.UPLOAD_FAILED
                    },
                    null,
                ),
            )
            CompletionStart.InProgress -> return PageContentUploadCompleteOutcome.InProgress
            CompletionStart.RequestReused -> return PageContentUploadCompleteOutcome.RequestReused
            is CompletionStart.Expired -> {
                return PageContentUploadCompleteOutcome.Rejected(
                    PageContentUploadError(PageContentUploadErrorCode.UPLOAD_EXPIRED, null),
                )
            }
            is CompletionStart.Started -> Unit
        }
        val startedCompletion = start
        val completingUpload = startedCompletion.upload
        return try {
            ensureTemporaryObjectCompleted(completingUpload, sortedParts, startedCompletion.recovered)
            val validated = validateCompletedObject(completingUpload)
            val sourceObjectRef = sourceObjectRef(command.workspaceId, command.uploadRef)
            when (objectStorage.writeImmutableIfAbsent(
                sourceObjectRef,
                validated.bytes.size.toLong(),
                validated.hash,
                flowOf(ByteBuffer.wrap(validated.bytes)),
            )) {
                PageContentObjectStorage.ImmutableWriteResult.Written,
                PageContentObjectStorage.ImmutableWriteResult.AlreadyPresent,
                -> Unit
                is PageContentObjectStorage.ImmutableWriteResult.Collision -> throw ValidationFailure(
                    PageContentUploadErrorCode.SOURCE_OBJECT_COLLISION,
                )
            }
            val storedSource = objectStorage.head(sourceObjectRef)
            if (storedSource == null) return PageContentUploadCompleteOutcome.InProgress
            if (storedSource.contentLength != validated.bytes.size.toLong() ||
                storedSource.contentHash != validated.hash
            ) {
                throw ValidationFailure(PageContentUploadErrorCode.SOURCE_OBJECT_COLLISION)
            }
            val finish = transactionRunner.run {
                val current = repository.findUploadForUpdateWithinTx(command.uploadRef)
                    ?: error("Page content upload disappeared during completion")
                if (current.status == PageContentUploadStatus.COMPLETED ||
                    current.status == PageContentUploadStatus.CONSUMED
                ) {
                    repository.enqueueObjectCleanupWithinTx(
                        current.finalSourceCleanup(sourceObjectRef, command.requestedAt),
                    )
                    return@run CompletionFinish.Replayed(current)
                }
                if (current.status != PageContentUploadStatus.COMPLETING ||
                    current.completionRequestHash != requestHash ||
                    current.completionClaimToken != startedCompletion.claimToken
                ) {
                    return@run CompletionFinish.OwnershipLost
                }
                val next = current.complete(
                    sourceObjectRef,
                    validated.hash,
                    validated.bytes.size.toLong(),
                    command.requestedAt,
                    startedCompletion.claimToken,
                )
                repository.updateUploadWithinTx(current, next)
                repository.enqueueObjectCleanupWithinTx(next.temporaryCleanup(command.requestedAt))
                CompletionFinish.Completed(next)
            }
            when (finish) {
                is CompletionFinish.Completed -> PageContentUploadCompleteOutcome.Completed(finish.upload.toView())
                is CompletionFinish.Replayed -> PageContentUploadCompleteOutcome.Replayed(finish.upload.toView())
                CompletionFinish.OwnershipLost -> PageContentUploadCompleteOutcome.InProgress
            }
        } catch (failure: ValidationFailure) {
            failUpload(completingUpload, startedCompletion.claimToken, failure.code, command.requestedAt)
            PageContentUploadCompleteOutcome.Rejected(PageContentUploadError(failure.code, failure.maximumBytes))
        }
    }

    override suspend fun abort(
        command: PageContentUploadUseCase.AbortCommand,
    ): PageContentUploadAbortOutcome {
        queryPort.findUpload(command.uploadRef)
            ?.takeIf { it.workspaceId == command.workspaceId && it.actor == command.actor }
            ?: return PageContentUploadAbortOutcome.NotFound
        val aborted = transactionRunner.run {
            val current = repository.findUploadForUpdateWithinTx(command.uploadRef)
                ?.takeIf { it.workspaceId == command.workspaceId && it.actor == command.actor }
                ?: return@run AbortResult.NotFound
            when (val transition = current.abort(command.requestedAt)) {
                is PageContentUploadAbortTransition.Aborted -> {
                    repository.updateUploadWithinTx(current, transition.upload)
                    repository.enqueueObjectCleanupWithinTx(transition.upload.temporaryCleanup(command.requestedAt))
                    repository.enqueueObjectCleanupWithinTx(
                        transition.upload.finalSourceCleanup(
                            sourceObjectRef(command.workspaceId, command.uploadRef),
                            command.requestedAt,
                        ),
                    )
                    AbortResult.Aborted
                }
                is PageContentUploadAbortTransition.Replayed -> AbortResult.Replayed
                PageContentUploadAbortTransition.InProgress -> AbortResult.InProgress
                PageContentUploadAbortTransition.AlreadyConsumed -> AbortResult.AlreadyConsumed
            }
        }
        return when (aborted) {
            AbortResult.Aborted -> PageContentUploadAbortOutcome.Aborted
            AbortResult.Replayed -> PageContentUploadAbortOutcome.Replayed
            AbortResult.NotFound -> PageContentUploadAbortOutcome.NotFound
            AbortResult.InProgress -> PageContentUploadAbortOutcome.InProgress
            AbortResult.AlreadyConsumed -> PageContentUploadAbortOutcome.AlreadyConsumed
        }
    }

    private suspend fun validateCompletedObject(upload: PageContentUpload): ValidatedSource {
        val download = objectStorage.downloadBounded(upload.temporaryObjectRef, MAXIMUM_SOURCE_BYTES)
            ?: throw ValidationFailure(PageContentUploadErrorCode.SOURCE_OBJECT_MISSING)
        if (download.contentLength != upload.expectedSizeBytes) {
            download.discard()
            throw ValidationFailure(PageContentUploadErrorCode.SOURCE_SIZE_MISMATCH)
        }
        val output = ByteArrayOutputStream(upload.expectedSizeBytes.toInt())
        val digest = MessageDigest.getInstance("SHA-256")
        var receivedBytes = 0L
        download.content.collect { sourceBuffer ->
            val buffer = sourceBuffer.asReadOnlyBuffer()
            val chunk = ByteArray(buffer.remaining())
            buffer.get(chunk)
            receivedBytes += chunk.size
            if (receivedBytes > MAXIMUM_SOURCE_BYTES) {
                throw ValidationFailure(PageContentUploadErrorCode.SOURCE_SIZE_INVALID, MAXIMUM_SOURCE_BYTES)
            }
            digest.update(chunk)
            output.write(chunk)
        }
        if (receivedBytes != upload.expectedSizeBytes) {
            throw ValidationFailure(PageContentUploadErrorCode.SOURCE_SIZE_MISMATCH)
        }
        val actualHash = PageContentHash(digest.digest().joinToString("") { byte -> "%02x".format(byte) })
        if (actualHash != upload.expectedHash) {
            throw ValidationFailure(PageContentUploadErrorCode.SOURCE_HASH_MISMATCH)
        }
        val bytes = output.toByteArray()
        validateFormat(upload.format, bytes)
        return ValidatedSource(bytes, actualHash)
    }

    private suspend fun ensureTemporaryObjectCompleted(
        upload: PageContentUpload,
        parts: List<PageContentUploadUseCase.CompletedPartCommand>,
        recovering: Boolean,
    ) {
        if (recovering && objectStorage.head(upload.temporaryObjectRef) != null) return
        val completion = try {
            objectStorage.completeMultipartUpload(
                upload.multipart(),
                parts.map { part -> PageContentObjectStorage.CompletedPart(part.partNumber, part.eTag) },
            )
        } catch (exception: Throwable) {
            if (objectStorage.head(upload.temporaryObjectRef) != null) return
            throw exception
        }
        val rejection = when (completion) {
            PageContentObjectStorage.MultipartCompletionResult.Completed -> return
            PageContentObjectStorage.MultipartCompletionResult.PartsRejected ->
                PageContentUploadErrorCode.INVALID_COMPLETED_PARTS
            PageContentObjectStorage.MultipartCompletionResult.UploadMissing ->
                PageContentUploadErrorCode.SOURCE_OBJECT_MISSING
        }
        if (objectStorage.head(upload.temporaryObjectRef) != null) return
        throw ValidationFailure(rejection)
    }

    private fun validateFormat(format: PageContentFormat, bytes: ByteArray) {
        when (format) {
            PageContentFormat.MARKDOWN,
            PageContentFormat.HTML,
            -> decodeUtf8(bytes)
            PageContentFormat.NOTEBOOK -> {
                val text = decodeUtf8(bytes)
                val root = try {
                    json.parseToJsonElement(text) as? JsonObject
                } catch (_: SerializationException) {
                    null
                }
                val nbformat = (root?.get("nbformat") as? JsonPrimitive)?.intOrNull
                if (nbformat != 4 || root["cells"] !is JsonArray) {
                    throw ValidationFailure(PageContentUploadErrorCode.SOURCE_FORMAT_MISMATCH)
                }
            }
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String = when (val decoded = strictUtf8Decoder.decode(bytes)) {
        is Utf8DecodingResult.Decoded -> decoded.text
        is Utf8DecodingResult.Rejected -> throw ValidationFailure(PageContentUploadErrorCode.SOURCE_UTF8_INVALID)
    }

    private suspend fun failUpload(
        upload: PageContentUpload,
        claimToken: Uuid,
        errorCode: PageContentUploadErrorCode,
        failedAt: Instant,
    ): Boolean = transactionRunner.run {
        val current = repository.findUploadForUpdateWithinTx(upload.uploadRef) ?: return@run false
        if (current.status == PageContentUploadStatus.COMPLETING && current.completionClaimToken == claimToken) {
            val failed = current.fail(errorCode.name, claimToken)
            repository.updateUploadWithinTx(current, failed)
            repository.enqueueObjectCleanupWithinTx(current.temporaryCleanup(failedAt))
            repository.enqueueObjectCleanupWithinTx(
                failed.finalSourceCleanup(
                    sourceObjectRef(failed.workspaceId, failed.uploadRef),
                    failedAt,
                ),
            )
            true
        } else {
            false
        }
    }

    private fun PageContentUpload.temporaryCleanup(notBefore: Instant): PageContentObjectCleanupCandidate =
        PageContentObjectCleanupCandidate(
            workspaceId = workspaceId,
            objectRef = temporaryObjectRef,
            objectKind = PageContentCleanupObjectKind.TEMPORARY,
            multipartUploadId = multipartUploadId,
            notBefore = notBefore,
            createdAt = createdAt,
        )

    private fun PageContentUpload.finalSourceCleanup(
        objectRef: String,
        notBefore: Instant,
    ): PageContentObjectCleanupCandidate = PageContentObjectCleanupCandidate(
        workspaceId = workspaceId,
        objectRef = objectRef,
        objectKind = PageContentCleanupObjectKind.FINAL_SOURCE,
        multipartUploadId = null,
        notBefore = notBefore,
        createdAt = createdAt,
    )

    private fun validateCompletedParts(
        upload: PageContentUpload,
        parts: List<PageContentUploadUseCase.CompletedPartCommand>,
    ): List<PageContentUploadUseCase.CompletedPartCommand>? {
        val expectedCount = ceil(upload.expectedSizeBytes.toDouble() / MULTIPART_PART_BYTES.toDouble()).toInt()
        if (parts.size != expectedCount || parts.size !in 1..MAXIMUM_PART_COUNT) return null
        if (parts.any { part ->
                part.partNumber !in 1..expectedCount || part.eTag.isBlank() || part.eTag.length > MAXIMUM_ETAG_LENGTH ||
                    part.eTag.any { character -> character == '\r' || character == '\n' || character.code < 0x20 }
            }
        ) return null
        val sorted = parts.sortedBy { it.partNumber }
        return sorted.takeIf { values -> values.map { it.partNumber } == (1..expectedCount).toList() }
    }

    private fun completionRequestHash(
        uploadRef: PageContentUploadRef,
        parts: List<PageContentUploadUseCase.CompletedPartCommand>,
    ): String {
        val body = buildJsonObject {
            put("uploadRef", uploadRef.toString())
            put("parts", JsonArray(parts.map { part ->
                buildJsonObject {
                    put("partNumber", part.partNumber)
                    put("eTag", part.eTag)
                }
            }))
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(json.encodeToString(JsonElement.serializer(), body).toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun expectedPartLength(totalBytes: Long, partNumber: Int, partCount: Int): Long =
        if (partNumber < partCount) MULTIPART_PART_BYTES else totalBytes - MULTIPART_PART_BYTES * (partCount - 1)

    private fun sourceObjectRef(workspaceId: Uuid, uploadRef: PageContentUploadRef): String =
        "workspace/$workspaceId/source/$uploadRef"

    private fun PageContentUpload.multipart(): PageContentObjectStorage.MultipartUpload =
        PageContentObjectStorage.MultipartUpload(temporaryObjectRef, multipartUploadId)

    private fun PageContentUpload.toView(): PageContentUploadView = PageContentUploadView(
        uploadRef = uploadRef,
        format = format,
        byteSize = expectedSizeBytes,
        sha256 = expectedHash,
        expiresAt = expiresAt,
    )

    private fun PageContentFormat.contentType(): String = when (this) {
        PageContentFormat.MARKDOWN -> "text/markdown; charset=utf-8"
        PageContentFormat.NOTEBOOK -> "application/x-ipynb+json"
        PageContentFormat.HTML -> "text/html; charset=utf-8"
    }

    private sealed interface CompletionStart {
        data class Started(
            val upload: PageContentUpload,
            val recovered: Boolean,
            val claimToken: Uuid,
        ) : CompletionStart
        data class Replayed(val upload: PageContentUpload) : CompletionStart
        data class Expired(val upload: PageContentUpload) : CompletionStart
        data object NotFound : CompletionStart
        data class Failed(val failureCode: String) : CompletionStart
        data object InProgress : CompletionStart
        data object RequestReused : CompletionStart
    }

    private sealed interface CompletionFinish {
        data class Completed(val upload: PageContentUpload) : CompletionFinish
        data class Replayed(val upload: PageContentUpload) : CompletionFinish
        data object OwnershipLost : CompletionFinish
    }

    private sealed interface AbortResult {
        data object Aborted : AbortResult
        data object Replayed : AbortResult
        data object NotFound : AbortResult
        data object InProgress : AbortResult
        data object AlreadyConsumed : AbortResult
    }

    private data class ValidatedSource(
        val bytes: ByteArray,
        val hash: PageContentHash,
    )

    private class ValidationFailure(
        val code: PageContentUploadErrorCode,
        val maximumBytes: Long? = null,
    ) : RuntimeException(code.name)

    companion object {
        const val MAXIMUM_SOURCE_BYTES: Long = 20L * 1024 * 1024
        const val MULTIPART_PART_BYTES: Long = 8L * 1024 * 1024
        const val MAXIMUM_PART_COUNT: Int = 7
        private const val MAXIMUM_ETAG_LENGTH: Int = 200
        private val UPLOAD_CAPABILITY_LIFETIME: Duration = Duration.ofMinutes(15)
        private val COMPLETION_LEASE_DURATION: Duration = Duration.ofSeconds(60)
    }
}
