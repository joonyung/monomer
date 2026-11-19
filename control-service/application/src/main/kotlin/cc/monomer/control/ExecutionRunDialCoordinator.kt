package cc.monomer.control

import cc.monomer.control.observability.addCorrelationId
import cc.monomer.control.jdbc.causeTypeChain
import cc.monomer.control.jdbc.sqlStateChain
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ContentDigest
import cc.monomer.connector.api.CredentialEnvelopeReference
import cc.monomer.connector.api.CredentialVersionId
import cc.monomer.connector.application.ManagedCredentialDocumentCodec
import cc.monomer.connector.application.ManagedCredentialReadOutcome
import cc.monomer.connector.application.ManagedCredentialReadPort
import cc.monomer.connector.application.ManagedCredentialReadRequest
import cc.monomer.execution.api.RunDispatchIntentPort
import cc.monomer.execution.api.ControlRunAssignment
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutionRunEvent
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunQueryPort
import cc.monomer.execution.api.ExecutionRunSettlementUseCase
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.ExecutionRunUseCase
import cc.monomer.execution.api.ExecutionRunPort
import cc.monomer.execution.api.PhysicalOutcome
import cc.monomer.execution.api.PhysicalOutcomeKind
import cc.monomer.execution.api.ExecutionArtifactStore
import cc.monomer.execution.api.ExecutionArtifactHead
import cc.monomer.execution.api.ArrowResultManifest
import cc.monomer.execution.api.ResultSinkKind
import cc.monomer.execution.api.isPhysicalTerminal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC

