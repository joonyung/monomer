package cc.monomer.identity

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.Container
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.io.File
import java.net.URI
import java.sql.Connection
import javax.sql.DataSource

internal object TargetIdentityPostgres : BeforeAllCallback, AfterAllCallback {
    private const val POSTGRES_IMAGE =
        "postgres:17.10-alpine3.24@" +
            "sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193"
    private const val DATABASE_NAME = "identity_runtime_gate_ephemeral"
    private const val DATABASE_USERNAME = "identity_runtime_gate"
    private const val DATABASE_PASSWORD = "identity_runtime_gate_test_only"

    private val postgres =
        PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE)
                .asCompatibleSubstituteFor("postgres"),
        )
            .withDatabaseName(DATABASE_NAME)
            .withUsername(DATABASE_USERNAME)
            .withPassword(DATABASE_PASSWORD)
    private var activeDataSource: DataSource? = null

    val dataSource: DataSource
        get() = checkNotNull(activeDataSource) {
            "Disposable identity PostgreSQL is not running"
        }

    override fun beforeAll(context: ExtensionContext) {
        check(activeDataSource == null)
        try {
            postgres.start()
            activeDataSource = newDataSource()
            verifyEphemeralIdentity()
        } catch (failure: Throwable) {
            activeDataSource = null
            runCatching { postgres.stop() }
            throw failure
        }
    }

    override fun afterAll(context: ExtensionContext) {
        try {
            postgres.stop()
            check(!postgres.isRunning)
        } finally {
            activeDataSource = null
        }
    }

    fun applyCanonicalSchemaTwice(connection: Connection, classLoader: ClassLoader) {
        val schema = checkNotNull(classLoader.getResource("db/identity-runtime/schema.sql")).readText()
        connection.createStatement().use { statement ->
            repeat(2) {
                statement.execute(schema)
            }
        }
    }

    // psql inside the container connects over the local socket, as an operator's cutover session does.
    fun runPsqlFile(script: File, database: String, user: String): Container.ExecResult {
        check(postgres.isRunning)
        val containerPath = "/tmp/${script.name}"
        postgres.copyFileToContainer(MountableFile.forHostPath(script.toPath()), containerPath)
        return postgres.execInContainer("psql", "-X", "-q", "-At", "-U", user, "-d", database, "-f", containerPath)
    }

    private fun newDataSource(): DataSource =
        PGSimpleDataSource().apply {
            check(postgres.isRunning)
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }

    private fun verifyEphemeralIdentity() {
        check(postgres.isRunning)
        check(postgres.containerId.isNotBlank())
        check(postgres.dockerImageName == POSTGRES_IMAGE)
        check(postgres.databaseName == DATABASE_NAME)
        val containerJdbcUri =
            URI(postgres.jdbcUrl.removePrefix("jdbc:").substringBefore("?"))
        check(containerJdbcUri.host == postgres.host)
        check(
            containerJdbcUri.port ==
                postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
        )
        check(containerJdbcUri.path == "/$DATABASE_NAME")
        dataSource.connection.use { connection ->
            val connectedJdbcUri =
                URI(connection.metaData.url.removePrefix("jdbc:").substringBefore("?"))
            check(
                connectedJdbcUri.host == postgres.host &&
                    connectedJdbcUri.port ==
                    postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) &&
                    connectedJdbcUri.path == "/$DATABASE_NAME",
            )
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT current_database(), current_user, inet_server_port()",
                ).use { resultSet ->
                    check(resultSet.next())
                    check(resultSet.getString(1) == DATABASE_NAME)
                    check(resultSet.getString(1) != "monomer")
                    check(resultSet.getString(2) == DATABASE_USERNAME)
                    check(resultSet.getInt(3) == PostgreSQLContainer.POSTGRESQL_PORT)
                }
            }
        }
    }
}
