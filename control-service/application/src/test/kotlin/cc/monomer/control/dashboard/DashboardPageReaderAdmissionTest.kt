package cc.monomer.control.dashboard

import cc.monomer.control.*
import cc.monomer.control.pagereader.PublicPagesAccess
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.domain.DashboardNamedQuery
import cc.monomer.dashboard.domain.DashboardResolvedQuery
import cc.monomer.dashboard.domain.DashboardSqlTemplate
import cc.monomer.dashboard.application.DashboardQueryPlanningPort
import cc.monomer.dashboard.application.DashboardQueryResolution
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.*
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.pages.api.*
import cc.monomer.semantic.api.*
import cc.monomer.semantic.application.SemanticQueryUseCase
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.uuid.Uuid
import tools.jackson.databind.ObjectMapper

class DashboardPageReaderAdmissionTest {
    private val workspace = WorkspaceId.generate()
    private val dashboard = DashboardId.generate()
    private val fixtureConnectorId = ConnectorId.generate()
    private val page = Uuid.generateV7()
    private val actor = CanonicalPrincipal(PrincipalId.generate(), PrincipalKind.USER)
    private val credential = PresentedCredential("fixture")
    private val organization = OrganizationId.generate()
    private val admission = mockk<ControlAdmissionFacade>()
    private val public = mockk<WorkspacePublicPagesQuery>()
    private val pages = mockk<PagesCurrentReadPort>()
    private val semantic = mockk<SemanticQueryUseCase>()
    private val snapshot = object : ConsistentSnapshotTransactionRunner {
        override suspend fun <T> run(action: suspend () -> T): T = action()
    }
    private val connection = mockk<java.sql.Connection>(relaxed = true)
    private val adapter = DashboardQueryPlanningAdapter(
        mockk { every { connection } returns this@DashboardPageReaderAdmissionTest.connection },
        ObjectMapper(), semantic, admission, snapshot, mockk(), kotlinx.coroutines.Dispatchers.Unconfined,
        PublicPagesAccess(public, admission, snapshot), pages,
        mockk { coEvery { findByWorkspaceAndId(workspace, fixtureConnectorId) } returns mockk { every { connectorType } returns cc.monomer.connector.api.ConnectorProductType.POSTGRESQL } },
    )
    private val query = Json.parseToJsonElement("""{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":1000}""").jsonObject
    private suspend fun resolve(pageId: Uuid?, token: PresentedCredential?, oauth: ExpectedOAuthResource?, sql: Boolean = false): DashboardQueryResolution {
        val evidence = if (pageId == null) null else when (val result = adapter.admitPageReader(workspace, dashboard, pageId, token, oauth)) {
            is cc.monomer.dashboard.api.DashboardOwnerOutcome.Success -> result.value
            else -> return DashboardQueryResolution.Rejected("DASHBOARD_QUERY_UNAUTHORIZED", null)
        }
        val command = semanticPlanningCommand(workspace, fixtureConnectorId, query, token, oauth, null, dashboard, evidence)
        return adapter.resolve(if (sql) command.copy(
            query = DashboardNamedQuery.Sql("query", command.query.connectorId, DashboardSqlTemplate.compile("SELECT 1 AS value", emptySet(), "query"), emptyMap(), listOf("value"), 1000),
            resolvedQuery = DashboardResolvedQuery.Sql(emptyList(), 1000),
        ) else command)
    }
    private fun rendered() = SemanticRenderResult.Rendered(mockk {
        every { workspaceId } returns workspace
        every { sql } returns "SELECT 1"
        every { reportingTimeZone } returns java.time.ZoneId.of("UTC")
        every { outputColumns } returns listOf(mockk {
            every { name } returns cc.monomer.semantic.api.SemanticResultColumnKey("metric.revenue")
            every { display } returns null
        })
    })
    init {
        coEvery { semantic.render(any()) } returns rendered()
        coEvery { pages.tree(workspace.value) } returns CurrentPagesTree(1,
            listOf(CurrentPage(page, "DASHBOARD", null, dashboard.value, null, "Published", null)), listOf(CurrentPagePlacement(page, emptyList())))
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Admitted(
            IdentityMembershipEvidence(actor, organization, workspace, AuthorizationRevision(1), CredentialKind.SESSION, CredentialSurface.WEB, null, WorkspaceRole.VIEWER))
        coEvery { admission.authorizeCredential(any()) } returns ControlAdmissionDecision.Admitted(IdentityCredentialEvidence(actor, CredentialKind.SESSION, CredentialSurface.WEB, null))
        coEvery { public.isPublic(workspace) } returns true
    }

