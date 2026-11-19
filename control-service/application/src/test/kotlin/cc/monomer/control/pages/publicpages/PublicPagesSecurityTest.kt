package cc.monomer.control.pages.publicpages

import cc.monomer.control.auth.inbound.oauth.JwtAuthenticationWebFilter
import cc.monomer.control.auth.inbound.oauth.SecurityConfiguration
import cc.monomer.identity.outbound.jwt.JwtVerifier
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.web.server.WebFilterChainProxy
import org.springframework.test.web.reactive.server.WebTestClient

class PublicPagesSecurityTest {
    @Test
    fun `public allowlist permits only display reads and resolve`() {
        val chain = SecurityConfiguration("https://app.monomer.test").buildSecurityWebFilterChain(
            ServerHttpSecurity.http(), JwtAuthenticationWebFilter(mockk(), mockk(), mockk(), mockk()),
            mockk(), mockk(), StaticListableBeanFactory().getBeanProvider(ReactiveClientRegistrationRepository::class.java),
        )
        val client = WebTestClient.bindToWebHandler { exchange ->
            exchange.response.statusCode = HttpStatus.OK
            exchange.response.setComplete()
        }.webFilter(PublicPagesNoStoreFilter(), WebFilterChainProxy(chain)).build()
        val prefix = "/v2/public/workspaces/workspace/pages/page"
        for (path in listOf("/v2/workspaces/workspace/pages/access", "$prefix/content", "$prefix/sessions", "$prefix/sessions/entry", "$prefix/sessions/session/query-runs/run/result")) {
            client.get().uri(path).exchange().expectStatus().isOk.expectHeader().valueEquals("Cache-Control", "no-store")
        }
        client.post().uri("$prefix/sessions/session:resolve").exchange().expectStatus().isOk
        for (path in listOf("$prefix/sessions", "$prefix/sessions/session:refresh", "$prefix/sessions/session/query-runs/run:cancel", "$prefix/content")) {
            client.post().uri(path).exchange().expectStatus().isUnauthorized.expectHeader().valueEquals("Cache-Control", "no-store")
        }
        client.patch().uri(prefix).exchange().expectStatus().isUnauthorized
        client.get().uri("$prefix/sessions/session/events").exchange().expectStatus().isUnauthorized
        client.get().uri("/v2/workspaces/workspace/pages/tree").exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `invalid bearer on public JSON routes returns the declared problem body`() {
        val verifier = mockk<JwtVerifier>()
        every { verifier.verify("expired") } returns JwtVerifier.Outcome.Invalid("expired")
        val chain = SecurityConfiguration("https://app.monomer.test").buildSecurityWebFilterChain(
            ServerHttpSecurity.http(), JwtAuthenticationWebFilter(verifier, mockk(), mockk(), mockk()),
            mockk(), mockk(), StaticListableBeanFactory().getBeanProvider(ReactiveClientRegistrationRepository::class.java),
        )
        val client = WebTestClient.bindToWebHandler { exchange ->
            exchange.response.statusCode = HttpStatus.OK
            exchange.response.setComplete()
        }.webFilter(PublicPagesNoStoreFilter(), WebFilterChainProxy(chain)).build()
        val prefix = "/v2/public/workspaces/workspace/pages/page"
        for (path in listOf("/v2/workspaces/workspace/pages/access", "/v2/public/workspaces/workspace/pages/tree", "$prefix/content")) {
            client.get().uri(path).header("Authorization", "Bearer expired").exchange()
                .expectStatus().isUnauthorized
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(String::class.java).isEqualTo("""{"code":"UNAUTHORIZED"}""")
        }
        client.post().uri("$prefix/sessions/session:resolve").header("Authorization", "Bearer expired").exchange()
            .expectStatus().isUnauthorized
            .expectBody(String::class.java).isEqualTo("""{"code":"UNAUTHORIZED"}""")
        client.get().uri("$prefix/html").header("Authorization", "Bearer expired").exchange()
            .expectStatus().isUnauthorized
            .expectHeader().valueEquals("Cache-Control", "no-store")
            .expectBody().isEmpty
        client.get().uri("/v2/workspaces/workspace/pages/tree").header("Authorization", "Bearer expired").exchange()
            .expectStatus().isUnauthorized
            .expectBody().isEmpty
    }
}
