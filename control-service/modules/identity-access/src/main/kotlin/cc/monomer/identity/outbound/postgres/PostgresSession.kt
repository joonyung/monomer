package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.domain.id.LoginIdentityId
import cc.monomer.identity.domain.id.RefreshTokenId
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.AuthenticationProtocol
import cc.monomer.identity.domain.model.EmailEvidence
import cc.monomer.identity.domain.model.LockedSessionRefreshToken
import cc.monomer.identity.domain.model.RefreshToken
import cc.monomer.identity.domain.model.Session
import cc.monomer.identity.domain.model.SessionRevocationReason
import cc.monomer.identity.domain.port.SessionRepository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import kotlin.uuid.toJavaUuid
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PostgresSessionRepository(
    @param:Qualifier("postgresJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) : SessionRepository {

    override fun findById(sessionId: SessionId): Session? = jdbc.query(
        "SELECT s.*, s.id AS session_id_value, s.revoked_at AS session_revoked_at FROM identity_runtime.sessions s WHERE s.id = :id",
        mapOf("id" to sessionId.value.toJavaUuid()),
    ) { resultSet, _ -> mapSession(resultSet) }.firstOrNull()

    override fun findLatestForUser(userId: UserId): List<Session> = jdbc.query(
        """
        SELECT DISTINCT ON (s.login_identity_id) s.*, s.id AS session_id_value, s.revoked_at AS session_revoked_at
          FROM identity_runtime.sessions s
          JOIN identity_runtime.login_identities i ON i.id = s.login_identity_id AND i.user_id = s.user_id
         WHERE s.user_id = :user AND i.protocol = 'oidc' AND i.namespace = 'https://accounts.google.com'
         ORDER BY s.login_identity_id, s.created_at DESC, s.id DESC
        """.trimIndent(),
        mapOf("user" to userId.value.toJavaUuid()),
    ) { resultSet, _ -> mapSession(resultSet) }

    override fun insert(session: Session) {
        jdbc.update(
            """
            INSERT INTO identity_runtime.sessions (
                id, user_id, login_identity_id, protocol, authentication_source_ref,
                authentication_issuer, asserted_email, asserted_email_verified, email_evidence,
                upstream_authenticated_at, authentication_context, authentication_methods,
                upstream_session_id, created_at, last_refreshed_at, absolute_expires_at,
                revoked_at, revocation_reason
            ) VALUES (
                :id, :userId, :loginIdentityId, :protocol, :authenticationSourceRef,
                :authenticationIssuer, :assertedEmail, :assertedEmailVerified, :emailEvidence,
                :upstreamAuthenticatedAt, :authenticationContext, :authenticationMethods,
                :upstreamSessionId, :createdAt, :lastRefreshedAt, :absoluteExpiresAt,
                :revokedAt, :revocationReason
            )
            """.trimIndent(),
            mapOf(
                "id" to session.id.value.toJavaUuid(),
                "userId" to session.userId.value.toJavaUuid(),
                "loginIdentityId" to session.loginIdentityId.value.toJavaUuid(),
                "protocol" to session.protocol.databaseValue,
                "authenticationSourceRef" to session.authenticationSourceRef,
                "authenticationIssuer" to session.authenticationIssuer,
                "assertedEmail" to session.assertedEmail,
                "assertedEmailVerified" to session.assertedEmailVerified,
                "emailEvidence" to session.emailEvidence.databaseValue,
                "upstreamAuthenticatedAt" to session.upstreamAuthenticatedAt?.let(Timestamp::from),
                "authenticationContext" to session.authenticationContext,
                "authenticationMethods" to session.authenticationMethods.toTypedArray(),
                "upstreamSessionId" to session.upstreamSessionId,
                "createdAt" to Timestamp.from(session.createdAt),
                "lastRefreshedAt" to Timestamp.from(session.lastRefreshedAt),
                "absoluteExpiresAt" to Timestamp.from(session.absoluteExpiresAt),
                "revokedAt" to session.revokedAt?.let(Timestamp::from),
                "revocationReason" to session.revocationReason?.databaseValue,
            ),
        )
    }

    override fun insertRefreshToken(refreshToken: RefreshToken) {
        jdbc.update(
            """
            INSERT INTO identity_runtime.refresh_tokens (id, session_id, token_hash, issued_at, expires_at, revoked_at)
            VALUES (:id, :sessionId, :tokenHash, :issuedAt, :expiresAt, :revokedAt)
            """.trimIndent(),
            mapOf(
                "id" to refreshToken.id.value.toJavaUuid(),
                "sessionId" to refreshToken.sessionId.value.toJavaUuid(),
                "tokenHash" to refreshToken.tokenHash,
                "issuedAt" to Timestamp.from(refreshToken.issuedAt),
                "expiresAt" to Timestamp.from(refreshToken.expiresAt),
                "revokedAt" to refreshToken.revokedAt?.let(Timestamp::from),
            ),
        )
    }

    override fun lockAndFindByRefreshTokenHash(tokenHash: String): LockedSessionRefreshToken? {
        val sessionId = jdbc.query(
            "SELECT session_id FROM identity_runtime.refresh_tokens WHERE token_hash = :tokenHash",
            mapOf("tokenHash" to tokenHash),
        ) { resultSet, _ -> SessionId(resultSet.getUuid("session_id")) }.firstOrNull() ?: return null

        jdbc.query(
            "SELECT id FROM identity_runtime.sessions WHERE id = :sessionId FOR UPDATE",
            mapOf("sessionId" to sessionId.value.toJavaUuid()),
        ) { _, _ -> Unit }.firstOrNull() ?: return null

        return jdbc.query(
            """
            SELECT
                s.id AS session_id_value, s.user_id, s.login_identity_id, s.protocol,
                s.authentication_source_ref, s.authentication_issuer, s.asserted_email,
                s.asserted_email_verified, s.email_evidence, s.upstream_authenticated_at,
                s.authentication_context, s.authentication_methods, s.upstream_session_id,
                s.created_at, s.last_refreshed_at, s.absolute_expires_at,
                s.revoked_at AS session_revoked_at, s.revocation_reason,
                rt.id AS refresh_token_id, rt.token_hash, rt.issued_at AS token_issued_at,
                rt.expires_at AS token_expires_at, rt.revoked_at AS token_revoked_at
              FROM identity_runtime.refresh_tokens rt
              JOIN identity_runtime.sessions s ON s.id = rt.session_id
             WHERE rt.token_hash = :tokenHash
             FOR UPDATE OF rt
            """.trimIndent(),
            mapOf("tokenHash" to tokenHash),
        ) { resultSet, _ -> mapLockedCredential(resultSet) }.firstOrNull()
    }

    override fun markRefreshTokenRevoked(refreshTokenId: RefreshTokenId, revokedAt: Instant): Boolean = jdbc.update(
        "UPDATE identity_runtime.refresh_tokens SET revoked_at = :revokedAt WHERE id = :id AND revoked_at IS NULL",
        mapOf(
            "id" to refreshTokenId.value.toJavaUuid(),
            "revokedAt" to Timestamp.from(revokedAt),
        ),
    ) == 1

    override fun updateLastRefreshedAt(sessionId: SessionId, refreshedAt: Instant) {
        check(jdbc.update(
            "UPDATE identity_runtime.sessions SET last_refreshed_at = :refreshedAt WHERE id = :sessionId AND revoked_at IS NULL",
            mapOf(
                "sessionId" to sessionId.value.toJavaUuid(),
                "refreshedAt" to Timestamp.from(refreshedAt),
            ),
        ) == 1) { "locked live session was not refreshed" }
    }

    override fun revokeSession(sessionId: SessionId, reason: SessionRevocationReason, revokedAt: Instant) {
        jdbc.update(
            """
            UPDATE identity_runtime.sessions
               SET revoked_at = :revokedAt, revocation_reason = :reason
             WHERE id = :sessionId AND revoked_at IS NULL
            """.trimIndent(),
            mapOf(
                "sessionId" to sessionId.value.toJavaUuid(),
                "reason" to reason.databaseValue,
                "revokedAt" to Timestamp.from(revokedAt),
            ),
        )
    }

    override fun revokeLiveRefreshTokens(sessionId: SessionId, revokedAt: Instant) {
        jdbc.update(
            "UPDATE identity_runtime.refresh_tokens SET revoked_at = :revokedAt WHERE session_id = :sessionId AND revoked_at IS NULL",
            mapOf(
                "sessionId" to sessionId.value.toJavaUuid(),
                "revokedAt" to Timestamp.from(revokedAt),
            ),
        )
    }

    override fun revokeAllForUser(userId: UserId, revokedAt: Instant) {
        jdbc.update(
            """
            UPDATE identity_runtime.sessions
               SET revoked_at = :revokedAt, revocation_reason = 'user_wide'
             WHERE user_id = :userId AND revoked_at IS NULL
            """.trimIndent(),
            mapOf(
                "userId" to userId.value.toJavaUuid(),
                "revokedAt" to Timestamp.from(revokedAt),
            ),
        )
        jdbc.update(
            """
            UPDATE identity_runtime.refresh_tokens token
               SET revoked_at = :revokedAt
              FROM identity_runtime.sessions session
             WHERE session.user_id = :userId
               AND token.session_id = session.id
               AND token.revoked_at IS NULL
            """.trimIndent(),
            mapOf(
                "userId" to userId.value.toJavaUuid(),
                "revokedAt" to Timestamp.from(revokedAt),
            ),
        )
    }

    private fun mapSession(resultSet: ResultSet): Session =
        Session(
            id = SessionId(resultSet.getUuid("session_id_value")),
            userId = UserId(resultSet.getUuid("user_id")),
            loginIdentityId = LoginIdentityId(resultSet.getUuid("login_identity_id")),
            protocol = AuthenticationProtocol.fromDatabaseValue(resultSet.getString("protocol")),
            authenticationSourceRef = resultSet.getString("authentication_source_ref"),
            authenticationIssuer = resultSet.getString("authentication_issuer"),
            assertedEmail = resultSet.getString("asserted_email"),
            assertedEmailVerified = resultSet.getBoolean("asserted_email_verified"),
            emailEvidence = EmailEvidence.fromDatabaseValue(resultSet.getString("email_evidence")),
            upstreamAuthenticatedAt = resultSet.getInstantNullable("upstream_authenticated_at"),
            authenticationContext = resultSet.getString("authentication_context"),
            authenticationMethods = (resultSet.getArray("authentication_methods").array as Array<*>)
                .map { it.toString() },
            upstreamSessionId = resultSet.getString("upstream_session_id"),
            createdAt = resultSet.getInstant("created_at"),
            lastRefreshedAt = resultSet.getInstant("last_refreshed_at"),
            absoluteExpiresAt = resultSet.getInstant("absolute_expires_at"),
            revokedAt = resultSet.getInstantNullable("session_revoked_at"),
            revocationReason = resultSet.getString("revocation_reason")?.let(SessionRevocationReason::fromDatabaseValue),
        )

    private fun mapLockedCredential(resultSet: ResultSet): LockedSessionRefreshToken {
        val sessionId = SessionId(resultSet.getUuid("session_id_value"))
        val session = mapSession(resultSet)
        return LockedSessionRefreshToken(
            session = session,
            refreshToken = RefreshToken(
                id = RefreshTokenId(resultSet.getUuid("refresh_token_id")),
                sessionId = sessionId,
                tokenHash = resultSet.getString("token_hash"),
                issuedAt = resultSet.getInstant("token_issued_at"),
                expiresAt = resultSet.getInstant("token_expires_at"),
                revokedAt = resultSet.getInstantNullable("token_revoked_at"),
            ),
        )
    }
}
