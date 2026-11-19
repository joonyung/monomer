package cc.monomer.control.auth

import cc.monomer.control.auth.inbound.oauth.*
import cc.monomer.control.auth.inbound.session.*
import cc.monomer.identity.application.login.LoginUseCase
import cc.monomer.identity.application.session.*
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.model.AuthenticationAssertion
import cc.monomer.identity.domain.model.IssuedToken
import io.mockk.*
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.SelfSignedCertificate
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.reactor.mono
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.server.WebFilterExchange
import org.springframework.web.cors.reactive.CorsWebFilter
import org.springframework.web.server.WebHandler
import org.springframework.web.server.adapter.WebHttpHandlerBuilder
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "MONOMER_AUTH_PLAYWRIGHT_PACKAGE", matches = ".+")
class SplitHttpsBrowserAuthTest {
    @Test
    fun `browser crosses HTTPS hosts using production callback cookie CORS and session handlers`() {
        val certificate = SelfSignedCertificate("*.monomer.test")
        val ssl = SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey()).build()
        val web = HttpServer.create().host("127.0.0.1").port(0).secure { it.sslContext(ssl) }
            .handle { _, response -> response.header("Content-Type", "text/html").sendString(Mono.just("<!doctype html><title>Auth transport fixture</title>")) }
            .bindNow()
        val webOrigin = "https://app.monomer.test:${web.port()}"
        val login = mockk<LoginUseCase>()
        val start = mockk<StartSessionUseCase>()
        val assertionFactory = mockk<GoogleAuthenticationAssertionFactory>()
        val assertion = mockk<AuthenticationAssertion>()
        val loginResult = mockk<LoginUseCase.Response>(relaxed = true)
        val principal = mockk<OidcUser>()
        every { assertionFactory.create(principal) } returns assertion
        coEvery { login.login(assertion, LoginUseCase.FirstSignIn.CREATE_PERSONAL_ORGANIZATION) } returns loginResult
        coEvery { start.start(any(), assertion) } returns StartSessionUseCase.Started(SessionId.generate(), "transport-fixture-refresh")
        val cookieFactory = RefreshCookieFactory(AuthJwtProperties("fixture", "fixture", 900, 604800, "", ""), RefreshCookieProperties(true))
        val success = OAuth2LoginSuccessHandler(login, assertionFactory, OAuthProperties("$webOrigin/auth/callback"), start, cookieFactory)
        val refresh = mockk<RefreshSessionUseCase>()
        val logout = mockk<LogoutUseCase>()
        coEvery { refresh.refresh("transport-fixture-refresh") } returns RefreshSessionUseCase.Result.Rotated(
            IssuedToken("transport-fixture-access", Instant.now().plusSeconds(900)), "transport-fixture-rotated",
        )
        coEvery { logout.logout("transport-fixture-rotated") } just Runs
        val session = RefreshTokenController(refresh, logout, mockk<LogoutAllSessionsUseCase>(), cookieFactory)
        val handler = WebHandler { exchange ->
            when (exchange.request.path.value()) {
                "/login/oauth2/code/google" -> success.onAuthenticationSuccess(
                    WebFilterExchange(exchange) { Mono.empty() }, TestingAuthenticationToken(principal, ""),
                )
                "/auth/refresh" -> mono {
                    val response = session.refresh(exchange)
                    exchange.response.statusCode = response.statusCode
                    response.body
                }.flatMap { body ->
                    exchange.response.headers.set("Content-Type", "application/json")
                    val bytes = """{"access_token":"${body.accessToken}","expires_at":"${body.expiresAt}"}""".toByteArray()
                    exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(bytes)))
                }.then()
                "/auth/logout" -> mono { exchange.response.statusCode = session.logout(exchange).statusCode }.then()
                else -> { exchange.response.statusCode = HttpStatus.NOT_FOUND; exchange.response.setComplete() }
            }
        }
        val http = WebHttpHandlerBuilder.webHandler(handler)
            .filter(CorsWebFilter(SecurityConfiguration(webOrigin).corsConfigurationSource())).build()
        val api = HttpServer.create().host("127.0.0.1").port(0).secure { it.sslContext(ssl) }
            .handle(ReactorHttpHandlerAdapter(http)).bindNow()
        val log = Files.createTempFile("monomer-split-https-auth-", ".log")
        try {
            val script = sequenceOf(Path.of("../testkit/split-https-auth/browser.mjs"), Path.of("../../testkit/split-https-auth/browser.mjs"))
                .first { Files.exists(it) }.toAbsolutePath()
            val process = ProcessBuilder(System.getenv("MONOMER_AUTH_NODE"), script.toString(), webOrigin,
                "https://api.monomer.test:${api.port()}", "https://untrusted.monomer.test:${web.port()}")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); error("HTTPS browser fixture timed out") }
            val output = Files.readString(log)
            assertEquals(0, process.exitValue(), output)
            assertTrue(output.contains("PASS:"), output)
            coVerify(exactly = 1) { refresh.refresh(any()) }
            coVerify(exactly = 1) { logout.logout(any()) }
            println(output)
        } finally {
            api.disposeNow(Duration.ofSeconds(10)); web.disposeNow(Duration.ofSeconds(10)); certificate.delete(); Files.deleteIfExists(log)
        }
    }
}
