@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import kotlinx.coroutines.CoroutineDispatcher
import cc.monomer.connector.api.ConnectorExecutionAccessInOwnerTransactionPort
import cc.monomer.connector.api.ConnectorRuntimeBindingProjectionPort
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.ControlProductAuthorityProperties
import cc.monomer.control.ControlRuntimeProperties
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardActivityReadPort
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardCommandReceiptRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardCurrentExistencePort
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardDraftRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardLifecycleRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardScheduleRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardSessionRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresQueryRunRepository
import cc.monomer.dashboard.api.DashboardActivityReadPort
import cc.monomer.dashboard.api.DashboardCurrentExistencePort
import cc.monomer.dashboard.application.DashboardCommandReceiptRepository
import cc.monomer.dashboard.application.DashboardDraftRepository
import cc.monomer.dashboard.application.DashboardLifecycleRepository
import cc.monomer.dashboard.application.DashboardRepository
import cc.monomer.dashboard.application.DashboardScheduleRepository
import cc.monomer.dashboard.application.DashboardSessionRepository
import cc.monomer.dashboard.application.QueryRunRepository
import cc.monomer.dashboard.application.DashboardExecutionAdmissionPort
import cc.monomer.dashboard.application.DashboardExecutionCancellationPort
import cc.monomer.dashboard.application.DashboardDraftService
import cc.monomer.dashboard.application.DashboardDraftUseCase
import cc.monomer.dashboard.application.DashboardQueryRunSettlementService
import cc.monomer.dashboard.application.DashboardResultReadPort
import cc.monomer.dashboard.application.DashboardQueryPlanningPort
import cc.monomer.dashboard.application.DashboardService
import cc.monomer.dashboard.application.DashboardSessionService
import cc.monomer.dashboard.application.DashboardTransactionPort
import cc.monomer.dashboard.application.DashboardUseCase
import cc.monomer.dashboard.application.DashboardSessionUseCase
import cc.monomer.dashboard.api.DashboardOwnerPort
import cc.monomer.execution.adapter.inbound.grpc.ProtoRawSqlRunSpecFactory
import cc.monomer.execution.api.ExecutionArtifactStore
import cc.monomer.execution.api.ExecutionRunAdmissionPort
import cc.monomer.execution.api.ExecutionRunOwnerSettlementPort
import cc.monomer.execution.api.OwnerExecutionRunCancellationPort
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.semantic.application.SemanticQueryUseCase
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class DashboardApplicationComposition {
    @Bean
    fun dashboardRequestAuthorizer(
        controlAdmissionFacade: ControlAdmissionFacade,
    ): DashboardRequestAuthorizer =
        DashboardRequestAuthorizer(controlAdmissionFacade)

    @Bean(destroyMethod = "close")
    fun postgresDashboardNotifications(
        @Qualifier("productControlDataSource") publicationDataSource: DataSource,
        properties: ControlProductAuthorityProperties,
        runtimeProperties: ControlRuntimeProperties,
    ): PostgresDashboardNotifications = PostgresDashboardNotifications(
        postgres = properties.postgres,
        publicationDataSource = publicationDataSource,
        transaction = properties.transaction,
        reconnectDelay = runtimeProperties.executionRuntime.limits.recoveryInterval,
    )

    @Bean
    fun dashboardSessionEventHub(
        productControlClock: Clock,
        notifications: PostgresDashboardNotifications,
    ): DashboardSessionEventHub = DashboardSessionEventHub(productControlClock, notifications)

    @Bean
    fun dashboardDraftEventHub(
        productControlClock: Clock,
        notifications: PostgresDashboardNotifications,
    ): DashboardDraftEventHub = DashboardDraftEventHub(productControlClock, notifications)

    @Bean
    fun dashboardRepository(productControlDataSource: DataSource): DashboardRepository =
        PostgresDashboardRepository(productControlDataSource)

    @Bean
    fun dashboardDraftRepository(productControlDataSource: DataSource): DashboardDraftRepository =
        PostgresDashboardDraftRepository(productControlDataSource)

    @Bean
    fun dashboardSessionRepository(productControlDataSource: DataSource): DashboardSessionRepository =
        PostgresDashboardSessionRepository(productControlDataSource)

    @Bean
    fun dashboardQueryRunRepository(productControlDataSource: DataSource): QueryRunRepository =
        PostgresQueryRunRepository(productControlDataSource)

    @Bean
    fun dashboardCommandReceiptRepository(productControlDataSource: DataSource): DashboardCommandReceiptRepository =
        PostgresDashboardCommandReceiptRepository(productControlDataSource)

    @Bean
    fun dashboardScheduleRepository(productControlDataSource: DataSource): DashboardScheduleRepository =
        PostgresDashboardScheduleRepository(productControlDataSource)

    @Bean
    fun dashboardLifecycleRepository(productControlDataSource: DataSource): DashboardLifecycleRepository =
        PostgresDashboardLifecycleRepository(productControlDataSource)

    @Bean
    fun dashboardCurrentExistencePort(productControlDataSource: DataSource): DashboardCurrentExistencePort =
        PostgresDashboardCurrentExistencePort(productControlDataSource)

    @Bean
    fun dashboardActivityReadPort(
        productControlDataSource: DataSource,
        @Qualifier("productAuthorityTransactionOperations") transactionOperations: TransactionTemplate,
        @Qualifier("jdbcDispatcher") blockingDispatcher: CoroutineDispatcher,
    ): DashboardActivityReadPort =
        PostgresDashboardActivityReadPort(
            productControlDataSource,
            TransactionTemplate(requireNotNull(transactionOperations.transactionManager)).apply {
                isReadOnly = true
                timeout = 5
                propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
                isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            },
            blockingDispatcher,
        )

    @Bean
    fun dashboardTransactionPort(
        @Qualifier("jdbcDispatcher") blockingDispatcher: CoroutineDispatcher,
        @Qualifier("productAuthorityTransactionOperations") transactionOperations: TransactionTemplate,
    ): DashboardTransactionPort =
        SpringDashboardTransactionPort(
            TransactionTemplate(requireNotNull(transactionOperations.transactionManager), transactionOperations).apply {
                isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
            },
            blockingDispatcher,
        )

    @Bean
    fun dashboardQueryPlanningPort(
        @Qualifier("jdbcDispatcher") blockingDispatcher: CoroutineDispatcher,
        productControlDataSource: DataSource,
        objectMapper: ObjectMapper,
        semanticQueryUseCase: SemanticQueryUseCase,
        @Qualifier("connectorWorkspaceReadUseCase") connectorRead: cc.monomer.connector.api.ConnectorWorkspaceReadUseCase,
        controlAdmissionFacade: ControlAdmissionFacade,
        consistentSnapshotTransactionRunner: ConsistentSnapshotTransactionRunner,
        @Qualifier("postgresConsistentSnapshotTransactionTemplate") planningTransactionOperations: TransactionTemplate,
        pageAccess: cc.monomer.control.pagereader.PublicPagesAccess,
        pages: cc.monomer.pages.api.PagesCurrentReadPort,
    ): DashboardQueryPlanningPort = DashboardQueryPlanningAdapter(
        dataSource = productControlDataSource,
        objectMapper = objectMapper,
        semanticQueryUseCase = semanticQueryUseCase,
        connectorRead = connectorRead,
        controlAdmissionFacade = controlAdmissionFacade,
        consistentSnapshotTransactionRunner = consistentSnapshotTransactionRunner,
        planningTransactionOperations = planningTransactionOperations,
        blockingDispatcher = blockingDispatcher,
        pageAccess = pageAccess,
        pages = pages,
    )

    @Bean
    fun dashboardExecutionAdmissionPort(
        connectorExecutionAccessInOwnerTransactionPort: ConnectorExecutionAccessInOwnerTransactionPort,
        connectorRuntimeBindingProjectionPort: ConnectorRuntimeBindingProjectionPort,
        executionRunAdmissionPort: ExecutionRunAdmissionPort,
        productControlClock: Clock,
        runtimeProperties: ControlRuntimeProperties,
    ): DashboardExecutionAdmissionPort = DashboardExecutionAdmissionAdapter(
        connectorAccess = connectorExecutionAccessInOwnerTransactionPort,
        connectorProjection = connectorRuntimeBindingProjectionPort,
        rawSqlRunSpecFactory = ProtoRawSqlRunSpecFactory(),
        admissionPort = executionRunAdmissionPort,
        clock = productControlClock,
        runtimeProperties = runtimeProperties,
    )

    @Bean
    fun dashboardExecutionCancellationPort(
        ownerExecutionRunCancellationPort: OwnerExecutionRunCancellationPort,
        productControlClock: Clock,
    ): DashboardExecutionCancellationPort =
        DashboardExecutionCancellationAdapter(ownerExecutionRunCancellationPort, productControlClock)

    @Bean
    fun dashboardResultReadPort(executionArtifactStore: ExecutionArtifactStore): DashboardResultReadPort =
        DashboardResultReader(executionArtifactStore)

    @Bean
    fun dashboardDependencyCleanupPort(
        cancellation: OwnerExecutionRunCancellationPort,
        executionRuns: cc.monomer.execution.api.ExecutionRunQueryPort,
        discard: cc.monomer.execution.api.OwnerExecutionRunDiscardPort,
        pages: cc.monomer.pages.application.PagesDirectoryUseCase,
        drafts: DashboardDraftEventHub,
        sessions: DashboardSessionEventHub,
    ): cc.monomer.dashboard.api.DashboardDependencyCleanupPort = DashboardDependencyCleanupAdapter(cancellation, executionRuns, discard, pages, drafts, sessions)

    @Bean
    fun dashboardLifecycleService(
        transaction: DashboardTransactionPort,
        dashboardRepository: DashboardRepository,
        dashboardLifecycleRepository: DashboardLifecycleRepository,
        dashboardCommandReceiptRepository: DashboardCommandReceiptRepository,
        admission: DashboardExecutionAdmissionPort,
        cleanup: cc.monomer.dashboard.api.DashboardDependencyCleanupPort,
        search: cc.monomer.dashboard.api.DashboardSearchProjection,
    ): cc.monomer.dashboard.application.DashboardLifecycleService = cc.monomer.dashboard.application.DashboardLifecycleService(
        transaction, dashboardRepository, dashboardLifecycleRepository, dashboardCommandReceiptRepository, admission, cleanup, search,
    )

    @Bean
    fun dashboardUseCase(
        searchProjection: cc.monomer.dashboard.api.DashboardSearchProjection,
        dashboardTransactionPort: DashboardTransactionPort,
        dashboardRepository: DashboardRepository,
        dashboardCommandReceiptRepository: DashboardCommandReceiptRepository,
    ): DashboardUseCase = DashboardService(
        searchProjection = searchProjection,
        transactionPort = dashboardTransactionPort,
        repository = dashboardRepository,
        commandReceiptRepository = dashboardCommandReceiptRepository,
    )

    @Bean
    fun dashboardSessionUseCase(
        dashboardTransactionPort: DashboardTransactionPort,
        dashboardRepository: DashboardRepository,
        dashboardSessionRepository: DashboardSessionRepository,
        dashboardQueryRunRepository: QueryRunRepository,
        dashboardCommandReceiptRepository: DashboardCommandReceiptRepository,
        dashboardQueryPlanningPort: DashboardQueryPlanningPort,
        dashboardExecutionAdmissionPort: DashboardExecutionAdmissionPort,
    ): DashboardSessionService = DashboardSessionService(
        transactionPort = dashboardTransactionPort,
        dashboardRepository = dashboardRepository,
        sessionRepository = dashboardSessionRepository,
        queryRunRepository = dashboardQueryRunRepository,
        commandReceiptRepository = dashboardCommandReceiptRepository,
        queryPlanningPort = dashboardQueryPlanningPort,
        executionAdmissionPort = dashboardExecutionAdmissionPort,
    )

    @Bean
    fun dashboardScheduleOwnerPort(
        dashboardTransactionPort: DashboardTransactionPort,
        dashboardScheduleRepository: DashboardScheduleRepository,
        dashboardSessionRepository: DashboardSessionRepository,
        dashboardQueryRunRepository: QueryRunRepository,
        dashboardExecutionAdmissionPort: DashboardExecutionAdmissionPort,
        dashboardSessionUseCase: DashboardSessionService,
    ): cc.monomer.dashboard.api.DashboardScheduleOwnerPort = cc.monomer.dashboard.application.DashboardScheduleService(
        dashboardTransactionPort, dashboardScheduleRepository, dashboardSessionRepository,
        dashboardQueryRunRepository, dashboardExecutionAdmissionPort, dashboardSessionUseCase,
    )

    @Bean
    fun dashboardScheduleLifecycle(owner: cc.monomer.dashboard.api.DashboardScheduleOwnerPort): DashboardScheduleLifecycle = DashboardScheduleLifecycle(owner)

    @Bean
    fun dashboardDraftUseCase(
        searchProjection: cc.monomer.dashboard.api.DashboardSearchProjection,
        dashboardTransactionPort: DashboardTransactionPort,
        dashboardRepository: DashboardRepository,
        dashboardDraftRepository: DashboardDraftRepository,
        dashboardQueryRunRepository: QueryRunRepository,
        dashboardCommandReceiptRepository: DashboardCommandReceiptRepository,
        dashboardQueryPlanningPort: DashboardQueryPlanningPort,
        dashboardExecutionAdmissionPort: DashboardExecutionAdmissionPort,
        dashboardExecutionCancellationPort: DashboardExecutionCancellationPort,
    ): DashboardDraftUseCase = DashboardDraftService(
        searchProjection = searchProjection,
        transactionPort = dashboardTransactionPort,
        dashboardRepository = dashboardRepository,
        draftRepository = dashboardDraftRepository,
        queryRunRepository = dashboardQueryRunRepository,
        commandReceiptRepository = dashboardCommandReceiptRepository,
        queryPlanningPort = dashboardQueryPlanningPort,
        executionAdmissionPort = dashboardExecutionAdmissionPort,
        executionCancellationPort = dashboardExecutionCancellationPort,
    )

    @Bean
    fun dashboardOwnerPort(
        @Qualifier("jdbcDispatcher") blockingDispatcher: CoroutineDispatcher,
        dashboardUseCase: DashboardUseCase,
        dashboardDraftUseCase: DashboardDraftUseCase,
        dashboardSessionUseCase: DashboardSessionUseCase,
        lifecycle: cc.monomer.dashboard.application.DashboardLifecycleService,
    ): DashboardOwnerPort = DashboardOwnerPortAdapter(dashboardUseCase, dashboardDraftUseCase, dashboardSessionUseCase, blockingDispatcher, lifecycle)

    @Bean
    fun dashboardSeedDefinitionUseCase(dashboards: DashboardUseCase): cc.monomer.dashboard.api.DashboardSeedDefinitionUseCase =
        cc.monomer.dashboard.application.DashboardSeedDefinitionService(dashboards)

    @Bean
    fun dashboardQueryRunSettlementUseCase(
        dashboardQueryRunRepository: QueryRunRepository,
        dashboardDraftRepository: DashboardDraftRepository,
    ): DashboardQueryRunSettlementService = DashboardQueryRunSettlementService(
        queryRunRepository = dashboardQueryRunRepository,
        draftRepository = dashboardDraftRepository,
    )

    @Bean
    fun dashboardExecutionRunOwnerSettlementPort(
        dashboardQueryRunSettlementUseCase: DashboardQueryRunSettlementService,
        dashboardQueryRunRepository: QueryRunRepository,
        dashboardSessionEventHub: DashboardSessionEventHub,
        dashboardDraftEventHub: DashboardDraftEventHub,
    ): ExecutionRunOwnerSettlementPort =
        DashboardExecutionSettlementAdapter(
            dashboardSettlement = dashboardQueryRunSettlementUseCase,
            queryRunRepository = dashboardQueryRunRepository,
            eventHub = dashboardSessionEventHub,
            draftEventHub = dashboardDraftEventHub,
        )
}
