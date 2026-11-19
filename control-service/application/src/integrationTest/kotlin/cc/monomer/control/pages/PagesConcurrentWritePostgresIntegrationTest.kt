@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.pages

import cc.monomer.control.EphemeralPostgresAuthoritySupport
import cc.monomer.pages.adapter.outbound.postgres.audit.PostgresPagesAuditRepository
import cc.monomer.pages.adapter.outbound.postgres.pages.PostgresPagesDirectoryRepository
import cc.monomer.pages.adapter.outbound.postgres.pages.content.PostgresPageContentRepository
import cc.monomer.pages.application.PagesDirectoryEventBus
import cc.monomer.pages.application.PagesDirectoryMutationOutcome
import cc.monomer.pages.application.PagesDirectoryUseCase
import cc.monomer.pages.application.PagesDirectoryUseCaseImpl
import cc.monomer.pages.application.PagesDirectoryWakeup
import cc.monomer.pages.application.content.PageContentMutationOutcome
import cc.monomer.pages.application.content.PageContentUseCase
import cc.monomer.pages.application.content.PageContentUseCaseImpl
import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageActorKind
import cc.monomer.pages.domain.PageDirectoryChange
import cc.monomer.pages.domain.PageDirectoryRequestId
import cc.monomer.pages.domain.PageId
import cc.monomer.pages.domain.PagesDirectoryRevision
import cc.monomer.pages.domain.audit.AuditActorContext
import cc.monomer.pages.domain.common.TransactionRunner
import cc.monomer.pages.domain.content.PageContentFormat
import cc.monomer.pages.domain.content.PageContentHash
import cc.monomer.pages.domain.content.PageContentUpload
import cc.monomer.pages.domain.content.PageContentUploadCompletionStart
import cc.monomer.pages.domain.content.PageContentUploadRef
import cc.monomer.pages.domain.content.PageVersionNumber
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

