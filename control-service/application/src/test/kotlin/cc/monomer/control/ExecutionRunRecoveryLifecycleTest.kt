@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control

import cc.monomer.identity.api.WorkspaceId
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.ExecutionArtifactHead
import cc.monomer.execution.api.ExecutionArtifactStore
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.ExecutionRunState
import cc.monomer.execution.api.ExecutionOwnerId
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutionRunSettlementUseCase
import cc.monomer.execution.api.PhysicalOutcome
import cc.monomer.execution.api.PhysicalOutcomeKind
import cc.monomer.execution.api.ResultSinkBinding
import cc.monomer.execution.api.ResultSinkKind
import cc.monomer.execution.api.RunRuntimeBindings
import cc.monomer.execution.api.Sha256Digest
import cc.monomer.execution.api.ExecutionRunUseCase
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.slf4j.LoggerFactory

class ExecutionRunRecoveryLifecycleTest {
    @Test
    fun `existing cleanup loop removes discarded success but protects retained success`() {
        val now = Instant.parse("2026-10-08T00:00:00Z")
        val base = runSnapshot(now.minusSeconds(60)).copy(state = ExecutionRunState.SUCCEEDED,
            physicalOutcome = PhysicalOutcome("success", PhysicalOutcomeKind.SUCCEEDED, null, null), ownerSettledAt = now,
            resultManifest = cc.monomer.execution.api.ArrowResultManifest("fixture-result.arrow", 10u, "etag", 1u, 1, "application/vnd.apache.arrow.file"))
        for (discarded in listOf(false, true)) {
            val run = base.copy(ownerDiscardedAt = if (discarded) now else null)
            val store = RecordingArtifactStore()
            val execution = object : ExecutionRunUseCase by DeadlineRecordingExecutionRunUseCase(run, run) {
                override suspend fun recover(command: ExecutionRunUseCase.RecoverRunsCommand) = recoveryBatch(run)
                    .copy(occupancyRecoveryCandidates = emptyList(), artifactCleanupCandidates = listOf(run))
                override suspend fun beginArtifactCleanup(command: ExecutionRunUseCase.BeginArtifactCleanupCommand) = true
                override suspend fun completeArtifactCleanup(command: ExecutionRunUseCase.CompleteArtifactCleanupCommand) = true
            }
            val lifecycle = lifecycle(execution, now, store)
            invokeCycle(lifecycle)
            assertEquals(if (discarded) listOf(run.runtimeBindings.resultSink.objectKey) else emptyList(), store.deletedKeys)
        }
    }

