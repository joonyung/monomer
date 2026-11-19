package cc.monomer.execution.driver.bigquery

import cc.monomer.execution.driver.spi.ConnectorId
import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.QueryParameter
import cc.monomer.execution.driver.spi.RawSqlValidationException
import cc.monomer.execution.driver.spi.SecretCharacters
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BigQueryRawSqlValidationIntegrationTest {
    @Test
    fun providerPassesDynamicSourcesToNativeValidation() = runTest {
        val gateway = RecordingGateway()
        credential().use { credential ->
            val sql = "SELECT * FROM EXTERNAL_QUERY('connection', 'SELECT 1') WHERE x = ?"
            provider(gateway).validateRawSql(connector(), credential, sql, listOf(QueryParameter.Text("paid", name = "")), DEADLINE_EPOCH_MILLIS, timing = testTiming())
            assertEquals(sql, gateway.sql)
            assertEquals(listOf(QueryParameter.Text("paid", name = "")), gateway.parameters)
            assertEquals(DEADLINE_EPOCH_MILLIS, gateway.deadlineEpochMillis)
            assertEquals(1, gateway.validationCalls)
        }
    }

    @Test
    fun expiredDeadlineRemainsTypedAndDoesNotOpenNativeValidation() = runTest {
        val gateway = RecordingGateway()
        credential().use { credential ->
            val failure =
                assertFailsWith<RawSqlValidationException> {
                    provider(gateway).validateRawSql(
                        connector = connector(),
                        credential = credential,
                        sql = "SELECT 1",
                        parameters = emptyList(),
                        deadlineEpochMillis = CLOCK.millis(),
                        timing = testTiming(),
                    )
                }

            assertEquals("RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", failure.failureCode)
            assertEquals(0, gateway.validationCalls)
        }
    }

    private fun provider(gateway: RecordingGateway): BigQueryExecutionDriverProvider =
        BigQueryExecutionDriverProvider(
            gatewayFactory = BigQueryGatewayFactory { _, _, _, _, _, _ -> gateway },
            pollInterval = Duration.ofMillis(1),
            blockingDispatcher = Dispatchers.Unconfined,
            clock = CLOCK,
            callTimeoutPolicy = BigQueryCallTimeoutPolicy(Duration.ofSeconds(5)),
        )

    private fun connector(): ConnectorRuntimeBinding =
        ConnectorRuntimeBinding(
            schemaVersion = 1u,
            connectorId = ConnectorId.parse("019f0000-0000-7000-8000-000000000003"),
            connectorType = ConnectorType.BIGQUERY,
            properties =
                mapOf(
                    "project_id" to ConnectorPropertyValue.Text("example-project"),
                    "location" to ConnectorPropertyValue.Text("asia-northeast3"),
                ),
        )

    private fun credential(): MaterializedCredential =
        MaterializedCredential(
            mapOf(
                "service_account_json" to SecretCharacters.copyOf("{}".toCharArray()),
            ),
        )

    private class RecordingGateway : BigQueryGateway {
        var sql: String? = null
        var parameters: List<QueryParameter> = emptyList()
        var deadlineEpochMillis: Long? = null
        var validationCalls = 0

        override suspend fun validateRawSql(
            sql: String,
            parameters: List<QueryParameter>,
            deadlineEpochMillis: Long,
        ): Unit {
            validationCalls += 1
            this.sql = sql
            this.parameters = parameters
            this.deadlineEpochMillis = deadlineEpochMillis
        }

        override suspend fun start(
            job: BigQueryJobKey,
            sql: String,
            parameters: List<QueryParameter>,
            maximumRows: ULong,
            deadlineEpochMillis: Long,
        ) = error("not used")

        override suspend fun observe(
            job: BigQueryJobKey,
            deadlineEpochMillis: Long,
        ): BigQueryJobSnapshot = error("not used")

        override suspend fun readResultPage(
            job: BigQueryJobKey,
            pageToken: String?,
            maximumRows: Int,
            deadlineEpochMillis: Long,
        ): BigQueryResultPage = error("not used")

        override suspend fun cancel(
            job: BigQueryJobKey,
            deadlineEpochMillis: Long,
        ): Boolean = error("not used")

        override fun close() = Unit
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)
        const val DEADLINE_EPOCH_MILLIS = 6_000L
    }
}
