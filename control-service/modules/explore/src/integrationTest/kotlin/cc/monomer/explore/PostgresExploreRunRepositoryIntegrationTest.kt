@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.explore

import cc.monomer.connector.api.ConnectorId
import cc.monomer.execution.api.ConnectorType
import cc.monomer.explore.adapter.outbound.postgres.PostgresExploreRunRepository
import cc.monomer.explore.adapter.outbound.postgres.PostgresExploreActivityReadPort
import cc.monomer.explore.adapter.outbound.postgres.SpringExploreTransactionPort
import cc.monomer.explore.api.ExploreConnectorBinding
import cc.monomer.explore.api.ExploreDigest
import cc.monomer.explore.api.ExploreResultRetention
import cc.monomer.explore.api.ExploreResultView
import cc.monomer.explore.api.ExploreRunId
import cc.monomer.explore.api.ExploreRunState
import cc.monomer.explore.api.ExploreRunPageBoundary
import cc.monomer.explore.domain.ExploreTerminalReceipt
import cc.monomer.explore.domain.ExploreRun
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.SemanticElementName
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

import cc.monomer.explore.adapter.outbound.postgres.SpringActiveExploreInitialAdmissionTransactionPort
import cc.monomer.explore.adapter.outbound.postgres.SpringRetryingExploreInitialAdmissionTransactionPort
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PostgresExploreRunRepositoryIntegrationTest {
    private val dataSource: DataSource
        get() = TargetExplorePostgres.dataSource
    private lateinit var repository: PostgresExploreRunRepository
    private lateinit var activityReader: PostgresExploreActivityReadPort
    private lateinit var transactionManager: DataSourceTransactionManager

    @BeforeEach
    fun setUp() {
        repository = PostgresExploreRunRepository(dataSource)
        activityReader = newActivityReader(dataSource, Dispatchers.IO)
        transactionManager = DataSourceTransactionManager(dataSource)
        dataSource.connection.use { connection ->
            TargetExplorePostgres.applyCanonicalSchemaTwice(connection, javaClass.classLoader)
        }
    }

    @Test
    fun `SQL variant and terminal receipt round trip through common tables`() {
        val fixture = Fixture()
        val terminalRun = fixture.runningRun.complete(fixture.successReceipt)
        repeatableRead {
            assertTrue(repository.insertAdmittedRun(fixture.runningRun))
            assertTrue(repository.commitTerminalReceipt(terminalRun, fixture.successReceipt, 0))
        }

        val persisted = checkNotNull(repository.findRun(fixture.runningRun.id))
        assertEquals(ExploreRunState.SUCCEEDED, persisted.state)
        assertEquals(fixture.successReceipt.result, persisted.visibleResult)
        assertEquals(fixture.successReceipt, repository.findTerminalReceipt(fixture.workspaceId, fixture.settlementKey))
    }

    @Test
    fun `semantic detail stores the render Reporting time zone and reads a run recorded without one as null`() {
        val fixture = Fixture()
        val zoned = fixture.semanticRun
        val unzoned = zoned.copy(
            id = ExploreRunId.generate(),
            idempotencyKey = "semantic-unzoned-${ExploreRunId.generate()}",
            kind = (zoned.kind as ExploreRun.Kind.Semantic).copy(reportingTimeZone = null),
        )
        repeatableRead {
            assertTrue(repository.insertAdmittedRun(zoned))
            assertTrue(repository.insertAdmittedRun(unzoned))
        }
        assertEquals(java.time.ZoneId.of("Asia/Seoul"), (checkNotNull(repository.findRun(zoned.id)).kind as ExploreRun.Kind.Semantic).reportingTimeZone)
        assertEquals(unzoned, repository.findRun(unzoned.id))
        val page = repository.findRuns(fixture.workspaceId, fixture.owner, null, 10)
        assertEquals(
            mapOf(zoned.id to java.time.ZoneId.of("Asia/Seoul"), unzoned.id to null),
            page.associate { it.id to (it.asView().kind as cc.monomer.explore.api.ExploreRunView.Kind.Semantic).reportingTimeZone },
        )
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT run_id, reporting_time_zone FROM explore.semantic_run_details WHERE workspace_id = ?").use { statement ->
                statement.setObject(1, java.util.UUID.fromString(fixture.workspaceId.toString()))
                statement.executeQuery().use { rows ->
                    val stored = buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) }
                    assertEquals(mapOf(zoned.id.toString() to "Asia/Seoul", unzoned.id.toString() to null), stored)
                }
            }
        }
    }

    @Test
    fun `semantic detail stores original query intent and captured display`() {
        val fixture = Fixture()
        val semantic = fixture.semanticRun
        repeatableRead { assertTrue(repository.insertAdmittedRun(semantic)) }
        val persisted = checkNotNull(repository.findRun(semantic.id))
        assertEquals(semantic, persisted)
        val receipt = fixture.failureReceipt.copy(runId = semantic.id)
        val failed = semantic.complete(receipt)
        repeatableRead { assertTrue(repository.commitTerminalReceipt(failed, receipt, semantic.revision)) }
        val settled = checkNotNull(repository.findRun(semantic.id))
        assertEquals(failed, settled)
        assertEquals(semantic.kind, settled.kind)
        assertEquals(
            (semantic.kind as ExploreRun.Kind.Semantic).query,
            (settled.asView().kind as cc.monomer.explore.api.ExploreRunView.Kind.Semantic).query,
        )
    }

    @Test
    fun `failed terminal receipt round trips safe detail into the run view`() {
        val fixture = Fixture()
        val failureReceipt = fixture.failureReceipt
        val failedRun = fixture.runningRun.complete(failureReceipt)
        repeatableRead {
            assertTrue(repository.insertAdmittedRun(fixture.runningRun))
            assertTrue(repository.commitTerminalReceipt(failedRun, failureReceipt, 0))
        }

        val persisted = checkNotNull(repository.findRun(fixture.runningRun.id))
        assertEquals(failureReceipt.failureMessage, persisted.failureMessage)
        assertEquals(failureReceipt.failureMessage, persisted.asView().failureDetail)
        assertEquals(failureReceipt, repository.findTerminalReceipt(fixture.workspaceId, fixture.failureKey))
    }

    @Test
    fun `classified warehouse failures persist through both failure constraints and the public view`() {
        listOf(
            cc.monomer.explore.api.ExploreRunFailureCode.PERMISSION_DENIED to "permission denied for schema private_data",
            cc.monomer.explore.api.ExploreRunFailureCode.CONNECTION_FAILED to "Connection to 127.0.0.1:15432 refused.",
            cc.monomer.explore.api.ExploreRunFailureCode.AUTHENTICATION_FAILED to "password authentication failed for user \"reader\"",
        ).forEach { (failureCode, failureMessage) ->
            val fixture = Fixture()
            val receipt = fixture.failureReceipt.copy(failureCode = failureCode, failureMessage = failureMessage)
            repeatableRead {
                assertTrue(repository.insertAdmittedRun(fixture.runningRun))
                assertTrue(repository.commitTerminalReceipt(fixture.runningRun.complete(receipt), receipt, 0))
            }
            val persisted = checkNotNull(repository.findRun(fixture.runningRun.id)).asView()
            assertEquals(receipt.failureCode, persisted.failureCode)
            assertEquals(receipt.failureMessage, persisted.failureDetail)
            assertEquals(receipt, repository.findTerminalReceipt(fixture.workspaceId, fixture.failureKey))
        }
    }

    @Test
    fun `standalone run read keeps one snapshot while settlement commits`() {
        val fixture = Fixture()
        repeatableRead { assertTrue(repository.insertAdmittedRun(fixture.runningRun)) }
        val receiptQueryReached = CountDownLatch(1)
        val settlementCompleted = CountDownLatch(1)
        val snapshotRepository = PostgresExploreRunRepository(
            PausingReceiptDataSource(dataSource, receiptQueryReached, settlementCompleted),
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val read = executor.submit<ExploreRun> {
                checkNotNull(snapshotRepository.findRun(fixture.runningRun.id))
            }
            assertTrue(receiptQueryReached.await(5, TimeUnit.SECONDS))
            val failedRun = fixture.runningRun.complete(fixture.failureReceipt)
            try {
                repeatableRead {
                    assertTrue(repository.commitTerminalReceipt(failedRun, fixture.failureReceipt, 0))
                }
            } finally {
                settlementCompleted.countDown()
            }

            val observed = read.get(5, TimeUnit.SECONDS)
            assertEquals(ExploreRunState.RUNNING, observed.state)
            assertEquals(null, observed.failureMessage)
        } finally {
            settlementCompleted.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `run history uses descending tuple keyset with actor and workspace isolation`() {
        val fixture = Fixture()
        val timestamp = Instant.parse("2026-08-20T00:00:00Z")
        fun targetRun(suffix: Int, createdAt: Instant = timestamp) = fixture.runningRun.copy(
            id = ExploreRunId(Uuid.parse("019e0000-0000-7000-8000-${suffix.toString().padStart(12, '0')}")),
            idempotencyKey = "page-$suffix",
            createdAt = createdAt,
            updatedAt = createdAt,
        )
        val oldest = targetRun(1, timestamp.minusSeconds(1))
        val tiedLower = targetRun(2)
        val tiedHigher = targetRun(3)
        val newest = targetRun(4, timestamp.plusSeconds(1))
        val otherActor = targetRun(8, timestamp.plusSeconds(2)).copy(ownerPrincipalId = PrincipalId.generate())
        val otherWorkspaceId = WorkspaceId.generate()
        val otherWorkspace = targetRun(9, timestamp.plusSeconds(2)).copy(
            connectorBinding = fixture.runningRun.connectorBinding.copy(workspaceId = otherWorkspaceId),

        )
        repeatableRead {
            listOf(oldest, tiedLower, tiedHigher, newest, otherActor, otherWorkspace).forEach {
                assertTrue(repository.insertAdmittedRun(it))
            }
        }

        val first = repository.findRuns(fixture.workspaceId, fixture.owner, null, 2)
        assertEquals(listOf(newest.id, tiedHigher.id), first.map { it.id })

        val insertedAfterFirstPage = targetRun(5, timestamp.plusSeconds(3))
        repeatableRead { assertTrue(repository.insertAdmittedRun(insertedAfterFirstPage)) }
        val second = repository.findRuns(
            fixture.workspaceId,
            fixture.owner,
            ExploreRunPageBoundary(tiedHigher.createdAt, tiedHigher.id),
            10,
        )
        assertEquals(listOf(tiedLower.id, oldest.id), second.map { it.id })
        assertEquals(
            emptyList(),
            repository.findRuns(
                fixture.workspaceId,
                fixture.owner,
                ExploreRunPageBoundary(oldest.createdAt, oldest.id),
                10,
            ),
        )
    }

    @Test
    fun `run history page reads runs, receipts and semantic details in one statement`() {
        val fixture = Fixture()
        val failed = fixture.runningRun.copy(
            id = ExploreRunId.generate(),
            idempotencyKey = "failed-${ExploreRunId.generate()}",
            createdAt = fixture.runningRun.createdAt.minusSeconds(1),
            updatedAt = fixture.runningRun.createdAt.minusSeconds(1),
        )
        val failureReceipt = fixture.failureReceipt.copy(runId = failed.id)
        repeatableRead {
            listOf(fixture.runningRun, failed, fixture.semanticRun).forEach { assertTrue(repository.insertAdmittedRun(it)) }
            assertTrue(repository.commitTerminalReceipt(fixture.runningRun.complete(fixture.successReceipt), fixture.successReceipt, 0))
            assertTrue(repository.commitTerminalReceipt(failed.complete(failureReceipt), failureReceipt, 0))
        }
        val counting = StatementCountingDataSource(dataSource)

        val page = PostgresExploreRunRepository(counting).findRuns(fixture.workspaceId, fixture.owner, null, 10)

        assertEquals(1, counting.preparedStatements)
        assertEquals(listOf(fixture.semanticRun.id, fixture.runningRun.id, failed.id), page.map { it.id })
        assertEquals(page.map { checkNotNull(repository.findRun(it.id)) }, page)
        assertEquals(fixture.successReceipt.result, page[1].visibleResult)
        assertEquals(failureReceipt.failureMessage, page[2].failureMessage)
        assertEquals((fixture.semanticRun.kind as ExploreRun.Kind.Semantic).outputColumnDisplays, (page[0].kind as ExploreRun.Kind.Semantic).outputColumnDisplays)
    }

    @Test
    fun `team activity reads across actors with stable cursor and terminal watch`() = runBlocking {
        val fixture = Fixture()
        val first = fixture.runningRun
        val second = first.copy(id = ExploreRunId.generate(), idempotencyKey = "another-actor", ownerPrincipalId = PrincipalId.generate(), createdAt = first.createdAt.plusSeconds(1), updatedAt = first.createdAt.plusSeconds(1))
        repeatableRead { assertTrue(repository.insertAdmittedRun(first)); assertTrue(repository.insertAdmittedRun(second)) }
        val head = activityReader.readActivity(fixture.workspaceId, true, null, null, null, null, null, 1, emptySet()).single()
        assertEquals(second.id.value, head.id)
        assertEquals(cc.monomer.identity.api.CredentialSurface.AGENT, head.credentialSurface)
        assertEquals(first.id.value, activityReader.readActivity(fixture.workspaceId, true, head.startedAt, head.id, null, null, null, 1, emptySet()).single().id)
        assertEquals(first.id.value, activityReader.readActivity(fixture.workspaceId, true, null, null, fixture.owner, null, null, 10, emptySet()).single().id)
        assertEquals(2, activityReader.readActivity(fixture.workspaceId, true, null, null, null, cc.monomer.identity.api.CredentialSurface.AGENT, null, 10, emptySet()).size)
        assertTrue(activityReader.readActivity(fixture.workspaceId, true, null, null, null, cc.monomer.identity.api.CredentialSurface.WEB, null, 10, emptySet()).isEmpty())
        repeatableRead { repository.commitTerminalReceipt(first.complete(fixture.successReceipt), fixture.successReceipt, 0) }
        val watched = activityReader.readActivity(fixture.workspaceId, true, null, null, null, null, null, 1, setOf(first.id.value)).single()
        assertEquals(ExploreRunState.SUCCEEDED, watched.state)
        assertEquals(first.id.value, activityReader.readActivity(fixture.workspaceId, false, null, null, null, null, null, 10, emptySet()).single().id)
        assertTrue(activityReader.readActivity(WorkspaceId.generate(), true, null, null, null, null, null, 10, setOf(first.id.value)).isEmpty())
    }

    @Test
    fun `agent runs keep their app, web runs keep none, and activity filters by app`() = runBlocking {
        val fixture = Fixture()
        val claude = cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata")
        val codex = cc.monomer.identity.api.AgentClientId("https://chatgpt.com/codex/metadata.json")
        val agentRun = fixture.runningRun
        val otherAppRun = agentRun.copy(id = ExploreRunId.generate(), idempotencyKey = "other-app", agentClientId = codex,
            createdAt = agentRun.createdAt.plusSeconds(1), updatedAt = agentRun.createdAt.plusSeconds(1))
        val webRun = agentRun.copy(id = ExploreRunId.generate(), idempotencyKey = "web", credentialSurface = cc.monomer.identity.api.CredentialSurface.WEB,
            agentClientId = null, createdAt = agentRun.createdAt.plusSeconds(2), updatedAt = agentRun.createdAt.plusSeconds(2))
        repeatableRead { listOf(agentRun, otherAppRun, webRun).forEach { assertTrue(repository.insertAdmittedRun(it)) } }
        repeatableRead {
            assertEquals(claude, checkNotNull(repository.lockRunForOwnerMutation(agentRun.id)).agentClientId)
            assertEquals(null, checkNotNull(repository.lockRunForOwnerMutation(webRun.id)).agentClientId)
        }
        val byApp = activityReader.readActivity(fixture.workspaceId, true, null, null, null, cc.monomer.identity.api.CredentialSurface.AGENT, claude, 10, emptySet())
        assertEquals(listOf(agentRun.id.value), byApp.map { it.id })
        assertEquals(claude, byApp.single().agentClientId)
        assertEquals(null, activityReader.readActivity(fixture.workspaceId, true, null, null, null, cc.monomer.identity.api.CredentialSurface.WEB, null, 10, emptySet()).single().agentClientId)
        assertTrue(activityReader.readRecentAgentClients(fixture.workspaceId, 500).isEmpty(), "running runs are not recent activity")
        repeatableRead { repository.commitTerminalReceipt(agentRun.complete(fixture.successReceipt), fixture.successReceipt, 0) }
        assertEquals(listOf(claude), activityReader.readRecentAgentClients(fixture.workspaceId, 500).map { it.clientId })
        assertTrue(activityReader.readRecentAgentClients(WorkspaceId.generate(), 500).isEmpty())
        val invalid = assertFailsWith<Exception> {
            repeatableRead {
                org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource).createStatement().use {
                    it.executeUpdate("UPDATE explore.runs SET agent_client_id = 'https://claude.ai/x' WHERE run_id = '${webRun.id.value}'")
                }
            }
        }
        assertTrue(invalid.toString().contains("runs_agent_client_id_check") || invalid.cause.toString().contains("runs_agent_client_id_check"))
    }

    @Test
    fun `activity and recent agent clients use bounded read-only owner transactions off the caller thread`() = runBlocking {
        val fixture = Fixture()
        repeatableRead {
            assertTrue(repository.insertAdmittedRun(fixture.runningRun))
            assertTrue(repository.commitTerminalReceipt(fixture.runningRun.complete(fixture.successReceipt), fixture.successReceipt, 0))
        }
        val observed = ObservingActivityDataSource(dataSource, Thread.currentThread())
        Executors.newSingleThreadExecutor { task -> Thread(task, "explore-activity-jdbc") }.asCoroutineDispatcher().use { dispatcher ->
            val reader = newActivityReader(observed, dispatcher)
            assertTrue(reader.readActivity(fixture.workspaceId, true, null, null, null, null, null, 10, emptySet()).isEmpty())
            assertEquals(fixture.runningRun.id.value, reader.readActivity(fixture.workspaceId, false, null, null, null, null, null, 10, emptySet()).single().id)
            assertEquals(fixture.runningRun.agentClientId, reader.readRecentAgentClients(fixture.workspaceId, 500).single().clientId)
        }
        assertEquals(3, observed.activityQueries)
    }

    @Test
    fun `initial replay holds the run lock while reading its terminal receipt`(): Unit {
        val fixture = Fixture()
        repeatableRead { assertTrue(repository.insertAdmittedRun(fixture.runningRun)) }
        val receiptReached = CountDownLatch(1)
        val allowReceipt = CountDownLatch(1)
        val pausedSource = PausingReceiptDataSource(dataSource, receiptReached, allowReceipt)
        val pausedRepository = PostgresExploreRunRepository(pausedSource)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val replay = executor.submit<cc.monomer.explore.domain.ExploreRun> {
                kotlinx.coroutines.runBlocking {
                    initialTransactions(pausedSource).inInitialAdmissionTransaction {
                        pausedRepository.lockAdmissionKey(fixture.workspaceId, fixture.runningRun.idempotencyKey)
                        checkNotNull(pausedRepository.findByAdmissionKey(fixture.workspaceId, fixture.runningRun.idempotencyKey))
                    }
                }
            }
            assertTrue(receiptReached.await(5, TimeUnit.SECONDS))
            val blocked = assertFailsWith<Exception> {
                repeatableRead {
                    org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource).createStatement().use {
                        it.execute("SET LOCAL lock_timeout = '150ms'")
                    }
                    repository.lockRunForOwnerMutation(fixture.runningRun.id)
                    assertTrue(repository.commitTerminalReceipt(fixture.runningRun.complete(fixture.failureReceipt), fixture.failureReceipt, 0))
                }
            }
            assertEquals("55P03", generateSequence<Throwable>(blocked) { it.cause }.filterIsInstance<java.sql.SQLException>().first().sqlState)
            allowReceipt.countDown()
            val observed = replay.get(5, TimeUnit.SECONDS)
            assertEquals(ExploreRunState.RUNNING, observed.state)
            assertNull(observed.failureMessage)
            repeatableRead {
                repository.lockRunForOwnerMutation(fixture.runningRun.id)
                assertTrue(repository.commitTerminalReceipt(fixture.runningRun.complete(fixture.failureReceipt), fixture.failureReceipt, 0))
            }
            val after = kotlinx.coroutines.runBlocking {
                initialTransactions(dataSource).inInitialAdmissionTransaction {
                    repository.lockAdmissionKey(fixture.workspaceId, fixture.runningRun.idempotencyKey)
                    checkNotNull(repository.findByAdmissionKey(fixture.workspaceId, fixture.runningRun.idempotencyKey))
                }
            }
            assertEquals(ExploreRunState.FAILED, after.state)
            assertEquals(fixture.failureReceipt.failureMessage, after.failureMessage)
        } finally {
            allowReceipt.countDown()
            executor.shutdownNow()
        }
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

    private fun <T> repeatableRead(block: () -> T): T = checkNotNull(
        TransactionTemplate(transactionManager).apply {
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }.execute { block() },
    )

    private fun newActivityReader(source: DataSource, dispatcher: CoroutineDispatcher): PostgresExploreActivityReadPort {
        val readOnly = TransactionTemplate(DataSourceTransactionManager(source)).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            timeout = 5
        }
        return PostgresExploreActivityReadPort(
            source,
            SpringExploreTransactionPort(readOnly, dispatcher),
            Duration.ofSeconds(4),
            Duration.ofSeconds(1),
        )
    }

    private companion object {
        @JvmField
        @RegisterExtension
        val postgres: TargetExplorePostgres = TargetExplorePostgres
    }
}