    @Test
    fun `recovery cycle failure logs safe types without exception content`() {
        val observedAt = Instant.parse("2026-08-18T00:05:00Z")
        val run = runSnapshot(observedAt.minusSeconds(120))
        val execution = object : ExecutionRunUseCase by DeadlineRecordingExecutionRunUseCase(run, run) {
            override suspend fun recover(command: ExecutionRunUseCase.RecoverRunsCommand): ExecutionRunUseCase.RunRecoveryBatch =
                throw IllegalStateException("user@example.test raw-secret-token private SQL statement")
        }
        val logger = LoggerFactory.getLogger(ExecutionRunRecoveryLifecycle::class.java) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            val lifecycle = lifecycle(execution, observedAt)
            invokeCycle(lifecycle)
            val event = logs.list.single()
            assertEquals("execution_run_recovery_cycle_failed", event.formattedMessage)
            val encoded = cc.monomer.control.observability.RequestLogTestSupport.encoded(event)
            assertTrue(encoded.contains("stack_trace"))
            assertTrue(encoded.contains("ExecutionRunRecoveryLifecycleTest.kt:"))
            val fields = event.keyValuePairs.associate { it.key to it.value }
            assertEquals("java.lang.IllegalStateException", fields["failure_types"])
            assertFalse(encoded.contains("user@example.test"))
            assertFalse(encoded.contains("raw-secret-token"))
            assertFalse(encoded.contains("private SQL statement"))
            assertEquals("java.lang.IllegalStateException", lifecycle.snapshot().cycleFailureType)
            assertEquals(null, lifecycle.snapshot().lastCompletedCycleAt)
        } finally {
            logger.detachAppender(logs)
        }
    }

    @Test
    fun `unreadable candidates are logged by run ID and skipped without failing the cycle`() {
        val observedAt = Instant.parse("2026-08-18T00:05:00Z")
        val run = runSnapshot(observedAt.minusSeconds(120))
        val unreadable = ExecutionRunId(Uuid.generateV7())
        val recovered = mutableListOf<ExecutionRunId>()
        val execution = object : ExecutionRunUseCase by DeadlineRecordingExecutionRunUseCase(run, run) {
            override suspend fun recover(command: ExecutionRunUseCase.RecoverRunsCommand) =
                recoveryBatch(run).copy(unreadableRunIds = listOf(unreadable))
            override suspend fun recoverExecutionOccupancy(command: ExecutionRunUseCase.RecoverExecutionOccupancyCommand): ExecutionRunUseCase.RunMutationOutcome {
                recovered += command.runId
                return ExecutionRunUseCase.RunMutationOutcome.Applied(run)
            }
        }
        val logger = LoggerFactory.getLogger(ExecutionRunRecoveryLifecycle::class.java) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            val lifecycle = lifecycle(execution, observedAt)
            invokeCycle(lifecycle)

            assertEquals(listOf(run.id), recovered)
            assertEquals(1, lifecycle.snapshot().unreadableRuns)
            assertEquals(0, lifecycle.snapshot().failedCandidates)
            assertEquals(null, lifecycle.snapshot().candidateFailureType)
            assertEquals(observedAt, lifecycle.snapshot().lastCompletedCycleAt)
            val event = logs.list.single()
            assertEquals(ch.qos.logback.classic.Level.ERROR, event.level)
            assertEquals("execution_run_recovery_unreadable", event.formattedMessage)
            assertEquals(unreadable.toString(), event.keyValuePairs.associate { it.key to it.value }["run_id"])
        } finally {
            logger.detachAppender(logs)
        }
    }

    @Test
    fun `stale run is failed and owner settled independently of exact key cleanup`() {
        val observedAt = Instant.parse("2026-08-18T00:05:00Z")
        val run = runSnapshot(observedAt.minusSeconds(120))
        val failedRun =
            run.copy(
                state = ExecutionRunState.FAILED,
                physicalOutcome =
                    PhysicalOutcome(
                        terminalIdempotencyKey = "${run.id}:terminal",
                        kind = PhysicalOutcomeKind.FAILED,
                        failureCode = "EXECUTION_ORPHANED",
                        safeMessage = "Execution stream closed without a terminal outcome",
                    ),
                updatedAt = observedAt,
                version = run.version + 1,
            )
        val execution = RecordingExecutionRunUseCase(run, failedRun, observedAt)
        val settlement = RecordingOwnerSettlementUseCase()
        val store = RecordingArtifactStore()
        val limits =
            testExecutionLimits().copy(
                recoveryInterval = Duration.ofSeconds(1),
                orphanTimeout = Duration.ofSeconds(90),
            )
        val lifecycle =
            ExecutionRunRecoveryLifecycle(
                executionRunUseCase = execution,
                executionRunSettlementUseCase = settlement,
                executionArtifactStore = store,
                clock = Clock.fixed(observedAt, ZoneOffset.UTC),
                limits = limits,
                ownerSettlementTypes = setOf(ExecutionOwnerType.EXPLORE),
            )

        invokeCycle(lifecycle)
        invokeCycle(lifecycle)

        val orphanCommand = assertNotNull(execution.recordedRecovery)
        assertEquals(run.id, orphanCommand.runId)
        assertEquals(observedAt, orphanCommand.observedAt)
        assertEquals(Duration.ofSeconds(90), orphanCommand.orphanTimeout)
        assertEquals(
            listOf(
                run.runtimeBindings.resultSink.objectKey,
            ),
            store.deletedKeys,
        )
        assertEquals(run.id, settlement.settledRunId)
        assertTrue(execution.recoveryCommands.all { command -> command.orphanTimeout == Duration.ofSeconds(90) })
    }

    @Test
    fun `dispatch and execution deadlines terminalize without deleting before terminal persistence`() {
        val observedAt = Instant.parse("2026-08-18T00:05:00Z")
        val dispatched = runSnapshot(observedAt.minusSeconds(120))
            .copy(deadline = observedAt)
        val unclaimed =
            runSnapshot(observedAt.minusSeconds(120)).copy(
                dispatchClaimedAt = null,
                lastHeartbeatAt = null,
            )
        val execution = DeadlineRecordingExecutionRunUseCase(unclaimed, dispatched)
        val store = RecordingArtifactStore()
        val lifecycle =
            ExecutionRunRecoveryLifecycle(
                executionRunUseCase = execution,
                executionRunSettlementUseCase = RecordingOwnerSettlementUseCase(),
                executionArtifactStore = store,
                clock = Clock.fixed(observedAt, ZoneOffset.UTC),
                limits = testExecutionLimits(),
                ownerSettlementTypes = setOf(ExecutionOwnerType.EXPLORE),
            )

        invokeCycle(lifecycle)

        assertEquals(
            setOf(unclaimed.id, dispatched.id),
            execution.recordedRecoveries.map { command -> command.runId }.toSet(),
        )
        assertTrue(store.deletedKeys.isEmpty())
    }

    @Test
    fun `fresh heartbeat recheck skips stale candidate and deduplicates overlapping recovery lists`() {
        val observedAt = Instant.parse("2026-08-18T00:05:00Z")
        val stale = runSnapshot(observedAt.minusSeconds(120))
        val fresh = stale.copy(lastHeartbeatAt = observedAt, version = stale.version + 1)
        val commands = mutableListOf<ExecutionRunUseCase.RecoverExecutionOccupancyCommand>()
        val execution = object : ExecutionRunUseCase by DeadlineRecordingExecutionRunUseCase(stale, stale) {
            override suspend fun recover(command: ExecutionRunUseCase.RecoverRunsCommand) =
                recoveryBatch(stale).copy(
                    dispatchDeadlineCandidates = listOf(stale),
                    executionDeadlineCandidates = listOf(stale),
                    orphanCandidates = listOf(stale),
                    occupancyRecoveryTruncated = true,
                )

            override suspend fun recoverExecutionOccupancy(command: ExecutionRunUseCase.RecoverExecutionOccupancyCommand): ExecutionRunUseCase.RunMutationOutcome {
                commands += command
                return ExecutionRunUseCase.RunMutationOutcome.Duplicate(fresh)
            }
        }
        val lifecycle = lifecycle(execution, observedAt)

        invokeCycle(lifecycle)

        assertEquals(listOf(ExecutionRunUseCase.RecoverExecutionOccupancyCommand(stale.id, observedAt, testExecutionLimits().orphanTimeout)), commands)
        assertEquals(0, lifecycle.snapshot().failedCandidates)
        assertEquals(observedAt, lifecycle.snapshot().lastCompletedCycleAt)
        assertTrue(lifecycle.snapshot().truncated)
    }

    @Test
    fun `terminal occupied candidate uses scoped recovery and normal recovery competition is healthy`() {
        val observedAt = Instant.parse("2026-08-18T00:05:00Z")
        val terminal = runSnapshot(observedAt.minusSeconds(120)).copy(
            state = ExecutionRunState.FAILED,
            physicalOutcome = PhysicalOutcome("terminal", PhysicalOutcomeKind.FAILED, "FAILED", "Failed"),
            executionOccupancyAcquiredAt = observedAt.minusSeconds(119),
        )
        var recoveredRunId: ExecutionRunId? = null
        val execution = object : ExecutionRunUseCase by DeadlineRecordingExecutionRunUseCase(terminal, terminal) {
            override suspend fun recover(command: ExecutionRunUseCase.RecoverRunsCommand) = recoveryBatch(terminal)
            override suspend fun recoverExecutionOccupancy(command: ExecutionRunUseCase.RecoverExecutionOccupancyCommand): ExecutionRunUseCase.RunMutationOutcome {
                recoveredRunId = command.runId
                return ExecutionRunUseCase.RunMutationOutcome.ConcurrentConflict
            }
        }
        val lifecycle = lifecycle(execution, observedAt)

        invokeCycle(lifecycle)

        assertEquals(terminal.id, recoveredRunId)
        assertEquals(0, lifecycle.snapshot().failedCandidates)
        assertEquals(observedAt, lifecycle.snapshot().lastCompletedCycleAt)
    }

    @Test
    fun `cleanup CAS competition is healthy while storage errors remain failures`() {
        val observedAt = Instant.parse("2026-08-18T00:05:00Z")
        val failed = runSnapshot(observedAt.minusSeconds(120)).copy(
            state = ExecutionRunState.FAILED,
            physicalOutcome = PhysicalOutcome("terminal", PhysicalOutcomeKind.FAILED, "FAILED", "Failed"),
        )
        for ((beginWins, completeWins, ioFails) in listOf(Triple(false, true, false), Triple(true, false, false), Triple(true, true, true))) {
            val store = RecordingArtifactStore()
            var deleteCalls = 0
            val artifacts = object : ExecutionArtifactStore by store {
                override suspend fun delete(objectKey: String) {
                    deleteCalls += 1
                    if (ioFails) throw java.io.IOException("storage unavailable")
                }
            }
            val execution = object : ExecutionRunUseCase by DeadlineRecordingExecutionRunUseCase(failed, failed) {
                override suspend fun recover(command: ExecutionRunUseCase.RecoverRunsCommand) =
                    recoveryBatch(failed).copy(occupancyRecoveryCandidates = emptyList(), artifactCleanupCandidates = listOf(failed))
                override suspend fun beginArtifactCleanup(command: ExecutionRunUseCase.BeginArtifactCleanupCommand) = beginWins
                override suspend fun completeArtifactCleanup(command: ExecutionRunUseCase.CompleteArtifactCleanupCommand) = completeWins
            }
            val lifecycle = lifecycle(execution, observedAt, artifacts)

            invokeCycle(lifecycle)

            assertEquals(if (beginWins) 1 else 0, deleteCalls)
            assertEquals(if (ioFails) 1 else 0, lifecycle.snapshot().failedCandidates)
            assertEquals(if (ioFails) "java.io.IOException" else null, lifecycle.snapshot().candidateFailureType)
            assertEquals(null, lifecycle.snapshot().cycleFailureType)
        }
    }

    @Test
    fun `owner CAS competition is healthy but genuine owner conflict and database errors fail`() {
        val observedAt = Instant.parse("2026-08-18T00:05:00Z")
        val run = runSnapshot(observedAt.minusSeconds(120))
        val execution = object : ExecutionRunUseCase by DeadlineRecordingExecutionRunUseCase(run, run) {
            override suspend fun recover(command: ExecutionRunUseCase.RecoverRunsCommand) =
                recoveryBatch(run).copy(occupancyRecoveryCandidates = emptyList(), ownerSettlementCandidates = listOf(run))
        }
        for (scenario in 0..2) {
            val settlement = object : ExecutionRunSettlementUseCase {
                override suspend fun settle(runId: ExecutionRunId, settledAt: Instant): ExecutionRunSettlementUseCase.SettlementOutcome =
                    when (scenario) {
                        0 -> ExecutionRunSettlementUseCase.SettlementOutcome.ConcurrentConflict
                        1 -> ExecutionRunSettlementUseCase.SettlementOutcome.Conflict
                        else -> throw java.sql.SQLException("database unavailable")
                    }
            }
            val lifecycle = lifecycle(execution, observedAt, settlement = settlement)

            invokeCycle(lifecycle)

            assertEquals(if (scenario == 0) 0 else 1, lifecycle.snapshot().failedCandidates)
            if (scenario == 2) assertEquals("java.sql.SQLException", lifecycle.snapshot().candidateFailureType)
        }
    }

    @Test
    fun `occupancy recovery database error is a counted candidate failure that keeps the loop healthy`() {
        val observedAt = Instant.parse("2026-08-18T00:05:00Z")
        val run = runSnapshot(observedAt.minusSeconds(120))
        val execution = object : ExecutionRunUseCase by DeadlineRecordingExecutionRunUseCase(run, run) {
            override suspend fun recover(command: ExecutionRunUseCase.RecoverRunsCommand) = recoveryBatch(run)
            override suspend fun recoverExecutionOccupancy(command: ExecutionRunUseCase.RecoverExecutionOccupancyCommand): ExecutionRunUseCase.RunMutationOutcome =
                throw java.sql.SQLException("database unavailable")
        }
        val lifecycle = lifecycle(execution, observedAt)

        invokeCycle(lifecycle)

        assertEquals(1, lifecycle.snapshot().failedCandidates)
        assertEquals("java.sql.SQLException", lifecycle.snapshot().candidateFailureType)
        assertEquals(null, lifecycle.snapshot().cycleFailureType)
        assertEquals(observedAt, lifecycle.snapshot().lastCompletedCycleAt)
        assertTrue(lifecycle.snapshot().copy(running = true).isHealthy(observedAt, testExecutionLimits().recoveryInterval))
    }

    @Test
    fun `a failed cycle is unhealthy until the next completed cycle while a stale completion is unhealthy`() {
        val observedAt = Instant.parse("2026-08-18T00:05:00Z")
        val run = runSnapshot(observedAt.minusSeconds(120))
        var recoveryFails = false
        val execution = object : ExecutionRunUseCase by DeadlineRecordingExecutionRunUseCase(run, run) {
            override suspend fun recover(command: ExecutionRunUseCase.RecoverRunsCommand): ExecutionRunUseCase.RunRecoveryBatch =
                if (recoveryFails) throw java.sql.SQLException("database unavailable") else recoveryBatch(run)
        }
        val lifecycle = lifecycle(execution, observedAt)
        val interval = testExecutionLimits().recoveryInterval

        invokeCycle(lifecycle)
        assertTrue(lifecycle.snapshot().copy(running = true).isHealthy(observedAt, interval))
        assertFalse(lifecycle.snapshot().isHealthy(observedAt, interval))
        assertFalse(lifecycle.snapshot().copy(running = true).isHealthy(observedAt.plus(interval.multipliedBy(3)), interval))

        recoveryFails = true
        invokeCycle(lifecycle)
        assertEquals("java.sql.SQLException", lifecycle.snapshot().cycleFailureType)
        assertFalse(lifecycle.snapshot().copy(running = true).isHealthy(observedAt, interval))

        recoveryFails = false
        invokeCycle(lifecycle)
        assertEquals(null, lifecycle.snapshot().cycleFailureType)
        assertTrue(lifecycle.snapshot().copy(running = true).isHealthy(observedAt, interval))
    }

    private fun recoveryBatch(run: ExecutionRunSnapshot) = ExecutionRunUseCase.RunRecoveryBatch(
        dispatchDeadlineCandidates = emptyList(),
        executionDeadlineCandidates = emptyList(),
        orphanCandidates = emptyList(),
        occupancyRecoveryCandidates = listOf(run),
        ownerSettlementCandidates = emptyList(),
        artifactCleanupCandidates = emptyList(),
        dispatchDeadlineTruncated = false,
        executionDeadlineTruncated = false,
        orphanTruncated = false,
        occupancyRecoveryTruncated = false,
        ownerSettlementTruncated = false,
        artifactCleanupTruncated = false,
        unreadableRunIds = emptyList(),
    )

    private fun lifecycle(
        execution: ExecutionRunUseCase,
        observedAt: Instant,
        artifacts: ExecutionArtifactStore = RecordingArtifactStore(),
        settlement: ExecutionRunSettlementUseCase = RecordingOwnerSettlementUseCase(),
    ) = ExecutionRunRecoveryLifecycle(
        executionRunUseCase = execution,
        executionRunSettlementUseCase = settlement,
        executionArtifactStore = artifacts,
        clock = Clock.fixed(observedAt, ZoneOffset.UTC),
        limits = testExecutionLimits(),
        ownerSettlementTypes = setOf(ExecutionOwnerType.EXPLORE),
    )

    private fun invokeCycle(lifecycle: ExecutionRunRecoveryLifecycle) {
        val method = ExecutionRunRecoveryLifecycle::class.java.getDeclaredMethod("runCycleSafely")
        method.isAccessible = true
        method.invoke(lifecycle)
    }
}

