package cc.monomer.execution.driver.jdbc

import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.DriverOperation
import cc.monomer.execution.driver.spi.RawSqlValidationException
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class RawSqlValidationRunnerTest {
    @Test
    fun `validation success rolls back closes and releases connection properties`() = runBlocking {
        val calls = CopyOnWriteArrayList<String>()
        run(calls, validate = { calls += "validate" })
        assertEquals(listOf("before", "connect", "validate", "getAutoCommit", "rollback", "close", "release"), calls.toList())
    }

    @Test
    fun `validation failures keep driver exceptions and classify every other failure`() = runBlocking {
        val rejected = RawSqlValidationException("RAW_SQL_VALIDATION_REJECTED", "rejected", null)
        assertSame(rejected, assertFailsWith<RawSqlValidationException> { run(CopyOnWriteArrayList(), validate = { throw rejected }) })
        val serverFailure = SQLException("server failure", "XX000")
        val classified = assertFailsWith<RawSqlValidationException> { run(CopyOnWriteArrayList(), validate = { throw serverFailure }) }
        assertEquals("CLASSIFIED", classified.failureCode)
        assertSame(serverFailure, classified.cause)
    }

    @Test
    fun `deadline aborts the active connection and maps to the driver deadline failure`() = runBlocking {
        val calls = CopyOnWriteArrayList<String>()
        val released = CountDownLatch(1)
        val failure = assertFailsWith<RawSqlValidationException> {
            run(calls, remainingMillis = 200, validate = { released.await(5, TimeUnit.SECONDS) })
        }
        assertEquals("DEADLINE", failure.failureCode)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while ("abort" !in calls && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("abort" in calls, calls.toString())
        released.countDown()
    }

    private suspend fun run(
        calls: MutableList<String>,
        remainingMillis: Long = 5_000,
        validate: (Connection) -> Unit,
    ) = validateRawSqlOnBlockingConnection(
        remainingMillis = remainingMillis,
        blockingDispatcher = Dispatchers.IO,
        timing = JdbcTimingFixture.timing(ConnectorType.POSTGRESQL, DriverOperation.VALIDATE),
        beforeConnect = { calls += "before" },
        connect = {
            calls += "connect"
            JdbcTimingFixture.proxy<Connection> { method, _ ->
                if (method in setOf("getAutoCommit", "rollback", "close", "abort")) calls += method
                null
            }
        },
        validate = validate,
        releaseConnectionProperties = { calls += "release" },
        classifyFailure = { failure -> RawSqlValidationException("CLASSIFIED", "classified", failure) },
        deadlineExceeded = { failure -> RawSqlValidationException("DEADLINE", "deadline", failure) },
    )
}
