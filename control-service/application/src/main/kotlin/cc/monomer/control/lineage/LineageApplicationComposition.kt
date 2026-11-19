package cc.monomer.control.lineage

import cc.monomer.authority.api.CurrentResourceFocusConePort
import cc.monomer.catalog.api.CatalogCurrentObjectsReadPort
import cc.monomer.connector.api.ConnectorWorkspaceReadUseCase
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.semantic.api.SemanticCurrentReadUseCase
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.beans.factory.annotation.Qualifier

@Configuration(proxyBeanMethods = false)
class LineageApplicationComposition {
    @Bean
    fun lineageGraphQueryService(
        focusConePort: CurrentResourceFocusConePort,
        @Qualifier("connectorWorkspaceReadUseCase") connectorReadUseCase: ConnectorWorkspaceReadUseCase,
        catalogCurrentObjectsReadPort: CatalogCurrentObjectsReadPort,
        semanticCurrentReadUseCase: SemanticCurrentReadUseCase,
        dashboardOwnerPort: cc.monomer.dashboard.api.DashboardOwnerPort,
        controlAdmissionFacade: ControlAdmissionFacade,
        consistentSnapshotTransactionRunner: ConsistentSnapshotTransactionRunner,
    ): LineageGraphQueryService = LineageGraphQueryService(
        focusConePort = focusConePort,
        connectorReadUseCase = connectorReadUseCase,
        catalogCurrentObjectsReadPort = catalogCurrentObjectsReadPort,
        semanticCurrentReadUseCase = semanticCurrentReadUseCase,
        dashboardOwnerPort = dashboardOwnerPort,
        controlAdmissionFacade = controlAdmissionFacade,
        consistentSnapshotTransactionRunner = consistentSnapshotTransactionRunner,
    )
}
