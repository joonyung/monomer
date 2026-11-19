package cc.monomer.control.human.auth

import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionDenial
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.ControlAdmissionStage
import cc.monomer.identity.api.OrganizationId
import cc.monomer.identity.api.AuthorizationRevision
import cc.monomer.identity.api.CanonicalPrincipal
import cc.monomer.identity.api.CredentialKind
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.IdentityAdmissionDenial
import cc.monomer.identity.api.IdentityRoleEvidence
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.PrincipalKind
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.control.connector.application.ConnectorNotFoundException
import cc.monomer.control.connector.application.ConnectorReadUseCase
import cc.monomer.control.auth.inbound.oauth.JwtAuthentication
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.model.UserCredentialProvenance
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import kotlin.test.assertEquals

class ConnectorCatalogRequestAccessTest {
    private val workspaceId = "019e0000-0000-7000-8000-0000000000a1"
    private val otherWorkspaceId = "019e0000-0000-7000-8000-0000000000a2"
    private val userId = "019e0000-0000-7000-8000-0000000000c1"

    @Test
    fun `workspace access requires an authenticated credential`() = runBlocking {
        val connectorReadUseCase: ConnectorReadUseCase = mockk()
        val admissionFacade: ControlAdmissionFacade = mockk()
        val access = ConnectorCatalogRequestAccess(connectorReadUseCase, admissionFacade)

        val failure = requireRequestProblem {
            access.requireWorkspace(unauthenticatedExchange(), workspaceId)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, failure.status)
        assertEquals("AUTHENTICATION_REQUIRED", failure.code)
        coVerify(exactly = 0) { admissionFacade.authorizeWorkspaceRole(any()) }
    }

    @Test
    fun `workspace access rejects a malformed bearer credential`() = runBlocking {
        val connectorReadUseCase: ConnectorReadUseCase = mockk()
        val admissionFacade: ControlAdmissionFacade = mockk()
        val access = ConnectorCatalogRequestAccess(connectorReadUseCase, admissionFacade)

        val failure = requireRequestProblem {
            access.requireWorkspace(authenticatedExchange("Basic rejected"), workspaceId)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, failure.status)
        assertEquals("AUTHENTICATION_REQUIRED", failure.code)
        coVerify(exactly = 0) { admissionFacade.authorizeWorkspaceRole(any()) }
    }

    @Test
    fun `workspace access rejects noncanonical workspace scope before authorization`() = runBlocking {
        val connectorReadUseCase: ConnectorReadUseCase = mockk()
        val admissionFacade: ControlAdmissionFacade = mockk()
        val access = ConnectorCatalogRequestAccess(connectorReadUseCase, admissionFacade)

        val failure = requireResponseFailure {
            access.requireWorkspace(authenticatedExchange("Bearer token"), workspaceId.uppercase())
        }

        assertEquals(HttpStatus.BAD_REQUEST, failure.statusCode)
        coVerify(exactly = 0) { admissionFacade.authorizeWorkspaceRole(any()) }
    }

