package cc.monomer.connector.adapter.outbound.postgres

import cc.monomer.connector.api.ConnectorExecutionAccessInOwnerTransactionPort
import cc.monomer.connector.api.ConnectorExecutionAccessResult
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.application.ConnectorExecutionAccessQueryPort
import cc.monomer.connector.application.resolveConnectorExecutionAccess
import cc.monomer.identity.api.WorkspaceId
import javax.sql.DataSource
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronizationManager

class SpringConnectorExecutionAccessInOwnerTransactionPort(
    private val dataSource: DataSource,
    private val queryPort: ConnectorExecutionAccessQueryPort,
) : ConnectorExecutionAccessInOwnerTransactionPort {
    override fun readCurrentForExecutionInOwnerTransaction(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
    ): ConnectorExecutionAccessResult {
        requireOwnerTransaction()
        return resolveConnectorExecutionAccess(
            queryPort.findCurrentExecutionAccessCandidate(workspaceId, connectorId),
        )
    }

    private fun requireOwnerTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Connector execution access requires an active owner transaction"
        }
        check(TransactionSynchronizationManager.hasResource(dataSource)) {
            "Connector execution access requires the transaction bound to the exact DataSource"
        }
        val connection = DataSourceUtils.getConnection(dataSource)
        try {
            check(!connection.autoCommit) {
                "Connector execution access requires a non-autocommit owner transaction"
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }
}
