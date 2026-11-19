package cc.monomer.identity.outbound.postgres

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.SQLException
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AuthSchemaVerifierTest {
    @Test
    fun `only connection SQLSTATE class and cannot-connect-now are retryable`() {
        assertThat(AuthSchemaVerifier.isRetryableSqlState("08001")).isTrue()
        assertThat(AuthSchemaVerifier.isRetryableSqlState("08006")).isTrue()
        assertThat(AuthSchemaVerifier.isRetryableSqlState("57P03")).isTrue()
        assertThat(AuthSchemaVerifier.isRetryableSqlState("57014")).isFalse()
        assertThat(AuthSchemaVerifier.isRetryableSqlState("42P01")).isFalse()
        assertThat(AuthSchemaVerifier.isRetryableSqlState("42501")).isFalse()
        assertThat(AuthSchemaVerifier.isRetryableSqlState(null)).isFalse()
    }

    @Test
    fun `connection unavailability retries only to the configured bound`() {
        val dataSource = mockk<DataSource>()
        val retryDelays = mutableListOf<Long>()
        every { dataSource.connection } throws SQLException("unavailable", "08006")

        assertThatThrownBy {
            AuthSchemaVerifier(
                dataSource = dataSource,
                maximumAttempts = 3,
                retryDelayMillis = 25,
                sleep = retryDelays::add,
            ).verify()
        }.isInstanceOf(AuthSchemaUnavailableException::class.java)
            .hasMessage(
                "AUTH_SCHEMA_CONTRACT_UNAVAILABLE: PostgreSQL remained unavailable after 3 attempts",
            )

        verify(exactly = 3) { dataSource.connection }
        assertThat(retryDelays).containsExactly(25, 25)
    }

    @Test
    fun `contract and permission SQL failures do not retry`() {
        listOf("42P01", "42501").forEach { sqlState ->
            val dataSource = mockk<DataSource>()
            val retryDelays = mutableListOf<Long>()
            every { dataSource.connection } throws SQLException("contract failure", sqlState)

            assertThatThrownBy {
                AuthSchemaVerifier(
                    dataSource = dataSource,
                    maximumAttempts = 5,
                    retryDelayMillis = 25,
                    sleep = retryDelays::add,
                ).verify()
            }.isInstanceOf(AuthSchemaContractMismatchException::class.java)
                .hasMessage(
                    "AUTH_SCHEMA_CONTRACT_MISMATCH: database query failed with SQLSTATE $sqlState",
                )

            verify(exactly = 1) { dataSource.connection }
            assertThat(retryDelays).isEmpty()
        }
    }

    @Test
    fun `non-retryable nested SQLSTATE prevents an availability wrapper from retrying`() {
        val dataSource = mockk<DataSource>()
        val retryDelays = mutableListOf<Long>()
        val permissionFailure = SQLException("permission denied", "42501")
        every { dataSource.connection } throws SQLException("pool unavailable", "08001", permissionFailure)

        assertThatThrownBy {
            AuthSchemaVerifier(
                dataSource = dataSource,
                maximumAttempts = 5,
                retryDelayMillis = 25,
                sleep = retryDelays::add,
            ).verify()
        }.isInstanceOf(AuthSchemaContractMismatchException::class.java)

        verify(exactly = 1) { dataSource.connection }
        assertThat(retryDelays).isEmpty()
    }

    @Test
    fun `non-retryable next exception prevents a retryable SQLSTATE chain from retrying`() {
        val dataSource = mockk<DataSource>()
        val retryDelays = mutableListOf<Long>()
        val availabilityFailure = SQLException("pool unavailable", "08001").apply {
            setNextException(SQLException("permission denied", "42501"))
        }
        every { dataSource.connection } throws availabilityFailure

        assertThatThrownBy {
            AuthSchemaVerifier(
                dataSource = dataSource,
                maximumAttempts = 5,
                retryDelayMillis = 25,
                sleep = retryDelays::add,
            ).verify()
        }.isInstanceOf(AuthSchemaContractMismatchException::class.java)

        verify(exactly = 1) { dataSource.connection }
        assertThat(retryDelays).isEmpty()
    }
}
