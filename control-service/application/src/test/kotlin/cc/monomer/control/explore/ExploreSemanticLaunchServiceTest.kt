@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.explore

import cc.monomer.catalog.api.CatalogCurrentObject
import cc.monomer.catalog.api.CatalogCurrentObjectSet
import cc.monomer.catalog.api.CatalogDigest
import cc.monomer.catalog.api.CatalogObjectId
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.catalog.api.CatalogPhysicalAddress
import cc.monomer.catalog.api.RelationKind
import cc.monomer.connector.api.ConnectorExecutionAccessResult
import cc.monomer.connector.api.ConnectorExecutionAccessUseCase
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorRuntimeBindingProjectionPort
import cc.monomer.connector.api.ConnectorExecutionBinding
import cc.monomer.connector.api.ContentDigest
import cc.monomer.connector.api.CredentialEnvelopeReference
import cc.monomer.connector.api.CredentialVersionId
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionDenial
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.ControlAdmissionStage
import cc.monomer.execution.api.BinaryValue
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorRuntimeBindingSpec
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.CredentialVersionReference
import cc.monomer.execution.api.ExecutionRunPort
import cc.monomer.execution.api.ManagedCredentialDispatchReference
import cc.monomer.execution.api.RawSqlRunSpec
import cc.monomer.execution.api.RawSqlRunSpecCommand
import cc.monomer.execution.api.ResultSinkBinding
import cc.monomer.execution.api.ResultSinkKind
import cc.monomer.execution.api.RunRuntimeBindings
import cc.monomer.execution.api.Sha256Digest
import cc.monomer.explore.api.ExploreDigest
import cc.monomer.explore.api.ExploreRunUseCase
import cc.monomer.explore.api.StartExploreRunOutcome
import cc.monomer.explore.api.StartSemanticExploreRunCommand
import cc.monomer.identity.api.OrganizationId
import cc.monomer.identity.api.AuthorizationRevision
import cc.monomer.identity.api.CanonicalPrincipal
import cc.monomer.identity.api.CredentialKind
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.IdentityAdmissionDenial
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.PrincipalKind
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticQuerySpec
import cc.monomer.semantic.api.SemanticRenderRequest
import cc.monomer.semantic.api.SemanticRenderResult
import cc.monomer.semantic.api.SemanticSqlDialect
import cc.monomer.semantic.api.SemanticSqlRender
import cc.monomer.semantic.api.SemanticInvalidReason
import cc.monomer.semantic.api.SemanticInvalidReasonCode
import cc.monomer.semantic.api.SemanticDefinitionIssue
import cc.monomer.semantic.api.SemanticDefinitionReference
import cc.monomer.semantic.application.SemanticQueryUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking

class ExploreSemanticLaunchServiceTest {
    private val workspaceId = WorkspaceId.parse("019e0000-0000-7000-8000-0000000000a1")
    private val connectorId = ConnectorId(Uuid.parse("019e0000-0000-7000-8000-0000000000c1"))
    private val metricId = MetricId(Uuid.parse("019e0000-0000-7000-8000-0000000000d1"))
    private val modelId = Uuid.parse("019e0000-0000-7000-8000-0000000000d2")
    private val relationId = CatalogObjectId(Uuid.parse("019e0000-0000-8000-8000-0000000000e2"))
    private val calendarId = CatalogObjectId(Uuid.parse("019e0000-0000-8000-8000-0000000000e3"))
    private val ownerId = PrincipalId.parse("019e0000-0000-7000-8000-0000000000d1")
    private val now = Instant.parse("2026-08-20T00:00:00Z")
    private val query = SemanticQuerySpec(
        metrics = listOf(SemanticElementName("revenue")),
        groupBys = emptyList(),
        metricTimeRange = null,
        filters = emptyList(),
        orderBy = emptyList(),
        rowLimit = 100,
    )

