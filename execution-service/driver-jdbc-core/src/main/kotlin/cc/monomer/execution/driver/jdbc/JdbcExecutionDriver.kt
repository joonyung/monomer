package cc.monomer.execution.driver.jdbc

import cc.monomer.execution.arrow.JdbcArrowEncoder
import cc.monomer.execution.arrow.ResultRowLimitExceededException
import cc.monomer.execution.driver.spi.ResultLimitExceededException
import cc.monomer.execution.driver.spi.UnsupportedResultTypeException
import cc.monomer.execution.driver.spi.warehouseDiagnostic
import cc.monomer.execution.driver.spi.ArrowResultManifest
import cc.monomer.execution.driver.spi.ArtifactUploadSink
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.DriverCancellationResult
import cc.monomer.execution.driver.spi.DriverPhysicalResult
import cc.monomer.execution.driver.spi.DriverOperationTiming
import cc.monomer.execution.driver.spi.DriverPhase
import cc.monomer.execution.driver.spi.InlineResultBatch
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.NullQueryParameterType
import cc.monomer.execution.driver.spi.QueryParameter
import cc.monomer.execution.driver.spi.QueryRequest
import cc.monomer.execution.driver.spi.QueryRequestCanonicalizer
import cc.monomer.execution.driver.spi.QueryResultTarget
import cc.monomer.execution.driver.spi.ResultProducingRunDriverSession
import cc.monomer.execution.driver.spi.RunAssignment
import cc.monomer.execution.driver.spi.RunResultSink
import cc.monomer.execution.driver.spi.StartExecutionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.time.Clock
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.EmptyCoroutineContext

interface JdbcConnectorDialect {
    fun jdbcUrl(connector: ConnectorRuntimeBinding): String

    fun connectionProperties(
        connector: ConnectorRuntimeBinding,
        credential: MaterializedCredential,
        timeoutBudget: JdbcRunTimeoutBudget,
    ): Properties

    fun prepareConnection(
        connection: Connection,
        connector: ConnectorRuntimeBinding,
        timeoutBudget: JdbcRunTimeoutBudget,
    )

    fun refreshTimeouts(
        connection: Connection,
        timeoutBudget: JdbcRunTimeoutBudget,
    )

    fun bindQueryParameters(statement: PreparedStatement, parameters: List<QueryParameter>) {
        statement.bindQueryParameters(parameters)
    }

    fun validateQueryParameters(parameters: List<QueryParameter>) = Unit

    fun cancelRunningStatement(statement: Statement)

    fun safeFailureCode(failure: Throwable): String

    val serializeConnectionLifecycle: Boolean

}

data class JdbcRunTimeoutPolicy(
    val maximumConnectMillis: Long,
    val maximumSocketMillis: Long,
    val maximumLockMillis: Long,
) {
    init {
        require(maximumConnectMillis in 1_000..Int.MAX_VALUE.toLong())
        require(maximumSocketMillis in 1_000..Int.MAX_VALUE.toLong())
        require(maximumLockMillis in 1..Int.MAX_VALUE.toLong())
    }

    fun timeoutBudget(
        remainingMillis: Long,
        maximumQuerySeconds: Int,
    ): JdbcRunTimeoutBudget =
        JdbcRunTimeoutBudget(
            remainingMillis = remainingMillis,
            connectTimeoutMillis = minOf(maximumConnectMillis, remainingMillis),
            socketTimeoutMillis = minOf(maximumSocketMillis, remainingMillis),
            statementTimeoutMillis = minOf(maximumQuerySeconds * 1_000L, remainingMillis),
            lockTimeoutMillis = minOf(maximumLockMillis, remainingMillis),
        )
}

