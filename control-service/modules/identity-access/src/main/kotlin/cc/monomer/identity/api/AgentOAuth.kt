package cc.monomer.identity.api

import java.time.Instant
import kotlin.uuid.Uuid

interface AgentOAuthPort {
    suspend fun inspectAuthorization(request: OAuthAuthorizationRequest): OAuthOutcome<OAuthClient>
    suspend fun authorize(command: OAuthConsentCommand): OAuthOutcome<OAuthAuthorizationCode>
    suspend fun exchangeCode(command: OAuthCodeExchange): OAuthOutcome<OAuthTokenResponse>
    suspend fun refresh(command: OAuthRefreshExchange): OAuthOutcome<OAuthTokenResponse>
    suspend fun revokeToken(clientId: String, token: PresentedCredential)
    suspend fun listConnections(browserSession: PresentedCredential, limit: Int, cursor: String?): OAuthOutcome<OAuthConnectionPage>
    suspend fun connectionSummary(browserSession: PresentedCredential, clientId: String): OAuthOutcome<OAuthConnectionSummary>
    suspend fun revokeConnection(browserSession: PresentedCredential, grantId: Uuid): OAuthOutcome<Unit>
    suspend fun validateCredential(credential: PresentedCredential, expectedResource: ExpectedOAuthResource): OAuthOutcome<OAuthAgentIdentity>
    suspend fun listOrganizations(credential: PresentedCredential, expectedResource: ExpectedOAuthResource): OAuthOutcome<List<OAuthOrganization>>
    suspend fun listWorkspaces(credential: PresentedCredential, expectedResource: ExpectedOAuthResource): OAuthOutcome<List<OAuthWorkspace>>
}

sealed interface OAuthOutcome<out T> {
    data class Success<T>(val value: T) : OAuthOutcome<T>
    data class Failure(val error: OAuthError) : OAuthOutcome<Nothing>
}
enum class OAuthError(val wireValue: String) {
    INVALID_REQUEST("invalid_request"), INVALID_CLIENT("invalid_client"), INVALID_GRANT("invalid_grant"),
    INVALID_TARGET("invalid_target"), INVALID_SCOPE("invalid_scope"), ACCESS_DENIED("access_denied")
}
data class OAuthClient(val clientId: String, val clientName: String, val redirectUris: Set<String>)
data class OAuthAuthorizationRequest(
    val clientId: String, val redirectUri: String, val resource: String,
    val scopes: Set<String>, val codeChallenge: String, val codeChallengeMethod: String,
)
class OAuthConsentCommand(val browserSession: PresentedCredential, val request: OAuthAuthorizationRequest)
class OAuthAuthorizationCode(val code: String)
class OAuthCodeExchange(val clientId: String, val redirectUri: String, val resource: String, val code: PresentedCredential, val codeVerifier: PresentedCredential)
class OAuthRefreshExchange(val clientId: String, val resource: String, val refreshToken: PresentedCredential)
class OAuthTokenResponse(val accessToken: String, val refreshToken: String, val expiresIn: Long, val scope: String)
data class OAuthConnection(val grantId: Uuid, val clientId: String, val clientName: String?, val resource: String, val issuedAt: Instant, val expiresAt: Instant, val revokedAt: Instant?, val lastUsedAt: Instant?)
data class OAuthConnectionPage(val connections: List<OAuthConnection>, val nextCursor: String?)
data class OAuthConnectionSummary(val activeCount: Long, val latestIssuedAt: Instant?)
data class OAuthAgentIdentity(val userId: PrincipalId, val clientId: String)
data class OAuthOrganization(val organizationId: OrganizationId, val displayName: String, val role: String)
data class OAuthWorkspace(val workspaceId: WorkspaceId, val organizationId: OrganizationId, val organizationDisplayName: String, val displayName: String, val sample: OAuthWorkspaceSample?)
data class OAuthWorkspaceSample(val dataset: String)