class ExecutionRunDialCoordinator(
    private val executionRunPort: ExecutionRunPort,
    private val executionRunQuery: ExecutionRunQueryPort,
    private val executionRunUseCase: ExecutionRunUseCase,
    private val executionRunSettlementUseCase: ExecutionRunSettlementUseCase,
    private val ownerSettlementTypes: Set<ExecutionOwnerType>,
    private val dispatchIntentPort: RunDispatchIntentPort,
    private val credentialReadPort: ManagedCredentialReadPort,
    private val artifactStore: ExecutionArtifactStore,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val executionDeadlineDuration: Duration,
    private val maximumCredentialDocumentBytes: Int,
    private val maximumCredentialFields: Int,
    private val maximumCredentialFieldNameBytes: Int,
    private val maximumCredentialFieldValueBytes: Int
) : AutoCloseable {
    private val liveDials = ConcurrentHashMap<ExecutionRunId, Job>()

    fun dispatchAfterCommit(runId: ExecutionRunId) {
        val loggingContext = runLoggingContext(runId)
        runAfterCommitOrNow { start(runId, loggingContext) }
    }

    fun cancelLiveDial(runId: ExecutionRunId) {
        liveDials[runId]?.cancel()
    }

    suspend fun reconcileLiveDial(runId: ExecutionRunId) {
        val job = liveDials[runId] ?: return
        try {
            val run = executionRunQuery.get(runId)
            if (run == null || run.state.isPhysicalTerminal() || run.executionOccupancyReleasedAt != null) {
                job.cancel()
            }
        } catch (failure: Exception) {
            job.cancel(ExecutionStateUnconfirmed(failure))
        }
    }

    suspend fun reconcileLiveDials() {
        liveDials.keys.toList().forEach { reconcileLiveDial(it) }
    }

    /** Runs the already-admitted inline query while delivering each frame to its caller. */
    suspend fun runInline(
        runId: ExecutionRunId,
        onBatch: suspend (ExecutionRunEvent.InlineResultBatch) -> Unit,
    ): PhysicalOutcome = withContext(MDCContext(runLoggingContext(runId))) {
        coroutineScope {
            val callerJob = checkNotNull(coroutineContext[Job])
            val previous = liveDials.putIfAbsent(runId, callerJob)
            check(previous == null || previous === callerJob) { "execution run is already being dialed" }
            try {
                execute(runId, onBatch)
                    ?: executionRunQuery.get(runId)?.physicalOutcome
                    ?: PhysicalOutcome(
                        terminalIdempotencyKey = runId.terminalIdempotencyKey(),
                        kind = PhysicalOutcomeKind.FAILED,
                        failureCode = "EXECUTION_RUN_NOT_FOUND",
                        safeMessage = "Execution run was not found",
                    )
            } finally {
                liveDials.remove(runId, callerJob)
            }
        }
    }

    /**
     * Cancels every detached dial and waits up to [DIAL_SHUTDOWN_TIMEOUT] for their cancellation to be
     * recorded and their occupancy released, before Spring closes the DataSource and the Execution channel.
     */
    override fun close() {
        val dials = scope.coroutineContext.job
        dials.cancel()
        val finished = runBlocking { withTimeoutOrNull(DIAL_SHUTDOWN_TIMEOUT.toMillis()) { dials.join() } } != null
        if (!finished) {
            executionRunTimingLogger.atWarn()
                .addKeyValue("event", "execution_run_dial_shutdown_incomplete")
                .addKeyValue("live_dials", liveDials.size)
                .log("execution_run_dial_shutdown_incomplete")
        }
    }

    private fun start(runId: ExecutionRunId, loggingContext: Map<String, String>) {
        var launched: Job? = null
        liveDials.compute(runId) { _, existing ->
            if (existing != null && !existing.isCompleted) {
                existing
            } else {
                scope.launch(MDCContext(loggingContext), start = CoroutineStart.LAZY) {
                    try {
                        execute(runId, onBatch = null)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        executionRunTimingLogger.atError()
                            .addKeyValue("event", "execution_run_dial_failed")
                            .addCorrelationId("run_id", runId.toString())
                            .setCause(failure)
                            .log("execution_run_dial_failed")
                    }
                }.also { job -> launched = job }
            }
        }
        launched?.let { job ->
            job.invokeOnCompletion { liveDials.remove(runId, job) }
            job.start()
        }
    }

    private suspend fun execute(
        runId: ExecutionRunId,
        onBatch: (suspend (ExecutionRunEvent.InlineResultBatch) -> Unit)?,
    ): PhysicalOutcome? {
        val admitted = executionRunQuery.get(runId) ?: return null
        if (admitted.dispatchClaimedAt != null) {
            return admitted.physicalOutcome ?: if (onBatch != null) {
                PhysicalOutcome(
                    terminalIdempotencyKey = admitted.id.terminalIdempotencyKey(),
                    kind = PhysicalOutcomeKind.FAILED,
                    failureCode = "EXECUTION_RUN_ALREADY_DISPATCHED",
                    safeMessage = "Execution run is already owned by another dispatcher",
                )
            } else {
                null
            }
        }
        val claimedAt = clock.instant()
        if (!claimedAt.isBefore(admitted.dispatchDeadlineAt) || admitted.state.isPhysicalTerminal()) {
            return admitted.physicalOutcome
        }
        val snapshot =
            when (
                val claim =
                    executionRunUseCase.claimDispatch(
                        ExecutionRunUseCase.ClaimRunDispatchCommand(
                            runId = runId,
                            claimedAt = claimedAt,
                            executionDeadlineAt = admitted.deadline,
                        ),
                    )
                ) {
                is ExecutionRunUseCase.RunMutationOutcome.Applied -> claim.snapshot
                // A durable claim is the one-shot dispatch fence.  A second
                // caller must never dial a run that another caller claimed.
                is ExecutionRunUseCase.RunMutationOutcome.Duplicate ->
                    return claim.snapshot.physicalOutcome ?: if (onBatch != null) {
                        PhysicalOutcome(
                            terminalIdempotencyKey = claim.snapshot.id.terminalIdempotencyKey(),
                            kind = PhysicalOutcomeKind.FAILED,
                            failureCode = "EXECUTION_RUN_ALREADY_DISPATCHED",
                            safeMessage = "Execution run is already owned by another dispatcher",
                        )
                    } else {
                        null
                    }
                else -> return null
            }
        var currentSnapshot = snapshot
        var acquiredAt: java.time.Instant? = null
        val timing = ExecutionRunDialTiming(
            runId = snapshot.id,
            ownerType = snapshot.owner.type,
            resultSinkKind = snapshot.runtimeBindings.resultSink.kind,
            admittedAt = snapshot.createdAt,
            dispatchClaimedAt = claimedAt,
        )
        val callerJob = checkNotNull(currentCoroutineContext()[Job])
        val heartbeatJob = scope.launch(MDCContext(runLoggingContext(runId))) {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MILLIS)
                try {
                    val current = executionRunUseCase.heartbeat(
                        ExecutionRunUseCase.HeartbeatCommand(snapshot.id, clock.instant()),
                    ).confirmedSnapshot()
                    if (!timing.terminalRecordStarted &&
                        (current.state.isPhysicalTerminal() || current.executionOccupancyReleasedAt != null)
                    ) {
                        callerJob.cancel()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (!timing.terminalRecordStarted) callerJob.cancel(ExecutionStateUnconfirmed(failure))
                    return@launch
                }
            }
        }
        try {
            val ready = withTimeoutOrNull(
                Duration.between(clock.instant(), snapshot.dispatchDeadlineAt).toMillis(),
            ) {
                while (true) {
                    if (!executionRunQuery.hasExecutionOccupancyCapacity(snapshot.workspaceId)) {
                        delay(OCCUPANCY_RETRY_INTERVAL_MILLIS)
                        continue
                    }
                    val result = executionRunUseCase.acquireExecutionOccupancy(
                        ExecutionRunUseCase.AcquireExecutionOccupancyCommand(snapshot.id, snapshot.workspaceId, clock.instant()),
                    )
                    if (result == ExecutionRunUseCase.RunMutationOutcome.ExecutionOccupancyFull) {
                        delay(OCCUPANCY_RETRY_INTERVAL_MILLIS)
                        continue
                    }
                    val current = result.confirmedSnapshot()
                    if (current.state.isPhysicalTerminal() || current.executionOccupancyReleasedAt != null) return@withTimeoutOrNull false
                    if (result is ExecutionRunUseCase.RunMutationOutcome.Applied && current.executionOccupancyAcquiredAt != null) {
                        acquiredAt = current.executionOccupancyAcquiredAt
                        timing.occupancyAcquiredAt = current.executionOccupancyAcquiredAt
                        currentSnapshot = current
                        return@withTimeoutOrNull true
                    }
                    check(current.executionOccupancyAcquiredAt == null) { "execution occupancy is already owned" }
                    delay(OCCUPANCY_RETRY_INTERVAL_MILLIS)
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
            if (ready == null) {
                persistDispatchDeadlineFailure(snapshot, timing)
            } else if (ready) {
                val startedAt = clock.instant()
                if (!startedAt.isBefore(snapshot.dispatchDeadlineAt)) {
                    persistDispatchDeadlineFailure(snapshot, timing)
                } else {
                    currentSnapshot = executionRunUseCase.narrowExecutionDeadline(
                        ExecutionRunUseCase.NarrowExecutionDeadlineCommand(
                            snapshot.id, minOf(snapshot.deadline, startedAt.plus(executionDeadlineDuration)), startedAt,
                        ),
                    ).confirmedSnapshot()
                    if (!currentSnapshot.state.isPhysicalTerminal() && currentSnapshot.executionOccupancyReleasedAt == null) {
                        val finished = withTimeoutOrNull(Duration.between(clock.instant(), currentSnapshot.deadline).toMillis()) {
                            runAssignment(currentSnapshot, onBatch, timing)
                            true
                        }
                        if (finished == null) persistDeadlineFailure(currentSnapshot, timing)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            try {
                withContext(NonCancellable) {
                    if (generateSequence<Throwable>(cancelled) { it.cause }.any { it is ExecutionStateUnconfirmed }) {
                        persistStreamFailure(currentSnapshot, timing)
                    } else {
                        persistTerminal(currentSnapshot, PhysicalOutcome(
                            terminalIdempotencyKey = currentSnapshot.id.terminalIdempotencyKey(),
                            kind = PhysicalOutcomeKind.CANCELLED,
                            failureCode = null,
                            safeMessage = null,
                        ), timing)
                    }
                }
            } catch (persistenceFailure: Exception) {
                cancelled.addSuppressed(persistenceFailure)
            }
            throw cancelled
        } catch (failure: Exception) {
            executionRunTimingLogger.atError()
                .addKeyValue("event", "execution_run_stream_failed")
                .addCorrelationId("run_id", runId.toString())
                .setCause(failure)
                .addKeyValue("failure_types", failure.causeTypeChain())
                .addKeyValue("sql_states", failure.sqlStateChain())
                .log("execution_run_stream_failed")
            persistStreamFailure(currentSnapshot, timing)
        } finally {
            withContext(NonCancellable) {
                // The heartbeat is cancelled before release so no later tick observes this dial's own
                // release and cancels a caller whose run already finished; joining after release keeps
                // the slot from waiting on the heartbeat's dispatch.
                heartbeatJob.cancel()
                try {
                    acquiredAt?.let { acquisition ->
                        executionRunUseCase.releaseExecutionOccupancy(
                            ExecutionRunUseCase.ReleaseExecutionOccupancyCommand(snapshot.id, acquisition, clock.instant()),
                        )
                    }
                } catch (failure: Exception) {
                    executionRunTimingLogger.atError()
                        .addKeyValue("event", "execution_run_occupancy_release_failed")
                        .addCorrelationId("run_id", runId.toString())
                        .setCause(failure)
                        .addKeyValue("failure_types", failure.causeTypeChain())
                        .log("execution_run_occupancy_release_failed")
                } finally {
                    heartbeatJob.join()
                }
                // Settling after release frees the Workspace occupancy slot before the owner write.
                if (timing.ownerSettlementPending) settleOwnerAfterTerminal(snapshot.id, timing)
            }
            timing.log()
        }
        return executionRunQuery.get(snapshot.id)?.physicalOutcome
    }

    private fun ExecutionRunUseCase.RunMutationOutcome.confirmedSnapshot(): ExecutionRunSnapshot = when (this) {
        is ExecutionRunUseCase.RunMutationOutcome.Applied -> snapshot
        is ExecutionRunUseCase.RunMutationOutcome.Duplicate -> snapshot
        else -> error("Execution state mutation was not confirmed: ${this::class.simpleName}")
    }

    private suspend fun confirmExecutionIsActive(snapshot: ExecutionRunSnapshot): Boolean {
        val current = executionRunUseCase.heartbeat(
            ExecutionRunUseCase.HeartbeatCommand(snapshot.id, clock.instant()),
        ).confirmedSnapshot()
        return clock.instant().isBefore(current.deadline) && !current.state.isPhysicalTerminal() &&
            current.executionOccupancyAcquiredAt == snapshot.executionOccupancyAcquiredAt &&
            current.executionOccupancyReleasedAt == null
    }

    private suspend fun runAssignment(
        snapshot: ExecutionRunSnapshot,
        onBatch: (suspend (ExecutionRunEvent.InlineResultBatch) -> Unit)?,
        timing: ExecutionRunDialTiming,
    ) {
        if (!confirmExecutionIsActive(snapshot)) return
        val assignment = when (val materialized = materializeAssignment(snapshot)) {
            is AssignmentMaterialization.Ready -> materialized.assignment
            AssignmentMaterialization.Missing -> {
                persistStreamFailure(snapshot, timing)
                return
            }
            AssignmentMaterialization.CredentialRejected -> {
                persistConnectorUnavailable(snapshot, timing)
                return
            }
        }
        var nextInlineSequence = 1uL
        var terminal = false
        try {
            if (!confirmExecutionIsActive(snapshot)) return
            timing.rpcStartedAt = clock.instant()
            executionRunPort.run(assignment).collect { event ->
                when (event) {
                    is ExecutionRunEvent.Progress -> {
                        check(!terminal) { "execution progress delivered after terminal" }
                        persistProgress(snapshot, event)
                    }
                    is ExecutionRunEvent.InlineResultBatch -> {
                        check(onBatch != null) { "inline result batch delivered to artifact caller" }
                        check(!terminal) { "inline result batch delivered after terminal" }
                        check(event.sequence == nextInlineSequence) { "inline result sequence must be contiguous" }
                        onBatch(event)
                        nextInlineSequence += 1uL
                    }
                    is ExecutionRunEvent.Terminal -> {
                        check(!terminal) { "execution terminal delivered more than once" }
                        timing.rpcTerminalAt = clock.instant()
                        persistTerminal(snapshot, event.outcome, timing)
                        terminal = true
                    }
                }
            }
            if (!terminal) persistStreamFailure(snapshot, timing)
        } finally {
            Arrays.fill(assignment.credentialDocument, 0)
        }
    }

    private suspend fun persistConnectorUnavailable(snapshot: ExecutionRunSnapshot, timing: ExecutionRunDialTiming) {
        persistTerminal(snapshot, PhysicalOutcome(
            terminalIdempotencyKey = snapshot.id.terminalIdempotencyKey(),
            kind = PhysicalOutcomeKind.FAILED,
            failureCode = "CONNECTOR_UNAVAILABLE",
            safeMessage = "The connector was deleted or its credentials are no longer available",
        ), timing)
    }

    private suspend fun persistDispatchDeadlineFailure(snapshot: ExecutionRunSnapshot, timing: ExecutionRunDialTiming) {
        persistTerminal(snapshot, PhysicalOutcome(
            terminalIdempotencyKey = snapshot.id.terminalIdempotencyKey(),
            kind = PhysicalOutcomeKind.FAILED,
            failureCode = "EXECUTION_UNAVAILABLE",
            safeMessage = "Execution did not start before the dispatch deadline",
        ), timing)
    }

    private suspend fun persistDeadlineFailure(snapshot: ExecutionRunSnapshot, timing: ExecutionRunDialTiming) {
        persistTerminal(snapshot, PhysicalOutcome(
            terminalIdempotencyKey = snapshot.id.terminalIdempotencyKey(),
            kind = PhysicalOutcomeKind.FAILED,
            failureCode = "EXECUTION_DEADLINE_EXCEEDED",
            safeMessage = "Execution exceeded the run deadline",
        ), timing)
    }

    private suspend fun materializeAssignment(
        snapshot: ExecutionRunSnapshot
    ): AssignmentMaterialization {
        val template =
            dispatchIntentPort.find(snapshot.id)?.assignmentTemplate ?: return AssignmentMaterialization.Missing
        val credentialVersion = snapshot.runtimeBindings.credentialVersion ?: return AssignmentMaterialization.Missing
        val credentialReference =
            CredentialEnvelopeReference(
                credentialVersionId = CredentialVersionId(credentialVersion.value),
                keyVersion = snapshot.runtimeBindings.credentialKeyVersion,
                envelopeDigest =
                    ContentDigest("sha256:${snapshot.runtimeBindings.credentialBindingDigest.value}"),
            )
        val credential =
            when (
                val read =
                    credentialReadPort.readExact(
                        ManagedCredentialReadRequest(
                            workspaceId = snapshot.workspaceId,
                            connectorId = ConnectorId(snapshot.connectorId.value),
                            connectorType = snapshot.connectorType.name.lowercase(),
                            reference = credentialReference,
                            serverDeadline = snapshot.deadline
                        )
                    )
            ) {
                is ManagedCredentialReadOutcome.Found -> read
                ManagedCredentialReadOutcome.Rejected -> return AssignmentMaterialization.CredentialRejected
                ManagedCredentialReadOutcome.Unavailable -> return AssignmentMaterialization.Missing
            }
        val document =
            try {
                ManagedCredentialDocumentCodec.encode(
                    credentialMaterial = credential.credentialMaterial,
                    maximumDocumentBytes = maximumCredentialDocumentBytes,
                    maximumFields = maximumCredentialFields,
                    maximumFieldNameBytes = maximumCredentialFieldNameBytes,
                    maximumFieldValueBytes = maximumCredentialFieldValueBytes
                )
            } finally {
                credential.close()
            }
        return AssignmentMaterialization.Ready(
            assignment =
                ControlRunAssignment(
                    runId = snapshot.id,
                    workspaceId = snapshot.workspaceId,
                    connectorId = snapshot.connectorId,
                    connectorType = snapshot.connectorType,
                    properties = snapshot.connectorProperties,
                    credentialDocument = document,
                    queryRequest = template,
                    deadline = snapshot.deadline
                ),
        )
    }

    private suspend fun persistProgress(
        snapshot: ExecutionRunSnapshot,
        event: ExecutionRunEvent.Progress
    ) {
        val current = executionRunUseCase.recordProgress(
            ExecutionRunUseCase.RecordRunProgressCommand(
                runId = snapshot.id,
                rowsProcessed = event.rowsProcessed,
                bytesProcessed = event.bytesProcessed,
                phase = event.phase.ifBlank { "running" },
                receivedAt = clock.instant(),
            ),
        ).confirmedSnapshot()
        if (current.state.isPhysicalTerminal() || current.executionOccupancyReleasedAt != null) {
            throw CancellationException("Execution has been stopped")
        }
    }

    private class ExecutionStateUnconfirmed(failure: Exception) : CancellationException("Execution state could not be confirmed") {
        init { initCause(failure) }
    }

    private sealed interface AssignmentMaterialization {
        data class Ready(val assignment: ControlRunAssignment) : AssignmentMaterialization
        data object Missing : AssignmentMaterialization
        data object CredentialRejected : AssignmentMaterialization
    }

    internal companion object {
        const val HEARTBEAT_INTERVAL_MILLIS = 10_000L
        const val OCCUPANCY_RETRY_INTERVAL_MILLIS = 500L

        /** Bounded so a stuck dial cannot hold Control shutdown inside the ECS stop timeout. */
        val DIAL_SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(10)
    }

    private suspend fun persistTerminal(
        snapshot: ExecutionRunSnapshot,
        outcome: PhysicalOutcome,
        timing: ExecutionRunDialTiming,
    ) {
        val verifiedOutcome =
            if (requiresArrowArtifactVerification(snapshot.runtimeBindings.resultSink.kind, outcome.kind)) {
                val manifest = outcome.arrowResultManifest
                val expectedObjectKey = deterministicResultObjectKey(snapshot)
                val head = if (manifest == null) null else artifactStore.head(expectedObjectKey)
                if (!matchesExactArtifact(
                        expectedObjectKey = expectedObjectKey,
                        manifest = manifest,
                        head = head,
                    )
                ) {
                    PhysicalOutcome(
                        terminalIdempotencyKey = snapshot.id.terminalIdempotencyKey(),
                        kind = PhysicalOutcomeKind.FAILED,
                        failureCode = "RESULT_PERSISTENCE_FAILED",
                        safeMessage = "Execution result object could not be verified",
                    )
                } else {
                    outcome
                }
            } else {
                outcome
            }
        val receivedAt = clock.instant()
        timing.terminalRecordStarted = true
        val recorded = executionRunUseCase.recordPhysicalOutcome(
            ExecutionRunUseCase.RecordPhysicalOutcomeCommand(
                runId = snapshot.id,
                outcome = verifiedOutcome.copy(terminalIdempotencyKey = snapshot.id.terminalIdempotencyKey()),
                receivedAt = receivedAt
            )
        )
        // A later late-cancellation or deadline attempt only conflicts with the durable outcome, so the
        // first recorded terminal stays the one the timing line reports.
        if (timing.terminalRecord == null) {
            timing.terminalRecordedAt = receivedAt
            timing.outcomeKind = verifiedOutcome.kind
            timing.failureCode = verifiedOutcome.failureCode
            timing.terminalRecord = recorded::class.simpleName
        }
        when (recorded) {
            is ExecutionRunUseCase.TerminalOutcome.Accepted,
            is ExecutionRunUseCase.TerminalOutcome.Duplicate,
            ExecutionRunUseCase.TerminalOutcome.TerminalConflict,
            -> timing.ownerSettlementPending = true
            ExecutionRunUseCase.TerminalOutcome.NotFound,
            ExecutionRunUseCase.TerminalOutcome.InvalidState,
            ExecutionRunUseCase.TerminalOutcome.BindingMismatch,
            ExecutionRunUseCase.TerminalOutcome.ConcurrentConflict,
            -> Unit
        }
    }

    /**
     * Settles the typed owner once the run holds a durable physical outcome and its occupancy is
     * released. The terminal record is already committed in its own transaction, so a failure here
     * only leaves the run as a pending owner-settlement candidate for the recovery loop.
     */
    private suspend fun settleOwnerAfterTerminal(
        runId: ExecutionRunId,
        timing: ExecutionRunDialTiming,
    ) {
        if (timing.ownerType !in ownerSettlementTypes) {
            timing.settlement = "NOT_OWNER_SETTLED"
            return
        }
        val settledAt = clock.instant()
        timing.settlementRequestedAt = settledAt
        val settlement =
            try {
                executionRunSettlementUseCase.settle(runId, settledAt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                timing.settlement = "EXCEPTION"
                executionRunTimingLogger.atWarn()
                    .addKeyValue("event", "execution_run_settlement_failed")
                    .addCorrelationId("run_id", runId.toString())
                    .setCause(failure)
                    .addKeyValue("failure_types", failure.causeTypeChain())
                    .log("execution_run_settlement_failed")
                return
            }
        timing.settlementFinishedAt = clock.instant()
        timing.settlement = settlement::class.simpleName
        when (settlement) {
            is ExecutionRunSettlementUseCase.SettlementOutcome.Completed ->
                timing.ownerSettledAt = settlement.snapshot.ownerSettledAt
            is ExecutionRunSettlementUseCase.SettlementOutcome.Duplicate -> Unit
            ExecutionRunSettlementUseCase.SettlementOutcome.NotFound,
            ExecutionRunSettlementUseCase.SettlementOutcome.ConcurrentConflict,
            ExecutionRunSettlementUseCase.SettlementOutcome.OwnerUnsupported,
            ExecutionRunSettlementUseCase.SettlementOutcome.Conflict,
            -> executionRunTimingLogger.atWarn()
                .addKeyValue("event", "execution_run_settlement_incomplete")
                .addCorrelationId("run_id", runId.toString())
                .addKeyValue("outcome", settlement::class.simpleName)
                .log("execution_run_settlement_incomplete")
        }
    }

    private suspend fun persistStreamFailure(snapshot: ExecutionRunSnapshot, timing: ExecutionRunDialTiming) {
        val deadlineExceeded = !clock.instant().isBefore(snapshot.deadline)
        persistTerminal(
            snapshot,
            PhysicalOutcome(
                terminalIdempotencyKey = snapshot.id.terminalIdempotencyKey(),
                kind = PhysicalOutcomeKind.FAILED,
                failureCode =
                    if (deadlineExceeded) {
                        "EXECUTION_DEADLINE_EXCEEDED"
                    } else {
                        "EXECUTION_ORPHANED"
                    },
                safeMessage =
                    if (deadlineExceeded) {
                        "Execution exceeded the run deadline"
                    } else {
                        "Execution stream closed without a terminal outcome"
                    },
            ),
            timing,
        )
    }

}

internal val executionRunTimingLogger = LoggerFactory.getLogger(ExecutionRunDialCoordinator::class.java)

private fun runLoggingContext(runId: ExecutionRunId): Map<String, String> = buildMap {
    MDC.get("request_id")?.let { put("request_id", it) }
    put("run_id", runId.toString())
}

private fun deterministicResultObjectKey(snapshot: ExecutionRunSnapshot): String =
    requireNotNull(snapshot.runtimeBindings.resultSink.objectKey) {
        "artifact result sink must have an object key"
    }

private fun normalizeEtag(value: String): String = value.trim().trim('"')

internal fun matchesExactArtifact(
    expectedObjectKey: String,
    manifest: ArrowResultManifest?,
    head: ExecutionArtifactHead?,
): Boolean =
    manifest != null &&
        head != null &&
        manifest.objectKey == expectedObjectKey &&
        head.objectKey == manifest.objectKey &&
        head.byteSize == manifest.byteSize &&
        normalizeEtag(head.etag) == normalizeEtag(manifest.objectEtag)

internal fun requiresArrowArtifactVerification(
    resultSinkKind: ResultSinkKind,
    outcomeKind: PhysicalOutcomeKind,
): Boolean =
    resultSinkKind == ResultSinkKind.ARTIFACT_UPLOAD &&
        outcomeKind == PhysicalOutcomeKind.SUCCEEDED
