package cc.monomer.execution.driver.duckdb

import cc.monomer.execution.driver.spi.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import kotlinx.coroutines.*
import org.duckdb.DuckDBConnection
import org.duckdb.MonomerDuckDbInterrupt
import kotlin.test.*

internal class DuckDbFixture : AutoCloseable {
    val root: Path = Files.createTempDirectory("sample-dataset")
    val database: Path = root.resolve("acme-cloud/1/acme_cloud.duckdb")
    val versionTwoDatabase: Path = root.resolve("acme-cloud/2/acme_cloud.duckdb")
    val currentDatabase: Path = root.resolve("acme-cloud/3/acme_cloud.duckdb")
    init {
        listOf(database to 100, versionTwoDatabase to 200, currentDatabase to 300).forEach { (path, count) ->
            Files.createDirectories(path.parent)
            DriverManager.getConnection("jdbc:duckdb:$path").use { connection ->
                connection.createStatement().use { it.execute("CREATE TABLE fixture AS SELECT i AS value FROM range($count) t(i)") }
            }
        }
    }
    fun provider() = DuckDbExecutionDriverProvider(16, root, Dispatchers.IO, Clock.systemUTC())
    override fun close() { root.toFile().deleteRecursively() }
}

internal val sampleConnector = ConnectorRuntimeBinding(1u, ConnectorId.parse("019f0000-0000-7000-8000-000000000003"), ConnectorType.DUCKDB, mapOf("sample_dataset" to ConnectorPropertyValue.Text("acme-cloud@1")))
internal fun sampleCredential() = MaterializedCredential(mapOf("dataset" to SecretCharacters.copyOf("acme-cloud@1".toCharArray())))
internal fun timing(operation: DriverOperation) = DriverOperationTiming(ConnectorType.DUCKDB, operation, "019f0000-0000-7000-8000-000000000001", "019f0000-0000-7000-8000-000000000002")

class DuckDbDatabaseLocatorTest {
    @Test fun `all supported datasets must open at startup and traversal cannot replace the sample locator`() {
        val emptyRoot = Files.createTempDirectory("missing-sample")
        try { assertFailsWith<IllegalStateException> { DuckDbSampleDatasets(emptyRoot) } } finally { Files.delete(emptyRoot) }
        DuckDbFixture().use { fixture ->
            Files.delete(fixture.currentDatabase)
            assertFailsWith<IllegalStateException> { DuckDbSampleDatasets(fixture.root) }
        }
        DuckDbFixture().use { fixture ->
            val outsideRoot = Files.createTempDirectory("outside-sample")
            try {
                Files.createDirectories(outsideRoot.resolve("acme-cloud/1"))
                Files.createSymbolicLink(outsideRoot.resolve("acme-cloud/1/acme_cloud.duckdb"), fixture.database)
                assertFailsWith<IllegalStateException> { DuckDbSampleDatasets(outsideRoot) }
            } finally { outsideRoot.toFile().deleteRecursively() }
            fixture.provider().use { provider ->
                val invalidProperties = listOf(emptyMap(), mapOf("sample_dataset" to ConnectorPropertyValue.Text("../other")), sampleConnector.properties + ("database" to ConnectorPropertyValue.Text(fixture.database.toString())))
                invalidProperties.forEach { properties ->
                    sampleCredential().use { credential ->
                        assertFailsWith<IllegalArgumentException> { provider.openSession(sampleConnector.copy(properties = properties), credential, discardSink, timing(DriverOperation.QUERY)) }
                    }
                }
                assertFailsWith<IllegalArgumentException> { provider.openSession(sampleConnector, null, discardSink, timing(DriverOperation.QUERY)) }
                MaterializedCredential(mapOf("dataset" to SecretCharacters.copyOf("other@1".toCharArray()))).use { credential ->
                    assertFailsWith<IllegalArgumentException> { provider.openSession(sampleConnector, credential, discardSink, timing(DriverOperation.QUERY)) }
                }
                val currentConnector = sampleConnector.copy(properties = mapOf("sample_dataset" to ConnectorPropertyValue.Text("acme-cloud@2")))
                sampleCredential().use { credential ->
                    assertFailsWith<IllegalArgumentException> { provider.openSession(currentConnector, credential, discardSink, timing(DriverOperation.QUERY)) }
                }
            }
        }
    }

