package cc.monomer.identity.application.agent

import cc.monomer.identity.api.*
import cc.monomer.identity.application.OpaqueCredentials
import cc.monomer.identity.domain.model.AgentOAuthGrantUse
import cc.monomer.identity.domain.port.TransactionRunner
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import kotlin.uuid.Uuid

class AgentOAuthService(
    private val repository: AgentOAuthRepository,
    private val transactionRunner: TransactionRunner,
    private val clients: OAuthClientResolver,
    private val clock: Clock,
    private val audiences: Map<ExpectedOAuthResource, String>,
) : AgentOAuthPort {
    private val hasher = AgentOAuthTokenHasher()

    init {
        require(audiences.keys == ExpectedOAuthResource.entries.toSet())
        require(audiences.values.all(String::isNotBlank) && audiences.values.toSet().size == audiences.size)
    }

    override suspend fun inspectAuthorization(request: OAuthAuthorizationRequest): OAuthOutcome<OAuthClient> {
        if (request.resource !in audiences.values) return failure(OAuthError.INVALID_TARGET)
        if (request.scopes != setOf(AgentScope.WORKSPACE.wireValue)) return failure(OAuthError.INVALID_SCOPE)
        if (request.codeChallengeMethod != "S256" || !Regex("[A-Za-z0-9_-]{43}").matches(request.codeChallenge)) return failure(OAuthError.INVALID_REQUEST)
        // Work done through the connection records this id, so an id that cannot be recorded never gets a grant.
        if (!AgentClientId.isValid(request.clientId)) return failure(OAuthError.INVALID_CLIENT)
        val client = clients.resolve(request.clientId) ?: return failure(OAuthError.INVALID_CLIENT)
        if (!clients.acceptsRedirect(client, request.redirectUri)) return failure(OAuthError.INVALID_REQUEST)
        return OAuthOutcome.Success(client)
    }

    override suspend fun authorize(command: OAuthConsentCommand): OAuthOutcome<OAuthAuthorizationCode> {
        val inspection = inspectAuthorization(command.request)
        if (inspection is OAuthOutcome.Failure) return inspection
        val client = (inspection as OAuthOutcome.Success).value
        return transactionRunner.run {
            val now = clock.instant()
            val userId = repository.currentBrowserUser(command.browserSession, now) ?: return@run failure(OAuthError.ACCESS_DENIED)
            if (!repository.lockCurrentUser(userId)) return@run failure(OAuthError.ACCESS_DENIED)
            val grant = OAuthStoredGrant(Uuid.generateV7(), userId, client.clientId, client.clientName, command.request.resource, now, null)
            val code = OpaqueCredentials.generate()
            repository.insertGrant(grant)
            repository.insertCode(hasher.hash(code), grant.id, command.request.redirectUri, command.request.codeChallenge, now.plusSeconds(300))
            OAuthOutcome.Success(OAuthAuthorizationCode(code))
        }
    }

    override suspend fun exchangeCode(command: OAuthCodeExchange): OAuthOutcome<OAuthTokenResponse> = transactionRunner.run {
        val now = clock.instant()
        val hash = hasher.hash(command.code.value)
        val code = repository.lockCode(hash) ?: return@run failure(OAuthError.INVALID_GRANT)
        val grant = repository.lockGrant(code.grantId) ?: return@run failure(OAuthError.INVALID_GRANT)
        if (code.redirectUri != command.redirectUri || grant.clientId != command.clientId || grant.audience != command.resource ||
            !validVerifier(command.codeVerifier.value, code.challenge)) return@run failure(OAuthError.INVALID_GRANT)
        if (code.consumedAt != null) {
            repository.revokeGrant(grant.id, now)
            return@run failure(OAuthError.INVALID_GRANT)
        }
        if (!now.isBefore(code.expiresAt) || !validGrant(grant, command.clientId, command.resource)) return@run failure(OAuthError.INVALID_GRANT)
        repository.consumeCode(hash, now)
        OAuthOutcome.Success(issue(grant))
    }

    override suspend fun refresh(command: OAuthRefreshExchange): OAuthOutcome<OAuthTokenResponse> = transactionRunner.run {
        val now = clock.instant()
        val hash = hasher.hash(command.refreshToken.value)
        val grantId = repository.findRefreshGrant(hash) ?: return@run failure(OAuthError.INVALID_GRANT)
        val grant = repository.lockGrant(grantId) ?: return@run failure(OAuthError.INVALID_GRANT)
        val refresh = repository.lockRefresh(hash) ?: return@run failure(OAuthError.INVALID_GRANT)
        if (grant.clientId != command.clientId || grant.audience != command.resource) return@run failure(OAuthError.INVALID_GRANT)
        if (refresh.consumedAt != null) {
            repository.revokeGrant(grantId, now)
            return@run failure(OAuthError.INVALID_GRANT)
        }
        if (!now.isBefore(refresh.expiresAt) || !validGrant(grant, command.clientId, command.resource)) return@run failure(OAuthError.INVALID_GRANT)
        repository.consumeRefresh(hash, now)
        OAuthOutcome.Success(issue(grant))
    }

    override suspend fun revokeToken(clientId: String, token: PresentedCredential) = transactionRunner.run {
        val grantId = repository.findTokenGrant(hasher.hash(token.value)) ?: return@run
        val grant = repository.lockGrant(grantId) ?: return@run
        if (grant.clientId == clientId) repository.revokeGrant(grantId, clock.instant())
    }

    override suspend fun listConnections(browserSession: PresentedCredential, limit: Int, cursor: String?): OAuthOutcome<OAuthConnectionPage> = transactionRunner.readOnly {
        val user = repository.currentBrowserUser(browserSession, clock.instant()) ?: return@readOnly failure(OAuthError.ACCESS_DENIED)
        if (limit !in 1..100) return@readOnly failure(OAuthError.INVALID_REQUEST)
        val before = cursor?.let { decodeConnectionCursor(it, user) ?: return@readOnly failure(OAuthError.INVALID_REQUEST) }
        val rows = repository.connections(user, limit + 1, before)
        val connections = rows.take(limit)
        val nextCursor = if (rows.size > limit) connections.last().let { connection ->
            encodeConnectionCursor(user, OAuthConnectionBoundary(connection.issuedAt, connection.grantId))
        } else null
        OAuthOutcome.Success(OAuthConnectionPage(connections, nextCursor))
    }

    override suspend fun connectionSummary(browserSession: PresentedCredential, clientId: String): OAuthOutcome<OAuthConnectionSummary> = transactionRunner.readOnly {
        val now = clock.instant()
        val user = repository.currentBrowserUser(browserSession, now) ?: return@readOnly failure(OAuthError.ACCESS_DENIED)
        OAuthOutcome.Success(repository.connectionSummary(user, clientId, now))
    }

    private fun encodeConnectionCursor(userId: Uuid, before: OAuthConnectionBoundary): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("1|$userId|${before.issuedAt}|${before.grantId}".toByteArray(Charsets.UTF_8))

    private fun decodeConnectionCursor(cursor: String, userId: Uuid): OAuthConnectionBoundary? {
        if (cursor.length !in 1..512) return null
        return try {
            val parts = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8).split('|')
            if (parts.size != 4 || parts[0] != "1" || parts[1] != userId.toString()) return null
            if (!parts[2].matches(Regex("[0-9]{4}-.*"))) return null
            val before = OAuthConnectionBoundary(Instant.parse(parts[2]), Uuid.parse(parts[3]))
            before.takeIf { encodeConnectionCursor(userId, it) == cursor }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: DateTimeParseException) {
            null
        }
    }

    override suspend fun revokeConnection(browserSession: PresentedCredential, grantId: Uuid): OAuthOutcome<Unit> = transactionRunner.run {
        val user = repository.currentBrowserUser(browserSession, clock.instant()) ?: return@run failure(OAuthError.ACCESS_DENIED)
        val grant = repository.lockGrant(grantId)
        if (grant != null && grant.userId == user) repository.revokeGrant(grantId, clock.instant())
        OAuthOutcome.Success(Unit)
    }

    override suspend fun validateCredential(credential: PresentedCredential, expectedResource: ExpectedOAuthResource): OAuthOutcome<OAuthAgentIdentity> =
        authenticated(credential, expectedResource) { identity -> identity }

    override suspend fun listOrganizations(credential: PresentedCredential, expectedResource: ExpectedOAuthResource): OAuthOutcome<List<OAuthOrganization>> =
        authenticated(credential, expectedResource, repository::organizations)

    override suspend fun listWorkspaces(credential: PresentedCredential, expectedResource: ExpectedOAuthResource): OAuthOutcome<List<OAuthWorkspace>> =
        authenticated(credential, expectedResource, repository::workspaces)

    // The hot path stays read-only; the grant's use is recorded at most once per interval, outside any transaction.
    private suspend fun <T> authenticated(
        credential: PresentedCredential,
        expectedResource: ExpectedOAuthResource,
        read: (OAuthAgentIdentity) -> T,
    ): OAuthOutcome<T> {
        val now = clock.instant()
        val (grant, value) = transactionRunner.readOnly {
            repository.credential(hasher.hash(credential.value), audiences.getValue(expectedResource), now)?.let { grant -> grant to read(grant.identity) }
        } ?: return failure(OAuthError.ACCESS_DENIED)
        if (AgentOAuthGrantUse.recordingDue(grant.lastUsedAt, now)) repository.recordGrantUse(grant.grantId, now)
        return OAuthOutcome.Success(value)
    }

    private fun validGrant(grant: OAuthStoredGrant, clientId: String, resource: String): Boolean =
        grant.revokedAt == null && grant.clientId == clientId && grant.audience == resource &&
            clock.instant().isBefore(grant.expiresAt) && repository.lockCurrentUser(grant.userId)

    private fun issue(grant: OAuthStoredGrant): OAuthTokenResponse {
        val now = clock.instant()
        val access = OpaqueCredentials.generate()
        val refresh = OpaqueCredentials.generate()
        val expiry = minOf(now.plusSeconds(900), grant.expiresAt)
        repository.insertAccess(hasher.hash(access), grant.id, now, expiry)
        repository.insertRefresh(hasher.hash(refresh), grant.id, now, grant.expiresAt)
        return OAuthTokenResponse(access, refresh, java.time.Duration.between(now, expiry).seconds, AgentScope.WORKSPACE.wireValue)
    }

    private fun validVerifier(verifier: String, challenge: String): Boolean {
        if (!Regex("[A-Za-z0-9._~-]{43,128}").matches(verifier)) return false
        return OpaqueCredentials.matchesS256CodeChallenge(verifier, challenge)
    }
    private fun failure(error: OAuthError) = OAuthOutcome.Failure(error)
}
