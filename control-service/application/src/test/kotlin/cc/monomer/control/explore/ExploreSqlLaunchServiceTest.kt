@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.explore

import cc.monomer.catalog.api.CatalogCurrentObject
import cc.monomer.catalog.api.CatalogCurrentObjectSet
import cc.monomer.catalog.api.CatalogCurrentObjectsReadPort
import cc.monomer.catalog.api.CatalogDigest
import cc.monomer.catalog.api.CatalogObjectId
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.catalog.api.CatalogPhysicalAddress
import cc.monomer.catalog.api.RelationKind
import cc.monomer.connector.api.ConnectorExecutionAccessResult
import cc.monomer.connector.api.ConnectorExecutionAccessUseCase
import cc.monomer.connector.api.ConnectorExecutionBinding
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorRuntimeBindingProjectionPort
import cc.monomer.connector.api.ContentDigest
import cc.monomer.connector.api.CredentialEnvelopeReference
import cc.monomer.connector.api.CredentialVersionId
import cc.monomer.connector.application.CredentialMaterial
import cc.monomer.connector.application.ManagedCredentialReadOutcome
import cc.monomer.connector.application.ManagedCredentialReadPort
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionDenial
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.ControlAdmissionStage
import cc.monomer.control.ExecutionAdmissionRequest
import cc.monomer.control.observability.RequestLogTestSupport
import cc.monomer.execution.api.BinaryValue
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorRuntimeBindingSpec
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.CredentialVersionReference
import cc.monomer.execution.api.RawSqlValidationRequest
import cc.monomer.execution.api.RawSqlValidationResult
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
import cc.monomer.identity.api.OrganizationId
import cc.monomer.identity.api.OrganizationPlan
import cc.monomer.identity.api.AgentClientId
import cc.monomer.identity.api.AuthorizedExecutionContext
import cc.monomer.identity.api.AuthorizationRevision
import cc.monomer.identity.api.CanonicalPrincipal
import cc.monomer.identity.api.CredentialKind
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.ExecutionIntent
import cc.monomer.identity.api.IdentityAdmissionDenial
import cc.monomer.identity.api.IdentityRoleEvidence
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.PrincipalKind
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

class ExploreSqlLaunchServiceTest {
    private val workspaceId = WorkspaceId.parse("019e0000-0000-7000-8000-0000000000a1")
    private val connectorId = ConnectorId(Uuid.parse("019e0000-0000-7000-8000-0000000000c1"))
    private val requestedAt = Instant.parse("2026-08-18T00:00:00Z")

    @Test
    fun `non-member is denied before Connector credential or Execution access`() =
        kotlinx.coroutines.runBlocking {
            val connectorAccess = mockk<ConnectorExecutionAccessUseCase>()
            val connectorProjection = mockk<ConnectorRuntimeBindingProjectionPort>()
            val credentialRead = mockk<ManagedCredentialReadPort>()
            val execution = mockk<ExecutionRunPort>()
            val admissionFacade = mockk<ControlAdmissionFacade>()
            val denial = ControlAdmissionDecision.Denied(
                ControlAdmissionStage.IDENTITY,
                ControlAdmissionDenial.Identity(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE),
            )
            coEvery { admissionFacade.authorizeWorkspaceRole(any()) } returns denial
            val service = service(
                connectorAccess = connectorAccess,
                connectorProjection = connectorProjection,
                credentialRead = credentialRead,
                execution = execution,
                specFactory = mockk(),
                exploreRunUseCase = mockk(),
                admissionFacade = admissionFacade,
            )

            val outcome = service.start(
                LaunchSqlExploreRunCommand(
                    workspaceId = workspaceId,
                    credential = PresentedCredential("token"),
                    expectedOAuthResource = null,
                    idempotencyKey = "non-member",
                    connectorId = connectorId,
                    sql = "SELECT * FROM analytics.orders",
                ),
            )

            assertEquals(ExploreSqlLaunchOutcome.AdmissionDenied(denial), outcome)
            coVerify(exactly = 0) { connectorAccess.readCurrentForExecution(workspaceId, connectorId) }
            coVerify(exactly = 0) { credentialRead.readExact(any()) }
            coVerify(exactly = 0) { execution.validateRawSql(any()) }
        }