    @Test
    fun `partition rejection uses the render snapshot returns bounded facts and creates no run`() = runBlocking {
        val facade = mockk<ControlAdmissionFacade>()
        coEvery { facade.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(resourceEvidence())
        val connector = connectorBinding()
        val connectorAccess = mockk<ConnectorExecutionAccessUseCase>()
        coEvery { connectorAccess.readCurrentForExecution(workspaceId, connectorId) } returns ConnectorExecutionAccessResult.Granted(connector)
        val projection = mockk<ConnectorRuntimeBindingProjectionPort>()
        every { projection.project(connector) } returns runtimeBinding()
        val specFactory = mockk<cc.monomer.execution.api.RawSqlRunSpecFactory>()
        every { specFactory.create(any()) } returns rawSqlRunSpec()
        val facts = (1..20).map {
            cc.monomer.semantic.api.SemanticPartitionFilter(
                cc.monomer.semantic.api.SemanticPartitionFilterKind.SEMANTIC_MODEL, connectorId,
                cc.monomer.semantic.api.SemanticPartitionRelation("warehouse", "analytics", "orders"),
                listOf("order_date"), SemanticElementName("orders"), listOf(SemanticElementName("order_date")), true,
            )
        }
        val render = rendered().copy(dialect = SemanticSqlDialect.BIGQUERY,
            partitionCheck = cc.monomer.semantic.api.SemanticPartitionCheck(true, facts))
        val credentialRead = mockk<cc.monomer.connector.application.ManagedCredentialReadPort>()
        coEvery { credentialRead.readExact(any()) } returns cc.monomer.connector.application.ManagedCredentialReadOutcome.Found(
            cc.monomer.connector.application.CredentialMaterial.copyOf(mapOf("username" to "fixture".toByteArray())),
        )
        val validation = slot<cc.monomer.execution.api.RawSqlValidationRequest>()
        val execution = mockk<ExecutionRunPort>()
        coEvery { execution.validateRawSql(capture(validation)) } returns cc.monomer.execution.api.RawSqlValidationResult.Failed(
            "PARTITION_FILTER_REQUIRED", "Warehouse partition filter rejected",
        )
        val explore = mockk<ExploreRunUseCase>()
        val result = service(facade, renderedSemanticQuery(render), connectorAccess, projection, execution, specFactory, explore,
            credentialRead = credentialRead).start(command())
        val rejected = kotlin.test.assertIs<ExploreSemanticLaunchOutcome.ValidationRejected>(result)
        assertEquals("PARTITION_FILTER_REQUIRED", rejected.code)
        assertEquals(facts.take(16), rejected.partitionFilters)
        assertEquals(render.sql, validation.captured.sql)
        kotlin.test.assertTrue(validation.captured.credentialDocument.all { it == 0.toByte() })
        coVerify(exactly = 0) { explore.start(any<StartSemanticExploreRunCommand>()) }
    }

    @Test
    fun `successful partition validation rechecks role before admitting the unchanged render`() = runBlocking {
        for (roleRevoked in listOf(false, true)) {
        val facade = mockk<ControlAdmissionFacade>()
        val admitted = ControlAdmissionDecision.Admitted(resourceEvidence())
        val denied = ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY,
            ControlAdmissionDenial.Identity(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE))
        coEvery { facade.authorizeWorkspaceRole(any()) } returnsMany listOf(admitted, if (roleRevoked) denied else admitted)
        val connector = connectorBinding()
        val connectorAccess = mockk<ConnectorExecutionAccessUseCase>()
        coEvery { connectorAccess.readCurrentForExecution(workspaceId, connectorId) } returns ConnectorExecutionAccessResult.Granted(connector)
        val projection = mockk<ConnectorRuntimeBindingProjectionPort>()
        every { projection.project(connector) } returns runtimeBinding()
        val specFactory = mockk<cc.monomer.execution.api.RawSqlRunSpecFactory>()
        every { specFactory.create(any()) } returns rawSqlRunSpec()
        val facts = (1..20).map {
            cc.monomer.semantic.api.SemanticPartitionFilter(
                cc.monomer.semantic.api.SemanticPartitionFilterKind.SEMANTIC_MODEL, connectorId,
                cc.monomer.semantic.api.SemanticPartitionRelation("warehouse", "analytics", "orders"),
                listOf("order_date"), SemanticElementName("orders"), listOf(SemanticElementName("order_date")), true,
            )
        }
        val render = rendered().copy(dialect = SemanticSqlDialect.BIGQUERY,
            partitionCheck = cc.monomer.semantic.api.SemanticPartitionCheck(true, facts))
        val credentialRead = mockk<cc.monomer.connector.application.ManagedCredentialReadPort>()
        coEvery { credentialRead.readExact(any()) } returns cc.monomer.connector.application.ManagedCredentialReadOutcome.Found(
            cc.monomer.connector.application.CredentialMaterial.copyOf(mapOf("username" to "fixture".toByteArray())),
        )
        val validation = slot<cc.monomer.execution.api.RawSqlValidationRequest>()
        val execution = mockk<ExecutionRunPort>()
        coEvery { execution.validateRawSql(capture(validation)) } returns cc.monomer.execution.api.RawSqlValidationResult.Accepted
        val explore = mockk<ExploreRunUseCase>()
        val started = slot<StartSemanticExploreRunCommand>()
        coEvery { explore.start(capture(started)) } returns StartExploreRunOutcome.Backpressured
        val result = service(facade, renderedSemanticQuery(render), connectorAccess, projection, execution, specFactory, explore,
            credentialRead = credentialRead).start(command())
        if (roleRevoked) assertEquals(ExploreSemanticLaunchOutcome.AdmissionDenied(denied), result)
        else {
            assertEquals(ExploreSemanticLaunchOutcome.ExecutionUnavailable("EXECUTION_BACKPRESSURED"), result)
            assertEquals(query, started.captured.query)
        }
        coVerify(exactly = 2) { facade.authorizeWorkspaceRole(any()) }
        assertEquals(render.sql, validation.captured.sql)
        kotlin.test.assertTrue(validation.captured.credentialDocument.all { it == 0.toByte() })
        coVerify(exactly = if (roleRevoked) 0 else 1) { explore.start(any<StartSemanticExploreRunCommand>()) }
        }
    }

