@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control

import cc.monomer.connector.adapter.outbound.postgres.ManagedCredentialKeyRing
import cc.monomer.connector.adapter.outbound.postgres.PostgresManagedCredentialStore
import cc.monomer.connector.adapter.outbound.postgres.SecureRandomCredentialVaultNonceSource
import cc.monomer.execution.adapter.inbound.grpc.ProtoInlineQueryRunSpecFactory
import cc.monomer.execution.adapter.inbound.grpc.ProtoRawSqlRunSpecFactory
import cc.monomer.execution.adapter.inbound.grpc.ExecutableRequestAssignmentCodec
import cc.monomer.execution.adapter.outbound.grpc.GrpcExecutionRunClient
import cc.monomer.execution.adapter.outbound.grpc.shutdownQuietly
import cc.monomer.execution.adapter.outbound.postgres.PostgresRunDispatchPersistence
import cc.monomer.execution.adapter.outbound.postgres.PostgresExecutionRuntimeStore
import cc.monomer.execution.adapter.outbound.postgres.SpringOwnerSettlementTransactionRunner
import cc.monomer.execution.adapter.outbound.postgres.SpringExecutionRuntimeTransactionRunner
import cc.monomer.execution.adapter.outbound.s3.S3ExecutionArtifactStore
import cc.monomer.execution.adapter.outbound.s3.S3ExecutionArtifactStoreConfiguration
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutionRunOwnerSettlementPort
import cc.monomer.execution.api.ExecutionRunQueryPort
import cc.monomer.execution.api.ExecutionRunSettlementUseCase
import cc.monomer.execution.api.ExecutionRunUseCase
import cc.monomer.execution.api.ExecutionArtifactStore
import cc.monomer.execution.api.ExecutionRunPort
import cc.monomer.execution.api.OwnerExecutionRunCancellationPort
import cc.monomer.execution.application.ExecutionRunLimits
import cc.monomer.execution.application.ExecutionRunService
import cc.monomer.execution.application.ExecutionRunAdmissionService
import cc.monomer.execution.application.ExecutionRunSettlementCoordinator
import io.grpc.ManagedChannel
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider

@Configuration(proxyBeanMethods = false)
class ControlExecutionRuntimeConfiguration {
    @Bean
    fun executableRequestAssignmentCodec(): ExecutableRequestAssignmentCodec =
        ExecutableRequestAssignmentCodec()

    @Bean
    fun protoInlineQueryRunSpecFactory(): ProtoInlineQueryRunSpecFactory =
        ProtoInlineQueryRunSpecFactory()

    @Bean
    fun protoRawSqlRunSpecFactory(): ProtoRawSqlRunSpecFactory =
        ProtoRawSqlRunSpecFactory()

    @Bean
    fun postgresRunDispatchPersistence(
        productControlDataSource: DataSource,
        productAuthorityTransactionOperations: TransactionTemplate,
    ): PostgresRunDispatchPersistence =
        PostgresRunDispatchPersistence(
            dataSource = productControlDataSource,
            transactionOperations = productAuthorityTransactionOperations,
        )

    @Bean
    fun executionRunAdmissionService(
        postgresExecutionRuntimeStore: PostgresExecutionRuntimeStore,
        postgresRunDispatchPersistence: PostgresRunDispatchPersistence,
        executableRequestAssignmentCodec: ExecutableRequestAssignmentCodec,
        runtimeProperties: ControlRuntimeProperties
    ): ExecutionRunAdmissionService =
        ExecutionRunAdmissionService(
            repository = postgresExecutionRuntimeStore,
            dispatchIntentPort = postgresRunDispatchPersistence,
            assignmentCodec = executableRequestAssignmentCodec,
            limits =
                ExecutionRunLimits(
                    maximumNonTerminalRunsPerWorkspace =
                        runtimeProperties.executionRuntime.limits
                            .maximumNonTerminalRunsPerWorkspace,
                )
        )

    @Bean(destroyMethod = "close")
    fun managedCredentialKeyRing(
        runtimeProperties: ControlRuntimeProperties
    ): ManagedCredentialKeyRing {
        val properties = runtimeProperties.executionRuntime.credentialVault
        val decodedKeys =
            properties.keysBase64.mapValues { (_, encodedKey) ->
                Base64.getDecoder().decode(encodedKey)
            }
        return try {
            ManagedCredentialKeyRing(
                activeKeyVersion = properties.activeKeyVersion,
                keysByVersion = decodedKeys
            )
        } finally {
            decodedKeys.values.forEach { key -> key.fill(0) }
        }
    }

