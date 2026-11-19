@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.explore

import cc.monomer.connector.api.ConnectorExecutionAccessUseCase
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorRuntimeBindingProjectionPort
import cc.monomer.connector.application.ManagedCredentialReadPort
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionDenial
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.ControlAdmissionStage
import cc.monomer.control.testControlRuntimeProperties
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.ExecutionRunPort
import cc.monomer.explore.api.ExploreConnectorBinding
import cc.monomer.explore.api.ExploreDigest
import cc.monomer.explore.api.ExploreRunId
import cc.monomer.explore.api.ExploreRunState
import cc.monomer.explore.api.ExploreRunUseCase
import cc.monomer.explore.application.ExploreRunRepository
import cc.monomer.explore.domain.ExploreRun
import cc.monomer.identity.api.*
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticQuerySpec
import cc.monomer.semantic.application.SemanticQueryUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ExploreLaunchReplayTest {
    private val workspace = WorkspaceId.generate()
    private val connector = ConnectorId.generate()
    private val owner = PrincipalId.generate()
    private val now = Instant.parse("2026-10-12T00:00:00Z")
    private val query = SemanticQuerySpec(listOf(SemanticElementName("revenue")), emptyList(), null, emptyList(), emptyList(), 100)
    private val sqlCommand = LaunchSqlExploreRunCommand(workspace, PresentedCredential("token"), null, "replay", connector, "SELECT 1")
    private val semanticCommand = LaunchSemanticExploreRunCommand(workspace, PresentedCredential("token"), null, "replay", connector, ExploreDigest("sha256:${"a".repeat(64)}"), query)
    private val admission = mockk<ControlAdmissionFacade>()
    private val repository = mockk<ExploreRunRepository>()
    private val connectors = mockk<ConnectorExecutionAccessUseCase>()
    private val projection = mockk<ConnectorRuntimeBindingProjectionPort>()
    private val credentials = mockk<ManagedCredentialReadPort>()
    private val execution = mockk<ExecutionRunPort>()
    private val runs = mockk<ExploreRunUseCase>()
    private val semantic = mockk<SemanticQueryUseCase>()
    private var snapshotActive = false
    private val snapshots = object : ConsistentSnapshotTransactionRunner {
        override suspend fun <T> run(action: suspend () -> T): T {
            snapshotActive = true
            return try { action() } finally { snapshotActive = false }
        }
    }
    private val sqlLaunch = ExploreSqlLaunchService(connectors, projection, credentials, execution, runs, repository,
        mockk(), admission, snapshots, Clock.fixed(now, ZoneOffset.UTC), testControlRuntimeProperties())
    private val semanticLaunch = ExploreSemanticLaunchService(connectors, projection, semantic, credentials, execution, runs,
        repository, mockk(), admission, snapshots, Clock.fixed(now, ZoneOffset.UTC), testControlRuntimeProperties())

    init {
        coEvery { admission.authorizeWorkspaceRole(any()) } coAnswers {
            assertTrue(snapshotActive)
            ControlAdmissionDecision.Admitted(IdentityRoleEvidence(OrganizationPlan.FREE, CanonicalPrincipal(owner, PrincipalKind.USER),
                OrganizationId.generate(), workspace, AuthorizationRevision(1), CredentialKind.SESSION, CredentialSurface.WEB, null, WorkspaceRole.EXPLORER))
        }
    }

    @Test
    fun `SQL replay returns recorded run without Connector credential validation or reserve`() = runBlocking {
        val stored = stored(ExploreRun.Kind.Sql, exploreRequestDigest("SQL", sqlCommand.sql))
        every { repository.findByAdmissionKey(workspace, "replay") } answers { assertTrue(snapshotActive); stored }
        assertEquals(ExploreSqlLaunchOutcome.Existing(stored.asView(), sqlCommand.sql), sqlLaunch.start(sqlCommand))
        requireNoPreflight()
    }

    @Test
    fun `Semantic replay survives missing current definitions and unavailable warehouse`() = runBlocking {
        val stored = stored(ExploreRun.Kind.Semantic(query, null, reportingTimeZone = null), semanticCommand.requestDigest)
        every { repository.findByAdmissionKey(workspace, "replay") } returns stored
        assertEquals(ExploreSemanticLaunchOutcome.Existing(stored.asView()), semanticLaunch.start(semanticCommand))
        coVerify(exactly = 0) { semantic.readRenderInputs(any()) }
        coVerify(exactly = 0) { semantic.renderFromInputs(any()) }
        requireNoPreflight()
    }

    @Test
    fun `SQL key mismatches owner Connector kind or original request before validation`() = runBlocking {
        val original = stored(ExploreRun.Kind.Sql, exploreRequestDigest("SQL", sqlCommand.sql))
        val mismatches = listOf(original.copy(ownerPrincipalId = PrincipalId.generate()),
            original.copy(connectorBinding = original.connectorBinding.copy(connectorId = ConnectorId.generate())),
            original.copy(kind = ExploreRun.Kind.Semantic(query, null, reportingTimeZone = null)),
            original.copy(requestDigest = ExploreDigest("sha256:${"b".repeat(64)}")))
        for (stored in mismatches) {
            every { repository.findByAdmissionKey(workspace, "replay") } returns stored
            assertIs<ExploreSqlLaunchOutcome.IdempotencyConflict>(sqlLaunch.start(sqlCommand))
        }
        requireNoPreflight()
    }

    @Test
    fun `Semantic key mismatches owner Connector kind digest or query before render`() = runBlocking {
        val original = stored(ExploreRun.Kind.Semantic(query, null, reportingTimeZone = null), semanticCommand.requestDigest)
        val mismatches = listOf(original.copy(ownerPrincipalId = PrincipalId.generate()),
            original.copy(connectorBinding = original.connectorBinding.copy(connectorId = ConnectorId.generate())),
            original.copy(kind = ExploreRun.Kind.Sql),
            original.copy(requestDigest = ExploreDigest("sha256:${"b".repeat(64)}")),
            original.copy(kind = ExploreRun.Kind.Semantic(query.copy(rowLimit = 20), null, reportingTimeZone = null)))
        for (stored in mismatches) {
            every { repository.findByAdmissionKey(workspace, "replay") } returns stored
            assertIs<ExploreSemanticLaunchOutcome.IdempotencyConflict>(semanticLaunch.start(semanticCommand))
        }
        coVerify(exactly = 0) { semantic.readRenderInputs(any()) }
        coVerify(exactly = 0) { semantic.renderFromInputs(any()) }
        requireNoPreflight()
    }

    @Test
    fun `lost membership rejects both replays before admission key lookup`() = runBlocking {
        val denial = ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY,
            ControlAdmissionDenial.Identity(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE))
        coEvery { admission.authorizeWorkspaceRole(any()) } returns denial
        assertEquals(ExploreSqlLaunchOutcome.AdmissionDenied(denial), sqlLaunch.start(sqlCommand))
        assertEquals(ExploreSemanticLaunchOutcome.AdmissionDenied(denial), semanticLaunch.start(semanticCommand))
        verify(exactly = 0) { repository.findByAdmissionKey(workspace, any()) }
        requireNoPreflight()
    }

    private fun stored(kind: ExploreRun.Kind, digest: ExploreDigest) = ExploreRun(
        ExploreRunId.generate(), kind, "replay", owner, CredentialSurface.WEB, null,
        ExploreConnectorBinding(workspace, connector, ConnectorType.POSTGRESQL), digest,
        ExploreRunState.RUNNING, null, null, null, 0, now, now)

    private fun requireNoPreflight() {
        coVerify(exactly = 0) { connectors.readCurrentForExecution(workspace, connector) }
        coVerify(exactly = 0) { credentials.readExact(any()) }
        coVerify(exactly = 0) { execution.validateRawSql(any()) }
        coVerify(exactly = 0) { runs.start(any<cc.monomer.explore.api.StartSqlExploreRunCommand>()) }
        coVerify(exactly = 0) { runs.start(any<cc.monomer.explore.api.StartSemanticExploreRunCommand>()) }
        coVerify(exactly = 0) { admission.authorizeExecution(any()) }
    }
}
