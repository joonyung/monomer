package cc.monomer.execution.driver.duckdb

import cc.monomer.execution.arrow.ResultRowLimitExceededException
import cc.monomer.execution.driver.jdbc.bindQueryParameter
import cc.monomer.execution.driver.spi.*
import java.nio.file.Path
import java.sql.PreparedStatement
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class DuckDbExecutionDriverProvider(
    private val arrowBatchSize: Int,
    allowedDatabaseRoot: Path,
    private val blockingDispatcher: CoroutineDispatcher,
    private val clock: Clock,
) : ExecutionDriverProvider, AutoCloseable {
    private val datasets = DuckDbSampleDatasets(allowedDatabaseRoot)
    override val capability = DriverCapabilityDescriptor(ConnectorType.DUCKDB)

    override fun openSession(connector: ConnectorRuntimeBinding, credential: MaterializedCredential?, resultSink: RunResultSink, timing: DriverOperationTiming): ResultProducingRunDriverSession {
        val dataset = timing.measure(DriverPhase.CREDENTIAL) { datasets.requireAccess(connector, credential) }
        return DuckDbExecutionDriverSession(datasets, dataset, connector, requireNotNull(credential), resultSink, arrowBatchSize, blockingDispatcher, clock, timing)
    }

    override suspend fun validateRawSql(connector: ConnectorRuntimeBinding, credential: MaterializedCredential?, sql: String, parameters: List<QueryParameter>, deadlineEpochMillis: Long, timing: DriverOperationTiming) {
        val dataset = timing.measure(DriverPhase.CREDENTIAL) { datasets.requireAccess(connector, credential) }
        timing.measure(DriverPhase.PREPARE) {
            requireQueryParameterNames(ConnectorType.DUCKDB, parameters)
            require(sql.toByteArray(Charsets.UTF_8).size <= MAXIMUM_RAW_SQL_BYTES)
        }
        try {
            duckDbOperation(datasets, dataset, deadlineEpochMillis, blockingDispatcher, clock, timing) { connection ->
                timing.measure(DriverPhase.VALIDATE) { DuckDbRawSqlValidator.validate(connection, sql) }
                currentCoroutineContext().ensureActive()
                timing.measure(DriverPhase.PREPARE) {
                    connection.prepareStatement(sql).use { statement ->
                        val boundBytes = statement.bindSampleParameters(parameters)
                        try {
                            require(statement.parameterMetaData.parameterCount == parameters.size)
                            require(statement.metaData.columnCount > 0)
                        } finally {
                            boundBytes.forEach { it.fill(0) }
                        }
                    }
                }
            }
        } catch (failure: TimeoutCancellationException) {
            throw RawSqlValidationException("RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", "Sample query validation exceeded its deadline.", failure)
        } catch (failure: DuckDbDeadlineExceeded) {
            throw RawSqlValidationException("RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", "Sample query validation exceeded its deadline.", failure)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: RawSqlValidationException) {
            throw failure
        } catch (failure: Throwable) {
            throw RawSqlValidationException(duckDbFailureCode(failure, true), "Sample query validation failed.", failure)
        }
    }

    override fun close() = datasets.close()
}

internal fun duckDbFailureCode(failure: Throwable, validating: Boolean): String = when (failure) {
    is RawSqlValidationException -> failure.failureCode
    is ResultRowLimitExceededException -> "RESULT_ROW_LIMIT_EXCEEDED"
    is ResultLimitExceededException -> "RESULT_LIMIT_EXCEEDED"
    is UnsupportedResultTypeException -> "UNSUPPORTED_RESULT_TYPE"
    else -> {
        val message = failure.message.orEmpty().lowercase()
        when {
            "out of memory" in message || "memory limit" in message -> "RESOURCE_EXHAUSTED"
            "disabled" in message || "permission" in message || "read-only" in message || "configuration has been locked" in message -> "PERMISSION_DENIED"
            validating && (failure is IllegalArgumentException || "catalog" in message || "binder" in message || "parser" in message || "parameter" in message) -> "RAW_SQL_VALIDATION_REJECTED"
            validating -> "RAW_SQL_VALIDATION_FAILED"
            "catalog" in message || "binder" in message || "parser" in message -> "SYNTAX_OR_OBJECT_ERROR"
            else -> "DUCKDB_EXECUTION_FAILED"
        }
    }
}

internal fun duckDbSafeMessage(failure: Throwable): String = when (failure) {
    is RawSqlValidationException -> failure.safeMessage
    is ResultRowLimitExceededException -> "Sample query exceeded its result row limit."
    is ResultLimitExceededException -> "Sample query exceeded its result size limit."
    is UnsupportedResultTypeException -> failure.message ?: "Sample query returned an unsupported result type."
    else -> "Sample query execution failed."
}

internal fun PreparedStatement.bindSampleParameters(parameters: List<QueryParameter>): List<ByteArray> {
    val boundBytes = mutableListOf<ByteArray>()
    try {
        parameters.forEachIndexed { index, parameter ->
            when (parameter) {
                // DuckDB JDBC retains the byte array until execute; the caller clears it after consumption.
                is QueryParameter.Bytes -> {
                    val bytes = parameter.value.copyBytes()
                    boundBytes += bytes
                    setBytes(index + 1, bytes)
                }
                is QueryParameter.Date -> setObject(index + 1, parameter.value)
                is QueryParameter.Timestamp -> setObject(index + 1, parameter.value.atOffset(java.time.ZoneOffset.UTC))
                else -> bindQueryParameter(index + 1, parameter)
            }
        }
        return boundBytes
    } catch (failure: Throwable) {
        boundBytes.forEach { it.fill(0) }
        throw failure
    }
}
