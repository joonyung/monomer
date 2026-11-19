package cc.monomer.control.auth.inbound.agentoauth

import cc.monomer.identity.domain.model.DeveloperAccessPolicy

import cc.monomer.control.ControlRuntimeProperties
import cc.monomer.control.mcp.McpDashboardView
import cc.monomer.control.mcp.MonomerDocsReader
import cc.monomer.control.mcp.McpRouter
import cc.monomer.control.mcp.McpTransportProperties
import cc.monomer.control.publicapi.AgentWorkspaceHttpPorts
import cc.monomer.identity.api.AgentOAuthPort
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.OAuthClient
import cc.monomer.identity.application.agent.AgentOAuthService
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.outbound.oauth.HttpsOAuthClientResolver
import cc.monomer.identity.outbound.postgres.PostgresAgentOAuthRepository
import cc.monomer.identity.domain.port.CurrentUserCredentialVerifier
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentOAuthProperties::class)
class AgentOAuthConfiguration {
    @Bean
    fun webSessionIdResolver(properties: AgentOAuthProperties): org.springframework.web.server.session.WebSessionIdResolver =
        org.springframework.web.server.session.CookieWebSessionIdResolver().apply {
            addCookieInitializer { cookie -> cookie.httpOnly(true).sameSite("Lax").secure(properties.issuer.startsWith("https://")) }
        }

    @Bean
    fun agentOAuthPort(
        @Qualifier("postgresJdbcTemplate") jdbc: NamedParameterJdbcTemplate,
        verifier: CurrentUserCredentialVerifier,
        transactionRunner: TransactionRunner,
        clock: Clock,
        runtime: ControlRuntimeProperties,
        properties: AgentOAuthProperties,
        developerAccessPolicy: DeveloperAccessPolicy,
    ): AgentOAuthPort = AgentOAuthService(
        PostgresAgentOAuthRepository(jdbc, verifier, developerAccessPolicy), transactionRunner,
        HttpsOAuthClientResolver(mapOf("monomer-native" to OAuthClient("monomer-native", "Monomer CLI", setOf("http://127.0.0.1/callback")))),
        clock, mapOf(ExpectedOAuthResource.AGENT_REST to runtime.security.agentResource, ExpectedOAuthResource.AGENT_MCP to properties.mcpResource),
    )

    @Bean
    fun mcpRoutes(
        publicApiProperties: cc.monomer.control.publicapi.ControlPublicApiProperties,
        ports: AgentWorkspaceHttpPorts,
        managementOwnerPort: cc.monomer.agent.api.AgentManagementOwnerPort,
        oauth: AgentOAuthPort,
        properties: AgentOAuthProperties,
        @Value("\${auth.cors.allowed-origins}") allowedOrigins: String,
        @Value("\${monomer.control.docs-origin}") docsOrigin: String,
        @Value("\${monomer.mcp.dashboard-view-file}") dashboardViewFile: String,
        clock: Clock,
    ) = McpRouter(ports, oauth, McpTransportProperties(
        "${properties.issuer}/.well-known/oauth-protected-resource/mcp",
        allowedOrigins.split(',').map(String::trim).filter(String::isNotEmpty).toSet(),
    ), MonomerDocsReader(docsOrigin), managementOwnerPort, publicApiProperties, McpDashboardView.load(dashboardViewFile), clock).routes()
}
