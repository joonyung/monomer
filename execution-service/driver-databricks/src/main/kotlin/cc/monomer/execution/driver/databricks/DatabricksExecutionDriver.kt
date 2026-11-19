package cc.monomer.execution.driver.databricks

import cc.monomer.execution.driver.spi.*
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.*
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

class DatabricksExecutionDriverProvider(
    private val maximumQuerySeconds: Int,
    private val arrowBatchSize: Int,
    private val maximumCallDuration: Duration,
    private val pollInterval: Duration,
    private val blockingDispatcher: CoroutineDispatcher,
    private val clock: Clock,
    private val httpClient: HttpClient,
) : ExecutionDriverProvider {
    override val capability = DriverCapabilityDescriptor(connectorType = ConnectorType.DATABRICKS)
    init {
        require(maximumQuerySeconds > 0 && arrowBatchSize > 0)
        require(maximumCallDuration.toMillis() > 0 && pollInterval.toMillis() > 0)
        require(httpClient.followRedirects() == HttpClient.Redirect.NEVER)
        val retryAll = System.getProperty("jdk.httpclient.enableAllMethodRetry")
        require(retryAll == null || (retryAll.isNotEmpty() && !retryAll.toBoolean())) {
            "Databricks HTTP client cannot enable retry of all HTTP methods"
        }
    }

    override fun openSession(connector: ConnectorRuntimeBinding, credential: MaterializedCredential?, resultSink: RunResultSink, timing: DriverOperationTiming): ResultProducingRunDriverSession =
        DatabricksExecutionDriverSession(
            connector, timing.measure(DriverPhase.CREDENTIAL) { requireNotNull(credential) }, resultSink, maximumQuerySeconds, arrowBatchSize,
            maximumCallDuration, pollInterval, blockingDispatcher, clock, httpClient, timing,
        )

    override suspend fun validateRawSql(connector: ConnectorRuntimeBinding, credential: MaterializedCredential?, sql: String, parameters: List<QueryParameter>, deadlineEpochMillis: Long, timing: DriverOperationTiming) {
        timing.measure(DriverPhase.PREPARE) { requireQueryParameterNames(ConnectorType.DATABRICKS, parameters) }
        val material = timing.measure(DriverPhase.CREDENTIAL) { requireNotNull(credential) }
        val client = timing.measure(DriverPhase.CONNECT) { DatabricksStatementClient(connector, material, httpClient, blockingDispatcher, clock, maximumCallDuration, timing) }
        val deadline = minOf(deadlineEpochMillis, clock.millis() + maximumQuerySeconds * 1_000L)
        try {
            withTimeout(timing.measure(DriverPhase.PREPARE) { client.remainingMillis(deadline) }) {
                validateDatabricksSql(client, sql, parameters, deadline, pollInterval, blockingDispatcher, clock, arrowBatchSize, timing)
            }
        } catch (_: TimeoutCancellationException) {
            client.cancelAll()
            throw validationFailure("RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", "Databricks Raw SQL validation deadline was exceeded")
        } catch (_: DatabricksDeadlineExceeded) {
            client.cancelAll()
            throw validationFailure("RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", "Databricks Raw SQL validation deadline was exceeded")
        } catch (failure: CancellationException) {
            client.cancelAll()
            throw failure
        } catch (failure: Throwable) {
            client.cancelAll()
            val code = if (clock.millis() >= deadline) "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED" else if (failure is DatabricksFailure) validationCode(failure.code) else "RAW_SQL_VALIDATION_FAILED"
            throw validationFailure(code, if (code == "QUERY_BIND_UNSUPPORTED") BYTES_UNSUPPORTED_MESSAGE else "Databricks Raw SQL validation failed")
        } finally { timing.measure(DriverPhase.CLEANUP) { client.close() } }
    }
}

