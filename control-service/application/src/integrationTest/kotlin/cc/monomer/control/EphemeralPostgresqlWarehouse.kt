package cc.monomer.control

import java.sql.Connection
import java.sql.DriverManager
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

object EphemeralPostgresqlWarehouse : BeforeAllCallback, AfterAllCallback {
    private const val POSTGRES_IMAGE =
        "postgres:16.14-alpine3.24@" +
            "sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777"
    private const val DATABASE_NAME = "monomer_control_runattempt_ephemeral"
    private const val DATABASE_USERNAME = "control_runattempt_gate"
    private const val DATABASE_PASSWORD = "control_runattempt_test_only"

    private val postgres =
        PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE)
                .asCompatibleSubstituteFor("postgres")
        )
            .withDatabaseName(DATABASE_NAME)
            .withUsername(DATABASE_USERNAME)
            .withPassword(DATABASE_PASSWORD)

    val jdbcUrl: String
        get() {
            check(postgres.isRunning) {
                "ephemeral PostgreSQL warehouse is not running"
            }
            return postgres.jdbcUrl
        }

    override fun beforeAll(context: ExtensionContext) {
        check(!postgres.isRunning)
        try {
            postgres.start()
        } catch (failure: Throwable) {
            runCatching { postgres.stop() }
            throw failure
        }
    }

    override fun afterAll(context: ExtensionContext) {
        postgres.stop()
        check(!postgres.isRunning)
    }

    fun openAdminConnection(): Connection {
        check(postgres.isRunning) {
            "ephemeral PostgreSQL warehouse is not running"
        }
        return DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username,
            postgres.password
        ).also { connection ->
            connection.autoCommit = true
        }
    }
}
