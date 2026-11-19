package cc.monomer.explore.adapter.outbound.postgres

import cc.monomer.explore.api.ExploreInitialAdmissionTransaction
import cc.monomer.explore.api.ExploreResultReadTransaction
import cc.monomer.explore.application.ActiveExploreInitialAdmissionTransactionPort
import cc.monomer.explore.application.ActiveExploreOwnerSettlementTransactionPort
import cc.monomer.explore.application.ExploreInitialAdmissionTransactionPort
import cc.monomer.explore.application.ExploreResultReadTransactionConcurrentConflictException
import cc.monomer.explore.application.ExploreResultReadTransactionPort
import cc.monomer.explore.application.ExploreTransactionPort
import cc.monomer.explore.application.ExploreTransactionConcurrentConflictException
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

class SpringExploreTransactionPort(
    private val transactionOperations: TransactionOperations,
    private val blockingDispatcher: CoroutineDispatcher,
) : ExploreTransactionPort {
    override suspend fun <T> inTransaction(block: () -> T): T =
        withContext(blockingDispatcher) {
            try {
                requireNotNull(transactionOperations.execute { block() })
            } catch (failure: Throwable) {
                if (failure.hasRetryableSqlState()) {
                    throw ExploreTransactionConcurrentConflictException(failure)
                }
                throw failure
            }
        }
}

class SpringActiveExploreInitialAdmissionTransactionPort(
    private val dataSource: DataSource,
) : ActiveExploreInitialAdmissionTransactionPort {
    override fun <T> inActiveInitialAdmissionTransaction(
        block: (ExploreInitialAdmissionTransaction) -> T,
    ): T {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Explore initial admission requires an active composition transaction"
        }
        check(TransactionSynchronizationManager.hasResource(dataSource)) {
            "Explore initial admission requires the exact owner DataSource transaction"
        }
        val connection = DataSourceUtils.getConnection(dataSource)
        return try {
            check(!connection.autoCommit) {
                "Explore initial admission requires a non-autocommit composition transaction"
            }
            check(connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED) {
                "Explore initial admission requires READ COMMITTED"
            }
            block(
                memoizedExploreTransaction(
                    resourceKey =
                        ExploreSpringTransactionResourceKey(
                            transactionKind = INITIAL_ADMISSION_TRANSACTION_KIND,
                            dataSource = dataSource,
                            connection = connection,
                        ),
                    description = "initial admission",
                    create = ::ExploreInitialAdmissionTransaction,
                ),
            )
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }
}

class SpringRetryingExploreInitialAdmissionTransactionPort(
    private val transactionTemplate: TransactionTemplate,
    private val activeTransactionPort: ActiveExploreInitialAdmissionTransactionPort,
    private val maximumAttempts: Int,
    private val blockingDispatcher: CoroutineDispatcher,
) : ExploreInitialAdmissionTransactionPort {
    init {
        require(maximumAttempts > 0)
        require(
            transactionTemplate.isolationLevel ==
                TransactionDefinition.ISOLATION_READ_COMMITTED,
        )
        require(
            transactionTemplate.propagationBehavior ==
                TransactionDefinition.PROPAGATION_REQUIRES_NEW,
        )
    }

    override suspend fun <T> inInitialAdmissionTransaction(
        block: (ExploreInitialAdmissionTransaction) -> T,
    ): T = withContext(blockingDispatcher) { admitInTransaction(block) }

    private fun <T> admitInTransaction(
        block: (ExploreInitialAdmissionTransaction) -> T,
    ): T {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Explore initial admission transaction must start without an ambient transaction"
        }
        var attempt = 1
        while (true) {
            try {
                return requireNotNull(
                    transactionTemplate.execute {
                        activeTransactionPort.inActiveInitialAdmissionTransaction(block)
                    },
                )
            } catch (failure: Exception) {
                if (attempt >= maximumAttempts || !failure.hasRetryableSqlState()) {
                    throw failure
                }
                attempt += 1
            }
        }
    }
}