private class DatabricksExecutionDriverSession(
    connector: ConnectorRuntimeBinding,
    private val credential: MaterializedCredential,
    private val resultSink: RunResultSink,
    private val maximumQuerySeconds: Int,
    private val arrowBatchSize: Int,
    maximumCallDuration: Duration,
    private val pollInterval: Duration,
    private val dispatcher: CoroutineDispatcher,
    private val clock: Clock,
    httpClient: HttpClient,
    private val timing: DriverOperationTiming,
) : ResultProducingRunDriverSession {
    override val connectorType = ConnectorType.DATABRICKS
    private val client = timing.measure(DriverPhase.CONNECT) { DatabricksStatementClient(connector, credential, httpClient, dispatcher, clock, maximumCallDuration, timing) }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val result = CompletableDeferred<DriverPhysicalResult>()
    private var execution: Job? = null

    override suspend fun enter(context: StartExecutionContext) {
        val request = context.assignment.request
        timing.measure(DriverPhase.PREPARE) {
            requireQueryParameterNames(connectorType, request.parameters)
            QueryRequestCanonicalizer.requireCanonical(request)
            check(!closed.get() && started.compareAndSet(false, true))
        }
        val deadline = minOf(context.assignment.deadlineEpochMillis, clock.millis() + maximumQuerySeconds * 1_000L)
        execution = scope.launch {
            val outcome = try {
                withTimeout(timing.measure(DriverPhase.PREPARE) { client.remainingMillis(deadline) }) {
                    timing.measure(DriverPhase.PREPARE) { databricksParameters(request.parameters) }
                    val maximumRows = timing.measure(DriverPhase.PREPARE) {
                        (request.resultTarget as? QueryResultTarget.ArtifactUpload)?.maximumArtifactRows?.let {
                            if (it >= Long.MAX_VALUE.toULong()) throw protocolFailure("Databricks row limit is outside the supported range")
                            it + 1uL
                        }
                    }
                    val submitted = timing.measure(DriverPhase.EXECUTE) {
                        client.submit(request.sql, request.parameters, maximumRows, deadline).also {
                            if (it.path("statement_id").asText("").isBlank()) throw protocolFailure("Databricks statement ID is missing")
                        }
                    }
                    val id = submitted.path("statement_id").asText("")
                    val snapshot = awaitStatement(client, id, submitted, deadline, pollInterval, timing)
                    DatabricksArrowResults(client, dispatcher, clock, arrowBatchSize, timing).emit(id, snapshot, deadline, request, resultSink)
                }
            } catch (_: TimeoutCancellationException) {
                client.cancelAll()
                deadlineResult(false)
            } catch (_: DatabricksDeadlineExceeded) {
                client.cancelAll()
                deadlineResult(false)
            } catch (_: CancellationException) {
                client.cancelAll()
                DriverPhysicalResult.Cancelled
            } catch (failure: Throwable) {
                val resultFailure = when (failure) {
                    is cc.monomer.execution.driver.spi.ResultLimitExceededException -> DatabricksFailure("RESULT_LIMIT_EXCEEDED", requireNotNull(failure.message))
                    is cc.monomer.execution.driver.spi.UnsupportedResultTypeException -> DatabricksFailure("UNSUPPORTED_RESULT_TYPE", requireNotNull(failure.message))
                    else -> failure as? DatabricksFailure
                }
                val safeMessage = resultFailure?.let {
                    if (it.code == "QUERY_BIND_UNSUPPORTED") BYTES_UNSUPPORTED_MESSAGE else client.diagnostic(it, request.parameters)
                } ?: "Databricks execution failed"
                client.cancelAll()
                val code = if (clock.millis() >= deadline) "RUN_DEADLINE_EXCEEDED" else resultFailure?.code ?: "DATABRICKS_EXECUTION_FAILED"
                if (code == "CANCELLED") DriverPhysicalResult.Cancelled else DriverPhysicalResult.Failed(code, safeMessage)
            }
            result.complete(outcome)
        }
        execution?.invokeOnCompletion { failure ->
            if (failure is CancellationException) result.complete(DriverPhysicalResult.Cancelled)
        }
    }
    override suspend fun cancel(): DriverCancellationResult {
        if (!started.get()) return DriverCancellationResult.UNSUPPORTED
        execution?.cancel()
        client.cancelAll()
        return DriverCancellationResult.REQUESTED
    }
    override suspend fun awaitPhysicalResult(): DriverPhysicalResult = result.await()
    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        execution?.cancelAndJoin()
        scope.cancel()
        timing.measure(DriverPhase.CLEANUP) {
            client.close()
            credential.close()
        }
    }
}

