@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.catalog.application.ingest

import cc.monomer.catalog.api.CatalogCommandResult
import cc.monomer.catalog.api.CatalogIngestEntry
import cc.monomer.catalog.api.CatalogIngestJobId
import cc.monomer.catalog.api.CatalogSelection
import cc.monomer.catalog.application.CatalogIngestRepository
import cc.monomer.catalog.application.CatalogSelectionRepository
import cc.monomer.catalog.application.CatalogTransactionPort
import cc.monomer.catalog.application.ingest.CatalogIngestApplicationService
import cc.monomer.catalog.application.ingest.CatalogIngestCancelCommand
import cc.monomer.catalog.application.ingest.CatalogIngestCascadeRunner
import cc.monomer.catalog.application.ingest.CatalogIngestJobView
import cc.monomer.catalog.domain.CatalogIngestJob
import cc.monomer.catalog.domain.CatalogIngestJobState
import cc.monomer.catalog.domain.CatalogIngestJobSummary
import cc.monomer.catalog.domain.CatalogIngestTask
import cc.monomer.catalog.domain.CatalogIngestTaskTarget
import cc.monomer.connector.api.ConnectorExecutionAccessResult
import cc.monomer.connector.api.ConnectorExecutionAccessUseCase
import cc.monomer.connector.api.ConnectorExecutionBinding
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorRuntimeBindingProjectionPort
import cc.monomer.execution.api.ConnectorRuntimeBindingSpec
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.OwnerExecutionRunCancellationPort
import cc.monomer.identity.api.AuthorizedExecutionContext
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

class CatalogIngestApplicationServiceRaceTest {
    @Test
    fun `each claimed job captures its current binding once and keeps it for the cascade`() = runBlocking {
        val now = Instant.parse("2026-08-19T03:00:00Z")
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val jobA = job(workspaceId, connectorId, now)
        val jobB = job(workspaceId, connectorId, now.plusSeconds(1))
        val currentJobs = ConcurrentHashMap(mapOf(jobA.id to jobA, jobB.id to jobB))
        val bindingA = mockk<ConnectorExecutionBinding>(relaxed = true)
        val bindingB = mockk<ConnectorExecutionBinding>(relaxed = true)
        val runtimeBindingA = mockk<ConnectorRuntimeBindingSpec>(relaxed = true)
        val runtimeBindingB = mockk<ConnectorRuntimeBindingSpec>(relaxed = true)
        val executionAccess = mockk<ConnectorExecutionAccessUseCase>()
        val bindingProjection = mockk<ConnectorRuntimeBindingProjectionPort>()
        every { bindingProjection.project(bindingA) } returns runtimeBindingA
        every { bindingProjection.project(bindingB) } returns runtimeBindingB
        val currentBinding = AtomicReference<ConnectorExecutionBinding>(bindingA)
        coEvery { executionAccess.readCurrentForExecution(workspaceId, connectorId) } answers {
            ConnectorExecutionAccessResult.Granted(currentBinding.get())
        }
        val observedBindings = ConcurrentHashMap<CatalogIngestJobId, ConnectorRuntimeBindingSpec>()
        val firstCascadeStarted = CompletableDeferred<Unit>()
        val secondCascadeStarted = CompletableDeferred<Unit>()
        val releaseCascades = CompletableDeferred<Unit>()
        val repository = mockk<CatalogSelectionAndIngestRepository>(relaxed = true)
        every { repository.claimIngestJob(jobA.id, any(), any()) } answers {
            currentJobs[jobA.id] = currentJobs.getValue(jobA.id).claim("runner-a", now)
            currentJobs.getValue(jobA.id)
        }
        every { repository.claimIngestJob(jobB.id, any(), any()) } answers {
            currentJobs[jobB.id] = currentJobs.getValue(jobB.id).claim("runner-a", now)
            currentJobs.getValue(jobB.id)
        }
        every { repository.findIngestJob(jobA.id) } answers { currentJobs[jobA.id] }
        every { repository.findIngestJob(jobB.id) } answers { currentJobs[jobB.id] }
        val cascadeRunner = mockk<CatalogIngestCascadeRunner>()
        coEvery { cascadeRunner.run(any(), any(), any()) } coAnswers {
            val claimed = firstArg<CatalogIngestJob>()
            observedBindings[claimed.id] = secondArg()
            if (claimed.id == jobA.id) firstCascadeStarted.complete(Unit)
            if (claimed.id == jobB.id) secondCascadeStarted.complete(Unit)
            releaseCascades.await()
            claimed
        }
        val runnerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = service(
            repository = repository,
            executionAccess = executionAccess,
            bindingProjection = bindingProjection,
            cascadeRunner = cascadeRunner,
            runnerScope = runnerScope,
            now = now,
        )

        try {
            val first = launch { service.runClaimedJob(jobA) }
            withTimeout(2_000) { firstCascadeStarted.await() }
            currentBinding.set(bindingB)
            val second = launch { service.runClaimedJob(jobB) }
            withTimeout(2_000) { secondCascadeStarted.await() }
            releaseCascades.complete(Unit)
            joinAll(first, second)
        } finally {
            releaseCascades.complete(Unit)
            runnerScope.cancel()
        }

        assertEquals(mapOf(jobA.id to runtimeBindingA, jobB.id to runtimeBindingB), observedBindings)
        coVerify(exactly = 2) { executionAccess.readCurrentForExecution(workspaceId, connectorId) }
        coVerify(exactly = 1) {
            cascadeRunner.run(jobA.copy(runnerInstanceId = "runner-a", heartbeatAt = now), runtimeBindingA, "runner-a")
        }
        coVerify(exactly = 1) {
            cascadeRunner.run(jobB.copy(runnerInstanceId = "runner-a", heartbeatAt = now), runtimeBindingB, "runner-a")
        }
    }

