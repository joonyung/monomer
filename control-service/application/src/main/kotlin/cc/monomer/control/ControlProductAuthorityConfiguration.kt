@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control

import kotlinx.coroutines.CoroutineDispatcher
import cc.monomer.identity.domain.model.DeveloperAccessPolicy

import cc.monomer.catalog.adapter.outbound.postgres.CatalogTransactionTimeouts
import cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogCurrentObjectsReadPort
import cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogActivityReadPort
import cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogConnectorStateRepository
import cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogIngestRepository
import cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogListRefreshRepository
import cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogSelectionRepository
import cc.monomer.catalog.adapter.outbound.postgres.SpringCatalogTransactionPort
import cc.monomer.connector.adapter.outbound.postgres.ConnectorTransactionTimeouts
import cc.monomer.connector.adapter.outbound.postgres.ConnectorExecutionAccessReadTimeouts
import cc.monomer.connector.adapter.outbound.postgres.PostgresConnectorMutationKeyLockPort
import cc.monomer.connector.adapter.outbound.postgres.PostgresConnectorRepository
import cc.monomer.connector.adapter.outbound.postgres.SpringConnectorExecutionAccessInOwnerTransactionPort
import cc.monomer.connector.adapter.outbound.postgres.SpringConnectorExecutionAccessReadPort
import cc.monomer.connector.adapter.outbound.postgres.SpringConnectorCurrentReadPort
import cc.monomer.connector.adapter.outbound.postgres.SpringConnectorTransactionPort
import cc.monomer.connector.api.ConnectorExecutionAccessInOwnerTransactionPort
import cc.monomer.connector.api.ConnectorExecutionAccessUseCase
import cc.monomer.connector.api.ConnectorWorkspaceReadUseCase
import cc.monomer.connector.api.ConnectorRuntimeBindingProjectionPort
import cc.monomer.connector.application.ConnectorAuthorityService
import cc.monomer.connector.application.ConnectorRuntimeBindingProjector
import cc.monomer.connector.application.ConnectorExecutionAccessService
import cc.monomer.connector.application.ManagedCredentialWritePort
import cc.monomer.identity.application.agent.AgentOAuthTokenHasher
import cc.monomer.identity.domain.port.CurrentUserCredentialVerifier
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.application.IdentityAdmissionService
import cc.monomer.identity.outbound.postgres.AuthSchemaVerifier
import cc.monomer.identity.outbound.postgres.PostgresCanonicalIdentityAdmissionSnapshot
import cc.monomer.execution.adapter.outbound.postgres.PostgresExecutionRuntimeStore
import cc.monomer.semantic.adapter.outbound.metricflow.MetricFlowSemanticRenderer
import cc.monomer.semantic.adapter.outbound.postgres.PostgresSemanticRepository
import cc.monomer.semantic.adapter.outbound.postgres.SemanticTransactionTimeouts
import cc.monomer.semantic.adapter.outbound.postgres.SpringSemanticTransactionPort
import cc.monomer.semantic.application.SemanticMetricFlowPort
import com.fasterxml.jackson.databind.ObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.time.Clock
import java.time.Duration
import javax.sql.DataSource
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ControlProductAuthorityProperties::class)
class ControlProductAuthorityConfiguration {
    @Bean
    fun productControlObjectMapper(): ObjectMapper =
        ObjectMapper().findAndRegisterModules()

    @Bean(destroyMethod = "close")
    fun productControlDataSource(
        properties: ControlProductAuthorityProperties
    ): HikariDataSource {
        val postgres = properties.postgres
        val hikari =
            HikariConfig().also { configuration ->
                configuration.jdbcUrl = postgres.jdbcUrl
                configuration.username = postgres.username
                configuration.password = postgres.password
                configuration.maximumPoolSize = postgres.maximumPoolSize
                configuration.minimumIdle = 0
                configuration.connectionTimeout = postgres.connectionTimeout.toMillis()
                configuration.validationTimeout = postgres.validationTimeout.toMillis()
                configuration.initializationFailTimeout = -1
                configuration.poolName = "monomer-product-authority"
                configuration.isAutoCommit = true
            }
        return verifyIdentitySchemaBeforeExpose(HikariDataSource(hikari))
    }

