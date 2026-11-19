package cc.monomer.control.auth.inbound.operationsoauth

import cc.monomer.control.auth.inbound.agentoauth.AgentOAuthProperties
import cc.monomer.identity.application.operations.*

import cc.monomer.control.auth.PendingAuthenticationFixture
import cc.monomer.control.ControlRuntimeProperties
import cc.monomer.control.auth.inbound.oauth.JwtAuthentication
import cc.monomer.control.publicapi.ControlPublicApiProperties
import cc.monomer.identity.api.*
import cc.monomer.identity.application.me.MeUseCase
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

class OperationsOAuthControllerTest {
    private val oauth = mockk<OperationsOAuthService>()
    private val me = mockk<MeUseCase>()
    private val runtime = mockk<ControlRuntimeProperties>()
    private val browser = UserCredentialProvenance.BrowserSession(UserId.generate(), SessionId.generate())
    private val secondBrowser = UserCredentialProvenance.BrowserSession(UserId.generate(), SessionId.generate())
    private val mapper = ObjectMapper()
    private val clock = Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC)
    private val pending = PendingAuthenticationFixture(clock)
    private val client: WebTestClient
    init {
        every { oauth.validate(any()) } just Runs
        coEvery { oauth.browserUser(PresentedCredential("browser")) } returns browser.userId.toString()
        coEvery { oauth.browserUser(PresentedCredential("second")) } returns secondBrowser.userId.toString()
        client = WebTestClient.bindToController(OperationsOAuthController(oauth,pending.requests,pending.browser,
            ControlPublicApiProperties(URI("http://127.0.0.1:3100")),
            AgentOAuthProperties("http://127.0.0.1:11077", "http://127.0.0.1:11077/mcp"),me))
            .controllerAdvice(OperationsOAuthProblemHandler())
            .webFilter<WebTestClient.ControllerSpec>(WebFilter { exchange, chain ->
                val auth = when (exchange.request.headers.getFirst("Authorization")) {
                    "Bearer browser" -> JwtAuthentication(browser, PresentedCredential("browser"))
                    "Bearer second" -> JwtAuthentication(secondBrowser, PresentedCredential("second"))
                    else -> null
                }
                if (auth == null) chain.filter(exchange) else chain.filter(exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
            }).build()
    }

    @Test fun `consent is bound to browser cookie current user csrf and is consumed once`() {
        val started = client.get().uri { uri -> uri.path("/ops/oauth/authorize").queryParam("response_type", "code")
            .queryParam("client_id", "monomer-operations-local").queryParam("redirect_uri", "http://127.0.0.1:5566/oauth/callback")
            .queryParam("resource", "http://127.0.0.1:11077/ops/v1").queryParam("scope", "ops.read")
            .queryParam("code_challenge", "A".repeat(43)).queryParam("code_challenge_method", "S256").queryParam("state", "s".repeat(43)).build() }
            .exchange().expectStatus().isFound.expectHeader().valueEquals("Cache-Control", "no-store").expectBody().returnResult()
        val cookie = started.responseCookies.getFirst(PendingAuthenticationFixture.COOKIE)!!
        val requestId = started.responseHeaders.location!!.rawQuery.substringAfter("request=")
        client.get().uri("/ops/oauth/consent?request=$requestId").header("Authorization", "Bearer browser").exchange().expectStatus().isBadRequest
        client.get().uri("/ops/oauth/consent?request=$requestId").cookie(cookie.name, cookie.value).exchange().expectStatus().isUnauthorized
        val view = client.get().uri("/ops/oauth/consent?request=$requestId").cookie(cookie.name, cookie.value).header("Authorization", "Bearer browser")
            .exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!
        val csrf = mapper.readTree(view).path("csrfToken").asText()
        client.get().uri("/ops/oauth/consent?request=$requestId").cookie(cookie.name,cookie.value).header("Authorization","Bearer second").exchange().expectStatus().isBadRequest
        val body = """{"request":"$requestId","csrfToken":"$csrf","approve":true}"""
        client.post().uri("/ops/oauth/consent").cookie(cookie.name,cookie.value).header("Authorization","Bearer browser")
            .header("Origin","https://attacker.test").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isForbidden
        client.post().uri("/ops/oauth/consent").cookie(cookie.name,cookie.value).header("Authorization","Bearer browser")
            .header("Origin","http://127.0.0.1:3100").contentType(MediaType.APPLICATION_JSON).bodyValue(body.replace(csrf,"wrong")).exchange().expectStatus().isBadRequest
        coEvery { oauth.authorize(any(),any()) } returns "issued-code"
        val approved = client.post().uri("/ops/oauth/consent").cookie(cookie.name,cookie.value).header("Authorization","Bearer browser")
            .header("Origin","http://127.0.0.1:3100").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!
        assertThat(approved).contains("issued-code", "iss=", "state=ssss").doesNotContain("access_token", "refresh_token")
        client.post().uri("/ops/oauth/consent").cookie(cookie.name,cookie.value).header("Authorization","Bearer browser")
            .header("Origin","http://127.0.0.1:3100").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isBadRequest
        coVerify(exactly=1) { oauth.authorize(any(),any()) }
    }

    @Test fun `token endpoint rejects refresh duplicate and authorization header`() {
        for (body in listOf("grant_type=refresh_token","grant_type=authorization_code&client_id=x&client_id=y")) {
            client.post().uri("/ops/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).bodyValue(body)
                .exchange().expectStatus().isBadRequest.expectHeader().valueEquals("Cache-Control","no-store")
        }
        client.post().uri("/ops/oauth/token").header("Authorization","Bearer browser").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue("grant_type=authorization_code").exchange().expectStatus().isBadRequest
    }
    @Test fun `token response has fixed lifetime and no refresh and revoke delegates token`() {
        coEvery { oauth.exchange(any(),any(),any()) } returns "ops-token"
        client.post().uri("/ops/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue("grant_type=authorization_code&client_id=monomer-operations-local&redirect_uri=http%3A%2F%2F127.0.0.1%3A5566%2Foauth%2Fcallback&resource=http%3A%2F%2F127.0.0.1%3A11077%2Fops%2Fv1&code=code&code_verifier=${"a".repeat(43)}")
            .exchange().expectStatus().isOk.expectBody().jsonPath("$.access_token").isEqualTo("ops-token")
            .jsonPath("$.expires_in").isEqualTo(3600).jsonPath("$.scope").isEqualTo("ops.read").jsonPath("$.refresh_token").doesNotExist()
        coEvery { oauth.revoke(PresentedCredential("ops-token")) } just Runs
        client.post().uri("/ops/oauth/revoke").contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue("client_id=monomer-operations-local&token=ops-token").exchange().expectStatus().isOk
        coVerify(exactly=1) { oauth.revoke(PresentedCredential("ops-token")) }
    }
    @Test fun `ops read rejects blank malformed and repeated bearer credentials before authentication`() {
        for (authorization in listOf("Bearer ", "Bearer   ", "bearer ops", "Basic b3Bz")) {
            client.get().uri("/ops/v1/me").header("Authorization", authorization).exchange().expectStatus().isUnauthorized
        }
        client.get().uri("/ops/v1/me").header("Authorization", "Bearer ops", "Bearer ops").exchange().expectStatus().isUnauthorized
        coVerify(exactly = 0) { oauth.authenticate(any()) }
    }
    @Test fun `ops read rejects missing ordinary and revoked credentials and non operators`() {
        client.get().uri("/ops/v1/me").exchange().expectStatus().isUnauthorized
        coEvery { oauth.authenticate(any()) } throws OperationsUnauthenticatedException()
        client.get().uri("/ops/v1/me").header("Authorization","Bearer browser").exchange().expectStatus().isUnauthorized
        coEvery { oauth.authenticate(any()) } throws OperationsForbiddenException()
        client.get().uri("/ops/v1/me").header("Authorization","Bearer ops").exchange().expectStatus().isForbidden
    }
}
