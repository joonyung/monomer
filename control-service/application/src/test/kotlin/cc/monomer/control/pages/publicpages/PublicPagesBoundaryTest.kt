package cc.monomer.control.pages.publicpages

import cc.monomer.control.pagereader.*

import kotlinx.coroutines.reactor.awaitSingle
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.auth.inbound.oauth.JwtAuthenticationWebFilter
import cc.monomer.control.dashboard.DashboardSessionEntryReader
import cc.monomer.dashboard.api.*
import cc.monomer.dashboard.application.DashboardResultReadPort
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.api.WorkspacePublicPagesQuery
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.identity.outbound.jwt.JwtVerifier
import cc.monomer.pages.api.*
import io.mockk.*
import java.time.Clock
import java.time.Instant
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper

class PublicPagesBoundaryTest {
    private val workspace = WorkspaceId(Uuid.generateV7())
    private val pageId = Uuid.generateV7()
    private val dashboard = DashboardId(Uuid.generateV7())
    private val sessionId = DashboardSessionId(Uuid.generateV7())
    private val publicQuery = mockk<WorkspacePublicPagesQuery>()
    private val pages = mockk<PagesCurrentReadPort>()
    private val owner = mockk<DashboardOwnerPort>()
    private val results = mockk<DashboardResultReadPort>()
    private val admission = mockk<ControlAdmissionFacade>()
    private val snapshot = object : ConsistentSnapshotTransactionRunner {
        override suspend fun <T> run(action: suspend () -> T): T = action()
    }
    private val access = PublicPagesAccess(publicQuery, admission, snapshot)
    private val mapper = JsonMapper.builder().build()
    private val now = Instant.parse("2026-09-21T00:00:00Z")
    private val definition = Json.parseToJsonElement("""{
        "meta":{"title":"Analysis"},"parameters":{},"variables":{},"widgets":{},"layout":{},
        "queries":{"revenue":{"type":"SEMANTIC","connectorId":"private-connector","query":{"sql":"PRIVATE SQL"}}},
        "schedules":{"daily":{"private":"schedule"}}
    }""") as JsonObject
    private val session = DashboardOwnerSession(sessionId, workspace, dashboard, definition, emptyMap(), emptyMap(), emptyList(), now, now)
    private val entryReader = mockk<DashboardSessionEntryReader>()
    private val reader = PageReaderService(access, pages, owner, results, entryReader, publicQuery, Clock.fixed(now, java.time.ZoneOffset.UTC))
    private val controller = PublicPagesController(reader, mapper)
    private val accessController = PagesAccessController(access, admission, publicQuery)

    init {
        coEvery { admission.authorizeCredential(any()) } returns cc.monomer.control.ControlAdmissionDecision.Admitted(
            cc.monomer.identity.api.IdentityCredentialEvidence(
                cc.monomer.identity.api.CanonicalPrincipal(cc.monomer.identity.api.PrincipalId.generate(), cc.monomer.identity.api.PrincipalKind.USER),
                cc.monomer.identity.api.CredentialKind.SESSION, cc.monomer.identity.api.CredentialSurface.WEB, null,
            ),
        )
        coEvery { publicQuery.isPublic(workspace) } returns true
        coEvery { publicQuery.pageReaderName(workspace) } returns "Northstar Commerce"
        coEvery { pages.tree(workspace.value) } returns CurrentPagesTree(1,
            listOf(CurrentPage(pageId, "DASHBOARD", null, dashboard.value, "Analysis", "Analysis", null)),
            listOf(CurrentPagePlacement(pageId, emptyList())))
        coEvery { owner.read(workspace, dashboard) } returns DashboardOwnerOutcome.Success(DashboardOwnerDashboard(dashboard, workspace, definition, now, now, null, null, null))
        coEvery { owner.readSession(workspace, dashboard, sessionId) } returns DashboardOwnerOutcome.Success(session)
    }

