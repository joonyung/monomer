@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control

import cc.monomer.agent.api.*
import cc.monomer.agent.application.AgentWorkspaceContractDecoder
import cc.monomer.control.agent.AgentWorkspaceApplicationComposition
import cc.monomer.control.auth.inbound.graphql.GrantMutationsResolver
import cc.monomer.control.auth.inbound.oauth.JwtAuthentication
import cc.monomer.control.connector.inbound.rest.ConnectorMutationController
import cc.monomer.control.dashboard.DashboardRequestAuthorizer
import cc.monomer.control.explore.*
import cc.monomer.control.human.auth.ConnectorCatalogRequestAccess
import cc.monomer.control.overview.OverviewActivityController
import cc.monomer.control.pagereader.PublicPagesAccess
import cc.monomer.control.pages.inbound.rest.pages.WorkspacePagesRequestAccess
import cc.monomer.control.semantic.inbound.rest.*
import cc.monomer.explore.api.ExploreRunId
import cc.monomer.explore.application.ExploreRunRepository
import cc.monomer.identity.api.*
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.application.grant.SetWorkspaceMemberRoleService
import cc.monomer.identity.application.workspace.OwnerWorkspaceMutationRunner
import cc.monomer.identity.domain.model.UserCredentialProvenance
import cc.monomer.identity.domain.port.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.test.runTest
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import reactor.core.publisher.Mono
import kotlin.test.*
import kotlin.uuid.Uuid

/** §10 policy fixture is independent of runtime requiredRole/includes and rejects inventory drift.
 * Owner fakes terminate after real Web and Agent admission; this is contract evidence, not live result QA.
 */
