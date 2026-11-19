@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.execution

import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.ExecutableRequestKind
import cc.monomer.execution.api.ExecutionOwnerId
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunOwnerSettlementPort
import cc.monomer.execution.api.ExecutionRunSettlementUseCase
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.ExecutionRunState
import cc.monomer.execution.api.PhysicalOutcome
import cc.monomer.execution.api.PhysicalOutcomeKind
import cc.monomer.execution.api.ResultSinkBinding
import cc.monomer.execution.api.ResultSinkKind
import cc.monomer.execution.api.RunRuntimeBindings
import cc.monomer.execution.api.Sha256Digest
import cc.monomer.execution.application.AdmittedExecutionRun
import cc.monomer.execution.application.ExecutionRunRepository
import cc.monomer.execution.application.ExecutionRunSettlementCoordinator
import cc.monomer.execution.application.ExecutionRuntimeTransactionRunner
import cc.monomer.execution.application.RunInsertOutcome
import cc.monomer.execution.application.StoredExecutionRunRead
import cc.monomer.identity.api.WorkspaceId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking

class ExecutionRunSettlementCoordinatorTest {
    private val terminalAt = Instant.parse("2026-10-11T00:00:05Z")

    @Test
    fun `a settlement clock behind the terminal record settles the owner and the run at the terminal time`() = runBlocking {
        val run = terminalRun()
        val repository = SettlementRepository(run)
        val owner = RecordingOwner(ExecutionRunOwnerSettlementPort.SettlementOutcome.Completed)
        val transactions = RecordingTransactions()

        val outcome = ExecutionRunSettlementCoordinator(listOf(owner), repository, transactions)
            .settle(run.id, terminalAt.minusSeconds(1))

        val completed = assertIs<ExecutionRunSettlementUseCase.SettlementOutcome.Completed>(outcome)
        assertEquals(terminalAt, completed.snapshot.ownerSettledAt)
        assertEquals(terminalAt, completed.snapshot.updatedAt)
        assertEquals(listOf(terminalAt), owner.settledAt)
        assertEquals(terminalAt, repository.stored.ownerSettledAt)
        assertFalse(transactions.rolledBack)
    }

    @Test
    fun `an owner conflict aborts the owner transaction instead of committing a half settlement`() = runBlocking {
        val run = terminalRun()
        val repository = SettlementRepository(run)
        val owner = RecordingOwner(ExecutionRunOwnerSettlementPort.SettlementOutcome.Conflict)
        val transactions = RecordingTransactions()

        val outcome = ExecutionRunSettlementCoordinator(listOf(owner), repository, transactions)
            .settle(run.id, terminalAt.plusSeconds(1))

        assertEquals(ExecutionRunSettlementUseCase.SettlementOutcome.Conflict, outcome)
        assertEquals(listOf(terminalAt.plusSeconds(1)), owner.settledAt)
        assertTrue(transactions.rolledBack)
        assertNull(repository.stored.ownerSettledAt)
    }

    private class RecordingOwner(
        private val outcome: ExecutionRunOwnerSettlementPort.SettlementOutcome,
    ) : ExecutionRunOwnerSettlementPort {
        override val ownerType = ExecutionOwnerType.EXPLORE
        val settledAt = mutableListOf<Instant>()

        override fun settleInOwnerTransaction(
            command: ExecutionRunOwnerSettlementPort.SettleCommand,
        ): ExecutionRunOwnerSettlementPort.SettlementOutcome {
            settledAt += command.settledAt
            return outcome
        }
    }

    private class RecordingTransactions : ExecutionRuntimeTransactionRunner {
        var rolledBack = false
            private set

        override suspend fun <T> run(block: () -> T): T =
            try {
                block()
            } catch (failure: RuntimeException) {
                rolledBack = true
                throw failure
            }
    }

    private class SettlementRepository(var stored: ExecutionRunSnapshot) : ExecutionRunRepository {
        override fun requireAmbientTransaction() = Unit
        override fun find(runId: ExecutionRunId) = AdmittedExecutionRun(stored, "request", stored.canonicalRequestDigest)
        override fun compareAndSet(expectedVersion: Long, snapshot: ExecutionRunSnapshot): Boolean {
            if (stored.version != expectedVersion) return false
            stored = snapshot
            return true
        }

