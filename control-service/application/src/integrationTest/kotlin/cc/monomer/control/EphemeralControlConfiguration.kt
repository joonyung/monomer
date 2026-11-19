package cc.monomer.control

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.Base64
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class EphemeralControlConfiguration {
    @Bean(destroyMethod = "close")
    fun ephemeralControlResources(): EphemeralControlResources = EphemeralControlResources()

    @Bean
    fun ephemeralControlProperties(resources: EphemeralControlResources): DynamicPropertyRegistrar =
        DynamicPropertyRegistrar { registry ->
            registry.add("monomer.control.product-authority.postgres.jdbc-url") { resources.postgres.jdbcUrl }
            registry.add("monomer.control.product-authority.postgres.username") { resources.postgres.username }
            registry.add("monomer.control.product-authority.postgres.password") { resources.postgres.password }
            registry.add("monomer.control.grpc.port") { resources.reservedPort.localPort }
            registry.add("monomer.control.execution-runtime.execution-target") { resources.unusedEndpoint }
            registry.add("monomer.control.object-storage.endpoint") { resources.unusedEndpoint }
            registry.add("monomer.control.execution-runtime.artifact-store.endpoint") { resources.unusedEndpoint }
            registry.add("auth.jwt.private-key-pem") { resources.authJwtPrivateKeyPem }
            registry.add("auth.jwt.private-key-path") { "" }
        }
}

class EphemeralControlResources : AutoCloseable {
    val authJwtPrivateKeyPem = generateEphemeralAuthJwtPrivateKeyPem()
    val postgres = PostgreSQLContainer(
        DockerImageName.parse(
            "postgres:17.10-alpine3.24@" +
                "sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193",
        ).asCompatibleSubstituteFor("postgres"),
    ).withDatabaseName("control_boot_ephemeral")
        .withUsername("control_boot_test")
        .withPassword("control_boot_test_only")
    // Keep unused network targets reserved so background work cannot reach a developer service.
    val reservedPort = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
    val unusedEndpoint: String get() = "http://127.0.0.1:${reservedPort.localPort}"

    init {
        try {
            postgres.start()
            val controlRoot = Path.of(System.getProperty("control.service.root"))
            val schemaPaths = controlRoot.resolve("deployment/database-bootstrap-ddl.txt")
                .toFile().readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
            check(schemaPaths.isNotEmpty())
            DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password).connection.use { connection ->
                schemaPaths.forEach { schemaPath ->
                    connection.createStatement().use { statement ->
                        statement.execute(controlRoot.resolve(schemaPath).toFile().readText())
                    }
                }
            }
        } catch (failure: Throwable) {
            runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    fun verifyIsolation(dataSource: DataSource, environment: Environment) {
        assertThat(environment.getRequiredProperty("server.port").toInt()).isZero()
        assertThat(environment.getRequiredProperty("local.server.port").toInt()).isPositive()
        assertThat(environment.getRequiredProperty("monomer.control.grpc.port").toInt())
            .isEqualTo(reservedPort.localPort)
        assertThat(environment.getRequiredProperty("monomer.control.execution-runtime.execution-target"))
            .isEqualTo(unusedEndpoint)
        assertThat(environment.getRequiredProperty("auth.jwt.private-key-pem") == authJwtPrivateKeyPem).isTrue()
        assertThat(environment.getRequiredProperty("auth.jwt.private-key-path")).isEmpty()
        dataSource.connection.use { connection ->
            assertThat(connection.metaData.url).isEqualTo(postgres.jdbcUrl)
            assertThat(connection.catalog).isEqualTo("control_boot_ephemeral")
            assertThat(connection.metaData.userName).isEqualTo("control_boot_test")
            println("Isolated Control: jdbcUrl=${connection.metaData.url}, httpPort=${environment.getRequiredProperty("local.server.port")}, reservedPort=${reservedPort.localPort}")
        }
    }

    override fun close() {
        try {
            postgres.stop()
        } finally {
            reservedPort.close()
        }
    }
}

internal fun generateEphemeralAuthJwtPrivateKeyPem(): String =
    Base64.getEncoder().encodeToString(
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private.encoded,
    )
