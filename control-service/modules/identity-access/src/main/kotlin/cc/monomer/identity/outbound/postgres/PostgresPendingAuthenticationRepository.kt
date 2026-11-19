package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.application.pending.*
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager

class PostgresPendingAuthenticationRepository(private val jdbc: NamedParameterJdbcTemplate) : PendingAuthenticationRepository {
    override fun knownBrowser(browserHash: String, now: Instant): Boolean = jdbc.query(
        "SELECT 1 FROM identity_runtime.pending_authentication_requests WHERE browser_hash=:browser AND expires_at>:now LIMIT 1",
        mapOf("browser" to browserHash, "now" to Timestamp.from(now))) { _, _ -> true }.isNotEmpty()

    override fun create(requestHash: String, browserHash: String, kind: PendingAuthenticationKind,
        payload: PendingAuthenticationPayload, now: Instant, expiresAt: Instant): Boolean {
        writeTransaction()
        val encoded = Json.encodeToString(payload)
        require(encoded.toByteArray().size <= 32768)
        val params = parameters(requestHash, browserHash, kind, now) + mapOf("payload" to encoded,
            "expires" to Timestamp.from(expiresAt), "maximum" to kind.maximum)
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:browser || ':' || :kind, 0))", params) { _, _ -> Unit }
        jdbc.update("""DELETE FROM identity_runtime.pending_authentication_requests WHERE request_hash IN
            (SELECT request_hash FROM identity_runtime.pending_authentication_requests WHERE expires_at<=:now
             ORDER BY expires_at,request_hash LIMIT 128 FOR UPDATE SKIP LOCKED)""", params)
        if (jdbc.query("SELECT 1 FROM identity_runtime.pending_authentication_requests WHERE request_hash=:request", params) { _, _ -> true }.isNotEmpty()) return false
        jdbc.update("""DELETE FROM identity_runtime.pending_authentication_requests WHERE request_hash IN
            (SELECT request_hash FROM identity_runtime.pending_authentication_requests WHERE browser_hash=:browser AND kind=:kind AND expires_at>:now
             ORDER BY created_at DESC,request_hash DESC OFFSET (:maximum - 1))""", params)
        jdbc.update("""INSERT INTO identity_runtime.pending_authentication_requests
            (request_hash,browser_hash,kind,payload,created_at,expires_at) VALUES (:request,:browser,:kind,CAST(:payload AS jsonb),:now,:expires)""", params)
        return true
    }
    override fun find(requestHash: String, browserHash: String, kind: PendingAuthenticationKind, now: Instant): PendingAuthenticationRequest? =
        jdbc.query("SELECT * FROM identity_runtime.pending_authentication_requests WHERE request_hash=:request AND browser_hash=:browser AND kind=:kind AND expires_at>:now",
            parameters(requestHash,browserHash,kind,now)) { rs, _ -> row(rs) }.firstOrNull()

    override fun consume(requestHash: String, browserHash: String, kind: PendingAuthenticationKind, now: Instant,
        userId: String?, csrfToken: String?): PendingAuthenticationRequest? {
        writeTransaction()
        val guard = if (kind in setOf(PendingAuthenticationKind.AGENT, PendingAuthenticationKind.OPERATIONS)) " AND user_id=:user AND payload->>'csrfToken'=:csrf" else ""
        return jdbc.query("DELETE FROM identity_runtime.pending_authentication_requests WHERE request_hash=:request AND browser_hash=:browser AND kind=:kind AND expires_at>:now$guard RETURNING *",
            parameters(requestHash,browserHash,kind,now) + mapOf("user" to userId,"csrf" to csrfToken)) { rs, _ -> row(rs) }.firstOrNull()
    }
    override fun bindConsent(requestHash: String, browserHash: String, kind: PendingAuthenticationKind, now: Instant, userId: String): PendingAuthenticationRequest? {
        writeTransaction()
        return jdbc.query("""UPDATE identity_runtime.pending_authentication_requests SET user_id=:user
            WHERE request_hash=:request AND browser_hash=:browser AND kind=:kind AND expires_at>:now
              AND (user_id IS NULL OR user_id=:user) RETURNING *""",
            parameters(requestHash,browserHash,kind,now) + ("user" to userId)) { rs, _ -> row(rs) }.firstOrNull()
    }
    override fun releaseConsent(requestHash: String, browserHash: String, kind: PendingAuthenticationKind, now: Instant, userId: String,
        csrfToken: String, payload: PendingAuthenticationPayload): Boolean {
        writeTransaction()
        val encoded = Json.encodeToString(payload)
        require(encoded.toByteArray().size <= 32768)
        return jdbc.update("""UPDATE identity_runtime.pending_authentication_requests SET user_id=NULL, payload=CAST(:payload AS jsonb)
            WHERE request_hash=:request AND browser_hash=:browser AND kind=:kind AND expires_at>:now
              AND user_id=:user AND payload->>'csrfToken'=:csrf""",
            parameters(requestHash,browserHash,kind,now) + mapOf("user" to userId, "csrf" to csrfToken, "payload" to encoded)) == 1
    }
    override fun removeInvitation(requestHash: String, browserHash: String) {
        writeTransaction()
        jdbc.update("DELETE FROM identity_runtime.pending_authentication_requests WHERE request_hash=:request AND browser_hash=:browser AND kind='INVITATION'",
            mapOf("request" to requestHash,"browser" to browserHash))
    }
    private fun parameters(requestHash: String, browserHash: String, kind: PendingAuthenticationKind, now: Instant) =
        mapOf("request" to requestHash, "browser" to browserHash, "kind" to kind.name, "now" to Timestamp.from(now))
    private fun row(rs: ResultSet) = PendingAuthenticationRequest(Json.decodeFromString<PendingAuthenticationPayload>(rs.getString("payload")),
        rs.getTimestamp("expires_at").toInstant(), rs.getString("user_id"))
    private fun writeTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive() && !TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        check(TransactionSynchronizationManager.hasResource(checkNotNull(jdbc.jdbcTemplate.dataSource)))
    }
}
