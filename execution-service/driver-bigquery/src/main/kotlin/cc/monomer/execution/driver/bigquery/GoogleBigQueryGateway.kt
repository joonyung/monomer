package cc.monomer.execution.driver.bigquery

import cc.monomer.execution.driver.spi.DriverOperationTiming
import cc.monomer.execution.driver.spi.DriverPhase
import cc.monomer.execution.driver.spi.BinaryValue
import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.NullQueryParameterType
import cc.monomer.execution.driver.spi.QueryParameter
import cc.monomer.execution.driver.spi.RawSqlValidationException
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.ServiceOptions
import com.google.cloud.bigquery.BigQuery
import com.google.cloud.bigquery.BigQueryException
import com.google.cloud.bigquery.BigQueryOptions
import com.google.cloud.bigquery.Job
import com.google.cloud.bigquery.JobId
import com.google.cloud.bigquery.JobInfo
import com.google.cloud.bigquery.JobStatistics
import com.google.cloud.bigquery.JobStatus
import com.google.cloud.bigquery.QueryJobConfiguration
import com.google.cloud.bigquery.QueryParameterValue
import com.google.cloud.bigquery.StandardSQLTypeName
import com.google.cloud.http.HttpTransportOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Duration

class GoogleBigQueryGatewayFactory(
    private val maximumBytesBilled: Long,
    private val jobTimeout: Duration,
) : BigQueryGatewayFactory {
    init {
        require(maximumBytesBilled > 0)
        require(!jobTimeout.isNegative && !jobTimeout.isZero)
        require(jobTimeout.toMillis() > 0)
    }

    override fun create(
        connector: ConnectorRuntimeBinding,
        credential: MaterializedCredential,
        blockingDispatcher: CoroutineDispatcher,
        clock: Clock,
        callTimeoutPolicy: BigQueryCallTimeoutPolicy,
        timing: DriverOperationTiming,
    ): BigQueryGateway {
        val projectId = connector.requiredGoogleText("project_id")
        val location = connector.requiredGoogleText("location")
        require(projectId.matches(PROJECT_ID_PATTERN))
        require(location.matches(LOCATION_PATTERN))
        return GoogleBigQueryGateway(
            bigQueryClientExecutor =
                CredentialScopedBigQueryClientExecutor(
                    credential = credential,
                    timing = timing,
                    clientFactory =
                        BigQueryClientFactory { jsonCharacters, timeout ->
                            createClient(
                                projectId = projectId,
                                location = location,
                                jsonCharacters = jsonCharacters,
                                timeout = timeout,
                            )
                        },
                ),
            projectId = projectId,
            location = location,
            maximumBytesBilled = maximumBytesBilled,
            jobTimeout = jobTimeout,
            blockingDispatcher = blockingDispatcher,
            clock = clock,
            callTimeoutPolicy = callTimeoutPolicy,
        )
    }

    internal fun createClient(
        projectId: String,
        location: String,
        jsonCharacters: CharArray,
        timeout: BigQueryRpcTimeout,
    ): BigQuery {
        val jsonBytes = ZeroingUtf8Encoder.encode(jsonCharacters)
        val credentials =
            try {
                ServiceAccountCredentials.fromStream(ByteArrayInputStream(jsonBytes))
            } finally {
                jsonBytes.fill(0)
            }
        require(credentials.tokenServerUri.toString() == "https://oauth2.googleapis.com/token") {
            "BigQuery service account token_uri must be https://oauth2.googleapis.com/token"
        }
        require(credentials.universeDomain == "googleapis.com") {
            "BigQuery service account universe_domain must be googleapis.com"
        }
        val timeoutDuration = Duration.ofMillis(timeout.totalTimeoutMillis)
        val retrySettings =
            ServiceOptions.getDefaultRetrySettings()
                .toBuilder()
                .setTotalTimeoutDuration(timeoutDuration)
                .setInitialRpcTimeoutDuration(timeoutDuration)
                .setMaxRpcTimeoutDuration(timeoutDuration)
                .build()
        val transportOptions =
            HttpTransportOptions.newBuilder()
                .setConnectTimeout(timeout.connectTimeoutMillis)
                .setReadTimeout(timeout.readTimeoutMillis)
                .build()
        return BigQueryOptions.newBuilder()
            .setProjectId(projectId)
            .setLocation(location)
            .setCredentials(credentials)
            .setRetrySettings(retrySettings)
            .setTransportOptions(transportOptions)
            .build()
            .service
    }

    private companion object {
        val PROJECT_ID_PATTERN = Regex("[a-z][a-z0-9-]{4,28}[a-z0-9]")
        val LOCATION_PATTERN = Regex("(?![A-Za-z0-9-]*--)[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")
    }
}

