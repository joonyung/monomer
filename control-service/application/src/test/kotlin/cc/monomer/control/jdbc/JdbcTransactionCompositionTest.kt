@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.jdbc

import cc.monomer.catalog.adapter.outbound.postgres.CatalogTransactionTimeouts
import cc.monomer.catalog.adapter.outbound.postgres.SpringCatalogTransactionPort
import cc.monomer.connector.adapter.outbound.postgres.ConnectorExecutionAccessReadTimeouts
import cc.monomer.connector.adapter.outbound.postgres.SpringConnectorCurrentReadPort
import cc.monomer.connector.adapter.outbound.postgres.SpringConnectorExecutionAccessReadPort
import cc.monomer.control.dashboard.SpringDashboardTransactionPort
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.domain.port.ConsistentSnapshotRetryException
import cc.monomer.identity.outbound.postgres.SpringConsistentSnapshotTransactionRunner
import cc.monomer.identity.outbound.postgres.SpringTransactionRunner as IdentityRunner
import cc.monomer.pages.adapter.outbound.postgres.SpringTransactionRunner as PagesRunner
import cc.monomer.semantic.adapter.outbound.postgres.SemanticTransactionTimeouts
import cc.monomer.semantic.adapter.outbound.postgres.SpringSemanticTransactionPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.Connection
import java.time.Duration
import javax.sql.DataSource
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