    @Test
    fun `validated SELECT reserves ordinary SQL run without Catalog selection`() =
        kotlinx.coroutines.runBlocking {
            val connectorBinding = connectorBinding()
            val connectorAccess = mockk<ConnectorExecutionAccessUseCase>()
            coEvery { connectorAccess.readCurrentForExecution(workspaceId, connectorId) } returns
                ConnectorExecutionAccessResult.Granted(connectorBinding)
            val runtimeBinding = runtimeBinding()
            val connectorProjection = mockk<ConnectorRuntimeBindingProjectionPort>()
            every { connectorProjection.project(connectorBinding) } returns runtimeBinding
            val credentialRead = mockk<ManagedCredentialReadPort>()
            coEvery { credentialRead.readExact(any()) } returns
                ManagedCredentialReadOutcome.Found(
                    CredentialMaterial.copyOf(
                        mapOf(
                            "username" to "dw_query".toByteArray(),
                            "password" to "secret".toByteArray(),
                        ),
                    ),
                )
            val execution = mockk<ExecutionRunPort>()
            coEvery { execution.validateRawSql(any()) } returns
                RawSqlValidationResult.Accepted
            val specCommand = slot<RawSqlRunSpecCommand>()
            val specFactory = mockk<cc.monomer.execution.api.RawSqlRunSpecFactory>()
            every { specFactory.create(capture(specCommand)) } returns rawSqlRunSpec()
            val exploreRunUseCase = mockk<ExploreRunUseCase>()
            val startCommand = slot<cc.monomer.explore.api.StartSqlExploreRunCommand>()
            coEvery { exploreRunUseCase.start(capture(startCommand)) } returns StartExploreRunOutcome.Backpressured
            val admissionFacade = mockk<ControlAdmissionFacade>()
            coEvery { admissionFacade.authorizeWorkspaceRole(any()) } returns
                ControlAdmissionDecision.Admitted(membershipEvidence())
            coEvery { admissionFacade.authorizeExecution(any()) } returns
                ControlAdmissionDecision.Admitted(authorizedExecutionContext())
            val service = service(
                connectorAccess = connectorAccess,
                connectorProjection = connectorProjection,
                credentialRead = credentialRead,
                execution = execution,
                specFactory = specFactory,
                exploreRunUseCase = exploreRunUseCase,
                admissionFacade = admissionFacade,
            )

            val outcome = service.start(
                LaunchSqlExploreRunCommand(
                    workspaceId = workspaceId,
                    credential = PresentedCredential("token"),
                    expectedOAuthResource = null,
                    idempotencyKey = "raw-sql-fixed-limit",
                    connectorId = connectorId,
                    sql = "SELECT 1",
                ),
            )

            assertEquals(ExploreSqlLaunchOutcome.ExecutionUnavailable("EXECUTION_BACKPRESSURED"), outcome)
            assertEquals(10_000uL, specCommand.captured.request.maximumResultRows)
            assertEquals(
                exploreRequestDigest("SQL", "SELECT 1"),
                startCommand.captured.requestDigest,
            )
            assertEquals(
                ExploreDigest("sha256:${"b".repeat(64)}"),
                startCommand.captured.executionReservation.executionRequestDigest,
            )
        }