internal object ZeroingUtf8Encoder {
    fun encode(characters: CharArray): ByteArray = encode(characters) {}

    internal fun encode(
        characters: CharArray,
        observeIntermediateBuffer: (ByteBuffer) -> Unit,
    ): ByteArray {
        val encoder =
            StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = encoder.encode(CharBuffer.wrap(characters))
        return try {
            observeIntermediateBuffer(encoded)
            ByteArray(encoded.remaining()).also(encoded::get)
        } finally {
            if (encoded.hasArray()) {
                encoded.array().fill(0)
            } else {
                encoded.clear()
                while (encoded.hasRemaining()) {
                    encoded.put(0)
                }
            }
        }
    }
}

internal fun interface BigQueryClientFactory {
    fun create(
        serviceAccountJson: CharArray,
        timeout: BigQueryRpcTimeout,
    ): BigQuery
}

internal interface BigQueryClientExecutor {
    fun <T> execute(
        timeout: BigQueryRpcTimeout,
        operation: (BigQuery) -> T,
    ): T
}

internal class CredentialScopedBigQueryClientExecutor(
    private val credential: MaterializedCredential,
    private val clientFactory: BigQueryClientFactory,
    private val timing: DriverOperationTiming,
) : BigQueryClientExecutor {
    override fun <T> execute(
        timeout: BigQueryRpcTimeout,
        operation: (BigQuery) -> T,
    ): T =
        credential.useSecret("service_account_json") { jsonCharacters ->
            val client = timing.measure(DriverPhase.CREDENTIAL) { clientFactory.create(jsonCharacters, timeout) }
            operation(client)
        }
}

internal data class BigQueryRpcTimeout(
    val connectTimeoutMillis: Int,
    val readTimeoutMillis: Int,
    val totalTimeoutMillis: Long,
) {
    init {
        require(connectTimeoutMillis > 0)
        require(readTimeoutMillis > 0)
        require(totalTimeoutMillis > 0)
        require(connectTimeoutMillis.toLong() <= totalTimeoutMillis)
        require(readTimeoutMillis.toLong() <= totalTimeoutMillis)
    }
}

