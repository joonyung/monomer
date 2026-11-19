@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.explore.application

import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ExecutableRequestKind
import cc.monomer.execution.api.ExecutionRunAdmissionPort
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.ResultSinkBindingCanonicalizer
import cc.monomer.execution.api.Sha256Digest
import cc.monomer.explore.api.ExploreDigest
import cc.monomer.explore.api.ExploreExecutionReservation
import cc.monomer.explore.api.StartSemanticExploreRunCommand
import cc.monomer.explore.api.StartExploreRunOutcome
import cc.monomer.explore.api.StartSqlExploreRunCommand
import cc.monomer.explore.domain.ExploreRun
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/** Persists an Explore variant and reserves its technical ExecutionRun atomically. */
class ExploreAdmissionCoordinator(
    private val initialAdmissionTransactionPort: ExploreInitialAdmissionTransactionPort,
    private val executionRunAdmissionPort: ExecutionRunAdmissionPort,
    private val repository: ExploreRunRepository,
) {
    suspend fun admit(command: StartSqlExploreRunCommand): StartExploreRunOutcome =
        admit(
            command = command.asAdmissionCommand(),
            run = { admittedAt -> ExploreRun.admit(command, admittedAt) },
            matchesReplay = { existing -> existing.replays(command) },
        )

    suspend fun admit(command: StartSemanticExploreRunCommand): StartExploreRunOutcome =
        admit(
            command = command.asAdmissionCommand(),
            run = { admittedAt -> ExploreRun.admit(command, admittedAt) },
            matchesReplay = { existing -> existing.replays(command) },
        )

    private suspend fun admit(
        command: ExploreAdmissionCommand,
        run: (Instant) -> ExploreRun,
        matchesReplay: (ExploreRun) -> Boolean,
    ): StartExploreRunOutcome = try {
        initialAdmissionTransactionPort.inInitialAdmissionTransaction {
            repository.lockAdmissionKey(command.workspaceId, command.idempotencyKey)
            val existing = repository.findByAdmissionKey(command.workspaceId, command.idempotencyKey)
            if (existing != null) {
                return@inInitialAdmissionTransaction if (matchesReplay(existing)) {
                    StartExploreRunOutcome.Existing(existing.asView())
                } else {
                    StartExploreRunOutcome.IdempotencyConflict
                }
            }

            val admittedAt = command.requestedAt.postgresPersistenceTime()
            when (val outcome = executionRunAdmissionPort.reserveInOwnerTransaction(command.executionRunAdmission(admittedAt))) {
                is ExecutionRunAdmissionPort.ReserveOutcome.Reserved -> {
                    if (!outcome.snapshot.matchesNewAdmission(command, admittedAt)) {
                        rollback(command, ExploreInitialAdmissionRollbackReason.EXECUTION_BINDING_MISMATCH)
                    }
                }
                is ExecutionRunAdmissionPort.ReserveOutcome.Existing -> {
                    if (!outcome.snapshot.matchesAdmissionBinding(command, admittedAt)) {
                        rollback(command, ExploreInitialAdmissionRollbackReason.EXECUTION_BINDING_MISMATCH)
                    }
                    rollback(command, ExploreInitialAdmissionRollbackReason.EXECUTION_ALREADY_RESERVED)
                }
                ExecutionRunAdmissionPort.ReserveOutcome.IdempotencyConflict ->
                    rollback(command, ExploreInitialAdmissionRollbackReason.EXECUTION_IDEMPOTENCY_CONFLICT)
                ExecutionRunAdmissionPort.ReserveOutcome.Backpressured ->
                    rollback(command, ExploreInitialAdmissionRollbackReason.EXECUTION_BACKPRESSURED)
                ExecutionRunAdmissionPort.ReserveOutcome.InvalidDispatchTemplate ->
                    rollback(command, ExploreInitialAdmissionRollbackReason.EXECUTION_DISPATCH_TEMPLATE_INVALID)
            }

            val admittedRun = run(admittedAt)
            if (!repository.insertAdmittedRun(admittedRun)) {
                rollback(command, ExploreInitialAdmissionRollbackReason.RUN_INSERT_CONFLICT)
            }
            StartExploreRunOutcome.Started(admittedRun.asView())
        }
    } catch (rollback: ExploreInitialAdmissionRollbackException) {
        if (rollback.reason == ExploreInitialAdmissionRollbackReason.EXECUTION_BACKPRESSURED) {
            StartExploreRunOutcome.Backpressured
        } else throw rollback
    }
}

private interface ExploreAdmissionCommand {
    val runId: cc.monomer.explore.api.ExploreRunId
    val workspaceId: cc.monomer.identity.api.WorkspaceId
    val idempotencyKey: String
    val requestedAt: Instant
    val ownerPrincipalId: cc.monomer.identity.api.PrincipalId
    val connectorBinding: cc.monomer.explore.api.ExploreConnectorBinding
    val requestDigest: ExploreDigest
    val executionReservation: ExploreExecutionReservation
    val executionRunId: cc.monomer.execution.api.ExecutionRunId
}

private fun StartSqlExploreRunCommand.asAdmissionCommand(): ExploreAdmissionCommand {
    val source = this
    return object : ExploreAdmissionCommand {
        override val runId = source.runId
        override val workspaceId = source.connectorBinding.workspaceId
        override val idempotencyKey = source.idempotencyKey
        override val requestedAt = source.requestedAt
        override val ownerPrincipalId = source.ownerPrincipalId
        override val connectorBinding = source.connectorBinding
        override val requestDigest = source.requestDigest
        override val executionReservation = source.executionReservation
        override val executionRunId = source.executionRunId
    }
}