private suspend fun validateDatabricksSql(client: DatabricksStatementClient, sql: String, parameters: List<QueryParameter>, deadline: Long, pollInterval: Duration, dispatcher: CoroutineDispatcher, clock: Clock, arrowBatchSize: Int, timing: DriverOperationTiming) {
    timing.measure(DriverPhase.PREPARE) {
        if (sql.toByteArray(Charsets.UTF_8).size > MAXIMUM_RAW_SQL_BYTES) throw DatabricksFailure("RAW_SQL_VALIDATION_REJECTED", "Databricks Raw SQL exceeds the maximum UTF-8 byte length")
        databricksParameters(parameters)
    }
    val submission = timing.measure(DriverPhase.VALIDATE) {
        client.submit("DESCRIBE QUERY $sql", parameters, null, deadline).also {
            if (it.path("statement_id").asText("").isBlank()) throw protocolFailure("Databricks statement ID is missing")
        }
    }
    val id = submission.path("statement_id").asText("")
    val snapshot = awaitStatement(client, id, submission, deadline, pollInterval, timing)
    val target = QueryResultTarget.InlineStream(1_024, 1_024 * 1_024, 128 * 1_024)
    val unsigned = QueryRequest(1u, Sha256Digest("0".repeat(64)), sql, parameters, target, false, emptyList())
    val metadataSink = object : RunResultSink {
        override suspend fun emitInlineResult(batch: InlineResultBatch) = Unit
        override suspend fun openObjectUpload(objectKey: String, mediaType: String): ArtifactUploadSink = error("validation metadata is inline")
    }
    val rows = DatabricksArrowResults(client, dispatcher, clock, arrowBatchSize, timing).emit(id, snapshot, deadline, unsigned, metadataSink).rowsProcessed
    timing.measure(DriverPhase.VALIDATE) {
        if (rows == 0uL) throw DatabricksFailure("RAW_SQL_VALIDATION_REJECTED", "Databricks DESCRIBE QUERY returned no output columns")
    }
}

private suspend fun awaitStatement(client: DatabricksStatementClient, id: String, initial: JsonNode, deadline: Long, pollInterval: Duration, timing: DriverOperationTiming): JsonNode = timing.measure(DriverPhase.WAIT) {
    var snapshot = initial
    currentCoroutineContext().ensureActive()
    while (snapshot.path("status").path("state").asText("") != "SUCCEEDED") {
        when (snapshot.path("status").path("state").asText("")) {
            "PENDING", "RUNNING" -> {
                delay(minOf(pollInterval.toMillis(), client.remainingMillis(deadline)))
                timing.polled()
                snapshot = client.observe(id, deadline)
            }
            "FAILED" -> throw statementFailure(snapshot.path("status"))
            "CANCELED", "CANCELLED" -> throw DatabricksFailure("CANCELLED", "Databricks statement was cancelled")
            else -> throw protocolFailure("Databricks statement state is invalid or unavailable")
        }
        currentCoroutineContext().ensureActive()
    }
    snapshot
}
private fun validationFailure(code: String, message: String) = RawSqlValidationException(code, message, null)
private fun validationCode(code: String): String = when (code) {
    "SYNTAX_ERROR", "OBJECT_NOT_FOUND", "RAW_SQL_VALIDATION_REJECTED" -> "RAW_SQL_VALIDATION_REJECTED"
    "TIMEOUT" -> "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED"
    "QUERY_BIND_UNSUPPORTED", "AUTHENTICATION_FAILED", "PERMISSION_DENIED", "CONNECTION_FAILED", "RESOURCE_EXHAUSTED" -> code
    else -> "RAW_SQL_VALIDATION_FAILED"
}
private fun deadlineResult(validation: Boolean) = DriverPhysicalResult.Failed(
    if (validation) "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED" else "RUN_DEADLINE_EXCEEDED",
    if (validation) "Databricks Raw SQL validation deadline was exceeded" else "Databricks execution exceeded the run deadline",
)
