@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.catalog.application.ingest

import cc.monomer.catalog.adapter.outbound.execution.CatalogInlineQueryAdapter
import cc.monomer.catalog.application.CatalogInlineQueryCommand
import cc.monomer.catalog.application.CatalogInlineQueryResult
import cc.monomer.catalog.application.CatalogTransactionPort
import cc.monomer.connector.api.ConnectorId
import cc.monomer.control.ExecutionRunDialCoordinator
import cc.monomer.control.logExecutionRunAccepted
import cc.monomer.control.registerExecutionRunAcceptedAfterCommit
import cc.monomer.execution.adapter.inbound.grpc.ProtoInlineQueryRunSpecFactory
import cc.monomer.execution.api.BinaryValue
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorRuntimeBindingSpec
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.ExecutionOwnerId
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutionRunAdmissionPort
import cc.monomer.execution.api.ExecutionRunEvent
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.InlineQueryRunSpec
import cc.monomer.execution.api.PhysicalOutcome
import cc.monomer.execution.api.PhysicalOutcomeKind
import cc.monomer.execution.api.ResultSinkBinding
import cc.monomer.execution.api.ResultSinkKind
import cc.monomer.execution.api.RunRuntimeBindings
import cc.monomer.execution.api.Sha256Digest
import cc.monomer.identity.api.WorkspaceId
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronizationManager

class CatalogInlineQueryAdapterTest {
    @Test
    fun `inline execution delivers each frame to Catalog without background dispatch`() =
        verifyInlineExecution(ConnectorType.POSTGRESQL)

    @Test
    fun `Databricks Catalog preserves names for text and typed null while positional engines omit them`() {
        listOf(ConnectorType.DATABRICKS, ConnectorType.SNOWFLAKE, ConnectorType.BIGQUERY, ConnectorType.TRINO, ConnectorType.REDSHIFT)
            .forEach { verifyInlineExecution(it) }
    }

    @Test
    fun `existing inline run does not log a new acceptance`() = verifyInlineExecution(ConnectorType.POSTGRESQL, existing = true)

    @Test
    fun `rolled back inline reservation does not log acceptance or dial`() = verifyInlineExecution(ConnectorType.POSTGRESQL, rollback = true)

    @Test
    fun `inline run acceptance waits for the active owner transaction commit`() = verifyInlineExecution(ConnectorType.POSTGRESQL, deferredCommit = true)

