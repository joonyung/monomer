package cc.monomer.control.auth

import cc.monomer.identity.application.pending.PendingAuthenticationRequests
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.outbound.postgres.PostgresPendingAuthenticationRepository
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

@Configuration
class PendingAuthenticationConfiguration {
    @Bean
    fun pendingAuthenticationRequests(
        @Qualifier("postgresJdbcTemplate") jdbc: NamedParameterJdbcTemplate,
        transactions: TransactionRunner,
        clock: Clock,
    ) =
        PendingAuthenticationRequests(PostgresPendingAuthenticationRepository(jdbc), transactions, clock)
}
