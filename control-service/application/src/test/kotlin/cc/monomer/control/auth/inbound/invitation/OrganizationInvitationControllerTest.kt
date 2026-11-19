package cc.monomer.control.auth.inbound.invitation

import cc.monomer.identity.application.OpaqueCredentials
import cc.monomer.control.auth.PendingAuthenticationFixture
import cc.monomer.control.auth.inbound.oauth.JwtAuthentication
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.application.organization.OrganizationInvitationService
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.OrganizationMembershipInvitation
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.InvitationPreview
import cc.monomer.identity.domain.model.InvitationWorkspaceAccess
import cc.monomer.identity.domain.model.InvitationWorkspaceGrant
import cc.monomer.identity.domain.model.User
import cc.monomer.identity.domain.model.UserCredentialProvenance
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebFilter

class OrganizationInvitationControllerTest {
    @Test
    fun `HTTP exchange binds proof to cookie and full organization preserves Browser acceptance continuation`() {
        val service = mockk<OrganizationInvitationService>()
        val raw = OpaqueCredentials.generate()
        val proof = OrganizationInvitationService.Proof(Uuid.generateV7(), OrganizationId.generate(), OpaqueCredentials.hash(raw), Instant.now().plusSeconds(1800))
        val browser = UserCredentialProvenance.BrowserSession(UserId.generate(), SessionId.generate())
        coEvery { service.exchange(raw) } returns proof
        coEvery { service.consume(proof, null, true) } returns OrganizationInvitationService.Result(OrganizationInvitationService.Outcome.AUTHENTICATION_REQUIRED, null, null, null)
        coEvery { service.consume(proof, browser, true) } returnsMany listOf(
            OrganizationInvitationService.Result(OrganizationInvitationService.Outcome.MEMBER_LIMIT_REACHED, null, null, null),
            OrganizationInvitationService.Result(OrganizationInvitationService.Outcome.ACCEPTED, null, null, null),
        )
        val client = WebTestClient.bindToController(OrganizationInvitationController(service, Clock.systemUTC(), PendingAuthenticationFixture(Clock.systemUTC()).invitations))
            .webFilter<WebTestClient.ControllerSpec>(WebFilter { exchange, chain ->
                val auth = when (exchange.request.headers.getFirst("Authorization")) {
                    "Bearer browser-test" -> JwtAuthentication(browser, PresentedCredential("browser-test"))
                    else -> null
                }
                if (auth == null) chain.filter(exchange) else chain.filter(exchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
            }).build()
        val exchange = client.post().uri("/auth/invitations/exchange").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"token":"$raw"}""").exchange().expectStatus().isOk
            .expectHeader().valueEquals("Cache-Control", "no-store").expectBody(String::class.java).returnResult()
        val body = checkNotNull(exchange.responseBody)
        assertThat(body).doesNotContain(raw, proof.expectedTokenHash)
        val continuationId = Json.parseToJsonElement(body).jsonObject.getValue("continuationId").jsonPrimitive.content
        val cookie = checkNotNull(exchange.responseCookies.getFirst(PendingAuthenticationFixture.COOKIE))
        val request = """{"continuationId":"$continuationId"}"""
        client.post().uri("/auth/invitations/accept").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer browser-test")
            .bodyValue(request).exchange().expectStatus().isOk.expectBody().jsonPath("$.outcome").isEqualTo("UNAVAILABLE")
        client.post().uri("/auth/invitations/accept").cookie(cookie.name, cookie.value).header("Authorization", "Bearer agent-test")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange().expectStatus().isOk.expectBody().jsonPath("$.outcome").isEqualTo("AUTHENTICATION_REQUIRED")
        client.post().uri("/auth/invitations/accept").cookie(cookie.name, cookie.value).header("Authorization", "Bearer browser-test")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange().expectStatus().isOk.expectBody().jsonPath("$.outcome").isEqualTo("MEMBER_LIMIT_REACHED")
        client.post().uri("/auth/invitations/accept").cookie(cookie.name, cookie.value).header("Authorization", "Bearer browser-test")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange().expectStatus().isOk.expectBody().jsonPath("$.outcome").isEqualTo("ACCEPTED")
        client.post().uri("/auth/invitations/accept").cookie(cookie.name, cookie.value).header("Authorization", "Bearer browser-test")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange().expectStatus().isOk.expectBody().jsonPath("$.outcome").isEqualTo("UNAVAILABLE")
        coVerify(exactly = 2) { service.consume(proof, browser, true) }
        coVerify(exactly = 1) { service.consume(proof, null, true) }
    }

    @Test
    fun `email mismatch responses expose invited email without organization metadata`() {
        val service = mockk<OrganizationInvitationService>()
        val now = Instant.now()
        val raw = OpaqueCredentials.generate()
        val organizationId = OrganizationId.generate()
        val invitationId = Uuid.generateV7()
        val inviter = User(UserId.generate(), "admin@example.test", "Private inviter")
        val invitation = OrganizationMembershipInvitation(invitationId, organizationId, "Invited@Example.test", "invited@example.test",
            OpaqueCredentials.hash(raw), inviter.userId, now, now.plusSeconds(604800), now, now, emptyList())
        val preview = InvitationPreview(invitation, "Private organization", inviter, emptyList())
        val proof = OrganizationInvitationService.Proof(invitationId, organizationId, invitation.tokenHash, now.plusSeconds(1800))
        val browser = UserCredentialProvenance.BrowserSession(UserId.generate(), SessionId.generate())
        val mismatch = OrganizationInvitationService.Result(OrganizationInvitationService.Outcome.EMAIL_MISMATCH, preview, null, null)
        coEvery { service.exchange(raw) } returns proof
        coEvery { service.resolve(proof, browser) } returns mismatch
        coEvery { service.consume(proof, browser, true) } returns mismatch
        coEvery { service.consume(proof, browser, false) } returns mismatch
        val client = WebTestClient.bindToController(OrganizationInvitationController(service, Clock.systemUTC(), PendingAuthenticationFixture(Clock.systemUTC()).invitations))
            .webFilter<WebTestClient.ControllerSpec>(WebFilter { exchange, chain ->
                chain.filter(exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                    JwtAuthentication(browser, PresentedCredential("browser-test")),
                ))
            }).build()
        val exchange = client.post().uri("/auth/invitations/exchange").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"token":"$raw"}""").exchange().expectStatus().isOk.expectBody(String::class.java).returnResult()
        val continuationId = Json.parseToJsonElement(checkNotNull(exchange.responseBody)).jsonObject.getValue("continuationId").jsonPrimitive.content
        val cookie = checkNotNull(exchange.responseCookies.getFirst(PendingAuthenticationFixture.COOKIE))
        for (action in listOf("resolve", "accept", "decline")) {
            client.post().uri("/auth/invitations/$action").cookie(cookie.name, cookie.value)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("""{"continuationId":"$continuationId"}""")
                .exchange().expectStatus().isOk.expectBody()
                .jsonPath("$.outcome").isEqualTo("EMAIL_MISMATCH")
                .jsonPath("$.deliveryEmail").isEqualTo(invitation.deliveryEmail)
                .jsonPath("$.organizationId").isEmpty
                .jsonPath("$.organizationDisplayName").isEmpty
                .jsonPath("$.invitedByDisplayName").isEmpty
                .jsonPath("$.expiresAt").isEmpty
        }
        coVerify(exactly = 1) { service.resolve(proof, browser) }
        coVerify(exactly = 1) { service.consume(proof, browser, true) }
        coVerify(exactly = 1) { service.consume(proof, browser, false) }
    }

    @Test
    fun `resolve reports existing membership and offered access, and accept reports granted and skipped access`() {
        val service = mockk<OrganizationInvitationService>()
        val now = Instant.now()
        val raw = OpaqueCredentials.generate()
        val organizationId = OrganizationId.generate()
        val invitationId = Uuid.generateV7()
        val revenue = WorkspaceId.generate()
        val removed = WorkspaceId.generate()
        val grants = listOf(InvitationWorkspaceGrant(revenue, WorkspaceRole.EXPLORER), InvitationWorkspaceGrant(removed, WorkspaceRole.EDITOR))
        val invitation = OrganizationMembershipInvitation(invitationId, organizationId, "sam@example.test", "sam@example.test",
            OpaqueCredentials.hash(raw), null, now, now.plusSeconds(604800), now, now, grants)
        val offered = listOf(InvitationWorkspaceAccess(revenue, "Revenue Analytics", WorkspaceRole.EXPLORER), InvitationWorkspaceAccess(removed, null, WorkspaceRole.EDITOR))
        val preview = InvitationPreview(invitation, "Northstar Labs", null, offered)
        val proof = OrganizationInvitationService.Proof(invitationId, organizationId, invitation.tokenHash, now.plusSeconds(1800))
        val browser = UserCredentialProvenance.BrowserSession(UserId.generate(), SessionId.generate())
        coEvery { service.exchange(raw) } returns proof
        coEvery { service.resolve(proof, browser) } returnsMany listOf(
            OrganizationInvitationService.Result(OrganizationInvitationService.Outcome.ALREADY_MEMBER, preview, null, null),
            OrganizationInvitationService.Result(OrganizationInvitationService.Outcome.AVAILABLE, preview, null, null),
        )
        coEvery { service.consume(proof, browser, true) } returns OrganizationInvitationService.Result(OrganizationInvitationService.Outcome.ACCEPTED, preview, null,
            OrganizationInvitationService.AcceptedWorkspaceAccess(listOf(offered[0]), listOf(offered[1])))
        val client = WebTestClient.bindToController(OrganizationInvitationController(service, Clock.systemUTC(), PendingAuthenticationFixture(Clock.systemUTC()).invitations))
            .webFilter<WebTestClient.ControllerSpec>(WebFilter { exchange, chain ->
                chain.filter(exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                    JwtAuthentication(browser, PresentedCredential("browser-test")),
                ))
            }).build()
        val exchange = client.post().uri("/auth/invitations/exchange").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"token":"$raw"}""").exchange().expectStatus().isOk.expectBody(String::class.java).returnResult()
        val continuationId = Json.parseToJsonElement(checkNotNull(exchange.responseBody)).jsonObject.getValue("continuationId").jsonPrimitive.content
        val cookie = checkNotNull(exchange.responseCookies.getFirst(PendingAuthenticationFixture.COOKIE))
        val request = """{"continuationId":"$continuationId"}"""
        client.post().uri("/auth/invitations/resolve").cookie(cookie.name, cookie.value).contentType(MediaType.APPLICATION_JSON).bodyValue(request)
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.outcome").isEqualTo("ALREADY_MEMBER")
            .jsonPath("$.organizationDisplayName").isEqualTo("Northstar Labs")
        client.post().uri("/auth/invitations/resolve").cookie(cookie.name, cookie.value).contentType(MediaType.APPLICATION_JSON).bodyValue(request)
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.outcome").isEqualTo("AVAILABLE")
            .jsonPath("$.workspaceAccess.length()").isEqualTo(1)
            .jsonPath("$.workspaceAccess[0].workspaceDisplayName").isEqualTo("Revenue Analytics")
            .jsonPath("$.workspaceAccess[0].role").isEqualTo("EXPLORER")
        client.post().uri("/auth/invitations/accept").cookie(cookie.name, cookie.value).contentType(MediaType.APPLICATION_JSON).bodyValue(request)
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.outcome").isEqualTo("ACCEPTED")
            .jsonPath("$.workspaceAccess[0].workspaceId").isEqualTo(revenue.toString())
            .jsonPath("$.skippedWorkspaceAccess[0].workspaceId").isEqualTo(removed.toString())
            .jsonPath("$.skippedWorkspaceAccess[0].role").isEqualTo("EDITOR")
        client.post().uri("/auth/invitations/resolve").cookie(cookie.name, cookie.value).contentType(MediaType.APPLICATION_JSON).bodyValue(request)
            .exchange().expectStatus().isOk.expectBody().jsonPath("$.outcome").isEqualTo("UNAVAILABLE")
        coVerify(exactly = 2) { service.resolve(proof, browser) }
    }
}