    @Test
    fun `admission denial preserves authentication membership and role distinctions before connector lookup`() = runBlocking {
        val credentialDenials = listOf(
            IdentityAdmissionDenial.CREDENTIAL_UNAVAILABLE,
            IdentityAdmissionDenial.CREDENTIAL_EXPIRED,
            IdentityAdmissionDenial.CREDENTIAL_REVOKED,
            IdentityAdmissionDenial.PRINCIPAL_MISMATCH,
            IdentityAdmissionDenial.AGENT_BINDING_MISMATCH,
            IdentityAdmissionDenial.AGENT_SCOPE_INSUFFICIENT,
        )
        val membershipDenials = listOf(
            IdentityAdmissionDenial.WORKSPACE_UNAVAILABLE,
            IdentityAdmissionDenial.ORGANIZATION_MEMBERSHIP_UNAVAILABLE,
            IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE,
        )
        val expectations = credentialDenials.map {
            Triple(ControlAdmissionDenial.Identity(it), HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED")
        } + membershipDenials.map {
            Triple(ControlAdmissionDenial.Identity(it), HttpStatus.NOT_FOUND, "NOT_FOUND")
        } + listOf(
            Triple(ControlAdmissionDenial.Identity(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE), HttpStatus.FORBIDDEN, "ROLE_REQUIRED"),
            Triple(ControlAdmissionDenial.INVALID_EXECUTION_TARGET, HttpStatus.FORBIDDEN, "FORBIDDEN"),
            Triple(ControlAdmissionDenial.IDENTITY_EVIDENCE_MISMATCH, HttpStatus.FORBIDDEN, "FORBIDDEN"),
        )

        for ((reason, status, code) in expectations) {
            val connectorReadUseCase: ConnectorReadUseCase = mockk()
            val admissionFacade: ControlAdmissionFacade = mockk()
            val access = ConnectorCatalogRequestAccess(connectorReadUseCase, admissionFacade)
            coEvery { admissionFacade.authorizeWorkspaceRole(any()) } returns
                ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY, reason)

            val workspaceFailure = requireRequestProblem {
                access.requireWorkspace(authenticatedExchange("Bearer token"), workspaceId)
            }
            val connectorFailure = requireRequestProblem {
                access.requireConnector(authenticatedExchange("Bearer token"), workspaceId, "019e0000-0000-7000-8000-0000000000b1")
            }

            assertEquals(status, workspaceFailure.status, reason.toString())
            assertEquals(code, workspaceFailure.code, reason.toString())
            assertEquals(status, connectorFailure.status, reason.toString())
            assertEquals(code, connectorFailure.code, reason.toString())
            coVerify(exactly = 2) { admissionFacade.authorizeWorkspaceRole(any()) }
            coVerify(exactly = 0) { connectorReadUseCase.get(any(), any()) }
        }
    }

    @Test
    fun `workspace access passes canonical scope and authenticated bearer token`() = runBlocking {
        val connectorReadUseCase: ConnectorReadUseCase = mockk()
        val admissionFacade: ControlAdmissionFacade = mockk()
        val access = ConnectorCatalogRequestAccess(connectorReadUseCase, admissionFacade)
        coEvery { admissionFacade.authorizeWorkspaceRole(any()) } returns admittedMembership()

        val granted = access.requireWorkspace(authenticatedExchange("Bearer token"), workspaceId)

        assertEquals(workspaceId, granted.workspaceId)
        coVerify(exactly = 1) { admissionFacade.authorizeWorkspaceRole(any()) }
    }

    @Test
    fun `connector access resolves the connector inside expected workspace`() = runBlocking {
        val connectorReadUseCase: ConnectorReadUseCase = mockk()
        val admissionFacade: ControlAdmissionFacade = mockk()
        val connectorId = "019e0000-0000-7000-8000-0000000000b1"
        val access = ConnectorCatalogRequestAccess(connectorReadUseCase, admissionFacade)
        val exchange = authenticatedExchange("Bearer token")
        coEvery { admissionFacade.authorizeWorkspaceRole(any()) } returns admittedMembership()
        coEvery { connectorReadUseCase.get(workspaceId, connectorId) } throws
            ConnectorNotFoundException("connector not found")

        val failure = try {
            access.requireConnector(exchange, workspaceId, connectorId)
            error("a connector outside the expected workspace must not resolve")
        } catch (error: ConnectorCatalogRequestProblem) {
            error
        }

        assertEquals(HttpStatus.NOT_FOUND, failure.status)
        assertEquals("NOT_FOUND", failure.code)
        coVerify(exactly = 1) { admissionFacade.authorizeWorkspaceRole(any()) }
        coVerify(exactly = 1) { connectorReadUseCase.get(workspaceId, connectorId) }
    }

