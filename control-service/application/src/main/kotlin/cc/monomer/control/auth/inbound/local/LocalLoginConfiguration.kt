package cc.monomer.control.auth.inbound.local

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
@Profile("local")
@ConditionalOnProperty(prefix = "auth.local-login", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(LocalLoginProperties::class)
class LocalLoginConfiguration(
    @Value("\${server.address:}")
    serverAddress: String,
) {
    init {
        require(serverAddress in LOOPBACK_SERVER_ADDRESSES) {
            "local login requires server.address to be an explicit loopback address"
        }
    }

    private companion object {
        val LOOPBACK_SERVER_ADDRESSES = setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
    }
}
