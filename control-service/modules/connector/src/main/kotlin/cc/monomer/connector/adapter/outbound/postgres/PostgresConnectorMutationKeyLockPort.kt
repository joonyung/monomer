@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.connector.adapter.outbound.postgres

import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.application.ConnectorMutationKeyLockPort
import cc.monomer.identity.api.WorkspaceId
import java.time.Duration
import javax.sql.DataSource
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronizationManager

class PostgresConnectorMutationKeyLockPort(
    private val dataSource: DataSource,
    private val queryTimeout: Duration,
) : ConnectorMutationKeyLockPort {
    init {
        require(queryTimeout.toMillis() > 0) {
            "Connector mutation lock query timeout must be positive"
        }
    }

    override fun lockWorkspaceInOwnerTransaction(workspaceId: WorkspaceId) {
        lock("connector-workspace:${workspaceId.value}")
    }

    override fun lockInOwnerTransaction(
        workspaceId: WorkspaceId,
        mutationIdempotencyKey: String,
    ) {
        require(mutationIdempotencyKey.isNotBlank()) {
            "Connector mutation idempotency key must not be blank"
        }
        lock("connector-mutation:${workspaceId.value}:$mutationIdempotencyKey")
    }

    override fun lockConnectorInOwnerTransaction(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
    ) {
        lock("connector-identity:${workspaceId.value}:$connectorId")
    }

    private fun lock(canonicalIdentity: String) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Connector mutation lock requires an active owner transaction"
        }
        check(TransactionSynchronizationManager.hasResource(dataSource)) {
            "Connector mutation lock requires the transaction bound to the exact DataSource"
        }
        val connection = DataSourceUtils.getConnection(dataSource)
        try {
            connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            ).use { statement ->
                statement.queryTimeout = queryTimeout.toPositiveCeilingSeconds()
                statement.setString(1, canonicalIdentity)
                statement.execute()
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }
}
