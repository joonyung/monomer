package cc.monomer.connector.adapter.outbound.postgres

import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.application.ConnectorExecutionAccessCandidate
import cc.monomer.connector.application.ConnectorExecutionAccessQueryPort
import cc.monomer.connector.application.ConnectorExecutionAccessReadPort
import cc.monomer.identity.api.WorkspaceId
import java.time.Duration
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager

data class ConnectorExecutionAccessReadTimeouts(
    val transaction: Duration,
    val statement: Duration,
)

class SpringConnectorExecutionAccessReadPort(
    private val dataSource: DataSource,
    private val transactionOperations: TransactionOperations,
    private val blockingDispatcher: CoroutineDispatcher,
    private val queryPort: ConnectorExecutionAccessQueryPort,
    private val timeouts: ConnectorExecutionAccessReadTimeouts,
) : ConnectorExecutionAccessReadPort {
    init {
        requireReadTransactionContract(transactionOperations, timeouts)
    }

    override suspend fun readCurrentExecutionAccessCandidate(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
    ): ConnectorExecutionAccessCandidate? =
        inReadOnlyTransaction {
            queryPort.findCurrentExecutionAccessCandidate(workspaceId, connectorId)
        }

    private suspend fun <T> inReadOnlyTransaction(
        block: () -> T,
    ): T =
        withContext(blockingDispatcher) {
            val nested = TransactionSynchronizationManager.isActualTransactionActive()
            requireNotNull(
                transactionOperations.execute {
                    requireExactReadTransaction()
                    if (!nested) applyDatabaseTimeouts()
                    ConnectorExecutionReadTransactionValue(block())
                },
            ).value
        }

    private fun requireExactReadTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Connector execution access requires an active read-only transaction"
        }
        check(TransactionSynchronizationManager.hasResource(dataSource)) {
            "Connector execution access requires the transaction bound to the exact DataSource"
        }
    }

    private fun applyDatabaseTimeouts() {
        val connection = DataSourceUtils.getConnection(dataSource)
        try {
            connection.prepareStatement(
                "SELECT set_config('statement_timeout', ?, true)",
            ).use { statement ->
                statement.queryTimeout = timeouts.transaction.toPositiveCeilingSeconds()
                statement.setString(1, "${timeouts.statement.toMillis()}ms")
                statement.execute()
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }
}

private data class ConnectorExecutionReadTransactionValue<T>(
    val value: T,
)

private fun requireReadTransactionContract(
    transactionOperations: TransactionOperations,
    timeouts: ConnectorExecutionAccessReadTimeouts,
) {
    require(timeouts.transaction.toMillis() > 0)
    require(timeouts.statement.toMillis() > 0 && timeouts.statement <= timeouts.transaction)
    val definition = requireNotNull(transactionOperations as? TransactionDefinition) {
        "Connector execution access transaction operations must expose TransactionDefinition"
    }
    require(definition.propagationBehavior == TransactionDefinition.PROPAGATION_REQUIRED) {
        "Connector execution access transactions must use PROPAGATION_REQUIRED"
    }
    require(definition.isReadOnly) {
        "Connector execution access transactions must be read-only"
    }
    require(definition.timeout == timeouts.transaction.toPositiveCeilingSeconds()) {
        "Connector execution access timeout must match the bounded transaction contract"
    }
}
