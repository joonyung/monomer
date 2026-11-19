package cc.monomer.pages.application.content

import cc.monomer.pages.application.PagesDirectoryEventBus
import cc.monomer.pages.application.PagesDirectoryWakeup
import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageActorKind
import cc.monomer.pages.domain.PageId
import cc.monomer.pages.domain.audit.AuditActorContext
import cc.monomer.pages.domain.audit.PagesAuditRepository
import cc.monomer.pages.domain.common.TransactionRunner
import cc.monomer.pages.domain.content.PageContentFormat
import cc.monomer.pages.domain.content.PageContentHash
import cc.monomer.pages.domain.content.PageContentRepository
import cc.monomer.pages.domain.content.PageContentUpload
import cc.monomer.pages.domain.content.PageContentUploadRef
import cc.monomer.pages.domain.content.PageVersionNumber
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PageContentUseCaseImplTest {
    private val workspaceId = Uuid.parse("019e8000-0000-7000-8000-000000000001")
    private val principalId = Uuid.parse("019e8000-0000-7000-8000-000000000002")
    private val pageId = PageId(Uuid.parse("019e8000-0000-7000-8000-000000000003"))
    private val actor = PageActor.create(PageActorKind.USER, principalId.toString())
    private val now = Instant.parse("2026-08-23T00:00:00Z")

    @Test
    fun `finalizing a document version wakes existing directory subscribers`() = runTest {
        val upload = completedUpload()
        val repository = mockk<PageContentRepository>(relaxed = true)
        every { repository.findCommandResultWithinTx(workspaceId, actor, "replace-1") } returns null
        every { repository.lockDocumentPageWithinTx(workspaceId, pageId) } returns true
        every { repository.findUploadForUpdateWithinTx(upload.uploadRef) } returns upload
        every { repository.findLatestVersionNumberWithinTx(pageId) } returns PageVersionNumber(1)
        val eventBus = RecordingPagesDirectoryEventBus()

        val outcome = PageContentUseCaseImpl(
            repository = repository,
            queryPort = mockk(relaxed = true),
            transactionRunner = DirectTransactionRunner,
            auditRepository = mockk<PagesAuditRepository>(relaxed = true),
            directoryEventBus = eventBus,
        ).replace(
            PageContentUseCase.ReplaceCommand(
                workspaceId = workspaceId,
                pageId = pageId,
                uploadRef = upload.uploadRef,
                requestId = "replace-1",
                actor = actor,
                agentClientId = null,
                auditActorContext = AuditActorContext(
                    organizationId = Uuid.parse("019e8000-0000-7000-8000-000000000004"),
                    principalId = principalId,
                    actorRef = principalId.toString(),
                ),
                requestedAt = now.plusSeconds(1),
            ),
        )

        assertThat(outcome).isInstanceOf(PageContentMutationOutcome.Applied::class.java)
        assertThat(eventBus.published).containsExactly(PagesDirectoryWakeup(workspaceId))
    }

    private fun completedUpload(): PageContentUpload {
        val bytes = "# source".toByteArray()
        val hash = PageContentHash("a".repeat(64))
        val initiated = PageContentUpload.initiate(
            uploadRef = PageContentUploadRef.generate(),
            workspaceId = workspaceId,
            actor = actor,
            format = PageContentFormat.MARKDOWN,
            expectedHash = hash,
            expectedSizeBytes = bytes.size.toLong(),
            temporaryObjectRef = "workspace/$workspaceId/uploads/source",
            multipartUploadId = "multipart-1",
            expiresAt = now.plusSeconds(900),
            createdAt = now,
        )
        val claimToken = Uuid.generateV7()
        val completing = (initiated.startCompletion(
            requestHash = "b".repeat(64),
            requestedAt = now,
            leaseExpiresAt = now.plusSeconds(60),
            claimToken = claimToken,
        ) as cc.monomer.pages.domain.content.PageContentUploadCompletionStart.Started).upload
        return completing.complete(
            sourceObjectRef = "workspace/$workspaceId/source/${initiated.uploadRef}",
            actualHash = hash,
            actualSizeBytes = bytes.size.toLong(),
            completedAt = now,
            claimToken = claimToken,
        )
    }

    private object DirectTransactionRunner : TransactionRunner {
        override suspend fun <T> run(block: () -> T): T = block()
        override suspend fun <T> readOnly(block: () -> T): T = block()
    }

    private class RecordingPagesDirectoryEventBus : PagesDirectoryEventBus {
        val published = mutableListOf<PagesDirectoryWakeup>()

        override fun publish(wakeup: PagesDirectoryWakeup) {
            published += wakeup
        }

        override fun subscribe(workspaceId: Uuid): Flow<PagesDirectoryWakeup> = emptyFlow()
    }
}