private class DeadlineRecordingExecutionRunUseCase(
    private val dispatchDeadlineRun: ExecutionRunSnapshot,
    private val executionDeadlineRun: ExecutionRunSnapshot,
) : ExecutionRunUseCase {
    val recordedRecoveries = mutableListOf<ExecutionRunUseCase.RecoverExecutionOccupancyCommand>()

    override suspend fun recover(
        command: ExecutionRunUseCase.RecoverRunsCommand,
    ): ExecutionRunUseCase.RunRecoveryBatch =
        ExecutionRunUseCase.RunRecoveryBatch(
            dispatchDeadlineCandidates = listOf(dispatchDeadlineRun),
            executionDeadlineCandidates = listOf(executionDeadlineRun),
            orphanCandidates = emptyList(),
            occupancyRecoveryCandidates = emptyList(),
            ownerSettlementCandidates = emptyList(),
            dispatchDeadlineTruncated = false,
            executionDeadlineTruncated = false,
            orphanTruncated = false,
            occupancyRecoveryTruncated = false,
            ownerSettlementTruncated = false,
            artifactCleanupTruncated = false,
            unreadableRunIds = emptyList(),
            artifactCleanupCandidates = emptyList(),
        )

    override suspend fun recoverExecutionOccupancy(
        command: ExecutionRunUseCase.RecoverExecutionOccupancyCommand,
    ): ExecutionRunUseCase.RunMutationOutcome {
        recordedRecoveries += command
        return ExecutionRunUseCase.RunMutationOutcome.Applied(
            if (command.runId == dispatchDeadlineRun.id) dispatchDeadlineRun else executionDeadlineRun,
        )
    }

    override suspend fun recordPhysicalOutcome(command: ExecutionRunUseCase.RecordPhysicalOutcomeCommand): ExecutionRunUseCase.TerminalOutcome =
        error("Recovery must recheck the current run under the runtime lock")

    override suspend fun beginArtifactCleanup(command: ExecutionRunUseCase.BeginArtifactCleanupCommand): Boolean = true

    override suspend fun completeArtifactCleanup(command: ExecutionRunUseCase.CompleteArtifactCleanupCommand): Boolean = true

    override suspend fun acquireExecutionOccupancy(command: ExecutionRunUseCase.AcquireExecutionOccupancyCommand) =
        ExecutionRunUseCase.RunMutationOutcome.NotFound

    override suspend fun releaseExecutionOccupancy(command: ExecutionRunUseCase.ReleaseExecutionOccupancyCommand) =
        ExecutionRunUseCase.RunMutationOutcome.NotFound

    override suspend fun heartbeat(command: ExecutionRunUseCase.HeartbeatCommand) =
        ExecutionRunUseCase.RunMutationOutcome.NotFound

    override suspend fun narrowExecutionDeadline(command: ExecutionRunUseCase.NarrowExecutionDeadlineCommand) =
        ExecutionRunUseCase.RunMutationOutcome.NotFound

    override suspend fun claimDispatch(command: ExecutionRunUseCase.ClaimRunDispatchCommand) =
        ExecutionRunUseCase.RunMutationOutcome.NotFound

    override suspend fun recordProgress(command: ExecutionRunUseCase.RecordRunProgressCommand) =
        ExecutionRunUseCase.RunMutationOutcome.NotFound
}

