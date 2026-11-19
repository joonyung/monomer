@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package cc.monomer.control

import cc.monomer.connector.application.CredentialMaterial
import cc.monomer.connector.application.ManagedCredentialReadOutcome
import cc.monomer.connector.application.ManagedCredentialReadPort
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import cc.monomer.connector.api.ConnectorId
import cc.monomer.execution.adapter.inbound.grpc.ProtoRawSqlRunSpecFactory
import cc.monomer.execution.adapter.inbound.grpc.ExecutableRequestAssignmentCodec
import cc.monomer.execution.adapter.outbound.postgres.PostgresExecutionRuntimeStore
import cc.monomer.execution.adapter.outbound.postgres.PostgresRunDispatchPersistence
import cc.monomer.control.jdbc.JdbcVirtualThreadDispatcher
import cc.monomer.execution.adapter.outbound.postgres.SpringOwnerSettlementTransactionRunner
import cc.monomer.execution.api.*
import cc.monomer.execution.application.*
import cc.monomer.explore.adapter.outbound.postgres.PostgresExploreRunRepository
import cc.monomer.explore.adapter.outbound.postgres.SpringActiveExploreOwnerSettlementTransactionPort
import cc.monomer.explore.api.*
import cc.monomer.explore.application.ExploreSettlementAdapter
import cc.monomer.explore.domain.ExploreRun
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.sql.Connection
import java.sql.SQLException
import org.springframework.jdbc.datasource.DataSourceUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.springframework.jdbc.datasource.DataSourceTransactionManager

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExecutionRunRestartPostgresIntegrationTest {
    private val admittedAt = Instant.parse("2026-09-07T00:00:00Z")
    private val recoveredAt = admittedAt.plusSeconds(120)

    @RegisterExtension
    val database: org.junit.jupiter.api.extension.Extension = object : BeforeEachCallback, AfterEachCallback {
        override fun beforeEach(context: ExtensionContext) {
            EphemeralPostgresAuthoritySupport.beforeAll(context)
            EphemeralPostgresAuthoritySupport.verifyEphemeralIdentity()
            EphemeralPostgresAuthoritySupport.applyAuthoritySchemasTwice()
        }

        override fun afterEach(context: ExtensionContext) {
            EphemeralPostgresAuthoritySupport.afterAll(context)
        }
    }

    @Test
    fun `workspace admits 256 nonterminal runs and preserves exact duplicate before rejecting overflow`() {
        val runtime = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        val workspace = WorkspaceId.generate()
        val commands = List(257) { runtime.capacityCommand(admittedAt, workspace) }
        runtime.inOwnerTransaction {
            commands.take(256).forEach { command ->
                assertThat(runtime.capacityAdmission.reserveInOwnerTransaction(command))
                    .isInstanceOf(ExecutionRunAdmissionPort.ReserveOutcome.Reserved::class.java)
            }
            assertThat(runtime.repository.countNonTerminalRuns(workspace)).isEqualTo(256)
            assertThat(runtime.capacityAdmission.reserveInOwnerTransaction(commands.first()))
                .isInstanceOf(ExecutionRunAdmissionPort.ReserveOutcome.Existing::class.java)
            assertThat(runtime.capacityAdmission.reserveInOwnerTransaction(commands.last()))
                .isEqualTo(ExecutionRunAdmissionPort.ReserveOutcome.Backpressured)
            assertThat(runtime.repository.find(commands.last().runId)).isNull()
            assertThat(runtime.capacityAdmission.reserveInOwnerTransaction(runtime.capacityCommand(admittedAt, WorkspaceId.generate())))
                .isInstanceOf(ExecutionRunAdmissionPort.ReserveOutcome.Reserved::class.java)
        }
    }

    @Test
    fun `two coordinators share eight database occupancies until cancelled stream cleanup releases one`() = runTest {
        val firstRuntime = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        val secondRuntime = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        val workspace = WorkspaceId.generate()
        val commands = List(9) { firstRuntime.capacityCommand(admittedAt, workspace) }
        firstRuntime.inOwnerTransaction {
            commands.forEach { command ->
                assertThat(firstRuntime.capacityAdmission.reserveInOwnerTransaction(command))
                    .isInstanceOf(ExecutionRunAdmissionPort.ReserveOutcome.Reserved::class.java)
            }
        }
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): Instant = admittedAt.plusMillis(testScheduler.currentTime)
        }
        val assignments = linkedMapOf<ExecutionRunId, ControlRunAssignment>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupFinished = CompletableDeferred<Unit>()
        val cancelledRunId = commands.first().runId
        val execution = mockk<ExecutionRunPort>()
        every { execution.run(any()) } answers {
            val assignment = firstArg<ControlRunAssignment>()
            flow<ExecutionRunEvent> {
                assignments[assignment.runId] = assignment
                try {
                    awaitCancellation()
                } finally {
                    if (assignment.runId == cancelledRunId) {
                        cleanupStarted.complete(Unit)
                        withContext(NonCancellable) { cleanupFinished.await() }
                    }
                }
            }
        }
        val credentials = mockk<ManagedCredentialReadPort>()
        coEvery { credentials.readExact(any()) } answers {
            ManagedCredentialReadOutcome.Found(
                CredentialMaterial.copyOf(mapOf("password" to "fixture-password".toByteArray())),
            )
        }
        fun coordinator(runtime: RuntimeFixture) = ExecutionRunDialCoordinator(
            execution, runtime.service, runtime.service, runtime.settlement, emptySet(),
            runtime.dispatch, credentials, ExactKeyArtifactFixture(),
            clock, backgroundScope, Duration.ofHours(1), 4096, 20, 100, 1000,
        )
        val firstCoordinator = coordinator(firstRuntime)
        val secondCoordinator = coordinator(secondRuntime)
        fun occupiedCount(runtime: RuntimeFixture): Int = runtime.inOwnerTransaction {
            runtime.repository.countExecutionOccupancy(workspace)
        }
        try {
            commands.forEachIndexed { index, command ->
                (if (index % 2 == 0) firstCoordinator else secondCoordinator).dispatchAfterCommit(command.runId)
            }
            runCurrent()
            assertThat(assignments.keys).containsExactlyElementsOf(commands.take(8).map { it.runId })
            assertThat(occupiedCount(firstRuntime)).isEqualTo(8)
            assertThat(occupiedCount(secondRuntime)).isEqualTo(8)
            assertThat(secondRuntime.service.get(commands.last().runId)?.executionOccupancyAcquiredAt).isNull()

            assertThat(secondRuntime.cancel(requireNotNull(secondRuntime.service.get(cancelledRunId)), "USER_CANCELLED", clock.instant()))
                .isInstanceOf(OwnerExecutionRunCancellationPort.CancellationOutcome.Applied::class.java)
            firstCoordinator.reconcileLiveDials()
            runCurrent()
            assertThat(cleanupStarted.isCompleted).isTrue()
            advanceTimeBy(10_000)
            runCurrent()
            val stopping = requireNotNull(secondRuntime.service.get(cancelledRunId))
            assertThat(stopping.state).isEqualTo(ExecutionRunState.CANCELLED)
            assertThat(stopping.executionOccupancyReleasedAt).isNull()
            assertThat(stopping.lastHeartbeatAt).isEqualTo(clock.instant())
            assertThat(occupiedCount(secondRuntime)).isEqualTo(8)
            assertThat(assignments).hasSize(8)

            cleanupFinished.complete(Unit)
            runCurrent()
            assertThat(secondRuntime.service.get(cancelledRunId)?.executionOccupancyReleasedAt).isNotNull()
            assertThat(assignments.getValue(cancelledRunId).credentialDocument).containsOnly(0.toByte())
            assertThat(occupiedCount(secondRuntime)).isEqualTo(7)
            advanceTimeBy(10_000)
            runCurrent()
            assertThat(assignments.keys).containsExactlyElementsOf(commands.map { it.runId })
            assertThat(occupiedCount(firstRuntime)).isEqualTo(8)
        } finally {
            cleanupFinished.complete(Unit)
            commands.forEach { command ->
                firstCoordinator.cancelLiveDial(command.runId)
                secondCoordinator.cancelLiveDial(command.runId)
            }
            runCurrent()
            firstCoordinator.close()
            secondCoordinator.close()
        }
        assertThat(occupiedCount(secondRuntime)).isZero()
        assertThat(commands.map { secondRuntime.service.get(it.runId)?.executionOccupancyReleasedAt }).doesNotContainNull()
    }

    @Test
    fun `dial path settles the Explore owner right after terminal persistence and recovery finds nothing left`() = runTest {
        val runtime = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        val run = runtime.admit(admittedAt)
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): Instant = admittedAt.plusMillis(testScheduler.currentTime)
        }
        val execution = mockk<ExecutionRunPort>()
        every { execution.run(any()) } answers {
            flow<ExecutionRunEvent> {
                kotlinx.coroutines.delay(2_000)
                emit(ExecutionRunEvent.Terminal(PhysicalOutcome(
                    "${run.id}:terminal", PhysicalOutcomeKind.FAILED, "PERMISSION_DENIED", "Warehouse permission denied",
                )))
            }
        }
        val credentials = mockk<ManagedCredentialReadPort>()
        coEvery { credentials.readExact(any()) } answers {
            ManagedCredentialReadOutcome.Found(
                CredentialMaterial.copyOf(mapOf("password" to "fixture-password".toByteArray())),
            )
        }
        val coordinator = ExecutionRunDialCoordinator(
            execution, runtime.service, runtime.service, runtime.settlement, setOf(ExecutionOwnerType.EXPLORE),
            runtime.dispatch, credentials, ExactKeyArtifactFixture(),
            clock, backgroundScope, Duration.ofHours(1), 4096, 20, 100, 1000,
        )
        try {
            coordinator.dispatchAfterCommit(run.id)
            runCurrent()
            assertThat(runtime.service.get(run.id)?.physicalOutcome).isNull()
            advanceTimeBy(2_001)
            runCurrent()

            val settled = requireNotNull(runtime.service.get(run.id))
            assertThat(settled.state).isEqualTo(ExecutionRunState.FAILED)
            assertThat(settled.ownerSettledAt).isEqualTo(admittedAt.plusSeconds(2))
            assertThat(settled.executionOccupancyReleasedAt).isNotNull()
            val owner = requireNotNull(runtime.owners.findRun(ExploreRunId(run.id.value)))
            assertThat(owner.state).isEqualTo(ExploreRunState.FAILED)
            assertThat(owner.failureCode).isEqualTo(ExploreRunFailureCode.PERMISSION_DENIED)
            assertThat(owner.updatedAt).isEqualTo(settled.ownerSettledAt)

            val recovery = runtime.recovery(ExactKeyArtifactFixture(), admittedAt.plusSeconds(30))
            repeat(2) { invokeCycle(recovery) }
            assertThat(recovery.snapshot().candidateFailureType).isNull()
            assertThat(recovery.snapshot().cycleFailureType).isNull()
            assertThat(runtime.service.get(run.id)?.version).isEqualTo(settled.version)
            assertThat(runtime.owners.findRun(ExploreRunId(run.id.value))?.revision).isEqualTo(owner.revision)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `an unreadable run row is skipped while recovery terminalizes and settles the other stale run`() = runBlocking {
        val runtime = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        val unreadable = runtime.admit(admittedAt)
        val readable = runtime.admit(admittedAt)
        listOf(unreadable, readable).forEach { run -> runtime.claim(run, admittedAt) }
        runtime.dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE execution_run.runs SET connector_properties = ? WHERE run_id = ?").use { statement ->
                statement.setBytes(1, byteArrayOf(0))
                statement.setObject(2, java.util.UUID.fromString(unreadable.id.toString()))
                check(statement.executeUpdate() == 1)
            }
        }
        val recovery = runtime.recovery(ExactKeyArtifactFixture(), recoveredAt)

        runRecoveryUntilSettled(recovery, runtime, listOf(readable.id))

        assertThat(recovery.snapshot().unreadableRuns).isEqualTo(1)
        assertThat(recovery.snapshot().failedCandidates).isZero()
        assertThat(recovery.snapshot().lastCompletedCycleAt).isEqualTo(recoveredAt)
        val recovered = requireNotNull(runtime.service.get(readable.id))
        assertThat(recovered.state).isEqualTo(ExecutionRunState.FAILED)
        assertThat(recovered.physicalOutcome?.failureCode).isEqualTo("EXECUTION_ORPHANED")
        assertThat(recovered.executionOccupancyReleasedAt).isNotNull()
        Unit
    }

    @Test
    fun `settlement with a clock behind the terminal record commits the owner and the run together`() = runBlocking {
        val runtime = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        val run = runtime.admit(admittedAt)
        runtime.claim(run, admittedAt)
        val terminalAt = admittedAt.plusSeconds(5)
        assertThat(runtime.service.recordPhysicalOutcome(ExecutionRunUseCase.RecordPhysicalOutcomeCommand(
            run.id,
            PhysicalOutcome("${run.id}:terminal", PhysicalOutcomeKind.FAILED, "PERMISSION_DENIED", "Warehouse permission denied"),
            terminalAt,
        ))).isInstanceOf(ExecutionRunUseCase.TerminalOutcome.Accepted::class.java)

        val outcome = runtime.settlement.settle(run.id, terminalAt.minusSeconds(1))

        assertThat(outcome).isInstanceOf(ExecutionRunSettlementUseCase.SettlementOutcome.Completed::class.java)
        assertThat(runtime.service.get(run.id)?.ownerSettledAt).isEqualTo(terminalAt)
        val owner = requireNotNull(runtime.owners.findRun(ExploreRunId(run.id.value)))
        assertThat(owner.state).isEqualTo(ExploreRunState.FAILED)
        assertThat(owner.updatedAt).isEqualTo(terminalAt)
        Unit
    }

    @Test
    fun `an owner conflict after its write rolls the owner write back and keeps the run pending`() = runBlocking {
        val runtime = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        val run = runtime.admit(admittedAt)
        runtime.claim(run, admittedAt)
        assertThat(runtime.service.recordPhysicalOutcome(ExecutionRunUseCase.RecordPhysicalOutcomeCommand(
            run.id,
            PhysicalOutcome("${run.id}:terminal", PhysicalOutcomeKind.FAILED, "PERMISSION_DENIED", "Warehouse permission denied"),
            admittedAt.plusSeconds(2),
        ))).isInstanceOf(ExecutionRunUseCase.TerminalOutcome.Accepted::class.java)
        val explore = ExploreSettlementAdapter(
            runtime.owners, SpringActiveExploreOwnerSettlementTransactionPort(runtime.dataSource), Duration.ofHours(1),
        )
        val conflictingAfterWrite = object : ExecutionRunOwnerSettlementPort {
            override val ownerType = ExecutionOwnerType.EXPLORE
            override fun settleInOwnerTransaction(command: ExecutionRunOwnerSettlementPort.SettleCommand): ExecutionRunOwnerSettlementPort.SettlementOutcome {
                assertThat(explore.settleInOwnerTransaction(command))
                    .isEqualTo(ExecutionRunOwnerSettlementPort.SettlementOutcome.Completed)
                return ExecutionRunOwnerSettlementPort.SettlementOutcome.Conflict
            }
        }
        val settlement = ExecutionRunSettlementCoordinator(
            listOf(conflictingAfterWrite), runtime.repository,
            SpringOwnerSettlementTransactionRunner(runtime.dataSource, 5, runtime.jdbcDispatcher),
        )

        assertThat(settlement.settle(run.id, admittedAt.plusSeconds(3)))
            .isEqualTo(ExecutionRunSettlementUseCase.SettlementOutcome.Conflict)

        assertThat(runtime.owners.findRun(ExploreRunId(run.id.value))?.state).isEqualTo(ExploreRunState.RUNNING)
        assertThat(runtime.service.get(run.id)?.ownerSettledAt).isNull()
        assertThat(runtime.settlement.settle(run.id, admittedAt.plusSeconds(4)))
            .isInstanceOf(ExecutionRunSettlementUseCase.SettlementOutcome.Completed::class.java)
        Unit
    }

    @Test
    fun `racing immediate and recovery settlements commit the owner terminal exactly once`() = runBlocking {
        val runtime = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        val run = runtime.admit(admittedAt)
        runtime.claim(run, admittedAt)
        assertThat(runtime.service.recordPhysicalOutcome(ExecutionRunUseCase.RecordPhysicalOutcomeCommand(
            run.id,
            PhysicalOutcome("${run.id}:terminal", PhysicalOutcomeKind.FAILED, "PERMISSION_DENIED", "Warehouse permission denied"),
            admittedAt.plusSeconds(2),
        ))).isInstanceOf(ExecutionRunUseCase.TerminalOutcome.Accepted::class.java)
        val settlers = java.util.concurrent.Executors.newFixedThreadPool(8)
        val outcomes = try {
            val start = java.util.concurrent.CountDownLatch(1)
            val pending = List(8) { index ->
                CompletableFuture.supplyAsync({
                    start.await()
                    runBlocking { runtime.settlement.settle(run.id, admittedAt.plusSeconds(3).plusMillis(index.toLong())) }
                }, settlers)
            }
            start.countDown()
            pending.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            settlers.shutdownNow()
        }

        val completed = outcomes.filterIsInstance<ExecutionRunSettlementUseCase.SettlementOutcome.Completed>()
        assertThat(completed).hasSize(1)
        assertThat(outcomes).allMatch { outcome ->
            outcome is ExecutionRunSettlementUseCase.SettlementOutcome.Completed ||
                outcome is ExecutionRunSettlementUseCase.SettlementOutcome.Duplicate ||
                outcome == ExecutionRunSettlementUseCase.SettlementOutcome.ConcurrentConflict
        }
        val settled = requireNotNull(runtime.service.get(run.id))
        assertThat(settled.ownerSettledAt).isEqualTo(completed.single().snapshot.ownerSettledAt)
        val owner = requireNotNull(runtime.owners.findRun(ExploreRunId(run.id.value)))
        assertThat(owner.state).isEqualTo(ExploreRunState.FAILED)
        assertThat(owner.revision).isEqualTo(1)
        assertThat(owner.updatedAt).isEqualTo(settled.ownerSettledAt)
        assertThat(runtime.settlement.settle(run.id, admittedAt.plusSeconds(4)))
            .isInstanceOf(ExecutionRunSettlementUseCase.SettlementOutcome.Duplicate::class.java)
        assertThat(runtime.service.get(run.id)?.version).isEqualTo(settled.version)
        Unit
    }

    @Test
    fun `narrowed execution deadline survives restart and cannot override heartbeat or terminal`() = runBlocking {
        val runtime = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        val run = runtime.admit(admittedAt)
        runtime.claim(run, admittedAt)
        runtime.service.recordProgress(ExecutionRunUseCase.RecordRunProgressCommand(
            run.id, 0u, 0u, "executing", admittedAt.plusSeconds(5),
        ))
        val deadline = admittedAt.plusSeconds(120)
        val result = runtime.service.narrowExecutionDeadline(ExecutionRunUseCase.NarrowExecutionDeadlineCommand(
            run.id, deadline, admittedAt.plusSeconds(4),
        ))
        assertThat(result).isInstanceOf(ExecutionRunUseCase.RunMutationOutcome.Applied::class.java)
        val restarted = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        val persisted = checkNotNull(restarted.service.get(run.id))
        assertThat(persisted.deadline).isEqualTo(deadline)
        assertThat(persisted.updatedAt).isEqualTo(admittedAt.plusSeconds(5))
        assertThat(persisted.lastHeartbeatAt).isEqualTo(admittedAt.plusSeconds(5))
        assertThat(restarted.service.narrowExecutionDeadline(ExecutionRunUseCase.NarrowExecutionDeadlineCommand(
            run.id, deadline.plusSeconds(1), admittedAt.plusSeconds(6),
        ))).isEqualTo(ExecutionRunUseCase.RunMutationOutcome.InvalidState)
        restarted.cancel(run, "User cancelled", admittedAt.plusSeconds(6))
        assertThat(restarted.service.narrowExecutionDeadline(ExecutionRunUseCase.NarrowExecutionDeadlineCommand(
            run.id, deadline.minusSeconds(1), admittedAt.plusSeconds(7),
        ))).isEqualTo(ExecutionRunUseCase.RunMutationOutcome.InvalidState)
        assertThat(restarted.service.get(run.id)?.state).isEqualTo(cc.monomer.execution.api.ExecutionRunState.CANCELLED)
        Unit
    }

    @Test
    fun `recreated recovery settles persisted orphan and preserves successful artifact`() = runBlocking {
        val artifacts = ExactKeyArtifactFixture()
        val beforeRestart = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        val orphan = beforeRestart.admit(admittedAt)
        val success = beforeRestart.admit(admittedAt)
        beforeRestart.claim(orphan, admittedAt)
        beforeRestart.claim(success, admittedAt)
        artifacts.objects += listOf(key(orphan), key(success), key(orphan) + ".unrelated")
        assertThat(beforeRestart.service.recordPhysicalOutcome(ExecutionRunUseCase.RecordPhysicalOutcomeCommand(
            success.id,
            PhysicalOutcome("${success.id}:terminal", PhysicalOutcomeKind.SUCCEEDED, null, null,
                ArrowResultManifest(key(success), 128u, "immutable-etag", 1u, 1, "application/vnd.apache.arrow.file")),
            admittedAt.plusSeconds(1),
        ))).isInstanceOf(ExecutionRunUseCase.TerminalOutcome.Accepted::class.java)

        // A new DataSource and every runtime/owner object reconstruct state solely from PostgreSQL.
        val restarted = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        val recovery = restarted.recovery(artifacts, recoveredAt)
        runRecoveryUntilSettled(recovery, restarted, listOf(orphan.id, success.id))
        assertThat(artifacts.objects).contains(key(orphan))
        val cleanupAt = admittedAt.plusSeconds(601)
        runRecoveryUntilSettled(restarted.recovery(artifacts, cleanupAt), restarted, listOf(orphan.id, success.id))
        val failed = requireNotNull(restarted.service.get(orphan.id))
        assertThat(failed.physicalOutcome?.failureCode).isEqualTo("EXECUTION_ORPHANED")
        val failedOwner = requireNotNull(restarted.owners.findRun(ExploreRunId(orphan.id.value)))
        assertThat(failedOwner.state).isEqualTo(ExploreRunState.FAILED)
        assertThat(failedOwner.failureCode).isEqualTo(ExploreRunFailureCode.EXECUTION_ORPHANED)
        assertThat(failedOwner.visibleResult).isNull()
        val succeededOwner = requireNotNull(restarted.owners.findRun(ExploreRunId(success.id.value)))
        assertThat(succeededOwner.state).isEqualTo(ExploreRunState.SUCCEEDED)
        assertThat(succeededOwner.visibleResult?.objectKey).isEqualTo(key(success))
        assertThat(artifacts.objects).containsExactlyInAnyOrder(key(success), key(orphan) + ".unrelated")
        assertThat(artifacts.deletedKeys).isNotEmpty().allMatch { it == key(orphan) }

        val settledVersion = failed.version
        val deletionCount = artifacts.deletedKeys.size
        val secondRestart = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        val repeatedRecovery = secondRestart.recovery(artifacts, cleanupAt.plusSeconds(1))
        runRecoveryUntilSettled(repeatedRecovery, secondRestart, listOf(orphan.id, success.id))
        assertThat(secondRestart.service.get(orphan.id)?.version).isEqualTo(settledVersion)
        assertThat(secondRestart.owners.findRun(ExploreRunId(orphan.id.value))?.revision).isEqualTo(failedOwner.revision)
        assertThat(artifacts.deletedKeys).hasSize(deletionCount)
        val newRun = secondRestart.admit(recoveredAt.plusSeconds(2))
        secondRestart.claim(newRun, recoveredAt.plusSeconds(2))
        assertThat(secondRestart.service.get(newRun.id)?.dispatchClaimedAt).isNotNull()
        Unit
    }

    @Test
    fun `recreated recovery enforces persisted dispatch and execution deadlines`() = runBlocking {
        val artifacts = ExactKeyArtifactFixture()
        val original = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        val unclaimed = original.admit(admittedAt)
        val expiredExecution = original.admit(admittedAt.minusSeconds(600))
        original.claim(expiredExecution, admittedAt.minusSeconds(600))
        artifacts.objects += key(expiredExecution)

        val restarted = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        runRecoveryUntilSettled(restarted.recovery(artifacts, recoveredAt), restarted, listOf(unclaimed.id, expiredExecution.id))
        assertThat(restarted.service.get(unclaimed.id)?.physicalOutcome?.failureCode).isEqualTo("EXECUTION_UNAVAILABLE")
        assertThat(restarted.service.get(expiredExecution.id)?.physicalOutcome?.failureCode).isEqualTo("EXECUTION_DEADLINE_EXCEEDED")
        for (run in listOf(unclaimed, expiredExecution)) {
            assertThat(restarted.owners.findRun(ExploreRunId(run.id.value))?.state).isEqualTo(ExploreRunState.FAILED)
            assertThat(restarted.owners.findRun(ExploreRunId(run.id.value))?.failureCode).isEqualTo(ExploreRunFailureCode.EXECUTION_FAILED)
        }
        assertThat(artifacts.objects).isEmpty()
        assertThat(artifacts.deletedKeys).contains(key(expiredExecution))
        assertThat(artifacts.deletedKeys).allMatch { it == key(unclaimed) || it == key(expiredExecution) }
        Unit
    }

    @Test
    fun `delete failure remains pending after owner settlement and recreation until exact key deletion succeeds`() = runBlocking {
        val original = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        val run = original.admit(admittedAt)
        original.claim(run, admittedAt)
        val artifacts = ExactKeyArtifactFixture().apply {
            objects += listOf(key(run), key(run) + ".unrelated")
            failDeletes = true
        }
        val afterDeadline = admittedAt.plusSeconds(601)
        val recovery = original.recovery(artifacts, afterDeadline)
        repeat(3) { invokeCycle(recovery) }
        val terminal = requireNotNull(original.service.get(run.id))
        assertThat(terminal.ownerSettledAt).isNotNull()
        assertThat(original.owners.findRun(ExploreRunId(run.id.value))?.state).isEqualTo(ExploreRunState.FAILED)
        assertThat(recovery.snapshot().failedCandidates).isPositive()
        assertThat(recovery.snapshot().candidateFailureType).isNotNull()
        assertThat(recovery.snapshot().cycleFailureType).isNull()
        assertThat(artifacts.objects).contains(key(run))

        val restarted = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        assertThat(restarted.cleanupCandidates(afterDeadline).map { it.id }).contains(run.id)
        artifacts.failDeletes = false
        runRecoveryUntilSettled(restarted.recovery(artifacts, afterDeadline), restarted, listOf(run.id))
        assertThat(restarted.cleanupCandidates(afterDeadline).map { it.id }).doesNotContain(run.id)
        assertThat(artifacts.objects).containsExactly(key(run) + ".unrelated")
        val deletionCount = artifacts.deletedKeys.size
        invokeCycle(RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource()).recovery(artifacts, afterDeadline))
        assertThat(artifacts.deletedKeys).hasSize(deletionCount)
        Unit
    }

    @Test
    fun `late terminal invalidates cleanup completion and fences concurrent completion without changing failure`() = runBlocking {
        val runtime = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        val run = runtime.admit(admittedAt)
        runtime.claim(run, admittedAt)
        val afterDeadline = admittedAt.plusSeconds(601)
        val artifacts = ExactKeyArtifactFixture()
        runRecoveryUntilSettled(runtime.recovery(artifacts, afterDeadline), runtime, listOf(run.id))
        val completed = requireNotNull(runtime.service.get(run.id))
        val lateSuccess = ExecutionRunUseCase.RecordPhysicalOutcomeCommand(
            run.id,
            PhysicalOutcome("${run.id}:terminal", PhysicalOutcomeKind.SUCCEEDED, null, null,
                ArrowResultManifest(key(run), 128u, "late-etag", 1u, 1, "application/vnd.apache.arrow.file")),
            afterDeadline.plusSeconds(1),
        )
        artifacts.objects += key(run)
        assertThat(runtime.service.recordPhysicalOutcome(lateSuccess))
            .isEqualTo(ExecutionRunUseCase.TerminalOutcome.TerminalConflict)
        val pending = runtime.cleanupCandidates(afterDeadline).single { it.id == run.id }
        assertThat(pending.version).isGreaterThan(completed.version)
        artifacts.beforeDelete = {
            runtime.service.recordPhysicalOutcome(lateSuccess)
            artifacts.beforeDelete = null
        }
        val recovery = runtime.recovery(artifacts, afterDeadline.plusSeconds(2))
        invokeCycle(recovery)
        assertThat(recovery.snapshot().failedCandidates).isZero()
        assertThat(recovery.snapshot().candidateFailureType).isNull()
        assertThat(runtime.cleanupCandidates(afterDeadline).map { it.id }).contains(run.id)
        invokeCycle(recovery)
        assertThat(runtime.cleanupCandidates(afterDeadline).map { it.id }).doesNotContain(run.id)
        assertThat(runtime.service.get(run.id)?.state).isEqualTo(ExecutionRunState.FAILED)
        assertThat(runtime.service.get(run.id)?.ownerSettledAt).isEqualTo(completed.ownerSettledAt)
        assertThat(artifacts.objects).doesNotContain(key(run))
        Unit
    }

    @Test
    fun `cleanup completion rejects successful runs stale versions and predeadline cancelled runs`() = runBlocking {
        val runtime = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        val success = runtime.admit(admittedAt)
        val cancelled = runtime.admit(admittedAt)
        runtime.claim(success, admittedAt)
        runtime.claim(cancelled, admittedAt)
        runtime.service.recordPhysicalOutcome(ExecutionRunUseCase.RecordPhysicalOutcomeCommand(
            success.id,
            PhysicalOutcome("${success.id}:terminal", PhysicalOutcomeKind.SUCCEEDED, null, null,
                ArrowResultManifest(key(success), 128u, "success-etag", 1u, 1, "application/vnd.apache.arrow.file")),
            admittedAt.plusSeconds(2),
        ))
        runtime.cancel(cancelled, "USER_CANCELLED", admittedAt.plusSeconds(2))
        val terminalSuccess = requireNotNull(runtime.service.get(success.id))
        val terminalCancel = requireNotNull(runtime.service.get(cancelled.id))
        val afterDeadline = admittedAt.plusSeconds(601)
        assertThat(runtime.service.completeArtifactCleanup(ExecutionRunUseCase.CompleteArtifactCleanupCommand(
            success.id, terminalSuccess.version, afterDeadline,
        ))).isFalse()
        assertThat(runtime.service.completeArtifactCleanup(ExecutionRunUseCase.CompleteArtifactCleanupCommand(
            cancelled.id, terminalCancel.version, admittedAt.plusSeconds(3),
        ))).isFalse()
        assertThat(runtime.service.completeArtifactCleanup(ExecutionRunUseCase.CompleteArtifactCleanupCommand(
            cancelled.id, terminalCancel.version - 1, afterDeadline,
        ))).isFalse()
        assertThat(runtime.cleanupCandidates(afterDeadline).map { it.id }).contains(cancelled.id).doesNotContain(success.id)
        val artifacts = ExactKeyArtifactFixture().apply { objects += listOf(key(success), key(cancelled)) }
        runRecoveryUntilSettled(runtime.recovery(artifacts, afterDeadline), runtime, listOf(success.id, cancelled.id))
        assertThat(artifacts.objects).containsExactly(key(success))
        assertThat(runtime.service.get(cancelled.id)?.state).isEqualTo(ExecutionRunState.CANCELLED)
        assertThat(runtime.cleanupCandidates(afterDeadline).map { it.id }).doesNotContain(cancelled.id)
        Unit
    }

    @Test
    fun `batch one retries permanent failure without starving another key across restart`() = runBlocking {
        val runtime = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        val runs = listOf(runtime.admit(admittedAt), runtime.admit(admittedAt))
        for (run in runs) {
            runtime.claim(run, admittedAt)
            runtime.service.recordPhysicalOutcome(ExecutionRunUseCase.RecordPhysicalOutcomeCommand(
                run.id, PhysicalOutcome("${run.id}:terminal", PhysicalOutcomeKind.FAILED, "EXECUTION_ORPHANED", "fixture failure"),
                admittedAt.plusSeconds(2),
            ))
            val terminal = requireNotNull(runtime.service.get(run.id))
            assertThat(runtime.service.releaseExecutionOccupancy(ExecutionRunUseCase.ReleaseExecutionOccupancyCommand(
                run.id, requireNotNull(terminal.executionOccupancyAcquiredAt), admittedAt.plusSeconds(2),
            ))).isInstanceOf(ExecutionRunUseCase.RunMutationOutcome.Applied::class.java)
        }
        val artifacts = ExactKeyArtifactFixture().apply { objects += runs.map(::key) }
        runRecoveryUntilSettled(runtime.recovery(artifacts, admittedAt.plusSeconds(3)), runtime, runs.map { it.id })
        val afterDeadline = admittedAt.plusSeconds(601)
        val ordered = runtime.cleanupCandidates(afterDeadline).filter { candidate -> runs.any { it.id == candidate.id } }
        val failing = ordered[0]
        val succeeding = ordered[1]
        artifacts.failedKeys += key(failing)
        runtime.maximumCleanupRunsPerCycle = 1
        invokeCycle(runtime.recovery(artifacts, afterDeadline))
        assertThat(artifacts.deletedKeys).containsExactly(key(failing))
        assertThat(artifacts.objects).contains(key(failing), key(succeeding))

        val restarted = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        restarted.maximumCleanupRunsPerCycle = 1
        invokeCycle(restarted.recovery(artifacts, afterDeadline.plusSeconds(1)))
        assertThat(artifacts.deletedKeys).containsExactly(key(failing), key(succeeding))
        assertThat(artifacts.objects).containsExactly(key(failing))
        assertThat(restarted.cleanupCandidates(afterDeadline).map { it.id }).contains(failing.id).doesNotContain(succeeding.id)
        invokeCycle(restarted.recovery(artifacts, afterDeadline.plusSeconds(2)))
        assertThat(artifacts.deletedKeys).containsExactly(key(failing), key(succeeding), key(failing))
        artifacts.failedKeys.clear()
        invokeCycle(restarted.recovery(artifacts, afterDeadline.plusSeconds(3)))
        assertThat(restarted.cleanupCandidates(afterDeadline).map { it.id }).doesNotContain(failing.id)
        assertThat(artifacts.objects).isEmpty()
        Unit
    }

    @Test
    fun `completion committed after stale observation is invalidated by current terminal row lock`() = runBlocking {
        val runtime = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        val run = runtime.admit(admittedAt)
        runtime.claim(run, admittedAt)
        val afterDeadline = admittedAt.plusSeconds(601)
        val artifacts = ExactKeyArtifactFixture()
        runRecoveryUntilSettled(runtime.recovery(artifacts, afterDeadline), runtime, listOf(run.id))
        val lateSuccess = ExecutionRunUseCase.RecordPhysicalOutcomeCommand(
            run.id,
            PhysicalOutcome("${run.id}:terminal", PhysicalOutcomeKind.SUCCEEDED, null, null,
                ArrowResultManifest(key(run), 128u, "late-etag", 1u, 1, "application/vnd.apache.arrow.file")),
            afterDeadline.plusSeconds(1),
        )
        runtime.service.recordPhysicalOutcome(lateSuccess)
        val pending = requireNotNull(runtime.service.get(run.id))
        assertThat(runtime.service.beginArtifactCleanup(ExecutionRunUseCase.BeginArtifactCleanupCommand(
            run.id, pending.version, afterDeadline.plusSeconds(2),
        ))).isTrue()
        val deleting = requireNotNull(runtime.service.get(run.id))
        val other = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        var interleaved = false
        val observedThenCompleted = object : ExecutionRunRepository by runtime.repository {
            override fun findForUpdate(runId: ExecutionRunId): AdmittedExecutionRun? {
                val observed = runtime.repository.find(runId)
                if (runId == run.id && !interleaved) {
                    interleaved = true
                    assertThat(CompletableFuture.supplyAsync {
                        runBlocking {
                            other.service.completeArtifactCleanup(ExecutionRunUseCase.CompleteArtifactCleanupCommand(
                                run.id, deleting.version, afterDeadline.plusSeconds(3),
                            ))
                        }
                    }.get(5, TimeUnit.SECONDS)).isTrue()
                    artifacts.objects += key(run)
                }
                val locked = runtime.repository.findForUpdate(runId)
                assertThat(locked?.snapshot?.version).isGreaterThan(requireNotNull(observed).snapshot.version)
                return locked
            }
        }
        val lateRuntime = runtime.serviceWithRepository(observedThenCompleted)
        assertThat(lateRuntime.recordPhysicalOutcome(lateSuccess))
            .isEqualTo(ExecutionRunUseCase.TerminalOutcome.TerminalConflict)
        assertThat(interleaved).isTrue()
        assertThat(other.cleanupCandidates(afterDeadline).map { it.id }).contains(run.id)
        runRecoveryUntilSettled(other.recovery(artifacts, afterDeadline.plusSeconds(4)), other, listOf(run.id))
        assertThat(artifacts.objects).doesNotContain(key(run))
        Unit
    }

    @Test
    fun `production mutation wiring uses a new read committed transaction and restores repeatable read owner context`() {
        val dataSource = EphemeralPostgresAuthoritySupport.newDataSource()
        val runtime = RuntimeFixture(dataSource, JdbcVirtualThreadDispatcher(dataSource))
        runtime.inOwnerTransaction {
            val outer = DataSourceUtils.getConnection(runtime.dataSource)
            assertThat(outer.transactionIsolation).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ)
            runBlocking {
                runtime.mutationRunner.run {
                    val mutation = DataSourceUtils.getConnection(runtime.dataSource)
                    assertThat(mutation).isNotSameAs(outer)
                    assertThat(mutation.transactionIsolation).isEqualTo(Connection.TRANSACTION_READ_COMMITTED)
                }
            }
            assertThat(DataSourceUtils.getConnection(runtime.dataSource)).isSameAs(outer)
            assertThat(outer.transactionIsolation).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ)
        }
    }

    @Test
    fun `production read wiring is a read only snapshot on a JDBC thread`() = runBlocking {
        val dataSource = EphemeralPostgresAuthoritySupport.newDataSource()
        val runtime = RuntimeFixture(dataSource, JdbcVirtualThreadDispatcher(dataSource))
        val callerThread = Thread.currentThread()
        val observed = runtime.readRunner.run {
            val connection = DataSourceUtils.getConnection(runtime.dataSource)
            Triple(connection.isReadOnly, connection.transactionIsolation, Thread.currentThread())
        }
        assertThat(observed.first).isTrue()
        assertThat(observed.second).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ)
        assertThat(observed.third).isNotSameAs(callerThread)
        assertThat(observed.third.isVirtual).isTrue()
        Unit
    }

    @Test
    fun `a dispatch claim locks the current row so a concurrent run update waits instead of failing the claim`() = runBlocking {
        val runtime = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        val run = runtime.admit(admittedAt)
        val writerBackend = AtomicInteger(0)
        var concurrentWrite: CompletableFuture<Int>? = null
        val racing = object : ExecutionRunRepository by runtime.repository {
            override fun findForUpdate(runId: ExecutionRunId): AdmittedExecutionRun? {
                val locked = runtime.repository.findForUpdate(runId)
                concurrentWrite = CompletableFuture.supplyAsync {
                    runtime.dataSource.connection.use { connection ->
                        connection.createStatement().use { statement ->
                            statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                                check(rows.next())
                                writerBackend.set(rows.getInt(1))
                            }
                        }
                        connection.prepareStatement(
                            "UPDATE execution_run.runs SET last_heartbeat_at = last_heartbeat_at WHERE run_id = ?",
                        ).use { statement ->
                            statement.setObject(1, java.util.UUID.fromString(runId.toString()))
                            statement.executeUpdate()
                        }
                    }
                }
                awaitBlockedBackend(runtime.dataSource, writerBackend)
                return locked
            }
        }

        val claim = runtime.serviceWithRepository(racing).claimDispatch(
            ExecutionRunUseCase.ClaimRunDispatchCommand(run.id, admittedAt.plusSeconds(1), admittedAt.plusSeconds(600)),
        )

        assertThat(claim).isInstanceOf(ExecutionRunUseCase.RunMutationOutcome.Applied::class.java)
        assertThat(requireNotNull(concurrentWrite).get(5, TimeUnit.SECONDS)).isEqualTo(1)
        assertThat(runtime.service.get(run.id)?.dispatchClaimedAt).isEqualTo(admittedAt.plusSeconds(1))
        Unit
    }

    private fun awaitBlockedBackend(dataSource: DataSource, backend: AtomicInteger) {
        val waitDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        var blocked = false
        dataSource.connection.use { observer ->
            while (!blocked && System.nanoTime() < waitDeadline) {
                observer.prepareStatement("SELECT cardinality(pg_blocking_pids(?))").use { statement ->
                    statement.setInt(1, backend.get())
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        blocked = rows.getInt(1) > 0
                    }
                }
                if (!blocked) Thread.sleep(10)
            }
        }
        assertThat(blocked).isTrue()
    }

    @Test
    fun `terminal row lock serializes late invalidation ahead of concurrent repeatable read cleanup completion`() = runBlocking {
        val runtime = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        val run = runtime.admit(admittedAt)
        runtime.claim(run, admittedAt)
        val afterDeadline = admittedAt.plusSeconds(601)
        val artifacts = ExactKeyArtifactFixture()
        runRecoveryUntilSettled(runtime.recovery(artifacts, afterDeadline), runtime, listOf(run.id))
        val lateSuccess = ExecutionRunUseCase.RecordPhysicalOutcomeCommand(
            run.id,
            PhysicalOutcome("${run.id}:terminal", PhysicalOutcomeKind.SUCCEEDED, null, null,
                ArrowResultManifest(key(run), 128u, "late-etag", 1u, 1, "application/vnd.apache.arrow.file")),
            afterDeadline.plusSeconds(1),
        )
        runtime.service.recordPhysicalOutcome(lateSuccess)
        val pending = requireNotNull(runtime.service.get(run.id))
        assertThat(runtime.service.beginArtifactCleanup(ExecutionRunUseCase.BeginArtifactCleanupCommand(
            run.id, pending.version, afterDeadline.plusSeconds(2),
        ))).isTrue()
        val deleting = requireNotNull(runtime.service.get(run.id))
        val other = RuntimeFixture(EphemeralPostgresAuthoritySupport.newDataSource())
        val completionBackend = AtomicInteger(0)
        var completion: CompletableFuture<Boolean>? = null
        val lockedThenCompleted = object : ExecutionRunRepository by runtime.repository {
            override fun findForUpdate(runId: ExecutionRunId): AdmittedExecutionRun? {
                val locked = runtime.repository.findForUpdate(runId)
                completion = CompletableFuture.supplyAsync {
                    other.inOwnerTransaction {
                        val connection = DataSourceUtils.getConnection(other.dataSource)
                        connection.createStatement().use { statement ->
                            statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                                check(rows.next())
                                completionBackend.set(rows.getInt(1))
                            }
                        }
                        other.repository.completeArtifactCleanup(run.id, deleting.version, afterDeadline.plusSeconds(3))
                    }
                }
                val waitDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
                var blocked = false
                other.dataSource.connection.use { observer ->
                    while (!blocked && System.nanoTime() < waitDeadline) {
                        observer.prepareStatement("SELECT cardinality(pg_blocking_pids(?))").use { statement ->
                            statement.setInt(1, completionBackend.get())
                            statement.executeQuery().use { rows ->
                                check(rows.next())
                                blocked = rows.getInt(1) > 0
                            }
                        }
                        if (!blocked) Thread.sleep(10)
                    }
                }
                assertThat(blocked).isTrue()
                return locked
            }
        }
        assertThat(runtime.serviceWithRepository(lockedThenCompleted).recordPhysicalOutcome(lateSuccess))
            .isEqualTo(ExecutionRunUseCase.TerminalOutcome.TerminalConflict)
        val completionFailure = runCatching { requireNotNull(completion).get(5, TimeUnit.SECONDS) }.exceptionOrNull()
        assertThat(generateSequence(completionFailure) { it.cause }.filterIsInstance<SQLException>().map { it.sqlState }.toList())
            .contains("40001")
        assertThat(other.cleanupCandidates(afterDeadline).map { it.id }).contains(run.id)
        artifacts.objects += key(run)
        runRecoveryUntilSettled(other.recovery(artifacts, afterDeadline.plusSeconds(4)), other, listOf(run.id))
        assertThat(artifacts.objects).doesNotContain(key(run))
        Unit
    }

    private suspend fun runRecoveryUntilSettled(
        lifecycle: ExecutionRunRecoveryLifecycle,
        runtime: RuntimeFixture,
        runIds: List<ExecutionRunId>,
    ) {
        repeat(4) { invokeCycle(lifecycle) }
        assertThat(runIds.map { runtime.service.get(it)?.ownerSettledAt }).doesNotContainNull()
        assertThat(lifecycle.snapshot().candidateFailureType).isNull()
        assertThat(lifecycle.snapshot().cycleFailureType).isNull()
    }

    private fun invokeCycle(lifecycle: ExecutionRunRecoveryLifecycle) {
        ExecutionRunRecoveryLifecycle::class.java.getDeclaredMethod("runCycleSafely").apply {
            isAccessible = true
        }.invoke(lifecycle)
    }

    private fun key(run: ExecutionRunSnapshot) = requireNotNull(run.runtimeBindings.resultSink.objectKey)

    /** Dial tests drive virtual time, so their JDBC work stays on the test thread unless a test opts into the production dispatcher. */
    private class RuntimeFixture(val dataSource: DataSource, val jdbcDispatcher: CoroutineDispatcher = Dispatchers.Unconfined) {
        private val properties = ControlProductAuthorityProperties(
            ControlProductPostgresProperties("jdbc:postgresql://unused/fixture", "fixture", "fixture-only", 2,
                Duration.ofSeconds(1), Duration.ofMillis(500)),
            ControlProductTransactionProperties(Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(4)),
        )
        private val configuration = ControlExecutionRuntimeConfiguration()
        private val ownerTransactions = ControlProductAuthorityConfiguration().productAuthorityTransactionOperations(
            DataSourceTransactionManager(dataSource), properties,
        )
        val mutationRunner = configuration.executionRunMutationTransactionRunner(dataSource, properties, jdbcDispatcher)
        val readRunner = configuration.executionRunReadTransactionRunner(dataSource, properties, jdbcDispatcher)
        val repository = PostgresExecutionRuntimeStore(dataSource)
        val dispatch = configuration.postgresRunDispatchPersistence(dataSource, ownerTransactions)
        private val codec = ExecutableRequestAssignmentCodec()
        val service = configuration.executionRunService(repository, dispatch, codec, mutationRunner, readRunner)
        fun serviceWithRepository(store: ExecutionRunRepository) = ExecutionRunService(store, mutationRunner, readRunner, dispatch, codec)
        val owners = PostgresExploreRunRepository(dataSource)
        val settlement = ExecutionRunSettlementCoordinator(
            listOf(ExploreSettlementAdapter(owners, SpringActiveExploreOwnerSettlementTransactionPort(dataSource), Duration.ofHours(1))),
            repository, configuration.ownerSettlementTransactionRunner(dataSource, properties, jdbcDispatcher),
        )

        fun cancel(run: ExecutionRunSnapshot, reasonCode: String, requestedAt: Instant) = inOwnerTransaction {
            service.requestCancellationInOwnerTransaction(OwnerExecutionRunCancellationPort.RequestCancellationCommand(
                run.id, run.owner, run.workspaceId, reasonCode, requestedAt,
            ))
        }

        /** A repeatable read owner transaction on the calling thread, as product owners open one. */
        fun <T> inOwnerTransaction(block: () -> T): T {
            @Suppress("UNCHECKED_CAST")
            return ownerTransactions.execute { block() } as T
        }

        val capacityAdmission = ExecutionRunAdmissionService(repository, dispatch, codec, ExecutionRunLimits(256))

        fun capacityCommand(at: Instant, workspace: WorkspaceId): ExecutionRunAdmissionPort.ReserveCommand {
            val runId = ExecutionRunId.generate()
            val objectKey = "artifacts/workspaces/$workspace/explore/runs/$runId/result.arrow"
            val request = RawSqlExecutionRequest(
                1u, Sha256Digest("0".repeat(64)), "select 1", emptyList(), 100u, 8192u, 100, 8192, 4096, 8192, false, emptyList(),
            ).let { it.copy(canonicalRequestDigest = RawSqlExecutionRequestCanonicalizer.canonicalDigest(it, objectKey)) }
            val connector = ConnectorRuntimeBindingSpec(
                ConnectorReference(Uuid.generateV7()), ConnectorType.POSTGRESQL,
                mapOf("host" to ConnectorRuntimePropertyValue.Text("unused-fixture-warehouse")),
                ManagedCredentialDispatchReference(CredentialVersionReference(Uuid.generateV7()), "fixture-key", Sha256Digest("a".repeat(64))),
            )
            val deadline = at.plusSeconds(7200)
            val spec = ProtoRawSqlRunSpecFactory().create(RawSqlRunSpecCommand(runId, workspace, objectKey, connector, request, deadline))
            return ExecutionRunAdmissionPort.ReserveCommand(
                runId, ExecutionOwnerReference(ExecutionOwnerType.EXPLORE, ExecutionOwnerId(runId.value)), workspace,
                "capacity-$runId", spec.canonicalRequestDigest, connector.connectorId, connector.connectorType, connector.properties,
                ExecutableRequestKind.QUERY, spec.runtimeBindings, spec.assignmentTemplate, at.plusSeconds(3600), deadline, at,
            )
        }

        fun admit(at: Instant): ExecutionRunSnapshot {
            val runId = ExecutionRunId(Uuid.generateV7())
            val workspaceId = WorkspaceId.generate()
            val connectorId = ConnectorId.generate()
            val objectKey = "artifacts/workspaces/$workspaceId/explore/runs/$runId/result.arrow"
            val owner = ExploreRun(
                ExploreRunId(runId.value),
                ExploreRun.Kind.Sql,
                "restart-$runId",
                PrincipalId.generate(),
                null,
                null,
                ExploreConnectorBinding(workspaceId, connectorId, ConnectorType.POSTGRESQL),
                ExploreDigest("sha256:${"a".repeat(64)}"),
                ExploreRunState.RUNNING,
                null,
                null,
                null,
                0,
                at,
                at,
            )
            val request = RawSqlExecutionRequest(
                1u, Sha256Digest("0".repeat(64)), "select 1", emptyList(), 100u, 8192u, 100, 8192, 4096, 8192, false, emptyList(),
            ).let { it.copy(canonicalRequestDigest = RawSqlExecutionRequestCanonicalizer.canonicalDigest(it, objectKey)) }
            val connector = ConnectorRuntimeBindingSpec(
                ConnectorReference(connectorId.value), ConnectorType.POSTGRESQL,
                mapOf("host" to ConnectorRuntimePropertyValue.Text("unused-fixture-warehouse")),
                ManagedCredentialDispatchReference(CredentialVersionReference(Uuid.generateV7()), "fixture-key", Sha256Digest("a".repeat(64))),
            )
            val spec = ProtoRawSqlRunSpecFactory().create(RawSqlRunSpecCommand(runId, workspaceId, objectKey, connector, request, at.plusSeconds(600)))
            return inOwnerTransaction {
                check(owners.insertAdmittedRun(owner))
                val result = ExecutionRunAdmissionService(repository, dispatch, codec, ExecutionRunLimits(100))
                    .reserveInOwnerTransaction(ExecutionRunAdmissionPort.ReserveCommand(
                        runId, ExecutionOwnerReference(ExecutionOwnerType.EXPLORE, ExecutionOwnerId(runId.value)), workspaceId,
                        owner.idempotencyKey, spec.canonicalRequestDigest, connector.connectorId, connector.connectorType, connector.properties,
                        ExecutableRequestKind.QUERY, spec.runtimeBindings, spec.assignmentTemplate, at.plusSeconds(10), at.plusSeconds(600), at,
                    ))
                check(result is ExecutionRunAdmissionPort.ReserveOutcome.Reserved) { result.toString() }
                result.snapshot
            }
        }

        suspend fun claim(run: ExecutionRunSnapshot, at: Instant) {
            assertThat(service.claimDispatch(ExecutionRunUseCase.ClaimRunDispatchCommand(run.id, at.plusSeconds(1), at.plusSeconds(600))))
                .isInstanceOf(ExecutionRunUseCase.RunMutationOutcome.Applied::class.java)
            assertThat(service.acquireExecutionOccupancy(ExecutionRunUseCase.AcquireExecutionOccupancyCommand(
                run.id, run.workspaceId, at.plusSeconds(1),
            ))).isInstanceOf(ExecutionRunUseCase.RunMutationOutcome.Applied::class.java)
        }

        fun cleanupCandidates(at: Instant) = inOwnerTransaction { repository.findArtifactCleanupCandidates(at, 1000) }
            .map { read -> (read as StoredExecutionRunRead.Readable).run.snapshot }

        var maximumCleanupRunsPerCycle = 100

        fun recovery(artifacts: ExecutionArtifactStore, at: Instant) = ExecutionRunRecoveryLifecycle(
            service, settlement, artifacts, Clock.fixed(at, ZoneOffset.UTC),
            ControlExecutionLimitsProperties(
                2 * 1024 * 1024, 100,
                Duration.ofHours(1), Duration.ofSeconds(1), Duration.ofSeconds(90), 100, maximumCleanupRunsPerCycle, 3,
                Duration.ofSeconds(10), Duration.ofMinutes(10),
            ), setOf(ExecutionOwnerType.EXPLORE),
        )
    }

    private class ExactKeyArtifactFixture : ExecutionArtifactStore {
        val objects = ConcurrentHashMap.newKeySet<String>()
        val deletedKeys = CopyOnWriteArrayList<String>()
        var failDeletes = false
        val failedKeys = mutableSetOf<String>()
        var beforeDelete: (suspend () -> Unit)? = null
        override suspend fun readRange(objectKey: String, expectedEtag: String, byteRange: LongRange): ByteArray =
        error("Conditional result ranges are outside this fixture")

    override suspend fun presignGet(objectKey: String, expiresAt: java.time.Instant, fileName: String): cc.monomer.execution.api.ExecutionArtifactDownloadLink =
        error("Download signing is outside this fixture")

    override suspend fun delete(objectKey: String) {
            deletedKeys += objectKey
            beforeDelete?.invoke()
            if (failDeletes || objectKey in failedKeys) error("injected artifact deletion failure")
            objects.remove(objectKey)
        }
        override suspend fun head(objectKey: String, deadline: java.time.Instant?): ExecutionArtifactHead? = error("No artifact reads in recovery")
        override suspend fun read(objectKey: String): ByteArray = error("No artifact reads in recovery")
        override fun readChunks(objectKey: String, expectedEtag: String, chunkSizeBytes: Int): Flow<ByteBuffer> =
            error("No artifact reads in recovery")
    }
}
