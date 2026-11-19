package cc.monomer.control

import cc.monomer.identity.outbound.postgres.AuthSchemaContractMismatchException
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.SQLException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ControlProductAuthorityConfigurationTest {
    @Test
    fun `identity schema mismatch closes the shared product authority pool before exposure`() {
        val candidateDataSource = FailingHikariDataSource(
            SQLException("missing contract", "42P01"),
        )

        assertThatThrownBy {
            ControlProductAuthorityConfiguration()
                .verifyIdentitySchemaBeforeExpose(candidateDataSource)
        }.isInstanceOf(AuthSchemaContractMismatchException::class.java)
            .hasMessage(
                "AUTH_SCHEMA_CONTRACT_MISMATCH: database query failed with SQLSTATE 42P01",
            )

        assertThat(candidateDataSource.connectionAttempts).isEqualTo(1)
        assertThat(candidateDataSource.isClosed).isTrue()
    }

    private class FailingHikariDataSource(
        private val connectionFailure: SQLException,
    ) : HikariDataSource() {
        var connectionAttempts = 0
            private set

        override fun getConnection(): Connection {
            connectionAttempts += 1
            throw connectionFailure
        }
    }
}
