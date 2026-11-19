@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.human

import cc.monomer.control.jdbc.sqlStateChain
import cc.monomer.control.logExecutionRunAccepted
import cc.monomer.control.registerExecutionRunAcceptedAfterCommit
import cc.monomer.catalog.adapter.outbound.execution.CatalogInlineQueryAdapter
import cc.monomer.connector.api.ConnectorExecutionAccessUseCase
import cc.monomer.connector.api.ConnectorRuntimeBindingProjectionPort
import cc.monomer.connector.api.ConnectorWorkspaceReadUseCase
import cc.monomer.catalog.application.ingest.CatalogIngestApplicationService
import cc.monomer.catalog.adapter.outbound.arrow.CatalogArrowBatchDecoder
import cc.monomer.catalog.application.ingest.CatalogIngestCascadeRunner
import cc.monomer.catalog.adapter.inbound.lifecycle.CatalogIngestRunnerLifecycle
import cc.monomer.catalog.application.ingest.CatalogListRefreshApplicationService
import cc.monomer.catalog.application.CatalogBrowseService
import cc.monomer.catalog.application.CatalogBrowseUseCase
import cc.monomer.control.connector.application.ConnectorReadService
import cc.monomer.control.connector.application.ConnectorReadUseCase
import cc.monomer.control.ExecutionRunDialCoordinator
import cc.monomer.execution.adapter.inbound.grpc.ProtoInlineQueryRunSpecFactory
import cc.monomer.execution.application.ExecutionRunAdmissionService
import cc.monomer.execution.api.OwnerExecutionRunCancellationPort
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.beans.factory.annotation.Qualifier

/** Composition for connector reads, Catalog browsing, and the canonical Catalog ingestion path. */
@Configuration(proxyBeanMethods = false)
class ConnectorCatalogApplicationComposition {
    @Bean
    fun catalogConnectorSearchProjection(index: cc.monomer.search.api.SearchIndex) =
        cc.monomer.control.search.CatalogConnectorSearchProjection(index)

    @Bean
    fun connectorDependentStateRemovalPort(
        postgresCatalogConnectorStateRepository: cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogConnectorStateRepository,
    ): cc.monomer.connector.api.ConnectorDependentStateRemovalPort =
        cc.monomer.catalog.application.CatalogConnectorStateRemoval(postgresCatalogConnectorStateRepository)

    @Bean
    fun catalogSearchReadPort(dataSource: javax.sql.DataSource): cc.monomer.catalog.api.CatalogSearchReadPort =
        cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogSearchReadPort(dataSource)

    @Bean
    fun catalogConnectorSummaryUseCase(
        dataSource: javax.sql.DataSource,
        catalogTransactionPort: cc.monomer.catalog.application.CatalogTransactionPort,
    ): cc.monomer.catalog.application.CatalogConnectorSummaryUseCase =
        cc.monomer.catalog.application.CatalogConnectorSummaryService(
            cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogConnectorSummaryReadPort(dataSource),
            catalogTransactionPort,
        )

    @Bean
    fun connectorReadUseCase(connectorWorkspaceReadUseCase: ConnectorWorkspaceReadUseCase): ConnectorReadUseCase =
        ConnectorReadService(connectorWorkspaceReadUseCase)

    @Bean
    fun catalogInstanceId(): String = UUID.randomUUID().toString()

    @Bean
    fun catalogProvisioningUseCase(ingest: cc.monomer.catalog.application.ingest.CatalogIngestApplicationPort): cc.monomer.catalog.api.CatalogProvisioningUseCase =
        cc.monomer.catalog.application.ingest.CatalogProvisioningService(ingest)

    @Bean
    fun catalogBrowseUseCase(
        catalogBrowseReadPort: cc.monomer.catalog.application.CatalogBrowseReadPort,
        postgresCatalogSelectionRepository: cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogSelectionRepository,
        catalogTransactionPort: cc.monomer.catalog.application.CatalogTransactionPort,
    ): CatalogBrowseUseCase =
        CatalogBrowseService(
            catalogBrowseReadPort,
            { workspaceId, connectorId, entry ->
                postgresCatalogSelectionRepository.currentSelection(workspaceId, connectorId, entry)
                    ?.selection?.selectedSchemas?.map { it.schemaName }?.toSet().orEmpty()
            },
            catalogTransactionPort,
        )