    @Bean
    fun managedCredentialStore(
        productControlDataSource: DataSource,
        managedCredentialKeyRing: ManagedCredentialKeyRing,
        controlClock: Clock,
        runtimeProperties: ControlRuntimeProperties
    ): PostgresManagedCredentialStore {
        val properties = runtimeProperties.executionRuntime.credentialVault
        return PostgresManagedCredentialStore(
            dataSource = productControlDataSource,
            keyRing = managedCredentialKeyRing,
            nonceSource =
                SecureRandomCredentialVaultNonceSource(SecureRandom()),
            clock = controlClock,
            blockingContext = Dispatchers.IO,
            maximumCredentialDocumentBytes = properties.maximumDocumentBytes,
            maximumCredentialFields = properties.maximumFields,
            maximumFieldNameBytes = properties.maximumFieldNameBytes,
            maximumFieldValueBytes = properties.maximumFieldValueBytes,
        )
    }

    @Bean(destroyMethod = "close")
    fun executionPlaneChannel(
        runtimeProperties: ControlRuntimeProperties
    ): ExecutionPlaneChannel {
        val (channel, client) =
            GrpcExecutionRunClient.plaintext(
                target = runtimeProperties.executionRuntime.executionTarget,
                maximumInboundMessageBytes =
                    runtimeProperties.grpc.maximumInboundMessageBytes
            )
        return ExecutionPlaneChannel(client, channel, runtimeProperties.grpc.shutdownGrace)
    }

    @Bean
    fun executionRunPort(
        executionPlaneChannel: ExecutionPlaneChannel
    ): ExecutionRunPort = executionPlaneChannel.client

    @Bean(destroyMethod = "close")
    fun executionRunDialCoordinator(
        executionRunPort: ExecutionRunPort,
        executionRunQueryPort: ExecutionRunQueryPort,
        executionRunUseCase: ExecutionRunUseCase,
        executionRunSettlementUseCase: ExecutionRunSettlementUseCase,
        postgresRunDispatchPersistence: PostgresRunDispatchPersistence,
        managedCredentialStore: PostgresManagedCredentialStore,
        executionArtifactStore: ExecutionArtifactStore,
        controlClock: Clock,
        runtimeProperties: ControlRuntimeProperties
    ): ExecutionRunDialCoordinator {
        val vault = runtimeProperties.executionRuntime.credentialVault
        return ExecutionRunDialCoordinator(
            executionRunPort = executionRunPort,
            executionRunQuery = executionRunQueryPort,
            executionRunUseCase = executionRunUseCase,
            executionRunSettlementUseCase = executionRunSettlementUseCase,
            ownerSettlementTypes = OWNER_SETTLED_EXECUTION_TYPES,
            dispatchIntentPort = postgresRunDispatchPersistence,
            credentialReadPort = managedCredentialStore,
            artifactStore = executionArtifactStore,
            clock = controlClock,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            executionDeadlineDuration = runtimeProperties.executionRuntime.limits.executionDeadlineDuration,
            maximumCredentialDocumentBytes = vault.maximumDocumentBytes,
            maximumCredentialFields = vault.maximumFields,
            maximumCredentialFieldNameBytes = vault.maximumFieldNameBytes,
            maximumCredentialFieldValueBytes = vault.maximumFieldValueBytes
        )
    }

    @Bean(destroyMethod = "close")
    fun postgresExecutionCancellationListener(
        properties: ControlProductAuthorityProperties,
        runtimeProperties: ControlRuntimeProperties,
        executionRunDialCoordinator: ExecutionRunDialCoordinator,
    ): cc.monomer.execution.adapter.outbound.postgres.PostgresExecutionCancellationListener =
        cc.monomer.execution.adapter.outbound.postgres.PostgresExecutionCancellationListener(
            jdbcUrl = properties.postgres.jdbcUrl,
            username = properties.postgres.username,
            password = properties.postgres.password,
            connectionTimeout = properties.postgres.connectionTimeout,
            statementTimeout = properties.transaction.statementTimeout,
            reconnectDelay = runtimeProperties.executionRuntime.limits.recoveryInterval,
            onRunChanged = { runId -> kotlinx.coroutines.runBlocking { executionRunDialCoordinator.reconcileLiveDial(runId) } },
            onReconnected = { kotlinx.coroutines.runBlocking { executionRunDialCoordinator.reconcileLiveDials() } },
        )

    @Bean
    fun executionCancellationListenerLifecycle(
        postgresExecutionCancellationListener: cc.monomer.execution.adapter.outbound.postgres.PostgresExecutionCancellationListener,
        controlExecutionRuntimeStartupVerifier: ControlExecutionRuntimeStartupVerifier,
    ): ExecutionCancellationListenerLifecycle = ExecutionCancellationListenerLifecycle(postgresExecutionCancellationListener)