internal class GoogleBigQueryGateway(
    private val bigQueryClientExecutor: BigQueryClientExecutor,
    private val projectId: String,
    private val location: String,
    private val maximumBytesBilled: Long,
    private val jobTimeout: Duration,
    private val blockingDispatcher: CoroutineDispatcher,
    private val clock: Clock,
    private val callTimeoutPolicy: BigQueryCallTimeoutPolicy,
) : BigQueryGateway {

    override suspend fun validateRawSql(
        sql: String,
        parameters: List<QueryParameter>,
        deadlineEpochMillis: Long,
    ): Unit = try {
        execute(deadlineEpochMillis) { bigQuery ->
            val dryRun =
                bigQuery.create(
                    JobInfo.of(
                        validationConfiguration(
                            sql = sql,
                            parameters = parameters,
                        ),
                    ),
                )
            val statistics =
                requireNotNull(dryRun.getStatistics<JobStatistics.QueryStatistics>()) {
                    "BigQuery dry run did not return query statistics"
                }
            check(
                statistics.statementType ==
                    JobStatistics.QueryStatistics.StatementType.SELECT,
            ) {
                "BigQuery Raw SQL must be a SELECT query"
            }
            check(statistics.schema != null) {
                "BigQuery SELECT dry run did not return a result schema"
            }

        }
    } catch (failure: RawSqlValidationException) {
        throw failure
    } catch (failure: BigQueryCallDeadlineExceededException) {
        throw RawSqlValidationException(
            failureCode = "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED",
            safeMessage = "BigQuery Raw SQL validation deadline was exceeded",
            cause = failure,
        )
    } catch (failure: BigQueryCallTimeoutException) {
        throw RawSqlValidationException(
            failureCode = "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED",
            safeMessage = "BigQuery Raw SQL validation API call timed out",
            cause = failure,
        )
    } catch (failure: BigQueryException) {
        throw RawSqlValidationException(
            failureCode = validationFailureCode(failure),
            safeMessage = "BigQuery rejected Raw SQL validation",
            cause = failure,
        )
    }

    override suspend fun start(
        job: BigQueryJobKey,
        sql: String,
        parameters: List<QueryParameter>,
        maximumRows: ULong,
        deadlineEpochMillis: Long,
    ) {
        execute(deadlineEpochMillis) { bigQuery ->
            requireJobBinding(job)
            require(maximumRows <= Long.MAX_VALUE.toULong())
            val configuration =
                executionConfiguration(
                    sql = sql,
                    parameters = parameters,
                )
            val jobId = job.toGoogleJobId()
            val existing = bigQuery.getJob(jobId)
            if (existing != null) {
                requireSameConfiguration(existing, configuration)
                return@execute
            }
            try {
                bigQuery.create(JobInfo.of(jobId, configuration))
            } catch (failure: RuntimeException) {
                val recovered = bigQuery.getJob(jobId) ?: throw failure
                requireSameConfiguration(recovered, configuration)
            }
        }
    }

    override suspend fun observe(
        job: BigQueryJobKey,
        deadlineEpochMillis: Long,
    ): BigQueryJobSnapshot =
        execute(deadlineEpochMillis) { bigQuery ->
            requireJobBinding(job)
            val current =
                bigQuery.getJob(job.toGoogleJobId())
                    ?: return@execute BigQueryJobSnapshot.Failed("BIGQUERY_JOB_NOT_FOUND", null)
            if (current.status.state != JobStatus.State.DONE) {
                return@execute BigQueryJobSnapshot.Running
            }
            val errorReason = current.status.error?.reason
            if (errorReason != null) {
                return@execute if (errorReason == "stopped") {
                    BigQueryJobSnapshot.Cancelled
                } else {
                    BigQueryJobSnapshot.Failed(
                        if (isPartitionFilterRequired(errorReason, current.status.error?.message)) "PARTITION_FILTER_REQUIRED" else safeFailureCode(errorReason),
                        current.status.error?.message,
                    )
                }
            }
            val statistics = current.getStatistics<JobStatistics.QueryStatistics>()
            BigQueryJobSnapshot.Succeeded(
                bytesProcessed =
                    (statistics.totalBytesProcessed ?: 0L)
                        .coerceAtLeast(0L)
                        .toULong(),
            )
        }

    override suspend fun readResultPage(
        job: BigQueryJobKey,
        pageToken: String?,
        maximumRows: Int,
        deadlineEpochMillis: Long,
    ): BigQueryResultPage =
        execute(deadlineEpochMillis) { bigQuery ->
            requireJobBinding(job)
            require(maximumRows in 1..MAXIMUM_PAGE_ROWS)
            val current =
                requireNotNull(bigQuery.getJob(job.toGoogleJobId())) {
                    "BigQuery job is unavailable"
                }
            check(current.status.state == JobStatus.State.DONE)
            check(current.status.error == null)
            val options =
                buildList {
                    add(BigQuery.QueryResultsOption.pageSize(maximumRows.toLong()))
                    if (pageToken != null) {
                        add(BigQuery.QueryResultsOption.pageToken(pageToken))
                    }
                }.toTypedArray()
            val result = current.getQueryResults(*options)
            val fields =
                requireNotNull(result.schema) {
                    "completed BigQuery result is missing its schema"
                }.fields.toList()
            BigQueryResultPageMapper.map(
                fields = fields,
                values = result.values,
                nextPageToken = result.nextPageToken?.takeIf(String::isNotBlank),
                maximumRows = maximumRows,
            )
        }

    override suspend fun cancel(
        job: BigQueryJobKey,
        deadlineEpochMillis: Long,
    ): Boolean =
        execute(deadlineEpochMillis) { bigQuery ->
            requireJobBinding(job)
            bigQuery.cancel(job.toGoogleJobId())
        }

    override fun close() {
        Unit
    }

    private suspend fun <T> execute(
        deadlineEpochMillis: Long,
        operation: (BigQuery) -> T,
    ): T {
        val timeout = timeout(deadlineEpochMillis)
        return try {
            withTimeout(timeout.totalTimeoutMillis) {
                withContext(blockingDispatcher) {
                    bigQueryClientExecutor.execute(timeout, operation)
                }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw timeoutFailure(deadlineEpochMillis)
        } catch (failure: BigQueryException) {
            if (generateSequence<Throwable>(failure) { it.cause }.any { it is SocketTimeoutException }) {
                throw timeoutFailure(deadlineEpochMillis)
            }
            throw failure
        }
    }

    private fun timeoutFailure(deadlineEpochMillis: Long): RuntimeException =
        if (deadlineEpochMillis <= clock.millis()) {
            BigQueryCallDeadlineExceededException()
        } else {
            BigQueryCallTimeoutException()
        }

    private fun timeout(deadlineEpochMillis: Long): BigQueryRpcTimeout {
        val nowEpochMillis = clock.millis()
        if (deadlineEpochMillis <= nowEpochMillis) {
            throw BigQueryCallDeadlineExceededException()
        }
        val remainingMillis =
            try {
                Math.subtractExact(deadlineEpochMillis, nowEpochMillis)
            } catch (_: ArithmeticException) {
                Long.MAX_VALUE
            }
        val totalTimeoutMillis =
            minOf(callTimeoutPolicy.maximumCallMillis, remainingMillis)
        val transportTimeoutMillis = totalTimeoutMillis.toInt()
        return BigQueryRpcTimeout(
            connectTimeoutMillis = transportTimeoutMillis,
            readTimeoutMillis = transportTimeoutMillis,
            totalTimeoutMillis = totalTimeoutMillis,
        )
    }

    private fun executionConfiguration(
        sql: String,
        parameters: List<QueryParameter>,
    ): QueryJobConfiguration {
        return QueryJobConfiguration.newBuilder(sql)
                .setUseLegacySql(false)
                .setDryRun(false)
                .setMaximumBytesBilled(maximumBytesBilled)
                .setJobTimeoutMs(jobTimeout.toMillis())
                .setUseQueryCache(false)
                .setPositionalParameters(parameters.map(::toGoogleParameter))
                .build()
    }

    private fun validationConfiguration(
        sql: String,
        parameters: List<QueryParameter>,
    ): QueryJobConfiguration =
        QueryJobConfiguration.newBuilder(sql)
            .setUseLegacySql(false)
            .setDryRun(true)
            .setUseQueryCache(false)
            .setPositionalParameters(parameters.map(::toGoogleParameter))
            .build()

    private fun toGoogleParameter(parameter: QueryParameter): QueryParameterValue =
        when (parameter) {
            is QueryParameter.Text -> QueryParameterValue.string(parameter.value)
            is QueryParameter.Integer64 -> QueryParameterValue.int64(parameter.value)
            is QueryParameter.Float64 -> QueryParameterValue.float64(parameter.value)
            is QueryParameter.BooleanValue -> QueryParameterValue.bool(parameter.value)
            is QueryParameter.Decimal -> {
                val decimal = java.math.BigDecimal(parameter.value)
                val type = if (decimal.scale() <= 9 && decimal.precision() - decimal.scale() <= 29) {
                    StandardSQLTypeName.NUMERIC
                } else {
                    StandardSQLTypeName.BIGNUMERIC
                }
                QueryParameterValue.of(decimal, type)
            }
            is QueryParameter.Date -> QueryParameterValue.date(parameter.value.toString())
            is QueryParameter.Timestamp -> QueryParameterValue.timestamp(
                Math.addExact(Math.multiplyExact(parameter.value.epochSecond, 1_000_000L), parameter.value.nano / 1_000L),
            )
            is QueryParameter.Bytes ->
                parameter.value.useBytes(QueryParameterValue::bytes)

            is QueryParameter.Null ->
                QueryParameterValue.of(
                    null,
                    when (parameter.type) {
                        NullQueryParameterType.TEXT -> StandardSQLTypeName.STRING
                        NullQueryParameterType.INTEGER64 -> StandardSQLTypeName.INT64
                        NullQueryParameterType.FLOAT64 -> StandardSQLTypeName.FLOAT64
                        NullQueryParameterType.BOOLEAN -> StandardSQLTypeName.BOOL
                        NullQueryParameterType.BYTES -> StandardSQLTypeName.BYTES
                        NullQueryParameterType.DECIMAL -> StandardSQLTypeName.BIGNUMERIC
                        NullQueryParameterType.DATE -> StandardSQLTypeName.DATE
                        NullQueryParameterType.TIMESTAMP -> StandardSQLTypeName.TIMESTAMP
                    },
                )
        }

    private fun requireJobBinding(job: BigQueryJobKey) {
        require(job.projectId == projectId)
        require(job.location == location)
    }

    private fun requireSameConfiguration(
        job: Job,
        expected: QueryJobConfiguration,
    ) {
        val actual = job.getConfiguration<QueryJobConfiguration>()
        check(actual == expected) {
            "deterministic BigQuery job identifier is bound to another query"
        }
    }

    private fun safeFailureCode(reason: String): String =
        when (reason) {
            "accessDenied" -> "PERMISSION_DENIED"
            "billingTierLimitExceeded",
            "quotaExceeded",
            "rateLimitExceeded",
            "resourcesExceeded",
            -> "RESOURCE_EXHAUSTED"

            "invalidQuery" -> "SYNTAX_OR_OBJECT_ERROR"
            "jobBackendError",
            "backendError",
            "internalError",
            -> "BIGQUERY_TRANSIENT_FAILURE"

            else -> "BIGQUERY_EXECUTION_FAILED"
        }

    private fun validationFailureCode(failure: BigQueryException): String =
        when {
            isPartitionFilterRequired(failure.reason, failure.message) -> "PARTITION_FILTER_REQUIRED"
            failure.code == 401 -> "AUTHENTICATION_FAILED"
            failure.code == 403 || failure.reason == "accessDenied" -> "PERMISSION_DENIED"
            failure.code == 404 || failure.reason == "notFound" -> "RAW_SQL_VALIDATION_REJECTED"
            failure.code == 400 || failure.reason == "invalidQuery" -> "RAW_SQL_VALIDATION_REJECTED"
            failure.code == 429 ||
                failure.reason in
                    setOf(
                        "billingTierLimitExceeded",
                        "quotaExceeded",
                        "rateLimitExceeded",
                        "resourcesExceeded",
                    ) -> "RESOURCE_EXHAUSTED"
            failure.code >= 500 ||
                failure.reason in setOf("jobBackendError", "backendError", "internalError") ->
                "BIGQUERY_TRANSIENT_FAILURE"
            else -> "RAW_SQL_VALIDATION_FAILED"
        }

    private fun BigQueryJobKey.toGoogleJobId(): JobId =
        JobId.newBuilder()
            .setProject(projectId)
            .setLocation(location)
            .setJob(jobId)
            .build()

    private companion object {
        const val MAXIMUM_PAGE_ROWS = 1_024
    }
}

private fun ConnectorRuntimeBinding.requiredGoogleText(name: String): String =
    when (val value = properties[name]) {
        is ConnectorPropertyValue.Text -> value.value
        else -> throw IllegalArgumentException("BigQuery connector property is unavailable")
    }

private fun BinaryValue.useBytes(block: (ByteArray) -> QueryParameterValue): QueryParameterValue {
    val bytes = copyBytes()
    return try {
        block(bytes)
    } finally {
        bytes.fill(0)
    }
}
