@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.execution

import cc.monomer.execution.api.BinaryValue
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.ExecutableRequestKind
import cc.monomer.execution.api.ExecutionOwnerId
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.ExecutionRunState
import cc.monomer.execution.api.ExecutionRunUseCase
import cc.monomer.execution.api.ResultSinkBinding
import cc.monomer.execution.api.ResultSinkKind
import cc.monomer.execution.api.RunDispatchIntent
import cc.monomer.execution.api.RunDispatchIntentPersistenceOutcome
import cc.monomer.execution.api.RunDispatchIntentPort
import cc.monomer.execution.api.RunRuntimeBindings
import cc.monomer.execution.api.Sha256Digest
import cc.monomer.execution.api.StoredDispatchIntentRead
import cc.monomer.execution.application.AdmittedExecutionRun
import cc.monomer.execution.application.ExecutionRunRepository
import cc.monomer.execution.application.ExecutionRunService
import cc.monomer.execution.application.ExecutionRuntimeTransactionRunner
import cc.monomer.execution.application.RunDispatchAssignmentCodec
import cc.monomer.execution.application.RunInsertOutcome
import cc.monomer.execution.application.StoredExecutionRunRead
import cc.monomer.identity.api.WorkspaceId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking

class ExecutionRunServiceTransactionTest {
    private val createdAt = Instant.parse("2026-10-11T00:00:00Z")

    @Test
    fun `run mutations use the mutation transaction and reads use the read transaction`() = runBlocking {
        val run = snapshot()
        val repository = InMemoryRunRepository(run, occupied = 0)
        val transactions = mutableListOf<String>()
        val service = service(repository, transactions)

        service.claimDispatch(ExecutionRunUseCase.ClaimRunDispatchCommand(run.id, createdAt.plusSeconds(1), createdAt.plusSeconds(600)))
        service.heartbeat(ExecutionRunUseCase.HeartbeatCommand(run.id, createdAt.plusSeconds(2)))
        service.get(run.id)
        service.hasExecutionOccupancyCapacity(run.workspaceId)

        assertEquals(listOf("mutation", "mutation", "read", "read"), transactions)
        assertEquals(listOf(run.id, run.id), repository.lockedRuns)
    }

    @Test
    fun `a full Workspace returns an explicit occupancy outcome without changing the run`() = runBlocking {
        val claimed = snapshot().copy(dispatchClaimedAt = createdAt.plusSeconds(1), lastHeartbeatAt = createdAt.plusSeconds(1), version = 1)
        val repository = InMemoryRunRepository(claimed, occupied = 8)

        val outcome = service(repository, mutableListOf()).acquireExecutionOccupancy(
            ExecutionRunUseCase.AcquireExecutionOccupancyCommand(claimed.id, claimed.workspaceId, createdAt.plusSeconds(2)),
        )

        assertEquals(ExecutionRunUseCase.RunMutationOutcome.ExecutionOccupancyFull, outcome)
        assertEquals(claimed, repository.stored)

        repository.occupied = 7
        val acquired = assertIs<ExecutionRunUseCase.RunMutationOutcome.Applied>(
            service(repository, mutableListOf()).acquireExecutionOccupancy(
                ExecutionRunUseCase.AcquireExecutionOccupancyCommand(claimed.id, claimed.workspaceId, createdAt.plusSeconds(3)),
            ),
        )
        assertEquals(createdAt.plusSeconds(3), acquired.snapshot.executionOccupancyAcquiredAt)
    }

    private fun service(repository: ExecutionRunRepository, transactions: MutableList<String>) = ExecutionRunService(
        repository = repository,
        mutationTransactionRunner = RecordingRunner("mutation", transactions),
        readTransactionRunner = RecordingRunner("read", transactions),
        dispatchIntentPort = object : RunDispatchIntentPort {
            override fun persist(intent: RunDispatchIntent): RunDispatchIntentPersistenceOutcome = error("unexpected write")
            override fun find(runId: ExecutionRunId): RunDispatchIntent? = error("unexpected read")
            override fun findAll(runIds: Set<ExecutionRunId>): List<StoredDispatchIntentRead> = error("unexpected read")
        },
        assignmentCodec = object : RunDispatchAssignmentCodec {
            override fun canonicalizeTemplate(assignmentTemplate: BinaryValue): BinaryValue = error("unexpected codec use")
            override fun readSql(canonicalTemplate: BinaryValue, snapshot: ExecutionRunSnapshot): String? = error("unexpected codec use")
        },
    )

    private class RecordingRunner(
        private val name: String,
        private val transactions: MutableList<String>,
    ) : ExecutionRuntimeTransactionRunner {
        override suspend fun <T> run(block: () -> T): T {
            transactions += name
            return block()
        }
    }

    private class InMemoryRunRepository(var stored: ExecutionRunSnapshot, var occupied: Int) : ExecutionRunRepository {
        val lockedRuns = mutableListOf<ExecutionRunId>()

        override fun find(runId: ExecutionRunId) = AdmittedExecutionRun(stored, "request", stored.canonicalRequestDigest)
        override fun findForUpdate(runId: ExecutionRunId): AdmittedExecutionRun {
            lockedRuns += runId
            return AdmittedExecutionRun(stored, "request", stored.canonicalRequestDigest)
        }
        override fun compareAndSet(expectedVersion: Long, snapshot: ExecutionRunSnapshot): Boolean {
            if (stored.version != expectedVersion) return false
            stored = snapshot
            return true
        }
        override fun lockWorkspaceRunAdmission(workspaceId: WorkspaceId) = Unit
        override fun countExecutionOccupancy(workspaceId: WorkspaceId): Int = occupied

        override fun requireAmbientTransaction() = error("unexpected owner transaction")
        override fun findAll(runIds: Set<ExecutionRunId>): List<StoredExecutionRunRead> = error("unexpected read")
        override fun findByIdempotencyKey(owner: ExecutionOwnerReference, idempotencyKey: String): AdmittedExecutionRun? = error("unexpected read")
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

    private fun snapshot(): ExecutionRunSnapshot {
        val deadline = createdAt.plusSeconds(3600)
        return ExecutionRunSnapshot(
            id = ExecutionRunId(Uuid.generateV7()),
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
            dispatchDeadlineAt = createdAt.plusSeconds(600),
            deadline = deadline,
            state = ExecutionRunState.RUNNING,
            physicalOutcome = null,
            cancelIntent = null,
            ownerSettledAt = null,
            ownerDiscardedAt = null,
            createdAt = createdAt,
            updatedAt = createdAt,
            version = 0,
        )
    }
}
