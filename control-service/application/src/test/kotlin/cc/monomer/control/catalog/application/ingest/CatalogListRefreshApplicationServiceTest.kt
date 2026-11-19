@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.catalog.application.ingest

import cc.monomer.catalog.adapter.outbound.execution.CatalogInlineQueryAdapter
import cc.monomer.catalog.api.CatalogListRefreshJobId
import cc.monomer.catalog.application.CatalogListRefreshRepository
import cc.monomer.catalog.application.CatalogTransactionPort
import cc.monomer.catalog.application.ingest.CancelCatalogListRefreshCommand
import cc.monomer.catalog.application.ingest.CatalogListRefreshApplicationService
import cc.monomer.catalog.application.ingest.CatalogListRefreshParent
import cc.monomer.catalog.application.ingest.StartCatalogListRefreshCommand
import cc.monomer.catalog.domain.CatalogListChildKind
import cc.monomer.catalog.domain.CatalogListOption
import cc.monomer.catalog.domain.CatalogListOptionRead
import cc.monomer.catalog.domain.CatalogListRefreshJob
import cc.monomer.catalog.domain.CatalogListRefreshTarget
import cc.monomer.catalog.domain.ingestion.CatalogQueryRow
import cc.monomer.connector.api.ConnectorExecutionAccessResult
import cc.monomer.connector.api.ConnectorExecutionAccessUseCase
import cc.monomer.connector.api.ConnectorExecutionBinding
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorRuntimeBindingProjectionPort
import cc.monomer.control.ExecutionRunDialCoordinator
import cc.monomer.control.jdbc.sqlStateChain
import cc.monomer.control.logExecutionRunAccepted
import cc.monomer.control.observability.RequestLogTestSupport
import cc.monomer.control.registerExecutionRunAcceptedAfterCommit
import cc.monomer.execution.adapter.inbound.grpc.ProtoInlineQueryRunSpecFactory
import cc.monomer.execution.api.BinaryValue
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorRuntimeBindingSpec
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.ExecutionRunAdmissionPort
import cc.monomer.execution.api.ExecutionRunEvent
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.InlineQueryRunSpec
import cc.monomer.execution.api.OwnerExecutionRunCancellationPort
import cc.monomer.execution.api.PhysicalOutcome
import cc.monomer.execution.api.PhysicalOutcomeKind
import cc.monomer.execution.api.ResultSinkBinding
import cc.monomer.execution.api.ResultSinkKind
import cc.monomer.execution.api.RunRuntimeBindings
import cc.monomer.execution.api.Sha256Digest
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class CatalogListRefreshApplicationServiceTest {
    @Test
    fun `postgres schema refresh executes one inline query and replaces relation options`() = verifyRefresh(ConnectorType.POSTGRESQL, "warehouse", false, false)

    @Test
    fun `a failing list refresh heartbeat does not cancel the refresh and logs a message free stack`() {
        val logger = LoggerFactory.getLogger(CatalogListRefreshApplicationService::class.java) as Logger
        val logs = object : ListAppender<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                event.prepareForDeferredProcessing()
                super.append(event)
            }
        }.apply { start() }
        logger.addAppender(logs)
        try {
            verifyRefresh(ConnectorType.POSTGRESQL, "warehouse", false, true)
            val failures = logs.list.filter { it.formattedMessage == "catalog_list_refresh_heartbeat_failed" }
            assertTrue(failures.isNotEmpty())
            failures.forEach { event ->
                val encoded = RequestLogTestSupport.encoded(event)
                assertTrue(encoded.contains("stack_trace"))
                assertTrue(encoded.contains("CatalogListRefreshApplicationServiceTest.kt:"))
                assertFalse(encoded.contains("catalog-heartbeat-sentinel"))
                assertFalse(encoded.contains("private@example.test"))
                assertFalse(encoded.contains("SELECT"))
            }
        } finally {
            logger.detachAppender(logs)
            logs.stop()
        }
    }

    @Test
    fun `redshift null schema target refreshes from the bound dev database`() = verifyRefresh(ConnectorType.REDSHIFT, "dev", false, false)

    @Test
    fun `foreign database option refresh fails without replacing the cache`() = verifyRefresh(ConnectorType.REDSHIFT, "dev", true, false)

    @Test
    fun `list refresh admission rejects a connector deleted before the catalog connector lock`() = runBlocking {
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val repository = mockk<CatalogListRefreshRepository>(relaxed = true)
        val access = mockk<ConnectorExecutionAccessUseCase>()
        val bindingView = mockk<ConnectorExecutionBinding>(relaxed = true)
        every { bindingView.connectorType } returns "postgresql"
        coEvery { access.readCurrentForExecution(workspace, connector) } returns ConnectorExecutionAccessResult.Granted(bindingView)
        val locked = mutableListOf<String>()
        every { repository.lockConnector(workspace, connector) } answers { locked += "connector" }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = CatalogListRefreshApplicationService(
            repository = repository,
            transactionPort = object : CatalogTransactionPort {
                override suspend fun <T> inTransaction(block: () -> T): T = block()
                override suspend fun <T> inReadOnlyTransaction(block: () -> T): T = block()
            },
            connectorExecutionAccessUseCase = access,
            connectorAccessInOwnerTransaction = object : cc.monomer.connector.api.ConnectorExecutionAccessInOwnerTransactionPort {
                override fun readCurrentForExecutionInOwnerTransaction(
                    workspaceId: WorkspaceId,
                    connectorId: ConnectorId,
                ): ConnectorExecutionAccessResult {
                    locked += "read"
                    return ConnectorExecutionAccessResult.NotFoundOrOutsideWorkspace
                }
            },
            connectorRuntimeBindingProjection = mockk(),
            createInlineQueryPort = { capturedBinding, capturedWorkspaceId, capturedOwner, ownerTransaction ->
                CatalogInlineQueryAdapter(
                    runSpecFactory = mockk(),
                    admittedRunAdmission = mockk(),
                    dispatchReservationDuration = Duration.ofSeconds(5),
                    executionDeadlineDuration = Duration.ofMinutes(5),
                    maximumRecordBatchRows = 128,
                    maximumRecordBatchBytes = 8 * 1024,
                    maximumCellBytes = 4 * 1024,
                    connectorBinding = capturedBinding,
                    transactionPort = ownerTransaction,
                    workspaceId = capturedWorkspaceId,
                    owner = capturedOwner,
                    clock = Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC),
                    runInline = mockk<cc.monomer.control.ExecutionRunDialCoordinator>()::runInline,
                    registerAcceptanceAfterCommit = ::registerExecutionRunAcceptedAfterCommit,
                    logAcceptance = ::logExecutionRunAccepted,
                )
            },
            ownerExecutionRunCancellation = mockk(),
            clock = Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC),
            runnerScope = scope,
            runnerInstanceId = "runner",
            heartbeatInterval = Duration.ofSeconds(30),
            decodeBatch = { emptyList() },
            failureSqlStates = { it.sqlStateChain() },
        )

        val result = service.startListRefresh(StartCatalogListRefreshCommand(
            mockk<cc.monomer.identity.api.AuthorizedExecutionContext>(relaxed = true).also {
                every { it.workspaceId } returns workspace
                every { it.agentClientId } returns null
            },
            connector,
            CatalogListRefreshParent.Connector,
        ))

        assertEquals(cc.monomer.catalog.api.CatalogCommandResult.NotFoundOrOutsideWorkspace, result)
        assertEquals(listOf("connector", "read"), locked)
        verify(exactly = 0) { repository.insertListRefreshJob(any()) }
        scope.cancel()
    }

    @Test
    fun `retained refresh reads use the exact persisted scope without a current connector`() = runBlocking {
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val repository = mockk<CatalogListRefreshRepository>()
        val access = mockk<ConnectorExecutionAccessUseCase>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = CatalogListRefreshApplicationService(
            repository = repository,
            transactionPort = ReadOnlyTransactionPort(),
            connectorExecutionAccessUseCase = access,
            connectorAccessInOwnerTransaction = mockk(),
            connectorRuntimeBindingProjection = mockk(),
            createInlineQueryPort = { capturedBinding, capturedWorkspaceId, capturedOwner, ownerTransaction ->
                CatalogInlineQueryAdapter(
                    runSpecFactory = mockk(),
                    admittedRunAdmission = mockk(),
                    dispatchReservationDuration = Duration.ofSeconds(5),
                    executionDeadlineDuration = Duration.ofMinutes(5),
                    maximumRecordBatchRows = 128,
                    maximumRecordBatchBytes = 8192,
                    maximumCellBytes = 4096,
                    connectorBinding = capturedBinding,
                    transactionPort = ownerTransaction,
                    workspaceId = capturedWorkspaceId,
                    owner = capturedOwner,
                    clock = Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC),
                    runInline = mockk<cc.monomer.control.ExecutionRunDialCoordinator>()::runInline,
                    registerAcceptanceAfterCommit = ::registerExecutionRunAcceptedAfterCommit,
                    logAcceptance = ::logExecutionRunAccepted,
                )
            },
            ownerExecutionRunCancellation = mockk(),
            clock = Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC),
            runnerScope = scope,
            runnerInstanceId = "runner",
            heartbeatInterval = Duration.ofSeconds(30),
            decodeBatch = { emptyList() },
            failureSqlStates = { it.sqlStateChain() },
        )
        val targets = listOf(
            CatalogListRefreshTarget.Connector(CatalogListChildKind.SCHEMA) to CatalogListRefreshParent.Connector,
            CatalogListRefreshTarget.Connector(CatalogListChildKind.CATALOG) to CatalogListRefreshParent.Connector,
            CatalogListRefreshTarget.Catalog("analytics") to CatalogListRefreshParent.Catalog("analytics"),
            CatalogListRefreshTarget.Schema(null, "public") to CatalogListRefreshParent.Schema(null, "public"),
            CatalogListRefreshTarget.Schema("analytics", "public") to CatalogListRefreshParent.Schema("analytics", "public"),
        )
        try {
            for ((target, parent) in targets) {
                val job = CatalogListRefreshJob.request(
                    id = CatalogListRefreshJobId.generate(),
                    workspaceId = workspace,
                    connectorId = connector,
                    target = target,
                    requestedBy = PrincipalId.generate(),
                    requestedAt = Instant.parse("2026-08-20T00:00:00Z"),
                    credentialSurface = null,
                    agentClientId = null,
                ).claim("runner", Instant.parse("2026-08-20T00:00:00Z"))
                    .completeWithErrors("WAREHOUSE_UNAVAILABLE", "Warehouse is unavailable", Instant.parse("2026-08-20T00:00:01Z"))
                every { repository.findListRefreshJob(job.id) } returns job
                assertEquals(job, service.findListRefreshJob(workspace, connector, parent, job.id)?.job)
                assertNull(service.findListRefreshJob(WorkspaceId.generate(), connector, parent, job.id))
                assertNull(service.findListRefreshJob(workspace, ConnectorId.generate(), parent, job.id))
                val wrongParents = listOf(
                    CatalogListRefreshParent.Connector,
                    CatalogListRefreshParent.Catalog("other"),
                    CatalogListRefreshParent.Schema(null, "other"),
                    CatalogListRefreshParent.Schema("other", "public"),
                ).filter { it != parent }
                for (wrongParent in wrongParents) {
                    assertNull(service.findListRefreshJob(workspace, connector, wrongParent, job.id))
                }
            }
            val missingId = CatalogListRefreshJobId.generate()
            every { repository.findListRefreshJob(missingId) } returns null
            assertNull(service.findListRefreshJob(workspace, connector, CatalogListRefreshParent.Connector, missingId))
            verify { access wasNot Called }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an option read takes the list fetch time and the option from one owner lookup`() = runBlocking {
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val fetchedAt = Instant.parse("2026-08-20T00:00:00Z")
        val target = CatalogListRefreshTarget.Schema(null, "public")
        val transactionPort = ReadOnlyTransactionPort()
        val repository = mockk<CatalogListRefreshRepository>()
        every { repository.findListOption(workspace, connector, target, "orders") } answers {
            check(transactionPort.readOnlyActive) { "option read outside the read snapshot" }
            CatalogListOptionRead(fetchedAt, CatalogListOption("orders", cc.monomer.catalog.api.RelationKind.TABLE, true))
        }
        val access = mockk<ConnectorExecutionAccessUseCase>()
        val binding = mockk<ConnectorExecutionBinding>(relaxed = true)
        every { binding.connectorType } returns "postgresql"
        coEvery { access.readCurrentForExecution(workspace, connector) } returns ConnectorExecutionAccessResult.Granted(binding)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = CatalogListRefreshApplicationService(
            repository = repository,
            transactionPort = transactionPort,
            connectorExecutionAccessUseCase = access,
            connectorAccessInOwnerTransaction = mockk(),
            connectorRuntimeBindingProjection = mockk(),
            createInlineQueryPort = { capturedBinding, capturedWorkspaceId, capturedOwner, ownerTransaction ->
                CatalogInlineQueryAdapter(
                    runSpecFactory = mockk(),
                    admittedRunAdmission = mockk(),
                    dispatchReservationDuration = Duration.ofSeconds(5),
                    executionDeadlineDuration = Duration.ofMinutes(5),
                    maximumRecordBatchRows = 128,
                    maximumRecordBatchBytes = 8192,
                    maximumCellBytes = 4096,
                    connectorBinding = capturedBinding,
                    transactionPort = ownerTransaction,
                    workspaceId = capturedWorkspaceId,
                    owner = capturedOwner,
                    clock = Clock.fixed(fetchedAt, ZoneOffset.UTC),
                    runInline = mockk<cc.monomer.control.ExecutionRunDialCoordinator>()::runInline,
                    registerAcceptanceAfterCommit = ::registerExecutionRunAcceptedAfterCommit,
                    logAcceptance = ::logExecutionRunAccepted,
                )
            },
            ownerExecutionRunCancellation = mockk(),
            clock = Clock.fixed(fetchedAt, ZoneOffset.UTC),
            runnerScope = scope,
            runnerInstanceId = "runner",
            heartbeatInterval = Duration.ofSeconds(30),
            decodeBatch = { emptyList() },
            failureSqlStates = { it.sqlStateChain() },
        )

        try {
            val read = service.findListOption(workspace, connector, CatalogListRefreshParent.Schema(null, "public"), "orders")

            assertEquals(fetchedAt, read?.fetchedAt)
            assertEquals("orders", read?.option?.name)
            assertEquals(1, transactionPort.readOnlyTransactions)
            verify(exactly = 1) { repository.findListOption(workspace, connector, target, "orders") }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `run cancellation conflict rolls back the list refresh cancel and reports a conflict`() = runBlocking {
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val requestedAt = Instant.parse("2026-08-20T00:00:00Z")
        val lockedAt = requestedAt.plusSeconds(2)
        val runId = ExecutionRunId.generate()
        val job = CatalogListRefreshJob.request(
            id = CatalogListRefreshJobId.generate(),
            workspaceId = workspace,
            connectorId = connector,
            target = CatalogListRefreshTarget.Schema("warehouse", "analytics"),
            requestedBy = PrincipalId.generate(),
            requestedAt = requestedAt,
            credentialSurface = null,
            agentClientId = null,
        ).claim("runner", requestedAt).copy(activeRunId = runId)
        var now = requestedAt
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): Instant = now
        }
        val repository = mockk<CatalogListRefreshRepository>()
        every { repository.lockListRefreshJob(job.id) } answers {
            now = lockedAt
            job
        }
        every { repository.updateListRefreshJob(any()) } returns true
        val cancellationCommand = AtomicReference<OwnerExecutionRunCancellationPort.RequestCancellationCommand>()
        val ownerCancellation = mockk<OwnerExecutionRunCancellationPort>()
        every { ownerCancellation.requestCancellationInOwnerTransaction(any()) } answers {
            cancellationCommand.set(firstArg())
            OwnerExecutionRunCancellationPort.CancellationOutcome.Conflict
        }
        var rolledBack = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = CatalogListRefreshApplicationService(
            repository = repository,
            transactionPort = object : CatalogTransactionPort {
                override suspend fun <T> inTransaction(block: () -> T): T =
                    try {
                        block()
                    } catch (failure: RuntimeException) {
                        rolledBack += 1
                        throw failure
                    }

                override suspend fun <T> inReadOnlyTransaction(block: () -> T): T = block()
            },
            connectorExecutionAccessUseCase = mockk(),
            connectorAccessInOwnerTransaction = mockk(),
            connectorRuntimeBindingProjection = mockk(),
            createInlineQueryPort = { capturedBinding, capturedWorkspaceId, capturedOwner, ownerTransaction ->
                CatalogInlineQueryAdapter(
                    runSpecFactory = mockk(),
                    admittedRunAdmission = mockk(),
                    dispatchReservationDuration = Duration.ofSeconds(5),
                    executionDeadlineDuration = Duration.ofMinutes(5),
                    maximumRecordBatchRows = 128,
                    maximumRecordBatchBytes = 8192,
                    maximumCellBytes = 4096,
                    connectorBinding = capturedBinding,
                    transactionPort = ownerTransaction,
                    workspaceId = capturedWorkspaceId,
                    owner = capturedOwner,
                    clock = clock,
                    runInline = mockk<cc.monomer.control.ExecutionRunDialCoordinator>()::runInline,
                    registerAcceptanceAfterCommit = ::registerExecutionRunAcceptedAfterCommit,
                    logAcceptance = ::logExecutionRunAccepted,
                )
            },
            ownerExecutionRunCancellation = ownerCancellation,
            clock = clock,
            runnerScope = scope,
            runnerInstanceId = "runner",
            heartbeatInterval = Duration.ofSeconds(30),
            decodeBatch = { emptyList() },
            failureSqlStates = { it.sqlStateChain() },
        )

        try {
            val result = service.cancelListRefresh(
                CancelCatalogListRefreshCommand(
                    mockk<cc.monomer.identity.api.AuthorizedExecutionContext>(relaxed = true).also {
                        every { it.workspaceId } returns workspace
                    },
                    connector,
                    job.id,
                    "CANCELLED_BY_ACTOR",
                ),
            )

            assertEquals(cc.monomer.catalog.api.CatalogCommandResult.Conflict, result)
            assertEquals(1, rolledBack)
            assertEquals(lockedAt, cancellationCommand.get().requestedAt)
            assertEquals(runId, cancellationCommand.get().runId)
        } finally {
            scope.cancel()
        }
    }

    private fun verifyRefresh(
        connectorType: ConnectorType,
        database: String,
        foreignObservation: Boolean,
        heartbeatFailure: Boolean,
    ) = runTest {
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val requested = CatalogListRefreshJob.request(
            id = CatalogListRefreshJobId.generate(),
            workspaceId = workspace,
            connectorId = connector,
            target = CatalogListRefreshTarget.Schema(null, "public"),
            requestedBy = PrincipalId.generate(),
            requestedAt = Instant.parse("2026-08-20T00:00:00Z"),
            credentialSurface = null,
            agentClientId = null,
        )
        val claimed = requested.claim("runner", requested.requestedAt)
        val repository = mockk<CatalogListRefreshRepository>(relaxed = true)
        every {
            repository.claimListRefreshJob(requested.id, "runner", any())
        } returns claimed
        every { repository.findListRefreshJob(requested.id) } returns claimed
        every { repository.lockListRefreshJob(requested.id) } returns claimed
        val runId = ExecutionRunId.generate()
        every { repository.setActiveListRefreshRun(requested.id, runId, "runner") } returns true
        val options = AtomicReference<List<CatalogListOption>?>(null)
        every {
            repository.completeListRefreshWithOptions(any(), any(), any(), "runner")
        } answers {
            options.set(secondArg())
            true
        }

        val access = mockk<ConnectorExecutionAccessUseCase>()
        val bindingView = mockk<ConnectorExecutionBinding>(relaxed = true)
        every { bindingView.connectorType } returns connectorType.name.lowercase()
        coEvery { access.readCurrentForExecution(workspace, connector) } returns
            ConnectorExecutionAccessResult.Granted(bindingView)
        val runtimeBinding = mockk<ConnectorRuntimeBindingSpec>(relaxed = true)
        every { runtimeBinding.connectorId } returns ConnectorReference(connector.value)
        every { runtimeBinding.connectorType } returns connectorType
        every { runtimeBinding.properties } returns mapOf(
            "host" to ConnectorRuntimePropertyValue.Text("localhost"),
            "database" to ConnectorRuntimePropertyValue.Text(database),
        )
        val projection = mockk<ConnectorRuntimeBindingProjectionPort>()
        every { projection.project(bindingView) } returns runtimeBinding

        val runSnapshot = mockk<ExecutionRunSnapshot>()
        every { runSnapshot.id } returns runId
        val runSpec = mockk<InlineQueryRunSpec>(relaxed = true)
        every { runSpec.canonicalRequestDigest } returns Sha256Digest("1".repeat(64))
        every { runSpec.runtimeBindings } returns RunRuntimeBindings(
            connectorBindingDigest = Sha256Digest("2".repeat(64)),
            credentialVersion = null,
            credentialKeyVersion = "test-key-v1",
            credentialBindingDigest = Sha256Digest("3".repeat(64)),
            resultSink = ResultSinkBinding(
                schemaVersion = 2u,
                kind = ResultSinkKind.INLINE_STREAM,
                objectKey = null,
                maximumArtifactRows = null,
                maximumArtifactBytes = null,
                expiresAt = null,
                maximumRecordBatchRows = 128,
                maximumFrameBytes = 8 * 1024,
                maximumCellBytes = 4 * 1024,
                maximumMultipartPartBytes = null,
                bindingDigest = Sha256Digest("0".repeat(64)),
            ),
        )
        every { runSpec.assignmentTemplate } returns BinaryValue.copyOf(byteArrayOf(1))
        val specFactory = mockk<ProtoInlineQueryRunSpecFactory>()
        every { specFactory.create(any()) } returns runSpec
        val admission = object : ExecutionRunAdmissionPort {
            override fun lockWorkspaceInOwnerTransaction(workspaceId: cc.monomer.identity.api.WorkspaceId) = Unit
            override fun hasUnreleasedOccupancyInOwnerTransaction(workspaceId: cc.monomer.identity.api.WorkspaceId, runIds: Set<cc.monomer.execution.api.ExecutionRunId>): Boolean = false
            override fun reserveInOwnerTransaction(
                command: ExecutionRunAdmissionPort.ReserveCommand,
            ): ExecutionRunAdmissionPort.ReserveOutcome {
                every { runSnapshot.workspaceId } returns command.workspaceId
                every { runSnapshot.owner } returns command.owner
                every { runSnapshot.runtimeBindings } returns command.runtimeBindings
                return ExecutionRunAdmissionPort.ReserveOutcome.Reserved(runSnapshot)
            }
        }
        val dialCoordinator = mockk<ExecutionRunDialCoordinator>()
        if (heartbeatFailure) {
            every { repository.heartbeatListRefreshJob(requested.id, "runner", any()) } throws
                IllegalStateException("password=catalog-heartbeat-sentinel SELECT private@example.test")
        }
        coEvery { dialCoordinator.runInline(runId, any()) } coAnswers {
            if (heartbeatFailure) delay(Duration.ofMinutes(2).toMillis())
            secondArg<suspend (ExecutionRunEvent.InlineResultBatch) -> Unit>()(
                ExecutionRunEvent.InlineResultBatch.fromPayload(
                    1uL,
                    BinaryValue.copyOf(byteArrayOf(1)),
                ),
            )
            PhysicalOutcome(
                terminalIdempotencyKey = "terminal",
                kind = PhysicalOutcomeKind.SUCCEEDED,
                failureCode = null,
                safeMessage = null,
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = CatalogListRefreshApplicationService(
            repository = repository,
            transactionPort = object : CatalogTransactionPort {
                override suspend fun <T> inTransaction(block: () -> T): T = block()
                override suspend fun <T> inReadOnlyTransaction(block: () -> T): T = block()
            },
            connectorExecutionAccessUseCase = access,
            connectorAccessInOwnerTransaction = object : cc.monomer.connector.api.ConnectorExecutionAccessInOwnerTransactionPort {
                override fun readCurrentForExecutionInOwnerTransaction(
                    workspaceId: cc.monomer.identity.api.WorkspaceId,
                    connectorId: cc.monomer.connector.api.ConnectorId,
                ) = cc.monomer.connector.api.ConnectorExecutionAccessResult.Granted(mockk(relaxed = true))
            },
            connectorRuntimeBindingProjection = projection,
            createInlineQueryPort = { capturedBinding, capturedWorkspaceId, capturedOwner, ownerTransaction ->
                CatalogInlineQueryAdapter(
                    runSpecFactory = specFactory,
                    admittedRunAdmission = admission,
                    dispatchReservationDuration = Duration.ofSeconds(5),
                    executionDeadlineDuration = Duration.ofMinutes(5),
                    maximumRecordBatchRows = 128,
                    maximumRecordBatchBytes = 8 * 1024,
                    maximumCellBytes = 4 * 1024,
                    connectorBinding = capturedBinding,
                    transactionPort = ownerTransaction,
                    workspaceId = capturedWorkspaceId,
                    owner = capturedOwner,
                    clock = Clock.fixed(requested.requestedAt, ZoneOffset.UTC),
                    runInline = dialCoordinator::runInline,
                    registerAcceptanceAfterCommit = ::registerExecutionRunAcceptedAfterCommit,
                    logAcceptance = ::logExecutionRunAccepted,
                )
            },
            ownerExecutionRunCancellation = mockk<OwnerExecutionRunCancellationPort>(relaxed = true),
            clock = Clock.fixed(requested.requestedAt, ZoneOffset.UTC),
            runnerScope = scope,
            runnerInstanceId = "runner",
            heartbeatInterval = Duration.ofSeconds(30),
            decodeBatch = {
                listOf(
                    CatalogQueryRow(
                        mapOf(
                            "catalog_name" to if (foreignObservation) "other_database" else database,
                            "schema_name" to "public",
                            "table_name" to "events",
                            "table_type" to "BASE TABLE",
                        ),
                    ),
                )
            },
            failureSqlStates = { it.sqlStateChain() },
        )

        try {
            service.runClaimedListRefresh(requested)
        } finally {
            scope.cancel()
        }

        if (foreignObservation) {
            assertEquals(null, options.get())
            verify(exactly = 1) { repository.failListRefreshJob(requested.id, "runner", "CATALOG_LIST_REFRESH_FAILED", any(), any()) }
            return@runTest
        }
        assertEquals(listOf(CatalogListOption("events", cc.monomer.catalog.api.RelationKind.TABLE, true)), options.get())
        verify(exactly = 1) { projection.project(bindingView) }
        verify(exactly = 1) { repository.setActiveListRefreshRun(requested.id, runId, "runner") }
        if (heartbeatFailure) {
            verify(atLeast = 2) { repository.heartbeatListRefreshJob(requested.id, "runner", any()) }
        }

        val cancellationRace = CatalogListRefreshJob.request(
            id = CatalogListRefreshJobId.generate(),
            workspaceId = workspace,
            connectorId = connector,
            target = CatalogListRefreshTarget.Schema(null, "public"),
            requestedBy = PrincipalId.generate(),
            requestedAt = requested.requestedAt,
            credentialSurface = null,
            agentClientId = null,
        )
        val cancellationRaceClaimed = cancellationRace.claim("runner", cancellationRace.requestedAt)
        every {
            repository.claimListRefreshJob(cancellationRace.id, "runner", any())
        } returns cancellationRaceClaimed
        every {
            repository.setActiveListRefreshRun(cancellationRace.id, runId, "runner")
        } returns true
        every {
            repository.lockListRefreshJob(cancellationRace.id)
        } returns cancellationRaceClaimed.requestCancellation()

        service.runClaimedListRefresh(cancellationRace)

        verify(exactly = 1) {
            repository.cancelListRefreshJob(cancellationRace.id, "runner", any())
        }
        verify(exactly = 0) {
            repository.completeListRefreshWithOptions(
                match { it.id == cancellationRace.id },
                any(),
                any(),
                "runner",
            )
        }
    }

    private class ReadOnlyTransactionPort : CatalogTransactionPort {
        var readOnlyActive = false
        var readOnlyTransactions = 0

        override suspend fun <T> inTransaction(block: () -> T): T = error("list refresh reads do not write")

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
}