    @Test
    fun `captures one binding for analysis and execution when current binding changes after analysis`() =
        kotlinx.coroutines.runBlocking {
            val initialBinding = connectorBinding().copy(
                displayName = "initial warehouse",
                properties = mapOf("host" to "initial-host", "database" to "warehouse"),
            )
            val changedBinding = initialBinding.copy(
                displayName = "changed warehouse",
                properties = mapOf("host" to "changed-host", "database" to "warehouse"),
                credentialReference = initialBinding.credentialReference.copy(
                    credentialVersionId = CredentialVersionId(
                        Uuid.parse("019e0000-0000-7000-8000-0000000000c4"),
                    ),
                    envelopeDigest = ContentDigest("sha256:${"b".repeat(64)}"),
                ),
            )
            var currentBinding = initialBinding
            val connectorAccess = mockk<ConnectorExecutionAccessUseCase>()
            coEvery { connectorAccess.readCurrentForExecution(workspaceId, connectorId) } answers {
                ConnectorExecutionAccessResult.Granted(currentBinding)
            }
            val initialRuntimeBinding = runtimeBinding().copy(
                properties = mapOf(
                    "host" to ConnectorRuntimePropertyValue.Text("initial-host"),
                    "database" to ConnectorRuntimePropertyValue.Text("warehouse"),
                ),
            )
            val changedRuntimeBinding = initialRuntimeBinding.copy(
                properties = mapOf(
                    "host" to ConnectorRuntimePropertyValue.Text("changed-host"),
                    "database" to ConnectorRuntimePropertyValue.Text("warehouse"),
                ),
                managedCredential = ManagedCredentialDispatchReference(
                    credentialVersion = CredentialVersionReference(
                        Uuid.parse("019e0000-0000-7000-8000-0000000000c4"),
                    ),
                    keyVersion = "changed-key",
                    envelopeDigest = Sha256Digest("b".repeat(64)),
                ),
            )
            val connectorProjection = mockk<ConnectorRuntimeBindingProjectionPort>()
            val projectedBinding = slot<ConnectorExecutionBinding>()
            every { connectorProjection.project(capture(projectedBinding)) } answers {
                if (projectedBinding.captured == initialBinding) initialRuntimeBinding else changedRuntimeBinding
            }
            val credentialRead = mockk<ManagedCredentialReadPort>()
            coEvery { credentialRead.readExact(any()) } returns
                ManagedCredentialReadOutcome.Found(
                    CredentialMaterial.copyOf(
                        mapOf(
                            "username" to "dw_query".toByteArray(),
                            "password" to "secret".toByteArray(),
                        ),
                    ),
                )
            val validationRequest = slot<RawSqlValidationRequest>()
            val execution = mockk<ExecutionRunPort>()
            coEvery { execution.validateRawSql(capture(validationRequest)) } returns
                RawSqlValidationResult.Accepted
            val relationId = CatalogObjectId(Uuid.parse("019e0000-0000-8000-8000-0000000000e2"))
            val catalog = mockk<CatalogCurrentObjectsReadPort>()
            every { catalog.readCurrent(workspaceId, connectorId) } returns catalogCurrentObjects(relationId)
            val specCommand = slot<RawSqlRunSpecCommand>()
            val specFactory = mockk<cc.monomer.execution.api.RawSqlRunSpecFactory>()
            every { specFactory.create(capture(specCommand)) } returns rawSqlRunSpec()
            val exploreRunUseCase = mockk<ExploreRunUseCase>()
            val startCommand = slot<cc.monomer.explore.api.StartSqlExploreRunCommand>()
            coEvery {
                exploreRunUseCase.start(capture(startCommand))
            } returns StartExploreRunOutcome.Conflict
            val admissionFacade = mockk<ControlAdmissionFacade>()
            coEvery { admissionFacade.authorizeWorkspaceRole(any()) } returns
                ControlAdmissionDecision.Admitted(membershipEvidence())
            coEvery { admissionFacade.authorizeExecution(any()) } answers {
                currentBinding = changedBinding
                ControlAdmissionDecision.Admitted(authorizedExecutionContext())
            }
            val service = service(
                connectorAccess = connectorAccess,
                connectorProjection = connectorProjection,
                credentialRead = credentialRead,
                execution = execution,
                specFactory = specFactory,
                exploreRunUseCase = exploreRunUseCase,
                admissionFacade = admissionFacade,
                catalogCurrentObjectsReadPort = catalog,
            )

            assertEquals(
                ExploreSqlLaunchOutcome.Conflict,
                service.start(launchCommand("SELECT * FROM analytics.orders")),
            )

            assertEquals(initialRuntimeBinding.connectorType, validationRequest.captured.connectorType)
            assertEquals(initialRuntimeBinding.properties, validationRequest.captured.properties)
            assertEquals(initialBinding, projectedBinding.captured)
            assertEquals(initialRuntimeBinding, specCommand.captured.connector)
            assertEquals(initialRuntimeBinding.properties, specCommand.captured.connector.properties)
            assertEquals(
                initialRuntimeBinding.properties,
                startCommand.captured.executionReservation.connectorProperties,
            )
            assertEquals(changedBinding, currentBinding)
            coVerify(exactly = 1) { connectorAccess.readCurrentForExecution(workspaceId, connectorId) }
            coVerify(exactly = 1) { execution.validateRawSql(any()) }
            verify(exactly = 1) { connectorProjection.project(any()) }
        }

    @Test
    fun `execution role denial stops run creation after statement validation`() =
        kotlinx.coroutines.runBlocking {
            val tableId = CatalogObjectId(Uuid.parse("019e0000-0000-8000-8000-0000000000e2"))
            val viewId = CatalogObjectId(Uuid.parse("019e0000-0000-8000-8000-0000000000e3"))
            val denial = ControlAdmissionDecision.Denied(
                ControlAdmissionStage.IDENTITY,
                ControlAdmissionDenial.Identity(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE),
            )
            val admissionRequest = slot<ExecutionAdmissionRequest>()
            val fixture = readyFixture(
                validationResult = RawSqlValidationResult.Accepted,
                currentCatalog = catalogCurrentObjects(
                    relationObject(
                        tableId,
                        CatalogPhysicalAddress(null, "analytics", "orders", null),
                        RelationKind.TABLE,
                        'b',
                    ),
                    relationObject(
                        viewId,
                        CatalogPhysicalAddress(null, "analytics", "recent_orders", null),
                        RelationKind.VIEW,
                        'c',
                    ),
                ),
                admissionDecision = denial,
                admissionRequest = admissionRequest,
            )

            val outcome = fixture.service.start(
                launchCommand(
                    "SELECT * FROM analytics.recent_orders UNION ALL SELECT * FROM analytics.orders",
                ),
            )

            assertEquals(ExploreSqlLaunchOutcome.AdmissionDenied(denial), outcome)
            verify(exactly = 0) { fixture.specFactory.create(any()) }
            coVerify(exactly = 0) {
                fixture.exploreRunUseCase.start(any<cc.monomer.explore.api.StartSqlExploreRunCommand>())
            }
        }

