package cc.monomer.control

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.net.URI
import java.util.UUID
import javax.sql.DataSource
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

object EphemeralPostgresAuthoritySupport : BeforeAllCallback, AfterAllCallback {
    private const val POSTGRES_IMAGE =
        "postgres:16.14-alpine3.24@" +
            "sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777"
    private const val DATABASE_NAME = "monomer_authority_gate_ephemeral"
    private const val DATABASE_USERNAME = "authority_gate"
    private const val DATABASE_PASSWORD = "authority_gate_test_only"

    private val controlRoot = Path.of(System.getProperty("control.service.root"))
    private val schemaOwnerBySchema: Map<String, String> by lazy {
        val moduleOwnership =
            ObjectMapper().readTree(
                controlRoot.resolve("../contracts/architecture/module-ownership.json").toFile(),
            )
        buildMap {
            moduleOwnership.path("modules").forEach { module ->
                val ownerId = module.path("id").asText()
                module.path("schemas").forEach { schema ->
                    val priorOwner = put(schema.asText(), ownerId)
                    check(priorOwner == null) {
                        "Schema ${schema.asText()} has multiple owners"
                    }
                }
            }
        }
    }
    private val postgres =
        PostgreSQLContainer(
            DockerImageName.parse(POSTGRES_IMAGE)
                .asCompatibleSubstituteFor("postgres"),
        )
            .withDatabaseName(DATABASE_NAME)
            .withUsername(DATABASE_USERNAME)
            .withPassword(DATABASE_PASSWORD)
    private var activeDataSource: DataSource? = null
    private var activeConnectorMutationLockDataSource: DataSource? = null
    private var activeTransactionOperations: TransactionOperations? = null

    val dataSource: DataSource
        get() = checkNotNull(activeDataSource) { "Ephemeral PostgreSQL is not running" }
    val connectorMutationLockDataSource: DataSource
        get() =
            checkNotNull(activeConnectorMutationLockDataSource) {
                "Ephemeral PostgreSQL is not running"
            }
    val transactionOperations: TransactionOperations
        get() =
            checkNotNull(activeTransactionOperations) {
                "Ephemeral PostgreSQL is not running"
            }

    override fun beforeAll(context: ExtensionContext) {
        check(activeDataSource == null)
        try {
            postgres.start()
            activeDataSource = newDataSource()
            activeConnectorMutationLockDataSource = newDataSource()
            activeTransactionOperations =
                TransactionTemplate(DataSourceTransactionManager(dataSource)).apply {
                    timeout = 5
                }
        } catch (failure: Throwable) {
            runCatching { postgres.stop() }
            throw failure
        }
    }

    fun newDataSource(): DataSource =
        DriverManagerDataSource().apply {
            check(postgres.isRunning)
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }

