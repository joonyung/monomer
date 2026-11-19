package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.domain.port.ConsistentSnapshotRetryException
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.identity.domain.port.TransactionRunner
import java.sql.SQLException
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.ContinuationInterceptor
import org.springframework.transaction.TransactionDefinition
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

@Component
class SpringTransactionRunner(
    @param:Qualifier("postgresTransactionTemplate")
    transactionTemplate: TransactionTemplate,
    @param:Qualifier("postgresReadOnlyTransactionTemplate")
    readOnlyTransactionTemplate: TransactionTemplate,
    @param:Qualifier("jdbcDispatcher") private val blockingDispatcher: CoroutineDispatcher,
) : TransactionRunner {
    private val transactionTemplate = transactionTemplate.boundedIdentityTransaction()
    private val readOnlyTransactionTemplate = readOnlyTransactionTemplate.boundedIdentityTransaction()

    override suspend fun <T> run(action: () -> T): T = execute(transactionTemplate, action)

    override suspend fun <T> readOnly(action: () -> T): T = execute(readOnlyTransactionTemplate, action)

    private suspend fun <T> execute(template: TransactionTemplate, action: () -> T): T =
        withContext(blockingDispatcher) {
            val nested = TransactionSynchronizationManager.isActualTransactionActive()
            check(!nested ||
                template.propagationBehavior == TransactionDefinition.PROPAGATION_REQUIRED) {
                "An independent Identity transaction cannot run inside an ambient transaction"
            }
            template.execute { status ->
                if (status.isNewTransaction) template.applyIdentityDatabaseTimeouts()
                action()
            }
        }
}

@Component
class SpringConsistentSnapshotTransactionRunner(
    @param:Qualifier("postgresConsistentSnapshotTransactionTemplate")
    transactionTemplate: TransactionTemplate,
    @param:Qualifier("jdbcDispatcher") private val blockingDispatcher: CoroutineDispatcher,
) : ConsistentSnapshotTransactionRunner {
    private val transactionTemplate = transactionTemplate.boundedIdentityTransaction()

    override suspend fun <T> run(action: suspend () -> T): T = withContext(blockingDispatcher) {
        val nested = TransactionSynchronizationManager.isActualTransactionActive()
        if (nested && transactionTemplate.isolationLevel != TransactionDefinition.ISOLATION_DEFAULT) {
            check(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel() == transactionTemplate.isolationLevel) {
                "A snapshot must join a transaction with the requested isolation"
            }
        }
        // Preserve caller context and cancellation, but execute JDBC on this exact thread.
        val bridgeContext = currentCoroutineContext().minusKey(ContinuationInterceptor)
        var lastRetryableFailure: Throwable? = null
        repeat(if (nested) 1 else MAX_TRANSACTION_ATTEMPTS) { attemptIndex ->
            currentCoroutineContext().ensureActive()
            try {
                return@withContext transactionTemplate.execute { status ->
                    if (status.isNewTransaction) transactionTemplate.applyIdentityDatabaseTimeouts()
                    runBlocking(bridgeContext) { action() }
                }
            } catch (error: Throwable) {
                // Only the owner may retry the entire transaction, never a nested fragment.
                if (nested || !error.isRetryableConsistentSnapshotFailure()) throw error
                lastRetryableFailure = error
                if (attemptIndex == MAX_TRANSACTION_ATTEMPTS - 1) throw error
            }
        }
        throw checkNotNull(lastRetryableFailure)
    }

    private companion object {
        const val MAX_TRANSACTION_ATTEMPTS = 3
    }
}

private fun Throwable.isRetryableConsistentSnapshotFailure(): Boolean =
    generateSequence(this) { it.cause }.any { failure ->
        failure is ConsistentSnapshotRetryException ||
            (failure is SQLException && failure.sqlState == SERIALIZATION_FAILURE_SQL_STATE)
    }

private const val SERIALIZATION_FAILURE_SQL_STATE = "40001"

private const val IDENTITY_TRANSACTION_TIMEOUT_SECONDS = 5
private const val IDENTITY_STATEMENT_TIMEOUT = "4000ms"
private const val IDENTITY_LOCK_TIMEOUT = "1000ms"

private fun TransactionTemplate.identityDataSource(): DataSource =
    requireNotNull((transactionManager as? DataSourceTransactionManager)?.dataSource) {
        "Identity transactions require a DataSourceTransactionManager with an exact DataSource"
    }

private fun TransactionTemplate.boundedIdentityTransaction(): TransactionTemplate {
    identityDataSource()
    return TransactionTemplate(requireNotNull(transactionManager), this).apply {
        timeout = IDENTITY_TRANSACTION_TIMEOUT_SECONDS
    }
}

private fun TransactionTemplate.applyIdentityDatabaseTimeouts() {
    val dataSource = identityDataSource()
    check(TransactionSynchronizationManager.isActualTransactionActive() &&
        TransactionSynchronizationManager.hasResource(dataSource)) {
        "Identity transaction requires the transaction bound to the exact DataSource"
    }
    val connection = DataSourceUtils.getConnection(dataSource)
    try {
        connection.prepareStatement(
            """
            SELECT
                set_config('statement_timeout', ?, true),
                set_config('lock_timeout', ?, true)
            """.trimIndent(),
        ).use { statement ->
            statement.queryTimeout = IDENTITY_TRANSACTION_TIMEOUT_SECONDS
            statement.setString(1, IDENTITY_STATEMENT_TIMEOUT)
            statement.setString(2, IDENTITY_LOCK_TIMEOUT)
            statement.execute()
        }
    } finally {
        DataSourceUtils.releaseConnection(connection, dataSource)
    }
}
