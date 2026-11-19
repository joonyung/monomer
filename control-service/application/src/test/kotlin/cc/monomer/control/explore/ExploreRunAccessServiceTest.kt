@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.explore

import cc.monomer.connector.api.ConnectorId
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionDenial
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.ControlAdmissionStage
import cc.monomer.control.WorkspaceRoleAdmissionRequest
import cc.monomer.explore.api.CancelExploreRunCommand
import cc.monomer.explore.api.CancelExploreRunOutcome
import cc.monomer.explore.api.ExploreConnectorBinding
import cc.monomer.explore.api.ExploreDigest
import cc.monomer.explore.api.ExploreRunId
import cc.monomer.explore.api.ExploreRunState
import cc.monomer.explore.api.ExploreRunUseCase
import cc.monomer.explore.api.ExploreRunView
import cc.monomer.explore.api.ExploreRunPage
import cc.monomer.explore.api.ListExploreRunsCommand
import cc.monomer.explore.api.ExploreResultArtifactReadOutcome
import cc.monomer.explore.api.ExploreResultProjection
import cc.monomer.explore.api.ExploreResultProjectionReadOutcome
import cc.monomer.explore.api.ExploreResultReadOutcome
import cc.monomer.explore.api.ExploreResultRetention
import cc.monomer.explore.api.ExploreResultView
import cc.monomer.explore.application.ExploreCancellationHandler
import cc.monomer.explore.application.ExploreRunRepository
import cc.monomer.explore.domain.ExploreRun
import cc.monomer.execution.api.ExecutionRunQueryPort
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionOwnerId
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ConnectorType
import cc.monomer.identity.api.OrganizationId
import cc.monomer.identity.api.AuthorizationRevision
import cc.monomer.identity.api.CanonicalPrincipal
import cc.monomer.identity.api.CredentialKind
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.IdentityAdmissionDenial
import cc.monomer.identity.api.IdentityRoleEvidence
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.PrincipalKind
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticQuerySpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking

class ExploreRunAccessServiceTest {
    private val workspaceId = WorkspaceId.parse("019e0000-0000-7000-8000-0000000000a1")
    private val connectorId = ConnectorId(Uuid.parse("019e0000-0000-7000-8000-0000000000c1"))
    private val ownerId = PrincipalId.parse("019e0000-0000-7000-8000-0000000000e1")
    private val metricId = Uuid.parse("019e0000-0000-7000-8000-0000000000d1")
    private val now = Instant.parse("2026-08-20T00:00:00Z")

    @Test
    fun `CSV and download reference reject current Explorer role denial before invoking owner`() = runBlocking {
        for (audience in listOf(null, ExpectedOAuthResource.AGENT_REST, ExpectedOAuthResource.AGENT_MCP)) {
            val run = semanticRun().succeededWithoutResultMetadata()
            val repository = mockk<ExploreRunRepository>()
            every { repository.findRun(run.id) } returns run
            val facade = mockk<ControlAdmissionFacade>()
            coEvery { facade.authorizeWorkspaceRole(any()) } returns
                ControlAdmissionDecision.Admitted(membershipEvidence())
            val denial = ControlAdmissionDecision.Denied(
                ControlAdmissionStage.IDENTITY,
                ControlAdmissionDenial.Identity(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE),
            )
            coEvery { facade.authorizeWorkspaceRole(any()) } returns denial
            val useCase = mockk<ExploreRunUseCase>()
            val service = service(repository, facade, useCase, mockk())
            val request = ReadExploreResultArtifactRequest(workspaceId, run.id, PresentedCredential("token"), audience)

            assertEquals(ExploreRunCsvReadOutcome.AdmissionDenied(denial), service.readCsv(request))
            assertEquals(ExploreRunDownloadLinkReadOutcome.AdmissionDenied(denial), service.readDownloadLink(request))
            coVerify(exactly = 2) { facade.authorizeWorkspaceRole(match { it.requiredRole == cc.monomer.identity.api.WorkspaceRole.EXPLORER && it.expectedOAuthResource == audience }) }
            coVerify(exactly = 0) { useCase.readVisibleResultCsv(any()) }
            coVerify(exactly = 0) { useCase.readVisibleResultDownloadLink(any()) }
        }
    }

