package cc.monomer.explore

import java.net.URI
import java.sql.Connection
import javax.sql.DataSource
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

internal object TargetExplorePostgres : BeforeAllCallback, AfterAllCallback {
    private const val postgresImage =
        "postgres:16.14-alpine3.24@" +
            "sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777"
    private const val databaseName = "explore_run_gate_ephemeral"
    private const val databaseUsername = "explore_run_gate"
    private const val databasePassword = "explore_run_gate_test_only"

    private val postgres =
        PostgreSQLContainer(
            DockerImageName.parse(postgresImage).asCompatibleSubstituteFor("postgres"),
        )
            .withDatabaseName(databaseName)
            .withUsername(databaseUsername)
            .withPassword(databasePassword)
    private var activeDataSource: DataSource? = null

    val dataSource: DataSource
        get() = checkNotNull(activeDataSource) { "Disposable Explore PostgreSQL is not running" }

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
        val operationSchema =
            checkNotNull(classLoader.getResource("db/execution-runtime.sql")).readText()
        val exploreSchema = checkNotNull(classLoader.getResource("db/explore/schema.sql")).readText()
        connection.createStatement().use { statement ->
            repeat(2) {
                statement.execute(operationSchema)
                statement.execute(exploreSchema)
            }
        }
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
        check(postgres.dockerImageName == postgresImage)
        check(postgres.databaseName == databaseName)
        val containerUri = URI(postgres.jdbcUrl.removePrefix("jdbc:").substringBefore("?"))
        check(containerUri.host == postgres.host)
        check(containerUri.port == postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT))
        check(containerUri.path == "/$databaseName")
        dataSource.connection.use { connection ->
            val connectedUri = URI(connection.metaData.url.removePrefix("jdbc:").substringBefore("?"))
            check(
                connectedUri.host == postgres.host &&
                    connectedUri.port == postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) &&
                    connectedUri.path == "/$databaseName",
            )
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT current_database(), current_user, inet_server_port()").use { rows ->
                    check(rows.next())
                    check(rows.getString(1) == databaseName)
                    check(rows.getString(1) != "monomer")
                    check(rows.getString(2) == databaseUsername)
                    check(rows.getInt(3) == PostgreSQLContainer.POSTGRESQL_PORT)
                }
            }
        }
    }
}
