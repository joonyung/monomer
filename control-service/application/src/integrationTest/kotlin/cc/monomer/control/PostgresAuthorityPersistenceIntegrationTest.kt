@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control

import cc.monomer.identity.domain.model.DeveloperAccessPolicy

import cc.monomer.control.agent.AgentWorkspaceApplicationComposition
import cc.monomer.control.explore.ExploreRunReadOutcome
import cc.monomer.control.explore.ExploreRunResultRead
import cc.monomer.control.explore.ExploreRunRecord
import cc.monomer.control.explore.ReadExploreRunCommand
import cc.monomer.execution.adapter.inbound.grpc.ExecutableRequestAssignmentCodec
import cc.monomer.execution.adapter.inbound.grpc.ProtoRawSqlRunSpecFactory
import cc.monomer.execution.adapter.outbound.postgres.PostgresExecutionRuntimeStore
import cc.monomer.execution.adapter.outbound.postgres.PostgresRunDispatchPersistence
import cc.monomer.execution.adapter.outbound.postgres.SpringExecutionRuntimeTransactionRunner
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorRuntimeBindingSpec
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.CredentialVersionReference
import cc.monomer.execution.api.ExecutableRequestKind
import cc.monomer.execution.api.ExecutionOwnerId
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutionRunAdmissionPort
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ManagedCredentialDispatchReference
import cc.monomer.execution.api.RawSqlExecutionRequest
import cc.monomer.execution.api.RawSqlExecutionRequestCanonicalizer
import cc.monomer.execution.api.RawSqlRunSpecCommand
import cc.monomer.execution.api.Sha256Digest
import cc.monomer.execution.application.ExecutionRunAdmissionService
import cc.monomer.execution.application.ExecutionRunLimits
import cc.monomer.execution.application.ExecutionRunService
import cc.monomer.explore.adapter.outbound.postgres.SpringExploreResultReadTransactionPort
import cc.monomer.explore.domain.ExploreRun
import cc.monomer.agent.api.AgentCatalogDiscoveryPort
import cc.monomer.agent.api.AgentConnectorOwnerPort
import cc.monomer.agent.api.AgentDashboardOwnerPort
import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.agent.api.AgentExploreOwnerPort
import cc.monomer.agent.api.AgentWorkspaceAdmissionDecision
import cc.monomer.agent.api.AgentWorkspaceAdmissionPort
import cc.monomer.agent.api.AgentWorkspaceAdmissionRequest
import cc.monomer.agent.api.AgentWorkspaceRoleEvidence
import cc.monomer.agent.application.AgentResourceRouteRegistry
import cc.monomer.agent.application.AgentWorkspaceCallerAuthenticator
import cc.monomer.agent.application.AgentWorkspaceContractDecoder
import cc.monomer.authority.api.CurrentResourceFocusConeResolution
import cc.monomer.authority.api.LineageEdge
import cc.monomer.authority.api.CurrentResourceFocusConeDenial
import cc.monomer.authority.api.ResourceKind
import cc.monomer.authority.api.ResourceRef
import cc.monomer.catalog.adapter.outbound.postgres.CatalogTransactionTimeouts
import cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogIngestRepository
import cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogSelectionRepository
import cc.monomer.catalog.adapter.outbound.postgres.SpringCatalogTransactionPort
import cc.monomer.catalog.api.CatalogIngestEntry
import cc.monomer.catalog.api.CatalogObjectId
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.catalog.api.CatalogRelationSelection
import cc.monomer.catalog.api.CatalogSelectedSchema
import cc.monomer.catalog.api.CatalogSelection
import cc.monomer.catalog.api.CatalogSelectionState
import cc.monomer.catalog.api.CatalogStableObjectIdentity
import cc.monomer.catalog.api.CatalogStableObjectIdentityCodec
import cc.monomer.catalog.api.RelationKind
import cc.monomer.connector.adapter.outbound.postgres.ConnectorTransactionTimeouts
import cc.monomer.connector.adapter.outbound.postgres.PostgresConnectorMutationKeyLockPort
import cc.monomer.connector.adapter.outbound.postgres.PostgresConnectorRepository
import cc.monomer.connector.adapter.outbound.postgres.SpringConnectorTransactionPort
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ContentDigest
import cc.monomer.connector.api.CredentialEnvelopeReference
import cc.monomer.connector.api.CredentialVersionId
import cc.monomer.connector.application.ConnectorTransactionOutcomeUnknownException
import cc.monomer.connector.application.ConnectorTransactionRolledBackException
import cc.monomer.connector.domain.Connector
import cc.monomer.connector.api.ConnectorSettingsProperties
import cc.monomer.connector.domain.ConnectorType
import cc.monomer.control.agent.AgentExploreOwnerAdapter
import cc.monomer.control.agent.AgentSemanticOwnerAdapter
import cc.monomer.control.explore.ExploreRunAccessService
import cc.monomer.control.publicapi.AgentWorkspaceHttpPorts
import cc.monomer.control.publicapi.AgentWorkspacePublicApiRouter
import cc.monomer.control.publicapi.AgentWorkspaceTransportCredentialResolution
import cc.monomer.control.publicapi.AgentWorkspaceTransportCredentialResolver
import cc.monomer.control.publicapi.ControlPublicApiProperties
import cc.monomer.control.publicapi.PublicControlContractValidator
import cc.monomer.explore.adapter.outbound.postgres.PostgresExploreRunRepository
import cc.monomer.explore.api.ExploreConnectorBinding
import cc.monomer.explore.api.ExploreDigest
import cc.monomer.explore.api.ExploreResultColumn
import cc.monomer.explore.api.ExploreResultProjection
import cc.monomer.explore.api.ExploreResultProjectionReadOutcome
import cc.monomer.explore.api.ExploreResultView
import cc.monomer.explore.api.ExploreRunId
import cc.monomer.explore.api.ExploreRunFailureCode
import cc.monomer.explore.api.ExploreRunState
import cc.monomer.explore.api.ExploreRunView
import cc.monomer.explore.application.ExploreAdmissionCoordinator
import cc.monomer.explore.application.ExploreCancellationHandler
import cc.monomer.explore.application.ExploreResultReader
import cc.monomer.explore.application.ExploreService
import cc.monomer.explore.domain.ExploreTerminalReceipt
import cc.monomer.execution.api.ConnectorType as ExecutionConnectorType
import cc.monomer.execution.api.ResultLogicalType
import cc.monomer.execution.api.ResultPreviewCell
import cc.monomer.identity.application.IdentityAdmissionService
import cc.monomer.identity.application.agent.AgentOAuthTokenHasher
import cc.monomer.identity.api.OrganizationId
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.AuthorizationRevision
import cc.monomer.identity.api.CanonicalPrincipal
import cc.monomer.identity.api.CredentialKind
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.ExecutionIntent
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.IdentityRoleEvidence
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.PrincipalKind
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.UserCredentialProvenance
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.identity.domain.port.CurrentUserCredentialVerifier
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.domain.port.VerifiedCurrentUserCredential
import cc.monomer.identity.outbound.postgres.PostgresCanonicalIdentityAdmissionSnapshot
import cc.monomer.identity.outbound.postgres.SpringConsistentSnapshotTransactionRunner
import cc.monomer.identity.outbound.postgres.SpringTransactionRunner
import cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogCurrentObjectsReadPort
import cc.monomer.semantic.adapter.outbound.postgres.PostgresSemanticRepository
import cc.monomer.semantic.adapter.outbound.postgres.SemanticTransactionTimeouts
import cc.monomer.semantic.adapter.outbound.postgres.SpringSemanticTransactionPort
import cc.monomer.semantic.api.PutSemanticModelCommand
import cc.monomer.semantic.api.PutMetricCommand
import cc.monomer.semantic.api.MetricView
import cc.monomer.semantic.api.SemanticCurrentReadUseCase
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticMetricInput
import cc.monomer.semantic.api.SemanticQuerySpec
import cc.monomer.semantic.api.SemanticModelId
import cc.monomer.semantic.api.SemanticDimensionDefinition
import cc.monomer.semantic.api.SemanticDimensionKind
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticEntityDefinition
import cc.monomer.semantic.api.SemanticEntityKind
import cc.monomer.semantic.api.SemanticMeasureAggregation
import cc.monomer.semantic.api.SemanticMeasureDefinition
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticModelView
import cc.monomer.semantic.api.SemanticMutationResult
import cc.monomer.semantic.api.SemanticSourceRelation
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogSearchReadPort
import cc.monomer.control.search.CatalogConnectorSearchProjection
import cc.monomer.control.search.SemanticDashboardSearchProjection
import cc.monomer.control.search.SearchApplicationComposition
import cc.monomer.control.search.SearchIndexBootstrap
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardSearchReadPort
import cc.monomer.search.adapter.outbound.postgres.PostgresSearchIndex
import cc.monomer.search.api.SearchMatch
import cc.monomer.search.api.SearchResourceKind
import cc.monomer.semantic.api.SemanticSearchProjection
import cc.monomer.semantic.api.SemanticModelSearchFields
import cc.monomer.semantic.application.SemanticAuthorityService
import cc.monomer.semantic.application.SemanticCurrentReadService
import cc.monomer.semantic.application.SemanticRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.net.URI
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresAuthorityPersistenceIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ephemeralPostgres = EphemeralPostgresAuthoritySupport
    }

    private val blockingThreadOrdinal = AtomicInteger()
    private val blockingDispatcher: ExecutorCoroutineDispatcher =
        Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "authority-db-${blockingThreadOrdinal.incrementAndGet()}")
        }.asCoroutineDispatcher()
    private val dataSource: DataSource
        get() = EphemeralPostgresAuthoritySupport.dataSource
    private val transactionOperations: TransactionOperations
        get() = EphemeralPostgresAuthoritySupport.transactionOperations
    private val objectMapper = ObjectMapper()
    private val fixedInstant = Instant.parse("2026-07-31T01:00:00Z")
    private val connectorRepository by lazy {
        PostgresConnectorRepository(dataSource, objectMapper)
    }
    private val catalogSelectionRepository by lazy { newCatalogSelectionRepository() }
    private val searchIndex by lazy { PostgresSearchIndex(dataSource) }
    @BeforeAll
    fun prepareEphemeralDatabase() {
        EphemeralPostgresAuthoritySupport.verifyEphemeralIdentity()
        EphemeralPostgresAuthoritySupport.verifyAuthoritySchemasAbsent()
        EphemeralPostgresAuthoritySupport.applyAuthoritySchemasTwice()
        EphemeralPostgresAuthoritySupport.verifyPhaseThreeSchemaContract()
        EphemeralPostgresAuthoritySupport.verifyNoCrossOwnerForeignKeys()
    }

    @AfterAll
    fun closeBlockingDispatcher() {
        blockingDispatcher.close()
    }

    @Test
    fun `owner mutation repositories fail closed without their exact ambient transaction`() {
        val connector = newConnector()
        val selection =
            CatalogSelectionState(
                workspaceId = connector.workspaceId,
                connectorId = connector.id,
                entry = CatalogIngestEntry.Connector(connector.id),
                selection = catalogSelection(),
                committedBy = PrincipalId(Uuid.generateV7()),
                committedAt = fixedInstant,
            )
        assertThatThrownBy { connectorRepository.insertConnector(connector) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("active owner transaction")
        assertThatThrownBy { catalogSelectionRepository.saveSelection(selection) }
            .isInstanceOf(IllegalStateException::class.java)
        val secondDataSource = EphemeralPostgresAuthoritySupport.newDataSource()
        val wrongBoundary =
            TransactionTemplate(DataSourceTransactionManager(secondDataSource)).apply {
                timeout = 5
            }
        assertThatThrownBy {
            runBlocking {
                connectorTransactionPort(wrongBoundary).inTransaction { "unreachable" }
            }
        }
            .isInstanceOf(ConnectorTransactionRolledBackException::class.java)
            .hasRootCauseMessage(
                "Connector transaction requires the transaction bound to the exact DataSource",
            )
    }

    @Test
    fun `Explore start and result read admissions share one repeatable read connection`() {
        val workspaceId = WorkspaceId(Uuid.generateV7())
        val actorUserId = Uuid.generateV7()
        val organizationId = Uuid.generateV7()
        val loginIdentityId = Uuid.generateV7()
        val sessionId = Uuid.generateV7()
        val credential = "integration-session-${Uuid.generateV7()}"
        transactionOperations.execute {
            seedIdentityMember(
                workspaceId = workspaceId,
                actorUserId = actorUserId,
                organizationId = organizationId,
                loginIdentityId = loginIdentityId,
                sessionId = sessionId,
            )
        }

        val transactionManager = DataSourceTransactionManager(dataSource)
        val writeTemplate = TransactionTemplate(transactionManager)
        val repeatableReadTemplate = TransactionTemplate(transactionManager).apply {
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }
        val readOnlyTemplate = TransactionTemplate(transactionManager).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }
        val snapshotRunner = SpringConsistentSnapshotTransactionRunner(repeatableReadTemplate, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        val identityTransactionRunner = SpringTransactionRunner(writeTemplate, readOnlyTemplate, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        val snapshotConnections = ArrayDeque<java.sql.Connection>()
        val identityConnectionChecks = mutableListOf<Pair<Boolean, Int>>()
        val recordingIdentityTransactionRunner = object : TransactionRunner {
            override suspend fun <T> run(action: () -> T): T =
                identityTransactionRunner.run(action)

            override suspend fun <T> readOnly(action: () -> T): T =
                identityTransactionRunner.readOnly {
                    val identityConnection = DataSourceUtils.getConnection(dataSource)
                    identityConnectionChecks +=
                        (identityConnection === snapshotConnections.last()) to
                            identityConnection.transactionIsolation
                    action()
                }
        }
        val credentialVerifier = object : CurrentUserCredentialVerifier {
            override fun verifyCurrentCredential(
                serializedToken: String,
            ): VerifiedCurrentUserCredential? =
                if (serializedToken == credential) {
                    VerifiedCurrentUserCredential(
                        provenance = UserCredentialProvenance.BrowserSession(
                            userId = UserId(actorUserId),
                            sessionId = SessionId(sessionId),
                        ),
                        expiresAt = fixedInstant.plusSeconds(900),
                    )
                } else {
                    null
                }
        }
        val identitySnapshot = PostgresCanonicalIdentityAdmissionSnapshot(
            jdbc = NamedParameterJdbcTemplate(dataSource),
            transactionRunner = recordingIdentityTransactionRunner,
            currentUserCredentialVerifier = credentialVerifier,
            agentOAuthTokenHasher = AgentOAuthTokenHasher(),
            clock = Clock.fixed(fixedInstant, ZoneOffset.UTC),
            agentResourceAudienceByExpectedResource = mapOf(
                ExpectedOAuthResource.AGENT_REST to "https://monomer.test/agent",
                ExpectedOAuthResource.AGENT_MCP to "https://monomer.test/mcp",
            ),
            developerAccessPolicy = DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.OPEN, emptySet()),
        )
        val identityPort = IdentityAdmissionService(identitySnapshot)

        val facade = ControlAdmissionFacade(
            identityAdmissionPort = identityPort,
            clock = Clock.fixed(fixedInstant, ZoneOffset.UTC),
        )
        val request = ExecutionAdmissionRequest(
            workspaceId = workspaceId,
            credential = PresentedCredential(credential),
            expectedOAuthResource = null,
            intent = ExecutionIntent.EXPLORE_RUN,
        )

        val startAdmission = runBlocking { snapshotRunner.run { snapshotConnections += DataSourceUtils.getConnection(dataSource); facade.authorizeExecution(request) } }
        val resultReadAdmission = runBlocking { snapshotRunner.run { snapshotConnections += DataSourceUtils.getConnection(dataSource); facade.authorizeExecution(request) } }

        assertThat(startAdmission).isInstanceOf(ControlAdmissionDecision.Admitted::class.java)
        assertThat(resultReadAdmission).isInstanceOf(ControlAdmissionDecision.Admitted::class.java)
        assertThat(snapshotConnections).hasSize(2)
        assertThat(identityConnectionChecks).hasSize(2)
        identityConnectionChecks.forEach { (sameConnection, transactionIsolation) ->
            assertThat(sameConnection).isTrue()
            assertThat(transactionIsolation)
                .isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ)
        }
    }

    private fun seedIdentityMember(
        workspaceId: WorkspaceId,
        actorUserId: Uuid,
        organizationId: Uuid,
        loginIdentityId: Uuid,
        sessionId: Uuid,
    ) {
        val jdbc = NamedParameterJdbcTemplate(dataSource)
        val parameters = mapOf(
            "workspaceId" to workspaceId.value.toJavaUuid(),
            "userId" to actorUserId.toJavaUuid(),
            "organizationId" to organizationId.toJavaUuid(),
            "loginIdentityId" to loginIdentityId.toJavaUuid(),
            "sessionId" to sessionId.toJavaUuid(),
            "createdAt" to Timestamp.from(fixedInstant),
            "expiresAt" to Timestamp.from(fixedInstant.plusSeconds(900)),
            "email" to "acl-${actorUserId}@example.com",
        )
        jdbc.update(
            "INSERT INTO identity_runtime.organizations(id, display_name, created_at) VALUES (:organizationId, 'ACL organization', :createdAt)",
            parameters,
        )
        jdbc.update(
            "INSERT INTO identity_runtime.principals(id, kind, display_name, created_at) VALUES (:userId, 'user', 'ACL user', :createdAt)",
            parameters,
        )
        jdbc.update(
            "INSERT INTO identity_runtime.users(id, primary_email, created_at) VALUES (:userId, :email, :createdAt)",
            parameters,
        )
        jdbc.update(
            "INSERT INTO identity_runtime.login_identities(id, user_id, protocol, namespace, subject, linked_via, linked_at) VALUES (:loginIdentityId, :userId, 'development', 'integration', :subject, 'signup', :createdAt)",
            parameters + mapOf("subject" to "acl-${actorUserId}"),
        )
        jdbc.update(
            "INSERT INTO identity_runtime.workspaces(id, organization_id, display_name, authorization_revision, created_at) VALUES (:workspaceId, :organizationId, 'Role workspace', 1, :createdAt)",
            parameters,
        )
        jdbc.update(
            "INSERT INTO identity_runtime.organization_members(organization_id, user_id, role, joined_at) VALUES (:organizationId, :userId, 'ADMIN', :createdAt)",
            parameters,
        )
        jdbc.update(
            "INSERT INTO identity_runtime.workspace_members(workspace_id, user_id, role, invited_at) VALUES (:workspaceId, :userId, 'OWNER', :createdAt)",
            parameters,
        )
        jdbc.update(
            "INSERT INTO identity_runtime.sessions(id, user_id, login_identity_id, protocol, authentication_source_ref, authentication_issuer, asserted_email, asserted_email_verified, email_evidence, authentication_methods, created_at, last_refreshed_at, absolute_expires_at) VALUES (:sessionId, :userId, :loginIdentityId, 'development', 'integration', 'integration', :email, TRUE, 'authoritative', '{}', :createdAt, :createdAt, :expiresAt)",
            parameters,
        )
    }

    @Test
    fun `connector mutation lock is transaction scoped bounded and independent by key`() {
        val workspaceId = WorkspaceId(Uuid.generateV7())
        val idempotencyKey = "pg-lock-${Uuid.generateV7()}"
        val transactionPort = connectorTransactionPort(transactionOperations)
        val lockPort =
            PostgresConnectorMutationKeyLockPort(
                dataSource = dataSource,
                queryTimeout = Duration.ofSeconds(1),
            )
        val holderEntered = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)

        Executors.newSingleThreadExecutor().use { executor ->
            val holder =
                executor.submit<String> {
                    runBlocking {
                        transactionPort.inTransaction {
                            lockPort.lockInOwnerTransaction(workspaceId, idempotencyKey)
                            holderEntered.countDown()
                            check(releaseHolder.await(10, TimeUnit.SECONDS))
                            "held"
                        }
                    }
                }
            check(holderEntered.await(10, TimeUnit.SECONDS))
            try {
                assertThat(
                    runBlocking {
                        transactionPort.inTransaction {
                            lockPort.lockInOwnerTransaction(
                                workspaceId,
                                "independent-${Uuid.generateV7()}",
                            )
                            "independent"
                        }
                    },
                ).isEqualTo("independent")
                assertThatThrownBy {
                    runBlocking {
                        transactionPort.inTransaction {
                            lockPort.lockInOwnerTransaction(workspaceId, idempotencyKey)
                        }
                    }
                }
                    .isInstanceOf(ConnectorTransactionRolledBackException::class.java)
                    .hasRootCauseInstanceOf(SQLException::class.java)
            } finally {
                releaseHolder.countDown()
                assertThat(holder.get(10, TimeUnit.SECONDS)).isEqualTo("held")
            }
        }
    }

    @Test
    fun `owner transaction ports use their declared execution boundary`() {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "event-loop-sentinel")
        }.use { eventLoop ->
            val result =
                eventLoop.submit<Pair<String, List<Pair<String, String>>>> {
                    val callerThreadName = Thread.currentThread().name
                    val transactionThreads =
                        runBlocking {
                            listOf(
                                "connector" to
                                    connectorTransactionPort(transactionOperations)
                                        .inTransaction { Thread.currentThread().name },
                                "catalog" to
                                    catalogTransactionPort(transactionOperations)
                                        .inTransaction { Thread.currentThread().name },
                                "semantic" to
                                semanticTransactionPort()
                                        .inSnapshotTransaction { Thread.currentThread().name },
                            )
                        }
                    callerThreadName to transactionThreads
                }.get(10, TimeUnit.SECONDS)

            assertThat(result.first).isEqualTo("event-loop-sentinel")
            result.second
                .forEach { (_, transactionThreadName) ->
                    assertThat(transactionThreadName)
                        .startsWith("authority-db-")
                        .isNotEqualTo(result.first)
                }
        }
    }

    @Test
    fun `semantic snapshot and mutation transactions use their declared isolation`() {
        val semanticPort = semanticTransactionPort()

        val isolations = runBlocking {
            listOf(
                semanticPort.inSnapshotTransaction {
                    DataSourceUtils.getConnection(dataSource).transactionIsolation
                },
                semanticPort.inMutationTransaction {
                    DataSourceUtils.getConnection(dataSource).transactionIsolation
                },
            )
        }

        assertThat(isolations).containsExactly(
            java.sql.Connection.TRANSACTION_REPEATABLE_READ,
            java.sql.Connection.TRANSACTION_READ_COMMITTED,
        )
    }

    @Test
    fun `semantic nested transactions preserve the exact ambient connection`() {
        val semanticPort = semanticTransactionPort(
            dispatcher = cc.monomer.control.jdbc.JdbcVirtualThreadDispatcher(dataSource),
        )
        val snapshotSameConnection = semanticTransactionTemplate(
            TransactionDefinition.ISOLATION_REPEATABLE_READ,
        ).execute {
            val outerConnection = DataSourceUtils.getConnection(dataSource)
            runBlocking {
                semanticPort.inSnapshotTransaction {
                    DataSourceUtils.getConnection(dataSource) === outerConnection
                }
            }
        }
        val mutationSameConnection = semanticTransactionTemplate(
            TransactionDefinition.ISOLATION_READ_COMMITTED,
        ).execute {
            val outerConnection = DataSourceUtils.getConnection(dataSource)
            runBlocking {
                semanticPort.inMutationTransaction {
                    DataSourceUtils.getConnection(dataSource) === outerConnection
                }
            }
        }

        assertThat(snapshotSameConnection).isTrue()
        assertThat(mutationSameConnection).isTrue()
    }

    @Test
    fun `Agent public REST create read edit and search use the real Semantic owner transaction`() {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val resourceName = "agent_${Uuid.generateV7().toString().replace("-", "")}"
        val semanticRepository = PostgresSemanticRepository(dataSource, objectMapper)
        val semanticPort = semanticTransactionPort()
        val authority = semanticService(
            semanticRepository,
            semanticPort,
        )
        val currentRead = SemanticCurrentReadService(semanticRepository, semanticRepository, semanticPort, mockk {
            coEvery { listByWorkspace(workspaceId) } returns emptyList()
            coEvery { findByWorkspaceAndIds(workspaceId, any()) } returns emptyList()
        },
            io.mockk.mockk(relaxed = true),
        )
        val actor = CanonicalPrincipal(PrincipalId.generate(), PrincipalKind.USER)
        val organizationId = OrganizationId.generate()
        val credential = PresentedCredential("agent-public-route-${Uuid.generateV7()}")
        val callerAuthenticator = AgentWorkspaceCallerAuthenticator(
            object : AgentWorkspaceAdmissionPort {
                override suspend fun decide(request: AgentWorkspaceAdmissionRequest): AgentWorkspaceAdmissionDecision {
                    val evidence =
                        AgentWorkspaceRoleEvidence(
                            actor = actor,
                            organizationId = organizationId,
                            organizationPlan = cc.monomer.identity.api.OrganizationPlan.FREE,
                            workspaceId = request.workspaceId,
                            authorizationRevision = AuthorizationRevision(1),
                            credentialKind = CredentialKind.AGENT_OAUTH,
                            credentialSurface = CredentialSurface.AGENT,
                            agentClientId = cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"),
                            role = WorkspaceRole.EDITOR,
                            intent = request.intent,
                        )
                    return AgentWorkspaceAdmissionDecision.Authorized(evidence)
                }
            },
        )
        val semanticAdmission = mockk<ControlAdmissionFacade>()
        coEvery { semanticAdmission.authorizeMutation(any()) } coAnswers {
            ControlAdmissionDecision.Admitted(IdentityRoleEvidence(
                cc.monomer.identity.api.OrganizationPlan.FREE, actor, organizationId, workspaceId, AuthorizationRevision(1),
                CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), WorkspaceRole.EDITOR,
            ).forMutation(firstArg<MutationAdmissionRequest>().intent))
        }
        val semanticOwner = AgentSemanticOwnerAdapter(
            authority,
            currentRead,
            mockk<cc.monomer.semantic.api.SemanticDefinitionCheckUseCase>(),
            cc.monomer.control.agent.AgentSemanticEditAvailability { true },
            semanticAdmission,
            cc.monomer.control.agent.fixedAgentProvenanceNames(),
        )
        val searchAdmission = mockk<ControlAdmissionFacade>()
        coEvery { searchAdmission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(IdentityRoleEvidence(
            cc.monomer.identity.api.OrganizationPlan.FREE, actor, organizationId, workspaceId, AuthorizationRevision(1),
            CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), WorkspaceRole.EXPLORER,
        ))
        val search = SearchApplicationComposition().agentSearchPort(
            searchIndex,
            SpringConsistentSnapshotTransactionRunner(semanticTransactionTemplate(TransactionDefinition.ISOLATION_REPEATABLE_READ), blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined),
            searchAdmission,
            connectorRepository,
            PostgresCatalogSearchReadPort(dataSource),
            currentRead,
            mockk(),
            mockk(),
        )
        val handler = AgentResourceRouteRegistry(
            searchPort = search,
            exploreOwnerPort = mockk<AgentExploreOwnerPort>(),
            connectorOwnerPort = mockk<AgentConnectorOwnerPort>(),
            catalogDiscoveryPort = mockk<AgentCatalogDiscoveryPort>(),
            semanticOwnerPort = semanticOwner,
            dashboardOwnerPort = mockk<AgentDashboardOwnerPort>(),
            pagesOwnerPort = null,
            workspaceCollections = listOf(AgentResourcePath.parse("workspace/semantic-models"), AgentResourcePath.parse("workspace/metrics")), managementOwnerPort = null
        )
        val router = AgentWorkspacePublicApiRouter(
                oauthResourceMetadataUrl = "http://127.0.0.1:11077/.well-known/oauth-protected-resource/v2/workspaces",
            agentPorts = AgentWorkspaceHttpPorts(callerAuthenticator, handler, AgentWorkspaceContractDecoder()),
            properties = ControlPublicApiProperties(URI("http://localhost:3000")),
            credentialResolver = object : AgentWorkspaceTransportCredentialResolver {
                override suspend fun resolve(request: org.springframework.web.reactive.function.server.ServerRequest) =
                    AgentWorkspaceTransportCredentialResolution.Resolved(credential)
            },
            contractValidator = PublicControlContractValidator(),
            oauth = mockk<cc.monomer.identity.api.AgentOAuthPort> {
                coEvery { validateCredential(credential, ExpectedOAuthResource.AGENT_REST) } returns
                    cc.monomer.identity.api.OAuthOutcome.Success(cc.monomer.identity.api.OAuthAgentIdentity(actor.id, "https://claude.ai/oauth/mcp-oauth-client-metadata"))
            },
        )
        val client = WebTestClient.bindToRouterFunction(router.routes()).build()
        val collectionPath = "workspace/semantic-models"
        val resourcePath = "$collectionPath/$resourceName"

        client.post()
            .uri("/v2/workspaces/$workspaceId/agent/list")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("""{"path":"workspace","limit":100}""")
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.items[0].path").isEqualTo("workspace/metrics")
            .jsonPath("$.items[1].path").isEqualTo(collectionPath)
            .jsonPath("$.availableOperations[1].operation").isEqualTo("LIST")

        client.post()
            .uri("/v2/workspaces/$workspaceId/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("""{"path":"workspace"}""")
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.body.resources[1].path").isEqualTo(collectionPath)

        client.post()
            .uri("/v2/workspaces/$workspaceId/agent/create")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue(
                """
                {
                  "path":"$collectionPath",
                  "input":{
                    "kind":"SEMANTIC_MODEL_DEFINITION", "aggregateMetrics": [],
                    "definition":{
                      "schemaVersion":3,
                      "name":"$resourceName",
                      "sourceRelations":[{
                        "connectorId":"$connectorId",
                        "schema":"analytics",
                        "relation":"orders"
                      }],
                      "entities":[{"name":"order_id","type":"primary","expression":"order_id"}],
                      "dimensions":[],
                      "measures":[]
                    }
                  }
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("COMPLETED")
            .jsonPath("$.result.path").isEqualTo(resourcePath)

        client.post()
            .uri("/v2/workspaces/$workspaceId/agent/search")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("""{"path":"workspace","queries":["$resourceName","nohits","$resourceName"],"kinds":["SEMANTIC_MODEL"],"limitPerQuery":2}""")
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.results.length()").isEqualTo(3)
            .jsonPath("$.results[0].query").isEqualTo(resourceName)
            .jsonPath("$.results[0].items[0].path").isEqualTo(resourcePath)
            .jsonPath("$.results[0].items[0].name").isEqualTo(resourceName)
            .jsonPath("$.results[1].query").isEqualTo("nohits")
            .jsonPath("$.results[1].items.length()").isEqualTo(0)
            .jsonPath("$.results[2].query").isEqualTo(resourceName)
            .jsonPath("$.results[2].items[0].path").isEqualTo(resourcePath)

        client.post()
            .uri("/v2/workspaces/$workspaceId/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("{\"path\":\"$resourcePath\"}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.revision").doesNotExist()
            .jsonPath("$.body.name").isEqualTo(resourceName)
            .jsonPath("$.body.definition").doesNotExist()

        client.post()
            .uri("/v2/workspaces/$workspaceId/agent/list")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("""{"path":"$collectionPath","limit":1}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.items[0].path").isEqualTo(resourcePath)
            .jsonPath("$.nextCursor").doesNotExist()

        client.post()
            .uri("/v2/workspaces/$workspaceId/agent/edit")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue(
                """{"path":"$resourcePath","patch":[{"op":"add","path":"/label","value":"Dogfood"}],"aggregateMetrics":[]}""",
            )
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("COMPLETED")

        val persisted = runBlocking {
            currentRead.findModelByName(workspaceId, SemanticElementName(resourceName))
        }
        assertThat(persisted).isNotNull
        assertThat(persisted!!.definition.label).isEqualTo("Dogfood")
        client.post()
            .uri("/v2/workspaces/$workspaceId/agent/search")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("""{"path":"workspace/semantic-models","queries":["Dogfood","$resourceName"],"limitPerQuery":1}""")
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.results[0].items[0].path").isEqualTo(resourcePath)
            .jsonPath("$.results[1].items[0].path").isEqualTo(resourcePath)

    }

    @Test
    fun `Search projection failure rolls back Semantic source receipt and index together`() {
        val workspaceId = WorkspaceId.generate()
        val repository = PostgresSemanticRepository(dataSource, objectMapper)
        val transactionPort = semanticTransactionPort()
        val projection = SemanticDashboardSearchProjection(searchIndex)
        var projectionWrites = 0
        val failingProjection = object : SemanticSearchProjection by projection {
            override fun upsertModel(fields: SemanticModelSearchFields) {
                projection.upsertModel(fields)
                projectionWrites++
                throw IllegalStateException("injected search projection failure")
            }
        }
        val failingAuthority = SemanticAuthorityService(
            repository, transactionPort, Clock.fixed(fixedInstant, ZoneOffset.UTC), failingProjection,
        )
        val evidence = semanticEditEvidence(workspaceId)
        val definition = semanticModelDefinition(ConnectorId.generate(), name = "rollback_orders")
        val createKey = "search-rollback-create-${Uuid.generateV7()}"
        assertThatThrownBy {
            runBlocking { failingAuthority.putModel(PutSemanticModelCommand(evidence.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE), createKey, null, definition, emptyList())) }
        }.isInstanceOf(IllegalStateException::class.java).hasMessage("injected search projection failure")
        runBlocking {
            transactionPort.inSnapshotTransaction {
                assertThat(repository.findModelByName(workspaceId, definition.name)).isNull()
                assertThat(repository.findMutationReceipt(workspaceId, createKey)).isNull()
                assertThat(searchIndex.candidates(workspaceId.value.toJavaUuid(), "workspace", setOf(SearchResourceKind.SEMANTIC_MODEL),
                    definition.name.value, SearchMatch.WHOLE_TOKENS, 20, 0)).isEmpty()
            }
        }
        assertThat(projectionWrites).isEqualTo(1)

        val normalAuthority = semanticService(repository, transactionPort)
        val created = runBlocking {
            normalAuthority.putModel(PutSemanticModelCommand(evidence.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE), createKey, null, definition, emptyList()))
        } as SemanticMutationResult.Committed
        val editKey = "search-rollback-edit-${Uuid.generateV7()}"
        assertThatThrownBy {
            runBlocking {
                failingAuthority.putModel(PutSemanticModelCommand(evidence.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE), editKey, created.value.model.semanticModelId,
                    definition.copy(label = "Uncommittedlabel"), emptyList()))
            }
        }.isInstanceOf(IllegalStateException::class.java).hasMessage("injected search projection failure")
        runBlocking {
            transactionPort.inSnapshotTransaction {
                assertThat(repository.findModel(created.value.model.semanticModelId)!!.definition).isEqualTo(definition)
                assertThat(repository.findMutationReceipt(workspaceId, createKey)).isNotNull()
                assertThat(repository.findMutationReceipt(workspaceId, editKey)).isNull()
                assertThat(searchIndex.candidates(workspaceId.value.toJavaUuid(), "workspace", setOf(SearchResourceKind.SEMANTIC_MODEL),
                    "Uncommittedlabel", SearchMatch.WHOLE_TOKENS, 20, 0)).isEmpty()
                assertThat(searchIndex.candidates(workspaceId.value.toJavaUuid(), "workspace", setOf(SearchResourceKind.SEMANTIC_MODEL),
                    definition.name.value, SearchMatch.WHOLE_TOKENS, 20, 0)).containsExactly("workspace/semantic-models/${definition.name.value}")
            }
        }
        assertThat(projectionWrites).isEqualTo(2)
    }

    @Test
    fun `Search bootstrap populates persisted owner inventory and is repeatable`() {
        val workspaceId = WorkspaceId.generate()
        val repository = PostgresSemanticRepository(dataSource, objectMapper)
        val definition = semanticModelDefinition(ConnectorId.generate(), name = "bootstrap_orders")
        val model = putModel(workspaceId, definition, null)
        transactionOperations.execute {
            searchIndex.deleteSubtree(workspaceId.value.toJavaUuid(), "workspace")
        }
        assertThat(EphemeralPostgresAuthoritySupport.count("search.documents", "workspace_id", workspaceId.value.toJavaUuid())).isZero()
        val bootstrap = SearchIndexBootstrap(
            connectorRepository, PostgresCatalogSearchReadPort(dataSource), repository,
            PostgresDashboardSearchReadPort(dataSource), searchIndex,
        )
        repeat(2) {
            val populated = semanticTransactionTemplate(TransactionDefinition.ISOLATION_REPEATABLE_READ).execute {
                bootstrap.populateInCurrentTransaction()
            }
            assertThat(populated).isGreaterThanOrEqualTo(1)
            runBlocking {
                semanticTransactionPort().inSnapshotTransaction {
                    assertThat(repository.findModel(model.semanticModelId)!!.definition).isEqualTo(definition)
                    assertThat(searchIndex.candidates(workspaceId.value.toJavaUuid(), "workspace", setOf(SearchResourceKind.SEMANTIC_MODEL),
                        definition.name.value, SearchMatch.WHOLE_TOKENS, 20, 0)).containsExactly("workspace/semantic-models/${definition.name.value}")
                }
            }
            assertThat(EphemeralPostgresAuthoritySupport.count("search.documents", "workspace_id", workspaceId.value.toJavaUuid())).isEqualTo(1)
        }
    }

    @Test
    fun `Agent public REST paginates and reads the real Explore PostgreSQL owner`() {
        val workspaceId = WorkspaceId.generate()
        val actor = CanonicalPrincipal(PrincipalId.generate(), PrincipalKind.USER)
        val organizationId = OrganizationId.generate()
        val credential = PresentedCredential("agent-explore-route-${Uuid.generateV7()}")
        val connectorId = ConnectorId.generate()
        val repository = PostgresExploreRunRepository(dataSource)
        val older = ExploreRun(
            id = ExploreRunId.generate(),
            kind = ExploreRun.Kind.Sql,
            idempotencyKey = "agent-explore-older-${Uuid.generateV7()}",
            ownerPrincipalId = actor.id,
            credentialSurface = null,
            agentClientId = null,
            connectorBinding = ExploreConnectorBinding(workspaceId, connectorId, ExecutionConnectorType.POSTGRESQL),
            requestDigest = ExploreDigest("sha256:${"a".repeat(64)}"),
            state = ExploreRunState.RUNNING,
            visibleResult = null,
            failureCode = null,
            failureMessage = null,
            revision = 0,
            createdAt = fixedInstant.minusSeconds(1),
            updatedAt = fixedInstant.minusSeconds(1),
        )
        val newer = older.copy(
            id = ExploreRunId.generate(),
            idempotencyKey = "agent-explore-newer-${Uuid.generateV7()}",
            requestDigest = ExploreDigest("sha256:${"b".repeat(64)}"),
            state = ExploreRunState.RUNNING,
            failureCode = null,
            failureMessage = null,
            createdAt = fixedInstant,
            updatedAt = fixedInstant,
        )
        val failureReceipt = ExploreTerminalReceipt(
            workspaceId = workspaceId,
            settlementIdempotencyKey = "agent-explore-failure-${Uuid.generateV7()}",
            runId = older.id,
            terminalState = ExploreRunState.FAILED,
            failureCode = ExploreRunFailureCode.EXECUTION_FAILED,
            failureMessage = "Safe warehouse failure",
            result = null,
            settledAt = fixedInstant,
        )
        transactionOperations.execute {
            check(repository.insertAdmittedRun(older))
            check(repository.commitTerminalReceipt(older.complete(failureReceipt), failureReceipt, 0))
            check(repository.insertAdmittedRun(newer))
        }
        val resultRun = newer.copy(
            id = ExploreRunId.generate(),
            idempotencyKey = "agent-explore-result-${Uuid.generateV7()}",
            requestDigest = ExploreDigest("sha256:${"d".repeat(64)}"),
            createdAt = fixedInstant.plusSeconds(1),
            updatedAt = fixedInstant.plusSeconds(1),
        )
        val resultView = ExploreResultView(
            runId = resultRun.id,
            objectKey = "artifacts/explore/${resultRun.id.value}.arrow",
            objectEtag = "result-etag",
            byteSize = 128u,
            mediaType = "application/vnd.apache.arrow.file",
            rowCount = 1u,
            columnCount = 1,
            retention = cc.monomer.explore.api.ExploreResultRetention(fixedInstant.plusSeconds(900)),
            ownerPrincipalId = actor.id,
            activatedAt = fixedInstant.plusSeconds(2),
        )
        val resultReceipt = ExploreTerminalReceipt(
            workspaceId = workspaceId,
            settlementIdempotencyKey = "agent-explore-result-settlement-${Uuid.generateV7()}",
            runId = resultRun.id,
            terminalState = ExploreRunState.SUCCEEDED,
            failureCode = null,
            failureMessage = null,
            result = resultView,
            settledAt = resultView.activatedAt,
        )
        val succeededResultRun = resultRun.complete(resultReceipt)
        val resultProjection = ExploreResultProjection(
            run = succeededResultRun.asView(),
            columns = listOf(ExploreResultColumn("order_id", ResultLogicalType.TEXT, display = null)),
            rows = listOf(listOf(ResultPreviewCell.Text("order-42"))),
            totalRowCount = 1u,
            returnedRowCount = 1,
            truncated = false,
            expiresAt = resultView.retention.expiresAt,
        )
        val identityMembership = IdentityRoleEvidence(
            organizationPlan = cc.monomer.identity.api.OrganizationPlan.FREE,
            role = WorkspaceRole.EXPLORER,
            actor = actor,
            organizationId = organizationId,
            workspaceId = workspaceId,
            authorizationRevision = AuthorizationRevision(1),
            credentialKind = CredentialKind.AGENT_OAUTH,
            credentialSurface = CredentialSurface.AGENT,
            agentClientId = cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"),
        )
        val controlAdmissionFacade = mockk<ControlAdmissionFacade>()
        coEvery { controlAdmissionFacade.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Admitted(identityMembership)
        val resultReader = mockk<ExploreResultReader>()
        coEvery { resultReader.readProjection(any()) } returns
            ExploreResultProjectionReadOutcome.Visible(resultProjection)
        val exploreService = ExploreService(
            mockk<ExploreAdmissionCoordinator>(),
            resultReader,
            repository,
            kotlinx.coroutines.Dispatchers.IO,
        )
        val originalSql = "  -- 원본 😀\r\nSELECT 'e\u0301' AS 값;\n"
        persistExecutionInput(older.complete(failureReceipt), originalSql)
        persistExecutionInput(newer, originalSql)
        val runAccess = ExploreRunAccessService(
            repository = repository,
            exploreRunUseCase = exploreService,
            executionRunQueryPort = executionHistoryService(),
            cancellationHandler = mockk<ExploreCancellationHandler>(),
            controlAdmissionFacade = controlAdmissionFacade,
            consistentSnapshotTransactionRunner = object : ConsistentSnapshotTransactionRunner {
                override suspend fun <T> run(action: suspend () -> T): T = action()
            },
            clock = Clock.fixed(fixedInstant, ZoneOffset.UTC),
            blockingDispatcher = kotlinx.coroutines.Dispatchers.IO,
        )
        val exploreOwner = AgentExploreOwnerAdapter(
            runAccessService = runAccess,
            sqlLaunchService = mockk(),
            semanticLaunchService = mockk(),
            clock = Clock.fixed(fixedInstant, ZoneOffset.UTC),
            objectMapper = objectMapper,
            provenanceNames = cc.monomer.control.agent.fixedAgentProvenanceNames(),
        )
        val callerAuthenticator = AgentWorkspaceCallerAuthenticator(
            object : AgentWorkspaceAdmissionPort {
                override suspend fun decide(request: AgentWorkspaceAdmissionRequest) =
                    AgentWorkspaceAdmissionDecision.Authorized(
                        AgentWorkspaceRoleEvidence(
                            organizationPlan = cc.monomer.identity.api.OrganizationPlan.FREE,
                            role = WorkspaceRole.EXPLORER,
                            actor = actor,
                            organizationId = organizationId,
                            workspaceId = request.workspaceId,
                            authorizationRevision = AuthorizationRevision(1),
                            credentialKind = CredentialKind.AGENT_OAUTH,
                            credentialSurface = CredentialSurface.AGENT,
                            agentClientId = cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"),
                            intent = request.intent,
                        ),
                    )
            },
        )
        val handler = AgentResourceRouteRegistry(
            exploreOwnerPort = exploreOwner,
            connectorOwnerPort = mockk(),
            catalogDiscoveryPort = mockk(),
            semanticOwnerPort = mockk(),
            dashboardOwnerPort = mockk(),
            pagesOwnerPort = null,
            workspaceCollections = listOf(AgentResourcePath.parse("workspace/explore/runs")),
            searchPort = mockk(), managementOwnerPort = null
        )
        val router = AgentWorkspacePublicApiRouter(
                oauthResourceMetadataUrl = "http://127.0.0.1:11077/.well-known/oauth-protected-resource/v2/workspaces",
            agentPorts = AgentWorkspaceHttpPorts(callerAuthenticator, handler, AgentWorkspaceContractDecoder()),
            properties = ControlPublicApiProperties(URI("http://localhost:3000")),
            credentialResolver = object : AgentWorkspaceTransportCredentialResolver {
                override suspend fun resolve(request: org.springframework.web.reactive.function.server.ServerRequest) =
                    AgentWorkspaceTransportCredentialResolution.Resolved(credential)
            },
            contractValidator = PublicControlContractValidator(),
            oauth = mockk<cc.monomer.identity.api.AgentOAuthPort> {
                coEvery { validateCredential(credential, ExpectedOAuthResource.AGENT_REST) } returns
                    cc.monomer.identity.api.OAuthOutcome.Success(cc.monomer.identity.api.OAuthAgentIdentity(actor.id, "https://claude.ai/oauth/mcp-oauth-client-metadata"))
            },
        )
        val client = WebTestClient.bindToRouterFunction(router.routes()).build()
        val collectionPath = "workspace/explore/runs"

        val firstBody = client.post()
            .uri("/v2/workspaces/$workspaceId/agent/list")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("""{"path":"$collectionPath","limit":1}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.items[0].path").isEqualTo("$collectionPath/${newer.id}")
            .jsonPath("$.items[0].state").isEqualTo("RUNNING")
            .jsonPath("$.items[0].sql").doesNotExist()
            .jsonPath("$.items[0].query").doesNotExist()
            .returnResult()
            .responseBody
        val cursor = checkNotNull(firstBody).let { bytes ->
            objectMapper.readTree(bytes).path("nextCursor").textValue()
        }

        client.post()
            .uri("/v2/workspaces/$workspaceId/agent/list")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue(objectMapper.writeValueAsString(mapOf("path" to collectionPath, "cursor" to cursor, "limit" to 1)))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.items[0].path").isEqualTo("$collectionPath/${older.id}")
            .jsonPath("$.items[0].state").isEqualTo("FAILED")
            .jsonPath("$.nextCursor").doesNotExist()

        client.post()
            .uri("/v2/workspaces/$workspaceId/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("""{"path":"$collectionPath/${newer.id}"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.kind").isEqualTo("EXPLORE_RUN")
            .jsonPath("$.body.runKind").isEqualTo("SQL")
            .jsonPath("$.body.sql").isEqualTo(originalSql)
            .jsonPath("$.body.query").doesNotExist()
            .jsonPath("$.body.parameters").doesNotExist()
            .jsonPath("$.body.state").isEqualTo("RUNNING")
            .jsonPath("$.availableOperations[0].action").isEqualTo("cancel")

        client.post()
            .uri("/v2/workspaces/$workspaceId/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("""{"path":"$collectionPath/${older.id}"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.body.sql").isEqualTo(originalSql)
            .jsonPath("$.body.failureCode").isEqualTo("EXECUTION_FAILED")
            .jsonPath("$.body.failureDetail").isEqualTo("Safe warehouse failure")
            .jsonPath("$.availableOperations").doesNotExist()

        transactionOperations.execute {
            check(repository.insertAdmittedRun(resultRun))
            check(repository.commitTerminalReceipt(succeededResultRun, resultReceipt, 0))
        }
        client.post()
            .uri("/v2/workspaces/$workspaceId/agent/read")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue("{\"path\":\"$collectionPath/${succeededResultRun.id}/result\"}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.kind").isEqualTo("EXPLORE_RESULT")
            .jsonPath("$.body.columns[0].name").isEqualTo("order_id")
            .jsonPath("$.body.rows[0][0]").isEqualTo("order-42")
            .jsonPath("$.body.totalRowCount").isEqualTo("1")
            .jsonPath("$.body.returnedRowCount").isEqualTo(1)
            .jsonPath("$.body.truncated").isEqualTo(false)
            .jsonPath("$.body.expiresAt").isEqualTo(resultView.retention.expiresAt.toString())

        coVerify(exactly = 1) { resultReader.readProjection(any()) }
    }

    @Test
    fun `Agent PostgreSQL Semantic history survives definition changes while retention and current role remain enforced`() {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val tree = insertCurrentCatalogTree(workspaceId, connectorId)
        val relationRef = ResourceRef(ResourceKind.RELATION, tree.relation.value)
        val actorId = Uuid.generateV7()
        val organizationId = Uuid.generateV7()
        val sessionId = Uuid.generateV7()
        val browserToken = "history-browser-${Uuid.generateV7()}"
        val agentToken = "history-agent-${Uuid.generateV7()}"
        transactionOperations.execute {
            seedIdentityMember(workspaceId, actorId, organizationId, Uuid.generateV7(), sessionId)
            seedHistoryAgentToken(actorId, organizationId, agentToken)
        }
        val model = putModel(workspaceId, semanticModelDefinition(connectorId), null)
        val modelRef = ResourceRef(ResourceKind.SEMANTIC_MODEL, model.semanticModelId.value)
        val semanticRepository = PostgresSemanticRepository(dataSource, objectMapper)
        val metric = runBlocking {
            semanticService(semanticRepository, semanticTransactionPort()).putMetric(PutMetricCommand(
                semanticEditEvidence(workspaceId).forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "history-metric-${Uuid.generateV7()}", null,
                SemanticMetricDefinition.Simple(
                    3u, SemanticElementName("revenue"), null, null, null, emptyList(),
                    model.definition.name, SemanticElementName("revenue"),
                    display = null,
                ),
            ))
        } as SemanticMutationResult.Committed
        val metricRef = ResourceRef(ResourceKind.METRIC, metric.value.metricId.value)
        val originalQuery = SemanticQuerySpec(
            listOf(SemanticElementName("revenue")), emptyList(), null, emptyList(), emptyList(), 37,
        )
        val originalSql = "-- 당시 생성 SQL 😀\nSELECT SUM(amount) AS revenue FROM analytics.orders WHERE 'e\u0301' = 'e\u0301' LIMIT 37"
        val admittedRun = ExploreRun(
            id = ExploreRunId.generate(),
            kind = ExploreRun.Kind.Semantic(query = originalQuery, outputColumnDisplays = null, reportingTimeZone = null),
            idempotencyKey = "history-semantic-${Uuid.generateV7()}",
            ownerPrincipalId = PrincipalId(actorId),
            credentialSurface = null,
            agentClientId = null,
            connectorBinding = ExploreConnectorBinding(workspaceId, connectorId, ExecutionConnectorType.POSTGRESQL),
            requestDigest = ExploreDigest("sha256:${"e".repeat(64)}"),
            state = ExploreRunState.RUNNING,
            visibleResult = null,
            failureCode = null,
            failureMessage = null,
            revision = 0,
            createdAt = fixedInstant.minusSeconds(120),
            updatedAt = fixedInstant.minusSeconds(120),
        )
        val expiredResult = ExploreResultView(
            runId = admittedRun.id, objectKey = "artifacts/explore/${admittedRun.id.value}.arrow",
            objectEtag = "immutable-history-etag", byteSize = 128u, mediaType = "application/vnd.apache.arrow.file",
            rowCount = 1u, columnCount = 1,
            retention = cc.monomer.explore.api.ExploreResultRetention(fixedInstant.minusSeconds(1)),
            ownerPrincipalId = PrincipalId(actorId), activatedAt = fixedInstant.minusSeconds(110),
        )
        val receipt = ExploreTerminalReceipt(
            workspaceId = workspaceId, settlementIdempotencyKey = "history-settle-${Uuid.generateV7()}",
            runId = admittedRun.id, terminalState = ExploreRunState.SUCCEEDED, failureCode = null,
            failureMessage = null, result = expiredResult, settledAt = expiredResult.activatedAt,
        )
        val succeeded = admittedRun.complete(receipt)
        val repository = PostgresExploreRunRepository(dataSource)
        transactionOperations.execute {
            check(repository.insertAdmittedRun(admittedRun))
            check(repository.commitTerminalReceipt(succeeded, receipt, 0))
        }
        persistExecutionInput(succeeded, originalSql)
        val manager = DataSourceTransactionManager(dataSource)
        val writeTemplate = TransactionTemplate(manager)
        val snapshotTemplate = TransactionTemplate(manager).apply {
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }
        val readTemplate = TransactionTemplate(manager).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }
        val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val identitySnapshot = PostgresCanonicalIdentityAdmissionSnapshot(
            jdbc = NamedParameterJdbcTemplate(dataSource),
            transactionRunner = SpringTransactionRunner(writeTemplate, readTemplate, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined),
            currentUserCredentialVerifier = object : CurrentUserCredentialVerifier {
                override fun verifyCurrentCredential(serializedToken: String): VerifiedCurrentUserCredential? =
                    if (serializedToken == browserToken) VerifiedCurrentUserCredential(
                        UserCredentialProvenance.BrowserSession(UserId(actorId), SessionId(sessionId)),
                        fixedInstant.plusSeconds(900),
                    ) else null
            },
            agentOAuthTokenHasher = AgentOAuthTokenHasher(), clock = clock,
            agentResourceAudienceByExpectedResource = mapOf(
                ExpectedOAuthResource.AGENT_REST to "https://monomer.test/agent",
                ExpectedOAuthResource.AGENT_MCP to "https://monomer.test/mcp",
            ),
            developerAccessPolicy = DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.OPEN, emptySet()),
        )
        val facade = ControlAdmissionFacade(IdentityAdmissionService(identitySnapshot), clock)
        val artifactStore = mockk<cc.monomer.execution.api.ExecutionArtifactStore>()
        val resultReader = ExploreResultReader(
            SpringExploreResultReadTransactionPort(dataSource, 1, Duration.ofSeconds(5), Duration.ofSeconds(4), Duration.ofSeconds(1), kotlinx.coroutines.Dispatchers.IO),
            repository, artifactStore, clock,
        )
        val access = ExploreRunAccessService(
            repository, ExploreService(mockk(), resultReader, repository, kotlinx.coroutines.Dispatchers.IO), executionHistoryService(), mockk(), facade,
            SpringConsistentSnapshotTransactionRunner(snapshotTemplate, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined), clock,
            kotlinx.coroutines.Dispatchers.IO,
        )
        val composition = AgentWorkspaceApplicationComposition()
        val owner = AgentExploreOwnerAdapter(access, mockk(), mockk(), clock, objectMapper, cc.monomer.control.agent.fixedAgentProvenanceNames())
        val handler = AgentResourceRouteRegistry(owner, mockk(), mockk(), mockk(), mockk(), null, listOf(AgentResourcePath.parse("workspace/explore/runs")), mockk(), managementOwnerPort = null)
        val router = composition.agentWorkspacePublicApiRouter(
            composition.agentWorkspaceHttpPorts(composition.agentWorkspaceCallerAuthenticator(composition.agentWorkspaceAdmissionPort(facade, mockk(), mockk(), mockk())), handler),
            ControlPublicApiProperties(URI("http://localhost:3000")),
            cc.monomer.control.auth.inbound.agentoauth.AgentOAuthProperties("http://127.0.0.1:11077", "http://127.0.0.1:11077/mcp"),
            mockk<cc.monomer.identity.api.AgentOAuthPort> {
                coEvery { validateCredential(any(), ExpectedOAuthResource.AGENT_REST) } returns
                    cc.monomer.identity.api.OAuthOutcome.Success(cc.monomer.identity.api.OAuthAgentIdentity(PrincipalId(actorId), "history-integration"))
            },
        )
        val client = WebTestClient.bindToRouterFunction(router.routes()).build()
        val path = "workspace/explore/runs/${succeeded.id}"
        fun read(token: String, requestWorkspace: WorkspaceId, resourcePath: String) = client.post()
            .uri("/v2/workspaces/$requestWorkspace/agent/read")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .bodyValue(objectMapper.writeValueAsString(mapOf("path" to resourcePath)))
            .exchange()
        fun assertHistory() {
            val response = read(agentToken, workspaceId, path).expectStatus().isOk.expectBody()
                .jsonPath("$.body.runKind").isEqualTo("SEMANTIC")
                .jsonPath("$.body.sql").isEqualTo(originalSql)
                .jsonPath("$.body.query.metrics[0]").isEqualTo("revenue")
                .jsonPath("$.body.query.rowLimit").isEqualTo(37)
                .jsonPath("$.body.query.schemaVersion").isEqualTo(4)
                .jsonPath("$.body.query.groupBys").isEmpty
                .jsonPath("$.body.query.filters").isEmpty
                .jsonPath("$.body.query.orderBy").isEmpty
                .jsonPath("$.body.parameters").doesNotExist()
                .returnResult().responseBody
            assertThat(checkNotNull(response)).isNotEmpty()
            assertThat((checkNotNull(runBlocking { repository.findRun(succeeded.id) }).kind as ExploreRun.Kind.Semantic).query)
                .isEqualTo(originalQuery)
        }
        fun humanRead() = runBlocking {
            access.read(ReadExploreRunCommand(workspaceId, succeeded.id, PresentedCredential(browserToken), null))
        }
        fun assertExpiredHistory() {
            assertHistory()
            read(agentToken, workspaceId, "$path/result").expectStatus().isNotFound
            assertThat((humanRead() as ExploreRunReadOutcome.Observed).result).isEqualTo(ExploreRunResultRead.Expired)
        }
        assertExpiredHistory()
        val missingRun = ExploreRunView(
            id = ExploreRunId.generate(),
            kind = ExploreRunView.Kind.Sql,
            workspaceId = workspaceId,
            connectorBinding = succeeded.asView().connectorBinding,
            requestDigest = succeeded.asView().requestDigest,
            state = ExploreRunState.RUNNING,
            revision = 0,
            resultAvailable = false,
            failureCode = null,
            failureDetail = null,
            createdAt = fixedInstant,
            updatedAt = fixedInstant,
            resultMetadata = null,
            ownerPrincipalId = succeeded.ownerPrincipalId,
            credentialSurface = null,
            agentClientId = null,
        )
        assertThat(runBlocking { access.readRecordedHistory(listOf(succeeded.asView(), missingRun)) })
            .containsExactly(ExploreRunRecord(succeeded.asView(), originalSql))
        val jdbc = NamedParameterJdbcTemplate(dataSource)
        val decisionParameters = mapOf("workspaceId" to workspaceId.value.toJavaUuid(), "actorId" to actorId.toJavaUuid())

        putModel(workspaceId, model.definition.copy(sourceRelations = listOf(SemanticSourceRelation(connectorId, "warehouse", "analytics", "missing"))), model.semanticModelId)
        assertExpiredHistory()
        putModel(workspaceId, model.definition, model.semanticModelId)
        removeCatalogTree(workspaceId, connectorId)
        assertExpiredHistory()
        insertCurrentCatalogTree(workspaceId, connectorId)
        jdbc.update("DELETE FROM semantic.semantic_models WHERE workspace_id = :workspaceId AND semantic_model_id = :modelId", mapOf("workspaceId" to workspaceId.value.toJavaUuid(), "modelId" to model.semanticModelId.value.toJavaUuid()))
        assertExpiredHistory()

        val otherActor = Uuid.generateV7()
        val otherToken = "history-other-${Uuid.generateV7()}"
        val otherWorkspace = WorkspaceId.generate()
        val otherOrganization = Uuid.generateV7()
        transactionOperations.execute {
            seedIdentityMember(otherWorkspace, otherActor, otherOrganization, Uuid.generateV7(), Uuid.generateV7())
            seedHistoryAgentToken(otherActor, otherOrganization, otherToken)
            jdbc.update("INSERT INTO identity_runtime.organization_members(organization_id, user_id, role, joined_at) VALUES (:organizationId, :actorId, 'MEMBER', :createdAt)", mapOf("organizationId" to organizationId.toJavaUuid(), "actorId" to otherActor.toJavaUuid(), "createdAt" to Timestamp.from(fixedInstant)))
            jdbc.update("INSERT INTO identity_runtime.workspace_members(workspace_id, user_id, role, invited_at) VALUES (:workspaceId, :actorId, 'EXPLORER', :createdAt)", decisionParameters + mapOf("actorId" to otherActor.toJavaUuid(), "createdAt" to Timestamp.from(fixedInstant)))
            seedHistoryAgentToken(otherActor, organizationId, "$otherToken-same-organization")
        }
        read("$otherToken-same-organization", workspaceId, path).expectStatus().isOk
        read(otherToken, otherWorkspace, path).expectStatus().isNotFound
        jdbc.update("UPDATE identity_runtime.workspace_members SET role = 'OWNER' WHERE workspace_id = :workspaceId AND user_id = :actorId", decisionParameters + mapOf("actorId" to otherActor.toJavaUuid()))
        jdbc.update("UPDATE identity_runtime.workspace_members SET role = 'VIEWER' WHERE workspace_id = :workspaceId AND user_id = :actorId", decisionParameters)
        read(agentToken, workspaceId, path).expectStatus().isForbidden
        read(agentToken, workspaceId, "$path/result").expectStatus().isForbidden
        assertThat(humanRead()).isInstanceOf(ExploreRunReadOutcome.AdmissionDenied::class.java)
        jdbc.update("DELETE FROM identity_runtime.workspace_members WHERE workspace_id = :workspaceId AND user_id = :actorId", decisionParameters)
        read(agentToken, workspaceId, path).expectStatus().isNotFound
        coVerify(exactly = 0) { artifactStore.head(any()) }
    }

    private fun seedHistoryAgentToken(actorId: Uuid, organizationId: Uuid, token: String) {
        val parameters = mapOf(
            "actorId" to actorId.toJavaUuid(), "organizationId" to organizationId.toJavaUuid(),
            "grantId" to Uuid.generateV7().toJavaUuid(), "tokenId" to Uuid.generateV7().toJavaUuid(),
            "tokenHash" to AgentOAuthTokenHasher().hash(token), "createdAt" to Timestamp.from(fixedInstant),
            "expiresAt" to Timestamp.from(fixedInstant.plusSeconds(900)),
        )
        val jdbc = NamedParameterJdbcTemplate(dataSource)
        jdbc.update("INSERT INTO identity_runtime.agent_oauth_grants(id, owner_user_id, oauth_client_id, resource_audience, scopes, issued_at) VALUES (:grantId, :actorId, 'history-integration', 'https://monomer.test/agent', ARRAY['agent.workspace']::TEXT[], :createdAt)", parameters)
        jdbc.update("INSERT INTO identity_runtime.agent_oauth_access_tokens(id, grant_id, token_hash, issued_at, expires_at) VALUES (:tokenId, :grantId, :tokenHash, :createdAt, :expiresAt)", parameters)
    }

    private fun executionHistoryService(): ExecutionRunService {
        val runner = SpringExecutionRuntimeTransactionRunner(transactionOperations, kotlinx.coroutines.Dispatchers.Unconfined)
        return ExecutionRunService(PostgresExecutionRuntimeStore(dataSource), runner, runner, PostgresRunDispatchPersistence(dataSource, transactionOperations), ExecutableRequestAssignmentCodec())
    }

    private fun persistExecutionInput(run: ExploreRun, sql: String) {
        val repository = PostgresExecutionRuntimeStore(dataSource)
        val runId = ExecutionRunId(run.id.value)
        val objectKey = "artifacts/explore/${run.id.value}.arrow"
        val request = RawSqlExecutionRequest(
            1u, Sha256Digest("0".repeat(64)), sql, emptyList(), 100u, 8192u, 100, 8192, 4096, 8192, false, emptyList(),
        ).let { it.copy(canonicalRequestDigest = RawSqlExecutionRequestCanonicalizer.canonicalDigest(it, objectKey)) }
        val connector = ConnectorRuntimeBindingSpec(
            ConnectorReference(run.connectorBinding.connectorId.value), ExecutionConnectorType.POSTGRESQL,
            mapOf("host" to ConnectorRuntimePropertyValue.Text("fixture-warehouse")),
            ManagedCredentialDispatchReference(CredentialVersionReference(Uuid.generateV7()), "fixture-key", Sha256Digest("a".repeat(64))),
        )
        val spec = ProtoRawSqlRunSpecFactory().create(RawSqlRunSpecCommand(runId, run.workspaceId, objectKey, connector, request, run.createdAt.plusSeconds(60)))
        val admission = ExecutionRunAdmissionService(repository, PostgresRunDispatchPersistence(dataSource, transactionOperations), ExecutableRequestAssignmentCodec(), ExecutionRunLimits(100))
        transactionOperations.executeWithoutResult {
            val admitted = admission.reserveInOwnerTransaction(ExecutionRunAdmissionPort.ReserveCommand(
                runId, ExecutionOwnerReference(ExecutionOwnerType.EXPLORE, ExecutionOwnerId(run.id.value)), run.workspaceId,
                run.idempotencyKey, spec.canonicalRequestDigest, connector.connectorId, connector.connectorType, connector.properties,
                ExecutableRequestKind.QUERY, spec.runtimeBindings, spec.assignmentTemplate, run.createdAt.plusSeconds(10), run.createdAt.plusSeconds(60), run.createdAt,
            ))
            check(admitted is ExecutionRunAdmissionPort.ReserveOutcome.Reserved)
            if (run.state != ExploreRunState.RUNNING) {
                val resultManifest = run.visibleResult?.let { result ->
                    cc.monomer.execution.api.ArrowResultManifest(
                        result.objectKey, result.byteSize, result.objectEtag, result.rowCount, result.columnCount, result.mediaType,
                    )
                }
                val terminalSnapshot = admitted.snapshot.copy(
                    state = cc.monomer.execution.api.ExecutionRunState.valueOf(run.state.name),
                    physicalOutcome = cc.monomer.execution.api.PhysicalOutcome(
                        "history-terminal-${run.id}", cc.monomer.execution.api.PhysicalOutcomeKind.valueOf(run.state.name),
                        run.failureCode?.name, run.failureMessage, resultManifest,
                    ),
                    resultManifest = resultManifest, updatedAt = run.updatedAt, ownerSettledAt = run.updatedAt, version = 1,
                )
                check(repository.compareAndSet(0, terminalSnapshot))
            }
        }
    }

    @Test
    fun `catalog removal preserves editable semantic definition and restored topology`() {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val tree = insertCurrentCatalogTree(workspaceId, connectorId)
        val model = putModel(workspaceId, semanticModelDefinition(connectorId), null)
        val modelRef = ResourceRef(ResourceKind.SEMANTIC_MODEL, model.semanticModelId.value)
        removeCatalogTree(workspaceId, connectorId)
        val updated = putModel(workspaceId, model.definition.copy(label = "Editable without source"), model.semanticModelId)
        assertThat(updated.semanticModelId).isEqualTo(model.semanticModelId)
        val authority = currentDefinitionAuthority()
        inCurrentSnapshot {
            assertThat(authority.readCurrentFocusCone(workspaceId, modelRef))
                .isEqualTo(CurrentResourceFocusConeResolution.Denied(CurrentResourceFocusConeDenial.UNRESOLVED_LINEAGE))
        }
        assertThat(insertCurrentCatalogTree(workspaceId, connectorId).relation).isEqualTo(tree.relation)
        inCurrentSnapshot {
            assertThat(authority.readCurrentFocusCone(workspaceId, modelRef)).isInstanceOf(CurrentResourceFocusConeResolution.Resolved::class.java)
        }
    }

    @Test
    fun `saved Run keeps connector binding and query when current definitions change`() {
        val workspaceId = WorkspaceId.generate()
        val connectorA = ConnectorId.generate()
        val connectorB = ConnectorId.generate()
        insertCurrentCatalogTree(workspaceId, connectorA)
        insertCurrentCatalogTree(workspaceId, connectorB)
        val model = putModel(workspaceId, semanticModelDefinition(connectorA), null)
        val repository = PostgresSemanticRepository(dataSource, objectMapper)
        val service = semanticService(repository, semanticTransactionPort())
        val simpleDefinition = SemanticMetricDefinition.Simple(
            3u, SemanticElementName("revenue"), null, null, null, emptyList(), model.definition.name, SemanticElementName("revenue"),
            display = null,
        )
        val simple = runBlocking {
            service.putMetric(PutMetricCommand(semanticEditEvidence(workspaceId).forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "simple-${Uuid.generateV7()}", null, simpleDefinition))
        } as SemanticMutationResult.Committed
        val originalInput = SemanticMetricInput(simple.value.definition.name, null, null, null, null)
        val derivedDefinition = SemanticMetricDefinition.Derived(
            3u, SemanticElementName("derived_revenue"), null, null, null, emptyList(), "revenue", listOf(originalInput),
            display = null,
        )
        val derived = runBlocking {
            service.putMetric(PutMetricCommand(semanticEditEvidence(workspaceId).forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "derived-${Uuid.generateV7()}", null, derivedDefinition))
        } as SemanticMutationResult.Committed
        val derivedRef = ResourceRef(ResourceKind.METRIC, derived.value.metricId.value)
        val runRepository = PostgresExploreRunRepository(dataSource)
        val run = ExploreRun(
            id = ExploreRunId.generate(),
            kind = ExploreRun.Kind.Semantic(query = SemanticQuerySpec(listOf(derivedDefinition.name), emptyList(), null, emptyList(), emptyList(), 100), outputColumnDisplays = null, reportingTimeZone = null),
            idempotencyKey = "saved-history-${Uuid.generateV7()}",
            ownerPrincipalId = PrincipalId.generate(),
            credentialSurface = null,
            agentClientId = null,
            connectorBinding = ExploreConnectorBinding(workspaceId, connectorA, ExecutionConnectorType.POSTGRESQL),
            requestDigest = ExploreDigest("sha256:${"e".repeat(64)}"),
            state = ExploreRunState.RUNNING,
            visibleResult = null,
            failureCode = null,
            failureMessage = null,
            revision = 0,
            createdAt = fixedInstant,
            updatedAt = fixedInstant,
        )
        transactionOperations.execute { check(runRepository.insertAdmittedRun(run)) }
        putModel(
            workspaceId,
            model.definition.copy(sourceRelations = model.definition.sourceRelations + SemanticSourceRelation(connectorB, "warehouse", "analytics", "missing")),
            model.semanticModelId,
        )
        putModel(workspaceId, semanticModelDefinition(connectorA, name = "unrelated", relationName = "also_missing"), null)
        val missingInput = SemanticMetricInput(SemanticElementName("missing_metric"), null, null, null, null)
        val updatedDerived = derivedDefinition.copy(expression = "revenue + missing_metric", inputMetrics = listOf(originalInput, missingInput))
        assertThat(runBlocking {
            service.putMetric(PutMetricCommand(semanticEditEvidence(workspaceId).forMutation(cc.monomer.identity.api.MutationIntent.METRIC_UPDATE), "derived-update-${Uuid.generateV7()}", derived.value.metricId, updatedDerived))
        }).isInstanceOf(SemanticMutationResult.Committed::class.java)
        val authority = currentDefinitionAuthority()
        inCurrentSnapshot {
            val saved = checkNotNull(runRepository.findRun(run.id))
            assertThat(saved.connectorBinding.connectorId).isEqualTo(connectorA)
            assertThat((saved.kind as ExploreRun.Kind.Semantic).query).isEqualTo((run.kind as ExploreRun.Kind.Semantic).query)
            assertThat(authority.readCurrentFocusCone(workspaceId, derivedRef))
                .isEqualTo(CurrentResourceFocusConeResolution.Denied(CurrentResourceFocusConeDenial.UNRESOLVED_LINEAGE))
        }
        runBlocking {
            service.putMetric(PutMetricCommand(
                semanticEditEvidence(workspaceId).forMutation(cc.monomer.identity.api.MutationIntent.METRIC_UPDATE), "derived-retarget-${Uuid.generateV7()}", derived.value.metricId,
                updatedDerived.copy(expression = "missing_metric", inputMetrics = listOf(missingInput)),
            ))
        }
        inCurrentSnapshot {
            val saved = checkNotNull(runRepository.findRun(run.id))
            assertThat(saved.connectorBinding).isEqualTo(run.connectorBinding)
            assertThat((saved.kind as ExploreRun.Kind.Semantic).query).isEqualTo((run.kind as ExploreRun.Kind.Semantic).query)
        }
    }

    @Test
    fun `current graph reads Semantic and Catalog from one repeatable read snapshot`() {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val tree = insertCurrentCatalogTree(workspaceId, connectorId)
        val replacement = insertCurrentCatalogRelation(workspaceId, connectorId, tree.schema, "returns")
        val model = putModel(workspaceId, semanticModelDefinition(connectorId), null)
        val modelRef = ResourceRef(ResourceKind.SEMANTIC_MODEL, model.semanticModelId.value)
        val repository = PostgresSemanticRepository(dataSource, objectMapper)
        val currentRead = SemanticCurrentReadService(repository, repository, semanticTransactionPort(), mockk {
            coEvery { listByWorkspace(workspaceId) } returns emptyList()
        },
            io.mockk.mockk(relaxed = true),
        )
        val semanticReadFinished = CountDownLatch(1)
        val writerCommitted = CountDownLatch(1)
        val read = object : SemanticCurrentReadUseCase by currentRead {
            override fun findModelsInCurrentTransaction(workspaceId: WorkspaceId): List<SemanticModelView> {
                val models = currentRead.findModelsInCurrentTransaction(workspaceId)
                semanticReadFinished.countDown()
                check(writerCommitted.await(10, TimeUnit.SECONDS))
                return models
            }
        }
        Executors.newSingleThreadExecutor().use { executor ->
            val concurrentWrite = executor.submit {
                check(semanticReadFinished.await(10, TimeUnit.SECONDS))
                try {
                    transactionOperations.execute {
                        val current = checkNotNull(repository.findModel(model.semanticModelId))
                        repository.updateModel(current.copy(definition = semanticModelDefinition(connectorId, relationName = "returns")), null, null, null)
                        NamedParameterJdbcTemplate(dataSource).update(
                            "DELETE FROM catalog.current_objects WHERE workspace_id = :workspaceId AND object_id = :objectId",
                            mapOf("workspaceId" to workspaceId.value.toJavaUuid(), "objectId" to tree.relation.value.toJavaUuid()),
                        )
                    }
                } finally {
                    writerCommitted.countDown()
                }
            }
            val authority = CurrentDefinitionResourceAuthority(read, PostgresCatalogCurrentObjectsReadPort(dataSource))
            val before = inCurrentSnapshot { authority.readCurrentFocusCone(workspaceId, modelRef) }
            concurrentWrite.get(10, TimeUnit.SECONDS)
            assertThat((before as CurrentResourceFocusConeResolution.Resolved).cone.edges)
                .containsExactly(LineageEdge(modelRef, ResourceRef(ResourceKind.RELATION, tree.relation.value)))
        }
        val after = inCurrentSnapshot { currentDefinitionAuthority().readCurrentFocusCone(workspaceId, modelRef) }
        assertThat((after as CurrentResourceFocusConeResolution.Resolved).cone.edges)
            .containsExactly(LineageEdge(modelRef, ResourceRef(ResourceKind.RELATION, replacement.value)))
    }

    @Test
    fun `current graph reports bounded PostgreSQL read cost at local workspace scale`() {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val tree = insertCurrentCatalogTree(workspaceId, connectorId, "orders_0")
        val relationIds = listOf(tree.relation) + (1 until 80).map { index ->
            insertCurrentCatalogRelation(workspaceId, connectorId, tree.schema, "orders_$index")
        }
        dataSource.connection.use { connection ->
            repeat(186) { index ->
                val relationIndex = index % 80
                val columnIndex = index / 80
                val identity = CatalogStableObjectIdentityCodec.derive(
                    connectorId, CatalogObjectKind.COLUMN, "warehouse", "analytics", "orders_$relationIndex", "column_$columnIndex", null,
                )
                connection.prepareStatement(
                    """
                    INSERT INTO catalog.current_objects (
                        workspace_id, connector_id, object_id, identity_digest, parent_object_id, object_kind,
                        catalog_name, schema_name, object_name, member_name, qualified_name,
                        column_ordinal, column_nullable, native_type, content_digest, observed_at
                    ) VALUES (?, ?, ?, ?, ?, 'COLUMN', 'warehouse', 'analytics', ?, ?, ?, ?, TRUE, 'BIGINT', ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, workspaceId.value.toJavaUuid())
                    statement.setObject(2, connectorId.value.toJavaUuid())
                    statement.setObject(3, identity.objectId.value.toJavaUuid())
                    statement.setString(4, identity.identityDigest.value)
                    statement.setObject(5, relationIds[relationIndex].value.toJavaUuid())
                    statement.setString(6, "orders_$relationIndex")
                    statement.setString(7, "column_$columnIndex")
                    statement.setString(8, "warehouse.analytics.orders_$relationIndex.column_$columnIndex")
                    statement.setInt(9, columnIndex + 1)
                    statement.setString(10, identity.identityDigest.value)
                    statement.setTimestamp(11, Timestamp.from(fixedInstant))
                    statement.executeUpdate()
                }
            }
        }
        val models = (0 until 3).map { index ->
            putModel(workspaceId, semanticModelDefinition(connectorId, name = "orders_$index", relationName = "orders_$index"), null)
        }
        val repository = PostgresSemanticRepository(dataSource, objectMapper)
        val service = semanticService(repository, semanticTransactionPort())
        val metricRefs = mutableListOf<ResourceRef>()
        val simpleNames = models.mapIndexed { index, model ->
            val definition = SemanticMetricDefinition.Simple(
                3u, SemanticElementName("revenue_$index"), null, null, null, emptyList(), model.definition.name, SemanticElementName("revenue"),
                display = null,
            )
            val result = runBlocking { service.putMetric(PutMetricCommand(semanticEditEvidence(workspaceId).forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "scale-simple-$index", null, definition)) }
                as SemanticMutationResult.Committed
            metricRefs += ResourceRef(ResourceKind.METRIC, result.value.metricId.value)
            definition.name
        }
        val inputNames = simpleNames.toMutableList()
        repeat(2) { index ->
            val definition = SemanticMetricDefinition.Derived(
                3u, SemanticElementName("combined_$index"), null, null, null, emptyList(),
                inputNames.joinToString(" + ") { it.value },
                inputNames.map { SemanticMetricInput(it, null, null, null, null) },
                display = null,
            )
            val result = runBlocking { service.putMetric(PutMetricCommand(semanticEditEvidence(workspaceId).forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "scale-derived-$index", null, definition)) }
                as SemanticMutationResult.Committed
            metricRefs += ResourceRef(ResourceKind.METRIC, result.value.metricId.value)
            inputNames += definition.name
        }
        val queryCount = AtomicInteger()
        val measuredDataSource = object : DataSource by dataSource {
            override fun getConnection(): Connection {
                val connection = dataSource.connection
                return java.lang.reflect.Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, arguments ->
                    if (method.name == "prepareStatement" && (arguments?.firstOrNull() as? String)?.trimStart()?.startsWith("SELECT") == true) {
                        queryCount.incrementAndGet()
                    }
                    method.invoke(connection, *(arguments ?: emptyArray()))
                } as Connection
            }
        }
        val measuredRepository = PostgresSemanticRepository(measuredDataSource, objectMapper)
        val authority = CurrentDefinitionResourceAuthority(
            SemanticCurrentReadService(measuredRepository, measuredRepository, semanticTransactionPort(), mockk {
                coEvery { listByWorkspace(workspaceId) } returns emptyList()
            },
                io.mockk.mockk(relaxed = true),
            ),
            PostgresCatalogCurrentObjectsReadPort(measuredDataSource),
        )
        val measuredTransaction = TransactionTemplate(DataSourceTransactionManager(measuredDataSource)).apply {
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            isReadOnly = true
        }
        val startedAt = System.nanoTime()
        val result = measuredTransaction.execute {
            authority.readCurrentFocusCone(workspaceId, metricRefs.last())
        }
        val durationMillis = (System.nanoTime() - startedAt) / 1_000_000.0
        assertThat(result).isInstanceOf(CurrentResourceFocusConeResolution.Resolved::class.java)
        assertThat(EphemeralPostgresAuthoritySupport.count("catalog.current_objects", "workspace_id", workspaceId.value.toJavaUuid())).isEqualTo(268)
        assertThat((result as CurrentResourceFocusConeResolution.Resolved).cone.ancestors).hasSize(10)
        assertThat(result.cone.edges).hasSize(13)
        assertThat(queryCount.get()).isEqualTo(4)
        println("CURRENT_DEFINITION_GRAPH catalogObjects=268 relationNodes=80 semanticModels=3 metrics=5 focusAncestors=10 conceptualEdges=13 selectQueries=${queryCount.get()} elapsedMillis=$durationMillis")
    }

    private fun currentDefinitionAuthority(): CurrentDefinitionResourceAuthority {
        val repository = PostgresSemanticRepository(dataSource, objectMapper)
        return CurrentDefinitionResourceAuthority(
            SemanticCurrentReadService(repository, repository, semanticTransactionPort(), mockk(relaxed = true),
                catalogReadPort = io.mockk.mockk(relaxed = true),
            ),
            PostgresCatalogCurrentObjectsReadPort(dataSource),
        )
    }

    private fun <T> inCurrentSnapshot(block: () -> T): T =
        runBlocking { semanticTransactionPort().inSnapshotTransaction(block) }

    private fun putModel(
        workspaceId: WorkspaceId,
        definition: SemanticModelDefinition,
        modelId: SemanticModelId?,
    ): SemanticModelView {
        val repository = PostgresSemanticRepository(dataSource, objectMapper)
        val result = runBlocking {
            semanticService(repository, semanticTransactionPort()).putModel(
                PutSemanticModelCommand(semanticEditEvidence(workspaceId).forMutation(
                    if (modelId == null) cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE
                    else cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE,
                ), "model-${Uuid.generateV7()}", modelId, definition, emptyList()),
            )
        }
        assertThat(result).isInstanceOf(SemanticMutationResult.Committed::class.java)
        return (result as SemanticMutationResult.Committed).value.model
    }

    @Test
    fun `overlapping semantic mutations read the latest committed definition after the workspace lock`() {
        val workspaceId = WorkspaceId(Uuid.generateV7())
        val connectorId = ConnectorId(Uuid.generateV7())
        val catalogTree = insertCurrentCatalogTree(workspaceId, connectorId)

        val delegate = PostgresSemanticRepository(dataSource, objectMapper)
        val firstLockAcquired = CountDownLatch(1)
        val secondLockAttempted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstLock = AtomicBoolean(true)
        val signallingRepository = object : SemanticRepository by delegate {
            override fun lockWorkspaceMutation(workspaceId: WorkspaceId) {
                if (firstLock.compareAndSet(true, false)) {
                    delegate.lockWorkspaceMutation(workspaceId)
                    firstLockAcquired.countDown()
                    check(releaseFirst.await(10, TimeUnit.SECONDS)) {
                        "first Semantic mutation did not receive the release signal"
                    }
                } else {
                    secondLockAttempted.countDown()
                    delegate.lockWorkspaceMutation(workspaceId)
                }
            }
        }
        val semanticPort = semanticTransactionPort()
        val service = semanticService(signallingRepository, semanticPort)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<SemanticMutationResult<cc.monomer.semantic.api.SemanticModelWriteResult>> {
                runBlocking {
                    service.putModel(
                        PutSemanticModelCommand(
                            authorization = semanticEditEvidence(workspaceId).forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE),
                            idempotencyKey = "overlap-first-${Uuid.generateV7()}",
                            semanticModelId = null,
                            definition = semanticModelDefinition(connectorId),
                            aggregateMetrics = emptyList(),
                        ),
                    )
                }
            }
            assertThat(firstLockAcquired.await(10, TimeUnit.SECONDS)).isTrue()

            val second = executor.submit<SemanticMutationResult<cc.monomer.semantic.api.SemanticModelWriteResult>> {
                runBlocking {
                    service.putModel(
                        PutSemanticModelCommand(
                            authorization = semanticEditEvidence(workspaceId).forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE),
                            idempotencyKey = "overlap-second-${Uuid.generateV7()}",
                            semanticModelId = null,
                            definition = semanticModelDefinition(connectorId),
                            aggregateMetrics = emptyList(),
                        ),
                    )
                }
            }
            assertThat(secondLockAttempted.await(10, TimeUnit.SECONDS)).isTrue()
            releaseFirst.countDown()

            assertThat(first.get(10, TimeUnit.SECONDS))
                .isInstanceOf(SemanticMutationResult.Committed::class.java)
            assertThat(second.get(10, TimeUnit.SECONDS))
                .isEqualTo(SemanticMutationResult.Conflict)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `overlapping updates to one model persist one complete definition and matching search projection`() {
        val workspaceId = WorkspaceId.generate()
        val evidence = semanticEditEvidence(workspaceId)
        val delegate = PostgresSemanticRepository(dataSource, objectMapper)
        val transactionPort = semanticTransactionPort()
        val initialDefinition = semanticModelDefinition(ConnectorId.generate(), name = "atomic_orders")
        val firstDefinition = initialDefinition.copy(
            label = "Firstmodellabel",
            description = "first complete model body",
            measures = initialDefinition.measures.map { it.copy(expression = "amount * 2") },
            dimensions = initialDefinition.dimensions.map { it.copy(expression = "first_ordered_at") },
        )
        val secondDefinition = initialDefinition.copy(
            label = "Secondmodellabel",
            description = "second complete model body",
            measures = initialDefinition.measures.map { it.copy(expression = "amount * 3") },
            dimensions = initialDefinition.dimensions.map { it.copy(expression = "second_ordered_at") },
        )
        val created = (runBlocking {
            semanticService(delegate, transactionPort).putModel(
                PutSemanticModelCommand(evidence.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE), "atomic-create-${Uuid.generateV7()}", null, initialDefinition, emptyList()),
            )
        } as SemanticMutationResult.Committed).value.model
        val firstLockAcquired = CountDownLatch(1)
        val secondLockAttempted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstLock = AtomicBoolean(true)
        val signallingRepository = object : SemanticRepository by delegate {
            override fun lockWorkspaceMutation(workspaceId: WorkspaceId) {
                if (firstLock.compareAndSet(true, false)) {
                    delegate.lockWorkspaceMutation(workspaceId)
                    firstLockAcquired.countDown()
                    check(releaseFirst.await(10, TimeUnit.SECONDS))
                } else {
                    secondLockAttempted.countDown()
                    delegate.lockWorkspaceMutation(workspaceId)
                }
            }
        }
        val service = semanticService(signallingRepository, transactionPort)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<SemanticMutationResult<cc.monomer.semantic.api.SemanticModelWriteResult>> {
                runBlocking {
                    service.putModel(PutSemanticModelCommand(
                        evidence.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE), "atomic-first-${Uuid.generateV7()}", created.semanticModelId, firstDefinition,
                        emptyList(),
                    ))
                }
            }
            assertThat(firstLockAcquired.await(10, TimeUnit.SECONDS)).isTrue()
            val second = executor.submit<SemanticMutationResult<cc.monomer.semantic.api.SemanticModelWriteResult>> {
                runBlocking {
                    service.putModel(PutSemanticModelCommand(
                        evidence.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE), "atomic-second-${Uuid.generateV7()}", created.semanticModelId, secondDefinition,
                        emptyList(),
                    ))
                }
            }
            assertThat(secondLockAttempted.await(10, TimeUnit.SECONDS)).isTrue()
            releaseFirst.countDown()
            val firstResult = first.get(10, TimeUnit.SECONDS)
            val secondResult = second.get(10, TimeUnit.SECONDS)
            assertThat(firstResult).isInstanceOf(SemanticMutationResult.Committed::class.java)
            assertThat(secondResult).isInstanceOf(SemanticMutationResult.Committed::class.java)
            val firstView = (firstResult as SemanticMutationResult.Committed).value.model
            val secondView = (secondResult as SemanticMutationResult.Committed).value.model
            assertThat(firstView.semanticModelId).isEqualTo(created.semanticModelId)
            assertThat(secondView.semanticModelId).isEqualTo(created.semanticModelId)
            assertThat(firstView.definition).isEqualTo(firstDefinition)
            assertThat(secondView.definition).isEqualTo(secondDefinition)
            val persisted = runBlocking {
                transactionPort.inSnapshotTransaction { delegate.findModel(created.semanticModelId)!! }
            }
            assertThat(persisted.definition).isIn(firstDefinition, secondDefinition)
            val invalid = runBlocking {
                service.putModel(PutSemanticModelCommand(
                    evidence.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE), "invalid-model-${Uuid.generateV7()}", created.semanticModelId,
                    firstDefinition.copy(name = SemanticElementName("renamed_orders")),
                    aggregateMetrics = emptyList(),
                ))
            }
            assertThat(invalid).isInstanceOf(SemanticMutationResult.Invalid::class.java)
            runBlocking {
                transactionPort.inSnapshotTransaction {
                    assertThat(delegate.findModel(created.semanticModelId)).isEqualTo(persisted)
                    assertThat(delegate.findModels(workspaceId)).containsExactly(persisted)
                    val losingDefinition = if (persisted.definition == firstDefinition) secondDefinition else firstDefinition
                    assertThat(searchIndex.candidates(
                        workspaceId.value.toJavaUuid(), "workspace", setOf(SearchResourceKind.SEMANTIC_MODEL),
                        persisted.definition.label!!, SearchMatch.WHOLE_TOKENS, 20, 0,
                    )).containsExactly("workspace/semantic-models/${persisted.definition.name.value}")
                    assertThat(searchIndex.candidates(
                        workspaceId.value.toJavaUuid(), "workspace", setOf(SearchResourceKind.SEMANTIC_MODEL),
                        losingDefinition.label!!, SearchMatch.WHOLE_TOKENS, 20, 0,
                    )).isEmpty()
                }
            }
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `overlapping updates to one metric persist one complete definition and matching search projection`() {
        val workspaceId = WorkspaceId.generate()
        val evidence = semanticEditEvidence(workspaceId)
        val delegate = PostgresSemanticRepository(dataSource, objectMapper)
        val transactionPort = semanticTransactionPort()
        val initialDefinition = SemanticMetricDefinition.Ratio(
            3u, SemanticElementName("atomic_ratio"), null, null, null, emptyList(),
            SemanticMetricInput(SemanticElementName("initial_numerator"), null, null, null, null),
            SemanticMetricInput(SemanticElementName("initial_denominator"), null, null, null, null),
            display = null,
        )
        val firstDefinition = initialDefinition.copy(
            label = "Firstmetriclabel",
            description = "first complete ratio body",
            numerator = SemanticMetricInput(SemanticElementName("first_numerator"), null, null, null, null),
            denominator = SemanticMetricInput(SemanticElementName("first_denominator"), null, null, null, null),
        )
        val secondDefinition = initialDefinition.copy(
            label = "Secondmetriclabel",
            description = "second complete ratio body",
            numerator = SemanticMetricInput(SemanticElementName("second_numerator"), null, null, null, null),
            denominator = SemanticMetricInput(SemanticElementName("second_denominator"), null, null, null, null),
        )
        val created = (runBlocking {
            semanticService(delegate, transactionPort).putMetric(
                PutMetricCommand(evidence.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "atomic-create-${Uuid.generateV7()}", null, initialDefinition),
            )
        } as SemanticMutationResult.Committed).value
        val firstLockAcquired = CountDownLatch(1)
        val secondLockAttempted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstLock = AtomicBoolean(true)
        val signallingRepository = object : SemanticRepository by delegate {
            override fun lockWorkspaceMutation(workspaceId: WorkspaceId) {
                if (firstLock.compareAndSet(true, false)) {
                    delegate.lockWorkspaceMutation(workspaceId)
                    firstLockAcquired.countDown()
                    check(releaseFirst.await(10, TimeUnit.SECONDS))
                } else {
                    secondLockAttempted.countDown()
                    delegate.lockWorkspaceMutation(workspaceId)
                }
            }
        }
        val service = semanticService(signallingRepository, transactionPort)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<SemanticMutationResult<MetricView>> {
                runBlocking {
                    service.putMetric(PutMetricCommand(
                        evidence.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_UPDATE), "atomic-first-${Uuid.generateV7()}", created.metricId, firstDefinition,
                    ))
                }
            }
            assertThat(firstLockAcquired.await(10, TimeUnit.SECONDS)).isTrue()
            val second = executor.submit<SemanticMutationResult<MetricView>> {
                runBlocking {
                    service.putMetric(PutMetricCommand(
                        evidence.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_UPDATE), "atomic-second-${Uuid.generateV7()}", created.metricId, secondDefinition,
                    ))
                }
            }
            assertThat(secondLockAttempted.await(10, TimeUnit.SECONDS)).isTrue()
            releaseFirst.countDown()
            val firstResult = first.get(10, TimeUnit.SECONDS)
            val secondResult = second.get(10, TimeUnit.SECONDS)
            assertThat(firstResult).isInstanceOf(SemanticMutationResult.Committed::class.java)
            assertThat(secondResult).isInstanceOf(SemanticMutationResult.Committed::class.java)
            val firstView = (firstResult as SemanticMutationResult.Committed).value
            val secondView = (secondResult as SemanticMutationResult.Committed).value
            assertThat(firstView.metricId).isEqualTo(created.metricId)
            assertThat(secondView.metricId).isEqualTo(created.metricId)
            assertThat(firstView.definition).isEqualTo(firstDefinition)
            assertThat(secondView.definition).isEqualTo(secondDefinition)
            val persisted = runBlocking {
                transactionPort.inSnapshotTransaction { delegate.findMetric(created.metricId)!! }
            }
            assertThat(persisted.definition).isIn(firstDefinition, secondDefinition)
            assertThatThrownBy {
                runBlocking {
                    service.putMetric(PutMetricCommand(
                        evidence.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_UPDATE), "invalid-ratio-${Uuid.generateV7()}", created.metricId,
                        firstDefinition.copy(denominator = firstDefinition.numerator),
                    ))
                }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("numerator and denominator must not have the same effective input")
            runBlocking {
                transactionPort.inSnapshotTransaction {
                    assertThat(delegate.findMetric(created.metricId)).isEqualTo(persisted)
                    assertThat(delegate.findMetrics(workspaceId)).containsExactly(persisted)
                    val losingDefinition = if (persisted.definition == firstDefinition) secondDefinition else firstDefinition
                    assertThat(searchIndex.candidates(
                        workspaceId.value.toJavaUuid(), "workspace", setOf(SearchResourceKind.METRIC),
                        persisted.definition.label!!, SearchMatch.WHOLE_TOKENS, 20, 0,
                    )).containsExactly("workspace/metrics/${persisted.definition.name.value}")
                    assertThat(searchIndex.candidates(
                        workspaceId.value.toJavaUuid(), "workspace", setOf(SearchResourceKind.METRIC),
                        losingDefinition.label!!, SearchMatch.WHOLE_TOKENS, 20, 0,
                    )).isEmpty()
                }
            }
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `commit outcome ambiguity is distinct from confirmed rollback`() {
        listOf(
            TransactionSystemException("commit acknowledgement lost"),
            IllegalStateException("after-commit listener failed"),
        ).forEach { commitFailure ->
            val ambiguousBoundary =
                object : TransactionTemplate(DataSourceTransactionManager(dataSource)) {
                    init {
                        timeout = 5
                    }

                    override fun <T : Any?> execute(action: TransactionCallback<T>): T {
                        super.execute(action)
                        throw commitFailure
                    }
                }
            assertThatThrownBy {
                runBlocking {
                    connectorTransactionPort(ambiguousBoundary)
                        .inTransaction { "possibly committed" }
                }
            }.isInstanceOf(ConnectorTransactionOutcomeUnknownException::class.java)
        }

        assertThatThrownBy {
            runBlocking {
                connectorTransactionPort(transactionOperations).inTransaction {
                    throw TransactionSystemException("callback failed before commit")
                }
            }
        }.isInstanceOf(ConnectorTransactionRolledBackException::class.java)
    }

    private fun connectorTransactionPort(
        operations: TransactionOperations,
    ): SpringConnectorTransactionPort =
        SpringConnectorTransactionPort(
            dataSource = dataSource,
            transactionOperations = operations,
            blockingDispatcher = blockingDispatcher,
            timeouts =
                ConnectorTransactionTimeouts(
                    transaction = Duration.ofSeconds(5),
                    statement = Duration.ofSeconds(2),
                    lock = Duration.ofMillis(300),
                ),
        )

    private fun catalogTransactionPort(
        operations: TransactionOperations,
    ): SpringCatalogTransactionPort =
        SpringCatalogTransactionPort(
            dataSource = dataSource,
            transactionOperations = operations,
            readOnlyTransactionOperations =
                TransactionTemplate(DataSourceTransactionManager(dataSource)).apply {
                    isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
                    isReadOnly = true
                    timeout = 5
                },
            blockingDispatcher = blockingDispatcher,
            timeouts =
                CatalogTransactionTimeouts(
                    transaction = Duration.ofSeconds(5),
                    statement = Duration.ofSeconds(2),
                    lock = Duration.ofSeconds(2),
                ),
        )

    private fun semanticTransactionPort(
        lockTimeout: Duration = Duration.ofMillis(300),
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = blockingDispatcher,
    ): SpringSemanticTransactionPort =
        SpringSemanticTransactionPort(
            dataSource = dataSource,
            transactionOperations = semanticTransactionTemplate(
                TransactionDefinition.ISOLATION_REPEATABLE_READ,
            ),
            mutationTransactionOperations = semanticTransactionTemplate(
                TransactionDefinition.ISOLATION_READ_COMMITTED,
            ),
            blockingDispatcher = dispatcher,
            timeouts =
                SemanticTransactionTimeouts(
                    transaction = Duration.ofSeconds(5),
                    statement = Duration.ofSeconds(2),
                    lock = lockTimeout,
                ),
        )

    private fun semanticTransactionTemplate(isolationLevel: Int): TransactionTemplate =
        TransactionTemplate(DataSourceTransactionManager(dataSource)).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
            this.isolationLevel = isolationLevel
            timeout = 5
        }

    private fun semanticService(
        repository: SemanticRepository,
        transactionPort: SpringSemanticTransactionPort,
    ): SemanticAuthorityService =
        SemanticAuthorityService(
            repository = repository,
            transactionPort = transactionPort,
            clock = Clock.fixed(fixedInstant, ZoneOffset.UTC),
            searchProjection = SemanticDashboardSearchProjection(searchIndex),
        )

    private fun newCatalogSelectionRepository(): PostgresCatalogSelectionRepository =
        PostgresCatalogSelectionRepository(
            dataSource = dataSource,
            objectMapper = objectMapper,
            searchProjection = CatalogConnectorSearchProjection(searchIndex),
        )

    private fun newCatalogIngestRepository(): PostgresCatalogIngestRepository =
        PostgresCatalogIngestRepository(
            dataSource = dataSource,
            objectMapper = objectMapper,
            searchProjection = CatalogConnectorSearchProjection(searchIndex),
            selectionRepository = newCatalogSelectionRepository(),
        )

    private fun semanticEditEvidence(workspaceId: WorkspaceId): IdentityRoleEvidence {
        val actor = CanonicalPrincipal(PrincipalId.generate(), PrincipalKind.USER)
        return IdentityRoleEvidence(
            actor = actor,
            organizationId = OrganizationId.generate(),
            organizationPlan = cc.monomer.identity.api.OrganizationPlan.FREE,
            workspaceId = workspaceId,
            authorizationRevision = AuthorizationRevision(1),
            credentialKind = CredentialKind.SESSION,
            credentialSurface = CredentialSurface.WEB,
            agentClientId = null,
            role = WorkspaceRole.EDITOR,
        )
    }

    private fun semanticModelDefinition(
        connectorId: ConnectorId,
        name: String = "orders",
        relationName: String = "orders",
        measureExpression: String = "amount",
    ): SemanticModelDefinition =
        SemanticModelDefinition(
            schemaVersion = 3u,
            name = SemanticElementName(name),
            label = null,
            description = null,
            sourceRelations = listOf(
                SemanticSourceRelation(connectorId, "warehouse", "analytics", relationName),
            ),
            aggregationTimeDimension = null,
            entities = listOf(
                SemanticEntityDefinition(
                    name = SemanticElementName("order_id"),
                    type = SemanticEntityKind.PRIMARY,
                    expression = "order_id",
                    label = null,
                    description = null,
                ),
            ),
            dimensions = listOf(
                SemanticDimensionDefinition(
                    name = SemanticElementName("ordered_at"),
                    type = SemanticDimensionKind.TIME,
                    expression = "ordered_at",
                    granularity = SemanticTimeGranularity.DAY,
                    label = null,
                    description = null,
                    isPartition = false,
                    timeKind = null,
                ),
            ),
            measures = listOf(
                SemanticMeasureDefinition(
                    name = SemanticElementName("revenue"),
                    aggregation = SemanticMeasureAggregation.SUM,
                    expression = measureExpression,
                    aggregationTimeDimension = null,
                    nonAdditiveDimension = null,
                    label = null,
                    description = null,
                ),
            ),
        )

    private fun removeCatalogTree(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
    ) {
        val repository = newCatalogIngestRepository()
        runBlocking {
            catalogTransactionPort(transactionOperations).inTransaction {
                repository.removeCurrentCatalogSubtree(
                    workspaceId = workspaceId,
                    connectorId = connectorId,
                    catalogName = "warehouse",
                )
            }
        }
    }

    private data class CurrentCatalogTree(
        val catalog: CatalogObjectId,
        val schema: CatalogObjectId,
        val relation: CatalogObjectId,
    )

    private fun insertCurrentCatalogTree(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        relationName: String = "orders",
    ): CurrentCatalogTree {
        val catalog = CatalogStableObjectIdentityCodec.derive(
            connectorId = connectorId,
            objectKind = CatalogObjectKind.CATALOG,
            catalogName = "warehouse",
            schemaName = null,
            objectName = null,
            memberName = null,
            relationshipKindName = null,
        )
        val schema = CatalogStableObjectIdentityCodec.derive(
            connectorId = connectorId,
            objectKind = CatalogObjectKind.SCHEMA,
            catalogName = "warehouse",
            schemaName = "analytics",
            objectName = null,
            memberName = null,
            relationshipKindName = null,
        )
        val relation = CatalogStableObjectIdentityCodec.derive(
            connectorId = connectorId,
            objectKind = CatalogObjectKind.RELATION,
            catalogName = "warehouse",
            schemaName = "analytics",
            objectName = relationName,
            memberName = null,
            relationshipKindName = null,
        )
        dataSource.connection.use { connection ->
            insertCurrentCatalogObject(
                connection = connection,
                workspaceId = workspaceId,
                connectorId = connectorId,
                identity = catalog,
                parentId = null,
                objectKind = CatalogObjectKind.CATALOG,
                relationKind = null,
                catalogName = "warehouse",
                schemaName = null,
                objectName = null,
            )
            insertCurrentCatalogObject(
                connection = connection,
                workspaceId = workspaceId,
                connectorId = connectorId,
                identity = schema,
                parentId = catalog.objectId,
                objectKind = CatalogObjectKind.SCHEMA,
                relationKind = null,
                catalogName = "warehouse",
                schemaName = "analytics",
                objectName = null,
            )
            insertCurrentCatalogObject(
                connection = connection,
                workspaceId = workspaceId,
                connectorId = connectorId,
                identity = relation,
                parentId = schema.objectId,
                objectKind = CatalogObjectKind.RELATION,
                relationKind = RelationKind.TABLE,
                catalogName = "warehouse",
                schemaName = "analytics",
                objectName = relationName,
            )
        }
        return CurrentCatalogTree(
            catalog = catalog.objectId,
            schema = schema.objectId,
            relation = relation.objectId,
        )
    }

    private fun insertCurrentCatalogRelation(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        schemaId: CatalogObjectId,
        relationName: String,
    ): CatalogObjectId {
        val identity = CatalogStableObjectIdentityCodec.derive(
            connectorId = connectorId,
            objectKind = CatalogObjectKind.RELATION,
            catalogName = "warehouse",
            schemaName = "analytics",
            objectName = relationName,
            memberName = null,
            relationshipKindName = null,
        )
        dataSource.connection.use { connection ->
            insertCurrentCatalogObject(
                connection = connection,
                workspaceId = workspaceId,
                connectorId = connectorId,
                identity = identity,
                parentId = schemaId,
                objectKind = CatalogObjectKind.RELATION,
                relationKind = RelationKind.TABLE,
                catalogName = "warehouse",
                schemaName = "analytics",
                objectName = relationName,
            )
        }
        return identity.objectId
    }

    private fun insertCurrentCatalogObject(
        connection: Connection,
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
        identity: CatalogStableObjectIdentity,
        parentId: CatalogObjectId?,
        objectKind: CatalogObjectKind,
        relationKind: RelationKind?,
        catalogName: String?,
        schemaName: String?,
        objectName: String?,
    ) {
        val qualifiedName = listOfNotNull(catalogName, schemaName, objectName).joinToString(".")
        connection.prepareStatement(
            """
                INSERT INTO catalog.current_objects (
                    workspace_id, connector_id, object_id, identity_digest, parent_object_id,
                    object_kind, relation_kind, catalog_name, schema_name, object_name,
                    qualified_name, content_digest, observed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.setObject(2, connectorId.value.toJavaUuid())
            statement.setObject(3, identity.objectId.value.toJavaUuid())
            statement.setString(4, identity.identityDigest.value)
            statement.setObject(5, parentId?.value?.toJavaUuid())
            statement.setString(6, objectKind.name)
            statement.setString(7, relationKind?.name)
            statement.setString(8, catalogName)
            statement.setString(9, schemaName)
            statement.setString(10, objectName)
            statement.setString(11, qualifiedName)
            statement.setString(12, identity.identityDigest.value)
            statement.setTimestamp(13, Timestamp.from(fixedInstant))
            statement.executeUpdate()
        }
    }

    private fun newConnector(): Connector {
        val connectorType = ConnectorType("postgresql")
        return Connector(
            id = ConnectorId.generate(),
            workspaceId = WorkspaceId(Uuid.generateV7()),
            connectorType = connectorType,
            displayName = "PostgreSQL",
            properties =
                ConnectorSettingsProperties.Postgresql(
                    host = "warehouse.internal",
                    port = 5432,
                    database = "analytics",
                    schema = null,
                    sslMode = null,
                ),
            credentialReference =
                CredentialEnvelopeReference(
                    credentialVersionId = CredentialVersionId.generate(),
                    keyVersion = "key-v1",
                    envelopeDigest = ContentDigest("sha256:${"b".repeat(64)}"),
                ),
            authorityRevision = 1,
            createdAt = fixedInstant,
            updatedAt = fixedInstant,
            deletedAt = null,
        )
    }

    private fun catalogSelection(): CatalogSelection =
        CatalogSelection(
            selectedSchemas =
                setOf(
                    CatalogSelectedSchema(
                        schemaName = "analytics",
                        relations = CatalogRelationSelection.AllRelations,
                    ),
                ),
        )
}

private fun IdentityRoleEvidence.forMutation(intent: cc.monomer.identity.api.MutationIntent) =
    cc.monomer.identity.api.AuthorizedMutationContext(
        organizationPlan, actor, organizationId, workspaceId, authorizationRevision,
        credentialKind, credentialSurface, agentClientId, role, intent, java.time.Instant.parse("2026-09-29T00:00:00Z"),
    )
