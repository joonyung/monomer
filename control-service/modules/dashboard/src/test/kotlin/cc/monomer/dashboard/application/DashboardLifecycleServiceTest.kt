@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.application

import cc.monomer.dashboard.api.*
import cc.monomer.dashboard.domain.Dashboard
import cc.monomer.dashboard.domain.DashboardDefinition
import cc.monomer.dashboard.domain.DashboardListEntry
import cc.monomer.dashboard.domain.DashboardListItem
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.identity.api.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.time.Instant
import kotlin.test.*
import kotlin.uuid.Uuid

class DashboardLifecycleServiceTest {
    @Test
    fun `deletion discards every execution and commits history search Pages and invalidation once`() = runTest {
        val fixture = Fixture()
        val command = fixture.deleteCommand()
        assertIs<DashboardOwnerOutcome.Success<Unit>>(fixture.service.delete(command))
        assertEquals(listOf("admission", "owner-locks", "discard", "history", "owner-delete", "search-delete", "pages-delete", "invalidation"), fixture.events)
        assertEquals(fixture.runs, fixture.discardedRuns)
        assertEquals("DASHBOARD_DELETED", fixture.discardReason)
        val change = fixture.changes.single()
        assertEquals(DashboardDefinitionChangeAction.DELETED, change.action)
        assertEquals(command.metadata.actorId, change.actorId)
        assertEquals(fixture.initial.definition.document, change.definition)
        assertNull(fixture.current)
        assertEquals(fixture.sessionIds, fixture.invalidatedSessions)
        assertEquals(DashboardOwnerOutcome.NotFound, fixture.service.delete(command))
        assertEquals(1, fixture.changes.size)
    }

    @Test
    fun `other Workspace cannot delete or read a definition history`() = runTest {
        val fixture = Fixture()
        fixture.change(DashboardDefinitionChangeAction.CREATED, fixture.initial.definition.document)
        assertEquals(DashboardOwnerOutcome.NotFound, fixture.service.delete(fixture.deleteCommand().copy(workspaceId = WorkspaceId.generate())))
        assertEquals(DashboardOwnerOutcome.NotFound, fixture.service.listHistory(ListDashboardHistoryCommand(WorkspaceId.generate(), fixture.initial.id, null, 20)))
        assertEquals(DashboardOwnerOutcome.NotFound, fixture.service.readHistory(ReadDashboardHistoryCommand(fixture.workspaceId, DashboardId.generate(), fixture.changes.single().changeId)))
        assertEquals(fixture.initial, fixture.current)
    }

    @Test
    fun `cancellation conflict rolls all owner changes back and returns retryable rejection`() = runTest {
        val fixture = Fixture().apply { discardOutcome = DashboardRunDiscardOutcome.RETRYABLE_CONFLICT }
        val result = assertIs<DashboardOwnerOutcome.Rejected>(fixture.service.delete(fixture.deleteCommand()))
        assertEquals("DASHBOARD_DELETE_RETRY_REQUIRED", result.code)
        assertEquals(fixture.initial, fixture.current)
        assertTrue(fixture.changes.isEmpty())
        assertFalse("pages-delete" in fixture.events || "invalidation" in fixture.events)
    }

    @Test
    fun `Page deletion failure rolls definition history search and deletion back`() = runTest {
        val fixture = Fixture().apply { failPages = true }
        assertFailsWith<IllegalStateException> { fixture.service.delete(fixture.deleteCommand()) }
        assertEquals(fixture.initial, fixture.current)
        assertTrue(fixture.changes.isEmpty())
        assertTrue(fixture.searchExists)
        assertFalse("invalidation" in fixture.events)
    }

