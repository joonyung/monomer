package cc.monomer.pages.application

import cc.monomer.pages.domain.audit.AuditActorContext
import cc.monomer.pages.domain.audit.AuditActorKind as GovernanceAuditActorKind
import cc.monomer.pages.domain.audit.PagesAuditRepository
import cc.monomer.pages.domain.audit.PagesAuditEvent
import cc.monomer.pages.domain.audit.PagesAuditAction
import cc.monomer.pages.domain.common.TransactionRunner
import cc.monomer.pages.domain.Page
import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageActorKind
import cc.monomer.pages.domain.PageCreationResult
import cc.monomer.pages.domain.PageDirectoryMutationRecord
import cc.monomer.pages.domain.PageDirectoryRequestId
import cc.monomer.pages.domain.PageDirectoryRequestRejection
import cc.monomer.pages.domain.PageDirectoryRequestRecord
import cc.monomer.pages.domain.PageDirectoryRequestResult
import cc.monomer.pages.domain.PagesDirectory
import cc.monomer.pages.domain.PagesDirectoryQueryPort
import cc.monomer.pages.domain.PagesDirectoryRepository
import cc.monomer.pages.domain.PagesDirectoryRevision
import cc.monomer.pages.domain.PagesDirectorySnapshot
import cc.monomer.pages.domain.PageNavigationMetadata
import cc.monomer.pages.domain.PageNavigationMetadataResult
import cc.monomer.pages.domain.PagePlacement
import cc.monomer.pages.domain.content.PageContentFormat
import cc.monomer.pages.domain.content.PageContentHash
import cc.monomer.pages.domain.content.PageContentQueryPort
import cc.monomer.pages.domain.content.PageContentRepository
import cc.monomer.pages.domain.content.PageContentUpload
import cc.monomer.pages.domain.content.PageContentUploadCompletionStart
import cc.monomer.pages.domain.content.PageContentUploadRef
import io.mockk.Called
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PagesDirectoryUseCaseImplTest {
    private val workspaceId = Uuid.parse("019e501f-0000-7000-8000-000000000011")
    private val principalId = Uuid.parse("019e501f-0000-7000-8000-000000000013")
    private val actor = PageActor.create(PageActorKind.USER, principalId.toString())
    private val auditActorContext = AuditActorContext(
        Uuid.parse("019e501f-0000-7000-8000-000000000014"),
        principalId,
        principalId.toString(),
    )
    private val now = Instant.parse("2026-07-10T00:00:00Z")

    @Test
    fun `dashboard link replay hashes target and independent links preserve target on removal`() = runTest {
        val repository = FakePagesDirectoryRepository(workspaceId)
        val eventBus = FakePagesDirectoryEventBus()
        val audit = mockk<PagesAuditRepository>(relaxed = true)
        val content = mockk<PageContentRepository>()
        val useCase = newUseCase(repository, eventBus, audit, content)
        val target = Uuid.generateV7()
        val command = PagesDirectoryUseCase.CreateDashboardPageCommand(
            workspaceId, target, "Link one", null, null, null,
            PagesDirectoryRevision(0), PageDirectoryRequestId("dashboard-one"), actor, null, auditActorContext, now,
        )
        val first = useCase.createDashboardPage(command) as PagesDirectoryMutationOutcome.Applied
        val page = first.snapshot.pages.single()
        assertThat(page.dashboardId).isEqualTo(target)
        assertThat(useCase.createDashboardPage(command)).isEqualTo(
            PagesDirectoryMutationOutcome.Replayed(first.change, first.snapshot),
        )
        assertThat(useCase.createDashboardPage(command.copy(dashboardId = Uuid.generateV7())))
            .isInstanceOf(PagesDirectoryMutationOutcome.RequestIdReused::class.java)
        val stale = command.copy(requestId = PageDirectoryRequestId("stale"))
        assertThat(useCase.createDashboardPage(stale)).isInstanceOf(PagesDirectoryMutationOutcome.RevisionConflict::class.java)
        val second = useCase.createDashboardPage(command.copy(
            title = "Link two", baseRevision = PagesDirectoryRevision(1), requestId = PageDirectoryRequestId("dashboard-two"),
        )) as PagesDirectoryMutationOutcome.Applied
        assertThat(second.snapshot.pages.map { it.dashboardId }).containsExactly(target, target)
        val removed = useCase.deletePage(PagesDirectoryUseCase.DeletePageCommand(
            workspaceId, page.id, PagesDirectoryRevision(2), PageDirectoryRequestId("remove-one"), actor, auditActorContext, now,
        )) as PagesDirectoryMutationOutcome.Applied
        assertThat(removed.snapshot.pages.single().dashboardId).isEqualTo(target)
        verify(exactly = 2) { audit.appendWithinTx(match { it.action == PagesAuditAction.DASHBOARD_CREATE }) }
        verify { content wasNot Called }
    }

    @Test
    fun `only committed revision advances publish directory wakeups`() = runTest {
        val repository = FakePagesDirectoryRepository(workspaceId)
        val eventBus = FakePagesDirectoryEventBus()
        val useCase = newUseCase(repository, eventBus)
        val command = PagesDirectoryUseCase.CreateFolderCommand(
            workspaceId,
            "Folder",
            null,
            null,
            null,
            PagesDirectoryRevision(0),
            PageDirectoryRequestId("folder-wakeup"),
            actor,
            null,
            auditActorContext,
            now,
        )

        useCase.createFolder(command)
        useCase.createFolder(command)
        useCase.createFolder(command.copy(requestId = PageDirectoryRequestId("stale-wakeup")))

        assertThat(eventBus.published).containsExactly(
            PagesDirectoryWakeup(workspaceId),
        )
    }

    @Test
    fun `directory projection loads latest document formats with one bulk query`() = runTest {
        val repository = FakePagesDirectoryRepository(workspaceId)
        val folder = folderPage("Folder")
        val document = documentPage("Document")
        repository.directory = PagesDirectory(
            workspaceId = workspaceId,
            pages = listOf(folder, document),
            placements = listOf(
                PagePlacement(folder.id, emptyList()),
                PagePlacement(document.id, emptyList()),
            ),
            revision = PagesDirectoryRevision(1),
            updatedAt = now,
            updatedBy = actor,
        )
        val contentQueryPort = mockk<PageContentQueryPort>()
        coEvery {
            contentQueryPort.findLatestDocumentFormats(workspaceId, setOf(document.id))
        } returns mapOf(document.id to PageContentFormat.NOTEBOOK)

        val projection = newUseCase(
            repository = repository,
            eventBus = FakePagesDirectoryEventBus(),
            auditRepository = mockk(relaxed = true),
            contentRepository = mockk(relaxed = true),
            contentQueryPort = contentQueryPort,
        ).getProjection(workspaceId)

        assertThat(projection.documentFormats).containsExactlyEntriesOf(
            mapOf(document.id to PageContentFormat.NOTEBOOK),
        )
        coVerify(exactly = 1) {
            contentQueryPort.findLatestDocumentFormats(workspaceId, setOf(document.id))
        }
    }

    @Test
    fun `same request replays first snapshot while changed body and stale revision are typed outcomes`() = runTest {
        val repository = FakePagesDirectoryRepository(workspaceId)
        val useCase = newUseCase(repository, FakePagesDirectoryEventBus())
        val original = PagesDirectoryUseCase.CreateFolderCommand(
            workspaceId = workspaceId,
            title = "First",
            description = null,
            parentPageId = null,
            index = null,
            baseRevision = PagesDirectoryRevision(0),
            requestId = PageDirectoryRequestId("create-1"),
            actor = actor,
            agentClientId = null,
            auditActorContext = auditActorContext,
            requestedAt = now,
        )

        val first = useCase.createFolder(original) as PagesDirectoryMutationOutcome.Applied
        assertThat(first.snapshot.revision).isEqualTo(PagesDirectoryRevision(1))

        val folder = useCase.createFolder(
            PagesDirectoryUseCase.CreateFolderCommand(
                workspaceId = workspaceId,
                title = "Folder",
                description = null,
                parentPageId = null,
                index = null,
                baseRevision = PagesDirectoryRevision(1),
                requestId = PageDirectoryRequestId("folder-1"),
                actor = actor,
                agentClientId = null,
                auditActorContext = auditActorContext,
                requestedAt = now.plusSeconds(1),
            ),
        ) as PagesDirectoryMutationOutcome.Applied
        assertThat(folder.snapshot.revision).isEqualTo(PagesDirectoryRevision(2))

        val replay = useCase.createFolder(original) as PagesDirectoryMutationOutcome.Replayed
        assertThat(replay.snapshot.revision).isEqualTo(PagesDirectoryRevision(1))
        assertThat(replay.snapshot.pages).hasSize(1)

        val reused = useCase.createFolder(
            original.copy(title = "Changed", baseRevision = PagesDirectoryRevision(2), requestedAt = now.plusSeconds(2)),
        ) as PagesDirectoryMutationOutcome.RequestIdReused
        assertThat(reused.snapshot.revision).isEqualTo(PagesDirectoryRevision(2))

        val staleCommand = PagesDirectoryUseCase.CreateFolderCommand(
            workspaceId = workspaceId,
            title = "Stale",
            description = null,
            parentPageId = null,
            index = null,
            baseRevision = PagesDirectoryRevision(0),
            requestId = PageDirectoryRequestId("stale-1"),
            actor = actor,
            agentClientId = null,
            auditActorContext = auditActorContext,
            requestedAt = now.plusSeconds(3),
        )
        val stale = useCase.createFolder(staleCommand) as PagesDirectoryMutationOutcome.RevisionConflict
        assertThat(stale.snapshot.revision).isEqualTo(PagesDirectoryRevision(2))

        useCase.createFolder(
            staleCommand.copy(
                title = "Advance",
                baseRevision = PagesDirectoryRevision(2),
                requestId = PageDirectoryRequestId("advance-after-conflict"),
            ),
        ) as PagesDirectoryMutationOutcome.Applied
        val replayedStale = useCase.createFolder(staleCommand) as PagesDirectoryMutationOutcome.RevisionConflict
        assertThat(replayedStale).isEqualTo(stale)
        val reusedConflictId = useCase.createFolder(staleCommand.copy(title = "Different"))
        assertThat(reusedConflictId).isInstanceOf(PagesDirectoryMutationOutcome.RequestIdReused::class.java)
    }

    @Test
    fun `Document creation rejects an unknown upload capability as an idempotent outcome`() = runTest {
        val repository = FakePagesDirectoryRepository(workspaceId)
        val useCase = newUseCase(repository, FakePagesDirectoryEventBus())

        val command = PagesDirectoryUseCase.CreateDocumentPageCommand(
            workspaceId = workspaceId,
            title = "Document",
            description = null,
            parentPageId = null,
            index = null,
            uploadRef = cc.monomer.pages.domain.content.PageContentUploadRef.generate(),
            baseRevision = PagesDirectoryRevision(0),
            requestId = PageDirectoryRequestId("document-1"),
            actor = actor,
            agentClientId = null,
            auditActorContext = auditActorContext,
            requestedAt = now,
        )
        val outcome = useCase.createDocumentPage(command)

        assertThat(outcome).isInstanceOf(PagesDirectoryMutationOutcome.Rejected::class.java)
        assertThat((outcome as PagesDirectoryMutationOutcome.Rejected).reason)
            .isEqualTo(
                PageDirectoryRequestRejection.ContentUploadInvalid(
                    cc.monomer.pages.domain.content.PageContentConsumptionRejection.UPLOAD_NOT_FOUND,
                ),
            )
        useCase.createFolder(
            PagesDirectoryUseCase.CreateFolderCommand(
                workspaceId,
                "Advance",
                null,
                null,
                null,
                PagesDirectoryRevision(0),
                PageDirectoryRequestId("advance-after-document"),
                actor,
                null,
                auditActorContext,
                now.plusSeconds(1),
            ),
        )
        assertThat(useCase.createDocumentPage(command)).isEqualTo(outcome)
        assertThat(useCase.createDocumentPage(command.copy(title = "Different")))
            .isInstanceOf(PagesDirectoryMutationOutcome.RequestIdReused::class.java)
        assertThat(repository.appliedResults).hasSize(1)
    }

    @Test
    fun `Document creation persists one source version without conversion state`() = runTest {
        val repository = FakePagesDirectoryRepository(workspaceId)
        val contentRepository = mockk<PageContentRepository>(relaxed = true)
        val uploadRef = PageContentUploadRef.generate()
        val upload = completedUpload(uploadRef)
        every { contentRepository.findUploadForUpdateWithinTx(uploadRef) } returns upload
        every {
            contentRepository.insertVersionAndConsumeUploadWithinTx(
                upload,
                any(),
                any(),
            )
        } returns Unit
        val useCase = newUseCase(
            repository,
            FakePagesDirectoryEventBus(),
            mockk<PagesAuditRepository>(relaxed = true),
            contentRepository,
        )

        val outcome = useCase.createDocumentPage(
            PagesDirectoryUseCase.CreateDocumentPageCommand(
                workspaceId = workspaceId,
                title = "Document",
                description = null,
                parentPageId = null,
                index = null,
                uploadRef = uploadRef,
                baseRevision = PagesDirectoryRevision(0),
                requestId = PageDirectoryRequestId("document-v2"),
                actor = actor,
                agentClientId = null,
                auditActorContext = auditActorContext,
                requestedAt = now,
            ),
        )

        assertThat(outcome).isInstanceOf(PagesDirectoryMutationOutcome.Applied::class.java)
        verify(exactly = 1) {
            contentRepository.insertVersionAndConsumeUploadWithinTx(upload, any(), any())
        }
    }

    @Test
    fun `every rejection family replays its first typed result after state changes`() = runTest {
        val repository = FakePagesDirectoryRepository(workspaceId)
        val useCase = newUseCase(repository, FakePagesDirectoryEventBus())

        val metadataCommand = PagesDirectoryUseCase.CreateFolderCommand(
            workspaceId,
            "   ",
            null,
            null,
            null,
            PagesDirectoryRevision(0),
            PageDirectoryRequestId("reject-metadata"),
            actor,
            null,
            auditActorContext,
            now,
        )
        val metadataRejected = useCase.createFolder(metadataCommand) as PagesDirectoryMutationOutcome.Rejected
        assertThat(metadataRejected.reason).isInstanceOf(PageDirectoryRequestRejection.MetadataInvalid::class.java)

        val seed = useCase.createFolder(
            metadataCommand.copy(
                title = "Seed",
                requestId = PageDirectoryRequestId("seed"),
                requestedAt = now.plusSeconds(1),
            ),
        ) as PagesDirectoryMutationOutcome.Applied
        assertThat(useCase.createFolder(metadataCommand)).isEqualTo(metadataRejected)
        assertThat(useCase.createFolder(metadataCommand.copy(title = "Different body")))
            .isInstanceOf(PagesDirectoryMutationOutcome.RequestIdReused::class.java)

        val directoryCommand = metadataCommand.copy(
            title = "Missing parent",
            parentPageId = cc.monomer.pages.domain.PageId(Uuid.generateV7()),
            baseRevision = seed.snapshot.revision,
            requestId = PageDirectoryRequestId("reject-directory"),
            requestedAt = now.plusSeconds(2),
        )
        val directoryRejected = useCase.createFolder(directoryCommand) as PagesDirectoryMutationOutcome.Rejected
        assertThat(directoryRejected.reason).isInstanceOf(PageDirectoryRequestRejection.DirectoryInvalid::class.java)

        val patchCommand = PagesDirectoryUseCase.PatchPlacementsCommand(
            workspaceId = workspaceId,
            patch = listOf(buildJsonObject { put("op", "remove"); put("path", "/99") }),
            baseRevision = seed.snapshot.revision,
            requestId = PageDirectoryRequestId("reject-patch"),
            actor = actor,
            auditActorContext = auditActorContext,
            requestedAt = now.plusSeconds(3),
        )
        val patchRejected = useCase.patchPlacements(patchCommand) as PagesDirectoryMutationOutcome.Rejected
        assertThat(patchRejected.reason).isInstanceOf(PageDirectoryRequestRejection.PatchInvalid::class.java)

        useCase.createFolder(
            metadataCommand.copy(
                title = "Advance again",
                baseRevision = seed.snapshot.revision,
                requestId = PageDirectoryRequestId("advance-after-rejections"),
                requestedAt = now.plusSeconds(4),
            ),
        ) as PagesDirectoryMutationOutcome.Applied

        assertThat(useCase.createFolder(directoryCommand)).isEqualTo(directoryRejected)
        assertThat(useCase.patchPlacements(patchCommand)).isEqualTo(patchRejected)
        assertThat(repository.appliedResults).hasSize(2)
    }

    @Test
    fun `patch replay canonicalizes object member order recursively`() = runTest {
        val repository = FakePagesDirectoryRepository(workspaceId)
        val useCase = newUseCase(repository, FakePagesDirectoryEventBus())
        val created = useCase.createFolder(
            PagesDirectoryUseCase.CreateFolderCommand(
                workspaceId,
                "Folder",
                null,
                null,
                null,
                PagesDirectoryRevision(0),
                PageDirectoryRequestId("canonical-seed"),
                actor,
                null,
                auditActorContext,
                now,
            ),
        ) as PagesDirectoryMutationOutcome.Applied
        val pageId = created.snapshot.pages.single().id.toString()
        val original = PagesDirectoryUseCase.PatchPlacementsCommand(
            workspaceId = workspaceId,
            patch = listOf(buildJsonObject {
                put("op", "test")
                put("path", "/0")
                put("value", buildJsonObject {
                    put("pageId", pageId)
                    put("children", JsonArray(emptyList()))
                })
            }),
            baseRevision = PagesDirectoryRevision(1),
            requestId = PageDirectoryRequestId("canonical-patch"),
            actor = actor,
            auditActorContext = auditActorContext,
            requestedAt = now.plusSeconds(1),
        )
        val reordered = original.copy(
            patch = listOf(buildJsonObject {
                put("value", buildJsonObject {
                    put("children", JsonArray(emptyList()))
                    put("pageId", pageId)
                })
                put("path", "/0")
                put("op", "test")
            }),
        )

        val applied = useCase.patchPlacements(original) as PagesDirectoryMutationOutcome.Applied
        val replayed = useCase.patchPlacements(reordered) as PagesDirectoryMutationOutcome.Replayed

        assertThat(replayed.change).isEqualTo(applied.change)
        assertThat(replayed.snapshot).isEqualTo(applied.snapshot)
        assertThat(repository.appliedResults).hasSize(2)
    }

    @Test
    fun `only applied mutations append bounded audit events with verified channel attribution`() = runTest {
        val repository = FakePagesDirectoryRepository(workspaceId)
        val audit = RecordingPagesAuditRepository()
        val useCase = newUseCase(repository, FakePagesDirectoryEventBus(), audit)
        val userCommand = PagesDirectoryUseCase.CreateFolderCommand(
            workspaceId = workspaceId,
            title = "Audited folder",
            description = null,
            parentPageId = null,
            index = null,
            baseRevision = PagesDirectoryRevision(0),
            requestId = PageDirectoryRequestId("audit-user"),
            actor = actor,
            agentClientId = null,
            auditActorContext = auditActorContext,
            requestedAt = now,
        )

        useCase.createFolder(userCommand)
        useCase.createFolder(userCommand)
        useCase.createFolder(
            userCommand.copy(
                title = "Stale folder",
                requestId = PageDirectoryRequestId("audit-stale"),
                baseRevision = PagesDirectoryRevision(0),
            ),
        )

        val agentPrincipalId = Uuid.parse("019e501f-0000-7000-8000-000000000015")
        val agentRunId = Uuid.parse("019e501f-0000-7000-8000-000000000016")
        useCase.createFolder(
            userCommand.copy(
                title = "Agent folder",
                baseRevision = PagesDirectoryRevision(1),
                requestId = PageDirectoryRequestId("audit-agent"),
                actor = PageActor.create(PageActorKind.AGENT, agentPrincipalId.toString()),
                auditActorContext = AuditActorContext(
                    organizationId = auditActorContext.organizationId,
                    principalId = agentPrincipalId,
                    actorRef = agentRunId.toString(),
                ),
            ),
        )

        assertThat(audit.events).hasSize(2)
        val userAudit = audit.events[0]
        assertThat(userAudit.action).isEqualTo(PagesAuditAction.FOLDER_CREATE)
        assertThat(userAudit.resourceId).isEqualTo(repository.appliedResults[0].change.let {
            (it as cc.monomer.pages.domain.PageDirectoryChange.FolderCreated).pageId.value
        })
        assertThat(userAudit.details.pageKind).isEqualTo("FOLDER")
        assertThat(userAudit.actorKind).isEqualTo(GovernanceAuditActorKind.USER)
        assertThat(userAudit.actorRef).isEqualTo(principalId.toString())
        assertThat(audit.events[1].actorKind).isEqualTo(GovernanceAuditActorKind.AGENT)
        assertThat(audit.events[1].actorRef).isEqualTo(agentRunId.toString())
    }

    private fun newUseCase(
        repository: FakePagesDirectoryRepository,
        eventBus: PagesDirectoryEventBus,
    ): PagesDirectoryUseCaseImpl = newUseCase(
        repository,
        eventBus,
        mockk<PagesAuditRepository>(relaxed = true),
    )

    private fun newUseCase(
        repository: FakePagesDirectoryRepository,
        eventBus: PagesDirectoryEventBus,
        auditRepository: PagesAuditRepository,
    ): PagesDirectoryUseCaseImpl = newUseCase(
        repository,
        eventBus,
        auditRepository,
        mockk<PageContentRepository>(relaxed = true),
    )

    private fun newUseCase(
        repository: FakePagesDirectoryRepository,
        eventBus: PagesDirectoryEventBus,
        auditRepository: PagesAuditRepository,
        contentRepository: PageContentRepository,
        contentQueryPort: PageContentQueryPort = mockk(relaxed = true),
    ): PagesDirectoryUseCaseImpl = PagesDirectoryUseCaseImpl(
        repository,
        repository,
        DirectTransactionRunner,
        eventBus,
        auditRepository,
        contentRepository,
        contentQueryPort,
        PagesDashboardReferencePort { _, _ -> true },
    )

    private fun completedUpload(uploadRef: PageContentUploadRef): PageContentUpload {
        val contentHash = PageContentHash("a".repeat(64))
        val initiated = PageContentUpload.initiate(
            uploadRef,
            workspaceId,
            actor,
            PageContentFormat.HTML,
            contentHash,
            123,
            "workspace/$workspaceId/uploads/$uploadRef/source",
            "multipart-$uploadRef",
            now.plusSeconds(900),
            now,
        )
        val claimToken = Uuid.generateV7()
        val completing = (initiated.startCompletion(
            "e".repeat(64),
            now.plusSeconds(1),
            now.plusSeconds(61),
            claimToken,
        ) as PageContentUploadCompletionStart.Started).upload
        return completing.complete(
            "workspace/$workspaceId/source/sha256/$contentHash",
            contentHash,
            123,
            now.plusSeconds(2),
            claimToken,
        )
    }

    private class RecordingPagesAuditRepository : PagesAuditRepository {
        val events = mutableListOf<PagesAuditEvent>()

        override fun appendWithinTx(event: PagesAuditEvent) {
            events += event
        }
    }

    private object DirectTransactionRunner : TransactionRunner {
        override suspend fun <T> run(block: () -> T): T = block()
        override suspend fun <T> readOnly(block: () -> T): T = block()
    }

    private fun folderPage(title: String): Page {
        val metadata = (PageNavigationMetadata.create(title, null) as PageNavigationMetadataResult.Valid).metadata
        return (Page.createFolder(workspaceId, metadata, actor, null, now) as PageCreationResult.Created).page
    }

    private fun documentPage(title: String): Page {
        val metadata = (PageNavigationMetadata.create(title, null) as PageNavigationMetadataResult.Valid).metadata
        return (Page.createDocument(workspaceId, metadata, actor, null, now) as PageCreationResult.Created).page
    }

    private class FakePagesDirectoryRepository(
        private val workspaceId: Uuid,
    ) : PagesDirectoryRepository, PagesDirectoryQueryPort {
        override fun requireAmbientTransaction() = Unit
        override fun saveDirectoryWithinTx(current: PagesDirectory, next: PagesDirectory) { directory = next }
        var directory: PagesDirectory? = null
        val appliedResults = mutableListOf<PageDirectoryRequestResult.Applied>()
        private val requestResults = mutableListOf<RecordedRequestResult>()

        override suspend fun findSnapshot(workspaceId: Uuid): PagesDirectorySnapshot? =
            directory?.let(PagesDirectorySnapshot::from)

        override fun findOrCreateForUpdateWithinTx(workspaceId: Uuid): PagesDirectory =
            directory ?: PagesDirectory.empty(workspaceId).also { directory = it }

        override fun findRequestResultWithinTx(
            workspaceId: Uuid,
            actor: PageActor,
            requestId: PageDirectoryRequestId,
        ): PageDirectoryRequestResult? = requestResults
            .firstOrNull { it.actor == actor && it.requestId == requestId }
            ?.result

        override fun saveMutationWithinTx(
            current: PagesDirectory,
            next: PagesDirectory,
            record: PageDirectoryMutationRecord,
        ): PageDirectoryRequestResult.Applied {
            val applied = PageDirectoryRequestResult.Applied(
                requestHash = record.requestHash,
                change = record.change,
                snapshot = PagesDirectorySnapshot.from(next),
            )
            directory = next
            appliedResults += applied
            requestResults += RecordedRequestResult(
                actor = record.actor,
                requestId = record.requestId,
                result = applied,
            )
            return applied
        }

        override fun saveNonAdvancingResultWithinTx(
            current: PagesDirectory,
            record: PageDirectoryRequestRecord,
            result: PageDirectoryRequestResult.NonAdvancing,
        ): PageDirectoryRequestResult.NonAdvancing {
            check(result.requestHash == record.requestHash)
            check(result.snapshot == PagesDirectorySnapshot.from(current))
            requestResults += RecordedRequestResult(record.actor, record.requestId, result)
            return result
        }

        private data class RecordedRequestResult(
            val actor: PageActor,
            val requestId: PageDirectoryRequestId,
            val result: PageDirectoryRequestResult,
        )
    }

    private class FakePagesDirectoryEventBus : PagesDirectoryEventBus {
        private val wakeups = MutableSharedFlow<PagesDirectoryWakeup>(extraBufferCapacity = 16)
        val published = mutableListOf<PagesDirectoryWakeup>()

        override fun publish(wakeup: PagesDirectoryWakeup) {
            published += wakeup
            wakeups.tryEmit(wakeup)
        }

        override fun subscribe(workspaceId: Uuid): Flow<PagesDirectoryWakeup> = wakeups
    }
}
