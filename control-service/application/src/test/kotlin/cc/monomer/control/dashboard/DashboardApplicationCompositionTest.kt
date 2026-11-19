package cc.monomer.control.dashboard

import cc.monomer.connector.api.ConnectorWorkspaceReadUseCase
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.pagereader.PublicPagesAccess
import cc.monomer.dashboard.application.DashboardQueryPlanningPort
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.pages.api.PagesCurrentReadPort
import cc.monomer.semantic.application.SemanticQueryUseCase
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.AbstractBeanDefinition
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import javax.sql.DataSource
import kotlin.test.assertIs

class DashboardApplicationCompositionTest {
    @Test
    fun `SQL planner resolves the workspace read bean when its implementation is also registered`() {
        val connectorRead = mockk<ConnectorWorkspaceReadUseCase>()
        val beans = DefaultListableBeanFactory().apply {
            autowireCandidateResolver = ContextAnnotationAutowireCandidateResolver()
            registerSingleton("composition", DashboardApplicationComposition())
            registerSingleton("jdbcDispatcher", Dispatchers.Unconfined)
            registerSingleton("productControlDataSource", mockk<DataSource>())
            registerSingleton("objectMapper", ObjectMapper())
            registerSingleton("semanticQueryUseCase", mockk<SemanticQueryUseCase>())
            registerSingleton("connectorCurrentReadPort", connectorRead)
            registerSingleton("connectorWorkspaceReadUseCase", connectorRead)
            registerSingleton("controlAdmissionFacade", mockk<ControlAdmissionFacade>())
            registerSingleton("consistentSnapshotTransactionRunner", mockk<ConsistentSnapshotTransactionRunner>())
            registerSingleton("postgresConsistentSnapshotTransactionTemplate", mockk<TransactionTemplate>())
            registerSingleton("pageAccess", mockk<PublicPagesAccess>())
            registerSingleton("pages", mockk<PagesCurrentReadPort>())
            registerBeanDefinition("dashboardQueryPlanningPort", RootBeanDefinition().apply {
                factoryBeanName = "composition"
                factoryMethodName = "dashboardQueryPlanningPort"
                autowireMode = AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR
            })
        }

        assertIs<DashboardQueryPlanningAdapter>(beans.getBean(DashboardQueryPlanningPort::class.java))
    }
}
