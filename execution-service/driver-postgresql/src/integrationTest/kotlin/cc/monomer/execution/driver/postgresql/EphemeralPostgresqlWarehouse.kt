package cc.monomer.execution.driver.postgresql

import java.sql.Connection
import java.net.InetSocketAddress
import java.util.Properties
import cc.monomer.execution.driver.jdbc.WarehouseJdbcConnectionFactory
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
    private const val DATABASE_NAME = "monomer_postgresql_driver_gate_ephemeral"
    private const val DATABASE_USERNAME = "postgresql_driver_gate"
    private const val DATABASE_PASSWORD = "postgresql_driver_gate_test_only"

    private val postgres =
        PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE)
                .asCompatibleSubstituteFor("postgres"),
        )
            .withDatabaseName(DATABASE_NAME)
            .withUsername(DATABASE_USERNAME)
            .withPassword(DATABASE_PASSWORD)
            .withCommand(
                "postgres",
                "-c",
                "log_statement=all",
                "-c",
                "log_parameter_max_length=-1",
            )

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
            openAdminConnection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        """
                        SELECT current_database(), current_user, inet_server_port()
                        """.trimIndent(),
                    ).use { resultSet ->
                        check(resultSet.next())
                        check(resultSet.getString(1) == DATABASE_NAME)
                        check(resultSet.getString(2) == DATABASE_USERNAME)
                        check(resultSet.getInt(3) == PostgreSQLContainer.POSTGRESQL_PORT)
                    }
                }
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

    fun openAdminConnection(): Connection {
        check(postgres.isRunning) {
            "ephemeral PostgreSQL warehouse is not running"
        }
        return DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username,
            postgres.password,
        ).also { connection ->
            connection.autoCommit = true
        }
    }

    fun openGuardedConnection(): Connection = WarehouseJdbcConnectionFactory(
        setOf(InetSocketAddress("127.0.0.1", postgres.firstMappedPort)),
    ).open("jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}", Properties().apply {
        setProperty("user", postgres.username)
        setProperty("password", postgres.password)
    })

    fun logs(): String = postgres.logs
}