    @Bean
    fun catalogIngestCascadeRunner(
        postgresCatalogIngestRepository: cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogIngestRepository,
        catalogTransactionPort: cc.monomer.catalog.application.CatalogTransactionPort,
        protoInlineQueryRunSpecFactory: ProtoInlineQueryRunSpecFactory,
        executionRunAdmissionService: ExecutionRunAdmissionService,
        executionRunDialCoordinator: ExecutionRunDialCoordinator,
        productControlClock: Clock,
        runtimeProperties: cc.monomer.control.ControlRuntimeProperties,
    ): CatalogIngestCascadeRunner {
        val limits = runtimeProperties.executionRuntime.limits
        return CatalogIngestCascadeRunner(
            repository = postgresCatalogIngestRepository,
            transactionPort = catalogTransactionPort,
            createInlineQueryPort = { capturedBinding, capturedWorkspaceId, capturedOwner, ownerTransaction ->
                CatalogInlineQueryAdapter(
                    runSpecFactory = protoInlineQueryRunSpecFactory,
                    admittedRunAdmission = executionRunAdmissionService,
                    dispatchReservationDuration = limits.dispatchReservationDuration,
                    executionDeadlineDuration = limits.executionDeadlineDuration,
                    maximumRecordBatchRows = 2_000,
                    maximumRecordBatchBytes = limits.maximumRunFrameBytes,
                    maximumCellBytes = 4 * 1_024 * 1_024,
                    connectorBinding = capturedBinding,
                    transactionPort = ownerTransaction,
                    workspaceId = capturedWorkspaceId,
                    owner = capturedOwner,
                    clock = productControlClock,
                    runInline = executionRunDialCoordinator::runInline,
                    registerAcceptanceAfterCommit = ::registerExecutionRunAcceptedAfterCommit,
                    logAcceptance = ::logExecutionRunAccepted,
                )
            },
            clock = productControlClock,
            heartbeatInterval = limits.recoveryInterval,
            decodeBatch = CatalogArrowBatchDecoder::decode,
            failureSqlStates = { it.sqlStateChain() },
        )
    }

    @Bean
    fun catalogIngestApplicationService(
        postgresCatalogSelectionRepository: cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogSelectionRepository,
        postgresCatalogIngestRepository: cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogIngestRepository,
        catalogTransactionPort: cc.monomer.catalog.application.CatalogTransactionPort,
        connectorExecutionAccessUseCase: ConnectorExecutionAccessUseCase,
        connectorExecutionAccessInOwnerTransactionPort: cc.monomer.connector.api.ConnectorExecutionAccessInOwnerTransactionPort,
        connectorRuntimeBindingProjectionPort: ConnectorRuntimeBindingProjectionPort,
        catalogIngestCascadeRunner: CatalogIngestCascadeRunner,
        ownerExecutionRunCancellationPort: OwnerExecutionRunCancellationPort,
        productControlClock: Clock,
        @Qualifier("applicationAsyncScope") applicationAsyncScope: CoroutineScope,
        catalogInstanceId: String,
    ): CatalogIngestApplicationService = CatalogIngestApplicationService(
        selectionRepository = postgresCatalogSelectionRepository,
        repository = postgresCatalogIngestRepository,
        transactionPort = catalogTransactionPort,
        connectorExecutionAccessUseCase = connectorExecutionAccessUseCase,
        connectorAccessInOwnerTransaction = connectorExecutionAccessInOwnerTransactionPort,
        connectorRuntimeBindingProjection = connectorRuntimeBindingProjectionPort,
        cascadeRunner = catalogIngestCascadeRunner,
        ownerExecutionRunCancellation = ownerExecutionRunCancellationPort,
        clock = productControlClock,
        runnerScope = applicationAsyncScope,
        runnerInstanceId = catalogInstanceId,
    )

