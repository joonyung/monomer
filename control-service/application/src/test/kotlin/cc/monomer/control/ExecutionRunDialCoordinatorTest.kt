@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control

import cc.monomer.connector.application.*
import cc.monomer.execution.api.*
import cc.monomer.execution.application.AdmittedExecutionRun
import cc.monomer.execution.application.ExecutionRunRepository
import cc.monomer.execution.application.ExecutionRunSettlementCoordinator
import cc.monomer.execution.application.ExecutionRuntimeTransactionRunner
import cc.monomer.identity.api.WorkspaceId
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.slf4j.MDCContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

class ExecutionRunDialCoordinatorTest {
    @Test
    fun `detached dial contains initial query claim terminal persistence and final query failures without raw stderr`() = runTest {
        val logger = LoggerFactory.getLogger(ExecutionRunDialCoordinator::class.java) as Logger
        val logs = correlationAppender()
        logger.addAppender(logs)
        val stderr = ByteArrayOutputStream()
        val previousStderr = System.err
        System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
        try {
            for (stage in listOf("INITIAL_QUERY", "CLAIM", "TERMINAL_PERSISTENCE", "FINAL_QUERY")) {
                val fixture = DialFixture(this)
                val run = fixture.admit()
                val failure = IllegalStateException("password=synthetic-dial-secret SELECT private@example.com", IllegalArgumentException("Bearer synthetic-cause-secret"))
                when (stage) {
                    "INITIAL_QUERY" -> fixture.beforeGet = { throw failure }
                    "CLAIM" -> fixture.beforeClaim = { throw failure }
                    "TERMINAL_PERSISTENCE" -> {
                        fixture.failedStreams += run.id
                        fixture.beforeRecordPhysicalOutcome = { throw failure }
                    }
                    "FINAL_QUERY" -> fixture.beforeGet = { id -> if (fixture.runs.getValue(id).physicalOutcome != null) throw failure }
                }
                val ownedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val coordinator = fixture.newCoordinator(ownedScope)
                try {
                    MDC.put("request_id", "srv_unhandled-$stage")
                    coordinator.dispatchAfterCommit(run.id)
                    MDC.clear()
                    fixture.finish(run)
                    ownedScope.coroutineContext.job.children.toList().joinAll()
                    val summary = logs.list.single { it.message == "execution_run_dial_failed" }
                    val fields = cc.monomer.control.observability.RequestLogTestSupport.fields(summary)
                    assertEquals("srv_unhandled-$stage", fields["request_id"])
                    assertEquals(run.id.toString(), fields["run_id"])
                    assertEquals("java.lang.IllegalStateException", fields["error.type"])
                    assertTrue((fields["error.stack_trace"] as String).contains("ExecutionRunDialCoordinatorTest"))
                    val output = stderr.toString(Charsets.UTF_8) + logs.list.joinToString { cc.monomer.control.observability.RequestLogTestSupport.encoded(it) }
                    for (secret in listOf("synthetic-dial-secret", "synthetic-cause-secret", "private@example.com", "SELECT", "Exception in thread")) {
                        assertFalse(output.contains(secret), "$stage leaked $secret")
                    }
                    logs.list.clear()
                } finally {
                    coordinator.close()
                    MDC.clear()
                }
            }
        } finally {
            System.setErr(previousStderr)
            logger.detachAppender(logs)
            MDC.clear()
        }
    }