    @Test
    fun `catalog cancellation is accepted while a claimed cascade is in flight`() = runBlocking {
        val now = Instant.parse("2026-08-19T02:00:00Z")
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val job = job(workspaceId, connectorId, now)
        var currentJob = job
        val repository = mockk<CatalogSelectionAndIngestRepository>(relaxed = true)
        every { repository.claimIngestJob(job.id, any(), any()) } answers {
            currentJob = currentJob.claim("runner-a", now.plusMillis(1))
            currentJob
        }
        every { repository.findIngestJob(job.id) } answers { currentJob }
        every { repository.findIngestJobSummary(job.id) } answers {
            CatalogIngestJobSummary(currentJob, 0, 0, 0)
        }
        every { repository.lockIngestJob(job.id) } answers { currentJob }
        every { repository.updateIngestJob(any()) } answers {
            currentJob = invocation.args[0] as CatalogIngestJob
            true
        }
        every { repository.findIngestTasks(job.id) } returns emptyList()
        every { repository.findRunningIngestTasks(job.id) } returns emptyList()

        val executionAccess = mockk<ConnectorExecutionAccessUseCase>()
        val binding = mockk<ConnectorExecutionBinding>(relaxed = true)
        coEvery { executionAccess.readCurrentForExecution(workspaceId, connectorId) } returns
            ConnectorExecutionAccessResult.Granted(binding)
        val bindingProjection = mockk<ConnectorRuntimeBindingProjectionPort>()
        val runtimeBinding = mockk<ConnectorRuntimeBindingSpec>(relaxed = true)
        every { bindingProjection.project(binding) } returns runtimeBinding
        val cascadeStarted = CompletableDeferred<Unit>()
        val allowCascade = CompletableDeferred<Unit>()
        val cascadeRunner = mockk<CatalogIngestCascadeRunner>()
        coEvery { cascadeRunner.run(any(), any(), any()) } coAnswers {
            cascadeStarted.complete(Unit)
            allowCascade.await()
            currentJob = currentJob.cancel(now.plusMillis(3))
            currentJob
        }
        val authorization = mockk<AuthorizedExecutionContext>(relaxed = true)
        every { authorization.agentClientId } returns null
        every { authorization.workspaceId } returns workspaceId
        val runnerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = service(
            repository = repository,
            executionAccess = executionAccess,
            bindingProjection = bindingProjection,
            cascadeRunner = cascadeRunner,
            runnerScope = runnerScope,
            now = now,
        )

        try {
            val run = service.launchClaimedJob(job)
            withTimeout(2_000) { cascadeStarted.await() }
            val cancellation = service.cancelIngest(
                CatalogIngestCancelCommand(
                    authorization = authorization,
                    connectorId = connectorId,
                    jobId = job.id,
                    reasonCode = "CANCELLED_BY_ACTOR",
                ),
            )
            assertEquals(CatalogIngestJobState.CANCEL_REQUESTED, currentJob.state)
            assertEquals(
                workspaceId,
                (cancellation as CatalogCommandResult.Accepted).value.job.workspaceId,
            )
            allowCascade.complete(Unit)
            withTimeout(2_000) { run.join() }
            assertEquals(CatalogIngestJobState.CANCELLED, currentJob.state)
        } finally {
            allowCascade.complete(Unit)
            runnerScope.cancel()
        }
    }