    @Test fun `ordinary Dashboard execution rejects a role downgraded since request admission`() = runTest {
        coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY,
            ControlAdmissionDenial.Identity(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE))
        assertIs<DashboardQueryResolution.Rejected>(resolve(null, credential, null))
        coVerify(exactly = 1) { admission.authorizeWorkspaceRole(match { it.requiredRole == WorkspaceRole.EXPLORER }) }
        coVerify(exactly = 0) { admission.authorizeCredential(any()) }
    }

    @Test fun `private Viewer reader remains admitted but current membership removal rejects execution`() = runTest {
        coEvery { public.isPublic(workspace) } returns false
        assertIs<DashboardQueryResolution.Resolved>(resolve(page, credential, null))
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY,
            ControlAdmissionDenial.Identity(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE))
        assertIs<DashboardQueryResolution.Rejected>(resolve(page, credential, null))
    }

    @Test fun `public nonmember keeps authenticated actor while Agent cannot fall back to public`() = runTest {
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY,
            ControlAdmissionDenial.Identity(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE))
        val result = assertIs<DashboardQueryResolution.Resolved>(resolve(page, credential, null))
        assertEquals(actor.id.toString(), result.actorId)
        assertEquals(CredentialSurface.WEB, result.credentialSurface)
        assertIs<DashboardQueryResolution.Rejected>(resolve(page, credential, ExpectedOAuthResource.AGENT_REST))
    }

    @Test fun `reader execution rechecks anonymous public access and current placement and dashboard link`() = runTest {
        assertIs<DashboardQueryResolution.Resolved>(resolve(page, null, null))
        coEvery { public.isPublic(workspace) } returns false
        assertIs<DashboardQueryResolution.Rejected>(resolve(page, null, null))
        coEvery { public.isPublic(workspace) } returns true
        coEvery { pages.tree(workspace.value) } returns CurrentPagesTree(2,
            listOf(CurrentPage(page, "DASHBOARD", null, dashboard.value, null, "Published", null)), emptyList())
        assertIs<DashboardQueryResolution.Rejected>(resolve(page, null, null))
        coEvery { pages.tree(workspace.value) } returns CurrentPagesTree(3,
            listOf(CurrentPage(page, "DASHBOARD", null, DashboardId.generate().value, null, "Published", null)), listOf(CurrentPagePlacement(page, emptyList())))
        assertIs<DashboardQueryResolution.Rejected>(resolve(page, null, null))
    }
    @Test fun `SQL uses the same current Explorer and Page admission boundary without Semantic rendering`() = runTest {
        coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY,
            ControlAdmissionDenial.Identity(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE))
        assertEquals("DASHBOARD_QUERY_UNAUTHORIZED", assertIs<DashboardQueryResolution.Rejected>(resolve(null, credential, null, sql = true)).code)
        coEvery { public.isPublic(workspace) } returns false
        val admitted = assertIs<DashboardQueryResolution.Resolved>(resolve(page, credential, null, sql = true))
        assertEquals("SELECT 1 AS value", admitted.sql)
        assertEquals(actor.id.toString(), admitted.actorId)
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY,
            ControlAdmissionDenial.Identity(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE))
        assertIs<DashboardQueryResolution.Rejected>(resolve(page, credential, null, sql = true))
        coEvery { public.isPublic(workspace) } returns true
        assertIs<DashboardQueryResolution.Resolved>(resolve(page, null, null, sql = true))
        assertIs<DashboardQueryResolution.Rejected>(resolve(page, credential, ExpectedOAuthResource.AGENT_REST, sql = true))
        coVerify(exactly = 0) { semantic.render(any()) }
    }

}