class JdbcTransactionCompositionTest {
    private val connection = mockk<Connection>(relaxed = true).also {
        every { it.autoCommit } returns true
        every { it.transactionIsolation } returns Connection.TRANSACTION_REPEATABLE_READ
    }
    private val source = mockk<DataSource>().also { every { it.connection } returns connection }
    private val dispatcher = JdbcVirtualThreadDispatcher(source)
    private val manager = DataSourceTransactionManager(source)
    private fun template(isolation: Int = Connection.TRANSACTION_REPEATABLE_READ) = TransactionTemplate(manager).apply {
        isolationLevel = isolation
        timeout = 5
    }
    private val snapshot = SpringConsistentSnapshotTransactionRunner(template(), dispatcher)
    private val semantic = SpringSemanticTransactionPort(
        source, template(), template(Connection.TRANSACTION_READ_COMMITTED), dispatcher,
        SemanticTransactionTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(4), Duration.ofSeconds(1)),
    )
    private val catalog = SpringCatalogTransactionPort(
        source, template(Connection.TRANSACTION_READ_COMMITTED), template().apply { isReadOnly = true }, dispatcher,
        CatalogTransactionTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(4), Duration.ofSeconds(1)),
    )

    @Test
    fun `bounded semantic batch rejects an ambient owner transaction`() = runBlocking<Unit> {
        snapshot.run {
            kotlin.test.assertFailsWith<IllegalStateException> {
                semantic.inSnapshotTransaction(java.time.Instant.now().plusSeconds(2)) { error("must reject before reading") }
            }
        }
    }

    @Test
    fun `bounded semantic snapshot holds exact thread for suspend connector batch and closes before cpu`() = runBlocking<Unit>(CoroutineName("batch")) {
        val queries = mockk<cc.monomer.connector.application.ConnectorCurrentReadQueryPort>()
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        var owner: Thread? = null
        every { queries.findCurrentByWorkspaceAndIds(workspaceId, setOf(connectorId)) } answers {
            assertThat(Thread.currentThread()).isSameAs(owner)
            assertThat(DataSourceUtils.getConnection(source)).isSameAs(connection)
            emptyList()
        }
        val connector = SpringConnectorCurrentReadPort(source, template().apply { isReadOnly = true }, dispatcher, queries,
            ConnectorExecutionAccessReadTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(4)))
        semantic.inSnapshotTransaction(java.time.Instant.now().plusSeconds(2)) {
            owner = Thread.currentThread()
            assertThat(owner!!.isVirtual).isTrue()
            assertThat(coroutineContext[CoroutineName]?.name).isEqualTo("batch")
            connector.findByWorkspaceAndIds(workspaceId, setOf(connectorId))
            assertThat(Thread.currentThread()).isSameAs(owner)
        }
        verify(exactly = 1) { connection.commit() }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
        }
    }

    @Test
    fun `snapshot identity pages dashboard semantic and connector reads share one owner connection`() = runBlocking(CoroutineName("request")) {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        snapshot.run {
            val owner = Thread.currentThread()
            assertThat(owner.isVirtual).isTrue()
            assertThat(coroutineContext[CoroutineName]?.name).isEqualTo("request")
            fun assertOwner() {
                assertThat(Thread.currentThread()).isSameAs(owner)
                assertThat(DataSourceUtils.getConnection(source)).isSameAs(connection)
            }
            IdentityRunner(template(), template(), dispatcher).readOnly { assertOwner() }
            PagesRunner(template(), template(), dispatcher).readOnly { assertOwner() }
            SpringDashboardTransactionPort(template(), dispatcher).inTransaction { assertOwner() }
            semantic.inSnapshotTransaction { assertOwner() }
            val readTemplate = template().apply { isReadOnly = true }
            val timeouts = ConnectorExecutionAccessReadTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(4))
            val currentQueries = mockk<cc.monomer.connector.application.ConnectorCurrentReadQueryPort>()
            every { currentQueries.listCurrentByWorkspace(workspaceId) } answers { assertOwner(); emptyList() }
            SpringConnectorCurrentReadPort(source, readTemplate, dispatcher, currentQueries, timeouts)
                .listByWorkspace(workspaceId)
            val accessQueries = mockk<cc.monomer.connector.application.ConnectorExecutionAccessQueryPort>()
            every { accessQueries.findCurrentExecutionAccessCandidate(workspaceId, connectorId) } answers { assertOwner(); null }
            SpringConnectorExecutionAccessReadPort(source, readTemplate, dispatcher, accessQueries, timeouts)
                .readCurrentExecutionAccessCandidate(workspaceId, connectorId)
        }
        verify(exactly = 1) { source.connection; connection.commit(); connection.close() }
        // Participating readers must not overwrite the snapshot owner's statement timeout.
        verify(exactly = 1) { connection.prepareStatement(match<String> { it.contains("statement_timeout") }) }
    }

    @Test
    fun `catalog joins a compatible owner without replacing its timeouts`() {
        template(Connection.TRANSACTION_READ_COMMITTED).execute {
            val owner = Thread.currentThread()
            runBlocking { catalog.inTransaction { assertThat(Thread.currentThread()).isSameAs(owner) } }
        }
        verify(exactly = 1) { source.connection; connection.commit() }
        verify(exactly = 0) { connection.prepareStatement(any<String>()) }
    }

    @Test
    fun `catalog reads join an active snapshot owner without replacing its timeouts`() {
        template(Connection.TRANSACTION_REPEATABLE_READ).execute {
            val owner = Thread.currentThread()
            runBlocking { catalog.inReadOnlyTransaction { assertThat(Thread.currentThread()).isSameAs(owner) } }
        }
        verify(exactly = 1) { source.connection; connection.commit() }
        verify(exactly = 0) { connection.prepareStatement(any<String>()) }
    }

    @Test
    fun `catalog reads reject an owner that cannot keep one snapshot before reading`() {
        var read = false
        assertThatThrownBy {
            template(Connection.TRANSACTION_READ_COMMITTED).execute {
                runBlocking { catalog.inReadOnlyTransaction { read = true } }
            }
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("isolation")
        assertThat(read).isFalse()
        verify(exactly = 1) { source.connection; connection.rollback() }
        verify(exactly = 0) { connection.commit(); connection.prepareStatement(any<String>()) }
    }

    @Test
    fun `catalog reads open one read-only snapshot with database timeouts`() {
        runBlocking {
            catalog.inReadOnlyTransaction {
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue()
                assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ)
            }
        }
        verify(exactly = 1) { source.connection; connection.commit() }
        verify(exactly = 1) { connection.prepareStatement(match<String> { it.contains("statement_timeout") }) }
    }

    @Test
    fun `catalog read transactions must be read-only snapshots`() {
        assertThatThrownBy {
            SpringCatalogTransactionPort(
                source, template(Connection.TRANSACTION_READ_COMMITTED), template(), dispatcher,
                CatalogTransactionTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(4), Duration.ofSeconds(1)),
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("read-only")
    }

    @Test
    fun `catalog rejects incompatible snapshot before running mutation`() {
        assertThatThrownBy { runBlocking { snapshot.run { catalog.inTransaction { error("must not mutate") } } } }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("isolation")
        verify(exactly = 1) { source.connection; connection.rollback() }
    }

    @Test
    fun `only snapshot owner retries and reacquires the connection for the whole operation`() = runBlocking {
        var outerAttempts = 0
        var innerAttempts = 0
        snapshot.run {
            outerAttempts++
            snapshot.run {
                innerAttempts++
                if (innerAttempts == 1) throw ConsistentSnapshotRetryException("retry owner")
            }
        }
        assertThat(outerAttempts).isEqualTo(2)
        assertThat(innerAttempts).isEqualTo(2)
        verify(exactly = 2) { source.connection; connection.close() }
        verify(exactly = 1) { connection.rollback(); connection.commit() }
    }

    @Test
    fun `snapshot cancellation rolls back and releases its connection`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            snapshot.run {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        job.cancelAndJoin()
        verify(exactly = 1) { connection.rollback(); connection.close() }
        verify(exactly = 0) { connection.commit() }
    }

    @Test
    fun `caught participating semantic failure still rolls back the owner`() {
        assertThatThrownBy {
            runBlocking {
                snapshot.run {
                    try { semantic.inSnapshotTransaction { throw IllegalArgumentException("failed write") } }
                    catch (_: IllegalArgumentException) { /* Outer work cannot commit a failed participant. */ }
                }
            }
        }.isInstanceOf(UnexpectedRollbackException::class.java)
        verify(exactly = 1) { connection.rollback() }
        verify(exactly = 0) { connection.commit() }
    }

    @Test
    fun `operations owner queries refuse nested independent transactions`() {
        val independent = template().apply {
            isReadOnly = true
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }
        val access = mockk<cc.monomer.identity.api.OperationsAccess>()
        val explore = cc.monomer.explore.adapter.outbound.postgres.PostgresExploreOperationsQueries(source, independent, dispatcher)
        val connector = cc.monomer.connector.adapter.outbound.postgres.PostgresConnectorOperationsQueries(source, independent, dispatcher)
        val dashboard = cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardOperationsQueries(source, independent, dispatcher)
        val semantic = cc.monomer.semantic.adapter.outbound.postgres.PostgresSemanticOperationsQueries(source, independent, dispatcher)
        val week = java.time.Instant.parse("2026-09-28T00:00:00Z")
        val reads = listOf<suspend () -> Any?>(
            { explore.run(access, "unused") },
            { connector.counts(access, emptySet()) },
            { dashboard.journeyActivity(access, emptySet(), week, week.plus(Duration.ofDays(7))) },
            { semantic.journeyActivity(access, emptySet(), week, week.plus(Duration.ofDays(7))) },
        )
        for (read in reads) {
            assertThatThrownBy { runBlocking { snapshot.run { read() } } }
                .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("independent root")
        }
        verify(exactly = reads.size) { source.connection; connection.rollback() }
        verify(exactly = reads.size) { connection.prepareStatement(match<String> { it.contains("statement_timeout") }) }
    }

    @Test
    fun `independent operations transaction is rejected inside an owner`() {
        val independent = template().apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
        assertThatThrownBy {
            runBlocking { snapshot.run { IdentityRunner(independent, independent, dispatcher).readOnly { error("must not execute") } } }
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("independent")
        verify(exactly = 1) { source.connection; connection.rollback() }
    }
}
