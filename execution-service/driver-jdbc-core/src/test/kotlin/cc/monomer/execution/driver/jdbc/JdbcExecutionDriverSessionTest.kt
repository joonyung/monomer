package cc.monomer.execution.driver.jdbc

import cc.monomer.execution.driver.spi.ResultLimitExceededException
import cc.monomer.execution.driver.spi.UnsupportedResultTypeException
import cc.monomer.execution.driver.spi.DriverOperationTiming
import cc.monomer.execution.driver.spi.DriverOperation
import cc.monomer.execution.arrow.JdbcArrowEncoder
import cc.monomer.execution.arrow.JavaTimeJdbcResultValueReader
import cc.monomer.execution.arrow.JdbcDecimalPhysicalEncoding
import cc.monomer.execution.driver.spi.ArtifactUploadSink
import cc.monomer.execution.driver.spi.ConnectorId
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.DriverCancellationResult
import cc.monomer.execution.driver.spi.DriverPhysicalResult
import cc.monomer.execution.driver.spi.InlineResultBatch
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.QueryRequest
import cc.monomer.execution.driver.spi.QueryRequestCanonicalizer
import cc.monomer.execution.driver.spi.QueryResultTarget
import cc.monomer.execution.driver.spi.RunAssignment
import cc.monomer.execution.driver.spi.RunId
import cc.monomer.execution.driver.spi.RunResultSink
import cc.monomer.execution.driver.spi.Sha256Digest
import cc.monomer.execution.driver.spi.StartExecutionContext
import cc.monomer.execution.driver.spi.WorkspaceId
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Statement
import java.sql.Types
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class JdbcExecutionDriverSessionTest {
    @Test
    fun declaredArrowColumnMismatchFailsWithoutEmittingEvenForEmptyResults() {
        listOf(emptyList(), listOf(1)).forEach { rows ->
            val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val sink = RecordingInlineSink()
            val now = Instant.parse("2026-10-06T00:00:00Z")
            val session = session(dispatcher, Clock.fixed(now, ZoneOffset.UTC),
                { _, _ -> fakeConnection(mutableListOf(), integerResultSet(rows)) }, sink)
            try {
                runBlocking {
                    val original = assignment(now.toEpochMilli() + 30_000, inlineTarget())
                    val unsigned = original.request.copy(expectedColumnNames = listOf("wrong"))
                    val request = unsigned.copy(canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(unsigned))
                    session.enter(StartExecutionContext(
                        cc.monomer.execution.driver.spi.RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")),
                        original.copy(request = request),
                    ))
                    assertEquals("RESULT_COLUMN_MISMATCH", assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult()).failureCode)
                    assertTrue(sink.inlineResults.isEmpty())
                    session.close()
                }
            } finally { dispatcher.close() }
        }
    }

    @Test
    fun `SQL diagnostics survive physical failure while unclassified exceptions stay generic`() {
        val now = Instant.parse("2026-07-31T00:00:00Z")
        listOf(
            java.sql.SQLException("column missing does not exist", "42703") to "column missing does not exist",
            java.sql.SQLException("statement failed", java.sql.SQLException("division by zero", "22012")) to "statement failed\ndivision by zero",
            IllegalStateException("internal implementation detail") to "external query execution failed",
            java.sql.SQLException("password=secret", "28000") to "external query execution failed",
        ).forEach { (failure, expected) ->
            val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val session = session(dispatcher, Clock.fixed(now, ZoneOffset.UTC), { _, _ ->
                val connection = fakeConnection(mutableListOf(), integerResultSet(emptyList()))
                Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, arguments ->
                    if (method.name == "prepareStatement") throw failure
                    method.invoke(connection, *arguments.orEmpty())
                } as Connection
            }, NoOpResultSink)
            try {
                runBlocking {
                    session.enter(StartExecutionContext(
                        cc.monomer.execution.driver.spi.RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")),
                        assignment(now.toEpochMilli() + 30_000, inlineTarget()),
                    ))
                    assertEquals(expected, assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult()).safeMessage)
                }
            } finally {
                runBlocking { session.close() }
                dispatcher.close()
            }
        }
    }

    @Test
    fun resultFailuresKeepNamedCodesAndSafeDetails() {
        val now = Instant.parse("2026-07-31T00:00:00Z")
        listOf(
            ResultLimitExceededException(3uL) to "RESULT_LIMIT_EXCEEDED",
            UnsupportedResultTypeException("value", "refcursor") to "UNSUPPORTED_RESULT_TYPE",
        ).forEach { (failure, code) ->
            val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val underlying = integerResultSet(listOf(1))
            val rows = Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java)) { _, method, arguments ->
                if (method.name == "getMetaData") throw failure
                method.invoke(underlying, *arguments.orEmpty())
            } as ResultSet
            val session = session(dispatcher, Clock.fixed(now, ZoneOffset.UTC),
                { _, _ -> fakeConnection(mutableListOf(), rows) }, NoOpResultSink)
            try {
                runBlocking {
                    session.enter(StartExecutionContext(
                        cc.monomer.execution.driver.spi.RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")),
                        assignment(now.toEpochMilli() + 30_000, inlineTarget()),
                    ))
                    val outcome = assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult())
                    assertEquals(code, outcome.failureCode)
                    assertEquals(failure.message, outcome.safeMessage)
                }
            } finally { runBlocking { session.close() }; dispatcher.close() }
        }
    }

    @Test
    fun oversizedFrameFailsWithNamedLimitCodeBeforeEmission() {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val sink = RecordingInlineSink()
        val now = Instant.parse("2026-07-31T00:00:00Z")
        val session = session(dispatcher, Clock.fixed(now, ZoneOffset.UTC),
            { _, _ -> fakeConnection(mutableListOf(), integerResultSet(listOf(1))) }, sink)
        try {
            runBlocking {
                session.enter(StartExecutionContext(
                    cc.monomer.execution.driver.spi.RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")),
                    assignment(now.toEpochMilli() + 30_000, inlineTarget().copy(maximumRecordBatchBytes = 8)),
                ))
                val outcome = assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult())
                assertEquals("RESULT_LIMIT_EXCEEDED", outcome.failureCode)
                assertTrue(outcome.safeMessage.contains("8 byte limit"))
                assertTrue(sink.inlineResults.isEmpty())
            }
        } finally { runBlocking { session.close() }; dispatcher.close() }
    }

    @Test
    fun `inline query opens a connection only when entering and streams arrow batches`() {
        val now = Instant.parse("2026-07-31T00:00:00Z")
        val queryTimeouts = mutableListOf<Int>()
        val sink = RecordingInlineSink()
        val dispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "jdbc-inline") }
            .asCoroutineDispatcher()
        val resultSet = integerResultSet(listOf(1, 2, 3))
        val session =
            session(
                blockingDispatcher = dispatcher,
                clock = Clock.fixed(now, ZoneOffset.UTC),
                connectionFactory = { _, _ -> fakeConnection(queryTimeouts, resultSet) },
                resultSink = sink,
            )
        val assignment = assignment(now.toEpochMilli() + 4_500, inlineTarget())
        try {
            runBlocking {
                session.enter(
                    StartExecutionContext(
                        run = cc.monomer.execution.driver.spi.RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")),
                        assignment = assignment,
                    ),
                )
                assertIs<DriverPhysicalResult.Succeeded>(session.awaitPhysicalResult())
            }
            assertEquals(2, sink.inlineResults.size)
            assertEquals(listOf(1L, 2L, 3L), sink.inlineResults.flatMap(::readInlineFrame))
            assertTrue(queryTimeouts.all { it in 1..30 })
        } finally {
            runBlocking { session.close() }
            dispatcher.close()
        }
    }

    @Test
    fun `expired deadline reports the run deadline before opening a connection`() {
        val now = Instant.parse("2026-07-31T00:00:00Z")
        val opens = AtomicInteger()
        val dispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "jdbc-deadline") }
            .asCoroutineDispatcher()
        val session =
            session(
                blockingDispatcher = dispatcher,
                clock = Clock.fixed(now, ZoneOffset.UTC),
                connectionFactory = { _, _ ->
                    opens.incrementAndGet()
                    error("expired query must not open a JDBC connection")
                },
                resultSink = NoOpResultSink,
            )
        try {
            val result =
                runBlocking {
                    session.enter(
                        StartExecutionContext(
                            run = cc.monomer.execution.driver.spi.RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")),
                            assignment = assignment(now.toEpochMilli() + 999, inlineTarget()),
                        ),
                    )
                    session.awaitPhysicalResult()
                }
            assertEquals("RUN_DEADLINE_EXCEEDED", assertIs<DriverPhysicalResult.Failed>(result).failureCode)
            assertEquals(0, opens.get())
        } finally {
            runBlocking { session.close() }
            dispatcher.close()
        }
    }

    @Test
    fun `total deadline aborts a blocking JDBC call`() {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val cancelMayFinish = CountDownLatch(1)
        val aborted = AtomicBoolean(false)
        val dispatcher = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
        val session =
            session(
                blockingDispatcher = dispatcher,
                clock = Clock.systemUTC(),
                connectionFactory = { _, _ -> blockingConnection(started, released, aborted, cancelMayFinish) },
                resultSink = NoOpResultSink,
            )
        try {
            val result =
                runBlocking {
                    session.enter(
                        StartExecutionContext(
                            run = cc.monomer.execution.driver.spi.RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")),
                            assignment = assignment(System.currentTimeMillis() + 2_000, inlineTarget()),
                        ),
                    )
                    session.awaitPhysicalResult()
                }
            val failure = assertIs<DriverPhysicalResult.Failed>(result)
            assertEquals("RUN_DEADLINE_EXCEEDED", failure.failureCode)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(aborted.get())
            assertTrue(released.await(2, TimeUnit.SECONDS))
        } finally {
            cancelMayFinish.countDown()
            runBlocking { session.close() }
            dispatcher.close()
        }
    }

    @Test
    fun `cancel request does not wait for a blocking JDBC cancel implementation`() {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val cancelMayFinish = CountDownLatch(1)
        val dispatcher = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
        val session =
            session(
                blockingDispatcher = dispatcher,
                clock = Clock.systemUTC(),
                connectionFactory = { _, _ -> blockingConnection(started, released, AtomicBoolean(), cancelMayFinish) },
                resultSink = NoOpResultSink,
            )
        try {
            runBlocking {
                session.enter(
                    StartExecutionContext(
                        run = cc.monomer.execution.driver.spi.RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")),
                        assignment = assignment(System.currentTimeMillis() + 10_000, inlineTarget()),
                    ),
                )
                assertTrue(started.await(2, TimeUnit.SECONDS))
                val startedAt = System.nanoTime()
                assertEquals(
                    DriverCancellationResult.REQUESTED,
                    session.cancel(),
                )
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 500)
            }
        } finally {
            cancelMayFinish.countDown()
            released.countDown()
            runBlocking { session.close() }
            dispatcher.close()
        }
    }

    @Test
    fun `cancel reaches the warehouse before the connection is aborted`() {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val aborted = CountDownLatch(1)
        val cancelReturned = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val statement =
            Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java)) { _, method, _ ->
                when (method.name) {
                    "setQueryTimeout", "close" -> null
                    "execute" -> {
                        started.countDown()
                        released.await(5, TimeUnit.SECONDS)
                        throw java.sql.SQLException("canceling statement due to user request", "57014")
                    }
                    "cancel" -> {
                        if (aborted.await(300, TimeUnit.MILLISECONDS)) {
                            events += "cancel dropped by a closed connection"
                        } else {
                            events += "cancel sent"
                            released.countDown()
                        }
                        cancelReturned.countDown()
                        null
                    }
                    else -> defaultValue(method.returnType)
                }
            } as PreparedStatement
        val connection =
            Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
                when (method.name) {
                    "setReadOnly", "setNetworkTimeout", "close", "rollback" -> null
                    "getAutoCommit" -> true
                    "prepareStatement" -> statement
                    "abort" -> {
                        events += "abort"
                        aborted.countDown()
                        released.countDown()
                        null
                    }
                    else -> defaultValue(method.returnType)
                }
            } as Connection
        val dispatcher = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
        val session =
            session(
                blockingDispatcher = dispatcher,
                clock = Clock.systemUTC(),
                connectionFactory = { _, _ -> connection },
                resultSink = NoOpResultSink,
            )
        try {
            runBlocking {
                session.enter(
                    StartExecutionContext(
                        run = cc.monomer.execution.driver.spi.RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")),
                        assignment = assignment(System.currentTimeMillis() + 10_000, inlineTarget()),
                    ),
                )
                assertTrue(started.await(2, TimeUnit.SECONDS))
                assertEquals(DriverCancellationResult.REQUESTED, session.cancel())
            }
            assertTrue(cancelReturned.await(2, TimeUnit.SECONDS))
            assertTrue(aborted.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("cancel sent", "abort"), events.toList())
        } finally {
            released.countDown()
            runBlocking { session.close() }
            dispatcher.close()
        }
    }

    @Test
    fun `cancel stops the running statement through the dialect`() {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val directStatementCancels = AtomicInteger()
        val statement =
            Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java)) { _, method, _ ->
                when (method.name) {
                    "setQueryTimeout", "close" -> null
                    "execute" -> {
                        started.countDown()
                        released.await(5, TimeUnit.SECONDS)
                        throw java.sql.SQLException("canceling statement due to user request", "57014")
                    }
                    "cancel" -> {
                        directStatementCancels.incrementAndGet()
                        null
                    }
                    else -> defaultValue(method.returnType)
                }
            } as PreparedStatement
        val connection =
            Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
                when (method.name) {
                    "setReadOnly", "setNetworkTimeout", "close", "rollback", "abort" -> null
                    "getAutoCommit" -> true
                    "prepareStatement" -> statement
                    else -> defaultValue(method.returnType)
                }
            } as Connection
        val dialectCancelled = java.util.concurrent.CompletableFuture<Statement>()
        val dialect =
            object : JdbcConnectorDialect by FakeDialect {
                override fun cancelRunningStatement(statement: Statement) {
                    dialectCancelled.complete(statement)
                    released.countDown()
                }
            }
        val dispatcher = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
        val session =
            session(
                blockingDispatcher = dispatcher,
                clock = Clock.systemUTC(),
                connectionFactory = { _, _ -> connection },
                resultSink = NoOpResultSink,
                dialect = dialect,
            )
        try {
            runBlocking {
                session.enter(
                    StartExecutionContext(
                        run = cc.monomer.execution.driver.spi.RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")),
                        assignment = assignment(System.currentTimeMillis() + 10_000, inlineTarget()),
                    ),
                )
                assertTrue(started.await(2, TimeUnit.SECONDS))
                assertEquals(DriverCancellationResult.REQUESTED, session.cancel())
            }
            assertTrue(dialectCancelled.get(2, TimeUnit.SECONDS) === statement)
            assertEquals(0, directStatementCancels.get())
        } finally {
            released.countDown()
            runBlocking { session.close() }
            dispatcher.close()
        }
    }

    @Test
    fun `deadline during dialect preparation aborts the connection and reports the run deadline`() {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val aborted = AtomicBoolean(false)
        val dispatcher = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
        val session =
            session(
                blockingDispatcher = dispatcher,
                clock = Clock.systemUTC(),
                connectionFactory = { _, _ -> blockingConnection(started, released, aborted, null) },
                resultSink = NoOpResultSink,
                dialect = BlockingPrepareDialect(started, released),
            )
        try {
            val result =
                runBlocking {
                    session.enter(
                        StartExecutionContext(
                            run = cc.monomer.execution.driver.spi.RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")),
                            assignment = assignment(System.currentTimeMillis() + 1_100, inlineTarget()),
                        ),
                    )
                    session.awaitPhysicalResult()
                }
            assertEquals("RUN_DEADLINE_EXCEEDED", assertIs<DriverPhysicalResult.Failed>(result).failureCode)
            assertTrue(aborted.get())
            assertTrue(released.await(2, TimeUnit.SECONDS))
        } finally {
            released.countDown()
            runBlocking { session.close() }
            dispatcher.close()
        }
    }

    @Test
    fun `a cancelled caller that reaches the deadline path stays cancelled`() {
        val now = Instant.parse("2026-07-31T00:00:00Z")
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val session =
            session(
                blockingDispatcher = dispatcher,
                clock = Clock.fixed(now, ZoneOffset.UTC),
                connectionFactory = { _, _ -> error("expired query must not open a JDBC connection") },
                resultSink = NoOpResultSink,
            )
        try {
            runBlocking {
                val entering =
                    async {
                        coroutineContext.job.cancel()
                        session.enter(
                            StartExecutionContext(
                                run = cc.monomer.execution.driver.spi.RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")),
                                assignment = assignment(now.toEpochMilli() + 999, inlineTarget()),
                            ),
                        )
                    }
                assertIs<kotlinx.coroutines.CancellationException>(runCatching { entering.await() }.exceptionOrNull())
                assertEquals(null, kotlinx.coroutines.withTimeoutOrNull(100) { session.awaitPhysicalResult() })
            }
        } finally {
            runBlocking { session.close() }
            dispatcher.close()
        }
    }

    @Test
    fun `connection setup that leaves less than the execution minimum reports the run deadline`() {
        val now = Instant.parse("2026-10-11T00:00:00Z").toEpochMilli()
        val deadline = now + 30_000
        val clockMillis = java.util.concurrent.atomic.AtomicLong(now)
        val closed = AtomicBoolean(false)
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val session =
            session(
                blockingDispatcher = dispatcher,
                clock = MovableClock(clockMillis),
                connectionFactory = { _, _ ->
                    clockMillis.set(deadline - 500)
                    val connection = fakeConnection(mutableListOf(), integerResultSet(listOf(1)))
                    Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                        if (method.name == "close") closed.set(true)
                        method.invoke(connection, *args.orEmpty())
                    } as Connection
                },
                resultSink = NoOpResultSink,
            )
        try {
            val result =
                runBlocking {
                    session.enter(
                        StartExecutionContext(
                            run = cc.monomer.execution.driver.spi.RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")),
                            assignment = assignment(deadline, inlineTarget()),
                        ),
                    )
                    session.awaitPhysicalResult()
                }
            assertEquals("RUN_DEADLINE_EXCEEDED", assertIs<DriverPhysicalResult.Failed>(result).failureCode)
            assertTrue(closed.get())
        } finally {
            runBlocking { session.close() }
            dispatcher.close()
        }
    }

    @Test
    fun `connect failure reports the run deadline only once the deadline has passed`() {
        val now = Instant.parse("2026-10-11T00:00:00Z").toEpochMilli()
        val deadline = now + 30_000
        listOf(false, true).forEach { deadlinePassed ->
            val clockMillis = java.util.concurrent.atomic.AtomicLong(now)
            val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val session =
                session(
                    blockingDispatcher = dispatcher,
                    clock = MovableClock(clockMillis),
                    connectionFactory = { _, _ ->
                        if (deadlinePassed) clockMillis.set(deadline)
                        throw java.sql.SQLException("connect timed out", "08001")
                    },
                    resultSink = NoOpResultSink,
                )
            try {
                val result =
                    runBlocking {
                        session.enter(
                            StartExecutionContext(
                                run = cc.monomer.execution.driver.spi.RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")),
                                assignment = assignment(deadline, inlineTarget()),
                            ),
                        )
                        session.awaitPhysicalResult()
                    }
                assertEquals(
                    if (deadlinePassed) "RUN_DEADLINE_EXCEEDED" else "JDBC_FAILED",
                    assertIs<DriverPhysicalResult.Failed>(result).failureCode,
                )
            } finally {
                runBlocking { session.close() }
                dispatcher.close()
            }
        }
    }

    private class MovableClock(private val millis: java.util.concurrent.atomic.AtomicLong) : Clock() {
        override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis.get())
    }

    @Test
    fun `deadline can abort a connection blocked while closing`() {
        val closeStarted = CountDownLatch(1)
        val closeReleased = CountDownLatch(1)
        val aborted = AtomicBoolean(false)
        val dispatcher = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
        val timing = JdbcTimingFixture.timing(ConnectorType.POSTGRESQL, DriverOperation.QUERY)
        val session =
            session(
                blockingDispatcher = dispatcher,
                clock = Clock.systemUTC(),
                connectionFactory = { _, _ ->
                    closingConnection(
                        resultSet = integerResultSet(listOf(1)),
                        closeStarted = closeStarted,
                        closeReleased = closeReleased,
                        aborted = aborted,
                    )
                },
                resultSink = RecordingInlineSink(),
                timing = timing,
            )
        try {
            val result =
                runBlocking {
                    var capturedResult: DriverPhysicalResult? = null
                    JdbcTimingFixture.summary(ConnectorType.POSTGRESQL, DriverOperation.QUERY,
                        "RUN_DEADLINE_EXCEEDED", cc.monomer.execution.driver.spi.DriverPhase.CLEANUP,
                        setOf(cc.monomer.execution.driver.spi.DriverPhase.CLEANUP), operationTiming = timing) {
                    session.enter(
                        StartExecutionContext(
                            run = cc.monomer.execution.driver.spi.RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")),
                            assignment = assignment(System.currentTimeMillis() + 2_000, inlineTarget()),
                        ),
                    )
                    session.awaitPhysicalResult().also { capturedResult = it }
                    }
                    requireNotNull(capturedResult)
                }
            val failure = assertIs<DriverPhysicalResult.Failed>(result)
            assertEquals("RUN_DEADLINE_EXCEEDED", failure.failureCode)
            assertTrue(closeStarted.await(3, TimeUnit.SECONDS))
            assertTrue(aborted.get())
            assertTrue(closeReleased.await(3, TimeUnit.SECONDS))
        } finally {
            closeReleased.countDown()
            runBlocking { session.close() }
            dispatcher.close()
        }
    }

    private fun session(
        blockingDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        clock: Clock,
        connectionFactory: JdbcConnectionFactory,
        resultSink: RunResultSink,
        dialect: JdbcConnectorDialect = FakeDialect,
        timing: DriverOperationTiming = DriverOperationTiming(ConnectorType.POSTGRESQL, DriverOperation.QUERY, null, null),
    ): JdbcExecutionDriverSession =
        JdbcExecutionDriverSession(
            connectorType = ConnectorType.POSTGRESQL,
            dialect = dialect,
            connector = connector(),
            credential = MaterializedCredential(emptyMap()),
            resultSink = resultSink,
            arrowEncoder =
                JdbcArrowEncoder(
                    batchSize = 128,
                    decimalPhysicalEncoding = JdbcDecimalPhysicalEncoding.NATIVE_DECIMAL,
                    valueReader = JavaTimeJdbcResultValueReader,
                ),
            maximumQuerySeconds = 30,
            timeoutPolicy =
                JdbcRunTimeoutPolicy(
                    maximumConnectMillis = 1_000,
                    maximumSocketMillis = 20_000,
                    maximumLockMillis = 5_000,
                ),
            blockingDispatcher = blockingDispatcher,
            clock = clock,
            connectionFactory = connectionFactory,
            timing = timing,
        )

    private fun assignment(deadlineEpochMillis: Long, target: QueryResultTarget): RunAssignment {
        val unsigned =
            QueryRequest(
                validateRawSqlBeforeRun = false,
                expectedColumnNames = emptyList(),
                schemaVersion = 1u,
                canonicalRequestDigest = digest('0'),
                sql = "SELECT value FROM public.orders",
                parameters = emptyList(),
                resultTarget = target,
            )
        val request = unsigned.copy(canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(unsigned))
        return RunAssignment(
            workspaceId = WorkspaceId.parse("019f0000-0000-7000-8000-000000000002"),
            connector = connector(),
            request = request,
            deadlineEpochMillis = deadlineEpochMillis,
        )
    }

    private fun inlineTarget() =
        QueryResultTarget.InlineStream(
            maximumRecordBatchRows = 2,
            maximumRecordBatchBytes = 64 * 1024,
            maximumCellBytes = 64 * 1024,
        )

    private fun connector() =
        ConnectorRuntimeBinding(
            schemaVersion = 1u,
            connectorId = ConnectorId.parse("019f0000-0000-7000-8000-000000000003"),
            connectorType = ConnectorType.POSTGRESQL,
            properties = emptyMap(),
        )

    private fun fakeConnection(queryTimeouts: MutableList<Int>, resultSet: ResultSet): Connection {
        val statement = fakePreparedStatement(queryTimeouts, resultSet)
        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when (method.name) {
                "setReadOnly", "close", "rollback", "setAutoCommit", "setNetworkTimeout" -> null
                "getAutoCommit" -> true
                "prepareStatement" -> statement
                "isClosed" -> false
                "isWrapperFor" -> false
                "unwrap" -> null
                "toString" -> "FakeConnection"
                else -> defaultValue(method.returnType)
            }
        } as Connection
    }

    private fun blockingConnection(
        started: CountDownLatch,
        released: CountDownLatch,
        aborted: AtomicBoolean,
        cancelMayFinish: CountDownLatch?,
    ): Connection {
        val statement =
            Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java)) { _, method, _ ->
                when (method.name) {
                    "setQueryTimeout", "close" -> null
                    "execute" -> {
                        started.countDown()
                        released.await(5, TimeUnit.SECONDS)
                        throw java.sql.SQLException("query was cancelled")
                    }
                    "cancel" -> {
                        cancelMayFinish?.await(5, TimeUnit.SECONDS)
                        released.countDown()
                        null
                    }
                    else -> defaultValue(method.returnType)
                }
            } as PreparedStatement
        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when (method.name) {
                "setReadOnly", "setNetworkTimeout", "close", "rollback" -> null
                "getAutoCommit" -> true
                "prepareStatement" -> statement
                "abort" -> {
                    aborted.set(true)
                    released.countDown()
                    null
                }
                else -> defaultValue(method.returnType)
            }
        } as Connection
    }

    private fun closingConnection(
        resultSet: ResultSet,
        closeStarted: CountDownLatch,
        closeReleased: CountDownLatch,
        aborted: AtomicBoolean,
    ): Connection {
        val statement = fakePreparedStatement(mutableListOf(), resultSet)
        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when (method.name) {
                "setReadOnly", "setNetworkTimeout", "rollback" -> null
                "getAutoCommit" -> true
                "prepareStatement" -> statement
                "close" -> {
                    closeStarted.countDown()
                    closeReleased.await(5, TimeUnit.SECONDS)
                    null
                }
                "abort" -> {
                    aborted.set(true)
                    closeReleased.countDown()
                    null
                }
                else -> defaultValue(method.returnType)
            }
        } as Connection
    }

    private fun integerResultSet(values: List<Int>): ResultSet {
        val rowIndex = AtomicInteger(-1)
        val metadata =
            Proxy.newProxyInstance(ResultSetMetaData::class.java.classLoader, arrayOf(ResultSetMetaData::class.java)) { _, method, _ ->
                when (method.name) {
                    "getColumnCount" -> 1
                    "getColumnLabel", "getColumnName" -> "value"
                    "getColumnType" -> Types.INTEGER
                    "getColumnTypeName" -> "INTEGER"
                    "isNullable" -> ResultSetMetaData.columnNoNulls
                    else -> defaultValue(method.returnType)
                }
            } as ResultSetMetaData
        return Proxy.newProxyInstance(ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java)) { _, method, _ ->
            when (method.name) {
                "next" -> {
                    val next = rowIndex.get() + 1
                    if (next < values.size) {
                        rowIndex.set(next)
                        true
                    } else false
                }
                "getInt" -> values[rowIndex.get()]
                "getMetaData" -> metadata
                "wasNull" -> false
                "close" -> null
                "isClosed" -> false
                "isWrapperFor" -> false
                "unwrap" -> null
                "toString" -> "IntegerResultSet"
                else -> defaultValue(method.returnType)
            }
        } as ResultSet
    }

    private fun fakePreparedStatement(queryTimeouts: MutableList<Int>, resultSet: ResultSet): PreparedStatement =
        Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java)) { _, method, args ->
            when (method.name) {
                "setQueryTimeout" -> {
                    queryTimeouts += args!![0] as Int
                    null
                }
                "execute" -> true
                "getResultSet" -> resultSet
                "close", "setString", "setLong", "setDouble", "setBoolean", "setBytes", "setNull" -> null
                "isClosed" -> false
                "isWrapperFor" -> false
                "unwrap" -> null
                "toString" -> "FakePreparedStatement"
                else -> defaultValue(method.returnType)
            }
        } as PreparedStatement

    private fun defaultValue(type: Class<*>): Any? =
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            java.lang.Float.TYPE -> 0.0f
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }

    private fun digest(character: Char): Sha256Digest = Sha256Digest(character.toString().repeat(64))

    private object FakeDialect : JdbcConnectorDialect {
        override val serializeConnectionLifecycle = false
        override fun jdbcUrl(connector: ConnectorRuntimeBinding): String = "jdbc:fake"
        override fun connectionProperties(
            connector: ConnectorRuntimeBinding,
            credential: MaterializedCredential,
            timeoutBudget: JdbcRunTimeoutBudget,
        ): Properties = Properties()
        override fun prepareConnection(
            connection: Connection,
            connector: ConnectorRuntimeBinding,
            timeoutBudget: JdbcRunTimeoutBudget,
        ) = Unit
        override fun refreshTimeouts(connection: Connection, timeoutBudget: JdbcRunTimeoutBudget) = Unit
        override fun cancelRunningStatement(statement: Statement) = statement.cancel()
        override fun safeFailureCode(failure: Throwable): String = "JDBC_FAILED"
    }

    private class BlockingPrepareDialect(
        private val started: CountDownLatch,
        private val released: CountDownLatch,
    ) : JdbcConnectorDialect {
        override val serializeConnectionLifecycle = false
        override fun jdbcUrl(connector: ConnectorRuntimeBinding): String = "jdbc:fake"
        override fun connectionProperties(
            connector: ConnectorRuntimeBinding,
            credential: MaterializedCredential,
            timeoutBudget: JdbcRunTimeoutBudget,
        ): Properties = Properties()
        override fun prepareConnection(
            connection: Connection,
            connector: ConnectorRuntimeBinding,
            timeoutBudget: JdbcRunTimeoutBudget,
        ) {
            started.countDown()
            released.await(5, TimeUnit.SECONDS)
        }
        override fun refreshTimeouts(connection: Connection, timeoutBudget: JdbcRunTimeoutBudget) = Unit
        override fun cancelRunningStatement(statement: Statement) = statement.cancel()
        override fun safeFailureCode(failure: Throwable): String = "JDBC_FAILED"
    }

    private object NoOpResultSink : RunResultSink {
        override suspend fun emitInlineResult(batch: InlineResultBatch) = Unit
        override suspend fun openObjectUpload(objectKey: String, mediaType: String): ArtifactUploadSink =
            error("not used")
    }

    private class RecordingInlineSink : RunResultSink {
        val inlineResults = mutableListOf<InlineResultBatch>()
        override suspend fun emitInlineResult(batch: InlineResultBatch) { inlineResults += batch }
        override suspend fun openObjectUpload(objectKey: String, mediaType: String): ArtifactUploadSink =
            error("inline query must not open object storage")
    }

    private fun readInlineFrame(batch: InlineResultBatch): List<Long> {
        val bytes = batch.arrowIpc.copyBytes()
        try {
            org.apache.arrow.memory.RootAllocator().use { allocator ->
                org.apache.arrow.vector.ipc.ArrowFileReader(
                    org.apache.arrow.vector.ipc.SeekableReadChannel(
                        org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel(bytes),
                    ),
                    allocator,
                ).use { reader ->
                    return reader.recordBlocks.flatMap { block ->
                        reader.loadRecordBatch(block)
                        val vector = reader.vectorSchemaRoot.getVector("value") as org.apache.arrow.vector.IntVector
                        (0 until vector.valueCount).map { index -> vector.get(index).toLong() }
                    }
                }
            }
        } finally {
            bytes.fill(0)
        }
    }
}