class RoleSurfaceParityTest {
    private val mapper = jacksonObjectMapper()
    private val matrix = mapper.readTree(requireNotNull(javaClass.getResourceAsStream("/role-surface-matrix.json")))
    private val rows = matrix["workspace"].toList()
    private val decoder = AgentWorkspaceContractDecoder()
    private val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("contracts/agent-workspace.schema.json")) }
    private val definitions = mapper.readTree(repository.resolve("contracts/agent-workspace.schema.json").toFile())["\$defs"]
    private val workspace = WorkspaceId(Uuid.parse(ID))
    private val organization = OrganizationId.generate()
    private val actor = CanonicalPrincipal(PrincipalId.generate(), PrincipalKind.USER)
    private val clock = Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC)
    private val snapshots = object : ConsistentSnapshotTransactionRunner {
        override suspend fun <T> run(action: suspend () -> T): T = action()
    }

    @Test
    fun `every supported Agent path and mutation branch has an explicit matrix entry`() {
        val pathDefinitions = definitions.fieldNames().asSequence().filter { it.endsWith("Path") }.toSet()
        assertEquals(pathDefinitions, matrix["pathDefinitions"].map { it.asText() }.toSet(), "New path needs explicit role coverage")
        val paths = rows.map { it["body"]["path"].asText() } + matrix["organizationPaths"].map { it["path"].asText() }
        for (name in pathDefinitions) {
            assertEquals(definitions[name], matrix["pathContracts"][name], "Changed path grammar requires reviewed matrix coverage: $name")
            assertTrue(paths.any { matches(definitions[name], it) }, "No matrix request covers $name")
            assertTrue(paths.containsAll(literalPaths(definitions[name])), "Missing literal path in $name: ${literalPaths(definitions[name]) - paths.toSet()}")
        }
        for ((operation, root) in mapOf("create" to "AgentCreateRequest", "edit" to "AgentEditRequest", "invoke" to "AgentInvokeRequest")) {
            val expected = definitions[root]["oneOf"].map { it["\$ref"].asText().substringAfterLast('/') }.toSet()
            val actual = rows.filter { it["operation"].asText() == operation }.mapNotNull {
                it["schemaDefinition"]?.takeUnless(JsonNode::isNull)?.asText()
            }.toSet() + matrix["organizationMutationDefinitions"].map { it.asText() }.filter { it in expected }
            assertEquals(expected, actual, "New $operation branch needs a role verdict")
        }
        // Accepted §10 rows 2/10 include Web-only helpers/public entry; the test below exercises them.
        assertEquals((1..10).toSet(), rows.map { it["section"].asInt() }.toSet() + setOf(2, 10))
    }

    @Test
    fun `REST and MCP decode all matrix requests through installed identity admission`() = runTest {
        for (role in WorkspaceRole.entries + listOf(null)) {
            val fixture = Boundary(role)
            for (audience in AgentWorkspaceAdmissionAudience.entries) {
                for (row in rows) {
                    val expected = role?.name in row["allowedRoles"].map { it.asText() }
                    val body = row["body"].toString()
                    val outcome = try { fixture.agent(row["operation"].asText(), body, audience) } catch (failure: Exception) { throw AssertionError("Invalid fixture: $body", failure) }
                    val label = "$role $audience ${row["operation"]} ${row["body"]["path"]}"
                    assertEquals(expected, outcome is AgentWorkspaceCallerAuthenticationOutcome.Authenticated, label)
                    assertEquals(audience.expectedOAuthResource, fixture.requests.last().expectedOAuthResource, label)
                    assertEquals(row["allowedRoles"][0].asText(), fixture.requests.last().requiredRole.name, label)
                    if (role == null) assertEquals(
                        AgentWorkspaceCallerAuthenticationOutcome.Denied(AgentWorkspaceAdmissionDenial.NOT_FOUND, null), outcome, label,
                    )
                }
            }
        }
    }

    @Test
    fun `Web REST owner authorizers and GraphQL role mutation agree with supported Agent actions`() = runTest {
        for (role in WorkspaceRole.entries + listOf(null)) {
            val fixture = Boundary(role)
            for (row in rows.distinctBy { it["webGate"].asText() }) {
                val gate = row["webGate"].asText()
                val expected = role?.name in row["allowedRoles"].map { it.asText() }
                assertEquals(expected, fixture.web(gate), "$role Web $gate")
            }
        }
    }

    @Test
    fun `query helpers Time Spine and Overview retain Web gates without inventing Agent routes`() = runTest {
        for (role in WorkspaceRole.entries + listOf(null)) {
            val fixture = Boundary(role)
            val explorer = role in setOf(WorkspaceRole.EXPLORER, WorkspaceRole.EDITOR, WorkspaceRole.OWNER)
            val editor = role in setOf(WorkspaceRole.EDITOR, WorkspaceRole.OWNER)
            assertEquals(explorer, fixture.web("QUERY_HELPERS"), "$role group-bys")
            assertEquals(editor, fixture.web("SEMANTIC_TIME_SPINE_UPDATE"), "$role Time Spine")
            assertEquals(explorer, fixture.web("OVERVIEW"), "$role Overview")
            assertEquals(role == WorkspaceRole.OWNER, fixture.graphqlPublicPages(), "$role Public Pages setting")
            assertEquals(role == WorkspaceRole.OWNER, fixture.graphqlReportingTimeZone(organizationAdmin = false), "$role Reporting time zone")
            assertTrue(fixture.graphqlReportingTimeZone(organizationAdmin = true), "$role Organization Admin Reporting time zone")
            for (path in listOf("workspace/semantic/query/group-bys", "workspace/semantic/query/render-sql",
                "workspace/semantic/time-spines", "workspace/overview/activity", "workspace/settings/public-pages",
                "workspace/settings/reporting-time-zone")) {
                for (audience in AgentWorkspaceAdmissionAudience.entries) {
                    val before = fixture.requests.size
                    assertEquals(AgentWorkspaceCallerAuthenticationOutcome.InvalidRequest,
                        fixture.agent("read", """{"path":"$path"}""", audience), path)
                    assertEquals(before, fixture.requests.size, "Undeclared path cannot issue admission")
                }
            }
        }
    }

    @Test
    fun `Page public browser exception never opens Agent membership or Page session creation`() = runTest {
        for (public in listOf(false, true)) for (role in WorkspaceRole.entries + listOf(null)) {
            val fixture = Boundary(role)
            val access = PublicPagesAccess(mockk { coEvery { isPublic(workspace) } returns public }, fixture.facade, snapshots)
            assertEquals(role != null || public, runCatching { access.requireWorkspace(workspace, fixture.webCredential, null) }.isSuccess)
            assertEquals(public, runCatching { access.requireWorkspace(workspace, null, null) }.isSuccess)
            for (audience in AgentWorkspaceAdmissionAudience.entries) {
                assertEquals(role != null, fixture.agent("read", """{"path":"workspace/pages/tree"}""", audience)
                    is AgentWorkspaceCallerAuthenticationOutcome.Authenticated)
                assertEquals(role != null, runCatching {
                    access.requireWorkspace(workspace, fixture.agentCredential, audience.expectedOAuthResource)
                }.isSuccess)
            }
        }
        val owner = Boundary(WorkspaceRole.OWNER)
        for (path in listOf("workspace/new-resource", "workspace/connectors/$ID/catalog/undeclared", "workspace/connectors/$ID/catalog/selection/options/catalogs", "workspace/pages/$ID/attachments", "workspace/pages/$ID/sessions/$ID/events")) {
            assertFailsWith<IllegalArgumentException> { AgentWorkspaceAdmissionIntent.Read(AgentResourcePath.parse(path)).requiredRole }
            assertEquals(AgentWorkspaceCallerAuthenticationOutcome.InvalidRequest,
                owner.agent("read", """{"path":"$path"}""", AgentWorkspaceAdmissionAudience.REST))
        }
        for ((path, action) in listOf("workspace/pages/$ID/sessions" to "open", "workspace/pages/$ID/sessions/$ID" to "refresh")) {
            assertFailsWith<AgentWorkspaceRequestDecodingException> {
                decoder.invoke("""{"path":"$path","action":"$action","input":{"kind":"DASHBOARD_SESSION_OPEN","parameterBindings":{}}}""")
            }
        }
    }

    @Test
    fun `Organization Admin role change is a separate axis from Workspace ownership`() = runTest {
        // R5's AgentManagementPersistenceIntegrationTest
        // exercise actual REST/MCP routing, tenant checks, audit/revision and last-personal-Owner protection.
        for (role in WorkspaceRole.entries + listOf(null)) {
            assertTrue(Boundary(role).graphqlRoleChange(organizationAdmin = true), "Organization Admin $role")
            assertEquals(role == WorkspaceRole.OWNER, Boundary(role).graphqlRoleChange(organizationAdmin = false), "$role without Admin")
        }
        for (row in matrix["organizationPaths"]) {
            val path = row["path"].asText()
            assertTrue(AgentManagementPaths.organization(path), path)
            assertTrue(matches(definitions[row["schemaDefinition"].asText()], path), path)
            assertEquals(AgentWorkspaceCallerAuthenticationOutcome.InvalidRequest,
                Boundary(WorkspaceRole.OWNER).agent(if (AgentManagementPaths.organizationCollection.matches(path)) "list" else "read",
                    mapper.writeValueAsString(if (AgentManagementPaths.organizationCollection.matches(path)) mapOf("path" to path, "limit" to 20) else mapOf("path" to path)),
                    AgentWorkspaceAdmissionAudience.REST), "Organization management must not borrow Workspace Owner admission")
        }
    }

    private fun literalPaths(schema: JsonNode): Set<String> {
        schema["\$ref"]?.let { return literalPaths(definitions[it.asText().substringAfterLast('/')]) }
        schema["allOf"]?.let { return it.flatMap { part -> literalPaths(part) }.toSet() }
        schema["const"]?.let { return setOf(it.asText()) }
        schema["enum"]?.let { return it.map(JsonNode::asText).toSet() }
        return emptySet()
    }

    private fun matches(schema: JsonNode, path: String): Boolean {
        schema["\$ref"]?.let { return matches(definitions[it.asText().substringAfterLast('/')], path) }
        schema["allOf"]?.let { return it.all { part -> matches(part, path) } }
        schema["const"]?.let { return it.asText() == path }
        schema["enum"]?.let { return it.any { value -> value.asText() == path } }
        schema["pattern"]?.let { return Regex(it.asText()).matches(path) }
        error("Uncovered path contract shape: $schema")
    }

    private inner class Boundary(val role: WorkspaceRole?) {
        val requests = mutableListOf<IdentityRoleAdmissionRequest>()
        val webCredential = PresentedCredential("web-fixture")
        val agentCredential = PresentedCredential("agent-fixture")
        private val identity = mockk<IdentityAdmissionPort>()
        val facade = ControlAdmissionFacade(identity, clock)
        private val composition = AgentWorkspaceApplicationComposition()
        private val authenticator = composition.agentWorkspaceCallerAuthenticator(composition.agentWorkspaceAdmissionPort(facade, mockk(), mockk(), mockk()))
        private val authentication = JwtAuthentication(UserCredentialProvenance.BrowserSession(
            cc.monomer.identity.domain.id.UserId(actor.id.value), cc.monomer.identity.domain.id.SessionId(Uuid.generateV7())), webCredential)
        private val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").header("Authorization", "Bearer web-fixture"))
            .mutate().principal(Mono.just(authentication)).build()
        init {
            coEvery { identity.decide(any<IdentityRoleAdmissionRequest>()) } answers {
                val request = firstArg<IdentityRoleAdmissionRequest>()
                requests += request
                if (role == null) IdentityRoleAdmissionDecision.Denied(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE)
                else IdentityRoleAdmissionDecision.Authorized(IdentityRoleEvidence(OrganizationPlan.FREE, actor, organization, workspace,
                    AuthorizationRevision(1), if (request.expectedOAuthResource == null) CredentialKind.SESSION else CredentialKind.AGENT_OAUTH,
                    if (request.expectedOAuthResource == null) CredentialSurface.WEB else CredentialSurface.AGENT,
                    if (request.expectedOAuthResource == null) null else cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE, role))
            }
            coEvery { identity.decide(any<IdentityMembershipAdmissionRequest>()) } answers {
                val request = firstArg<IdentityMembershipAdmissionRequest>()
                if (role == null) IdentityMembershipAdmissionDecision.Denied(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE)
                else IdentityMembershipAdmissionDecision.Authorized(IdentityMembershipEvidence(actor, organization, workspace, AuthorizationRevision(1),
                    if (request.expectedOAuthResource == null) CredentialKind.SESSION else CredentialKind.AGENT_OAUTH,
                    if (request.expectedOAuthResource == null) CredentialSurface.WEB else CredentialSurface.AGENT,
                    if (request.expectedOAuthResource == null) null else cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE, role))
            }
            coEvery { identity.decide(any<IdentityCredentialAdmissionRequest>()) } returns IdentityCredentialAdmissionDecision.Authorized(
                IdentityCredentialEvidence(actor, CredentialKind.SESSION, CredentialSurface.WEB, null))
        }
        suspend fun agent(operation: String, body: String, audience: AgentWorkspaceAdmissionAudience): AgentWorkspaceCallerAuthenticationOutcome {
            val requestId = AgentRequestId("srv_matrix")
            return when (operation) {
                "list" -> authenticator.authenticate(workspace, agentCredential, audience, decoder.list(body), requestId)
                "read" -> authenticator.authenticate(workspace, agentCredential, audience, decoder.read(body), requestId)
                "search" -> authenticator.authenticate(workspace, agentCredential, audience, decoder.search(body), requestId)
                "create" -> authenticator.authenticate(workspace, agentCredential, audience, decoder.create(body).value, requestId)
                "edit" -> authenticator.authenticate(workspace, agentCredential, audience, decoder.edit(body).value, requestId)
                "invoke" -> authenticator.authenticate(workspace, agentCredential, audience, decoder.invoke(body).value, requestId)
                else -> error(operation)
            }
        }
        suspend fun web(gate: String): Boolean {
            if (gate == "WORKSPACE_OWNER") return graphqlRoleChange(false)
            val semantic = SemanticWorkspaceRequestAuthorizer(facade)
            return try {
                when (gate) {
                    "MEMBERSHIP" -> return facade.authorizeWorkspaceMembership(
                        WorkspaceMembershipAdmissionRequest(workspace, webCredential, null)) is ControlAdmissionDecision.Admitted
                    "PAGE_READER" -> PublicPagesAccess(mockk { coEvery { isPublic(workspace) } returns false }, facade, snapshots)
                        .requireWorkspace(workspace, webCredential, null)
                    "SEMANTIC_READ" -> semantic.authorizeExplorer(exchange, workspace)
                    "DASHBOARD_READ", "DASHBOARD_DELETE", "DASHBOARD_RESTORE", "DASHBOARD_HISTORY", "DASHBOARD_EDIT" ->
                        DashboardRequestAuthorizer(facade).requireWorkspace(exchange, workspace.toString())
                    "PAGE_EDIT" -> WorkspacePagesRequestAccess(facade).require(exchange, workspace.toString())
                    "CATALOG_READ" -> ConnectorCatalogRequestAccess(mockk(), facade).requireWorkspace(exchange, workspace.toString())
                    "EXPLORE_READ" -> {
                        val runs = mockk<ExploreRunRepository>()
                        coEvery { runs.findRun(ExploreRunId(Uuid.parse(ID))) } throws OwnerBoundaryReached()
                        val service = ExploreRunAccessService(runs, mockk(), mockk(), mockk(), facade, snapshots, clock, kotlinx.coroutines.Dispatchers.Unconfined)
                        return service.readSnapshot(ReadExploreRunCommand(workspace, ExploreRunId(Uuid.parse(ID)), webCredential, null)) !is ExploreRunSnapshotOutcome.AdmissionDenied
                    }
                    "EXPLORE_EXECUTE" -> {
                        val connectors = mockk<cc.monomer.connector.api.ConnectorExecutionAccessUseCase>()
                        coEvery { connectors.readCurrentForExecution(workspace, cc.monomer.connector.api.ConnectorId(Uuid.parse(ID))) } throws OwnerBoundaryReached()
                        val service = ExploreSqlLaunchService(connectors, mockk(), mockk(), mockk(), mockk(),
                            mockk { io.mockk.every { findByAdmissionKey(workspace, any()) } returns null }, mockk(), facade,
                            snapshots, clock, mockk(relaxed = true))
                        return service.start(LaunchSqlExploreRunCommand(workspace, webCredential, null, "matrix",
                            cc.monomer.connector.api.ConnectorId(Uuid.parse(ID)), "SELECT 1")) !is ExploreSqlLaunchOutcome.AdmissionDenied
                    }
                    "CATALOG_EXECUTE" -> {
                        val ingest = mockk<cc.monomer.catalog.application.ingest.CatalogIngestApplicationPort>()
                        coEvery { ingest.startIngest(any()) } throws OwnerBoundaryReached()
                        cc.monomer.control.catalog.inbound.rest.CatalogAuthorityController(ingest, mockk(), facade, mockk(), cc.monomer.control.agentapp.FixedAgentAppDisplays)
                            .startIngest(workspace.toString(), ID,
                                cc.monomer.control.catalog.inbound.rest.CatalogAuthorityController.CatalogIngestHttpRequest("CONNECTOR", null), exchange)
                    }
                    "CONNECTOR_CREATE" -> {
                        val authority = mockk<cc.monomer.connector.api.ConnectorAuthorityUseCase>()
                        coEvery { authority.create(any()) } throws OwnerBoundaryReached()
                        ConnectorMutationController(authority, facade).create(workspace.toString(), "matrix",
                            """{"connectorType":"POSTGRESQL","displayName":"Warehouse","properties":{"host":"db.example.com","port":5432,"database":"warehouse"},"credential":{"kind":"USERNAME_PASSWORD","username":"reader","password":"fixture-only"}}""", exchange)
                    }
                    "CONNECTOR_UPDATE" -> {
                        val authority = mockk<cc.monomer.connector.api.ConnectorAuthorityUseCase>()
                        coEvery { authority.revise(any()) } throws OwnerBoundaryReached()
                        val controller = ConnectorMutationController(authority, facade)
                        controller.revise(workspace.toString(), ID, "matrix", """{"displayName":"Warehouse","properties":{}}""", exchange)
                    }
                    "CONNECTOR_DELETE" -> {
                        val authority = mockk<cc.monomer.connector.api.ConnectorAuthorityUseCase>()
                        coEvery { authority.delete(any()) } throws OwnerBoundaryReached()
                        ConnectorMutationController(authority, facade).delete(workspace.toString(), ID, exchange)
                    }
                    "QUERY_HELPERS" -> {
                        val queries = mockk<cc.monomer.semantic.application.SemanticQueryUseCase>()
                        coEvery { queries.readGroupByInputs(any()) } throws OwnerBoundaryReached()
                        SemanticQueryController(queries, semantic, snapshots).listGroupBys(workspace.toString(),
                            SemanticGroupByOptionsHttpRequest(ID, listOf("revenue")), exchange)
                    }
                    "OVERVIEW" -> OverviewActivityController(facade, mockk(relaxed = true), mockk(relaxed = true),
                        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), cc.monomer.control.agentapp.FixedAgentAppDisplays, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
                        .read(workspace.toString(), "RECENT", null, null, null, null, 20, null, exchange)
                    else -> semantic.authorizeMutation(exchange, workspace, MutationIntent.valueOf(gate))
                }
                true
            } catch (_: OwnerBoundaryReached) {
                true
            } catch (failure: Exception) {
                if (failure is io.mockk.MockKException) throw failure
                false
            }
        }
        suspend fun graphqlPublicPages(): Boolean {
            val assignments = mockk<AuthorizationAssignmentRepository>()
            every { assignments.actorIsOwnerAfterLock(any(), any()) } returns (role == WorkspaceRole.OWNER)
            val transactions = object : TransactionRunner {
                override suspend fun <T> run(action: () -> T): T = action()
                override suspend fun <T> readOnly(action: () -> T): T = action()
            }
            val query = mockk<WorkspaceQuery>()
            coEvery { query.find(any()) } returns cc.monomer.identity.domain.model.Workspace(
                cc.monomer.identity.domain.id.WorkspaceId(workspace.value),
                cc.monomer.identity.domain.id.OrganizationId(organization.value), "Workspace", clock.instant(), false,
                cc.monomer.identity.domain.model.ReportingTimeZone.UTC, null)
            val repository = mockk<WorkspaceRepository>()
            every { repository.updatePublicPages(any(), true) } throws OwnerBoundaryReached()
            val service = cc.monomer.identity.application.workspace.SetWorkspacePublicPagesService(
                OwnerWorkspaceMutationRunner(transactions, assignments), query, repository, mockk(), AuditEventFactory(clock))
            val resolver = GrantMutationsResolver(mockk(), mockk(), mockk(), mockk(), service,
                mockk(), mockk(), mockk())
            return try {
                mono { resolver.setWorkspacePublicPages(workspace.toString(), true) }
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)).awaitSingle()
                error("Expected owner mutation boundary")
            } catch (_: OwnerBoundaryReached) { true }
            catch (_: cc.monomer.identity.application.AccessDeniedException) { false }
        }
        suspend fun graphqlReportingTimeZone(organizationAdmin: Boolean): Boolean {
            val assignments = mockk<AuthorizationAssignmentRepository>()
            every { assignments.actorIsOrganizationAdminAfterLock(any(), any()) } returns organizationAdmin
            every { assignments.actorIsOwnerAfterLock(any(), any()) } returns (role == WorkspaceRole.OWNER)
            val transactions = object : TransactionRunner {
                override suspend fun <T> run(action: () -> T): T = action()
                override suspend fun <T> readOnly(action: () -> T): T = action()
            }
            val query = mockk<WorkspaceQuery>()
            coEvery { query.find(any()) } returns cc.monomer.identity.domain.model.Workspace(
                cc.monomer.identity.domain.id.WorkspaceId(workspace.value),
                cc.monomer.identity.domain.id.OrganizationId(organization.value), "Workspace", clock.instant(), false,
                cc.monomer.identity.domain.model.ReportingTimeZone.UTC, null)
            val repository = mockk<WorkspaceRepository>()
            every { repository.updateReportingTimeZone(any(), any()) } throws OwnerBoundaryReached()
            val service = cc.monomer.identity.application.workspace.SetWorkspaceReportingTimeZoneService(
                OwnerWorkspaceMutationRunner(transactions, assignments), query, repository, mockk(), AuditEventFactory(clock))
            val resolver = GrantMutationsResolver(mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), service)
            return try {
                mono { resolver.setWorkspaceReportingTimeZone(workspace.toString(), "Asia/Seoul") }
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)).awaitSingle()
                error("Expected Organization Admin or Owner mutation boundary")
            } catch (_: OwnerBoundaryReached) { true }
            catch (_: cc.monomer.identity.application.AccessDeniedException) { false }
        }
        suspend fun graphqlRoleChange(organizationAdmin: Boolean): Boolean {
            val assignments = mockk<AuthorizationAssignmentRepository>()
            every { assignments.actorIsOrganizationAdminAfterLock(any(), any()) } returns organizationAdmin
            every { assignments.actorIsOwnerAfterLock(any(), any()) } returns (role == WorkspaceRole.OWNER)
            every { assignments.isEligibleMemberRoleTarget(any(), any(), any()) } returns true
            every { assignments.setMemberRole(any(), any(), any()) } returns AuthorizationAssignmentTransition.NoTransition
            val transactions = object : TransactionRunner {
                override suspend fun <T> run(action: () -> T): T = action()
                override suspend fun <T> readOnly(action: () -> T): T = action()
            }
            val members = mockk<cc.monomer.identity.domain.port.WorkspaceMemberRepository>()
            every { members.insert(any()) } answers { cc.monomer.identity.domain.port.WorkspaceMemberInsertion.AlreadyMember(firstArg()) }
            val service = SetWorkspaceMemberRoleService(OwnerWorkspaceMutationRunner(transactions, assignments), assignments,
                mockk(), AuditEventFactory(clock), members, clock)
            val resolver = GrantMutationsResolver(mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
                service, mockk())
            return try {
                mono { resolver.setWorkspaceMemberRole(workspace.toString(), ID, "EXPLORER") }
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)).awaitSingle()
                verify(exactly = 1) { assignments.setMemberRole(any(), any(), WorkspaceRole.EXPLORER) }
                true
            } catch (_: cc.monomer.identity.application.AccessDeniedException) {
                verify(exactly = 0) { assignments.setMemberRole(any(), any(), any()) }
                false
            }
        }
    }
    private class OwnerBoundaryReached : RuntimeException()
    private companion object { const val ID = "01942b04-6e00-7000-8000-000000000010" }
}