    private fun verifyInlineExecution(connectorType: ConnectorType, existing: Boolean = false, rollback: Boolean = false, deferredCommit: Boolean = false) = runTest {
        val workspaceId = WorkspaceId(Uuid.generateV7())
        val connectorId = ConnectorId.generate()
        val executionRunId = ExecutionRunId.generate()
        val binding = mockk<ConnectorRuntimeBindingSpec>(relaxed = true)
        every { binding.connectorId } returns ConnectorReference(connectorId.value)
        every { binding.connectorType } returns connectorType
        every { binding.managedCredential } returns cc.monomer.execution.api.ManagedCredentialDispatchReference(
            cc.monomer.execution.api.CredentialVersionReference(Uuid.generateV7()), "test-key",
            Sha256Digest("3".repeat(64)),
        )
        every { binding.properties } returns mapOf(
            "host" to ConnectorRuntimePropertyValue.Text("localhost"),
        )

        val inlineSink = ResultSinkBinding(
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
        )
        val spec = mockk<InlineQueryRunSpec>()
        every { spec.canonicalRequestDigest } returns Sha256Digest("1".repeat(64))
        every { spec.runtimeBindings } returns RunRuntimeBindings(
            connectorBindingDigest = Sha256Digest("2".repeat(64)),
            credentialVersion = null,
            credentialKeyVersion = "test-key-v1",
            credentialBindingDigest = Sha256Digest("3".repeat(64)),
            resultSink = inlineSink,
        )
        every { spec.assignmentTemplate } returns BinaryValue.copyOf(byteArrayOf(1))
        val specFactory = mockk<ProtoInlineQueryRunSpecFactory>()
        val specCommand = io.mockk.slot<cc.monomer.execution.api.InlineQueryRunSpecCommand>()
        every { specFactory.create(capture(specCommand)) } returns spec

        val snapshot = mockk<ExecutionRunSnapshot>()
        every { snapshot.id } returns executionRunId
        every { snapshot.workspaceId } returns workspaceId
        every { snapshot.owner } returns ExecutionOwnerReference(ExecutionOwnerType.CATALOG, ExecutionOwnerId(Uuid.generateV7()))
        every { snapshot.runtimeBindings } returns spec.runtimeBindings
        val reserveCalls = AtomicInteger()
        val admission = object : ExecutionRunAdmissionPort {
            override fun lockWorkspaceInOwnerTransaction(workspaceId: cc.monomer.identity.api.WorkspaceId) = Unit
            override fun hasUnreleasedOccupancyInOwnerTransaction(workspaceId: cc.monomer.identity.api.WorkspaceId, runIds: Set<cc.monomer.execution.api.ExecutionRunId>): Boolean = false
            override fun reserveInOwnerTransaction(
                command: ExecutionRunAdmissionPort.ReserveCommand,
            ): ExecutionRunAdmissionPort.ReserveOutcome {
                reserveCalls.incrementAndGet()
                return if (existing) ExecutionRunAdmissionPort.ReserveOutcome.Existing(snapshot)
                    else ExecutionRunAdmissionPort.ReserveOutcome.Reserved(snapshot)
            }
        }
        val inOwnerTransaction = AtomicBoolean(false)
        val transaction = object : CatalogTransactionPort {
            override suspend fun <T> inTransaction(block: () -> T): T {
                if (deferredCommit) TransactionSynchronizationManager.initSynchronization()
                inOwnerTransaction.set(true)
                return try {
                    val result = block()
                    if (rollback) error("Injected owner rollback")
                    result
                } finally {
                    inOwnerTransaction.set(false)
                }
            }

            override suspend fun <T> inReadOnlyTransaction(block: () -> T): T = error("inline query admission writes")
        }
        val dialCoordinator = mockk<ExecutionRunDialCoordinator>(relaxed = true)
        val payload = BinaryValue.copyOf(byteArrayOf(7, 8, 9))
        coEvery { dialCoordinator.runInline(executionRunId, any()) } coAnswers {
            secondArg<suspend (ExecutionRunEvent.InlineResultBatch) -> Unit>()(
                ExecutionRunEvent.InlineResultBatch.fromPayload(1uL, payload),
            )
            PhysicalOutcome(
                terminalIdempotencyKey = "${executionRunId}:terminal",
                kind = PhysicalOutcomeKind.SUCCEEDED,
                failureCode = null,
                safeMessage = null,
            )
        }
        val adapter = CatalogInlineQueryAdapter(
            connectorBinding = binding,
            runSpecFactory = specFactory,
            admittedRunAdmission = admission,
            transactionPort = transaction,
            runInline = dialCoordinator::runInline,
            registerAcceptanceAfterCommit = ::registerExecutionRunAcceptedAfterCommit,
            logAcceptance = ::logExecutionRunAccepted,
            workspaceId = workspaceId,
            owner = ExecutionOwnerReference(
                ExecutionOwnerType.CATALOG,
                ExecutionOwnerId(Uuid.generateV7()),
            ),
            clock = Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC),
            dispatchReservationDuration = Duration.ofSeconds(5),
            executionDeadlineDuration = Duration.ofMinutes(5),
            maximumRecordBatchRows = 128,
            maximumRecordBatchBytes = 8 * 1024,
            maximumCellBytes = 4 * 1024,
        )