    @Test
    fun `deleted history exposes latest deletion and distinct older definitions only`() = runTest {
        val fixture = Fixture()
        val older = fixture.change(DashboardDefinitionChangeAction.CREATED, fixture.definition("Older"))
        val same = fixture.change(DashboardDefinitionChangeAction.APPLIED, fixture.initial.definition.document)
        fixture.service.delete(fixture.deleteCommand())
        val page = assertIs<DashboardOwnerOutcome.Success<DashboardOwnerHistoryPage>>(fixture.service.listHistory(ListDashboardHistoryCommand(fixture.workspaceId, fixture.initial.id, null, 2))).value
        assertTrue(page.deleted)
        assertEquals("Current", page.name)
        assertEquals(listOf(true, false), page.changes.map { it.restorable })
        val next = assertIs<DashboardOwnerOutcome.Success<DashboardOwnerHistoryPage>>(fixture.service.listHistory(ListDashboardHistoryCommand(fixture.workspaceId, fixture.initial.id, page.nextCursor, 2))).value
        assertEquals(listOf(older.changeId), next.changes.map { it.changeId })
        assertTrue(next.changes.single().restorable)
        assertNull(next.nextCursor)
        assertFalse(assertIs<DashboardOwnerOutcome.Success<DashboardOwnerDefinitionChange>>(fixture.service.readHistory(ReadDashboardHistoryCommand(fixture.workspaceId, fixture.initial.id, same.changeId))).value.restorable)
        val foreignCursor = page.nextCursor!!.copy(workspaceId = WorkspaceId.generate())
        assertEquals("INVALID_CURSOR", assertIs<DashboardOwnerOutcome.Rejected>(fixture.service.listHistory(ListDashboardHistoryCommand(fixture.workspaceId, fixture.initial.id, foreignCursor, 20))).code)
        assertFailsWith<IllegalArgumentException> { fixture.service.listHistory(ListDashboardHistoryCommand(fixture.workspaceId, fixture.initial.id, null, 101)) }
    }

    @Test
    fun `living Dashboard history is read only and cannot be restored`() = runTest {
        val fixture = Fixture()
        val change = fixture.change(DashboardDefinitionChangeAction.CREATED, fixture.definition("Older"))
        val page = assertIs<DashboardOwnerOutcome.Success<DashboardOwnerHistoryPage>>(fixture.service.listHistory(ListDashboardHistoryCommand(fixture.workspaceId, fixture.initial.id, null, 20))).value
        assertFalse(page.deleted)
        assertTrue(page.changes.none { it.restorable })
        val rejected = assertIs<DashboardOwnerOutcome.Rejected>(fixture.service.restore(fixture.restoreCommand(change.changeId)))
        assertEquals("DASHBOARD_NOT_RESTORABLE", rejected.code)
        assertEquals("This dashboard isn't deleted, so there's nothing to restore. Open it to edit it instead.", rejected.message)
        assertEquals(fixture.initial, fixture.current)
    }

    @Test
    fun `restore uses original ID validated snapshot and a single CREATED record on retry`() = runTest {
        val fixture = Fixture()
        fixture.service.delete(fixture.deleteCommand())
        val deleted = fixture.changes.single()
        val command = fixture.restoreCommand(deleted.changeId)
        val restored = assertIs<DashboardOwnerOutcome.Success<Dashboard>>(fixture.service.restore(command)).value
        assertEquals(fixture.initial.id, restored.id)
        assertEquals(fixture.initial.definition.document, restored.definition.document)
        assertEquals(command.metadata.requestedAt, restored.createdAt)
        assertEquals(command.metadata.actorId, restored.updatedBy)
        assertEquals(listOf(DashboardDefinitionChangeAction.DELETED, DashboardDefinitionChangeAction.CREATED), fixture.changes.map { it.action })
        assertEquals(restored, assertIs<DashboardOwnerOutcome.Success<Dashboard>>(fixture.service.restore(command)).value)
        assertEquals(2, fixture.changes.size)
        fixture.service.delete(fixture.deleteCommand().copy(metadata = fixture.deleteCommand().metadata.copy(requestedAt = command.metadata.requestedAt.plusSeconds(1))))
        assertEquals("IDEMPOTENCY_CONFLICT", assertIs<DashboardOwnerOutcome.Rejected>(fixture.service.restore(command)).code)
        assertNull(fixture.current)
    }

    @Test
    fun `restore rejects identical old version and another dashboards version`() = runTest {
        val fixture = Fixture()
        val same = fixture.change(DashboardDefinitionChangeAction.APPLIED, fixture.initial.definition.document)
        fixture.service.delete(fixture.deleteCommand())
        val rejected = assertIs<DashboardOwnerOutcome.Rejected>(fixture.service.restore(fixture.restoreCommand(same.changeId)))
        assertEquals("DASHBOARD_NOT_RESTORABLE", rejected.code)
        assertEquals("This version matches the dashboard as it was deleted. Restore the deleted version instead.", rejected.message)
        assertEquals(DashboardOwnerOutcome.NotFound, fixture.service.restore(fixture.restoreCommand(Uuid.generateV7())))
        assertNull(fixture.current)
    }