private class RecordingExecutionRunUseCase(
    private val orphan: ExecutionRunSnapshot,
    private val settled: ExecutionRunSnapshot,
    private val observedAt: Instant,
) : ExecutionRunUseCase {
    var recordedRecovery: ExecutionRunUseCase.RecoverExecutionOccupancyCommand? = null
    val recoveryCommands = mutableListOf<ExecutionRunUseCase.RecoverRunsCommand>()
    private var recoveryCount = 0

    override suspend fun recover(command: ExecutionRunUseCase.RecoverRunsCommand): ExecutionRunUseCase.RunRecoveryBatch {
        recoveryCommands += command
        recoveryCount += 1
        return if (recoveryCount == 1) {
            ExecutionRunUseCase.RunRecoveryBatch(
                dispatchDeadlineCandidates = emptyList(),
                executionDeadlineCandidates = emptyList(),
                orphanCandidates = listOf(orphan),
                occupancyRecoveryCandidates = emptyList(),
                ownerSettlementCandidates = emptyList(),
                dispatchDeadlineTruncated = false,
                executionDeadlineTruncated = false,
                orphanTruncated = false,
                occupancyRecoveryTruncated = false,
                ownerSettlementTruncated = false,
                artifactCleanupTruncated = false,
                unreadableRunIds = emptyList(),
                artifactCleanupCandidates = emptyList(),
            )
        } else {
            ExecutionRunUseCase.RunRecoveryBatch(
                dispatchDeadlineCandidates = emptyList(),
                executionDeadlineCandidates = emptyList(),
                orphanCandidates = emptyList(),
                occupancyRecoveryCandidates = emptyList(),
                ownerSettlementCandidates = listOf(settled),
                dispatchDeadlineTruncated = false,
                executionDeadlineTruncated = false,
                orphanTruncated = false,
                occupancyRecoveryTruncated = false,
                ownerSettlementTruncated = false,
                artifactCleanupTruncated = false,
                unreadableRunIds = emptyList(),
                artifactCleanupCandidates = listOf(settled),
            )
        }
    }

    override suspend fun recoverExecutionOccupancy(command: ExecutionRunUseCase.RecoverExecutionOccupancyCommand): ExecutionRunUseCase.RunMutationOutcome {
        recordedRecovery = command
        return ExecutionRunUseCase.RunMutationOutcome.Applied(settled)
    }

    override suspend fun recordPhysicalOutcome(command: ExecutionRunUseCase.RecordPhysicalOutcomeCommand): ExecutionRunUseCase.TerminalOutcome =
        error("Recovery must recheck the current run under the runtime lock")

    override suspend fun beginArtifactCleanup(command: ExecutionRunUseCase.BeginArtifactCleanupCommand): Boolean = true

    override suspend fun completeArtifactCleanup(command: ExecutionRunUseCase.CompleteArtifactCleanupCommand): Boolean = true

    override suspend fun acquireExecutionOccupancy(command: ExecutionRunUseCase.AcquireExecutionOccupancyCommand) =
        ExecutionRunUseCase.RunMutationOutcome.NotFound

    override suspend fun releaseExecutionOccupancy(command: ExecutionRunUseCase.ReleaseExecutionOccupancyCommand) =
        ExecutionRunUseCase.RunMutationOutcome.NotFound

    override suspend fun heartbeat(command: ExecutionRunUseCase.HeartbeatCommand) =
        ExecutionRunUseCase.RunMutationOutcome.NotFound

    override suspend fun narrowExecutionDeadline(command: ExecutionRunUseCase.NarrowExecutionDeadlineCommand) =
        ExecutionRunUseCase.RunMutationOutcome.NotFound

    override suspend fun claimDispatch(command: ExecutionRunUseCase.ClaimRunDispatchCommand) =
        ExecutionRunUseCase.RunMutationOutcome.NotFound

    override suspend fun recordProgress(command: ExecutionRunUseCase.RecordRunProgressCommand) =
        ExecutionRunUseCase.RunMutationOutcome.NotFound
}

