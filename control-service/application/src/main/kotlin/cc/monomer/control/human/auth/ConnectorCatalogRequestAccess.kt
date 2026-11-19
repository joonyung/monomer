package cc.monomer.control.human.auth

import cc.monomer.control.auth.inbound.BearerAuthorization
import cc.monomer.control.auth.inbound.BearerCredentials
import cc.monomer.control.auth.inbound.oauth.JwtAuthentication
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionDenial
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.WorkspaceRoleAdmissionRequest
import cc.monomer.control.connector.application.ConnectorNotFoundException
import cc.monomer.control.connector.application.ConnectorReadUseCase
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.IdentityAdmissionDenial
import kotlin.uuid.Uuid
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

@Component
class ConnectorCatalogRequestAccess(
    private val connectorReadUseCase: ConnectorReadUseCase,
    private val controlAdmissionFacade: ControlAdmissionFacade,
) {
    suspend fun requireWorkspace(
        exchange: ServerWebExchange,
        rawWorkspaceId: String,
    ): ConnectorCatalogAccess {
        val workspaceId = parseCanonicalUuid(rawWorkspaceId, "workspace id")
        return requireAccess(exchange, workspaceId)
    }

    suspend fun requireConnector(
        exchange: ServerWebExchange,
        rawWorkspaceId: String,
        rawConnectorId: String,
    ): ConnectorCatalogAccess {
        val workspaceId = parseCanonicalUuid(rawWorkspaceId, "workspace id")
        val connectorId = parseCanonicalUuid(rawConnectorId, "connector id")
        val access = requireAccess(exchange, workspaceId)
        try {
            connectorReadUseCase.get(workspaceId.toString(), connectorId.toString())
        } catch (_: ConnectorNotFoundException) {
            throw connectorNotFound()
        }
        return access
    }

    private suspend fun requireAccess(
        exchange: ServerWebExchange,
        workspaceId: Uuid,
    ): ConnectorCatalogAccess {
        exchange.getPrincipal<JwtAuthentication>().awaitSingleOrNull()
            ?: throw ConnectorCatalogRequestProblem(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required.")
        val credential = requireBearerCredential(exchange)
        when (
            val decision = controlAdmissionFacade.authorizeWorkspaceRole(
                WorkspaceRoleAdmissionRequest(
                    workspaceId = cc.monomer.identity.api.WorkspaceId(workspaceId),
                    credential = credential,
                    expectedOAuthResource = null,
                    requiredRole = cc.monomer.identity.api.WorkspaceRole.EXPLORER,
                ),
            )
        ) {
            is ControlAdmissionDecision.Admitted -> Unit
            is ControlAdmissionDecision.Denied -> throw decision.toConnectorCatalogProblem()
        }
        return ConnectorCatalogAccess(workspaceId.toString())
    }

    private fun requireBearerCredential(exchange: ServerWebExchange): PresentedCredential =
        when (val bearer = BearerCredentials.fromFirstAuthorizationHeader(exchange.request.headers)) {
            is BearerAuthorization.Presented -> bearer.credential
            BearerAuthorization.Missing, BearerAuthorization.Malformed ->
                throw ConnectorCatalogRequestProblem(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required.")
        }

    private fun parseCanonicalUuid(rawValue: String, fieldName: String): Uuid {
        val parsed = runCatching { Uuid.parse(rawValue) }.getOrElse {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid $fieldName: $rawValue")
        }
        if (parsed.toString() != rawValue) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid $fieldName: $rawValue")
        }
        return parsed
    }

    private fun connectorNotFound(): ConnectorCatalogRequestProblem =
        ConnectorCatalogRequestProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Connector resource was not found.")
}

data class ConnectorCatalogAccess(val workspaceId: String) {
    init {
        require(workspaceId.isNotBlank())
    }
}

class ConnectorCatalogRequestProblem(
    val status: HttpStatus,
    val code: String,
    val detail: String,
) : RuntimeException(detail)

fun ControlAdmissionDecision.Denied.toConnectorCatalogProblem(): ConnectorCatalogRequestProblem =
    when (val denial = reason) {
        is ControlAdmissionDenial.Identity -> when (denial.reason) {
            IdentityAdmissionDenial.CREDENTIAL_UNAVAILABLE,
            IdentityAdmissionDenial.CREDENTIAL_EXPIRED,
            IdentityAdmissionDenial.CREDENTIAL_REVOKED,
            IdentityAdmissionDenial.PRINCIPAL_MISMATCH,
            IdentityAdmissionDenial.AGENT_BINDING_MISMATCH,
            IdentityAdmissionDenial.AGENT_SCOPE_INSUFFICIENT,
            -> ConnectorCatalogRequestProblem(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required.")
            IdentityAdmissionDenial.WORKSPACE_UNAVAILABLE,
            IdentityAdmissionDenial.ORGANIZATION_MEMBERSHIP_UNAVAILABLE,
            IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE,
            -> ConnectorCatalogRequestProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace resource was not found.")
            IdentityAdmissionDenial.PERMISSION_UNAVAILABLE ->
                ConnectorCatalogRequestProblem(HttpStatus.FORBIDDEN, "ROLE_REQUIRED", "The required Workspace role is unavailable.")
        }
        ControlAdmissionDenial.INVALID_EXECUTION_TARGET,
        ControlAdmissionDenial.IDENTITY_EVIDENCE_MISMATCH,
        -> ConnectorCatalogRequestProblem(HttpStatus.FORBIDDEN, "FORBIDDEN", "The Catalog request is forbidden.")
    }
