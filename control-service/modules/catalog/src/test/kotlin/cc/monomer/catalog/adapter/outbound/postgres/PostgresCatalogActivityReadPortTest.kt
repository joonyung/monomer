@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.adapter.outbound.postgres

import cc.monomer.identity.api.WorkspaceId
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Duration
import java.util.concurrent.Executors
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

class PostgresCatalogActivityReadPortTest {
    @Test
    fun `activity and recent agent reads execute in bounded read-only owner transactions off the caller thread`() = runBlocking {
        val callerThread = Thread.currentThread()
        var autoCommit = true
        val connection = mockk<Connection>(relaxed = true)
        every { connection.autoCommit } answers { autoCommit }
        every { connection.setAutoCommit(any()) } answers { autoCommit = firstArg() }
        every { connection.transactionIsolation } returns Connection.TRANSACTION_READ_COMMITTED
        val dataSource = mockk<DataSource>()
        every { dataSource.connection } returns connection
        val rows = mockk<ResultSet>(relaxed = true)
        every { rows.next() } returns false
        val statement = mockk<PreparedStatement>(relaxed = true)
        every { connection.prepareStatement(any<String>()) } returns statement
        every { statement.connection } returns connection
        every { statement.executeQuery() } answers {
            assertNotSame(callerThread, Thread.currentThread())
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            assertEquals(Connection.TRANSACTION_REPEATABLE_READ, TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
            assertSame(connection, DataSourceUtils.getConnection(dataSource))
            assertFalse(connection.autoCommit)
            rows
        }
        val transactionManager = DataSourceTransactionManager(dataSource)
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val transactions = SpringCatalogTransactionPort(
                dataSource,
                TransactionTemplate(transactionManager).apply { timeout = 5 },
                TransactionTemplate(transactionManager).apply {
                    timeout = 5
                    isReadOnly = true
                    isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
                },
                dispatcher,
                CatalogTransactionTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(4), Duration.ofSeconds(1)),
            )
            val activity = PostgresCatalogActivityReadPort(dataSource, transactions)
            val workspaceId = WorkspaceId.generate()
            for (refresh in listOf(false, true)) {
                assertTrue(activity.readActivity(workspaceId, refresh, true, null, null, null, null, null, 10, emptySet()).isEmpty())
                assertTrue(activity.readRecentAgentClients(workspaceId, refresh, 500).isEmpty())
            }
        }
        verify(exactly = 4) { dataSource.connection; connection.commit(); statement.executeQuery() }
        verify(exactly = 4) { connection.prepareStatement(match<String> { it.contains("statement_timeout") }) }
        verify(exactly = 4) { statement.setString(1, "4000ms"); statement.setString(2, "1000ms") }
    }
}
