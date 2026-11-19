package cc.monomer.control

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IntegrationGateSafetyContractTest {
    private val controlRoot = Path.of(System.getProperty("control.service.root"))

    @Test
    fun `default test sources do not start containers`() {
        val forbiddenImport = "import org." + "testcontainers"
        val defaultTestSources =
            Files.walk(controlRoot).use { paths ->
                paths
                    .filter { path ->
                        Files.isRegularFile(path) &&
                            path.extension == "kt" &&
                            path.toString().contains("/src/test/")
                    }
                    .toList()
            }

        defaultTestSources.forEach { source ->
            assertThat(Files.readString(source))
                .describedAs("Default test source must not use Testcontainers: $source")
                .doesNotContain(forbiddenImport)
        }
    }

    @Test
    fun `integration gates contain no destructive schema or unbounded row cleanup`() {
        val integrationSources =
            Files.walk(controlRoot.resolve("application/src/integrationTest")).use { paths ->
                paths
                    .filter { path -> Files.isRegularFile(path) && path.extension == "kt" }
                    .toList()
            }
        val destructiveSchemaSql = Regex("""(?i)\b(drop\s+(schema|table)|truncate)\b""")
        val unboundedDeleteSql =
            Regex(
                "(?is)\\bdelete\\s+from\\s+[a-z0-9_.\"]+\\s*" +
                    "(?:;|\"\"\"|\"\\s*\\)|\\z)",
            )

        integrationSources.forEach { source ->
            val text = Files.readString(source)
            assertThat(destructiveSchemaSql.containsMatchIn(text))
                .describedAs("Integration gate must not destroy schemas or tables: $source")
                .isFalse()
            assertThat(unboundedDeleteSql.containsMatchIn(text))
                .describedAs("Integration gate must not delete an unbounded shared table: $source")
                .isFalse()
        }
    }

    @Test
    fun `postgres authority gate is fixed-version disposable and ignores external databases`() {
        val support =
            Files.readString(
                controlRoot.resolve(
                    "application/src/integrationTest/kotlin/cc/monomer/control/" +
                        "EphemeralPostgresAuthoritySupport.kt",
                ),
            )

        assertThat(support)
            .contains("org.testcontainers.postgresql.PostgreSQLContainer")
            .contains("postgres:16.14-alpine3.24@")
            .contains(
                "sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777",
            )
            .contains("monomer_authority_gate_ephemeral")
            .contains("postgres.stop()")
            .doesNotContain(
                "System.getenv",
                "target-environment-descriptor",
                "MONOMER_TARGET_POSTGRES_PASSWORD",
            )
        assertThat(
            Files.readString(controlRoot.resolve("application/build.gradle.kts")),
        ).contains("org.testcontainers:testcontainers-postgresql:2.0.5")
    }

    @Test
    fun `postgres integration fixtures retain database-aware readiness`() {
        val integrationSources =
            Files.walk(controlRoot).use { paths ->
                paths
                    .filter { path ->
                        Files.isRegularFile(path) &&
                            path.extension == "kt" &&
                            path.toString().contains("/src/integrationTest/")
                    }
                    .toList()
            }

        integrationSources.forEach { source ->
            val text = Files.readString(source)
            if (text.contains("PostgreSQLContainer")) {
                assertThat(text)
                    .describedAs(
                        "PostgreSQL fixtures must not replace database readiness with a port-only wait: $source",
                    )
                    .doesNotContain("Wait.forListeningPort()")
            }
        }
    }
}