        override fun findAll(runIds: Set<ExecutionRunId>): List<StoredExecutionRunRead> = error("unexpected read")
        override fun findForUpdate(runId: ExecutionRunId): AdmittedExecutionRun? = error("unexpected write")
        override fun findByIdempotencyKey(owner: ExecutionOwnerReference, idempotencyKey: String): AdmittedExecutionRun? = error("unexpected read")
        override fun lockWorkspaceRunAdmission(workspaceId: WorkspaceId) = error("unexpected write")
        override fun countExecutionOccupancy(workspaceId: WorkspaceId): Int = error("unexpected read")
        override fun findOccupancyRecoveryCandidates(observedAt: Instant, staleAfter: Instant, maximumRuns: Int): List<StoredExecutionRunRead> = error("unexpected read")
        override fun countNonTerminalRuns(workspaceId: WorkspaceId): Int = error("unexpected read")
        override fun insert(run: AdmittedExecutionRun): RunInsertOutcome = error("unexpected write")
        override fun findOrphanCandidates(observedAt: Instant, staleAfter: Instant, maximumRuns: Int): List<StoredExecutionRunRead> = error("unexpected read")
        override fun findDispatchDeadlineCandidates(observedAt: Instant, maximumRuns: Int): List<StoredExecutionRunRead> = error("unexpected read")
        override fun findExecutionDeadlineCandidates(observedAt: Instant, maximumRuns: Int): List<StoredExecutionRunRead> = error("unexpected read")
        override fun findArtifactCleanupCandidates(observedAt: Instant, maximumRuns: Int): List<StoredExecutionRunRead> = error("unexpected read")
        override fun beginArtifactCleanup(runId: ExecutionRunId, expectedVersion: Long, attemptedAt: Instant): Boolean = error("unexpected write")
        override fun completeArtifactCleanup(runId: ExecutionRunId, expectedVersion: Long, completedAt: Instant): Boolean = error("unexpected write")
        override fun invalidateArtifactCleanup(runId: ExecutionRunId, expectedVersion: Long): Boolean = error("unexpected write")
        override fun findOwnerSettlementCandidates(ownerTypes: Set<ExecutionOwnerType>, maximumRuns: Int): List<StoredExecutionRunRead> = error("unexpected read")
    }

    private fun terminalRun(): ExecutionRunSnapshot {
        val createdAt = terminalAt.minusSeconds(5)
        val deadline = createdAt.plusSeconds(3600)
        val id = ExecutionRunId(Uuid.generateV7())
        return ExecutionRunSnapshot(
            id = id,
            owner = ExecutionOwnerReference(ExecutionOwnerType.EXPLORE, ExecutionOwnerId(Uuid.generateV7())),
            workspaceId = WorkspaceId(Uuid.generateV7()),
            connectorId = ConnectorReference(Uuid.generateV7()),
            connectorType = ConnectorType.POSTGRESQL,
            connectorProperties = mapOf("host" to ConnectorRuntimePropertyValue.Text("localhost")),
            requestKind = ExecutableRequestKind.QUERY,
            canonicalRequestDigest = Sha256Digest("a".repeat(64)),
            runtimeBindings = RunRuntimeBindings(
                connectorBindingDigest = Sha256Digest("b".repeat(64)),
                credentialVersion = null,
                credentialKeyVersion = "local-primary",
                credentialBindingDigest = Sha256Digest("c".repeat(64)),
                resultSink = ResultSinkBinding(
                    schemaVersion = 2u,
                    kind = ResultSinkKind.INLINE_STREAM,
                    objectKey = null,
                    maximumArtifactRows = null,
                    maximumArtifactBytes = null,
                    expiresAt = null,
                    maximumRecordBatchRows = 128,
                    maximumFrameBytes = 1_024,
                    maximumCellBytes = 1_024,
                    maximumMultipartPartBytes = null,
                    bindingDigest = Sha256Digest("d".repeat(64)),
                ),
            ),
            executionOccupancyAcquiredAt = null,
            executionOccupancyReleasedAt = null,
            dispatchDeadlineAt = createdAt.plusSeconds(10),
            deadline = deadline,
            state = ExecutionRunState.FAILED,
            physicalOutcome = PhysicalOutcome("$id:terminal", PhysicalOutcomeKind.FAILED, "PERMISSION_DENIED", "Denied"),
            cancelIntent = null,
            ownerSettledAt = null,
            ownerDiscardedAt = null,
            createdAt = createdAt,
            updatedAt = terminalAt,
            version = 3,
        )
    }
}
