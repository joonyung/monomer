package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.domain.port.ConsistentSnapshotRetryException
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager

class SpringConsistentSnapshotTransactionRunnerTest {
    @Test
    fun `identity runners reject transaction managers without an exact JDBC datasource`() {
        val template = TransactionTemplate(mockk<PlatformTransactionManager>())
        assertThatThrownBy {
            SpringTransactionRunner(template, template, kotlinx.coroutines.Dispatchers.Unconfined)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("DataSourceTransactionManager")
        assertThatThrownBy {
            SpringConsistentSnapshotTransactionRunner(template, kotlinx.coroutines.Dispatchers.Unconfined)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("DataSourceTransactionManager")
    }

    @Test
    fun `nested snapshot stays on its owner thread and never retries part of a transaction`() {
        val runner = SpringConsistentSnapshotTransactionRunner(TransactionTemplate(RecordingTransactionManager()), blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        val ownerThread = Thread.currentThread()
        var calls = 0
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            val failure = catchThrowable {
                runBlocking {
                    runner.run {
                        calls++
                        assertThat(Thread.currentThread()).isSameAs(ownerThread)
                        throw ConsistentSnapshotRetryException("restart the outer transaction")
                    }
                }
            }
            assertThat(failure).isInstanceOf(ConsistentSnapshotRetryException::class.java)
            assertThat(calls).isEqualTo(1)
        } finally {
            TransactionSynchronizationManager.clear()
        }
    }

    @Test
    fun `runner retries the whole transaction for serialization and invisible snapshot failures`() = runTest {
        val transactionManager = RecordingTransactionManager()
        val runner = SpringConsistentSnapshotTransactionRunner(
            TransactionTemplate(transactionManager), blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        var actionInvocationCount = 0

        val result = runner.run {
            actionInvocationCount += 1
            when (actionInvocationCount) {
                1 -> throw IllegalStateException(
                    "wrapped serialization failure",
                    SQLException("serialization failure", "40001"),
                )
                2 -> throw ConsistentSnapshotRetryException("winner is outside this snapshot")
                else -> "completed"
            }
        }

        assertThat(result).isEqualTo("completed")
        assertThat(actionInvocationCount).isEqualTo(3)
        assertThat(transactionManager.startedCount).isEqualTo(3)
        assertThat(transactionManager.rolledBackCount).isEqualTo(2)
        assertThat(transactionManager.committedCount).isEqualTo(1)
    }

    @Test
    fun `runner stops after three retryable transaction failures`() = runTest {
        val transactionManager = RecordingTransactionManager()
        val runner = SpringConsistentSnapshotTransactionRunner(
            TransactionTemplate(transactionManager), blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        var actionInvocationCount = 0

        val failure = catchThrowable {
            runBlocking {
                runner.run {
                    actionInvocationCount += 1
                    throw ConsistentSnapshotRetryException("snapshot remains stale")
                }
            }
        }

        assertThat(failure).isInstanceOf(ConsistentSnapshotRetryException::class.java)
        assertThat(actionInvocationCount).isEqualTo(3)
        assertThat(transactionManager.startedCount).isEqualTo(3)
        assertThat(transactionManager.rolledBackCount).isEqualTo(3)
        assertThat(transactionManager.committedCount).isZero()
    }
}

private class RecordingTransactionManager : DataSourceTransactionManager(recordingDataSource()) {
    var startedCount = 0
        private set
    var committedCount = 0
        private set
    var rolledBackCount = 0
        private set

    override fun doBegin(transaction: Any, definition: TransactionDefinition) {
        super.doBegin(transaction, definition)
        startedCount += 1
    }

    override fun doCommit(status: DefaultTransactionStatus) {
        super.doCommit(status)
        committedCount += 1
    }

    override fun doRollback(status: DefaultTransactionStatus) {
        super.doRollback(status)
        rolledBackCount += 1
    }
}

private fun recordingDataSource(): DataSource {
    val connection = mockk<Connection>(relaxed = true).also {
        every { it.autoCommit } returns true
    }
    return mockk<DataSource>().also { every { it.connection } returns connection }
}
