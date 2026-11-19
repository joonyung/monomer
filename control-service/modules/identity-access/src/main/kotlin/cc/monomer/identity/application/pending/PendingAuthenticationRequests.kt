package cc.monomer.identity.application.pending

import cc.monomer.identity.application.OpaqueCredentials
import cc.monomer.identity.application.organization.OrganizationInvitationService
import cc.monomer.identity.api.OAuthAuthorizationRequest
import cc.monomer.identity.api.OAuthClient
import cc.monomer.identity.domain.port.TransactionRunner
import java.time.Clock
import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
sealed interface PendingAuthenticationPayload {
    @Serializable
    data class Google(val authorizationUri: String, val clientId: String, val redirectUri: String,
        val scopes: Set<String>, val state: String, val registrationId: String, val nonce: String?,
        val nonceHash: String?, val codeVerifier: String?, val codeChallenge: String?, val codeChallengeMethod: String?,
        val invitationContinuation: String?) : PendingAuthenticationPayload
    @Serializable
    data class Agent(val clientId: String, val clientName: String, val redirectUris: Set<String>,
        val redirectUri: String, val resource: String, val scopes: Set<String>, val codeChallenge: String,
        val codeChallengeMethod: String, val state: String?, val csrfToken: String) : PendingAuthenticationPayload {
        fun authorizationRequest() = OAuthAuthorizationRequest(clientId, redirectUri, resource, scopes, codeChallenge, codeChallengeMethod)
        fun client() = OAuthClient(clientId, clientName, redirectUris)
    }
    @Serializable
    data class Operations(val clientId: String, val redirectUri: String, val resource: String, val codeChallenge: String,
        val state: String, val csrfToken: String) : PendingAuthenticationPayload
    @Serializable
    data class Invitation(val invitationId: String, val organizationId: String, val tokenHash: String,
        val proofExpiresAt: String) : PendingAuthenticationPayload
}

enum class PendingAuthenticationKind(val ttlSeconds: Long, val maximum: Int) {
    GOOGLE(1800, 16), OPERATIONS(600, 8), AGENT(600, 8), INVITATION(1800, 16)
}
class PendingAuthenticationRequest(val payload: PendingAuthenticationPayload, val expiresAt: Instant, val userId: String?)

interface PendingAuthenticationRepository {
    fun knownBrowser(browserHash: String, now: Instant): Boolean
    fun create(requestHash: String, browserHash: String, kind: PendingAuthenticationKind,
        payload: PendingAuthenticationPayload, now: Instant, expiresAt: Instant): Boolean
    fun find(requestHash: String, browserHash: String, kind: PendingAuthenticationKind, now: Instant): PendingAuthenticationRequest?
    fun consume(requestHash: String, browserHash: String, kind: PendingAuthenticationKind,
        now: Instant, userId: String?, csrfToken: String?): PendingAuthenticationRequest?
    fun bindConsent(requestHash: String, browserHash: String, kind: PendingAuthenticationKind, now: Instant, userId: String): PendingAuthenticationRequest?
    fun releaseConsent(requestHash: String, browserHash: String, kind: PendingAuthenticationKind, now: Instant, userId: String,
        csrfToken: String, payload: PendingAuthenticationPayload): Boolean
    fun removeInvitation(requestHash: String, browserHash: String)
}