data class JdbcRunTimeoutBudget(
    val remainingMillis: Long,
    val connectTimeoutMillis: Long,
    val socketTimeoutMillis: Long,
    val statementTimeoutMillis: Long,
    val lockTimeoutMillis: Long,
) {
    init {
        require(remainingMillis >= 1_000)
        require(connectTimeoutMillis in 1_000..remainingMillis)
        require(socketTimeoutMillis in 1_000..remainingMillis)
        require(statementTimeoutMillis in 1_000..remainingMillis)
        require(lockTimeoutMillis in 1..remainingMillis)
    }

    val statementTimeoutSeconds: Int
        get() = (statementTimeoutMillis / 1_000).toInt()

    val connectTimeoutSeconds: Int
        get() = (connectTimeoutMillis / 1_000).toInt()

    val socketTimeoutSeconds: Int
        get() = (socketTimeoutMillis / 1_000).toInt()
}

fun interface JdbcConnectionFactory {
    fun open(
        jdbcUrl: String,
        properties: Properties,
    ): Connection
}

object DriverManagerJdbcConnectionFactory : JdbcConnectionFactory {
    override fun open(
        jdbcUrl: String,
        properties: Properties,
    ): Connection = DriverManager.getConnection(jdbcUrl, properties)
}

fun PreparedStatement.bindQueryParameters(parameters: List<QueryParameter>) {
    parameters.forEachIndexed { index, parameter -> bindQueryParameter(index + 1, parameter) }
}

fun PreparedStatement.bindQueryParameter(parameterIndex: Int, parameter: QueryParameter) {
    when (parameter) {
        is QueryParameter.Text -> setString(parameterIndex, parameter.value)
        is QueryParameter.Integer64 -> setLong(parameterIndex, parameter.value)
        is QueryParameter.Float64 -> setDouble(parameterIndex, parameter.value)
        is QueryParameter.BooleanValue -> setBoolean(parameterIndex, parameter.value)
        is QueryParameter.Decimal -> setBigDecimal(parameterIndex, java.math.BigDecimal(parameter.value))
        is QueryParameter.Date -> setDate(parameterIndex, java.sql.Date(parameter.value.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()), utcCalendar())
        is QueryParameter.Timestamp -> setTimestamp(parameterIndex, java.sql.Timestamp.from(parameter.value), utcCalendar())
        is QueryParameter.Bytes -> {
            val bytes = parameter.value.copyBytes()
            try {
                setBytes(parameterIndex, bytes)
            } finally {
                bytes.fill(0)
            }
        }
        is QueryParameter.Null ->
            setNull(
                parameterIndex,
                when (parameter.type) {
                    NullQueryParameterType.TEXT -> java.sql.Types.VARCHAR
                    NullQueryParameterType.INTEGER64 -> java.sql.Types.BIGINT
                    NullQueryParameterType.FLOAT64 -> java.sql.Types.DOUBLE
                    NullQueryParameterType.BOOLEAN -> java.sql.Types.BOOLEAN
                    NullQueryParameterType.BYTES -> java.sql.Types.VARBINARY
                    NullQueryParameterType.DECIMAL -> java.sql.Types.DECIMAL
                    NullQueryParameterType.DATE -> java.sql.Types.DATE
                    NullQueryParameterType.TIMESTAMP -> java.sql.Types.TIMESTAMP
                },
            )
    }
}

private fun utcCalendar(): java.util.Calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))

