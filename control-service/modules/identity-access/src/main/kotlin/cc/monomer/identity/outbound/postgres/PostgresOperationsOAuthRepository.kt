package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.application.operations.*
import cc.monomer.identity.application.agent.AgentOAuthRepository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class PostgresOperationsOAuthRepository(private val jdbc: NamedParameterJdbcTemplate,
    private val browserCredentials: AgentOAuthRepository) : OperationsOAuthRepository {
    override fun browserUser(credential: PresentedCredential, now: Instant) = browserCredentials.currentBrowserUser(credential, now)?.toString()
    override fun currentUser(userId: String) = jdbc.query("SELECT 1 FROM identity_runtime.users u JOIN identity_runtime.principals p ON p.id=u.id AND p.kind='user' WHERE u.id=:id",
        mapOf("id" to UUID.fromString(userId))) { _, _ -> true }.isNotEmpty()
    override fun insertCode(hash: String, code: OperationsStoredCode) {
        jdbc.update("""INSERT INTO identity_runtime.operations_authorization_codes(code_hash,user_id,client_id,redirect_uri,resource,code_challenge,expires_at)
            VALUES(:hash,:user,:client,:redirect,:resource,:challenge,:expires)""", mapOf("hash" to hash,"user" to UUID.fromString(code.userId),
            "client" to code.authorization.clientId,"redirect" to code.authorization.redirectUri,"resource" to code.authorization.resource,
            "challenge" to code.authorization.challenge,"expires" to Timestamp.from(code.expiresAt)))
    }
    override fun lockCode(hash: String) = jdbc.query("SELECT * FROM identity_runtime.operations_authorization_codes WHERE code_hash=:hash FOR UPDATE", mapOf("hash" to hash)) { rs, _ ->
        OperationsStoredCode(rs.getString("user_id"), OperationsAuthorization(rs.getString("client_id"),rs.getString("redirect_uri"),rs.getString("resource"),rs.getString("code_challenge")),rs.getTimestamp("expires_at").toInstant()) }.firstOrNull()
    override fun deleteCode(hash: String) { jdbc.update("DELETE FROM identity_runtime.operations_authorization_codes WHERE code_hash=:hash", mapOf("hash" to hash)) }
    override fun insertSession(hash: String, session: OperationsStoredSession) {
        jdbc.update("""INSERT INTO identity_runtime.operations_sessions(token_hash,user_id,client_id,resource,expires_at) VALUES(:hash,:user,:client,:resource,:expires)""",
            mapOf("hash" to hash,"user" to UUID.fromString(session.userId),"client" to session.clientId,"resource" to session.resource,"expires" to Timestamp.from(session.expiresAt)))
    }
    override fun session(hash: String) = jdbc.query("SELECT * FROM identity_runtime.operations_sessions WHERE token_hash=:hash",mapOf("hash" to hash)) { rs, _ ->
        OperationsStoredSession(rs.getString("user_id"),rs.getString("client_id"),rs.getString("resource"),rs.getTimestamp("expires_at").toInstant()) }.firstOrNull()
    override fun revoke(hash: String) { jdbc.update("DELETE FROM identity_runtime.operations_sessions WHERE token_hash=:hash", mapOf("hash" to hash)) }
}