private class ObservingActivityDataSource(
    target: DataSource,
    private val callerThread: Thread,
) : org.springframework.jdbc.datasource.DelegatingDataSource(target) {
    var activityQueries = 0
        private set

    override fun getConnection(): Connection {
        val connection = checkNotNull(super.getConnection())
        lateinit var boundConnection: Connection
        boundConnection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, arguments ->
            val sql = arguments?.firstOrNull() as? String
            if (method.name == "prepareStatement" && sql?.contains("FROM explore.runs") == true) {
                assertTrue(Thread.currentThread() !== callerThread)
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
                assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                assertTrue(TransactionSynchronizationManager.hasResource(this))
                val currentConnection = DataSourceUtils.getConnection(this)
                try {
                    assertTrue(currentConnection === boundConnection)
                } finally {
                    DataSourceUtils.releaseConnection(currentConnection, this)
                }
                assertTrue(!connection.autoCommit && connection.isReadOnly)
                assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.transactionIsolation)
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT current_setting('transaction_read_only'), current_setting('statement_timeout'), current_setting('lock_timeout')",
                    ).use { rows ->
                        assertTrue(rows.next())
                        assertEquals("on", rows.getString(1))
                        assertEquals("4s", rows.getString(2))
                        assertEquals("1s", rows.getString(3))
                    }
                }
                activityQueries += 1
            }
            try {
                method.invoke(connection, *(arguments ?: emptyArray()))
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        } as Connection
        return boundConnection
    }
}

