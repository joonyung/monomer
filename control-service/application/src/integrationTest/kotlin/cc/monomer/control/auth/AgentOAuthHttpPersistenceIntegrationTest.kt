package cc.monomer.control.auth

import cc.monomer.control.auth.inbound.PendingAuthenticationBrowser
import cc.monomer.identity.application.pending.PendingAuthenticationRequests
import cc.monomer.identity.domain.model.DeveloperAccessPolicy

import cc.monomer.agent.application.AgentResourceRouteRegistry
import cc.monomer.agent.application.AgentWorkspaceCallerAuthenticator
import cc.monomer.agent.application.AgentWorkspaceContractDecoder
import cc.monomer.control.*
import cc.monomer.control.agent.AgentWorkspaceApplicationComposition
import cc.monomer.control.auth.inbound.agentoauth.*
import cc.monomer.control.auth.inbound.oauth.JwtAuthenticationWebFilter
import cc.monomer.control.auth.inbound.session.*
import cc.monomer.identity.application.session.*
import cc.monomer.identity.outbound.jwt.*
import cc.monomer.control.mcp.*
import cc.monomer.control.publicapi.*
import cc.monomer.identity.api.*
import cc.monomer.identity.application.IdentityAdmissionService
import cc.monomer.identity.application.agent.*
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.UserCredentialProvenance
import cc.monomer.identity.outbound.oauth.HttpsOAuthClientResolver
import cc.monomer.identity.outbound.postgres.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.*
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.security.MessageDigest
import java.util.Base64
import javax.sql.DataSource
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.*
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class AgentOAuthHttpPersistenceIntegrationTest {
    private val jdbc get()=NamedParameterJdbcTemplate(database.dataSource)
    private val now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
    private val clock=Clock.fixed(now,ZoneOffset.UTC)
    private val user=UserId.generate()
    private val admin=UserId.generate()
    private val organization=OrganizationId.generate()
    private val workspace=WorkspaceId.generate()
    private val browserSession=SessionId.generate()
    private val keyPair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val keySource = JwtKeySource(keyPair.private as java.security.interfaces.RSAPrivateKey, keyPair.public as java.security.interfaces.RSAPublicKey, "test")
    private val jwtProperties = JwtProperties("monomer-auth", "monomer-internal", 900, 604800, "", "")
    private val issuer = JwtIssuer(keySource, jwtProperties, clock)
    private val browserCredential = PresentedCredential(issuer.issueBrowserSession(user, browserSession).serialized)
    private val browserRefresh = RefreshTokenFactory().generate()
    private val mcpAudience="http://127.0.0.1:11077/mcp"
    private val restAudience="http://127.0.0.1:11077/v2/workspaces"
    private val proof="a".repeat(43)
    private val challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(proof.toByteArray()))
    private val callback="http://127.0.0.1:49152/callback"
    private val mapper=ObjectMapper()
    private lateinit var transactions: SpringTransactionRunner
    private lateinit var oauthClient: WebTestClient
    private lateinit var mcpClient: WebTestClient
    private lateinit var restClient: WebTestClient
    private lateinit var refreshClient: WebTestClient

    @BeforeEach fun setup()=runBlocking<Unit> {
        database.dataSource.connection.use { connection ->
            val schema=checkNotNull(javaClass.classLoader.getResource("db/identity-runtime/schema.sql")).readText()
            repeat(2) { connection.createStatement().use { it.execute(schema) } }
            AuthSchemaVerifier.verifyContractFacts(connection)
        }
        val manager=DataSourceTransactionManager(database.dataSource)
        transactions=SpringTransactionRunner(TransactionTemplate(manager),TransactionTemplate(manager).apply { isReadOnly=true; isolationLevel=java.sql.Connection.TRANSACTION_REPEATABLE_READ }, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        buildClients(DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.OPEN, emptySet()))
        transactions.run {
            val params=mapOf("organization" to organization.value.toJavaUuid(),"workspace" to workspace.value.toJavaUuid(),"user" to user.value.toJavaUuid(),"admin" to admin.value.toJavaUuid(),"email" to "$user@example.test","adminEmail" to "$admin@example.test")
            jdbc.update("INSERT INTO identity_runtime.organizations(id,display_name) VALUES(:organization,'HTTP OAuth')",params)
            jdbc.update("INSERT INTO identity_runtime.principals(id,kind,display_name) VALUES(:user,'user','User'),(:admin,'user','Admin')",params)
            jdbc.update("INSERT INTO identity_runtime.users(id,primary_email) VALUES(:user,:email),(:admin,:adminEmail)",params)
            jdbc.update("INSERT INTO identity_runtime.organization_members(organization_id,user_id,role) VALUES(:organization,:user,'MEMBER'),(:organization,:admin,'ADMIN')",params)
            jdbc.update("INSERT INTO identity_runtime.workspaces(id,organization_id,display_name) VALUES(:workspace,:organization,'HTTP Workspace')",params)
            jdbc.update("INSERT INTO identity_runtime.workspace_members(workspace_id,user_id,role) VALUES(:workspace,:user,'EXPLORER'),(:workspace,:admin,'OWNER')",params)
            val login=Uuid.generateV7()
            val sessionParams=params+mapOf("login" to login.toJavaUuid(),"subject" to login.toString(),"session" to browserSession.value.toJavaUuid(),"now" to Timestamp.from(now),"expiry" to Timestamp.from(now.plusSeconds(3600)))
            jdbc.update("INSERT INTO identity_runtime.login_identities(id,user_id,protocol,namespace,subject,linked_via,linked_at) VALUES(:login,:user,'oidc','https://accounts.google.com',:subject,'signup',:now)",sessionParams)
            jdbc.update("""INSERT INTO identity_runtime.sessions(id,user_id,login_identity_id,protocol,authentication_source_ref,authentication_issuer,asserted_email,asserted_email_verified,email_evidence,created_at,last_refreshed_at,absolute_expires_at)
                VALUES(:session,:user,:login,'oidc','builtin/google','https://accounts.google.com',:email,true,'authoritative',:now,:now,:expiry)""",sessionParams)
            jdbc.update("INSERT INTO identity_runtime.refresh_tokens(id,session_id,token_hash,issued_at,expires_at) VALUES(:id,:session,:hash,:now,:expiry)",
                sessionParams + mapOf("id" to Uuid.generateV7().toJavaUuid(), "hash" to browserRefresh.hash))
        }
    }

    private fun buildClients(policy: DeveloperAccessPolicy) {
        val browser=UserCredentialProvenance.BrowserSession(user,browserSession)
        val upstreamVerifier = JwtVerifier(keySource, jwtProperties, clock)
        val audiences=mapOf(ExpectedOAuthResource.AGENT_REST to restAudience,ExpectedOAuthResource.AGENT_MCP to mcpAudience)
        val oauth=AgentOAuthService(PostgresAgentOAuthRepository(jdbc,upstreamVerifier, developerAccessPolicy = policy),transactions,
            HttpsOAuthClientResolver(mapOf("monomer-native" to OAuthClient("monomer-native","Monomer CLI",setOf("http://127.0.0.1/callback")))),clock,audiences)
        val identity=IdentityAdmissionService(PostgresCanonicalIdentityAdmissionSnapshot(jdbc,transactions,upstreamVerifier,AgentOAuthTokenHasher(),clock,audiences, developerAccessPolicy = policy))
        val facade=ControlAdmissionFacade(identity,clock)
        val admission=AgentWorkspaceApplicationComposition().agentWorkspaceAdmissionPort(facade, mockk(), mockk(), mockk())
        val root=AgentResourceRouteRegistry(mockk(),mockk(),mockk(),mockk(),mockk(),null,emptyList(),mockk(), managementOwnerPort = null)
        val ports=AgentWorkspaceHttpPorts(AgentWorkspaceCallerAuthenticator(admission),root,AgentWorkspaceContractDecoder())
        mcpClient=WebTestClient.bindToRouterFunction(McpRouter(ports,oauth,
            McpTransportProperties("http://127.0.0.1:11077/.well-known/oauth-protected-resource/mcp",setOf("http://127.0.0.1:3100")), cc.monomer.control.mcp.MonomerDocsReader("http://127.0.0.1:1"), managementOwnerPort = io.mockk.mockk(), publicApiProperties = cc.monomer.control.publicapi.ControlPublicApiProperties(java.net.URI("http://127.0.0.1:3100")), dashboardViewHtml = "<!doctype html><title>Fixture view</title>", clock = java.time.Clock.systemUTC()).routes()).configureClient().codecs { it.defaultCodecs().maxInMemorySize(4 * 1024 * 1024) }.build()
        val runtime=mockk<ControlRuntimeProperties>()
        every { runtime.security } returns ControlSecurityProperties("local",restAudience,"local","local","local",true)
        val pending = PendingAuthenticationRequests(PostgresPendingAuthenticationRepository(jdbc), transactions, clock)
        oauthClient=WebTestClient.bindToController(AgentOAuthController(oauth,AgentOAuthProperties("http://127.0.0.1:11077",mcpAudience),runtime,
            ControlPublicApiProperties(URI("http://127.0.0.1:3100")),clock,pending,PendingAuthenticationBrowser(pending, RefreshCookieProperties(false))))
            .controllerAdvice(AgentOAuthProblemHandler())
            .webFilter<WebTestClient.ControllerSpec>(JwtAuthenticationWebFilter(upstreamVerifier, policy, PostgresSessionRepository(jdbc), transactions)).build()
        val restRouter = AgentWorkspacePublicApiRouter(
            ports, "http://127.0.0.1:11077/.well-known/oauth-protected-resource/v2/workspaces",
            ControlPublicApiProperties(URI("http://127.0.0.1:3100")),
            object : AgentWorkspaceTransportCredentialResolver {
                override suspend fun resolve(request: org.springframework.web.reactive.function.server.ServerRequest): AgentWorkspaceTransportCredentialResolution =
                    request.headers().firstHeader("Authorization")?.removePrefix("Bearer ")?.let { AgentWorkspaceTransportCredentialResolution.Resolved(PresentedCredential(it)) }
                        ?: AgentWorkspaceTransportCredentialResolution.Missing
            }, PublicControlContractValidator(), oauth,
        )
        restClient = WebTestClient.bindToRouterFunction(restRouter.routes().and(restRouter.organizationRoutes(oauth, mockk()))).build()
        val sessions = PostgresSessionRepository(jdbc)
        refreshClient = WebTestClient.bindToController(RefreshTokenController(
            RefreshSessionUseCase(transactions, sessions, RefreshTokenFactory(), issuer, JwtSessionLifetimePolicy(jwtProperties), clock, policy),
            LogoutUseCase(transactions, sessions, RefreshTokenFactory(), clock),
            LogoutAllSessionsUseCase(transactions, sessions, RefreshTokenFactory(), clock),
            RefreshCookieFactory(AuthJwtProperties("monomer-auth", "monomer-internal", 900, 604800, "", ""), RefreshCookieProperties(false)),
        )).build()
    }

    @Test fun `reloaded developer policy rejects previously issued browser and Agent credentials over HTTP`() {
        buildClients(DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.RESTRICTED, setOf("$user@example.test")))
        val mcp = exchange(authorize(mcpAudience), mcpAudience)
        val rest = exchange(authorize(restAudience), restAudience)
        val pendingMcpCode = authorize(mcpAudience)
        val pendingRestCode = authorize(restAudience)
        call(mcp.path("access_token").asText(), "monomer_list", """{"path":"workspaces"}""").expectStatus().isOk
        restCall(rest.path("access_token").asText()).expectStatus().isOk
        oauthClient.get().uri("/oauth/connections").headers { it.setBearerAuth(browserCredential.value) }.exchange().expectStatus().isOk
        val refreshed = refreshClient.post().uri("/auth/refresh").cookie("refresh_token", browserRefresh.raw).exchange().expectStatus().isOk.expectBody().returnResult()
        val oldRefresh = checkNotNull(refreshed.responseCookies.getFirst("refresh_token")).value

        buildClients(DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.RESTRICTED, emptySet()))
        oauthClient.get().uri("/oauth/connections").headers { it.setBearerAuth(browserCredential.value) }.exchange().expectStatus().isUnauthorized
        refreshClient.post().uri("/auth/refresh").cookie("refresh_token", oldRefresh).exchange().expectStatus().isUnauthorized
        call(mcp.path("access_token").asText(), "monomer_list", """{"path":"workspaces"}""").expectStatus().isUnauthorized
            .expectHeader().valueMatches("WWW-Authenticate", "Bearer .*resource_metadata.*")
        call(mcp.path("access_token").asText(), "monomer_list", """{"workspaceId":"$workspace","path":"workspace","limit":20}""").expectStatus().isUnauthorized
        restCall(rest.path("access_token").asText()).expectStatus().isUnauthorized
            .expectHeader().valueMatches("WWW-Authenticate", "Bearer .*resource_metadata.*")
        listOf(mcpAudience to mcp, restAudience to rest).forEach { (resource, token) ->
            form("/oauth/token", mapOf("grant_type" to "refresh_token", "client_id" to "monomer-native", "resource" to resource, "refresh_token" to token.path("refresh_token").asText()))
                .expectStatus().isBadRequest.expectBody().jsonPath("$.error").isEqualTo("invalid_grant")
        }
        tokenRequest(pendingMcpCode, mcpAudience, proof).expectStatus().isBadRequest.expectBody().jsonPath("$.error").isEqualTo("invalid_grant")
        tokenRequest(pendingRestCode, restAudience, proof).expectStatus().isBadRequest.expectBody().jsonPath("$.error").isEqualTo("invalid_grant")
        oauthClient.get().uri("/.well-known/oauth-authorization-server").exchange().expectStatus().isOk.expectBody().jsonPath("$.issuer").isEqualTo("http://127.0.0.1:11077")
        oauthClient.get().uri("/.well-known/oauth-protected-resource/mcp").exchange().expectStatus().isOk.expectBody().jsonPath("$.resource").isEqualTo(mcpAudience)
        mcpClient.post().uri("/mcp").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
            .bodyValue("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").exchange().expectStatus().isUnauthorized.expectHeader().exists(HttpHeaders.WWW_AUTHENTICATE)
    }

    @Test fun `Agent developer admission uses latest Google evidence while browser logout stays independent`() = runBlocking<Unit> {
        buildClients(DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.RESTRICTED, setOf("$user@example.test")))
        val token = exchange(authorize(mcpAudience), mcpAudience).path("access_token").asText()
        transactions.run {
            jdbc.update("UPDATE identity_runtime.sessions SET revoked_at=:now, revocation_reason='logout' WHERE id=:id", mapOf("now" to Timestamp.from(now), "id" to browserSession.value.toJavaUuid()))
        }
        call(token, "monomer_list", """{"path":"workspaces"}""").expectStatus().isOk
        val repository = PostgresSessionRepository(jdbc)
        transactions.run {
            val previous = checkNotNull(repository.findById(browserSession))
            repository.insert(previous.copy(id = SessionId.generate(), assertedEmail = "changed@example.test", createdAt = now.plusSeconds(1), lastRefreshedAt = now.plusSeconds(1), revokedAt = null, revocationReason = null))
        }
        call(token, "monomer_list", """{"path":"workspaces"}""").expectStatus().isUnauthorized
    }

    @Test fun `logout all revokes every browser refresh family of the user only and preserves Agent grants`() = runBlocking<Unit> {
        val agent = exchange(authorize(mcpAudience), mcpAudience)
        val secondRefresh = insertBrowserSession(user)
        val adminRefresh = insertBrowserSession(admin)

        val loggedOut = refreshClient.post().uri("/auth/logout-all").cookie("refresh_token", browserRefresh.raw)
            .exchange().expectStatus().isNoContent.returnResult(Void::class.java)
        assertEquals(java.time.Duration.ZERO, checkNotNull(loggedOut.responseCookies.getFirst("refresh_token")).maxAge)

        for (revoked in listOf(browserRefresh.raw, secondRefresh)) {
            refreshClient.post().uri("/auth/refresh").cookie("refresh_token", revoked).exchange().expectStatus().isUnauthorized
        }
        oauthClient.get().uri("/oauth/connections").headers { it.setBearerAuth(browserCredential.value) }.exchange().expectStatus().isUnauthorized
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM identity_runtime.sessions WHERE user_id=:user AND revoked_at IS NULL",
            mapOf("user" to user.value.toJavaUuid()), Int::class.java))
        assertEquals(setOf("user_wide"), jdbc.queryForList("SELECT DISTINCT revocation_reason FROM identity_runtime.sessions WHERE user_id=:user",
            mapOf("user" to user.value.toJavaUuid()), String::class.java).toSet())
        refreshClient.post().uri("/auth/refresh").cookie("refresh_token", adminRefresh).exchange().expectStatus().isOk

        call(agent.path("access_token").asText(), "monomer_list", """{"path":"workspaces"}""").expectStatus().isOk
        form("/oauth/token", mapOf("grant_type" to "refresh_token", "client_id" to "monomer-native", "resource" to mcpAudience,
            "refresh_token" to agent.path("refresh_token").asText())).expectStatus().isOk
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM identity_runtime.agent_oauth_grants WHERE owner_user_id=:user AND revoked_at IS NOT NULL",
            mapOf("user" to user.value.toJavaUuid()), Int::class.java))

        refreshClient.post().uri("/auth/logout-all").cookie("refresh_token", browserRefresh.raw).exchange().expectStatus().isUnauthorized
        refreshClient.post().uri("/auth/logout-all").exchange().expectStatus().isUnauthorized
    }

    private suspend fun insertBrowserSession(owner: UserId): String {
        val refresh = RefreshTokenFactory().generate()
        val login = Uuid.generateV7()
        transactions.run {
            val values = mapOf("login" to login.toJavaUuid(), "user" to owner.value.toJavaUuid(), "subject" to login.toString(),
                "session" to Uuid.generateV7().toJavaUuid(), "email" to "$owner@example.test", "now" to Timestamp.from(now),
                "expiry" to Timestamp.from(now.plusSeconds(3600)), "token" to Uuid.generateV7().toJavaUuid(), "hash" to refresh.hash)
            jdbc.update("INSERT INTO identity_runtime.login_identities(id,user_id,protocol,namespace,subject,linked_via,linked_at) VALUES(:login,:user,'oidc','https://accounts.google.com',:subject,'signup',:now)", values)
            jdbc.update("""INSERT INTO identity_runtime.sessions(id,user_id,login_identity_id,protocol,authentication_source_ref,authentication_issuer,asserted_email,asserted_email_verified,email_evidence,created_at,last_refreshed_at,absolute_expires_at)
                VALUES(:session,:user,:login,'oidc','builtin/google','https://accounts.google.com',:email,true,'authoritative',:now,:now,:expiry)""", values)
            jdbc.update("INSERT INTO identity_runtime.refresh_tokens(id,session_id,token_hash,issued_at,expires_at) VALUES(:token,:session,:hash,:now,:expiry)", values)
        }
        return refresh.raw
    }

    private fun restCall(token: String) = restClient.post().uri("/v2/workspaces/$workspace/agent/list")
        .headers { it.setBearerAuth(token) }.contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""{"path":"workspace","limit":20}""").exchange()

    @Test fun `HTTP consent tokens MCP discovery and real root admission share current Identity`()=runBlocking<Unit> {
        val token=exchange(authorize(mcpAudience),mcpAudience)
        val access=token.path("access_token").asText()
        call(access,"monomer_list","""{"path":"workspaces"}""").expectStatus().isOk.expectBody()
            .jsonPath("$.result.structuredContent.workspaces[0].workspaceId").isEqualTo(workspace.toString())
        call(access,"monomer_list","""{"workspaceId":"$workspace","path":"workspace","limit":20}""")
            .expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(false).jsonPath("$.result.structuredContent.items").isArray
        val rest=exchange(authorize(restAudience),restAudience)
        call(rest.path("access_token").asText(),"monomer_list","""{"path":"workspaces"}""").expectStatus().isUnauthorized
        transactions.run { jdbc.update("DELETE FROM identity_runtime.workspace_members WHERE workspace_id=:workspace AND user_id=:user",params()) }
        call(access,"monomer_list","""{"path":"workspaces"}""").expectStatus().isOk.expectBody().jsonPath("$.result.structuredContent.workspaces.length()").isEqualTo(0)
        call(access,"monomer_list","""{"workspaceId":"$workspace","path":"workspace","limit":20}""")
            .expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(true)
        transactions.run { jdbc.update("DELETE FROM identity_runtime.organization_members WHERE organization_id=:organization AND user_id=:user",params()) }
        call(access,"monomer_list","""{"path":"workspaces"}""").expectStatus().isOk.expectBody().jsonPath("$.result.structuredContent.workspaces.length()").isEqualTo(0)
        form("/oauth/token",mapOf("grant_type" to "refresh_token","client_id" to "monomer-native","resource" to mcpAudience,"refresh_token" to token.path("refresh_token").asText()))
            .expectStatus().isOk
    }

    @Test fun `one user grant discovers future Organizations and admits both Workspaces with current membership`() = runBlocking<Unit> {
        val mcpToken = exchange(authorize(mcpAudience), mcpAudience)
        val access = mcpToken.path("access_token").asText()
        val restAccess = exchange(authorize(restAudience), restAudience).path("access_token").asText()
        val secondOrganization = OrganizationId.generate()
        val secondWorkspace = WorkspaceId.generate()
        val readySample = WorkspaceId.generate()
        val provisioningSample = WorkspaceId.generate()
        transactions.run {
            val values = params() + mapOf("secondOrganization" to secondOrganization.value.toJavaUuid(), "secondWorkspace" to secondWorkspace.value.toJavaUuid(), "admin" to admin.value.toJavaUuid(),
                "readySample" to readySample.value.toJavaUuid(), "provisioningSample" to provisioningSample.value.toJavaUuid())
            jdbc.update("INSERT INTO identity_runtime.organizations(id,display_name) VALUES(:secondOrganization,'Second team')", values)
            jdbc.update("INSERT INTO identity_runtime.organization_members(organization_id,user_id,role) VALUES(:secondOrganization,:user,'MEMBER'),(:secondOrganization,:admin,'ADMIN')", values)
            jdbc.update("INSERT INTO identity_runtime.workspaces(id,organization_id,display_name) VALUES(:secondWorkspace,:secondOrganization,'Future workspace')", values)
            jdbc.update("""INSERT INTO identity_runtime.workspaces(id,organization_id,display_name,sample_dataset,sample_state,sample_initiator)
                VALUES(:readySample,:secondOrganization,'Acme Cloud (sample)','acme-cloud@3','READY',:admin),(:provisioningSample,:organization,'Acme Cloud (sample)','acme-cloud@3','PROVISIONING',:admin)""", values)
            jdbc.update("INSERT INTO identity_runtime.workspace_members(workspace_id,user_id,role) VALUES(:secondWorkspace,:user,'EXPLORER'),(:secondWorkspace,:admin,'OWNER'),(:readySample,:user,'EXPLORER'),(:readySample,:admin,'OWNER'),(:provisioningSample,:user,'EXPLORER'),(:provisioningSample,:admin,'OWNER')", values)
        }
        val organizations = json(call(access, "monomer_list", """{"path":"organizations"}""").expectStatus().isOk).at("/result/structuredContent/organizations")
        assertEquals(setOf(organization.toString(), secondOrganization.toString()), organizations.map { it.path("organizationId").asText() }.toSet())
        val workspaces = json(restClient.post().uri("/v2/agent/list").headers { it.setBearerAuth(restAccess) }.contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"path":"workspaces"}""").exchange().expectStatus().isOk.expectHeader().valueEquals("Cache-Control", "no-store")).path("workspaces")
        assertEquals(setOf(workspace.toString(), secondWorkspace.toString(), readySample.toString()), workspaces.map { it.path("workspaceId").asText() }.toSet())
        assertEquals("Second team", workspaces.single { it.path("workspaceId").asText() == secondWorkspace.toString() }.path("organizationDisplayName").asText())
        assertTrue(workspaces.filter { it.path("workspaceId").asText() != readySample.toString() }.all { it.has("sample") && it.path("sample").isNull })
        assertEquals("acme-cloud@3", workspaces.single { it.path("workspaceId").asText() == readySample.toString() }.at("/sample/dataset").asText())
        val mcpWorkspaces = json(call(access, "monomer_list", """{"path":"workspaces"}""").expectStatus().isOk).at("/result/structuredContent/workspaces")
        assertEquals(workspaces, mcpWorkspaces)
        for (target in listOf(workspace, secondWorkspace)) {
            call(access, "monomer_list", """{"workspaceId":"$target","path":"workspace","limit":20}""").expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(false)
        }
        transactions.run { jdbc.update("DELETE FROM identity_runtime.organization_members WHERE organization_id=:organization AND user_id=:user", params()) }
        call(access, "monomer_list", """{"workspaceId":"$workspace","path":"workspace","limit":20}""").expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(true)
        call(access, "monomer_list", """{"workspaceId":"$secondWorkspace","path":"workspace","limit":20}""").expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(false)
        call(access, "monomer_list", """{"path":"organizations"}""").expectStatus().isOk.expectBody().jsonPath("$.result.structuredContent.organizations.length()").isEqualTo(1)
        form("/oauth/token", mapOf("grant_type" to "refresh_token", "client_id" to "monomer-native", "resource" to mcpAudience, "refresh_token" to mcpToken.path("refresh_token").asText())).expectStatus().isOk
    }

    @Test fun `HTTP code binding replay and refresh rotation revoke actual persisted tokens`() {
        val code=authorize(mcpAudience)
        tokenRequest(code,mcpAudience,"b".repeat(43)).expectStatus().isBadRequest.expectBody().jsonPath("$.error").isEqualTo("invalid_grant")
        val token=exchange(code,mcpAudience)
        tokenRequest(code,mcpAudience,proof).expectStatus().isBadRequest
        call(token.path("access_token").asText(),"monomer_list","""{"path":"workspaces"}""").expectStatus().isUnauthorized
        val first=exchange(authorize(mcpAudience),mcpAudience)
        val refreshBody=mapOf("grant_type" to "refresh_token","client_id" to "monomer-native","resource" to mcpAudience,"refresh_token" to first.path("refresh_token").asText())
        val second=json(form("/oauth/token",refreshBody).expectStatus().isOk)
        assertNotEquals(first.path("refresh_token").asText(),second.path("refresh_token").asText())
        form("/oauth/token",refreshBody).expectStatus().isBadRequest
        call(second.path("access_token").asText(),"monomer_list","""{"path":"workspaces"}""").expectStatus().isUnauthorized
    }

    @Test fun `pending consent crosses independent controllers and concurrent approval creates one grant`() {
        val first = oauthClient
        buildClients(DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.OPEN, emptySet()))
        val second = oauthClient
        val started = first.get().uri { uri -> uri.path("/oauth/authorize")
            .queryParam("response_type", "code").queryParam("client_id", "monomer-native")
            .queryParam("redirect_uri", callback).queryParam("resource", mcpAudience)
            .queryParam("scope", "agent.workspace").queryParam("code_challenge", challenge)
            .queryParam("code_challenge_method", "S256").queryParam("state", "cross-instance").build()
        }.exchange().expectStatus().isFound.expectBody().returnResult()
        val cookie = checkNotNull(started.responseCookies.getFirst("monomer_auth_pending_local"))
        val requestId = checkNotNull(started.responseHeaders.location).rawQuery.substringAfter("request=")
        second.get().uri("/oauth/consent?request=$requestId").headers { it.setBearerAuth(browserCredential.value) }
            .exchange().expectStatus().isBadRequest
        val view = json(second.get().uri("/oauth/consent?request=$requestId").cookie(cookie.name, cookie.value)
            .headers { it.setBearerAuth(browserCredential.value) }.exchange().expectStatus().isOk)
        val body = mapOf("request" to requestId, "csrfToken" to view.path("csrfToken").asText(), "approve" to true)
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val results = listOf(first, second).map { instance -> executor.submit<Int> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS)
                instance.post().uri("/oauth/consent").cookie(cookie.name, cookie.value)
                    .headers { it.setBearerAuth(browserCredential.value) }.header("Origin", "http://127.0.0.1:3100")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(mapper.writeValueAsString(body)).exchange()
                    .returnResult(String::class.java).status.value()
            } }.map { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(listOf(200, 400), results.sorted())
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM identity_runtime.agent_oauth_grants WHERE owner_user_id=:user",
                mapOf("user" to user.value.toJavaUuid()), Int::class.java))
        } finally { executor.shutdownNow() }
    }

    private fun authorize(resource: String): String {
        val started=oauthClient.get().uri { uri -> uri.path("/oauth/authorize").queryParam("response_type","code").queryParam("client_id","monomer-native")
            .queryParam("redirect_uri",callback).queryParam("resource",resource).queryParam("scope","agent.workspace").queryParam("code_challenge",challenge)
            .queryParam("code_challenge_method","S256").queryParam("state","bound-state").build() }.exchange().expectStatus().isFound.expectBody().returnResult()
        val cookie=checkNotNull(started.responseCookies.getFirst("monomer_auth_pending_local"))
        val requestId=checkNotNull(started.responseHeaders.location).rawQuery.substringAfter("request=")
        val view=json(oauthClient.get().uri("/oauth/consent?request=$requestId").cookie(cookie.name,cookie.value).headers { it.setBearerAuth(browserCredential.value) }.exchange().expectStatus().isOk)
        val submission=mapOf("request" to requestId,"csrfToken" to view.path("csrfToken").asText(),"approve" to true)
        val result=json(oauthClient.post().uri("/oauth/consent").cookie(cookie.name,cookie.value).headers { it.setBearerAuth(browserCredential.value) }
            .header("Origin","http://127.0.0.1:3100").contentType(MediaType.APPLICATION_JSON).bodyValue(mapper.writeValueAsString(submission)).exchange().expectStatus().isOk)
        val query=URI(result.path("redirectUri").asText()).rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='),Charsets.UTF_8) }
        assertEquals("bound-state",query["state"])
        assertEquals("http://127.0.0.1:11077",query["iss"])
        return checkNotNull(query["code"])
    }
    private fun exchange(code: String,resource: String)=json(tokenRequest(code,resource,proof).expectStatus().isOk.expectHeader().valueEquals("Cache-Control","no-store"))
    private fun tokenRequest(code: String,resource: String,verifier: String)=form("/oauth/token",mapOf("grant_type" to "authorization_code","client_id" to "monomer-native","redirect_uri" to callback,"resource" to resource,"code" to code,"code_verifier" to verifier))
    private fun form(path: String,body: Map<String,String>)=oauthClient.post().uri(path).contentType(MediaType.APPLICATION_FORM_URLENCODED).bodyValue(body.entries.joinToString("&") { (key,value) -> "$key=${URLEncoder.encode(value,Charsets.UTF_8)}" }).exchange()
    private fun json(response: WebTestClient.ResponseSpec): JsonNode=mapper.readTree(response.expectBody(String::class.java).returnResult().responseBody!!)
    private fun call(token: String,name: String,arguments: String)=mcpClient.post().uri("/mcp").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON,MediaType.TEXT_EVENT_STREAM)
        .header("MCP-Protocol-Version","2025-11-25").headers { it.setBearerAuth(token) }.bodyValue("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}""").exchange()
    private fun params()=mapOf("organization" to organization.value.toJavaUuid(),"user" to user.value.toJavaUuid(),"workspace" to workspace.value.toJavaUuid())
    companion object { @JvmField @RegisterExtension val database=OAuthHttpPostgres() }
}

class OAuthHttpPostgres : BeforeAllCallback,AfterAllCallback {
    private val container=PostgreSQLContainer(DockerImageName.parse("postgres:17.10-alpine3.24@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("oauth_http_gate_ephemeral").withUsername("oauth_http_gate").withPassword("oauth_http_gate_test_only")
    lateinit var dataSource: DataSource
        private set
    override fun beforeAll(context: ExtensionContext) {
        try {
            container.start()
            check(container.isRunning && container.databaseName=="oauth_http_gate_ephemeral")
            dataSource=PGSimpleDataSource().apply { setURL(container.jdbcUrl); user=container.username; password=container.password }
            dataSource.connection.use { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("SELECT current_database(),current_user").use { result ->
                    check(result.next() && result.getString(1)=="oauth_http_gate_ephemeral" && result.getString(2)=="oauth_http_gate")
                }
            } }
        } catch(failure: Throwable) { runCatching { container.stop() }; throw failure }
    }
    override fun afterAll(context: ExtensionContext) { container.stop(); check(!container.isRunning) }
}
