package cc.monomer.control.auth

import cc.monomer.control.auth.inbound.PendingAuthenticationBrowser
import cc.monomer.control.auth.inbound.invitation.InvitationContinuations
import cc.monomer.control.auth.inbound.session.RefreshCookieProperties
import cc.monomer.identity.application.pending.*
import cc.monomer.identity.domain.port.TransactionRunner
import java.time.Clock
import java.time.Instant

class PendingAuthenticationFixture(clock: Clock) {
    private val repository = MemoryPendingAuthenticationRepository()
    private val transactions = object : TransactionRunner {
        override suspend fun <T> run(action: () -> T): T = synchronized(repository) { action() }
        override suspend fun <T> readOnly(action: () -> T): T = synchronized(repository) { action() }
    }
    val requests = PendingAuthenticationRequests(repository, transactions, clock)
    val browser = PendingAuthenticationBrowser(requests, RefreshCookieProperties(false))
    val invitations = InvitationContinuations(requests, browser)
    companion object { const val COOKIE = "monomer_auth_pending_local" }
}

private class MemoryPendingAuthenticationRepository : PendingAuthenticationRepository {
    private data class Stored(val browserHash: String, val kind: PendingAuthenticationKind, val request: PendingAuthenticationRequest)
    private val rows = linkedMapOf<String, Stored>()
    override fun knownBrowser(browserHash: String, now: Instant) = rows.values.any { it.browserHash == browserHash && it.request.expiresAt > now }
    override fun create(requestHash: String, browserHash: String, kind: PendingAuthenticationKind, payload: PendingAuthenticationPayload,
        now: Instant, expiresAt: Instant): Boolean {
        rows.entries.removeIf { it.value.request.expiresAt <= now }
        if (requestHash in rows) return false
        val matching = rows.filterValues { it.browserHash == browserHash && it.kind == kind }.keys
        if (matching.size >= kind.maximum) rows.remove(matching.first())
        rows[requestHash] = Stored(browserHash, kind, PendingAuthenticationRequest(payload, expiresAt, null))
        return true
    }
    override fun find(requestHash: String, browserHash: String, kind: PendingAuthenticationKind, now: Instant) =
        rows[requestHash]?.takeIf { it.browserHash == browserHash && it.kind == kind && it.request.expiresAt > now }?.request
    override fun consume(requestHash: String, browserHash: String, kind: PendingAuthenticationKind, now: Instant,
        userId: String?, csrfToken: String?): PendingAuthenticationRequest? {
        val request = find(requestHash, browserHash, kind, now) ?: return null
        if (kind in setOf(PendingAuthenticationKind.AGENT, PendingAuthenticationKind.OPERATIONS) && (request.userId != userId ||
                (when (val payload = request.payload) { is PendingAuthenticationPayload.Agent -> payload.csrfToken; is PendingAuthenticationPayload.Operations -> payload.csrfToken; else -> null }) != csrfToken)) return null
        rows.remove(requestHash)
        return request
    }
    override fun bindConsent(requestHash: String, browserHash: String, kind: PendingAuthenticationKind, now: Instant, userId: String): PendingAuthenticationRequest? {
        val request = find(requestHash, browserHash, kind, now) ?: return null
        if (request.userId != null && request.userId != userId) return null
        val bound = PendingAuthenticationRequest(request.payload, request.expiresAt, userId)
        rows[requestHash] = rows.getValue(requestHash).copy(request = bound)
        return bound
    }
    override fun releaseConsent(requestHash: String, browserHash: String, kind: PendingAuthenticationKind, now: Instant, userId: String,
        csrfToken: String, payload: PendingAuthenticationPayload): Boolean {
        val request = find(requestHash, browserHash, kind, now) ?: return false
        val current = request.payload as? PendingAuthenticationPayload.Agent ?: return false
        if (request.userId != userId || current.csrfToken != csrfToken) return false
        rows[requestHash] = rows.getValue(requestHash).copy(request = PendingAuthenticationRequest(payload, request.expiresAt, null))
        return true
    }
    override fun removeInvitation(requestHash: String, browserHash: String) {
        rows[requestHash]?.takeIf { it.browserHash == browserHash && it.kind == PendingAuthenticationKind.INVITATION }?.let { rows.remove(requestHash) }
    }
}