    @Test
    fun `unclaimed catalog cancellation settles immediately without tasks or owner run cancellation`() = runBlocking {
        val now = Instant.parse("2026-08-19T01:00:00Z")
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val job = job(workspaceId, connectorId, now)
        var currentJob = job
        val repository = mockk<CatalogSelectionAndIngestRepository>(relaxed = true)
        every { repository.lockIngestJob(job.id) } answers { currentJob }
        every { repository.updateIngestJob(any()) } answers {
            currentJob = invocation.args[0] as CatalogIngestJob
            true
        }
        every { repository.findIngestJobSummary(job.id) } answers {
            CatalogIngestJobSummary(currentJob, 0, 0, 0)
        }
        every { repository.findRunningIngestTasks(job.id) } returns emptyList()
        val ownerCancellation = mockk<OwnerExecutionRunCancellationPort>(relaxed = true)
        val authorization = mockk<AuthorizedExecutionContext>(relaxed = true)
        every { authorization.agentClientId } returns null
        every { authorization.workspaceId } returns workspaceId
        val runnerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = service(
            repository = repository,
            executionAccess = mockk<ConnectorExecutionAccessUseCase>(),
            bindingProjection = mockk<ConnectorRuntimeBindingProjectionPort>(),
            cascadeRunner = mockk<CatalogIngestCascadeRunner>(),
            runnerScope = runnerScope,
            now = now,
            ownerExecutionRunCancellation = ownerCancellation,
        )

        try {
            val result = service.cancelIngest(
                CatalogIngestCancelCommand(
                    authorization = authorization,
                    connectorId = connectorId,
                    jobId = job.id,
                    reasonCode = "CANCELLED_BY_ACTOR",
                ),
            )

            val view = assertIs<CatalogCommandResult.Accepted<*>>(result).value as CatalogIngestJobView
            assertEquals(CatalogIngestJobState.CANCELLED, currentJob.state)
            assertEquals(CatalogIngestJobState.CANCELLED, view.job.state)
            assertEquals(job.entry, currentJob.entry)
            assertEquals(job.selection, currentJob.selection)
            assertEquals(job.requestedAt, currentJob.requestedAt)
            assertEquals(0, view.tasks.size)
            assertEquals(0, view.refreshedRelationCount)
            assertEquals(0, view.runningTaskCount)
            assertEquals(0, view.errorCount)
            verify(exactly = 1) {
                repository.updateIngestJob(match { it.id == job.id && it.state == CatalogIngestJobState.CANCELLED })
            }
            verify(exactly = 0) { repository.findOrInsertOwnedIngestTask(any(), any()) }
            verify(exactly = 0) { ownerCancellation.requestCancellationInOwnerTransaction(any()) }
        } finally {
            runnerScope.cancel()
        }
    }

