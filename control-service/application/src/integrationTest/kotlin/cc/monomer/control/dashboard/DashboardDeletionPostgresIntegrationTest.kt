@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.connector.api.*
import cc.monomer.control.ControlProductPostgresProperties
import cc.monomer.control.ControlProductTransactionProperties
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.ControlExecutionLimitsProperties
import cc.monomer.control.EphemeralPostgresAuthoritySupport
import cc.monomer.control.ExecutionRunRecoveryLifecycle
import cc.monomer.control.jdbc.JdbcVirtualThreadDispatcher
import cc.monomer.control.pagereader.PublicPagesAccess
import cc.monomer.control.search.SemanticDashboardSearchProjection
import cc.monomer.control.testControlRuntimeProperties
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardCommandReceiptRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardCurrentExistencePort
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardDraftRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardLifecycleRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardScheduleRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardSessionRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresQueryRunRepository
import cc.monomer.dashboard.api.*
import cc.monomer.dashboard.application.*
import cc.monomer.dashboard.domain.*
import cc.monomer.execution.adapter.inbound.grpc.ExecutableRequestAssignmentCodec
import cc.monomer.execution.adapter.inbound.grpc.ProtoRawSqlRunSpecFactory
import cc.monomer.execution.adapter.outbound.postgres.*
import cc.monomer.execution.api.*
import cc.monomer.execution.application.ExecutionRunAdmissionService
import cc.monomer.execution.application.ExecutionRunLimits
import cc.monomer.execution.application.ExecutionRunService
import cc.monomer.execution.application.ExecutionRunSettlementCoordinator
import cc.monomer.identity.api.*
import cc.monomer.identity.application.IdentityAdmissionService
import cc.monomer.identity.application.agent.AgentOAuthTokenHasher
import cc.monomer.identity.domain.model.DeveloperAccessPolicy
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.identity.outbound.postgres.PostgresCanonicalIdentityAdmissionSnapshot
import cc.monomer.pages.adapter.outbound.postgres.audit.PostgresPagesAuditRepository
import cc.monomer.pages.adapter.outbound.postgres.pages.PostgresPagesDirectoryRepository
import cc.monomer.pages.adapter.outbound.postgres.pages.content.PostgresPageContentRepository
import cc.monomer.pages.application.*
import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageActorKind
import cc.monomer.pages.domain.PageDirectoryRequestId
import cc.monomer.pages.domain.PagesDirectoryRevision
import cc.monomer.pages.domain.audit.AuditActorContext
import cc.monomer.pages.domain.audit.PagesAuditEvent
import cc.monomer.pages.domain.audit.PagesAuditRepository
import cc.monomer.search.adapter.outbound.postgres.PostgresSearchIndex
import cc.monomer.semantic.api.SemanticRenderResult
import cc.monomer.semantic.api.SemanticResultColumnKey
import cc.monomer.semantic.application.SemanticQueryUseCase
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

