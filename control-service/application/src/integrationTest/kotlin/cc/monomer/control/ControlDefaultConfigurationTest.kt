package cc.monomer.control

import cc.monomer.authority.api.CurrentResourceFocusConePort
import cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogCurrentObjectsReadPort
import cc.monomer.catalog.api.CatalogCurrentObjectsReadPort
import cc.monomer.connector.api.ConnectorExecutionAccessInOwnerTransactionPort
import cc.monomer.connector.application.ConnectorAuthorityService
import cc.monomer.identity.application.IdentityAdmissionService
import cc.monomer.execution.api.ExecutionRunUseCase
import cc.monomer.semantic.application.SemanticAuthorityService
import cc.monomer.semantic.adapter.outbound.metricflow.MetricFlowSemanticRenderer
import cc.monomer.semantic.application.SemanticMetricFlowPort
import java.nio.file.Path
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource

class ControlDefaultConfigurationTest {
    @Test
    fun `local configuration fixes local ports and keeps admission closed`() {
        val dataSource = ephemeralPostgres.dataSource
        val controlRoot = Path.of(System.getProperty("control.service.root"))
        val schemaPaths = controlRoot.resolve("deployment/database-bootstrap-ddl.txt")
            .toFile().readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
        check(schemaPaths.isNotEmpty())
        dataSource.connection.use { connection ->
            schemaPaths.forEach { schemaPath ->
                connection.createStatement().use { statement ->
                    statement.execute(controlRoot.resolve(schemaPath).toFile().readText())
                }
            }
        }
        val application = SpringApplication(ControlApplication::class.java)
        application.setWebApplicationType(WebApplicationType.NONE)
        val authJwtPrivateKeyPem = generateEphemeralAuthJwtPrivateKeyPem()

        application.run(
            "--spring.main.banner-mode=off",
            "--spring.profiles.active=local",
            "--auth.jwt.private-key-pem=$authJwtPrivateKeyPem",
            "--monomer.control.product-authority.postgres.jdbc-url=${dataSource.url}",
            "--monomer.control.product-authority.postgres.username=${dataSource.username}",
            "--monomer.control.product-authority.postgres.password=${dataSource.password}",
            "--monomer.credentials.aes-gcm.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "--monomer.credentials.aes-gcm.key-ref=default-configuration-test",
            "--monomer.control.execution-runtime.credential-vault.keys-base64.local-primary=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        ).use { applicationContext ->
            val environment = applicationContext.environment
            val runtimeProperties = applicationContext.getBean(ControlRuntimeProperties::class.java)
            assertThat(environment.getRequiredProperty("auth.jwt.private-key-pem") == authJwtPrivateKeyPem).isTrue()

            assertThat(environment.getRequiredProperty("server.address"))
                .isEqualTo("127.0.0.1")
            assertThat(environment.getRequiredProperty("server.port").toInt())
                .isEqualTo(11077)
            assertThat(runtimeProperties.grpc.port).isEqualTo(11078)
            assertThat(runtimeProperties.grpc.address).isEqualTo("127.0.0.1")
            assertThat(runtimeProperties.grpc.maximumInboundMessageBytes)
                .isEqualTo(17 * 1_024 * 1_024)
            assertThat(runtimeProperties.grpc.shutdownGrace)
                .isEqualTo(java.time.Duration.ofSeconds(5))
            assertThat(runtimeProperties.admission.mode)
                .isEqualTo(ControlAdmissionMode.OPERATOR_BOOTSTRAP)
            assertThat(runtimeProperties.admission.publicWrites)
                .isEqualTo(PublicWriteAdmission.CLOSED)
            assertThat(runtimeProperties.executionRuntime.executionTarget)
                .isEqualTo(java.net.URI("http://127.0.0.1:11099"))
            assertThat(runtimeProperties.executionRuntime.credentialVault.activeKeyVersion)
                .isEqualTo("local-primary")
            assertThat(runtimeProperties.security.issuer)
                .isEqualTo("urn:monomer:control:local")
            assertThat(runtimeProperties.security.agentResource)
                .isEqualTo("http://127.0.0.1:11077/v2/workspaces")
            assertThat(runtimeProperties.security.loginNamespace)
                .isEqualTo("monomer-local-dev")
            assertThat(runtimeProperties.security.loginSubjectUrnPrefix)
                .isEqualTo("urn:monomer:auth:dev")
            assertThat(runtimeProperties.security.sessionCookieName)
                .isEqualTo("monomer_session")
            assertThat(runtimeProperties.security.sessionCookieHostOnly).isTrue()
            assertThat(applicationContext.getBeansOfType(DataSource::class.java))
                .hasSize(1)
            assertThat(
                applicationContext.getBeansOfType(
                    DataSourceTransactionManager::class.java,
                ),
            ).hasSize(1)
            assertThat(applicationContext.getBean(IdentityAdmissionService::class.java))
                .isNotNull()
            assertThat(applicationContext.getBean(CurrentResourceFocusConePort::class.java))
                .isNotNull()
            assertThat(applicationContext.getBean(ExecutionRunUseCase::class.java))
                .isNotNull()
            assertThat(applicationContext.getBean(ConnectorAuthorityService::class.java))
                .isNotNull()
            assertThat(applicationContext.getBean(SemanticAuthorityService::class.java))
                .isNotNull()
            assertThat(applicationContext.getBean(SemanticMetricFlowPort::class.java))
                .isInstanceOf(MetricFlowSemanticRenderer::class.java)
            assertThat(applicationContext.getBean(CatalogCurrentObjectsReadPort::class.java))
                .isInstanceOf(PostgresCatalogCurrentObjectsReadPort::class.java)
            assertThat(
                applicationContext.getBeansOfType(ConnectorExecutionAccessInOwnerTransactionPort::class.java),
            ).hasSize(1)
            assertThat(applicationContext.getBean(
                cc.monomer.catalog.application.ingest.CatalogIngestApplicationService::class.java,
            ))
                .isNotNull()
        }
    }

    companion object {
        @JvmField
        @RegisterExtension
        val ephemeralPostgres = DefaultConfigurationPostgres()
    }
}

class DefaultConfigurationPostgres : BeforeAllCallback, AfterAllCallback {
    private val postgres = PostgreSQLContainer(
        DockerImageName.parse(
            "postgres:17.10-alpine3.24@" +
                "sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193",
        ).asCompatibleSubstituteFor("postgres"),
    ).withDatabaseName("default_configuration_ephemeral")
        .withUsername("configuration_test")
        .withPassword("configuration_test_only")
    lateinit var dataSource: DriverManagerDataSource
        private set

    override fun beforeAll(context: ExtensionContext) {
        try {
            postgres.start()
            check(postgres.isRunning)
            dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            dataSource.connection.use { connection ->
                val uri = java.net.URI(connection.metaData.url.removePrefix("jdbc:").substringBefore("?"))
                check(uri.host == postgres.host)
                check(uri.port == postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT))
                check(uri.path == "/default_configuration_ephemeral")
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT current_database(), current_user").use { result ->
                        check(result.next())
                        check(result.getString(1) == "default_configuration_ephemeral")
                        check(result.getString(2) == "configuration_test")
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
}
