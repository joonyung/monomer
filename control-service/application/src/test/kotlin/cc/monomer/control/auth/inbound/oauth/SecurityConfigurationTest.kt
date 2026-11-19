package cc.monomer.control.auth.inbound.oauth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.cors.reactive.CorsWebFilter

class SecurityConfigurationTest {
    @Test
    fun `only the closed Agent Workspace transport paths bypass the browser JWT gate`() {
        assertThat(SecurityConfiguration.AGENT_WORKSPACE_PATHS).containsExactly(
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

    @Test
    fun `cors configuration allows local frontend graphql requests`() {
        val configurationSource = SecurityConfiguration(
            "http://localhost:3000, http://127.0.0.1:3000",
        ).corsConfigurationSource()
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest
                .options("/v2/graphql")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
                .build(),
        )

        val configuration = configurationSource.getCorsConfiguration(exchange)

        assertThat(configuration).isNotNull
        assertThat(configuration!!.allowedOrigins).contains("http://localhost:3000")
        assertThat(configuration.allowedMethods).contains("POST", "PUT", "OPTIONS")
        assertThat(configuration.allowedHeaders).contains("Authorization", "Content-Type", "Idempotency-Key")
        assertThat(configuration.allowedHeaders).doesNotContain(
            "X-Organization-Id",
            "X-Workspace-Id",
            "X-Internal-Api-Key",
            "x-service-name",
        )
        assertThat(configuration.exposedHeaders).contains(
            HttpHeaders.CONTENT_DISPOSITION,
            HttpHeaders.ETAG,
            HttpHeaders.LOCATION,
            HttpHeaders.RETRY_AFTER,
            HttpHeaders.WWW_AUTHENTICATE,
        )
        assertThat(configuration.allowCredentials).isTrue()
    }

    @ParameterizedTest
    @CsvSource(
        "DELETE, /v2/workspaces/workspace-id/pages/page-id",
        "PATCH, /v2/workspaces/workspace-id/pages/page-id",
        "PATCH, /v2/workspaces/workspace-id/pages/tree",
    )
    fun `cors preflight allows page mutations from the configured frontend`(method: String, path: String) {
        corsPreflightClient().options()
            .uri(path)
            .header(HttpHeaders.ORIGIN, "https://app.monomer.test")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,idempotency-key")
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://app.monomer.test")
            .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
            .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS) { methods ->
                assertThat(methods.split(",").map { it.trim() }).contains(method)
            }
            .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS) { headers ->
                assertThat(headers.lowercase().split(",").map { it.trim() })
                    .contains("authorization", "content-type", "idempotency-key")
            }
    }

    @ParameterizedTest
    @CsvSource(
        "https://untrusted.monomer.test, DELETE",
        "https://untrusted.monomer.test, PATCH",
        "https://app.monomer.test, TRACE",
    )
    fun `cors preflight rejects unconfigured origins and unsupported methods`(origin: String, method: String) {
        corsPreflightClient().options()
            .uri("/v2/workspaces/workspace-id/pages/page-id")
            .header(HttpHeaders.ORIGIN, origin)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
            .exchange()
            .expectStatus().isForbidden
            .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
    }

    private fun corsPreflightClient(): WebTestClient = WebTestClient
        .bindToWebHandler { error("CORS preflight must complete before the application handler") }
        .webFilter(CorsWebFilter(SecurityConfiguration("https://app.monomer.test").corsConfigurationSource()))
        .configureClient()
        .baseUrl("https://api.monomer.test")
        .build()
}
