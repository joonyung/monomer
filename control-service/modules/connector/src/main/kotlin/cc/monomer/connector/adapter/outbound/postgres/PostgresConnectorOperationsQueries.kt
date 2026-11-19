package cc.monomer.connector.adapter.outbound.postgres

import cc.monomer.connector.api.*
import cc.monomer.identity.api.OperationsAccess
import cc.monomer.identity.api.OperationsUnauthenticatedException
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineDispatcher
import org.springframework.transaction.support.TransactionSynchronizationManager
import kotlinx.coroutines.withContext
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.support.AbstractSqlTypeValue
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionOperations

class PostgresConnectorOperationsQueries(
    dataSource: DataSource,
    private val transaction: TransactionOperations,
    private val blockingDispatcher: CoroutineDispatcher,
) : ConnectorOperationsQueries {

    private val jdbc = NamedParameterJdbcTemplate(dataSource)
    init {
        val definition = requireNotNull(transaction as? TransactionDefinition)
        require(definition.isReadOnly && definition.timeout == 5)
    }
    private suspend fun <T> read(access: OperationsAccess, block: () -> T): T = withContext(blockingDispatcher) {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Operations reads require an independent root transaction"
        }
        if (!access.expiresAt.isAfter(Instant.now())) throw OperationsUnauthenticatedException()
        requireNotNull(transaction.execute {
            jdbc.jdbcTemplate.execute("SET LOCAL statement_timeout = '5s'")
            Box(block())
        }).value
    }
    private data class Box<T>(val value: T)
    override suspend fun counts(access:OperationsAccess,workspaceIds:Set<String>)=read(access){
        require(workspaceIds.size<=100)
        if(workspaceIds.isEmpty()) emptyMap() else jdbc.query("SELECT workspace_id,connector_type,count(*) count FROM connector.connectors WHERE deleted_at IS NULL AND workspace_id IN (:ids) GROUP BY workspace_id,connector_type",mapOf("ids" to workspaceIds.map(UUID::fromString))){r,_->r.getString("workspace_id") to OperationsConnectorCount(r.getString("connector_type"),r.getLong("count"))}.groupBy({it.first},{it.second})
    }
    override suspend fun firstConnectorCreatedAt(access:OperationsAccess,workspaceIds:Set<String>)=read(access){
        jdbc.query("SELECT workspace_id,min(created_at) first_created_at FROM connector.connectors WHERE connector_type <> 'duckdb' AND workspace_id=ANY(:ids) GROUP BY workspace_id",
            mapOf("ids" to uuidArray(workspaceIds))){r,_->r.getString("workspace_id") to r.getTimestamp("first_created_at").toInstant()}.toMap()
    }
    override suspend fun firstSampleConnectorCreatedAt(access:OperationsAccess,workspaceIds:Set<String>)=read(access){
        jdbc.query("SELECT workspace_id,min(created_at) first_created_at FROM connector.connectors WHERE connector_type = 'duckdb' AND workspace_id=ANY(:ids) GROUP BY workspace_id",
            mapOf("ids" to uuidArray(workspaceIds))){r,_->r.getString("workspace_id") to r.getTimestamp("first_created_at").toInstant()}.toMap()
    }
    private fun uuidArray(ids:Collection<String>)=object:AbstractSqlTypeValue(){
        override fun createTypeValue(connection:Connection,sqlType:Int,typeName:String?):Any=connection.createArrayOf("uuid",ids.map(UUID::fromString).toTypedArray())
    }
}
