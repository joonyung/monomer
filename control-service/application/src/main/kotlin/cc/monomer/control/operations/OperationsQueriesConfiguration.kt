package cc.monomer.control.operations

import cc.monomer.connector.api.ConnectorOperationsQueries
import cc.monomer.connector.adapter.outbound.postgres.PostgresConnectorOperationsQueries
import cc.monomer.dashboard.api.DashboardOperationsQueries
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardOperationsQueries
import cc.monomer.explore.api.ExploreOperationsQueries
import cc.monomer.explore.adapter.outbound.postgres.PostgresExploreOperationsQueries
import cc.monomer.identity.api.IdentityOperationsQueries
import cc.monomer.identity.outbound.postgres.PostgresIdentityOperationsQueries
import cc.monomer.semantic.api.SemanticOperationsQueries
import cc.monomer.semantic.adapter.outbound.postgres.PostgresSemanticOperationsQueries
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineDispatcher
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import cc.monomer.identity.outbound.postgres.SpringTransactionRunner

@Configuration
class OperationsQueriesConfiguration {
    @Bean
    fun identityOperationsQueries(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        @Qualifier("jdbcDispatcher") blockingDispatcher: CoroutineDispatcher,
    ): IdentityOperationsQueries = PostgresIdentityOperationsQueries(
        dataSource,
        SpringTransactionRunner(readTransaction(transactionManager), readTransaction(transactionManager), blockingDispatcher),
    )

    @Bean
    fun exploreOperationsQueries(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        @Qualifier("jdbcDispatcher") blockingDispatcher: CoroutineDispatcher,
    ): ExploreOperationsQueries = PostgresExploreOperationsQueries(dataSource, readTransaction(transactionManager), blockingDispatcher)

    @Bean
    fun connectorOperationsQueries(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        @Qualifier("jdbcDispatcher") blockingDispatcher: CoroutineDispatcher,
    ): ConnectorOperationsQueries = PostgresConnectorOperationsQueries(dataSource, readTransaction(transactionManager), blockingDispatcher)

    @Bean
    fun dashboardOperationsQueries(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        @Qualifier("jdbcDispatcher") blockingDispatcher: CoroutineDispatcher,
    ): DashboardOperationsQueries = PostgresDashboardOperationsQueries(dataSource, readTransaction(transactionManager), blockingDispatcher)

    @Bean
    fun semanticOperationsQueries(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        @Qualifier("jdbcDispatcher") blockingDispatcher: CoroutineDispatcher,
    ): SemanticOperationsQueries = PostgresSemanticOperationsQueries(dataSource, readTransaction(transactionManager), blockingDispatcher)

    private fun readTransaction(manager: PlatformTransactionManager) = TransactionTemplate(manager).apply {
        isReadOnly = true
        timeout = 5
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
    }
}