    @Test
    fun `restore rejects an undecodable recorded definition without writing`() = runTest {
        val fixture = Fixture()
        val broken = fixture.change(DashboardDefinitionChangeAction.CREATED, buildJsonObject { put("meta", buildJsonObject { put("title", "Broken") }) })
        fixture.service.delete(fixture.deleteCommand())
        val rejected = assertIs<DashboardOwnerOutcome.Rejected>(fixture.service.restore(fixture.restoreCommand(broken.changeId)))
        assertEquals("INVALID_DASHBOARD_DEFINITION", rejected.code)
        assertNotNull(rejected.message)
        assertNull(fixture.current)
        assertEquals(2, fixture.changes.size)
        assertTrue(fixture.receipts.isEmpty())
    }

    @Test
    fun `restore that loses the insert to an existing Dashboard ID is not restorable`() = runTest {
        val fixture = Fixture()
        fixture.service.delete(fixture.deleteCommand())
        val deleted = fixture.changes.single()
        val occupant = fixture.initial.copy(workspaceId = WorkspaceId.generate())
        fixture.current = occupant
        val rejected = assertIs<DashboardOwnerOutcome.Rejected>(fixture.service.restore(fixture.restoreCommand(deleted.changeId)))
        assertEquals("DASHBOARD_NOT_RESTORABLE", rejected.code)
        assertEquals("This dashboard isn't deleted, so there's nothing to restore. Open it to edit it instead.", rejected.message)
        assertEquals(occupant, fixture.current)
        assertTrue(fixture.receipts.isEmpty())
    }

    private class Fixture : DashboardRepository, DashboardLifecycleRepository, DashboardCommandReceiptRepository, DashboardTransactionPort, DashboardDependencyCleanupPort, DashboardExecutionAdmissionPort, DashboardSearchProjection {
        val workspaceId = WorkspaceId.generate()
        val now = Instant.parse("2026-10-08T00:00:00Z")
        val initial = Dashboard(DashboardId.generate(), workspaceId, DashboardDefinitionDecoder.decode(definition("Current")), now, now, "creator", CredentialSurface.WEB, null)
        var current: Dashboard? = initial
        val events = mutableListOf<String>()
        val changes = mutableListOf<DashboardOwnerDefinitionChange>()
        val receipts = mutableMapOf<Triple<WorkspaceId, String?, String>, CommandReceipt>()
        val sessionIds = listOf(DashboardSessionId.generate())
        val runs = listOf(true, false).map { running -> QueryRunId.generate().let { DashboardDeletionRun(it, ExecutionRunId(it.value), running) } }
        var discardedRuns = emptyList<DashboardDeletionRun>()
        var invalidatedSessions = emptyList<DashboardSessionId>()
        var discardReason: String? = null
        var discardOutcome = DashboardRunDiscardOutcome.COMPLETED
        var failPages = false
        var searchExists = true
        val service = DashboardLifecycleService(this, this, this, this, this, this, this)

        fun definition(title: String): JsonObject = Json.parseToJsonElement("""{
            "schemaVersion":1,"meta":{"title":"$title"},"parameters":{},"variables":{},"queries":{},
            "widgets":{"text":{"type":"TEXT","content":"Hello"}},
            "layout":{"sections":[{"id":"main","height":{"mode":"CONTENT"},"root":{"type":"WIDGET","id":"textItem","widgetId":"text"}}]}
        }""").jsonObject
        fun change(action: DashboardDefinitionChangeAction, document: JsonObject) = DashboardOwnerDefinitionChange(Uuid.generateV7(), workspaceId, initial.id,
            document.getValue("meta").jsonObject.getValue("title").jsonPrimitive.content, action, document, null, null, null,
            now.plusSeconds(changes.size.toLong()), false).also { changes += it }
        fun deleteCommand() = DeleteDashboardCommand(workspaceId, initial.id, DashboardDeletionMetadata("deleter", OrganizationId.generate(), PresentedCredential("test-credential"), null, now.plusSeconds(10), CredentialSurface.WEB, null))
        fun restoreCommand(changeId: Uuid) = RestoreDashboardCommand(workspaceId, initial.id, changeId,
            DashboardOwnerCommandMetadata("restorer", PresentedCredential("test-credential"), null, "restore-key", now.plusSeconds(20), CredentialSurface.WEB, null))

