package cc.monomer.control.auth.inbound.agentoauth

import cc.monomer.control.auth.PendingAuthenticationFixture
import cc.monomer.control.ControlRuntimeProperties
import cc.monomer.control.ControlSecurityProperties
import cc.monomer.control.auth.inbound.oauth.JwtAuthentication
import cc.monomer.control.publicapi.ControlPublicApiProperties
import cc.monomer.identity.api.*
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.UserCredentialProvenance
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.*
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import org.springframework.http.MediaType
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebFilter

class AgentOAuthControllerTest {
    private val oauth = mockk<AgentOAuthPort>()
    private val runtime = mockk<ControlRuntimeProperties>()
    private val browser = UserCredentialProvenance.BrowserSession(UserId.generate(), SessionId.generate())
    private val secondBrowser = UserCredentialProvenance.BrowserSession(UserId.generate(), SessionId.generate())
    private val mapper = ObjectMapper()
    private val clock = Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC)
    private val pending = PendingAuthenticationFixture(clock)
    private val client: WebTestClient
    init {
        every { runtime.security } returns ControlSecurityProperties("local", "http://127.0.0.1:11077/v2/workspaces", "local", "local", "local", true)
        coEvery { oauth.inspectAuthorization(any()) } returns OAuthOutcome.Success(OAuthClient("monomer-native", "Monomer CLI", setOf("http://127.0.0.1/callback")))
        coEvery { oauth.listConnections(any(), any(), any()) } returns OAuthOutcome.Success(OAuthConnectionPage(emptyList(), null))
        coEvery { oauth.connectionSummary(any(), any()) } returns OAuthOutcome.Success(OAuthConnectionSummary(0, null))
        client = WebTestClient.bindToController(AgentOAuthController(oauth,
            AgentOAuthProperties("http://127.0.0.1:11077", "http://127.0.0.1:11077/mcp"), runtime,
            ControlPublicApiProperties(URI("http://127.0.0.1:3100")),
            clock, pending.requests, pending.browser))
            .controllerAdvice(AgentOAuthProblemHandler())
            .webFilter<WebTestClient.ControllerSpec>(WebFilter { exchange, chain ->
                val auth = when (exchange.request.headers.getFirst("Authorization")) {
                    "Bearer browser" -> JwtAuthentication(browser, PresentedCredential("browser"))
                    "Bearer second" -> JwtAuthentication(secondBrowser, PresentedCredential("second"))
                    else -> null
                }
                if (auth == null) chain.filter(exchange) else chain.filter(exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
            }).build()
    }

    @Test fun `discovery exposes separate audience and no unsupported grants`() {
        client.get().uri("/.well-known/oauth-authorization-server").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.issuer").isEqualTo("http://127.0.0.1:11077")
            .jsonPath("$.client_id_metadata_document_supported").isEqualTo(true)
            .jsonPath("$.registration_endpoint").doesNotExist()
        client.get().uri("/.well-known/oauth-protected-resource/mcp").exchange().expectStatus().isOk.expectBody().jsonPath("$.resource").isEqualTo("http://127.0.0.1:11077/mcp")
    }

    @Test fun `consent is bound to browser cookie current user csrf and is consumed once`() {
        val started = client.get().uri { uri -> uri.path("/oauth/authorize").queryParam("response_type", "code")
            .queryParam("client_id", "monomer-native").queryParam("redirect_uri", "http://127.0.0.1:5566/callback")
            .queryParam("resource", "http://127.0.0.1:11077/v2/workspaces").queryParam("scope", "agent.workspace")
            .queryParam("code_challenge", "A".repeat(43)).queryParam("code_challenge_method", "S256").queryParam("state", "client-state").build() }
            .exchange().expectStatus().isFound.expectHeader().valueEquals("Cache-Control", "no-store").expectBody().returnResult()
        val cookie = started.responseCookies.getFirst(PendingAuthenticationFixture.COOKIE)!!
        val requestId = started.responseHeaders.location!!.rawQuery.substringAfter("request=")
        client.get().uri("/oauth/consent?request=$requestId").header("Authorization", "Bearer browser").exchange().expectStatus().isBadRequest
        client.get().uri("/oauth/consent?request=$requestId").cookie(cookie.name, cookie.value).exchange().expectStatus().isUnauthorized
        val view = client.get().uri("/oauth/consent?request=$requestId").cookie(cookie.name, cookie.value).header("Authorization", "Bearer browser")
            .exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!
        val csrf = mapper.readTree(view).path("csrfToken").asText()
        client.get().uri("/oauth/consent?request=$requestId").cookie(cookie.name,cookie.value).header("Authorization","Bearer second").exchange().expectStatus().isBadRequest
        val body = """{"request":"$requestId","csrfToken":"$csrf","approve":true}"""
        client.post().uri("/oauth/consent").cookie(cookie.name,cookie.value).header("Authorization","Bearer browser")
            .header("Origin","https://attacker.test").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isForbidden
        client.post().uri("/oauth/consent").cookie(cookie.name,cookie.value).header("Authorization","Bearer browser")
            .header("Origin","http://127.0.0.1:3100").contentType(MediaType.APPLICATION_JSON).bodyValue(body.replace(csrf,"wrong")).exchange().expectStatus().isBadRequest
        client.post().uri("/oauth/consent").cookie(cookie.name,cookie.value).header("Authorization","Bearer browser")
            .header("Origin","http://127.0.0.1:3100").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body.replace("\"approve\":true", "\"organizationId\":\"${OrganizationId.generate()}\",\"approve\":true")).exchange().expectStatus().isBadRequest
        coEvery { oauth.authorize(any()) } returns OAuthOutcome.Success(OAuthAuthorizationCode("issued-code"))
        val approved = client.post().uri("/oauth/consent").cookie(cookie.name,cookie.value).header("Authorization","Bearer browser")
            .header("Origin","http://127.0.0.1:3100").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!
        assertThat(approved).contains("issued-code", "iss=", "state=client-state").doesNotContain("access_token", "refresh_token")
        client.post().uri("/oauth/consent").cookie(cookie.name,cookie.value).header("Authorization","Bearer browser")
            .header("Origin","http://127.0.0.1:3100").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isBadRequest
        coVerify(exactly=1) { oauth.authorize(any()) }
    }

    @Test fun `connections expose when each connection was last used`() {
        val now = clock.instant()
        val used = kotlin.uuid.Uuid.generateV7()
        coEvery { oauth.listConnections(PresentedCredential("browser"), 20, null) } returns OAuthOutcome.Success(OAuthConnectionPage(listOf(
            OAuthConnection(used, "https://claude.ai/oauth/claude-code-client-metadata", "Claude", "http://127.0.0.1:11077/mcp", now.minusSeconds(86400), now.plusSeconds(86400 * 29), null, now.minusSeconds(120)),
            OAuthConnection(kotlin.uuid.Uuid.generateV7(), "monomer-native", "Monomer CLI", "http://127.0.0.1:11077/v2/workspaces", now.minusSeconds(60), now.plusSeconds(86400 * 30), null, null),
        ), null))
        val body = client.get().uri("/oauth/connections").header("Authorization", "Bearer browser")
            .exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!
        val connections = mapper.readTree(body).path("connections")
        assertThat(connections[0].path("id").asText()).isEqualTo(used.toString())
        assertThat(connections[0].path("lastUsedAt").asText()).isEqualTo(now.minusSeconds(120).toString())
        assertThat(connections[1].has("lastUsedAt")).isTrue()
        assertThat(connections[1].path("lastUsedAt").isNull).isTrue()
    }

    @Test fun `connection list uses default and explicit bounded page arguments`() {
        val credential = PresentedCredential("browser")
        client.get().uri("/oauth/connections").header("Authorization", "Bearer browser")
            .exchange().expectStatus().isOk.expectBody().jsonPath("$.nextCursor").isEmpty
        coVerify(exactly = 1) { oauth.listConnections(credential, 20, null) }
        coEvery { oauth.listConnections(credential, 100, "opaque-next") } returns OAuthOutcome.Success(OAuthConnectionPage(emptyList(), "following-page"))
        client.get().uri("/oauth/connections?limit=100&cursor=opaque-next").header("Authorization", "Bearer browser")
            .exchange().expectStatus().isOk.expectBody().jsonPath("$.nextCursor").isEqualTo("following-page")
        coVerify(exactly = 1) { oauth.listConnections(credential, 100, "opaque-next") }
    }

    @Test fun `connection list rejects invalid and duplicated pagination inputs`() {
        listOf("limit=0", "limit=101", "limit=-1", "limit=one", "limit=", "limit=1&limit=2", "cursor=a&cursor=b", "unknown=1").forEach { query ->
            client.get().uri("/oauth/connections?$query").header("Authorization", "Bearer browser")
                .exchange().expectStatus().isBadRequest.expectBody().jsonPath("$.error").isEqualTo("invalid_request")
        }
        coVerify(exactly = 0) { oauth.listConnections(any(), any(), any()) }
        coEvery { oauth.listConnections(PresentedCredential("browser"), 20, "invalid") } returns OAuthOutcome.Failure(OAuthError.INVALID_REQUEST)
        client.get().uri("/oauth/connections?cursor=invalid").header("Authorization", "Bearer browser")
            .exchange().expectStatus().isBadRequest.expectBody().jsonPath("$.error").isEqualTo("invalid_request")
        client.get().uri("/oauth/connections").exchange().expectStatus().isUnauthorized
    }

    @Test fun `consent reports the signed-in user's active connections to the same app`() {
        val (cookie, requestId) = startAuthorization()
        val now = clock.instant()
        coEvery { oauth.connectionSummary(PresentedCredential("browser"), "monomer-native") } returns
            OAuthOutcome.Success(OAuthConnectionSummary(2, now.minusSeconds(86400 * 2)))
        client.get().uri("/oauth/consent?request=$requestId").cookie(cookie.name, cookie.value).header("Authorization", "Bearer browser")
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.existingConnectionCount").isEqualTo(2)
            .jsonPath("$.latestConnectionIssuedAt").isEqualTo(now.minusSeconds(86400 * 2).toString())
        val (otherCookie, otherRequest) = startAuthorization()
        coEvery { oauth.connectionSummary(PresentedCredential("second"), "monomer-native") } returns OAuthOutcome.Success(OAuthConnectionSummary(0, null))
        client.get().uri("/oauth/consent?request=$otherRequest").cookie(otherCookie.name, otherCookie.value).header("Authorization", "Bearer second")
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.existingConnectionCount").isEqualTo(0)
            .jsonPath("$.latestConnectionIssuedAt").isEmpty
    }

    @Test fun `switch account releases the request to the next account in the same browser with a rotated csrf token`() {
        val (cookie, requestId) = startAuthorization()
        val first = mapper.readTree(client.get().uri("/oauth/consent?request=$requestId").cookie(cookie.name, cookie.value).header("Authorization", "Bearer browser")
            .exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!).path("csrfToken").asText()
        client.get().uri("/oauth/consent?request=$requestId").cookie(cookie.name, cookie.value).header("Authorization", "Bearer second")
            .exchange().expectStatus().isBadRequest
        fun release(token: String, auth: String, origin: String, withCookie: Boolean = true) = client.post().uri("/oauth/consent/release")
            .apply { if (withCookie) cookie(cookie.name, cookie.value) }.header("Authorization", auth).header("Origin", origin)
            .contentType(MediaType.APPLICATION_JSON).bodyValue("""{"request":"$requestId","csrfToken":"$token"}""").exchange()
        release(first, "Bearer browser", "https://attacker.test").expectStatus().isForbidden
        release(first, "Bearer second", "http://127.0.0.1:3100").expectStatus().isBadRequest
        release("x".repeat(43), "Bearer browser", "http://127.0.0.1:3100").expectStatus().isBadRequest
        release(first, "Bearer browser", "http://127.0.0.1:3100", withCookie = false).expectStatus().isBadRequest
        release(first, "Bearer browser", "http://127.0.0.1:3100").expectStatus().isNoContent
        release(first, "Bearer browser", "http://127.0.0.1:3100").expectStatus().isBadRequest
        val second = mapper.readTree(client.get().uri("/oauth/consent?request=$requestId").cookie(cookie.name, cookie.value).header("Authorization", "Bearer second")
            .exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!).path("csrfToken").asText()
        assertThat(second).isNotEqualTo(first)
        client.post().uri("/oauth/consent").cookie(cookie.name, cookie.value).header("Authorization", "Bearer second")
            .header("Origin", "http://127.0.0.1:3100").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"request":"$requestId","csrfToken":"$first","approve":true}""").exchange().expectStatus().isBadRequest
        client.get().uri("/oauth/consent?request=$requestId").cookie(cookie.name, cookie.value).header("Authorization", "Bearer browser")
            .exchange().expectStatus().isBadRequest
    }

    private fun startAuthorization(): Pair<org.springframework.http.ResponseCookie, String> {
        val started = client.get().uri { uri -> uri.path("/oauth/authorize").queryParam("response_type", "code")
            .queryParam("client_id", "monomer-native").queryParam("redirect_uri", "http://127.0.0.1:5566/callback")
            .queryParam("resource", "http://127.0.0.1:11077/v2/workspaces").queryParam("scope", "agent.workspace")
            .queryParam("code_challenge", "A".repeat(43)).queryParam("code_challenge_method", "S256").build() }
            .exchange().expectStatus().isFound.expectBody().returnResult()
        return started.responseCookies.getFirst(PendingAuthenticationFixture.COOKIE)!! to started.responseHeaders.location!!.rawQuery.substringAfter("request=")
    }

    @Test fun `token form rejects duplicates and unsupported flows and marks secrets no store`() {
        client.post().uri("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).bodyValue("grant_type=client_credentials&client_id=x&resource=x")
            .exchange().expectStatus().isBadRequest.expectBody().jsonPath("$.error").isEqualTo("unsupported_grant_type")
        client.post().uri("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).bodyValue("grant_type=refresh_token&client_id=x&client_id=y&resource=x")
            .exchange().expectStatus().isBadRequest.expectBody().jsonPath("$.error").isEqualTo("invalid_request")
        coEvery { oauth.refresh(any()) } returns OAuthOutcome.Success(OAuthTokenResponse("test-access", "test-refresh", 900, "agent.workspace"))
        client.post().uri("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).bodyValue("grant_type=refresh_token&client_id=x&resource=x&refresh_token=test")
            .exchange().expectStatus().isOk.expectHeader().valueEquals("Cache-Control", "no-store").expectBody().jsonPath("$.token_type").isEqualTo("Bearer")
    }

    @Test fun `token endpoint rejects JWT bearer grant advertised by Claude`() {
        val form = "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&client_id=https://claude.ai/oauth/mcp-oauth-client-metadata&resource=http://127.0.0.1:11077/mcp"
        client.post().uri("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue(form)
            .exchange().expectStatus().isBadRequest.expectBody()
            .jsonPath("$.error").isEqualTo("unsupported_grant_type")
            .jsonPath("$.access_token").doesNotExist()
            .jsonPath("$.refresh_token").doesNotExist()
        client.post().uri("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue("$form&assertion=test-assertion")
            .exchange().expectStatus().isBadRequest.expectBody()
            .jsonPath("$.error").isEqualTo("invalid_request")
            .jsonPath("$.access_token").doesNotExist()
            .jsonPath("$.refresh_token").doesNotExist()
        coVerify(exactly = 0) { oauth.exchangeCode(any()) }
        coVerify(exactly = 0) { oauth.refresh(any()) }
    }

    @Test fun `storage errors return sanitized OAuth error`() {
        coEvery { oauth.refresh(any()) } throws IllegalStateException("secret-value-in-database-driver")
        client.post().uri("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).bodyValue("grant_type=refresh_token&client_id=x&resource=x&refresh_token=test")
            .exchange().expectStatus().isEqualTo(503).expectBody().jsonPath("$.error").isEqualTo("temporarily_unavailable")
    }
}
