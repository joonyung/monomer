package cc.monomer.control

import cc.monomer.control.observability.addCorrelationId
import cc.monomer.execution.api.ExecutionRunUseCase
import cc.monomer.execution.api.ExecutionArtifactStore
import cc.monomer.execution.api.ResultSinkKind
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutionRunSettlementUseCase
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle

/**
 * A completed cycle read its candidates and attempted each one; a candidate failure is counted in
 * [failedCandidates] and [candidateFailureType] but does not make the cycle fail. [cycleFailureType]
 * names a cycle that could not complete (for example, the candidate query itself failed) and is
 * cleared by the next completed cycle.
 */
data class ExecutionRunRecoverySnapshot(
    val running: Boolean,
    val cycleInProgress: Boolean,
    val lastCycleStartedAt: Instant?,
    val lastCompletedCycleAt: Instant?,
    val cycleFailureType: String?,
    val failedCandidates: Int,
    val candidateFailureType: String?,
    val unreadableRuns: Int,
    val truncated: Boolean,
)

class ExecutionRunRecoveryLifecycle(
    private val executionRunUseCase: ExecutionRunUseCase,
    private val executionRunSettlementUseCase: ExecutionRunSettlementUseCase,
    private val executionArtifactStore: ExecutionArtifactStore,
    private val clock: Clock,
    private val limits: ControlExecutionLimitsProperties,
    private val ownerSettlementTypes: Set<ExecutionOwnerType>,
) : SmartLifecycle {
    private val executorReference = AtomicReference<ScheduledExecutorService?>()
    private val cycleInProgress = AtomicBoolean(false)
    private val lastCycleStartedAt = AtomicReference<Instant?>()
    private val lastCompletedCycleAt = AtomicReference<Instant?>()
    private val cycleFailureType = AtomicReference<String?>()
    private val failedCandidates = AtomicReference(0)
    private val candidateFailureType = AtomicReference<String?>()
    private val unreadableRuns = AtomicReference(0)
    private val lastTruncated = AtomicBoolean(false)

    override fun start() {
        if (executorReference.get() != null) {
            return
        }
        val executor =
            Executors.newSingleThreadScheduledExecutor { command ->
                Thread(command, "monomer-run-recovery").also { thread ->
                    thread.isDaemon = true
                }
            }
        if (!executorReference.compareAndSet(null, executor)) {
            executor.shutdownNow()
            return
        }
        executor.scheduleWithFixedDelay(
            ::runCycleSafely,
            0,
            limits.recoveryInterval.toMillis(),
            TimeUnit.MILLISECONDS,
        )
    }

    override fun stop() {
        executorReference.getAndSet(null)?.let { executor ->
            executor.shutdown()
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow()
                executor.awaitTermination(1, TimeUnit.SECONDS)
            }
        }
    }

    override fun isRunning(): Boolean =
        executorReference.get()?.let { executor ->
            !executor.isShutdown && !executor.isTerminated
        } == true

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MAX_VALUE - 200

    fun snapshot(): ExecutionRunRecoverySnapshot =
        ExecutionRunRecoverySnapshot(
            running = isRunning,
            cycleInProgress = cycleInProgress.get(),
            lastCycleStartedAt = lastCycleStartedAt.get(),
            lastCompletedCycleAt = lastCompletedCycleAt.get(),
            cycleFailureType = cycleFailureType.get(),
            failedCandidates = failedCandidates.get(),
            candidateFailureType = candidateFailureType.get(),
            unreadableRuns = unreadableRuns.get(),
            truncated = lastTruncated.get(),
        )

    private fun runCycleSafely() {
        if (!cycleInProgress.compareAndSet(false, true)) {
            return
        }
        val startedAt = clock.instant()
        lastCycleStartedAt.set(startedAt)
        try {
            val candidateFailures = mutableListOf<Throwable>()
            runBlocking {
                val batch =
                    executionRunUseCase.recover(
                        ExecutionRunUseCase.RecoverRunsCommand(
                            observedAt = startedAt,
                            orphanTimeout = limits.orphanTimeout,
                            maximumOrphanRuns = limits.maximumOrphanRunsPerCycle,
                            maximumOwnerSettlementRuns =
                                limits.maximumOwnerSettlementRunsPerCycle,
                            ownerSettlementTypes = ownerSettlementTypes,
                        ),
                    )
                lastTruncated.set(
                    batch.dispatchDeadlineTruncated ||
                        batch.executionDeadlineTruncated ||
                        batch.orphanTruncated ||
                        batch.occupancyRecoveryTruncated ||
                        batch.ownerSettlementTruncated ||
                        batch.artifactCleanupTruncated,
                )
                unreadableRuns.set(batch.unreadableRunIds.size)
                batch.unreadableRunIds.forEach { runId ->
                    recoveryLogger.atError()
                        .addKeyValue("event", "execution_run_recovery_unreadable")
                        .addCorrelationId("run_id", runId.toString())
                        .log("execution_run_recovery_unreadable")
                }
                val recoveryCandidates =
                    (batch.dispatchDeadlineCandidates + batch.executionDeadlineCandidates +
                        batch.orphanCandidates + batch.occupancyRecoveryCandidates).distinctBy { it.id }
                recoveryCandidates.forEach { run ->
                    try {
                        when (
                            executionRunUseCase.recoverExecutionOccupancy(
                                ExecutionRunUseCase.RecoverExecutionOccupancyCommand(
                                    runId = run.id,
                                    observedAt = startedAt,
                                    orphanTimeout = limits.orphanTimeout,
                                ),
                            )
                        ) {
                            is ExecutionRunUseCase.RunMutationOutcome.Applied,
                            is ExecutionRunUseCase.RunMutationOutcome.Duplicate,
                            ExecutionRunUseCase.RunMutationOutcome.ConcurrentConflict,
                            -> Unit
                            else -> error("execution occupancy recovery failed")
                        }
                    } catch (failure: Throwable) {
                        recoveryLogger.atError()
                            .addKeyValue("event", "execution_run_recovery_failed")
                            .addCorrelationId("run_id", run.id.toString())
                            .addKeyValue("stage", "EXECUTION_OCCUPANCY")
                            .setCause(failure)
                            .addKeyValue("failure_types", failure.causeTypeChain())
                            .log("execution_run_recovery_failed")
                        candidateFailures += failure
                    }
                }
                batch.ownerSettlementCandidates.forEach { run ->
                    var stage = "OWNER_SETTLEMENT_PREPARE"
                    try {
                        stage = "OWNER_SETTLEMENT"
                        when (
                            val outcome =
                                executionRunSettlementUseCase.settle(run.id, startedAt)
                        ) {
                            is ExecutionRunSettlementUseCase.SettlementOutcome.Completed,
                            is ExecutionRunSettlementUseCase.SettlementOutcome.Duplicate,
                            ExecutionRunSettlementUseCase.SettlementOutcome.ConcurrentConflict,
                            -> Unit
                            else -> {
                                stage = "OWNER_SETTLEMENT_${outcome::class.simpleName}"
                                error("owner settlement failed")
                            }
                        }
                    } catch (failure: Throwable) {
                        recoveryLogger.atError()
                            .addKeyValue("event", "execution_run_recovery_failed")
                            .addCorrelationId("run_id", run.id.toString())
                            .addKeyValue("stage", stage)
                            .setCause(failure)
                            .addKeyValue("failure_types", failure.causeTypeChain())
                            .log("execution_run_recovery_failed")
                        candidateFailures += failure
                    }
                }
                batch.artifactCleanupCandidates.forEach { run ->
                    try {
                        check(run.state in setOf(cc.monomer.execution.api.ExecutionRunState.FAILED, cc.monomer.execution.api.ExecutionRunState.CANCELLED) ||
                            (run.state == cc.monomer.execution.api.ExecutionRunState.SUCCEEDED && run.ownerDiscardedAt != null))
                        check(run.runtimeBindings.resultSink.kind == ResultSinkKind.ARTIFACT_UPLOAD)
                        if (!executionRunUseCase.beginArtifactCleanup(
                            ExecutionRunUseCase.BeginArtifactCleanupCommand(run.id, run.version, clock.instant()),
                        )) return@forEach
                        executionArtifactStore.delete(requireNotNull(run.runtimeBindings.resultSink.objectKey))
                        executionRunUseCase.completeArtifactCleanup(
                            ExecutionRunUseCase.CompleteArtifactCleanupCommand(run.id, run.version + 1, clock.instant()),
                        )
                    } catch (failure: Throwable) {
                        recoveryLogger.atError()
                            .addKeyValue("event", "execution_run_recovery_failed")
                            .addCorrelationId("run_id", run.id.toString())
                            .addKeyValue("stage", "ARTIFACT_CLEANUP")
                            .setCause(failure)
                            .addKeyValue("failure_types", failure.causeTypeChain())
                            .log("execution_run_recovery_failed")
                        candidateFailures += failure
                    }
                }
            }
            failedCandidates.set(candidateFailures.size)
            candidateFailureType.set(candidateFailures.firstOrNull()?.typeName())
            cycleFailureType.set(null)
            lastCompletedCycleAt.set(clock.instant())
        } catch (failure: Throwable) {
            recoveryLogger.atError()
                .addKeyValue("event", "execution_run_recovery_cycle_failed")
                .setCause(failure)
                .addKeyValue("failure_types", failure.causeTypeChain())
                .log("execution_run_recovery_cycle_failed")
            cycleFailureType.set(failure.typeName())
        } finally {
            cycleInProgress.set(false)
        }
    }
}

private val recoveryLogger = LoggerFactory.getLogger(ExecutionRunRecoveryLifecycle::class.java)

private fun Throwable.typeName(): String = this::class.qualifiedName ?: javaClass.name

private fun Throwable.causeTypeChain(): String =
    generateSequence(this) { current -> current.cause }
        .map { current -> current.javaClass.name }
        .joinToString(" -> ")
