package cc.monomer.pages.adapter.outbound.postgres.audit

import cc.monomer.pages.adapter.outbound.postgres.pages.PostgresPagesDirectoryRepository
import cc.monomer.pages.adapter.outbound.postgres.pages.content.PostgresPageContentRepository
import cc.monomer.pages.adapter.outbound.postgres.pages.content.TargetPagesPostgres
import cc.monomer.pages.application.PagesDashboardReferencePort
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
import cc.monomer.pages.domain.PageDirectoryRequestId
import cc.monomer.pages.domain.PageId
import cc.monomer.pages.domain.PagesDirectoryRevision
import cc.monomer.pages.domain.audit.AuditActorContext
import cc.monomer.pages.domain.audit.AuditActorKind
import cc.monomer.pages.domain.audit.PageContentAuditDetails
import cc.monomer.pages.domain.audit.PagesAuditAction
import cc.monomer.pages.domain.audit.PagesAuditDetails
import cc.monomer.pages.domain.audit.PagesAuditEvent
import cc.monomer.pages.domain.audit.PagesAuditRepository
import cc.monomer.pages.domain.content.PageContentFormat
import cc.monomer.pages.domain.content.PageContentHash
import cc.monomer.pages.domain.content.PageContentUpload
import cc.monomer.pages.domain.content.PageContentUploadCompletionStart
import cc.monomer.pages.domain.content.PageContentUploadRef
import cc.monomer.pages.domain.content.PageContentUploadStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.transaction.support.TransactionTemplate

@ExtendWith(TargetPagesPostgres::class)
class PagesAuditPostgresIntegrationTest {
    private val workspaceId = Uuid.generateV7()
    private val principalId = Uuid.generateV7()
    private val actor = PageActor.create(PageActorKind.USER, principalId.toString())
    private val context = AuditActorContext(Uuid.generateV7(), principalId, "user-session")
    private val now = Instant.parse("2026-09-06T00:00:00Z")
    private val mapper = jacksonObjectMapper()
    private val jdbc get() = JdbcTemplate(TargetPagesPostgres.dataSource)
    private val transactionRunner get() = TargetPagesPostgres.transactionRunner()
    private val namedJdbc get() = NamedParameterJdbcTemplate(TargetPagesPostgres.dataSource)
    private val auditRepository get() = PostgresPagesAuditRepository(namedJdbc, mapper)
    private val directoryRepository get() = PostgresPagesDirectoryRepository(namedJdbc, transactionRunner)
    private val contentRepository get() = PostgresPageContentRepository(namedJdbc, transactionRunner)

    @BeforeEach
    fun clearAuthorities() {
        jdbc.execute(
            "TRUNCATE pages_relocation.audit_events, pages_relocation.page_directories, " +
                "pages_relocation.page_content_uploads, pages_relocation.page_content_command_key_fences, " +
                "pages_relocation.page_content_command_results, " +
                "pages_relocation.page_content_object_cleanup CASCADE",
        )
    }