    @Test
    fun `close cancels an in flight dial and returns only after its cancellation and release are recorded`() = runTest {
        val fixture = DialFixture(this)
        val run = fixture.admit()
        val ownedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = fixture.newCoordinator(ownedScope)
        coordinator.dispatchAfterCommit(run.id)
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (run.id !in fixture.started) delay(10) }
        }

        coordinator.close()

        val closed = fixture.runs.getValue(run.id)
        assertEquals(ExecutionRunState.CANCELLED, closed.state)
        assertNotNull(closed.executionOccupancyReleasedAt)
        assertTrue(ownedScope.coroutineContext.job.isCompleted)
    }

    @Test
    fun `detached dial captures only request and run correlation before commit and carries heartbeat and timing`() = runTest {
        val fixture = DialFixture(this)
        val run = fixture.admit()
        val logger = LoggerFactory.getLogger(ExecutionRunDialCoordinator::class.java) as Logger
        val logs = correlationAppender()
        logger.addAppender(logs)
        try {
            MDC.put("request_id", "srv_detached-request")
            MDC.put("private_value", "credential-must-not-propagate")
            TransactionSynchronizationManager.initSynchronization()
            fixture.coordinator.dispatchAfterCommit(run.id)
            val synchronizations = TransactionSynchronizationManager.getSynchronizations()
            TransactionSynchronizationManager.clearSynchronization()
            MDC.clear()
            synchronizations.forEach { it.afterCommit() }
            runCurrent()
            advanceTimeBy(10_001)
            runCurrent()
            val expected = mapOf("request_id" to "srv_detached-request", "run_id" to run.id.toString())
            assertEquals(expected, fixture.executionLoggingContexts.getValue(run.id))
            assertTrue(fixture.heartbeatLoggingContexts.getValue(run.id).all { it == expected })
            fixture.finish(run)
            runCurrent()
            val timing = logs.list.single { it.message == "execution_run_timing" }
            assertEquals(expected, timing.mdcPropertyMap)
            val fields = timing.mdcPropertyMap + timing.keyValuePairs.associate { it.key to it.value }
            assertEquals(run.id.toString(), fields["run_id"])
            assertIs<Long>(fields["rpc_ms"])
            assertIs<Long>(fields["admitted_to_settled_ms"])
            assertFalse(fields.values.contains("credential-must-not-propagate"))
            assertTrue(MDC.getCopyOfContextMap().isNullOrEmpty())
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization()
            MDC.clear()
            logger.detachAppender(logs)
        }
    }

    @Test
    fun `inline and recovery dial correlation is scoped without inventing a request id`() = runTest {
        val fixture = DialFixture(this)
        val inlineRun = fixture.admit(inline = true)
        val caller = async(MDCContext(mapOf("request_id" to "srv_inline-request", "private_value" to "private"))) {
            fixture.coordinator.runInline(inlineRun.id) {}
        }
        runCurrent()
        fixture.finish(inlineRun)
        runCurrent()
        assertEquals(PhysicalOutcomeKind.SUCCEEDED, caller.await().kind)
        assertEquals(mapOf("request_id" to "srv_inline-request", "run_id" to inlineRun.id.toString()),
            fixture.executionLoggingContexts.getValue(inlineRun.id))
        val recoveredRun = fixture.admit()
        fixture.coordinator.dispatchAfterCommit(recoveredRun.id)
        runCurrent()
        assertEquals(mapOf("run_id" to recoveredRun.id.toString()), fixture.executionLoggingContexts.getValue(recoveredRun.id))
        fixture.finish(recoveredRun)
        runCurrent()
        assertTrue(MDC.getCopyOfContextMap().isNullOrEmpty())
    }

    @Test
    fun `new admission correlation logs once after commit but not for rollback existing or rejected`() = runTest {
        val fixture = DialFixture(this)
        val run = fixture.admit()
        val delegate = mockk<cc.monomer.execution.application.ExecutionRunAdmissionService>()
        val coordinator = mockk<ExecutionRunDialCoordinator>(relaxed = true)
        val admission = DialingExecutionRunAdmissionPort(delegate, coordinator)
        val command = mockk<ExecutionRunAdmissionPort.ReserveCommand>()
        val logger = LoggerFactory.getLogger(ExecutionRunDialCoordinator::class.java) as Logger
        val logs = correlationAppender()
        logger.addAppender(logs)
        try {
            MDC.put("request_id", "srv_accepted-request")
            every { delegate.reserveInOwnerTransaction(command) } returns ExecutionRunAdmissionPort.ReserveOutcome.Reserved(run)
            TransactionSynchronizationManager.initSynchronization()
            admission.reserveInOwnerTransaction(command)
            assertTrue(logs.list.isEmpty())
            val committed = TransactionSynchronizationManager.getSynchronizations()
            TransactionSynchronizationManager.clearSynchronization()
            MDC.clear()
            committed.forEach { it.afterCommit() }
            val accepted = logs.list.single()
            val fields = accepted.mdcPropertyMap + accepted.keyValuePairs.associate { it.key to it.value }
            assertEquals("execution_run_accepted", accepted.message)
            assertEquals("srv_accepted-request", fields["request_id"])
            assertEquals(run.id.toString(), fields["run_id"])
            assertEquals(run.workspaceId.toString(), fields["workspace_id"])
            assertEquals(ExecutionOwnerType.EXPLORE.name, fields["owner_type"])
            logs.list.clear()
            TransactionSynchronizationManager.initSynchronization()
            admission.reserveInOwnerTransaction(command)
            val rolledBack = TransactionSynchronizationManager.getSynchronizations()
            TransactionSynchronizationManager.clearSynchronization()
            rolledBack.forEach { it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK) }
            every { delegate.reserveInOwnerTransaction(command) } returns ExecutionRunAdmissionPort.ReserveOutcome.Existing(run)
            admission.reserveInOwnerTransaction(command)
            every { delegate.reserveInOwnerTransaction(command) } returns ExecutionRunAdmissionPort.ReserveOutcome.Backpressured
            admission.reserveInOwnerTransaction(command)
            assertTrue(logs.list.isEmpty())
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization()
            MDC.clear()
            logger.detachAppender(logs)
        }
    }

    @Test
    fun `full workspace polls capacity without locked acquisitions for its 248 waiters`() = runTest {
        val fixture = DialFixture(this)
        val second = fixture.newCoordinator()
        val busy = List(8) { fixture.admit() }
        busy.forEach { fixture.coordinator.dispatchAfterCommit(it.id) }
        val waiting = List(248) { fixture.admit() }
        waiting.forEach { second.dispatchAfterCommit(it.id) }
        runCurrent()
        assertEquals(busy.map { it.id }, fixture.occupancyAcquisitionAttempts)
        advanceTimeBy(1_501)
        runCurrent()
        assertTrue(fixture.occupancyCapacityChecks > busy.size + waiting.size)
        assertEquals(busy.map { it.id }, fixture.occupancyAcquisitionAttempts)
        assertTrue(waiting.none { it.id in fixture.started })

        waiting.forEach { second.cancelLiveDial(it.id) }
        busy.forEach { fixture.finish(it) }
        runCurrent()
        fixture.assertNoPermitsOrDials()
    }

    @Test
    fun `release just after a failed retry starts one waiter on another coordinator within one second`() = runTest {
        val fixture = DialFixture(this)
        val second = fixture.newCoordinator()
        val busy = List(8) { fixture.admit() }
        busy.forEach { fixture.coordinator.dispatchAfterCommit(it.id) }
        val waiting = List(2) { fixture.admit() }
        waiting.forEach { second.dispatchAfterCommit(it.id) }
        runCurrent()
        advanceTimeBy(501)
        runCurrent()
        assertEquals(8, fixture.started.size)
        assertTrue(waiting.none { fixture.runs.getValue(it.id).executionOccupancyAcquiredAt != null })
        assertEquals(busy.map { it.id }, fixture.occupancyAcquisitionAttempts)
        assertTrue((busy + waiting).all { fixture.runs.getValue(it.id).lastHeartbeatAt == fixture.origin })

        fixture.finish(busy.first())
        runCurrent()
        val releasedAt = assertNotNull(fixture.runs.getValue(busy.first().id).executionOccupancyReleasedAt)
        assertEquals(fixture.clock.instant(), releasedAt)
        advanceTimeBy(499)
        runCurrent()
        val next = waiting.single { it.id in fixture.started }
        val acquiredAt = assertNotNull(fixture.runs.getValue(next.id).executionOccupancyAcquiredAt)
        assertEquals(Duration.ofMillis(499), Duration.between(releasedAt, acquiredAt))
        assertEquals(busy.map { it.id } + next.id, fixture.occupancyAcquisitionAttempts)
        assertEquals(8, fixture.runs.values.count {
            it.executionOccupancyAcquiredAt != null && it.executionOccupancyReleasedAt == null
        })
        assertEquals(acquiredAt, fixture.runs.getValue(next.id).lastHeartbeatAt)
        assertTrue((busy + waiting).filter { it.id != next.id }.all {
            fixture.runs.getValue(it.id).lastHeartbeatAt == fixture.origin
        })

        fixture.finish(next)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertTrue(waiting.all { it.id in fixture.started })
        (busy + waiting).forEach { fixture.finish(it) }
        runCurrent()
        fixture.assertNoPermitsOrDials()
    }

    @Test
    fun `twelve artifact runs hold eight permits through suspension and start next only on release`() = runTest {
        val fixture = DialFixture(this)
        val runs = List(12) { fixture.admit() }
        runs.forEach { fixture.coordinator.dispatchAfterCommit(it.id) }
        runCurrent()
        assertEquals(runs.take(8).map { it.id }, fixture.started)
        assertEquals(8, fixture.credentialReads.size)
        assertEquals(12, fixture.runs.values.count { it.dispatchClaimedAt != null })
        advanceTimeBy(120_000)
        runCurrent()
        assertTrue(runs.all { fixture.runs.getValue(it.id).lastHeartbeatAt == fixture.clock.instant() })
        assertEquals(runs.last().deadline, fixture.runs.getValue(runs.last().id).deadline)
        fixture.finish(runs.first())
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(runs.take(9).map { it.id }, fixture.started)
        val next = fixture.runs.getValue(runs[8].id)
        assertEquals(fixture.clock.instant().plusSeconds(3600), next.deadline)
        assertEquals(next.deadline, fixture.assignments.getValue(next.id).deadline)
        assertEquals(next.deadline, fixture.credentialReads.last().serverDeadline)
        runs.forEach { fixture.finish(it) }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertTrue(fixture.runs.values.all { it.state == ExecutionRunState.SUCCEEDED })
        assertTrue(fixture.assignments.values.all { it.credentialDocument.all { byte -> byte == 0.toByte() } })
        fixture.assertNoPermitsOrDials()
    }

    @Test
    fun `inline and artifact work share the same workspace permits and other workspaces remain independent`() = runTest {
        val fixture = DialFixture(this)
        val busy = List(8) { fixture.admit() }
        busy.forEach { fixture.coordinator.dispatchAfterCommit(it.id) }
        val waitingInline = fixture.admit(inline = true)
        val inline = async { fixture.coordinator.runInline(waitingInline.id) {} }
        val other = fixture.admit(workspace = WorkspaceId.generate(), inline = true)
        val otherInline = async { fixture.coordinator.runInline(other.id) {} }
        runCurrent()
        assertEquals(9, fixture.started.size)
        assertFalse(waitingInline.id in fixture.started)
        assertTrue(other.id in fixture.started)
        fixture.finish(busy.first())
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertTrue(waitingInline.id in fixture.started)
        (busy + waitingInline + other).forEach { fixture.finish(it) }
        runCurrent()
        assertEquals(PhysicalOutcomeKind.SUCCEEDED, inline.await().kind)
        assertEquals(PhysicalOutcomeKind.SUCCEEDED, otherInline.await().kind)
        fixture.assertNoPermitsOrDials()
    }

    @Test
    fun `waiting caller cancellation propagates without materializing credentials or leaking a permit`() = runTest {
        val fixture = DialFixture(this)
        val busy = List(8) { fixture.admit() }
        busy.forEach { fixture.coordinator.dispatchAfterCommit(it.id) }
        val waiting = fixture.admit(inline = true)
        val caller = async { fixture.coordinator.runInline(waiting.id) {} }
        runCurrent()
        caller.cancelAndJoin()
        assertTrue(caller.isCancelled)
        assertEquals(ExecutionRunState.CANCELLED, fixture.runs.getValue(waiting.id).state)
        assertEquals(8, fixture.credentialReads.size)
        val next = fixture.admit()
        fixture.coordinator.dispatchAfterCommit(next.id)
        fixture.finish(busy.first())
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertTrue(next.id in fixture.started)
        (busy + next).forEach { fixture.finish(it) }
        runCurrent()
        fixture.assertNoPermitsOrDials()
    }

    @Test
    fun `one hour admission waiting timeout fails without dialing while execution has its own hour`() = runTest {
        val fixture = DialFixture(this)
        val busy = List(8) { fixture.admit() }
        busy.forEach { fixture.coordinator.dispatchAfterCommit(it.id) }
        val waiting = fixture.admit()
        fixture.coordinator.dispatchAfterCommit(waiting.id)
        runCurrent()
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals("EXECUTION_UNAVAILABLE", fixture.runs.getValue(waiting.id).physicalOutcome?.failureCode)
        assertFalse(waiting.id in fixture.started)
        assertTrue(busy.all { fixture.runs.getValue(it.id).physicalOutcome?.failureCode == "EXECUTION_DEADLINE_EXCEEDED" })
        fixture.assertNoPermitsOrDials()
    }

    @Test
    fun `deadline begins after waiting and protects a suspended inline stream`() = runTest {
        val fixture = DialFixture(this)
        val busy = List(8) { fixture.admit() }
        busy.forEach { fixture.coordinator.dispatchAfterCommit(it.id) }
        val waiting = fixture.admit(inline = true)
        val caller = async { fixture.coordinator.runInline(waiting.id) {} }
        runCurrent()
        advanceTimeBy(3_000_000)
        busy.forEach { fixture.finish(it) }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(fixture.origin.plusMillis(6_600_500), fixture.assignments.getValue(waiting.id).deadline)
        advanceTimeBy(3_599_999)
        runCurrent()
        assertFalse(caller.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("EXECUTION_DEADLINE_EXCEEDED", caller.await().failureCode)
        fixture.assertNoPermitsOrDials()
    }

    @Test
    fun `a run whose connector credential was destroyed fails as connector unavailable`() = runTest {
        val fixture = DialFixture(this)
        val deletedConnector = fixture.admit()
        val keyUnavailable = fixture.admit()
        fixture.destroyedCredentials += deletedConnector.id
        fixture.rejectedCredentials += keyUnavailable.id
        fixture.coordinator.dispatchAfterCommit(deletedConnector.id)
        fixture.coordinator.dispatchAfterCommit(keyUnavailable.id)
        runCurrent()
        val failed = fixture.runs.getValue(deletedConnector.id)
        assertEquals(ExecutionRunState.FAILED, failed.state)
        assertEquals("CONNECTOR_UNAVAILABLE", failed.physicalOutcome?.failureCode)
        assertEquals("The connector was deleted or its credentials are no longer available", failed.physicalOutcome?.safeMessage)
        assertNotNull(failed.ownerSettledAt)
        assertEquals("EXECUTION_ORPHANED", fixture.runs.getValue(keyUnavailable.id).physicalOutcome?.failureCode)
        assertTrue(deletedConnector.id !in fixture.started)
        fixture.assertNoPermitsOrDials()
    }

    @Test
    fun `credential failure stream failure and durable cancellation all release permits`() = runTest {
        val fixture = DialFixture(this)
        val credentialFailure = fixture.admit()
        val streamFailure = fixture.admit()
        fixture.rejectedCredentials += credentialFailure.id
        fixture.failedStreams += streamFailure.id
        val others = List(10) { fixture.admit() }
        (listOf(credentialFailure, streamFailure) + others).forEach { fixture.coordinator.dispatchAfterCommit(it.id) }
        runCurrent()
        assertEquals(ExecutionRunState.FAILED, fixture.runs.getValue(credentialFailure.id).state)
        assertEquals(ExecutionRunState.FAILED, fixture.runs.getValue(streamFailure.id).state)
        assertEquals(8, others.count { it.id in fixture.started })
        val cancelled = others.first()
        fixture.runs[cancelled.id] = fixture.runs.getValue(cancelled.id).copy(
            state = ExecutionRunState.CANCELLED,
            physicalOutcome = PhysicalOutcome("${cancelled.id}:terminal", PhysicalOutcomeKind.CANCELLED, null, "User cancelled"),
        )
        fixture.coordinator.cancelLiveDial(cancelled.id)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals("User cancelled", fixture.runs.getValue(cancelled.id).physicalOutcome?.safeMessage)
        assertEquals(9, others.count { it.id in fixture.started })
        others.forEach { fixture.finish(it) }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        fixture.assertNoPermitsOrDials()
    }

    @Test
    fun `cancellation during credential read does not dial and returns the permit`() = runTest {
        val fixture = DialFixture(this)
        val blocked = fixture.admit(inline = true)
        fixture.blockedCredentials += blocked.id
        val caller = async { fixture.coordinator.runInline(blocked.id) {} }
        runCurrent()
        caller.cancelAndJoin()
        assertTrue(caller.isCancelled)
        assertTrue(fixture.started.isEmpty())
        fixture.assertNoPermitsOrDials()
    }

    @Test
    fun `permit acquired at dispatch deadline records waiting failure immediately`() = runTest {
        val fixture = DialFixture(this)
        val run = fixture.admit()
        fixture.afterClaim = { fixture.clockReadsBeforeDeadlineJump = 1 }
        fixture.coordinator.dispatchAfterCommit(run.id)
        runCurrent()
        assertEquals("EXECUTION_UNAVAILABLE", fixture.runs.getValue(run.id).physicalOutcome?.failureCode)
        assertTrue(fixture.credentialReads.isEmpty())
        fixture.assertNoPermitsOrDials()
    }

    @Test
    fun `terminalized waiter never materializes credentials after another run releases a permit`() = runTest {
        val fixture = DialFixture(this)
        val busy = List(8) { fixture.admit() }
        busy.forEach { fixture.coordinator.dispatchAfterCommit(it.id) }
        val waiting = fixture.admit()
        fixture.coordinator.dispatchAfterCommit(waiting.id)
        runCurrent()
        val terminal = PhysicalOutcome("${waiting.id}:terminal", PhysicalOutcomeKind.FAILED, "EXECUTION_ORPHANED", "Execution interrupted")
        fixture.runs[waiting.id] = fixture.runs.getValue(waiting.id).copy(state = ExecutionRunState.FAILED, physicalOutcome = terminal)
        fixture.finish(busy.first())
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertFalse(waiting.id in fixture.started)
        assertEquals(8, fixture.credentialReads.size)
        assertEquals(terminal, fixture.runs.getValue(waiting.id).physicalOutcome)
        busy.forEach { fixture.finish(it) }
        runCurrent()
        fixture.assertNoPermitsOrDials()
    }

    @Test
    fun `dispatch preserves a tracked lazy dial and cancellation reaches it before start`() = runTest {
        val fixture = DialFixture(this)
        val run = fixture.admit()
        val lazyDial = launch(start = CoroutineStart.LAZY) { error("cancelled lazy dial must not execute") }
        val field = ExecutionRunDialCoordinator::class.java.getDeclaredField("liveDials").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val liveDials = field.get(fixture.coordinator) as MutableMap<ExecutionRunId, Job>
        liveDials[run.id] = lazyDial
        assertFalse(lazyDial.isActive)
        assertFalse(lazyDial.isCompleted)

        fixture.coordinator.dispatchAfterCommit(run.id)

        assertSame(lazyDial, liveDials[run.id])
        fixture.coordinator.cancelLiveDial(run.id)
        assertTrue(lazyDial.isCancelled)
        assertFalse(lazyDial.start())
        runCurrent()
        assertTrue(fixture.started.isEmpty())
        fixture.assertNoPermitsOrDials()
    }

    @Test
    fun `repeated dispatch never starts a second physical run`() = runTest {
        val fixture = DialFixture(this)
        val run = fixture.admit()
        repeat(3) { fixture.coordinator.dispatchAfterCommit(run.id) }
        runCurrent()
        assertEquals(listOf(run.id), fixture.started)
        fixture.finish(run)
        runCurrent()
        fixture.coordinator.dispatchAfterCommit(run.id)
        runCurrent()
        assertEquals(listOf(run.id), fixture.started)
        fixture.assertNoPermitsOrDials()
    }

    @Test
    fun `two coordinators share eight occupancy records and missed cancel notification stops at heartbeat`() = runTest {
        val fixture = DialFixture(this)
        val second = fixture.newCoordinator()
        val runs = List(10) { fixture.admit() }
        runs.take(5).forEach { fixture.coordinator.dispatchAfterCommit(it.id) }
        runs.drop(5).forEach { second.dispatchAfterCommit(it.id) }
        runCurrent()
        assertEquals(8, fixture.started.size)
        val cancelled = runs.first()
        fixture.cancelInDatabase(cancelled)
        assertNull(fixture.runs.getValue(cancelled.id).executionOccupancyReleasedAt)
        advanceTimeBy(10_000)
        runCurrent()
        assertNotNull(fixture.runs.getValue(cancelled.id).executionOccupancyReleasedAt)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(9, fixture.started.size)
        runs.forEach { fixture.finish(it) }
        advanceTimeBy(500)
        runCurrent()
        fixture.assertNoPermitsOrDials()
    }

    @Test
    fun `cancelled stream keeps occupancy and heartbeat until its cleanup finishes`() = runTest {
        val fixture = DialFixture(this)
        val runs = List(9) { fixture.admit() }
        val cleanup = CompletableDeferred<Unit>()
        fixture.cleanupGates[runs.first().id] = cleanup
        runs.forEach { fixture.coordinator.dispatchAfterCommit(it.id) }
        runCurrent()
        fixture.cancelInDatabase(runs.first())
        fixture.coordinator.reconcileLiveDial(runs.first().id)
        advanceTimeBy(20_000)
        runCurrent()
        val stopping = fixture.runs.getValue(runs.first().id)
        assertNull(stopping.executionOccupancyReleasedAt)
        assertEquals(fixture.clock.instant(), stopping.lastHeartbeatAt)
        assertFalse(runs.last().id in fixture.started)
        cleanup.complete(Unit)
        runCurrent()
        assertTrue(fixture.assignments.getValue(runs.first().id).credentialDocument.all { it == 0.toByte() })
        assertNotNull(fixture.runs.getValue(runs.first().id).executionOccupancyReleasedAt)
        advanceTimeBy(500)
        runCurrent()
        assertTrue(runs.last().id in fixture.started)
        runs.forEach { fixture.finish(it) }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        fixture.assertNoPermitsOrDials()
    }

    @Test
    fun `release database failure retains occupancy for recovery without reentering`() = runTest {
        val fixture = DialFixture(this)
        val run = fixture.admit()
        fixture.failedReleases += run.id
        fixture.coordinator.dispatchAfterCommit(run.id)
        runCurrent()
        fixture.finish(run)
        runCurrent()
        assertNull(fixture.runs.getValue(run.id).executionOccupancyReleasedAt)
        assertEquals(ExecutionRunState.SUCCEEDED, fixture.runs.getValue(run.id).state)
        fixture.coordinator.dispatchAfterCommit(run.id)
        runCurrent()
        assertEquals(listOf(run.id), fixture.started)
        fixture.assertNoDials()
    }

    @Test
    fun `heartbeat database failure stops local stream and does not dispatch waiting work`() = runTest {
        val fixture = DialFixture(this)
        val run = fixture.admit()
        fixture.coordinator.dispatchAfterCommit(run.id)
        runCurrent()
        fixture.failedHeartbeats += run.id
        advanceTimeBy(10_000)
        runCurrent()
        fixture.assertNoDials()
        assertEquals(ExecutionRunState.FAILED, fixture.runs.getValue(run.id).state)
        assertNotNull(fixture.runs.getValue(run.id).executionOccupancyReleasedAt)
        assertTrue(fixture.assignments.getValue(run.id).credentialDocument.all { it == 0.toByte() })
    }

    @Test
    fun `heartbeat during a slow occupancy release does not cancel an inline caller that succeeded`() = runTest {
        val fixture = DialFixture(this)
        val run = fixture.admit(inline = true)
        fixture.releaseDelays[run.id] = 25_000
        val caller = async { fixture.coordinator.runInline(run.id) {} }
        runCurrent()
        fixture.finish(run)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()

        assertFalse(caller.isCancelled)
        assertEquals(PhysicalOutcomeKind.SUCCEEDED, caller.await().kind)
        fixture.assertNoPermitsOrDials()
    }

    @Test
    fun `heartbeat after this dial recorded the terminal does not cancel the inline caller`() = runTest {
        val fixture = DialFixture(this)
        val run = fixture.admit(inline = true)
        fixture.terminalAcknowledgementDelays[run.id] = 25_000
        val caller = async { fixture.coordinator.runInline(run.id) {} }
        runCurrent()
        fixture.finish(run)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()

        assertFalse(caller.isCancelled)
        assertEquals(PhysicalOutcomeKind.SUCCEEDED, caller.await().kind)
        fixture.assertNoPermitsOrDials()
    }

    @Test
    fun `terminal outcome settles the owner on the dial path without waiting for recovery`() = runTest {
        val fixture = DialFixture(this)
        val run = fixture.admit()
        fixture.coordinator.dispatchAfterCommit(run.id)
        runCurrent()
        assertTrue(fixture.ownerSettlements.isEmpty())
        advanceTimeBy(1_500)
        fixture.finish(run)
        runCurrent()
        val settled = fixture.runs.getValue(run.id)
        assertEquals(ExecutionRunState.SUCCEEDED, settled.state)
        assertEquals(fixture.clock.instant(), settled.ownerSettledAt)
        assertEquals(listOf(run.id), fixture.ownerSettlements)
        assertNotNull(settled.executionOccupancyReleasedAt)
        fixture.assertNoPermitsOrDials()
    }

    @Test
    fun `failure terminals and owner cancellation settle immediately while catalog runs stay owner driven`() = runTest {
        val fixture = DialFixture(this)
        val streamFailure = fixture.admit()
        fixture.failedStreams += streamFailure.id
        val cancelled = fixture.admit()
        val catalog = fixture.admit(inline = true, ownerType = ExecutionOwnerType.CATALOG)
        fixture.coordinator.dispatchAfterCommit(streamFailure.id)
        fixture.coordinator.dispatchAfterCommit(cancelled.id)
        val inline = async { fixture.coordinator.runInline(catalog.id) {} }
        runCurrent()
        assertEquals("EXECUTION_ORPHANED", fixture.runs.getValue(streamFailure.id).physicalOutcome?.failureCode)
        assertNotNull(fixture.runs.getValue(streamFailure.id).ownerSettledAt)
        fixture.cancelInDatabase(cancelled)
        fixture.coordinator.cancelLiveDial(cancelled.id)
        fixture.finish(catalog)
        runCurrent()
        assertEquals("User cancelled", fixture.runs.getValue(cancelled.id).physicalOutcome?.safeMessage)
        assertNotNull(fixture.runs.getValue(cancelled.id).ownerSettledAt)
        assertEquals(PhysicalOutcomeKind.SUCCEEDED, inline.await().kind)
        assertNull(fixture.runs.getValue(catalog.id).ownerSettledAt)
        assertEquals(listOf(streamFailure.id, cancelled.id), fixture.ownerSettlements)
        fixture.assertNoPermitsOrDials()
    }

    @Test
    fun `immediate settlement failure keeps the terminal outcome and recovery settles the run`() = runTest {
        val fixture = DialFixture(this)
        val run = fixture.admit()
        fixture.failedOwnerSettlements += run.id
        fixture.coordinator.dispatchAfterCommit(run.id)
        runCurrent()
        fixture.finish(run)
        runCurrent()
        val terminal = fixture.runs.getValue(run.id)
        assertEquals(ExecutionRunState.SUCCEEDED, terminal.state)
        assertEquals(PhysicalOutcomeKind.SUCCEEDED, terminal.physicalOutcome?.kind)
        assertNull(terminal.ownerSettledAt)
        assertTrue(fixture.ownerSettlements.isEmpty())
        fixture.assertNoPermitsOrDials()

        fixture.failedOwnerSettlements.clear()
        advanceTimeBy(30_000)
        val recovery = fixture.recovery()
        invokeRecoveryCycle(recovery)

        assertEquals(fixture.clock.instant(), fixture.runs.getValue(run.id).ownerSettledAt)
        assertEquals(listOf(run.id), fixture.ownerSettlements)
        assertEquals(0, recovery.snapshot().failedCandidates)
    }

    @Test
    fun `recovery settling a run the dial path already settled is an idempotent duplicate`() = runTest {
        val fixture = DialFixture(this)
        val run = fixture.admit()
        fixture.coordinator.dispatchAfterCommit(run.id)
        runCurrent()
        val staleRecoveryCandidates = listOf(fixture.runs.getValue(run.id))
        fixture.finish(run)
        runCurrent()
        val settled = fixture.runs.getValue(run.id)
        assertNotNull(settled.ownerSettledAt)
        fixture.recoverySettlementCandidates = { staleRecoveryCandidates }
        advanceTimeBy(30_000)
        val recovery = fixture.recovery()

        invokeRecoveryCycle(recovery)
        assertIs<ExecutionRunSettlementUseCase.SettlementOutcome.Duplicate>(
            fixture.settlement.settle(run.id, fixture.clock.instant()),
        )

        assertEquals(settled, fixture.runs.getValue(run.id))
        assertEquals(listOf(run.id), fixture.ownerSettlements)
        assertEquals(0, recovery.snapshot().failedCandidates)
    }

    private fun invokeRecoveryCycle(lifecycle: ExecutionRunRecoveryLifecycle) {
        ExecutionRunRecoveryLifecycle::class.java.getDeclaredMethod("runCycleSafely").apply {
            isAccessible = true
        }.invoke(lifecycle)
    }
}

