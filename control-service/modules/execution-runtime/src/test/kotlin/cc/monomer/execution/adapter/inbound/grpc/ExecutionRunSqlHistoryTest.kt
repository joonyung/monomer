package cc.monomer.execution.adapter.inbound.grpc

import cc.monomer.execution.adapter.inbound.grpc.ExecutableRequestAssignmentCodec
import cc.monomer.execution.adapter.inbound.grpc.ProtoQueryRequestCanonicalizer
import cc.monomer.execution.api.BinaryValue
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.ExecutableRequestKind
import cc.monomer.execution.api.ExecutionOwnerId
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunQueryPort
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.ExecutionRunState
import cc.monomer.execution.api.PhysicalOutcome
import cc.monomer.execution.api.PhysicalOutcomeKind
import cc.monomer.execution.api.ResultSinkBinding
import cc.monomer.execution.api.ResultSinkBindingCanonicalizer
import cc.monomer.execution.api.ResultSinkKind
import cc.monomer.execution.api.RunDispatchIntent
import cc.monomer.execution.api.RunDispatchIntentPersistenceOutcome
import cc.monomer.execution.api.RunDispatchIntentPort
import cc.monomer.execution.api.RunDispatchLimits
import cc.monomer.execution.api.RunRuntimeBindings
import cc.monomer.execution.api.Sha256Digest
import cc.monomer.execution.application.AdmittedExecutionRun
import cc.monomer.execution.application.ExecutionRunRepository
import cc.monomer.execution.application.ExecutionRunService
import cc.monomer.execution.application.ExecutionRuntimeTransactionRunner
import cc.monomer.execution.application.RunInsertOutcome
import cc.monomer.execution.application.StoredExecutionRunRead
import cc.monomer.execution.api.StoredDispatchIntentRead
import cc.monomer.execution.protocol.v1.ArtifactUploadTarget
import cc.monomer.execution.protocol.v1.QueryParameter
import cc.monomer.execution.protocol.v1.QueryRequest
import cc.monomer.identity.api.WorkspaceId
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.uuid.Uuid

class ExecutionRunSqlHistoryTest {
    @Test
    fun `reads exact Unicode whitespace and comments after dispatch and result expiry`() = runTest {
        val sql = "  -- 주석 😀\r\nSELECT 'e\u0301', 'é', '東京'\tAS 이름;\n"
        val fixture = Fixture(sql)

        val result = assertIs<ExecutionRunQueryPort.ReadSqlOutcome.Available>(fixture.read())

        assertEquals(sql, result.sql)
        assertFalse(result.toString().contains(sql))
        assertEquals(Instant.parse("2020-01-01T00:01:00Z"), fixture.snapshot.runtimeBindings.resultSink.expiresAt)
    }

    @Test
    fun `reads a full maximum size dispatch template without truncation`() = runTest {
        val base = "SELECT '끝😀' /*"
        val suffix = "*/"
        val overhead = Fixture(base + suffix).intent!!.assignmentTemplate.size
        val sql = base + "x".repeat(RunDispatchLimits.MAXIMUM_PAYLOAD_BYTES - overhead - 3) + suffix
        val fixture = Fixture(sql)
        val remaining = RunDispatchLimits.MAXIMUM_PAYLOAD_BYTES - fixture.intent!!.assignmentTemplate.size
        val maximumSql = base + "x".repeat(sql.length - base.length - suffix.length + remaining) + suffix
        val maximumFixture = Fixture(maximumSql)

        assertEquals(RunDispatchLimits.MAXIMUM_PAYLOAD_BYTES, maximumFixture.intent!!.assignmentTemplate.size)
        assertEquals(
            maximumSql,
            assertIs<ExecutionRunQueryPort.ReadSqlOutcome.Available>(maximumFixture.read()).sql,
        )
    }

