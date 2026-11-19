package cc.monomer.control.auth.inbound.agentoauth

import java.net.URI
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("monomer.control.oauth")
data class AgentOAuthProperties(val issuer: String, val mcpResource: String) {
    init {
        for (value in listOf(issuer, mcpResource)) {
            val uri = URI(value)
            require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "[::1]")))
            require(uri.userInfo == null && uri.query == null && uri.fragment == null)
        }
        require(URI(issuer).path.isNullOrEmpty())
        require(URI(mcpResource).path == "/mcp")
    }
}
