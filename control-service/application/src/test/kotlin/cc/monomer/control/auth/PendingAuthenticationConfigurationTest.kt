package cc.monomer.control.auth

import cc.monomer.identity.application.pending.PendingAuthenticationRequests
import cc.monomer.identity.domain.port.TransactionRunner
import io.mockk.mockk
import java.time.Clock
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class PendingAuthenticationConfigurationTest {
    @Test
    fun `pending authentication starts when Identity and Pages JDBC templates coexist`() {
        val identityJdbc = NamedParameterJdbcTemplate(mockk<DataSource>())
        val pagesJdbc = NamedParameterJdbcTemplate(mockk<DataSource>())
        ApplicationContextRunner()
            .withUserConfiguration(PendingAuthenticationConfiguration::class.java)
            .withBean("postgresJdbcTemplate", NamedParameterJdbcTemplate::class.java, { identityJdbc })
            .withBean("pagesJdbcTemplate", NamedParameterJdbcTemplate::class.java, { pagesJdbc })
            .withBean(TransactionRunner::class.java, { mockk() })
            .withBean(Clock::class.java, { Clock.systemUTC() })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(PendingAuthenticationRequests::class.java)
                assertThat(context.getBeansOfType(NamedParameterJdbcTemplate::class.java).keys)
                    .containsExactlyInAnyOrder("postgresJdbcTemplate", "pagesJdbcTemplate")
            }
    }
}
