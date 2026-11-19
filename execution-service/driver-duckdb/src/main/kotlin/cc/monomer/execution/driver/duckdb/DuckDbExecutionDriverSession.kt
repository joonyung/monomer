package cc.monomer.execution.driver.duckdb

import cc.monomer.execution.arrow.JavaTimeJdbcResultValueReader
import cc.monomer.execution.arrow.JdbcArrowEncoder
import cc.monomer.execution.arrow.JdbcDecimalPhysicalEncoding
import cc.monomer.execution.arrow.JdbcResultColumnReader
import cc.monomer.execution.driver.spi.*
import java.sql.ResultSet
import java.time.Clock
import kotlinx.coroutines.*

internal class DuckDbExecutionDriverSession(
    private val datasets: DuckDbSampleDatasets,
    private val dataset: String,
    private val connector: ConnectorRuntimeBinding,
    private val credential: MaterializedCredential,
    private val resultSink: RunResultSink,
    arrowBatchSize: Int,
    private val dispatcher: CoroutineDispatcher,
    private val clock: Clock,
    private val timing: DriverOperationTiming,
) : ResultProducingRunDriverSession {
    override val connectorType = ConnectorType.DUCKDB
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val result = CompletableDeferred<DriverPhysicalResult>()
    private var execution: Job? = null
    private var closed = false
    private val encoder = JdbcArrowEncoder(arrowBatchSize, JdbcDecimalPhysicalEncoding.NATIVE_DECIMAL, JavaTimeJdbcResultValueReader)

    override suspend fun enter(context: StartExecutionContext) {
        check(!closed && execution == null)
        require(context.assignment.connector == connector)
        val request = context.assignment.request
        timing.measure(DriverPhase.PREPARE) {
            QueryRequestCanonicalizer.requireCanonical(request)
            requireQueryParameterNames(connectorType, request.parameters)
            require(request.sql.toByteArray(Charsets.UTF_8).size <= MAXIMUM_RAW_SQL_BYTES)
        }
        execution = scope.launch {
            val outcome = try {
                duckDbOperation(datasets, dataset, context.assignment.deadlineEpochMillis, dispatcher, clock, timing) { connection ->
                    timing.measure(DriverPhase.VALIDATE) { DuckDbRawSqlValidator.validate(connection, request.sql) }
                    currentCoroutineContext().ensureActive()
                    val statement = timing.measure(DriverPhase.PREPARE) { connection.prepareStatement(request.sql) }
                    statement.use {
                        currentCoroutineContext().ensureActive()
                        val boundBytes = statement.bindSampleParameters(request.parameters)
                        try {
                            timing.measure(DriverPhase.EXECUTE) { check(statement.execute()) }
                            statement.resultSet.use { results -> timing.measure(DriverPhase.READ) { stream(results, request) } }
                        } finally {
                            boundBytes.forEach { it.fill(0) }
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                DriverPhysicalResult.Failed("RUN_DEADLINE_EXCEEDED", "Sample query exceeded its 60-second execution limit or run deadline.")
            } catch (_: DuckDbDeadlineExceeded) {
                DriverPhysicalResult.Failed("RUN_DEADLINE_EXCEEDED", "Sample query exceeded its run deadline.")
            } catch (_: CancellationException) {
                DriverPhysicalResult.Cancelled
            } catch (failure: Throwable) {
                DriverPhysicalResult.Failed(duckDbFailureCode(failure, false), duckDbSafeMessage(failure))
            }
            result.complete(outcome)
        }
        execution?.invokeOnCompletion { failure -> if (failure is CancellationException) result.complete(DriverPhysicalResult.Cancelled) }
    }

    private suspend fun stream(results: ResultSet, request: QueryRequest): DriverPhysicalResult {
        if (request.expectedColumnNames.isNotEmpty() && JdbcResultColumnReader.resultColumns(results.metaData).map { it.name } != request.expectedColumnNames) {
            timing.markFailure(DriverPhase.READ)
            return DriverPhysicalResult.Failed("RESULT_COLUMN_MISMATCH", "Query result columns do not match declared columns.")
        }
        return when (val target = request.resultTarget) {
            is QueryResultTarget.InlineStream -> {
                var sequence = 1uL
                val streamed = encoder.streamInline(results, target.maximumRecordBatchRows, target.maximumRecordBatchBytes, target.maximumCellBytes) { payload ->
                    timing.measure(DriverPhase.DELIVERY) { resultSink.emitInlineResult(InlineResultBatch.fromPayload(sequence++, payload)) }
                }
                DriverPhysicalResult.Succeeded(streamed.rowCount, streamed.byteCount, null)
            }
            is QueryResultTarget.ArtifactUpload -> upload(results, target)
        }
    }

    private suspend fun upload(results: ResultSet, target: QueryResultTarget.ArtifactUpload): DriverPhysicalResult {
        var upload: ArtifactUploadSink? = null
        var byteCount = 0uL
        try {
            val streamed = encoder.stream(results, target.maximumArtifactRows, target.maximumRecordBatchRows, target.maximumRecordBatchBytes, target.maximumCellBytes) { payload ->
                byteCount += payload.size.toULong()
                if (byteCount > target.maximumArtifactBytes) throw ResultLimitExceededException(target.maximumArtifactBytes)
                timing.measure(DriverPhase.UPLOAD) {
                    val active = upload ?: resultSink.openObjectUpload(target.objectKey, ArrowResultManifest.ARROW_FILE_MEDIA_TYPE).also { upload = it }
                    active.append(payload)
                }
            }
            val manifest = timing.measure(DriverPhase.UPLOAD) { requireNotNull(upload).complete(streamed.rowCount, streamed.columnCount) }
            return DriverPhysicalResult.Succeeded(streamed.rowCount, streamed.byteCount, manifest)
        } catch (failure: Throwable) {
            withContext(NonCancellable) { timing.measure(DriverPhase.CLEANUP) { runCatching { upload?.abort() }.onFailure { timing.cleanupFailed() } } }
            throw failure
        }
    }

    override suspend fun awaitPhysicalResult(): DriverPhysicalResult = result.await()

    override suspend fun cancel(): DriverCancellationResult {
        if (result.isCompleted) return DriverCancellationResult.CONFIRMED
        timing.interrupted()
        execution?.cancel()
        return DriverCancellationResult.REQUESTED
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        execution?.cancel()
        withContext(NonCancellable) { execution?.join() }
        scope.cancel()
        credential.close()
    }
}
