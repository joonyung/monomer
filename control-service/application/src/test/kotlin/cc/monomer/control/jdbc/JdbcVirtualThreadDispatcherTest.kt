package cc.monomer.control.jdbc

import cc.monomer.pages.adapter.outbound.postgres.SpringTransactionRunner
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.Connection
import javax.sql.DataSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionTemplate

class JdbcVirtualThreadDispatcherTest {
    private val connection = mockk<Connection>(relaxed = true).also {
        every { it.autoCommit } returns true
    }
    private val source = mockk<DataSource>().also { every { it.connection } returns connection }
    private val dispatcher = JdbcVirtualThreadDispatcher(source)
    private val template = TransactionTemplate(DataSourceTransactionManager(source))

    @Test
    fun `root dispatch uses a virtual thread and nested dispatch retains one connection`() = runBlocking {
        withContext(dispatcher) {
            assertThat(Thread.currentThread().isVirtual).isTrue()
            template.execute {
                val ownerThread = Thread.currentThread()
                runBlocking {
                    SpringTransactionRunner(template, template, dispatcher).readOnly {
                        assertThat(Thread.currentThread()).isSameAs(ownerThread)
                        assertThat(DataSourceUtils.getConnection(source)).isSameAs(connection)
                    }
                }
            }
        }
        verify(exactly = 1) { source.connection }
        verify(exactly = 1) { connection.commit() }
        verify(exactly = 1) { connection.close() }
    }

    @Test
    fun `a bound connection without an active transaction is rejected`() {
        org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(source, org.springframework.jdbc.datasource.ConnectionHolder(connection))
        try {
            assertThatThrownBy { runBlocking { withContext(dispatcher) { error("must not run") } } }
                .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("unowned")
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.unbindResource(source)
        }
        verify(exactly = 0) { source.connection }
    }

    @Test
    fun `a foreign DataSource transaction fails before allocating another connection`() {
        val foreign = JdbcVirtualThreadDispatcher(mockk<DataSource>())
        assertThatThrownBy {
            template.execute { runBlocking { withContext(foreign) { error("must not run") } } }
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("cannot cross")
        verify(exactly = 1) { connection.rollback() }
    }
}