class PagesConcurrentWritePostgresIntegrationTest {
    private val principalId = Uuid.generateV7()
    private val actor = PageActor.create(PageActorKind.USER, principalId.toString())
    private val auditActor = AuditActorContext(Uuid.generateV7(), principalId, "user:$principalId")
    private val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    @BeforeEach
    fun applyPagesSchema() {
        val controlRoot = Path.of(System.getProperty("control.service.root"))
        database.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(Files.readString(controlRoot.resolve("modules/pages/src/main/resources/schema-postgres.sql")))
            }
        }
    }

    @Test
    fun `tree edits waiting on a committed edit replay or conflict instead of failing serialization`() = runBlocking {
        val fixture = Fixture()
        val workspaceId = Uuid.generateV7()
        val seeded = fixture.directory.createFolder(folder(workspaceId, "seed", "Seed", 0))
        assertIs<PagesDirectoryMutationOutcome.Applied>(seeded)

        val first = fixture.eventBus.holdNextPublish()
        val applied = async(Dispatchers.IO) { fixture.directory.createFolder(folder(workspaceId, "first", "First", 1)) }
        first.awaitEntered()
        val replayed = async(Dispatchers.IO) { fixture.directory.createFolder(folder(workspaceId, "first", "First", 1)) }
        val conflicted = async(Dispatchers.IO) { fixture.directory.createFolder(folder(workspaceId, "second", "Second", 1)) }
        awaitLockWaiters(2)
        first.release()

        val appliedOutcome = assertIs<PagesDirectoryMutationOutcome.Applied>(applied.await())
        assertEquals(PagesDirectoryRevision(2), appliedOutcome.snapshot.revision)
        val replayedOutcome = assertIs<PagesDirectoryMutationOutcome.Replayed>(replayed.await())
        assertEquals(appliedOutcome.change, replayedOutcome.change)
        val conflictOutcome = assertIs<PagesDirectoryMutationOutcome.RevisionConflict>(conflicted.await())
        assertEquals(PagesDirectoryRevision(2), conflictOutcome.snapshot.revision)
        assertEquals(appliedOutcome.snapshot, conflictOutcome.snapshot)
    }

    @Test
    fun `tree edit waiting on the directory lock reads the pages its holder committed`() = runBlocking {
        val fixture = Fixture()
        val workspaceId = Uuid.generateV7()
        val seeded = fixture.directory.createFolder(folder(workspaceId, "seed", "Seed", 0))
        assertIs<PagesDirectoryMutationOutcome.Applied>(seeded)
        val upload = fixture.completedUpload(workspaceId, "e")

        val uploadHolder = database.dataSource.connection
        try {
            uploadHolder.autoCommit = false
            uploadHolder.prepareStatement(
                "SELECT upload_ref FROM pages_relocation.page_content_uploads WHERE upload_ref = ?::uuid FOR UPDATE",
            ).use { statement ->
                statement.setString(1, upload.toString())
                statement.executeQuery().use { result -> check(result.next()) }
            }
            val created = async(Dispatchers.IO) {
                fixture.directory.createDocumentPage(
                    PagesDirectoryUseCase.CreateDocumentPageCommand(
                        workspaceId = workspaceId,
                        title = "Report",
                        description = null,
                        parentPageId = null,
                        index = null,
                        uploadRef = upload,
                        baseRevision = PagesDirectoryRevision(1),
                        requestId = PageDirectoryRequestId("create-document"),
                        actor = actor,
                        agentClientId = null,
                        auditActorContext = auditActor,
                        requestedAt = now,
                    ),
                )
            }
            awaitLockWaiters(1)
            val conflicted = async(Dispatchers.IO) { fixture.directory.createFolder(folder(workspaceId, "second", "Second", 1)) }
            awaitLockWaiters(2)
            uploadHolder.rollback()

            val createdOutcome = assertIs<PagesDirectoryMutationOutcome.Applied>(created.await())
            val conflictOutcome = assertIs<PagesDirectoryMutationOutcome.RevisionConflict>(conflicted.await())
            assertEquals(createdOutcome.snapshot, conflictOutcome.snapshot)
        } finally {
            uploadHolder.close()
        }
    }

    @Test
    fun `content replace waiting on a committed replace finalizes the following version`() = runBlocking {
        val fixture = Fixture()
        val workspaceId = Uuid.generateV7()
        val initialUpload = fixture.completedUpload(workspaceId, "a")
        val firstUpload = fixture.completedUpload(workspaceId, "b")
        val secondUpload = fixture.completedUpload(workspaceId, "c")
        val created = fixture.directory.createDocumentPage(
            PagesDirectoryUseCase.CreateDocumentPageCommand(
                workspaceId = workspaceId,
                title = "Report",
                description = null,
                parentPageId = null,
                index = null,
                uploadRef = initialUpload,
                baseRevision = PagesDirectoryRevision(0),
                requestId = PageDirectoryRequestId("create-document"),
                actor = actor,
                agentClientId = null,
                auditActorContext = auditActor,
                requestedAt = now,
            ),
        )
        val pageId = (assertIs<PagesDirectoryMutationOutcome.Applied>(created).change as PageDirectoryChange.DocumentPageCreated).pageId

        val first = fixture.eventBus.holdNextPublish()
        val firstReplace = async(Dispatchers.IO) { fixture.content.replace(replace(workspaceId, pageId, firstUpload, "replace-first")) }
        first.awaitEntered()
        val secondReplace = async(Dispatchers.IO) { fixture.content.replace(replace(workspaceId, pageId, secondUpload, "replace-second")) }
        awaitLockWaiters(1)
        first.release()

        assertEquals(PageVersionNumber(2), assertIs<PageContentMutationOutcome.Applied>(firstReplace.await()).version.versionNumber)
        assertEquals(PageVersionNumber(3), assertIs<PageContentMutationOutcome.Applied>(secondReplace.await()).version.versionNumber)
    }

    private fun folder(workspaceId: Uuid, requestId: String, title: String, baseRevision: Long) =
        PagesDirectoryUseCase.CreateFolderCommand(
            workspaceId = workspaceId,
            title = title,
            description = null,
            parentPageId = null,
            index = null,
            baseRevision = PagesDirectoryRevision(baseRevision),
            requestId = PageDirectoryRequestId(requestId),
            actor = actor,
            agentClientId = null,
            auditActorContext = auditActor,
            requestedAt = now,
        )

    private fun replace(workspaceId: Uuid, pageId: PageId, uploadRef: PageContentUploadRef, requestId: String) =
        PageContentUseCase.ReplaceCommand(
            workspaceId = workspaceId,
            pageId = pageId,
            uploadRef = uploadRef,
            requestId = requestId,
            actor = actor,
            agentClientId = null,
            auditActorContext = auditActor,
            requestedAt = now,
        )

    private fun awaitLockWaiters(expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            val waiting = database.dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        """SELECT count(*) FROM pg_stat_activity
                           WHERE datname = current_database() AND wait_event_type = 'Lock'""",
                    ).use { result ->
                        result.next()
                        result.getInt(1)
                    }
                }
            }
            if (waiting >= expected) return
            check(System.nanoTime() < deadline) { "expected $expected Pages writers to wait on a row lock, saw $waiting" }
            Thread.sleep(10)
        }
    }

    private inner class Fixture {
        private val jdbc = NamedParameterJdbcTemplate(database.dataSource)
        private val productAuthorityTransactionOperations =
            TransactionTemplate(DataSourceTransactionManager(database.dataSource)).apply {
                propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
                isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
                timeout = 15
            }
        private val readOnlyTransactionOperations =
            TransactionTemplate(DataSourceTransactionManager(database.dataSource)).apply {
                isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
                isReadOnly = true
            }
        private val transactions: TransactionRunner = PagesApplicationComposition().pagesTransactionRunner(
            Dispatchers.IO,
            productAuthorityTransactionOperations,
            readOnlyTransactionOperations,
        )
        private val directoryRepository = PostgresPagesDirectoryRepository(jdbc, transactions)
        private val contentRepository = PostgresPageContentRepository(jdbc, transactions)
        private val audit = PostgresPagesAuditRepository(jdbc, jacksonObjectMapper())
        val eventBus = HoldingEventBus()
        val directory = PagesDirectoryUseCaseImpl(
            directoryRepository,
            directoryRepository,
            transactions,
            eventBus,
            audit,
            contentRepository,
            contentRepository,
            cc.monomer.pages.application.PagesDashboardReferencePort { _, _ -> false },
        )
        val content = PageContentUseCaseImpl(contentRepository, contentRepository, transactions, audit, eventBus)

        suspend fun completedUpload(workspaceId: Uuid, hashCharacter: String): PageContentUploadRef {
            val hash = PageContentHash(hashCharacter.repeat(64))
            val initiated = PageContentUpload.initiate(
                uploadRef = PageContentUploadRef.generate(),
                workspaceId = workspaceId,
                actor = actor,
                format = PageContentFormat.MARKDOWN,
                expectedHash = hash,
                expectedSizeBytes = 4,
                temporaryObjectRef = "workspace/$workspaceId/uploads/$hashCharacter",
                multipartUploadId = "multipart-$hashCharacter",
                expiresAt = now.plusSeconds(3_600),
                createdAt = now.minusSeconds(60),
            )
            val claimToken = Uuid.generateV7()
            val completing = assertIs<PageContentUploadCompletionStart.Started>(
                initiated.startCompletion("d".repeat(64), now.minusSeconds(30), now.plusSeconds(30), claimToken),
            ).upload
            val completed = completing.complete(
                sourceObjectRef = "workspace/$workspaceId/source/${initiated.uploadRef}",
                actualHash = hash,
                actualSizeBytes = 4,
                completedAt = now.minusSeconds(20),
                claimToken = claimToken,
            )
            transactions.run {
                contentRepository.insertUploadWithinTx(initiated)
                contentRepository.updateUploadWithinTx(initiated, completing)
                contentRepository.updateUploadWithinTx(completing, completed)
            }
            return completed.uploadRef
        }
    }

    private class HoldingEventBus : PagesDirectoryEventBus {
        @Volatile
        private var held: PublishHold? = null

        fun holdNextPublish(): PublishHold = PublishHold().also { hold -> held = hold }

        override fun publish(wakeup: PagesDirectoryWakeup) {
            val hold = held ?: return
            held = null
            hold.enter()
        }

        override fun subscribe(workspaceId: Uuid) = emptyFlow<PagesDirectoryWakeup>()
    }

    private class PublishHold {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun enter() {
            entered.countDown()
            assertTrue(released.await(10, TimeUnit.SECONDS), "held Pages writer was never released")
        }

        fun awaitEntered() {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "first Pages writer never reached its publish")
        }

        fun release() = released.countDown()
    }

    companion object {
        @JvmField @RegisterExtension val database = EphemeralPostgresAuthoritySupport
    }
}