class JdbcExecutionDriverSession(
    override val connectorType: ConnectorType,
    private val dialect: JdbcConnectorDialect,
    private val connector: ConnectorRuntimeBinding,
    private val credential: MaterializedCredential,
    private val resultSink: RunResultSink,
    private val arrowEncoder: JdbcArrowEncoder,
    private val maximumQuerySeconds: Int,
    private val timeoutPolicy: JdbcRunTimeoutPolicy,
    private val blockingDispatcher: CoroutineDispatcher,
    private val clock: Clock,
    private val connectionFactory: JdbcConnectionFactory,
    private val timing: DriverOperationTiming,
) : ResultProducingRunDriverSession {
    private val scope = CoroutineScope(SupervisorJob() + blockingDispatcher)
    private val lifecycleMutex = Mutex()
    private val activeStatement = AtomicReference<Statement?>()
    private val activeConnection = AtomicReference<Connection?>()
    private var result = CompletableDeferred<DriverPhysicalResult>()
    private var entered = false
    private var closed = false

    init {
        require(connector.connectorType == connectorType)
        require(maximumQuerySeconds > 0)
    }

    override suspend fun enter(context: StartExecutionContext) {
        try {
            val timeoutBudget = timing.measure(DriverPhase.PREPARE) {
                cc.monomer.execution.driver.spi.requireQueryParameterNames(connectorType, context.assignment.request.parameters)
                require(context.assignment.connector == connector)
                val remainingMillis = context.assignment.deadlineEpochMillis - clock.millis()
                if (remainingMillis < 1_000) null else timeoutPolicy.timeoutBudget(remainingMillis, maximumQuerySeconds)
            }
            val attached = timeoutBudget != null && withTimeoutOrNull(timeoutBudget.remainingMillis) {
                lifecycleMutex.withLock {
                    timing.measure(DriverPhase.PREPARE) {
                        check(!closed)
                        check(!entered)
                    }
                    val connection = openConnectionCancellable(timeoutBudget)
                    val executionRemainingMillis = context.assignment.deadlineEpochMillis - clock.millis()
                    if (executionRemainingMillis < 1_000) {
                        activeConnection.compareAndSet(connection, null)
                        closeConnection(connection)
                        return@withLock false
                    }
                    entered = true
                    val deadlineExceeded = AtomicBoolean(false)
                    val deadlineCancellation =
                        scope.launch {
                            delay(executionRemainingMillis)
                            deadlineExceeded.set(true)
                            timing.interrupted()
                            requestJdbcCancellation()
                        }
                    val deferred =
                        scope.async {
                            val physicalResult =
                                try {
                                    try {
                                        withTimeout(executionRemainingMillis) {
                                            execute(connection, context.assignment)
                                        }
                                    } catch (_: TimeoutCancellationException) {
                                        runDeadlineExceededResult()
                                    } catch (failure: Throwable) {
                                        if (!deadlineExceeded.get() && clock.millis() < context.assignment.deadlineEpochMillis) {
                                            throw failure
                                        }
                                        runDeadlineExceededResult()
                                    }
                                } finally {
                                    try {
                                        closeConnection(connection)
                                    } finally {
                                        activeConnection.compareAndSet(connection, null)
                                        deadlineCancellation.cancel()
                                    }
                                }
                            if (deadlineExceeded.get()) runDeadlineExceededResult() else physicalResult
                        }
                    deferred.invokeOnCompletion { failure ->
                        if (failure != null && failure !is CancellationException) {
                            result.complete(
                                DriverPhysicalResult.Failed(
                                    failureCode =
                                        when (failure) {
                                            is ResultRowLimitExceededException -> "RESULT_ROW_LIMIT_EXCEEDED"
                                            is ResultLimitExceededException -> "RESULT_LIMIT_EXCEEDED"
                                            is UnsupportedResultTypeException -> "UNSUPPORTED_RESULT_TYPE"
                                            else -> dialect.safeFailureCode(failure)
                                        },
                                    safeMessage = warehouseDiagnostic(
                                        if (failure is ResultLimitExceededException || failure is UnsupportedResultTypeException) failure.message else jdbcDiagnostic(failure),
                                        credential,
                                        "external query execution failed",
                                    ),
                                ),
                            )
                        }
                    }
                    scope.async {
                        try {
                            result.complete(deferred.await())
                        } catch (_: CancellationException) {
                            result.complete(DriverPhysicalResult.Cancelled)
                        }
                    }
                    true
                }
            } == true
            if (!attached) {
                // A deadline seen by a cancelled caller is that caller's cancellation, not the run deadline.
                currentCoroutineContext().ensureActive()
                timing.interrupted()
                result.complete(runDeadlineExceededResult())
            }
        } catch (failure: java.sql.SQLException) {
            result.complete(
                if (clock.millis() >= context.assignment.deadlineEpochMillis) {
                    runDeadlineExceededResult()
                } else {
                    DriverPhysicalResult.Failed(
                        dialect.safeFailureCode(failure),
                        warehouseDiagnostic(jdbcDiagnostic(failure), credential, "external query execution failed"),
                    )
                },
            )
        }
    }

    private suspend fun openConnectionCancellable(timeoutBudget: JdbcRunTimeoutBudget): Connection =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                timing.interrupted()
                requestJdbcCancellation()
            }
            blockingDispatcher.dispatch(continuation.context, Runnable {
                var connection: Connection? = null
                try {
                    connection = openConnection(timeoutBudget)
                    if (!continuation.isActive) {
                        try {
                            closeConnection(connection)
                        } finally {
                            activeConnection.compareAndSet(connection, null)
                        }
                    } else {
                        continuation.resumeWith(Result.success(connection))
                    }
                } catch (failure: Throwable) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(failure))
                    }
                }
            })
        }

    private fun requestJdbcCancellation() {
        val cancelReturned = CountDownLatch(1)
        val statement = activeStatement.get()
        if (statement == null) {
            cancelReturned.countDown()
        } else {
            blockingDispatcher.dispatch(EmptyCoroutineContext, Runnable {
                try {
                    runCatching { dialect.cancelRunningStatement(statement) }.onFailure { timing.cleanupFailed() }
                } finally {
                    cancelReturned.countDown()
                }
            })
        }
        activeConnection.get()?.let { connection ->
            blockingDispatcher.dispatch(EmptyCoroutineContext, Runnable {
                // pgjdbc and Redshift drop a cancel issued after abort closes the connection. A JDBC cancel
                // opens a separate warehouse connection, so a stuck one is bounded by the connect timeout.
                runCatching { cancelReturned.await(timeoutPolicy.maximumConnectMillis, TimeUnit.MILLISECONDS) }.onFailure { timing.cleanupFailed() }
                runCatching { connection.abort(DirectExecutor) }.onFailure { timing.cleanupFailed() }
                runCatching(connection::close).onFailure { timing.cleanupFailed() }
            })
        }
    }

    private fun runDeadlineExceededResult(): DriverPhysicalResult.Failed =
        DriverPhysicalResult.Failed(
            failureCode = "RUN_DEADLINE_EXCEEDED",
            safeMessage = "external query exceeded the run deadline",
        )

    private companion object {
        val serializedConnectionLifecycleLock = ReentrantLock()
    }

    override suspend fun cancel(): DriverCancellationResult {
        if (result.isCompleted) return DriverCancellationResult.CONFIRMED
        timing.interrupted()
        requestJdbcCancellation()
        return DriverCancellationResult.REQUESTED
    }

    override suspend fun awaitPhysicalResult(): DriverPhysicalResult = result.await()

    override suspend fun close() {
        withContext(blockingDispatcher) {
            lifecycleMutex.withLock {
                if (closed) return@withLock
                closed = true
                requestJdbcCancellation()
                scope.cancel()
                runCatching { activeStatement.getAndSet(null)?.close() }.onFailure { timing.cleanupFailed() }
                activeConnection.getAndSet(null)?.let(::closeConnection)
                credential.close()
            }
        }
    }

    private suspend fun execute(
        connection: Connection,
        assignment: RunAssignment,
    ): DriverPhysicalResult =
        executeQuery(
            connection = connection,
            request = assignment.request,
            deadlineEpochMillis = assignment.deadlineEpochMillis,
        )

    private suspend fun executeQuery(
        connection: Connection,
        request: QueryRequest,
        deadlineEpochMillis: Long,
    ): DriverPhysicalResult {
        timing.measure(DriverPhase.PREPARE) {
            QueryRequestCanonicalizer.requireCanonical(request)
            dialect.validateQueryParameters(request.parameters)
        }
        val statement = timing.measure(DriverPhase.EXECUTE) { connection.prepareStatement(request.sql) }
        try {
            timing.measure(DriverPhase.EXECUTE) {
                statement.queryTimeout = timeoutBudgetForStatement(deadlineEpochMillis).statementTimeoutSeconds
                dialect.bindQueryParameters(statement, request.parameters)
                activeStatement.set(statement)
                var hasResultSet = statement.execute()
                // Validation admits one meaningful statement; only driver-created comment-only fragments may be skipped here.
                while (!hasResultSet && statement.updateCount == 0) {
                    hasResultSet = statement.moreResults
                }
                check(hasResultSet) { "Query returned no result set" }
            }
            return timing.measure(DriverPhase.READ) {
                statement.resultSet.use { resultSet ->
                    if (request.expectedColumnNames.isNotEmpty()) {
                        val columnNames = cc.monomer.execution.arrow.JdbcResultColumnReader.resultColumns(resultSet.metaData).map { it.name }
                        if (columnNames != request.expectedColumnNames) {
                            timing.markFailure(DriverPhase.READ)
                            return@measure DriverPhysicalResult.Failed("RESULT_COLUMN_MISMATCH", "query result columns do not match declared columns")
                        }
                    }
                    when (val target = request.resultTarget) {
                        is QueryResultTarget.InlineStream -> streamInlineResult(resultSet, target)
                        is QueryResultTarget.ArtifactUpload -> uploadQueryArtifact(resultSet, target)
                    }
                }
            }
        } finally {
            activeStatement.compareAndSet(statement, null)
            runCatching(statement::close).onFailure { timing.cleanupFailed() }
        }
    }

    private suspend fun streamInlineResult(
        resultSet: ResultSet,
        target: QueryResultTarget.InlineStream,
    ): DriverPhysicalResult {
        var sequence = 1uL
        val streamed =
            arrowEncoder.streamInline(
                resultSet = resultSet,
                maximumRecordBatchRows = target.maximumRecordBatchRows,
                maximumRecordBatchBytes = target.maximumRecordBatchBytes,
                maximumCellBytes = target.maximumCellBytes,
            ) { payload ->
                timing.measure(DriverPhase.DELIVERY) {
                    resultSink.emitInlineResult(InlineResultBatch.fromPayload(sequence, payload))
                }
                sequence += 1uL
            }
        return DriverPhysicalResult.Succeeded(
            rowsProcessed = streamed.rowCount,
            bytesProcessed = streamed.byteCount,
            manifest = null,
        )
    }

    private suspend fun uploadQueryArtifact(
        resultSet: ResultSet,
        target: QueryResultTarget.ArtifactUpload,
    ): DriverPhysicalResult {
        var upload: ArtifactUploadSink? = null
        try {
            val streamed =
                arrowEncoder.stream(
                    resultSet = resultSet,
                    maximumRows = target.maximumArtifactRows,
                    maximumRecordBatchRows = target.maximumRecordBatchRows,
                    maximumRecordBatchBytes = target.maximumRecordBatchBytes,
                    maximumCellBytes = target.maximumCellBytes,
                ) { payload ->
                    timing.measure(DriverPhase.UPLOAD) {
                        val activeUpload =
                            upload ?: resultSink.openObjectUpload(
                                objectKey = target.objectKey,
                                mediaType = ArrowResultManifest.ARROW_FILE_MEDIA_TYPE,
                            ).also { upload = it }
                        activeUpload.append(payload)
                    }
                }
            val manifest = timing.measure(DriverPhase.UPLOAD) {
                requireNotNull(upload) {
                    "an artifact query must open exactly one artifact upload"
                }.complete(
                    rowCount = streamed.rowCount,
                    columnCount = streamed.columnCount,
                )
            }
            return DriverPhysicalResult.Succeeded(
                rowsProcessed = streamed.rowCount,
                bytesProcessed = streamed.byteCount,
                manifest = manifest,
            )
        } catch (failure: Throwable) {
            timing.measure(DriverPhase.CLEANUP) {
                upload?.let { activeUpload -> runCatching { activeUpload.abort() }.onFailure { timing.cleanupFailed() } }
            }
            throw failure
        }
    }

    private fun openConnection(timeoutBudget: JdbcRunTimeoutBudget): Connection {
        val properties = timing.measure(DriverPhase.CREDENTIAL) {
            dialect.connectionProperties(connector, credential, timeoutBudget)
        }
        return try {
            timing.measure(DriverPhase.CONNECT) {
                withLifecycleLock {
                    connectionFactory.open(dialect.jdbcUrl(connector), properties)
                }
            }.also { connection ->
                activeConnection.set(connection)
                try {
                    timing.measure(DriverPhase.SESSION_SETUP) {
                        connection.isReadOnly = true
                        dialect.prepareConnection(connection, connector, timeoutBudget)
                    }
                } catch (failure: Throwable) {
                    try {
                        closeConnection(connection)
                    } finally {
                        activeConnection.compareAndSet(connection, null)
                    }
                    throw failure
                }
            }
        } finally {
            properties.clear()
        }
    }

    private fun timeoutBudgetForStatement(deadlineEpochMillis: Long): JdbcRunTimeoutBudget =
        timeoutBudget(minOf(deadlineEpochMillis, clock.millis() + maximumQuerySeconds * 1_000L))

    private fun timeoutBudget(deadlineEpochMillis: Long): JdbcRunTimeoutBudget {
        val remainingMillis = deadlineEpochMillis - clock.millis()
        if (remainingMillis < 1_000) {
            throw IllegalArgumentException("run deadline has expired")
        }
        return timeoutPolicy.timeoutBudget(remainingMillis, maximumQuerySeconds)
    }

    private fun closeConnection(connection: Connection) {
        timing.measure(DriverPhase.CLEANUP) {
            withLifecycleLock {
                if (runCatching { connection.autoCommit }.onFailure { timing.cleanupFailed() }.getOrNull() == false) {
                    runCatching(connection::rollback).onFailure { timing.cleanupFailed() }
                }
                runCatching(connection::close).onFailure { timing.cleanupFailed() }
            }
        }
    }

    private fun <T> withLifecycleLock(action: () -> T): T =
        if (dialect.serializeConnectionLifecycle) {
            serializedConnectionLifecycleLock.withLock(action)
        } else {
            action()
        }

}