    @Test fun `Session cursor input is 422 while unrelated run cursor behavior is preserved`() {
        val handler = PublicPagesProblemHandler()
        val failure = PageReaderProblem(PageReaderFailure.InvalidCursor)
        fun exchange(path: String) = org.springframework.mock.web.server.MockServerWebExchange.from(
            org.springframework.mock.http.server.reactive.MockServerHttpRequest.get(path).build())
        assertEquals(422, handler.readerProblem(failure, exchange("/v2/public/workspaces/$workspace/pages/$pageId/sessions")).statusCode.value())
        assertEquals(400, handler.readerProblem(failure, exchange("/v2/public/workspaces/$workspace/pages/$pageId/sessions/$sessionId/query-runs")).statusCode.value())
    }

    @Test fun `resolve hides lost access and maps backpressure to service unavailable`() = runTest {
        for ((ownerCode, status, publicCode) in listOf(
            Triple("DASHBOARD_QUERY_UNAUTHORIZED", HttpStatus.NOT_FOUND, "NOT_FOUND"),
            Triple("EXECUTION_BACKPRESSURED", HttpStatus.SERVICE_UNAVAILABLE, "EXECUTION_BACKPRESSURED"),
        )) {
            coEvery { owner.resolvePublicSession(any()) } returns DashboardOwnerOutcome.Rejected(ownerCode, "private diagnostic")
            val failure = assertFailsWith<PageReaderProblem> {
                controller.resolve(workspace.toString(), pageId.toString(), sessionId.toString(), Uuid.generateV7().toString(), "{\"variables\":{}}")
            }
            val response = PublicPagesProblemHandler().readerProblem(failure, org.springframework.mock.web.server.MockServerWebExchange.from(org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/").build()))
            assertEquals(status, response.statusCode)
            assertEquals(mapOf("code" to publicCode, "retryable" to (status == HttpStatus.SERVICE_UNAVAILABLE)), response.body)
            assertEquals("no-store", response.headers.cacheControl)
        }
    }

    @Test fun `entry projects metadata with one dashboard lookup per authorization snapshot`() = runTest {
        val run = DashboardOwnerQueryRun(QueryRunId.generate(), dashboard, sessionId, "revenue", "SEMANTIC", ConnectorId.generate(), "SUCCEEDED", true, null, "PRIVATE DIAGNOSTIC", listOf("revenue"), now, now, PUBLIC_RUN_ACTOR_ID, cc.monomer.identity.api.CredentialSurface.AGENT, cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE, outputColumnDisplays = null, reportingTimeZone = null)
        val lookup = DashboardOwnerResultLookup(run,
            cc.monomer.execution.api.ArrowResultManifest("private/object", 100u, "etag", 1u, 1, "application/vnd.apache.arrow.file"))
        coEvery { entryReader.select(workspace, dashboard, null, any(), any()) } coAnswers {
            val authorize = arg<suspend () -> Unit>(3)
            authorize()
            val lookups = (0 until 7).map { lookup.copy(queryRun = run.copy(queryRunId = QueryRunId.generate())) }
            arg<suspend (List<DashboardOwnerResultLookup>) -> Unit>(4)(lookups)
            cc.monomer.control.dashboard.DashboardSessionEntry(session, mapOf("revenue" to run))
        }
        val entry = controller.entry(workspace.toString(), pageId.toString(), null)
        assertEquals(mapOf("revenue" to run.readerProjection().response()), entry.initialQueryRuns)
        val serialized = mapper.writeValueAsString(entry)
        for (secret in listOf("PRIVATE", "connectorId", "dashboardId", "accessGraph", "private/object", "credentialSurface", "AGENT", "startedBy", PUBLIC_RUN_ACTOR_ID)) assertFalse(secret in serialized)
        coVerify(exactly = 4) { owner.read(workspace, dashboard) }
        coVerify(exactly = 4) { publicQuery.isPublic(workspace) }
        coVerify(exactly = 4) { pages.tree(workspace.value) }
        coVerify(exactly = 2) { owner.readSession(workspace, dashboard, sessionId) }
        coVerify(exactly = 0) { owner.resolvePublicSession(any()) }
    }

    @Test fun `entry rejects a changed dashboard during result authorization`() = runTest {
        val other = DashboardId.generate()
        val run = DashboardOwnerQueryRun(QueryRunId.generate(), other, sessionId, "revenue", "SEMANTIC", ConnectorId.generate(), "SUCCEEDED", true, null, null, listOf("revenue"), now, now, PUBLIC_RUN_ACTOR_ID, cc.monomer.identity.api.CredentialSurface.AGENT, cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE, outputColumnDisplays = null, reportingTimeZone = null)
        coEvery { entryReader.select(workspace, dashboard, null, any(), any()) } coAnswers {
            coEvery { pages.tree(workspace.value) } returns CurrentPagesTree(2,
                listOf(CurrentPage(pageId, "DASHBOARD", null, other.value, "Analysis", "Analysis", null)),
                listOf(CurrentPagePlacement(pageId, emptyList())))
            coEvery { owner.read(workspace, other) } returns DashboardOwnerOutcome.Success(DashboardOwnerDashboard(other, workspace, definition, now, now, null, null, null))
            coEvery { owner.readSession(workspace, other, sessionId) } returns DashboardOwnerOutcome.Success(session.copy(dashboardId = other))
            arg<suspend (List<DashboardOwnerResultLookup>) -> Unit>(4)(listOf(DashboardOwnerResultLookup(run,
                cc.monomer.execution.api.ArrowResultManifest("private/object", 100u, "etag", 1u, 1, "application/vnd.apache.arrow.file"))))
            error("Changed dashboard must be rejected")
        }
        assertEquals(PageReaderFailure.NotFound, assertFailsWith<PageReaderProblem> {
            controller.entry(workspace.toString(), pageId.toString(), null)
        }.failure)
    }

    @Test fun `entry rechecks current public access after selection`() = runTest {
        coEvery { entryReader.select(workspace, dashboard, null, any(), any()) } coAnswers {
            coEvery { publicQuery.isPublic(workspace) } returns false
            cc.monomer.control.dashboard.DashboardSessionEntry(session, emptyMap())
        }
        assertEquals(PageReaderFailure.NotFound, assertFailsWith<PageReaderProblem> {
            controller.entry(workspace.toString(), pageId.toString(), null)
        }.failure)
    }

    @Test fun `entry rejects dashboard relink after selection`() = runTest {
        val other = DashboardId.generate()
        coEvery { entryReader.select(workspace, dashboard, null, any(), any()) } coAnswers {
            coEvery { pages.tree(workspace.value) } returns CurrentPagesTree(2,
                listOf(CurrentPage(pageId, "DASHBOARD", null, other.value, "Analysis", "Analysis", null)),
                listOf(CurrentPagePlacement(pageId, emptyList())))
            coEvery { owner.read(workspace, other) } returns DashboardOwnerOutcome.Success(
                DashboardOwnerDashboard(other, workspace, definition, now, now, null, null, null))
            coEvery { owner.readSession(workspace, other, sessionId) } returns
                DashboardOwnerOutcome.Success(session.copy(dashboardId = other))
            cc.monomer.control.dashboard.DashboardSessionEntry(session, emptyMap())
        }
        assertEquals(PageReaderFailure.NotFound, assertFailsWith<PageReaderProblem> {
            controller.entry(workspace.toString(), pageId.toString(), null)
        }.failure)
    }

    @Test fun `public tree includes workspace name only while public`() = runTest {
        assertEquals("Northstar Commerce", controller.tree(workspace.value.toString()).workspaceName)
        coEvery { publicQuery.isPublic(workspace) } returns false
        assertEquals(PageReaderFailure.NotFound, assertFailsWith<PageReaderProblem> {
            controller.tree(workspace.value.toString())
        }.failure)
    }

    @Test fun `anonymous access is public`() = runTest {
        assertEquals("PUBLIC", accessController.mode(workspace, MockServerWebExchange.from(MockServerHttpRequest.get("/"))))
    }

    @Test fun `invalid credential denials are unauthorized for access mode and reader alike`() = runTest {
        val authentication = cc.monomer.control.auth.inbound.oauth.JwtAuthentication(
            cc.monomer.identity.domain.model.UserCredentialProvenance.BrowserSession(
                cc.monomer.identity.domain.id.UserId(Uuid.generateV7()), cc.monomer.identity.domain.id.SessionId(Uuid.generateV7())),
            cc.monomer.identity.api.PresentedCredential("revoked"),
        )
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/")).mutate().principal(Mono.just(authentication)).build()
        for (denial in listOf(
            cc.monomer.identity.api.IdentityAdmissionDenial.CREDENTIAL_UNAVAILABLE,
            cc.monomer.identity.api.IdentityAdmissionDenial.CREDENTIAL_EXPIRED,
            cc.monomer.identity.api.IdentityAdmissionDenial.CREDENTIAL_REVOKED,
            cc.monomer.identity.api.IdentityAdmissionDenial.PRINCIPAL_MISMATCH,
        )) {
            coEvery { admission.authorizeWorkspaceMembership(any()) } returns cc.monomer.control.ControlAdmissionDecision.Denied(
                cc.monomer.control.ControlAdmissionStage.IDENTITY, cc.monomer.control.ControlAdmissionDenial.Identity(denial),
            )
            val modeFailure = assertFailsWith<PageReaderProblem> { accessController.mode(workspace, exchange) }
            val response = PublicPagesProblemHandler().readerProblem(modeFailure, org.springframework.mock.web.server.MockServerWebExchange.from(org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/").build()))
            assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
            assertEquals(mapOf("code" to "UNAUTHORIZED", "retryable" to false), response.body)
            assertEquals(PageReaderFailure.Unauthorized, assertFailsWith<PageReaderProblem> {
                reader.tree(PageReaderCaller(workspace, authentication.presentedCredential, null, null, null))
            }.failure)
        }
        coVerify(exactly = 0) { admission.authorizeCredential(any()) }
    }

    @Test fun `dashboard page detail checks the linked dashboard within one tree read`() = runTest {
        val detail = controller.page(workspace.toString(), pageId.toString())
        assertEquals(pageId.toString(), detail.page.pageId)
        assertEquals(listOf(pageId.toString()), detail.path)
        coVerify(exactly = 1) { pages.tree(workspace.value) }
        coVerify(exactly = 1) { owner.read(workspace, dashboard) }
        coEvery { owner.read(workspace, dashboard) } returns DashboardOwnerOutcome.Success(
            DashboardOwnerDashboard(dashboard, WorkspaceId(Uuid.generateV7()), definition, now, now, null, null, null))
        assertEquals(PageReaderFailure.NotFound, assertFailsWith<PageReaderProblem> {
            controller.page(workspace.toString(), pageId.toString())
        }.failure)
    }

    @Test fun `members keep member path while a permission denial never falls back to public`() = runTest {
        val authentication = cc.monomer.control.auth.inbound.oauth.JwtAuthentication(
            cc.monomer.identity.domain.model.UserCredentialProvenance.BrowserSession(
                cc.monomer.identity.domain.id.UserId(Uuid.generateV7()), cc.monomer.identity.domain.id.SessionId(Uuid.generateV7())),
            cc.monomer.identity.api.PresentedCredential("valid"),
        )
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").header("Authorization", "Bearer valid"))
            .mutate().principal(Mono.just(authentication)).build()
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns cc.monomer.control.ControlAdmissionDecision.Admitted(mockk { every { role } returns cc.monomer.identity.api.WorkspaceRole.EXPLORER })
        coEvery { publicQuery.isPublic(workspace) } returns false
        assertEquals("MEMBER", accessController.mode(workspace, exchange))
        val contextExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").header("Authorization", "Bearer valid"))
        val contextMode = kotlinx.coroutines.reactor.mono { accessController.mode(workspace, contextExchange) }
            .contextWrite(org.springframework.security.core.context.ReactiveSecurityContextHolder.withAuthentication(authentication))
            .awaitSingle()
        assertEquals("MEMBER", contextMode)
        coEvery { publicQuery.isPublic(workspace) } returns true
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns cc.monomer.control.ControlAdmissionDecision.Denied(
            cc.monomer.control.ControlAdmissionStage.IDENTITY,
            cc.monomer.control.ControlAdmissionDenial.Identity(cc.monomer.identity.api.IdentityAdmissionDenial.PERMISSION_UNAVAILABLE),
        )
        assertEquals(PageReaderFailure.NotFound, assertFailsWith<PageReaderProblem> { accessController.mode(workspace, exchange) }.failure)
    }

    @Test fun `Viewer reads private workspace through the Page display contract but anonymous cannot`() = runTest {
        val credential = cc.monomer.identity.api.PresentedCredential("viewer")
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns cc.monomer.control.ControlAdmissionDecision.Admitted(mockk {
            every { actor } returns cc.monomer.identity.api.CanonicalPrincipal(cc.monomer.identity.api.PrincipalId.generate(), cc.monomer.identity.api.PrincipalKind.USER)
            every { role } returns cc.monomer.identity.api.WorkspaceRole.VIEWER
            every { credentialKind } returns cc.monomer.identity.api.CredentialKind.SESSION
            every { credentialSurface } returns cc.monomer.identity.api.CredentialSurface.WEB
            every { agentClientId } returns null
        })
        coEvery { publicQuery.isPublic(workspace) } returns false
        assertEquals(sessionId, reader.session(PageReaderCaller(workspace, credential, null, null, null), pageId.toString(), sessionId.toString()).sessionId)
        assertEquals(PageReaderFailure.NotFound, assertFailsWith<PageReaderProblem> {
            reader.session(PageReaderCaller(workspace, null, null, null, null), pageId.toString(), sessionId.toString())
        }.failure)
        val authentication = cc.monomer.control.auth.inbound.oauth.JwtAuthentication(
            cc.monomer.identity.domain.model.UserCredentialProvenance.BrowserSession(
                cc.monomer.identity.domain.id.UserId(Uuid.generateV7()), cc.monomer.identity.domain.id.SessionId(Uuid.generateV7())), credential,
        )
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/")).mutate().principal(Mono.just(authentication)).build()
        assertEquals("VIEWER", accessController.mode(workspace, exchange))
        coEvery { pages.tree(workspace.value) } returns CurrentPagesTree(2, emptyList(), emptyList())
        assertEquals(PageReaderFailure.NotFound, assertFailsWith<PageReaderProblem> {
            reader.session(PageReaderCaller(workspace, credential, null, null, null), pageId.toString(), sessionId.toString())
        }.failure)
    }

    @Test fun `authenticated Page resolve retains credential actor and session without creating another`() = runTest {
        val credential = cc.monomer.identity.api.PresentedCredential("viewer")
        val principal = cc.monomer.identity.api.PrincipalId.generate()
        coEvery { admission.authorizeCredential(any()) } returns cc.monomer.control.ControlAdmissionDecision.Admitted(
            cc.monomer.identity.api.IdentityCredentialEvidence(
                cc.monomer.identity.api.CanonicalPrincipal(principal, cc.monomer.identity.api.PrincipalKind.USER),
                cc.monomer.identity.api.CredentialKind.SESSION, cc.monomer.identity.api.CredentialSurface.WEB, null,
            ),
        )
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns cc.monomer.control.ControlAdmissionDecision.Admitted(mockk {
            every { role } returns cc.monomer.identity.api.WorkspaceRole.VIEWER
            every { credentialKind } returns cc.monomer.identity.api.CredentialKind.SESSION
            every { credentialSurface } returns cc.monomer.identity.api.CredentialSurface.WEB
            every { agentClientId } returns null
            every { actor } returns cc.monomer.identity.api.CanonicalPrincipal(principal, cc.monomer.identity.api.PrincipalKind.USER)
        })
        coEvery { publicQuery.isPublic(workspace) } returns false
        coEvery { owner.resolvePublicSession(any()) } returns DashboardOwnerOutcome.Success(DashboardOwnerSessionResolution(emptyMap(), emptyMap()))
        val authentication = cc.monomer.control.auth.inbound.oauth.JwtAuthentication(
            cc.monomer.identity.domain.model.UserCredentialProvenance.BrowserSession(
                cc.monomer.identity.domain.id.UserId(principal.value), cc.monomer.identity.domain.id.SessionId(Uuid.generateV7())), credential,
        )
        kotlinx.coroutines.reactor.mono {
            controller.resolve(workspace.toString(), pageId.toString(), sessionId.toString(), Uuid.generateV7().toString(), "{\"variables\":{}}")
        }.contextWrite(org.springframework.security.core.context.ReactiveSecurityContextHolder.withAuthentication(authentication)).awaitSingle()
        coVerify(exactly = 1) { owner.resolvePublicSession(match {
            it.credential == credential && it.actorId == principal.toString() && it.sessionId == sessionId && it.expectedOAuthResource == null
        }) }
        coVerify(exactly = 0) { owner.openSession(any()) }
    }

    @Test fun `authenticated nonmember public resolve keeps actual actor and closes when public turns off`() = runTest {
        val credential = cc.monomer.identity.api.PresentedCredential("public-visitor")
        val principal = cc.monomer.identity.api.PrincipalId.generate()
        coEvery { admission.authorizeCredential(any()) } returns cc.monomer.control.ControlAdmissionDecision.Admitted(
            cc.monomer.identity.api.IdentityCredentialEvidence(
                cc.monomer.identity.api.CanonicalPrincipal(principal, cc.monomer.identity.api.PrincipalKind.USER),
                cc.monomer.identity.api.CredentialKind.SESSION, cc.monomer.identity.api.CredentialSurface.WEB, null,
            ),
        )
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns cc.monomer.control.ControlAdmissionDecision.Denied(
            cc.monomer.control.ControlAdmissionStage.IDENTITY,
            cc.monomer.control.ControlAdmissionDenial.Identity(cc.monomer.identity.api.IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE),
        )
        coEvery { owner.resolvePublicSession(any()) } returns DashboardOwnerOutcome.Success(DashboardOwnerSessionResolution(emptyMap(), emptyMap()))
        val authentication = cc.monomer.control.auth.inbound.oauth.JwtAuthentication(
            cc.monomer.identity.domain.model.UserCredentialProvenance.BrowserSession(
                cc.monomer.identity.domain.id.UserId(principal.value), cc.monomer.identity.domain.id.SessionId(Uuid.generateV7())), credential,
        )
        kotlinx.coroutines.reactor.mono {
            controller.resolve(workspace.toString(), pageId.toString(), sessionId.toString(), Uuid.generateV7().toString(), "{\"variables\":{}}")
        }.contextWrite(org.springframework.security.core.context.ReactiveSecurityContextHolder.withAuthentication(authentication)).awaitSingle()
        coVerify(exactly = 1) { owner.resolvePublicSession(match { it.credential == credential && it.actorId == principal.toString() }) }
        coVerify(exactly = 0) { owner.openSession(any()) }
        coEvery { publicQuery.isPublic(workspace) } returns false
        assertEquals(PageReaderFailure.NotFound, assertFailsWith<PageReaderProblem> {
            reader.session(PageReaderCaller(workspace, credential, null, null, null), pageId.toString(), sessionId.toString())
        }.failure)
    }

    @Test fun `Agent Page reader cannot use public fallback after losing direct membership`() = runTest {
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns cc.monomer.control.ControlAdmissionDecision.Denied(
            cc.monomer.control.ControlAdmissionStage.IDENTITY,
            cc.monomer.control.ControlAdmissionDenial.Identity(cc.monomer.identity.api.IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE),
        )
        assertEquals(PageReaderFailure.NotFound, assertFailsWith<PageReaderProblem> {
            reader.session(PageReaderCaller(workspace, cc.monomer.identity.api.PresentedCredential("agent"), cc.monomer.identity.api.ExpectedOAuthResource.AGENT_REST, "actor", null), pageId.toString(), sessionId.toString())
        }.failure)
        coVerify(exactly = 0) { owner.readSession(workspace, dashboard, sessionId) }
    }

    @Test fun `revocation is rechecked before receipt replay`() = runTest {
        coEvery { owner.resolvePublicSession(any()) } returns DashboardOwnerOutcome.Success(DashboardOwnerSessionResolution(emptyMap(), emptyMap()))
        val key = Uuid.generateV7().toString()
        controller.resolve(workspace.toString(), pageId.toString(), sessionId.toString(), key, "{\"variables\":{}}")
        coEvery { publicQuery.isPublic(workspace) } returns false
        assertEquals(PageReaderFailure.NotFound, assertFailsWith<PageReaderProblem> {
            controller.resolve(workspace.toString(), pageId.toString(), sessionId.toString(), key, "{\"variables\":{}}")
        }.failure)
        coVerify(exactly = 1) { owner.resolvePublicSession(any()) }
    }

    @Test fun `page detached from current tree cannot expose session`() = runTest {
        coEvery { pages.tree(workspace.value) } returns CurrentPagesTree(2,
            listOf(CurrentPage(pageId, "DASHBOARD", null, dashboard.value, "Analysis", "Analysis", null)), emptyList())
        assertFailsWith<PageReaderProblem> { controller.session(workspace.toString(), pageId.toString(), sessionId.toString()) }
        coVerify(exactly = 0) { owner.readSession(workspace, dashboard, sessionId) }
    }

    @Test fun `cross workspace session is rejected`() = runTest {
        coEvery { owner.readSession(workspace, dashboard, sessionId) } returns DashboardOwnerOutcome.Success(session.copy(workspaceId = WorkspaceId(Uuid.generateV7())))
        assertEquals(PageReaderFailure.NotFound, assertFailsWith<PageReaderProblem> { reader.session(PageReaderCaller(workspace, null, null, null, null), pageId.toString(), sessionId.toString()) }.failure)
    }

    @Test fun `draft other session and undeclared query runs are rejected`() = runTest {
        val run = DashboardOwnerQueryRun(QueryRunId(Uuid.generateV7()), dashboard, sessionId, "revenue", "SEMANTIC", ConnectorId(Uuid.generateV7()), "SUCCEEDED", true, null, "PRIVATE DIAGNOSTIC", listOf("revenue"), now, now, PUBLIC_RUN_ACTOR_ID, cc.monomer.identity.api.CredentialSurface.AGENT, cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE, outputColumnDisplays = null, reportingTimeZone = null)
        coEvery { owner.readSessionQueryRun(any()) } returns DashboardOwnerOutcome.Success(run)
        controller.run(workspace.toString(), pageId.toString(), sessionId.toString(), run.queryRunId.toString())
        for (foreign in listOf(run.copy(viewerSessionId = null), run.copy(viewerSessionId = DashboardSessionId(Uuid.generateV7())), run.copy(queryName = "secret"), run.copy(dashboardId = DashboardId(Uuid.generateV7())))) {
            coEvery { owner.readSessionQueryRun(any()) } returns DashboardOwnerOutcome.Success(foreign)
            assertEquals(PageReaderFailure.NotFound, assertFailsWith<PageReaderProblem> { controller.run(workspace.toString(), pageId.toString(), sessionId.toString(), run.queryRunId.toString()) }.failure)
        }
    }

    @Test fun `display response cannot serialize query definitions internal ids or diagnostics`() = runTest {
        val response = controller.session(workspace.toString(), pageId.toString(), sessionId.toString())
        val display = response.display.toString()
        assertEquals("[\"revenue\"]", response.display["queryNames"].toString())
        for (secret in listOf("PRIVATE SQL", "private-connector", "schedules", "connectorId")) assertFalse(secret in display)
        for ((failureCode, safeMessage) in listOf(
            "DRIVER_STACK" to "PRIVATE DIAGNOSTIC",
            "QUERY_BIND_UNSUPPORTED" to "Databricks binary parameters, including typed nulls, are unsupported. Use a Semantic query.",
        )) {
            val run = DashboardOwnerQueryRun(QueryRunId(Uuid.generateV7()), dashboard, sessionId, "revenue", "SQL", ConnectorId(Uuid.generateV7()), "FAILED", false, failureCode, safeMessage, emptyList(), now, now, null, null, null, outputColumnDisplays = null, reportingTimeZone = null).readerProjection().response()
            assertEquals("QUERY_FAILED", run.failureCode)
            assertFalse(safeMessage in run.toString())
            assertFalse(failureCode in run.toString())
        }
    }

    @Test fun `resolve rejects injected fields duplicates trailing data and invalid key before owner invocation`() = runTest {
        for (body in listOf("{\"variables\":{},\"actorId\":\"owner\"}", "{\"variables\":{},\"variables\":{}}", "{\"variables\":{}} {}", "{\"variables\":[],\"sql\":\"x\"}")) {
            assertEquals(PageReaderFailure.InvalidRequest, assertFailsWith<PageReaderProblem> {
                controller.resolve(workspace.toString(), pageId.toString(), sessionId.toString(), Uuid.generateV7().toString(), body)
            }.failure)
        }
        assertFailsWith<PageReaderProblem> { controller.resolve(workspace.toString(), pageId.toString(), sessionId.toString(), "not-uuid", "{\"variables\":{}}") }
        coVerify(exactly = 0) { owner.resolvePublicSession(any()) }
    }

    @Test fun `invalid bearer never falls back to anonymous on public path`() {
        val verifier = mockk<JwtVerifier>()
        every { verifier.verify("expired") } returns JwtVerifier.Outcome.Invalid("expired")
        val filter = JwtAuthenticationWebFilter(verifier, mockk(), mockk(), mockk())
        val prefix = "/v2/public/workspaces/$workspace/pages/$pageId"
        val jsonRequests = listOf<() -> MockServerHttpRequest.BaseBuilder<*>>(
            { MockServerHttpRequest.get("/v2/workspaces/$workspace/pages/access") },
            { MockServerHttpRequest.get("/v2/public/workspaces/$workspace/pages/tree") },
            { MockServerHttpRequest.get(prefix) },
            { MockServerHttpRequest.get("$prefix/sessions/$sessionId/query-runs/${Uuid.generateV7()}/result") },
            { MockServerHttpRequest.post("$prefix/sessions/$sessionId:resolve") },
        )
        val emptyRequests = listOf<() -> MockServerHttpRequest.BaseBuilder<*>>(
            { MockServerHttpRequest.get("$prefix/html") },
            { MockServerHttpRequest.head("$prefix/html") },
        )
        for (header in listOf("Bearer expired", "Basic invalid")) {
            for ((requests, body) in listOf(jsonRequests to "{\"code\":\"UNAUTHORIZED\"}", emptyRequests to "")) {
                for (request in requests) {
                    val exchange = MockServerWebExchange.from(request().header("Authorization", header))
                    var invoked = false
                    filter.filter(exchange, WebFilterChain { invoked = true; Mono.empty() }).block()
                    assertEquals(HttpStatus.UNAUTHORIZED, exchange.response.statusCode)
                    assertEquals("no-store", exchange.response.headers.cacheControl)
                    assertEquals(if (body.isEmpty()) null else org.springframework.http.MediaType.APPLICATION_JSON, exchange.response.headers.contentType)
                    assertEquals(body, exchange.response.bodyAsString.block().orEmpty())
                    assertFalse(invoked)
                }
            }
        }
    }

    @Test fun `no store covers errors and responses on every public path`() {
        for (path in listOf("/v2/workspaces/$workspace/pages/access", "/v2/public/workspaces/$workspace/pages/tree", "/v2/public/workspaces/$workspace/pages/$pageId/sessions/$sessionId/query-runs/invalid/result")) {
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path))
            PublicPagesNoStoreFilter().filter(exchange, WebFilterChain {
                exchange.response.statusCode = HttpStatus.NOT_FOUND
                exchange.response.setComplete()
            }).block()
            assertEquals("no-store", exchange.response.headers.cacheControl)
        }
    }
}

/** A member who started a run that public readers see; no public response may name them. */
private const val PUBLIC_RUN_ACTOR_ID = "01900000-0000-7000-8000-00000000a11c"
