package cc.monomer.execution.driver.bigquery

import cc.monomer.execution.driver.spi.warehouseDiagnostic
import com.google.cloud.bigquery.BigQueryException
import cc.monomer.execution.arrow.TabularArrowEncoder
import cc.monomer.execution.arrow.TabularArrowStreamEncoder
import cc.monomer.execution.arrow.TabularColumn
import cc.monomer.execution.arrow.TabularPage
import cc.monomer.execution.driver.spi.DriverOperationTiming
import cc.monomer.execution.driver.spi.DriverPhase
import cc.monomer.execution.driver.spi.RunResultSink
import cc.monomer.execution.driver.spi.ArrowResultManifest
import cc.monomer.execution.driver.spi.ArtifactUploadSink
import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.DriverCancellationResult
import cc.monomer.execution.driver.spi.DriverCapabilityDescriptor
import cc.monomer.execution.driver.spi.DriverPhysicalResult
import cc.monomer.execution.driver.spi.ExecutionDriverProvider
import cc.monomer.execution.driver.spi.RawSqlValidationException
import cc.monomer.execution.driver.spi.ResultLimitExceededException
import cc.monomer.execution.driver.spi.UnsupportedResultTypeException
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.QueryParameter
import cc.monomer.execution.driver.spi.QueryRequest
import cc.monomer.execution.driver.spi.QueryRequestCanonicalizer
import cc.monomer.execution.driver.spi.QueryResultTarget
import cc.monomer.execution.driver.spi.InlineResultBatch
import cc.monomer.execution.driver.spi.ResultProducingRunDriverSession
import cc.monomer.execution.driver.spi.StartExecutionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Clock
import java.time.Duration

data class BigQueryJobKey(
    val projectId: String,
    val location: String,
    val jobId: String,
)

sealed interface BigQueryJobSnapshot {
    data object Running : BigQueryJobSnapshot

    data class Succeeded(
        val bytesProcessed: ULong,
    ) : BigQueryJobSnapshot

    data class Failed(
        val safeCode: String,
        val message: String?,
    ) : BigQueryJobSnapshot

    data object Cancelled : BigQueryJobSnapshot
}

data class BigQueryResultPage(
    val page: TabularPage,
    val nextPageToken: String?,
) {
    init {
        require(nextPageToken == null || nextPageToken.isNotBlank())
    }
}

data class BigQueryCallTimeoutPolicy(
    val maximumCallDuration: Duration,
) {
    internal val maximumCallMillis = maximumCallDuration.toMillis()

    init {
        require(!maximumCallDuration.isNegative && !maximumCallDuration.isZero)
        require(maximumCallMillis in 1..Int.MAX_VALUE.toLong())
    }
}

internal class BigQueryCallDeadlineExceededException : RuntimeException()

internal class BigQueryCallTimeoutException : RuntimeException()

interface BigQueryGateway : AutoCloseable {
    suspend fun validateRawSql(
        sql: String,
        parameters: List<QueryParameter>,
        deadlineEpochMillis: Long,
    ): Unit

    suspend fun start(
        job: BigQueryJobKey,
        sql: String,
        parameters: List<QueryParameter>,
        maximumRows: ULong,
        deadlineEpochMillis: Long,
    )

    suspend fun observe(
        job: BigQueryJobKey,
        deadlineEpochMillis: Long,
    ): BigQueryJobSnapshot

    suspend fun readResultPage(
        job: BigQueryJobKey,
        pageToken: String?,
        maximumRows: Int,
        deadlineEpochMillis: Long,
    ): BigQueryResultPage

    suspend fun cancel(
        job: BigQueryJobKey,
        deadlineEpochMillis: Long,
    ): Boolean
}

fun interface BigQueryGatewayFactory {
    fun create(
        connector: ConnectorRuntimeBinding,
        credential: MaterializedCredential,
        blockingDispatcher: CoroutineDispatcher,
        clock: Clock,
        callTimeoutPolicy: BigQueryCallTimeoutPolicy,
        timing: DriverOperationTiming,
    ): BigQueryGateway
}

