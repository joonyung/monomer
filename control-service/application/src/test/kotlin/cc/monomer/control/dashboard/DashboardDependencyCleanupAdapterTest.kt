@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.dashboard.api.*
import cc.monomer.execution.api.*
import cc.monomer.identity.api.WorkspaceId
import io.mockk.*
import java.time.Instant
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class DashboardDependencyCleanupAdapterTest {
    private val cancellation = mockk<OwnerExecutionRunCancellationPort>()
    private val executionRuns = mockk<ExecutionRunQueryPort>()
    private val discard = mockk<OwnerExecutionRunDiscardPort>()
    private val adapter = DashboardDependencyCleanupAdapter(cancellation, executionRuns, discard, mockk(), mockk(), mockk())
    private val workspace = WorkspaceId(Uuid.generateV7())
    private val queryRun = QueryRunId(Uuid.generateV7())
    private val executionRun = ExecutionRunId(Uuid.generateV7())
    private val owner = ExecutionOwnerReference(ExecutionOwnerType.DASHBOARD, ExecutionOwnerId(queryRun.value))
    private val now = Instant.parse("2026-10-08T00:00:00Z")

    @Test
    fun `terminal conflict recheck uses exact execution id and query owner before discard`() = runTest {
        every { cancellation.requestCancellationInOwnerTransaction(any()) } returns OwnerExecutionRunCancellationPort.CancellationOutcome.Conflict
        val terminal = snapshot(ExecutionRunState.SUCCEEDED)
        coEvery { executionRuns.get(executionRun) } returns terminal
        every { discard.discardInOwnerTransaction(any()) } returns OwnerExecutionRunDiscardPort.DiscardOutcome.Applied(terminal)
        assertEquals(DashboardRunDiscardOutcome.COMPLETED, adapter.discardRunsInCallerTransaction(workspace,
            listOf(DashboardDeletionRun(queryRun, executionRun, true)), "DASHBOARD_DELETED", now))
        verify { discard.discardInOwnerTransaction(match { it.runId == executionRun && it.owner == owner && it.workspaceId == workspace && it.discardedAt == now }) }
    }

    @Test
    fun `nonterminal or foreign owner conflict is retryable and does not discard`() = runTest {
        every { cancellation.requestCancellationInOwnerTransaction(any()) } returns OwnerExecutionRunCancellationPort.CancellationOutcome.Conflict
        val running = snapshot(ExecutionRunState.RUNNING)
        val foreign = snapshot(ExecutionRunState.SUCCEEDED).also { every { it.workspaceId } returns WorkspaceId(Uuid.generateV7()) }
        for (current in listOf(running, foreign, null)) {
            coEvery { executionRuns.get(executionRun) } returns current
            assertEquals(DashboardRunDiscardOutcome.RETRYABLE_CONFLICT, adapter.discardRunsInCallerTransaction(workspace,
                listOf(DashboardDeletionRun(queryRun, executionRun, true)), "DASHBOARD_DELETED", now))
        }
        verify(exactly = 0) { discard.discardInOwnerTransaction(any()) }
    }

    @Test
    fun `failed query without admitted execution has nothing to discard`() {
        every { discard.discardInOwnerTransaction(any()) } returns OwnerExecutionRunDiscardPort.DiscardOutcome.NotFound
        assertEquals(DashboardRunDiscardOutcome.COMPLETED, adapter.discardRunsInCallerTransaction(workspace,
            listOf(DashboardDeletionRun(queryRun, executionRun, false)), "DASHBOARD_DELETED", now))
        verify(exactly = 0) { cancellation.requestCancellationInOwnerTransaction(any()) }
        coVerify(exactly = 0) { executionRuns.get(executionRun) }
    }

    @Test
    fun `already finished owner queries are discarded without another cancellation`() {
        every { discard.discardInOwnerTransaction(any()) } returns OwnerExecutionRunDiscardPort.DiscardOutcome.Duplicate(snapshot(ExecutionRunState.SUCCEEDED))
        assertEquals(DashboardRunDiscardOutcome.COMPLETED, adapter.discardRunsInCallerTransaction(workspace,
            listOf(DashboardDeletionRun(queryRun, executionRun, false)), "DASHBOARD_DELETED", now))
        verify(exactly = 0) { cancellation.requestCancellationInOwnerTransaction(any()) }
        every { discard.discardInOwnerTransaction(any()) } returns OwnerExecutionRunDiscardPort.DiscardOutcome.Conflict
        assertEquals(DashboardRunDiscardOutcome.RETRYABLE_CONFLICT, adapter.discardRunsInCallerTransaction(workspace,
            listOf(DashboardDeletionRun(queryRun, executionRun, false)), "DASHBOARD_DELETED", now))
    }

    private fun snapshot(state: ExecutionRunState): ExecutionRunSnapshot = mockk {
        every { this@mockk.state } returns state
        every { workspaceId } returns workspace
        every { this@mockk.owner } returns this@DashboardDependencyCleanupAdapterTest.owner
    }
}
