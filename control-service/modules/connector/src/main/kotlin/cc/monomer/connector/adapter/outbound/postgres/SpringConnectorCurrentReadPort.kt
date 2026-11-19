package cc.monomer.connector.adapter.outbound.postgres

import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorWorkspaceReadUseCase
import cc.monomer.connector.api.CurrentConnectorRead
import cc.monomer.connector.application.ConnectorCurrentReadQueryPort
import cc.monomer.identity.api.WorkspaceId
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager

class SpringConnectorCurrentReadPort(
    private val dataSource: DataSource,
    private val transactionOperations: TransactionOperations,
    private val blockingDispatcher: CoroutineDispatcher,
    private val queryPort: ConnectorCurrentReadQueryPort,
    private val timeouts: ConnectorExecutionAccessReadTimeouts,
) : ConnectorWorkspaceReadUseCase {
    init {
        requireReadTransactionContract(transactionOperations, timeouts)
    }

    override suspend fun findByWorkspaceAndId(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
    ): CurrentConnectorRead? = inReadOnlyTransaction {
        queryPort.findCurrentByWorkspaceAndId(workspaceId, connectorId)
    }

    override suspend fun findByWorkspaceAndIds(
        workspaceId: WorkspaceId,
        connectorIds: Set<ConnectorId>,
    ): List<CurrentConnectorRead> = if (connectorIds.isEmpty()) {
        emptyList()
    } else {
        inReadOnlyTransaction {
            queryPort.findCurrentByWorkspaceAndIds(workspaceId, connectorIds)
        }
    }

    override suspend fun listByWorkspace(workspaceId: WorkspaceId): List<CurrentConnectorRead> =
        inReadOnlyTransaction { queryPort.listCurrentByWorkspace(workspaceId) }

    private suspend fun <T> inReadOnlyTransaction(block: () -> T): T =
        withContext(blockingDispatcher) {
            val nested = TransactionSynchronizationManager.isActualTransactionActive()
            requireNotNull(
                transactionOperations.execute {
                    requireExactReadTransaction(dataSource)
                    if (!nested) applyDatabaseReadTimeout(dataSource, timeouts)
                    ConnectorCurrentReadTransactionValue(block())
                },
            ).value
        }
}

private data class ConnectorCurrentReadTransactionValue<T>(val value: T)

private fun requireExactReadTransaction(dataSource: DataSource) {
    check(TransactionSynchronizationManager.isActualTransactionActive()) {
        "Connector current read requires an active read-only transaction"
    }
    check(TransactionSynchronizationManager.hasResource(dataSource)) {
        "Connector current read requires the transaction bound to the exact DataSource"
    }
}

private fun applyDatabaseReadTimeout(
    dataSource: DataSource,
    timeouts: ConnectorExecutionAccessReadTimeouts,
) {
    val connection = DataSourceUtils.getConnection(dataSource)
    try {
        connection.prepareStatement("SELECT set_config('statement_timeout', ?, true)").use { statement ->
            statement.queryTimeout = timeouts.transaction.toPositiveCeilingSeconds()
            statement.setString(1, "${timeouts.statement.toMillis()}ms")
            statement.execute()
        }
    } finally {
        DataSourceUtils.releaseConnection(connection, dataSource)
    }
}

private fun requireReadTransactionContract(
    transactionOperations: TransactionOperations,
    timeouts: ConnectorExecutionAccessReadTimeouts,
) {
    require(timeouts.transaction.toMillis() > 0)
    require(timeouts.statement.toMillis() > 0 && timeouts.statement <= timeouts.transaction)
    val definition = requireNotNull(transactionOperations as? TransactionDefinition) {
        "Connector current read transaction operations must expose TransactionDefinition"
    }
    require(definition.propagationBehavior == TransactionDefinition.PROPAGATION_REQUIRED) {
        "Connector current read transactions must use PROPAGATION_REQUIRED"
    }
    require(definition.isReadOnly) {
        "Connector current read transactions must be read-only"
    }
    require(definition.timeout == timeouts.transaction.toPositiveCeilingSeconds()) {
        "Connector current read timeout must match the bounded transaction contract"
    }
}
