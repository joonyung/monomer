package cc.monomer.semantic.adapter.outbound.postgres

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.images.builder.Transferable
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SemanticDefinitionCutoverPostgresIntegrationTest {
    private val postgres = PostgreSQLContainer(DockerImageName.parse(
        "postgres:17.10-bookworm@sha256:9b18b78397054fce88a9552e9d5a3ad5bb7fd258c5b3cc1c5028e46373d6ea8f",
    ).asCompatibleSubstituteFor("postgres")).withDatabaseName("monomer").withUsername("monomer").withPassword("cutover_disposable_test_only")
    private val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("infra/cloud-dev/cutovers/semantic-definition-changes.sql")) }
    private val cutover = Files.readString(root.resolve("infra/cloud-dev/cutovers/semantic-definition-changes.sql"))
    private val preflight = Files.readString(root.resolve("infra/cloud-dev/cutovers/semantic-definition-changes-preflight.sql"))
    private val ddl = checkNotNull(javaClass.classLoader.getResource("db/semantic/schema.sql")).readText()
    private val workspace = "019b0000-0000-7000-8000-000000000001"
    private val actor = "019b0000-0000-7000-8000-000000000002"

    @BeforeAll
    fun startDisposable() { postgres.start(); check(postgres.isRunning) }

    @AfterAll
    fun stopDisposable() { postgres.stop(); check(!postgres.isRunning) }

    @BeforeEach
    fun seedLegacyRows() {
        sql("DROP SCHEMA IF EXISTS semantic CASCADE;\n" + ddl.substringBefore("CREATE TABLE IF NOT EXISTS semantic.definition_changes"))
        for ((table, idColumn, name, suffix, created, surface, principal, app) in listOf(
            Seed("semantic_models", "semantic_model_id", "orders", "101", true, "WEB", "'$actor'", "NULL"),
            Seed("semantic_models", "semantic_model_id", "returns", "102", false, "AGENT", "'$actor'", "'fixture-agent'"),
            Seed("metrics", "metric_id", "revenue", "201", true, null, "NULL", "NULL"),
            Seed("metrics", "metric_id", "margin", "202", false, "AGENT", "'$actor'", "NULL"),
        )) {
            val id = "019b0000-0000-7000-8000-${suffix.padStart(12, '0')}"
            val updated = if (created) "2026-10-01T00:00:00Z" else "2026-10-02T03:04:05.123456Z"
            sql("""
                INSERT INTO semantic.$table ($idColumn,workspace_id,definition,created_at,updated_at,actor_id,credential_surface,agent_client_id)
                VALUES ('$id','$workspace','{"schemaVersion":3,"name":"$name","label":"$name label","description":"Keep all text","nested":{"expression":"x + 1"}}',
                    '2026-10-01T00:00:00Z','$updated',$principal,${surface?.let { "'$it'" } ?: "NULL"},$app);
            """.trimIndent())
        }
        sql("""
            INSERT INTO semantic.time_spine_configurations VALUES ('$workspace','019b0000-0000-7000-8000-000000000003','{"preserved":true}');
            INSERT INTO semantic.mutation_receipts VALUES ('$workspace','preserved-key','$actor','METRIC_CREATE','METRIC:revenue',
                '019b0000-0000-7000-8000-000000000201','2026-10-01T00:00:00Z');
        """.trimIndent())
    }

    @Test
    fun `preflight is read only before and after cutover`() {
        val before = snapshot()
        sql(preflight)
        assertEquals(before, snapshot())
        sql(cutover)
        val after = snapshot()
        sql(preflight)
        assertEquals(after, snapshot())
    }

    @Test
    fun `backfill preserves every current row and attribution with one full snapshot per definition`() {
        val before = snapshot()
        sql(cutover)
        assertEquals(before.substringBefore("CHANGES="), snapshot().substringBefore("CHANGES="))
        assertEquals("METRIC|CREATED|1\nMETRIC|UPDATED|1\nSEMANTIC_MODEL|CREATED|1\nSEMANTIC_MODEL|UPDATED|1", sql("""
            SELECT definition_kind,action,count(*) FROM semantic.definition_changes GROUP BY definition_kind,action ORDER BY definition_kind,action;
        """.trimIndent()).trim())
        assertEquals("4", sql("SELECT count(*) FROM semantic.definition_changes WHERE substr(change_id::text,15,1)='7';").trim())
        assertEquals("1", sql("SELECT count(*) FROM semantic.definition_changes WHERE actor_id IS NULL AND credential_surface IS NULL;").trim())
        assertEquals("1", sql("SELECT count(*) FROM semantic.definition_changes WHERE credential_surface='AGENT' AND agent_client_id='fixture-agent';").trim())
        assertEquals("4", sql("SELECT count(*) FROM semantic.definition_changes WHERE definition#>>'{nested,expression}'='x + 1';").trim())
    }

    @Test
    fun `repeat dev and local application does not append or rewrite records`() {
        sql(cutover)
        val once = snapshot()
        sql(cutover)
        assertEquals(once, snapshot())
    }

    @Test
    fun `malformed current name rejects without partial table or changed rows`() {
        sql("UPDATE semantic.metrics SET definition=definition-'name' WHERE definition->>'name'='margin';")
        val before = snapshot()
        val failed = attempt(cutover)
        assertTrue(failed.exitCode != 0)
        assertEquals(before, snapshot())
        assertEquals("", sql("SELECT to_regclass('semantic.definition_changes');").trim())
    }

    @Test
    fun `wrong database is rejected before writes`() {
        sql("CREATE DATABASE cutover_wrong_database;")
        try {
            val failed = attempt(cutover, "cutover_wrong_database")
            assertTrue(failed.exitCode != 0)
            assertTrue(failed.stderr.contains("unexpected database or role"))
            assertEquals("", sql("SELECT to_regclass('semantic.definition_changes');", "cutover_wrong_database").trim())
        } finally { sql("DROP DATABASE cutover_wrong_database;") }
    }

    @Test
    fun `open application connection prevents cutover`() {
        val source = PGSimpleDataSource().apply { setURL(postgres.jdbcUrl); user=postgres.username; password=postgres.password }
        source.connection.use {
            val before = snapshot()
            val failed = attempt(cutover)
            assertTrue(failed.exitCode != 0)
            assertTrue(failed.stderr.contains("stop application connections"))
            assertEquals(before, snapshot())
        }
    }

    @Test
    fun `failure after backfill rolls back records table and original data`() {
        val before = snapshot()
        val failed = attempt(cutover.replace("DO \$verify\$", "DO \$fail\$ BEGIN RAISE EXCEPTION 'injected verification failure'; END \$fail\$;\nDO \$verify\$"))
        assertTrue(failed.exitCode != 0)
        assertTrue(failed.stderr.contains("injected verification failure"))
        assertEquals(before, snapshot())
    }

    @Test
    fun `existing inconsistent latest record is rejected and preserved`() {
        sql(cutover)
        sql("UPDATE semantic.metrics SET definition=jsonb_set(definition,'{label}','\"Unrecorded change\"') WHERE definition->>'name'='margin';")
        val before = snapshot()
        val failed = attempt(cutover)
        assertTrue(failed.exitCode != 0)
        assertTrue(failed.stderr.contains("does not exactly represent current definition"))
        assertEquals(before, snapshot())
    }

    @Test
    fun `installed table constraints and indexes match canonical module DDL`() {
        sql(cutover)
        val installed = changeSchema()
        sql("DROP TABLE semantic.definition_changes;\n" + ddl)
        assertEquals(installed, changeSchema())
        assertFalse(installed.isBlank())
    }

    private fun changeSchema() = sql("""
        SELECT column_name,data_type,is_nullable FROM information_schema.columns WHERE table_schema='semantic' AND table_name='definition_changes' ORDER BY ordinal_position;
        SELECT conname,pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='semantic.definition_changes'::regclass ORDER BY conname;
        SELECT indexname,indexdef FROM pg_indexes WHERE schemaname='semantic' AND tablename='definition_changes' ORDER BY indexname;
    """.trimIndent())

    private fun snapshot(): String = buildString {
        for (table in listOf("semantic_models", "metrics", "time_spine_configurations", "mutation_receipts")) {
            append(table).append('=').append(sql("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]') FROM semantic.$table t;")).append('\n')
        }
        append("CHANGES=")
        if (sql("SELECT to_regclass('semantic.definition_changes');").trim().isNotBlank()) {
            append(sql("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY change_id),'[]') FROM semantic.definition_changes t;"))
        }
    }

    private fun attempt(statement: String, database: String = "monomer"): org.testcontainers.containers.Container.ExecResult {
        check(postgres.isRunning && postgres.databaseName == "monomer")
        val file = "/tmp/semantic-cutover-${UUID.randomUUID()}.sql"
        postgres.copyFileToContainer(Transferable.of(statement.toByteArray(Charsets.UTF_8)), file)
        return postgres.execInContainer("psql", "-X", "-U", "monomer", "-d", database, "-v", "ON_ERROR_STOP=1", "-At", "-f", file)
    }

    private fun sql(statement: String, database: String = "monomer"): String {
        val result = attempt(statement, database)
        check(result.exitCode == 0) { result.stderr }
        return result.stdout.lines().filterNot { it in setOf("BEGIN", "COMMIT", "DO", "CREATE TABLE", "CREATE INDEX", "CREATE FUNCTION", "LOCK TABLE", "SET") }.joinToString("\n")
    }

    private data class Seed(val table: String, val idColumn: String, val name: String, val suffix: String, val created: Boolean,
        val surface: String?, val principal: String, val app: String)
}
