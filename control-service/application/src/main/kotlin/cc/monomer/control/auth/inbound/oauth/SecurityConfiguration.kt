package cc.monomer.control.auth.inbound.oauth

import cc.monomer.control.auth.inbound.session.RefreshCookieProperties
import cc.monomer.identity.domain.model.DeveloperAccessPolicy
import cc.monomer.identity.domain.port.SessionRepository
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.outbound.jwt.JwtVerifier
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.oauth2.client.web.server.ServerAuthorizationRequestRepository
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsConfigurationSource
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(OAuthProperties::class, RefreshCookieProperties::class)
class SecurityConfiguration(
    @param:Value("\${auth.cors.allowed-origins}")
    private val corsAllowedOrigins: String,
) {

    /**
     * Stateless security. Browser routes authenticate JWTs here. The six Agent Workspace routes
     * authenticate opaque Agent OAuth credentials in their closed public router instead.
     */
    @Bean
    fun securityWebFilterChain(
        http: ServerHttpSecurity,
        jwtVerifier: JwtVerifier,
        developerAccessPolicy: DeveloperAccessPolicy,
        sessionRepository: SessionRepository,
        transactionRunner: TransactionRunner,
        oauthSuccessHandler: OAuth2LoginSuccessHandler,
        authorizationRequestRepository: ServerAuthorizationRequestRepository<OAuth2AuthorizationRequest>,
        clientRegistrationRepository: ObjectProvider<ReactiveClientRegistrationRepository>,
    ): SecurityWebFilterChain = buildSecurityWebFilterChain(
        http,
        JwtAuthenticationWebFilter(jwtVerifier, developerAccessPolicy, sessionRepository, transactionRunner),
        oauthSuccessHandler,
        authorizationRequestRepository,
        clientRegistrationRepository,
    )

    // A WebFilter bean would also join the global WebFlux chain and authenticate every request twice.
    internal fun buildSecurityWebFilterChain(
        http: ServerHttpSecurity,
        jwtFilter: JwtAuthenticationWebFilter,
        oauthSuccessHandler: OAuth2LoginSuccessHandler,
        authorizationRequestRepository: ServerAuthorizationRequestRepository<OAuth2AuthorizationRequest>,
        clientRegistrationRepository: ObjectProvider<ReactiveClientRegistrationRepository>,
    ): SecurityWebFilterChain {
        http
            .cors { }
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .exceptionHandling { handling ->
                handling.authenticationEntryPoint { exchange, _ ->
                    exchange.response.statusCode = HttpStatus.UNAUTHORIZED
                    exchange.response.setComplete()
                }
            }
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .addFilterAt(jwtFilter, SecurityWebFiltersOrder.AUTHENTICATION)
            .authorizeExchange { spec ->
                spec
                    .pathMatchers("/internal/**").denyAll()
                    .pathMatchers("/v2/semantic/**").denyAll()
                    .pathMatchers(*AGENT_WORKSPACE_PATHS).permitAll()
                    .pathMatchers(HttpMethod.GET, *cc.monomer.control.pages.publicpages.PublicPagesHttpPaths.reads).permitAll()
                    .pathMatchers(HttpMethod.POST, *cc.monomer.control.pages.publicpages.PublicPagesHttpPaths.resolves).permitAll()
                    .pathMatchers(HttpMethod.HEAD, *cc.monomer.control.pages.publicpages.PublicPagesHttpPaths.heads).permitAll()
                    .pathMatchers(HttpMethod.GET, "/v2/workspaces/*/agent/explore/runs/*/result").permitAll()
                    .pathMatchers(
                        "/.well-known/jwks.json",
                        "/.well-known/oauth-authorization-server",
                        "/.well-known/oauth-protected-resource/**",
                        "/ops/oauth/authorize",
                        "/ops/oauth/token",
                        "/ops/oauth/revoke",
                        "/ops/v1/**",
                        "/oauth/authorize",
                        "/oauth/token",
                        "/oauth/revoke",
                        "/mcp",
                        "/actuator/**",
                        "/auth/local/**",
                        "/auth/refresh",
                        "/auth/invitations/exchange",
                        "/auth/invitations/resolve",
                        "/auth/invitations/accept",
                        "/auth/invitations/decline",
                        "/auth/logout",
                        "/auth/logout-all",
                        "/login/**",
                        "/oauth2/**",
                    ).permitAll()
                    .anyExchange().authenticated()
            }
        clientRegistrationRepository.ifAvailable {
            http.oauth2Login { login ->
                login.authorizationRequestResolver(OAuthStateAuthorizationRequestResolver(it))
                login.authorizationRequestRepository(authorizationRequestRepository)
                login.authenticationSuccessHandler(oauthSuccessHandler)
                login.authenticationFailureHandler(oauthSuccessHandler)
            }
        }
        return http.build()
    }

    internal companion object {
        val AGENT_WORKSPACE_PATHS = arrayOf(
            "/v2/agent/list",
            "/v2/agent/organizations/*/list",
            "/v2/agent/organizations/*/read",
            "/v2/agent/organizations/*/create",
            "/v2/agent/organizations/*/invoke",
            "/v2/workspaces/*/agent/list",
            "/v2/workspaces/*/agent/search",
            "/v2/workspaces/*/agent/read",
            "/v2/workspaces/*/agent/create",
            "/v2/workspaces/*/agent/edit",
            "/v2/workspaces/*/agent/invoke",
        )
    }

    @Bean
    fun authorizationRequestRepository(requests: cc.monomer.identity.application.pending.PendingAuthenticationRequests, browser: cc.monomer.control.auth.inbound.PendingAuthenticationBrowser): ServerAuthorizationRequestRepository<OAuth2AuthorizationRequest> =
        MultiRequestOAuth2AuthorizationRequestRepository(requests, browser)

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        configuration.allowedOrigins = corsAllowedOrigins
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
        configuration.allowedHeaders = listOf(
            "Authorization",
            "Content-Type",
            "Accept",
            "Idempotency-Key",
            "MCP-Protocol-Version",
        )
        configuration.exposedHeaders = listOf(
            "X-Request-Id",
            HttpHeaders.CONTENT_DISPOSITION,
            HttpHeaders.ETAG,
            HttpHeaders.LOCATION,
            HttpHeaders.RETRY_AFTER,
            HttpHeaders.WWW_AUTHENTICATE,
        )
        configuration.allowCredentials = true

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }
}
