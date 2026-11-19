package cc.monomer.execution.driver.duckdb

import cc.monomer.execution.driver.spi.*
import java.sql.SQLException
import kotlin.test.*
import kotlinx.coroutines.*
import org.duckdb.MonomerDuckDbInterrupt
import org.duckdb.MonomerDuckDbTestAccess

class DuckDbCancellationTest {
    @Test fun `native interrupt registered before prepare stops real constant folding and closes safely`() = runBlocking<Unit> {
        DuckDbFixture().use { fixture -> DuckDbSampleDatasets(fixture.root).use { datasets ->
            val connection = datasets.duplicate("acme-cloud@1")
            val interrupt = MonomerDuckDbInterrupt(connection)
            val prepared = async(Dispatchers.IO) {
                runCatching { connection.prepareStatement("SELECT list_sum(list_transform(range(10000000), x -> sin(x)))").use { it.metaData } }
            }
            withTimeout(5000) { while (!MonomerDuckDbTestAccess.connectionLockHeld(connection)) delay(1) }
            delay(20)
            interrupt.interrupt()
            val failure = assertIs<SQLException>(withTimeout(3000) { prepared.await() }.exceptionOrNull())
            assertTrue(failure.message.orEmpty().contains("interrupt", ignoreCase = true), failure.message)
            coroutineScope {
                val interrupts = launch(Dispatchers.IO) { repeat(1000) { interrupt.interrupt() } }
                val closing = launch(Dispatchers.IO) { interrupt.close() }
                interrupts.join(); closing.join()
            }
            assertTrue(connection.isClosed)
            interrupt.interrupt()
            datasets.duplicate("acme-cloud@1").use { reopened -> assertTrue(reopened.isValid(1)) }
        } }
    }

    @Test fun `RPC cancellation during execution interrupts only its duplicated connection`() = runBlocking<Unit> {
        DuckDbFixture().use { fixture -> fixture.provider().use { provider ->
            val session = provider.openSession(sampleConnector, sampleCredential(), discardSink, timing(DriverOperation.QUERY))
            try {
                session.enter(context("SELECT sum(sin(i)) FROM range(1000000000000) t(i)", 120_000, QueryResultTarget.InlineStream(16, 65_536, 65_536)))
                delay(100)
                val second = runQuery(provider, "SELECT 42", 5000, discardSink)
                assertIs<DriverPhysicalResult.Succeeded>(second)
                assertEquals(DriverCancellationResult.REQUESTED, session.cancel())
                assertEquals(DriverPhysicalResult.Cancelled, withTimeout(3000) { session.awaitPhysicalResult() })
            } finally { withTimeout(3000) { session.close() } }
            assertIs<DriverPhysicalResult.Succeeded>(runQuery(provider, "SELECT count(*) FROM fixture", 5000, discardSink))
        } }
    }

    @Test fun `RPC cancellation during streaming delivery and validation cleans all query resources`() = runBlocking<Unit> {
        DuckDbFixture().use { fixture -> fixture.provider().use { provider ->
            val delivering = CompletableDeferred<Unit>()
            val sink = object : RunResultSink {
                override suspend fun emitInlineResult(batch: InlineResultBatch) { delivering.complete(Unit); awaitCancellation() }
                override suspend fun openObjectUpload(objectKey: String, mediaType: String): ArtifactUploadSink = error("unused")
            }
            val session = provider.openSession(sampleConnector, sampleCredential(), sink, timing(DriverOperation.QUERY))
            try {
                session.enter(context("SELECT i FROM range(1000000000000) t(i)", 120_000, QueryResultTarget.InlineStream(16, 65_536, 65_536)))
                withTimeout(3000) { delivering.await() }
                session.cancel()
                assertEquals(DriverPhysicalResult.Cancelled, withTimeout(3000) { session.awaitPhysicalResult() })
            } finally { withTimeout(3000) { session.close() } }
            val validation = launch(Dispatchers.IO) {
                sampleCredential().use { credential ->
                    provider.validateRawSql(sampleConnector, credential, "SELECT list_sum(list_transform(range(10000000), x -> sin(x)))", emptyList(), System.currentTimeMillis() + 120_000, timing(DriverOperation.VALIDATE))
                }
            }
            delay(50)
            withTimeout(3000) { validation.cancelAndJoin() }
            assertIs<DriverPhysicalResult.Succeeded>(runQuery(provider, "SELECT 42", 5000, discardSink))
        } }
    }

    @Test fun `long query families reach the driver 60 second limit despite a longer Control deadline`() = runBlocking<Unit> {
        assertEquals(60_000L, DUCKDB_EXECUTION_LIMIT_MILLIS)
        DuckDbFixture().use { fixture -> fixture.provider().use { provider ->
            val queries = listOf(
                "SELECT sum(sin(i)) FROM range(1000000000000) t(i)",
                "SELECT sum(sin(a.i + b.i)) FROM range(1000000000000) a(i) CROSS JOIN range(10000) b(i)",
                "WITH RECURSIVE counter(k, i) USING KEY (k) AS (SELECT 1, 0 UNION SELECT k, i + 1 FROM counter WHERE i < 1000000000) SELECT max(i) FROM counter",
                "SELECT i FROM range(1000000000000) t(i) ORDER BY hash(i) LIMIT 10",
            )
            val outcomes = queries.map { sql -> async(Dispatchers.IO) {
                val started = System.nanoTime()
                val outcome = runQuery(provider, sql, 120_000, discardSink)
                val elapsedMillis = (System.nanoTime() - started) / 1_000_000
                assertEquals("RUN_DEADLINE_EXCEEDED", assertIs<DriverPhysicalResult.Failed>(outcome).failureCode, sql)
                assertTrue(elapsedMillis in 59_000..70_000, "$elapsedMillis ms: $sql")
            } }
            withTimeout(75_000) { outcomes.awaitAll() }
            assertIs<DriverPhysicalResult.Succeeded>(runQuery(provider, "SELECT 1", 5000, discardSink))
        } }
    }

    @Test fun `shorter Control deadline covers validation prepare execution and result delivery`() = runBlocking<Unit> {
        DuckDbFixture().use { fixture -> fixture.provider().use { provider ->
            val deadlineResult = runQuery(provider, "SELECT sum(sin(i)) FROM range(1000000000000) t(i)", 100, discardSink)
            assertEquals("RUN_DEADLINE_EXCEEDED", assertIs<DriverPhysicalResult.Failed>(deadlineResult).failureCode)
            val stalledSink = object : RunResultSink {
                override suspend fun emitInlineResult(batch: InlineResultBatch) { awaitCancellation() }
                override suspend fun openObjectUpload(objectKey: String, mediaType: String): ArtifactUploadSink = error("unused")
            }
            val deliveryResult = runQuery(provider, "SELECT 1", 100, stalledSink)
            assertEquals("RUN_DEADLINE_EXCEEDED", assertIs<DriverPhysicalResult.Failed>(deliveryResult).failureCode)
            sampleCredential().use { credential ->
                val expired = assertFailsWith<RawSqlValidationException> {
                    provider.validateRawSql(sampleConnector, credential, "SELECT 1", emptyList(), System.currentTimeMillis() - 1000, timing(DriverOperation.VALIDATE))
                }
                assertEquals("RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", expired.failureCode)
                val failure = assertFailsWith<RawSqlValidationException> {
                    provider.validateRawSql(sampleConnector, credential, "SELECT list_sum(list_transform(range(10000000), x -> sin(x)))", emptyList(), System.currentTimeMillis() + 30, timing(DriverOperation.VALIDATE))
                }
                assertEquals("RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", failure.failureCode)
            }
        } }
    }
}
