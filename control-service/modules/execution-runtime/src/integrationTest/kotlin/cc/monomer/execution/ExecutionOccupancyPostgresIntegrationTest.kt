package cc.monomer.execution

import kotlinx.coroutines.Dispatchers
import org.springframework.transaction.support.TransactionOperations
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.ExecutionRunState
import cc.monomer.execution.api.ExecutionOwnerId
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutableRequestKind
import cc.monomer.execution.api.ResultSinkBinding
import cc.monomer.execution.api.ResultSinkKind
import cc.monomer.execution.api.RunRuntimeBindings
import cc.monomer.execution.api.Sha256Digest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid

import cc.monomer.execution.api.*
import cc.monomer.execution.application.*
import cc.monomer.execution.adapter.outbound.postgres.*
import cc.monomer.execution.adapter.inbound.grpc.ExecutableRequestAssignmentCodec
import java.time.Duration
import java.sql.Connection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

class ExecutionOccupancyPostgresIntegrationTest {
    companion object {
        @JvmField @RegisterExtension val postgres = TargetPostgresTestSupport
    }
    private val at = Instant.parse("2026-09-11T00:00:00Z")

    private class Runtime : AutoCloseable {
        val source = SingleConnectionDataSource(TargetPostgresTestSupport.openVerifiedConnection(), true)
        val store = PostgresExecutionRuntimeStore(source)
        val transactions = TransactionTemplate(DataSourceTransactionManager(source)).apply {
            isolationLevel = Connection.TRANSACTION_READ_COMMITTED
        }
        val tx = BlockingTransactions(transactions)
        val runner = SpringExecutionRuntimeTransactionRunner(transactions, Dispatchers.Unconfined)
        val service = ExecutionRunService(store, runner, runner, object : RunDispatchIntentPort {
            override fun persist(intent: RunDispatchIntent): RunDispatchIntentPersistenceOutcome = error("unused")
            override fun find(runId: ExecutionRunId): RunDispatchIntent? = null
            override fun findAll(runIds: Set<ExecutionRunId>): List<cc.monomer.execution.api.StoredDispatchIntentRead> = emptyList()
        }, ExecutableRequestAssignmentCodec())
        fun cancel(run: ExecutionRunSnapshot, requestedAt: Instant) = tx.run {
            service.requestCancellationInOwnerTransaction(OwnerExecutionRunCancellationPort.RequestCancellationCommand(
                run.id, run.owner, run.workspaceId, "cancelled", requestedAt,
            ))
        }
        fun insert(snapshot: ExecutionRunSnapshot) = tx.run {
            store.insert(AdmittedExecutionRun(snapshot, snapshot.id.toString(), snapshot.canonicalRequestDigest))
        }
        override fun close() = source.destroy()
    }

    private fun claimed(workspace: WorkspaceId): ExecutionRunSnapshot {
        val created = snapshot(at).copy(workspaceId = workspace,
            runtimeBindings = snapshot(at).runtimeBindings.copy(credentialVersion = CredentialVersionReference(Uuid.generateV7())))
        return created.copy(dispatchClaimedAt = at, lastHeartbeatAt = at, version = 1)
    }