    @Test
    fun `connector access carries each requested workspace through membership and owner lookup`() = runBlocking {
        val connectorReadUseCase: ConnectorReadUseCase = mockk()
        val admissionFacade: ControlAdmissionFacade = mockk()
        val connectorId = "019e0000-0000-7000-8000-0000000000b1"
        val access = ConnectorCatalogRequestAccess(connectorReadUseCase, admissionFacade)
        coEvery { admissionFacade.authorizeWorkspaceRole(any()) } returns admittedMembership()
        coEvery { connectorReadUseCase.get(any(), connectorId) } throws ConnectorNotFoundException("connector not found")

        val workspaceAFailure = try {
            access.requireConnector(authenticatedExchange("Bearer token"), workspaceId, connectorId)
            error("workspace A connector must not resolve")
        } catch (error: ConnectorCatalogRequestProblem) {
            error
        }
        val workspaceBFailure = try {
            access.requireConnector(authenticatedExchange("Bearer token"), otherWorkspaceId, connectorId)
            error("workspace B connector must not resolve")
        } catch (error: ConnectorCatalogRequestProblem) {
            error
        }

        assertEquals(HttpStatus.NOT_FOUND, workspaceAFailure.status)
        assertEquals(HttpStatus.NOT_FOUND, workspaceBFailure.status)
        coVerify(exactly = 1) {
            admissionFacade.authorizeWorkspaceRole(match { request -> request.workspaceId.toString() == workspaceId })
        }
        coVerify(exactly = 1) {
            admissionFacade.authorizeWorkspaceRole(match { request -> request.workspaceId.toString() == otherWorkspaceId })
        }
        coVerify(exactly = 1) { connectorReadUseCase.get(workspaceId, connectorId) }
        coVerify(exactly = 1) { connectorReadUseCase.get(otherWorkspaceId, connectorId) }
    }

    private fun admittedMembership(): ControlAdmissionDecision<IdentityRoleEvidence> =
        ControlAdmissionDecision.Admitted(
            IdentityRoleEvidence(
                actor = CanonicalPrincipal(PrincipalId(kotlin.uuid.Uuid.parse(userId)), PrincipalKind.USER),
                organizationId = OrganizationId(kotlin.uuid.Uuid.parse("019e0000-0000-7000-8000-0000000000d1")),
                workspaceId = cc.monomer.identity.api.WorkspaceId(kotlin.uuid.Uuid.parse(workspaceId)),
                authorizationRevision = AuthorizationRevision(1),
                organizationPlan = cc.monomer.identity.api.OrganizationPlan.FREE,
                role = cc.monomer.identity.api.WorkspaceRole.EXPLORER,
                credentialKind = CredentialKind.SESSION,
                credentialSurface = CredentialSurface.WEB,
                agentClientId = null,
            ),
        )

    private fun unauthenticatedExchange(): ServerWebExchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/").build())

    private fun authenticatedExchange(authorization: String): ServerWebExchange {
        val authentication = JwtAuthentication(
            UserCredentialProvenance.BrowserSession(
                userId = UserId(kotlin.uuid.Uuid.parse(userId)),
                sessionId = SessionId(kotlin.uuid.Uuid.parse("019e0000-0000-7000-8000-0000000000e1")),
            ),
            PresentedCredential(authorization.removePrefix("Bearer ")),
        )
        return MockServerWebExchange.from(
            MockServerHttpRequest.get("/").header("Authorization", authorization).build(),
        ).mutate().principal(Mono.just(authentication)).build()
    }

    private suspend fun requireResponseFailure(block: suspend () -> Unit): ResponseStatusException =
        try {
            block()
            error("request must fail")
        } catch (error: ResponseStatusException) {
            error
        }

    private suspend fun requireRequestProblem(block: suspend () -> Unit): ConnectorCatalogRequestProblem =
        try {
            block()
            error("request must fail")
        } catch (error: ConnectorCatalogRequestProblem) {
            error
        }
}