class BigQueryExecutionDriverProvider(
    private val gatewayFactory: BigQueryGatewayFactory,
    private val pollInterval: Duration,
    private val blockingDispatcher: CoroutineDispatcher,
    private val clock: Clock,
    private val callTimeoutPolicy: BigQueryCallTimeoutPolicy,
) : ExecutionDriverProvider {
    override val capability = BIGQUERY_CAPABILITY

    override fun openSession(
        connector: ConnectorRuntimeBinding,
        credential: MaterializedCredential?,
        resultSink: RunResultSink,
        timing: DriverOperationTiming,
    ): ResultProducingRunDriverSession {
        val materializedCredential = timing.measure(DriverPhase.CREDENTIAL) {
            requireNotNull(credential) { "BigQuery credential material was not supplied" }.also {
                require(it.fieldNames() == setOf("service_account_json")) {
                    "BigQuery credential fields do not match the driver contract"
                }
            }
        }
        return BigQueryExecutionDriverSession(
            connector = connector,
            credential = materializedCredential,
            resultSink = resultSink,
            timing = timing,
            gateway =
                timing.measure(DriverPhase.CONNECT) { gatewayFactory.create(
                    connector = connector,
                    credential = materializedCredential,
                    blockingDispatcher = blockingDispatcher,
                    clock = clock,
                    callTimeoutPolicy = callTimeoutPolicy,
                    timing = timing,
                ) },
            pollInterval = pollInterval,
            blockingDispatcher = blockingDispatcher,
            clock = clock,
            callTimeoutPolicy = callTimeoutPolicy,
        )
    }

    override suspend fun validateRawSql(
        connector: ConnectorRuntimeBinding,
        credential: MaterializedCredential?,
        sql: String,
        parameters: List<QueryParameter>,
        deadlineEpochMillis: Long,
        timing: DriverOperationTiming,
    ): Unit {
        timing.measure(DriverPhase.PREPARE) { cc.monomer.execution.driver.spi.requireQueryParameterNames(connector.connectorType, parameters) }
        val materializedCredential = timing.measure(DriverPhase.CREDENTIAL) {
            requireNotNull(credential) { "BigQuery credential material was not supplied" }.also {
                require(it.fieldNames() == setOf("service_account_json")) {
                    "BigQuery credential fields do not match the driver contract"
                }
            }
        }
        return try {
            timing.measure(DriverPhase.VALIDATE) {
                if (deadlineEpochMillis <= clock.millis()) throw BigQueryCallDeadlineExceededException()
            }
            timing.measure(DriverPhase.CONNECT) { gatewayFactory.create(
                connector = connector,
                credential = materializedCredential,
                blockingDispatcher = blockingDispatcher,
                clock = clock,
                callTimeoutPolicy = callTimeoutPolicy,
                timing = timing,
            ) }.use { gateway ->
                timing.measure(DriverPhase.VALIDATE) { gateway.validateRawSql(
                    sql = sql,
                    parameters = parameters,
                    deadlineEpochMillis = deadlineEpochMillis,
                ) }
            }
        } catch (failure: CancellationException) {
            throw failure
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
        } catch (failure: Throwable) {
            throw RawSqlValidationException(
                failureCode = "RAW_SQL_VALIDATION_REJECTED",
                safeMessage = "BigQuery Raw SQL is outside the supported read-query subset",
                cause = failure,
            )
        }
    }
}

internal val BIGQUERY_CAPABILITY =
    DriverCapabilityDescriptor(connectorType = ConnectorType.BIGQUERY)

