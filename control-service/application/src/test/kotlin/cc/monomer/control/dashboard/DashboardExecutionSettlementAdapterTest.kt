@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.dashboard.application.DashboardQueryRunSettlementUseCase
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunOwnerSettlementPort
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.PhysicalOutcome
import cc.monomer.execution.api.PhysicalOutcomeKind
import cc.monomer.identity.api.WorkspaceId
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class DashboardExecutionSettlementAdapterTest {
    @Test
    fun `an owner-requested cancel settles without carrying its reason as a failure message`() {
        val settlement = mockk<DashboardQueryRunSettlementUseCase>()
        val command = slot<DashboardQueryRunSettlementUseCase.Command>()
        every { settlement.settleInOwnerTransaction(capture(command)) } returns DashboardQueryRunSettlementUseCase.Outcome.Duplicate
        val adapter = DashboardExecutionSettlementAdapter(settlement, mockk(), mockk(), mockk())

        adapter.settleInOwnerTransaction(settleCommand(PhysicalOutcomeKind.CANCELLED, null, "USER_REQUESTED"))
        assertEquals(DashboardQueryRunSettlementUseCase.State.CANCELLED, command.captured.state)
        assertNull(command.captured.failureMessage)

        adapter.settleInOwnerTransaction(settleCommand(PhysicalOutcomeKind.FAILED, "RUN_FAILED", "external execution failed"))
        assertEquals("RUN_FAILED", command.captured.failureCode)
        assertEquals("external execution failed", command.captured.failureMessage)
    }

    private fun settleCommand(kind: PhysicalOutcomeKind, failureCode: String?, safeMessage: String?): ExecutionRunOwnerSettlementPort.SettleCommand {
        val outcome = PhysicalOutcome(
            terminalIdempotencyKey = "terminal",
            kind = kind,
            failureCode = failureCode,
            safeMessage = safeMessage,
        )
        val run = mockk<ExecutionRunSnapshot> {
            every { physicalOutcome } returns outcome
            every { ownerSettledAt } returns null
            every { workspaceId } returns WorkspaceId(Uuid.generateV7())
            every { id } returns ExecutionRunId(Uuid.generateV7())
        }
        return ExecutionRunOwnerSettlementPort.SettleCommand(run, Instant.parse("2026-10-04T00:00:00Z"))
    }
}