private class DialFixture(private val scope: TestScope) {
    val origin: Instant = Instant.parse("2026-09-09T00:00:00Z")
    var clockOffsetMillis = 0L
    var clockReadsBeforeDeadlineJump: Int? = null
    val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant {
            clockReadsBeforeDeadlineJump?.let { remaining ->
                if (remaining == 0) {
                    clockOffsetMillis = 3_600_000
                    clockReadsBeforeDeadlineJump = null
                } else clockReadsBeforeDeadlineJump = remaining - 1
            }
            return origin.plusMillis(scope.testScheduler.currentTime + clockOffsetMillis)
        }
    }
    private val workspace = WorkspaceId.generate()
    val runs = linkedMapOf<ExecutionRunId, ExecutionRunSnapshot>()
    val started = mutableListOf<ExecutionRunId>()
    val occupancyAcquisitionAttempts = mutableListOf<ExecutionRunId>()
    var occupancyCapacityChecks = 0
    val assignments = mutableMapOf<ExecutionRunId, ControlRunAssignment>()
    val executionLoggingContexts = mutableMapOf<ExecutionRunId, Map<String, String>>()
    val heartbeatLoggingContexts = mutableMapOf<ExecutionRunId, MutableList<Map<String, String>>>()
    val credentialReads = mutableListOf<ManagedCredentialReadRequest>()
    val rejectedCredentials = mutableSetOf<ExecutionRunId>()
    val destroyedCredentials = mutableSetOf<ExecutionRunId>()
    val blockedCredentials = mutableSetOf<ExecutionRunId>()
    val failedStreams = mutableSetOf<ExecutionRunId>()
    val failedReleases = mutableSetOf<ExecutionRunId>()
    val failedHeartbeats = mutableSetOf<ExecutionRunId>()
    val releaseDelays = mutableMapOf<ExecutionRunId, Long>()
    val terminalAcknowledgementDelays = mutableMapOf<ExecutionRunId, Long>()
    val cleanupGates = mutableMapOf<ExecutionRunId, CompletableDeferred<Unit>>()
    var afterClaim: () -> Unit = {}
    var beforeGet: (ExecutionRunId) -> Unit = {}
    var beforeClaim: () -> Unit = {}
    var beforeRecordPhysicalOutcome: () -> Unit = {}
    private val completions = mutableMapOf<ExecutionRunId, CompletableDeferred<Unit>>()
    private val query = object : ExecutionRunQueryPort {
        override suspend fun get(runId: ExecutionRunId): ExecutionRunSnapshot? {
            beforeGet(runId)
            return runs[runId]
        }
        override suspend fun hasExecutionOccupancyCapacity(workspaceId: WorkspaceId): Boolean {
            occupancyCapacityChecks++
            return runs.values.count {
                it.workspaceId == workspaceId && it.executionOccupancyAcquiredAt != null && it.executionOccupancyReleasedAt == null
            } < 8
        }
        override suspend fun readSql(command: ExecutionRunQueryPort.ReadSqlCommand) = error("SQL history is outside this fixture")
        override suspend fun readSqlBatch(command: ExecutionRunQueryPort.ReadSqlBatchCommand) = error("SQL history is outside this fixture")
    }
    private val useCase = mockk<ExecutionRunUseCase>()
    private val dispatch = object : RunDispatchIntentPort {
        override fun persist(intent: RunDispatchIntent) = error("Admission is outside this fixture")
        override fun findAll(runIds: Set<ExecutionRunId>): List<cc.monomer.execution.api.StoredDispatchIntentRead> = error("SQL history is outside this fixture")
        override fun find(runId: ExecutionRunId): RunDispatchIntent {
            val run = runs.getValue(runId)
            return RunDispatchIntent(run.id, digest(), BinaryValue.copyOf(byteArrayOf(1)), run.dispatchDeadlineAt, run.createdAt)
        }
    }
    private val credentials = mockk<ManagedCredentialReadPort>()
    private val execution = mockk<ExecutionRunPort>()
    private val artifacts = mockk<ExecutionArtifactStore>()
    val ownerSettlements = mutableListOf<ExecutionRunId>()
    val failedOwnerSettlements = mutableSetOf<ExecutionRunId>()
    var recoverySettlementCandidates: () -> List<ExecutionRunSnapshot> = {
        runs.values.filter { it.physicalOutcome != null && it.ownerSettledAt == null }
    }
    private val exploreOwner = object : ExecutionRunOwnerSettlementPort {
        override val ownerType = ExecutionOwnerType.EXPLORE
        override fun settleInOwnerTransaction(command: ExecutionRunOwnerSettlementPort.SettleCommand): ExecutionRunOwnerSettlementPort.SettlementOutcome {
            if (command.run.id in failedOwnerSettlements) error("Injected owner settlement database failure")
            ownerSettlements += command.run.id
            return ExecutionRunOwnerSettlementPort.SettlementOutcome.Completed
        }
    }
    private val runRepository = object : ExecutionRunRepository by mockk<ExecutionRunRepository>() {
        override fun requireAmbientTransaction() = Unit
        override fun find(runId: ExecutionRunId) = runs[runId]?.let { AdmittedExecutionRun(it, "admission-$runId", digest()) }
        override fun compareAndSet(expectedVersion: Long, snapshot: ExecutionRunSnapshot): Boolean {
            if (runs[snapshot.id]?.version != expectedVersion) return false
            runs[snapshot.id] = snapshot
            return true
        }
    }
    val settlement = ExecutionRunSettlementCoordinator(
        listOf(exploreOwner),
        runRepository,
        object : ExecutionRuntimeTransactionRunner {
            override suspend fun <T> run(block: () -> T): T = block()
        },
    )
    private val coordinatorScopes = mutableListOf<CoroutineScope>()

    private fun coordinatorScope(): CoroutineScope =
        CoroutineScope(scope.backgroundScope.coroutineContext + SupervisorJob(scope.backgroundScope.coroutineContext.job))
            .also(coordinatorScopes::add)

    fun newCoordinator(coroutineScope: CoroutineScope = coordinatorScope()) = ExecutionRunDialCoordinator(execution, query, useCase, settlement,
        setOf(ExecutionOwnerType.EXPLORE, ExecutionOwnerType.DASHBOARD), dispatch, credentials, artifacts,
        clock, coroutineScope, Duration.ofHours(1), 4096, 20, 100, 1000)
    val coordinator = newCoordinator()

    fun recovery() = ExecutionRunRecoveryLifecycle(
        executionRunUseCase = useCase,
        executionRunSettlementUseCase = settlement,
        executionArtifactStore = artifacts,
        clock = clock,
        limits = testExecutionLimits(),
        ownerSettlementTypes = setOf(ExecutionOwnerType.EXPLORE, ExecutionOwnerType.DASHBOARD),
    )

    init {
        coEvery { useCase.recover(any()) } answers {
            ExecutionRunUseCase.RunRecoveryBatch(
                dispatchDeadlineCandidates = emptyList(),
                executionDeadlineCandidates = emptyList(),
                orphanCandidates = emptyList(),
                ownerSettlementCandidates = recoverySettlementCandidates(),
                occupancyRecoveryCandidates = emptyList(),
                occupancyRecoveryTruncated = false,
                artifactCleanupCandidates = emptyList(),
                dispatchDeadlineTruncated = false,
                executionDeadlineTruncated = false,
                orphanTruncated = false,
                ownerSettlementTruncated = false,
                artifactCleanupTruncated = false,
                unreadableRunIds = emptyList(),
            )
        }
        coEvery { useCase.acquireExecutionOccupancy(any()) } answers {
            val command = firstArg<ExecutionRunUseCase.AcquireExecutionOccupancyCommand>()
            occupancyAcquisitionAttempts += command.runId
            val old = runs.getValue(command.runId)
            if (old.state.isPhysicalTerminal() || old.executionOccupancyAcquiredAt != null) ExecutionRunUseCase.RunMutationOutcome.Duplicate(old)
            else if (runs.values.count { it.workspaceId == old.workspaceId && it.executionOccupancyAcquiredAt != null && it.executionOccupancyReleasedAt == null } >= 8) {
                ExecutionRunUseCase.RunMutationOutcome.ExecutionOccupancyFull
            } else {
                val acquired = old.copy(executionOccupancyAcquiredAt = command.observedAt, version = old.version + 1)
                runs[old.id] = acquired
                ExecutionRunUseCase.RunMutationOutcome.Applied(acquired)
            }
        }
        coEvery { useCase.releaseExecutionOccupancy(any()) } coAnswers {
            val command = firstArg<ExecutionRunUseCase.ReleaseExecutionOccupancyCommand>()
            if (command.runId in failedReleases) error("Injected release database failure")
            releaseDelays[command.runId]?.let { delay(it) }
            val old = runs.getValue(command.runId)
            assertTrue(assignments[old.id]?.credentialDocument?.all { it == 0.toByte() } != false)
            if (!old.state.isPhysicalTerminal() || old.executionOccupancyReleasedAt != null) ExecutionRunUseCase.RunMutationOutcome.Duplicate(old)
            else {
                assertEquals(old.executionOccupancyAcquiredAt, command.acquiredAt)
                val released = old.copy(executionOccupancyReleasedAt = command.releasedAt, version = old.version + 1)
                runs[old.id] = released
                ExecutionRunUseCase.RunMutationOutcome.Applied(released)
            }
        }
        coEvery { useCase.heartbeat(any()) } answers {
            val command = firstArg<ExecutionRunUseCase.HeartbeatCommand>()
            heartbeatLoggingContexts.getOrPut(command.runId) { mutableListOf() }.add(MDC.getCopyOfContextMap().orEmpty())
            if (command.runId in failedHeartbeats) error("Injected heartbeat database failure")
            val old = runs.getValue(command.runId)
            if (old.executionOccupancyReleasedAt != null) ExecutionRunUseCase.RunMutationOutcome.Duplicate(old)
            else {
                val updated = old.copy(lastHeartbeatAt = command.observedAt, version = old.version + 1)
                runs[old.id] = updated
                ExecutionRunUseCase.RunMutationOutcome.Applied(updated)
            }
        }
        coEvery { useCase.claimDispatch(any()) } answers {
            beforeClaim()
            val command = firstArg<ExecutionRunUseCase.ClaimRunDispatchCommand>()
            val old = runs.getValue(command.runId)
            val claimed = old.copy(dispatchClaimedAt = command.claimedAt, lastHeartbeatAt = command.claimedAt,
                deadline = command.executionDeadlineAt, version = old.version + 1)
            runs[old.id] = claimed
            afterClaim()
            ExecutionRunUseCase.RunMutationOutcome.Applied(claimed)
        }
        coEvery { useCase.narrowExecutionDeadline(any()) } answers {
            val command = firstArg<ExecutionRunUseCase.NarrowExecutionDeadlineCommand>()
            val old = runs.getValue(command.runId)
            if (old.state.isPhysicalTerminal()) ExecutionRunUseCase.RunMutationOutcome.InvalidState
            else {
                val narrowed = old.copy(deadline = command.deadlineAt, version = old.version + 1)
                runs[old.id] = narrowed
                ExecutionRunUseCase.RunMutationOutcome.Applied(narrowed)
            }
        }
        coEvery { useCase.recordProgress(any()) } answers {
            val command = firstArg<ExecutionRunUseCase.RecordRunProgressCommand>()
            val old = runs.getValue(command.runId)
            val updated = old.copy(lastHeartbeatAt = command.receivedAt, updatedAt = command.receivedAt, version = old.version + 1)
            runs[old.id] = updated
            ExecutionRunUseCase.RunMutationOutcome.Applied(updated)
        }
        coEvery { useCase.recordPhysicalOutcome(any()) } coAnswers {
            beforeRecordPhysicalOutcome()
            val command = firstArg<ExecutionRunUseCase.RecordPhysicalOutcomeCommand>()
            val old = runs.getValue(command.runId)
            if (old.state.isPhysicalTerminal()) ExecutionRunUseCase.TerminalOutcome.TerminalConflict
            else {
                val updated = old.copy(physicalOutcome = command.outcome, resultManifest = command.outcome.arrowResultManifest, state = when (command.outcome.kind) {
                    PhysicalOutcomeKind.SUCCEEDED -> ExecutionRunState.SUCCEEDED
                    PhysicalOutcomeKind.FAILED -> ExecutionRunState.FAILED
                    PhysicalOutcomeKind.CANCELLED -> ExecutionRunState.CANCELLED
                })
                runs[old.id] = updated
                terminalAcknowledgementDelays[command.runId]?.let { delay(it) }
                ExecutionRunUseCase.TerminalOutcome.Accepted(updated)
            }
        }
        coEvery { credentials.readExact(any()) } coAnswers {
            val request = firstArg<ManagedCredentialReadRequest>()
            credentialReads += request
            val run = runs.values.single { it.connectorId.value == request.connectorId.value }
            if (run.id in blockedCredentials) awaitCancellation()
            if (run.id in rejectedCredentials) ManagedCredentialReadOutcome.Unavailable
            else if (run.id in destroyedCredentials) ManagedCredentialReadOutcome.Rejected
            else ManagedCredentialReadOutcome.Found(CredentialMaterial.copyOf(mapOf("password" to "test-secret".toByteArray())))
        }
        every { execution.run(any()) } answers {
            val assignment = firstArg<ControlRunAssignment>()
            flow {
                executionLoggingContexts[assignment.runId] = MDC.getCopyOfContextMap().orEmpty()
                started += assignment.runId
                assignments[assignment.runId] = assignment
                if (assignment.runId in failedStreams) error("Injected stream failure")
                try {
                    completions.getValue(assignment.runId).await()
                } finally {
                    cleanupGates[assignment.runId]?.let { gate -> withContext(NonCancellable) { gate.await() } }
                }
                val run = runs.getValue(assignment.runId)
                val manifest = run.runtimeBindings.resultSink.objectKey?.let {
                    ArrowResultManifest(it, 128u, "etag", 1u, 1, "application/vnd.apache.arrow.file")
                }
                emit(ExecutionRunEvent.Terminal(PhysicalOutcome("${run.id}:terminal", PhysicalOutcomeKind.SUCCEEDED, null, null, manifest)))
            }
        }
        coEvery { artifacts.head(any()) } answers { ExecutionArtifactHead(firstArg(), 128u, "etag", clock.instant()) }
    }

    fun admit(
        workspace: WorkspaceId = this.workspace,
        inline: Boolean = false,
        ownerType: ExecutionOwnerType = ExecutionOwnerType.EXPLORE,
    ): ExecutionRunSnapshot {
        val id = ExecutionRunId.generate()
        val at = clock.instant()
        val deadline = at.plusSeconds(7200)
        val sink = if (inline) ResultSinkBinding(2u, ResultSinkKind.INLINE_STREAM, null, null, null, null,
            100, 1024, 4096, null, digest())
        else ResultSinkBinding(2u, ResultSinkKind.ARTIFACT_UPLOAD, "artifacts/$id/result.arrow", 100u, 8192u, deadline,
            100, 1024, 4096, 8192, digest())
        val run = ExecutionRunSnapshot(id, ExecutionOwnerReference(ownerType, ExecutionOwnerId(Uuid.generateV7())),
            workspace, ConnectorReference(Uuid.generateV7()), ConnectorType.POSTGRESQL,
            mapOf("host" to ConnectorRuntimePropertyValue.Text("localhost")), ExecutableRequestKind.QUERY, digest(),
            RunRuntimeBindings(digest(), CredentialVersionReference(Uuid.generateV7()), "test-key", digest(), sink),
            at.plusSeconds(3600), deadline, ExecutionRunState.RUNNING, null, null, null, at, at, 0, executionOccupancyAcquiredAt = null, executionOccupancyReleasedAt = null, ownerDiscardedAt = null)
        runs[id] = run
        completions[id] = CompletableDeferred()
        return run
    }

    fun cancelInDatabase(run: ExecutionRunSnapshot) {
        runs[run.id] = runs.getValue(run.id).copy(state = ExecutionRunState.CANCELLED,
            physicalOutcome = PhysicalOutcome("${run.id}:terminal", PhysicalOutcomeKind.CANCELLED, null, "User cancelled"))
    }

    fun finish(run: ExecutionRunSnapshot) { completions.getValue(run.id).complete(Unit) }

    fun assertNoDials() {
        assertTrue(coordinatorScopes.all { coordinatorScope -> coordinatorScope.coroutineContext.job.children.none { it.isActive } })
    }

    fun assertNoPermitsOrDials() {
        assertNoDials()
        assertTrue(runs.values.none { it.executionOccupancyAcquiredAt != null && it.executionOccupancyReleasedAt == null })
    }

    private fun digest() = Sha256Digest("a".repeat(64))
}

private fun correlationAppender(): ListAppender<ILoggingEvent> = object : ListAppender<ILoggingEvent>() {
    override fun append(eventObject: ILoggingEvent) {
        eventObject.prepareForDeferredProcessing()
        super.append(eventObject)
    }
}.apply { start() }
