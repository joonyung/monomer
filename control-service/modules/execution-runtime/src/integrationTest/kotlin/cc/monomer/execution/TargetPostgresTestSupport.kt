package cc.monomer.execution

import java.sql.Connection
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

object TargetPostgresTestSupport : BeforeAllCallback, AfterAllCallback {
    private const val POSTGRES_IMAGE =
        "postgres:16.14-alpine3.24@" +
            "sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777"
    private const val DATABASE_NAME = "monomer_execution_gate_ephemeral"
    private const val DATABASE_USERNAME = "execution_gate"
    private const val DATABASE_PASSWORD = "execution_gate_test_only"

    private val postgres =
        PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE)
                .asCompatibleSubstituteFor("postgres"),
        )
            .withDatabaseName(DATABASE_NAME)
            .withUsername(DATABASE_USERNAME)
            .withPassword(DATABASE_PASSWORD)

    override fun beforeAll(context: ExtensionContext) {
        check(!postgres.isRunning)
        try {
            postgres.start()
            openVerifiedConnection().use { connection ->
                verifyCanonicalSchemasAbsent(connection)
                applyExecutionRuntimeSchema(connection, javaClass.classLoader)
            }
        } catch (failure: Throwable) {
            runCatching { postgres.stop() }
            throw failure
        }
    }

    override fun afterAll(context: ExtensionContext) {
        postgres.stop()
        check(!postgres.isRunning)
    }

    fun openVerifiedConnection(): Connection {
        check(postgres.isRunning) {
            "ephemeral PostgreSQL is not running"
        }
        return java.sql.DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username,
            postgres.password,
        ).also(::verifyDatabaseIdentity)
    }

    fun applyExecutionRuntimeSchema(
        connection: Connection,
        classLoader: ClassLoader,
    ) {
        val schema =
            checkNotNull(
                classLoader.getResource("db/execution-runtime.sql"),
            ).readText()
        connection.createStatement().use { statement ->
            statement.execute(schema)
        }
    }

    private fun verifyDatabaseIdentity(connection: Connection) {
        check(connection.metaData.url == postgres.jdbcUrl)
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT current_database(), current_user, inet_server_port()
                """.trimIndent(),
            ).use { resultSet ->
                check(resultSet.next())
                check(resultSet.getString(1) == DATABASE_NAME)
                check(resultSet.getString(2) == DATABASE_USERNAME)
                check(
                    resultSet.getInt(3) ==
                        PostgreSQLContainer.POSTGRESQL_PORT,
                )
            }
        }
    }

    private fun verifyCanonicalSchemasAbsent(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT COUNT(*)
                FROM information_schema.schemata
                WHERE schema_name IN ('operation', 'execution_run')
                """.trimIndent(),
            ).use { resultSet ->
                check(resultSet.next())
                check(resultSet.getInt(1) == 0) {
                    "ephemeral PostgreSQL must start without Product Control schemas"
                }
            }
        }
    }
}