    @Test
    fun `CSV and download reference allow a teammate after current Explorer role admission`() = runBlocking {
        for (audience in listOf(null, ExpectedOAuthResource.AGENT_REST, ExpectedOAuthResource.AGENT_MCP)) {
            val run = semanticRun().succeededWithoutResultMetadata()
            val repository = mockk<ExploreRunRepository>()
            every { repository.findRun(run.id) } returns run
            val facade = mockk<ControlAdmissionFacade>()
            val otherActor = PrincipalId.parse("019e0000-0000-7000-8000-0000000000e2")
            coEvery { facade.authorizeWorkspaceRole(any()) } returns
                ControlAdmissionDecision.Admitted(membershipEvidence(otherActor))
            coEvery { facade.authorizeWorkspaceRole(any()) } returns
                ControlAdmissionDecision.Admitted(resourceEvidence(otherActor))
            val useCase = mockk<ExploreRunUseCase>()
            coEvery { useCase.readVisibleResultCsv(any()) } returns cc.monomer.explore.api.ExploreResultCsvReadOutcome.NotFound
            coEvery { useCase.readVisibleResultDownloadLink(any()) } returns cc.monomer.explore.api.ExploreResultDownloadLinkReadOutcome.NotFound
            val service = service(repository, facade, useCase, mockk())
            val request = ReadExploreResultArtifactRequest(workspaceId, run.id, PresentedCredential("token"), audience)

            assertEquals(ExploreRunCsvReadOutcome.NotFound, service.readCsv(request))
            assertEquals(ExploreRunDownloadLinkReadOutcome.NotFound, service.readDownloadLink(request))
            coVerify(exactly = 2) { facade.authorizeWorkspaceRole(any()) }
            coVerify(exactly = 1) { useCase.readVisibleResultCsv(match { it.ownerPrincipalId == ownerId }) }
            coVerify(exactly = 1) { useCase.readVisibleResultDownloadLink(match { it.ownerPrincipalId == ownerId }) }
        }
    }

