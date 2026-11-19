package cc.monomer.execution.driver.trino

import cc.monomer.execution.driver.spi.DriverOperationTiming
import cc.monomer.execution.driver.spi.DriverOperation
import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutBudget
import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutPolicy
import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.ConnectorId
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.QueryParameter
import cc.monomer.execution.driver.spi.SecretCharacters
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import cc.monomer.execution.driver.jdbc.WarehouseSocketFactory
import java.sql.SQLException
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class TrinoExternalDwConformanceTest {
    private val host = requiredEnvironment("DW_LAB_TRINO_HOST")
    private val port = requiredEnvironment("DW_LAB_TRINO_PORT").toLong()
    private val connectionFactory = TrinoJdbcConnectionFactory(WarehouseSocketFactory.parseLocalEndpoints("$host:$port"))
    private val caPath = Path.of(requiredEnvironment("DW_LAB_TRINO_CA_PATH")).toAbsolutePath()
    private val username = requiredEnvironment("DW_LAB_TRINO_USERNAME")
    private val passwordPath = Path.of(requiredEnvironment("DW_LAB_TRINO_PASSWORD_FILE")).toAbsolutePath()
    private val password by lazy { Files.readString(passwordPath).trim() }

    init {
        require(Files.isRegularFile(caPath) && Files.isReadable(caPath)) {
            "DW_LAB_TRINO_CA_PATH must be a readable regular PEM file"
        }
        require(Files.isRegularFile(passwordPath) && Files.isReadable(passwordPath)) {
            "DW_LAB_TRINO_PASSWORD_FILE must be a readable regular file"
        }
    }

    @Test
    fun `validation reaches query and catalog surfaces through production connection mapping`() {
        listOf("postgres", "iceberg", "hive").forEach { catalog ->
            open(catalog).use { connection ->
                connection.prepareStatement("SELECT 1").use { statement ->
                    statement.queryTimeout = 10
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(1, rows.getInt(1))
                    }
                }
                connection.prepareStatement("SHOW CATALOGS").use { statement ->
                    statement.executeQuery().use { rows ->
                        val visible = buildSet {
                            while (rows.next()) add(rows.getString(1))
                        }
                        assertEquals(setOf("hive", "iceberg", "postgres", "tpch"), visible)
                    }
                }
            }
        }
    }

    @Test
    fun `raw SQL validation resolves direct relations and native validation`() = runBlocking {
        val provider =
            TrinoExecutionDriverProvider(
                maximumQuerySeconds = 300,
                arrowBatchSize = 1_024,
                warehouseTrustCertificatePath = caPath,
                timeoutPolicy =
                    JdbcRunTimeoutPolicy(
                        maximumConnectMillis = 3_000,
                        maximumSocketMillis = 7_000,
                        maximumLockMillis = 1_000,
                    ),
                blockingDispatcher = Dispatchers.IO,
                clock = Clock.systemUTC(),
                connectionFactory = connectionFactory,
            )
        val cases =
            listOf(
                Triple("postgres", "postgres.analytics.orders", 1L),
                Triple("iceberg", "iceberg.northstar_iceberg.orders", 1L),
                Triple("hive", "hive.northstar_hive.orders", 1L),
            )
        cases.forEach { (catalog, table, expectedRows) ->
            open(catalog).use { connection ->
                val sql = "SELECT order_id FROM $table WHERE order_id=?"
                credential(password).use { credential ->
                    val analysis =
                        provider.validateRawSql(
                            connector = connector(catalog),
                            credential = credential,
                            sql = sql,
                            parameters = listOf(QueryParameter.Integer64(1, name = "")),
                            deadlineEpochMillis = System.currentTimeMillis() + 30_000,
                            timing = DriverOperationTiming(ConnectorType.TRINO, DriverOperation.VALIDATE, null, null),
                        )
                    assertEquals(
                        Unit,
                        analysis,
                    )
                }
                connection.prepareStatement(sql).use { statement ->
                    statement.setLong(1, 1)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(expectedRows, rows.getLong(1))
                    }
                }
            }
        }

        credential(password).use { credential ->
            val relationFree =
                provider.validateRawSql(
                    connector = connector("hive"),
                    credential = credential,
                    sql = "SELECT 1 AS connectivity_probe",
                    parameters = emptyList(),
                    deadlineEpochMillis = System.currentTimeMillis() + 30_000,
                    timing = DriverOperationTiming(ConnectorType.TRINO, DriverOperation.VALIDATE, null, null),
                )
            assertEquals(Unit, relationFree)
        }
        open("hive").use { connection ->
            connection.prepareStatement("SELECT 1 AS connectivity_probe").use { statement ->
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(1, rows.getInt(1))
                }
            }
        }

        val viewOnlySql =
            "SELECT order_id FROM hive.northstar_hive.recent_orders ORDER BY order_id LIMIT 1"
        credential(password).use { credential ->
            val viewOnly =
                provider.validateRawSql(
                    connector = connector("hive"),
                    credential = credential,
                    sql = viewOnlySql,
                    parameters = emptyList(),
                    deadlineEpochMillis = System.currentTimeMillis() + 30_000,
                    timing = DriverOperationTiming(ConnectorType.TRINO, DriverOperation.VALIDATE, null, null),
                )
            assertEquals(
                Unit,
                viewOnly,
            )
        }
        open("hive").use { connection ->
            connection.prepareStatement(viewOnlySql).use { statement ->
                statement.executeQuery().use { rows -> assertTrue(rows.next()) }
            }
        }

        val viewAndBaseSql =
            "SELECT recent.order_id " +
                "FROM hive.northstar_hive.recent_orders recent " +
                "JOIN hive.northstar_hive.orders base ON base.order_id=recent.order_id " +
                "ORDER BY recent.order_id LIMIT 1"
        credential(password).use { credential ->
            val viewAndBase =
                provider.validateRawSql(
                    connector = connector("hive"),
                    credential = credential,
                    sql = viewAndBaseSql,
                    parameters = emptyList(),
                    deadlineEpochMillis = System.currentTimeMillis() + 30_000,
                    timing = DriverOperationTiming(ConnectorType.TRINO, DriverOperation.VALIDATE, null, null),
                )
            assertEquals(
                Unit,
                viewAndBase,
            )
        }
        open("hive").use { connection ->
            connection.prepareStatement(viewAndBaseSql).use { statement ->
                statement.executeQuery().use { rows -> assertTrue(rows.next()) }
            }
        }

        credential(password).use { credential ->
            val crossCatalog =
                provider.validateRawSql(
                    connector = connector("postgres"),
                    credential = credential,
                    sql =
                        "SELECT p.order_id " +
                            "FROM postgres.analytics.orders p " +
                            "JOIN iceberg.northstar_iceberg.orders i ON i.order_id=p.order_id " +
                            "WHERE p.order_id=?",
                    parameters = listOf(QueryParameter.Integer64(1, name = "")),
                    deadlineEpochMillis = System.currentTimeMillis() + 30_000,
                    timing = DriverOperationTiming(ConnectorType.TRINO, DriverOperation.VALIDATE, null, null),
                )
            assertEquals(
                Unit,
                crossCatalog,
            )
        }
    }

    @Test
    fun `live failures retain safe authentication object and permission codes`() {
        val wrongPasswordFailure =
            assertFailsWith<SQLException> {
                open("postgres", passwordOverride = "definitely-not-the-password").use { connection ->
                    connection.prepareStatement("SELECT 1").use { statement ->
                        statement.executeQuery().close()
                    }
                }
            }
        assertEquals("AUTHENTICATION_FAILED", dialect().safeFailureCode(wrongPasswordFailure))

        open("iceberg").use { connection ->
            val missing =
                assertFailsWith<SQLException> {
                    connection.prepareStatement(
                        "SELECT * FROM iceberg.northstar_iceberg.missing_table",
                    ).use { statement -> statement.executeQuery().close() }
                }
            assertEquals("OBJECT_NOT_FOUND", dialect().safeFailureCode(missing))

            val denied =
                assertFailsWith<SQLException> {
                    connection.prepareStatement(
                        "DELETE FROM iceberg.northstar_iceberg.orders WHERE order_id=-1",
                    ).use { statement -> statement.executeUpdate() }
                }
            assertEquals("PERMISSION_DENIED", dialect().safeFailureCode(denied))
        }
    }

    private fun open(
        catalog: String,
        passwordOverride: String = password,
    ): Connection {
        val connector = connector(catalog)
        val credential = credential(passwordOverride)
        val properties = credential.use { dialect().connectionProperties(connector, credential, timeoutBudget) }
        return try {
            connectionFactory.open(dialect().jdbcUrl(connector), properties).also { connection ->
                connection.isReadOnly = true
                dialect().prepareConnection(connection, connector, timeoutBudget)
            }
        } finally {
            properties.clear()
        }
    }

    private fun connector(catalog: String): ConnectorRuntimeBinding =
        ConnectorRuntimeBinding(
            schemaVersion = 1u,
            connectorId = ConnectorId.parse("019f0000-0000-7000-8000-000000000003"),
            connectorType = ConnectorType.TRINO,
            properties = mapOf(
                    "host" to ConnectorPropertyValue.Text(host),
                    "port" to ConnectorPropertyValue.Integer(port),
                    "catalog" to ConnectorPropertyValue.Text(catalog),
                    "ssl" to ConnectorPropertyValue.Text("true"),
                    "ssl_verification" to ConnectorPropertyValue.Text("full"),
                ),
        )

    private fun credential(passwordValue: String): MaterializedCredential =
        MaterializedCredential(
            mapOf(
                "username" to SecretCharacters.copyOf(username.toCharArray()),
                "password" to SecretCharacters.copyOf(passwordValue.toCharArray()),
            ),
        )

    private fun dialect() = TrinoDialect(caPath)

    private val timeoutBudget =
        JdbcRunTimeoutBudget(
            remainingMillis = 30_000,
            connectTimeoutMillis = 10_000,
            socketTimeoutMillis = 20_000,
            statementTimeoutMillis = 20_000,
            lockTimeoutMillis = 5_000,
        )

    private fun requiredEnvironment(name: String): String =
        requireNotNull(System.getenv(name)?.takeIf(String::isNotBlank)) {
            "$name is required; absence is BLOCKED_ENV, not a skipped G5"
        }
}
