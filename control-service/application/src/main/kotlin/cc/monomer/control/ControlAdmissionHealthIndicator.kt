package cc.monomer.control

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component

@Component
class ControlAdmissionHealthIndicator(
    private val runtimeProperties: ControlRuntimeProperties,
) : HealthIndicator {
    override fun health(): Health =
        Health.up()
            .withDetail("mode", runtimeProperties.admission.mode.name)
            .withDetail("publicWrites", runtimeProperties.admission.publicWrites.name)
            .withDetail("executionTarget", runtimeProperties.executionRuntime.executionTarget.toString())
            .withDetail("grpcPort", runtimeProperties.grpc.port)
            .build()
}