private class RecordingOwnerSettlementUseCase : ExecutionRunSettlementUseCase {
    var settledRunId: ExecutionRunId? = null

    override suspend fun settle(
        runId: ExecutionRunId,
        settledAt: Instant,
    ): ExecutionRunSettlementUseCase.SettlementOutcome {
        settledRunId = runId
        return ExecutionRunSettlementUseCase.SettlementOutcome.Completed(failedRun(runId))
    }

    private fun failedRun(runId: ExecutionRunId): ExecutionRunSnapshot =
        ExecutionRunSnapshot(
            id = runId,
            owner = ExecutionOwnerReference(ExecutionOwnerType.EXPLORE, ExecutionOwnerId(Uuid.generateV7())),
            workspaceId = WorkspaceId(Uuid.generateV7()),
            connectorId = ConnectorReference(Uuid.generateV7()),
            connectorType = ConnectorType.POSTGRESQL,
            connectorProperties =
                mapOf("host" to ConnectorRuntimePropertyValue.Text("localhost")),
            requestKind = cc.monomer.execution.api.ExecutableRequestKind.QUERY,
            canonicalRequestDigest = digest('a'),
            runtimeBindings = bindings("artifacts/settlement/result.arrow", Instant.parse("2026-08-18T01:00:00Z")),
            dispatchDeadlineAt = Instant.parse("2026-08-18T00:00:30Z"),
            deadline = Instant.parse("2026-08-18T01:00:00Z"),
            state = ExecutionRunState.FAILED,
            physicalOutcome =
                PhysicalOutcome(
                    terminalIdempotencyKey = "$runId:terminal",
                    kind = PhysicalOutcomeKind.FAILED,
                    failureCode = "EXECUTION_ORPHANED",
                    safeMessage = "orphan",
                ),
            cancelIntent = null,
            ownerSettledAt = Instant.parse("2026-08-18T00:05:00Z"),
            createdAt = Instant.parse("2026-08-18T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-18T00:05:00Z"),
            version = 1,
            executionOccupancyAcquiredAt = null,
            executionOccupancyReleasedAt = null,
            ownerDiscardedAt = null,
        )

}

