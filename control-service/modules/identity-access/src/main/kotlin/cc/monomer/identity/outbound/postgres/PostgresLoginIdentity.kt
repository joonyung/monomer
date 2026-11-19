package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.domain.id.LoginIdentityId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.AuthenticationProtocol
import cc.monomer.identity.domain.model.LoginIdentity
import cc.monomer.identity.domain.model.LoginIdentityKey
import cc.monomer.identity.domain.model.LoginIdentityLinkedVia
import cc.monomer.identity.domain.port.LoginIdentityQuery
import cc.monomer.identity.domain.port.LoginIdentityRepository
import cc.monomer.identity.domain.port.TransactionRunner
import java.sql.ResultSet
import java.sql.Timestamp
import kotlin.uuid.toJavaUuid
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository

@Repository
class PostgresLoginIdentityRepository(
    @param:Qualifier("postgresJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) : LoginIdentityRepository {

    override fun find(key: LoginIdentityKey): LoginIdentity? = jdbc.query(
        """
        SELECT id, user_id, protocol, namespace, subject, linked_via, linked_at
          FROM identity_runtime.login_identities
         WHERE protocol = :protocol
           AND namespace = :namespace
           AND subject = :subject
        """.trimIndent(),
        key.parameters(),
    ) { resultSet, _ -> mapLoginIdentity(resultSet) }.firstOrNull()

    override fun insertIfAbsent(loginIdentity: LoginIdentity): Boolean = jdbc.update(
        """
        INSERT INTO identity_runtime.login_identities (
            id, user_id, protocol, namespace, subject, linked_via, linked_at
        ) VALUES (
            :id, :userId, :protocol, :namespace, :subject, :linkedVia, :linkedAt
        )
        ON CONFLICT ON CONSTRAINT login_identities_protocol_namespace_subject_key DO NOTHING
        """.trimIndent(),
        loginIdentity.key.parameters() + mapOf(
            "id" to loginIdentity.id.value.toJavaUuid(),
            "userId" to loginIdentity.userId.value.toJavaUuid(),
            "linkedVia" to loginIdentity.linkedVia.databaseValue,
            "linkedAt" to Timestamp.from(loginIdentity.linkedAt),
        ),
    ) == 1

    private fun LoginIdentityKey.parameters(): Map<String, Any> = mapOf(
        "protocol" to protocol.databaseValue,
        "namespace" to namespace,
        "subject" to subject,
    )

    companion object {
        fun mapLoginIdentity(resultSet: ResultSet): LoginIdentity = LoginIdentity(
            id = LoginIdentityId(resultSet.getUuid("id")),
            userId = UserId(resultSet.getUuid("user_id")),
            key = LoginIdentityKey(
                protocol = AuthenticationProtocol.fromDatabaseValue(resultSet.getString("protocol")),
                namespace = resultSet.getString("namespace"),
                subject = resultSet.getString("subject"),
            ),
            linkedVia = LoginIdentityLinkedVia.fromDatabaseValue(resultSet.getString("linked_via")),
            linkedAt = resultSet.getInstant("linked_at"),
        )
    }
}

@Component
class PostgresLoginIdentityQuery(
    @param:Qualifier("postgresJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
    private val transactionRunner: TransactionRunner,
) : LoginIdentityQuery {

    override suspend fun listByUser(userId: UserId): List<LoginIdentity> = transactionRunner.readOnly {
        jdbc.query(
            """
            SELECT id, user_id, protocol, namespace, subject, linked_via, linked_at
              FROM identity_runtime.login_identities
             WHERE user_id = :userId
             ORDER BY linked_at, id
            """.trimIndent(),
            mapOf("userId" to userId.value.toJavaUuid()),
        ) { resultSet, _ -> PostgresLoginIdentityRepository.mapLoginIdentity(resultSet) }
    }
}
