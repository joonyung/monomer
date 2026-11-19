package cc.monomer.semantic.adapter.outbound.postgres

import cc.monomer.semantic.application.SemanticTransactionPort
import java.time.Duration
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager

data class SemanticTransactionTimeouts(
    val transaction: Duration,
    val statement: Duration,
    val lock: Duration,
)

class SpringSemanticTransactionPort(
    private val dataSource: DataSource,
    private val transactionOperations: TransactionOperations,
    private val mutationTransactionOperations: TransactionOperations,
    private val blockingDispatcher: CoroutineDispatcher,
    private val timeouts: SemanticTransactionTimeouts,
) : SemanticTransactionPort {
    init {
        requireTransactionContract(
            transactionOperations,
            timeouts,
            TransactionDefinition.ISOLATION_REPEATABLE_READ,
        )
        requireTransactionContract(
            mutationTransactionOperations,
            timeouts,
            TransactionDefinition.ISOLATION_READ_COMMITTED,
        )
    }

    override suspend fun <T> inSnapshotTransaction(block: () -> T): T =
        executeSemanticTransaction(
            dataSource = dataSource,
            transactionOperations = transactionOperations,
            blockingDispatcher = blockingDispatcher,
            timeouts = timeouts,
            block = block,
        )

    override suspend fun <T> inSnapshotTransaction(deadline: java.time.Instant, block: suspend () -> T): T {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Semantic batch requires an independent read snapshot"
        }
        val remaining = Duration.between(java.time.Instant.now(), deadline).toMillis()
        if (remaining <= 0) throw java.util.concurrent.TimeoutException("Planning deadline exceeded")
        return kotlinx.coroutines.withTimeout(remaining) {
            val bridgeContext = kotlin.coroutines.coroutineContext.minusKey(kotlin.coroutines.ContinuationInterceptor)
            kotlinx.coroutines.runInterruptible(blockingDispatcher) {
                check(!TransactionSynchronizationManager.isActualTransactionActive())
                requireNotNull(transactionOperations.execute {
                    requireExactTransaction(dataSource)
                    val available = Duration.between(java.time.Instant.now(), deadline)
                    if (available.toMillis() <= 0) throw java.util.concurrent.TimeoutException("Planning deadline exceeded")
                    applyDatabaseTimeouts(dataSource, timeouts.copy(
                        statement = minOf(timeouts.statement, available),
                        lock = minOf(timeouts.lock, available),
                    ))
                    TransactionValue(kotlinx.coroutines.runBlocking(bridgeContext) { block() })
                }).value
            }
        }
    }

    override suspend fun <T> inMutationTransaction(block: () -> T): T =
        executeSemanticTransaction(
            dataSource = dataSource,
            transactionOperations = mutationTransactionOperations,
            blockingDispatcher = blockingDispatcher,
            timeouts = timeouts,
            block = block,
        )
}

private data class TransactionValue<T>(
    val value: T,
)

private fun requireExactTransaction(dataSource: DataSource) {
    check(TransactionSynchronizationManager.isActualTransactionActive()) {
        "Semantic transaction requires an active owner transaction"
    }
    check(TransactionSynchronizationManager.hasResource(dataSource)) {
        "Semantic transaction requires the transaction bound to the exact DataSource"
    }
}

private fun requireTransactionContract(
    transactionOperations: TransactionOperations,
    timeouts: SemanticTransactionTimeouts,
    isolationLevel: Int,
) {
    require(timeouts.transaction.toMillis() > 0)
    require(timeouts.statement.toMillis() > 0 && timeouts.statement <= timeouts.transaction)
    require(timeouts.lock.toMillis() > 0 && timeouts.lock <= timeouts.statement)
    val definition = requireNotNull(transactionOperations as? TransactionDefinition) {
        "Semantic transaction operations must expose TransactionDefinition"
    }
    require(definition.propagationBehavior == TransactionDefinition.PROPAGATION_REQUIRED) {
        "Semantic transaction operations must use PROPAGATION_REQUIRED"
    }
    require(definition.isolationLevel == isolationLevel) {
        "Semantic transaction isolation must be $isolationLevel"
    }
    require(definition.timeout == timeouts.transaction.toPositiveCeilingSeconds()) {
        "Semantic transaction timeout must match the bounded transaction contract"
    }
}

private suspend fun <T> executeSemanticTransaction(
    dataSource: DataSource,
    transactionOperations: TransactionOperations,
    blockingDispatcher: CoroutineDispatcher,
    timeouts: SemanticTransactionTimeouts,
    block: () -> T,
): T {
    return withContext(blockingDispatcher) {
        val nested = hasExactActiveTransaction(dataSource)
        if (nested) requireActiveTransactionIsolation(dataSource, transactionOperations)
        requireNotNull(
            transactionOperations.execute {
                requireExactTransaction(dataSource)
                if (!nested) applyDatabaseTimeouts(dataSource, timeouts)
                TransactionValue(block())
            },
        ).value
    }
}

private fun hasExactActiveTransaction(dataSource: DataSource): Boolean =
    TransactionSynchronizationManager.isActualTransactionActive() &&
        TransactionSynchronizationManager.hasResource(dataSource)

private fun requireActiveTransactionIsolation(
    dataSource: DataSource,
    transactionOperations: TransactionOperations,
) {
    val expectedIsolation = requireNotNull(transactionOperations as? TransactionDefinition) {
        "Semantic transaction operations must expose TransactionDefinition"
    }.isolationLevel
    val connection = DataSourceUtils.getConnection(dataSource)
    try {
        check(connection.transactionIsolation == expectedIsolation) {
            "Semantic active transaction isolation must be $expectedIsolation"
        }
    } finally {
        DataSourceUtils.releaseConnection(connection, dataSource)
    }
}

private fun applyDatabaseTimeouts(
    dataSource: DataSource,
    timeouts: SemanticTransactionTimeouts,
) {
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

private fun Duration.toPositiveCeilingSeconds(): Int =
    ((toMillis() + 999) / 1_000)
        .coerceIn(1, Int.MAX_VALUE.toLong())
        .toInt()
