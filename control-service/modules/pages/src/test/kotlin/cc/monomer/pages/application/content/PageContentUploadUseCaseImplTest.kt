package cc.monomer.pages.application.content

import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageActorKind
import cc.monomer.pages.domain.PageId
import cc.monomer.pages.domain.common.TransactionRunner
import cc.monomer.pages.domain.content.ClaimedPageContentObjectCleanup
import cc.monomer.pages.domain.content.PageContentCleanupClaimResult
import cc.monomer.pages.domain.content.PageContentCleanupObjectKind
import cc.monomer.pages.domain.content.PageContentCleanupRepository
import cc.monomer.pages.domain.content.PageContentFormat
import cc.monomer.pages.domain.content.PageContentHash
import cc.monomer.pages.domain.content.PageContentObjectCleanupCandidate
import cc.monomer.pages.domain.content.PageContentObjectStorage
import cc.monomer.pages.domain.content.PageContentQueryPort
import cc.monomer.pages.domain.content.PageContentRepository
import cc.monomer.pages.domain.content.PageContentUpload
import cc.monomer.pages.domain.content.PageContentUploadRef
import cc.monomer.pages.domain.content.PageContentUploadStatus
import cc.monomer.pages.domain.content.PageVersion
import cc.monomer.pages.domain.content.PageVersionNumber
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PageContentUploadUseCaseImplTest {
    private val workspaceId = Uuid.parse("019e8000-0000-7000-8000-000000000001")
    private val actor = PageActor.create(PageActorKind.USER, "upload-test-user")
    private val createdAt = Instant.parse("2026-08-21T00:00:00Z")

    @Test
    fun `stale completion claim does not enqueue or delete the source owned by a recovery claim`() = runTest {
        val bytes = "# source".toByteArray()
        val upload = initiatedUpload(bytes)
        val repository = InMemoryUploadRepository(upload)
        val storage = InMemoryUploadStorage(upload, bytes)
        storage.afterFinalHead = {
            val current = repository.current
            val recovered = (current.startCompletion(
                requestHash = checkNotNull(current.completionRequestHash),
                requestedAt = createdAt.plusSeconds(100),
                leaseExpiresAt = createdAt.plusSeconds(160),
                claimToken = Uuid.parse("019e8000-0000-7000-8000-000000000004"),
            ) as cc.monomer.pages.domain.content.PageContentUploadCompletionStart.Started).upload
            repository.current = recovered
        }

        val outcome = useCase(repository, storage).complete(command(upload))

        assertThat(outcome).isEqualTo(PageContentUploadCompleteOutcome.InProgress)
        assertThat(repository.cleanupCandidates).noneMatch { candidate ->
            candidate.objectKind == PageContentCleanupObjectKind.FINAL_SOURCE
        }
        PageContentCleanupProcessor(repository, storage).processBatch("cleanup-worker")
        assertThat(storage.deletedObjects).isEmpty()
    }

    @Test
    fun `terminal completion failure queues deterministic source cleanup after the write`() = runTest {
        val bytes = "# source".toByteArray()
        val upload = initiatedUpload(bytes)
        val repository = InMemoryUploadRepository(upload)
        val storage = InMemoryUploadStorage(upload, bytes).apply {
            finalHeadHash = PageContentHash("b".repeat(64))
        }

        val outcome = useCase(repository, storage).complete(command(upload))
        val finalSource = "workspace/$workspaceId/source/${upload.uploadRef}"

        assertThat(outcome).isEqualTo(
            PageContentUploadCompleteOutcome.Rejected(
                PageContentUploadError(PageContentUploadErrorCode.SOURCE_OBJECT_COLLISION, null),
            ),
        )
        assertThat(repository.cleanupCandidates).anyMatch { candidate ->
            candidate.objectKind == PageContentCleanupObjectKind.FINAL_SOURCE &&
                candidate.objectRef == finalSource
        }
        PageContentCleanupProcessor(repository, storage).processBatch("cleanup-worker")
        assertThat(storage.deletedObjects).contains(finalSource)
    }

    @Test
    fun `object store rejection of the completed parts fails the upload instead of leaving it completing`() = runTest {
        listOf(
            PageContentObjectStorage.MultipartCompletionResult.PartsRejected to
                PageContentUploadErrorCode.INVALID_COMPLETED_PARTS,
            PageContentObjectStorage.MultipartCompletionResult.UploadMissing to
                PageContentUploadErrorCode.SOURCE_OBJECT_MISSING,
        ).forEach { (completion, errorCode) ->
            val bytes = "# source".toByteArray()
            val upload = initiatedUpload(bytes)
            val repository = InMemoryUploadRepository(upload)
            val storage = InMemoryUploadStorage(upload, bytes).apply { completionResult = completion }

            val outcome = useCase(repository, storage).complete(command(upload))

            assertThat(outcome).describedAs(completion.toString()).isEqualTo(
                PageContentUploadCompleteOutcome.Rejected(PageContentUploadError(errorCode, null)),
            )
            assertThat(repository.current.status).isEqualTo(PageContentUploadStatus.FAILED)
            assertThat(repository.current.failureCode).isEqualTo(errorCode.name)
            assertThat(repository.cleanupCandidates).anyMatch { candidate ->
                candidate.objectKind == PageContentCleanupObjectKind.TEMPORARY &&
                    candidate.objectRef == upload.temporaryObjectRef
            }
        }
    }

    @Test
    fun `notebook sources of the wrong shape are rejected as a format mismatch`() = runTest {
        listOf(
            "{\"nbformat\":{\"major\":4},\"cells\":[]}",
            "{\"nbformat\":4,\"cells\":{}}",
            "[{\"nbformat\":4,\"cells\":[]}]",
            "{\"nbformat\":4,",
        ).forEach { notebook ->
            val bytes = notebook.toByteArray()
            val upload = initiatedUpload(bytes, PageContentFormat.NOTEBOOK)

            val outcome = useCase(InMemoryUploadRepository(upload), InMemoryUploadStorage(upload, bytes)).complete(command(upload))

            assertThat(outcome).describedAs(notebook).isEqualTo(
                PageContentUploadCompleteOutcome.Rejected(
                    PageContentUploadError(PageContentUploadErrorCode.SOURCE_FORMAT_MISMATCH, null),
                ),
            )
        }
    }

    @Test
    fun `notebook sources keep the UTF-8 rejection code`() = runTest {
        val bytes = byteArrayOf(0x7b, 0xff.toByte(), 0x7d)
        val upload = initiatedUpload(bytes, PageContentFormat.NOTEBOOK)

        val outcome = useCase(InMemoryUploadRepository(upload), InMemoryUploadStorage(upload, bytes)).complete(command(upload))

        assertThat(outcome).isEqualTo(
            PageContentUploadCompleteOutcome.Rejected(
                PageContentUploadError(PageContentUploadErrorCode.SOURCE_UTF8_INVALID, null),
            ),
        )
    }

    @Test
    fun `a completed object of the wrong size is rejected and its unread download released`() = runTest {
        val bytes = "# source".toByteArray()
        val upload = initiatedUpload(bytes)
        val storage = InMemoryUploadStorage(upload, bytes).apply { downloadContentLength = bytes.size + 1L }

        val outcome = useCase(InMemoryUploadRepository(upload), storage).complete(command(upload))

        assertThat(outcome).isEqualTo(
            PageContentUploadCompleteOutcome.Rejected(
                PageContentUploadError(PageContentUploadErrorCode.SOURCE_SIZE_MISMATCH, null),
            ),
        )
        assertThat(storage.discardedDownloads).isEqualTo(1)
    }

    private fun useCase(
        repository: InMemoryUploadRepository,
        storage: InMemoryUploadStorage,
    ): PageContentUploadUseCaseImpl = PageContentUploadUseCaseImpl(
        repository = repository,
        queryPort = repository,
        transactionRunner = DirectTransactionRunner,
        objectStorage = storage,
    )

    private fun command(upload: PageContentUpload): PageContentUploadUseCase.CompleteCommand =
        PageContentUploadUseCase.CompleteCommand(
            workspaceId = workspaceId,
            actor = actor,
            uploadRef = upload.uploadRef,
            parts = listOf(PageContentUploadUseCase.CompletedPartCommand(1, "etag-1")),
            requestedAt = createdAt.plusSeconds(1),
        )

    private fun initiatedUpload(bytes: ByteArray): PageContentUpload = initiatedUpload(bytes, PageContentFormat.MARKDOWN)

    private fun initiatedUpload(bytes: ByteArray, format: PageContentFormat): PageContentUpload = PageContentUpload.initiate(
        uploadRef = PageContentUploadRef(Uuid.parse("019e8000-0000-7000-8000-000000000003")),
        workspaceId = workspaceId,
        actor = actor,
        format = format,
        expectedHash = PageContentHash(sha256(bytes)),
        expectedSizeBytes = bytes.size.toLong(),
        temporaryObjectRef = "workspace/$workspaceId/uploads/source",
        multipartUploadId = "multipart-1",
        expiresAt = createdAt.plusSeconds(900),
        createdAt = createdAt,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private object DirectTransactionRunner : TransactionRunner {
        override suspend fun <T> run(block: () -> T): T = block()
        override suspend fun <T> readOnly(block: () -> T): T = block()
    }
}

private class InMemoryUploadRepository(
    initial: PageContentUpload,
) : PageContentRepository, PageContentQueryPort, PageContentCleanupRepository {
    var current: PageContentUpload = initial
    val cleanupCandidates = mutableListOf<PageContentObjectCleanupCandidate>()

    override suspend fun findUpload(uploadRef: PageContentUploadRef): PageContentUpload? =
        current.takeIf { it.uploadRef == uploadRef }

    override suspend fun findLatestDocumentFormats(
        workspaceId: Uuid,
        pageIds: Set<PageId>,
    ): Map<PageId, PageContentFormat> = emptyMap()

    override fun findUploadForUpdateWithinTx(uploadRef: PageContentUploadRef): PageContentUpload? =
        current.takeIf { it.uploadRef == uploadRef }

    override fun updateUploadWithinTx(current: PageContentUpload, next: PageContentUpload) {
        check(this.current == current) { "test repository state changed unexpectedly" }
        this.current = next
    }

    override fun enqueueObjectCleanupWithinTx(candidate: PageContentObjectCleanupCandidate) {
        cleanupCandidates += candidate
    }

    override suspend fun terminalizeExpiredUploads(limit: Int): Int = 0

    override suspend fun claimDueObjectCleanup(
        workerId: String,
        leaseDurationMillis: Long,
        limit: Int,
    ): List<ClaimedPageContentObjectCleanup> = cleanupCandidates.map { candidate ->
        ClaimedPageContentObjectCleanup(
            workspaceId = candidate.workspaceId,
            objectRef = candidate.objectRef,
            objectKind = candidate.objectKind,
            multipartUploadId = candidate.multipartUploadId,
            claimToken = Uuid.generateV7(),
            leaseExpiresAt = candidate.notBefore.plusMillis(leaseDurationMillis),
        )
    }.take(limit)

    override suspend fun completeObjectCleanup(
        claim: ClaimedPageContentObjectCleanup,
    ): PageContentCleanupClaimResult = PageContentCleanupClaimResult.APPLIED

    override suspend fun retryObjectCleanup(
        claim: ClaimedPageContentObjectCleanup,
        retryBackoffMillis: Long,
    ): PageContentCleanupClaimResult = PageContentCleanupClaimResult.APPLIED

    override suspend fun listVersions(
        workspaceId: Uuid,
        pageId: PageId,
        beforeVersionNumber: PageVersionNumber?,
        limit: Int,
    ): List<PageVersion> = error("unused in upload race test")

    override suspend fun findVersion(
        workspaceId: Uuid,
        pageId: PageId,
        versionNumber: PageVersionNumber,
    ): PageVersion? = error("unused in upload race test")

    override fun insertUploadWithinTx(upload: PageContentUpload) = error("unused in upload race test")

    override fun prepareDocumentPageDeletionWithinTx(
        workspaceId: Uuid,
        pageId: PageId,
        notBefore: Instant,
    ) = error("unused in upload race test")

    override fun lockDocumentPageWithinTx(workspaceId: Uuid, pageId: PageId): Boolean =
        error("unused in upload race test")

    override fun findLatestVersionNumberWithinTx(pageId: PageId): PageVersionNumber? =
        error("unused in upload race test")

    override fun lockCommandKeyWithinTx(
        workspaceId: Uuid,
        actor: PageActor,
        requestId: String,
        createdAt: Instant,
    ) = error("unused in upload race test")

    override fun findCommandResultWithinTx(
        workspaceId: Uuid,
        actor: PageActor,
        requestId: String,
    ): cc.monomer.pages.domain.content.PageContentCommandResult? = error("unused in upload race test")

    override fun insertVersionAndConsumeUploadWithinTx(
        upload: PageContentUpload,
        version: PageVersion,
        consumedAt: Instant,
    ) = error("unused in upload race test")

    override fun insertCommandResultWithinTx(
        result: cc.monomer.pages.domain.content.PageContentCommandResult,
    ) = error("unused in upload race test")
}

private class InMemoryUploadStorage(
    private val upload: PageContentUpload,
    private val bytes: ByteArray,
) : PageContentObjectStorage {
    var afterFinalHead: (() -> Unit)? = null
    var completionResult: PageContentObjectStorage.MultipartCompletionResult =
        PageContentObjectStorage.MultipartCompletionResult.Completed
    var finalHeadHash: PageContentHash = PageContentHash(sha256(bytes))
    var downloadContentLength: Long = bytes.size.toLong()
    var discardedDownloads = 0
    val deletedObjects = mutableListOf<String>()

    override suspend fun createMultipartUpload(objectRef: String, contentType: String) =
        error("unused in upload race test")

    override suspend fun presignUploadPart(
        upload: PageContentObjectStorage.MultipartUpload,
        partNumber: Int,
        contentLength: Long,
        signatureDuration: Duration,
    ): URI = error("unused in upload race test")

    override suspend fun completeMultipartUpload(
        upload: PageContentObjectStorage.MultipartUpload,
        parts: List<PageContentObjectStorage.CompletedPart>,
    ): PageContentObjectStorage.MultipartCompletionResult = completionResult

    override suspend fun abortMultipartUpload(upload: PageContentObjectStorage.MultipartUpload) = Unit

    override suspend fun downloadBounded(objectRef: String, maximumBytes: Long): PageContentObjectStorage.ObjectDownload? =
        PageContentObjectStorage.ObjectDownload(
            contentLength = downloadContentLength,
            contentType = "text/markdown",
            content = flowOf(ByteBuffer.wrap(bytes)),
            discard = { discardedDownloads += 1 },
        ).takeIf { objectRef == upload.temporaryObjectRef }

    override suspend fun writeImmutableIfAbsent(
        objectRef: String,
        contentLength: Long,
        contentHash: PageContentHash,
        content: Flow<ByteBuffer>,
    ): PageContentObjectStorage.ImmutableWriteResult =
        PageContentObjectStorage.ImmutableWriteResult.AlreadyPresent

    override suspend fun head(objectRef: String): PageContentObjectStorage.ObjectMetadata? {
        if (objectRef != "workspace/${upload.workspaceId}/source/${upload.uploadRef}") return null
        afterFinalHead?.invoke()
        afterFinalHead = null
        return PageContentObjectStorage.ObjectMetadata(bytes.size.toLong(), finalHeadHash)
    }

    override suspend fun delete(objectRef: String) {
        deletedObjects += objectRef
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
