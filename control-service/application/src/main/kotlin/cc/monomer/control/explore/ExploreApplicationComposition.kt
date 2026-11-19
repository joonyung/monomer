package cc.monomer.control.explore

import cc.monomer.connector.adapter.outbound.postgres.PostgresManagedCredentialStore
import cc.monomer.connector.api.ConnectorExecutionAccessUseCase
import cc.monomer.connector.api.ConnectorRuntimeBindingProjectionPort
import cc.monomer.connector.application.ManagedCredentialReadPort
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.ControlProductAuthorityProperties
import cc.monomer.control.ControlRuntimeProperties
import cc.monomer.control.toPositiveCeilingSeconds
import cc.monomer.execution.adapter.inbound.grpc.ProtoRawSqlRunSpecFactory
import cc.monomer.execution.api.ExecutionArtifactStore
import cc.monomer.execution.api.ExecutionRunAdmissionPort
import cc.monomer.execution.api.ExecutionRunPort
import cc.monomer.execution.api.ExecutionRunQueryPort
import cc.monomer.execution.api.OwnerExecutionRunCancellationPort
import cc.monomer.explore.adapter.outbound.postgres.PostgresExploreRunRepository
import cc.monomer.explore.adapter.outbound.postgres.PostgresExploreActivityReadPort
import cc.monomer.explore.adapter.outbound.postgres.SpringActiveExploreInitialAdmissionTransactionPort
import cc.monomer.explore.adapter.outbound.postgres.SpringActiveExploreOwnerSettlementTransactionPort
import cc.monomer.explore.adapter.outbound.postgres.SpringExploreResultReadTransactionPort
import cc.monomer.explore.adapter.outbound.postgres.SpringExploreTransactionPort
import cc.monomer.explore.adapter.outbound.postgres.SpringRetryingExploreInitialAdmissionTransactionPort
import cc.monomer.explore.application.ExploreAdmissionCoordinator
import cc.monomer.explore.application.ExploreCancellationHandler
import cc.monomer.explore.application.ExploreResultReader
import cc.monomer.explore.application.ExploreService
import cc.monomer.explore.application.ExploreSettlementAdapter
import cc.monomer.semantic.application.SemanticQueryUseCase
import java.time.Clock
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineDispatcher
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class ExploreApplicationComposition {
    @Bean
    fun postgresExploreRunRepository(
        productControlDataSource: DataSource
    ): PostgresExploreRunRepository =
        PostgresExploreRunRepository(productControlDataSource)

    @Bean
    fun postgresExploreActivityReadPort(
        productControlDataSource: DataSource,
        properties: ControlProductAuthorityProperties,
        @Qualifier("jdbcDispatcher") blockingDispatcher: CoroutineDispatcher,
    ): PostgresExploreActivityReadPort =
        PostgresExploreActivityReadPort(
            dataSource = productControlDataSource,
            activityReadTransactionPort = SpringExploreTransactionPort(
                TransactionTemplate(DataSourceTransactionManager(productControlDataSource)).apply {
                    propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
                    isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
                    isReadOnly = true
                    timeout = properties.transaction.transactionTimeout.toPositiveCeilingSeconds()
                },
                blockingDispatcher,
            ),
            statementTimeout = properties.transaction.statementTimeout,
            lockTimeout = properties.transaction.lockTimeout,
        )

    @Bean
    fun activeExploreInitialAdmissionTransactionPort(
        productControlDataSource: DataSource
    ): SpringActiveExploreInitialAdmissionTransactionPort =
        SpringActiveExploreInitialAdmissionTransactionPort(productControlDataSource)

    @Bean
    fun exploreInitialAdmissionTransactionPort(
        productControlDataSource: DataSource,
        activeExploreInitialAdmissionTransactionPort:
            SpringActiveExploreInitialAdmissionTransactionPort,
        properties: ControlProductAuthorityProperties,
        @Qualifier("jdbcDispatcher") blockingDispatcher: CoroutineDispatcher,
    ): SpringRetryingExploreInitialAdmissionTransactionPort {
        val transactionTemplate =
            TransactionTemplate(DataSourceTransactionManager(productControlDataSource)).apply {
                propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
                isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
                timeout = properties.transaction.transactionTimeout.toPositiveCeilingSeconds()
            }
        return SpringRetryingExploreInitialAdmissionTransactionPort(
            transactionTemplate = transactionTemplate,
            activeTransactionPort = activeExploreInitialAdmissionTransactionPort,
            maximumAttempts = 3,
            blockingDispatcher = blockingDispatcher,
        )
    }

    @Bean
    fun activeExploreOwnerSettlementTransactionPort(
        productControlDataSource: DataSource
    ): SpringActiveExploreOwnerSettlementTransactionPort =
        SpringActiveExploreOwnerSettlementTransactionPort(productControlDataSource)

    @Bean
    fun exploreTransactionPort(
        productAuthorityTransactionOperations: TransactionTemplate,
        @Qualifier("jdbcDispatcher") blockingDispatcher: CoroutineDispatcher,
    ): SpringExploreTransactionPort =
        SpringExploreTransactionPort(productAuthorityTransactionOperations, blockingDispatcher)

    @Bean
    fun exploreResultReadTransactionPort(
        productControlDataSource: DataSource,
        runtimeProperties: ControlRuntimeProperties,
        properties: ControlProductAuthorityProperties,
        @Qualifier("jdbcDispatcher") blockingDispatcher: CoroutineDispatcher,
    ): SpringExploreResultReadTransactionPort {
        val transaction = properties.transaction
        return SpringExploreResultReadTransactionPort(
            dataSource = productControlDataSource,
            maximumAttempts =
                runtimeProperties.executionRuntime.limits.maximumExploreResultReadTransactionAttempts,
            transactionTimeout = transaction.transactionTimeout,
            statementTimeout = transaction.statementTimeout,
            lockTimeout = transaction.lockTimeout,
            blockingDispatcher = blockingDispatcher,
        )
    }

    @Bean
    fun exploreAdmissionCoordinator(
        exploreInitialAdmissionTransactionPort:
            SpringRetryingExploreInitialAdmissionTransactionPort,
        executionRunAdmissionPort: ExecutionRunAdmissionPort,
        postgresExploreRunRepository: PostgresExploreRunRepository
    ): ExploreAdmissionCoordinator =
        ExploreAdmissionCoordinator(
            initialAdmissionTransactionPort = exploreInitialAdmissionTransactionPort,
            executionRunAdmissionPort = executionRunAdmissionPort,
            repository = postgresExploreRunRepository
        )

    @Bean
    fun exploreResultReader(
        exploreResultReadTransactionPort: SpringExploreResultReadTransactionPort,
        postgresExploreRunRepository: PostgresExploreRunRepository,
        executionArtifactStore: ExecutionArtifactStore,
        controlClock: Clock
    ): ExploreResultReader =
        ExploreResultReader(
            resultReadTransactionPort = exploreResultReadTransactionPort,
            repository = postgresExploreRunRepository,
            executionArtifactStore = executionArtifactStore,
            clock = controlClock
        )

    @Bean
    fun exploreService(
        exploreAdmissionCoordinator: ExploreAdmissionCoordinator,
        exploreResultReader: ExploreResultReader,
        postgresExploreRunRepository: PostgresExploreRunRepository,
        @Qualifier("jdbcDispatcher") blockingDispatcher: CoroutineDispatcher,
    ): ExploreService =
        ExploreService(
            admissionCoordinator = exploreAdmissionCoordinator,
            resultReader = exploreResultReader,
            repository = postgresExploreRunRepository,
            blockingDispatcher = blockingDispatcher,
        )

    @Bean
    fun exploreSqlLaunchService(
        connectorExecutionAccessUseCase: ConnectorExecutionAccessUseCase,
        connectorRuntimeBindingProjectionPort:
            ConnectorRuntimeBindingProjectionPort,
        managedCredentialStore: PostgresManagedCredentialStore,
        executionRunPort: ExecutionRunPort,
        exploreService: ExploreService,
        postgresExploreRunRepository: PostgresExploreRunRepository,
        protoRawSqlRunSpecFactory: ProtoRawSqlRunSpecFactory,
        controlAdmissionFacade: ControlAdmissionFacade,
        consistentSnapshotTransactionRunner: cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner,
        controlClock: Clock,
        runtimeProperties: ControlRuntimeProperties
    ): ExploreSqlLaunchService =
        ExploreSqlLaunchService(
            connectorExecutionAccessUseCase = connectorExecutionAccessUseCase,
            connectorRuntimeBindingProjectionPort =
                connectorRuntimeBindingProjectionPort,
            managedCredentialReadPort = managedCredentialStore,
            executionRunPort = executionRunPort,
            exploreRunUseCase = exploreService,
            repository = postgresExploreRunRepository,
            rawSqlRunSpecFactory = protoRawSqlRunSpecFactory,
            controlAdmissionFacade = controlAdmissionFacade,
            consistentSnapshotTransactionRunner = consistentSnapshotTransactionRunner,
            clock = controlClock,
            runtimeProperties = runtimeProperties
        )

    @Bean
    fun exploreSemanticLaunchService(
        connectorExecutionAccessUseCase: ConnectorExecutionAccessUseCase,
        connectorRuntimeBindingProjectionPort: ConnectorRuntimeBindingProjectionPort,
        semanticQueryUseCase: SemanticQueryUseCase,
        managedCredentialReadPort: ManagedCredentialReadPort,
        executionRunPort: ExecutionRunPort,
        exploreService: ExploreService,
        postgresExploreRunRepository: PostgresExploreRunRepository,
        protoRawSqlRunSpecFactory: ProtoRawSqlRunSpecFactory,
        controlAdmissionFacade: ControlAdmissionFacade,
        consistentSnapshotTransactionRunner: cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner,
        controlClock: Clock,
        runtimeProperties: ControlRuntimeProperties,
    ): ExploreSemanticLaunchService =
        ExploreSemanticLaunchService(
            connectorExecutionAccessUseCase = connectorExecutionAccessUseCase,
            connectorRuntimeBindingProjectionPort = connectorRuntimeBindingProjectionPort,
            semanticQueryUseCase = semanticQueryUseCase,
            exploreRunUseCase = exploreService,
            repository = postgresExploreRunRepository,
            rawSqlRunSpecFactory = protoRawSqlRunSpecFactory,
            controlAdmissionFacade = controlAdmissionFacade,
            consistentSnapshotTransactionRunner = consistentSnapshotTransactionRunner,
            clock = controlClock,
            runtimeProperties = runtimeProperties,
            managedCredentialReadPort = managedCredentialReadPort,
            executionRunPort = executionRunPort,
        )

    @Bean
    fun exploreRunAccessService(
        postgresExploreRunRepository: PostgresExploreRunRepository,
        exploreService: ExploreService,
        executionRunQueryPort: ExecutionRunQueryPort,
        exploreCancellationHandler: ExploreCancellationHandler,
        controlAdmissionFacade: ControlAdmissionFacade,
        consistentSnapshotTransactionRunner: cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner,
        controlClock: Clock,
        @Qualifier("jdbcDispatcher") blockingDispatcher: CoroutineDispatcher,
    ): ExploreRunAccessService =
        ExploreRunAccessService(
            repository = postgresExploreRunRepository,
            exploreRunUseCase = exploreService,
            executionRunQueryPort = executionRunQueryPort,
            cancellationHandler = exploreCancellationHandler,
            controlAdmissionFacade = controlAdmissionFacade,
            consistentSnapshotTransactionRunner = consistentSnapshotTransactionRunner,
            clock = controlClock,
            blockingDispatcher = blockingDispatcher,
        )

    @Bean
    fun exploreSettlementAdapter(
        postgresExploreRunRepository: PostgresExploreRunRepository,
        activeExploreOwnerSettlementTransactionPort:
            SpringActiveExploreOwnerSettlementTransactionPort,
        runtimeProperties: ControlRuntimeProperties
    ): ExploreSettlementAdapter =
        ExploreSettlementAdapter(
            repository = postgresExploreRunRepository,
            activeTransactionPort = activeExploreOwnerSettlementTransactionPort,
            resultRetention = runtimeProperties.executionRuntime.limits.exploreResultRetention
        )

    @Bean
    fun exploreCancellationHandler(
        postgresExploreRunRepository: PostgresExploreRunRepository,
        exploreTransactionPort: SpringExploreTransactionPort,
        ownerExecutionRunCancellationPort: OwnerExecutionRunCancellationPort
    ): ExploreCancellationHandler =
        ExploreCancellationHandler(
            repository = postgresExploreRunRepository,
            transactionPort = exploreTransactionPort,
            runCancellationPort = ownerExecutionRunCancellationPort
        )
}