    @Bean
    @Primary
    fun executionRunAdmissionPort(
        executionRunAdmissionService: ExecutionRunAdmissionService,
        executionRunDialCoordinator: ExecutionRunDialCoordinator
    ): DialingExecutionRunAdmissionPort =
        DialingExecutionRunAdmissionPort(
            delegate = executionRunAdmissionService,
            dialCoordinator = executionRunDialCoordinator
        )

    @Bean
    @Primary
    fun ownerExecutionRunCancellationPort(
        executionRunService: ExecutionRunService,
        executionRunDialCoordinator: ExecutionRunDialCoordinator
    ): OwnerExecutionRunCancellationPort =
        DialCancellingOwnerExecutionRunCancellationPort(
            delegate = executionRunService,
            dialCoordinator = executionRunDialCoordinator
        )

    @Bean
    fun executionRunMutationTransactionRunner(
        productControlDataSource: DataSource,
        properties: ControlProductAuthorityProperties,
        @Qualifier("jdbcDispatcher") jdbcDispatcher: CoroutineDispatcher,
    ): SpringExecutionRuntimeTransactionRunner = SpringExecutionRuntimeTransactionRunner(
        TransactionTemplate(DataSourceTransactionManager(productControlDataSource)).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
            timeout = properties.transaction.transactionTimeout.toPositiveCeilingSeconds()
        },
        jdbcDispatcher,
    )

    @Bean
    fun executionRunReadTransactionRunner(
        productControlDataSource: DataSource,
        properties: ControlProductAuthorityProperties,
        @Qualifier("jdbcDispatcher") jdbcDispatcher: CoroutineDispatcher,
    ): SpringExecutionRuntimeTransactionRunner = SpringExecutionRuntimeTransactionRunner(
        TransactionTemplate(DataSourceTransactionManager(productControlDataSource)).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            isReadOnly = true
            timeout = properties.transaction.transactionTimeout.toPositiveCeilingSeconds()
        },
        jdbcDispatcher,
    )

    @Bean
    fun executionRunService(
        postgresExecutionRuntimeStore: PostgresExecutionRuntimeStore,
        postgresRunDispatchPersistence: PostgresRunDispatchPersistence,
        executableRequestAssignmentCodec: ExecutableRequestAssignmentCodec,
        @Qualifier("executionRunMutationTransactionRunner")
        executionRunMutationTransactionRunner: SpringExecutionRuntimeTransactionRunner,
        @Qualifier("executionRunReadTransactionRunner")
        executionRunReadTransactionRunner: SpringExecutionRuntimeTransactionRunner,
    ): ExecutionRunService =
        ExecutionRunService(
            repository = postgresExecutionRuntimeStore,
            mutationTransactionRunner = executionRunMutationTransactionRunner,
            readTransactionRunner = executionRunReadTransactionRunner,
            dispatchIntentPort = postgresRunDispatchPersistence,
            assignmentCodec = executableRequestAssignmentCodec,
        )

    @Bean(destroyMethod = "close")
    fun executionArtifactCredentials(
        runtimeProperties: ControlRuntimeProperties
    ): ExecutionArtifactCredentials {
        val artifactStore = runtimeProperties.executionRuntime.artifactStore
        return when (artifactStore.credentialMode) {
            ControlArtifactCredentialMode.WORKLOAD -> {
                val provider = DefaultCredentialsProvider.builder().build()
                ExecutionArtifactCredentials(provider, provider)
            }
            ControlArtifactCredentialMode.LOCAL_TEST_STATIC ->
                ExecutionArtifactCredentials(
                    provider =
                        StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(
                                requireNotNull(artifactStore.localTestAccessKey),
                                requireNotNull(artifactStore.localTestSecretKey)
                            )
                        ),
                    closeable = null
                )
        }
    }

    @Bean(destroyMethod = "close")
    fun executionArtifactStore(
        runtimeProperties: ControlRuntimeProperties,
        executionArtifactCredentials: ExecutionArtifactCredentials,
        controlClock: Clock,
    ): S3ExecutionArtifactStore {
        val store = runtimeProperties.executionRuntime.artifactStore
        return S3ExecutionArtifactStore.create(
            configuration =
                S3ExecutionArtifactStoreConfiguration(
                    endpoint = store.endpoint,
                    region = store.region,
                    bucket = store.bucket,
                    objectPrefix = store.objectPrefix,
                    pathStyleAccessEnabled = store.pathStyleAccessEnabled,
                ),
            credentialsProvider = executionArtifactCredentials.provider,
            clock = controlClock,
            blockingDispatcher = Dispatchers.IO,
        )
    }

    @Bean
    fun executionRunSettlementUseCase(
        executionRunOwnerSettlementPorts: List<ExecutionRunOwnerSettlementPort>,
        postgresExecutionRuntimeStore: PostgresExecutionRuntimeStore,
        ownerSettlementTransactionRunner: SpringOwnerSettlementTransactionRunner,
    ): ExecutionRunSettlementUseCase =
        ExecutionRunSettlementCoordinator(
            ownerSettlementPorts = executionRunOwnerSettlementPorts,
            repository = postgresExecutionRuntimeStore,
            transactionRunner = ownerSettlementTransactionRunner,
        )

    @Bean
    fun ownerSettlementTransactionRunner(
        productControlDataSource: DataSource,
        properties: ControlProductAuthorityProperties,
        @Qualifier("jdbcDispatcher") jdbcDispatcher: CoroutineDispatcher,
    ): SpringOwnerSettlementTransactionRunner =
        SpringOwnerSettlementTransactionRunner(
            dataSource = productControlDataSource,
            transactionTimeoutSeconds =
                properties.transaction.transactionTimeout.toPositiveCeilingSeconds(),
            blockingDispatcher = jdbcDispatcher,
        )

    @Bean
    fun controlExecutionRuntimeStartupVerifier(
        productControlDataSource: DataSource,
    ): ControlExecutionRuntimeStartupVerifier =
        ControlExecutionRuntimeStartupVerifier(
            dataSource = productControlDataSource,
        ).also(ControlExecutionRuntimeStartupVerifier::verify)

    @Bean
    fun executionRunRecoveryLifecycle(
        executionRunUseCase: ExecutionRunUseCase,
        executionRunSettlementUseCase: ExecutionRunSettlementUseCase,
        executionArtifactStore: ExecutionArtifactStore,
        runtimeProperties: ControlRuntimeProperties,
        controlClock: Clock,
        controlExecutionRuntimeStartupVerifier: ControlExecutionRuntimeStartupVerifier,
    ): ExecutionRunRecoveryLifecycle =
        ExecutionRunRecoveryLifecycle(
            executionRunUseCase = executionRunUseCase,
            executionRunSettlementUseCase = executionRunSettlementUseCase,
            executionArtifactStore = executionArtifactStore,
            clock = controlClock,
            limits = runtimeProperties.executionRuntime.limits,
            ownerSettlementTypes = OWNER_SETTLED_EXECUTION_TYPES,
        )

}

