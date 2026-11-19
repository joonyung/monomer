package cc.monomer.catalog.adapter.outbound.postgres

import cc.monomer.catalog.application.CatalogTransactionPort
import java.time.Duration
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager

data class CatalogTransactionTimeouts(
    val transaction: Duration,
    val statement: Duration,
    val lock: Duration,
)

class SpringCatalogTransactionPort(
    private val dataSource: DataSource,
    private val transactionOperations: TransactionOperations,
    private val readOnlyTransactionOperations: TransactionOperations,
    private val blockingDispatcher: CoroutineDispatcher,
    private val timeouts: CatalogTransactionTimeouts,
) : CatalogTransactionPort {
    init {
        requireTransactionContract(transactionOperations, timeouts)
        requireTransactionContract(readOnlyTransactionOperations, timeouts)
        val readOnlyDefinition = readOnlyTransactionOperations as TransactionDefinition
        require(readOnlyDefinition.isReadOnly) { "Catalog read transactions must be read-only" }
        require(readOnlyDefinition.isolationLevel == TransactionDefinition.ISOLATION_REPEATABLE_READ) {
            "Catalog read transactions must read one REPEATABLE_READ snapshot"
        }
    }

    override suspend fun <T> inTransaction(block: () -> T): T =
        withContext(blockingDispatcher) {
            val nested = TransactionSynchronizationManager.isActualTransactionActive()
            if (nested) {
                requireExactTransaction()
                check(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel() ==
                    (transactionOperations as TransactionDefinition).isolationLevel) {
                    "Catalog cannot join a transaction with a different isolation level"
                }
            }
            requireNotNull(
                transactionOperations.execute {
                    requireExactTransaction()
                    if (!nested) applyDatabaseTimeouts()
                    TransactionValue(block())
                },
            ).value
        }

    override suspend fun <T> inReadOnlyTransaction(block: () -> T): T =
        withContext(blockingDispatcher) {
            val nested = TransactionSynchronizationManager.isActualTransactionActive()
            if (nested) {
                requireExactTransaction()
                check(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel() ==
                    (readOnlyTransactionOperations as TransactionDefinition).isolationLevel) {
                    "Catalog reads cannot join a transaction with a different isolation level"
                }
            }
            requireNotNull(
                readOnlyTransactionOperations.execute {
                    requireExactTransaction()
                    if (!nested) applyDatabaseTimeouts()
                    TransactionValue(block())
                },
            ).value
        }

    private fun requireExactTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Catalog transaction requires an active owner transaction"
        }
        check(TransactionSynchronizationManager.hasResource(dataSource)) {
            "Catalog transaction requires the transaction bound to the exact DataSource"
        }
    }

    private fun applyDatabaseTimeouts() {
        val connection = DataSourceUtils.getConnection(dataSource)
        try {
            connection.prepareStatement(
                """
                SELECT
                    set_config('statement_timeout', ?, true),
                    set_config('lock_timeout', ?, true)
                """.trimIndent(),
            ).use { statement ->
                statement.queryTimeout = timeouts.transaction.toPositiveCeilingSeconds()
                statement.setString(1, "${timeouts.statement.toMillis()}ms")
                statement.setString(2, "${timeouts.lock.toMillis()}ms")
                statement.execute()
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }
}

private data class TransactionValue<T>(
    val value: T,
)

private fun requireTransactionContract(
    transactionOperations: TransactionOperations,
    timeouts: CatalogTransactionTimeouts,
) {
    require(timeouts.transaction.toMillis() > 0)
    require(timeouts.statement.toMillis() > 0 && timeouts.statement <= timeouts.transaction)
    require(timeouts.lock.toMillis() > 0 && timeouts.lock <= timeouts.statement)
    val definition = requireNotNull(transactionOperations as? TransactionDefinition) {
        "Catalog transaction operations must expose TransactionDefinition"
    }
    require(definition.propagationBehavior == TransactionDefinition.PROPAGATION_REQUIRED) {
        "Catalog transaction operations must use PROPAGATION_REQUIRED"
    }
    require(definition.timeout == timeouts.transaction.toPositiveCeilingSeconds()) {
        "Catalog transaction timeout must match the bounded transaction contract"
    }
}

private fun Duration.toPositiveCeilingSeconds(): Int =
    ((toMillis() + 999) / 1_000)
        .coerceIn(1, Int.MAX_VALUE.toLong())
        .toInt()
