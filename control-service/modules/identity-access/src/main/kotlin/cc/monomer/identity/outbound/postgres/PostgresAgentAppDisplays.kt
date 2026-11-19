package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.api.AgentAppDisplay
import cc.monomer.identity.api.AgentAppDisplays
import cc.monomer.identity.api.AgentClientId
import cc.monomer.identity.api.verifiedHost
import cc.monomer.identity.domain.port.TransactionRunner
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

@Component
class PostgresAgentAppDisplays(
    @param:Qualifier("postgresJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
    private val transactionRunner: TransactionRunner,
) : AgentAppDisplays {
    override suspend fun read(clientIds: Set<AgentClientId>): Map<AgentClientId, AgentAppDisplay> {
        require(clientIds.size <= AgentAppDisplays.MAX_CLIENT_IDS)
        if (clientIds.isEmpty()) return emptyMap()
        val latestNames = transactionRunner.readOnly {
            jdbc.query(
                """
                SELECT DISTINCT ON (oauth_client_id) oauth_client_id, client_label
                  FROM identity_runtime.agent_oauth_grants
                 WHERE oauth_client_id IN (:clientIds) AND client_label IS NOT NULL AND btrim(client_label) <> ''
                 ORDER BY oauth_client_id, issued_at DESC, id DESC
                """.trimIndent(),
                mapOf("clientIds" to clientIds.map(AgentClientId::value)),
            ) { row, _ -> row.getString("oauth_client_id") to row.getString("client_label") }.toMap()
        }
        return clientIds.mapNotNull { clientId ->
            val host = clientId.verifiedHost()
            val name = latestNames[clientId.value] ?: host ?: return@mapNotNull null
            clientId to AgentAppDisplay(clientId, name, host)
        }.toMap()
    }
}
