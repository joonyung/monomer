@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.pages

import cc.monomer.control.pagereader.*

import cc.monomer.agent.api.*
import cc.monomer.agent.application.AgentResourceRouteRegistry
import cc.monomer.agent.application.AgentWorkspaceCallerAuthenticator
import cc.monomer.agent.application.AgentWorkspaceContractDecoder
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.EphemeralPostgresAuthoritySupport
import cc.monomer.control.agent.AgentPagesOwnerAdapter
import cc.monomer.control.agent.AgentWorkspaceApplicationComposition
import cc.monomer.control.mcp.*
import cc.monomer.control.publicapi.*
import cc.monomer.dashboard.api.DashboardOwnerPort
import cc.monomer.identity.api.*
import cc.monomer.pages.adapter.outbound.postgres.SpringTransactionRunner
import cc.monomer.pages.adapter.outbound.postgres.audit.PostgresPagesAuditRepository
import cc.monomer.pages.adapter.outbound.postgres.pages.PostgresPagesDirectoryRepository
import cc.monomer.pages.adapter.outbound.postgres.pages.content.PostgresPageContentRepository
import cc.monomer.pages.domain.*
import cc.monomer.pages.domain.audit.AuditActorContext
import cc.monomer.pages.application.*
import cc.monomer.pages.application.content.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.coEvery
import io.mockk.mockk
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

class PagesViewerHttpPersistenceIntegrationTest {
    private val workspace = WorkspaceId(Uuid.generateV7())
    private val organization = OrganizationId(Uuid.generateV7())
    private val principal = CanonicalPrincipal(PrincipalId(Uuid.generateV7()), PrincipalKind.USER)
    private val credential = PresentedCredential("pages-mcp-fixture")

