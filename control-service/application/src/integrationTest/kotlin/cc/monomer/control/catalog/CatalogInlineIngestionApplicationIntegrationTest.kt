@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.catalog

import cc.monomer.catalog.application.CatalogInlineQueryCommand
import cc.monomer.catalog.application.CatalogInlineQueryResult
import cc.monomer.catalog.application.CatalogTransactionPort
import cc.monomer.connector.api.ConnectorId
import cc.monomer.control.ExecutionRunDialCoordinator
import cc.monomer.catalog.adapter.outbound.execution.CatalogInlineQueryAdapter
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.execution.adapter.inbound.grpc.ProtoInlineQueryRunSpecFactory
import cc.monomer.execution.api.BinaryValue
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.ConnectorRuntimeBindingSpec
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.ExecutionRunEvent
import cc.monomer.execution.api.ExecutionOwnerId
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutionRunAdmissionPort
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.InlineQueryRunSpec
import cc.monomer.execution.api.PhysicalOutcome
import cc.monomer.execution.api.PhysicalOutcomeKind
import cc.monomer.execution.api.ResultSinkBinding
import cc.monomer.execution.api.ResultSinkKind
import cc.monomer.execution.api.RunRuntimeBindings
import cc.monomer.execution.api.Sha256Digest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * Application seam test: a fake Execution inline stream is consumed by the Catalog adapter and
 * handed directly to the Catalog-owned callback. No dispatch wrapper or artifact store is used.
 */
class CatalogInlineIngestionApplicationIntegrationTest {
    @Test
    fun `Control inline execution delivers each frame once to Catalog persistence`() = runTest {
        val workspaceId = WorkspaceId(Uuid.generateV7())
        val connectorId = ConnectorId.generate()
        val executionReservationKey = "integration-${Uuid.generateV7()}"
        val executionRunId = ExecutionRunId.generate()
        val binding = mockk<ConnectorRuntimeBindingSpec>(relaxed = true)
        every { binding.connectorId } returns ConnectorReference(connectorId.value)
        every { binding.connectorType } returns ConnectorType.POSTGRESQL
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
        val runtimeBindings = RunRuntimeBindings(
            connectorBindingDigest = Sha256Digest("2".repeat(64)),
            credentialVersion = null,
            credentialKeyVersion = "test-key-v1",
            credentialBindingDigest = Sha256Digest("3".repeat(64)),
            resultSink = inlineSink,
        )
        val spec = mockk<InlineQueryRunSpec>()
        every { spec.canonicalRequestDigest } returns Sha256Digest("1".repeat(64))
        every { spec.runtimeBindings } returns runtimeBindings
        every { spec.assignmentTemplate } returns BinaryValue.copyOf(byteArrayOf(1))
        val specFactory = mockk<ProtoInlineQueryRunSpecFactory>()
        every { specFactory.create(any()) } returns spec

        val owner = ExecutionOwnerReference(
            ExecutionOwnerType.CATALOG,
            ExecutionOwnerId(Uuid.generateV7()),
        )
        val snapshot = mockk<ExecutionRunSnapshot>()
        every { snapshot.id } returns executionRunId
        every { snapshot.workspaceId } returns workspaceId
        every { snapshot.owner } returns owner
        every { snapshot.runtimeBindings } returns runtimeBindings
        val reserveCalls = AtomicInteger()
        val admission = object : ExecutionRunAdmissionPort {
            override fun lockWorkspaceInOwnerTransaction(workspaceId: cc.monomer.identity.api.WorkspaceId) = Unit
            override fun hasUnreleasedOccupancyInOwnerTransaction(workspaceId: cc.monomer.identity.api.WorkspaceId, runIds: Set<cc.monomer.execution.api.ExecutionRunId>): Boolean = false
            override fun reserveInOwnerTransaction(
                command: ExecutionRunAdmissionPort.ReserveCommand,
            ): ExecutionRunAdmissionPort.ReserveOutcome {
                reserveCalls.incrementAndGet()
                return ExecutionRunAdmissionPort.ReserveOutcome.Reserved(snapshot)
            }
        }
        val transaction = object : CatalogTransactionPort {
            override suspend fun <T> inTransaction(block: () -> T): T = block()
            override suspend fun <T> inReadOnlyTransaction(block: () -> T): T = block()
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
            registerAcceptanceAfterCommit = { _, _ -> false },
            logAcceptance = { _, _ -> },
            workspaceId = workspaceId,
            owner = owner,
            clock = Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC),
            dispatchReservationDuration = Duration.ofSeconds(5),
            executionDeadlineDuration = Duration.ofMinutes(5),
            maximumRecordBatchRows = 128,
            maximumRecordBatchBytes = 8 * 1024,
            maximumCellBytes = 4 * 1024,
        )

        // This callback is the Catalog persistence boundary for the inline batch.
        val persistedFrames = mutableListOf<ByteArray>()
        val result = adapter.execute(
            command = CatalogInlineQueryCommand(
                workspaceId = workspaceId,
                connectorId = connectorId,
                connectorBinding = binding,
                executionReservationKey = executionReservationKey,
                sql = "SELECT 1",
                parameters = emptyList(),
            ),
            onRunAdmittedInOwnerTransaction = {},
            onBatch = { batch -> persistedFrames += batch.arrowIpc },
        )

        assertIs<CatalogInlineQueryResult.Succeeded>(result)
        assertEquals(executionRunId, result.runId)
        assertEquals(listOf(payload.copyBytes().toList()), persistedFrames.map(ByteArray::toList))
        assertEquals(1, reserveCalls.get())
        coVerify(exactly = 1) { dialCoordinator.runInline(executionRunId, any()) }
        verify(exactly = 0) { dialCoordinator.dispatchAfterCommit(executionRunId) }
    }
}
