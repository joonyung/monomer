@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.dashboard.application.DashboardExecutionCancellationPort
import cc.monomer.execution.api.ExecutionOwnerId
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.OwnerExecutionRunCancellationPort
import cc.monomer.identity.api.WorkspaceId
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.Test
import kotlin.uuid.Uuid

class DashboardExecutionCancellationAdapterTest {
    @Test
    fun `replacement cancellation is propagated to dashboard owned execution run`() {
        val cancellationPort = mockk<OwnerExecutionRunCancellationPort>()
        val runId = QueryRunId(Uuid.parse("019e0000-0000-7000-8000-0000000000a1"))
        val workspaceId = WorkspaceId(Uuid.parse("019e0000-0000-7000-8000-0000000000a2"))
        val command = slot<OwnerExecutionRunCancellationPort.RequestCancellationCommand>()
        every { cancellationPort.requestCancellationInOwnerTransaction(capture(command)) } returns
            OwnerExecutionRunCancellationPort.CancellationOutcome.Conflict

        val outcome = DashboardExecutionCancellationAdapter(
            cancellationPort,
            Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC),
        ).requestCancel(
            DashboardExecutionCancellationPort.CancelCommand(
                queryRunId = runId,
                workspaceId = workspaceId,
                reason = "QUERY_REPLACED",
            ),
        )

        assertIs<DashboardExecutionCancellationPort.CancellationOutcome.NotFound>(outcome)
        assertEquals(ExecutionRunId(runId.value), command.captured.runId)
        assertEquals(
            ExecutionOwnerReference(ExecutionOwnerType.DASHBOARD, ExecutionOwnerId(runId.value)),
            command.captured.owner,
        )
        assertEquals(workspaceId, command.captured.workspaceId)
        assertEquals("QUERY_REPLACED", command.captured.reasonCode)
        verify(exactly = 1) { cancellationPort.requestCancellationInOwnerTransaction(any()) }
    }
}