    @Test
    fun `Viewer REST and MCP read and search hide unplaced deleted and missing Pages with real PostgreSQL snapshots`() {
        val controlRoot = Path.of(System.getProperty("control.service.root"))
        database.dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(Files.readString(controlRoot.resolve("modules/pages/src/main/resources/schema-postgres.sql"))) }
        }
        val jdbc = NamedParameterJdbcTemplate(database.dataSource)
        val manager = DataSourceTransactionManager(database.dataSource)
        val transactions = SpringTransactionRunner(TransactionTemplate(manager), TransactionTemplate(manager).apply { isReadOnly = true }, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        val directoryRepository = PostgresPagesDirectoryRepository(jdbc, transactions)
        val contentRepository = PostgresPageContentRepository(jdbc, transactions)
        val audit = PostgresPagesAuditRepository(jdbc, jacksonObjectMapper())
        val events = object : PagesDirectoryEventBus {
            override fun publish(wakeup: PagesDirectoryWakeup) = Unit
            override fun subscribe(workspaceId: Uuid) = emptyFlow<PagesDirectoryWakeup>()
        }
        val directory = PagesDirectoryUseCaseImpl(directoryRepository, directoryRepository, transactions, events, audit, contentRepository, contentRepository, cc.monomer.pages.application.PagesDashboardReferencePort { _, _ -> false })
        val content = PageContentUseCaseImpl(contentRepository, contentRepository, transactions, audit, events)
        val uploads = mockk<PageContentUploadUseCase>()
        val admission = ControlAdmissionFacade(identityFixture(), Clock.systemUTC())
        val dashboard = mockk<DashboardOwnerPort>()
        val placed = Uuid.generateV7()
        val unplaced = Uuid.generateV7()
        val persistedPages = PagesCurrentReader(directory, mockk())
        // The owner forbids durable unplaced Pages; remove one placement only at the reader projection seam.
        val currentPages = object : cc.monomer.pages.api.PagesCurrentReadPort by persistedPages {
            override suspend fun tree(workspaceId: Uuid): cc.monomer.pages.api.CurrentPagesTree =
                persistedPages.tree(workspaceId).let { tree ->
                    tree.copy(placements = tree.placements.filterNot { it.pageId == unplaced })
                }
        }
        val snapshot = cc.monomer.identity.outbound.postgres.SpringConsistentSnapshotTransactionRunner(
            TransactionTemplate(manager).apply { isolationLevel = org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ },
            blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        val publicPages = mockk<WorkspacePublicPagesQuery>()
        coEvery { publicPages.pageReaderName(workspace) } returns "Reports"
        val pageReader = cc.monomer.control.pages.publicpages.PageReaderService(
            cc.monomer.control.pagereader.PublicPagesAccess(mockk(), admission, snapshot),
            currentPages, dashboard, mockk(), mockk(), publicPages, Clock.systemUTC(),
        )
        val reader = cc.monomer.control.agent.AgentPageReader(pageReader)
        val pages = AgentPagesOwnerAdapter(directory, content, uploads, dashboard, admission, Clock.systemUTC(), reader, cc.monomer.control.agent.fixedAgentProvenanceNames())
        val router = AgentResourceRouteRegistry(
            exploreOwnerPort = mockk(), connectorOwnerPort = mockk(), catalogDiscoveryPort = mockk(),
            semanticOwnerPort = mockk(), dashboardOwnerPort = mockk(), pagesOwnerPort = pages,
            workspaceCollections = listOf(AgentResourcePath.parse("workspace/pages")),
            searchPort = cc.monomer.control.search.SearchApplicationComposition().agentSearchPort(
                mockk(), snapshot, admission, mockk(), mockk(), mockk(), dashboard, pages,
            ), managementOwnerPort = null
        )
        val composition = AgentWorkspaceApplicationComposition()
        val ports = AgentWorkspaceHttpPorts(AgentWorkspaceCallerAuthenticator(composition.agentWorkspaceAdmissionPort(admission, mockk(), mockk(), mockk())), router, AgentWorkspaceContractDecoder())
        val oauth = mockk<AgentOAuthPort>()
        coEvery { oauth.validateCredential(credential, ExpectedOAuthResource.AGENT_MCP) } returns OAuthOutcome.Success(OAuthAgentIdentity(principal.id, clientId = "https://client.example/metadata.json"))
        coEvery { oauth.validateCredential(credential, ExpectedOAuthResource.AGENT_REST) } returns OAuthOutcome.Success(OAuthAgentIdentity(principal.id, clientId = "https://client.example/metadata.json"))
        val mcp = McpRouter(ports, oauth, McpTransportProperties("http://127.0.0.1/.well-known/oauth-protected-resource/mcp", emptySet()), MonomerDocsReader("http://127.0.0.1"), managementOwnerPort = io.mockk.mockk(), publicApiProperties = cc.monomer.control.publicapi.ControlPublicApiProperties(java.net.URI("http://127.0.0.1:3100")), dashboardViewHtml = "<!doctype html><title>Fixture view</title>", clock = java.time.Clock.systemUTC())
        val rest = AgentWorkspacePublicApiRouter(ports, "http://127.0.0.1/.well-known/oauth-protected-resource/v2/workspaces", ControlPublicApiProperties(URI("http://127.0.0.1")),
            object : AgentWorkspaceTransportCredentialResolver {
                override suspend fun resolve(request: org.springframework.web.reactive.function.server.ServerRequest) =
                    if (request.headers().firstHeader("Authorization") == "Bearer pages-mcp-fixture") AgentWorkspaceTransportCredentialResolution.Resolved(credential)
                    else AgentWorkspaceTransportCredentialResolution.Missing
            }, PublicControlContractValidator(), oauth)
        val deleted = Uuid.generateV7()
        val missing = Uuid.generateV7()
        jdbc.jdbcTemplate.update(
            "INSERT INTO pages_relocation.page_directories VALUES (?::uuid, ?::jsonb, 1, 0, 'USER', ?)",
            workspace.toString(), """[{"pageId":"$placed","children":[]},{"pageId":"$unplaced","children":[]}]""", principal.id.toString(),
        )
        for (page in listOf(placed, unplaced, deleted)) {
            jdbc.jdbcTemplate.update(
                """INSERT INTO pages_relocation.pages
                (id, workspace_id, kind, title, created_at, created_by_kind, created_by_id, updated_at, updated_by_kind, updated_by_id)
                VALUES (?::uuid, ?::uuid, 'FOLDER', ?, 0, 'USER', ?, 0, 'USER', ?)""",
                page.toString(), workspace.toString(), "Reports $page", principal.id.toString(), principal.id.toString(),
            )
        }
        jdbc.jdbcTemplate.update("DELETE FROM pages_relocation.pages WHERE id = ?::uuid", deleted.toString())
        kotlinx.coroutines.runBlocking {
            assertEquals(2, directory.getProjection(workspace.value).snapshot.pages.size)
            val failure = kotlin.test.assertFailsWith<PageReaderProblem> {
                pageReader.page(cc.monomer.control.pages.publicpages.PageReaderCaller(workspace, credential, ExpectedOAuthResource.AGENT_REST, principal.id.toString(), null), unplaced.toString())
            }
            assertEquals(PageReaderFailure.NotFound, failure.failure)
        }
        val client = WebTestClient.bindToRouterFunction(mcp.routes().and(rest.routes())).build()
        val mapper = jacksonObjectMapper()
        fun restRead(path: String) = client.post().uri("/v2/workspaces/$workspace/agent/read")
            .contentType(MediaType.APPLICATION_JSON).headers { it.setBearerAuth("pages-mcp-fixture") }
            .bodyValue("""{"path":"$path"}""").exchange()
        fun mcpRead(path: String) = client.post().uri("/mcp")
            .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
            .header("MCP-Protocol-Version", "2025-11-25").headers { it.setBearerAuth("pages-mcp-fixture") }
            .bodyValue("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"monomer_read","arguments":{"workspaceId":"$workspace","path":"$path"}}}""").exchange()
        for (page in listOf(unplaced, deleted, missing)) {
            val path = "workspace/pages/$page"
            restRead(path).expectStatus().isNotFound.expectBody()
                .jsonPath("$.code").isEqualTo("NOT_FOUND").jsonPath("$.retryable").isEqualTo(false)
            val bytes = mcpRead(path).expectStatus().isOk.expectBody()
                .jsonPath("$.result.isError").isEqualTo(true).returnResult().responseBody!!
            val problem = mapper.readTree(mapper.readTree(bytes).at("/result/content/0/text").asText())
            assertEquals(404, problem.path("status").asInt())
            assertEquals("NOT_FOUND", problem.path("code").asText())
            assertEquals(false, problem.path("retryable").asBoolean())
        }
        for (path in listOf("workspace/pages/tree", "workspace/pages/$placed")) {
            restRead(path).expectStatus().isOk.expectBody().jsonPath("$.path").isEqualTo(path)
            mcpRead(path).expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(false)
                .jsonPath("$.result.structuredContent.path").isEqualTo(path)
        }
        fun searchBody(scope: String) = """{"path":"$scope","queries":["Reports"],"kinds":["PAGE"],"limitPerQuery":20}"""
        fun assertSearch(expectedPaths: List<String>) {
            for (scope in listOf("workspace", "workspace/pages")) {
                val restBytes = client.post().uri("/v2/workspaces/$workspace/agent/search")
                    .contentType(MediaType.APPLICATION_JSON).headers { it.setBearerAuth("pages-mcp-fixture") }
                    .bodyValue(searchBody(scope)).exchange().expectStatus().isOk.expectBody().returnResult().responseBody!!
                val restItems = mapper.readTree(restBytes).at("/results/0/items")
                assertEquals(expectedPaths, restItems.map { it.path("path").asText() })
                val mcpBytes = client.post().uri("/mcp")
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                    .header("MCP-Protocol-Version", "2025-11-25").headers { it.setBearerAuth("pages-mcp-fixture") }
                    .bodyValue("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"monomer_search","arguments":{"workspaceId":"$workspace",${searchBody(scope).drop(1).dropLast(1)}}}}""")
                    .exchange().expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(false).returnResult().responseBody!!
                val mcpItems = mapper.readTree(mcpBytes).at("/result/structuredContent/results/0/items")
                assertEquals(restItems, mcpItems)
            }
        }
        assertSearch(listOf("workspace/pages/$placed"))
        kotlinx.coroutines.runBlocking {
            kotlin.test.assertIs<PagesDirectoryMutationOutcome.Applied>(directory.deletePage(PagesDirectoryUseCase.DeletePageCommand(
                workspace.value, PageId(placed), PagesDirectoryRevision(1), PageDirectoryRequestId("delete-search-page"),
                PageActor.create(PageActorKind.USER, principal.id.toString()), AuditActorContext(organization.value, principal.id.value, "pages-search-integration"),
                Clock.systemUTC().instant(),
            )))
        }
        assertSearch(emptyList())

    }

    private fun identityFixture(): IdentityAdmissionPort {
        val identity = mockk<IdentityAdmissionPort>()
        coEvery { identity.decide(any<IdentityMembershipAdmissionRequest>()) } answers {
            val request = firstArg<IdentityMembershipAdmissionRequest>()
            if (request.workspaceId != workspace || request.credential != credential) IdentityMembershipAdmissionDecision.Denied(IdentityAdmissionDenial.WORKSPACE_UNAVAILABLE)
            else IdentityMembershipAdmissionDecision.Authorized(IdentityMembershipEvidence(principal, organization, workspace, AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), WorkspaceRole.VIEWER))
        }
        coEvery { identity.decide(any<IdentityRoleAdmissionRequest>()) } answers {
            val request = firstArg<IdentityRoleAdmissionRequest>()
            if (request.workspaceId != workspace || request.credential != credential || !WorkspaceRole.VIEWER.includes(request.requiredRole)) IdentityRoleAdmissionDecision.Denied(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE)
            else IdentityRoleAdmissionDecision.Authorized(IdentityRoleEvidence(OrganizationPlan.FREE, principal, organization, workspace, AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), WorkspaceRole.VIEWER))
        }
        coEvery { identity.decide(any<IdentityCredentialAdmissionRequest>()) } answers {
            val request = firstArg<IdentityCredentialAdmissionRequest>()
            if (request.workspaceId != workspace || request.credential != credential) IdentityCredentialAdmissionDecision.Denied(IdentityAdmissionDenial.CREDENTIAL_UNAVAILABLE)
            else IdentityCredentialAdmissionDecision.Authorized(IdentityCredentialEvidence(principal, CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata")))
        }
        return identity
    }

    companion object {
        @JvmField @RegisterExtension val database = EphemeralPostgresAuthoritySupport
    }
}