    @Test
    fun `failed and cancelled runs retain their admitted SQL`() = runTest {
        for (state in listOf(ExecutionRunState.FAILED, ExecutionRunState.CANCELLED)) {
            val fixture = Fixture("SELECT 1")
            fixture.snapshot = fixture.snapshot.copy(
                state = state,
                physicalOutcome = PhysicalOutcome(
                    terminalIdempotencyKey = "terminal",
                    kind = if (state == ExecutionRunState.FAILED) PhysicalOutcomeKind.FAILED else PhysicalOutcomeKind.CANCELLED,
                    failureCode = if (state == ExecutionRunState.FAILED) "EXECUTION_FAILED" else null,
                    safeMessage = null,
                    arrowResultManifest = null,
                ),
            )
            assertEquals("SELECT 1", assertIs<ExecutionRunQueryPort.ReadSqlOutcome.Available>(fixture.read()).sql)
        }
    }

    @Test
    fun `missing execution run and mismatched identities do not read a template`() = runTest {
        val fixture = Fixture("SELECT 1")
        fixture.runExists = false
        assertEquals(ExecutionRunQueryPort.ReadSqlOutcome.NotFound, fixture.read())
        fixture.runExists = true
        val command = fixture.command()
        for (mismatch in listOf(
            command.copy(runId = ExecutionRunId.generate()),
            command.copy(workspaceId = WorkspaceId(Uuid.generateV7())),
            command.copy(owner = command.owner.copy(id = ExecutionOwnerId(Uuid.generateV7()))),
            command.copy(owner = command.owner.copy(type = ExecutionOwnerType.CATALOG)),
        )) {
            assertEquals(ExecutionRunQueryPort.ReadSqlOutcome.BindingMismatch, fixture.service.readSql(mismatch))
        }
        assertEquals(0, fixture.intentReads)
    }

    @Test
    fun `batch reads recorded SQL with one run read and one intent read and marks the rest unavailable`() = runTest {
        val fixture = Fixture("SELECT 1")
        val unknown = ExecutionRunQueryPort.RunOwnerBinding(ExecutionRunId.generate(), fixture.snapshot.owner)
        val foreignOwner = ExecutionRunQueryPort.RunOwnerBinding(
            fixture.snapshot.id,
            fixture.snapshot.owner.copy(type = ExecutionOwnerType.CATALOG),
        )

        val outcome = fixture.service.readSqlBatch(
            ExecutionRunQueryPort.ReadSqlBatchCommand(fixture.snapshot.workspaceId, listOf(fixture.binding(), unknown)),
        )

        assertEquals(listOf(ExecutionRunQueryPort.RecordedRunSql(fixture.snapshot.id, "SELECT 1")), outcome.recorded)
        assertEquals(listOf(unknown.runId), outcome.unavailableRunIds)
        assertFalse(outcome.recorded.single().toString().contains("SELECT 1"))
        assertEquals(1, fixture.batchRunReads)
        assertEquals(1, fixture.batchIntentReads)
        assertEquals(0, fixture.intentReads)

        val mismatched = fixture.service.readSqlBatch(
            ExecutionRunQueryPort.ReadSqlBatchCommand(fixture.snapshot.workspaceId, listOf(foreignOwner)),
        )
        assertEquals(listOf(fixture.snapshot.id), mismatched.unavailableRunIds)
        val otherWorkspace = fixture.service.readSqlBatch(
            ExecutionRunQueryPort.ReadSqlBatchCommand(WorkspaceId(Uuid.generateV7()), listOf(fixture.binding())),
        )
        assertEquals(listOf(fixture.snapshot.id), otherWorkspace.unavailableRunIds)
        fixture.intent = null
        val missingIntent = fixture.service.readSqlBatch(
            ExecutionRunQueryPort.ReadSqlBatchCommand(fixture.snapshot.workspaceId, listOf(fixture.binding())),
        )
        assertEquals(emptyList(), missingIntent.recorded)
        assertEquals(listOf(fixture.snapshot.id), missingIntent.unavailableRunIds)
    }

