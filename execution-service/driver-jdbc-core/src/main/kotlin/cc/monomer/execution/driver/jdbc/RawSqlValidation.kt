package cc.monomer.execution.driver.jdbc

import cc.monomer.execution.driver.spi.DriverOperationTiming
import cc.monomer.execution.driver.spi.DriverPhase
import cc.monomer.execution.driver.spi.RawSqlValidationException
import java.sql.Connection
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

suspend fun validateRawSqlOnBlockingConnection(
    remainingMillis: Long,
    blockingDispatcher: CoroutineDispatcher,
    timing: DriverOperationTiming,
    beforeConnect: () -> Unit,
    connect: () -> Connection,
    validate: (Connection) -> Unit,
    releaseConnectionProperties: () -> Unit,
    classifyFailure: (Throwable) -> RawSqlValidationException,
    deadlineExceeded: (TimeoutCancellationException) -> RawSqlValidationException,
) {
    try {
        withTimeout(remainingMillis) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val activeConnection = AtomicReference<Connection?>()
                continuation.invokeOnCancellation {
                    timing.interrupted()
                    activeConnection.get()?.let { connection ->
                        blockingDispatcher.dispatch(continuation.context, Runnable {
                            runCatching { connection.abort(DirectExecutor) }.onFailure { timing.cleanupFailed() }
                            runCatching(connection::close).onFailure { timing.cleanupFailed() }
                        })
                    }
                }
                blockingDispatcher.dispatch(continuation.context, Runnable {
                    var connection: Connection? = null
                    var outcome: Result<Unit>? = null
                    try {
                        beforeConnect()
                        if (!continuation.isActive) return@Runnable
                        val openedConnection = connect()
                        connection = openedConnection
                        activeConnection.set(openedConnection)
                        if (!continuation.isActive) return@Runnable
                        validate(openedConnection)
                        outcome = Result.success(Unit)
                    } catch (failure: RawSqlValidationException) {
                        outcome = Result.failure(failure)
                    } catch (failure: Throwable) {
                        outcome = Result.failure(classifyFailure(failure))
                    } finally {
                        try {
                            timing.measure(DriverPhase.CLEANUP) {
                                connection?.let { openedConnection ->
                                    if (runCatching { openedConnection.autoCommit }.onFailure { timing.cleanupFailed() }.getOrNull() == false) {
                                        runCatching(openedConnection::rollback).onFailure { timing.cleanupFailed() }
                                    }
                                    runCatching(openedConnection::close).onFailure { timing.cleanupFailed() }
                                }
                            }
                        } finally {
                            activeConnection.set(null)
                            releaseConnectionProperties()
                        }
                    }
                    if (continuation.isActive) continuation.resumeWith(requireNotNull(outcome))
                })
            }
        }
    } catch (failure: TimeoutCancellationException) {
        throw deadlineExceeded(failure)
    }
}

fun isOnlyWhitespaceAndComments(
    sql: String,
    isSpace: (Char) -> Boolean,
    parseLineComment: (CharArray, Int) -> Int,
    parseBlockComment: (CharArray, Int) -> Int,
): Boolean {
    val characters = sql.toCharArray()
    var offset = 0
    while (offset < characters.size) {
        if (isSpace(characters[offset])) {
            offset++
            continue
        }
        val commentEnd = when (characters[offset]) {
            '-' -> parseLineComment(characters, offset)
            '/' -> parseBlockComment(characters, offset)
            else -> return false
        }
        if (commentEnd == offset || commentEnd >= characters.size) return false
        offset = commentEnd + 1
    }
    return true
}
