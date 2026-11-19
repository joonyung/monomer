package cc.monomer.identity.application.agent

import cc.monomer.identity.api.*
import java.time.Instant
import kotlin.uuid.Uuid

interface OAuthClientResolver {
    suspend fun resolve(clientId: String): OAuthClient?
    fun acceptsRedirect(client: OAuthClient, redirectUri: String): Boolean
}

interface AgentOAuthRepository {
    fun currentBrowserUser(credential: PresentedCredential, now: Instant): Uuid?
    fun lockCurrentUser(userId: Uuid): Boolean
    fun insertGrant(grant: OAuthStoredGrant)
    fun insertCode(hash: String, grantId: Uuid, redirectUri: String, challenge: String, expiresAt: Instant)
    fun lockCode(hash: String): OAuthStoredCode?
    fun consumeCode(hash: String, now: Instant)
    fun lockGrant(grantId: Uuid): OAuthStoredGrant?
    fun insertAccess(hash: String, grantId: Uuid, now: Instant, expiresAt: Instant)
    fun insertRefresh(hash: String, grantId: Uuid, now: Instant, expiresAt: Instant)
    fun findRefreshGrant(hash: String): Uuid?
    fun lockRefresh(hash: String): OAuthStoredRefresh?
    fun consumeRefresh(hash: String, now: Instant)
    fun revokeGrant(grantId: Uuid, now: Instant)
    fun findTokenGrant(hash: String): Uuid?
    fun connections(userId: Uuid, limit: Int, before: OAuthConnectionBoundary?): List<OAuthConnection>
    fun connectionSummary(userId: Uuid, clientId: String, now: Instant): OAuthConnectionSummary
    fun credential(hash: String, audience: String, now: Instant): OAuthAuthenticatedGrant?
    /** Best effort: never joins a caller's transaction, waits on a locked grant, or throws. */
    suspend fun recordGrantUse(grantId: Uuid, now: Instant)
    fun organizations(identity: OAuthAgentIdentity): List<OAuthOrganization>
    fun workspaces(identity: OAuthAgentIdentity): List<OAuthWorkspace>
}

data class OAuthConnectionBoundary(val issuedAt: Instant, val grantId: Uuid)
data class OAuthStoredGrant(val id: Uuid, val userId: Uuid, val clientId: String, val clientName: String?, val audience: String, val issuedAt: Instant, val revokedAt: Instant?) {
    val expiresAt: Instant get() = issuedAt.plusSeconds(30L * 24 * 3600)
}
data class OAuthAuthenticatedGrant(val identity: OAuthAgentIdentity, val grantId: Uuid, val lastUsedAt: Instant?)
data class OAuthStoredCode(val grantId: Uuid, val redirectUri: String, val challenge: String, val expiresAt: Instant, val consumedAt: Instant?)
data class OAuthStoredRefresh(val grantId: Uuid, val expiresAt: Instant, val consumedAt: Instant?)