private fun StartSemanticExploreRunCommand.asAdmissionCommand(): ExploreAdmissionCommand {
    val source = this
    return object : ExploreAdmissionCommand {
        override val runId = source.runId
        override val workspaceId = source.connectorBinding.workspaceId
        override val idempotencyKey = source.idempotencyKey
        override val requestedAt = source.requestedAt
        override val ownerPrincipalId = source.ownerPrincipalId
        override val connectorBinding = source.connectorBinding
        override val requestDigest = source.requestDigest
        override val executionReservation = source.executionReservation
        override val executionRunId = source.executionRunId
    }
}

private fun ExploreAdmissionCommand.executionRunAdmission(
    admittedAt: Instant,
): ExecutionRunAdmissionPort.ReserveCommand =
    ExecutionRunAdmissionPort.ReserveCommand(
        runId = executionRunId,
        owner = cc.monomer.execution.api.ExecutionOwnerReference(
            type = cc.monomer.execution.api.ExecutionOwnerType.EXPLORE,
            id = cc.monomer.execution.api.ExecutionOwnerId(runId.value),
        ),
        workspaceId = connectorBinding.workspaceId,
        idempotencyKey = idempotencyKey,
        requestFingerprint = executionReservation.executionRequestDigest.toExecutionDigest(),
        connectorId = ConnectorReference(connectorBinding.connectorId.value),
        connectorType = connectorBinding.connectorType,
        connectorProperties = executionReservation.connectorProperties,
        requestKind = ExecutableRequestKind.QUERY,
        runtimeBindings = executionReservation.runtimeBindings,
        assignmentTemplate = executionReservation.assignmentTemplate,
        dispatchIntentExpiresAt = executionReservation.dispatchIntentExpiresAt,
        deadline = executionReservation.deadline,
        reservedAt = admittedAt,
    )

internal fun ExploreRun.replays(command: StartSqlExploreRunCommand): Boolean =
    kind == ExploreRun.Kind.Sql &&
        ownerPrincipalId == command.ownerPrincipalId &&
        connectorBinding == command.connectorBinding &&
        requestDigest == command.requestDigest

internal fun ExploreRun.replays(command: StartSemanticExploreRunCommand): Boolean =
    kind is ExploreRun.Kind.Semantic &&
        ownerPrincipalId == command.ownerPrincipalId &&
        connectorBinding == command.connectorBinding &&
        requestDigest == command.requestDigest &&
        kind.query == command.query

private fun ExecutionRunSnapshot.matchesNewAdmission(
    command: ExploreAdmissionCommand,
    admittedAt: Instant,
): Boolean =
    matchesAdmissionBinding(command, admittedAt) &&
        state == cc.monomer.execution.api.ExecutionRunState.RUNNING &&
        updatedAt == admittedAt

private fun ExecutionRunSnapshot.matchesAdmissionBinding(
    command: ExploreAdmissionCommand,
    admittedAt: Instant,
): Boolean {
    val reserve = command.executionReservation
    val canonicalResultSink = reserve.runtimeBindings.resultSink.copy(
        expiresAt = reserve.runtimeBindings.resultSink.expiresAt?.postgresPersistenceTime(),
    )
    val canonicalRuntimeBindings = reserve.runtimeBindings.copy(
        resultSink = canonicalResultSink.copy(
            bindingDigest = ResultSinkBindingCanonicalizer.digest(canonicalResultSink),
        ),
    )
    return id == command.executionRunId &&
        workspaceId == command.workspaceId &&
        connectorId == ConnectorReference(command.connectorBinding.connectorId.value) &&
        connectorType == command.connectorBinding.connectorType &&
        requestKind == ExecutableRequestKind.QUERY &&
        canonicalRequestDigest == reserve.executionRequestDigest.toExecutionDigest() &&
        runtimeBindings == canonicalRuntimeBindings &&
        deadline == reserve.deadline.postgresPersistenceTime() &&
        createdAt == admittedAt
}

private fun ExploreDigest.toExecutionDigest(): Sha256Digest =
    Sha256Digest(value.removePrefix("sha256:"))

private fun Instant.postgresPersistenceTime(): Instant = truncatedTo(ChronoUnit.MICROS)

class ExploreInitialAdmissionRollbackException(
    val reason: ExploreInitialAdmissionRollbackReason,
) : RuntimeException("Explore initial admission must roll back: $reason")

/**
 * Why an initial admission rolled back. A replay of the same key is answered earlier from the Explore run
 * under the admission-key lock, and every admission reserves a fresh run id, so every reason except
 * backpressure means corrupt state or a Control defect and must not read as a request conflict.
 */
enum class ExploreInitialAdmissionRollbackReason {
    EXECUTION_BINDING_MISMATCH,
    EXECUTION_ALREADY_RESERVED,
    EXECUTION_IDEMPOTENCY_CONFLICT,
    EXECUTION_BACKPRESSURED,
    EXECUTION_DISPATCH_TEMPLATE_INVALID,
    RUN_INSERT_CONFLICT,
}

private fun rollback(command: ExploreAdmissionCommand, reason: ExploreInitialAdmissionRollbackReason): Nothing {
    val event = if (reason == ExploreInitialAdmissionRollbackReason.EXECUTION_BACKPRESSURED) {
        logger.atWarn()
    } else {
        logger.atError()
    }
    event.addKeyValue("event", "explore_initial_admission_rolled_back")
        .addKeyValue("reason", reason.name)
        .addKeyValue("workspace_id", command.workspaceId.toString())
        .let { if (MDC.get("run_id") == null) it.addKeyValue("run_id", command.runId.toString()) else it }
        .log("explore_initial_admission_rolled_back")
    throw ExploreInitialAdmissionRollbackException(reason)
}

private val logger = LoggerFactory.getLogger(ExploreAdmissionCoordinator::class.java)