class SpringExploreResultReadTransactionPort(
    private val dataSource: DataSource,
    private val maximumAttempts: Int,
    private val transactionTimeout: Duration,
    private val statementTimeout: Duration,
    private val lockTimeout: Duration,
    private val blockingDispatcher: CoroutineDispatcher,
) : ExploreResultReadTransactionPort {
    private val transactionTemplate =
        TransactionTemplate(DataSourceTransactionManager(dataSource)).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            timeout = transactionTimeout.toPositiveCeilingSeconds()
        }

    init {
        require(maximumAttempts in 1..10)
        require(transactionTimeout.toMillis() > 0)
        require(statementTimeout.toMillis() > 0 && statementTimeout <= transactionTimeout)
        require(lockTimeout.toMillis() > 0 && lockTimeout <= statementTimeout)
    }

    override suspend fun <T> inResultReadTransaction(
        block: (ExploreResultReadTransaction) -> T,
    ): T = withContext(blockingDispatcher) { readInTransaction(block) }

    private fun <T> readInTransaction(
        block: (ExploreResultReadTransaction) -> T,
    ): T {
        rejectAmbientTransaction()
        var attempt = 1
        while (true) {
            try {
                return requireNotNull(
                    transactionTemplate.execute {
                        try {
                            requireResultReadTransaction()
                            applyDatabaseTimeouts()
                            ExploreResultReadTransactionValue(
                                block(ExploreResultReadTransaction()),
                            )
                        } catch (failure: Throwable) {
                            throw ExploreResultReadTransactionBlockFailure(failure)
                        }
                    },
                ).value
            } catch (failure: Throwable) {
                val resultReadFailure = failure.unwrapResultReadBlockFailure()
                if (!resultReadFailure.hasRetryableSqlState()) {
                    throw resultReadFailure
                }
                if (attempt >= maximumAttempts) {
                    throw ExploreResultReadTransactionConcurrentConflictException(
                        resultReadFailure,
                    )
                }
                attempt += 1
            }
        }
    }

    private fun rejectAmbientTransaction() {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Explore result read rejects an ambient transaction"
        }
        check(!TransactionSynchronizationManager.isSynchronizationActive()) {
            "Explore result read rejects ambient transaction synchronization"
        }
        check(!TransactionSynchronizationManager.hasResource(dataSource)) {
            "Explore result read rejects an ambient Control DataSource connection"
        }
    }

    private fun requireResultReadTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Explore result read requires an active transaction"
        }
        check(TransactionSynchronizationManager.hasResource(dataSource)) {
            "Explore result read requires the Control DataSource transaction"
        }
        val connection = DataSourceUtils.getConnection(dataSource)
        try {
            check(!connection.autoCommit) {
                "Explore result read requires a non-autocommit transaction"
            }
            check(connection.transactionIsolation == Connection.TRANSACTION_REPEATABLE_READ) {
                "Explore result read requires REPEATABLE READ"
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
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
                statement.queryTimeout = transactionTimeout.toPositiveCeilingSeconds()
                statement.setString(1, "${statementTimeout.toMillis()}ms")
                statement.setString(2, "${lockTimeout.toMillis()}ms")
                statement.execute()
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }
}

class SpringActiveExploreOwnerSettlementTransactionPort(
    private val dataSource: DataSource,
) : ActiveExploreOwnerSettlementTransactionPort {
    override fun <T> inActiveOwnerSettlementTransaction(block: () -> T): T {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Explore owner settlement requires the coordinator's active Control transaction"
        }
        check(TransactionSynchronizationManager.hasResource(dataSource)) {
            "Explore owner settlement requires the exact owner DataSource transaction"
        }
        val connection = DataSourceUtils.getConnection(dataSource)
        return try {
            check(!connection.autoCommit) {
                "Explore owner settlement requires a non-autocommit Control transaction"
            }
            check(connection.transactionIsolation == Connection.TRANSACTION_REPEATABLE_READ) {
                "Explore owner settlement requires the coordinator's REPEATABLE READ transaction"
            }
            block()
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }
}

private inline fun <reified T : Any> memoizedExploreTransaction(
    resourceKey: Any,
    description: String,
    create: () -> T,
): T {
    val existing = TransactionSynchronizationManager.getResource(resourceKey)
    if (existing != null) {
        check(existing is T) {
            "Explore $description transaction resource has an unexpected type"
        }
        return existing
    }
    check(TransactionSynchronizationManager.isSynchronizationActive()) {
        "Explore $description requires active transaction synchronization"
    }
    val created = create()
    TransactionSynchronizationManager.bindResource(resourceKey, created)
    TransactionSynchronizationManager.registerSynchronization(
        object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if (TransactionSynchronizationManager.getResource(resourceKey) === created) {
                    TransactionSynchronizationManager.unbindResource(resourceKey)
                }
            }
        },
    )
    return created
}

private class ExploreSpringTransactionResourceKey(
    private val transactionKind: Any,
    private val dataSource: DataSource,
    private val connection: Connection,
) {
    override fun equals(other: Any?): Boolean =
        other is ExploreSpringTransactionResourceKey &&
            transactionKind === other.transactionKind &&
            dataSource === other.dataSource &&
            connection === other.connection

    override fun hashCode(): Int =
        31 * (31 * System.identityHashCode(transactionKind) + System.identityHashCode(dataSource)) +
            System.identityHashCode(connection)
}

private val INITIAL_ADMISSION_TRANSACTION_KIND = Any()
private data class ExploreResultReadTransactionValue<T>(
    val value: T,
)

private class ExploreResultReadTransactionBlockFailure(
    cause: Throwable,
) : RuntimeException(cause)

private fun Throwable.unwrapResultReadBlockFailure(): Throwable =
    (this as? ExploreResultReadTransactionBlockFailure)?.cause ?: this

private fun Duration.toPositiveCeilingSeconds(): Int =
    ((toMillis() + 999) / 1_000)
        .coerceIn(1, Int.MAX_VALUE.toLong())
        .toInt()

private fun Throwable.hasRetryableSqlState(): Boolean =
    generateSequence(this) { failure -> failure.cause }
        .filterIsInstance<SQLException>()
        .any { failure -> failure.sqlState == "40001" || failure.sqlState == "40P01" }