    @Test
    fun `batch marks a run with an unreadable run or intent row unavailable without failing the others`() = runTest {
        val fixture = Fixture("SELECT 1")
        val other = ExecutionRunQueryPort.RunOwnerBinding(ExecutionRunId.generate(), fixture.snapshot.owner)
        suspend fun read() = fixture.service.readSqlBatch(
            ExecutionRunQueryPort.ReadSqlBatchCommand(fixture.snapshot.workspaceId, listOf(fixture.binding(), other)),
        )

        fixture.runUnreadable = true
        assertEquals(listOf(fixture.snapshot.id, other.runId), read().unavailableRunIds)
        fixture.runUnreadable = false
        fixture.intentUnreadable = true
        assertEquals(listOf(fixture.snapshot.id, other.runId), read().unavailableRunIds)
        fixture.intentUnreadable = false
        assertEquals(listOf(ExecutionRunQueryPort.RecordedRunSql(fixture.snapshot.id, "SELECT 1")), read().recorded)
    }

    @Test
    fun `missing malformed and digest corrupted templates fail explicitly`() = runTest {
        val fixture = Fixture("SELECT 1")
        val originalIntent = fixture.intent!!
        fixture.intent = null
        assertEquals(ExecutionRunQueryPort.ReadSqlOutcome.Unavailable, fixture.read())
        fixture.intent = originalIntent.copy(templateDigest = digest('f'))
        assertEquals(ExecutionRunQueryPort.ReadSqlOutcome.Unavailable, fixture.read())
        val malformed = BinaryValue.copyOf(byteArrayOf(0xff.toByte()))
        fixture.intent = originalIntent.copy(assignmentTemplate = malformed, templateDigest = sha256(malformed))
        assertEquals(ExecutionRunQueryPort.ReadSqlOutcome.Unavailable, fixture.read())
        fixture.intentFailure = true
        assertEquals(ExecutionRunQueryPort.ReadSqlOutcome.Unavailable, fixture.read())
    }

    @Test
    fun `valid replacement template cannot escape immutable run request or sink binding`() = runTest {
        val fixture = Fixture("SELECT 1")
        val original = fixture.intent!!
        val otherSql = BinaryValue.copyOf(QueryRequest.parseFrom(original.assignmentTemplate.copyBytes()).toBuilder().setSql("SELECT 2").build().toByteArray())
        fixture.intent = original.copy(assignmentTemplate = otherSql, templateDigest = sha256(otherSql))
        assertEquals(ExecutionRunQueryPort.ReadSqlOutcome.Unavailable, fixture.read())
        fixture.intent = original
        val sink = fixture.snapshot.runtimeBindings.resultSink.copy(objectKey = "another/result.arrow")
        fixture.snapshot = fixture.snapshot.copy(runtimeBindings = fixture.snapshot.runtimeBindings.copy(resultSink = sink))
        assertEquals(ExecutionRunQueryPort.ReadSqlOutcome.Unavailable, fixture.read())
    }

    @Test
    fun `dispatch identity and immutable timestamps must remain bound to the run`() = runTest {
        val fixture = Fixture("SELECT 1")
        val original = fixture.intent!!
        for (corrupted in listOf(
            original.copy(runId = ExecutionRunId.generate()),
            original.copy(expiresAt = original.expiresAt.plusSeconds(1)),
            original.copy(persistedAt = original.persistedAt.plusSeconds(1)),
        )) {
            fixture.intent = corrupted
            assertEquals(ExecutionRunQueryPort.ReadSqlOutcome.Unavailable, fixture.read())
        }
    }