    @Bean
    fun catalogListRefreshApplicationService(
        postgresCatalogListRefreshRepository: cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogListRefreshRepository,
        catalogTransactionPort: cc.monomer.catalog.application.CatalogTransactionPort,
        connectorExecutionAccessUseCase: ConnectorExecutionAccessUseCase,
        connectorExecutionAccessInOwnerTransactionPort: cc.monomer.connector.api.ConnectorExecutionAccessInOwnerTransactionPort,
        connectorRuntimeBindingProjectionPort: ConnectorRuntimeBindingProjectionPort,
        protoInlineQueryRunSpecFactory: ProtoInlineQueryRunSpecFactory,
        executionRunAdmissionService: ExecutionRunAdmissionService,
        executionRunDialCoordinator: ExecutionRunDialCoordinator,
        ownerExecutionRunCancellationPort: OwnerExecutionRunCancellationPort,
        productControlClock: Clock,
        @Qualifier("applicationAsyncScope") applicationAsyncScope: CoroutineScope,
        catalogInstanceId: String,
        runtimeProperties: cc.monomer.control.ControlRuntimeProperties,
    ): CatalogListRefreshApplicationService {
        val limits = runtimeProperties.executionRuntime.limits
        return CatalogListRefreshApplicationService(
            repository = postgresCatalogListRefreshRepository,
            transactionPort = catalogTransactionPort,
            connectorExecutionAccessUseCase = connectorExecutionAccessUseCase,
            connectorAccessInOwnerTransaction = connectorExecutionAccessInOwnerTransactionPort,
            connectorRuntimeBindingProjection = connectorRuntimeBindingProjectionPort,
            createInlineQueryPort = { capturedBinding, capturedWorkspaceId, capturedOwner, ownerTransaction ->
                CatalogInlineQueryAdapter(
                    runSpecFactory = protoInlineQueryRunSpecFactory,
                    admittedRunAdmission = executionRunAdmissionService,
                    dispatchReservationDuration = limits.dispatchReservationDuration,
                    executionDeadlineDuration = limits.executionDeadlineDuration,
                    maximumRecordBatchRows = 2_000,
                    maximumRecordBatchBytes = limits.maximumRunFrameBytes,
                    maximumCellBytes = 4 * 1_024 * 1_024,
                    connectorBinding = capturedBinding,
                    transactionPort = ownerTransaction,
                    workspaceId = capturedWorkspaceId,
                    owner = capturedOwner,
                    clock = productControlClock,
                    runInline = executionRunDialCoordinator::runInline,
                    registerAcceptanceAfterCommit = ::registerExecutionRunAcceptedAfterCommit,
                    logAcceptance = ::logExecutionRunAccepted,
                )
            },
            ownerExecutionRunCancellation = ownerExecutionRunCancellationPort,
            clock = productControlClock,
            runnerScope = applicationAsyncScope,
            runnerInstanceId = catalogInstanceId,
            heartbeatInterval = limits.recoveryInterval,
            decodeBatch = CatalogArrowBatchDecoder::decode,
            failureSqlStates = { it.sqlStateChain() },
        )
    }

    @Bean
    fun catalogIngestRunnerLifecycle(
        service: CatalogIngestApplicationService,
        catalogListRefreshApplicationService: CatalogListRefreshApplicationService,
        postgresCatalogIngestRepository: cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogIngestRepository,
        postgresCatalogListRefreshRepository: cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogListRefreshRepository,
        catalogTransactionPort: cc.monomer.catalog.application.CatalogTransactionPort,
        productControlClock: Clock,
        runtimeProperties: cc.monomer.control.ControlRuntimeProperties,
    ): CatalogIngestRunnerLifecycle =
        CatalogIngestRunnerLifecycle(
            service = service,
            listRefreshService = catalogListRefreshApplicationService,
            ingestRepository = postgresCatalogIngestRepository,
            listRefreshRepository = postgresCatalogListRefreshRepository,
            transactionPort = catalogTransactionPort,
            clock = productControlClock,
            recoveryInterval = runtimeProperties.executionRuntime.limits.recoveryInterval,
            orphanTimeout = runtimeProperties.executionRuntime.limits.orphanTimeout,
        )

}
