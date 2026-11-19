package cc.monomer.control.auth

import cc.monomer.control.auth.inbound.PendingAuthenticationBrowser
import cc.monomer.identity.application.pending.PendingAuthenticationRequests
import cc.monomer.identity.domain.model.DeveloperAccessPolicy

import cc.monomer.control.*
import cc.monomer.control.auth.inbound.agentoauth.*
import cc.monomer.control.auth.inbound.oauth.JwtAuthenticationWebFilter
import cc.monomer.control.auth.inbound.session.*
import cc.monomer.identity.application.session.*
import cc.monomer.identity.outbound.jwt.*
import cc.monomer.control.mcp.*
import cc.monomer.control.publicapi.*
import cc.monomer.identity.api.*
import cc.monomer.identity.application.agent.*
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
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
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.*
import org.springframework.http.MediaType
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.transaction.support.TransactionTemplate

import cc.monomer.control.auth.inbound.operationsoauth.*
import cc.monomer.identity.application.operations.*
import cc.monomer.identity.application.me.MeService

class OperationsOAuthHttpPersistenceIntegrationTest {
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
    private val callback="http://127.0.0.1:49152/oauth/callback"
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
        oauthClient = client(setOf(user.toString()))
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

    private fun client(allowed: Set<String>): WebTestClient {
        val verifier = JwtVerifier(keySource,jwtProperties,clock)
        val policy = DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.OPEN,emptySet())
        val oauth = OperationsOAuthService(PostgresOperationsOAuthRepository(jdbc,PostgresAgentOAuthRepository(jdbc,verifier,policy)),transactions,clock,allowed,resource)
        val pending = PendingAuthenticationRequests(PostgresPendingAuthenticationRepository(jdbc),transactions,clock)
        val me = MeService(PostgresUserQuery(jdbc,transactions),PostgresOrganizationQuery(jdbc,transactions))
        return WebTestClient.bindToController(OperationsOAuthController(oauth,pending,PendingAuthenticationBrowser(pending,RefreshCookieProperties(false)),
            ControlPublicApiProperties(URI("http://127.0.0.1:3100")),AgentOAuthProperties("http://127.0.0.1:11077",mcpAudience),me))
            .controllerAdvice(OperationsOAuthProblemHandler())
            .webFilter<WebTestClient.ControllerSpec>(JwtAuthenticationWebFilter(verifier,policy,PostgresSessionRepository(jdbc),transactions)).build()
    }
    private val resource = "http://127.0.0.1:11077/ops/v1"

    @Test fun `real browser JWT consent crosses replicas and ops credential is revoked across replicas`() {
        val first = oauthClient
        val second = client(setOf(user.toString()))
        val denied = client(emptySet())
        val started = first.get().uri { uri -> uri.path("/ops/oauth/authorize").queryParam("response_type","code")
            .queryParam("client_id",OperationsOAuthService.CLIENT_ID).queryParam("redirect_uri",callback).queryParam("resource",resource)
            .queryParam("scope","ops.read").queryParam("code_challenge",challenge).queryParam("code_challenge_method","S256")
            .queryParam("state","s".repeat(43)).build() }.exchange().expectStatus().isFound.expectBody().returnResult()
        val cookie = checkNotNull(started.responseCookies.getFirst("monomer_auth_pending_local"))
        val reference = checkNotNull(started.responseHeaders.location).rawQuery.substringAfter("request=")
        denied.get().uri("/ops/oauth/consent?request=$reference").cookie(cookie.name,cookie.value)
            .headers { it.setBearerAuth(browserCredential.value) }.exchange().expectStatus().isForbidden
        second.get().uri("/ops/oauth/consent?request=$reference").cookie(cookie.name,cookie.value)
            .header("Authorization","Bearer ${browserCredential.value}","Bearer ${browserCredential.value}").exchange().expectStatus().isUnauthorized
        second.get().uri("/ops/oauth/consent?request=$reference").headers { it.setBearerAuth(browserCredential.value) }.exchange().expectStatus().isBadRequest
        val view = json(second.get().uri("/ops/oauth/consent?request=$reference").cookie(cookie.name,cookie.value)
            .headers { it.setBearerAuth(browserCredential.value) }.exchange().expectStatus().isOk)
        val body = mapOf("request" to reference,"csrfToken" to view.path("csrfToken").asText(),"approve" to true)
        second.post().uri("/ops/oauth/consent").cookie(cookie.name,cookie.value).headers { it.setBearerAuth(browserCredential.value) }
            .header("Origin","https://attacker.test").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isForbidden
        val approved = json(second.post().uri("/ops/oauth/consent").cookie(cookie.name,cookie.value).headers { it.setBearerAuth(browserCredential.value) }
            .header("Origin","http://127.0.0.1:3100").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk)
        val query = URI(approved.path("redirectUri").asText()).rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='),Charsets.UTF_8) }
        assertEquals("s".repeat(43),query["state"])
        assertEquals("http://127.0.0.1:11077",query["iss"])
        val code = checkNotNull(query["code"])
        fun exchange(instance: WebTestClient, verifier: String) = form(instance,"/ops/oauth/token",mapOf("grant_type" to "authorization_code","client_id" to OperationsOAuthService.CLIENT_ID,"redirect_uri" to callback,"resource" to resource,"code" to code,"code_verifier" to verifier))
        exchange(first,"b".repeat(43)).expectStatus().isBadRequest
        val token = json(exchange(first,proof).expectStatus().isOk)
        assertEquals(3600,token.path("expires_in").asInt())
        assertFalse(token.has("refresh_token"))
        exchange(second,proof).expectStatus().isBadRequest
        val bearer = token.path("access_token").asText()
        second.get().uri("/ops/v1/me").headers { it.setBearerAuth(bearer) }.exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.userId").isEqualTo(user.toString()).jsonPath("$.displayName").isEqualTo("User").jsonPath("$.expiresAt").isEqualTo(now.plusSeconds(3600).toString())
        second.get().uri("/ops/v1/me").header("Authorization","Bearer $bearer","Bearer $bearer").exchange().expectStatus().isUnauthorized
        second.get().uri("/ops/v1/me").headers { it.setBearerAuth(browserCredential.value) }.exchange().expectStatus().isUnauthorized
        denied.get().uri("/ops/v1/me").headers { it.setBearerAuth(bearer) }.exchange().expectStatus().isForbidden
        form(second,"/ops/oauth/revoke",mapOf("client_id" to OperationsOAuthService.CLIENT_ID,"token" to bearer)).expectStatus().isOk
        first.get().uri("/ops/v1/me").headers { it.setBearerAuth(bearer) }.exchange().expectStatus().isUnauthorized
        runBlocking { transactions.run { jdbc.update("UPDATE identity_runtime.sessions SET revoked_at=:now,revocation_reason='logout' WHERE id=:id",mapOf("now" to Timestamp.from(now),"id" to browserSession.value.toJavaUuid())) } }
        second.get().uri("/ops/oauth/consent?request=$reference").cookie(cookie.name,cookie.value).headers { it.setBearerAuth(browserCredential.value) }
            .exchange().expectStatus().isUnauthorized
    }
    private fun form(client: WebTestClient,path: String,body: Map<String,String>)=client.post().uri(path).contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .bodyValue(body.entries.joinToString("&") { (key,value) -> "$key=${URLEncoder.encode(value,Charsets.UTF_8)}" }).exchange()
    private fun json(response: WebTestClient.ResponseSpec): JsonNode=mapper.readTree(response.expectBody(String::class.java).returnResult().responseBody!!)
    companion object { @JvmField @RegisterExtension val database=OAuthHttpPostgres() }
}
