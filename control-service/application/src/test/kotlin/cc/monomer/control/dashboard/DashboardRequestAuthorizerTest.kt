@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.WorkspaceRoleAdmissionRequest
import cc.monomer.control.auth.inbound.oauth.JwtAuthentication
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.UserCredentialProvenance
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class DashboardRequestAuthorizerTest {
    private val workspace = WorkspaceId(Uuid.parse("019e0000-0000-7000-8000-0000000000a1"))
    private val userId = UserId(Uuid.parse("019e0000-0000-7000-8000-0000000000b1"))
    private val sessionId = SessionId(Uuid.parse("019e0000-0000-7000-8000-0000000000b2"))

    @Test
    fun `dashboard request requires Explorer role`() = runBlocking {
        val admission = mockk<ControlAdmissionFacade>()
        val authorizer = DashboardRequestAuthorizer(admission)
        val exchange = authenticatedExchange("Bearer dashboard-token")
        coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(mockk {
            io.mockk.every { actor } returns cc.monomer.identity.api.CanonicalPrincipal(cc.monomer.identity.api.PrincipalId(userId.value), cc.monomer.identity.api.PrincipalKind.USER)
            io.mockk.every { organizationId } returns cc.monomer.identity.api.OrganizationId.generate()
            io.mockk.every { credentialSurface } returns cc.monomer.identity.api.CredentialSurface.WEB
            io.mockk.every { agentClientId } returns null
        })

        val access = authorizer.requireWorkspace(exchange, workspace.toString())

        assertEquals(workspace, access.workspaceId)
        assertEquals(userId.value.toString(), access.actorId)
        coVerify(exactly = 1) {
            admission.authorizeWorkspaceRole(match<WorkspaceRoleAdmissionRequest> { request ->
                request.workspaceId == workspace &&
                    request.credential == PresentedCredential("dashboard-token") &&
                    request.expectedOAuthResource == null && request.requiredRole == WorkspaceRole.EXPLORER
            })
        }
    }

    @Test
    fun `Viewer cannot enter any direct Dashboard route`() = runBlocking {
        val admission = mockk<ControlAdmissionFacade>()
        coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Denied(
            cc.monomer.control.ControlAdmissionStage.IDENTITY,
            cc.monomer.control.ControlAdmissionDenial.Identity(cc.monomer.identity.api.IdentityAdmissionDenial.PERMISSION_UNAVAILABLE),
        )
        val authorizer = DashboardRequestAuthorizer(admission)
        val denied = kotlin.test.assertFailsWith<org.springframework.web.server.ResponseStatusException> {
            authorizer.requireWorkspace(authenticatedExchange("Bearer viewer"), workspace.toString())
        }
        assertEquals(403, denied.statusCode.value())
        assertEquals("ROLE_REQUIRED", denied.body.properties?.get("code"))
        coVerify(exactly = 1) { admission.authorizeWorkspaceRole(match { it.requiredRole == WorkspaceRole.EXPLORER }) }
    }

    @Test
    fun `missing workspace or membership remains hidden`() = runBlocking {
        for (reason in listOf(cc.monomer.identity.api.IdentityAdmissionDenial.WORKSPACE_UNAVAILABLE,
            cc.monomer.identity.api.IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE)) {
            val admission = mockk<ControlAdmissionFacade>()
            coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Denied(
                cc.monomer.control.ControlAdmissionStage.IDENTITY, cc.monomer.control.ControlAdmissionDenial.Identity(reason))
            val authorizer = DashboardRequestAuthorizer(admission)
            val denied = kotlin.test.assertFailsWith<org.springframework.web.server.ResponseStatusException> {
                authorizer.requireWorkspace(authenticatedExchange("Bearer fixture"), workspace.toString())
            }
            assertEquals(404, denied.statusCode.value())
        }
    }

    @Test
    fun `credential denial remains unauthorized after principal authentication`() = runBlocking {
        for (reason in listOf(cc.monomer.identity.api.IdentityAdmissionDenial.CREDENTIAL_UNAVAILABLE,
            cc.monomer.identity.api.IdentityAdmissionDenial.CREDENTIAL_EXPIRED,
            cc.monomer.identity.api.IdentityAdmissionDenial.CREDENTIAL_REVOKED)) {
            val admission = mockk<ControlAdmissionFacade>()
            coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Denied(
                cc.monomer.control.ControlAdmissionStage.IDENTITY, cc.monomer.control.ControlAdmissionDenial.Identity(reason))
            val denied = kotlin.test.assertFailsWith<org.springframework.web.server.ResponseStatusException> {
                DashboardRequestAuthorizer(admission).requireWorkspace(authenticatedExchange("Bearer stale"), workspace.toString())
            }
            assertEquals(401, denied.statusCode.value())
        }
    }

    private fun authenticatedExchange(authorization: String): org.springframework.web.server.ServerWebExchange {
        val authentication = JwtAuthentication(
            UserCredentialProvenance.BrowserSession(userId, sessionId),
            PresentedCredential(authorization.removePrefix("Bearer ")),
        )
        return MockServerWebExchange.from(
            MockServerHttpRequest.get("/")
                .header("Authorization", authorization)
                .build(),
        ).mutate().principal(Mono.just(authentication)).build()
    }

}