    @Test fun `independent connections share eight slots and cancellation keeps occupancy until fenced release`() = runBlocking<Unit> {
        Runtime().use { first -> Runtime().use { second ->
            val workspace = WorkspaceId(Uuid.generateV7())
            val runs = List(10) { claimed(workspace).also(first::insert) }
            val executor = Executors.newFixedThreadPool(2)
            try {
                val gate = CountDownLatch(1)
                val outcomes = runs.mapIndexed { index, run -> executor.submit<ExecutionRunUseCase.RunMutationOutcome> {
                    gate.await()
                    val runtime = if (index % 2 == 0) first else second
                    synchronized(runtime) { runBlocking { runtime.service.acquireExecutionOccupancy(
                        ExecutionRunUseCase.AcquireExecutionOccupancyCommand(run.id, workspace, at.plusSeconds(1).plusNanos(123456))) } }
                } }
                gate.countDown()
                val values = outcomes.map { it.get(10, TimeUnit.SECONDS) }
                assertEquals(8, values.count { it is ExecutionRunUseCase.RunMutationOutcome.Applied })
                val acquired = values.filterIsInstance<ExecutionRunUseCase.RunMutationOutcome.Applied>().first().snapshot
                val waiters = runs.filterIndexed { index, _ -> values[index] == ExecutionRunUseCase.RunMutationOutcome.ExecutionOccupancyFull }
                assertEquals(2, waiters.size)
                val waiting = waiters.first()
                val cancelled = assertIs<OwnerExecutionRunCancellationPort.CancellationOutcome.Applied>(
                    first.cancel(acquired, at.plusSeconds(2))).snapshot
                assertEquals(8, second.tx.run { second.store.countExecutionOccupancy(workspace) })
                assertEquals(ExecutionRunUseCase.RunMutationOutcome.ExecutionOccupancyFull, second.service.acquireExecutionOccupancy(
                    ExecutionRunUseCase.AcquireExecutionOccupancyCommand(waiting.id, workspace, at.plusSeconds(3))))
                val settled = cancelled.copy(ownerSettledAt = at.plusSeconds(3), updatedAt = at.plusSeconds(3), version = cancelled.version + 1)
                first.tx.run { assertTrue(first.store.compareAndSet(cancelled.version, settled)) }
                val beat = assertIs<ExecutionRunUseCase.RunMutationOutcome.Applied>(first.service.heartbeat(
                    ExecutionRunUseCase.HeartbeatCommand(acquired.id, at.plusSeconds(4)))).snapshot
                assertEquals(settled.updatedAt, beat.updatedAt)
                assertIs<ExecutionRunUseCase.RunMutationOutcome.Duplicate>(first.service.releaseExecutionOccupancy(
                    ExecutionRunUseCase.ReleaseExecutionOccupancyCommand(acquired.id, at, at.plusSeconds(5))))
                val released = assertIs<ExecutionRunUseCase.RunMutationOutcome.Applied>(first.service.releaseExecutionOccupancy(
                    ExecutionRunUseCase.ReleaseExecutionOccupancyCommand(acquired.id, acquired.executionOccupancyAcquiredAt!!, at.plusSeconds(5)))).snapshot
                assertEquals(settled.updatedAt, released.updatedAt)
                assertIs<ExecutionRunUseCase.RunMutationOutcome.Duplicate>(first.service.heartbeat(
                    ExecutionRunUseCase.HeartbeatCommand(acquired.id, at.plusSeconds(6))))
                assertIs<ExecutionRunUseCase.RunMutationOutcome.Duplicate>(first.service.acquireExecutionOccupancy(
                    ExecutionRunUseCase.AcquireExecutionOccupancyCommand(acquired.id, workspace, at.plusSeconds(6))))
                assertTrue(first.service.hasExecutionOccupancyCapacity(workspace))
                assertTrue(second.service.hasExecutionOccupancyCapacity(workspace))
                val retryGate = CountDownLatch(1)
                val retries = waiters.mapIndexed { index, waiter -> executor.submit<ExecutionRunUseCase.RunMutationOutcome> {
                    retryGate.await()
                    val runtime = if (index == 0) first else second
                    runBlocking { runtime.service.acquireExecutionOccupancy(
                        ExecutionRunUseCase.AcquireExecutionOccupancyCommand(waiter.id, workspace, at.plusSeconds(6))) }
                } }
                retryGate.countDown()
                val retried = retries.map { it.get(10, TimeUnit.SECONDS) }
                assertEquals(1, retried.count { it is ExecutionRunUseCase.RunMutationOutcome.Applied })
                assertEquals(1, retried.count { it == ExecutionRunUseCase.RunMutationOutcome.ExecutionOccupancyFull })
                assertEquals(8, second.tx.run { second.store.countExecutionOccupancy(workspace) })
                assertFalse(first.service.hasExecutionOccupancyCapacity(workspace))
            } finally { executor.shutdownNow() }
        } }
    }