    @Test
    fun `statement validation preserves execution SQL across two depth connectors`() =
        kotlinx.coroutines.runBlocking {
            listOf(ConnectorType.POSTGRESQL, ConnectorType.REDSHIFT).forEach { connectorType ->
                val relationId = CatalogObjectId(Uuid.parse("019e0000-0000-8000-8000-0000000000e2"))
                val admissionRequest = slot<ExecutionAdmissionRequest>()
                val fixture = readyFixture(
                    connectorType = connectorType,
                    validationResult = RawSqlValidationResult.Accepted,
                    currentCatalog = catalogCurrentObjects(relationId),
                    admissionRequest = admissionRequest,
                )
                val specification = slot<RawSqlRunSpecCommand>()
                every { fixture.specFactory.create(capture(specification)) } returns rawSqlRunSpec()
                coEvery { fixture.exploreRunUseCase.start(any<cc.monomer.explore.api.StartSqlExploreRunCommand>()) } returns
                    StartExploreRunOutcome.Conflict
                val sql = "SELECT * FROM analytics.orders /* original SQL */"

                assertEquals(ExploreSqlLaunchOutcome.Conflict, fixture.service.start(launchCommand(sql)))
                assertEquals(sql, specification.captured.request.sql)
                assertEquals(runtimeBinding(connectorType), specification.captured.connector)
            }
        }

    @Test
    fun `analysis gets at most sixty seconds without extending original dispatch and execution deadlines`() =
        kotlinx.coroutines.runBlocking {
            for (elapsedBeforeValidation in listOf(0L, 300L)) {
                val analysisClock = mockk<Clock>()
                every { analysisClock.instant() } returnsMany listOf(
                    requestedAt,
                    requestedAt.plusSeconds(elapsedBeforeValidation),
                    requestedAt.plusSeconds(elapsedBeforeValidation + 50),
                )
                val fixture = readyFixture(
                    validationResult = RawSqlValidationResult.Accepted,
                    clock = analysisClock,
                )
                val validationRequest = slot<RawSqlValidationRequest>()
                coEvery { fixture.execution.validateRawSql(capture(validationRequest)) } answers {
                    analysisClock.instant()
                    RawSqlValidationResult.Accepted
                }
                val specification = slot<RawSqlRunSpecCommand>()
                every { fixture.specFactory.create(capture(specification)) } returns rawSqlRunSpec()
                val reservation = slot<cc.monomer.explore.api.StartSqlExploreRunCommand>()
                coEvery { fixture.exploreRunUseCase.start(capture(reservation)) } returns StartExploreRunOutcome.Conflict

                assertEquals(ExploreSqlLaunchOutcome.Conflict, fixture.service.start(launchCommand("SELECT 1")))

                assertEquals(
                    requestedAt.plusSeconds(if (elapsedBeforeValidation == 0L) 60 else 310),
                    validationRequest.captured.deadline,
                )
                assertEquals(requestedAt.plusSeconds(310), specification.captured.deadline)
                assertEquals(requestedAt.plusSeconds(10), reservation.captured.executionReservation.dispatchIntentExpiresAt)
                assertEquals(requestedAt.plusSeconds(310), reservation.captured.executionReservation.deadline)
                assertEquals(requestedAt, reservation.captured.requestedAt)
                coVerify(exactly = 1) { fixture.execution.validateRawSql(any()) }
            }
        }

