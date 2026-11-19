package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.domain.model.DeveloperAccessPolicy

import cc.monomer.identity.api.*
import cc.monomer.identity.application.agent.*
import cc.monomer.identity.domain.model.UserCredentialProvenance
import cc.monomer.identity.domain.port.CurrentUserCredentialVerifier
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager

class PostgresAgentOAuthRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val verifier: CurrentUserCredentialVerifier,
    private val developerAccessPolicy: DeveloperAccessPolicy,
) : AgentOAuthRepository {
    override fun currentBrowserUser(credential: PresentedCredential, now: Instant): Uuid? {
        val verified = verifier.verifyCurrentCredential(credential.value) ?: return null
        val session = verified.provenance as? UserCredentialProvenance.BrowserSession ?: return null
        if (!now.isBefore(verified.expiresAt)) return null
        if (!developerAccessPolicy.isOpen && PostgresSessionRepository(jdbc).findById(session.sessionId)?.let(developerAccessPolicy::allows) != true) return null
        return jdbc.query("""SELECT s.user_id FROM identity_runtime.sessions s
            JOIN identity_runtime.users u ON u.id=s.user_id JOIN identity_runtime.principals p ON p.id=u.id AND p.kind='user'
            WHERE s.id=:id AND s.user_id=:user AND s.revoked_at IS NULL AND s.absolute_expires_at>:now""",
            mapOf("id" to session.sessionId.value.toJavaUuid(), "user" to session.userId.value.toJavaUuid(), "now" to Timestamp.from(now)),
        ) { rs, _ -> rs.getUuid("user_id") }.firstOrNull()
    }
    override fun lockCurrentUser(userId: Uuid): Boolean {
        writeTransaction()
        if (!allowsDeveloper(userId)) return false
        return jdbc.query("""SELECT u.id FROM identity_runtime.users u
            JOIN identity_runtime.principals p ON p.id=u.id AND p.kind='user'
            WHERE u.id=:user FOR KEY SHARE OF u""",
            mapOf("user" to userId.toJavaUuid()),
        ) { _, _ -> true }.firstOrNull() == true
    }
    override fun insertGrant(grant: OAuthStoredGrant) {
        writeTransaction()
        jdbc.update("""INSERT INTO identity_runtime.agent_oauth_grants(id,owner_user_id,oauth_client_id,client_label,resource_audience,scopes,issued_at)
            VALUES(:id,:user,:client,:label,:audience,ARRAY['agent.workspace'],:now)""",
            mapOf("id" to grant.id.toJavaUuid(), "user" to grant.userId.toJavaUuid(), "client" to grant.clientId,
                "label" to grant.clientName, "audience" to grant.audience, "now" to Timestamp.from(grant.issuedAt)))
    }
    override fun insertCode(hash: String, grantId: Uuid, redirectUri: String, challenge: String, expiresAt: Instant) {
        writeTransaction()
        jdbc.update("""INSERT INTO identity_runtime.agent_oauth_authorization_codes(code_hash,grant_id,redirect_uri,code_challenge,expires_at)
            VALUES(:hash,:grant,:redirect,:challenge,:expires)""", mapOf("hash" to hash,"grant" to grantId.toJavaUuid(),"redirect" to redirectUri,"challenge" to challenge,"expires" to Timestamp.from(expiresAt)))
    }
    override fun lockCode(hash: String): OAuthStoredCode? {
        writeTransaction()
        return jdbc.query("SELECT * FROM identity_runtime.agent_oauth_authorization_codes WHERE code_hash=:hash FOR UPDATE", mapOf("hash" to hash)) { rs, _ ->
            OAuthStoredCode(rs.getUuid("grant_id"),rs.getString("redirect_uri"),rs.getString("code_challenge"),rs.getInstant("expires_at"),rs.getInstantNullable("consumed_at")) }.firstOrNull()
    }
    override fun consumeCode(hash: String, now: Instant) = consume("agent_oauth_authorization_codes", "code_hash", hash, now)
    override fun lockGrant(grantId: Uuid): OAuthStoredGrant? {
        writeTransaction()
        return jdbc.query("SELECT * FROM identity_runtime.agent_oauth_grants WHERE id=:id FOR UPDATE", mapOf("id" to grantId.toJavaUuid())) { rs, _ -> grant(rs) }.firstOrNull()
    }
    override fun insertAccess(hash: String, grantId: Uuid, now: Instant, expiresAt: Instant) = insertToken("agent_oauth_access_tokens", hash, grantId, now, expiresAt)
    override fun insertRefresh(hash: String, grantId: Uuid, now: Instant, expiresAt: Instant) = insertToken("agent_oauth_refresh_tokens", hash, grantId, now, expiresAt)
    override fun findRefreshGrant(hash: String): Uuid? = jdbc.query("SELECT grant_id FROM identity_runtime.agent_oauth_refresh_tokens WHERE token_hash=:hash", mapOf("hash" to hash)) { rs, _ -> rs.getUuid("grant_id") }.firstOrNull()
    override fun lockRefresh(hash: String): OAuthStoredRefresh? {
        writeTransaction()
        return jdbc.query("SELECT * FROM identity_runtime.agent_oauth_refresh_tokens WHERE token_hash=:hash FOR UPDATE",mapOf("hash" to hash)) { rs, _ ->
            OAuthStoredRefresh(rs.getUuid("grant_id"),rs.getInstant("expires_at"),rs.getInstantNullable("consumed_at")) }.firstOrNull()
    }
    override fun consumeRefresh(hash: String, now: Instant) = consume("agent_oauth_refresh_tokens", "token_hash", hash, now)
    override fun revokeGrant(grantId: Uuid, now: Instant) {
        writeTransaction()
        jdbc.update("UPDATE identity_runtime.agent_oauth_grants SET revoked_at=COALESCE(revoked_at,:now) WHERE id=:id",mapOf("id" to grantId.toJavaUuid(),"now" to Timestamp.from(now)))
    }
    override fun findTokenGrant(hash: String): Uuid? = jdbc.query("""SELECT grant_id FROM identity_runtime.agent_oauth_access_tokens WHERE token_hash=:hash
        UNION SELECT grant_id FROM identity_runtime.agent_oauth_refresh_tokens WHERE token_hash=:hash""", mapOf("hash" to hash)) { rs, _ -> rs.getUuid("grant_id") }.firstOrNull()
    override fun connections(userId: Uuid, limit: Int, before: OAuthConnectionBoundary?): List<OAuthConnection> = jdbc.query(
        """SELECT * FROM identity_runtime.agent_oauth_grants WHERE owner_user_id=:user
            ${if (before == null) "" else "AND (issued_at,id)<(:issuedAt,:grant)"}
            ORDER BY issued_at DESC,id DESC LIMIT :limit""",
        mapOf("user" to userId.toJavaUuid(), "limit" to limit,
            "issuedAt" to before?.issuedAt?.let(Timestamp::from), "grant" to before?.grantId?.toJavaUuid()),
    ) { rs, _ ->
        val grant = grant(rs)
        OAuthConnection(grant.id,grant.clientId,grant.clientName,grant.audience,grant.issuedAt,grant.expiresAt,grant.revokedAt,rs.getInstantNullable("last_used_at"))
    }
    override fun connectionSummary(userId: Uuid, clientId: String, now: Instant): OAuthConnectionSummary = jdbc.query(
        """SELECT count(*) AS active_count,max(issued_at) AS latest_issued_at FROM identity_runtime.agent_oauth_grants
            WHERE owner_user_id=:user AND oauth_client_id=:client AND revoked_at IS NULL
            AND issued_at>:cutoff""",
        mapOf("user" to userId.toJavaUuid(), "client" to clientId, "cutoff" to Timestamp.from(now.minusSeconds(30L * 24 * 3600))),
    ) { rs, _ -> OAuthConnectionSummary(rs.getLong("active_count"),rs.getInstantNullable("latest_issued_at")) }.single()
    override fun credential(hash: String, audience: String, now: Instant): OAuthAuthenticatedGrant? = jdbc.query("""SELECT g.id,g.owner_user_id,g.oauth_client_id,g.last_used_at FROM identity_runtime.agent_oauth_access_tokens t
        JOIN identity_runtime.agent_oauth_grants g ON g.id=t.grant_id
        JOIN identity_runtime.users u ON u.id=g.owner_user_id JOIN identity_runtime.principals p ON p.id=u.id AND p.kind='user'
        WHERE t.token_hash=:hash AND t.revoked_at IS NULL AND t.expires_at>:now AND g.revoked_at IS NULL
          AND g.issued_at + INTERVAL '30 days'>:now AND g.resource_audience=:audience AND g.scopes @> ARRAY['agent.workspace']::text[]""",
        mapOf("hash" to hash,"audience" to audience,"now" to Timestamp.from(now))) { rs, _ ->
            OAuthAuthenticatedGrant(OAuthAgentIdentity(PrincipalId(rs.getUuid("owner_user_id")), rs.getString("oauth_client_id")), rs.getUuid("id"), rs.getInstantNullable("last_used_at"))
        }.firstOrNull()?.takeIf { allowsDeveloper(it.identity.userId.value) }
    override suspend fun recordGrantUse(grantId: Uuid, now: Instant) =
        recordAgentGrantUse(checkNotNull(jdbc.jdbcTemplate.dataSource), grantId, now)
    override fun organizations(identity: OAuthAgentIdentity): List<OAuthOrganization> = jdbc.query("""SELECT a.id,a.display_name,m.role FROM identity_runtime.organizations a
        JOIN identity_runtime.organization_members m ON m.organization_id=a.id AND m.user_id=:user ORDER BY a.id""",
        mapOf("user" to identity.userId.value.toJavaUuid())) { rs, _ -> OAuthOrganization(OrganizationId(rs.getUuid("id")),rs.getString("display_name"),rs.getString("role")) }
    override fun workspaces(identity: OAuthAgentIdentity): List<OAuthWorkspace> = jdbc.query("""SELECT w.id,w.organization_id,a.display_name AS organization_display_name,w.display_name,w.sample_dataset FROM identity_runtime.workspaces w
        JOIN identity_runtime.organizations a ON a.id=w.organization_id
        JOIN identity_runtime.organization_members am ON am.organization_id=a.id AND am.user_id=:user
        JOIN identity_runtime.workspace_members m ON m.workspace_id=w.id AND m.user_id=:user
        WHERE w.sample_state IS DISTINCT FROM 'PROVISIONING' ORDER BY a.id,w.id""",
        mapOf("user" to identity.userId.value.toJavaUuid())) { rs, _ -> OAuthWorkspace(WorkspaceId(rs.getUuid("id")),OrganizationId(rs.getUuid("organization_id")),rs.getString("organization_display_name"),rs.getString("display_name"),
            rs.getString("sample_dataset")?.let(::OAuthWorkspaceSample)) }

    private fun allowsDeveloper(userId: Uuid): Boolean = developerAccessPolicy.isOpen ||
        PostgresSessionRepository(jdbc).findLatestForUser(cc.monomer.identity.domain.id.UserId(userId)).any(developerAccessPolicy::allows)

    private fun grant(rs: ResultSet) = OAuthStoredGrant(rs.getUuid("id"),rs.getUuid("owner_user_id"),rs.getString("oauth_client_id"),rs.getString("client_label"),rs.getString("resource_audience"),rs.getInstant("issued_at"),rs.getInstantNullable("revoked_at"))
    private fun insertToken(table: String, hash: String, grantId: Uuid, now: Instant, expiresAt: Instant) {
        writeTransaction()
        jdbc.update("INSERT INTO identity_runtime.$table(id,grant_id,token_hash,issued_at,expires_at) VALUES(:id,:grant,:hash,:now,:expires)",
            mapOf("id" to Uuid.generateV7().toJavaUuid(),"grant" to grantId.toJavaUuid(),"hash" to hash,"now" to Timestamp.from(now),"expires" to Timestamp.from(expiresAt)))
    }
    private fun consume(table: String, column: String, hash: String, now: Instant) {
        writeTransaction()
        check(jdbc.update("UPDATE identity_runtime.$table SET consumed_at=:now WHERE $column=:hash AND consumed_at IS NULL",mapOf("hash" to hash,"now" to Timestamp.from(now))) == 1)
    }
    private fun writeTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive() && !TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        check(TransactionSynchronizationManager.hasResource(checkNotNull(jdbc.jdbcTemplate.dataSource)))
    }
}