class DashboardDeletionPostgresIntegrationTest {
    @Test
    fun `delete removes all owner children Pages and search while preserving recorded definitions and receipts`() = runBlocking {
        val fixture = fixture()
        val draft = fixture.startDraft()
        val session = fixture.openSession()
        fixture.page("First")
        fixture.page("Second")
        fixture.makeScheduleDue()
        assertEquals(1, fixture.schedule.tick().processed)
        val successRun = session.queryRuns.single()
        val executionId = ExecutionRunId(successRun.id.value)
        val resultKey = requireNotNull(fixture.execution.get(executionId)).runtimeBindings.resultSink.objectKey!!
        assertIs<ExecutionRunUseCase.TerminalOutcome.Accepted>(fixture.execution.recordPhysicalOutcome(
            ExecutionRunUseCase.RecordPhysicalOutcomeCommand(executionId,
                PhysicalOutcome("$executionId:terminal", PhysicalOutcomeKind.SUCCEEDED, null, null,
                    ArrowResultManifest(resultKey, 10u, "etag", 1u, 1, "application/vnd.apache.arrow.file")), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS))))
        assertEquals(QueryRunState.RUNNING, fixture.queryRuns.find(successRun.id)!!.state)
        val receiptCount = fixture.count("dashboard.command_receipts")
        val executionIds = fixture.queryExecutionIds()
        assertEquals(3, executionIds.size)
        assertIs<DashboardOwnerOutcome.Success<Unit>>(fixture.lifecycle.delete(fixture.deleteCommand()))
        listOf("dashboards", "drafts", "sessions", "query_runs", "schedule_cursors", "schedule_occurrences").forEach {
            assertEquals(0, fixture.count("dashboard.$it"), it)
        }
        assertEquals(0, fixture.count("pages_relocation.pages"))
        assertEquals(0, fixture.count("search.documents"))
        assertEquals(2, fixture.count("dashboard.definition_changes"))
        assertEquals(receiptCount, fixture.count("dashboard.command_receipts"))
        val remainingExecutions = executionIds.map { assertNotNull(fixture.execution.get(it)) }
        assertEquals(1, remainingExecutions.count { it.state == ExecutionRunState.SUCCEEDED })
        assertEquals(2, remainingExecutions.count { it.state == ExecutionRunState.CANCELLED })
        assertTrue(remainingExecutions.all { it.ownerDiscardedAt != null && it.ownerSettledAt != null })
        fixture.transaction.inTransaction {
            assertTrue(fixture.executionStore.findOwnerSettlementCandidates(setOf(ExecutionOwnerType.DASHBOARD), 100).none { it.runId in executionIds })
            assertEquals(executionIds.toSet(), fixture.executionStore.findArtifactCleanupCandidates(fixture.now.plusSeconds(1000), 100).filter { it.runId in executionIds }.map { it.runId }.toSet())
        }
        assertEquals(2, fixture.jdbc.queryForObject("SELECT count(*) FROM pages_relocation.audit_events WHERE payload->>'workspaceId' = ? AND payload->>'action' = 'pages.page.delete'", Int::class.java, fixture.workspaceId.toString()))
        assertEquals(DashboardOwnerOutcome.NotFound, fixture.lifecycle.delete(fixture.deleteCommand()))
        assertEquals("IDEMPOTENCY_CONFLICT", assertIs<DraftResult.Rejected>(fixture.draftService.start(fixture.startCommand)).code)
        assertEquals("IDEMPOTENCY_CONFLICT", assertIs<SessionResult.Rejected>(fixture.sessionService.open(fixture.openCommand)).code)
        assertEquals(DashboardResult.IdempotencyConflict, fixture.dashboardService.create(fixture.createCommand))
        assertEquals(DashboardOwnerOutcome.NotFound, fixture.lifecycle.delete(fixture.deleteCommand().copy(workspaceId = WorkspaceId.generate())))
        assertEquals(draft.draft.dashboardId, fixture.dashboard.id)
    }

    @Test
    fun `delete succeeds when actual admission backpressure left a failed Draft query without an execution`() = runBlocking<Unit> {
        val fixture = fixture()
        val admitted = fixture.openSession().queryRuns.single()
        val draft = assertIs<DraftResult.Found>(fixture.newDraftService(fixture.admission(1)).start(fixture.startCommand))
        val failed = draft.queryRuns.single()
        assertEquals(QueryRunState.FAILED, failed.state)
        assertEquals("EXECUTION_BACKPRESSURED", failed.failureCode)
        assertEquals(failed, fixture.queryRuns.find(failed.id))
        assertNull(fixture.execution.get(ExecutionRunId(failed.id.value)))
        fixture.page("Backpressured Draft")
        val receiptCount = fixture.count("dashboard.command_receipts")
        assertIs<DashboardOwnerOutcome.Success<Unit>>(fixture.lifecycle.delete(fixture.deleteCommand()))
        listOf("dashboard.dashboards", "dashboard.drafts", "dashboard.sessions", "dashboard.query_runs",
            "pages_relocation.pages", "search.documents").forEach { assertEquals(0, fixture.count(it), it) }
        assertEquals(receiptCount, fixture.count("dashboard.command_receipts"))
        assertEquals(2, fixture.count("dashboard.definition_changes"))
        assertNull(fixture.execution.get(ExecutionRunId(failed.id.value)))
        assertNotNull(assertNotNull(fixture.execution.get(ExecutionRunId(admitted.id.value))).ownerDiscardedAt)
    }

    @Test
    fun `delete succeeds for rejected and existing admission Draft failures without an execution`() = runBlocking<Unit> {
        for ((outcome, failureCode) in listOf(
            DashboardExecutionAdmissionPort.AdmissionOutcome.Rejected("RAW_SQL_VALIDATION_REJECTED") to "RAW_SQL_VALIDATION_REJECTED",
            DashboardExecutionAdmissionPort.AdmissionOutcome.Existing to "EXECUTION_ADMISSION_CONFLICT",
        )) {
            val fixture = fixture()
            val admission = object : DashboardExecutionAdmissionPort by fixture.admission {
                override fun admit(command: DashboardExecutionAdmissionPort.AdmissionCommand) = outcome
            }
            val draft = assertIs<DraftResult.Found>(fixture.newDraftService(admission).start(fixture.startCommand))
            val failed = draft.queryRuns.single()
            assertEquals(QueryRunState.FAILED, failed.state)
            assertEquals(failureCode, failed.failureCode)
            assertEquals(failed, fixture.queryRuns.find(failed.id))
            assertNull(fixture.execution.get(ExecutionRunId(failed.id.value)))
            fixture.page("Rejected Draft")
            val receiptCount = fixture.count("dashboard.command_receipts")
            assertIs<DashboardOwnerOutcome.Success<Unit>>(fixture.lifecycle.delete(fixture.deleteCommand()))
            listOf("dashboard.dashboards", "dashboard.drafts", "dashboard.query_runs", "pages_relocation.pages",
                "search.documents").forEach { assertEquals(0, fixture.count(it), it) }
            assertEquals(receiptCount, fixture.count("dashboard.command_receipts"))
            assertEquals(2, fixture.count("dashboard.definition_changes"))
            assertNull(fixture.execution.get(ExecutionRunId(failed.id.value)))
        }
    }

    @Test
    fun `foreign execution owner or Workspace still rejects deletion and preserves all owner state`() = runBlocking<Unit> {
        for (mismatch in listOf("owner_id", "workspace_id")) {
            val fixture = fixture()
            val run = fixture.startDraft().queryRuns.single()
            val executionId = ExecutionRunId(run.id.value)
            fixture.recordSuccess(run)
            assertIs<ExecutionRunSettlementUseCase.SettlementOutcome.Completed>(fixture.newSettlement().settle(executionId, Instant.now()))
            assertEquals(QueryRunState.SUCCEEDED, assertNotNull(fixture.queryRuns.find(run.id)).state)
            fixture.page("Foreign execution")
            fixture.jdbc.update("UPDATE execution_run.runs SET $mismatch = ? WHERE run_id = ?",
                Uuid.generateV7().toJavaUuid(), run.id.value.toJavaUuid())
            val foreign = assertNotNull(fixture.execution.get(executionId))
            val before = listOf("dashboard.dashboards", "dashboard.drafts", "dashboard.query_runs", "dashboard.command_receipts",
                "dashboard.definition_changes", "pages_relocation.pages", "pages_relocation.audit_events", "search.documents").associateWith(fixture::count)
            repeat(2) {
                val result = assertIs<DashboardOwnerOutcome.Rejected>(fixture.lifecycle.delete(fixture.deleteCommand()))
                assertEquals("DASHBOARD_DELETE_RETRY_REQUIRED", result.code)
                assertEquals(before, before.keys.associateWith(fixture::count))
                assertEquals(foreign, fixture.execution.get(executionId))
            }
        }
    }

    @Test
    fun `Page audit failure and Dashboard history failure both roll the entire deletion transaction back`() = runBlocking {
        for (failureOwner in listOf("pages", "dashboard")) {
            val fixture = fixture()
            fixture.startDraft()
            fixture.openSession()
            fixture.page("Linked")
            val runIds = fixture.queryExecutionIds()
            val before = listOf("dashboard.dashboards", "dashboard.drafts", "dashboard.sessions", "dashboard.query_runs", "dashboard.definition_changes", "search.documents", "pages_relocation.pages", "pages_relocation.audit_events").associateWith(fixture::count)
            val lifecycle = if (failureOwner == "pages") {
                fixture.failPagesAudit = true
                fixture.lifecycle
            } else fixture.newLifecycle(object : DashboardLifecycleRepository by fixture.lifecycleRepository {
                override fun appendDefinitionChange(change: DashboardOwnerDefinitionChange) {
                    fixture.lifecycleRepository.appendDefinitionChange(change)
                    error("definition history unavailable")
                }
            })
            assertFailsWith<IllegalStateException> { lifecycle.delete(fixture.deleteCommand()) }
            assertEquals(before, before.keys.associateWith(fixture::count))
            runIds.forEach { runId ->
                val run = assertNotNull(fixture.execution.get(runId))
                assertEquals(ExecutionRunState.RUNNING, run.state)
                assertNull(run.cancelIntent)
                assertNull(run.ownerDiscardedAt)
                assertNull(run.ownerSettledAt)
            }
        }
    }

    @Test
    fun `restore keeps original ID starts schedules at restore time and never restores Pages or results`() = runBlocking {
        val fixture = fixture()
        fixture.startDraft()
        fixture.openSession()
        fixture.page("Linked")
        fixture.makeScheduleDue()
        fixture.lifecycle.delete(fixture.deleteCommand())
        val page = assertIs<DashboardOwnerOutcome.Success<DashboardOwnerHistoryPage>>(fixture.lifecycle.listHistory(ListDashboardHistoryCommand(fixture.workspaceId, fixture.dashboard.id, null, 20))).value
        assertTrue(page.deleted)
        assertEquals(listOf(true, false), page.changes.map { it.restorable })
        val restoredAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        val restore = RestoreDashboardCommand(fixture.workspaceId, fixture.dashboard.id, page.changes.first().changeId,
            DashboardOwnerCommandMetadata(fixture.actorId.toString(), PresentedCredential("fixture-token"), null, "restore", restoredAt, CredentialSurface.WEB, null))
        val restored = assertIs<DashboardOwnerOutcome.Success<Dashboard>>(fixture.lifecycle.restore(restore)).value
        assertEquals(fixture.dashboard.id, restored.id)
        assertEquals(fixture.dashboard.definition.document, restored.definition.document)
        assertEquals(restoredAt, restored.createdAt)
        assertEquals(0, fixture.count("pages_relocation.pages"))
        assertEquals(0, fixture.count("dashboard.sessions"))
        assertEquals(0, fixture.count("dashboard.query_runs"))
        assertEquals(0, fixture.count("dashboard.schedule_occurrences"))
        assertEquals(1, fixture.count("search.documents"))
        val cursor = fixture.schedules.listSchedules(fixture.workspaceId, fixture.dashboard.id).single()
        assertTrue(cursor.nextScheduledAt > restoredAt)
        assertEquals(fixture.dashboard.definition.schedules.getValue("hourly").nextAfter(restoredAt), cursor.nextScheduledAt)
        assertEquals(restored, assertIs<DashboardOwnerOutcome.Success<Dashboard>>(fixture.lifecycle.restore(restore)).value)
        val live = assertIs<DashboardOwnerOutcome.Rejected>(fixture.lifecycle.restore(restore.copy(metadata = restore.metadata.copy(idempotencyKey = "restore-live"))))
        assertEquals("DASHBOARD_NOT_RESTORABLE", live.code)
        assertEquals("This dashboard isn't deleted, so there's nothing to restore. Open it to edit it instead.", live.message)
        assertEquals(3, fixture.count("dashboard.definition_changes"))
        assertFalse(assertIs<DashboardOwnerOutcome.Success<DashboardOwnerHistoryPage>>(fixture.lifecycle.listHistory(ListDashboardHistoryCommand(fixture.workspaceId, fixture.dashboard.id, null, 20))).value.changes.any { it.restorable })
    }

    @Test
    fun `older and equal timestamp DELETE records follow committed Apply and remain restorable`() = runBlocking {
        for (deleteTimestamp in listOf("older", "equal")) {
            val fixture = fixture()
            val draft = fixture.startDraft().draft
            val appliedAt = fixture.now.plusSeconds(1)
            val pendingDelete = fixture.deleteCommand().let { command -> command.copy(metadata = command.metadata.copy(
                requestedAt = if (deleteTimestamp == "older") fixture.now else appliedAt)) }
            assertIs<DraftApplyResult.Applied>(fixture.draftService.apply(DashboardDraftUseCase.ApplyCommand(
                fixture.workspaceId, fixture.dashboard.id, PresentedCredential("fixture-token"), null, draft.authoringRevision,
                fixture.actorId.toString(), "apply", appliedAt, CredentialSurface.WEB, null)))
            assertIs<DashboardOwnerOutcome.Success<Unit>>(fixture.lifecycle.delete(pendingDelete))
            val history = assertIs<DashboardOwnerOutcome.Success<DashboardOwnerHistoryPage>>(fixture.lifecycle.listHistory(
                ListDashboardHistoryCommand(fixture.workspaceId, fixture.dashboard.id, null, 20))).value
            assertTrue(history.deleted)
            assertEquals(listOf(DashboardDefinitionChangeAction.DELETED, DashboardDefinitionChangeAction.APPLIED,
                DashboardDefinitionChangeAction.CREATED), history.changes.map { it.action })
            val deleted = history.changes.first()
            assertTrue(deleted.restorable)
            assertEquals(appliedAt, history.changes[1].occurredAt)
            assertEquals(appliedAt.plusNanos(1_000), deleted.occurredAt)
            assertEquals(fixture.actorId.toString(), deleted.actorId)
            assertEquals(CredentialSurface.WEB, deleted.credentialSurface)
            val restored = assertIs<DashboardOwnerOutcome.Success<Dashboard>>(fixture.lifecycle.restore(RestoreDashboardCommand(
                fixture.workspaceId, fixture.dashboard.id, deleted.changeId,
                DashboardOwnerCommandMetadata(fixture.actorId.toString(), PresentedCredential("fixture-token"), null,
                    "restore", appliedAt, CredentialSurface.WEB, null)))).value
            assertEquals(fixture.dashboard.id, restored.id)
            val restoredHistory = assertIs<DashboardOwnerOutcome.Success<DashboardOwnerHistoryPage>>(fixture.lifecycle.listHistory(
                ListDashboardHistoryCommand(fixture.workspaceId, fixture.dashboard.id, null, 20))).value
            assertFalse(restoredHistory.deleted)
            assertEquals(DashboardDefinitionChangeAction.CREATED, restoredHistory.changes.first().action)
            assertEquals(deleted.occurredAt.plusNanos(1_000), restoredHistory.changes.first().occurredAt)
            assertFalse(restoredHistory.changes.any { it.restorable })
        }
    }

    @Test
    fun `Draft start and Session open prepared before deletion return not found after admission`() = runBlocking {
        for (surface in listOf("draft", "session")) {
            val fixture = fixture()
            val gate = Gate()
            fixture.planner.beforeResolve = gate::pause
            val pending = async(Dispatchers.IO) {
                if (surface == "draft") fixture.draftService.start(fixture.startCommand) else fixture.sessionService.open(fixture.openCommand)
            }
            gate.awaitEntry()
            assertIs<DashboardOwnerOutcome.Success<Unit>>(fixture.lifecycle.delete(fixture.deleteCommand()))
            gate.release()
            val result = pending.await()
            if (surface == "draft") assertEquals(DraftResult.NotFound, result) else assertEquals(SessionResult.NotFound, result)
            assertEquals(0, fixture.count("dashboard.drafts"))
            assertEquals(0, fixture.count("dashboard.sessions"))
            assertEquals(0, fixture.count("dashboard.query_runs"))
        }
    }

    @Test
    fun `Page creation and Apply wait for owner deletion and finish without orphan or database error`() = runBlocking {
        for (surface in listOf("page", "apply")) {
            val fixture = fixture()
            val draft = fixture.startDraft()
            val gate = Gate()
            val deleteCleanup = object : DashboardDependencyCleanupPort by fixture.cleanup {
                override fun discardRunsInCallerTransaction(workspaceId: WorkspaceId, runs: List<DashboardDeletionRun>, reason: String, at: Instant): DashboardRunDiscardOutcome {
                    gate.pause()
                    return fixture.cleanup.discardRunsInCallerTransaction(workspaceId, runs, reason, at)
                }
            }
            val deletion = async(Dispatchers.IO) { fixture.newLifecycle(cleanup = deleteCleanup).delete(fixture.deleteCommand()) }
            gate.awaitEntry()
            val attempted = CountDownLatch(1)
            val pending = async(Dispatchers.IO) {
                if (surface == "page") {
                    fixture.beforePageExistence = { attempted.countDown() }
                    fixture.pages.createDashboardPage(fixture.pageCommand("Concurrent"))
                } else {
                    val admission = object : DashboardExecutionAdmissionPort by fixture.admission {
                        override fun lockWorkspace(workspaceId: WorkspaceId) { attempted.countDown(); fixture.admission.lockWorkspace(workspaceId) }
                    }
                    fixture.newDraftService(admission).apply(DashboardDraftUseCase.ApplyCommand(fixture.workspaceId, fixture.dashboard.id,
                        PresentedCredential("fixture-token"), null, draft.draft.authoringRevision, fixture.actorId.toString(), "apply", fixture.now.plusSeconds(1), CredentialSurface.WEB, null))
                }
            }
            withContext(Dispatchers.IO) { check(attempted.await(3, TimeUnit.SECONDS)) }
            gate.release()
            assertIs<DashboardOwnerOutcome.Success<Unit>>(deletion.await())
            val result = pending.await()
            if (surface == "page") assertEquals(PagesDirectoryMutationOutcome.NotFound, result) else assertEquals(DraftApplyResult.NotFound, result)
            assertEquals(0, fixture.count("pages_relocation.pages"))
            assertEquals(0, fixture.count("dashboard.dashboards"))
        }
    }

    @Test
    fun `schedule decision and public Session resolve prepared before deletion do not resurrect children`() = runBlocking {
        for (surface in listOf("schedule", "public")) {
            val fixture = fixture()
            val session = if (surface == "public") fixture.openSession() else null
            val page = if (surface == "public") fixture.page("Public").snapshot.pages.single().id.value else null
            fixture.makeScheduleDue()
            val gate = Gate()
            fixture.planner.beforeResolve = gate::pause
            val pending = async(Dispatchers.IO) {
                if (surface == "schedule") fixture.schedule.tick() else fixture.sessionService.resolvePublic(ResolvePublicDashboardSessionCommand(
                    fixture.workspaceId, fixture.dashboard.id, session!!.session.id, session.session.initialVariables, "public", fixture.now.plusSeconds(1), null, null, null, page!!))
            }
            gate.awaitEntry()
            assertIs<DashboardOwnerOutcome.Success<Unit>>(fixture.lifecycle.delete(fixture.deleteCommand()))
            gate.release()
            val result = pending.await()
            if (surface == "schedule") {
                assertEquals(0, (result as DashboardScheduleTickResult).processed)
                assertEquals(0, result.failed)
            } else assertEquals(SessionResolutionResult.NotFound, result)
            assertEquals(0, fixture.count("dashboard.sessions"))
            assertEquals(0, fixture.count("dashboard.query_runs"))
            assertEquals(0, fixture.count("dashboard.schedule_occurrences"))
            assertEquals(0, fixture.count("pages_relocation.pages"))
        }
    }

    @Test
    fun `delete and actual settlement serialize in both orders and discarded success leaves the recovery loop`() = runBlocking {
        for (winner in listOf("delete", "settlement")) {
            val fixture = fixture()
            val ownerRun = fixture.openSession().queryRuns.single()
            val executionId = ExecutionRunId(ownerRun.id.value)
            val resultKey = fixture.recordSuccess(ownerRun)
            val ownerGate = Gate()
            val rivalAttempted = CountDownLatch(1)
            val queryRuns = object : QueryRunRepository by fixture.queryRuns {
                override fun find(queryRunId: QueryRunId): QueryRun? = fixture.queryRuns.find(queryRunId).also {
                    if (queryRunId == ownerRun.id && winner == "delete") ownerGate.pause()
                }
                override fun update(expectedUpdatedAt: Instant, queryRun: QueryRun): Boolean {
                    if (winner == "delete") rivalAttempted.countDown()
                    return fixture.queryRuns.update(expectedUpdatedAt, queryRun).also { updated ->
                        if (updated && winner == "settlement") ownerGate.pause()
                    }
                }
            }
            val settlement = fixture.newSettlement(queryRuns)
            val settling = async(Dispatchers.IO) { settlement.settle(executionId, Instant.now()) }
            ownerGate.awaitEntry()
            if (winner == "delete") {
                val deleteGate = Gate()
                val cleanup = object : DashboardDependencyCleanupPort by fixture.cleanup {
                    override fun discardRunsInCallerTransaction(workspaceId: WorkspaceId, runs: List<DashboardDeletionRun>, reason: String, at: Instant): DashboardRunDiscardOutcome {
                        deleteGate.pause()
                        return fixture.cleanup.discardRunsInCallerTransaction(workspaceId, runs, reason, at)
                    }
                }
                val deleting = async(Dispatchers.IO) { fixture.newLifecycle(cleanup = cleanup).delete(fixture.deleteCommand()) }
                deleteGate.awaitEntry()
                ownerGate.release()
                withContext(Dispatchers.IO) { check(rivalAttempted.await(3, TimeUnit.SECONDS)) }
                deleteGate.release()
                assertIs<DashboardOwnerOutcome.Success<Unit>>(deleting.await())
                assertEquals(ExecutionRunSettlementUseCase.SettlementOutcome.ConcurrentConflict, settling.await())
            } else {
                val deletionRepository = object : DashboardLifecycleRepository by fixture.lifecycleRepository {
                    override fun lockForDeletion(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardDeletionState? {
                        rivalAttempted.countDown()
                        return fixture.lifecycleRepository.lockForDeletion(workspaceId, dashboardId)
                    }
                }
                val deleting = async(Dispatchers.IO) { fixture.newLifecycle(deletionRepository).delete(fixture.deleteCommand()) }
                withContext(Dispatchers.IO) { check(rivalAttempted.await(3, TimeUnit.SECONDS)) }
                ownerGate.release()
                assertIs<ExecutionRunSettlementUseCase.SettlementOutcome.Completed>(settling.await())
                assertIs<DashboardOwnerOutcome.Success<Unit>>(deleting.await())
            }
            assertEquals(0, fixture.count("dashboard.dashboards"))
            assertEquals(0, fixture.count("dashboard.sessions"))
            assertEquals(0, fixture.count("dashboard.query_runs"))
            val discarded = assertNotNull(fixture.execution.get(executionId))
            assertEquals(ExecutionRunState.SUCCEEDED, discarded.state)
            assertNotNull(discarded.ownerDiscardedAt)
            assertNotNull(discarded.ownerSettledAt)
            assertIs<ExecutionRunSettlementUseCase.SettlementOutcome.Duplicate>(fixture.newSettlement().settle(executionId, Instant.now()))
            fixture.verifyRecoveryCleanup(executionId, resultKey)
            assertEquals(0, fixture.count("dashboard.query_runs"))
        }
    }

    @Test
    fun `authenticated Viewer Page reader resolve receipt returns not found after Dashboard deletion`() = runBlocking {
        val fixture = fixture()
        val session = fixture.openSession().session
        val pageId = fixture.page("Reader retry").snapshot.pages.single().id.value
        val readerId = Uuid.generateV7()
        val token = "page-reader-retry-${Uuid.generateV7()}"
        val credential = PresentedCredential(token)
        val args = mapOf("organization" to fixture.organizationId.value.toJavaUuid(), "workspace" to fixture.workspaceId.value.toJavaUuid(),
            "owner" to fixture.actorId.toJavaUuid(), "reader" to readerId.toJavaUuid(), "grant" to Uuid.generateV7().toJavaUuid(),
            "tokenId" to Uuid.generateV7().toJavaUuid(), "hash" to AgentOAuthTokenHasher().hash(token),
            "now" to java.sql.Timestamp.from(fixture.now), "expires" to java.sql.Timestamp.from(fixture.now.plusSeconds(600)))
        fixture.operations.execute {
            fixture.namedJdbc.update("INSERT INTO identity_runtime.organizations(id, display_name, created_at) VALUES (:organization, 'Reader retry', :now)", args)
            fixture.namedJdbc.update("INSERT INTO identity_runtime.principals(id, kind, display_name, created_at) VALUES (:owner, 'user', 'Owner', :now), (:reader, 'user', 'Reader', :now)", args)
            fixture.namedJdbc.update("INSERT INTO identity_runtime.users(id, primary_email, created_at) VALUES (:owner, :ownerEmail, :now), (:reader, :readerEmail, :now)", args + mapOf("ownerEmail" to "${fixture.actorId}@example.test", "readerEmail" to "$readerId@example.test"))
            fixture.namedJdbc.update("INSERT INTO identity_runtime.workspaces(id, organization_id, display_name, authorization_revision, created_at) VALUES (:workspace, :organization, 'Reader retry', 1, :now)", args)
            fixture.namedJdbc.update("INSERT INTO identity_runtime.organization_members(organization_id, user_id, role, joined_at) VALUES (:organization, :owner, 'ADMIN', :now), (:organization, :reader, 'MEMBER', :now)", args)
            fixture.namedJdbc.update("INSERT INTO identity_runtime.workspace_members(workspace_id, user_id, invited_at, role) VALUES (:workspace, :owner, :now, 'OWNER'), (:workspace, :reader, :now, 'VIEWER')", args)
            fixture.namedJdbc.update("INSERT INTO identity_runtime.agent_oauth_grants(id, owner_user_id, oauth_client_id, resource_audience, scopes, issued_at) VALUES (:grant, :reader, 'reader-retry', 'https://monomer.test/agent', ARRAY['agent.workspace']::TEXT[], :now)", args)
            fixture.namedJdbc.update("INSERT INTO identity_runtime.agent_oauth_access_tokens(id, grant_id, token_hash, issued_at, expires_at) VALUES (:tokenId, :grant, :hash, :now, :expires)", args)
        }
        val clock = Clock.fixed(fixture.now, ZoneOffset.UTC)
        val readTransactions = TransactionTemplate(fixture.manager).apply { isReadOnly = true }
        val identity = IdentityAdmissionService(PostgresCanonicalIdentityAdmissionSnapshot(fixture.namedJdbc,
            cc.monomer.identity.outbound.postgres.SpringTransactionRunner(fixture.operations, readTransactions, Dispatchers.Unconfined),
            mockk { every { verifyCurrentCredential(any()) } returns null }, AgentOAuthTokenHasher(), clock,
            mapOf(ExpectedOAuthResource.AGENT_REST to "https://monomer.test/agent", ExpectedOAuthResource.AGENT_MCP to "https://monomer.test/mcp"),
            DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.OPEN, emptySet())))
        val admission = ControlAdmissionFacade(identity, clock)
        val snapshots = object : ConsistentSnapshotTransactionRunner { override suspend fun <T> run(action: suspend () -> T): T = action() }
        val pageAccess = PublicPagesAccess(mockk<WorkspacePublicPagesQuery>(), admission, snapshots)
        val semantic = mockk<SemanticQueryUseCase> {
            coEvery { render(any()) } answers { SemanticRenderResult.Rendered(mockk {
                every { workspaceId } returns fixture.workspaceId
                every { sql } returns "SELECT 1"
                every { reportingTimeZone } returns java.time.ZoneId.of("UTC")
                every { outputColumns } returns listOf(mockk {
                    every { name } returns SemanticResultColumnKey("metric.total")
                    every { display } returns null
                })
            }) }
        }
        val planning = DashboardQueryPlanningAdapter(fixture.source, tools.jackson.databind.ObjectMapper(), semantic, admission,
            snapshots, readTransactions, Dispatchers.Unconfined, pageAccess, PagesCurrentReader(fixture.pages, mockk()), mockk())
        val service = DashboardSessionService(fixture.transaction, fixture.dashboards, fixture.sessions, fixture.queryRuns,
            fixture.receipts, planning, fixture.admission)
        assertEquals(readerId, pageAccess.requireWorkspace(fixture.workspaceId, credential, ExpectedOAuthResource.AGENT_REST)!!.actor.id.value)
        val command = ResolvePublicDashboardSessionCommand(fixture.workspaceId, fixture.dashboard.id, session.id, session.initialVariables,
            "reader-resolve", fixture.now, credential, ExpectedOAuthResource.AGENT_REST, readerId.toString(), pageId)
        assertIs<SessionResolutionResult.Resolved>(service.resolvePublic(command))
        val receipt = assertNotNull(fixture.receipts.find(fixture.workspaceId, readerId.toString(), command.idempotencyKey))
        assertEquals("RESOLVE_SESSION", receipt.commandKind)
        assertEquals(readerId.toString(), receipt.actorId)
        assertIs<DashboardOwnerOutcome.Success<Unit>>(fixture.lifecycle.delete(fixture.deleteCommand()))
        assertEquals(receipt, fixture.receipts.find(fixture.workspaceId, readerId.toString(), command.idempotencyKey))
        assertEquals(SessionResolutionResult.NotFound, service.resolvePublic(command))
        assertEquals(0, fixture.count("pages_relocation.pages"))
        assertEquals(0, fixture.count("dashboard.sessions"))
        assertEquals(0, fixture.count("dashboard.query_runs"))
        coVerify(exactly = 1) { semantic.render(any()) }
    }

    private suspend fun fixture(): Fixture = Fixture().also { fixture ->
        fixture.dashboard = assertIs<DashboardResult.Found>(fixture.dashboardService.create(fixture.createCommand)).dashboard
    }

    private class Fixture {
        val source = postgres.dataSource
        val jdbc = JdbcTemplate(source)
        val namedJdbc = NamedParameterJdbcTemplate(source)
        val workspaceId = WorkspaceId.generate()
        val actorId = Uuid.generateV7()
        val organizationId = OrganizationId.generate()
        val connectorId = ConnectorId.generate()
        val now = jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp::class.java)!!.toInstant()
        val manager = DataSourceTransactionManager(source)
        val operations = TransactionTemplate(manager).apply { timeout = 5 }
        val dispatcher = JdbcVirtualThreadDispatcher(source)
        val transaction = DashboardApplicationComposition().dashboardTransactionPort(dispatcher, operations)
        val dashboards = PostgresDashboardRepository(source)
        val drafts = PostgresDashboardDraftRepository(source)
        val sessions = PostgresDashboardSessionRepository(source)
        val queryRuns = PostgresQueryRunRepository(source)
        val receipts = PostgresDashboardCommandReceiptRepository(source)
        val schedules = PostgresDashboardScheduleRepository(source)
        val lifecycleRepository = PostgresDashboardLifecycleRepository(source)
        val existence = PostgresDashboardCurrentExistencePort(source)
        val search = SemanticDashboardSearchProjection(PostgresSearchIndex(source))
        val dashboardService = DashboardService(search, transaction, dashboards, receipts)
        val createCommand = DashboardUseCase.CreateCommand(workspaceId, definition(), actorId.toString(), actorId.toString(), "create", now, CredentialSurface.WEB, null)
        lateinit var dashboard: Dashboard
        val runtimeRunner = SpringExecutionRuntimeTransactionRunner(operations, Dispatchers.Unconfined)
        val executionStore = PostgresExecutionRuntimeStore(source)
        val dispatch = PostgresRunDispatchPersistence(source, operations)
        val codec = ExecutableRequestAssignmentCodec()
        val execution = ExecutionRunService(executionStore, runtimeRunner, runtimeRunner, dispatch, codec)
        val admission = admission(10)
        val planner = Planner()
        val sessionService = DashboardSessionService(transaction, dashboards, sessions, queryRuns, receipts, planner, admission)
        val draftService get() = newDraftService(admission)
        val schedule = DashboardScheduleService(transaction, schedules, sessions, queryRuns, admission, sessionService)
        val pagesRunner = cc.monomer.pages.adapter.outbound.postgres.SpringTransactionRunner(operations, TransactionTemplate(manager).apply { isReadOnly = true }, dispatcher)
        val directoryRepository = PostgresPagesDirectoryRepository(namedJdbc, pagesRunner)
        val contentRepository = PostgresPageContentRepository(namedJdbc, pagesRunner)
        val auditRepository = PostgresPagesAuditRepository(namedJdbc, jacksonObjectMapper())
        var failPagesAudit = false
        var beforePageExistence: (() -> Unit)? = null
        val pages = PagesDirectoryUseCaseImpl(directoryRepository, directoryRepository, pagesRunner, SilentPagesEvents,
            object : PagesAuditRepository by auditRepository {
                override fun appendWithinTx(event: PagesAuditEvent) { auditRepository.appendWithinTx(event); check(!failPagesAudit) { "Page audit unavailable" } }
            }, contentRepository, contentRepository, PagesDashboardReferencePort { workspace, dashboard ->
                beforePageExistence?.invoke()
                existence.existsInCallerTransaction(WorkspaceId(workspace), DashboardId(dashboard))
            })
        private val notifications = notifications()
        private val draftEvents = DashboardDraftEventHub(Clock.systemUTC(), notifications)
        private val sessionEvents = DashboardSessionEventHub(Clock.systemUTC(), notifications)
        val cleanup = DashboardDependencyCleanupAdapter(execution, execution, execution, pages,
            draftEvents, sessionEvents)
        val lifecycle get() = newLifecycle()
        val startCommand get() = DashboardDraftUseCase.StartCommand(workspaceId, dashboard.id, PresentedCredential("fixture-token"), null, emptyMap(), actorId.toString(), "start", now)
        val openCommand get() = DashboardSessionUseCase.OpenSessionCommand(workspaceId, PresentedCredential("fixture-token"), null, dashboard.id, emptyMap(), actorId.toString(), "open", now)
        fun deleteCommand() = DeleteDashboardCommand(workspaceId, dashboard.id, DashboardDeletionMetadata(actorId.toString(), organizationId, PresentedCredential("fixture-token"), null, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS), CredentialSurface.WEB, null))
        fun newLifecycle(lifecycleRepository: DashboardLifecycleRepository = this.lifecycleRepository, cleanup: DashboardDependencyCleanupPort = this.cleanup) = DashboardLifecycleService(transaction, dashboards, lifecycleRepository, receipts, admission, cleanup, search)
        fun newDraftService(admission: DashboardExecutionAdmissionPort) = DashboardDraftService(search, transaction, dashboards, drafts, queryRuns, receipts, planner, admission, DashboardExecutionCancellationAdapter(execution, Clock.systemUTC()))
        fun newSettlement(queryRuns: QueryRunRepository = this.queryRuns) = ExecutionRunSettlementCoordinator(
            listOf(DashboardExecutionSettlementAdapter(DashboardQueryRunSettlementService(queryRuns, drafts), queryRuns, sessionEvents, draftEvents)),
            executionStore, SpringOwnerSettlementTransactionRunner(source, 5, Dispatchers.Unconfined))
        suspend fun recordSuccess(run: QueryRun): String {
            val executionId = ExecutionRunId(run.id.value)
            val key = assertNotNull(execution.get(executionId)).runtimeBindings.resultSink.objectKey!!
            assertIs<ExecutionRunUseCase.TerminalOutcome.Accepted>(execution.recordPhysicalOutcome(ExecutionRunUseCase.RecordPhysicalOutcomeCommand(
                executionId, PhysicalOutcome("$executionId:terminal", PhysicalOutcomeKind.SUCCEEDED, null, null,
                    ArrowResultManifest(key, 10u, "etag", 1u, 1, "application/vnd.apache.arrow.file")), Instant.now())))
            return key
        }
        suspend fun verifyRecoveryCleanup(runId: ExecutionRunId, resultKey: String) {
            val observedAt = Instant.now().plusSeconds(1000)
            transaction.inTransaction {
                assertTrue(executionStore.findOwnerSettlementCandidates(setOf(ExecutionOwnerType.DASHBOARD), 100).none { it.runId == runId })
                assertTrue(executionStore.findArtifactCleanupCandidates(observedAt, 100).any { it.runId == runId })
            }
            val scopedRecovery = object : ExecutionRunUseCase by execution {
                override suspend fun recover(command: ExecutionRunUseCase.RecoverRunsCommand): ExecutionRunUseCase.RunRecoveryBatch {
                    val batch = execution.recover(command)
                    return batch.copy(dispatchDeadlineCandidates = batch.dispatchDeadlineCandidates.filter { it.id == runId },
                        executionDeadlineCandidates = batch.executionDeadlineCandidates.filter { it.id == runId },
                        orphanCandidates = batch.orphanCandidates.filter { it.id == runId },
                        occupancyRecoveryCandidates = batch.occupancyRecoveryCandidates.filter { it.id == runId },
                        ownerSettlementCandidates = batch.ownerSettlementCandidates.filter { it.id == runId },
                        artifactCleanupCandidates = batch.artifactCleanupCandidates.filter { it.id == runId })
                }
            }
            val artifacts = mockk<ExecutionArtifactStore> { coEvery { delete(resultKey) } returns Unit }
            val recovery = ExecutionRunRecoveryLifecycle(scopedRecovery, newSettlement(), artifacts, Clock.fixed(observedAt, ZoneOffset.UTC),
                ControlExecutionLimitsProperties(2 * 1024 * 1024, 100, Duration.ofHours(1), Duration.ofSeconds(1), Duration.ofSeconds(90),
                    100, 100, 3, Duration.ofSeconds(10), Duration.ofMinutes(10)), setOf(ExecutionOwnerType.DASHBOARD))
            repeat(3) {
                ExecutionRunRecoveryLifecycle::class.java.getDeclaredMethod("runCycleSafely").apply { isAccessible = true }.invoke(recovery)
                assertEquals(0, recovery.snapshot().failedCandidates)
                assertNull(recovery.snapshot().candidateFailureType)
                assertNull(recovery.snapshot().cycleFailureType)
            }
            coVerify(exactly = 1) { artifacts.delete(resultKey) }
            transaction.inTransaction {
                assertTrue(executionStore.findOwnerSettlementCandidates(setOf(ExecutionOwnerType.DASHBOARD), 100).none { it.runId == runId })
                assertTrue(executionStore.findArtifactCleanupCandidates(observedAt, 100).none { it.runId == runId })
            }
        }
        suspend fun startDraft() = assertIs<DraftResult.Found>(draftService.start(startCommand))
        suspend fun openSession() = assertIs<SessionResult.Found>(sessionService.open(openCommand))
        suspend fun pageCommand(title: String) = PagesDirectoryUseCase.CreateDashboardPageCommand(workspaceId.value, dashboard.id.value, title, null, null, null,
            pages.getSnapshot(workspaceId.value).revision, PageDirectoryRequestId(Uuid.generateV7().toString()),
            PageActor.create(PageActorKind.USER, actorId.toString()), null, AuditActorContext(organizationId.value, actorId, "fixture"), now)
        suspend fun page(title: String) = assertIs<PagesDirectoryMutationOutcome.Applied>(pages.createDashboardPage(pageCommand(title)))
        fun makeScheduleDue() { jdbc.update("UPDATE dashboard.schedule_cursors SET next_scheduled_at = ? WHERE workspace_id = ?", java.sql.Timestamp.from(now.minusSeconds(5)), workspaceId.value.toJavaUuid()) }
        fun count(table: String): Int = if (table == "pages_relocation.audit_events") jdbc.queryForObject("SELECT count(*) FROM pages_relocation.audit_events WHERE payload->>'workspaceId' = ?", Int::class.java, workspaceId.toString())!! else postgres.count(table, "workspace_id", workspaceId.value.toJavaUuid())
        fun queryExecutionIds() = jdbc.query("SELECT query_run_id FROM dashboard.query_runs WHERE workspace_id = ?", { row, _ -> ExecutionRunId(Uuid.parse(row.getString(1))) }, workspaceId.value.toJavaUuid())
        private fun definition(): JsonObject = Json.parseToJsonElement("""{
            "schemaVersion":1,"meta":{"title":"Deletion gate"},"parameters":{},"variables":{},
            "queries":{"total":{"type":"SEMANTIC","connectorId":"$connectorId","query":{"schemaVersion":4,"metrics":["total"],"groupBys":[],"filters":[],"orderBy":[],"metricTimeRange":null,"rowLimit":100}}},
            "widgets":{"chart":{"type":"CHART","queryNames":["total"],"module":{"inputs":{"parameters":[],"variables":[]},"source":"export default defineChart({ render() { return {}; } });"}}},
            "layout":{"sections":[{"id":"main","height":{"mode":"SCALED","scale":1},"root":{"type":"WIDGET","id":"item","widgetId":"chart"}}]},
            "schedules":{"hourly":{"cron":"0 * * * *","timeZone":"UTC","parameters":{}}}
        }""").jsonObject
        fun admission(maximumNonTerminalRuns: Int): DashboardExecutionAdmissionPort {
            val credentialVersion = Uuid.generateV7()
            val binding = ConnectorExecutionBinding(connectorId, workspaceId, "POSTGRESQL", "fixture", mapOf("host" to "fixture-warehouse"),
                CredentialEnvelopeReference(CredentialVersionId(credentialVersion), "fixture-key", ContentDigest("sha256:${"a".repeat(64)}")), now)
            val projection = ConnectorRuntimeBindingSpec(ConnectorReference(connectorId.value), ConnectorType.POSTGRESQL, mapOf("host" to ConnectorRuntimePropertyValue.Text("fixture-warehouse")),
                ManagedCredentialDispatchReference(CredentialVersionReference(credentialVersion), "fixture-key", Sha256Digest("a".repeat(64))))
            return DashboardExecutionAdmissionAdapter(object : ConnectorExecutionAccessInOwnerTransactionPort {
                override fun readCurrentForExecutionInOwnerTransaction(workspaceId: WorkspaceId, connectorId: ConnectorId): ConnectorExecutionAccessResult =
                    if (workspaceId == binding.workspaceId && connectorId == binding.connectorId) ConnectorExecutionAccessResult.Granted(binding) else ConnectorExecutionAccessResult.NotFoundOrOutsideWorkspace
            }, ConnectorRuntimeBindingProjectionPort { projection }, ProtoRawSqlRunSpecFactory(), ExecutionRunAdmissionService(executionStore, dispatch, codec, ExecutionRunLimits(maximumNonTerminalRuns)), Clock.fixed(now, ZoneOffset.UTC), testControlRuntimeProperties())
        }
        private fun notifications(): PostgresDashboardNotifications {
            val source = postgres.newDataSource() as DriverManagerDataSource
            return PostgresDashboardNotifications(ControlProductPostgresProperties(requireNotNull(source.url), requireNotNull(source.username), requireNotNull(source.password), 2, Duration.ofSeconds(2), Duration.ofMillis(250)),
                ControlProductTransactionProperties(Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofMillis(100)), Duration.ofMillis(20), this.source)
        }
    }

    private class Planner : DashboardQueryPlanningPort {
        var beforeResolve: (() -> Unit)? = null
        override suspend fun admitPageReader(workspaceId: WorkspaceId, dashboardId: DashboardId, pageId: Uuid, credential: PresentedCredential?, expectedOAuthResource: ExpectedOAuthResource?): DashboardOwnerOutcome<IdentityCredentialEvidence?> = DashboardOwnerOutcome.Success(null)
        override suspend fun resolveBatch(commands: List<DashboardQueryPlanningPort.ResolveQueryCommand>) = commands.map { resolve(it) }
        override suspend fun resolve(command: DashboardQueryPlanningPort.ResolveQueryCommand): DashboardQueryResolution {
            beforeResolve?.invoke()
            return DashboardQueryResolution.Resolved(command.resolvedQuery, "SELECT 1", listOf("metric.total"), null, null, null, emptyList(), null, reportingTimeZone = null)
        }
    }

    private class Gate {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        fun pause() { entered.countDown(); check(released.await(15, TimeUnit.SECONDS)) { "test gate was reached but not released" } }
        suspend fun awaitEntry() = withContext(Dispatchers.IO) { check(entered.await(10, TimeUnit.SECONDS)) { "test gate was not reached" } }
        fun release() { released.countDown() }
    }
    private object SilentPagesEvents : PagesDirectoryEventBus {
        override fun publish(wakeup: PagesDirectoryWakeup) = Unit
        override fun subscribe(workspaceId: Uuid): Flow<PagesDirectoryWakeup> = emptyFlow()
    }
    companion object {
        @JvmField @RegisterExtension val postgres = EphemeralPostgresAuthoritySupport
        @JvmStatic @BeforeAll fun schemas() {
            postgres.verifyEphemeralIdentity()
            postgres.applyAuthoritySchemasTwice()
            postgres.dataSource.connection.use { connection -> connection.createStatement().use { it.execute(Path.of(System.getProperty("control.service.root"), "modules/pages/src/main/resources/schema-postgres.sql").toFile().readText()) } }
        }
    }
}