    @Test
    fun `validation launch is logged before RPC with admitted human and agent attribution but no SQL values`() = kotlinx.coroutines.runBlocking {
        val logger = LoggerFactory.getLogger(ExploreSqlLaunchService::class.java) as Logger
        val appender = object : ListAppender<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                event.prepareForDeferredProcessing()
                super.append(event)
            }
        }.apply { start() }
        logger.addAppender(appender)
        val previousRequestId = MDC.get("request_id")
        try {
            CredentialSurface.entries.forEach { surface ->
                appender.list.clear()
                val requestId = "srv_validation-${surface.name.lowercase()}"
                MDC.put("request_id", requestId)
                val evidence = membershipEvidence().copy(
                    credentialKind = if (surface == CredentialSurface.AGENT) CredentialKind.AGENT_OAUTH else CredentialKind.SESSION,
                    credentialSurface = surface,
                    agentClientId = if (surface == CredentialSurface.AGENT) AgentClientId("https://agent.example/client.json") else null,
                )
                val fixture = readyFixture(validationResult = RawSqlValidationResult.Accepted)
                coEvery { fixture.admissionFacade.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(evidence)
                coEvery { fixture.execution.validateRawSql(any()) } coAnswers {
                    val request = firstArg<RawSqlValidationRequest>()
                    val started = appender.list.single()
                    val encoded = RequestLogTestSupport.encoded(started)
                    val fields = ObjectMapper().readTree(encoded)
                    assertEquals("explore_sql_validation_started", fields.path("event").asText())
                    assertEquals(workspaceId.toString(), fields.path("workspace_id").asText())
                    assertEquals(evidence.actor.id.toString(), fields.path("principal_id").asText())
                    assertEquals(surface.name, fields.path("credential_surface").asText())
                    assertEquals(evidence.agentClientId?.value, fields.path("agent_client_id").takeUnless { it.isNull || it.isMissingNode }?.asText())
                    assertEquals("POSTGRESQL", fields.path("connector_type").asText())
                    assertEquals(requestId, fields.path("request_id").asText())
                    assertEquals(request.runId.toString(), fields.path("run_id").asText())
                    assertEquals("31d45b418686d3e1718101ae8ea6d376541a20eca92a3a79b08cefc68ad441d9", fields.path("sql_sha256").asText())
                    assertFalse(encoded.contains("private-value"))
                    assertFalse(encoded.contains(request.sql))
                    assertFalse(fields.has("sql"))
                    assertFalse(fields.has("parameters"))
                    RawSqlValidationResult.Failed("EXECUTION_UNAVAILABLE", "private diagnostic")
                }

                assertEquals(
                    ExploreSqlLaunchOutcome.ExecutionUnavailable("EXECUTION_UNAVAILABLE"),
                    fixture.service.start(launchCommand("SELECT 'private-value-π' AS result")),
                )

                val completed = appender.list.last()
                assertEquals("explore_sql_launch_timing", RequestLogTestSupport.fields(completed)["event"])
                assertEquals(requestId, RequestLogTestSupport.fields(completed)["request_id"])
                assertEquals(RequestLogTestSupport.fields(appender.list.first())["run_id"], RequestLogTestSupport.fields(completed)["run_id"])
                coVerify(exactly = 0) { fixture.admissionFacade.authorizeExecution(any()) }
                coVerify(exactly = 0) { fixture.exploreRunUseCase.start(any<cc.monomer.explore.api.StartSqlExploreRunCommand>()) }
            }
        } finally {
            if (previousRequestId == null) MDC.remove("request_id") else MDC.put("request_id", previousRequestId)
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `statement safety rejection stops before execution admission and run reservation`() = kotlinx.coroutines.runBlocking {
        val fixture = readyFixture(validationResult = RawSqlValidationResult.Failed("RAW_SQL_VALIDATION_REJECTED", "SQL has no output columns."))
        assertEquals(ExploreSqlLaunchOutcome.SqlValidationFailed("RAW_SQL_VALIDATION_REJECTED", "SQL has no output columns."), fixture.service.start(launchCommand("SELECT * FROM orders FOR UPDATE")))
        coVerify(exactly = 0) { fixture.admissionFacade.authorizeExecution(any()) }
        verify(exactly = 0) { fixture.specFactory.create(any()) }
        coVerify(exactly = 0) { fixture.exploreRunUseCase.start(any<cc.monomer.explore.api.StartSqlExploreRunCommand>()) }
    }

    @Test
    fun `warehouse policy rejections stop before run reservation`() = kotlinx.coroutines.runBlocking {
        listOf("PERMISSION_DENIED", "READ_ONLY_VIOLATION").forEach { code ->
            val fixture = readyFixture(validationResult = RawSqlValidationResult.Failed(code, "The warehouse transaction is read-only."))
            val outcome = fixture.service.start(launchCommand("SELECT 1"))
            val message = if (code == "PERMISSION_DENIED") {
                "The Connector's warehouse account is not allowed to run this SQL."
            } else {
                "The warehouse transaction is read-only."
            }
            assertEquals(ExploreSqlLaunchOutcome.SqlValidationFailed(code, message), outcome)
            coVerify(exactly = 0) { fixture.admissionFacade.authorizeExecution(any()) }
            coVerify(exactly = 0) { fixture.exploreRunUseCase.start(any<cc.monomer.explore.api.StartSqlExploreRunCommand>()) }
        }
    }

    @Test
    fun `warehouse and transport failures stop launch as execution unavailable`() = kotlinx.coroutines.runBlocking {
        listOf("EXECUTION_UNAVAILABLE", "RESOURCE_EXHAUSTED", "CREDENTIAL_UNAVAILABLE", "CONNECTION_FAILED", "AUTHENTICATION_FAILED", "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", "RAW_SQL_VALIDATION_FAILED").forEach { code ->
            val fixture = readyFixture(validationResult = RawSqlValidationResult.Failed(code, "private diagnostic"))
            val outcome = fixture.service.start(launchCommand("SELECT 1"))
            assertEquals(ExploreSqlLaunchOutcome.ExecutionUnavailable(code), outcome)
            assertEquals(code != "AUTHENTICATION_FAILED", (outcome as ExploreSqlLaunchOutcome.ExecutionUnavailable).retryable)
            assertEquals(
                when (code) {
                    "AUTHENTICATION_FAILED" -> "Warehouse authentication failed. Update the Connector credentials before trying again."
                    "CONNECTION_FAILED" -> "Warehouse connection failed. Check the Connector connection settings and warehouse availability before trying again."
                    "CREDENTIAL_UNAVAILABLE" -> "Connector credentials are unavailable. Check the Connector credentials before trying again."
                    "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED" -> "SQL validation timed out. Try again."
                    else -> "Explore SQL execution is temporarily unavailable."
                },
                outcome.message,
            )
            coVerify(exactly = 0) { fixture.admissionFacade.authorizeExecution(any()) }
            verify(exactly = 0) { fixture.specFactory.create(any()) }
            coVerify(exactly = 0) { fixture.exploreRunUseCase.start(any<cc.monomer.explore.api.StartSqlExploreRunCommand>()) }
        }
    }

    @Test
    fun `validated SQL outside Catalog selection reaches execution unchanged`() = kotlinx.coroutines.runBlocking {
        val fixture = readyFixture(validationResult = RawSqlValidationResult.Accepted)
        val specification = slot<RawSqlRunSpecCommand>()
        every { fixture.specFactory.create(capture(specification)) } returns rawSqlRunSpec()
        coEvery { fixture.exploreRunUseCase.start(any<cc.monomer.explore.api.StartSqlExploreRunCommand>()) } returns StartExploreRunOutcome.Conflict
        val sql = "SELECT * FROM unselected_schema.orders"
        assertEquals(ExploreSqlLaunchOutcome.Conflict, fixture.service.start(launchCommand(sql)))
        assertEquals(sql, specification.captured.request.sql)
        verify(exactly = 0) { fixture.catalog.readCurrent(workspaceId, connectorId) }
    }

    private fun service(
        connectorAccess: ConnectorExecutionAccessUseCase,
        connectorProjection: ConnectorRuntimeBindingProjectionPort,
        credentialRead: ManagedCredentialReadPort,
        execution: ExecutionRunPort,
        specFactory: cc.monomer.execution.api.RawSqlRunSpecFactory,
        exploreRunUseCase: ExploreRunUseCase,
        admissionFacade: ControlAdmissionFacade,
        catalogCurrentObjectsReadPort: CatalogCurrentObjectsReadPort = mockk(),
        snapshotRunner: ConsistentSnapshotTransactionRunner = RecordingSnapshotRunner(),
        clock: Clock = Clock.fixed(requestedAt, ZoneOffset.UTC),
        repository: cc.monomer.explore.application.ExploreRunRepository = mockk { every { findByAdmissionKey(workspaceId, any()) } returns null },
    ) = ExploreSqlLaunchService(
        connectorExecutionAccessUseCase = connectorAccess,
        connectorRuntimeBindingProjectionPort = connectorProjection,
        managedCredentialReadPort = credentialRead,
        executionRunPort = execution,
        exploreRunUseCase = exploreRunUseCase,
        repository = repository,
        rawSqlRunSpecFactory = specFactory,
        controlAdmissionFacade = admissionFacade,
        consistentSnapshotTransactionRunner = snapshotRunner,
        clock = clock,
        runtimeProperties = cc.monomer.control.testControlRuntimeProperties(),
    )

    private fun catalogCurrentObjects(relationId: CatalogObjectId): CatalogCurrentObjectSet =
        catalogCurrentObjects(
            relationObject(
                relationId,
                CatalogPhysicalAddress(null, "analytics", "orders", null),
                RelationKind.TABLE,
                'b',
            ),
        )

    private fun catalogCurrentObjects(vararg relations: CatalogCurrentObject): CatalogCurrentObjectSet {
        val schemaId = CatalogObjectId(Uuid.parse("019e0000-0000-8000-8000-0000000000e1"))
        return CatalogCurrentObjectSet(
            workspaceId = workspaceId,
            connectorId = connectorId,
            objects = listOf(
                catalogCurrentObject(
                    id = schemaId,
                    parentId = null,
                    kind = CatalogObjectKind.SCHEMA,
                    relationKind = null,
                    address = CatalogPhysicalAddress(relations.firstOrNull()?.address?.catalogName, "analytics", null, null),
                    digestCharacter = 'a',
                ),
                *relations,
            ).sortedBy { it.identityDigest.value },
        )
    }

    private fun relationObject(
        id: CatalogObjectId,
        address: CatalogPhysicalAddress,
        relationKind: RelationKind,
        digestCharacter: Char,
    ): CatalogCurrentObject = catalogCurrentObject(
        id = id,
        parentId = CatalogObjectId(Uuid.parse("019e0000-0000-8000-8000-0000000000e1")),
        kind = CatalogObjectKind.RELATION,
        relationKind = relationKind,
        address = address,
        digestCharacter = digestCharacter,
    )

    private fun readyFixture(
        validationResult: RawSqlValidationResult,
        connectorType: ConnectorType = ConnectorType.POSTGRESQL,
        currentCatalog: CatalogCurrentObjectSet? = null,
        admissionDecision: ControlAdmissionDecision<AuthorizedExecutionContext> =
            ControlAdmissionDecision.Admitted(authorizedExecutionContext()),
        admissionRequest: io.mockk.CapturingSlot<ExecutionAdmissionRequest>? = null,
        clock: Clock = Clock.fixed(requestedAt, ZoneOffset.UTC),
        repository: cc.monomer.explore.application.ExploreRunRepository = mockk { every { findByAdmissionKey(workspaceId, any()) } returns null },
    ): ReadyFixture {
        val connectorBinding = connectorBinding(connectorType)
        val connectorAccess = mockk<ConnectorExecutionAccessUseCase>()
        coEvery { connectorAccess.readCurrentForExecution(workspaceId, connectorId) } returns
            ConnectorExecutionAccessResult.Granted(connectorBinding)
        val connectorProjection = mockk<ConnectorRuntimeBindingProjectionPort>()
        every { connectorProjection.project(connectorBinding) } returns runtimeBinding(connectorType)
        val credentialRead = mockk<ManagedCredentialReadPort>()
        coEvery { credentialRead.readExact(any()) } returns
            ManagedCredentialReadOutcome.Found(
                CredentialMaterial.copyOf(
                    mapOf(
                        "username" to "dw_query".toByteArray(),
                        "password" to "secret".toByteArray(),
                    ),
                ),
            )
        val execution = mockk<ExecutionRunPort>()
        coEvery { execution.validateRawSql(any()) } returns validationResult
        val catalog = mockk<CatalogCurrentObjectsReadPort>()
        if (currentCatalog != null) {
            every { catalog.readCurrent(workspaceId, connectorId) } returns currentCatalog
        }
        val admissionFacade = mockk<ControlAdmissionFacade>()
        coEvery { admissionFacade.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Admitted(membershipEvidence())
        if (admissionRequest == null) {
            coEvery { admissionFacade.authorizeExecution(any()) } returns admissionDecision
        } else {
            coEvery { admissionFacade.authorizeExecution(capture(admissionRequest)) } returns admissionDecision
        }
        val specFactory = mockk<cc.monomer.execution.api.RawSqlRunSpecFactory>()
        val exploreRunUseCase = mockk<ExploreRunUseCase>()
        return ReadyFixture(
            service = service(
                connectorAccess = connectorAccess,
                connectorProjection = connectorProjection,
                credentialRead = credentialRead,
                execution = execution,
                specFactory = specFactory,
                exploreRunUseCase = exploreRunUseCase,
                admissionFacade = admissionFacade,
                catalogCurrentObjectsReadPort = catalog,
                clock = clock,
            ),
            catalog = catalog,
            admissionFacade = admissionFacade,
            specFactory = specFactory,
            exploreRunUseCase = exploreRunUseCase,
            execution = execution,
        )
    }

    private fun launchCommand(sql: String) = LaunchSqlExploreRunCommand(
        workspaceId = workspaceId,
        credential = PresentedCredential("token"),
        expectedOAuthResource = null,
        idempotencyKey = "raw-sql-acl-${sql.hashCode()}",
        connectorId = connectorId,
        sql = sql,
    )

    private data class ReadyFixture(
        val service: ExploreSqlLaunchService,
        val catalog: CatalogCurrentObjectsReadPort,
        val admissionFacade: ControlAdmissionFacade,
        val specFactory: cc.monomer.execution.api.RawSqlRunSpecFactory,
        val exploreRunUseCase: ExploreRunUseCase,
        val execution: ExecutionRunPort,
    )

    private fun catalogCurrentObject(
        id: CatalogObjectId,
        parentId: CatalogObjectId?,
        kind: CatalogObjectKind,
        relationKind: RelationKind?,
        address: CatalogPhysicalAddress,
        digestCharacter: Char,
    ) = CatalogCurrentObject(
        id = id,
        identityDigest = CatalogDigest("sha256:${digestCharacter.toString().repeat(64)}"),
        parentId = parentId,
        kind = kind,
        relationKind = relationKind,
        address = address,
        qualifiedName = listOfNotNull(address.catalogName, address.schemaName, address.objectName).joinToString("."),
        nativeType = null,
        contentDigest = CatalogDigest("sha256:${"f".repeat(64)}"),
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
        observedAt = requestedAt,
        partitionKeys = null, clusteringKeys = null, requiresPartitionFilter = null,
    )

    private class RecordingSnapshotRunner : ConsistentSnapshotTransactionRunner {
        var active = false
        var runCalls = 0

        override suspend fun <T> run(action: suspend () -> T): T {
            runCalls += 1
            active = true
            return try {
                action()
            } finally {
                active = false
            }
        }
    }

    private fun connectorBinding(connectorType: ConnectorType = ConnectorType.POSTGRESQL) = cc.monomer.connector.api.ConnectorExecutionBinding(
        connectorId = connectorId,
        workspaceId = workspaceId,
        connectorType = connectorType.name,
        displayName = "warehouse",
        properties = mapOf("host" to "localhost", "database" to "warehouse"),
        credentialReference = CredentialEnvelopeReference(
            credentialVersionId = CredentialVersionId(Uuid.parse("019e0000-0000-7000-8000-0000000000c3")),
            keyVersion = "test-key",
            envelopeDigest = ContentDigest("sha256:${"a".repeat(64)}"),
        ),
        updatedAt = requestedAt,
    )

    private fun runtimeBinding(connectorType: ConnectorType = ConnectorType.POSTGRESQL) = ConnectorRuntimeBindingSpec(
        connectorId = ConnectorReference(connectorId.value),
        connectorType = connectorType,
        properties = mapOf(
            "host" to ConnectorRuntimePropertyValue.Text("localhost"),
            "database" to ConnectorRuntimePropertyValue.Text("warehouse"),
        ),
        managedCredential = ManagedCredentialDispatchReference(
            credentialVersion = CredentialVersionReference(Uuid.parse("019e0000-0000-7000-8000-0000000000c3")),
            keyVersion = "test-key",
            envelopeDigest = Sha256Digest("a".repeat(64)),
        ),
    )

    private fun membershipEvidence() = IdentityRoleEvidence(
        organizationPlan = OrganizationPlan.FREE,
        actor = CanonicalPrincipal(PrincipalId.parse("019e0000-0000-7000-8000-0000000000d1"), PrincipalKind.USER),
        organizationId = OrganizationId.parse("019e0000-0000-7000-8000-0000000000d2"),
        workspaceId = workspaceId,
        authorizationRevision = AuthorizationRevision(1),
        credentialKind = CredentialKind.SESSION,
        credentialSurface = CredentialSurface.WEB,
        agentClientId = null,
        role = WorkspaceRole.EXPLORER,
    )

    private fun authorizedExecutionContext() = AuthorizedExecutionContext(
        actor = CanonicalPrincipal(PrincipalId.parse("019e0000-0000-7000-8000-0000000000d1"), PrincipalKind.USER),
        organizationId = OrganizationId.parse("019e0000-0000-7000-8000-0000000000d2"),
        workspaceId = workspaceId,
        authorizationRevision = AuthorizationRevision(1),
        credentialKind = CredentialKind.SESSION,
        credentialSurface = CredentialSurface.WEB,
        agentClientId = null,
        intent = ExecutionIntent.EXPLORE_RUN,
        admittedAt = requestedAt,
    )

    private fun rawSqlRunSpec(): RawSqlRunSpec = RawSqlRunSpec(
        canonicalRequestDigest = Sha256Digest("b".repeat(64)),
        runtimeBindings = RunRuntimeBindings(
            connectorBindingDigest = Sha256Digest("c".repeat(64)),
            credentialVersion = null,
            credentialKeyVersion = "test-key",
            credentialBindingDigest = Sha256Digest("d".repeat(64)),
            resultSink = ResultSinkBinding(
                schemaVersion = 2u,
                kind = ResultSinkKind.ARTIFACT_UPLOAD,
                objectKey = "artifacts/raw-sql/test.arrow",
                maximumArtifactRows = 10_000uL,
                maximumArtifactBytes = 1_024uL,
                expiresAt = requestedAt.plusSeconds(600),
                maximumRecordBatchRows = 1_024,
                maximumFrameBytes = 1_024,
                maximumCellBytes = 4 * 1_024 * 1_024,
                maximumMultipartPartBytes = 1_024,
                bindingDigest = Sha256Digest("e".repeat(64)),
            ),
        ),
        assignmentTemplate = BinaryValue.copyOf(byteArrayOf(1)),
    )
}