    internal fun verifyIdentitySchemaBeforeExpose(dataSource: HikariDataSource): HikariDataSource {
        var verificationSucceeded = false
        try {
            AuthSchemaVerifier(
                dataSource = dataSource,
                maximumAttempts = AUTH_SCHEMA_VERIFICATION_MAXIMUM_ATTEMPTS,
                retryDelayMillis = AUTH_SCHEMA_VERIFICATION_RETRY_DELAY_MILLIS,
                sleep = Thread::sleep,
            ).verify()
            verificationSucceeded = true
            return dataSource
        } finally {
            if (!verificationSucceeded) {
                dataSource.close()
            }
        }
    }

    @Bean
    fun productControlClock(): Clock = Clock.systemUTC()

    @Bean
    fun productAuthorityTransactionManager(
        productControlDataSource: DataSource
    ): DataSourceTransactionManager =
        DataSourceTransactionManager(productControlDataSource)

    @Bean
    fun productAuthorityTransactionOperations(
        productAuthorityTransactionManager: DataSourceTransactionManager,
        properties: ControlProductAuthorityProperties
    ): TransactionTemplate =
        TransactionTemplate(productAuthorityTransactionManager).also { template ->
            template.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
            template.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            template.timeout =
                properties.transaction.transactionTimeout.toPositiveCeilingSeconds()
        }

    @Bean
    fun postgresCanonicalIdentityAdmissionSnapshot(
        @Qualifier("postgresJdbcTemplate") identityRuntimeJdbcTemplate: NamedParameterJdbcTemplate,
        transactionRunner: TransactionRunner,
        currentUserCredentialVerifier: CurrentUserCredentialVerifier,
        agentOAuthTokenHasher: AgentOAuthTokenHasher,
        productControlClock: Clock,
        runtimeProperties: ControlRuntimeProperties,
        oauthProperties: cc.monomer.control.auth.inbound.agentoauth.AgentOAuthProperties,
        developerAccessPolicy: DeveloperAccessPolicy,
    ): PostgresCanonicalIdentityAdmissionSnapshot =
        PostgresCanonicalIdentityAdmissionSnapshot(
            jdbc = identityRuntimeJdbcTemplate,
            transactionRunner = transactionRunner,
            currentUserCredentialVerifier = currentUserCredentialVerifier,
            agentOAuthTokenHasher = agentOAuthTokenHasher,
            clock = productControlClock,
            developerAccessPolicy = developerAccessPolicy,
            agentResourceAudienceByExpectedResource =
                mapOf(
                    ExpectedOAuthResource.AGENT_REST to runtimeProperties.security.agentResource,
                    ExpectedOAuthResource.AGENT_MCP to oauthProperties.mcpResource,
                ),
            )

    @Bean
    fun identityAdmissionService(
        postgresCanonicalIdentityAdmissionSnapshot: PostgresCanonicalIdentityAdmissionSnapshot,
    ): IdentityAdmissionService =
        IdentityAdmissionService(
            snapshotPort = postgresCanonicalIdentityAdmissionSnapshot,
        )

    @Bean
    fun currentDefinitionResourceAuthority(
        semanticRead: cc.monomer.semantic.api.SemanticCurrentReadUseCase,
        catalogRead: cc.monomer.catalog.api.CatalogCurrentObjectsReadPort,
    ): CurrentDefinitionResourceAuthority = CurrentDefinitionResourceAuthority(semanticRead, catalogRead)

    @Bean
    fun controlAdmissionFacade(
        identityAdmissionService: IdentityAdmissionService,
        productControlClock: Clock
    ): ControlAdmissionFacade =
        ControlAdmissionFacade(
            identityAdmissionPort = identityAdmissionService,
            clock = productControlClock
        )

    @Bean
    fun postgresExecutionRuntimeStore(
        productControlDataSource: DataSource
    ): PostgresExecutionRuntimeStore =
        PostgresExecutionRuntimeStore(productControlDataSource)

    @Bean
    fun postgresConnectorRepository(
        productControlDataSource: DataSource,
        objectMapper: ObjectMapper
    ): PostgresConnectorRepository =
        PostgresConnectorRepository(
            dataSource = productControlDataSource,
            objectMapper = objectMapper
        )

