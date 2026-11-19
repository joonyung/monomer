package cc.monomer.control.auth.inbound.operationsoauth

import cc.monomer.control.auth.inbound.agentoauth.AgentOAuthProperties
import cc.monomer.identity.application.operations.OperationsOAuthService
import cc.monomer.identity.domain.model.DeveloperAccessPolicy
import cc.monomer.identity.domain.port.CurrentUserCredentialVerifier
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.outbound.postgres.PostgresAgentOAuthRepository
import cc.monomer.identity.outbound.postgres.PostgresOperationsOAuthRepository
import java.time.Clock
import java.util.UUID
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

@Configuration(proxyBeanMethods = false)
class OperationsOAuthConfiguration {
    @Bean
    fun operationsOAuthService(@Qualifier("postgresJdbcTemplate") jdbc: NamedParameterJdbcTemplate,
        verifier: CurrentUserCredentialVerifier, developerAccessPolicy: DeveloperAccessPolicy,
        transactions: TransactionRunner, clock: Clock, properties: AgentOAuthProperties,
        @Value("\${MONOMER_OPERATIONS_ALLOWED_USER_IDS:}") allowedUserIds: String): OperationsOAuthService {
        val allowed = allowedUserIds.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
        require(allowed.all { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) })
        return OperationsOAuthService(PostgresOperationsOAuthRepository(jdbc, PostgresAgentOAuthRepository(jdbc, verifier, developerAccessPolicy)),
            transactions, clock, allowed, "${properties.issuer.trimEnd('/')}/ops/v1")
    }
}