    @Test
    fun `all stored result reads reject admission before any run lookup`() = runBlocking {
        for (audience in listOf(null, ExpectedOAuthResource.AGENT_REST, ExpectedOAuthResource.AGENT_MCP)) {
            for (reason in listOf(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE, IdentityAdmissionDenial.CREDENTIAL_UNAVAILABLE)) {
                val repository = mockk<ExploreRunRepository>()
                val facade = mockk<ControlAdmissionFacade>()
                val denial = ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY, ControlAdmissionDenial.Identity(reason))
                val credential = PresentedCredential("other-surface-token")
                coEvery { facade.authorizeWorkspaceRole(match {
                    it.workspaceId == workspaceId && it.credential == credential && it.expectedOAuthResource == audience
                }) } returns denial
                val useCase = mockk<ExploreRunUseCase>()
                val service = service(repository, facade, useCase, mockk())
                val request = ReadExploreResultArtifactRequest(workspaceId, ExploreRunId.generate(), credential, audience)

                assertEquals(ExploreRunArtifactReadOutcome.AdmissionDenied(denial), service.readArtifact(request))
                assertEquals(ExploreRunCsvReadOutcome.AdmissionDenied(denial), service.readCsv(request))
                assertEquals(ExploreRunDownloadLinkReadOutcome.AdmissionDenied(denial), service.readDownloadLink(request))
                assertEquals(ExploreResultProjectionAccessOutcome.AdmissionDenied(denial), service.readResult(
                    ReadExploreResultProjectionRequest(workspaceId, request.runId, credential, audience),
                ))
                coVerify(exactly = 4) { facade.authorizeWorkspaceRole(any()) }
                verify(exactly = 0) { repository.findRun(request.runId) }
                coVerify(exactly = 0) { useCase.readVisibleResultCsv(any()) }
                coVerify(exactly = 0) { useCase.readVisibleResultDownloadLink(any()) }
            }
        }
    }

    @Test
    fun `Human non-result read uses owner history admission without lineage`() = runBlocking {
        val run = semanticRun(queryMetrics = listOf(SemanticElementName("old_name")))
        val repository = mockk<ExploreRunRepository>()
        every { repository.findRun(run.id) } returns run
        val facade = mockk<ControlAdmissionFacade>()
        coEvery { facade.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Admitted(membershipEvidence())
        val service = service(repository, facade, mockk(), mockk())

        val outcome = service.read(
            ReadExploreRunCommand(workspaceId, run.id, PresentedCredential("token"), null),
        )
        assertEquals(ExploreRunReadOutcome.Observed(run.asView(), ExploreRunResultRead.None), outcome)
    }

    @Test
    fun `Semantic artifact read uses current Explorer role without reconstructing current definition`() = runBlocking {
        val run = semanticRun(queryMetrics = listOf(SemanticElementName("definition_changed")))
        val repository = mockk<ExploreRunRepository>()
        every { repository.findRun(run.id) } returns run
        val facade = mockk<ControlAdmissionFacade>()
        val request = slot<WorkspaceRoleAdmissionRequest>()
        coEvery { facade.authorizeWorkspaceRole(capture(request)) } returns
            ControlAdmissionDecision.Admitted(resourceEvidence())
        val useCase = mockk<ExploreRunUseCase>()
        coEvery { useCase.readVisibleResultArtifact(any()) } returns ExploreResultArtifactReadOutcome.NotFound
        val service = service(repository, facade, useCase, mockk())

        val outcome = service.readArtifact(
            ReadExploreResultArtifactRequest(workspaceId, run.id, PresentedCredential("token"), null),
        )

        assertEquals(ExploreRunArtifactReadOutcome.NotFound, outcome)
    }

    @Test
    fun `stored SQL result access requires Explorer`() = runBlocking {
        val run = sqlRun()
        val repository = mockk<ExploreRunRepository>()
        every { repository.findRun(run.id) } returns run
        val facade = mockk<ControlAdmissionFacade>()
        coEvery { facade.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Admitted(membershipEvidence())
        val service = service(repository, facade, mockk(), mockk())

        val outcome = service.read(
            ReadExploreRunCommand(workspaceId, run.id, PresentedCredential("token"), null),
        )

        assertEquals(ExploreRunReadOutcome.Observed(run.asView(), ExploreRunResultRead.None), outcome)
    }

    @Test
    fun `cancel checks membership and owner without lineage authorization`() = runBlocking {
        val run = semanticRun()
        val repository = mockk<ExploreRunRepository>()
        every { repository.findRun(run.id) } returns run
        val facade = mockk<ControlAdmissionFacade>()
        coEvery { facade.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Admitted(membershipEvidence())
        val cancellation = mockk<ExploreCancellationHandler>()
        val command = slot<CancelExploreRunCommand>()
        coEvery { cancellation.cancel(capture(command)) } returns CancelExploreRunOutcome.Accepted
        val service = service(repository, facade, mockk(), cancellation)

        val outcome = service.cancel(
            CancelExploreRunRequest(workspaceId, run.id, PresentedCredential("token"), null, "USER_REQUESTED"),
        )

        assertEquals(ownerId, command.captured.ownerPrincipalId)
        assertEquals(ExploreRunCancelOutcome.Accepted(run.asView()), outcome)
    }

    @Test
    fun `cancel of a run that settled meanwhile answers with the settled run`() = runBlocking {
        val run = semanticRun()
        val settled = run.copy(
            state = ExploreRunState.CANCELLED,
            failureCode = cc.monomer.explore.api.ExploreRunFailureCode.CANCELLED_BY_ACTOR,
            failureMessage = "Cancelled",
            revision = run.revision + 1,
        )
        val repository = mockk<ExploreRunRepository>()
        every { repository.findRun(run.id) } returnsMany listOf(run, settled)
        val facade = mockk<ControlAdmissionFacade>()
        coEvery { facade.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Admitted(membershipEvidence())
        val cancellation = mockk<ExploreCancellationHandler>()
        coEvery { cancellation.cancel(any()) } returns CancelExploreRunOutcome.AlreadyTerminal
        val service = service(repository, facade, mockk(), cancellation)

        val outcome = service.cancel(
            CancelExploreRunRequest(workspaceId, run.id, PresentedCredential("token"), null, "USER_REQUESTED"),
        )

        assertEquals(ExploreRunCancelOutcome.AlreadyTerminal(settled.asView()), outcome)
    }

    @Test
    fun `cancel rejects a non-member before reading run existence`() = runBlocking {
        val repository = mockk<ExploreRunRepository>()
        val facade = mockk<ControlAdmissionFacade>()
        val denial = ControlAdmissionDecision.Denied(
            ControlAdmissionStage.IDENTITY,
            ControlAdmissionDenial.Identity(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE),
        )
        coEvery { facade.authorizeWorkspaceRole(any()) } returns denial
        val service = service(repository, facade, mockk(), mockk())
        val runId = ExploreRunId.generate()

        val outcome = service.cancel(
            CancelExploreRunRequest(workspaceId, runId, PresentedCredential("token"), null, "USER_REQUESTED"),
        )

        assertEquals(ExploreRunCancelOutcome.AdmissionDenied(denial), outcome)
        verify(exactly = 0) { repository.findRun(runId) }
    }

    @Test
    fun `recent runs are scoped to the current actor and capped at twenty`() = runBlocking {
        val run = semanticRun()
        val repository = mockk<ExploreRunRepository>()
        val facade = mockk<ControlAdmissionFacade>()
        coEvery { facade.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Admitted(membershipEvidence())
        val useCase = mockk<ExploreRunUseCase>()
        coEvery {
            useCase.list(ListExploreRunsCommand(workspaceId, ownerId, null, 20))
        } returns ExploreRunPage(listOf(run.asView()), null)
        val service = service(repository, facade, useCase, mockk())

        val outcome = service.listRuns(
            ListExploreRunsRequest(workspaceId, PresentedCredential("token"), null, null, 20),
        )

        assertEquals(listOf<ExploreRunView>(run.asView()), assertIs<ExploreRunListOutcome.Observed>(outcome).page.runs)
        coVerify(exactly = 1) { useCase.list(ListExploreRunsCommand(workspaceId, ownerId, null, 20)) }
    }

    @Test
    fun `read admits a teammate only after current Explorer role admission`() = runBlocking {
        val run = semanticRun()
        val repository = mockk<ExploreRunRepository>()
        every { repository.findRun(run.id) } returns run
        val facade = mockk<ControlAdmissionFacade>()
        val otherActor = PrincipalId.parse("019e0000-0000-7000-8000-0000000000e2")
        coEvery { facade.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Admitted(membershipEvidence(otherActor))
        coEvery { facade.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(resourceEvidence(otherActor))
        val useCase = mockk<ExploreRunUseCase>()
        val service = service(repository, facade, useCase, mockk())

        val outcome = service.read(
            ReadExploreRunCommand(workspaceId, run.id, PresentedCredential("token"), null),
        )

        assertEquals(ExploreRunReadOutcome.Observed(run.asView(), ExploreRunResultRead.None), outcome)
        coVerify(exactly = 1) { facade.authorizeWorkspaceRole(any()) }
        coVerify(exactly = 0) { useCase.readVisibleResult(any()) }
    }

    @Test
    fun `successful owner and teammate reads admit the current role once before results`() = runBlocking {
        for (actor in listOf(ownerId, PrincipalId.generate())) {
            for (audience in listOf(null, ExpectedOAuthResource.AGENT_REST)) {
                val run = semanticRun().succeededWithoutResultMetadata()
                val repository = mockk<ExploreRunRepository>()
                every { repository.findRun(run.id) } returns run
                val facade = mockk<ControlAdmissionFacade>()
                coEvery { facade.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(membershipEvidence(actor))
                val useCase = mockk<ExploreRunUseCase>()
                coEvery { useCase.readVisibleResult(any()) } returns ExploreResultReadOutcome.RetentionExpired
                val outcome = service(repository, facade, useCase, mockk()).read(
                    ReadExploreRunCommand(workspaceId, run.id, PresentedCredential("token"), audience),
                )
                assertEquals(ExploreRunReadOutcome.Observed(run.asView(), ExploreRunResultRead.Expired), outcome)
                coVerify(exactly = 1) { facade.authorizeWorkspaceRole(match {
                    it.requiredRole == cc.monomer.identity.api.WorkspaceRole.EXPLORER && it.expectedOAuthResource == audience
                }) }
                coVerify(exactly = 1) { useCase.readVisibleResult(match { it.ownerPrincipalId == ownerId }) }
            }
        }
    }

    @Test
    fun `expired and unpreviewable results still return the run with an explicit result state`() = runBlocking {
        val run = semanticRun().succeededWithoutResultMetadata()
        val repository = mockk<ExploreRunRepository>()
        every { repository.findRun(run.id) } returns run
        val facade = mockk<ControlAdmissionFacade>()
        coEvery { facade.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Admitted(resourceEvidence())
        coEvery { facade.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Admitted(membershipEvidence())
        val useCase = mockk<ExploreRunUseCase>()
        val request = ReadExploreRunCommand(workspaceId, run.id, PresentedCredential("token"), null)
        val service = service(repository, facade, useCase, mockk())
        for ((resultOutcome, resultRead) in listOf(
            ExploreResultReadOutcome.RetentionExpired to ExploreRunResultRead.Expired,
            ExploreResultReadOutcome.PreviewUnavailable to ExploreRunResultRead.PreviewUnavailable,
            ExploreResultReadOutcome.NotFound to ExploreRunResultRead.None,
        )) {
            coEvery { useCase.readVisibleResult(any()) } returns resultOutcome
            assertEquals(ExploreRunReadOutcome.Observed(run.asView(), resultRead), service.read(request))
        }
        coEvery { useCase.readVisibleResult(any()) } returns ExploreResultReadOutcome.TemporarilyUnavailable
        assertEquals(ExploreRunReadOutcome.TemporarilyUnavailable, service.read(request))
    }

    @Test
    fun `human run detail previews one thousand rows while the agent projection keeps two hundred`() = runBlocking {
        val run = semanticRun().succeededWithoutResultMetadata()
        val repository = mockk<ExploreRunRepository>()
        every { repository.findRun(run.id) } returns run
        val facade = mockk<ControlAdmissionFacade>()
        coEvery { facade.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Admitted(membershipEvidence())
        val useCase = mockk<ExploreRunUseCase>()
        coEvery { useCase.readVisibleResult(any()) } returns ExploreResultReadOutcome.RetentionExpired
        coEvery { useCase.readVisibleResultProjection(any()) } returns ExploreResultProjectionReadOutcome.RetentionExpired
        val service = service(repository, facade, useCase, mockk())

        service.read(ReadExploreRunCommand(workspaceId, run.id, PresentedCredential("token"), null))
        service.readResult(ReadExploreResultProjectionRequest(workspaceId, run.id, PresentedCredential("token"), null))

        coVerify(exactly = 1) { useCase.readVisibleResult(match { it.previewRowLimit == 1_000 }) }
        coVerify(exactly = 1) { useCase.readVisibleResultProjection(match { it.previewRowLimit == 200 }) }
    }

    @Test
    fun `listing history reads recorded SQL in one batch and leaves out runs whose history is broken`() = runBlocking {
        val first = sqlRun().asView()
        val broken = semanticRun().asView()
        val third = sqlRun().asView()
        val executionQuery = mockk<ExecutionRunQueryPort>()
        val command = slot<ExecutionRunQueryPort.ReadSqlBatchCommand>()
        coEvery { executionQuery.readSqlBatch(capture(command)) } returns ExecutionRunQueryPort.ReadSqlBatchOutcome(
            recorded = listOf(
                ExecutionRunQueryPort.RecordedRunSql(ExecutionRunId(third.id.value), "SELECT 3"),
                ExecutionRunQueryPort.RecordedRunSql(ExecutionRunId(first.id.value), "SELECT 1"),
            ),
            unavailableRunIds = listOf(ExecutionRunId(broken.id.value)),
        )
        val service = service(mockk(), mockk(), mockk(), mockk(), executionQuery)

        val records = service.readRecordedHistory(listOf(first, broken, third))

        assertEquals(listOf(ExploreRunRecord(first, "SELECT 1"), ExploreRunRecord(third, "SELECT 3")), records)
        assertEquals(workspaceId, command.captured.workspaceId)
        assertEquals(
            listOf(first, broken, third).map { run ->
                ExecutionRunQueryPort.RunOwnerBinding(
                    ExecutionRunId(run.id.value),
                    ExecutionOwnerReference(ExecutionOwnerType.EXPLORE, ExecutionOwnerId(run.id.value)),
                )
            },
            command.captured.runs,
        )
        assertEquals(emptyList(), service.readRecordedHistory(emptyList()))
        coVerify(exactly = 1) { executionQuery.readSqlBatch(any()) }
        coVerify(exactly = 0) { executionQuery.readSql(any()) }
    }

    @Test
    fun `recorded SQL reads the bound Explore execution and closes damaged history as absent`() = runBlocking {
        val run = sqlRun()
        val executionQuery = mockk<ExecutionRunQueryPort>()
        val boundCommand = ExecutionRunQueryPort.ReadSqlCommand(
            ExecutionRunId(run.id.value),
            workspaceId,
            ExecutionOwnerReference(ExecutionOwnerType.EXPLORE, ExecutionOwnerId(run.id.value)),
        )
        val service = service(mockk(), mockk(), mockk(), mockk(), executionQuery)

        coEvery { executionQuery.readSql(boundCommand) } returns ExecutionRunQueryPort.ReadSqlOutcome.Available("SELECT 1")
        assertEquals("SELECT 1", service.readRecordedSql(run.asView()))
        for (failure in listOf(
            ExecutionRunQueryPort.ReadSqlOutcome.NotFound,
            ExecutionRunQueryPort.ReadSqlOutcome.BindingMismatch,
            ExecutionRunQueryPort.ReadSqlOutcome.Unavailable,
        )) {
            coEvery { executionQuery.readSql(boundCommand) } returns failure
            assertEquals(null, service.readRecordedSql(run.asView()))
        }
    }

    @Test
    fun `result projection reauthorizes Explorer role and returns the owner projection unchanged`() = runBlocking {
        val run = semanticRun().succeededWithoutResultMetadata()
        val projection = ExploreResultProjection(
            run = run.asView(),
            columns = emptyList(),
            rows = emptyList(),
            totalRowCount = 0u,
            returnedRowCount = 0,
            truncated = false,
            expiresAt = now.plusSeconds(300),
        )
        val repository = mockk<ExploreRunRepository>()
        every { repository.findRun(run.id) } returns run
        val facade = mockk<ControlAdmissionFacade>()
        val request = slot<WorkspaceRoleAdmissionRequest>()
        coEvery { facade.authorizeWorkspaceRole(capture(request)) } returns
            ControlAdmissionDecision.Admitted(resourceEvidence())
        val useCase = mockk<ExploreRunUseCase>()
        coEvery { useCase.readVisibleResultProjection(any()) } returns
            ExploreResultProjectionReadOutcome.Visible(projection)

        val outcome = service(repository, facade, useCase, mockk()).readResult(
            ReadExploreResultProjectionRequest(workspaceId, run.id, PresentedCredential("token"), null),
        )

        assertEquals(ExploreResultProjectionAccessOutcome.Observed(projection), outcome)
    }

    @Test
    fun `result projection allows an authorized teammate to read owner results`() = runBlocking {
        val run = semanticRun().succeededWithoutResultMetadata()
        val repository = mockk<ExploreRunRepository>()
        every { repository.findRun(run.id) } returns run
        val facade = mockk<ControlAdmissionFacade>()
        val otherActor = PrincipalId.parse("019e0000-0000-7000-8000-0000000000e2")
        coEvery { facade.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Admitted(resourceEvidence(otherActor))
        val useCase = mockk<ExploreRunUseCase>()
        coEvery { useCase.readVisibleResultProjection(any()) } returns ExploreResultProjectionReadOutcome.NotFound

        val outcome = service(repository, facade, useCase, mockk()).readResult(
            ReadExploreResultProjectionRequest(workspaceId, run.id, PresentedCredential("token"), null),
        )

        assertEquals(ExploreResultProjectionAccessOutcome.NotFound, outcome)
        coVerify(exactly = 1) { useCase.readVisibleResultProjection(match { it.ownerPrincipalId == ownerId }) }
    }

    @Test
    fun `historical input survives deleted definitions and subsequent role loss denies results`() = runBlocking {
        val run = semanticRun(listOf(SemanticElementName("deleted_metric"))).succeededWithoutResultMetadata()
        val repository = mockk<ExploreRunRepository>()
        every { repository.findRun(run.id) } returns run
        val facade = mockk<ControlAdmissionFacade>()
        coEvery { facade.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Admitted(membershipEvidence())
        val denial = ControlAdmissionDecision.Denied(
            ControlAdmissionStage.IDENTITY,
            ControlAdmissionDenial.Identity(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE),
        )
        val useCase = mockk<ExploreRunUseCase>()
        val executionQuery = mockk<ExecutionRunQueryPort>()
        val sql = "SELECT '당시 리터럴 🐧' WHERE amount > 123.4500\n".repeat(20_000)
        coEvery {
            executionQuery.readSql(
                ExecutionRunQueryPort.ReadSqlCommand(
                    ExecutionRunId(run.id.value), workspaceId,
                    ExecutionOwnerReference(ExecutionOwnerType.EXPLORE, ExecutionOwnerId(run.id.value)),
                ),
            )
        } returns ExecutionRunQueryPort.ReadSqlOutcome.Available(sql)
        val service = service(repository, facade, useCase, mockk(), executionQuery)
        val request = ReadExploreRunCommand(workspaceId, run.id, PresentedCredential("token"), null)

        val history = assertIs<ExploreRunSnapshotOutcome.Observed>(service.readSnapshot(request))

        assertEquals(sql, history.sql)
        assertEquals((run.kind as ExploreRun.Kind.Semantic).query, assertIs<ExploreRunView.Kind.Semantic>(history.run.kind).query)
        coEvery { facade.authorizeWorkspaceRole(any()) } returns denial
        assertEquals(ExploreRunReadOutcome.AdmissionDenied(denial), service.read(request))
        assertEquals(
            ExploreResultProjectionAccessOutcome.AdmissionDenied(denial),
            service.readResult(ReadExploreResultProjectionRequest(workspaceId, run.id, PresentedCredential("token"), null)),
        )
        assertEquals(
            ExploreRunArtifactReadOutcome.AdmissionDenied(denial),
            service.readArtifact(ReadExploreResultArtifactRequest(workspaceId, run.id, PresentedCredential("token"), null)),
        )
        coVerify(exactly = 0) { useCase.readVisibleResult(any()) }
        coVerify(exactly = 0) { useCase.readVisibleResultProjection(any()) }
        coVerify(exactly = 0) { useCase.readVisibleResultArtifact(any()) }
    }

    @Test
    fun `history membership loss prevents run existence and SQL reads`() = runBlocking {
        val facade = mockk<ControlAdmissionFacade>()
        val denial = ControlAdmissionDecision.Denied(
            ControlAdmissionStage.IDENTITY,
            ControlAdmissionDenial.Identity(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE),
        )
        coEvery { facade.authorizeWorkspaceRole(any()) } returns denial
        val repository = mockk<ExploreRunRepository>()
        val executionQuery = mockk<ExecutionRunQueryPort>()
        val service = service(repository, facade, mockk(), mockk(), executionQuery)
        val request = ReadExploreRunCommand(workspaceId, ExploreRunId.generate(), PresentedCredential("token"), null)

        assertEquals(ExploreRunSnapshotOutcome.AdmissionDenied(denial), service.readSnapshot(request))
        assertEquals(ExploreRunReadOutcome.AdmissionDenied(denial), service.read(request))
        verify(exactly = 0) { repository.findRun(request.runId) }
        coVerify(exactly = 0) { executionQuery.readSql(any()) }
    }

    @Test
    fun `stored SQL history admits workspace teammates and rejects another workspace`() = runBlocking {
        val run = sqlRun()
        val repository = mockk<ExploreRunRepository>()
        every { repository.findRun(run.id) } returns run
        val facade = mockk<ControlAdmissionFacade>()
        val otherActor = PrincipalId.parse("019e0000-0000-7000-8000-0000000000e2")
        coEvery { facade.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Admitted(membershipEvidence(otherActor))
        val executionQuery = mockk<ExecutionRunQueryPort>()
        coEvery { executionQuery.readSql(any()) } returns ExecutionRunQueryPort.ReadSqlOutcome.Available("SELECT 1")
        val service = service(repository, facade, mockk(), mockk(), executionQuery)
        val request = ReadExploreRunCommand(workspaceId, run.id, PresentedCredential("token"), null)

        assertEquals("SELECT 1", assertIs<ExploreRunSnapshotOutcome.Observed>(service.readSnapshot(request)).sql)
        assertEquals(ExploreRunReadOutcome.Observed(run.asView(), ExploreRunResultRead.None), service.read(request))
        val foreignRequest = request.copy(workspaceId = WorkspaceId.parse("019e0000-0000-7000-8000-0000000000a2"))
        assertEquals(ExploreRunSnapshotOutcome.NotFound, service.readSnapshot(foreignRequest))
        assertEquals(ExploreRunReadOutcome.NotFound, service.read(foreignRequest))
        coVerify(exactly = 1) { executionQuery.readSql(any()) }
    }

    @Test
    fun `missing mismatched or damaged execution history fails explicitly`() = runBlocking {
        val run = sqlRun()
        val repository = mockk<ExploreRunRepository>()
        every { repository.findRun(run.id) } returns run
        val facade = mockk<ControlAdmissionFacade>()
        coEvery { facade.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Admitted(membershipEvidence())
        val executionQuery = mockk<ExecutionRunQueryPort>()
        val service = service(repository, facade, mockk(), mockk(), executionQuery)
        for (failure in listOf(
            ExecutionRunQueryPort.ReadSqlOutcome.NotFound,
            ExecutionRunQueryPort.ReadSqlOutcome.BindingMismatch,
            ExecutionRunQueryPort.ReadSqlOutcome.Unavailable,
        )) {
            coEvery { executionQuery.readSql(any()) } returns failure
            assertEquals(
                ExploreRunSnapshotOutcome.TemporarilyUnavailable,
                service.readSnapshot(ReadExploreRunCommand(workspaceId, run.id, PresentedCredential("token"), null)),
            )
        }
    }

    private fun service(
        repository: ExploreRunRepository,
        facade: ControlAdmissionFacade,
        useCase: ExploreRunUseCase,
        cancellation: ExploreCancellationHandler,
        executionQuery: ExecutionRunQueryPort = mockk(),
    ) = ExploreRunAccessService(
        repository = repository,
        exploreRunUseCase = useCase,
        executionRunQueryPort = executionQuery,
        cancellationHandler = cancellation,
        controlAdmissionFacade = facade,
        consistentSnapshotTransactionRunner = object : ConsistentSnapshotTransactionRunner {
            override suspend fun <T> run(action: suspend () -> T): T = action()
        },
        clock = Clock.fixed(now, ZoneOffset.UTC),
        blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    )

    private fun semanticRun(
        queryMetrics: List<SemanticElementName> = listOf(SemanticElementName("revenue")),
    ) = ExploreRun(
        id = ExploreRunId.generate(),
        kind = ExploreRun.Kind.Semantic(query = SemanticQuerySpec(queryMetrics, emptyList(), null, emptyList(), emptyList(), 100), outputColumnDisplays = null, reportingTimeZone = null),
        idempotencyKey = "semantic-1",
        ownerPrincipalId = ownerId,
        credentialSurface = null,
        agentClientId = null,
        connectorBinding = ExploreConnectorBinding(workspaceId, connectorId, ConnectorType.POSTGRESQL),
        requestDigest = ExploreDigest("sha256:${"a".repeat(64)}"),
        state = ExploreRunState.RUNNING,
        visibleResult = null,
        failureCode = null,
        failureMessage = null,
        revision = 0,
        createdAt = now,
        updatedAt = now,
    )

    private fun sqlRun() = ExploreRun(
        id = ExploreRunId.generate(),
        kind = ExploreRun.Kind.Sql,
        idempotencyKey = "sql-1",
        ownerPrincipalId = ownerId,
        credentialSurface = null,
        agentClientId = null,
        connectorBinding = ExploreConnectorBinding(workspaceId, connectorId, ConnectorType.POSTGRESQL),
        requestDigest = ExploreDigest("sha256:${"b".repeat(64)}"),
        state = ExploreRunState.RUNNING,
        visibleResult = null,
        failureCode = null,
        failureMessage = null,
        revision = 0,
        createdAt = now,
        updatedAt = now,
    )

    private fun ExploreRun.succeededWithoutResultMetadata(): ExploreRun {
        val result = ExploreResultView(
            runId = id,
            objectKey = "artifacts/workspaces/$workspaceId/explore/runs/$id/result.arrow",
            objectEtag = "etag",
            byteSize = 1u,
            mediaType = "application/vnd.apache.arrow.file",
            rowCount = 0u,
            columnCount = 1,
            retention = ExploreResultRetention(now.plusSeconds(300)),
            ownerPrincipalId = ownerPrincipalId,
            activatedAt = now,
        )
        return ExploreRun(
            id = id,
            kind = kind,
            idempotencyKey = idempotencyKey,
            ownerPrincipalId = ownerPrincipalId,
            credentialSurface = null,
            agentClientId = null,
            connectorBinding = connectorBinding,
            requestDigest = requestDigest,
            state = ExploreRunState.SUCCEEDED,
            visibleResult = result,
            failureCode = null,
            failureMessage = null,
            revision = 1,
            createdAt = createdAt,
            updatedAt = updatedAt.plusSeconds(1),
        )
    }

    private fun principal(principalId: PrincipalId = ownerId) = CanonicalPrincipal(principalId, PrincipalKind.USER)

    private fun membershipEvidence(principalId: PrincipalId = ownerId) = IdentityRoleEvidence(
        organizationPlan = cc.monomer.identity.api.OrganizationPlan.FREE,
        role = cc.monomer.identity.api.WorkspaceRole.EXPLORER,
        actor = principal(principalId),
        organizationId = OrganizationId.parse("019e0000-0000-7000-8000-0000000000f1"),
        workspaceId = workspaceId,
        authorizationRevision = AuthorizationRevision(1),
        credentialKind = CredentialKind.SESSION,
        credentialSurface = CredentialSurface.WEB,
        agentClientId = null,
    )

    private fun resourceEvidence(principalId: PrincipalId = ownerId) = membershipEvidence(principalId)
}
