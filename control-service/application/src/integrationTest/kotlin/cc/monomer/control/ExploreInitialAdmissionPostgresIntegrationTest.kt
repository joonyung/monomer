@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control

import cc.monomer.connector.api.ConnectorId
import cc.monomer.execution.api.*
import cc.monomer.execution.application.*
import cc.monomer.execution.adapter.outbound.postgres.*
import cc.monomer.explore.api.*
import cc.monomer.explore.application.*
import cc.monomer.explore.domain.ExploreRun
import cc.monomer.explore.adapter.outbound.postgres.PostgresExploreRunRepository
import cc.monomer.explore.adapter.outbound.postgres.SpringActiveExploreInitialAdmissionTransactionPort
import cc.monomer.explore.adapter.outbound.postgres.SpringRetryingExploreInitialAdmissionTransactionPort
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

class ExploreInitialAdmissionPostgresIntegrationTest {
    companion object {
        @JvmField @RegisterExtension val database = EphemeralPostgresAuthoritySupport
    }
    private val dataSource: DataSource get() = EphemeralPostgresAuthoritySupport.dataSource
    private lateinit var repository: PostgresExploreRunRepository
    private lateinit var transactionManager: DataSourceTransactionManager

    @BeforeEach
    fun setUp() {
        EphemeralPostgresAuthoritySupport.verifyEphemeralIdentity()
        EphemeralPostgresAuthoritySupport.applyAuthoritySchemasTwice()
        transactionManager = DataSourceTransactionManager(dataSource)
        repository = PostgresExploreRunRepository(dataSource)
    }

