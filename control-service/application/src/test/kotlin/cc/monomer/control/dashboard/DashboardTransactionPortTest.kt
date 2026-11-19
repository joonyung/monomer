package cc.monomer.control.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager

class DashboardTransactionPortTest {
    @Test
    fun `composition changes dashboard isolation while preserving shared transaction definition`() = runTest {
        var actualDefinition: TransactionDefinition? = null
        val manager = object : PlatformTransactionManager {
            override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
                actualDefinition = DefaultTransactionDefinition(requireNotNull(definition))
                return SimpleTransactionStatus()
            }
            override fun commit(status: TransactionStatus) = Unit
            override fun rollback(status: TransactionStatus) = Unit
        }
        val shared = TransactionTemplate(manager).apply {
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
            timeout = 17
            setName("product-authority")
        }
        DashboardApplicationComposition().dashboardTransactionPort(kotlinx.coroutines.Dispatchers.Unconfined, shared).inTransaction { Unit }
        val dashboard = requireNotNull(actualDefinition)
        assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, dashboard.isolationLevel)
        assertEquals(shared.propagationBehavior, dashboard.propagationBehavior)
        assertEquals(shared.timeout, dashboard.timeout)
        assertEquals(shared.name, dashboard.name)
        assertEquals(shared.isReadOnly, dashboard.isReadOnly)
        assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ, shared.isolationLevel)
    }

    @Test
    fun `active outer transaction keeps dashboard work on the trace transaction thread`() = runTest {
        val operations = RecordingTransactionOperations()
        val port = SpringDashboardTransactionPort(operations, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        val traceTransactionThread = Thread.currentThread()
        val wasActive = TransactionSynchronizationManager.isActualTransactionActive()
        TransactionSynchronizationManager.setActualTransactionActive(true)

        try {
            assertEquals(
                "dashboard mutation",
                port.inTransaction {
                    assertSame(traceTransactionThread, Thread.currentThread())
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
                    "dashboard mutation"
                },
            )
            assertEquals(1, operations.invocations)
            assertSame(traceTransactionThread, operations.callbackThread)
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(wasActive)
        }
    }

    private class RecordingTransactionOperations : TransactionOperations {
        var invocations: Int = 0
        var callbackThread: Thread? = null

        override fun <T> execute(action: TransactionCallback<T>): T {
            invocations += 1
            callbackThread = Thread.currentThread()
            return action.doInTransaction(SimpleTransactionStatus())
        }
    }
}
