package cc.monomer.execution.api

import java.time.Instant

object RunDispatchLimits {
    const val MAXIMUM_PAYLOAD_BYTES = 2 * 1024 * 1024
}

data class RunDispatchIntent(
    val runId: ExecutionRunId,
    val templateDigest: Sha256Digest,
    val assignmentTemplate: BinaryValue,
    val expiresAt: Instant,
    val persistedAt: Instant,
) {
    init {
        require(assignmentTemplate.size in 1..RunDispatchLimits.MAXIMUM_PAYLOAD_BYTES)
        require(expiresAt.isAfter(persistedAt))
    }

    override fun toString(): String =
        "RunDispatchIntent(runId=$runId, templateDigest=$templateDigest, " +
            "assignmentTemplate=[REDACTED], expiresAt=$expiresAt, persistedAt=$persistedAt)"
}

interface RunDispatchIntentPort {
    fun persist(intent: RunDispatchIntent): RunDispatchIntentPersistenceOutcome

    fun find(runId: ExecutionRunId): RunDispatchIntent?

    /** One entry per stored intent; a row that violates the intent invariants is reported, not thrown. */
    fun findAll(runIds: Set<ExecutionRunId>): List<StoredDispatchIntentRead>
}

sealed interface StoredDispatchIntentRead {
    val runId: ExecutionRunId

    data class Readable(val intent: RunDispatchIntent) : StoredDispatchIntentRead {
        override val runId: ExecutionRunId get() = intent.runId
    }

    data class Unreadable(override val runId: ExecutionRunId) : StoredDispatchIntentRead
}

enum class RunDispatchIntentPersistenceOutcome {
    PERSISTED,
    EXACT_DUPLICATE,
    RUN_NOT_FOUND,
    BINDING_MISMATCH,
    CONFLICT,
}
