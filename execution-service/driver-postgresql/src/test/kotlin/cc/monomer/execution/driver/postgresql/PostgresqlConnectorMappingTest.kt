package cc.monomer.execution.driver.postgresql

import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutBudget
import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.ConnectorId
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.SecretCharacters
import java.nio.file.Path
import org.postgresql.PGProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PostgresqlConnectorMappingTest {
    @Test
    fun timestampBindingsHaveExplicitUtcInstantType() {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val statement = java.lang.reflect.Proxy.newProxyInstance(
            java.sql.PreparedStatement::class.java.classLoader,
            arrayOf(java.sql.PreparedStatement::class.java),
        ) { _, method, arguments -> calls += method.name to arguments.orEmpty().toList(); null } as java.sql.PreparedStatement
        val instant = java.time.Instant.parse("2026-10-06T01:02:03.123456Z")
        PostgresqlDialect(warehouseTrustCertificatePath, 128).bindQueryParameters(statement, listOf(
            cc.monomer.execution.driver.spi.QueryParameter.Timestamp(instant, name = ""),
            cc.monomer.execution.driver.spi.QueryParameter.Null(cc.monomer.execution.driver.spi.NullQueryParameterType.TIMESTAMP, name = ""),
        ))
        assertEquals("setObject", calls[0].first)
        assertEquals(listOf(1, instant.atOffset(java.time.ZoneOffset.UTC)), calls[0].second)
        assertEquals(listOf(2, java.sql.Types.TIMESTAMP_WITH_TIMEZONE), calls[1].second)
    }

    @Test
    fun `typed port and product ssl modes map exactly to JDBC`() {
        val credential = credential()
        val dialect = PostgresqlDialect(warehouseTrustCertificatePath, 1_024)
        credential.use {
            val verifyCa = connector("verify_ca", ConnectorPropertyValue.Integer(6543))
            assertEquals(
                "jdbc:postgresql://db.example.com:6543/analytics",
                dialect.jdbcUrl(verifyCa),
            )
            val verifyCaProperties =
                dialect.connectionProperties(
                    verifyCa,
                    credential,
                    timeoutBudget,
                )
            assertEquals("verify-ca", verifyCaProperties.getProperty("sslmode"))
            assertEquals(
                warehouseTrustCertificatePath.toString(),
                verifyCaProperties.getProperty("sslrootcert"),
            )
            assertEquals("3", verifyCaProperties.getProperty("connectTimeout"))
            assertEquals("7", verifyCaProperties.getProperty("socketTimeout"))

            val verifyFullProperties =
                dialect.connectionProperties(
                    connector("verify_full", ConnectorPropertyValue.Integer(5432)),
                    credential,
                    timeoutBudget,
                )
            assertEquals("verify-full", verifyFullProperties.getProperty("sslmode"))
            assertEquals(
                warehouseTrustCertificatePath.toString(),
                verifyFullProperties.getProperty("sslrootcert"),
            )
        }
    }

    @Test
    fun `disable and require do not inject the warehouse CA`() {
        val dialect = PostgresqlDialect(warehouseTrustCertificatePath, 1_024)
        val credential = credential()
        credential.use {
            listOf("disable", "require").forEach { sslMode ->
                val properties =
                    dialect.connectionProperties(
                        connector(sslMode, ConnectorPropertyValue.Integer(5432)),
                        credential,
                        timeoutBudget,
                    )

                assertEquals(sslMode, properties.getProperty("sslmode"))
                assertEquals(null, properties.getProperty("sslrootcert"))
            }
        }
    }

    @Test
    fun `result rows stream through a server cursor of one Arrow batch`() {
        val credential = credential()
        credential.use {
            val properties =
                PostgresqlDialect(warehouseTrustCertificatePath, 512).connectionProperties(
                    connector("require", ConnectorPropertyValue.Integer(5432)),
                    credential,
                    timeoutBudget,
                )

            assertEquals(512, PGProperty.DEFAULT_ROW_FETCH_SIZE.getInt(properties))
        }
        assertFailsWith<IllegalArgumentException> {
            PostgresqlDialect(warehouseTrustCertificatePath, 0)
        }
    }

    @Test
    fun `string port and JDBC-spelled ssl modes are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PostgresqlDialect(warehouseTrustCertificatePath, 1_024).jdbcUrl(
                connector("require", ConnectorPropertyValue.Text("5432")),
            )
        }
        val credential = credential()
        credential.use {
            assertFailsWith<IllegalArgumentException> {
                PostgresqlDialect(warehouseTrustCertificatePath, 1_024).connectionProperties(
                    connector("verify-ca", ConnectorPropertyValue.Integer(5432)),
                    credential,
                    timeoutBudget,
                )
            }
        }
    }

    @Test
    fun `bracketed IPv6 hosts accepted by the connector contract map to JDBC`() {
        assertEquals(
            "jdbc:postgresql://[2001:db8::10]:5432/analytics",
            PostgresqlDialect(warehouseTrustCertificatePath, 1_024).jdbcUrl(
                connector("require", ConnectorPropertyValue.Integer(5432), "[2001:db8::10]"),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            PostgresqlDialect(warehouseTrustCertificatePath, 1_024).jdbcUrl(
                connector("require", ConnectorPropertyValue.Integer(5432), "[2001:db8::10]/x"),
            )
        }
    }

    private fun connector(
        sslMode: String,
        port: ConnectorPropertyValue,
    ): ConnectorRuntimeBinding = connector(sslMode, port, "db.example.com")

    private fun connector(
        sslMode: String,
        port: ConnectorPropertyValue,
        host: String,
    ): ConnectorRuntimeBinding =
        ConnectorRuntimeBinding(
            schemaVersion = 1u,
            connectorId = ConnectorId.parse(
                    "019f0000-0000-7000-8000-000000000003",
                ),
            connectorType = ConnectorType.POSTGRESQL,
            properties = mapOf(
                    "host" to ConnectorPropertyValue.Text(host),
                    "port" to port,
                    "database" to ConnectorPropertyValue.Text("analytics"),
                    "ssl_mode" to ConnectorPropertyValue.Text(sslMode),
                ),
        )

    private fun credential(): MaterializedCredential =
        MaterializedCredential(
            mapOf(
                "username" to SecretCharacters.copyOf("reader".toCharArray()),
                "password" to SecretCharacters.copyOf("secret".toCharArray()),
            ),
        )

    private val timeoutBudget =
        JdbcRunTimeoutBudget(
            remainingMillis = 10_000,
            connectTimeoutMillis = 3_000,
            socketTimeoutMillis = 7_000,
            statementTimeoutMillis = 9_000,
            lockTimeoutMillis = 1_000,
        )

    private val warehouseTrustCertificatePath = Path.of("/operator/trust/ca.pem")
}
