package cc.monomer.execution.application

import cc.monomer.identity.api.WorkspaceId
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.Sha256Digest
import java.time.Instant

/** Runs blocking repository work in one transaction off the caller's thread. */
interface ExecutionRuntimeTransactionRunner {
    suspend fun <T> run(block: () -> T): T
}

class OwnerSettlementConcurrentConflictException : RuntimeException()

interface ExecutionRunRepository {
    fun requireAmbientTransaction()

    fun find(runId: ExecutionRunId): AdmittedExecutionRun?

    /** One entry per stored run; a row that violates the snapshot invariants is reported, not thrown. The candidate reads below follow the same rule. */
    fun findAll(runIds: Set<ExecutionRunId>): List<StoredExecutionRunRead>

    fun findForUpdate(runId: ExecutionRunId): AdmittedExecutionRun?

    fun findByIdempotencyKey(
        owner: ExecutionOwnerReference,
        idempotencyKey: String,
    ): AdmittedExecutionRun?

    fun lockWorkspaceRunAdmission(workspaceId: WorkspaceId)

    fun countExecutionOccupancy(workspaceId: WorkspaceId): Int

    fun findOccupancyRecoveryCandidates(observedAt: Instant, staleAfter: Instant, maximumRuns: Int): List<StoredExecutionRunRead>

    fun countNonTerminalRuns(workspaceId: WorkspaceId): Int

    fun insert(run: AdmittedExecutionRun): RunInsertOutcome

    fun compareAndSet(expectedVersion: Long, snapshot: ExecutionRunSnapshot): Boolean

    fun findOrphanCandidates(
        observedAt: Instant,
        staleAfter: Instant,
        maximumRuns: Int,
    ): List<StoredExecutionRunRead>

    fun findDispatchDeadlineCandidates(
        observedAt: Instant,
        maximumRuns: Int,
    ): List<StoredExecutionRunRead>

    fun findExecutionDeadlineCandidates(
        observedAt: Instant,
        maximumRuns: Int,
    ): List<StoredExecutionRunRead>

    fun findArtifactCleanupCandidates(observedAt: Instant, maximumRuns: Int): List<StoredExecutionRunRead>

    fun beginArtifactCleanup(runId: ExecutionRunId, expectedVersion: Long, attemptedAt: Instant): Boolean

    fun completeArtifactCleanup(runId: ExecutionRunId, expectedVersion: Long, completedAt: Instant): Boolean

    fun invalidateArtifactCleanup(runId: ExecutionRunId, expectedVersion: Long): Boolean

    fun findOwnerSettlementCandidates(
        ownerTypes: Set<ExecutionOwnerType>,
        maximumRuns: Int,
    ): List<StoredExecutionRunRead>
}

sealed interface StoredExecutionRunRead {
    val runId: ExecutionRunId

    data class Readable(val run: AdmittedExecutionRun) : StoredExecutionRunRead {
        override val runId: ExecutionRunId get() = run.snapshot.id
    }

    data class Unreadable(override val runId: ExecutionRunId) : StoredExecutionRunRead
}

data class AdmittedExecutionRun(
    val snapshot: ExecutionRunSnapshot,
    val idempotencyKey: String,
    val requestFingerprint: Sha256Digest,
)

enum class RunInsertOutcome {
    INSERTED,
    EXACT_DUPLICATE,
    CONFLICT,
}