private class RecordingArtifactStore : ExecutionArtifactStore {
    val deletedKeys = mutableListOf<String>()

    override suspend fun head(objectKey: String, deadline: java.time.Instant?): ExecutionArtifactHead? = null
    override suspend fun read(objectKey: String): ByteArray = error("not used")
    override fun readChunks(objectKey: String, expectedEtag: String, chunkSizeBytes: Int): Flow<ByteBuffer> =
        emptyFlow()
    override suspend fun readRange(objectKey: String, expectedEtag: String, byteRange: LongRange): ByteArray =
        error("Conditional result ranges are outside this fixture")

    override suspend fun presignGet(objectKey: String, expiresAt: java.time.Instant, fileName: String): cc.monomer.execution.api.ExecutionArtifactDownloadLink =
        error("Download signing is outside this fixture")

    override suspend fun delete(objectKey: String) {
        deletedKeys += objectKey
    }
}

private fun runSnapshot(createdAt: Instant): ExecutionRunSnapshot {
    val deadline = createdAt.plusSeconds(300)
    return ExecutionRunSnapshot(
        id = ExecutionRunId(Uuid.generateV7()),
        owner = ExecutionOwnerReference(ExecutionOwnerType.EXPLORE, ExecutionOwnerId(Uuid.generateV7())),
        workspaceId = WorkspaceId(Uuid.generateV7()),
        connectorId = ConnectorReference(Uuid.generateV7()),
        connectorType = ConnectorType.POSTGRESQL,
        connectorProperties =
            mapOf("host" to ConnectorRuntimePropertyValue.Text("localhost")),
        requestKind = cc.monomer.execution.api.ExecutableRequestKind.QUERY,
        canonicalRequestDigest = digest('a'),
        runtimeBindings = bindings("artifacts/workspaces/test/explore/runs/result.arrow", deadline),
        dispatchDeadlineAt = createdAt.plusSeconds(30),
        deadline = deadline,
        state = ExecutionRunState.RUNNING,
        physicalOutcome = null,
        cancelIntent = null,
        ownerSettledAt = null,
        createdAt = createdAt,
        updatedAt = createdAt,
        version = 0,
        executionOccupancyAcquiredAt = null,
        executionOccupancyReleasedAt = null,
        dispatchClaimedAt = createdAt.plusSeconds(1),
        lastHeartbeatAt = createdAt.plusSeconds(1),
        ownerDiscardedAt = null,
    )
}

private fun bindings(objectKey: String, deadline: Instant): RunRuntimeBindings =
    RunRuntimeBindings(
        connectorBindingDigest = digest('b'),
        credentialVersion = null,
        credentialKeyVersion = "local-primary",
        credentialBindingDigest = digest('c'),
        resultSink =
            ResultSinkBinding(
                schemaVersion = 2u,
                kind = ResultSinkKind.ARTIFACT_UPLOAD,
                objectKey = objectKey,
                maximumArtifactRows = 1_000u,
                maximumArtifactBytes = 1_024u,
                expiresAt = deadline,
                maximumRecordBatchRows = 1_024,
                maximumFrameBytes = 1_024,
                maximumCellBytes = 4 * 1_024,
                maximumMultipartPartBytes = 1_024,
                bindingDigest = digest('d'),
            ),
    )

private fun digest(character: Char): Sha256Digest = Sha256Digest(character.toString().repeat(64))