    private class Fixture(sql: String) {
        private val createdAt = Instant.parse("2020-01-01T00:00:00Z")
        private val queryRequest = QueryRequest.newBuilder()
            .setSchemaVersion(1)
            .setSql(sql)
            .addParameters(QueryParameter.newBuilder().setStringValue("parameter must remain private"))
            .setArtifactUpload(
                ArtifactUploadTarget.newBuilder()
                    .setObjectKey("private/result.arrow")
                    .setMaximumArtifactRows(100)
                    .setMaximumArtifactBytes(8192)
                    .setMaximumRecordBatchRows(100)
                    .setMaximumRecordBatchBytes(8192)
                    .setMaximumCellBytes(4096)
                    .setMaximumMultipartPartBytes(8192),
            ).build()
        private val sink = ResultSinkBinding(
            schemaVersion = 2u,
            kind = ResultSinkKind.ARTIFACT_UPLOAD,
            objectKey = queryRequest.artifactUpload.objectKey,
            maximumArtifactRows = 100u,
            maximumArtifactBytes = 8192u,
            expiresAt = createdAt.plusSeconds(60),
            maximumRecordBatchRows = 100,
            maximumFrameBytes = 8192,
            maximumCellBytes = 4096,
            maximumMultipartPartBytes = 8192,
            bindingDigest = digest('a'),
        ).let { it.copy(bindingDigest = ResultSinkBindingCanonicalizer.digest(it)) }
        var snapshot = ExecutionRunSnapshot(
            id = ExecutionRunId.generate(),
            owner = ExecutionOwnerReference(ExecutionOwnerType.EXPLORE, ExecutionOwnerId(Uuid.generateV7())),
            workspaceId = WorkspaceId(Uuid.generateV7()),
            connectorId = ConnectorReference(Uuid.generateV7()),
            connectorType = ConnectorType.POSTGRESQL,
            connectorProperties = mapOf("host" to ConnectorRuntimePropertyValue.Text("private-host")),
            requestKind = ExecutableRequestKind.QUERY,
            canonicalRequestDigest = ProtoQueryRequestCanonicalizer.canonicalDigest(queryRequest),
            runtimeBindings = RunRuntimeBindings(digest('b'), null, "private-key", digest('c'), sink),
            executionOccupancyAcquiredAt = null,
            executionOccupancyReleasedAt = null,
            dispatchDeadlineAt = createdAt.plusSeconds(10),
            deadline = createdAt.plusSeconds(60),
            state = ExecutionRunState.RUNNING,
            physicalOutcome = null,
            cancelIntent = null,
            ownerSettledAt = null,
            ownerDiscardedAt = null,
            createdAt = createdAt,
            updatedAt = createdAt,
            version = 0,
        )
        var runExists = true
        var intentFailure = false
        var intentReads = 0
        var batchRunReads = 0
        var runUnreadable = false
        var intentUnreadable = false
        var batchIntentReads = 0
        var intent: RunDispatchIntent? = BinaryValue.copyOf(queryRequest.toByteArray()).let { template ->
            RunDispatchIntent(snapshot.id, sha256(template), template, snapshot.dispatchDeadlineAt, createdAt)
        }
        val service = ExecutionRunService(
            repository = object : ExecutionRunRepository {
                override fun find(runId: ExecutionRunId) = if (runExists) AdmittedExecutionRun(snapshot, "request", snapshot.canonicalRequestDigest) else null
                override fun findAll(runIds: Set<ExecutionRunId>): List<StoredExecutionRunRead> {
                    batchRunReads++
                    if (!runExists || snapshot.id !in runIds) return emptyList()
                    return listOf(
                        if (runUnreadable) StoredExecutionRunRead.Unreadable(snapshot.id)
                        else StoredExecutionRunRead.Readable(AdmittedExecutionRun(snapshot, "request", snapshot.canonicalRequestDigest)),
                    )
                }
                override fun findForUpdate(runId: ExecutionRunId): AdmittedExecutionRun? = error("unexpected write")
                override fun requireAmbientTransaction() = error("unexpected write")
                override fun findByIdempotencyKey(owner: ExecutionOwnerReference, idempotencyKey: String): AdmittedExecutionRun? = error("unexpected read")
                override fun lockWorkspaceRunAdmission(workspaceId: WorkspaceId) = error("unexpected write")
                override fun countExecutionOccupancy(workspaceId: WorkspaceId): Int = error("unexpected read")
                override fun findOccupancyRecoveryCandidates(observedAt: Instant, staleAfter: Instant, maximumRuns: Int): List<StoredExecutionRunRead> = error("unexpected read")
                override fun countNonTerminalRuns(workspaceId: WorkspaceId): Int = error("unexpected read")
                override fun insert(run: AdmittedExecutionRun): RunInsertOutcome = error("unexpected write")
                override fun compareAndSet(expectedVersion: Long, snapshot: ExecutionRunSnapshot): Boolean = error("unexpected write")
                override fun findOrphanCandidates(observedAt: Instant, staleAfter: Instant, maximumRuns: Int): List<StoredExecutionRunRead> = error("unexpected read")
                override fun findDispatchDeadlineCandidates(observedAt: Instant, maximumRuns: Int): List<StoredExecutionRunRead> = error("unexpected read")
                override fun findExecutionDeadlineCandidates(observedAt: Instant, maximumRuns: Int): List<StoredExecutionRunRead> = error("unexpected read")
                override fun findArtifactCleanupCandidates(observedAt: Instant, maximumRuns: Int): List<StoredExecutionRunRead> = error("unexpected read")
                override fun beginArtifactCleanup(runId: ExecutionRunId, expectedVersion: Long, attemptedAt: Instant): Boolean = error("unexpected write")
                override fun completeArtifactCleanup(runId: ExecutionRunId, expectedVersion: Long, completedAt: Instant): Boolean = error("unexpected write")
                override fun invalidateArtifactCleanup(runId: ExecutionRunId, expectedVersion: Long): Boolean = error("unexpected write")
                override fun findOwnerSettlementCandidates(ownerTypes: Set<ExecutionOwnerType>, maximumRuns: Int): List<StoredExecutionRunRead> = error("unexpected read")
            },
            readTransactionRunner = object : ExecutionRuntimeTransactionRunner {
                override suspend fun <T> run(block: () -> T): T = block()
            },
            mutationTransactionRunner = object : ExecutionRuntimeTransactionRunner {
                override suspend fun <T> run(block: () -> T): T = error("unexpected mutation transaction")
            },
            dispatchIntentPort = object : RunDispatchIntentPort {
                override fun persist(intent: RunDispatchIntent): RunDispatchIntentPersistenceOutcome = error("unexpected write")
                override fun find(runId: ExecutionRunId): RunDispatchIntent? {
                    intentReads++
                    require(!intentFailure)
                    return intent
                }
                override fun findAll(runIds: Set<ExecutionRunId>): List<StoredDispatchIntentRead> {
                    batchIntentReads++
                    val stored = intent?.takeIf { it.runId in runIds } ?: return emptyList()
                    return listOf(
                        if (intentUnreadable) StoredDispatchIntentRead.Unreadable(stored.runId)
                        else StoredDispatchIntentRead.Readable(stored),
                    )
                }
            },
            assignmentCodec = ExecutableRequestAssignmentCodec(),
        )
        fun command() = ExecutionRunQueryPort.ReadSqlCommand(snapshot.id, snapshot.workspaceId, snapshot.owner)
        fun binding() = ExecutionRunQueryPort.RunOwnerBinding(snapshot.id, snapshot.owner)
        suspend fun read() = service.readSql(command())
    }
}

private fun digest(character: Char): Sha256Digest = Sha256Digest(character.toString().repeat(64))

private fun sha256(value: BinaryValue): Sha256Digest = Sha256Digest(
    MessageDigest.getInstance("SHA-256").digest(value.copyBytes())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) },
)