    @Bean
    fun connectorTransactionPort(
        productControlDataSource: DataSource,
        productAuthorityTransactionManager: DataSourceTransactionManager,
        @Qualifier("jdbcDispatcher") blockingDispatcher: CoroutineDispatcher,
        properties: ControlProductAuthorityProperties
    ): SpringConnectorTransactionPort {
        val transaction = properties.transaction
        val transactionOperations =
            TransactionTemplate(productAuthorityTransactionManager).also { template ->
                template.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
                template.isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
                template.timeout = transaction.transactionTimeout.toPositiveCeilingSeconds()
            }
        return SpringConnectorTransactionPort(
            dataSource = productControlDataSource,
            transactionOperations = transactionOperations,
            blockingDispatcher = blockingDispatcher,
            timeouts =
                ConnectorTransactionTimeouts(
                    transaction = transaction.transactionTimeout,
                    statement = transaction.statementTimeout,
                    lock = transaction.lockTimeout
                )
        )
    }

    @Bean
    fun connectorExecutionAccessReadTransactionOperations(
        productAuthorityTransactionManager: DataSourceTransactionManager,
        properties: ControlProductAuthorityProperties
    ): TransactionOperations =
        TransactionTemplate(productAuthorityTransactionManager).also { template ->
            template.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
            template.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            template.isReadOnly = true
            template.timeout = properties.transaction.transactionTimeout.toPositiveCeilingSeconds()
        }

    @Bean
    fun connectorExecutionAccessReadPort(
        @Qualifier("jdbcDispatcher") blockingDispatcher: CoroutineDispatcher,
        productControlDataSource: DataSource,
        connectorExecutionAccessReadTransactionOperations: TransactionOperations,
        postgresConnectorRepository: PostgresConnectorRepository,
        properties: ControlProductAuthorityProperties
    ): SpringConnectorExecutionAccessReadPort {
        val transaction = properties.transaction
        return SpringConnectorExecutionAccessReadPort(
            dataSource = productControlDataSource,
            transactionOperations = connectorExecutionAccessReadTransactionOperations,
            blockingDispatcher = blockingDispatcher,
            queryPort = postgresConnectorRepository,
            timeouts =
                ConnectorExecutionAccessReadTimeouts(
                    transaction = transaction.transactionTimeout,
                    statement = transaction.statementTimeout
                )
        )
    }

    @Bean
    fun connectorCurrentReadPort(
        @Qualifier("jdbcDispatcher") blockingDispatcher: CoroutineDispatcher,
        productControlDataSource: DataSource,
        connectorExecutionAccessReadTransactionOperations: TransactionOperations,
        postgresConnectorRepository: PostgresConnectorRepository,
        properties: ControlProductAuthorityProperties
    ): SpringConnectorCurrentReadPort {
        val transaction = properties.transaction
        return SpringConnectorCurrentReadPort(
            dataSource = productControlDataSource,
            transactionOperations = connectorExecutionAccessReadTransactionOperations,
            blockingDispatcher = blockingDispatcher,
            queryPort = postgresConnectorRepository,
            timeouts =
                ConnectorExecutionAccessReadTimeouts(
                    transaction = transaction.transactionTimeout,
                    statement = transaction.statementTimeout
                )
        )
    }

    @Bean
    fun connectorWorkspaceReadUseCase(
        connectorCurrentReadPort: SpringConnectorCurrentReadPort
    ): ConnectorWorkspaceReadUseCase = connectorCurrentReadPort

    @Bean
    fun connectorExecutionAccessUseCase(
        connectorExecutionAccessReadPort: SpringConnectorExecutionAccessReadPort
    ): ConnectorExecutionAccessUseCase =
        ConnectorExecutionAccessService(connectorExecutionAccessReadPort)

    @Bean
    fun connectorExecutionAccessInOwnerTransactionPort(
        productControlDataSource: DataSource,
        postgresConnectorRepository: PostgresConnectorRepository
    ): ConnectorExecutionAccessInOwnerTransactionPort =
        SpringConnectorExecutionAccessInOwnerTransactionPort(
            dataSource = productControlDataSource,
            queryPort = postgresConnectorRepository
        )

    @Bean
    fun connectorRuntimeBindingProjectionPort():
        ConnectorRuntimeBindingProjectionPort =
        ConnectorRuntimeBindingProjector()

    @Bean
    fun postgresConnectorMutationKeyLockPort(
        productControlDataSource: DataSource,
        properties: ControlProductAuthorityProperties
    ): PostgresConnectorMutationKeyLockPort =
        PostgresConnectorMutationKeyLockPort(
            dataSource = productControlDataSource,
            queryTimeout = properties.transaction.lockTimeout
        )

