@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.api.PrincipalDisplayNames
import cc.monomer.identity.domain.port.TransactionRunner
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

@Component
class PostgresPrincipalDisplayNames(
    @param:Qualifier("postgresJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
    private val transactionRunner: TransactionRunner,
) : PrincipalDisplayNames {
    override suspend fun read(userIds: Set<Uuid>): Map<Uuid, String> = transactionRunner.readOnly {
        require(userIds.size <= 200)
        if (userIds.isEmpty()) return@readOnly emptyMap()
        jdbc.query(
            "SELECT id, display_name FROM identity_runtime.principals WHERE id IN (:ids) AND kind = 'user'",
            mapOf("ids" to userIds.map { it.toJavaUuid() }),
        ) { row, _ -> row.getObject("id", java.util.UUID::class.java).toKotlinUuid() to row.getString("display_name") }.toMap()
    }
}
