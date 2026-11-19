package cc.monomer.control

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status

class ControlAdmissionHealthIndicatorTest {
    @Test
    fun `operator bootstrap is healthy while public writes remain closed`() {
        val runtimeProperties = testControlRuntimeProperties()

        val health = ControlAdmissionHealthIndicator(runtimeProperties).health()

        assertThat(health.status).isEqualTo(Status.UP)
        assertThat(health.details).containsEntry("mode", "OPERATOR_BOOTSTRAP")
        assertThat(health.details).containsEntry("publicWrites", "CLOSED")
        assertThat(health.details)
            .containsEntry("executionTarget", "http://127.0.0.1:11099")
        assertThat(health.details).containsEntry("grpcPort", 12078)
    }
}
