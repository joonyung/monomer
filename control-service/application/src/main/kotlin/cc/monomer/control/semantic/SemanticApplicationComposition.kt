package cc.monomer.control.semantic

import cc.monomer.catalog.api.CatalogCurrentObjectsReadPort
import cc.monomer.connector.api.ConnectorWorkspaceReadUseCase
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.semantic.inbound.rest.SemanticWorkspaceRequestAuthorizer
import cc.monomer.identity.api.WorkspaceReportingTimeZoneQuery
import cc.monomer.semantic.application.SemanticCurrentReadService
import cc.monomer.semantic.application.SemanticDefinitionCheckService
import cc.monomer.semantic.application.SemanticAuthorityService
import cc.monomer.semantic.application.SemanticMetricFlowPort
import cc.monomer.semantic.application.SemanticQueryService
import cc.monomer.semantic.application.SemanticRepository
import cc.monomer.semantic.application.SemanticTransactionPort
import cc.monomer.semantic.application.SemanticTimeSpineRepository
import cc.monomer.semantic.application.SemanticTimeSpineService
import cc.monomer.semantic.adapter.outbound.postgres.PostgresSemanticRepository
import cc.monomer.semantic.adapter.outbound.postgres.SpringSemanticTransactionPort
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Composition for current Semantic definitions and request-local query rendering. */
@Configuration(proxyBeanMethods = false)
class SemanticApplicationComposition {
    @Bean
    fun semanticAuthorityService(
        postgresSemanticRepository: PostgresSemanticRepository,
        semanticTransactionPort: SpringSemanticTransactionPort,
        productControlClock: Clock,
        searchProjection: cc.monomer.semantic.api.SemanticSearchProjection,
    ): SemanticAuthorityService =
        SemanticAuthorityService(
            repository = postgresSemanticRepository,
            transactionPort = semanticTransactionPort,
            clock = productControlClock,
            searchProjection = searchProjection,
        )

    @Bean
    fun semanticCurrentReadService(
        repository: SemanticRepository,
        timeSpineRepository: SemanticTimeSpineRepository,
        transactionPort: SemanticTransactionPort,
        @Qualifier("connectorWorkspaceReadUseCase")
        connectorReadUseCase: ConnectorWorkspaceReadUseCase,
        catalogReadPort: CatalogCurrentObjectsReadPort,
    ): SemanticCurrentReadService =
        SemanticCurrentReadService(
            repository = repository,
            timeSpineRepository = timeSpineRepository,
            transactionPort = transactionPort,
            connectorReadUseCase = connectorReadUseCase,
            catalogReadPort = catalogReadPort,
        )

    @Bean
    fun semanticTimeSpineService(
        timeSpineRepository: SemanticTimeSpineRepository,
        transactionPort: SpringSemanticTransactionPort,
        @Qualifier("connectorWorkspaceReadUseCase")
        connectorReadUseCase: ConnectorWorkspaceReadUseCase,
        catalogReadPort: CatalogCurrentObjectsReadPort,
    ): SemanticTimeSpineService =
        SemanticTimeSpineService(
            repository = timeSpineRepository,
            transactionPort = transactionPort,
            connectorReadUseCase = connectorReadUseCase,
            catalogReadPort = catalogReadPort,
        )

    @Bean
    fun semanticQueryService(
        repository: SemanticRepository,
        timeSpineRepository: SemanticTimeSpineRepository,
        transactionPort: SemanticTransactionPort,
        metricFlowPort: SemanticMetricFlowPort,
        @Qualifier("connectorWorkspaceReadUseCase")
        connectorReadUseCase: ConnectorWorkspaceReadUseCase,
        catalogReadPort: CatalogCurrentObjectsReadPort,
        reportingTimeZoneQuery: WorkspaceReportingTimeZoneQuery,
    ): SemanticQueryService =
        SemanticQueryService(
            repository = repository,
            timeSpineRepository = timeSpineRepository,
            transactionPort = transactionPort,
            metricFlowPort = metricFlowPort,
            connectorReadUseCase = connectorReadUseCase,
            catalogReadPort = catalogReadPort,
            reportingTimeZoneQuery = reportingTimeZoneQuery,
        )

    @Bean
    fun semanticDefinitionCheckService(
        repository: SemanticRepository,
        timeSpineRepository: SemanticTimeSpineRepository,
        transactionPort: SemanticTransactionPort,
        metricFlowPort: SemanticMetricFlowPort,
        @Qualifier("connectorWorkspaceReadUseCase")
        connectorReadUseCase: ConnectorWorkspaceReadUseCase,
        catalogReadPort: CatalogCurrentObjectsReadPort,
        reportingTimeZoneQuery: WorkspaceReportingTimeZoneQuery,
    ): SemanticDefinitionCheckService =
        SemanticDefinitionCheckService(
            repository = repository,
            timeSpineRepository = timeSpineRepository,
            transactionPort = transactionPort,
            metricFlowPort = metricFlowPort,
            connectorReadUseCase = connectorReadUseCase,
            catalogReadPort = catalogReadPort,
            reportingTimeZoneQuery = reportingTimeZoneQuery,
        )

    @Bean
    fun semanticWorkspaceRequestAuthorizer(
        controlAdmissionFacade: ControlAdmissionFacade,
    ): SemanticWorkspaceRequestAuthorizer =
        SemanticWorkspaceRequestAuthorizer(controlAdmissionFacade)
}