    @Test
    fun `run cancellation conflict rolls back the job cancel and reports a conflict`() = runBlocking {
        val requestedAt = Instant.parse("2026-08-19T03:00:00Z")
        val lockedAt = requestedAt.plusSeconds(2)
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val job = job(workspaceId, connectorId, requestedAt).claim("runner-a", requestedAt)
        val runId = ExecutionRunId.generate()
        val task = CatalogIngestTask.start(job, null, CatalogIngestTaskTarget.Connector, requestedAt)
            .copy(activeRunId = runId)
        val clock = MutableClock(requestedAt)
        val repository = mockk<CatalogSelectionAndIngestRepository>(relaxed = true)
        every { repository.lockIngestJob(job.id) } answers {
            clock.now = lockedAt
            job
        }
        every { repository.updateIngestJob(any()) } returns true
        every { repository.findIngestTasks(job.id) } returns listOf(task)
        val cancellationCommand = AtomicReference<OwnerExecutionRunCancellationPort.RequestCancellationCommand>()
        val ownerCancellation = mockk<OwnerExecutionRunCancellationPort>()
        every { ownerCancellation.requestCancellationInOwnerTransaction(any()) } answers {
            cancellationCommand.set(firstArg())
            OwnerExecutionRunCancellationPort.CancellationOutcome.Conflict
        }
        val transactionPort = RollbackRecordingCatalogTransactionPort()
        val authorization = mockk<AuthorizedExecutionContext>(relaxed = true)
        every { authorization.workspaceId } returns workspaceId
        val runnerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = CatalogIngestApplicationService(
            selectionRepository = repository,
            repository = repository,
            transactionPort = transactionPort,
            connectorExecutionAccessUseCase = mockk(),
            connectorAccessInOwnerTransaction = mockk(),
            connectorRuntimeBindingProjection = mockk(),
            cascadeRunner = mockk(),
            ownerExecutionRunCancellation = ownerCancellation,
            clock = clock,
            runnerScope = runnerScope,
            runnerInstanceId = "runner-a",
        )

        try {
            val result = service.cancelIngest(
                CatalogIngestCancelCommand(authorization, connectorId, job.id, "CANCELLED_BY_ACTOR"),
            )

            assertEquals(CatalogCommandResult.Conflict, result)
            assertEquals(1, transactionPort.rolledBack)
            assertEquals(lockedAt, cancellationCommand.get().requestedAt)
            assertEquals(runId, cancellationCommand.get().runId)
            verify(exactly = 0) { repository.findIngestJobSummary(job.id) }
        } finally {
            runnerScope.cancel()
        }
    }

    @Test
    fun `a job read takes its counts and running tasks from one read-only snapshot`() = runBlocking {
        val now = Instant.parse("2026-08-19T04:00:00Z")
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val job = job(workspaceId, connectorId, now).claim("runner-a", now)
        val runningTask = CatalogIngestTask.start(job, null, CatalogIngestTaskTarget.Connector, now)
        val transactionPort = ReadSnapshotRecordingTransactionPort()
        val repository = mockk<CatalogSelectionAndIngestRepository>()
        every { repository.findIngestJobSummary(job.id) } answers {
            check(transactionPort.readOnlyActive) { "summary read outside the read snapshot" }
            CatalogIngestJobSummary(job, 0, 1, 0)
        }
        every { repository.findRunningIngestTasks(job.id) } answers {
            check(transactionPort.readOnlyActive) { "task read outside the read snapshot" }
            listOf(runningTask)
        }
        val runnerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = CatalogIngestApplicationService(
            selectionRepository = repository,
            repository = repository,
            transactionPort = transactionPort,
            connectorExecutionAccessUseCase = mockk(),
            connectorAccessInOwnerTransaction = mockk(),
            connectorRuntimeBindingProjection = mockk(),
            cascadeRunner = mockk(),
            ownerExecutionRunCancellation = mockk(),
            clock = Clock.fixed(now, ZoneOffset.UTC),
            runnerScope = runnerScope,
            runnerInstanceId = "runner-a",
        )

        try {
            val view = assertNotNull(service.findJob(workspaceId, job.id))

            assertEquals(listOf(runningTask), view.tasks)
            assertEquals(1, view.runningTaskCount)
            assertEquals(1, transactionPort.readOnlyTransactions)
            assertEquals(0, transactionPort.writeTransactions)
        } finally {
            runnerScope.cancel()
        }
    }

