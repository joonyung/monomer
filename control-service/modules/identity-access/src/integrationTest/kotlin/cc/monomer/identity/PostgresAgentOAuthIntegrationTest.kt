package cc.monomer.identity

import cc.monomer.identity.domain.model.DeveloperAccessPolicy

import cc.monomer.identity.api.*
import cc.monomer.identity.application.agent.*
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.UserCredentialProvenance
import cc.monomer.identity.domain.port.CurrentUserCredentialVerifier
import cc.monomer.identity.domain.port.VerifiedCurrentUserCredential
import cc.monomer.identity.outbound.oauth.HttpsOAuthClientResolver
import cc.monomer.identity.outbound.postgres.*
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.security.MessageDigest
import java.util.Base64
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

class PostgresAgentOAuthIntegrationTest {
    private val jdbc get() = NamedParameterJdbcTemplate(TargetIdentityPostgres.dataSource)
    private val initial = Instant.parse("2026-09-07T00:00:00Z")
    private var now = initial
    private val clock = object : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private val user = Uuid.generateV7()
    private val admin = Uuid.generateV7()
    private val organization = OrganizationId.generate()
    private val session = SessionId.generate()
    private val otherSession = SessionId.generate()
    private val browser = PresentedCredential("browser-fixture")
    private val otherBrowser = PresentedCredential("other-browser-fixture")
    private val audience = "https://api.example/mcp"
    private val verifier = "a".repeat(43)
    private val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
    private val request = OAuthAuthorizationRequest("monomer-native","http://127.0.0.1:49152/callback",audience,setOf("agent.workspace"),challenge,"S256")
    private lateinit var service: AgentOAuthService
    private lateinit var credentialVerifier: CurrentUserCredentialVerifier
    private lateinit var transactions: SpringTransactionRunner

