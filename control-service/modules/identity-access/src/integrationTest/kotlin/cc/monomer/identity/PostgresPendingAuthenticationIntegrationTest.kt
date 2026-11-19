package cc.monomer.identity

import cc.monomer.identity.application.OpaqueCredentials
import cc.monomer.identity.application.organization.OrganizationInvitationService
import cc.monomer.identity.application.pending.*
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.outbound.postgres.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.uuid.Uuid
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

class PostgresPendingAuthenticationIntegrationTest {
    private var now = Instant.parse("2026-09-11T00:00:00Z")
    private val clock = object : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private val jdbc get() = NamedParameterJdbcTemplate(TargetIdentityPostgres.dataSource)
    private fun service(): PendingAuthenticationRequests {
        val manager = DataSourceTransactionManager(TargetIdentityPostgres.dataSource)
        return PendingAuthenticationRequests(PostgresPendingAuthenticationRepository(jdbc),
            SpringTransactionRunner(TransactionTemplate(manager), TransactionTemplate(manager).apply { isReadOnly = true }, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined), clock)
    }
    @BeforeEach fun setup() {
        TargetIdentityPostgres.dataSource.connection.use {
            TargetIdentityPostgres.applyCanonicalSchemaTwice(it, javaClass.classLoader)
            AuthSchemaVerifier.verifyContractFacts(it)
        }
    }
    @Test fun `separate instances consume Google once with invitation correlation and fixed expiry`() = runBlocking<Unit> {
        val first = service()
        val browser = OpaqueCredentials.generate()
        val invitation = first.createInvitation(browser, proof(now.plusSeconds(3600)))
        val state = OpaqueCredentials.generate()
        assertTrue(first.createGoogle(browser, google(state, invitation)))
        assertNull(service().consumeGoogle(state, OpaqueCredentials.generate()))
        val consumed = coroutineScope { (1..8).map { async { service().consumeGoogle(state,browser) } }.map { it.await() } }
        assertEquals(1, consumed.count { it != null })
        assertEquals(invitation, (consumed.filterNotNull().single().payload as PendingAuthenticationPayload.Google).invitationContinuation)
        assertNotNull(first.find(invitation,browser,PendingAuthenticationKind.INVITATION))
        assertNull(service().consumeGoogle(state,browser))
        val expiredState = OpaqueCredentials.generate()
        assertTrue(first.createGoogle(browser,google(expiredState,null)))
        now = now.plusSeconds(1800)
        assertNull(service().consumeGoogle(expiredState,browser))
        assertNull(service().find(invitation,browser,PendingAuthenticationKind.INVITATION))
    }
    @Test fun `Agent first user binding and guarded consume are atomic across instances`() = runBlocking<Unit> {
        val browser = OpaqueCredentials.generate()
        val payload = agent()
        val reference = service().createAgent(browser,payload)
        val bindings = coroutineScope { listOf("user-one","user-two").map { user -> async { user to service().bindAgent(reference,browser,user) } }.map { it.await() } }
        val winner = bindings.single { it.second != null }.first
        assertNull(service().consumeAgent(reference,browser,"other-user",payload.csrfToken))
        assertNull(service().consumeAgent(reference,browser,winner,OpaqueCredentials.generate()))
        val consumed = coroutineScope { (1..8).map { async { service().consumeAgent(reference,browser,winner,payload.csrfToken) } }.map { it.await() } }
        assertEquals(1,consumed.count { it != null })
        val expiring = service().createAgent(browser,payload)
        now = now.plusSeconds(600)
        assertNull(service().bindAgent(expiring,browser,winner))
    }
    @Test fun `parallel creates cap each browser and kind and preserve independent tabs`() = runBlocking<Unit> {
        val browser = OpaqueCredentials.generate()
        val otherBrowser = OpaqueCredentials.generate()
        val other = service().createAgent(otherBrowser,agent())
        val references = coroutineScope { (1..40).map { async { service().createAgent(browser,agent()) } }.map { it.await() } }
        assertEquals(8,references.count { service().find(it,browser,PendingAuthenticationKind.AGENT) != null })
        assertNotNull(service().find(other,otherBrowser,PendingAuthenticationKind.AGENT))
        val states = coroutineScope { (1..24).map { async {
            val state = OpaqueCredentials.generate()
            assertTrue(service().createGoogle(browser,google(state,null)))
            state
        } }.map { it.await() } }
        assertEquals(16,states.count { service().find(it,browser,PendingAuthenticationKind.GOOGLE) != null })
        val invitations = coroutineScope { (1..24).map { async {
            service().createInvitation(browser,proof(now.plusSeconds(3600)))
        } }.map { it.await() } }
        assertEquals(16,invitations.count { service().find(it,browser,PendingAuthenticationKind.INVITATION) != null })
        val tabBrowser = OpaqueCredentials.generate()
        val firstState = OpaqueCredentials.generate()
        val secondState = OpaqueCredentials.generate()
        assertTrue(service().createGoogle(tabBrowser,google(firstState,null)))
        assertTrue(service().createGoogle(tabBrowser,google(secondState,null)))
        assertNotNull(service().consumeGoogle(firstState,tabBrowser))
        assertNotNull(service().consumeGoogle(secondState,tabBrowser))
    }
    @Test fun `expired invitation correlation is dropped and terminal removal is browser guarded`() = runBlocking<Unit> {
        val browser = OpaqueCredentials.generate()
        val invitation = service().createInvitation(browser,proof(now.plusSeconds(10)))
        val state = OpaqueCredentials.generate()
        assertTrue(service().createGoogle(browser,google(state,invitation)))
        service().removeInvitation(invitation,OpaqueCredentials.generate())
        assertNotNull(service().find(invitation,browser,PendingAuthenticationKind.INVITATION))
        now = now.plusSeconds(10)
        assertNull((service().consumeGoogle(state,browser)!!.payload as PendingAuthenticationPayload.Google).invitationContinuation)
        assertFalse(service().createGoogle(browser,google(OpaqueCredentials.generate(),invitation)))
    }
    @Test fun `new traffic cleans expired rows in bounded batches and stores hashes only`() = runBlocking<Unit> {
        val browser = OpaqueCredentials.generate()
        val reference = service().createAgent(browser,agent())
        assertTrue(service().knownBrowser(browser))
        val hashes = jdbc.query("SELECT request_hash,browser_hash FROM identity_runtime.pending_authentication_requests WHERE request_hash=:hash",
            mapOf("hash" to OpaqueCredentials.hash(reference))) { rs,_ -> rs.getString(1) to rs.getString(2) }.single()
        assertNotEquals(reference,hashes.first)
        assertNotEquals(browser,hashes.second)
        now = now.plusSeconds(600)
        assertFalse(service().knownBrowser(browser))
        service().createAgent(OpaqueCredentials.generate(),agent())
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM identity_runtime.pending_authentication_requests WHERE request_hash=:hash",
            mapOf("hash" to hashes.first),Int::class.java))
    }
    private fun proof(expiry: Instant) = OrganizationInvitationService.Proof(Uuid.generateV7(), OrganizationId.generate(), "a".repeat(64), expiry)
    private fun google(state: String, invitation: String?) = PendingAuthenticationPayload.Google("https://accounts.google.com/o/oauth2/v2/auth",
        "client","https://api.example/login/oauth2/code/google",setOf("openid"),state,"google","nonce","nonce-hash","verifier","challenge","S256",invitation)
    private fun agent() = PendingAuthenticationPayload.Agent("native","CLI",setOf("http://127.0.0.1/callback"),
        "http://127.0.0.1/callback","https://api.example/mcp",setOf("agent.workspace"),"challenge","S256","state",OpaqueCredentials.generate())
    companion object { @JvmField @RegisterExtension internal val postgres = TargetIdentityPostgres }
}