    @Test
    fun `Explorer role is authorized before Connector credential or Execution access`() = runBlocking {
        val facade = mockk<ControlAdmissionFacade>()
        val denial = ControlAdmissionDecision.Denied(
            ControlAdmissionStage.IDENTITY,
            ControlAdmissionDenial.Identity(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE),
        )
        coEvery { facade.authorizeWorkspaceRole(any()) } returns denial
        val semanticQuery = mockk<SemanticQueryUseCase>()
        coEvery { semanticQuery.renderFromInputs(any()) } returns
            SemanticRenderResult.Rendered(rendered())
        val connectorAccess = mockk<ConnectorExecutionAccessUseCase>()
        val execution = mockk<ExecutionRunPort>()
        val service = service(
            facade = facade,
            semanticQuery = semanticQuery,
            connectorAccess = connectorAccess,
            execution = execution,
        )

        val outcome = service.start(command())

        assertEquals(ExploreSemanticLaunchOutcome.AdmissionDenied(denial), outcome)
        coVerify(exactly = 0) { connectorAccess.readCurrentForExecution(workspaceId, connectorId) }
        coVerify(exactly = 0) { execution.validateRawSql(any()) }
    }

    @Test
    fun `wrong workspace Semantic render is rejected before credential access or run start`() = runBlocking {
        val facade = mockk<ControlAdmissionFacade>()
        coEvery { facade.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(resourceEvidence())
        val connectorAccess = mockk<ConnectorExecutionAccessUseCase>()
        coEvery { connectorAccess.readCurrentForExecution(workspaceId, connectorId) } returns ConnectorExecutionAccessResult.Granted(connectorBinding())
        val explore = mockk<ExploreRunUseCase>()
        val service = service(
            facade = facade,
            semanticQuery = renderedSemanticQuery(rendered().copy(workspaceId = WorkspaceId.generate())),
            connectorAccess = connectorAccess,
            explore = explore,
        )
        assertEquals(ExploreSemanticLaunchOutcome.NotFound, service.start(command()))
        coVerify(exactly = 1) { connectorAccess.readCurrentForExecution(workspaceId, connectorId) }
        coVerify(exactly = 0) { explore.start(any<StartSemanticExploreRunCommand>()) }
    }

    @Test
    fun `a missing Connector reports the Connector problem before credential access or run creation`() = runBlocking {
        val facade = mockk<ControlAdmissionFacade>()
        coEvery { facade.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(resourceEvidence())
        val semanticQuery = mockk<SemanticQueryUseCase>()
        coEvery { semanticQuery.renderFromInputs(any()) } returns SemanticRenderResult.NotFoundOrOutsideWorkspace
        val connectorAccess = mockk<ConnectorExecutionAccessUseCase>()
        coEvery { connectorAccess.readCurrentForExecution(workspaceId, connectorId) } returns ConnectorExecutionAccessResult.Granted(connectorBinding())
        val explore = mockk<ExploreRunUseCase>()
        val service = service(facade, semanticQuery, connectorAccess, explore = explore)

        assertEquals(ExploreSemanticLaunchOutcome.ConnectorNotFound, service.start(command()))

        coVerify(exactly = 1) { connectorAccess.readCurrentForExecution(workspaceId, connectorId) }
        coVerify(exactly = 0) { explore.start(any<StartSemanticExploreRunCommand>()) }
    }

    @Test
    fun `a missing top-level Metric is named before credential access or run creation`() = runBlocking {
        val facade = mockk<ControlAdmissionFacade>()
        coEvery { facade.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(resourceEvidence())
        val semanticQuery = mockk<SemanticQueryUseCase>()
        val missing = SemanticElementName("revenue")
        coEvery { semanticQuery.renderFromInputs(any()) } returns SemanticRenderResult.MetricNotFound(missing)
        val connectorAccess = mockk<ConnectorExecutionAccessUseCase>()
        coEvery { connectorAccess.readCurrentForExecution(workspaceId, connectorId) } returns ConnectorExecutionAccessResult.Granted(connectorBinding())
        val explore = mockk<ExploreRunUseCase>()
        val execution = mockk<ExecutionRunPort>()
        val service = service(facade, semanticQuery, connectorAccess, explore = explore, execution = execution)

        assertEquals(ExploreSemanticLaunchOutcome.MetricNotFound(missing), service.start(command()))

        coVerify(exactly = 1) { connectorAccess.readCurrentForExecution(workspaceId, connectorId) }
        coVerify(exactly = 0) { explore.start(any<StartSemanticExploreRunCommand>()) }
        coVerify(exactly = 0) { execution.validateRawSql(any()) }
    }

    @Test
    fun `invalid Semantic render keeps the broken definitions and starts nothing`() = runBlocking {
        val facade = mockk<ControlAdmissionFacade>()
        coEvery { facade.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(resourceEvidence())
        val reasons = listOf(SemanticInvalidReason(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID, null))
        val issues = listOf(
            SemanticDefinitionIssue(
                SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID,
                SemanticDefinitionReference.Metric(SemanticElementName("revenue")),
                "The measure `amount` is not defined in semantic model `orders`.",
            ),
        )
        val semanticQuery = mockk<SemanticQueryUseCase>()
        coEvery { semanticQuery.renderFromInputs(any()) } returns SemanticRenderResult.Invalid(reasons, issues)
        val connectorAccess = mockk<ConnectorExecutionAccessUseCase>()
        coEvery { connectorAccess.readCurrentForExecution(workspaceId, connectorId) } returns ConnectorExecutionAccessResult.Granted(connectorBinding())
        val explore = mockk<ExploreRunUseCase>()
        val service = service(
            facade = facade,
            semanticQuery = semanticQuery,
            connectorAccess = connectorAccess,
            explore = explore,
        )

        assertEquals(ExploreSemanticLaunchOutcome.Invalid(reasons, issues), service.start(command()))
        coVerify(exactly = 1) { connectorAccess.readCurrentForExecution(workspaceId, connectorId) }
        coVerify(exactly = 0) { explore.start(any<StartSemanticExploreRunCommand>()) }
    }

    @Test
    fun `semantic CTE execution preserves SQL without raw SQL validation`() = runBlocking {
        val facade = mockk<ControlAdmissionFacade>()
        coEvery { facade.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Admitted(resourceEvidence())
        val connector = connectorBinding()
        val connectorAccess = mockk<ConnectorExecutionAccessUseCase>()
        coEvery { connectorAccess.readCurrentForExecution(workspaceId, connectorId) } returns
            ConnectorExecutionAccessResult.Granted(connector)
        val projection = mockk<ConnectorRuntimeBindingProjectionPort>()
        every { projection.project(connector) } returns runtimeBinding()
        val execution = mockk<ExecutionRunPort>()
        coVerify(exactly = 0) { execution.validateRawSql(any()) }
        val specFactory = mockk<cc.monomer.execution.api.RawSqlRunSpecFactory>()
        every { specFactory.create(any<RawSqlRunSpecCommand>()) } returns rawSqlRunSpec()
        val start = slot<StartSemanticExploreRunCommand>()
        val explore = mockk<ExploreRunUseCase>()
        coEvery { explore.start(capture(start)) } returns StartExploreRunOutcome.Conflict
        val service = service(
            facade = facade,
            semanticQuery = renderedSemanticQuery(
                rendered(
                    "WITH orders_cte AS (SELECT * FROM warehouse.analytics.orders) " +
                        "SELECT * FROM orders_cte",
                ),
            ),
            connectorAccess = connectorAccess,
            projection = projection,
            execution = execution,
            specFactory = specFactory,
            explore = explore,
        )

        val outcome = service.start(command(rowLimit = 50_000))

        assertEquals(ExploreSemanticLaunchOutcome.Conflict, outcome)
        assertEquals(50_000uL, start.captured.executionReservation.runtimeBindings.resultSink.maximumArtifactRows)
        assertEquals(ExploreDigest("sha256:${"1".repeat(64)}"), start.captured.requestDigest)
        coVerify(exactly = 0) { facade.authorizeExecution(any()) }
    }

    @Test
    fun `Spring snapshot closes before the real owner initial admission transaction starts`(): Unit = runBlocking {
        val connections = mutableListOf<java.sql.Connection>()
        val dataSource = mockk<javax.sql.DataSource>()
        every { dataSource.connection } answers {
            var autoCommit = true
            var isolation = java.sql.Connection.TRANSACTION_READ_COMMITTED
            mockk<java.sql.Connection>(relaxed = true).also { connection ->
                every { connection.autoCommit } answers { autoCommit }
                every { connection.setAutoCommit(any()) } answers { autoCommit = firstArg(); Unit }
                every { connection.transactionIsolation } answers { isolation }
                every { connection.setTransactionIsolation(any()) } answers { isolation = firstArg(); Unit }
                connections += connection
            }
        }
        val manager = org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)
        val snapshotTemplate = org.springframework.transaction.support.TransactionTemplate(manager).apply {
            isolationLevel = org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ
        }
        val ownerTemplate = org.springframework.transaction.support.TransactionTemplate(manager).apply {
            isolationLevel = org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED
            propagationBehavior = org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }
        val snapshotRunner = cc.monomer.identity.outbound.postgres.SpringConsistentSnapshotTransactionRunner(snapshotTemplate, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        val ownerTransactions = cc.monomer.explore.adapter.outbound.postgres.SpringRetryingExploreInitialAdmissionTransactionPort(
            ownerTemplate,
            cc.monomer.explore.adapter.outbound.postgres.SpringActiveExploreInitialAdmissionTransactionPort(dataSource),
            1,
            kotlinx.coroutines.Dispatchers.IO,
        )
        var snapshotConnection: java.sql.Connection? = null
        fun requireSnapshot() {
            kotlin.test.assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            val connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource)
            assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ, connection.transactionIsolation)
            if (snapshotConnection == null) snapshotConnection = connection
            if (connections.size == 1) kotlin.test.assertSame(snapshotConnection, connection)
        }
        val semanticQuery = mockk<SemanticQueryUseCase>()
        coEvery { semanticQuery.renderFromInputs(any()) } coAnswers {
            kotlin.test.assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            kotlin.test.assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(dataSource))
            SemanticRenderResult.Rendered(rendered())
        }
        val facade = mockk<ControlAdmissionFacade>()
        coEvery { facade.authorizeWorkspaceRole(any()) } coAnswers {
            requireSnapshot()
            ControlAdmissionDecision.Admitted(resourceEvidence())
        }
        val connector = connectorBinding()
        val connectorAccess = mockk<ConnectorExecutionAccessUseCase>()
        coEvery { connectorAccess.readCurrentForExecution(workspaceId, connectorId) } coAnswers {
            requireSnapshot()
            ConnectorExecutionAccessResult.Granted(connector)
        }
        val projection = mockk<ConnectorRuntimeBindingProjectionPort>()
        every { projection.project(connector) } returns runtimeBinding()
        val specFactory = mockk<cc.monomer.execution.api.RawSqlRunSpecFactory>()
        every { specFactory.create(any<RawSqlRunSpecCommand>()) } returns rawSqlRunSpec()
        val explore = mockk<ExploreRunUseCase>()
        var ownerAdmitted = false
        coEvery { explore.start(any<StartSemanticExploreRunCommand>()) } coAnswers {
            ownerTransactions.inInitialAdmissionTransaction {
                kotlin.test.assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                val ownerConnection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource)
                kotlin.test.assertNotSame(snapshotConnection, ownerConnection)
                assertEquals(java.sql.Connection.TRANSACTION_READ_COMMITTED, ownerConnection.transactionIsolation)
                ownerAdmitted = true
                StartExploreRunOutcome.Conflict
            }
        }
        val service = service(
            facade, semanticQuery, connectorAccess, projection,
            specFactory = specFactory, explore = explore, snapshotRunner = snapshotRunner,
        )

        assertEquals(ExploreSemanticLaunchOutcome.Conflict, service.start(command()))
        kotlin.test.assertTrue(ownerAdmitted)
        assertEquals(3, connections.size)
        kotlin.test.assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
    }

    private fun service(
        facade: ControlAdmissionFacade,
        semanticQuery: SemanticQueryUseCase,
        connectorAccess: ConnectorExecutionAccessUseCase,
        projection: ConnectorRuntimeBindingProjectionPort = mockk(),
        execution: ExecutionRunPort = mockk(),
        specFactory: cc.monomer.execution.api.RawSqlRunSpecFactory = mockk(),
        explore: ExploreRunUseCase = mockk(),
        credentialRead: cc.monomer.connector.application.ManagedCredentialReadPort = mockk(),
        repository: cc.monomer.explore.application.ExploreRunRepository = mockk { every { findByAdmissionKey(workspaceId, any()) } returns null },
        snapshotRunner: ConsistentSnapshotTransactionRunner = object : ConsistentSnapshotTransactionRunner {
            override suspend fun <T> run(action: suspend () -> T): T = action()
        },
    ): ExploreSemanticLaunchService {
        coEvery { semanticQuery.readRenderInputs(any()) } coAnswers {
            cc.monomer.semantic.application.SemanticRenderInputs(firstArg(),
                cc.monomer.semantic.application.SemanticCurrentQueryInputs(false, null, null, emptyMap(), emptyMap(), emptyList(), null))
        }
        return ExploreSemanticLaunchService(
        connectorExecutionAccessUseCase = connectorAccess,
        connectorRuntimeBindingProjectionPort = projection,
        semanticQueryUseCase = semanticQuery,
        exploreRunUseCase = explore,
        repository = repository,
        rawSqlRunSpecFactory = specFactory,
        controlAdmissionFacade = facade,
        consistentSnapshotTransactionRunner = snapshotRunner,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        runtimeProperties = cc.monomer.control.testControlRuntimeProperties(),
        managedCredentialReadPort = credentialRead, executionRunPort = execution,
    )
    }

    private fun command(rowLimit: Int = 100) = LaunchSemanticExploreRunCommand(
        workspaceId = workspaceId,
        credential = PresentedCredential("token"),
        expectedOAuthResource = null,
        idempotencyKey = "semantic-1",
        connectorId = connectorId,
        requestDigest = ExploreDigest("sha256:${"1".repeat(64)}"),
        query = query.copy(rowLimit = rowLimit),
    )

    private fun renderedSemanticQuery(render: SemanticSqlRender): SemanticQueryUseCase {
        val semanticQuery = mockk<SemanticQueryUseCase>()
        coEvery { semanticQuery.renderFromInputs(any()) } returns SemanticRenderResult.Rendered(render)
        return semanticQuery
    }

    private fun rendered(sql: String = "SELECT * FROM warehouse.analytics.orders") = SemanticSqlRender(
        workspaceId = workspaceId,
        dialect = SemanticSqlDialect.POSTGRESQL,
        sql = sql,
        reportingTimeZone = java.time.ZoneId.of("UTC"),
        outputColumns = emptyList(),
        partitionCheck = cc.monomer.semantic.api.SemanticPartitionCheck(false, emptyList()),
    )

    private fun currentCatalog(vararg relations: Pair<CatalogObjectId, String>) = CatalogCurrentObjectSet(
        workspaceId = workspaceId,
        connectorId = connectorId,
        objects = listOf(catalogSchema()) + relations.mapIndexed { index, (id, name) ->
            catalogRelation(id, name, ('b'.code + index).toChar())
        },
    )

    private fun catalogSchema() = CatalogCurrentObject(
        id = CatalogObjectId(Uuid.parse("019e0000-0000-8000-8000-0000000000e1")),
        identityDigest = CatalogDigest("sha256:${"a".repeat(64)}"),
        parentId = null,
        kind = CatalogObjectKind.SCHEMA,
        relationKind = null,
        address = CatalogPhysicalAddress("warehouse", "analytics", null, null),
        qualifiedName = "warehouse.analytics",
        nativeType = null,
        contentDigest = CatalogDigest("sha256:${"a".repeat(64)}"),
        definition = null,
        ordinal = null,
        nullable = null,
        defaultExpression = null,
        relationshipKind = null,
        memberOrdinal = null,
        constraintName = null,
        sourceColumn = null,
        referencedCatalog = null,
        referencedSchema = null,
        referencedRelation = null,
        referencedColumn = null,
        observedAt = now,
        partitionKeys = null, clusteringKeys = null, requiresPartitionFilter = null,
    )

    private fun catalogRelation(id: CatalogObjectId, name: String, digestCharacter: Char) = CatalogCurrentObject(
        id = id,
        identityDigest = CatalogDigest("sha256:${digestCharacter.toString().repeat(64)}"),
        parentId = CatalogObjectId(Uuid.parse("019e0000-0000-8000-8000-0000000000e1")),
        kind = CatalogObjectKind.RELATION,
        relationKind = RelationKind.TABLE,
        address = CatalogPhysicalAddress("warehouse", "analytics", name, null),
        qualifiedName = "warehouse.analytics.$name",
        nativeType = null,
        contentDigest = CatalogDigest("sha256:${"b".repeat(64)}"),
        definition = null,
        ordinal = null,
        nullable = null,
        defaultExpression = null,
        relationshipKind = null,
        memberOrdinal = null,
        constraintName = null,
        sourceColumn = null,
        referencedCatalog = null,
        referencedSchema = null,
        referencedRelation = null,
        referencedColumn = null,
        observedAt = now,
        partitionKeys = null, clusteringKeys = null, requiresPartitionFilter = null,
    )

    private fun connectorBinding() = ConnectorExecutionBinding(
        connectorId = connectorId,
        workspaceId = workspaceId,
        connectorType = "POSTGRESQL",
        displayName = "warehouse",
        properties = mapOf("host" to "localhost"),
        credentialReference = CredentialEnvelopeReference(
            credentialVersionId = CredentialVersionId(Uuid.parse("019e0000-0000-7000-8000-0000000000c3")),
            keyVersion = "test-key",
            envelopeDigest = ContentDigest("sha256:${"a".repeat(64)}"),
        ),
        updatedAt = now,
    )

    private fun runtimeBinding() = ConnectorRuntimeBindingSpec(
        connectorId = ConnectorReference(connectorId.value),
        connectorType = ConnectorType.POSTGRESQL,
        properties = mapOf("host" to ConnectorRuntimePropertyValue.Text("localhost")),
        managedCredential = ManagedCredentialDispatchReference(
            credentialVersion = CredentialVersionReference(Uuid.parse("019e0000-0000-7000-8000-0000000000c3")),
            keyVersion = "test-key",
            envelopeDigest = Sha256Digest("a".repeat(64)),
        ),
    )

    private fun rawSqlRunSpec() = RawSqlRunSpec(
        canonicalRequestDigest = Sha256Digest("b".repeat(64)),
        runtimeBindings = RunRuntimeBindings(
            connectorBindingDigest = Sha256Digest("c".repeat(64)),
            credentialVersion = null,
            credentialKeyVersion = "test-key",
            credentialBindingDigest = Sha256Digest("d".repeat(64)),
            resultSink = ResultSinkBinding(
                schemaVersion = 2u,
                kind = ResultSinkKind.ARTIFACT_UPLOAD,
                objectKey = "artifacts/semantic/test.arrow",
                maximumArtifactRows = 50_000uL,
                maximumArtifactBytes = 1_024uL,
                expiresAt = now.plusSeconds(600),
                maximumRecordBatchRows = 1_024,
                maximumFrameBytes = 1_024,
                maximumCellBytes = 4 * 1_024 * 1_024,
                maximumMultipartPartBytes = 1_024,
                bindingDigest = Sha256Digest("e".repeat(64)),
            ),
        ),
        assignmentTemplate = BinaryValue.copyOf(byteArrayOf(1)),
    )

    private fun resourceEvidence() = cc.monomer.identity.api.IdentityRoleEvidence(
        organizationPlan = cc.monomer.identity.api.OrganizationPlan.FREE,
        actor = CanonicalPrincipal(ownerId, PrincipalKind.USER),
        organizationId = OrganizationId.parse("019e0000-0000-7000-8000-0000000000f1"),
        workspaceId = workspaceId,
        authorizationRevision = AuthorizationRevision(1),
        credentialKind = CredentialKind.SESSION,
        credentialSurface = CredentialSurface.WEB,
        agentClientId = null,
        role = cc.monomer.identity.api.WorkspaceRole.EXPLORER,
    )
}