        val batches = mutableListOf<ByteArray>()
        val logger = LoggerFactory.getLogger(ExecutionRunDialCoordinator::class.java) as Logger
        val logs = object : ListAppender<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                event.prepareForDeferredProcessing()
                super.append(event)
            }
        }.apply { start() }
        logger.addAppender(logs)
        suspend fun execute() = withContext(MDCContext(mapOf("request_id" to "srv_catalog-request"))) { adapter.execute(
            command = CatalogInlineQueryCommand(
                workspaceId = workspaceId,
                connectorId = connectorId,
                connectorBinding = binding,
                executionReservationKey = "ingest-task:test",
                sql = if (connectorType == ConnectorType.DATABRICKS) "SELECT :catalog, :schema" else "SELECT ?, ?",
                parameters = listOf(cc.monomer.catalog.domain.CatalogQueryParameter("catalog", "warehouse"), cc.monomer.catalog.domain.CatalogQueryParameter("schema", null)),
            ),
            onRunAdmittedInOwnerTransaction = { assertTrue(inOwnerTransaction.get()) },
            onBatch = { batch -> batches += batch.arrowIpc },
        ) }
        val result = try {
            if (rollback) {
                assertFailsWith<IllegalStateException> { execute() }
                assertTrue(logs.list.isEmpty())
                coVerify(exactly = 0) { dialCoordinator.runInline(executionRunId, any()) }
                return@runTest
            }
            execute().also {
                if (deferredCommit) {
                    assertTrue(logs.list.isEmpty())
                    val synchronizations = TransactionSynchronizationManager.getSynchronizations()
                    TransactionSynchronizationManager.clearSynchronization()
                    synchronizations.forEach { it.afterCommit() }
                }
                if (existing) {
                    assertTrue(logs.list.isEmpty())
                } else {
                    val event = logs.list.single()
                    val fields = event.mdcPropertyMap + event.keyValuePairs.associate { it.key to it.value }
                    assertEquals("execution_run_accepted", event.message)
                    assertEquals("srv_catalog-request", fields["request_id"])
                    assertEquals(executionRunId.toString(), fields["run_id"])
                    assertEquals(workspaceId.toString(), fields["workspace_id"])
                    assertEquals("CATALOG", fields["owner_type"])
                    assertEquals("INLINE_STREAM", fields["result_sink"])
                    assertTrue(fields.values.none { value -> value == "warehouse" || value.toString().startsWith("SELECT ") })
                }
            }
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization()
            logger.detachAppender(logs)
        }

        assertIs<CatalogInlineQueryResult.Succeeded>(result)
        assertEquals(executionRunId, result.runId)
        assertEquals(listOf(payload.copyBytes().toList()), batches.map(ByteArray::toList))
        assertEquals(listOf(
            cc.monomer.execution.api.QueryParameterValue.Text("warehouse", if (connectorType == ConnectorType.DATABRICKS) "catalog" else ""),
            cc.monomer.execution.api.QueryParameterValue.Null(cc.monomer.execution.api.NullQueryParameterType.TEXT, if (connectorType == ConnectorType.DATABRICKS) "schema" else ""),
        ), specCommand.captured.parameters)
        val wire = cc.monomer.execution.protocol.v1.QueryRequest.parseFrom(
            ProtoInlineQueryRunSpecFactory().create(specCommand.captured).assignmentTemplate.copyBytes(),
        )
        assertEquals(if (connectorType == ConnectorType.DATABRICKS) listOf("catalog", "schema") else listOf("", ""), wire.parametersList.map { it.name })
        assertEquals("warehouse", wire.parametersList.first().stringValue)
        assertEquals(cc.monomer.execution.protocol.v1.QueryParameterType.QUERY_PARAMETER_TYPE_TEXT, wire.parametersList.last().nullValue.type)
        assertEquals(1, reserveCalls.get())
        coVerify(exactly = 1) { dialCoordinator.runInline(executionRunId, any()) }
        verify(exactly = 0) { dialCoordinator.dispatchAfterCommit(executionRunId) }
    }
}