    @Bean
    fun connectorAuthorityService(
        postgresConnectorRepository: PostgresConnectorRepository,
        connectorTransactionPort: SpringConnectorTransactionPort,
        postgresConnectorMutationKeyLockPort: PostgresConnectorMutationKeyLockPort,
        managedCredentialWritePort: ManagedCredentialWritePort,
        searchProjection: cc.monomer.connector.api.ConnectorSearchProjectionPort,
        dependentStateRemoval: cc.monomer.connector.api.ConnectorDependentStateRemovalPort,
        workspaceSamples: cc.monomer.identity.api.WorkspaceSampleQuery,
        productControlClock: Clock,
    ): ConnectorAuthorityService =
        ConnectorAuthorityService(
            repository = postgresConnectorRepository,
            transactionPort = connectorTransactionPort,
            mutationKeyLockPort = postgresConnectorMutationKeyLockPort,
            credentialWritePort = managedCredentialWritePort,
            searchProjection = searchProjection,
            dependentStateRemoval = dependentStateRemoval,
            workspaceSampleQuery = workspaceSamples,
            clock = productControlClock
        )

    @Bean
    fun sampleConnectorUseCase(authority: ConnectorAuthorityService): cc.monomer.connector.api.SampleConnectorUseCase =
        cc.monomer.connector.application.SampleConnectorService(authority)

    @Bean
    fun postgresSemanticRepository(
        productControlDataSource: DataSource,
        objectMapper: ObjectMapper
    ): PostgresSemanticRepository =
        PostgresSemanticRepository(
            dataSource = productControlDataSource,
            objectMapper = objectMapper
        )

    @Bean
    fun postgresCatalogCurrentObjectsReadPort(
        productControlDataSource: DataSource
    ): PostgresCatalogCurrentObjectsReadPort =
        PostgresCatalogCurrentObjectsReadPort(productControlDataSource)

    @Bean
    fun semanticMetricFlowPort(): SemanticMetricFlowPort =
        MetricFlowSemanticRenderer.production()

    @Bean
    fun semanticMutationTransactionOperations(
        productAuthorityTransactionManager: DataSourceTransactionManager,
        properties: ControlProductAuthorityProperties,
    ): TransactionTemplate =
        TransactionTemplate(productAuthorityTransactionManager).also { template ->
            template.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
            template.isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
            template.timeout = properties.transaction.transactionTimeout.toPositiveCeilingSeconds()
        }

    @Bean
    fun semanticTransactionPort(
        @Qualifier("jdbcDispatcher") blockingDispatcher: CoroutineDispatcher,
        productControlDataSource: DataSource,
        productAuthorityTransactionOperations: TransactionTemplate,
        @Qualifier("semanticMutationTransactionOperations")
        semanticMutationTransactionOperations: TransactionTemplate,
        properties: ControlProductAuthorityProperties
    ): SpringSemanticTransactionPort {
        val transaction = properties.transaction
        return SpringSemanticTransactionPort(
            dataSource = productControlDataSource,
            transactionOperations = productAuthorityTransactionOperations,
            mutationTransactionOperations = semanticMutationTransactionOperations,
            blockingDispatcher = blockingDispatcher,
            timeouts =
                SemanticTransactionTimeouts(
                    transaction = transaction.transactionTimeout,
                    statement = transaction.statementTimeout,
                    lock = transaction.lockTimeout
                )
        )
    }

    @Bean
    fun postgresCatalogSelectionRepository(
        productControlDataSource: DataSource,
        objectMapper: ObjectMapper,
        searchProjection: cc.monomer.catalog.api.CatalogSearchProjectionPort,
    ): PostgresCatalogSelectionRepository =
        PostgresCatalogSelectionRepository(
            dataSource = productControlDataSource,
            objectMapper = objectMapper,
            searchProjection = searchProjection,
        )

    @Bean
    fun postgresCatalogIngestRepository(
        productControlDataSource: DataSource,
        objectMapper: ObjectMapper,
        searchProjection: cc.monomer.catalog.api.CatalogSearchProjectionPort,
        postgresCatalogSelectionRepository: PostgresCatalogSelectionRepository,
    ): PostgresCatalogIngestRepository =
        PostgresCatalogIngestRepository(
            dataSource = productControlDataSource,
            objectMapper = objectMapper,
            searchProjection = searchProjection,
            selectionRepository = postgresCatalogSelectionRepository,
        )