class PendingAuthenticationRequests(private val repository: PendingAuthenticationRepository,
    private val transactions: TransactionRunner, private val clock: Clock) {
    suspend fun knownBrowser(binding: String): Boolean = validReference(binding) && transactions.readOnly {
        repository.knownBrowser(OpaqueCredentials.hash(binding), clock.instant())
    }
    suspend fun createGoogle(binding: String, payload: PendingAuthenticationPayload.Google): Boolean = transactions.run {
        require(validReference(binding))
        val now = clock.instant()
        if (payload.invitationContinuation != null && (!validReference(payload.invitationContinuation) ||
            repository.find(OpaqueCredentials.hash(payload.invitationContinuation), OpaqueCredentials.hash(binding), PendingAuthenticationKind.INVITATION, now) == null)) false
        else repository.create(OpaqueCredentials.hash(binding + ":" + payload.state), OpaqueCredentials.hash(binding), PendingAuthenticationKind.GOOGLE, payload, now, now.plusSeconds(1800))
    }
    suspend fun createAgent(binding: String, payload: PendingAuthenticationPayload.Agent): String = create(binding, PendingAuthenticationKind.AGENT, payload, null)
    suspend fun createOperations(binding: String, payload: PendingAuthenticationPayload.Operations): String = create(binding, PendingAuthenticationKind.OPERATIONS, payload, null)
    suspend fun bindOperations(reference: String, binding: String, userId: String): PendingAuthenticationRequest? =
        if (!validInput(reference, binding, PendingAuthenticationKind.OPERATIONS)) null else transactions.run {
            repository.bindConsent(OpaqueCredentials.hash(reference), OpaqueCredentials.hash(binding), PendingAuthenticationKind.OPERATIONS, clock.instant(), userId)
        }
    suspend fun consumeOperations(reference: String, binding: String, userId: String, csrfToken: String): PendingAuthenticationRequest? =
        if (!validInput(reference, binding, PendingAuthenticationKind.OPERATIONS) || !validReference(csrfToken)) null else transactions.run {
            repository.consume(OpaqueCredentials.hash(reference), OpaqueCredentials.hash(binding), PendingAuthenticationKind.OPERATIONS, clock.instant(), userId, csrfToken)
        }
    suspend fun createInvitation(binding: String, proof: OrganizationInvitationService.Proof): String = create(binding,
        PendingAuthenticationKind.INVITATION, PendingAuthenticationPayload.Invitation(proof.invitationId.toString(),
            proof.organizationId.toString(), proof.expectedTokenHash, proof.expiresAt.toString()), proof.expiresAt)
    private suspend fun create(binding: String, kind: PendingAuthenticationKind, payload: PendingAuthenticationPayload, proofExpiry: Instant?): String {
        require(validReference(binding))
        val reference = OpaqueCredentials.generate()
        return transactions.run {
            val now = clock.instant()
            val expiresAt = minOf(now.plusSeconds(kind.ttlSeconds), proofExpiry ?: Instant.MAX)
            check(repository.create(OpaqueCredentials.hash(reference), OpaqueCredentials.hash(binding), kind, payload, now, expiresAt))
            reference
        }
    }
    suspend fun find(reference: String, binding: String, kind: PendingAuthenticationKind): PendingAuthenticationRequest? =
        if (!validInput(reference, binding, kind)) null else transactions.readOnly { repository.find(requestHash(reference, binding, kind), OpaqueCredentials.hash(binding), kind, clock.instant()) }
    suspend fun consumeGoogle(state: String, binding: String): PendingAuthenticationRequest? =
        if (!validInput(state, binding, PendingAuthenticationKind.GOOGLE)) null else transactions.run {
            val now = clock.instant()
            val stored = repository.consume(OpaqueCredentials.hash(binding + ":" + state), OpaqueCredentials.hash(binding), PendingAuthenticationKind.GOOGLE, now, null, null) ?: return@run null
            val payload = stored.payload as PendingAuthenticationPayload.Google
            val correlation = payload.invitationContinuation?.takeIf {
                repository.find(OpaqueCredentials.hash(it), OpaqueCredentials.hash(binding), PendingAuthenticationKind.INVITATION, now) != null
            }
            PendingAuthenticationRequest(payload.copy(invitationContinuation = correlation), stored.expiresAt, stored.userId)
        }
    suspend fun bindAgent(reference: String, binding: String, userId: String): PendingAuthenticationRequest? =
        if (!validInput(reference, binding, PendingAuthenticationKind.AGENT)) null else transactions.run {
            repository.bindConsent(OpaqueCredentials.hash(reference), OpaqueCredentials.hash(binding), PendingAuthenticationKind.AGENT, clock.instant(), userId)
        }
    suspend fun consumeAgent(reference: String, binding: String, userId: String, csrfToken: String): PendingAuthenticationRequest? =
        if (!validInput(reference, binding, PendingAuthenticationKind.AGENT) || !validReference(csrfToken)) null else transactions.run {
            repository.consume(OpaqueCredentials.hash(reference), OpaqueCredentials.hash(binding), PendingAuthenticationKind.AGENT, clock.instant(), userId, csrfToken)
        }
    // The bound user explicitly hands the consent request back so another account can sign in and
    // review it in this browser. The rotated CSRF token makes the released page's decision unusable.
    suspend fun releaseAgent(reference: String, binding: String, userId: String, csrfToken: String): Boolean =
        if (!validInput(reference, binding, PendingAuthenticationKind.AGENT) || !validReference(csrfToken)) false else transactions.run {
            val now = clock.instant()
            val stored = repository.find(OpaqueCredentials.hash(reference), OpaqueCredentials.hash(binding), PendingAuthenticationKind.AGENT, now) ?: return@run false
            val payload = stored.payload as? PendingAuthenticationPayload.Agent ?: return@run false
            repository.releaseConsent(OpaqueCredentials.hash(reference), OpaqueCredentials.hash(binding), PendingAuthenticationKind.AGENT, now, userId, csrfToken,
                payload.copy(csrfToken = OpaqueCredentials.generate()))
        }
    suspend fun removeInvitation(reference: String, binding: String) {
        if (validInput(reference, binding, PendingAuthenticationKind.INVITATION)) transactions.run { repository.removeInvitation(OpaqueCredentials.hash(reference), OpaqueCredentials.hash(binding)) }
    }
    private fun requestHash(reference: String, binding: String, kind: PendingAuthenticationKind) = OpaqueCredentials.hash(if (kind == PendingAuthenticationKind.GOOGLE) binding + ":" + reference else reference)
    private fun validInput(reference: String, binding: String, kind: PendingAuthenticationKind) = validReference(binding) &&
        if (kind == PendingAuthenticationKind.GOOGLE) reference.matches(Regex("[A-Za-z0-9_-]{32,256}")) else validReference(reference)
    companion object {
        fun validReference(reference: String) = reference.matches(Regex("[A-Za-z0-9_-]{43}"))
    }
}
