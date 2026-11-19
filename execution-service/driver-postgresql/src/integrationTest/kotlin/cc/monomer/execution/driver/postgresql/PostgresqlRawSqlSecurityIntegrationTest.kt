package cc.monomer.execution.driver.postgresql

import cc.monomer.execution.driver.spi.DriverOperationTiming
import cc.monomer.execution.driver.spi.DriverOperation
import cc.monomer.execution.driver.spi.QueryParameter
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.ConnectorId
import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.Sha256Digest
import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutBudget
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.assertIs
import cc.monomer.execution.driver.jdbc.JdbcConnectionFactory
import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutPolicy
import cc.monomer.execution.driver.spi.ArtifactUploadSink
import cc.monomer.execution.driver.spi.DriverCancellationResult
import cc.monomer.execution.driver.spi.DriverPhysicalResult
import cc.monomer.execution.driver.spi.InlineResultBatch
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.QueryRequest
import cc.monomer.execution.driver.spi.QueryRequestCanonicalizer
import cc.monomer.execution.driver.spi.QueryResultTarget
import cc.monomer.execution.driver.spi.RawSqlValidationException
import cc.monomer.execution.driver.spi.RunAssignment
import cc.monomer.execution.driver.spi.RunId
import cc.monomer.execution.driver.spi.RunIdentity
import cc.monomer.execution.driver.spi.RunResultSink
import cc.monomer.execution.driver.spi.SecretCharacters
import cc.monomer.execution.driver.spi.StartExecutionContext
import cc.monomer.execution.driver.spi.WorkspaceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.TimeStampMicroTZVector
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.ipc.SeekableReadChannel
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel
import org.junit.jupiter.api.extension.RegisterExtension

