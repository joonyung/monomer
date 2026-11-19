package cc.monomer.control

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.annotation.DirtiesContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.Environment
import org.springframework.test.web.reactive.server.WebTestClient
import javax.sql.DataSource

@ActiveProfiles("local")
@Import(EphemeralControlConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [ControlApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.health.db.enabled=false",
        "monomer.credentials.aes-gcm.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "monomer.credentials.aes-gcm.key-ref=integration-test",
        "monomer.control.execution-runtime.credential-vault.keys-base64.local-primary=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    ],
)
class ControlApplicationIntegrationTest @Autowired constructor(
    private val applicationContext: ConfigurableApplicationContext,
    private val environment: Environment,
    private val runtimeProperties: ControlRuntimeProperties,
    private val controlAdmissionHealthIndicator: ControlAdmissionHealthIndicator,
    private val dataSource: DataSource,
    private val resources: EphemeralControlResources,
) {
    @Test
    fun `application boots with closed admission and exposes safe truthful health`() {
        val localServerPort = environment.getRequiredProperty("local.server.port").toInt()
        resources.verifyIsolation(dataSource, environment)

        assertThat(applicationContext.isActive).isTrue()
        assertThat(environment.getRequiredProperty("server.address")).isEqualTo("127.0.0.1")
        assertThat(localServerPort).isPositive()
        assertThat(runtimeProperties.grpc.port).isEqualTo(resources.reservedPort.localPort)
        assertThat(runtimeProperties.admission.mode)
            .isEqualTo(ControlAdmissionMode.OPERATOR_BOOTSTRAP)
        assertThat(runtimeProperties.admission.publicWrites)
            .isEqualTo(PublicWriteAdmission.CLOSED)
        assertThat(runtimeProperties.executionRuntime.executionTarget)
            .isEqualTo(java.net.URI(resources.unusedEndpoint))
        assertThat(runtimeProperties.security.agentResource)
            .isEqualTo("http://127.0.0.1:11077/v2/workspaces")
        assertThat(runtimeProperties.security.sessionCookieHostOnly).isTrue()
        assertThat(applicationContext.getBeansOfType(DataSource::class.java)).hasSize(1)
        assertThat(controlAdmissionHealthIndicator.health().status.code).isEqualTo("UP")

        WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:$localServerPort")
            .build()
            .get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("UP")
            .jsonPath("$.components").doesNotExist()
    }
}