    private fun service(
        repository: CatalogSelectionAndIngestRepository,
        executionAccess: ConnectorExecutionAccessUseCase,
        bindingProjection: ConnectorRuntimeBindingProjectionPort,
        cascadeRunner: CatalogIngestCascadeRunner,
        runnerScope: CoroutineScope,
        now: Instant,
        ownerExecutionRunCancellation: OwnerExecutionRunCancellationPort =
            mockk<OwnerExecutionRunCancellationPort>(relaxed = true),
    ) = CatalogIngestApplicationService(
        selectionRepository = repository,
        repository = repository,
        transactionPort = DirectCatalogTransactionPort(),
        connectorExecutionAccessUseCase = executionAccess,
        connectorAccessInOwnerTransaction = object : cc.monomer.connector.api.ConnectorExecutionAccessInOwnerTransactionPort {
                override fun readCurrentForExecutionInOwnerTransaction(
                    workspaceId: cc.monomer.identity.api.WorkspaceId,
                    connectorId: cc.monomer.connector.api.ConnectorId,
                ) = cc.monomer.connector.api.ConnectorExecutionAccessResult.Granted(mockk(relaxed = true))
            },
        connectorRuntimeBindingProjection = bindingProjection,
        cascadeRunner = cascadeRunner,
        ownerExecutionRunCancellation = ownerExecutionRunCancellation,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        runnerScope = runnerScope,
        runnerInstanceId = "runner-a",
    )

    private fun job(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        requestedAt: Instant,
    ): CatalogIngestJob = CatalogIngestJob.request(
        id = CatalogIngestJobId.generate(),
        workspaceId = workspaceId,
        connectorId = connectorId,
        selection = CatalogSelection(emptySet()),
        entry = CatalogIngestEntry.Connector(connectorId),
        requestedBy = PrincipalId.generate(),
        requestedAt = requestedAt,
        credentialSurface = null,
        agentClientId = null,
    )

    private class DirectCatalogTransactionPort : CatalogTransactionPort {
        override suspend fun <T> inTransaction(block: () -> T): T = block()
        override suspend fun <T> inReadOnlyTransaction(block: () -> T): T = block()
    }

    private class ReadSnapshotRecordingTransactionPort : CatalogTransactionPort {
        var readOnlyActive = false
        var readOnlyTransactions = 0
        var writeTransactions = 0

        override suspend fun <T> inTransaction(block: () -> T): T {
            writeTransactions += 1
            return block()
        }

        override suspend fun <T> inReadOnlyTransaction(block: () -> T): T {
            readOnlyTransactions += 1
            readOnlyActive = true
            return try {
                block()
            } finally {
                readOnlyActive = false
            }
        }
    }

    private class RollbackRecordingCatalogTransactionPort : CatalogTransactionPort {
        var rolledBack = 0

        override suspend fun <T> inTransaction(block: () -> T): T =
            try {
                block()
            } catch (failure: RuntimeException) {
                rolledBack += 1
                throw failure
            }

        override suspend fun <T> inReadOnlyTransaction(block: () -> T): T = block()
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneOffset = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private interface CatalogSelectionAndIngestRepository : CatalogSelectionRepository, CatalogIngestRepository
}
