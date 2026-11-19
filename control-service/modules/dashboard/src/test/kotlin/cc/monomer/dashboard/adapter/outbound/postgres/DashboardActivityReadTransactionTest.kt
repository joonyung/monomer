package cc.monomer.dashboard.adapter.outbound.postgres

import cc.monomer.identity.api.WorkspaceId
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.util.concurrent.Executors
import javax.sql.DataSource
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DashboardActivityReadTransactionTest {
    @Test
    fun `every activity read uses a bounded read-only owner transaction on the JDBC dispatcher`() {
        val callerThread = Thread.currentThread()
        val events = mutableListOf<String>()
        var autoCommit = true
        var readOnly = false
        var timeoutApplied = false
        var connectionAcquisitions = 0
        lateinit var dataSource: DataSource
        fun assertReadTransaction() {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            assertTrue(TransactionSynchronizationManager.hasResource(dataSource))
            assertFalse(autoCommit)
            assertTrue(readOnly)
            assertNotSame(callerThread, Thread.currentThread())
        }
        val emptyRows = proxy<ResultSet> { method, _ ->
            when (method) {
                "next" -> false
                "close" -> null
                else -> error(method)
            }
        }
        val connection = proxy<Connection> { method, arguments ->
            when (method) {
                "getAutoCommit" -> autoCommit
                "setAutoCommit" -> {
                    autoCommit = arguments[0] as Boolean
                    if (!autoCommit) {
                        timeoutApplied = false
                        events += "transaction"
                    }
                    null
                }
                "isReadOnly" -> readOnly
                "setReadOnly" -> { readOnly = arguments[0] as Boolean; null }
                "createStatement" -> proxy<Statement> { statementMethod, values ->
                    when (statementMethod) {
                        "execute" -> {
                            assertReadTransaction()
                            assertEquals("SET LOCAL statement_timeout = '5s'", values[0])
                            timeoutApplied = true
                            events += "timeout"
                            false
                        }
                        "close" -> null
                        else -> error(statementMethod)
                    }
                }
                "prepareStatement" -> proxy<PreparedStatement> { statementMethod, _ ->
                    when (statementMethod) {
                        "executeQuery" -> {
                            assertReadTransaction()
                            assertTrue(timeoutApplied)
                            events += "query"
                            emptyRows
                        }
                        "setObject", "setBoolean", "setInt", "close" -> null
                        else -> error(statementMethod)
                    }
                }
                "commit", "close" -> { events += method; null }
                else -> error(method)
            }
        }
        dataSource = proxy<DataSource> { method, _ ->
            if (method == "getConnection") {
                connectionAcquisitions++
                connection
            } else error(method)
        }
        val transaction = TransactionTemplate(DataSourceTransactionManager(dataSource)).apply {
            isReadOnly = true
            timeout = 5
        }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val reader = PostgresDashboardActivityReadPort(dataSource, transaction, dispatcher)
            val workspace = WorkspaceId(Uuid.random())
            runBlocking {
                assertEquals(emptyList(), reader.readChanges(workspace, null, null, null, null, null, 1, true))
                assertEquals(emptyList(), reader.readSessions(workspace, true, null, null, null, null, null, 1, emptySet()))
                assertEquals(emptyList(), reader.readDraftRuns(workspace, false, null, null, null, null, null, 1, emptySet()))
                assertEquals(emptyList(), reader.readRecentAgentClients(workspace, 1))
            }
        }
        assertEquals(4, connectionAcquisitions)
        assertEquals(List(4) { listOf("transaction", "timeout", "query", "commit", "close") }.flatten(), events)
        assertTrue(autoCommit)
        assertFalse(readOnly)
    }

    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { instance, method, arguments ->
            when (method.name) {
                "equals" -> instance === arguments?.single()
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> T::class.java.simpleName
                else -> call(method.name, arguments ?: emptyArray())
            }
        } as T
}
