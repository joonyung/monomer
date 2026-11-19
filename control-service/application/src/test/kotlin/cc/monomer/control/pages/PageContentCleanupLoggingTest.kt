package cc.monomer.control.pages

import cc.monomer.control.observability.RequestLogTestSupport
import cc.monomer.control.pages.inbound.scheduler.PageContentCleanupScheduler
import cc.monomer.pages.adapter.outbound.messaging.InstanceIdHolder
import cc.monomer.pages.application.content.PageContentCleanupBatchResult
import cc.monomer.pages.application.content.PageContentCleanupProcessor
import cc.monomer.pages.domain.content.ClaimedPageContentObjectCleanup
import cc.monomer.pages.domain.content.PageContentCleanupClaimResult
import cc.monomer.pages.domain.content.PageContentCleanupObjectKind
import cc.monomer.pages.domain.content.PageContentCleanupRepository
import cc.monomer.pages.domain.content.PageContentObjectStorage
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class PageContentCleanupLoggingTest {
    private val workspaceId = Uuid.parse("019e501f-0000-7000-8000-000000000011")
    private val repository = mockk<PageContentCleanupRepository>()
    private val objectStorage = mockk<PageContentObjectStorage>()

    @Test
    fun `failed object cleanup logs kind and cause without the object ref and schedules a retry`() = runTest {
        val failing = claim("workspaces/$workspaceId/uploads/private-temporary", PageContentCleanupObjectKind.TEMPORARY, "private-multipart-id")
        val completed = claim("workspaces/$workspaceId/sources/private-final", PageContentCleanupObjectKind.FINAL_SOURCE, null)
        coEvery { repository.terminalizeExpiredUploads(any()) } returns 0
        coEvery { repository.claimDueObjectCleanup("pages-local", any(), any()) } returns listOf(failing, completed)
        coEvery { objectStorage.abortMultipartUpload(any()) } throws IllegalStateException("upstream private-temporary secret-token")
        coEvery { objectStorage.delete(completed.objectRef) } returns Unit
        coEvery { repository.completeObjectCleanup(completed) } returns PageContentCleanupClaimResult.APPLIED
        coEvery { repository.retryObjectCleanup(failing, any()) } returns PageContentCleanupClaimResult.APPLIED
        val logs = capture(PageContentCleanupProcessor::class.java)
        try {
            val result = PageContentCleanupProcessor(repository, objectStorage).processBatch("pages-local")

            assertEquals(PageContentCleanupBatchResult(0, 2, 1, 1), result)
            val event = logs.list.single()
            val fields = RequestLogTestSupport.fields(event)
            assertEquals("pages_content_cleanup_object_failed", event.formattedMessage)
            assertEquals(setOf("event", "object_kind", "retry_scheduled", "error.type", "error.stack_trace"), fields.keys)
            assertEquals("TEMPORARY", fields["object_kind"])
            assertEquals(true, fields["retry_scheduled"])
            assertEquals(IllegalStateException::class.java.name, fields["error.type"])
            assertTrue((fields["error.stack_trace"] as String).startsWith(IllegalStateException::class.java.name))
            for (secret in listOf("private-temporary", "private-multipart-id", "secret-token", workspaceId.toString())) {
                assertFalse(fields.values.toString().contains(secret))
            }
            coVerify(exactly = 1) { repository.retryObjectCleanup(failing, any()) }
        } finally {
            (LoggerFactory.getLogger(PageContentCleanupProcessor::class.java) as Logger).detachAppender(logs)
        }
    }

    @Test
    fun `failed cleanup batch logs its cause`() {
        val processor = mockk<PageContentCleanupProcessor>()
        coEvery { processor.processBatch("pages-local") } throws IllegalStateException("database private diagnostics")
        val logs = capture(PageContentCleanupScheduler::class.java)
        try {
            PageContentCleanupScheduler(processor, InstanceIdHolder("pages-local"), CoroutineScope(Dispatchers.Unconfined))
                .cleanupPageContent()

            val event = logs.list.single()
            val fields = RequestLogTestSupport.fields(event)
            assertEquals("pages_content_cleanup_batch_failed", event.formattedMessage)
            assertEquals(setOf("event", "error.type", "error.stack_trace"), fields.keys)
            assertEquals(IllegalStateException::class.java.name, fields["error.type"])
            assertFalse(fields.values.toString().contains("private diagnostics"))
        } finally {
            (LoggerFactory.getLogger(PageContentCleanupScheduler::class.java) as Logger).detachAppender(logs)
        }
    }

    private fun claim(
        objectRef: String,
        objectKind: PageContentCleanupObjectKind,
        multipartUploadId: String?,
    ) = ClaimedPageContentObjectCleanup(
        workspaceId = workspaceId,
        objectRef = objectRef,
        objectKind = objectKind,
        multipartUploadId = multipartUploadId,
        claimToken = Uuid.generateV7(),
        leaseExpiresAt = Instant.parse("2026-10-11T00:01:00Z"),
    )

    private fun capture(type: Class<*>): ListAppender<ILoggingEvent> {
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        (LoggerFactory.getLogger(type) as Logger).addAppender(logs)
        return logs
    }
}