    @BeforeEach fun setup() = runBlocking<Unit> {
        TargetIdentityPostgres.dataSource.connection.use {
            TargetIdentityPostgres.applyCanonicalSchemaTwice(it,javaClass.classLoader)
            AuthSchemaVerifier.verifyContractFacts(it)
        }
        val manager = DataSourceTransactionManager(TargetIdentityPostgres.dataSource)
        transactions = SpringTransactionRunner(TransactionTemplate(manager),TransactionTemplate(manager).apply { isReadOnly=true; isolationLevel=java.sql.Connection.TRANSACTION_REPEATABLE_READ }, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        credentialVerifier = object : CurrentUserCredentialVerifier {
            override fun verifyCurrentCredential(serializedToken: String): VerifiedCurrentUserCredential? = when(serializedToken) {
                browser.value -> VerifiedCurrentUserCredential(UserCredentialProvenance.BrowserSession(UserId(user),session),now.plusSeconds(3600))
                otherBrowser.value -> VerifiedCurrentUserCredential(UserCredentialProvenance.BrowserSession(UserId(admin),otherSession),now.plusSeconds(3600))
                else -> null
            }
        }
        service = AgentOAuthService(PostgresAgentOAuthRepository(jdbc,credentialVerifier, developerAccessPolicy = DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.OPEN, emptySet())),transactions,
            HttpsOAuthClientResolver(mapOf("monomer-native" to OAuthClient("monomer-native","CLI",setOf("http://127.0.0.1/callback")))),clock,
            mapOf(ExpectedOAuthResource.AGENT_REST to "https://api.example/agent",ExpectedOAuthResource.AGENT_MCP to audience))
        transactions.run {
            val params=mapOf("organization" to organization.value.toJavaUuid(),"user" to user.toJavaUuid(),"admin" to admin.toJavaUuid(),"now" to Timestamp.from(now),"email" to "$user@example.test","adminEmail" to "$admin@example.test")
            jdbc.update("INSERT INTO identity_runtime.organizations(id,display_name) VALUES(:organization,'OAuth Test')",params)
            jdbc.update("INSERT INTO identity_runtime.principals(id,kind,display_name) VALUES(:user,'user','User'),(:admin,'user','Admin')",params)
            jdbc.update("INSERT INTO identity_runtime.users(id,primary_email) VALUES(:user,:email),(:admin,:adminEmail)",params)
            jdbc.update("INSERT INTO identity_runtime.organization_members(organization_id,user_id,role) VALUES(:organization,:user,'MEMBER'),(:organization,:admin,'ADMIN')",params)
            insertSession(user,session)
            insertSession(admin,otherSession)
        }
    }
    @Test fun `code is single use bound to client redirect PKCE and resource`() = runBlocking<Unit> {
        val code=authorize()
        assertFailure(service.exchangeCode(exchange(code,client="wrong")))
        assertFailure(service.exchangeCode(exchange(code,resource="https://api.example/agent")))
        assertFailure(service.exchangeCode(exchange(code,redirect="http://127.0.0.1:49153/callback")))
        assertFailure(service.exchangeCode(exchange(code,proof="b".repeat(43))))
        val responses=coroutineScope { listOf(async { service.exchangeCode(exchange(code)) },async { service.exchangeCode(exchange(code)) }).map { it.await() } }
        assertEquals(1,responses.count { it is OAuthOutcome.Success })
        assertEquals(1,responses.count { it is OAuthOutcome.Failure })
        val token=(responses.filterIsInstance<OAuthOutcome.Success<OAuthTokenResponse>>().single()).value
        assertFailure(service.validateCredential(PresentedCredential(token.accessToken),ExpectedOAuthResource.AGENT_MCP))
        val fresh = tokens()
        assertIs<OAuthOutcome.Success<*>>(service.validateCredential(PresentedCredential(fresh.accessToken),ExpectedOAuthResource.AGENT_MCP))
        assertFailure(service.validateCredential(PresentedCredential(fresh.accessToken),ExpectedOAuthResource.AGENT_REST))
        assertFalse(token.toString().contains(token.accessToken))
    }
    @Test fun `refresh reuse revokes rotated access and grant with no grace`() = runBlocking<Unit> {
        val first=tokens()
        val second=success(service.refresh(OAuthRefreshExchange(request.clientId,audience,PresentedCredential(first.refreshToken))))
        assertNotEquals(first.refreshToken,second.refreshToken)
        assertFailure(service.refresh(OAuthRefreshExchange(request.clientId,audience,PresentedCredential(first.refreshToken))))
        assertFailure(service.validateCredential(PresentedCredential(second.accessToken),ExpectedOAuthResource.AGENT_MCP))
        assertFailure(service.refresh(OAuthRefreshExchange(request.clientId,audience,PresentedCredential(second.refreshToken))))
    }
    @Test fun `expiry is fixed to approval and code expires at five minutes`() = runBlocking<Unit> {
        val code=authorize()
        now=initial.plusSeconds(300)
        assertFailure(service.exchangeCode(exchange(code)))
        now=initial
        val first=tokens()
        now=initial.plusSeconds(30L*86400-1)
        val last=success(service.refresh(OAuthRefreshExchange(request.clientId,audience,PresentedCredential(first.refreshToken))))
        assertEquals(1,last.expiresIn)
        now=initial.plusSeconds(30L*86400)
        assertFailure(service.refresh(OAuthRefreshExchange(request.clientId,audience,PresentedCredential(last.refreshToken))))
        assertFailure(service.validateCredential(PresentedCredential(last.accessToken),ExpectedOAuthResource.AGENT_MCP))
    }
    @Test fun `own connection revoke is immediate while Organization withdrawal preserves connection`() = runBlocking<Unit> {
        val first=tokens()
        val connection=success(service.listConnections(browser, 100, null)).connections.single()
        assertTrue(success(service.listConnections(otherBrowser, 100, null)).connections.isEmpty())
        success(service.revokeConnection(otherBrowser,connection.grantId))
        assertIs<OAuthOutcome.Success<*>>(service.validateCredential(PresentedCredential(first.accessToken),ExpectedOAuthResource.AGENT_MCP))
        success(service.revokeConnection(browser,connection.grantId))
        assertFailure(service.validateCredential(PresentedCredential(first.accessToken),ExpectedOAuthResource.AGENT_MCP))
        val second=tokens()
        transactions.run { jdbc.update("DELETE FROM identity_runtime.organization_members WHERE organization_id=:organization AND user_id=:user",mapOf("organization" to organization.value.toJavaUuid(),"user" to user.toJavaUuid())) }
        success(service.refresh(OAuthRefreshExchange(request.clientId,audience,PresentedCredential(second.refreshToken))))
        success(service.validateCredential(PresentedCredential(second.accessToken),ExpectedOAuthResource.AGENT_MCP))
        assertTrue(success(service.listOrganizations(PresentedCredential(second.accessToken),ExpectedOAuthResource.AGENT_MCP)).isEmpty())
        success(service.authorize(OAuthConsentCommand(browser,request)))
    }
    @Test fun `connection pages are bounded stable owner-bound and retain ended grants`() = runBlocking<Unit> {
        val expected = (1..103).map { Uuid.generateV7() }.sortedDescending()
        transactions.run {
            expected.forEachIndexed { index, id ->
                val issuedAt = if (index < 102) initial else initial.minusSeconds(31L * 86400)
                jdbc.update("""INSERT INTO identity_runtime.agent_oauth_grants(id,owner_user_id,oauth_client_id,client_label,resource_audience,scopes,issued_at,revoked_at)
                    VALUES(:id,:user,'monomer-native','CLI',:audience,ARRAY['agent.workspace'],:issued,:revoked)""",
                    mapOf("id" to id.toJavaUuid(), "user" to user.toJavaUuid(), "audience" to audience,
                        "issued" to Timestamp.from(issuedAt), "revoked" to if (index == 100) Timestamp.from(initial) else null))
            }
            jdbc.update("""INSERT INTO identity_runtime.agent_oauth_grants(id,owner_user_id,oauth_client_id,resource_audience,scopes,issued_at)
                VALUES(:id,:user,'monomer-native',:audience,ARRAY['agent.workspace'],:issued)""",
                mapOf("id" to Uuid.generateV7().toJavaUuid(), "user" to admin.toJavaUuid(), "audience" to audience, "issued" to Timestamp.from(initial)))
        }
        val first = success(service.listConnections(browser, 100, null))
        assertEquals(expected.take(100), first.connections.map { it.grantId })
        val cursor = assertNotNull(first.nextCursor)
        assertEquals(OAuthOutcome.Failure(OAuthError.INVALID_REQUEST), service.listConnections(otherBrowser, 100, cursor))
        val encodedInvalidInstant = Base64.getUrlEncoder().withoutPadding().encodeToString("1|$user|bad-time|${expected[99]}".toByteArray())
        val wrongVersion = Base64.getUrlEncoder().withoutPadding().encodeToString("2|$user|$initial|${expected[99]}".toByteArray())
        val expandedYearCursors = listOf(Instant.MIN.toString(), Instant.MAX.toString(), "+10000-01-01T00:00:00Z").map {
            Base64.getUrlEncoder().withoutPadding().encodeToString("1|$user|$it|${expected[99]}".toByteArray())
        }
        (listOf("", "not-a-cursor", cursor + "=", "a".repeat(513), encodedInvalidInstant, wrongVersion) + expandedYearCursors).forEach {
            assertEquals(OAuthOutcome.Failure(OAuthError.INVALID_REQUEST), service.listConnections(browser, 100, it))
        }
        listOf(0, 101).forEach { assertEquals(OAuthOutcome.Failure(OAuthError.INVALID_REQUEST), service.listConnections(browser, it, null)) }
        assertEquals(OAuthOutcome.Failure(OAuthError.ACCESS_DENIED), service.listConnections(PresentedCredential("unknown"), 100, cursor))
        transactions.run { insertGrant(AgentClientId("monomer-native"), "New", initial.plusSeconds(1), revoked = false) }
        val last = success(service.listConnections(browser, 100, cursor))
        assertEquals(expected.drop(100), last.connections.map { it.grantId })
        assertNull(last.nextCursor)
        assertNotNull(last.connections.first().revokedAt)
        assertTrue(last.connections.last().expiresAt < initial)
        assertEquals(OAuthConnectionSummary(102, initial.plusSeconds(1)), success(service.connectionSummary(browser, "monomer-native")))
        assertEquals(OAuthConnectionSummary(0, null), success(service.connectionSummary(browser, "other-client")))
        assertEquals(OAuthConnectionSummary(1, initial), success(service.connectionSummary(otherBrowser, "monomer-native")))
        assertEquals(OAuthOutcome.Failure(OAuthError.ACCESS_DENIED), service.connectionSummary(PresentedCredential("unknown"), "monomer-native"))
        val small = success(service.listConnections(browser, 1, null))
        assertEquals(1, small.connections.size)
        assertNotNull(small.nextCursor)
    }

