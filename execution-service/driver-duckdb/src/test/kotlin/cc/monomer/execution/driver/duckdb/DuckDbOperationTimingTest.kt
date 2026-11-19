package cc.monomer.execution.driver.duckdb

import cc.monomer.execution.driver.jdbc.JdbcTimingFixture
import cc.monomer.execution.driver.spi.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DuckDbOperationTimingTest {
    @Test fun `successful sample query and rejected parser validation preserve timing without SQL or paths`() = runBlocking<Unit> {
        DuckDbFixture().use { fixture -> fixture.provider().use { provider ->
            JdbcTimingFixture.summary(ConnectorType.DUCKDB, DriverOperation.QUERY, null, null,
                setOf(DriverPhase.CREDENTIAL, DriverPhase.CONNECT, DriverPhase.VALIDATE, DriverPhase.PREPARE, DriverPhase.EXECUTE, DriverPhase.READ, DriverPhase.DELIVERY, DriverPhase.CLEANUP), additionalSecrets = listOf(fixture.root.toString())) { timing ->
                JdbcTimingFixture.query(provider, sampleConnector, sampleCredential(), timing, JdbcTimingFixture.inlineTarget, JdbcTimingFixture.inlineSink)
            }
            sampleCredential().use { credential ->
                JdbcTimingFixture.summary(ConnectorType.DUCKDB, DriverOperation.VALIDATE, "RAW_SQL_VALIDATION_REJECTED", DriverPhase.VALIDATE, setOf(DriverPhase.VALIDATE, DriverPhase.CLEANUP), additionalSecrets = listOf(fixture.root.toString())) { timing ->
                    val failure = assertFailsWith<RawSqlValidationException> { provider.validateRawSql(sampleConnector, credential, "SELECT 1; SELECT 2", emptyList(), System.currentTimeMillis() + 30_000, timing) }
                    DriverPhysicalResult.Failed(failure.failureCode, failure.safeMessage)
                }
            }
        } }
    }
}
