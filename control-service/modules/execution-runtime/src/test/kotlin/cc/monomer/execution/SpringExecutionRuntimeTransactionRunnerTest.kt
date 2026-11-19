package cc.monomer.execution

import cc.monomer.execution.adapter.outbound.postgres.SpringExecutionRuntimeTransactionRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate

class SpringExecutionRuntimeTransactionRunnerTest {
    @Test
    fun `confirmed rollback preserves the application failure`() {
        val transactionManager =
            ScriptedTransactionManager(
                commitFailure = null,
                rollbackFailure = null,
            )
        val runner =
            SpringExecutionRuntimeTransactionRunner(
                TransactionTemplate(transactionManager),
                Dispatchers.Unconfined,
            )
        val applicationFailure = IllegalArgumentException("application failure")

        val thrown =
            assertFailsWith<IllegalArgumentException> {
                runBlocking {
                    runner.run<Unit> {
                        throw applicationFailure
                    }
                }
            }

        assertSame(applicationFailure, thrown)
        assertEquals(0, transactionManager.commits)
        assertEquals(1, transactionManager.rollbacks)
    }

    @Test
    fun `commit failure remains a typed unknown transaction outcome`() {
        val commitFailure = TransactionSystemException("commit outcome is unknown")
        val transactionManager =
            ScriptedTransactionManager(
                commitFailure = commitFailure,
                rollbackFailure = null,
            )
        val runner =
            SpringExecutionRuntimeTransactionRunner(
                TransactionTemplate(transactionManager),
                Dispatchers.Unconfined,
            )

        val thrown =
            assertFailsWith<TransactionSystemException> {
                runBlocking { runner.run { "completed body" } }
            }

        assertSame(commitFailure, thrown)
        assertEquals(1, transactionManager.commits)
        assertEquals(0, transactionManager.rollbacks)
    }

    @Test
    fun `rollback failure remains a typed unknown transaction outcome`() {
        val rollbackFailure = TransactionSystemException("rollback outcome is unknown")
        val transactionManager =
            ScriptedTransactionManager(
                commitFailure = null,
                rollbackFailure = rollbackFailure,
            )
        val runner =
            SpringExecutionRuntimeTransactionRunner(
                TransactionTemplate(transactionManager),
                Dispatchers.Unconfined,
            )

        val thrown =
            assertFailsWith<TransactionSystemException> {
                runBlocking {
                    runner.run<Unit> {
                        throw IllegalArgumentException("application failure")
                    }
                }
            }

        assertSame(rollbackFailure, thrown)
        assertEquals(0, transactionManager.commits)
        assertEquals(1, transactionManager.rollbacks)
    }

    @Test
    fun `transaction work runs on the blocking dispatcher instead of the caller thread`() {
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "execution-runtime-jdbc-test") }
        try {
            val runner =
                SpringExecutionRuntimeTransactionRunner(
                    TransactionTemplate(ScriptedTransactionManager(commitFailure = null, rollbackFailure = null)),
                    executor.asCoroutineDispatcher(),
                )

            val transactionThread = runBlocking { runner.run { Thread.currentThread().name } }

            assertTrue(transactionThread.startsWith("execution-runtime-jdbc-test"), transactionThread)
        } finally {
            executor.shutdownNow()
        }
    }

    private class ScriptedTransactionManager(
        private val commitFailure: TransactionSystemException?,
        private val rollbackFailure: TransactionSystemException?,
    ) : PlatformTransactionManager {
        var commits: Int = 0
            private set
        var rollbacks: Int = 0
            private set

        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus =
            SimpleTransactionStatus()

        override fun commit(status: TransactionStatus) {
            commits += 1
            if (commitFailure != null) {
                throw commitFailure
            }
        }

        override fun rollback(status: TransactionStatus) {
            rollbacks += 1
            if (rollbackFailure != null) {
                throw rollbackFailure
            }
        }
    }
}