private fun jdbcDiagnostic(failure: Throwable): String? =
    generateSequence(failure) { it.cause }.take(16)
        .filterIsInstance<java.sql.SQLException>()
        .flatMap { generateSequence(it) { exception -> exception.nextException }.take(16) }
        .mapNotNull { it.message?.takeIf(String::isNotBlank) }
        .distinct()
        .joinToString("\n")
        .takeIf(String::isNotBlank)

fun JdbcConnectorDialect.rawSqlValidationFailureCode(failure: Throwable): String {
    val causes = generateSequence(failure) { it.cause }.take(32).toList()
    val sqlStates = causes.filterIsInstance<SQLException>().mapNotNull { it.sqlState }
    val executionCode = safeFailureCode(failure)
    return when {
        executionCode == "AUTHENTICATION_FAILED" -> executionCode
        sqlStates.any { it.startsWith("08") } || causes.any { it is SocketTimeoutException } -> "CONNECTION_FAILED"
        sqlStates.any { it.startsWith("28") } -> "AUTHENTICATION_FAILED"
        causes.any { it is SQLTimeoutException } || "57014" in sqlStates || executionCode in setOf("TIMEOUT", "CANCELLED") ->
            "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED"
        executionCode in setOf("AUTHENTICATION_FAILED", "CONNECTION_FAILED", "RESOURCE_EXHAUSTED", "PERMISSION_DENIED", "QUERY_BIND_UNSUPPORTED") -> executionCode
        sqlStates.any { it.startsWith("42") } || executionCode in setOf("SYNTAX_ERROR", "OBJECT_NOT_FOUND") ->
            "RAW_SQL_VALIDATION_REJECTED"
        failure is IllegalArgumentException -> "RAW_SQL_VALIDATION_REJECTED"
        else -> "RAW_SQL_VALIDATION_FAILED"
    }
}