private val OWNER_SETTLED_EXECUTION_TYPES = setOf(ExecutionOwnerType.EXPLORE, ExecutionOwnerType.DASHBOARD)

/**
 * Owns the Control to Execution channel. Spring closes it after every bean that dials through
 * [client], including the dial coordinator that waits for its in-flight dials first.
 */
class ExecutionPlaneChannel(
    val client: ExecutionRunPort,
    private val channel: ManagedChannel,
    private val shutdownGrace: java.time.Duration,
) : AutoCloseable {
    override fun close() {
        channel.shutdownQuietly(shutdownGrace.toMillis())
    }
}

class ExecutionArtifactCredentials(
    val provider: AwsCredentialsProvider,
    private val closeable: AutoCloseable?
) : AutoCloseable {
    override fun close() {
        closeable?.close()
    }

    override fun toString(): String = "ExecutionArtifactCredentials([REDACTED])"
}

class ControlExecutionRuntimeStartupVerifier(
    private val dataSource: DataSource,
) {
    @Volatile
    var verified: Boolean = false
        private set

    fun verify() {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { statement ->
                REQUIRED_EXECUTION_RELATIONS.forEach { relation ->
                    statement.setString(1, relation)
                    statement.executeQuery().use { resultSet ->
                        check(resultSet.next() && resultSet.getBoolean(1)) {
                            "execution runtime is missing relation $relation"
                        }
                    }
                }
            }
        }
        verified = true
    }

    internal companion object {
        val REQUIRED_EXECUTION_RELATIONS: Set<String> =
            sortedSetOf(
                "execution_run.runs",
                "execution_run.dispatch_intents",
                "connector.credential_versions",
                "connector.connectors",
                "connector.connector_mutation_receipts",
                "catalog.connector_catalog_selections",
                "catalog.selections",
                "catalog.list_refresh_jobs",
                "catalog.selection_option_lists",
                "catalog.selection_options",
                "catalog.ingest_jobs",
                "catalog.ingest_tasks",
                "catalog.current_objects",
                "explore.admission_keys",
                "explore.runs",
                "explore.semantic_run_details",
                "explore.terminal_receipts",
                "dashboard.dashboards",
                "dashboard.definition_changes",
                "dashboard.drafts",
                "dashboard.sessions",
                "dashboard.query_runs",
                "dashboard.command_receipts",
                "dashboard.schedule_cursors",
                "dashboard.schedule_occurrences",
            )
    }
}
