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
import cc.monomer.pages.adapter.outbound.s3.pagecontent.PageContentObjectStorageConfiguration
import cc.monomer.pages.adapter.outbound.s3.pagecontent.S3PageContentObjectStorage
import cc.monomer.pages.application.*
import cc.monomer.pages.application.content.*
import cc.monomer.pages.domain.PageId
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.coEvery
import io.mockk.mockk
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter
import cc.monomer.pages.adapter.outbound.postgres.pages.PostgresPageActivityReader
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.reactive.function.server.RouterFunctions
import reactor.netty.http.server.HttpServer
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.CreateBucketRequest

@EnabledIfEnvironmentVariable(named = "MONOMER_MCP_INTEROP_PYTHON", matches = ".+")
class PagesAgentHttpPersistenceIntegrationTest {
    private val workspace = WorkspaceId(Uuid.parse("01942b04-6e00-7000-8000-000000000001"))
    private val organization = OrganizationId(Uuid.parse("01942b04-6e00-7000-8000-000000000002"))
    private val principal = CanonicalPrincipal(PrincipalId(Uuid.parse("01942b04-6e00-7000-8000-000000000003")), PrincipalKind.USER)
    private val credential = PresentedCredential("pages-mcp-fixture")

    @Test
    fun `official MCP client uploads file and shares durable Pages receipts with REST`() = runBlocking {
        val controlRoot = Path.of(System.getProperty("control.service.root"))
        database.dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(Files.readString(controlRoot.resolve("modules/pages/src/main/resources/schema-postgres.sql"))) }
        }
        val minio = GenericContainer(DockerImageName.parse(MINIO_IMAGE))
            .withEnv("MINIO_ROOT_USER", "pages-integration")
            .withEnv("MINIO_ROOT_PASSWORD", "pages-integration-only")
            .withCommand("server", "/data", "--address", ":9000")
            .withExposedPorts(9000).waitingFor(Wait.forHttp("/minio/health/live").forPort(9000))
        minio.start()
        try {
            val endpoint = URI("http://127.0.0.1:${minio.getMappedPort(9000)}")
            val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("pages-integration", "pages-integration-only"))
            S3AsyncClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1).credentialsProvider(credentials).forcePathStyle(true).build().use { client ->
                client.createBucket(CreateBucketRequest.builder().bucket("pages-integration").build()).await()
            }
            S3PageContentObjectStorage.create(PageContentObjectStorageConfiguration(endpoint, "us-east-1", "pages-integration", "content"), credentials).use { storage ->
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
                val uploads = PageContentUploadUseCaseImpl(contentRepository, contentRepository, transactions, storage)
                val admission = ControlAdmissionFacade(identityFixture(), Clock.systemUTC())
                val dashboard = mockk<DashboardOwnerPort>()
                val currentPages = PagesCurrentReader(directory, PageContentViewerUseCaseImpl(contentRepository, storage))
                val snapshot = cc.monomer.identity.outbound.postgres.SpringConsistentSnapshotTransactionRunner(
                    TransactionTemplate(manager).apply { isolationLevel = org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ },
                    blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
                )
                val reader = cc.monomer.control.agent.AgentPageReader(cc.monomer.control.pages.publicpages.PageReaderService(
                    cc.monomer.control.pagereader.PublicPagesAccess(mockk(), admission, snapshot),
                    currentPages, dashboard, mockk(), mockk(), mockk(), Clock.systemUTC(),
                ))
                val pages = AgentPagesOwnerAdapter(directory, content, uploads, dashboard, admission, Clock.systemUTC(), reader, cc.monomer.control.agent.fixedAgentProvenanceNames())
                val router = AgentResourceRouteRegistry(
                    exploreOwnerPort = mockk(), connectorOwnerPort = mockk(), catalogDiscoveryPort = mockk(),
                    semanticOwnerPort = mockk(), dashboardOwnerPort = mockk(), pagesOwnerPort = pages,
                    workspaceCollections = listOf(AgentResourcePath.parse("workspace/pages")), searchPort = mockk(), managementOwnerPort = null
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
                val server = HttpServer.create().host("127.0.0.1").port(0).handle(ReactorHttpHandlerAdapter(RouterFunctions.toHttpHandler(mcp.routes().and(rest.routes())))).bindNow(Duration.ofSeconds(10))
                val output = Files.createTempFile("pages-mcp-integration-", ".log")
                try {
                    val process = ProcessBuilder(System.getenv("MONOMER_MCP_INTEROP_PYTHON"), controlRoot.resolve("../testkit/agent-oauth-client/verify_pages_mcp.py").normalize().toString(), "http://127.0.0.1:${server.port()}")
                        .redirectErrorStream(true).redirectOutput(output.toFile()).start()
                    if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Pages MCP client exceeded 90 seconds") }
                    val log = Files.readString(output)
                    assertEquals(0, process.exitValue(), log)
                    val report = jacksonObjectMapper().readTree(log.lineSequence().last { it.startsWith("{") })
                    assertEquals("PASS", report.path("result").asText())
                    val pageId = PageId(Uuid.parse(report.path("documentPath").asText().substringAfterLast('/')))
                    val viewer = PageContentViewerUseCaseImpl(contentRepository, storage)
                    val query = PageContentViewerUseCase.Query(workspace.value, pageId, 1)
                    assertEquals("Latest report", assertIs<PageContentViewOutcome.Found>(viewer.view(query)).document.title)
                    val attachment = assertIs<PageContentAttachmentOutcome.Found>(viewer.attachment(query)).attachment
                    val bytes = attachment.content.toList().fold(ByteArray(0)) { acc, buffer -> val chunk = ByteArray(buffer.remaining()); buffer.get(chunk); acc + chunk }
                    assertEquals(report.path("source").asText(), bytes.decodeToString())
                    assertEquals(2, jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM pages_relocation.page_versions", Int::class.java))
                    assertEquals(8, jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM pages_relocation.audit_events", Int::class.java))
                    assertTrue(jdbc.jdbcTemplate.queryForList("SELECT payload->>'actorPrincipalId' AS principal FROM pages_relocation.audit_events").all { it["principal"] == principal.id.toString() })
                    assertEquals(listOf("AGENT"), jdbc.jdbcTemplate.queryForList("SELECT DISTINCT finalized_by_kind FROM pages_relocation.page_versions", String::class.java))
                    assertEquals(listOf("AGENT"), jdbc.jdbcTemplate.queryForList("SELECT DISTINCT created_by_kind FROM pages_relocation.pages", String::class.java))
                    val activity = PostgresPageActivityReader(jdbc, transactions)
                    assertTrue(activity.readRecent(workspace.value, null, null, null, "AGENT", null, 10).isNotEmpty())
                    assertTrue(activity.readRecent(workspace.value, null, null, null, "USER", null, 10).isEmpty())
                    println("Pages REST/MCP persistence and multipart round trip: PASS (fixture Identity admission; real Pages/PostgreSQL/MinIO)")
                } finally { server.disposeNow(Duration.ofSeconds(10)); Files.deleteIfExists(output) }
            }
        } finally { minio.stop() }
    }

    private fun identityFixture(): IdentityAdmissionPort {
        val identity = mockk<IdentityAdmissionPort>()
        coEvery { identity.decide(any<IdentityMembershipAdmissionRequest>()) } answers {
            val request = firstArg<IdentityMembershipAdmissionRequest>()
            if (request.workspaceId != workspace || request.credential != credential) IdentityMembershipAdmissionDecision.Denied(IdentityAdmissionDenial.WORKSPACE_UNAVAILABLE)
            else IdentityMembershipAdmissionDecision.Authorized(IdentityMembershipEvidence(principal, organization, workspace, AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), WorkspaceRole.EXPLORER))
        }
        coEvery { identity.decide(any<IdentityRoleAdmissionRequest>()) } answers {
            val request = firstArg<IdentityRoleAdmissionRequest>()
            if (request.workspaceId != workspace || request.credential != credential || !WorkspaceRole.EXPLORER.includes(request.requiredRole)) IdentityRoleAdmissionDecision.Denied(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE)
            else IdentityRoleAdmissionDecision.Authorized(IdentityRoleEvidence(OrganizationPlan.FREE, principal, organization, workspace, AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), WorkspaceRole.EXPLORER))
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
        private const val MINIO_IMAGE = "minio/minio:RELEASE.2024-08-17T01-24-54Z@sha256:6f23072e3e222e64fe6f86b31a7f7aca971e5129e55cbccef649b109b8e651a1"
    }
}
