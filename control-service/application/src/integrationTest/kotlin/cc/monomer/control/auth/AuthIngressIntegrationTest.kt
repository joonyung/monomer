package cc.monomer.control.auth

import cc.monomer.control.ControlApplication
import cc.monomer.control.EphemeralControlConfiguration
import cc.monomer.control.EphemeralControlResources
import cc.monomer.identity.domain.id.LoginIdentityId
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.AuthenticationProtocol
import cc.monomer.identity.domain.model.EmailEvidence
import cc.monomer.identity.domain.model.Session
import cc.monomer.identity.domain.model.UserCredentialProvenance
import cc.monomer.identity.domain.port.SessionRepository
import cc.monomer.identity.outbound.jwt.JwtIssuer
import cc.monomer.identity.outbound.jwt.JwtVerifier
import java.time.Duration
import java.time.Instant
import org.mockito.Mockito.doReturn
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.annotation.DirtiesContext
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import javax.sql.DataSource

@ActiveProfiles("local")
@Import(EphemeralControlConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [ControlApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.health.db.enabled=false",
        "spring.graphql.schema.locations=classpath*:graphql/**/",
        "monomer.credentials.aes-gcm.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "monomer.credentials.aes-gcm.key-ref=integration-test",
        "monomer.control.execution-runtime.credential-vault.keys-base64.local-primary=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    ],
)
class AuthIngressIntegrationTest @Autowired constructor(
    private val environment: Environment,
    private val jwtIssuer: JwtIssuer,
    private val jwtVerifier: JwtVerifier,
    private val resources: EphemeralControlResources,
    private val dataSource: DataSource,
) {
    @MockitoBean
    private lateinit var sessionRepository: SessionRepository

    @Test
    fun `auth context uses isolated database and ports`() {
        resources.verifyIsolation(dataSource, environment)
    }

    @Test
    fun `JWKS endpoint publishes only the active public RSA signing key`() {
        client().get().uri("/.well-known/jwks.json")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.keys").isArray
            .jsonPath("$.keys[0].kty").isEqualTo("RSA")
            .jsonPath("$.keys[0].use").isEqualTo("sig")
            .jsonPath("$.keys[0].alg").isEqualTo("RS256")
            .jsonPath("$.keys[0].kid").exists()
            .jsonPath("$.keys[0].n").exists()
            .jsonPath("$.keys[0].e").exists()
            .jsonPath("$.keys[0].d").doesNotExist()
    }

    @Test
    fun `issuer and verifier retain the browser session token contract`() {
        val userId = UserId.generate()
        val sessionId = SessionId.generate()
        val token = jwtIssuer.issueBrowserSession(userId, sessionId)

        val outcome = jwtVerifier.verify(token.serialized)

        assertThat(outcome).isInstanceOf(JwtVerifier.Outcome.Valid::class.java)
        assertThat((outcome as JwtVerifier.Outcome.Valid).credential)
            .isEqualTo(UserCredentialProvenance.BrowserSession(userId, sessionId))
    }

    @Test
    fun `refresh and logout preserve their unauthenticated cookie boundary`() {
        client().post().uri("/auth/refresh")
            .exchange()
            .expectStatus().isUnauthorized

        val logout = client().post().uri("/auth/logout")
            .exchange()
            .expectStatus().isNoContent
            .returnResult(Void::class.java)

        assertThat(logout.responseCookies.getFirst("refresh_token")!!.maxAge).isEqualTo(Duration.ZERO)

        val logoutAll = client().post().uri("/auth/logout-all")
            .exchange()
            .expectStatus().isUnauthorized
            .returnResult(Void::class.java)

        assertThat(logoutAll.responseCookies.getFirst("refresh_token")!!.maxAge).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `GraphQL remains protected when no accepted bearer credential is supplied`() {
        client().post().uri("/v2/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"query\":\"{ me { user { id } } }\"}")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `GraphQL is served only from the canonical v2 path`() {
        val sessionToken = currentSessionToken()
        val requestBody = "{\"query\":\"{ __typename }\"}"

        client().post().uri("/v2/graphql")
            .header("Authorization", "Bearer ${sessionToken.serialized}")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.data.__typename").isEqualTo("Query")

        client().post().uri("/graphql")
            .header("Authorization", "Bearer ${sessionToken.serialized}")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `the relocated semantic transport paths are not reachable with a user session`() {
        val sessionToken = currentSessionToken()

        // The predecessor served these behind an internal API key held only by the dashboard BFF.
        // In one process their only callers are in-process beans, so no credential may reach them.
        listOf(
            "/v2/semantic/metrics",
            "/v2/semantic/semantic-models",
            "/v2/semantic/time-axis-bindings",
            "/v2/semantic/health/graph",
        ).forEach { path ->
            client().get().uri(path)
                .header("Authorization", "Bearer ${sessionToken.serialized}")
                .header("X-Workspace-Id", "019e7000-0000-7000-8000-0000000000ff")
                .exchange()
                .expectStatus().isForbidden
        }

        client().post().uri("/v2/semantic/query/render-sql")
            .header("Authorization", "Bearer ${sessionToken.serialized}")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"workspaceId\":\"019e7000-0000-7000-8000-0000000000ff\"}")
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `retired introspection endpoint denies internal keys and Browser sessions`() {
        val sessionToken = currentSessionToken()
        client().post().uri("/internal/agent/oauth/introspect")
            .header("X-Internal-Api-Key", "retired-introspection-key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isUnauthorized

        client().post().uri("/internal/agent/oauth/introspect")
            .header("X-Internal-Api-Key", "retired-introspection-key")
            .header("Authorization", "Bearer ${sessionToken.serialized}")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `signed JWT without a current session cannot reach GraphQL`() {
        val token = jwtIssuer.issueBrowserSession(UserId.generate(), SessionId.generate())
        client().post().uri("/v2/graphql")
            .headers { it.setBearerAuth(token.serialized) }
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"query\":\"{ me { user { id } } }\"}")
            .exchange()
            .expectStatus().isUnauthorized
    }

    private fun currentSessionToken(): cc.monomer.identity.domain.model.IssuedToken {
        val userId = UserId.generate()
        val sessionId = SessionId.generate()
        val now = Instant.now()
        val session = Session(
            sessionId, userId, LoginIdentityId.generate(), AuthenticationProtocol.OIDC,
            "builtin/google", "https://accounts.google.com", "developer@example.com", true,
            EmailEvidence.AUTHORITATIVE, null, null, emptyList(), null,
            now, now, now.plusSeconds(3600), null, null,
        )
        doReturn(session).`when`(sessionRepository).findById(sessionId)
        return jwtIssuer.issueBrowserSession(userId, sessionId)
    }

    private fun client(): WebTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:${environment.getRequiredProperty("local.server.port")}")
            .build()
}