private class StatementCountingDataSource(target: DataSource) : org.springframework.jdbc.datasource.DelegatingDataSource(target) {
    var preparedStatements = 0
        private set

    override fun getConnection(): Connection {
        val connection = checkNotNull(super.getConnection())
        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, arguments ->
            if (method.name == "prepareStatement") preparedStatements += 1
            try {
                method.invoke(connection, *(arguments ?: emptyArray()))
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        } as Connection
    }
}

private class PausingReceiptDataSource(
    target: DataSource,
    private val receiptQueryReached: CountDownLatch,
    private val settlementCompleted: CountDownLatch,
) : org.springframework.jdbc.datasource.DelegatingDataSource(target) {
    override fun getConnection(): Connection = pauseBeforeReceiptQuery(checkNotNull(super.getConnection()))

    private fun pauseBeforeReceiptQuery(connection: Connection): Connection = Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf(Connection::class.java),
    ) { _, method, arguments ->
        val sql = arguments?.firstOrNull() as? String
        if (
            method.name == "prepareStatement" &&
            sql?.contains("FROM explore.terminal_receipts") == true &&
            sql.contains("WHERE run_id = ?")
        ) {
            receiptQueryReached.countDown()
            check(settlementCompleted.await(5, TimeUnit.SECONDS))
        }
        try {
            method.invoke(connection, *(arguments ?: emptyArray()))
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    } as Connection
}