    fun verifyEphemeralIdentity() {
        check(postgres.isRunning)
        check(postgres.databaseName == DATABASE_NAME)
        val jdbcUri =
            URI(postgres.jdbcUrl.removePrefix("jdbc:").substringBefore("?"))
        check(jdbcUri.host == postgres.host)
        check(jdbcUri.port == postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT))
        check(jdbcUri.path == "/$DATABASE_NAME")
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
    }

    fun verifyAuthoritySchemasAbsent() {
        val authoritySchemas =
            listOf(
                "execution_operation",
                "execution_attempt",
                "connector",
                "catalog",
                "semantic",
                "discovery",
                "search",
            )
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM information_schema.schemata
                WHERE schema_name = ANY (?)
                """.trimIndent(),
            ).use { statement ->
                statement.setArray(
                    1,
                    connection.createArrayOf("text", authoritySchemas.toTypedArray()),
                )
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    check(resultSet.getInt(1) == 0)
                }
            }
        }
    }

    override fun afterAll(context: ExtensionContext) {
        try {
            postgres.stop()
            check(!postgres.isRunning)
        } finally {
            activeTransactionOperations = null
            activeConnectorMutationLockDataSource = null
            activeDataSource = null
        }
    }

    fun applyAuthoritySchemasTwice() {
        val schemas =
            listOf(
                "modules/identity-access/src/main/resources/db/identity-runtime/schema.sql",
                "modules/execution-runtime/src/main/resources/db/execution-runtime.sql",
                "modules/connector/src/main/resources/db/connector/schema.sql",
                "modules/catalog/src/main/resources/db/catalog/schema.sql",
                "modules/semantic/src/main/resources/db/semantic/schema.sql",
                "modules/explore/src/main/resources/db/explore/schema.sql",
                "modules/dashboard/src/main/resources/db/dashboard/schema.sql",
                "modules/search/src/main/resources/db/search/schema.sql",
            )
        repeat(2) {
            dataSource.connection.use { connection ->
                connection.autoCommit = true
                schemas.forEach { relativePath ->
                    val sql = controlRoot.resolve(relativePath).toFile().readText()
                    connection.createStatement().use { statement -> statement.execute(sql) }
                }
            }
        }
    }

    fun verifyPhaseThreeSchemaContract() {
        val violations = buildList {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT to_regnamespace('authority') IS NULL").use { resultSet ->
                        check(resultSet.next())
                        if (!resultSet.getBoolean(1)) add("authority schema must be absent")
                    }
                }
            }
            if (columnExists("catalog", "current_objects", "current_state")) {
                add("catalog.current_objects.current_state must be absent")
            }

            if (columnExists("connector", "connector_validation_settlement_receipts", "workspace_id")) {
                add("connector.connector_validation_settlement_receipts must be absent")
            }
            requireColumn(
                schema = "connector",
                table = "credential_versions",
                column = "workspace_id",
            )
            requireColumn(
                schema = "connector",
                table = "connectors",
                column = "authority_revision",
            )
            requireColumn(
                schema = "catalog",
                table = "current_objects",
                column = "workspace_id",
            )
            if (columnExists("semantic", "semantic_models", "authority_revision")) {
                add("semantic.semantic_models.authority_revision must be absent")
            }
            if (columnExists("semantic", "metrics", "authority_revision")) {
                add("semantic.metrics.authority_revision must be absent")
            }
        }
        check(violations.isEmpty()) {
            "Ephemeral PostgreSQL authority schema contract drifted: " +
                violations.joinToString()
        }
    }

    fun verifyNoCrossOwnerForeignKeys() {
        val violations = mutableListOf<String>()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT
                    source_namespace.nspname,
                    source_table.relname,
                    constraint_definition.conname,
                    target_namespace.nspname,
                    target_table.relname
                FROM pg_constraint constraint_definition
                JOIN pg_class source_table
                  ON source_table.oid = constraint_definition.conrelid
                JOIN pg_namespace source_namespace
                  ON source_namespace.oid = source_table.relnamespace
                JOIN pg_class target_table
                  ON target_table.oid = constraint_definition.confrelid
                JOIN pg_namespace target_namespace
                  ON target_namespace.oid = target_table.relnamespace
                WHERE constraint_definition.contype = 'f'
                ORDER BY
                    source_namespace.nspname,
                    source_table.relname,
                    constraint_definition.conname
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    while (resultSet.next()) {
                        val sourceSchema = resultSet.getString(1)
                        val targetSchema = resultSet.getString(4)
                        val sourceOwner = schemaOwnerBySchema[sourceSchema]
                        val targetOwner = schemaOwnerBySchema[targetSchema]
                        if (
                            sourceOwner != null &&
                            targetOwner != null &&
                            sourceOwner != targetOwner
                        ) {
                            violations +=
                                "$sourceSchema.${resultSet.getString(2)} " +
                                "constraint ${resultSet.getString(3)} references " +
                                "$targetSchema.${resultSet.getString(5)}"
                        }
                    }
                }
            }
        }
        check(violations.isEmpty()) {
            "Ephemeral PostgreSQL contains cross-owner foreign keys: " +
                violations.joinToString()
        }
    }

    private fun MutableList<String>.requireColumn(
        schema: String,
        table: String,
        column: String,
    ) {
        if (!columnExists(schema, table, column)) {
            add("$schema.$table.$column is missing")
        }
    }

    private fun columnExists(
        schema: String,
        table: String,
        column: String,
    ): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.columns
                    WHERE table_schema = ?
                      AND table_name = ?
                      AND column_name = ?
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, schema)
                statement.setString(2, table)
                statement.setString(3, column)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    resultSet.getBoolean(1)
                }
            }
        }

    fun count(
        table: String,
        column: String,
        id: UUID,
    ): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE $column = ?").use {
                statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    resultSet.getInt(1)
                }
            }
        }

    fun count(
        table: String,
        column: String,
        value: String,
    ): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE $column = ?").use {
                statement ->
                statement.setString(1, value)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next())
                    resultSet.getInt(1)
                }
            }
        }

    private fun Instant.toOffsetDateTime(): OffsetDateTime =
        OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