    @Test fun `read only occupancy check bypasses held workspace admission and run row locks`() = runBlocking<Unit> {
        Runtime().use { first -> Runtime().use { second ->
            val workspace = WorkspaceId(Uuid.generateV7())
            assertTrue(second.service.hasExecutionOccupancyCapacity(workspace))
            val runs = List(8) { claimed(workspace).also(first::insert) }
            val occupied = runs.map { run ->
                assertIs<ExecutionRunUseCase.RunMutationOutcome.Applied>(first.service.acquireExecutionOccupancy(
                    ExecutionRunUseCase.AcquireExecutionOccupancyCommand(run.id, workspace, at.plusSeconds(1)))).snapshot
            }
            second.source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("SET lock_timeout = '250ms'")
                    statement.execute("SET default_transaction_read_only = on")
                }
            }
            first.tx.run {
                first.store.lockWorkspaceRunAdmission(workspace)
                assertNotNull(first.store.findForUpdate(runs.first().id))
                runBlocking { assertFalse(second.service.hasExecutionOccupancyCapacity(workspace)) }
            }
            val released = occupied.first()
            first.cancel(released, at.plusSeconds(2))
            first.service.releaseExecutionOccupancy(ExecutionRunUseCase.ReleaseExecutionOccupancyCommand(
                released.id, released.executionOccupancyAcquiredAt!!, at.plusSeconds(3)))
            assertTrue(second.service.hasExecutionOccupancyCapacity(workspace))
        } }
    }

    @Test fun `fresh terminal heartbeat wins stale recovery and recovered release rejects late heartbeat`() = runBlocking<Unit> {
        Runtime().use { first -> Runtime().use { second ->
            val run = claimed(WorkspaceId(Uuid.generateV7())).also(first::insert)
            first.service.acquireExecutionOccupancy(ExecutionRunUseCase.AcquireExecutionOccupancyCommand(run.id, run.workspaceId, at.plusSeconds(1)))
            first.cancel(run, at.plusSeconds(2))
            val candidates = second.tx.run { second.store.findOccupancyRecoveryCandidates(at.plusSeconds(20), at.plusSeconds(10), 100) }
            assertTrue(candidates.any { it.runId == run.id })
            first.service.heartbeat(ExecutionRunUseCase.HeartbeatCommand(run.id, at.plusSeconds(19)))
            assertIs<ExecutionRunUseCase.RunMutationOutcome.Duplicate>(second.service.recoverExecutionOccupancy(
                ExecutionRunUseCase.RecoverExecutionOccupancyCommand(run.id, at.plusSeconds(20), Duration.ofSeconds(10))))
            val recovered = assertIs<ExecutionRunUseCase.RunMutationOutcome.Applied>(second.service.recoverExecutionOccupancy(
                ExecutionRunUseCase.RecoverExecutionOccupancyCommand(run.id, at.plusSeconds(30), Duration.ofSeconds(10)))).snapshot
            assertEquals(ExecutionRunState.CANCELLED, recovered.state)
            assertEquals(at.plusSeconds(30), recovered.executionOccupancyReleasedAt)
            assertIs<ExecutionRunUseCase.RunMutationOutcome.Duplicate>(first.service.heartbeat(
                ExecutionRunUseCase.HeartbeatCommand(run.id, at.plusSeconds(31))))
        } }
    }

    @Test fun `failed release rolls back and remains recoverable`() = runBlocking<Unit> {
        Runtime().use { runtime ->
            val run = claimed(WorkspaceId(Uuid.generateV7())).also(runtime::insert)
            val acquired = assertIs<ExecutionRunUseCase.RunMutationOutcome.Applied>(runtime.service.acquireExecutionOccupancy(
                ExecutionRunUseCase.AcquireExecutionOccupancyCommand(run.id, run.workspaceId, at.plusSeconds(1)))).snapshot
            runtime.cancel(run, at.plusSeconds(2))
            assertFailsWith<IllegalStateException> {
                runtime.tx.run {
                    val current = runtime.store.findForUpdate(run.id)!!.snapshot
                    val released = current.copy(executionOccupancyReleasedAt = at.plusSeconds(3), version = current.version + 1)
                    assertTrue(runtime.store.compareAndSet(current.version, released))
                    error("injected commit failure")
                }
            }
            assertNull(runtime.service.get(run.id)!!.executionOccupancyReleasedAt)
            assertIs<ExecutionRunUseCase.RunMutationOutcome.Applied>(runtime.service.recoverExecutionOccupancy(
                ExecutionRunUseCase.RecoverExecutionOccupancyCommand(run.id, at.plusSeconds(60), Duration.ofSeconds(10))))
        }
    }

    @Test fun `runtime cancellation notification is delivered only after commit`() = runBlocking<Unit> {
        Runtime().use { runtime -> TargetPostgresTestSupport.openVerifiedConnection().use { listener ->
            listener.createStatement().use { it.execute("LISTEN monomer_execution_cancel") }
            val pg = listener.unwrap(org.postgresql.PGConnection::class.java)
            val run = claimed(WorkspaceId(Uuid.generateV7())).also(runtime::insert)
            assertFailsWith<IllegalStateException> {
                runtime.tx.run {
                    runtime.cancel(run, at.plusSeconds(1))
                    error("rollback cancellation")
                }
            }
            assertTrue(pg.getNotifications(50).isNullOrEmpty())
            runtime.cancel(run, at.plusSeconds(1))
            assertEquals(run.id.toString(), pg.getNotifications(1000)!!.single().parameter)
        } }
    }

    private fun reserve(runtime: Runtime, run: ExecutionRunSnapshot): ExecutionRunAdmissionPort.ReserveOutcome {
        val codec = object : RunDispatchAssignmentCodec {
            override fun canonicalizeTemplate(assignmentTemplate: BinaryValue) = assignmentTemplate
            override fun readSql(canonicalTemplate: BinaryValue, snapshot: ExecutionRunSnapshot): String? = null
        }
        val admission = ExecutionRunAdmissionService(runtime.store, PostgresRunDispatchPersistence(runtime.source, runtime.transactions), codec, ExecutionRunLimits(256))
        return admission.reserveInOwnerTransaction(ExecutionRunAdmissionPort.ReserveCommand(
            run.id, run.owner, run.workspaceId, run.id.toString(), run.canonicalRequestDigest,
            run.connectorId, run.connectorType, run.connectorProperties, run.requestKind, run.runtimeBindings,
            BinaryValue.copyOf(byteArrayOf(1)), run.dispatchDeadlineAt, run.deadline, run.createdAt,
        ))
    }

    @Test fun `historical repeatable read mechanism exceeds 256 while production initial admission uses read committed`() {
        Runtime().use { first -> Runtime().use { second ->
            val workspace = WorkspaceId(Uuid.generateV7())
            repeat(255) { first.insert(claimed(workspace)) }
            val rr = TransactionTemplate(DataSourceTransactionManager(second.source)).apply {
                isolationLevel = Connection.TRANSACTION_REPEATABLE_READ
            }
            rr.executeWithoutResult {
                assertEquals(255, second.store.countNonTerminalRuns(workspace))
                first.tx.run {
                    assertIs<ExecutionRunAdmissionPort.ReserveOutcome.Reserved>(reserve(first, claimed(workspace)))
                }
                assertEquals(255, second.store.countNonTerminalRuns(workspace))
                assertIs<ExecutionRunAdmissionPort.ReserveOutcome.Reserved>(reserve(second, claimed(workspace)))
            }
            assertEquals(257, first.tx.run { first.store.countNonTerminalRuns(workspace) })
        } }
    }

    @Test fun `owner discard reports missing execution without creating an execution record`() = runBlocking<Unit> {
        Runtime().use { runtime ->
            val command = OwnerExecutionRunDiscardPort.DiscardCommand(
                ExecutionRunId(Uuid.generateV7()),
                ExecutionOwnerReference(ExecutionOwnerType.DASHBOARD, ExecutionOwnerId(Uuid.generateV7())),
                WorkspaceId(Uuid.generateV7()),
                at.plusSeconds(3),
            )
            assertIs<OwnerExecutionRunDiscardPort.DiscardOutcome.NotFound>(runtime.tx.run {
                runtime.service.discardInOwnerTransaction(command)
            })
            assertNull(runtime.service.get(command.runId))
        }
    }

    @Test fun `owner discard verifies binding requires caller transaction and rolls back atomically`() = runBlocking<Unit> {
        Runtime().use { runtime ->
            val run = claimed(WorkspaceId(Uuid.generateV7())).copy(owner = ExecutionOwnerReference(
                ExecutionOwnerType.DASHBOARD, ExecutionOwnerId(Uuid.generateV7()),
            )).also(runtime::insert)
            val command = OwnerExecutionRunDiscardPort.DiscardCommand(run.id, run.owner, run.workspaceId, at.plusSeconds(3))
            assertFailsWith<IllegalStateException> { runtime.service.discardInOwnerTransaction(command) }
            assertIs<OwnerExecutionRunDiscardPort.DiscardOutcome.Conflict>(runtime.tx.run {
                runtime.service.discardInOwnerTransaction(command)
            })
            runtime.tx.run {
                assertIs<OwnerExecutionRunCancellationPort.CancellationOutcome.Applied>(runtime.service.requestCancellationInOwnerTransaction(
                    OwnerExecutionRunCancellationPort.RequestCancellationCommand(run.id, run.owner, run.workspaceId, "DASHBOARD_DELETED", at.plusSeconds(2)),
                ))
            }
            val beforeMismatchedDiscard = requireNotNull(runtime.service.get(run.id))
            assertIs<OwnerExecutionRunDiscardPort.DiscardOutcome.Conflict>(runtime.tx.run {
                runtime.service.discardInOwnerTransaction(command.copy(workspaceId = WorkspaceId(Uuid.generateV7())))
            })
            assertEquals(beforeMismatchedDiscard, runtime.service.get(run.id))
            assertIs<OwnerExecutionRunDiscardPort.DiscardOutcome.Conflict>(runtime.tx.run {
                runtime.service.discardInOwnerTransaction(command.copy(owner = run.owner.copy(id = ExecutionOwnerId(Uuid.generateV7()))))
            })
            assertEquals(beforeMismatchedDiscard, runtime.service.get(run.id))
            assertFailsWith<IllegalStateException> {
                runtime.tx.run {
                    assertIs<OwnerExecutionRunDiscardPort.DiscardOutcome.Applied>(runtime.service.discardInOwnerTransaction(command))
                    error("owner deletion failed")
                }
            }
            assertNull(runtime.service.get(run.id)?.ownerDiscardedAt)
            val discarded = assertIs<OwnerExecutionRunDiscardPort.DiscardOutcome.Applied>(runtime.tx.run {
                runtime.service.discardInOwnerTransaction(command)
            }).snapshot
            assertEquals(command.discardedAt, discarded.ownerDiscardedAt)
            assertEquals(command.discardedAt, discarded.ownerSettledAt)
            assertIs<OwnerExecutionRunDiscardPort.DiscardOutcome.Duplicate>(runtime.tx.run { runtime.service.discardInOwnerTransaction(command) })
            assertFalse(runtime.tx.run { runtime.store.findOwnerSettlementCandidates(setOf(ExecutionOwnerType.DASHBOARD), 100) }.any { it.runId == run.id })
            val settlement = ExecutionRunSettlementCoordinator(emptyList(), runtime.store, runtime.runner)
            assertIs<ExecutionRunSettlementUseCase.SettlementOutcome.Duplicate>(settlement.settle(run.id, at.plusSeconds(4)))
            assertTrue(runtime.service.beginArtifactCleanup(ExecutionRunUseCase.BeginArtifactCleanupCommand(run.id, discarded.version, at.plusSeconds(61))))
            val cleaning = requireNotNull(runtime.service.get(run.id))
            assertTrue(runtime.service.completeArtifactCleanup(ExecutionRunUseCase.CompleteArtifactCleanupCommand(run.id, cleaning.version, at.plusSeconds(61))))
            val lateSuccess = PhysicalOutcome("${run.id}:terminal", PhysicalOutcomeKind.SUCCEEDED, null, null,
                ArrowResultManifest(requireNotNull(run.runtimeBindings.resultSink.objectKey), 128u, "late-etag", 1u, 1, "application/vnd.apache.arrow.file"))
            assertIs<ExecutionRunUseCase.TerminalOutcome.TerminalConflict>(runtime.service.recordPhysicalOutcome(
                ExecutionRunUseCase.RecordPhysicalOutcomeCommand(run.id, lateSuccess, at.plusSeconds(62)),
            ))
            assertEquals(ExecutionRunState.CANCELLED, runtime.service.get(run.id)?.state)
            assertTrue(runtime.tx.run { runtime.store.findArtifactCleanupCandidates(at.plusSeconds(63), 100) }.any { it.runId == run.id })
        }
    }

    @Test fun `owner discard waits on current execution row and rereads the committed terminal`() = runBlocking<Unit> {
        Runtime().use { first -> Runtime().use { second ->
            val run = claimed(WorkspaceId(Uuid.generateV7())).also(first::insert)
            val executor = Executors.newSingleThreadExecutor()
            try {
                lateinit var result: java.util.concurrent.Future<OwnerExecutionRunDiscardPort.DiscardOutcome>
                val entered = CountDownLatch(1)
                first.tx.run {
                    assertNotNull(first.store.findForUpdate(run.id))
                    result = executor.submit<OwnerExecutionRunDiscardPort.DiscardOutcome> {
                        entered.countDown()
                        second.tx.run { second.service.discardInOwnerTransaction(
                            OwnerExecutionRunDiscardPort.DiscardCommand(run.id, run.owner, run.workspaceId, at.plusSeconds(3)),
                        ) }
                    }
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    assertFailsWith<java.util.concurrent.TimeoutException> { result.get(150, TimeUnit.MILLISECONDS) }
                    assertIs<OwnerExecutionRunCancellationPort.CancellationOutcome.Applied>(first.service.requestCancellationInOwnerTransaction(
                        OwnerExecutionRunCancellationPort.RequestCancellationCommand(run.id, run.owner, run.workspaceId, "DASHBOARD_DELETED", at.plusSeconds(2)),
                    ))
                }
                val discarded = assertIs<OwnerExecutionRunDiscardPort.DiscardOutcome.Applied>(result.get(5, TimeUnit.SECONDS)).snapshot
                assertEquals(ExecutionRunState.CANCELLED, discarded.state)
                assertEquals(run.version + 2, discarded.version)
                assertEquals(at.plusSeconds(3), discarded.ownerDiscardedAt)
            } finally { executor.shutdownNow() }
        } }
    }

    @Test fun `discarded success before owner settlement joins cleanup and late terminal reopens guarded cleanup`() = runBlocking<Unit> {
        Runtime().use { runtime ->
            val run = claimed(WorkspaceId(Uuid.generateV7())).copy(owner = ExecutionOwnerReference(
                ExecutionOwnerType.DASHBOARD, ExecutionOwnerId(Uuid.generateV7()),
            )).also(runtime::insert)
            val outcome = PhysicalOutcome("${run.id}:terminal", PhysicalOutcomeKind.SUCCEEDED, null, null,
                ArrowResultManifest(requireNotNull(run.runtimeBindings.resultSink.objectKey), 128u, "test-etag", 1u, 1, "application/vnd.apache.arrow.file"))
            val completed = assertIs<ExecutionRunUseCase.TerminalOutcome.Accepted>(runtime.service.recordPhysicalOutcome(
                ExecutionRunUseCase.RecordPhysicalOutcomeCommand(run.id, outcome, at.plusSeconds(2)),
            )).snapshot
            assertNull(completed.ownerSettledAt)
            assertFalse(runtime.service.beginArtifactCleanup(ExecutionRunUseCase.BeginArtifactCleanupCommand(run.id, completed.version, at.plusSeconds(61))))
            runtime.tx.run {
                assertIs<OwnerExecutionRunCancellationPort.CancellationOutcome.AlreadyTerminal>(runtime.service.requestCancellationInOwnerTransaction(
                    OwnerExecutionRunCancellationPort.RequestCancellationCommand(run.id, run.owner, run.workspaceId, "DASHBOARD_DELETED", at.plusSeconds(3)),
                ))
                runtime.service.discardInOwnerTransaction(OwnerExecutionRunDiscardPort.DiscardCommand(run.id, run.owner, run.workspaceId, at.plusSeconds(3)))
            }
            var current = requireNotNull(runtime.service.get(run.id))
            assertFalse(runtime.service.beginArtifactCleanup(ExecutionRunUseCase.BeginArtifactCleanupCommand(run.id, current.version, at.plusSeconds(59))))
            assertTrue(runtime.tx.run { runtime.store.findArtifactCleanupCandidates(at.plusSeconds(61), 100) }.any { it.runId == run.id })
            assertTrue(runtime.service.beginArtifactCleanup(ExecutionRunUseCase.BeginArtifactCleanupCommand(run.id, current.version, at.plusSeconds(61))))
            current = requireNotNull(runtime.service.get(run.id))
            assertTrue(runtime.service.completeArtifactCleanup(ExecutionRunUseCase.CompleteArtifactCleanupCommand(run.id, current.version, at.plusSeconds(61))))
            assertFalse(runtime.tx.run { runtime.store.findArtifactCleanupCandidates(at.plusSeconds(62), 100) }.any { it.runId == run.id })
            val beforeLate = requireNotNull(runtime.service.get(run.id))
            assertIs<ExecutionRunUseCase.TerminalOutcome.Duplicate>(runtime.service.recordPhysicalOutcome(
                ExecutionRunUseCase.RecordPhysicalOutcomeCommand(run.id, outcome, at.plusSeconds(63)),
            ))
            assertFalse(runtime.service.completeArtifactCleanup(ExecutionRunUseCase.CompleteArtifactCleanupCommand(run.id, beforeLate.version, at.plusSeconds(64))))
            assertTrue(runtime.tx.run { runtime.store.findArtifactCleanupCandidates(at.plusSeconds(64), 100) }.any { it.runId == run.id })
            assertEquals(beforeLate.physicalOutcome, runtime.service.get(run.id)?.physicalOutcome)
            assertEquals(beforeLate.ownerDiscardedAt, runtime.service.get(run.id)?.ownerDiscardedAt)
        }
    }

    private fun snapshot(createdAt: Instant): ExecutionRunSnapshot {
        val deadline = createdAt.plusSeconds(60)
        return ExecutionRunSnapshot(
            id = ExecutionRunId(Uuid.generateV7()),
            owner =
                ExecutionOwnerReference(
                    type = ExecutionOwnerType.EXPLORE,
                    id = ExecutionOwnerId(Uuid.generateV7()),
                ),
            workspaceId = WorkspaceId(Uuid.generateV7()),
            connectorId = ConnectorReference(Uuid.generateV7()),
            connectorType = ConnectorType.POSTGRESQL,
            connectorProperties = mapOf("host" to cc.monomer.execution.api.ConnectorRuntimePropertyValue.Text("localhost")),
            requestKind = ExecutableRequestKind.QUERY,
            canonicalRequestDigest = digest('a'),
            runtimeBindings =
                RunRuntimeBindings(
                    connectorBindingDigest = digest('b'),
                    credentialVersion = null,
                    credentialKeyVersion = "local-primary",
                    credentialBindingDigest = digest('c'),
                    resultSink =
                        ResultSinkBinding(
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
                            bindingDigest = digest('d'),
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
}

private fun digest(character: Char): Sha256Digest = Sha256Digest(character.toString().repeat(64))

private class BlockingTransactions(private val operations: TransactionOperations) {
    @Suppress("UNCHECKED_CAST")
    fun <T> run(block: () -> T): T = operations.execute { block() } as T
}
