package cc.monomer.control.sample

import cc.monomer.catalog.api.CatalogProvisioningUseCase
import cc.monomer.connector.api.SampleConnectorUseCase
import cc.monomer.dashboard.api.DashboardSeedDefinitionUseCase
import cc.monomer.identity.api.SampleWorkspaceProvisioningUseCase
import cc.monomer.semantic.api.SemanticSeedDefinitionUseCase
import cc.monomer.semantic.api.SemanticCurrentReadUseCase
import cc.monomer.semantic.api.SemanticTimeSpineUseCase
import java.time.Clock
import cc.monomer.control.ControlProductAuthorityProperties
import org.springframework.jdbc.datasource.DriverManagerDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class SampleWorkspaceComposition {
    @Bean
    fun sampleWorkspaceProvisioning(
        workspaces: SampleWorkspaceProvisioningUseCase,
        connectors: SampleConnectorUseCase,
        catalog: CatalogProvisioningUseCase,
        semantic: SemanticSeedDefinitionUseCase,
        semanticRead: SemanticCurrentReadUseCase,
        timeSpines: SemanticTimeSpineUseCase,
        dashboards: DashboardSeedDefinitionUseCase,
        properties: ControlProductAuthorityProperties,
        @Qualifier("jdbcDispatcher") jdbcDispatcher: CoroutineDispatcher,
        @Qualifier("applicationAsyncScope") runnerScope: CoroutineScope,
        clock: Clock,
    ): SampleWorkspaceProvisioning = SampleWorkspaceProvisioning(workspaces, connectors, catalog, semantic, semanticRead,
        timeSpines, dashboards, SampleDefinitionBundle(), PostgresSampleWorkspaceLock(DriverManagerDataSource(properties.postgres.jdbcUrl, properties.postgres.username, properties.postgres.password), jdbcDispatcher), runnerScope, clock)
}
