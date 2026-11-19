package cc.monomer.connector.adapter.outbound.postgres

import cc.monomer.connector.application.ConnectorTransactionOutcomeUnknownException
import cc.monomer.connector.application.ConnectorTransactionPort
import cc.monomer.connector.application.ConnectorTransactionRolledBackException
import java.time.Duration
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager

data class ConnectorTransactionTimeouts(
    val transaction: Duration,
    val statement: Duration,
    val lock: Duration,
)

class SpringConnectorTransactionPort(
    private val dataSource: DataSource,
    private val transactionOperations: TransactionOperations,
    private val blockingDispatcher: CoroutineDispatcher,
    private val timeouts: ConnectorTransactionTimeouts,
) : ConnectorTransactionPort {
    init {
        requireTransactionContract(transactionOperations, timeouts)
    }

    override suspend fun <T> inTransaction(block: () -> T): T =
        withContext(blockingDispatcher) { executeTransaction(block) }

    private fun <T> executeTransaction(block: () -> T): T {
        var callbackCompleted = false
        try {
            return requireNotNull(
                transactionOperations.execute {
                    requireExactTransaction()
                    applyDatabaseTimeouts()
                    val value = block()
                    callbackCompleted = true
                    TransactionValue(value)
                },
            ).value
        } catch (failure: ConnectorTransactionOutcomeUnknownException) {
            throw failure
        } catch (failure: ConnectorTransactionRolledBackException) {
            throw failure
        } catch (failure: RuntimeException) {
            if (callbackCompleted) {
                throw ConnectorTransactionOutcomeUnknownException(failure)
            }
            throw ConnectorTransactionRolledBackException(failure)
        }
    }

    private fun requireExactTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Connector transaction requires an active owner transaction"
        }
        check(TransactionSynchronizationManager.hasResource(dataSource)) {
            "Connector transaction requires the transaction bound to the exact DataSource"
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
    timeouts: ConnectorTransactionTimeouts,
) {
    require(timeouts.transaction.toMillis() > 0)
    require(timeouts.statement.toMillis() > 0 && timeouts.statement <= timeouts.transaction)
    require(timeouts.lock.toMillis() > 0 && timeouts.lock <= timeouts.statement)
    val definition = requireNotNull(transactionOperations as? TransactionDefinition) {
        "Connector transaction operations must expose TransactionDefinition"
    }
    require(definition.propagationBehavior == TransactionDefinition.PROPAGATION_REQUIRED) {
        "Connector transaction operations must use PROPAGATION_REQUIRED"
    }
    require(definition.timeout == timeouts.transaction.toPositiveCeilingSeconds()) {
        "Connector transaction timeout must match the bounded transaction contract"
    }
}

internal fun Duration.toPositiveCeilingSeconds(): Int =
    ((toMillis() + 999) / 1_000)
        .coerceIn(1, Int.MAX_VALUE.toLong())
        .toInt()