    @Test fun `dataset versions keep independent read only instances and concurrent results`() = runBlocking {
        DuckDbFixture().use { fixture ->
            repeat(2) {
                DuckDbSampleDatasets(fixture.root).use { datasets ->
                    val results = listOf("acme-cloud@1", "acme-cloud@2", "acme-cloud@3").map { dataset ->
                        async(Dispatchers.IO) {
                            datasets.duplicate(dataset).use { connection ->
                                assertTrue(connection.isReadOnly)
                                connection.createStatement().use { statement ->
                                    statement.executeQuery("SELECT sum(value) FROM fixture").use { result ->
                                        assertTrue(result.next())
                                        result.getLong(1)
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                    assertEquals(listOf(4950L, 19900L, 44850L), results)
                }
            }
        }
    }

    @Test fun `duplicate connections run concurrently and reopened instance preserves lockdown`() = runBlocking {
        DuckDbFixture().use { fixture ->
            repeat(2) {
                DuckDbSampleDatasets(fixture.root).use { datasets ->
                    datasets.duplicate("acme-cloud@1").use { first ->
                        datasets.duplicate("acme-cloud@1").use { second ->
                            val queries = listOf(first, second).map { connection -> async(Dispatchers.IO) { connection.createStatement().use { statement -> statement.executeQuery("SELECT sum(value) FROM fixture").use { result -> assertTrue(result.next()); result.getLong(1) } } } }
                            assertEquals(listOf(4950L, 4950L), queries.awaitAll())
                            listOf(first, second).forEach { connection ->
                                assertTrue(connection.isReadOnly)
                                connection.createStatement().use { statement ->
                                    statement.executeQuery("SELECT current_setting('threads'), current_setting('temp_directory'), current_setting('enable_external_access'), current_setting('lock_configuration'), current_setting('jdbc_stream_results')").use { result ->
                                        assertTrue(result.next()); assertEquals(1, result.getInt(1)); assertEquals("", result.getString(2)); assertFalse(result.getBoolean(3)); assertTrue(result.getBoolean(4)); assertTrue(result.getBoolean(5))
                                    }
                                    listOf("SET threads=2", "SET memory_limit='1GB'", "SET enable_external_access=true", "SET lock_configuration=false", "CREATE TABLE writable(a INT)", "INSERT INTO fixture VALUES (100)", "ATTACH ':memory:' AS writable", "COPY fixture TO '/private/tmp/monomer-sample-forbidden.csv'", "INSTALL httpfs", "LOAD httpfs", "SELECT * FROM read_text('/etc/passwd')", "SELECT * FROM read_csv('/etc/passwd')", "SELECT * FROM glob('/private/tmp/*')", "SELECT * FROM read_csv('https://example.com/data.csv')").forEach { sql ->
                                        assertFailsWith<SQLException>(sql) { statement.execute(sql) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun `metadata exposes only immutable sample file and fixed WAL names with no other host directories`() {
        DuckDbFixture().use { fixture -> DuckDbSampleDatasets(fixture.root).use { datasets ->
            listOf("acme-cloud@1" to fixture.database, "acme-cloud@2" to fixture.versionTwoDatabase, "acme-cloud@3" to fixture.currentDatabase).forEach { (dataset, database) ->
            datasets.duplicate(dataset).use { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name, value FROM duckdb_settings()").use { result ->
                    val settings = buildMap { while (result.next()) put(result.getString(1), result.getString(2).orEmpty()) }
                    assertEquals("disabled", settings["secret_directory"])
                    assertEquals("disabled", settings["extension_directory"])
                    assertEquals("false", settings["allow_persistent_secrets"])
                    listOf("temp_directory", "home_directory", "file_search_path", "log_query_path").forEach { assertEquals("", settings[it], it) }
                    val allowedPaths = requireNotNull(settings["allowed_paths"]).removePrefix("[").removeSuffix("]").split(", ").toSet()
                    assertEquals(setOf("", ".wal", ".wal.checkpoint", ".wal.recovery").map { database.toRealPath().toString() + it }.toSet(), allowedPaths)
                    settings.filterKeys { it != "allowed_paths" }.forEach { (name, value) ->
                        assertFalse(value.contains(fixture.root.toString()), name)
                        assertFalse(value.contains(System.getProperty("user.home")), name)
                        assertFalse(value.contains(System.getProperty("user.dir")), name)
                    }
                }
                statement.executeQuery("SELECT path FROM duckdb_databases() WHERE NOT internal").use { result -> assertTrue(result.next()); assertEquals(database.toRealPath().toString(), result.getString(1)); assertFalse(result.next()) }
                listOf("duckdb_secrets()", "duckdb_external_file_cache()", "duckdb_temporary_files()").forEach { function -> statement.executeQuery("SELECT * FROM $function").use { assertFalse(it.next()) } }
                val error = assertFailsWith<SQLException> { statement.executeQuery("SELECT * FROM duckdb_extensions()") }
                assertFalse(error.message.orEmpty().contains(System.getProperty("user.home")))
                assertFalse(error.message.orEmpty().contains(fixture.root.toString()))
                assertEquals(setOf("acme_cloud.duckdb"), Files.list(database.parent).use { paths -> paths.map { it.fileName.toString() }.toList().toSet() })
            } }
            }
            assertFalse(Files.exists(fixture.root.resolve("disabled")))
        } }
    }

    @Test fun `native parser admits one SELECT including comments and refuses multi statement before user prepare`() = runBlocking {
        DuckDbFixture().use { fixture -> fixture.provider().use { provider ->
            sampleCredential().use { credential ->
                listOf("SELECT 1; -- end", "/* ; SELECT 2 */ SELECT ';' AS value", "WITH a AS (SELECT 1) SELECT * FROM a", "SELECT 1 UNION ALL SELECT 2").forEach { sql -> provider.validateRawSql(sampleConnector, credential, sql, emptyList(), System.currentTimeMillis() + 30_000, timing(DriverOperation.VALIDATE)) }
                listOf("SELECT 1;SELECT 2", "SELECT 1; /* hide */ SET threads=2", "-- first\nSELECT 1;--hide\nSELECT 2", "CREATE TABLE invalid(a INT); SELECT 1", "DELETE FROM fixture RETURNING *", "SELECT * INTO invalid FROM fixture", "EXPLAIN ANALYZE SELECT 1", "SELECT 1; /* incomplete").forEach { sql ->
                    val failure = assertFailsWith<RawSqlValidationException>(sql) { provider.validateRawSql(sampleConnector, credential, sql, emptyList(), System.currentTimeMillis() + 30_000, timing(DriverOperation.VALIDATE)) }
                    assertEquals("RAW_SQL_VALIDATION_REJECTED", failure.failureCode, sql)
                }
            }
            val result = runQuery(provider, "CREATE TABLE invalid(a INT); SELECT 1", 30_000, discardSink)
            assertEquals("RAW_SQL_VALIDATION_REJECTED", assertIs<DriverPhysicalResult.Failed>(result).failureCode)
        } }
    }
}

internal val discardSink = object : RunResultSink {
    override suspend fun emitInlineResult(batch: InlineResultBatch) = Unit
    override suspend fun openObjectUpload(objectKey: String, mediaType: String): ArtifactUploadSink = error("unused")
}

internal fun context(sql: String, durationMillis: Long, target: QueryResultTarget): StartExecutionContext {
    val unsigned = QueryRequest(1u, Sha256Digest("0".repeat(64)), sql, emptyList(), target, false, emptyList())
    val request = unsigned.copy(canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(unsigned))
    return StartExecutionContext(RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")), RunAssignment(WorkspaceId.parse("019f0000-0000-7000-8000-000000000002"), sampleConnector, request, System.currentTimeMillis() + durationMillis))
}
internal suspend fun runQuery(provider: DuckDbExecutionDriverProvider, sql: String, durationMillis: Long, sink: RunResultSink): DriverPhysicalResult {
    val session = provider.openSession(sampleConnector, sampleCredential(), sink, timing(DriverOperation.QUERY))
    return try { session.enter(context(sql, durationMillis, QueryResultTarget.InlineStream(16, 65_536, 65_536))); withTimeout(durationMillis + 5000) { session.awaitPhysicalResult() } } finally { session.close() }
}
