package cc.monomer.control.jdbc

import javax.sql.DataSource
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Dispatch only at JDBC entry. Nested calls retain the exact thread-bound connection. */
class JdbcVirtualThreadDispatcher(private val dataSource: DataSource) : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean {
        val active = TransactionSynchronizationManager.isActualTransactionActive()
        val bound = TransactionSynchronizationManager.hasResource(dataSource)
        check(active == bound) {
            "JDBC dispatch cannot cross an ambient transaction or an unowned DataSource connection"
        }
        return !active
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        Thread.ofVirtual().name("control-jdbc-", 0).start(block)
    }
}

@Configuration
class JdbcDispatcherConfiguration {
    @Bean("jdbcDispatcher")
    fun jdbcDispatcher(productControlDataSource: DataSource): CoroutineDispatcher =
        JdbcVirtualThreadDispatcher(productControlDataSource)
}