private class Fixture {
    val workspaceId = WorkspaceId.generate()
    val owner = PrincipalId.generate()
    private val now = Instant.parse("2026-08-20T00:00:00Z")
    val settlementKey = "terminal-${ExploreRunId.generate()}"
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
    val semanticRun = cc.monomer.explore.domain.ExploreRun(
        id = ExploreRunId.generate(),
        kind = cc.monomer.explore.domain.ExploreRun.Kind.Semantic(query = cc.monomer.semantic.api.SemanticQuerySpec(
            metrics = listOf(SemanticElementName("revenue")),
            groupBys = emptyList(),
            metricTimeRange = null,
            filters = emptyList(),
            orderBy = emptyList(),
            rowLimit = 100,
        ), outputColumnDisplays = listOf(cc.monomer.semantic.api.SemanticOutputColumnDisplay(
            cc.monomer.semantic.api.SemanticResultColumnKey("metric.revenue"),
            cc.monomer.semantic.api.MetricDisplay.Currency(2, cc.monomer.semantic.api.MetricDisplayCurrency.USD),
        )), reportingTimeZone = java.time.ZoneId.of("Asia/Seoul")),
        idempotencyKey = "semantic-${ExploreRunId.generate()}",
        ownerPrincipalId = owner,
        credentialSurface = cc.monomer.identity.api.CredentialSurface.AGENT,
        agentClientId = cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"),
        connectorBinding = binding,
        requestDigest = ExploreDigest("sha256:${"b".repeat(64)}"),
        state = ExploreRunState.RUNNING,
        visibleResult = null,
        failureCode = null,
        failureMessage = null,
        revision = 0,
        createdAt = now,
        updatedAt = now,
    )
    val successReceipt = ExploreTerminalReceipt(
        workspaceId = workspaceId,
        settlementIdempotencyKey = settlementKey,
        runId = runningRun.id,
        terminalState = ExploreRunState.SUCCEEDED,
        failureCode = null,
        failureMessage = null,
        result = ExploreResultView(
            runId = runningRun.id,
            objectKey = "artifacts/workspaces/$workspaceId/explore/runs/${runningRun.id}/result.arrow",
            objectEtag = "etag",
            byteSize = 1u,
            mediaType = "application/vnd.apache.arrow.file",
            rowCount = 0u,
            columnCount = 1,
            retention = ExploreResultRetention(now.plusSeconds(300)),
            ownerPrincipalId = owner,
            activatedAt = now,
        ),
        settledAt = now,
    )
    val failureKey = "failure-${ExploreRunId.generate()}"
    val failureReceipt = ExploreTerminalReceipt(
        workspaceId = workspaceId,
        settlementIdempotencyKey = failureKey,
        runId = runningRun.id,
        terminalState = ExploreRunState.FAILED,
        failureCode = cc.monomer.explore.api.ExploreRunFailureCode.EXECUTION_FAILED,
        failureMessage = "The warehouse rejected the query.",
        result = null,
        settledAt = now,
    )
}