    @Bean
    fun postgresCatalogListRefreshRepository(
        productControlDataSource: DataSource,
        postgresCatalogSelectionRepository: PostgresCatalogSelectionRepository,
    ): PostgresCatalogListRefreshRepository =
        PostgresCatalogListRefreshRepository(
            dataSource = productControlDataSource,
            selectionRepository = postgresCatalogSelectionRepository,
        )

    @Bean
    fun postgresCatalogConnectorStateRepository(
        productControlDataSource: DataSource,
        searchProjection: cc.monomer.catalog.api.CatalogSearchProjectionPort,
    ): PostgresCatalogConnectorStateRepository =
        PostgresCatalogConnectorStateRepository(
            dataSource = productControlDataSource,
            searchProjection = searchProjection,
        )

    @Bean
    fun postgresCatalogActivityReadPort(
        productControlDataSource: DataSource,
        catalogTransactionPort: SpringCatalogTransactionPort,
    ): PostgresCatalogActivityReadPort =
        PostgresCatalogActivityReadPort(productControlDataSource, catalogTransactionPort)

    @Bean
    fun catalogTransactionPort(
        @Qualifier("jdbcDispatcher") blockingDispatcher: CoroutineDispatcher,
        productControlDataSource: DataSource,
        productAuthorityTransactionManager: DataSourceTransactionManager,
        properties: ControlProductAuthorityProperties
    ): SpringCatalogTransactionPort {
        val transaction = properties.transaction
        val transactionOperations =
            TransactionTemplate(productAuthorityTransactionManager).also { template ->
                template.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
                template.isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
                template.timeout = transaction.transactionTimeout.toPositiveCeilingSeconds()
            }
        val readOnlyTransactionOperations =
            TransactionTemplate(productAuthorityTransactionManager).also { template ->
                template.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
                template.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
                template.isReadOnly = true
                template.timeout = transaction.transactionTimeout.toPositiveCeilingSeconds()
            }
        return SpringCatalogTransactionPort(
            dataSource = productControlDataSource,
            transactionOperations = transactionOperations,
            readOnlyTransactionOperations = readOnlyTransactionOperations,
            blockingDispatcher = blockingDispatcher,
            timeouts =
                CatalogTransactionTimeouts(
                    transaction = transaction.transactionTimeout,
                    statement = transaction.statementTimeout,
                    lock = transaction.lockTimeout
                )
        )
    }

    private companion object {
        const val AUTH_SCHEMA_VERIFICATION_MAXIMUM_ATTEMPTS = 5
        const val AUTH_SCHEMA_VERIFICATION_RETRY_DELAY_MILLIS = 500L
    }
}

@ConfigurationProperties("monomer.control.product-authority")
data class ControlProductAuthorityProperties(
    val postgres: ControlProductPostgresProperties,
    val transaction: ControlProductTransactionProperties,
)

data class ControlProductPostgresProperties(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val maximumPoolSize: Int,
    val connectionTimeout: Duration,
    val validationTimeout: Duration
) {
    init {
        require(jdbcUrl.startsWith("jdbc:postgresql://"))
        require(username.isNotBlank())
        require(password.isNotBlank())
        require(maximumPoolSize in 2..64)
        require(connectionTimeout in Duration.ofMillis(250)..Duration.ofSeconds(30))
        require(
            validationTimeout >= Duration.ofMillis(250) &&
                validationTimeout < connectionTimeout
        )
    }

    override fun toString(): String =
        "ControlProductPostgresProperties(" +
            "jdbcUrl=$jdbcUrl, username=$username, password=[REDACTED], " +
            "maximumPoolSize=$maximumPoolSize, connectionTimeout=$connectionTimeout, " +
            "validationTimeout=$validationTimeout)"
}

data class ControlProductTransactionProperties(
    val transactionTimeout: Duration,
    val statementTimeout: Duration,
    val lockTimeout: Duration
) {
    init {
        require(transactionTimeout in Duration.ofSeconds(1)..Duration.ofSeconds(30))
        require(statementTimeout in Duration.ofMillis(1)..transactionTimeout)
        require(lockTimeout in Duration.ofMillis(1)..statementTimeout)
    }
}

internal fun Duration.toPositiveCeilingSeconds(): Int =
    ((toMillis() + 999) / 1_000)
        .coerceIn(1, Int.MAX_VALUE.toLong())
        .toInt()