    @Test fun `authenticated requests record last use at most hourly and failures never do`() = runBlocking<Unit> {
        val token=tokens()
        val credential=PresentedCredential(token.accessToken)
        assertNull(success(service.listConnections(browser, 100, null)).connections.single().lastUsedAt)
        assertFailure(service.validateCredential(credential,ExpectedOAuthResource.AGENT_REST))
        assertFailure(service.validateCredential(PresentedCredential("unknown"),ExpectedOAuthResource.AGENT_MCP))
        assertNull(success(service.listConnections(browser, 100, null)).connections.single().lastUsedAt)
        success(service.validateCredential(credential,ExpectedOAuthResource.AGENT_MCP))
        assertEquals(initial,success(service.listConnections(browser, 100, null)).connections.single().lastUsedAt)
        now=initial.plusSeconds(600)
        success(service.listWorkspaces(credential,ExpectedOAuthResource.AGENT_MCP))
        assertEquals(initial,success(service.listConnections(browser, 100, null)).connections.single().lastUsedAt)
        now=initial.plusSeconds(3600)
        val refreshed=success(service.refresh(OAuthRefreshExchange(request.clientId,audience,PresentedCredential(token.refreshToken))))
        assertEquals(initial,success(service.listConnections(browser, 100, null)).connections.single().lastUsedAt)
        success(service.listOrganizations(PresentedCredential(refreshed.accessToken),ExpectedOAuthResource.AGENT_MCP))
        assertEquals(now,success(service.listConnections(browser, 100, null)).connections.single().lastUsedAt)
        val used=now
        now=initial.plusSeconds(3*3600)
        success(service.revokeConnection(browser,success(service.listConnections(browser, 100, null)).connections.single().grantId))
        assertFailure(service.validateCredential(PresentedCredential(refreshed.accessToken),ExpectedOAuthResource.AGENT_MCP))
        assertEquals(used,success(service.listConnections(browser, 100, null)).connections.single().lastUsedAt)
    }
    @Test fun `a failing use record never fails authentication`() = runBlocking<Unit> {
        val token=tokens()
        val rejectingUse=object : org.springframework.jdbc.datasource.DelegatingDataSource(TargetIdentityPostgres.dataSource) {
            override fun getConnection(): java.sql.Connection {
                val connection=super.getConnection()
                return java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.Connection::class.java)) { _, method, arguments ->
                    if (method.name=="prepareStatement" && (arguments?.firstOrNull() as? String)?.contains("SET last_used_at")==true) throw java.sql.SQLException("use rejected","57014")
                    try { method.invoke(connection,*(arguments ?: emptyArray())) } catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
                } as java.sql.Connection
            }
        }
        val manager=DataSourceTransactionManager(rejectingUse)
        val rejectingTransactions=SpringTransactionRunner(TransactionTemplate(manager),TransactionTemplate(manager).apply { isReadOnly=true; isolationLevel=java.sql.Connection.TRANSACTION_REPEATABLE_READ }, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        val rejecting=AgentOAuthService(PostgresAgentOAuthRepository(NamedParameterJdbcTemplate(rejectingUse),credentialVerifier, developerAccessPolicy = DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.OPEN, emptySet())),rejectingTransactions,
            HttpsOAuthClientResolver(mapOf("monomer-native" to OAuthClient("monomer-native","CLI",setOf("http://127.0.0.1/callback")))),clock,
            mapOf(ExpectedOAuthResource.AGENT_REST to "https://api.example/agent",ExpectedOAuthResource.AGENT_MCP to audience))
        success(rejecting.validateCredential(PresentedCredential(token.accessToken),ExpectedOAuthResource.AGENT_MCP))
        assertTrue(success(rejecting.listWorkspaces(PresentedCredential(token.accessToken),ExpectedOAuthResource.AGENT_MCP)).isEmpty())
        assertNull(success(service.listConnections(browser, 100, null)).connections.single().lastUsedAt)
        success(service.validateCredential(PresentedCredential(token.accessToken),ExpectedOAuthResource.AGENT_MCP))
        assertEquals(initial,success(service.listConnections(browser, 100, null)).connections.single().lastUsedAt)
    }
    @Test fun `token revocation checks client binding and unknown token is silent`() = runBlocking<Unit> {
        val token=tokens()
        service.revokeToken("other",PresentedCredential(token.refreshToken))
        assertIs<OAuthOutcome.Success<*>>(service.validateCredential(PresentedCredential(token.accessToken),ExpectedOAuthResource.AGENT_MCP))
        service.revokeToken(request.clientId,PresentedCredential("unknown"))
        service.revokeToken(request.clientId,PresentedCredential(token.accessToken))
        assertFailure(service.refresh(OAuthRefreshExchange(request.clientId,audience,PresentedCredential(token.refreshToken))))
    }
    @Test fun `concurrent refresh has one issuance and replay revokes that issuance`() = runBlocking<Unit> {
        val first=tokens()
        val command=OAuthRefreshExchange(request.clientId,audience,PresentedCredential(first.refreshToken))
        val responses=coroutineScope { listOf(async { service.refresh(command) },async { service.refresh(command) }).map { it.await() } }
        assertEquals(1,responses.count { it is OAuthOutcome.Success })
        assertEquals(1,responses.count { it is OAuthOutcome.Failure })
        val rotated=responses.filterIsInstance<OAuthOutcome.Success<OAuthTokenResponse>>().single().value
        assertFailure(service.validateCredential(PresentedCredential(rotated.accessToken),ExpectedOAuthResource.AGENT_MCP))
    }
    @Test
    fun `discovery marks ready samples hides provisioning samples and keeps normal Workspaces`() = runBlocking<Unit> {
        val token=tokens()
        val normal=Uuid.generateV7()
        val ready=Uuid.generateV7()
        val provisioning=Uuid.generateV7()
        val sampleOrganization=Uuid.generateV7()
        val params=mapOf("normal" to normal.toJavaUuid(),"ready" to ready.toJavaUuid(),"provisioning" to provisioning.toJavaUuid(),"sampleOrganization" to sampleOrganization.toJavaUuid(),
            "organization" to organization.value.toJavaUuid(),"user" to user.toJavaUuid(),"admin" to admin.toJavaUuid())
        transactions.run {
            jdbc.update("INSERT INTO identity_runtime.organizations(id,display_name) VALUES(:sampleOrganization,'Sample team')",params)
            jdbc.update("INSERT INTO identity_runtime.organization_members(organization_id,user_id,role) VALUES(:sampleOrganization,:admin,'ADMIN'),(:sampleOrganization,:user,'MEMBER')",params)
            jdbc.update("INSERT INTO identity_runtime.workspaces(id,organization_id,display_name) VALUES(:normal,:organization,'Revenue')",params)
            jdbc.update("""INSERT INTO identity_runtime.workspaces(id,organization_id,display_name,sample_dataset,sample_state,sample_initiator)
                VALUES(:ready,:organization,'Renamed sample','acme-cloud@2','READY',:admin),(:provisioning,:sampleOrganization,'Acme Cloud (sample)','acme-cloud@3','PROVISIONING',:admin)""",params)
            jdbc.update("INSERT INTO identity_runtime.workspace_members(workspace_id,user_id,role) VALUES(:normal,:user,'EXPLORER'),(:ready,:user,'EXPLORER'),(:provisioning,:user,'EXPLORER'),(:normal,:admin,'OWNER'),(:ready,:admin,'OWNER'),(:provisioning,:admin,'OWNER')",params)
        }
        val credential=PresentedCredential(token.accessToken)
        val listed=success(service.listWorkspaces(credential,ExpectedOAuthResource.AGENT_MCP))
        assertEquals(setOf(normal,ready),listed.map { it.workspaceId.value }.toSet())
        assertEquals(2,listed.size)
        assertNull(listed.single { it.workspaceId.value==normal }.sample)
        assertEquals(OAuthWorkspaceSample("acme-cloud@2"),listed.single { it.workspaceId.value==ready }.sample)
        assertEquals("Renamed sample",listed.single { it.workspaceId.value==ready }.displayName)
        transactions.run { jdbc.update("UPDATE identity_runtime.workspaces SET sample_state='READY' WHERE id=:provisioning",params) }
        val afterReady=success(service.listWorkspaces(credential,ExpectedOAuthResource.AGENT_MCP))
        assertEquals(OAuthWorkspaceSample("acme-cloud@3"),afterReady.single { it.workspaceId.value==provisioning }.sample)
        assertEquals(3,afterReady.size)
    }

    @Test
    fun `discovery spans current and future Organizations and only direct Workspace members`() = runBlocking<Unit> {
        val token=tokens()
        val workspace=Uuid.generateV7()
        val hiddenWorkspace=Uuid.generateV7()
        val otherWorkspace=Uuid.generateV7()
        val otherOrganization=Uuid.generateV7()
        transactions.run {
            val params=mapOf("workspace" to workspace.toJavaUuid(),"hidden" to hiddenWorkspace.toJavaUuid(),"otherWorkspace" to otherWorkspace.toJavaUuid(),"otherOrganization" to otherOrganization.toJavaUuid(),"organization" to organization.value.toJavaUuid(),"user" to user.toJavaUuid(),"admin" to admin.toJavaUuid())
            jdbc.update("INSERT INTO identity_runtime.organizations(id,display_name) VALUES(:otherOrganization,'Other')",params)
            jdbc.update("INSERT INTO identity_runtime.organization_members(organization_id,user_id,role) VALUES(:otherOrganization,:admin,'ADMIN'),(:otherOrganization,:user,'MEMBER')",params)
            jdbc.update("INSERT INTO identity_runtime.workspaces(id,organization_id,display_name) VALUES(:workspace,:organization,'Visible'),(:hidden,:organization,'Hidden'),(:otherWorkspace,:otherOrganization,'Other')",params)
            jdbc.update("INSERT INTO identity_runtime.workspace_members(workspace_id,user_id,role) VALUES(:workspace,:user,'EXPLORER'),(:otherWorkspace,:user,'EXPLORER'),(:workspace,:admin,'OWNER'),(:hidden,:admin,'OWNER'),(:otherWorkspace,:admin,'OWNER')",params)
        }
        val credential=PresentedCredential(token.accessToken)
        assertEquals(listOf(workspace,otherWorkspace),success(service.listWorkspaces(credential,ExpectedOAuthResource.AGENT_MCP)).map { it.workspaceId.value })
        assertEquals(listOf("OAuth Test","Other"),success(service.listWorkspaces(credential,ExpectedOAuthResource.AGENT_MCP)).map { it.organizationDisplayName })
        assertEquals(listOf(organization.value,otherOrganization),success(service.listOrganizations(credential,ExpectedOAuthResource.AGENT_MCP)).map { it.organizationId.value })
        assertEquals(listOf("MEMBER","MEMBER"),success(service.listOrganizations(credential,ExpectedOAuthResource.AGENT_MCP)).map { it.role })
        assertFailure(service.listWorkspaces(credential,ExpectedOAuthResource.AGENT_REST))
        transactions.run { jdbc.update("DELETE FROM identity_runtime.workspace_members WHERE workspace_id=:workspace AND user_id=:user",mapOf("workspace" to workspace.toJavaUuid(),"user" to user.toJavaUuid())) }
        assertEquals(listOf(otherWorkspace),success(service.listWorkspaces(credential,ExpectedOAuthResource.AGENT_MCP)).map { it.workspaceId.value })
        transactions.run {
            val repository=PostgresOrganizationRepository(jdbc)
            repository.deleteMember(cc.monomer.identity.domain.id.OrganizationId(organization.value),UserId(user))
        }
        assertEquals(listOf(otherOrganization),success(service.listOrganizations(credential,ExpectedOAuthResource.AGENT_MCP)).map { it.organizationId.value })
        val refreshed=success(service.refresh(OAuthRefreshExchange(request.clientId,audience,PresentedCredential(token.refreshToken))))
        assertEquals(listOf(otherWorkspace),success(service.listWorkspaces(PresentedCredential(refreshed.accessToken),ExpectedOAuthResource.AGENT_MCP)).map { it.workspaceId.value })
        transactions.run { PostgresOrganizationRepository(jdbc).deleteMember(cc.monomer.identity.domain.id.OrganizationId(otherOrganization),UserId(user)) }
        assertTrue(success(service.listOrganizations(credential,ExpectedOAuthResource.AGENT_MCP)).isEmpty())
        assertTrue(success(service.listWorkspaces(credential,ExpectedOAuthResource.AGENT_MCP)).isEmpty())
        success(service.refresh(OAuthRefreshExchange(request.clientId,audience,PresentedCredential(refreshed.refreshToken))))
    }
    @Test fun `user without memberships can issue and refresh and grants have no Organization foreign key`() = runBlocking<Unit> {
        val first=tokens()
        transactions.run { PostgresOrganizationRepository(jdbc).deleteMember(cc.monomer.identity.domain.id.OrganizationId(organization.value),UserId(user)) }
        val credential=PresentedCredential(first.accessToken)
        success(service.validateCredential(credential,ExpectedOAuthResource.AGENT_MCP))
        assertTrue(success(service.listOrganizations(credential,ExpectedOAuthResource.AGENT_MCP)).isEmpty())
        assertTrue(success(service.listWorkspaces(credential,ExpectedOAuthResource.AGENT_MCP)).isEmpty())
        success(service.refresh(OAuthRefreshExchange(request.clientId,audience,PresentedCredential(first.refreshToken))))
        assertEquals(0,jdbc.queryForObject("""SELECT count(*) FROM pg_constraint
            WHERE conrelid='identity_runtime.agent_oauth_grants'::regclass AND contype='f'
            AND confrelid='identity_runtime.organizations'::regclass""",emptyMap<String,Any>(),Int::class.java))
        val unaffiliated=tokens()
        success(service.refresh(OAuthRefreshExchange(request.clientId,audience,PresentedCredential(unaffiliated.refreshToken))))
    }
    @Test fun `current developer policy and user deletion deny credentials code and refresh`() = runBlocking<Unit> {
        val first=tokens()
        val code=authorize()
        val blocked=AgentOAuthService(PostgresAgentOAuthRepository(jdbc,credentialVerifier,
            DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.RESTRICTED,emptySet())),transactions,
            HttpsOAuthClientResolver(mapOf("monomer-native" to OAuthClient("monomer-native","CLI",setOf("http://127.0.0.1/callback")))),clock,
            mapOf(ExpectedOAuthResource.AGENT_REST to "https://api.example/agent",ExpectedOAuthResource.AGENT_MCP to audience))
        assertFailure(blocked.authorize(OAuthConsentCommand(browser,request)))
        assertFailure(blocked.exchangeCode(exchange(code)))
        assertFailure(blocked.refresh(OAuthRefreshExchange(request.clientId,audience,PresentedCredential(first.refreshToken))))
        assertFailure(blocked.validateCredential(PresentedCredential(first.accessToken),ExpectedOAuthResource.AGENT_MCP))
        assertFailure(blocked.listOrganizations(PresentedCredential(first.accessToken),ExpectedOAuthResource.AGENT_MCP))
        assertFailure(blocked.listWorkspaces(PresentedCredential(first.accessToken),ExpectedOAuthResource.AGENT_MCP))
        transactions.run { jdbc.update("DELETE FROM identity_runtime.users WHERE id=:user",mapOf("user" to user.toJavaUuid())) }
        assertFailure(service.exchangeCode(exchange(code)))
        assertFailure(service.refresh(OAuthRefreshExchange(request.clientId,audience,PresentedCredential(first.refreshToken))))
        assertFailure(service.validateCredential(PresentedCredential(first.accessToken),ExpectedOAuthResource.AGENT_MCP))
    }
    @Test fun `a client id that work cannot record is an invalid client`() = runBlocking<Unit> {
        val tooLong = "https://agent.example/" + "a".repeat(AgentClientId.MAX_LENGTH)
        assertEquals(OAuthOutcome.Failure(OAuthError.INVALID_CLIENT), service.inspectAuthorization(request.copy(clientId = tooLong)))
        assertEquals(OAuthOutcome.Failure(OAuthError.INVALID_CLIENT), service.inspectAuthorization(request.copy(clientId = "monomer-native\n")))
    }
    @Test fun `agent app displays name revoked connections by their latest client name and https host`() = runBlocking<Unit> {
        val claude = AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata")
        val native = AgentClientId("monomer-native")
        transactions.run {
            insertGrant(claude, "Claude (old)", initial.minusSeconds(86400), revoked = true)
            insertGrant(claude, "Claude", initial, revoked = true)
            insertGrant(native, "CLI", initial, revoked = false)
        }
        val displays = PostgresAgentAppDisplays(jdbc, transactions).read(setOf(claude, native, AgentClientId("https://chatgpt.com/metadata.json"), AgentClientId("never-connected")))
        assertEquals(AgentAppDisplay(claude, "Claude", "claude.ai"), displays[claude])
        assertEquals(AgentAppDisplay(native, "CLI", null), displays[native])
        assertEquals(AgentAppDisplay(AgentClientId("https://chatgpt.com/metadata.json"), "chatgpt.com", "chatgpt.com"), displays[AgentClientId("https://chatgpt.com/metadata.json")])
        assertNull(displays[AgentClientId("never-connected")])
        assertTrue(PostgresAgentAppDisplays(jdbc, transactions).read(emptySet()).isEmpty())
    }
    private fun insertGrant(clientId: AgentClientId, label: String, issuedAt: Instant, revoked: Boolean) {
        jdbc.update("""INSERT INTO identity_runtime.agent_oauth_grants(id,owner_user_id,oauth_client_id,client_label,resource_audience,scopes,issued_at,revoked_at)
            VALUES(:id,:user,:client,:label,:audience,ARRAY['agent.workspace'],:issued,:revoked)""",
            mapOf("id" to Uuid.generateV7().toJavaUuid(),"user" to user.toJavaUuid(),"client" to clientId.value,"label" to label,"audience" to audience,
                "issued" to Timestamp.from(issuedAt),"revoked" to if (revoked) Timestamp.from(issuedAt.plusSeconds(60)) else null))
    }
    private suspend fun authorize()=success(service.authorize(OAuthConsentCommand(browser,request))).code
    private suspend fun tokens()=success(service.exchangeCode(exchange(authorize())))
    private fun exchange(code: String, client: String=request.clientId,resource: String=audience,redirect: String=request.redirectUri,proof: String=verifier)=OAuthCodeExchange(client,redirect,resource,PresentedCredential(code),PresentedCredential(proof))
    private fun <T> success(outcome: OAuthOutcome<T>): T = assertIs<OAuthOutcome.Success<T>>(outcome).value
    private fun assertFailure(outcome: OAuthOutcome<*>) { assertIs<OAuthOutcome.Failure>(outcome) }
    private fun insertSession(userId: Uuid,sessionId: SessionId) {
        val id=Uuid.generateV7()
        val params=mapOf("id" to id.toJavaUuid(),"session" to sessionId.value.toJavaUuid(),"user" to userId.toJavaUuid(),"subject" to id.toString(),"now" to Timestamp.from(now),"expiry" to Timestamp.from(now.plusSeconds(40L*86400)),"email" to "$userId@example.test")
        jdbc.update("INSERT INTO identity_runtime.login_identities(id,user_id,protocol,namespace,subject,linked_via,linked_at) VALUES(:id,:user,'oidc','https://accounts.google.com',:subject,'signup',:now)",params)
        jdbc.update("""INSERT INTO identity_runtime.sessions(id,user_id,login_identity_id,protocol,authentication_source_ref,authentication_issuer,asserted_email,asserted_email_verified,email_evidence,created_at,last_refreshed_at,absolute_expires_at)
            VALUES(:session,:user,:id,'oidc','builtin/google','https://accounts.google.com',:email,true,'authoritative',:now,:now,:expiry)""",params)
    }
    companion object { @JvmField @RegisterExtension internal val postgres=TargetIdentityPostgres }
}
