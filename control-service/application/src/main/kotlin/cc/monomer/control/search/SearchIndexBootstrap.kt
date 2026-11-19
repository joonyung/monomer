package cc.monomer.control.search

import cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogSearchReadPort
import cc.monomer.catalog.api.CatalogSearchReadPort
import cc.monomer.connector.adapter.outbound.postgres.PostgresConnectorRepository
import cc.monomer.connector.api.ConnectorSearchReadPort
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardSearchReadPort
import cc.monomer.dashboard.api.DashboardSearchReadPort
import cc.monomer.search.adapter.outbound.postgres.PostgresSearchIndex
import cc.monomer.search.api.SearchIndex
import cc.monomer.semantic.adapter.outbound.postgres.PostgresSemanticRepository
import cc.monomer.semantic.api.SemanticSearchReadPort
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

class SearchIndexBootstrap(
    private val connectors: ConnectorSearchReadPort,
    private val catalog: CatalogSearchReadPort,
    private val semantic: SemanticSearchReadPort,
    private val dashboards: DashboardSearchReadPort,
    private val index: SearchIndex,
) {
    fun populateInCurrentTransaction(): Int {
        val connectorCatalogProjection = CatalogConnectorSearchProjection(index)
        val semanticDashboardProjection = SemanticDashboardSearchProjection(index)
        val connectorFields = connectors.inventory()
        val catalogFields = catalog.inventory()
        val semanticFields = semantic.inventory()
        val dashboardFields = dashboards.inventory()
        connectorFields.forEach(connectorCatalogProjection::upsert)
        catalogFields.forEach(connectorCatalogProjection::upsert)
        semanticFields.models.forEach(semanticDashboardProjection::upsertModel)
        semanticFields.metrics.forEach(semanticDashboardProjection::upsertMetric)
        dashboardFields.forEach(semanticDashboardProjection::upsert)
        return connectorFields.size + catalogFields.size + semanticFields.models.size + semanticFields.metrics.size + dashboardFields.size
    }

    companion object {
        @JvmStatic fun main(arguments: Array<String>) {
            require(arguments.isEmpty())
            val jdbcUrl = System.getenv("MONOMER_CONTROL_POSTGRES_JDBC_URL") ?: "jdbc:postgresql://127.0.0.1:5433/monomer"
            require(jdbcUrl == "jdbc:postgresql://127.0.0.1:5433/monomer") { "This command only initializes the canonical local Control database" }
            val username = System.getenv("MONOMER_CONTROL_POSTGRES_USERNAME") ?: "monomer"
            require(username == "monomer")
            val dataSource = DriverManagerDataSource(jdbcUrl, username, System.getenv("MONOMER_CONTROL_POSTGRES_PASSWORD") ?: "monomer")
            val objectMapper = jacksonObjectMapper()
            val initializer = SearchIndexBootstrap(
                PostgresConnectorRepository(dataSource, objectMapper), PostgresCatalogSearchReadPort(dataSource),
                PostgresSemanticRepository(dataSource, objectMapper), PostgresDashboardSearchReadPort(dataSource), PostgresSearchIndex(dataSource),
            )
            val transaction = TransactionTemplate(DataSourceTransactionManager(dataSource)).apply {
                isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            }
            val count = transaction.execute { initializer.populateInCurrentTransaction() }
            println("Initialized $count current metadata search documents.")
        }
    }
}