        override suspend fun <T> inTransaction(block: () -> T): T {
            val before = current
            val beforeChanges = changes.toList()
            val beforeReceipts = receipts.toMap()
            val beforeSearch = searchExists
            return try { block() } catch (error: Throwable) {
                current = before; changes.clear(); changes += beforeChanges; receipts.clear(); receipts += beforeReceipts; searchExists = beforeSearch
                throw error
            }
        }
        override fun lockWorkspace(workspaceId: WorkspaceId) { events += "admission" }
        override fun hasUnreleasedOccupancy(workspaceId: WorkspaceId, queryRunIds: Set<QueryRunId>): Boolean = error("not used")
        override fun admit(command: DashboardExecutionAdmissionPort.AdmissionCommand): DashboardExecutionAdmissionPort.AdmissionOutcome = error("not used")
        override fun lockForDeletion(workspaceId: WorkspaceId, dashboardId: DashboardId): DashboardDeletionState? {
            events += "owner-locks"
            return find(workspaceId, dashboardId)?.let { DashboardDeletionState(it, true, runs, sessionIds) }
        }
        override fun discardRunsInCallerTransaction(workspaceId: WorkspaceId, runs: List<DashboardDeletionRun>, reason: String, at: Instant): DashboardRunDiscardOutcome {
            events += "discard"; discardedRuns = runs; discardReason = reason; return discardOutcome
        }
        override fun deleteOwnedRows(workspaceId: WorkspaceId, dashboardId: DashboardId) { events += "owner-delete"; current = null }
        override fun appendDefinitionChange(change: DashboardOwnerDefinitionChange) { events += "history"; changes += change }
        override fun deletePagesInCallerTransaction(command: DeleteDashboardCommand) { events += "pages-delete"; check(!failPages) { "Pages unavailable" } }
        override fun invalidateAfterCommit(workspaceId: WorkspaceId, dashboardId: DashboardId, draftExisted: Boolean, sessionIds: List<DashboardSessionId>) { assertTrue(draftExisted); events += "invalidation"; invalidatedSessions = sessionIds }
        override fun readDefinitionChanges(workspaceId: WorkspaceId, dashboardId: DashboardId, cursor: DashboardHistoryCursor?, limit: Int): List<DashboardOwnerDefinitionChange> = changes
            .filter { it.workspaceId == workspaceId && it.dashboardId == dashboardId }
            .sortedWith(compareByDescending<DashboardOwnerDefinitionChange> { it.occurredAt }.thenByDescending { it.changeId.toString() })
            .filter { cursor == null || it.occurredAt < cursor.occurredAt || (it.occurredAt == cursor.occurredAt && it.changeId.toString() < cursor.changeId.toString()) }.take(limit)
        override fun findDefinitionChange(workspaceId: WorkspaceId, dashboardId: DashboardId, changeId: Uuid) = changes.firstOrNull { it.workspaceId == workspaceId && it.dashboardId == dashboardId && it.changeId == changeId }
        override fun find(workspaceId: WorkspaceId, dashboardId: DashboardId) = current?.takeIf { it.workspaceId == workspaceId && it.id == dashboardId }
        override fun insert(dashboard: Dashboard): Boolean {
            if (current != null) return false
            current = dashboard
            appendDefinitionChange(DashboardOwnerDefinitionChange(Uuid.generateV7(), dashboard.workspaceId, dashboard.id, dashboard.definition.meta.title,
                DashboardDefinitionChangeAction.CREATED, dashboard.definition.document, dashboard.updatedBy, dashboard.credentialSurface, dashboard.agentClientId, dashboard.updatedAt, false))
            return true
        }
        override fun lock(workspaceId: WorkspaceId, actorId: String?, idempotencyKey: String) = Unit
        override fun find(workspaceId: WorkspaceId, actorId: String?, idempotencyKey: String) = receipts[Triple(workspaceId, actorId, idempotencyKey)]
        override fun insert(receipt: CommandReceipt) = receipts.putIfAbsent(Triple(receipt.workspaceId, receipt.actorId, receipt.idempotencyKey), receipt) == null
        override fun upsert(fields: DashboardSearchFields) { searchExists = true }
        override fun delete(workspaceId: WorkspaceId, dashboardId: DashboardId) { events += "search-delete"; searchExists = false }
        override fun readCurrentSemanticReferences(workspaceId: WorkspaceId): List<DashboardCurrentSemanticReferences> = error("not used")
        override fun findForUpdate(workspaceId: WorkspaceId, dashboardId: DashboardId): Dashboard? = find(workspaceId, dashboardId)
        override fun list(workspaceId: WorkspaceId): List<DashboardListEntry> = error("not used")
        override fun findPage(workspaceId: WorkspaceId, cursor: DashboardPageCursor?, limit: Int): List<DashboardListItem> = error("not used")
        override fun applyCurrent(workspaceId: WorkspaceId, dashboardId: DashboardId, definition: DashboardDefinition, updatedAt: Instant, updatedBy: String?, credentialSurface: CredentialSurface?, agentClientId: AgentClientId?): Boolean = error("not used")
    }
}
