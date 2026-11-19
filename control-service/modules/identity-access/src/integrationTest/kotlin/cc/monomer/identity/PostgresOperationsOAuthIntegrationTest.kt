package cc.monomer.identity

import cc.monomer.identity.application.OpaqueCredentials
import cc.monomer.identity.domain.model.DeveloperAccessPolicy

import cc.monomer.identity.api.*
import cc.monomer.identity.application.agent.*
import cc.monomer.identity.application.operations.*
import cc.monomer.identity.application.pending.*
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.UserCredentialProvenance
import cc.monomer.identity.domain.port.CurrentUserCredentialVerifier
import cc.monomer.identity.domain.port.VerifiedCurrentUserCredential
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

class PostgresOperationsOAuthIntegrationTest {
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
    private val audience = "https://api.example/ops/v1"
    private val verifier = "a".repeat(43)
    private val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
    private val request = OperationsAuthorization(OperationsOAuthService.CLIENT_ID,"http://127.0.0.1:49152/oauth/callback",audience,challenge)
    private lateinit var service: OperationsOAuthService
    private lateinit var transactions: SpringTransactionRunner

    @BeforeEach fun setup() = runBlocking<Unit> {
        TargetIdentityPostgres.dataSource.connection.use {
            TargetIdentityPostgres.applyCanonicalSchemaTwice(it,javaClass.classLoader)
            AuthSchemaVerifier.verifyContractFacts(it)
        }
        val manager = DataSourceTransactionManager(TargetIdentityPostgres.dataSource)
        transactions = SpringTransactionRunner(TransactionTemplate(manager),TransactionTemplate(manager).apply { isReadOnly=true; isolationLevel=java.sql.Connection.TRANSACTION_REPEATABLE_READ }, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        val credentialVerifier = object : CurrentUserCredentialVerifier {
            override fun verifyCurrentCredential(serializedToken: String): VerifiedCurrentUserCredential? = when(serializedToken) {
                browser.value -> VerifiedCurrentUserCredential(UserCredentialProvenance.BrowserSession(UserId(user),session),now.plusSeconds(3600))
                otherBrowser.value -> VerifiedCurrentUserCredential(UserCredentialProvenance.BrowserSession(UserId(admin),otherSession),now.plusSeconds(3600))
                else -> null
            }
        }
        service = OperationsOAuthService(PostgresOperationsOAuthRepository(jdbc, PostgresAgentOAuthRepository(jdbc,credentialVerifier,
            DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.OPEN, emptySet()))),transactions,clock,setOf(user.toString()),audience)
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
    @Test fun `code exchange is single use across replicas and binding failures preserve valid proof`() = runBlocking<Unit> {
        val code = service.authorize(browser, request)
        assertFailsWith<OperationsUnauthenticatedException> { service.exchange(code,"b".repeat(43),request) }
        assertFailsWith<OperationsUnauthenticatedException> { service.exchange(code,verifier,OperationsAuthorization(request.clientId,"http://127.0.0.1:49153/oauth/callback",audience,challenge)) }
        assertFailsWith<IllegalArgumentException> { service.exchange(code,verifier,OperationsAuthorization(request.clientId,request.redirectUri,"https://other.example/ops/v1",challenge)) }
        val replica = OperationsOAuthService(PostgresOperationsOAuthRepository(jdbc, io.mockk.mockk()),transactions,clock,setOf(user.toString()),audience)
        val responses = coroutineScope { listOf(service, replica).map { instance -> async { runCatching { instance.exchange(code,verifier,request) } } }.map { it.await() } }
        assertEquals(1, responses.count { it.isSuccess })
        assertEquals(1, responses.count { it.exceptionOrNull() is OperationsUnauthenticatedException })
        val token = responses.single { it.isSuccess }.getOrThrow()
        assertEquals(user.toString(), replica.authenticate(PresentedCredential(token)).userId)
        assertEquals(initial.plusSeconds(3600),service.authenticate(PresentedCredential(token)).expiresAt)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM identity_runtime.operations_sessions WHERE token_hash=:token",mapOf("token" to token),Int::class.java))
        replica.revoke(PresentedCredential(token))
        assertFailsWith<OperationsUnauthenticatedException> { service.authenticate(PresentedCredential(token)) }
    }
    @Test fun `code and session expire exactly and allowlist removal denies existing session`() = runBlocking<Unit> {
        val code = service.authorize(browser,request)
        now = initial.plusSeconds(300)
        assertFailsWith<OperationsUnauthenticatedException> { service.exchange(code,verifier,request) }
        now = initial
        val token = service.exchange(service.authorize(browser,request),verifier,request)
        val removed = OperationsOAuthService(PostgresOperationsOAuthRepository(jdbc,io.mockk.mockk()),transactions,clock,emptySet(),audience)
        assertFailsWith<OperationsForbiddenException> { removed.authenticate(PresentedCredential(token)) }
        now = initial.plusSeconds(3600)
        assertFailsWith<OperationsUnauthenticatedException> { service.authenticate(PresentedCredential(token)) }
    }
    @Test fun `ordinary credentials and non operator browser never obtain ops access`() = runBlocking<Unit> {
        for (value in listOf(browser.value,"google-id-token","agent-access-token","dev-audience-jwt")) {
            assertFailsWith<OperationsUnauthenticatedException> { service.authenticate(PresentedCredential(value)) }
        }
        assertFailsWith<OperationsForbiddenException> { service.authorize(otherBrowser,request) }
        for (redirect in listOf("http://localhost:49152/oauth/callback","https://127.0.0.1:49152/oauth/callback","http://127.0.0.1:49152/oauth/callback?x=1","http://127.0.0.1:49152/else")) {
            assertFailsWith<IllegalArgumentException> { service.validate(OperationsAuthorization(request.clientId,redirect,audience,challenge)) }
        }
        transactions.run { jdbc.update("UPDATE identity_runtime.sessions SET revoked_at=:now,revocation_reason='logout' WHERE id=:id",mapOf("now" to Timestamp.from(now),"id" to session.value.toJavaUuid())) }
        assertFailsWith<OperationsUnauthenticatedException> { service.authorize(browser,request) }
    }
    @Test fun `operations pending consent stays bound to browser user csrf and replica`() = runBlocking<Unit> {
        fun requests() = PendingAuthenticationRequests(PostgresPendingAuthenticationRepository(jdbc),transactions,clock)
        val binding = OpaqueCredentials.generate()
        val csrf = OpaqueCredentials.generate()
        val reference = requests().createOperations(binding,PendingAuthenticationPayload.Operations(request.clientId,request.redirectUri,audience,challenge,"s".repeat(43),csrf))
        assertNull(requests().bindOperations(reference,OpaqueCredentials.generate(),user.toString()))
        assertNotNull(requests().bindOperations(reference,binding,user.toString()))
        assertNull(requests().bindOperations(reference,binding,admin.toString()))
        assertNull(requests().consumeOperations(reference,binding,user.toString(),OpaqueCredentials.generate()))
        assertNotNull(requests().consumeOperations(reference,binding,user.toString(),csrf))
        assertNull(requests().consumeOperations(reference,binding,user.toString(),csrf))
    }
    private fun insertSession(userId: Uuid,sessionId: SessionId) {
        val id=Uuid.generateV7()
        val params=mapOf("id" to id.toJavaUuid(),"session" to sessionId.value.toJavaUuid(),"user" to userId.toJavaUuid(),"subject" to id.toString(),"now" to Timestamp.from(now),"expiry" to Timestamp.from(now.plusSeconds(40L*86400)),"email" to "$userId@example.test")
        jdbc.update("INSERT INTO identity_runtime.login_identities(id,user_id,protocol,namespace,subject,linked_via,linked_at) VALUES(:id,:user,'oidc','https://accounts.google.com',:subject,'signup',:now)",params)
        jdbc.update("""INSERT INTO identity_runtime.sessions(id,user_id,login_identity_id,protocol,authentication_source_ref,authentication_issuer,asserted_email,asserted_email_verified,email_evidence,created_at,last_refreshed_at,absolute_expires_at)
            VALUES(:session,:user,:id,'oidc','builtin/google','https://accounts.google.com',:email,true,'authoritative',:now,:now,:expiry)""",params)
    }
    companion object { @JvmField @RegisterExtension internal val postgres=TargetIdentityPostgres }
}
