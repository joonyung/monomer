package cc.monomer.control.pages

import cc.monomer.control.toPositiveCeilingSeconds
import kotlinx.coroutines.CoroutineDispatcher
import cc.monomer.control.ControlProductAuthorityProperties
import cc.monomer.pages.adapter.outbound.messaging.InstanceIdHolder
import cc.monomer.pages.adapter.outbound.messaging.PostgresNotificationListener
import cc.monomer.pages.adapter.outbound.messaging.pages.PostgresPagesDirectoryEventBus
import cc.monomer.pages.adapter.outbound.postgres.PostgresProperties
import cc.monomer.pages.adapter.outbound.postgres.SpringTransactionRunner
import cc.monomer.pages.adapter.outbound.postgres.audit.PostgresPagesAuditRepository
import cc.monomer.pages.adapter.outbound.postgres.pages.PostgresPagesDirectoryRepository
import cc.monomer.pages.adapter.outbound.postgres.pages.content.PostgresPageContentRepository
import cc.monomer.pages.application.PagesDirectoryEventBus
import cc.monomer.pages.application.PagesDirectoryUseCaseImpl
import cc.monomer.pages.application.content.PageContentCleanupProcessor
import cc.monomer.pages.application.content.PageContentUploadUseCase
import cc.monomer.pages.application.content.PageContentUploadUseCaseImpl
import cc.monomer.pages.application.content.PageContentUseCase
import cc.monomer.pages.application.content.PageContentUseCaseImpl
import cc.monomer.pages.application.content.PageContentViewerUseCase
import cc.monomer.pages.application.content.PageContentViewerUseCaseImpl
import cc.monomer.pages.domain.common.TransactionRunner
import cc.monomer.pages.domain.content.PageContentObjectStorage
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class PagesApplicationComposition {
    @Bean
    fun pagesCurrentReader(
        directory: cc.monomer.pages.application.PagesDirectoryUseCase,
        viewer: PageContentViewerUseCase,
    ): cc.monomer.pages.api.PagesCurrentReadPort = cc.monomer.pages.application.PagesCurrentReader(directory, viewer)

    @Bean("pagesJdbcTemplate")
    fun pagesJdbcTemplate(
        productControlDataSource: DataSource,
    ): NamedParameterJdbcTemplate = NamedParameterJdbcTemplate(productControlDataSource)

    @Bean("pagesReadOnlyTransactionOperations")
    fun pagesReadOnlyTransactionOperations(
        productAuthorityTransactionManager: DataSourceTransactionManager,
        properties: ControlProductAuthorityProperties,
    ): TransactionTemplate =
        TransactionTemplate(productAuthorityTransactionManager).also { transaction ->
            transaction.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
            transaction.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            transaction.isReadOnly = true
            transaction.timeout = properties.transaction.transactionTimeout.toPositiveCeilingSeconds()
        }

    @Bean
    fun pagesTransactionRunner(
        @Qualifier("jdbcDispatcher") blockingDispatcher: CoroutineDispatcher,
        @Qualifier("productAuthorityTransactionOperations")
        productAuthorityTransactionOperations: TransactionTemplate,
        @Qualifier("pagesReadOnlyTransactionOperations")
        pagesReadOnlyTransactionOperations: TransactionTemplate,
    ): TransactionRunner =
        SpringTransactionRunner(
            transactionTemplate = TransactionTemplate(
                requireNotNull(productAuthorityTransactionOperations.transactionManager),
                productAuthorityTransactionOperations,
            ).apply {
                isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
            },
            readOnlyTransactionTemplate = pagesReadOnlyTransactionOperations,
            blockingDispatcher = blockingDispatcher,
        )

    @Bean
    fun pageActivityReader(
        @Qualifier("pagesJdbcTemplate") jdbc: NamedParameterJdbcTemplate,
        pagesTransactionRunner: TransactionRunner,
    ): cc.monomer.pages.api.PageActivityReadPort = cc.monomer.pages.adapter.outbound.postgres.pages.PostgresPageActivityReader(jdbc, pagesTransactionRunner)

    @Bean
    fun pagesDirectoryRepository(
        @Qualifier("pagesJdbcTemplate") pagesJdbcTemplate: NamedParameterJdbcTemplate,
        pagesTransactionRunner: TransactionRunner,
    ): PostgresPagesDirectoryRepository =
        PostgresPagesDirectoryRepository(
            jdbc = pagesJdbcTemplate,
            transactionRunner = pagesTransactionRunner,
        )

    @Bean
    fun pageContentRepository(
        @Qualifier("pagesJdbcTemplate") pagesJdbcTemplate: NamedParameterJdbcTemplate,
        pagesTransactionRunner: TransactionRunner,
    ): PostgresPageContentRepository =
        PostgresPageContentRepository(
            jdbc = pagesJdbcTemplate,
            transactionRunner = pagesTransactionRunner,
        )

    @Bean
    fun pagesAuditRepository(
        @Qualifier("pagesJdbcTemplate") pagesJdbcTemplate: NamedParameterJdbcTemplate,
        productControlObjectMapper: ObjectMapper,
    ): PostgresPagesAuditRepository =
        PostgresPagesAuditRepository(
            jdbc = pagesJdbcTemplate,
            objectMapper = productControlObjectMapper,
        )

    @Bean
    fun pagesPostgresProperties(
        productAuthorityProperties: ControlProductAuthorityProperties,
    ): PostgresProperties {
        val postgres = productAuthorityProperties.postgres
        return PostgresProperties(
            jdbcUrl = postgres.jdbcUrl,
            username = postgres.username,
            password = postgres.password,
        )
    }

    @Bean
    fun pagesInstanceIdHolder(): InstanceIdHolder = InstanceIdHolder(UUID.randomUUID().toString())

    @Bean
    fun pagesNotificationListener(
        pagesPostgresProperties: PostgresProperties,
    ): PostgresNotificationListener = PostgresNotificationListener(props = pagesPostgresProperties)

    @Bean
    fun pagesDirectoryEventBus(
        @Qualifier("pagesJdbcTemplate") pagesJdbcTemplate: NamedParameterJdbcTemplate,
        pagesNotificationListener: PostgresNotificationListener,
        pagesInstanceIdHolder: InstanceIdHolder,
    ): PagesDirectoryEventBus =
        PostgresPagesDirectoryEventBus(
            jdbc = pagesJdbcTemplate,
            listener = pagesNotificationListener,
            instanceIdHolder = pagesInstanceIdHolder,
        )

    @Bean
    fun pagesDirectoryUseCase(
        pagesDirectoryRepository: PostgresPagesDirectoryRepository,
        pagesTransactionRunner: TransactionRunner,
        pagesDirectoryEventBus: PagesDirectoryEventBus,
        pagesAuditRepository: PostgresPagesAuditRepository,
        pageContentRepository: PostgresPageContentRepository,
        dashboardExistence: cc.monomer.dashboard.api.DashboardCurrentExistencePort,
    ): PagesDirectoryUseCaseImpl =
        PagesDirectoryUseCaseImpl(
            queryPort = pagesDirectoryRepository,
            repository = pagesDirectoryRepository,
            transactionRunner = pagesTransactionRunner,
            eventBus = pagesDirectoryEventBus,
            auditRepository = pagesAuditRepository,
            contentRepository = pageContentRepository,
            contentQueryPort = pageContentRepository,
            dashboardReferencePort = cc.monomer.pages.application.PagesDashboardReferencePort { workspaceId, dashboardId ->
                dashboardExistence.existsInCallerTransaction(cc.monomer.identity.api.WorkspaceId(workspaceId), cc.monomer.dashboard.api.DashboardId(dashboardId))
            },
        )

    @Bean
    fun pageContentUseCase(
        pageContentRepository: PostgresPageContentRepository,
        pagesTransactionRunner: TransactionRunner,
        pagesAuditRepository: PostgresPagesAuditRepository,
        pagesDirectoryEventBus: PagesDirectoryEventBus,
    ): PageContentUseCase =
        PageContentUseCaseImpl(
            repository = pageContentRepository,
            queryPort = pageContentRepository,
            transactionRunner = pagesTransactionRunner,
            auditRepository = pagesAuditRepository,
            directoryEventBus = pagesDirectoryEventBus,
        )

    @Bean
    fun pageContentUploadUseCase(
        pageContentRepository: PostgresPageContentRepository,
        pagesTransactionRunner: TransactionRunner,
        pageContentObjectStorage: PageContentObjectStorage,
    ): PageContentUploadUseCase =
        PageContentUploadUseCaseImpl(
            repository = pageContentRepository,
            queryPort = pageContentRepository,
            transactionRunner = pagesTransactionRunner,
            objectStorage = pageContentObjectStorage,
        )

    @Bean
    fun pageContentViewerUseCase(
        pageContentRepository: PostgresPageContentRepository,
        pageContentObjectStorage: PageContentObjectStorage,
    ): PageContentViewerUseCase =
        PageContentViewerUseCaseImpl(
            viewQueryPort = pageContentRepository,
            objectStorage = pageContentObjectStorage,
        )

    @Bean
    fun pageContentCleanupProcessor(
        pageContentRepository: PostgresPageContentRepository,
        pageContentObjectStorage: PageContentObjectStorage,
    ): PageContentCleanupProcessor =
        PageContentCleanupProcessor(
            repository = pageContentRepository,
            objectStorage = pageContentObjectStorage,
        )
}