    @Test
    fun `production initial admission accepts only one of two concurrent requests after 255 runs`(): Unit = runBlocking {
        val fixture = Fixture()
        val coordinator = coordinator(repository, 256)
        repeat(255) { assertIs<StartExploreRunOutcome.Started>(coordinator.admit(admissionCommand(fixture))) }
        val barrier = CyclicBarrier(2)
        val synchronizedRepository = object : ExploreRunRepository by repository {
            override fun lockAdmissionKey(workspaceId: WorkspaceId, idempotencyKey: String) {
                repository.lockAdmissionKey(workspaceId, idempotencyKey)
                barrier.await(5, TimeUnit.SECONDS)
            }
        }
        val simultaneous = coordinator(synchronizedRepository, 256)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = List(2) {
                val command = admissionCommand(fixture)
                executor.submit<Boolean> {
                    when (val outcome = runBlocking { simultaneous.admit(command) }) {
                        is StartExploreRunOutcome.Started -> true
                        StartExploreRunOutcome.Backpressured -> false
                        else -> error("Unexpected admission outcome: $outcome")
                    }
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it })
            assertEquals(256, workspaceCount("execution_run.runs", fixture.workspaceId))
            assertEquals(256, workspaceCount("explore.runs", fixture.workspaceId))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `initial admission replays a concurrent identical key without reserving another execution`(): Unit {
        val fixture = Fixture()
        val command = admissionCommand(fixture)
        val coordinator = coordinator(repository, 256)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = List(2) { executor.submit<StartExploreRunOutcome> {
                check(start.await(5, TimeUnit.SECONDS))
                runBlocking { coordinator.admit(command) }
            } }
            start.countDown()
            val outcomes = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it is StartExploreRunOutcome.Started })
            assertEquals(1, outcomes.count { it is StartExploreRunOutcome.Existing })
            assertEquals(1, workspaceCount("execution_run.runs", fixture.workspaceId))
            assertEquals(1, workspaceCount("explore.runs", fixture.workspaceId))
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `workspace backpressure rolls back before returning a retryable admission outcome`(): Unit = runBlocking {
        val fixture = Fixture()
        val first = admissionCommand(fixture)
        val second = first.copy(runId = ExploreRunId.generate(), idempotencyKey = "backpressured-${ExploreRunId.generate()}")
        val admission = coordinator(repository, 1)
        assertIs<StartExploreRunOutcome.Started>(admission.admit(first))
        assertEquals(StartExploreRunOutcome.Backpressured, admission.admit(second))
        assertEquals(1, workspaceCount("execution_run.runs", fixture.workspaceId))
        assertEquals(1, workspaceCount("explore.runs", fixture.workspaceId))
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM execution_run.dispatch_intents WHERE run_id = ?").use { statement ->
                statement.setObject(1, second.runId.value.toJavaUuid())
                statement.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(0, rows.getInt(1)) }
            }
        }
    }

    @Test
    fun `initial admission rollback removes reserved execution and dispatch intent`(): Unit = runBlocking {
        val fixture = Fixture()
        val command = admissionCommand(fixture)
        val rejecting = object : ExploreRunRepository by repository {
            override fun insertAdmittedRun(run: cc.monomer.explore.domain.ExploreRun): Boolean = false
        }
        val failure = assertFailsWith<ExploreInitialAdmissionRollbackException> {
            coordinator(rejecting, 256).admit(command)
        }
        assertEquals(ExploreInitialAdmissionRollbackReason.RUN_INSERT_CONFLICT, failure.reason)
        assertEquals(0, workspaceCount("execution_run.runs", fixture.workspaceId))
        assertEquals(0, workspaceCount("explore.runs", fixture.workspaceId))
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM execution_run.dispatch_intents WHERE run_id = ?").use { statement ->
                statement.setObject(1, command.runId.value.toJavaUuid())
                statement.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(0, rows.getInt(1)) }
            }
        }
    }

    @Test
    fun `an execution reserved for the run without its Explore run fails loudly instead of reading as a conflict`(): Unit = runBlocking {
        val fixture = Fixture()
        val command = admissionCommand(fixture)
        assertIs<StartExploreRunOutcome.Started>(coordinator(repository, 256).admit(command))
        val missingExploreRun = object : ExploreRunRepository by repository {
            override fun findByAdmissionKey(workspaceId: WorkspaceId, idempotencyKey: String): cc.monomer.explore.domain.ExploreRun? = null
        }
        assertEquals(
            ExploreInitialAdmissionRollbackReason.EXECUTION_ALREADY_RESERVED,
            assertFailsWith<ExploreInitialAdmissionRollbackException> { coordinator(missingExploreRun, 256).admit(command) }.reason,
        )
        val changedExecutionRequest = command.copy(
            executionReservation = command.executionReservation.copy(executionRequestDigest = ExploreDigest("sha256:${"c".repeat(64)}")),
        )
        assertEquals(
            ExploreInitialAdmissionRollbackReason.EXECUTION_IDEMPOTENCY_CONFLICT,
            assertFailsWith<ExploreInitialAdmissionRollbackException> { coordinator(missingExploreRun, 256).admit(changedExecutionRequest) }.reason,
        )
        assertEquals(1, workspaceCount("execution_run.runs", fixture.workspaceId))
        assertEquals(1, workspaceCount("explore.runs", fixture.workspaceId))
    }

    private fun initialTransactions(source: DataSource): SpringRetryingExploreInitialAdmissionTransactionPort =
        SpringRetryingExploreInitialAdmissionTransactionPort(
            TransactionTemplate(DataSourceTransactionManager(source)).apply {
                isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
                propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
                timeout = 10
            },
            SpringActiveExploreInitialAdmissionTransactionPort(source),
            3,
            kotlinx.coroutines.Dispatchers.IO,
        )

    private fun coordinator(ownerRepository: ExploreRunRepository, maximumActiveRuns: Int): ExploreAdmissionCoordinator {
        val codec = object : RunDispatchAssignmentCodec {
            override fun canonicalizeTemplate(assignmentTemplate: BinaryValue): BinaryValue = assignmentTemplate
            override fun readSql(canonicalTemplate: BinaryValue, snapshot: ExecutionRunSnapshot): String? = null
        }
        return ExploreAdmissionCoordinator(
            initialTransactions(dataSource),
            ExecutionRunAdmissionService(
                PostgresExecutionRuntimeStore(dataSource), PostgresRunDispatchPersistence(dataSource, TransactionTemplate(transactionManager)),
                codec, ExecutionRunLimits(maximumActiveRuns),
            ),
            ownerRepository,
        )
    }

    private fun workspaceCount(table: String, workspaceId: WorkspaceId): Int = dataSource.connection.use { connection ->
        check(table in setOf("execution_run.runs", "explore.runs"))
        connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE workspace_id = ?").use { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
        }
    }

    private fun admissionCommand(fixture: Fixture): StartSqlExploreRunCommand {
        val run = fixture.runningRun.copy(id = ExploreRunId.generate(), idempotencyKey = "admission-${ExploreRunId.generate()}")
        val deadline = run.createdAt.plusSeconds(120)
        val sink = ResultSinkBinding(
            2u, ResultSinkKind.ARTIFACT_UPLOAD,
            "artifacts/workspaces/${run.workspaceId}/explore/runs/${run.id}/result.arrow",
            1000u, 1048576u, deadline, 128, 1024, 1024, 1024, Sha256Digest("a".repeat(64)),
        ).let { it.copy(bindingDigest = ResultSinkBindingCanonicalizer.digest(it)) }
        return StartSqlExploreRunCommand(
            run.id, run.idempotencyKey, run.ownerPrincipalId, run.credentialSurface, run.agentClientId, run.connectorBinding,
            run.requestDigest,
            ExploreExecutionReservation(
                run.requestDigest, mapOf("host" to ConnectorRuntimePropertyValue.Text("localhost")),
                RunRuntimeBindings(Sha256Digest("a".repeat(64)), CredentialVersionReference(Uuid.generateV7()), "test-key",
                    Sha256Digest("b".repeat(64)), sink),
                BinaryValue.copyOf(byteArrayOf(1)), run.createdAt.plusSeconds(60), deadline,
            ),
            run.createdAt,
        )
    }

}

private class Fixture {
    val workspaceId = WorkspaceId.generate()
    val owner = PrincipalId.generate()
    private val now = Instant.parse("2026-08-20T00:00:00Z")
    private val binding = ExploreConnectorBinding(workspaceId, ConnectorId(Uuid.generateV7()), ConnectorType.POSTGRESQL)
    val runningRun = ExploreRun(
        id = ExploreRunId.generate(),
        kind = ExploreRun.Kind.Sql,
        idempotencyKey = "admission-${ExploreRunId.generate()}",
        ownerPrincipalId = owner,
        credentialSurface = cc.monomer.identity.api.CredentialSurface.AGENT,
        agentClientId = cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"),
        connectorBinding = binding,
        requestDigest = ExploreDigest("sha256:${"a".repeat(64)}"),
        state = ExploreRunState.RUNNING,
        visibleResult = null,
        failureCode = null,
        failureMessage = null,
        revision = 0,
        createdAt = now.minusSeconds(1),
        updatedAt = now.minusSeconds(1),
    )
}