class PostgresqlRawSqlSecurityIntegrationTest {
    @Test
    fun `session read only default survives transaction completion`() {
        fixtureConnection().use { connection ->
            repeat(2) {
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT current_setting('default_transaction_read_only'), " +
                        "current_setting('transaction_read_only')").use { rows ->
                        assertTrue(rows.next())
                        assertEquals("on", rows.getString(1))
                        assertEquals("on", rows.getString(2))
                    }
                }
                connection.commit()
            }
        }
    }

    @Test
    fun `security fixture connects as a non administrator with write permission`() {
        fixtureConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT current_user, rolsuper, rolcreaterole, rolcreatedb, rolbypassrls, " +
                    "has_table_privilege(current_user, 'validation_fixture.fixture', 'INSERT, UPDATE, DELETE') " +
                    "FROM pg_roles WHERE rolname = current_user").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("validation_user", rows.getString(1))
                    (2..5).forEach { assertEquals(false, rows.getBoolean(it)) }
                    assertTrue(rows.getBoolean(6))
                }
            }
        }
    }

    @Test
    fun `validation rejects transaction control multiple commands syntax and missing output`() = runBlocking {
        setupFixture()
        listOf("-- only comment", "COMMIT", "BEGIN", "SET TRANSACTION READ WRITE",
            "SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE",
            "SELECT 1; SELECT 2", "SELECT 1; COMMIT; DELETE FROM fixture RETURNING value",
            "SELECT 1; -- line comment\nCOMMIT", "SELECT 1; SET application_name = 'unexpected'",
            "SELECT 1; /* comment */ SET application_name = 'unexpected'", "SELECT 1; /* unclosed",
            "SELECT 1; /* outer /* inner */", "SELECT 1 /* unclosed", "SELEC 1",
            "CREATE TEMP TABLE forbidden(value int)", "SELECT 1 INTO TEMP forbidden",
            "DELETE FROM fixture").forEach { sql ->
            credential().use { credential ->
                val failure = assertFailsWith<RawSqlValidationException>(sql) {
                    provider().validateRawSql(connector(), credential, sql, emptyList(), deadline(), timing = DriverOperationTiming(ConnectorType.POSTGRESQL, DriverOperation.VALIDATE, null, null))
                }
                assertEquals("RAW_SQL_VALIDATION_REJECTED", failure.failureCode, sql)
            }
        }
    }

    @Test
    fun `actual production path blocks write and transaction escape attempts`() = runBlocking {
        setupFixture()
        listOf(
            "DELETE FROM fixture RETURNING value" to "READ_ONLY_VIOLATION",
            "WITH changed AS (DELETE FROM fixture RETURNING value) SELECT * FROM changed" to "READ_ONLY_VIOLATION",
            "SELECT * FROM fixture FOR UPDATE" to "READ_ONLY_VIOLATION",
            "SELECT * FROM fixture FOR SHARE" to "READ_ONLY_VIOLATION",
            "SELECT trap()" to "READ_ONLY_VIOLATION",
            "EXPLAIN ANALYZE DELETE FROM fixture" to "READ_ONLY_VIOLATION",
            "CALL write_with_output(NULL)" to "READ_ONLY_VIOLATION",
            "SELECT set_config('transaction_read_only','off',true)" to "POSTGRESQL_EXECUTION_FAILED",
        ).forEach { (sql, expectedCode) ->
            credential().use { credential ->
                val provider = provider()
                provider.validateRawSql(connector(), credential, sql, emptyList(), deadline(), timing = DriverOperationTiming(ConnectorType.POSTGRESQL, DriverOperation.VALIDATE, null, null))
                val session = provider.openSession(connector(), credential, sink, timing = DriverOperationTiming(ConnectorType.POSTGRESQL, DriverOperation.QUERY, null, null))
                try {
                    val request = QueryRequest(1u, Sha256Digest("0".repeat(64)), sql, emptyList(),
                        QueryResultTarget.InlineStream(100, 65536, 65536), false, emptyList())
                    session.enter(StartExecutionContext(
                        RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")),
                        RunAssignment(WorkspaceId.parse("019f0000-0000-7000-8000-000000000002"), connector(),
                            request.copy(canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(request)), deadline()),
                    ))
                    assertEquals(expectedCode, assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult(), sql).failureCode, sql)
                } finally {
                    session.close()
                }
            }
        }
        EphemeralPostgresqlWarehouse.openAdminConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM validation_fixture.fixture").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(1, rows.getInt(1))
                }
            }
        }
    }

    @Test
    fun `warehouse permission denial retains its code during validation and execution`() = runBlocking {
        setupFixture()
        EphemeralPostgresqlWarehouse.openAdminConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA permission_fixture")
                statement.execute("CREATE TABLE permission_fixture.private_data(value int)")
            }
        }
        val sql = "SELECT * FROM permission_fixture.private_data"
        credential().use { credential ->
            val provider = provider()
            val rejected = assertFailsWith<RawSqlValidationException> {
                provider.validateRawSql(connector(), credential, sql, emptyList(), deadline(), timing = DriverOperationTiming(ConnectorType.POSTGRESQL, DriverOperation.VALIDATE, null, null))
            }
            assertEquals("PERMISSION_DENIED", rejected.failureCode)
            assertEquals("The Connector's warehouse account is not allowed to run this SQL.", rejected.safeMessage)
            val session = provider.openSession(connector(), credential, sink, timing = DriverOperationTiming(ConnectorType.POSTGRESQL, DriverOperation.QUERY, null, null))
            try {
                val request = QueryRequest(1u, Sha256Digest("0".repeat(64)), sql, emptyList(),
                    QueryResultTarget.InlineStream(100, 65536, 65536), false, emptyList())
                session.enter(StartExecutionContext(
                    RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000011")),
                    RunAssignment(WorkspaceId.parse("019f0000-0000-7000-8000-000000000002"), connector(),
                        request.copy(canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(request)), deadline()),
                ))
                val failed = assertIs<DriverPhysicalResult.Failed>(session.awaitPhysicalResult())
                assertEquals("PERMISSION_DENIED", failed.failureCode)
                assertTrue(failed.safeMessage.contains("permission denied", ignoreCase = true))
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `validation does not execute and actual query executes exactly once`() {
        val sql = "SELECT 42 AS wp1_execution_once"
        fixtureConnection().use { connection ->
            PostgresqlRawSqlValidator(connection).validate(sql, emptyList())
            assertEquals(0, EphemeralPostgresqlWarehouse.logs().lineSequence()
                .count { it.contains("execute ") && it.contains(sql) })
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals(42, result.getInt(1))
                }
            }
            assertEquals(1, EphemeralPostgresqlWarehouse.logs().lineSequence()
                .count { it.contains("execute ") && it.contains(sql) })
        }
    }

    @Test
    fun `original SQL executes once and returns first result with trailing comment fragments`() {
        listOf("SELECT 42 AS trailing_plain;", "SELECT 42 AS trailing_line; -- comment",
            "SELECT 42 AS trailing_block; /* comment */", "SELECT 42 AS trailing_nested; /* outer /* inner */ end */",
            "/* leading */ SELECT 42 AS leading_comment", "SELECT 42 AS inner_line -- a;b",
            "SELECT 42 AS quoted_semicolon, ';';").forEach { sql ->
            fixtureConnection().use { connection ->
                PostgresqlRawSqlValidator(connection).validate(sql, emptyList())
                val queryText = sql.substringBefore(';').substringBefore(" --")
                assertEquals(0, EphemeralPostgresqlWarehouse.logs().lineSequence()
                    .count { it.contains("execute ") && it.contains(queryText) }, sql)
                connection.prepareStatement(sql).use { statement ->
                    assertTrue(statement.execute(), sql)
                    statement.resultSet.use { result ->
                        assertTrue(result.next(), sql)
                        assertEquals(42, result.getInt(1), sql)
                        assertEquals(false, result.next(), sql)
                    }
                    assertEquals(false, statement.moreResults, sql)
                }
                assertEquals(1, EphemeralPostgresqlWarehouse.logs().lineSequence()
                    .count { it.contains("execute ") && it.contains(queryText) }, sql)
            }
        }
    }

    @Test
    fun `production session executes original SQL with trailing comment fragments`() = runBlocking {
        setupFixture()
        listOf("SELECT 42; -- trailing", "SELECT 42; /* trailing */").forEach { sql ->
            credential().use { credential ->
                val provider = provider()
                provider.validateRawSql(connector(), credential, sql, emptyList(), deadline(), timing = DriverOperationTiming(ConnectorType.POSTGRESQL, DriverOperation.VALIDATE, null, null))
                val session = provider.openSession(connector(), credential, sink, timing = DriverOperationTiming(ConnectorType.POSTGRESQL, DriverOperation.QUERY, null, null))
                try {
                    val request = QueryRequest(1u, Sha256Digest("0".repeat(64)), sql, emptyList(),
                        QueryResultTarget.InlineStream(100, 65536, 65536), false, emptyList())
                    session.enter(StartExecutionContext(
                        RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")),
                        RunAssignment(WorkspaceId.parse("019f0000-0000-7000-8000-000000000002"), connector(),
                            request.copy(canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(request)), deadline()),
                    ))
                    assertEquals(1uL, assertIs<DriverPhysicalResult.Succeeded>(session.awaitPhysicalResult(), sql).rowsProcessed, sql)
                } finally {
                    session.close()
                }
            }
        }
    }

    @Test
    fun `production session executes once and fetches a multi batch result through a server cursor`() = runBlocking {
        setupFixture()
        val sql = "SELECT g AS cursor_streamed FROM generate_series(1, 250) AS g"
        credential().use { credential ->
            val session = provider().openSession(connector(), credential, sink, timing = DriverOperationTiming(ConnectorType.POSTGRESQL, DriverOperation.QUERY, null, null))
            try {
                val request = QueryRequest(1u, Sha256Digest("0".repeat(64)), sql, emptyList(),
                    QueryResultTarget.InlineStream(100, 65536, 65536), false, emptyList())
                session.enter(StartExecutionContext(
                    RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000021")),
                    RunAssignment(WorkspaceId.parse("019f0000-0000-7000-8000-000000000002"), connector(),
                        request.copy(canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(request)), deadline()),
                ))
                assertEquals(250uL, assertIs<DriverPhysicalResult.Succeeded>(session.awaitPhysicalResult()).rowsProcessed)
            } finally {
                session.close()
            }
        }
        val queryLogLines = EphemeralPostgresqlWarehouse.logs().lineSequence().filter { it.contains(sql) }.toList()
        assertEquals(1, queryLogLines.count { it.contains("execute ") && !it.contains("execute fetch from") })
        assertTrue(queryLogLines.count { it.contains("execute fetch from") } >= 2, queryLogLines.joinToString("\n"))
    }

    @Test
    fun `production session cancel stops the warehouse query during a later cursor fetch`() = runBlocking {
        setupFixture()
        val sql = "SELECT g AS cursor_cancelled, CASE WHEN g = 150 THEN pg_sleep(30)::text END AS slept " +
            "FROM generate_series(1, 200) AS g"
        val runDeadline = System.currentTimeMillis() + 30_000
        credential().use { credential ->
            val session = provider(maximumQuerySeconds = 30).openSession(connector(), credential, sink, timing = DriverOperationTiming(ConnectorType.POSTGRESQL, DriverOperation.QUERY, null, null))
            try {
                val request = QueryRequest(1u, Sha256Digest("0".repeat(64)), sql, emptyList(),
                    QueryResultTarget.InlineStream(100, 65536, 65536), false, emptyList())
                session.enter(StartExecutionContext(
                    RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000031")),
                    RunAssignment(WorkspaceId.parse("019f0000-0000-7000-8000-000000000002"), connector(),
                        request.copy(canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(request)), runDeadline),
                ))
                assertTrue(awaitSleepingFixtureBackends(expected = 1), "the second cursor fetch must reach pg_sleep")
                assertEquals(DriverCancellationResult.REQUESTED, session.cancel())
                assertTrue(awaitSleepingFixtureBackends(expected = 0), "cancel must stop the warehouse query, not only the client")
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `production session keeps time zone timestamps before the Gregorian cutover exact`() = runBlocking {
        setupFixture()
        val instants = listOf("0001-01-01T00:00:00Z", "1500-06-01T12:00:00Z", "2026-07-31T01:02:03.123456Z").map(Instant::parse)
        val sql = "SELECT v AS value FROM (VALUES " +
            instants.joinToString(", ") { "('$it'::timestamptz)" } + ") AS t(v)"
        val recordingSink = RecordingInlineSink()
        credential().use { credential ->
            val session = provider().openSession(connector(), credential, recordingSink, timing = DriverOperationTiming(ConnectorType.POSTGRESQL, DriverOperation.QUERY, null, null))
            try {
                val request = QueryRequest(1u, Sha256Digest("0".repeat(64)), sql, emptyList(),
                    QueryResultTarget.InlineStream(100, 65536, 65536), false, emptyList())
                session.enter(StartExecutionContext(
                    RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000041")),
                    RunAssignment(WorkspaceId.parse("019f0000-0000-7000-8000-000000000002"), connector(),
                        request.copy(canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(request)), deadline()),
                ))
                assertEquals(3uL, assertIs<DriverPhysicalResult.Succeeded>(session.awaitPhysicalResult()).rowsProcessed)
            } finally {
                session.close()
            }
        }
        assertEquals(
            instants.map { it.epochSecond * 1_000_000L + it.nano / 1_000 },
            recordingSink.batches.flatMap(::timestampMicros),
        )
    }

    private fun awaitSleepingFixtureBackends(expected: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        EphemeralPostgresqlWarehouse.openAdminConnection().use { observer ->
            while (System.nanoTime() < deadline) {
                observer.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM pg_stat_activity " +
                        "WHERE usename = 'validation_user' AND wait_event = 'PgSleep'").use { rows ->
                        rows.next()
                        if (rows.getInt(1) == expected) return true
                    }
                }
                Thread.sleep(20)
            }
        }
        return false
    }

    private fun timestampMicros(batch: InlineResultBatch): List<Long> {
        val bytes = batch.arrowIpc.copyBytes()
        try {
            RootAllocator().use { allocator ->
                ArrowFileReader(SeekableReadChannel(ByteArrayReadableSeekableByteChannel(bytes)), allocator).use { reader ->
                    return reader.recordBlocks.flatMap { block ->
                        reader.loadRecordBatch(block)
                        val vector = reader.vectorSchemaRoot.getVector("value") as TimeStampMicroTZVector
                        (0 until vector.valueCount).map { index -> vector.get(index) }
                    }
                }
            }
        } finally {
            bytes.fill(0)
        }
    }

    @Test
    fun `describe accepts native SQL and typed parameters without executing functions`() {
        fixtureConnection().use { connection ->
            val validator = PostgresqlRawSqlValidator(connection)
            validator.validate("SELECT pg_sleep(60)", emptyList())
            validator.validate("SELECT ?::bigint, ';'::text", listOf(QueryParameter.Integer64(7, name = "")))
            validator.validate("WITH RECURSIVE n AS (SELECT 1 x UNION ALL SELECT x+1 FROM n WHERE x<3) SELECT * FROM n", emptyList())
            validator.validate("WITH n AS (SELECT 1 AS x) SELECT row_number() OVER (ORDER BY x), x AS \"quoted;name\" FROM n", emptyList())
            validator.validate("/* leading */ SELECT ?::text, ?::bigint, ?::float8, ?::boolean; /* trailing ? */",
                listOf(QueryParameter.Text("value", name = ""), QueryParameter.Integer64(7, name = ""), QueryParameter.Float64(1.5, name = ""),
                    QueryParameter.BooleanValue(true, name = "")))
            assertFailsWith<IllegalArgumentException> {
                validator.validate("SELECT ?::int; -- ignored ?", emptyList())
            }
            validator.validate("SELECT trap()", emptyList())
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM fixture").use { result ->
                    assertTrue(result.next())
                    assertEquals(1, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun `JDBC metadata alone accepts multiple statements and returning mutations`() {
        fixtureConnection().use { connection ->
            connection.prepareStatement("SELECT 1; SELECT 2").use { assertTrue(it.metaData.columnCount > 0) }
            connection.prepareStatement("DELETE FROM fixture RETURNING value").use { assertTrue(it.metaData.columnCount > 0) }
        }
    }

    private fun setupFixture() {
        EphemeralPostgresqlWarehouse.openAdminConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DO $$ BEGIN CREATE ROLE validation_user LOGIN PASSWORD 'ephemeral_test_only'; EXCEPTION WHEN duplicate_object THEN NULL; END $$")
                statement.execute("CREATE SCHEMA IF NOT EXISTS validation_fixture")
                statement.execute("CREATE TABLE IF NOT EXISTS validation_fixture.fixture(value int)")
                statement.execute("INSERT INTO validation_fixture.fixture SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM validation_fixture.fixture)")
                statement.execute("""CREATE OR REPLACE FUNCTION validation_fixture.trap() RETURNS int LANGUAGE plpgsql AS
                    ${'$'}body${'$'} BEGIN INSERT INTO validation_fixture.fixture VALUES (2); RETURN 2; END ${'$'}body${'$'}""")
                statement.execute("""CREATE OR REPLACE PROCEDURE validation_fixture.write_with_output(OUT value int) LANGUAGE plpgsql AS
                    ${'$'}body${'$'} BEGIN INSERT INTO validation_fixture.fixture VALUES (3); value := 3; END ${'$'}body${'$'}""")
                statement.execute("GRANT USAGE ON SCHEMA validation_fixture TO validation_user")
                statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON validation_fixture.fixture TO validation_user")
            }
        }
    }

    private fun fixtureConnection(): Connection {
        setupFixture()
        return DriverManager.getConnection(EphemeralPostgresqlWarehouse.jdbcUrl, "validation_user", "ephemeral_test_only").also {
            PostgresqlDialect(Path.of("unused-ca.pem"), 1_024).prepareConnection(it, connector(),
                JdbcRunTimeoutBudget(5_000, 2_000, 5_000, 2_000, 1_000))
        }
    }

    private fun connector() = ConnectorRuntimeBinding(
        1u, ConnectorId.parse("019f0000-0000-7000-8000-000000000003"), ConnectorType.POSTGRESQL,
        mapOf("host" to ConnectorPropertyValue.Text("localhost"), "database" to ConnectorPropertyValue.Text("fixture"),
            "schema" to ConnectorPropertyValue.Text("validation_fixture")),
    )

    private fun credential() = MaterializedCredential(mapOf(
        "username" to SecretCharacters.copyOf("validation_user".toCharArray()),
        "password" to SecretCharacters.copyOf("ephemeral_test_only".toCharArray()),
    ))

    private fun provider() = provider(maximumQuerySeconds = 5)

    private fun provider(maximumQuerySeconds: Int) = PostgresqlExecutionDriverProvider(
        maximumQuerySeconds, 100, Path.of("unused.pem"), JdbcRunTimeoutPolicy(2_000, 5_000, 1_000), Dispatchers.IO, Clock.systemUTC(),
        JdbcConnectionFactory { _, properties -> DriverManager.getConnection(EphemeralPostgresqlWarehouse.jdbcUrl, properties) },
    )

    private fun deadline() = System.currentTimeMillis() + 5_000

    private val sink = object : RunResultSink {
        override suspend fun emitInlineResult(batch: InlineResultBatch) = Unit
        override suspend fun openObjectUpload(objectKey: String, mediaType: String): ArtifactUploadSink = error("unused")
    }

    private class RecordingInlineSink : RunResultSink {
        val batches = mutableListOf<InlineResultBatch>()
        override suspend fun emitInlineResult(batch: InlineResultBatch) { batches += batch }
        override suspend fun openObjectUpload(objectKey: String, mediaType: String): ArtifactUploadSink = error("unused")
    }

    companion object {
        @JvmField
        @RegisterExtension
        val ephemeralWarehouse = EphemeralPostgresqlWarehouse
    }
}
