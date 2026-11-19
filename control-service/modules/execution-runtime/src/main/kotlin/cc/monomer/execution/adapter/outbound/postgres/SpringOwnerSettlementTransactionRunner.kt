package cc.monomer.execution.adapter.outbound.postgres

import cc.monomer.execution.application.ExecutionRuntimeTransactionRunner
import java.sql.Connection
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

class SpringOwnerSettlementTransactionRunner(
    private val dataSource: DataSource,
    private val transactionTimeoutSeconds: Int,
    private val blockingDispatcher: CoroutineDispatcher,
) : ExecutionRuntimeTransactionRunner {
    private val transactionTemplate =
        TransactionTemplate(DataSourceTransactionManager(dataSource)).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            timeout = transactionTimeoutSeconds
        }

    init {
        require(transactionTimeoutSeconds > 0) {
            "owner settlement transaction timeout must be positive"
        }
    }

    override suspend fun <T> run(block: () -> T): T = withContext(blockingDispatcher) {
        rejectAmbientExecution()
        try { requireNotNull(
            transactionTemplate.execute {
                requireOwnerSettlementTransaction()
                block()
            },
        ) } catch (failure: Exception) {
            if (generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<java.sql.SQLException>()
                    .any { it.sqlState == "40001" || it.sqlState == "40P01" }) {
                throw cc.monomer.execution.application.OwnerSettlementConcurrentConflictException()
            }
            throw failure
        }
    }

    private fun rejectAmbientExecution() {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "owner settlement transaction rejects an ambient transaction"
        }
        check(!TransactionSynchronizationManager.isSynchronizationActive()) {
            "owner settlement transaction rejects ambient transaction synchronization"
        }
        check(!TransactionSynchronizationManager.hasResource(dataSource)) {
            "owner settlement transaction rejects an ambient Control DataSource connection"
        }
    }

    private fun requireOwnerSettlementTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "owner settlement transaction requires an active transaction"
        }
        check(TransactionSynchronizationManager.hasResource(dataSource)) {
            "owner settlement transaction requires the Control DataSource transaction"
        }
        val connection = DataSourceUtils.getConnection(dataSource)
        try {
            check(
                connection.transactionIsolation == Connection.TRANSACTION_REPEATABLE_READ,
            ) {
                "owner settlement transaction requires REPEATABLE READ"
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }

}
