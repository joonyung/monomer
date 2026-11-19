package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.User
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.domain.port.UserQuery
import cc.monomer.identity.domain.port.UserRepository
import java.sql.ResultSet
import kotlin.uuid.toJavaUuid
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository

@Repository
class PostgresUserRepository(
    @param:Qualifier("postgresJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) : UserRepository {

    override fun findByIdForUpdate(userId: UserId): User? = jdbc.query(
        "$BASE WHERE u.id = :userId FOR UPDATE OF u",
        mapOf("userId" to userId.value.toJavaUuid()),
    ) { resultSet, _ -> mapUser(resultSet) }.firstOrNull()

    override fun findByPrimaryEmailForUpdate(primaryEmail: String): User? = jdbc.query(
        "$BASE WHERE u.primary_email = :primaryEmail FOR UPDATE OF u",
        mapOf("primaryEmail" to primaryEmail),
    ) { resultSet, _ -> mapUser(resultSet) }.firstOrNull()

    override fun insertIfPrimaryEmailAbsent(user: User): Boolean {
        jdbc.update(
            """
            INSERT INTO identity_runtime.principals (id, kind, display_name)
            VALUES (:id, 'user', :displayName)
            """.trimIndent(),
            mapOf(
                "id" to user.userId.value.toJavaUuid(),
                "displayName" to user.displayName,
            ),
        )
        val inserted = jdbc.update(
            """
            INSERT INTO identity_runtime.users (id, primary_email)
            VALUES (:id, :primaryEmail)
            ON CONFLICT ON CONSTRAINT users_primary_email_unique DO NOTHING
            """.trimIndent(),
            mapOf(
                "id" to user.userId.value.toJavaUuid(),
                "primaryEmail" to user.primaryEmail,
            ),
        ) == 1
        if (!inserted) {
            jdbc.update(
                "DELETE FROM identity_runtime.principals WHERE id = :id",
                mapOf("id" to user.userId.value.toJavaUuid()),
            )
        }
        return inserted
    }

    override fun updateDisplayName(userId: UserId, displayName: String) {
        check(jdbc.update(
            "UPDATE identity_runtime.principals SET display_name = :displayName WHERE id = :id AND kind = 'user'",
            mapOf("id" to userId.value.toJavaUuid(), "displayName" to displayName),
        ) == 1)
    }

    override fun delete(userId: UserId) {
        jdbc.update(
            "DELETE FROM identity_runtime.principals WHERE id = :id",
            mapOf("id" to userId.value.toJavaUuid()),
        )
    }

    companion object {
        val BASE = """
            SELECT u.id, u.primary_email, p.display_name
              FROM identity_runtime.users u
              JOIN identity_runtime.principals p ON p.id = u.id
        """.trimIndent()

        fun mapUser(resultSet: ResultSet): User = User(
            userId = UserId(resultSet.getUuid("id")),
            primaryEmail = resultSet.getString("primary_email"),
            displayName = resultSet.getString("display_name"),
        )
    }
}

@Component
class PostgresUserQuery(
    @param:Qualifier("postgresJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
    private val transactionRunner: TransactionRunner,
) : UserQuery {

    override suspend fun find(id: UserId): User? = transactionRunner.readOnly {
        jdbc.query(
            "${PostgresUserRepository.BASE} WHERE u.id = :id",
            mapOf("id" to id.value.toJavaUuid()),
        ) { resultSet, _ -> PostgresUserRepository.mapUser(resultSet) }.firstOrNull()
    }
}