    @Test
    fun `dashboard references roundtrip in directory receipts and audit with duplicate targets`() = runBlocking<Unit> {
        val useCase = directoryUseCase(auditRepository)
        val target = Uuid.generateV7()
        val command = PagesDirectoryUseCase.CreateDashboardPageCommand(
            workspaceId, target, "Analysis", null, null, null, PagesDirectoryRevision(0),
            PageDirectoryRequestId("dashboard-link"), actor, null, context, now,
        )
        val first = useCase.createDashboardPage(command) as PagesDirectoryMutationOutcome.Applied
        assertThat(directoryRepository.findSnapshot(workspaceId)?.pages?.single()?.dashboardId).isEqualTo(target)
        val pageId = first.snapshot.pages.single().id
        assertThat(transactionRunner.run { contentRepository.lockDocumentPageWithinTx(workspaceId, pageId) }).isFalse()
        assertThat(contentRepository.findDocumentVersionSource(workspaceId, pageId, null)).isNull()
        assertThat(contentRepository.listVersions(workspaceId, pageId, null, 20)).isEmpty()
        assertThat(contentRepository.findLatestDocumentFormats(workspaceId, setOf(pageId))).isEmpty()
        assertThat(useCase.createDashboardPage(command)).isEqualTo(
            PagesDirectoryMutationOutcome.Replayed(first.change, first.snapshot),
        )
        assertThat(useCase.createDashboardPage(command.copy(dashboardId = Uuid.generateV7())))
            .isInstanceOf(PagesDirectoryMutationOutcome.RequestIdReused::class.java)
        useCase.createDashboardPage(command.copy(
            title = "Second link", baseRevision = PagesDirectoryRevision(1), requestId = PageDirectoryRequestId("second-link"),
        ))
        assertThat(directoryRepository.findSnapshot(workspaceId)?.pages?.map { it.dashboardId }).containsExactly(target, target)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pages_relocation.audit_events", Long::class.java)).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pages_relocation.page_versions", Long::class.java)).isZero()
        assertThat(jdbc.queryForList("SELECT payload->>'action' FROM pages_relocation.audit_events", String::class.java))
            .containsOnly("pages.dashboard.create")
        assertThatThrownBy {
            jdbc.update("UPDATE pages_relocation.pages SET dashboard_id = NULL WHERE workspace_id = ?", java.util.UUID.fromString(workspaceId.toString()))
        }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
    }

    @Test
    fun `audit fact keeps the event identity and exact payload including null detail fields`() = runBlocking<Unit> {
        val event = PagesAuditEvent(
            Uuid.generateV7(), workspaceId, context.organizationId, principalId, AuditActorKind.AGENT,
            "agent-run", PagesAuditAction.DOCUMENT_CREATE, Uuid.generateV7(), "create-document", now,
            PagesAuditDetails(null, null, PageContentAuditDetails(Uuid.generateV7(), 1, "HTML", "a".repeat(64), Uuid.generateV7()), null, null),
        )
        transactionRunner.run { auditRepository.appendWithinTx(event) }
        val row = jdbc.queryForMap("SELECT * FROM pages_relocation.audit_events")
        assertThat(row.keys).containsExactlyInAnyOrder(
            "event_id", "aggregate_type", "aggregate_id", "event_type", "payload", "created_at",
        )
        assertThat(row["event_id"].toString()).isEqualTo(event.eventId.toString())
        assertThat(row["aggregate_id"].toString()).isEqualTo(event.resourceId.toString())
        assertThat(row["aggregate_type"]).isEqualTo("page")
        assertThat(row["event_type"]).isEqualTo("AUDIT_REQUESTED")
        assertThat(row["created_at"]).isEqualTo(now.toEpochMilli())
        val content = checkNotNull(event.details.content)
        assertThat(mapper.readTree(row["payload"].toString())).isEqualTo(mapper.readTree(
            """{
              "eventId":"${event.eventId}", "workspaceId":"$workspaceId", "organizationId":"${context.organizationId}",
              "actorPrincipalId":"$principalId", "actorKind":"AGENT", "actorRef":"agent-run",
              "action":"pages.document.create", "resourceType":"page", "resourceId":"${event.resourceId}",
              "requestId":"create-document", "occurredAtEpochMs":${now.toEpochMilli()},
              "details":{"pageKind":null,"appliedOperationCount":null,"pageId":"${content.pageId}",
                "versionNo":1,"format":"HTML","contentHash":"${content.contentHash}","parentPageId":"${content.parentPageId}","cause":null,"dashboardId":null}
            }""",
        ))
        assertThat(jdbc.queryForObject("SELECT to_regclass('pages_relocation.audit_outbox_events')::text", String::class.java)).isNull()
        assertThatThrownBy {
            runBlocking { transactionRunner.run { auditRepository.appendWithinTx(event) } }
        }.isInstanceOf(org.springframework.dao.DuplicateKeyException::class.java)
        assertThat(count("audit_events")).isEqualTo(1)
    }

    @Test
    fun `audit append refuses missing and other datasource transactions before writing`() {
        val event = PagesAuditEvent(
            Uuid.generateV7(), workspaceId, context.organizationId, principalId, AuditActorKind.USER,
            context.actorRef, PagesAuditAction.FOLDER_CREATE, Uuid.generateV7(), "folder", now,
            PagesAuditDetails("FOLDER", null, null, null, null),
        )
        assertThatThrownBy { auditRepository.appendWithinTx(event) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("active owner transaction")
        val otherDataSource = DelegatingDataSource(TargetPagesPostgres.dataSource)
        val otherTransaction = TransactionTemplate(DataSourceTransactionManager(otherDataSource))
        assertThatThrownBy { otherTransaction.execute { auditRepository.appendWithinTx(event) } }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("exact DataSource")
        assertThat(count("audit_events")).isZero()
    }

    @Test
    fun `folder replay and request id reuse preserve one mutation and its audit fact`() = runBlocking<Unit> {
        val useCase = directoryUseCase(auditRepository)
        val command = folderCommand()
        val applied = useCase.createFolder(command) as PagesDirectoryMutationOutcome.Applied
        val originalAudit = auditPayloads()
        assertThat(originalAudit).hasSize(1)
        assertThat(mapper.readTree(originalAudit.single())["action"].asText()).isEqualTo("pages.folder.create")
        assertThat(useCase.createFolder(command)).isEqualTo(PagesDirectoryMutationOutcome.Replayed(applied.change, applied.snapshot))
        assertThat(useCase.createFolder(command.copy(title = "different")))
            .isInstanceOf(PagesDirectoryMutationOutcome.RequestIdReused::class.java)
        assertThat(auditPayloads()).isEqualTo(originalAudit)
        assertThat(count("pages")).isEqualTo(1)
        assertThat(count("page_directory_command_results")).isEqualTo(1)
    }

    @Test
    fun `directory mutation and audit append roll back together including command result`() {
        assertThatThrownBy { runBlocking { directoryUseCase(failingAudit()).createFolder(folderCommand()) } }
            .isInstanceOf(AuditAppendFailure::class.java)
        assertThat(count("audit_events")).isZero()
        assertThat(count("pages")).isZero()
        assertThat(count("page_directories")).isZero()
        assertThat(count("page_directory_command_results")).isZero()
    }

    @Test
    fun `document creation and replacement replay retain their original versions and audit facts`() = runBlocking<Unit> {
        val upload = completedUpload()
        val directory = directoryUseCase(auditRepository)
        val command = documentCommand(upload)
        val applied = directory.createDocumentPage(command) as PagesDirectoryMutationOutcome.Applied
        val originalCreateAudit = auditPayloads()
        assertThat(originalCreateAudit).hasSize(1)
        assertThat(directory.createDocumentPage(command)).isEqualTo(PagesDirectoryMutationOutcome.Replayed(applied.change, applied.snapshot))
        assertThat(directory.createDocumentPage(command.copy(title = "different")))
            .isInstanceOf(PagesDirectoryMutationOutcome.RequestIdReused::class.java)
        assertThat(auditPayloads()).isEqualTo(originalCreateAudit)
        val pageId = applied.snapshot.pages.single().id
        val replacementUpload = completedUpload()
        val replacement = replaceCommand(pageId, replacementUpload)
        val content = contentUseCase(auditRepository)
        val replaced = content.replace(replacement) as PageContentMutationOutcome.Applied
        assertThat(replaced.version.versionNumber.value).isEqualTo(2)
        val originalAudits = auditPayloads()
        assertThat(originalAudits).hasSize(2)
        assertThat(content.replace(replacement)).isEqualTo(PageContentMutationOutcome.Replayed(replaced.version))
        assertThat(content.replace(replacement.copy(uploadRef = PageContentUploadRef.generate())))
            .isInstanceOf(PageContentMutationOutcome.RequestIdReused::class.java)
        assertThat(auditPayloads()).isEqualTo(originalAudits)
        assertThat(count("page_versions")).isEqualTo(2)
        assertThat(count("page_content_command_results")).isEqualTo(1)
        val payloadsByAction = originalAudits.map(mapper::readTree).associateBy { it["action"].asText() }
        assertThat(payloadsByAction.keys).containsExactlyInAnyOrder("pages.document.create", "pages.content.version.finalize")
        for ((action, payload) in payloadsByAction) {
            assertThat(payload["resourceId"].asText()).isEqualTo(pageId.toString())
            assertThat(payload["details"]["pageId"].asText()).isEqualTo(pageId.toString())
            assertThat(payload["details"]["versionNo"].asInt()).isEqualTo(if (action == "pages.document.create") 1 else 2)
            assertThat(payload["details"]["format"].asText()).isEqualTo("MARKDOWN")
            assertThat(payload["details"]["contentHash"].asText()).isEqualTo("a".repeat(64))
        }
    }

    @Test
    fun `document creation audit failure restores upload and removes page version and command`() = runBlocking<Unit> {
        val upload = completedUpload()
        assertThatThrownBy { runBlocking { directoryUseCase(failingAudit()).createDocumentPage(documentCommand(upload)) } }
            .isInstanceOf(AuditAppendFailure::class.java)
        assertThat(count("audit_events")).isZero()
        assertThat(count("pages")).isZero()
        assertThat(count("page_versions")).isZero()
        assertThat(count("page_directory_command_results")).isZero()
        assertThat(contentRepository.findUpload(upload.uploadRef)?.status).isEqualTo(PageContentUploadStatus.COMPLETED)
    }

    @Test
    fun `content audit failure restores upload version and command atomically`() = runBlocking<Unit> {
        val created = directoryUseCase(auditRepository).createDocumentPage(documentCommand(completedUpload())) as PagesDirectoryMutationOutcome.Applied
        val originalAudit = auditPayloads()
        val upload = completedUpload()
        assertThatThrownBy {
            runBlocking { contentUseCase(failingAudit()).replace(replaceCommand(created.snapshot.pages.single().id, upload)) }
        }.isInstanceOf(AuditAppendFailure::class.java)
        assertThat(auditPayloads()).isEqualTo(originalAudit)
        assertThat(count("page_versions")).isEqualTo(1)
        assertThat(count("page_content_command_results")).isZero()
        assertThat(count("page_content_command_key_fences")).isZero()
        assertThat(contentRepository.findUpload(upload.uploadRef)?.status).isEqualTo(PageContentUploadStatus.COMPLETED)
    }

    @Test
    fun `dashboard bulk deletion preserves folders advances once and audits each Page in caller transaction`() = runBlocking<Unit> {
        val bus = RecordingEventBus()
        val useCase = directoryUseCase(auditRepository, bus)
        val folder = useCase.createFolder(folderCommand()) as PagesDirectoryMutationOutcome.Applied
        val folderId = folder.snapshot.pages.single().id
        val target = Uuid.generateV7()
        val first = dashboardCommand(target, 1, "first").copy(parentPageId = folderId)
        useCase.createDashboardPage(first)
        useCase.createDashboardPage(dashboardCommand(target, 2, "second"))
        useCase.createDashboardPage(dashboardCommand(Uuid.generateV7(), 3, "unrelated"))
        useCase.patchPlacements(PagesDirectoryUseCase.PatchPlacementsCommand(
            workspaceId, listOf(Json.parseToJsonElement("""{"op":"remove","path":"/1"}""") as JsonObject),
            PagesDirectoryRevision(4), PageDirectoryRequestId("unplace-second-link"), actor, context, now,
        ))
        val original = requireNotNull(directoryRepository.findSnapshot(workspaceId))
        val receipts = count("page_directory_command_results")
        bus.published.clear()
        val deleteActor = PageActor.create(PageActorKind.AGENT, "dashboard-delete-agent")
        val deleteContext = context.copy(actorRef = "dashboard-delete-agent")
        val command = PagesDirectoryUseCase.DeleteDashboardPagesCommand(workspaceId, target, deleteActor, deleteContext, now.plusSeconds(10))
        assertThatThrownBy { useCase.deleteDashboardPagesInCallerTransaction(command) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("active owner transaction")
        transactionRunner.run { useCase.deleteDashboardPagesInCallerTransaction(command) }
        val after = requireNotNull(directoryRepository.findSnapshot(workspaceId))
        assertThat(after.revision.value).isEqualTo(original.revision.value + 1)
        assertThat(after.pages).hasSize(2)
        assertThat(after.pages.map { it.id }).contains(folderId)
        assertThat(after.pages.none { it.dashboardId == target }).isTrue()
        assertThat(after.placements.single { it.pageId == folderId }.children).isEmpty()
        assertThat(count("page_directory_command_results")).isEqualTo(receipts)
        assertThat(bus.published).containsExactly(PagesDirectoryWakeup(workspaceId))
        val deletes = jdbc.queryForList("SELECT payload::text FROM pages_relocation.audit_events WHERE payload->>'action' = 'pages.page.delete'", String::class.java).map(mapper::readTree)
        assertThat(deletes).hasSize(2)
        deletes.forEach { audit ->
            assertThat(audit["actorKind"].asText()).isEqualTo("AGENT")
            assertThat(audit["actorPrincipalId"].asText()).isEqualTo(principalId.toString())
            assertThat(audit["actorRef"].asText()).isEqualTo("dashboard-delete-agent")
            assertThat(audit["details"]["cause"].asText()).isEqualTo("DASHBOARD_DELETED")
            assertThat(audit["details"]["dashboardId"].asText()).isEqualTo(target.toString())
        }
        transactionRunner.run { useCase.deleteDashboardPagesInCallerTransaction(command) }
        assertThat(directoryRepository.findSnapshot(workspaceId)).isEqualTo(after)
        assertThat(bus.published).hasSize(1)
    }

    @Test
    fun `dashboard deletion rolls back all Pages revision and audit on either owner failure`() = runBlocking<Unit> {
        val useCase = directoryUseCase(auditRepository)
        val target = Uuid.generateV7()
        useCase.createDashboardPage(dashboardCommand(target, 0, "first"))
        useCase.createDashboardPage(dashboardCommand(target, 1, "second"))
        val original = directoryRepository.findSnapshot(workspaceId)
        val originalAudit = auditPayloads()
        val command = PagesDirectoryUseCase.DeleteDashboardPagesCommand(workspaceId, target, actor, context, now.plusSeconds(10))
        assertThatThrownBy {
            runBlocking { transactionRunner.run { directoryUseCase(failingAudit()).deleteDashboardPagesInCallerTransaction(command) } }
        }.isInstanceOf(AuditAppendFailure::class.java)
        assertThat(directoryRepository.findSnapshot(workspaceId)).isEqualTo(original)
        assertThat(auditPayloads()).isEqualTo(originalAudit)
        assertThatThrownBy {
            runBlocking { transactionRunner.run {
                useCase.deleteDashboardPagesInCallerTransaction(command)
                throw AuditAppendFailure()
            } }
        }.isInstanceOf(AuditAppendFailure::class.java)
        assertThat(directoryRepository.findSnapshot(workspaceId)).isEqualTo(original)
        assertThat(auditPayloads()).isEqualTo(originalAudit)
    }

    @Test
    fun `Dashboard reference is checked within creation transaction before directory locking and receipt replay`() = runBlocking<Unit> {
        var checked = false
        val references = PagesDashboardReferencePort { _, _ ->
            checked = true
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
            assertThat(count("page_directories")).isZero()
            false
        }
        val useCase = PagesDirectoryUseCaseImpl(directoryRepository, directoryRepository, transactionRunner, SilentEventBus,
            auditRepository, contentRepository, contentRepository, references)
        assertThat(useCase.createDashboardPage(dashboardCommand(Uuid.generateV7(), 0, "missing")))
            .isEqualTo(PagesDirectoryMutationOutcome.NotFound)
        assertThat(checked).isTrue()
        assertThat(count("page_directories")).isZero()
        assertThat(count("pages")).isZero()
        assertThat(count("page_directory_command_results")).isZero()
    }

    private fun dashboardCommand(target: Uuid, revision: Long, requestId: String) = PagesDirectoryUseCase.CreateDashboardPageCommand(
        workspaceId, target, requestId, null, null, null, PagesDirectoryRevision(revision), PageDirectoryRequestId(requestId), actor, null, context, now,
    )

    private class RecordingEventBus : PagesDirectoryEventBus {
        val published = mutableListOf<PagesDirectoryWakeup>()
        override fun publish(wakeup: PagesDirectoryWakeup) { published += wakeup }
        override fun subscribe(workspaceId: Uuid): Flow<PagesDirectoryWakeup> = emptyFlow()
    }

    private fun directoryUseCase(audit: PagesAuditRepository, bus: PagesDirectoryEventBus = SilentEventBus) = PagesDirectoryUseCaseImpl(
        directoryRepository, directoryRepository, transactionRunner, bus, audit, contentRepository, contentRepository, PagesDashboardReferencePort { _, _ -> true },
    )

    private fun contentUseCase(audit: PagesAuditRepository) = PageContentUseCaseImpl(
        contentRepository, contentRepository, transactionRunner, audit, SilentEventBus,
    )

    private fun folderCommand() = PagesDirectoryUseCase.CreateFolderCommand(
        workspaceId, "folder", null, null, null, PagesDirectoryRevision(0), PageDirectoryRequestId("folder-create"), actor, null, context, now,
    )

    private fun documentCommand(upload: PageContentUpload) = PagesDirectoryUseCase.CreateDocumentPageCommand(
        workspaceId, "document", null, null, null, upload.uploadRef, PagesDirectoryRevision(0),
        PageDirectoryRequestId("document-create"), actor, null, context, now,
    )

    private fun replaceCommand(pageId: PageId, upload: PageContentUpload) = PageContentUseCase.ReplaceCommand(
        workspaceId, pageId, upload.uploadRef, "content-replace", actor, null, context, now,
    )

    private suspend fun completedUpload(): PageContentUpload {
        val hash = PageContentHash("a".repeat(64))
        val uploadRef = PageContentUploadRef.generate()
        val initiated = PageContentUpload.initiate(
            uploadRef, workspaceId, actor, PageContentFormat.MARKDOWN, hash, 8,
            "workspace/$workspaceId/uploads/$uploadRef/source", "multipart-$uploadRef", now.plusSeconds(900), now,
        )
        val token = Uuid.generateV7()
        val completing = (initiated.startCompletion("b".repeat(64), now, now.plusSeconds(60), token) as PageContentUploadCompletionStart.Started).upload
        val completed = completing.complete("workspace/$workspaceId/source/${initiated.uploadRef}", hash, 8, now, token)
        transactionRunner.run {
            contentRepository.insertUploadWithinTx(initiated)
            contentRepository.updateUploadWithinTx(initiated, completing)
            contentRepository.updateUploadWithinTx(completing, completed)
        }
        return completed
    }

    private fun failingAudit(): PagesAuditRepository = object : PagesAuditRepository {
        override fun appendWithinTx(event: PagesAuditEvent) {
            auditRepository.appendWithinTx(event)
            throw AuditAppendFailure()
        }
    }

    private fun count(table: String): Int = checkNotNull(jdbc.queryForObject("SELECT count(*) FROM pages_relocation.$table", Int::class.java))
    private fun auditPayloads(): List<String> = jdbc.queryForList("SELECT payload::text FROM pages_relocation.audit_events ORDER BY event_id", String::class.java).map(::checkNotNull)
    private class AuditAppendFailure : RuntimeException()
    private object SilentEventBus : PagesDirectoryEventBus {
        override fun publish(wakeup: PagesDirectoryWakeup) = Unit
        override fun subscribe(workspaceId: Uuid): Flow<PagesDirectoryWakeup> = emptyFlow()
    }
}
