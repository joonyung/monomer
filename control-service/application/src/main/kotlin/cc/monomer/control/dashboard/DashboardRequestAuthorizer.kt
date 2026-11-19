package cc.monomer.control.dashboard

import cc.monomer.control.human.auth.toHumanHttpException

import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.WorkspaceRoleAdmissionRequest
import cc.monomer.control.auth.inbound.BearerAuthorization
import cc.monomer.control.auth.inbound.BearerCredentials
import cc.monomer.control.auth.inbound.oauth.JwtAuthentication
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import kotlin.uuid.Uuid
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

data class AuthorizedDashboardRequest(
    val workspaceId: WorkspaceId,
    val organizationId: cc.monomer.identity.api.OrganizationId,
    val actorId: String,
    val credential: PresentedCredential,
    val credentialSurface: cc.monomer.identity.api.CredentialSurface,
    val agentClientId: cc.monomer.identity.api.AgentClientId?,
)

class DashboardRequestAuthorizer(
    private val controlAdmissionFacade: ControlAdmissionFacade,
) {
    suspend fun requireWorkspace(
        exchange: ServerWebExchange,
        rawWorkspaceId: String,
    ): AuthorizedDashboardRequest {
        exchange.getPrincipal<JwtAuthentication>().awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.")
        val workspaceId = runCatching { Uuid.parse(rawWorkspaceId) }
            .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "workspaceId must be a UUID") }
        val credential = presentedCredential(exchange)
        val membership = when (
            val decision = controlAdmissionFacade.authorizeWorkspaceRole(
                WorkspaceRoleAdmissionRequest(
                    workspaceId = WorkspaceId(workspaceId),
                    credential = credential,
                    expectedOAuthResource = null,
                    requiredRole = WorkspaceRole.EXPLORER,
                ),
            )
        ) {
            is ControlAdmissionDecision.Admitted -> decision.context
            is ControlAdmissionDecision.Denied ->
                throw decision.toHumanHttpException()
        }
        return AuthorizedDashboardRequest(WorkspaceId(workspaceId), membership.organizationId, membership.actor.id.toString(), credential, membership.credentialSurface, membership.agentClientId)
    }

    private fun presentedCredential(exchange: ServerWebExchange): PresentedCredential =
        when (val bearer = BearerCredentials.fromFirstAuthorizationHeader(exchange.request.headers)) {
            is BearerAuthorization.Presented -> bearer.credential
            BearerAuthorization.Missing, BearerAuthorization.Malformed ->
                throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.")
        }
}
