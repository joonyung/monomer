@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.explore.application

import cc.monomer.explore.api.MAX_EXPLORE_FAILURE_DETAIL_LENGTH
import cc.monomer.connector.api.ConnectorId
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.ExecutionOwnerId
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunOwnerSettlementPort
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.PhysicalOutcome
import cc.monomer.execution.api.PhysicalOutcomeKind
import cc.monomer.explore.api.ExploreConnectorBinding
import cc.monomer.explore.api.ExploreDigest
import cc.monomer.explore.api.ExploreRunFailureCode
import cc.monomer.explore.api.ExploreRunId
import cc.monomer.explore.api.ExploreRunState
import cc.monomer.explore.domain.ExploreRun
import cc.monomer.explore.domain.ExploreTerminalReceipt
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Duration
import java.time.Instant
import kotlin.uuid.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals

class ExploreSettlementAdapterTest {
    @Test
    fun `settlement keeps classified warehouse codes and safe detail and collapses every other code`() {
        val now = Instant.parse("2026-09-30T00:00:00Z")
        val workspace = WorkspaceId.generate()
        val connector = ExploreConnectorBinding(workspace, ConnectorId(Uuid.generateV7()), ConnectorType.POSTGRESQL)
        val run = ExploreRun(
            id = ExploreRunId.generate(),
            kind = ExploreRun.Kind.Sql,
            idempotencyKey = "sql-permission",
            ownerPrincipalId = PrincipalId.generate(),
            credentialSurface = null,
            agentClientId = null,
            connectorBinding = connector,
            requestDigest = ExploreDigest("sha256:${"a".repeat(64)}"),
            state = ExploreRunState.RUNNING,
            visibleResult = null,
            failureCode = null,
            failureMessage = null,
            revision = 0,
            createdAt = now,
            updatedAt = now,
        )
        listOf(
            "PERMISSION_DENIED" to ExploreRunFailureCode.PERMISSION_DENIED,
            "CONNECTION_FAILED" to ExploreRunFailureCode.CONNECTION_FAILED,
            "AUTHENTICATION_FAILED" to ExploreRunFailureCode.AUTHENTICATION_FAILED,
            "EXECUTION_ORPHANED" to ExploreRunFailureCode.EXECUTION_ORPHANED,
            "READ_ONLY_VIOLATION" to ExploreRunFailureCode.EXECUTION_FAILED,
            "POSTGRESQL_EXECUTION_FAILED" to ExploreRunFailureCode.EXECUTION_FAILED,
            "CANCELLED_BY_ACTOR" to ExploreRunFailureCode.EXECUTION_FAILED,
            "UNSUPPORTED_RESULT_TYPE" to ExploreRunFailureCode.EXECUTION_FAILED,
            "RESULT_LIMIT_EXCEEDED" to ExploreRunFailureCode.EXECUTION_FAILED,
        ).forEach { (physicalCode, expectedCode) ->
            val message = if (physicalCode == "UNSUPPORTED_RESULT_TYPE") {
                "Result column 'sample' has unsupported type 'vendor_type'."
            } else {
                "Warehouse diagnostic for $physicalCode"
            }
            val expectedMessage = if (physicalCode == "RESULT_LIMIT_EXCEEDED") {
                "The query result exceeds the supported size limit. Return fewer rows or smaller values."
            } else {
                message
            }
            val execution = mockk<ExecutionRunSnapshot>()
            every { execution.id } returns ExecutionRunId(run.id.value)
            every { execution.workspaceId } returns workspace
            every { execution.owner } returns ExecutionOwnerReference(ExecutionOwnerType.EXPLORE, ExecutionOwnerId(run.id.value))
            every { execution.connectorId } returns ConnectorReference(connector.connectorId.value)
            every { execution.connectorType } returns ConnectorType.POSTGRESQL
            every { execution.ownerSettledAt } returns null
            every { execution.physicalOutcome } returns PhysicalOutcome("terminal", PhysicalOutcomeKind.FAILED, physicalCode, message, null)
            val repository = mockk<ExploreRunRepository>()
            every { repository.lockRunForOwnerMutation(run.id) } returns run
            val receipt = slot<ExploreTerminalReceipt>()
            val completedRun = slot<ExploreRun>()
            every { repository.commitTerminalReceipt(capture(completedRun), capture(receipt), 0) } returns true
            val transactions = object : ActiveExploreOwnerSettlementTransactionPort {
                override fun <T> inActiveOwnerSettlementTransaction(block: () -> T): T = block()
            }
            val adapter = ExploreSettlementAdapter(repository, transactions, Duration.ofHours(1))
            assertEquals(
                ExecutionRunOwnerSettlementPort.SettlementOutcome.Completed,
                adapter.settleInOwnerTransaction(ExecutionRunOwnerSettlementPort.SettleCommand(execution, now)),
            )
            assertEquals(expectedCode, receipt.captured.failureCode)
            assertEquals(expectedMessage, receipt.captured.failureMessage)
            assertEquals(ExploreRunState.FAILED, completedRun.captured.asView().state)
            assertEquals(expectedCode, completedRun.captured.asView().failureCode)
            assertEquals(expectedMessage, completedRun.captured.asView().failureDetail)
        }
    }

    @Test
    fun `overlong physical failure detail uses the stable fallback`() {
        assertEquals(
            "Explore query execution failed",
            safeExploreFailureDetail(
                "x".repeat(MAX_EXPLORE_FAILURE_DETAIL_LENGTH + 1),
                "Explore query execution failed",
            ),
        )
    }

    @Test
    fun `nonblank bounded physical failure detail is preserved`() {
        assertEquals(
            "Warehouse query failed.",
            safeExploreFailureDetail("Warehouse query failed.", "fallback"),
        )
    }

    @Test
    fun `unsupported result type falls back when safe detail is unavailable or overlong`() {
        val fallback = "The query result contains a type that cannot be returned. Cast the column to a supported type."
        listOf(null, " ", "x".repeat(MAX_EXPLORE_FAILURE_DETAIL_LENGTH + 1)).forEach { detail ->
            assertEquals(fallback, exploreExecutionFailureDetail("UNSUPPORTED_RESULT_TYPE", detail))
        }
    }
}
