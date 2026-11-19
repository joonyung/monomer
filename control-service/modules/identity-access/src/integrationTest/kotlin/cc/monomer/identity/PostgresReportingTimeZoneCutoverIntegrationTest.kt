package cc.monomer.identity

import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.HexFormat
import kotlin.uuid.Uuid
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.postgresql.ds.PGSimpleDataSource

class PostgresReportingTimeZoneCutoverIntegrationTest {
    private val cutoverScript = File("docs/reporting-time-zone-cutover.sql")
    private val canonicalSchema = pinnedSchema(
        "6ea0f427a196fc185a5a8109a46e7eece084b0df",
        "7db190c9bc7d83787725d0aba700ad219ecb62030b0f43fdaba10a91a566e6aa",
    )
    private val sourceSchema = pinnedSchema(
        "063d3b4c25854847c213cfe416844306032ee46d",
        "ca4bc1f88fff84efd17388a5e5f4d1d89099cbe6f5e236c80a7a49d326d8d66e",
    )

    @Test
    fun `cutover moves the source schema to the canonical contract and keeps every Workspace`() {
        val monomer = monomerDatabase()
        monomer.connection.use { connection -> connection.createStatement().use { it.execute(sourceSchema) } }
        val organization = Uuid.generateV7()
        val user = Uuid.generateV7()
        val analytics = Uuid.generateV7()
        val finance = Uuid.generateV7()
        val sample = Uuid.generateV7()
        monomer.connection.use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.execute("INSERT INTO identity_runtime.principals(id,kind,display_name) VALUES('$user','user','Sam Kim')")
                statement.execute("INSERT INTO identity_runtime.users(id,primary_email) VALUES('$user','sam@example.test')")
                statement.execute("INSERT INTO identity_runtime.organizations(id,display_name) VALUES('$organization','Cutover')")
                statement.execute("INSERT INTO identity_runtime.organization_members(organization_id,user_id,role) VALUES('$organization','$user','ADMIN')")
                statement.execute(
                    "INSERT INTO identity_runtime.workspaces(id,organization_id,display_name,authorization_revision,public_pages) " +
                        "VALUES('$analytics','$organization','Analytics',4,true),('$finance','$organization','Finance',0,false)",
                )
                statement.execute(
                    "INSERT INTO identity_runtime.workspaces(id,organization_id,display_name,sample_dataset,sample_state,sample_initiator) " +
                        "VALUES('$sample','$organization','Acme Cloud (sample)','acme-cloud@1','READY','$user')",
                )
                statement.execute(
                    "INSERT INTO identity_runtime.workspace_members(workspace_id,user_id,role) " +
                        "VALUES('$analytics','$user','OWNER'),('$finance','$user','OWNER'),('$sample','$user','OWNER')",
                )
            }
            connection.commit()
        }
        val workspacesBefore = monomer.connection.use { workspaceRows(it) }

        val remoteCutover = cutoverScript.readLines().filterNot { it.startsWith("\\set ") }.joinToString("\n")
        assertThatThrownBy { monomer.connection.use { run(it, remoteCutover) } }
            .isInstanceOf(SQLException::class.java).hasMessageContaining("TLS is required for remote connections")
        assertRefused(TargetIdentityPostgres.runPsqlFile(cutoverScript, GATE_DATABASE, GATE_USER), "unexpected database or role")
        monomer.connection.use { idle ->
            assertThat(idle.isValid(1)).isTrue()
            assertRefused(psqlCutover(), "stop application connections before cutover")
        }
        monomer.connection.use { connection ->
            assertThat(workspaceRows(connection)).isEqualTo(workspacesBefore)
            assertThat(contractRevision(connection)).isEqualTo(SOURCE_REVISION)
        }

        awaitNoMonomerSessions()
        val applied = psqlCutover()

        assertThat(applied.exitCode).describedAs(applied.stderr).isZero()
        assertThat(applied.stdout.lines().last { it.isNotBlank() }).isEqualTo(TARGET_REVISION)
        monomer.connection.use { connection ->
            verifyHistoricalTarget(connection)
            assertThat(workspaceRows(connection)).isEqualTo(workspacesBefore)
            assertThat(reportingTimeZones(connection)).containsExactlyInAnyOrder("$analytics" to "UTC", "$finance" to "UTC", "$sample" to "UTC")
        }
        awaitNoMonomerSessions()
        assertRefused(psqlCutover(), "reporting time zone is already installed")
        monomer.connection.use { verifyHistoricalTarget(it) }
    }

    private fun psqlCutover() = TargetIdentityPostgres.runPsqlFile(cutoverScript, "monomer", "monomer")

    private fun assertRefused(result: org.testcontainers.containers.Container.ExecResult, reason: String) {
        assertThat(result.exitCode).isNotZero()
        assertThat(result.stderr).contains(reason)
    }

    private fun contractRevision(connection: Connection): String = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT contract_revision FROM identity_runtime.schema_contract").use { rows ->
            rows.next()
            rows.getString(1)
        }
    }

    private fun monomerDatabase(): PGSimpleDataSource {
        val gate = TargetIdentityPostgres.dataSource as PGSimpleDataSource
        gate.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE ROLE monomer LOGIN PASSWORD 'reporting_time_zone_cutover_test_only'")
                statement.execute("CREATE DATABASE monomer OWNER monomer")
            }
        }
        return PGSimpleDataSource().apply {
            serverNames = gate.serverNames
            portNumbers = gate.portNumbers
            databaseName = "monomer"
            user = "monomer"
            password = "reporting_time_zone_cutover_test_only"
        }
    }

    private fun awaitNoMonomerSessions() {
        val deadline = System.nanoTime() + 10_000_000_000
        TargetIdentityPostgres.dataSource.connection.use { gate ->
            while (true) {
                val sessions = gate.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname = 'monomer' AND usename = 'monomer' AND backend_type = 'client backend'",
                    ).use { rows -> rows.next(); rows.getInt(1) }
                }
                if (sessions == 0) return
                check(System.nanoTime() < deadline) { "closed monomer sessions did not end" }
                Thread.sleep(50)
            }
        }
    }

    private fun run(connection: Connection, sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    private fun workspaceRows(connection: Connection): List<List<Any?>> = connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT id, organization_id, display_name, authorization_revision, created_at, public_pages, " +
                "sample_dataset, sample_state, sample_initiator FROM identity_runtime.workspaces ORDER BY id",
        ).use { rows ->
            buildList { while (rows.next()) add((1..9).map { column -> rows.getObject(column) }) }
        }
    }

    private fun reportingTimeZones(connection: Connection): List<Pair<String, String>> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT id::TEXT, reporting_time_zone FROM identity_runtime.workspaces").use { rows ->
            buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) }
        }
    }

    private fun pinnedSchema(commit: String, expectedSha256: String): String {
        val process = ProcessBuilder("git", "show", "$commit:control-service/modules/identity-access/src/main/resources/db/identity-runtime/schema.sql")
            .directory(File("../../.."))
            .redirectErrorStream(true)
            .start()
        val bytes = process.inputStream.readBytes()
        check(process.waitFor() == 0) { "pinned historical Identity schema is unavailable" }
        check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) == expectedSha256) {
            "pinned historical Identity schema digest changed"
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun verifyHistoricalTarget(connection: Connection) {
        run(connection, canonicalSchema)
        assertThat(contractRevision(connection)).isEqualTo(TARGET_REVISION)
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT catalog_fingerprint, identity_runtime.compute_schema_catalog_fingerprint() FROM identity_runtime.schema_contract").use { rows ->
                assertThat(rows.next()).isTrue()
                assertThat(rows.getString(1)).isEqualTo(TARGET_FINGERPRINT)
                assertThat(rows.getString(2)).isEqualTo(TARGET_FINGERPRINT)
                assertThat(rows.next()).isFalse()
            }
        }
    }

    companion object {
        private const val SOURCE_REVISION = "2026-10-12.sample-workspace.v1"
        private const val TARGET_REVISION = "2026-10-13.reporting-time-zone.v1"
        private const val TARGET_FINGERPRINT = "dd533f296b14a0f8a0f82cabf47f6d3d"
        private const val GATE_DATABASE = "identity_runtime_gate_ephemeral"
        private const val GATE_USER = "identity_runtime_gate"

        @JvmField
        @RegisterExtension
        internal val postgres = TargetIdentityPostgres
    }
}
