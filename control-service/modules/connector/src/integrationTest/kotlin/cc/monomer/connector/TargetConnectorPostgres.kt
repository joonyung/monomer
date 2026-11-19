package cc.monomer.connector

import java.net.URI
import java.sql.Connection
import javax.sql.DataSource
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

internal object TargetConnectorPostgres : BeforeAllCallback, AfterAllCallback {
    private const val POSTGRES_IMAGE =
        "postgres:16.14-alpine3.24@" +
            "sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777"
    private const val DATABASE_NAME = "connector_authority_gate_ephemeral"
    private const val DATABASE_USERNAME = "connector_authority_gate"
    private const val DATABASE_PASSWORD = "connector_authority_gate_test_only"

    private val postgres =
        PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"),
        )
            .withDatabaseName(DATABASE_NAME)
            .withUsername(DATABASE_USERNAME)
            .withPassword(DATABASE_PASSWORD)
    private var activeDataSource: DataSource? = null

    val dataSource: DataSource
        get() = checkNotNull(activeDataSource) {
            "Disposable Connector PostgreSQL is not running"
        }

    override fun beforeAll(context: ExtensionContext) {
        check(activeDataSource == null)
        try {
            postgres.start()
            activeDataSource = newDataSource()
            verifyEphemeralIdentity()
            dataSource.connection.use { connection ->
                verifyCanonicalSchemasAbsent(connection)
                applyCanonicalSchemaTwice(connection)
            }
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

    private fun newDataSource(): DataSource =
        PGSimpleDataSource().apply {
            check(postgres.isRunning)
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }

    private fun applyCanonicalSchemaTwice(connection: Connection) {
        val schema = checkNotNull(javaClass.classLoader.getResource("db/connector/schema.sql")).readText()
        connection.createStatement().use { statement ->
            repeat(2) { statement.execute(schema) }
        }
    }

    private fun verifyEphemeralIdentity() {
        check(postgres.isRunning)
        check(postgres.containerId.isNotBlank())
        check(postgres.dockerImageName == POSTGRES_IMAGE)
        check(postgres.databaseName == DATABASE_NAME)
        val containerJdbcUri = URI(postgres.jdbcUrl.removePrefix("jdbc:").substringBefore("?"))
        check(containerJdbcUri.host == postgres.host)
        check(containerJdbcUri.port == postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT))
        check(containerJdbcUri.path == "/$DATABASE_NAME")
        dataSource.connection.use { connection ->
            val connectedJdbcUri = URI(connection.metaData.url.removePrefix("jdbc:").substringBefore("?"))
            check(
                connectedJdbcUri.host == postgres.host &&
                    connectedJdbcUri.port == postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) &&
                    connectedJdbcUri.path == "/$DATABASE_NAME",
            )
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT current_database(), current_user, inet_server_port()").use { resultSet ->
                    check(resultSet.next())
                    check(resultSet.getString(1) == DATABASE_NAME)
                    check(resultSet.getString(1) != "monomer")
                    check(resultSet.getString(2) == DATABASE_USERNAME)
                    check(resultSet.getInt(3) == PostgreSQLContainer.POSTGRESQL_PORT)
                }
            }
        }
    }

    private fun verifyCanonicalSchemasAbsent(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT COUNT(*)
                FROM information_schema.schemata
                WHERE schema_name IN ('connector', 'credential_vault')
                """.trimIndent(),
            ).use { resultSet ->
                check(resultSet.next())
                check(resultSet.getInt(1) == 0) {
                    "Disposable PostgreSQL must start without Connector-owned schemas"
                }
            }
        }
    }
}
