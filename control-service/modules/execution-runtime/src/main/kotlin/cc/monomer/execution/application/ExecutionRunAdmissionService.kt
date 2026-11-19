package cc.monomer.execution.application

import cc.monomer.execution.api.BinaryValue
import cc.monomer.execution.api.ExecutionRunAdmissionPort
import cc.monomer.execution.api.ExecutionRunAdmissionRollbackException
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.ExecutionRunState
import cc.monomer.execution.api.ResultSinkBindingCanonicalizer
import cc.monomer.execution.api.RunDispatchIntent
import cc.monomer.execution.api.RunDispatchIntentPersistenceOutcome
import cc.monomer.execution.api.RunDispatchIntentPort
import cc.monomer.execution.api.Sha256Digest
import cc.monomer.execution.domain.ExecutionRun
import java.time.Instant
import java.time.temporal.ChronoUnit

data class ExecutionRunLimits(
    val maximumNonTerminalRunsPerWorkspace: Int,
) {
    init {
        require(maximumNonTerminalRunsPerWorkspace > 0)
    }
}

class ExecutionRunAdmissionService(
    private val repository: ExecutionRunRepository,
    private val dispatchIntentPort: RunDispatchIntentPort,
    private val assignmentCodec: RunDispatchAssignmentCodec,
    private val limits: ExecutionRunLimits,
) : ExecutionRunAdmissionPort {
    override fun lockWorkspaceInOwnerTransaction(workspaceId: cc.monomer.identity.api.WorkspaceId) {
        repository.requireAmbientTransaction()
        repository.lockWorkspaceRunAdmission(workspaceId)
    }

    override fun hasUnreleasedOccupancyInOwnerTransaction(workspaceId: cc.monomer.identity.api.WorkspaceId, runIds: Set<cc.monomer.execution.api.ExecutionRunId>): Boolean {
        repository.requireAmbientTransaction()
        return runIds.any { id ->
            repository.find(id)?.snapshot?.let { snapshot ->
                snapshot.workspaceId == workspaceId && snapshot.executionOccupancyAcquiredAt != null && snapshot.executionOccupancyReleasedAt == null
            } == true
        }
    }

    override fun reserveInOwnerTransaction(
        command: ExecutionRunAdmissionPort.ReserveCommand,
    ): ExecutionRunAdmissionPort.ReserveOutcome {
        repository.requireAmbientTransaction()
        val canonical = command.canonicalizeForPostgresPersistence()
        if (canonical.runtimeBindings.credentialVersion == null) {
            return ExecutionRunAdmissionPort.ReserveOutcome.InvalidDispatchTemplate
        }
        val canonicalTemplate = runCatching {
            assignmentCodec.canonicalizeTemplate(canonical.assignmentTemplate)
        }.getOrElse {
            return ExecutionRunAdmissionPort.ReserveOutcome.InvalidDispatchTemplate
        }
        val intent =
            RunDispatchIntent(
                runId = canonical.runId,
                templateDigest = sha256(canonicalTemplate),
                assignmentTemplate = canonicalTemplate,
                expiresAt = canonical.dispatchIntentExpiresAt,
                persistedAt = canonical.reservedAt,
            )
        repository.lockWorkspaceRunAdmission(canonical.workspaceId)
        val existing = repository.findByIdempotencyKey(canonical.owner, canonical.idempotencyKey)
        if (existing != null) {
            val existingIntent = dispatchIntentPort.find(existing.snapshot.id)
            return if (existing.matches(canonical) && existingIntent == intent) {
                ExecutionRunAdmissionPort.ReserveOutcome.Existing(existing.snapshot)
            } else {
                ExecutionRunAdmissionPort.ReserveOutcome.IdempotencyConflict
            }
        }
        if (
            repository.countNonTerminalRuns(canonical.workspaceId) >=
            limits.maximumNonTerminalRunsPerWorkspace
        ) {
            return ExecutionRunAdmissionPort.ReserveOutcome.Backpressured
        }
        val snapshot =
            ExecutionRunSnapshot(
                id = canonical.runId,
                owner = canonical.owner,
                workspaceId = canonical.workspaceId,
                connectorId = canonical.connectorId,
                connectorType = canonical.connectorType,
                connectorProperties = canonical.connectorProperties,
                requestKind = canonical.requestKind,
                canonicalRequestDigest = canonical.requestFingerprint,
                runtimeBindings = canonical.runtimeBindings,
                executionOccupancyAcquiredAt = null,
                executionOccupancyReleasedAt = null,
                dispatchDeadlineAt = canonical.dispatchIntentExpiresAt,
                deadline = canonical.deadline,
                state = ExecutionRunState.RUNNING,
                physicalOutcome = null,
                cancelIntent = null,
                ownerSettledAt = null,
            ownerDiscardedAt = null,
                createdAt = canonical.reservedAt,
                updatedAt = canonical.reservedAt,
                version = 0,
            )
        ExecutionRun.create(snapshot)
        when (
            repository.insert(
                AdmittedExecutionRun(
                    snapshot = snapshot,
                    idempotencyKey = canonical.idempotencyKey,
                    requestFingerprint = canonical.requestFingerprint,
                ),
            )
        ) {
            RunInsertOutcome.INSERTED -> Unit
            RunInsertOutcome.EXACT_DUPLICATE,
            RunInsertOutcome.CONFLICT,
            -> throw ExecutionRunAdmissionRollbackException(
                ExecutionRunAdmissionRollbackException.Reason.RUN_CONFLICT,
            )
        }
        when (dispatchIntentPort.persist(intent)) {
            RunDispatchIntentPersistenceOutcome.PERSISTED -> Unit
            else -> throw ExecutionRunAdmissionRollbackException(
                ExecutionRunAdmissionRollbackException.Reason.DISPATCH_INTENT_CONFLICT,
            )
        }
        return ExecutionRunAdmissionPort.ReserveOutcome.Reserved(snapshot)
    }
}

private fun AdmittedExecutionRun.matches(
    command: ExecutionRunAdmissionPort.ReserveCommand,
): Boolean =
    requestFingerprint == command.requestFingerprint &&
        snapshot.owner == command.owner &&
        snapshot.workspaceId == command.workspaceId &&
        snapshot.connectorId == command.connectorId &&
        snapshot.connectorType == command.connectorType &&
        snapshot.connectorProperties == command.connectorProperties &&
        snapshot.requestKind == command.requestKind &&
        snapshot.runtimeBindings == command.runtimeBindings &&
        snapshot.dispatchDeadlineAt == command.dispatchIntentExpiresAt &&
        snapshot.deadline == command.deadline

private fun ExecutionRunAdmissionPort.ReserveCommand.canonicalizeForPostgresPersistence():
    ExecutionRunAdmissionPort.ReserveCommand {
    val resultSink = runtimeBindings.resultSink.copy(
        expiresAt = runtimeBindings.resultSink.expiresAt?.postgresPersistenceTime(),
    )
    return copy(
        runtimeBindings = runtimeBindings.copy(
            resultSink = resultSink.copy(bindingDigest = ResultSinkBindingCanonicalizer.digest(resultSink)),
        ),
        dispatchIntentExpiresAt = dispatchIntentExpiresAt.postgresPersistenceTime(),
        deadline = deadline.postgresPersistenceTime(),
        reservedAt = reservedAt.postgresPersistenceTime(),
    )
}

private fun sha256(value: BinaryValue): Sha256Digest = Sha256Digest.of(value.copyBytes())

private fun Instant.postgresPersistenceTime(): Instant = truncatedTo(ChronoUnit.MICROS)
