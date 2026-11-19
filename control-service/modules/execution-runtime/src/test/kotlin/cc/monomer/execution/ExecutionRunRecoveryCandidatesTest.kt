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
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking

class ExecutionRunRecoveryCandidatesTest {
    private val observedAt = Instant.parse("2026-10-11T00:00:00Z")

    @Test
    fun `unreadable candidate rows are reported once and skipped while readable candidates still recover`() = runBlocking {
        val readable = snapshot()
        val unreadable = ExecutionRunId(Uuid.generateV7())
        val repository = CandidateRepository(
            orphan = listOf(StoredExecutionRunRead.Unreadable(unreadable), readable.stored()),
            occupancy = listOf(StoredExecutionRunRead.Unreadable(unreadable)),
        )

        val batch = service(repository).recover(command(maximumRuns = 2))

        assertEquals(listOf(readable), batch.orphanCandidates)
        assertEquals(emptyList(), batch.occupancyRecoveryCandidates)
        assertEquals(listOf(unreadable), batch.unreadableRunIds)
        assertFalse(batch.orphanTruncated)
    }

    @Test
    fun `an unreadable row counts toward the category window and truncation`() = runBlocking {
        val first = snapshot()
        val unreadable = ExecutionRunId(Uuid.generateV7())
        val repository = CandidateRepository(
            ownerSettlement = listOf(first.stored(), StoredExecutionRunRead.Unreadable(unreadable), snapshot().stored()),
        )

        val batch = service(repository).recover(command(maximumRuns = 2))

        assertEquals(listOf(first), batch.ownerSettlementCandidates)
        assertEquals(listOf(unreadable), batch.unreadableRunIds)
        assertTrue(batch.ownerSettlementTruncated)
        assertEquals(3, repository.requestedOwnerSettlementRuns)
    }

    private fun command(maximumRuns: Int) = ExecutionRunUseCase.RecoverRunsCommand(
        observedAt = observedAt,
        orphanTimeout = Duration.ofSeconds(90),
        maximumOrphanRuns = maximumRuns,
        maximumOwnerSettlementRuns = maximumRuns,
        ownerSettlementTypes = setOf(ExecutionOwnerType.EXPLORE),
    )

    private fun service(repository: ExecutionRunRepository): ExecutionRunService {
        val directRunner = object : ExecutionRuntimeTransactionRunner {
            override suspend fun <T> run(block: () -> T): T = block()
        }
        return ExecutionRunService(
            repository = repository,
            mutationTransactionRunner = directRunner,
            readTransactionRunner = directRunner,
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
    }

    private fun ExecutionRunSnapshot.stored(): StoredExecutionRunRead =
        StoredExecutionRunRead.Readable(AdmittedExecutionRun(this, "request-$id", canonicalRequestDigest))

    private fun snapshot(): ExecutionRunSnapshot {
        val createdAt = observedAt.minusSeconds(600)
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
                    kind = ResultSinkKind.ARTIFACT_UPLOAD,
                    objectKey = "artifacts/test/result.arrow",
                    maximumArtifactRows = 1_024u,
                    maximumArtifactBytes = 1_024u,
                    expiresAt = deadline,
                    maximumRecordBatchRows = 128,
                    maximumFrameBytes = 1_024,
                    maximumCellBytes = 1_024,
                    maximumMultipartPartBytes = 1_024,
                    bindingDigest = Sha256Digest("d".repeat(64)),
                ),
            ),
            executionOccupancyAcquiredAt = null,
            executionOccupancyReleasedAt = null,
            dispatchDeadlineAt = createdAt.plusSeconds(10),
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

    private class CandidateRepository(
        private val orphan: List<StoredExecutionRunRead> = emptyList(),
        private val occupancy: List<StoredExecutionRunRead> = emptyList(),
        private val ownerSettlement: List<StoredExecutionRunRead> = emptyList(),
    ) : ExecutionRunRepository {
        var requestedOwnerSettlementRuns = 0
            private set

        override fun findOrphanCandidates(observedAt: Instant, staleAfter: Instant, maximumRuns: Int) = orphan.take(maximumRuns)
        override fun findOccupancyRecoveryCandidates(observedAt: Instant, staleAfter: Instant, maximumRuns: Int) = occupancy.take(maximumRuns)
        override fun findDispatchDeadlineCandidates(observedAt: Instant, maximumRuns: Int) = emptyList<StoredExecutionRunRead>()
        override fun findExecutionDeadlineCandidates(observedAt: Instant, maximumRuns: Int) = emptyList<StoredExecutionRunRead>()
        override fun findArtifactCleanupCandidates(observedAt: Instant, maximumRuns: Int) = emptyList<StoredExecutionRunRead>()
        override fun findOwnerSettlementCandidates(ownerTypes: Set<ExecutionOwnerType>, maximumRuns: Int): List<StoredExecutionRunRead> {
            requestedOwnerSettlementRuns = maximumRuns
            return ownerSettlement.take(maximumRuns)
        }

        override fun requireAmbientTransaction() = error("unexpected transaction check")
        override fun find(runId: ExecutionRunId): AdmittedExecutionRun? = error("unexpected single read")
        override fun findAll(runIds: Set<ExecutionRunId>): List<StoredExecutionRunRead> = error("unexpected batch read")
        override fun findForUpdate(runId: ExecutionRunId): AdmittedExecutionRun? = error("unexpected write")
        override fun findByIdempotencyKey(owner: ExecutionOwnerReference, idempotencyKey: String): AdmittedExecutionRun? = error("unexpected read")
        override fun lockWorkspaceRunAdmission(workspaceId: WorkspaceId) = error("unexpected write")
        override fun countExecutionOccupancy(workspaceId: WorkspaceId): Int = error("unexpected read")
        override fun countNonTerminalRuns(workspaceId: WorkspaceId): Int = error("unexpected read")
        override fun insert(run: AdmittedExecutionRun): RunInsertOutcome = error("unexpected write")
        override fun compareAndSet(expectedVersion: Long, snapshot: ExecutionRunSnapshot): Boolean = error("unexpected write")
        override fun beginArtifactCleanup(runId: ExecutionRunId, expectedVersion: Long, attemptedAt: Instant): Boolean = error("unexpected write")
        override fun completeArtifactCleanup(runId: ExecutionRunId, expectedVersion: Long, completedAt: Instant): Boolean = error("unexpected write")
        override fun invalidateArtifactCleanup(runId: ExecutionRunId, expectedVersion: Long): Boolean = error("unexpected write")
    }
}
