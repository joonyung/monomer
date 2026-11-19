package cc.monomer.execution.driver.bigquery

import cc.monomer.execution.driver.spi.QueryParameter
import cc.monomer.execution.driver.spi.RawSqlValidationException
import com.google.api.services.bigquery.model.JobStatistics as ApiJobStatistics
import com.google.api.services.bigquery.model.JobStatistics2 as ApiQueryStatistics
import com.google.api.services.bigquery.model.TableFieldSchema as ApiTableFieldSchema
import com.google.api.services.bigquery.model.TableSchema as ApiTableSchema
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.bigquery.BigQuery
import com.google.cloud.bigquery.BigQueryException
import com.google.cloud.bigquery.BigQueryOptions
import com.google.cloud.bigquery.Job
import com.google.cloud.bigquery.JobInfo
import com.google.cloud.bigquery.JobStatistics
import com.google.cloud.bigquery.QueryJobConfiguration
import com.google.cloud.bigquery.TableId
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoogleBigQueryGatewayValidationTest {
    @Test
    fun partitionFilterDryRunFailureIsPermanentAndOrdinaryInvalidQueryStaysDistinct() = runTest {
        val message = "Cannot query over table 'project.dataset.events' without a filter over column 'event_date' that can be used for partition elimination"
        val failure = BigQueryException(400, message, com.google.cloud.bigquery.BigQueryError("invalidQuery", null, message))
        val rejected = assertFailsWith<RawSqlValidationException> {
            gateway(RecordingBigQuery(dryRunFailure = failure)).validateRawSql("SELECT 1", emptyList(), DEADLINE_EPOCH_MILLIS)
        }
        assertEquals("PARTITION_FILTER_REQUIRED", rejected.failureCode)
        assertFalse(isPartitionFilterRequired("accessDenied", message))
        assertFalse(isPartitionFilterRequired("invalidQuery", "Unrecognized name: event_date"))
    }

    @Test
    fun typedParametersPreserveDecimalDateTimestampAndNulls() = runTest {
        val client = RecordingBigQuery()
        gateway(client).validateRawSql(
            "SELECT ?, ?, ?, ?, ?, ?, ?",
            listOf(
                QueryParameter.Decimal("1.23456789", name = ""),
                QueryParameter.Decimal("1.234567890123456789", name = ""),
                QueryParameter.Decimal("123456789012345678901234567890", name = ""),
                QueryParameter.Date(java.time.LocalDate.parse("2026-10-06"), name = ""),
                QueryParameter.Timestamp(Instant.parse("2026-10-06T01:02:03.123456Z"), name = ""),
                QueryParameter.Null(cc.monomer.execution.driver.spi.NullQueryParameterType.DECIMAL, name = ""),
                QueryParameter.Null(cc.monomer.execution.driver.spi.NullQueryParameterType.TIMESTAMP, name = ""),
            ),
            DEADLINE_EPOCH_MILLIS,
        )
        val binds = client.createdJob!!.getConfiguration<QueryJobConfiguration>().positionalParameters
        assertEquals(listOf("NUMERIC", "BIGNUMERIC", "BIGNUMERIC", "DATE", "TIMESTAMP", "BIGNUMERIC", "TIMESTAMP"), binds.map { it.type.name })
        assertEquals("1.234567890123456789", binds[1].value)
        assertEquals("2026-10-06", binds[3].value)
        assertEquals(null, binds[5].value)
    }

    @Test
    fun nativeJobErrorRetainsWarehouseMessage() = runTest {
        val reason = "Cannot query over table 'project.dataset.events' without a filter over column 'event_date' that can be used for partition elimination"
        val client = RecordingBigQuery(nativeFailure = reason)
        val snapshot = gateway(client).observe(BigQueryJobKey("project", "asia-northeast3", "job"), DEADLINE_EPOCH_MILLIS)
        assertEquals(BigQueryJobSnapshot.Failed("PARTITION_FILTER_REQUIRED", reason), snapshot)
    }

    @Test
    fun dryRunValidatesExactSqlAndParametersWithoutMetadataCalls() = runTest {
        val sql = "SELECT ? FROM EXTERNAL_QUERY('connection', 'SELECT 1')"
        val client = RecordingBigQuery()
        gateway(client).validateRawSql(sql, listOf(QueryParameter.Text("paid", name = "")), DEADLINE_EPOCH_MILLIS)
        assertTrue(client.requestedTables.isEmpty())
        val configuration = client.createdJob!!.getConfiguration<QueryJobConfiguration>()
        assertEquals(sql, configuration.query)
        assertTrue(configuration.dryRun())
        assertFalse(configuration.useLegacySql())
        assertFalse(configuration.useQueryCache())
        assertEquals("paid", configuration.positionalParameters.single().value)
    }

    @Test
    fun `forwards native SQL corpus unchanged to dry run request`() = runTest {
        val corpus: List<Pair<String, List<QueryParameter>>> =
            listOf(
                "SELECT 1;" to emptyList(),
                "-- leading comment\n/* block ; comment */ SELECT ';' AS value" to emptyList(),
                "SELECT 'it''s;valid' AS value" to emptyList(),
                "SELECT 1 AS `MixedCase`" to emptyList(),
                "WITH source AS (SELECT 1 AS n) SELECT n FROM source" to emptyList(),
                "WITH RECURSIVE source(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM source WHERE n < 3) SELECT n FROM source" to emptyList(),
                "SELECT ROW_NUMBER() OVER (ORDER BY 1) AS row_number" to emptyList(),
                "SELECT STRUCT(1 AS id).id AS value" to emptyList(),
                "SELECT ? AS status" to listOf(QueryParameter.Text("paid'; DELETE FROM orders; --", name = "")),
            )

        corpus.forEach { (sql, parameters) ->
            val client = RecordingBigQuery()
            gateway(client).validateRawSql(sql, parameters, DEADLINE_EPOCH_MILLIS)
            val configuration = client.createdJob!!.getConfiguration<QueryJobConfiguration>()
            assertEquals(sql, configuration.query)
            assertTrue(configuration.dryRun())
            assertFalse(configuration.useLegacySql())
            assertEquals(parameters.size, configuration.positionalParameters.size)
            if (parameters.isNotEmpty()) {
                assertEquals(parameters.map { (it as QueryParameter.Text).value }, configuration.positionalParameters.map { it.value })
            }
        }
    }

    @Test
    fun rejectsNativeNonSelectStatementTypes() = runTest {
        listOf("SCRIPT", "INSERT", "CREATE_TABLE_AS_SELECT", "EXPORT_DATA", "DELETE").forEach { statementType ->
            assertFailsWith<IllegalStateException>(statementType) {
                gateway(RecordingBigQuery(statementType = statementType))
                    .validateRawSql("SELECT 1", emptyList(), DEADLINE_EPOCH_MILLIS)
            }
        }
    }

    @Test
    fun nativeInvalidQueryAndValidationDeadlineRemainTyped() = runTest {
        val invalidQuery =
            assertFailsWith<RawSqlValidationException> {
                gateway(
                    RecordingBigQuery(
                        dryRunFailure = BigQueryException(400, "invalid query"),
                    ),
                ).validateRawSql(
                    sql = "SELECT * FROM `project.dataset.orders`",
                    parameters = emptyList(),
                    deadlineEpochMillis = DEADLINE_EPOCH_MILLIS,
                )
            }
        assertEquals("RAW_SQL_VALIDATION_REJECTED", invalidQuery.failureCode)

        val expired =
            assertFailsWith<RawSqlValidationException> {
                gateway(RecordingBigQuery()).validateRawSql(
                    sql = "SELECT 1",
                    parameters = emptyList(),
                    deadlineEpochMillis = CLOCK.millis(),
                )
            }
        assertEquals("RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", expired.failureCode)
    }

    @Test
    fun missingObjectIsValidationRejectionWhileInfrastructureCodesStayDistinct() = runTest {
        listOf(401 to "AUTHENTICATION_FAILED", 404 to "RAW_SQL_VALIDATION_REJECTED", 403 to "PERMISSION_DENIED",
            429 to "RESOURCE_EXHAUSTED", 503 to "BIGQUERY_TRANSIENT_FAILURE").forEach { (status, expectedCode) ->
            val client = RecordingBigQuery(dryRunFailure = BigQueryException(status, "warehouse failure"))
            val failure = assertFailsWith<RawSqlValidationException> {
                gateway(client).validateRawSql("SELECT 1", emptyList(), DEADLINE_EPOCH_MILLIS)
            }
            assertEquals(expectedCode, failure.failureCode)
            assertTrue(client.createdJob!!.getConfiguration<QueryJobConfiguration>().dryRun())
        }
    }

    private fun gateway(client: RecordingBigQuery): GoogleBigQueryGateway =
        GoogleBigQueryGateway(
            bigQueryClientExecutor =
                object : BigQueryClientExecutor {
                    override fun <T> execute(
                        timeout: BigQueryRpcTimeout,
                        operation: (BigQuery) -> T,
                    ): T = operation(client.proxy)
                },
            projectId = "project",
            location = "asia-northeast3",
            maximumBytesBilled = 1_000_000,
            jobTimeout = Duration.ofSeconds(30),
            blockingDispatcher = Dispatchers.Unconfined,
            clock = CLOCK,
            callTimeoutPolicy = BigQueryCallTimeoutPolicy(Duration.ofSeconds(5)),
        )

    private class RecordingBigQuery(
        private val dryRunFailure: BigQueryException? = null,
        private val nativeFailure: String? = null,
        private val statementType: String = "SELECT",
    ) : InvocationHandler {
        private val options =
            BigQueryOptions.newBuilder()
                .setProjectId("project")
                .setLocation("asia-northeast3")
                .setCredentials(
                    GoogleCredentials.create(
                        AccessToken("test-token", Date(Long.MAX_VALUE)),
                    ),
                )
                .build()
        val proxy: BigQuery =
            Proxy.newProxyInstance(
                BigQuery::class.java.classLoader,
                arrayOf(BigQuery::class.java),
                this,
            ) as BigQuery
        val requestedTables = mutableListOf<TableId>()
        var createdJob: JobInfo? = null

        override fun invoke(
            ignoredProxy: Any,
            method: Method,
            arguments: Array<out Any?>?,
        ): Any? {
            val values = arguments.orEmpty()
            return when {
                method.name == "getTable" && values.firstOrNull() is TableId -> {
                    requestedTables += values[0] as TableId
                    error("validation must not read table metadata")
                }
                method.name == "create" && values.firstOrNull() is JobInfo -> {
                    createdJob = values[0] as JobInfo
                    dryRunFailure?.let { throw it }
                    dryRunJob(createdJob!!)
                }
                method.name == "getJob" -> dryRunJob(JobInfo.of(QueryJobConfiguration.of("SELECT 1")))
                method.name == "getOptions" -> options
                method.name == "toString" -> "RecordingBigQuery"
                method.name == "hashCode" -> System.identityHashCode(this)
                method.name == "equals" -> ignoredProxy === values.firstOrNull()
                else -> error("unexpected BigQuery call: ${method.name}")
            }
        }

        private fun dryRunJob(configuration: JobInfo): Job {
            val apiStatistics =
                ApiJobStatistics().setQuery(
                    ApiQueryStatistics()
                        .setStatementType(statementType)
                        .setSchema(
                            ApiTableSchema().setFields(
                                listOf(ApiTableFieldSchema().setName("value").setType("STRING")),
                            ),
                        ),
                )
            val fromPb =
                JobStatistics.QueryStatistics::class.java.getDeclaredMethod(
                    "fromPb",
                    ApiJobStatistics::class.java,
                )
            fromPb.isAccessible = true
            val statistics = fromPb.invoke(null, apiStatistics) as JobStatistics.QueryStatistics
            val builder = configuration.toBuilder()
            nativeFailure?.let { message ->
                val apiStatus = com.google.api.services.bigquery.model.JobStatus()
                    .setState("DONE")
                    .setErrorResult(com.google.api.services.bigquery.model.ErrorProto().setReason("invalidQuery").setMessage(message))
                val fromStatus = com.google.cloud.bigquery.JobStatus::class.java.getDeclaredMethod(
                    "fromPb", com.google.api.services.bigquery.model.JobStatus::class.java,
                )
                fromStatus.isAccessible = true
                val setStatus = builder.javaClass.getDeclaredMethod("setStatus", com.google.cloud.bigquery.JobStatus::class.java)
                setStatus.isAccessible = true
                setStatus.invoke(builder, fromStatus.invoke(null, apiStatus))
            }
            val setStatistics =
                builder.javaClass.getDeclaredMethod(
                    "setStatistics",
                    JobStatistics::class.java,
                )
            setStatistics.isAccessible = true
            setStatistics.invoke(builder, statistics)
            val constructor =
                Job::class.java.declaredConstructors.single { candidate ->
                    candidate.parameterCount == 2 &&
                        candidate.parameterTypes[0] == BigQuery::class.java &&
                        candidate.parameterTypes[1].isAssignableFrom(builder.javaClass)
                }
            constructor.isAccessible = true
            return constructor.newInstance(proxy, builder) as Job
        }
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)
        const val DEADLINE_EPOCH_MILLIS = 6_000L
    }
}
