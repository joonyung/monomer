package cc.monomer.pages.adapter.outbound.postgres

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.Connection
import javax.sql.DataSource
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionTemplate

class SpringTransactionRunnerTest {
    @Test
    fun `nested Pages reads and writes reuse the outer snapshot connection`() {
        val connection = mockk<Connection>(relaxed = true)
        every { connection.autoCommit } returns true
        val dataSource = mockk<DataSource>()
        every { dataSource.connection } returns connection
        val template = TransactionTemplate(DataSourceTransactionManager(dataSource))
        val runner = SpringTransactionRunner(template, template, kotlinx.coroutines.Dispatchers.Unconfined)
        template.execute {
            val outerThread = Thread.currentThread()
            runBlocking {
                runner.readOnly {
                    assertThat(Thread.currentThread()).isSameAs(outerThread)
                    assertThat(DataSourceUtils.getConnection(dataSource)).isSameAs(connection)
                }
                runner.run { assertThat(Thread.currentThread()).isSameAs(outerThread) }
            }
        }
        verify(exactly = 1) { dataSource.connection }
        verify(exactly = 1) { connection.commit() }
        verify(exactly = 1) { connection.close() }
    }
}