class BigQueryExecutionDriverSession(
    private val connector: ConnectorRuntimeBinding,
    private val credential: MaterializedCredential,
    private val resultSink: RunResultSink,
    private val gateway: BigQueryGateway,
    private val pollInterval: Duration,
    private val blockingDispatcher: CoroutineDispatcher,
    private val clock: Clock,
    private val callTimeoutPolicy: BigQueryCallTimeoutPolicy,
    private val timing: DriverOperationTiming,
) : ResultProducingRunDriverSession {
    override val connectorType = ConnectorType.BIGQUERY
    private val scope = CoroutineScope(SupervisorJob() + blockingDispatcher)
    private val mutex = Mutex()
    private var closed = false
    private var lifecycleVersion = 0L
    private var activeExecution: ActiveBigQueryExecution? = null
    private val result = CompletableDeferred<DriverPhysicalResult>()

    init {
        require(connector.connectorType == ConnectorType.BIGQUERY)
        require(!pollInterval.isNegative && !pollInterval.isZero)
        require(pollInterval.toMillis() > 0)
    }
    override suspend fun enter(context: StartExecutionContext) {
        val job = timing.measure(DriverPhase.PREPARE) {
            cc.monomer.execution.driver.spi.requireQueryParameterNames(connectorType, context.assignment.request.parameters)
            QueryRequestCanonicalizer.requireCanonical(context.assignment.request)
            BigQueryJobKey(
                projectId = connector.requiredText("project_id"),
                location = connector.requiredText("location"),
                jobId =
                    BigQueryNativeJobId.from(
                        run = context.run,
                        canonicalRequestDigest =
                            context.assignment.request.canonicalRequestDigest,
                        connectorId =
                            context.assignment.connector.connectorId,
                    ),
            )
        }
        if (context.assignment.deadlineEpochMillis <= clock.millis()) {
            timing.markFailure(DriverPhase.PREPARE)
            result.complete(deadlineExceededResult())
            return
        }
        val query = requestQuery(context.assignment.request)
        val execution =
            mutex.withLock {
                check(!closed)
                check(activeExecution == null)
                ActiveBigQueryExecution(
                    job = job,
                    deadlineEpochMillis = context.assignment.deadlineEpochMillis,
                    lifecycleVersion = lifecycleVersion,
                    timing = timing,
                ).also { activeExecution = it }
            }
        try {
            timing.measure(DriverPhase.EXECUTE) { callWithinDeadline(execution.deadlineEpochMillis) {
                gateway.start(
                    job = job,
                    sql = query.sql,
                    parameters = query.parameters,
                    maximumRows = query.maximumRows,
                    deadlineEpochMillis = execution.deadlineEpochMillis,
                )
            } }
        } catch (failure: BigQueryException) {
            clearActiveExecution(execution)
            val failed = DriverPhysicalResult.Failed(
                if (isPartitionFilterRequired(failure.reason, failure.message)) "PARTITION_FILTER_REQUIRED" else "BIGQUERY_EXECUTION_FAILED",
                warehouseDiagnostic(failure.message, credential, "BigQuery execution failed"),
            )
            result.complete(failed)
            return
        } catch (failure: TimeoutCancellationException) {
            // A timeout seen by a cancelled caller is that caller's cancellation, not the run deadline.
            currentCoroutineContext().ensureActive()
            val failed = timeoutResult(execution.deadlineEpochMillis)
            cancelAbandonedJob(job)
            clearActiveExecution(execution)
            result.complete(failed)
            return
        } catch (failure: CancellationException) {
            // The blocking create may still have created the job; keep it reachable by cancel().
            throw failure
        } catch (failure: BigQueryCallDeadlineExceededException) {
            cancelAbandonedJob(job)
            clearActiveExecution(execution)
            result.complete(deadlineExceededResult())
            return
        } catch (failure: BigQueryCallTimeoutException) {
            val failed = timeoutResult(execution.deadlineEpochMillis)
            cancelAbandonedJob(job)
            clearActiveExecution(execution)
            result.complete(failed)
            return
        } catch (failure: Throwable) {
            clearActiveExecution(execution)
            throw failure
        }
        val startAccepted =
            mutex.withLock {
                !closed &&
                    lifecycleVersion == execution.lifecycleVersion &&
                    activeExecution === execution
            }
        if (!startAccepted) {
            throw CancellationException("BigQuery session closed before query execution")
        }
        poll(
            execution = execution,
            request = context.assignment.request,
            maximumRows = query.maximumRows,
        )
    }

    override suspend fun cancel(): DriverCancellationResult {
        val cancellation =
            mutex.withLock {
                if (closed) {
                    return DriverCancellationResult.UNSUPPORTED
                }
                val active = activeExecution ?: return DriverCancellationResult.UNSUPPORTED
                BigQueryCancellationCall(
                    job = active.job,
                    deadlineEpochMillis = active.deadlineEpochMillis,
                    lifecycleVersion = lifecycleVersion,
                    activeExecution = active,
                )
            }
        val requested =
            callWithinDeadline(cancellation.deadlineEpochMillis) {
                gateway.cancel(
                    job = cancellation.job,
                    deadlineEpochMillis = cancellation.deadlineEpochMillis,
                )
            }
        mutex.withLock {
            if (
                requested &&
                !closed &&
                lifecycleVersion == cancellation.lifecycleVersion &&
                activeExecution === cancellation.activeExecution
            ) {
                cancellation.activeExecution.cancelRequested = true
            }
        }
        return if (requested) {
            DriverCancellationResult.REQUESTED
        } else {
            DriverCancellationResult.UNSUPPORTED
        }
    }

    override suspend fun awaitPhysicalResult(): DriverPhysicalResult = result.await()

    override suspend fun close() {
        val shouldClose =
            mutex.withLock {
                if (closed) {
                    false
                } else {
                    closed = true
                    lifecycleVersion += 1
                    scope.cancel()
                    true
                }
            }
        if (!shouldClose) {
            return
        }
        runCatching {
            withContext(blockingDispatcher) {
                gateway.close()
            }
        }
        credential.close()
    }

    private fun poll(
        execution: ActiveBigQueryExecution,
        request: QueryRequest,
        maximumRows: ULong,
    ) {
        val polling =
            scope.async<DriverPhysicalResult> {
                observeUntilTerminal(execution, request, maximumRows)
            }
        polling.invokeOnCompletion { failure ->
            if (failure is CancellationException) {
                result.complete(DriverPhysicalResult.Cancelled)
            } else if (failure != null) {
                result.complete(
                    DriverPhysicalResult.Failed(
                        failureCode = "BIGQUERY_OBSERVE_FAILED",
                        safeMessage = "BigQuery observation failed",
                    ),
                )
            }
        }
        scope.async {
            runCatching { polling.await() }
                .onSuccess { completed -> result.complete(completed) }
        }
    }

    private suspend fun observeUntilTerminal(
        execution: ActiveBigQueryExecution,
        request: QueryRequest,
        maximumRows: ULong,
    ): DriverPhysicalResult {
        val timing = execution.timing
        return try {
            var terminalResult: DriverPhysicalResult? = null
            while (terminalResult == null) {
                if (!isCurrent(execution)) {
                    return DriverPhysicalResult.Cancelled
                }
                timing.polled()
                val snapshot =
                    timing.measure(DriverPhase.WAIT) { callWithinDeadline(execution.deadlineEpochMillis) {
                        gateway.observe(
                            job = execution.job,
                            deadlineEpochMillis = execution.deadlineEpochMillis,
                        )
                    } }
                if (!isCurrent(execution)) {
                    return DriverPhysicalResult.Cancelled
                }
                terminalResult =
                    when (snapshot) {
                        BigQueryJobSnapshot.Running -> {
                            val remainingMillis =
                                remainingRunMillis(
                                    execution.deadlineEpochMillis,
                                )
                            timing.measure(DriverPhase.WAIT) { delay(minOf(pollInterval.toMillis(), remainingMillis)) }
                            null
                        }
                        is BigQueryJobSnapshot.Succeeded -> {
                            val emitter = BigQueryResultEmitter(gateway, resultSink, timing)
                            timing.measure(DriverPhase.READ) {
                                emitter.emit(
                                    job = execution.job,
                                    snapshot = snapshot,
                                    request = request,
                                    maximumRows = maximumRows,
                                    deadlineEpochMillis =
                                        execution.deadlineEpochMillis,
                                    executionIsCurrent = {
                                        isCurrent(execution)
                                    },
                                )
                            }
                        }
                        is BigQueryJobSnapshot.Failed -> {
                            timing.markFailure(DriverPhase.WAIT)
                            DriverPhysicalResult.Failed(
                                failureCode = snapshot.safeCode,
                                safeMessage = warehouseDiagnostic(snapshot.message, credential, "BigQuery execution failed"),
                            )
                        }
                        BigQueryJobSnapshot.Cancelled ->
                            DriverPhysicalResult.Cancelled
                    }
            }
            requireNotNull(terminalResult)
        } catch (failure: BigQueryException) {
            cancelAbandonedJob(execution.job)
            DriverPhysicalResult.Failed(
                "BIGQUERY_OBSERVE_FAILED",
                warehouseDiagnostic(failure.message, credential, "BigQuery observation failed"),
            )
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            val failed = timeoutResult(execution.deadlineEpochMillis)
            cancelAbandonedJob(execution.job)
            failed
        } catch (_: BigQueryCallDeadlineExceededException) {
            cancelAbandonedJob(execution.job)
            deadlineExceededResult()
        } catch (_: BigQueryCallTimeoutException) {
            val failed = timeoutResult(execution.deadlineEpochMillis)
            cancelAbandonedJob(execution.job)
            failed
        } catch (failure: ResultLimitExceededException) {
            val failed = if (execution.deadlineEpochMillis <= clock.millis()) {
                deadlineExceededResult()
            } else {
                DriverPhysicalResult.Failed("RESULT_LIMIT_EXCEEDED", requireNotNull(failure.message))
            }
            cancelAbandonedJob(execution.job)
            failed
        } catch (failure: UnsupportedResultTypeException) {
            val failed = if (execution.deadlineEpochMillis <= clock.millis()) {
                deadlineExceededResult()
            } else {
                DriverPhysicalResult.Failed(
                    "UNSUPPORTED_RESULT_TYPE",
                    warehouseDiagnostic(failure.message, credential, "BigQuery result type is unsupported"),
                )
            }
            cancelAbandonedJob(execution.job)
            failed
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            cancelAbandonedJob(execution.job)
            throw failure
        }
    }

    private suspend fun cancelAbandonedJob(job: BigQueryJobKey) {
        timing.measure(DriverPhase.CLEANUP) { withContext(NonCancellable) {
            runCatching {
                withTimeout(JOB_CANCEL_CLEANUP_TIMEOUT_MILLIS) {
                    if (!gateway.cancel(
                        job = job,
                        deadlineEpochMillis = clock.millis() + JOB_CANCEL_CLEANUP_TIMEOUT_MILLIS,
                    )) timing.cleanupFailed()
                }
            }.onFailure { timing.cleanupFailed() }
        } }
    }

    private suspend fun <T> callWithinDeadline(
        deadlineEpochMillis: Long,
        operation: suspend () -> T,
    ): T {
        val timeoutMillis =
            minOf(
                callTimeoutPolicy.maximumCallMillis,
                remainingRunMillis(deadlineEpochMillis),
            )
        return withTimeout(timeoutMillis) {
            operation()
        }
    }

    private fun remainingRunMillis(deadlineEpochMillis: Long): Long {
        val nowEpochMillis = clock.millis()
        if (deadlineEpochMillis <= nowEpochMillis) {
            throw BigQueryCallDeadlineExceededException()
        }
        return try {
            Math.subtractExact(deadlineEpochMillis, nowEpochMillis)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
    }

    private fun deadlineExceededResult(): DriverPhysicalResult.Failed =
        DriverPhysicalResult.Failed(
            failureCode = "RUN_DEADLINE_EXCEEDED",
            safeMessage = "BigQuery execution exceeded the run deadline",
        )

    private fun timeoutResult(deadlineEpochMillis: Long): DriverPhysicalResult.Failed =
        if (deadlineEpochMillis <= clock.millis()) {
            deadlineExceededResult()
        } else {
            DriverPhysicalResult.Failed(
                failureCode = "TIMEOUT",
                safeMessage = "BigQuery API call timed out",
            )
        }

    private suspend fun clearActiveExecution(execution: ActiveBigQueryExecution) {
        withContext(NonCancellable) {
            mutex.withLock {
                if (
                    lifecycleVersion == execution.lifecycleVersion &&
                    activeExecution === execution
                ) {
                    activeExecution = null
                }
            }
        }
    }

    private suspend fun isCurrent(execution: ActiveBigQueryExecution): Boolean =
        mutex.withLock {
            !closed &&
                lifecycleVersion == execution.lifecycleVersion &&
                activeExecution === execution
        }

    private fun requestQuery(request: QueryRequest): BigQueryQuery {
        val maximumRows =
            when (val target = request.resultTarget) {
                is QueryResultTarget.InlineStream -> Long.MAX_VALUE.toULong()
                is QueryResultTarget.ArtifactUpload -> target.maximumArtifactRows
            }
        return BigQueryQuery(request.sql, request.parameters, maximumRows)
    }
}

private class ActiveBigQueryExecution(
    val job: BigQueryJobKey,
    val deadlineEpochMillis: Long,
    val lifecycleVersion: Long,
    val timing: DriverOperationTiming,
) {
    var cancelRequested = false
}

private const val JOB_CANCEL_CLEANUP_TIMEOUT_MILLIS = 5_000L

private data class BigQueryCancellationCall(
    val job: BigQueryJobKey,
    val deadlineEpochMillis: Long,
    val lifecycleVersion: Long,
    val activeExecution: ActiveBigQueryExecution,
)

internal class BigQueryResultEmitter(
    private val gateway: BigQueryGateway,
    private val resultSink: RunResultSink,
    private val timing: DriverOperationTiming,
) {

    suspend fun emit(
        job: BigQueryJobKey,
        snapshot: BigQueryJobSnapshot.Succeeded,
        request: QueryRequest,
        maximumRows: ULong,
        deadlineEpochMillis: Long,
        executionIsCurrent: suspend () -> Boolean,
    ): DriverPhysicalResult {
        require(maximumRows > 0uL)
        val target = request.resultTarget
        var acceptedColumns = emptyList<TabularColumn>()
        var totalEncodedBytes = 0uL
        var totalRows = 0uL
        var sequence = 1uL
        var upload: ArtifactUploadSink? = null
        var pageToken: String? = null
        var encoder: TabularArrowStreamEncoder? = null
        try {
            do {
                val remainingRows = maximumRows - totalRows
                if (remainingRows == 0uL) {
                    return failAndAbort(
                        upload,
                        "RESULT_ROW_LIMIT_EXCEEDED",
                        "BigQuery result exceeded the authorized row limit",
                    )
                }
                val pageRowLimit =
                    when (target) {
                        is QueryResultTarget.InlineStream ->
                            minOf(RESULT_PAGE_ROWS, target.maximumRecordBatchRows)
                        is QueryResultTarget.ArtifactUpload -> RESULT_PAGE_ROWS
                    }
                val requestedRows =
                    if (remainingRows >= pageRowLimit.toULong()) {
                        pageRowLimit
                    } else {
                        (remainingRows + 1uL).toInt()
                    }
                if (!executionIsCurrent()) {
                    throw CancellationException("BigQuery execution is no longer active")
                }
                val page = gateway.readResultPage(
                    job = job,
                    pageToken = pageToken,
                    maximumRows = requestedRows,
                    deadlineEpochMillis = deadlineEpochMillis,
                )
                if (!executionIsCurrent()) {
                    throw CancellationException("BigQuery execution is no longer active")
                }
                if (page.page.rows.isEmpty() && page.nextPageToken != null) {
                    return failAndAbort(
                        upload,
                        "BIGQUERY_PAGINATION_STALLED",
                        "BigQuery result pagination made no progress",
                    )
                }
                if (request.expectedColumnNames.isNotEmpty() && page.page.columns.map { it.name } != request.expectedColumnNames) {
                    return failAndAbort(upload, "RESULT_COLUMN_MISMATCH", "query result columns do not match declared columns")
                }
                val receivedRows = page.page.rows.size.toULong()
                if (
                    receivedRows > remainingRows ||
                    (receivedRows == remainingRows && page.nextPageToken != null)
                ) {
                    return failAndAbort(
                        upload,
                        "RESULT_ROW_LIMIT_EXCEEDED",
                        "BigQuery result exceeded the authorized row limit",
                    )
                }
                when (target) {
                    is QueryResultTarget.InlineStream -> {
                        if (acceptedColumns.isEmpty()) {
                            acceptedColumns = page.page.columns
                        } else {
                            require(page.page.columns == acceptedColumns) {
                                "tabular schema changed between Arrow result frames"
                            }
                        }
                        val encoded =
                            TabularArrowEncoder().encode(
                                page = page.page,
                                maximumBytes = target.maximumRecordBatchBytes.toULong(),
                                maximumCellBytes = target.maximumCellBytes,
                            )
                        timing.measure(DriverPhase.DELIVERY) { resultSink.emitInlineResult(
                            InlineResultBatch.fromPayload(sequence, encoded.payload),
                        ) }
                        sequence += 1uL
                        totalRows += receivedRows
                        totalEncodedBytes += encoded.payload.size.toULong()
                    }
                    is QueryResultTarget.ArtifactUpload -> {
                        val activeEncoder =
                            encoder ?: TabularArrowStreamEncoder(
                                columns = page.page.columns,
                                maximumChunkBytes = target.maximumRecordBatchBytes,
                                maximumCellBytes = target.maximumCellBytes,
                            ).also {
                                encoder = it
                                acceptedColumns = page.page.columns
                            }
                        if (page.page.rows.isNotEmpty()) {
                            val chunk = activeEncoder.append(page.page)
                            upload =
                                emitPayload(
                                    payload = chunk.payload,
                                    currentUpload = upload,
                                    objectKey = target.objectKey,
                                )
                            totalRows += receivedRows
                            totalEncodedBytes += chunk.payload.size.toULong()
                        }
                    }
                }
                pageToken = page.nextPageToken
            } while (pageToken != null)
            return when (target) {
                is QueryResultTarget.InlineStream ->
                    DriverPhysicalResult.Succeeded(
                        rowsProcessed = totalRows,
                        bytesProcessed = maxOf(snapshot.bytesProcessed, totalEncodedBytes),
                        manifest = null,
                    )
                is QueryResultTarget.ArtifactUpload -> {
                    val terminalPayload = requireNotNull(encoder).finish()
                    upload =
                        emitPayload(
                            payload = terminalPayload,
                            currentUpload = upload,
                            objectKey = target.objectKey,
                        )
                    totalEncodedBytes += terminalPayload.size.toULong()
                    val manifest =
                        timing.measure(DriverPhase.UPLOAD) {
                            requireNotNull(upload) {
                                "an artifact query must open exactly one artifact upload"
                            }.complete(
                                rowCount = totalRows,
                                columnCount = acceptedColumns.size,
                            )
                        }
                    DriverPhysicalResult.Succeeded(
                        rowsProcessed = totalRows,
                        bytesProcessed = maxOf(snapshot.bytesProcessed, totalEncodedBytes),
                        manifest = manifest,
                    )
                }
            }
        } catch (failure: Throwable) {
            timing.failed(DriverPhase.READ, failure)
            upload?.let { activeUpload ->
                timing.measure(DriverPhase.CLEANUP) {
                    runCatching { activeUpload.abort() }.onFailure { timing.cleanupFailed() }
                }
            }
            throw failure
        } finally {
            encoder?.close()
        }
    }

    private suspend fun emitPayload(
        payload: cc.monomer.execution.driver.spi.BinaryValue,
        currentUpload: ArtifactUploadSink?,
        objectKey: String,
    ): ArtifactUploadSink {
        return timing.measure(DriverPhase.UPLOAD) {
            val upload =
                currentUpload ?: resultSink.openObjectUpload(
                    objectKey = objectKey,
                    mediaType = ArrowResultManifest.ARROW_FILE_MEDIA_TYPE,
                )
            try {
                upload.append(payload)
                upload
            } catch (failure: Throwable) {
                timing.failed(DriverPhase.UPLOAD, failure)
                timing.measure(DriverPhase.CLEANUP) {
                    runCatching { upload.abort() }.onFailure { timing.cleanupFailed() }
                }
                throw failure
            }
        }
    }

    private suspend fun failAndAbort(
        upload: ArtifactUploadSink?,
        failureCode: String,
        safeMessage: String,
    ): DriverPhysicalResult.Failed {
        timing.markFailure(DriverPhase.READ)
        upload?.let { activeUpload ->
            timing.measure(DriverPhase.CLEANUP) {
                runCatching { activeUpload.abort() }.onFailure { timing.cleanupFailed() }
            }
        }
        return DriverPhysicalResult.Failed(failureCode, safeMessage)
    }

    private companion object {
        const val RESULT_PAGE_ROWS = 128
    }
}

data class BigQueryQuery(
    val sql: String,
    val parameters: List<QueryParameter>,
    val maximumRows: ULong,
)

private fun ConnectorRuntimeBinding.requiredText(name: String): String =
    when (val value = properties[name]) {
        is ConnectorPropertyValue.Text -> value.value
        else -> throw IllegalArgumentException("BigQuery connector property is unavailable")
    }
